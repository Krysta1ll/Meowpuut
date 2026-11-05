import AppKit

struct Snapshot: Decodable {
    var preedit = ""
    var commit = ""
    var candidates: [String] = []
    var highlighted = 0
    var page = 0
    var lastPage = true
    var input = ""
    var pageSize = 7
}
struct IndexedCandidate: Decodable { var index: Int; var text: String }

// Data modules are local, versioned JSON: no engine internals leak into extensions.
struct PhraseModule: Codable, Identifiable {
    var id: String
    var apiVersion: Int
    var name: String
    var trigger: String
    var phrases: [String]
}

final class Preferences: ObservableObject {
    static let shared = Preferences()
    private let defaults = CommandLine.arguments.contains("--smoke") ? UserDefaults(suiteName: "app.meowpuut.smoke")! : UserDefaults.standard
    @Published var simplified: Bool { didSet { defaults.set(simplified, forKey: "simplified") } }
    @Published var modulesEnabled: Bool { didSet { defaults.set(modulesEnabled, forKey: "modulesEnabled") } }
    @Published var modules: [PhraseModule] = []
    @Published var moduleError = ""
    @Published var pins: [String: String] { didSet { defaults.set(pins, forKey: "candidatePins") } }
    static let directory = CommandLine.arguments.contains("--smoke") ? FileManager.default.temporaryDirectory.appendingPathComponent("Meowpuut-Smoke-\(ProcessInfo.processInfo.processIdentifier)") : FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Meowpuut")
    var moduleDirectory: URL { Self.directory.appendingPathComponent("Modules") }
    private init() {
        simplified = defaults.bool(forKey: "simplified")
        modulesEnabled = defaults.object(forKey: "modulesEnabled") as? Bool ?? true
        pins = defaults.dictionary(forKey: "candidatePins") as? [String: String] ?? [:]
        reload()
    }
    func reload() {
        do {
            try FileManager.default.createDirectory(at: moduleDirectory, withIntermediateDirectories: true)
            let example = moduleDirectory.appendingPathComponent("hello.json")
            if !FileManager.default.fileExists(atPath: example.path), let bundled = Bundle.main.url(forResource: "hello", withExtension: "json", subdirectory: "Modules") {
                try FileManager.default.copyItem(at: bundled, to: example)
            }
            modules = []
            moduleError = ""
            for file in try FileManager.default.contentsOfDirectory(at: moduleDirectory, includingPropertiesForKeys: nil).filter({ $0.pathExtension == "json" }).sorted(by: { $0.path < $1.path }) {
                do {
                    let data = try Data(contentsOf: file)
                    guard data.count <= 1_048_576 else { throw CocoaError(.fileReadTooLarge) }
                    let module = try JSONDecoder().decode(PhraseModule.self, from: data)
                    guard module.apiVersion == 1, !module.id.isEmpty, !module.name.isEmpty, module.trigger.hasPrefix("/"), module.trigger.count > 1, module.phrases.count <= 100, !module.phrases.isEmpty, module.phrases.allSatisfy({ !$0.isEmpty && $0.count <= 4096 }) else { throw CocoaError(.fileReadCorruptFile) }
                    guard !modules.contains(where: { $0.id == module.id }) else { throw CocoaError(.fileReadCorruptFile) }
                    modules.append(module)
                } catch { moduleError += "\(file.lastPathComponent)：\(error.localizedDescription)\n" }
            }
        } catch { moduleError = error.localizedDescription }
    }
}

