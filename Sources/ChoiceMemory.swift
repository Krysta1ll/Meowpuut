import Foundation
import Combine

struct RememberedChoice: Codable {
    var text: String
    var uses: Int
    var lastUsed: TimeInterval
}

final class ChoiceMemory: ObservableObject {
    static let shared = ChoiceMemory()
    @Published private(set) var records: [String: [RememberedChoice]] = [:]
    @Published private(set) var error = ""
    // Allows the active composition to reflect an explicit preference change
    // or a clear operation without storing or copying any user text elsewhere.
    private(set) var revision: UInt64 = 0
    private let file = Preferences.directory.appendingPathComponent("ChoiceMemory.json")
    // Bound local history: eight alternatives per reading, 500 distinct readings.
    private let readingLimit = 5000
    private let choiceLimit = 8
    init() { reload() }
    func reload() {
        guard FileManager.default.fileExists(atPath: file.path) else { records = [:]; revision &+= 1; return }
        do { records = try JSONDecoder().decode([String: [RememberedChoice]].self, from: Data(contentsOf: file)); revision &+= 1; error = "" }
        catch { self.error = "选词记录读取失败，已保留原文件。" }
    }
    func remember(_ selections: [(reading: String, text: String)]) {
        guard Preferences.shared.rememberChoices, error.isEmpty, !selections.isEmpty else { return }
        var changed = false
        for (reading, text) in selections {
        guard !reading.isEmpty, reading.count <= 256, text.count <= 100,
              (reading.hasPrefix("ja:") || reading.hasPrefix("py:") || reading.hasPrefix("t:") || reading.hasPrefix("s:") || text.unicodeScalars.contains(where: { (0x3400...0x9fff).contains($0.value) || (0x20000...0x323af).contains($0.value) })) else { continue }
        var choices = records[reading] ?? []
        if let index = choices.firstIndex(where: { $0.text == text }) {
            choices[index].uses = min(choices[index].uses + 1, 1_000_000)
            choices[index].lastUsed = Date().timeIntervalSince1970
        } else { choices.append(RememberedChoice(text: text, uses: 1, lastUsed: Date().timeIntervalSince1970)) }
        records[reading] = Array(choices.sorted(by: Self.rank).prefix(choiceLimit))
        changed = true
        }
        guard changed else { return }
        while records.count > readingLimit {
            let oldest = records.min { ($0.value.map(\.lastUsed).max() ?? 0) < ($1.value.map(\.lastUsed).max() ?? 0) }?.key
            if let oldest { records.removeValue(forKey: oldest) }
        }
        revision &+= 1
        save()
    }
    private static func rank(_ left: RememberedChoice, _ right: RememberedChoice) -> Bool {
        left.uses == right.uses ? left.lastUsed > right.lastUsed : left.uses > right.uses
    }
    func preferred(for reading: String, context: String? = nil) -> [String] {
        guard Preferences.shared.rememberChoices else { return [] }
        let contextual = context.map { records["ctx|" + $0 + "|" + reading] ?? [] } ?? []
        return contextual.filter { $0.uses >= 2 }.sorted(by: Self.rank).map(\.text) + (records[reading] ?? []).sorted(by: Self.rank).map(\.text)
    }
    func boost(for reading: String, text: String, context: String?) -> Int {
        guard Preferences.shared.rememberChoices else { return 0 }
        let uses = records[reading]?.first(where: { $0.text == text })?.uses ?? 0
        let contextual = context.flatMap { records["ctx|" + $0 + "|" + reading]?.first(where: { $0.text == text })?.uses } ?? 0
        return min(12, uses) * 700 + (contextual >= 2 ? min(4, contextual) * 1200 : 0)
    }
    func personalCandidates(for reading: String) -> [String] {
        guard Preferences.shared.rememberChoices else { return [] }
        return (records[reading] ?? []).filter { $0.uses >= 2 && $0.text.count <= 8 && $0.text.unicodeScalars.allSatisfy { (0x3400...0x9fff).contains($0.value) } }.sorted(by: Self.rank).map(\.text)
    }
    func forget(_ reading: String) { if records.removeValue(forKey: reading) != nil { revision &+= 1; save() } }
    func clear() { records = [:]; revision &+= 1; error = ""; save() }
    private let writer = DispatchQueue(label: "app.meowpuut.memory", qos: .utility)
    private var pendingSave: DispatchWorkItem?
    @discardableResult private func persist(_ snapshot: [String: [RememberedChoice]]) -> Bool {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(snapshot).write(to: file, options: .atomic)
            return true
        } catch {
            DispatchQueue.main.async { self.error = "選詞記錄未能保存。" }
            return false
        }
    }
    private func save() {
        pendingSave?.cancel()
        let snapshot = records
        let work = DispatchWorkItem { [weak self] in _ = self?.persist(snapshot) }
        pendingSave = work
        writer.asyncAfter(deadline: .now() + 0.25, execute: work)
    }
    @discardableResult func flush() -> Bool {
        pendingSave?.cancel()
        let snapshot = records
        return writer.sync { persist(snapshot) }
    }
}
