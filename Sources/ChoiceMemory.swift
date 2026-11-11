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
    static var storageDirectory: URL {
        #if os(iOS)
        // An iOS keyboard without Full Access can read the shared container
        // but cannot write to it, so what it learns stays in its own sandbox.
        return Preferences.directory
        #else
        if !Preferences.testing,
           let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.krysta1ll.meowpuut.shared") {
            return group
        }
        return Preferences.directory
        #endif
    }
    #if os(iOS)
    // 0.5.0 kept the records in the shared container. They are read from
    // there once, when the keyboard's own files do not exist yet.
    private static let earlierSharedDirectory: URL? = Preferences.testing ? nil
        : FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.krysta1ll.meowpuut.shared")
    #else
    private static let earlierSharedDirectory: URL? = nil
    #endif
    private let file = ChoiceMemory.storageDirectory.appendingPathComponent("mimo_user.db")
    private let legacyFile = Preferences.directory.appendingPathComponent("ChoiceMemory.json")
    private let walFile = ChoiceMemory.storageDirectory.appendingPathComponent("mimo_user_wal.log")
    // Bound local history: eight alternatives per reading, 5000 distinct readings.
    private let readingLimit = 5000
    private let choiceLimit = 8
    private var uncompactedWalCount = 0

    init() { reload() }

    private static func isValidSelection(reading: String, text: String) -> Bool {
        guard !reading.isEmpty, reading.count <= 256, !text.isEmpty, text.count <= 100,
              !reading.contains("\n"), !reading.contains("\t"), !text.contains("\n"), !text.contains("\t") else { return false }
        return reading.hasPrefix("ja:") || reading.hasPrefix("py:") || reading.hasPrefix("t:") || reading.hasPrefix("s:")
            || text.unicodeScalars.contains(where: { (0x3400...0x9fff).contains($0.value) || (0x20000...0x323af).contains($0.value) })
    }

    @discardableResult
    private func applySelection(reading: String, text: String, timestamp: TimeInterval) -> Bool {
        guard Self.isValidSelection(reading: reading, text: text) else { return false }
        var choices = records[reading] ?? []
        if let index = choices.firstIndex(where: { $0.text == text }) {
            choices[index].uses = min(choices[index].uses + 1, 1_000_000)
            choices[index].lastUsed = max(choices[index].lastUsed, timestamp)
        } else {
            choices.append(RememberedChoice(text: text, uses: 1, lastUsed: timestamp))
        }
        records[reading] = Array(choices.sorted(by: Self.rank).prefix(choiceLimit))
        return true
    }

    private func trimReadingLimit() {
        while records.count > readingLimit {
            let oldest = records.min { ($0.value.map(\.lastUsed).max() ?? 0) < ($1.value.map(\.lastUsed).max() ?? 0) }?.key
            if let oldest { records.removeValue(forKey: oldest) }
        }
    }

    func reload() {
        var loaded: [String: [RememberedChoice]] = [:]
        var loadFailed = false
        var baseTarget = FileManager.default.fileExists(atPath: file.path) ? file : (FileManager.default.fileExists(atPath: legacyFile.path) ? legacyFile : nil)
        var logs = [walFile]
        var importing = false
        if baseTarget == nil, !FileManager.default.fileExists(atPath: walFile.path), let shared = Self.earlierSharedDirectory {
            let earlier = shared.appendingPathComponent("mimo_user.db")
            if FileManager.default.fileExists(atPath: earlier.path) { baseTarget = earlier }
            logs.append(shared.appendingPathComponent("mimo_user_wal.log"))
            importing = true
        }
        if let baseTarget {
            do {
                loaded = try JSONDecoder().decode([String: [RememberedChoice]].self, from: Data(contentsOf: baseTarget))
            } catch {
                // An unreadable earlier file is skipped; it must not stop new records.
                loadFailed = !importing
            }
        }
        records = loaded
        var replayed = 0
        for log in logs {
            guard let walText = try? String(contentsOf: log, encoding: .utf8) else { continue }
            for line in walText.split(separator: "\n") {
                let parts = line.split(separator: "\t", omittingEmptySubsequences: false)
                if parts.count == 4, parts[0] == "W", let ts = TimeInterval(parts[1]) {
                    if applySelection(reading: String(parts[2]), text: String(parts[3]), timestamp: ts) {
                        replayed += 1
                    }
                }
            }
        }
        if replayed > 0 { trimReadingLimit() }
        uncompactedWalCount = replayed
        revision &+= 1
        error = loadFailed ? "選詞記錄讀取失敗，已保留原檔案。" : ""
        // Imported records move into this process's own files right away.
        if importing, !records.isEmpty { save() }
    }

    func remember(_ selections: [(reading: String, text: String)]) {
        guard Preferences.shared.rememberChoices, error.isEmpty, !selections.isEmpty else { return }
        let now = Date().timeIntervalSince1970
        var walLines = ""
        for (reading, text) in selections {
            if applySelection(reading: reading, text: text, timestamp: now) {
                walLines += "W\t\(now)\t\(reading)\t\(text)\n"
                uncompactedWalCount += 1
            }
        }
        guard !walLines.isEmpty else { return }
        trimReadingLimit()
        revision &+= 1
        // Every 64 records the log is folded into the main file, and the
        // count starts again. Without the reset every later choice would
        // rewrite the whole file.
        let compact = uncompactedWalCount >= 64
        if compact { uncompactedWalCount = 0 }
        appendWal(walLines, shouldCompact: compact)
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
    func contextualPredictions(after context: String, limit: Int = 4) -> [String] {
        guard Preferences.shared.rememberChoices, !context.isEmpty, limit > 0 else { return [] }
        var matched: [RememberedChoice] = []
        for len in [4, 3, 2, 1] where context.count >= len {
            let prefix = "ctx|" + String(context.suffix(len)) + "|"
            for (key, choices) in records where key.hasPrefix(prefix) {
                matched.append(contentsOf: choices.filter { $0.uses >= 2 })
            }
            if !matched.isEmpty { break }
        }
        var seen = Set<String>()
        return matched.sorted(by: Self.rank).compactMap { choice in
            seen.insert(choice.text).inserted ? choice.text : nil
        }.prefix(limit).map { $0 }
    }
    func forget(_ reading: String) { if records.removeValue(forKey: reading) != nil { revision &+= 1; save() } }
    func clear() { records = [:]; uncompactedWalCount = 0; revision &+= 1; error = ""; save() }

    private let writer = DispatchQueue(label: "app.meowpuut.memory", qos: .utility)
    private var pendingSave: DispatchWorkItem?

    private func appendWal(_ lines: String, shouldCompact: Bool) {
        let snapshot = shouldCompact ? records : nil
        writer.async { [weak self] in
            guard let self else { return }
            do {
                try FileManager.default.createDirectory(at: self.walFile.deletingLastPathComponent(), withIntermediateDirectories: true)
                let data = Data(lines.utf8)
                if FileManager.default.fileExists(atPath: self.walFile.path),
                   let handle = try? FileHandle(forWritingTo: self.walFile) {
                    defer { try? handle.close() }
                    try handle.seekToEnd()
                    try handle.write(contentsOf: data)
                } else {
                    try data.write(to: self.walFile, options: .atomic)
                }
                if let snapshot { _ = self.persist(snapshot) }
            } catch {
                DispatchQueue.main.async { self.error = "選詞記錄未能保存。" }
            }
        }
    }

    @discardableResult private func persist(_ snapshot: [String: [RememberedChoice]]) -> Bool {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            let encoded = try JSONEncoder().encode(snapshot)
            try encoded.write(to: file, options: .atomic)
            #if !os(iOS)
            // The older file name is still written on the Mac. The iOS
            // keyboard reads mimo_user.db and skips the second write.
            if legacyFile != file {
                try? FileManager.default.createDirectory(at: legacyFile.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? encoded.write(to: legacyFile, options: .atomic)
            }
            #endif
            try? FileManager.default.removeItem(at: walFile)
            return true
        } catch {
            DispatchQueue.main.async { self.error = "選詞記錄未能保存。" }
            return false
        }
    }
    private func save() {
        pendingSave?.cancel()
        uncompactedWalCount = 0
        let snapshot = records
        let work = DispatchWorkItem { [weak self] in _ = self?.persist(snapshot) }
        pendingSave = work
        writer.asyncAfter(deadline: .now() + 0.25, execute: work)
    }
    @discardableResult func flush() -> Bool {
        pendingSave?.cancel()
        uncompactedWalCount = 0
        let snapshot = records
        return writer.sync { persist(snapshot) }
    }
}