final class Engine: ObservableObject {
    static var initialized = false
    let session: UInt
    @Published var snapshot = Snapshot()
    @Published var english = false
    @Published var error = ""
    private var moduleBuffer: String?
    private var candidateOrder: [Int] = []
    @Published var browsing = false
    @Published var phrasesOnly = false
    @Published var browseCount = 0
    private var pool: [IndexedCandidate] = []
    // Bound optional exploration; ordinary keystrokes still request only the engine page.
    private let explorationLimit = 256
    private func candidates() -> [IndexedCandidate] {
        (try? JSONDecoder().decode([IndexedCandidate].self, from: Data(String(cString: mp_candidates(session, Int32(explorationLimit))).utf8))) ?? []
    }
    func browse(phrases: Bool) {
        guard session != 0, moduleBuffer == nil, !snapshot.input.isEmpty else { return }
        if !browsing { pool = candidates() }
        browsing = true; phrasesOnly = phrases
        showBrowsePage(0)
    }
    private func showBrowsePage(_ requestedPage: Int) {
        let items = phrasesOnly ? pool.filter { $0.text.count > 1 } : pool
        browseCount = items.count
        let page = max(0, min(requestedPage, max(0, (items.count - 1) / 7)))
        let start = min(page * 7, items.count)
        let visible = Array(items.dropFirst(start).prefix(7))
        candidateOrder = visible.map(\.index)
        snapshot.candidates = visible.map(\.text)
        snapshot.page = page; snapshot.lastPage = start + 7 >= items.count; snapshot.highlighted = 0
    }
    func finishComposition() {
        if moduleBuffer != nil {
            let raw = moduleBuffer ?? ""; clear(); snapshot.commit = raw; return
        }
        if !snapshot.candidates.isEmpty { choose(snapshot.highlighted) }
        if snapshot.commit.isEmpty { mp_commit(session); refresh() }
    }
    var pinKey: String { (Preferences.shared.simplified ? "s:" : "t:") + snapshot.input }
    func isPinned(_ text: String) -> Bool { !snapshot.input.isEmpty && Preferences.shared.pins[pinKey] == text }
    func pin(_ index: Int) {
        guard !snapshot.input.isEmpty, snapshot.candidates.indices.contains(index) else { return }
        let text = snapshot.candidates[index]
        var pins = Preferences.shared.pins
        if pins[pinKey] == text { pins.removeValue(forKey: pinKey) } else { pins[pinKey] = text }
        Preferences.shared.pins = pins
        refresh()
    }
    init() {
        if !Self.initialized {
            let root = Preferences.directory
            do {
                try FileManager.default.createDirectory(at: root.appendingPathComponent("Rime"), withIntermediateDirectories: true)
                guard let data = Bundle.main.resourceURL?.appendingPathComponent("Rime"), FileManager.default.fileExists(atPath: data.appendingPathComponent("bopomofo.schema.yaml").path) else {
                    session = 0; error = "找不到注音词库，请重新构建应用。"; return
                }
                Self.initialized = data.path.withCString { shared in root.appendingPathComponent("Rime").path.withCString { mp_start(shared, $0) != 0 } }
            } catch { session = 0; self.error = error.localizedDescription; return }
        }
        session = mp_session()
        if session == 0 { error = "注音方案加载失败，请检查 Rime 构建日志。" }
    }
    deinit { if session != 0 { mp_destroy(session) } }
    func refresh() {
        guard session != 0 else { return }
        snapshot = (try? JSONDecoder().decode(Snapshot.self, from: Data(String(cString: mp_snapshot(session)).utf8))) ?? Snapshot()
        browsing = false; pool = []
        candidateOrder = snapshot.candidates.indices.map { snapshot.page * snapshot.pageSize + $0 }
        if snapshot.page == 0, !snapshot.input.isEmpty, let pin = Preferences.shared.pins[pinKey], let found = candidates().first(where: { $0.text == pin }) {
            if let existing = candidateOrder.firstIndex(of: found.index) { candidateOrder.remove(at: existing); snapshot.candidates.remove(at: existing) }
            candidateOrder.insert(found.index, at: 0); snapshot.candidates.insert(found.text, at: 0)
            candidateOrder = Array(candidateOrder.prefix(7)); snapshot.candidates = Array(snapshot.candidates.prefix(7))
            snapshot.highlighted = 0
        }
    }
    func takeCommit() -> String { let text = snapshot.commit; snapshot.commit = ""; return text }
    func clear() {
        moduleBuffer = nil
        if session != 0 { mp_clear(session) }
        snapshot = Snapshot()
        candidateOrder = []
        browsing = false; pool = []
    }
    func modeChanged() {
        guard session != 0 else { return }
        mp_mode(session, Preferences.shared.simplified ? 1 : 0)
        if moduleBuffer == nil { refresh() }
    }
    @discardableResult func key(_ code: Int32, modifiers: Int32 = 0) -> Bool {
        guard session != 0 else { return false }
        if code == 32 && modifiers == 1 {
            finishComposition()
            english.toggle()
            return true
        }
        if english {
            snapshot = Snapshot()
            if (32...126).contains(code), let scalar = UnicodeScalar(UInt32(code)) {
                snapshot.commit = String(scalar); return true
            }
            return false
        }
        if browsing && code == 0xff1b { refresh(); return true }
        // Recover a Latin word typed while in Chinese without changing the mode.
        if code == 0xff0d && modifiers == 1, moduleBuffer != nil || !snapshot.input.isEmpty {
            let literal = moduleBuffer ?? snapshot.input
            clear(); snapshot.commit = literal
            return true
        }
        if moduleBuffer == nil, !snapshot.candidates.isEmpty {
            if code == 0xff52 || code == 0xff54 || code == 0xff09 {
                let count = snapshot.candidates.count
                snapshot.highlighted = (snapshot.highlighted + (code == 0xff52 || (code == 0xff09 && modifiers == 1) ? count - 1 : 1)) % count
                return true
            }
            if code == 0xff0d {
                choose(snapshot.highlighted)
                if snapshot.commit.isEmpty { mp_commit(session); refresh() }
                return true
            }
            if modifiers == 1, let scalar = UnicodeScalar(UInt32(code)), let index = Array("!@#$%^&*(").firstIndex(of: Character(scalar)) { choose(index); return true }
        }
        if browsing, code == 0xff55 || code == 0xff56 { showBrowsePage(snapshot.page + (code == 0xff55 ? -1 : 1)); return true }
        mp_mode(session, Preferences.shared.simplified ? 1 : 0)
        if Preferences.shared.modulesEnabled, code == 47, snapshot.preedit.isEmpty, modifiers == 0 {
            moduleBuffer = "/"; moduleSnapshot(); return true
        }
        if var buffer = moduleBuffer {
            if code == 0xff55 || code == 0xff56 { moduleSnapshot(page: snapshot.page + (code == 0xff55 ? -1 : 1)); return true }
            if modifiers == 1, let scalar = UnicodeScalar(UInt32(code)), let index = Array("!@#$%^&*(").firstIndex(of: Character(scalar)) { choose(index); return true }
            if code == 0xff52 || code == 0xff54 || code == 0xff09 {
                let count = snapshot.candidates.count
                if count > 0 { snapshot.highlighted = (snapshot.highlighted + (code == 0xff52 || (code == 0xff09 && modifiers == 1) ? count - 1 : 1)) % count }
                return true
            }
            if code == 0xff1b { clear(); return true }
            if code == 0xff08 { buffer = String(buffer.dropLast()) }
            else if code == 0xff0d || code == 32 {
                if snapshot.candidates.isEmpty { finishComposition(); if code == 32 { snapshot.commit += " " } } else { choose(snapshot.highlighted) }
                return true
            }
            else if (33...126).contains(code), let scalar = UnicodeScalar(UInt32(code)) { buffer.append(Character(scalar)) }
            else { return true }
            moduleBuffer = buffer.isEmpty ? nil : buffer
            moduleSnapshot(); return true
        }
        let handled = mp_key(session, code, modifiers) != 0
        refresh()
        return handled
    }
    private func moduleSnapshot(page requestedPage: Int = 0) {
        guard let buffer = moduleBuffer else { snapshot = Snapshot(); return }
        let matches = Preferences.shared.modules.filter { buffer == $0.trigger || $0.trigger.hasPrefix(buffer) }
        let phrases = matches.flatMap(\.phrases)
        let page = max(0, min(requestedPage, max(0, (phrases.count - 1) / 7)))
        snapshot = Snapshot(preedit: buffer, candidates: Array(phrases.dropFirst(page * 7).prefix(7)), page: page, lastPage: (page + 1) * 7 >= phrases.count)
    }
    func choose(_ index: Int) {
        guard snapshot.candidates.indices.contains(index) else { return }
        if moduleBuffer != nil {
            let text = snapshot.candidates[index]; clear(); snapshot.commit = text
        } else {
            mp_choose(session, Int32(candidateOrder[index])); refresh()
            if snapshot.commit.isEmpty && snapshot.candidates.isEmpty && !snapshot.preedit.isEmpty {
                mp_commit(session); refresh()
            }
        }
    }
}

func rimeKey(_ event: NSEvent) -> (Int32, Int32)? {
    if !event.modifierFlags.intersection([.command, .control, .option]).isEmpty { return nil }
    let special: [UInt16: Int32] = [36:0xff0d, 48:0xff09, 51:0xff08, 53:0xff1b, 123:0xff51, 124:0xff53, 125:0xff54, 126:0xff52, 116:0xff55, 121:0xff56]
    let usKeys: [UInt16:String] = [0:"a",1:"s",2:"d",3:"f",4:"h",5:"g",6:"z",7:"x",8:"c",9:"v",11:"b",12:"q",13:"w",14:"e",15:"r",16:"y",17:"t",18:"1",19:"2",20:"3",21:"4",22:"6",23:"5",24:"=",25:"9",26:"7",27:"-",28:"8",29:"0",30:"]",31:"o",32:"u",33:"[",34:"i",35:"p",37:"l",38:"j",39:"'",40:"k",41:";",42:"\\",43:",",44:"/",45:"n",46:"m",47:".",49:" ",50:"`"]
    let shifted = ["1":"!", "2":"@", "3":"#", "4":"$", "5":"%", "6":"^", "7":"&", "8":"*", "9":"(", "0":")", "-":"_", "=":"+", "[":"{", "]":"}", ";":":", "'":"\"", ",":"<", ".":">", "/":"?", "\\":"|", "`":"~"]
    let character = usKeys[event.keyCode].map { key -> String in
        let shift = event.modifierFlags.contains(.shift)
        if key.uppercased() != key.lowercased() { return shift != event.modifierFlags.contains(.capsLock) ? key.uppercased() : key }
        return shift ? shifted[key] ?? key : key
    }
    let code = special[event.keyCode] ?? (character ?? event.characters)?.unicodeScalars.first.map { Int32($0.value) }
    return code.map { ($0, event.modifierFlags.contains(.shift) ? 1 : 0) }
}

func readingLabel(_ key: String) -> String {
    let keys = Array("1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'")
    let symbols = Array("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙ ")
    let mapping = Dictionary(uniqueKeysWithValues: zip(keys, symbols))
    return (key.hasPrefix("s:") ? "简 · " : "繁 · ") + String(key.dropFirst(2).map { mapping[$0] ?? $0 })
}
