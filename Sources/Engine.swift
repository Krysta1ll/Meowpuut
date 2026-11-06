import Foundation
import Combine
#if canImport(AppKit)
import AppKit
#endif

struct Snapshot: Decodable {
    var preedit = ""
    var commit = ""
    var candidates: [String] = []
    var highlighted = 0
    var page = 0
    var lastPage = true
    var input = ""
    var pageSize = 7
    var requiredCandidate: String? = nil
    var literalCandidate = false
    var confidentCandidates: [String] = []
    var candidateWeights: [String: Int] = [:]
    var japaneseCandidates: [String] = []
    var compactIndices: [Int] {
        let japanese = Set(japaneseCandidates)
        let first = candidates.firstIndex { japanese.contains($0) }
        return candidates.indices.filter { !japanese.contains(candidates[$0]) } + (first.map { [$0] } ?? [])
    }
    var layoutCandidates: [String] { candidates.map { $0 + (japaneseCandidates.contains($0) ? "　" : "") } }
}
// Preserve strong Chinese results, then alternate Japanese with weaker Chinese.
func interleaveLanguages<T>(_ items: [T], japanese: (T) -> Bool, strong: (T) -> Bool) -> [T] {
    let jp = items.filter(japanese)
    guard !jp.isEmpty else { return items }
    let chinese = items.filter { !japanese($0) }
    let trusted = chinese.filter(strong)
    let weak = chinese.filter { !strong($0) }
    let leading = trusted.isEmpty ? Array(weak.prefix(2)) : Array(trusted.prefix(6))
    let remaining = trusted.isEmpty ? Array(weak.dropFirst(2)) : Array(trusted.dropFirst(6)) + weak
    var result = leading
    for index in 0..<max(jp.count, remaining.count) {
        if index < jp.count { result.append(jp[index]) }
        if index < remaining.count { result.append(remaining[index]) }
    }
    return result
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
    static let testing = CommandLine.arguments.contains("--smoke-languages") || CommandLine.arguments.contains("--smoke")
    static let enginePreview = Bundle.main.bundleIdentifier == "app.meowpuut.EnginePreview"
    private let defaults = Preferences.testing ? UserDefaults(suiteName: "app.meowpuut.smoke")! : UserDefaults.standard
    @Published var simplified: Bool { didSet { defaults.set(simplified, forKey: "simplified") } }
    @Published var modulesEnabled: Bool { didSet { defaults.set(modulesEnabled, forKey: "modulesEnabled") } }
    @Published var rememberChoices: Bool { didSet { defaults.set(rememberChoices, forKey: "rememberChoices") } }
    var catGlassSkin: Bool {
        get { defaults.bool(forKey: "catGlassSkin") }
        set { defaults.set(newValue, forKey: "catGlassSkin") }
    }
    var inputMode: InputMode {
        get { InputMode(rawValue: defaults.string(forKey: "inputMode") ?? "") ?? .zhuyin }
        set { defaults.set(newValue.rawValue, forKey: "inputMode") }
    }
    var keyboardBackend: InputBackendKind {
        get { InputBackendKind(rawValue: defaults.string(forKey: "keyboardBackend") ?? "") ?? .meowpuut }
        set { defaults.set(newValue.rawValue, forKey: "keyboardBackend") }
    }
    @Published var modules: [PhraseModule] = []
    @Published var moduleError = ""
    @Published var pins: [String: String] { didSet { defaults.set(pins, forKey: "candidatePins") } }
    static let directory = testing
        ? FileManager.default.temporaryDirectory.appendingPathComponent("Meowpuut-Smoke-\(ProcessInfo.processInfo.processIdentifier)")
        : FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(enginePreview ? "Meowpuut Engine Preview" : "Meowpuut")
    var moduleDirectory: URL { Self.directory.appendingPathComponent("Modules") }
    private init() {
        simplified = defaults.bool(forKey: "simplified")
        modulesEnabled = defaults.object(forKey: "modulesEnabled") as? Bool ?? true
        rememberChoices = defaults.object(forKey: "rememberChoices") as? Bool ?? (Self.testing || !Self.enginePreview)
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
    static let modulesKey: Int32 = 0x100000 // Explicit command; '/' is the ㄥ key.
    @Published private(set) var inputMode: InputMode = .zhuyin
    @Published private(set) var backendKind: InputBackendKind = .meowpuut
    private var learningTail: (reading: String, text: String, time: TimeInterval)?
    private func learn(_ selections: [(reading: String, text: String)]) {
        var records: [(reading: String, text: String)] = []
        for selection in selections {
            var reading = selection.reading
            if !snapshot.input.isEmpty && reading.hasSuffix(snapshot.input) { reading = String(reading.dropLast(snapshot.input.count)) }
            records.append((reading, selection.text))
            let isChinese = !selection.text.isEmpty && selection.text.unicodeScalars.allSatisfy { (0x3400...0x9fff).contains($0.value) }
            guard isChinese else { learningTail = nil; continue }
            let now = Date().timeIntervalSince1970
            let prefix = reading.hasPrefix("py:") ? String(reading.prefix(5)) : String(reading.prefix(2))
            if let previous = learningTail, now - previous.time < 20, previous.reading.hasPrefix(prefix), previous.text.count + selection.text.count <= 8 {
                records.append(("ctx|" + previous.text + "|" + reading, selection.text))
                records.append((previous.reading + reading.dropFirst(prefix.count), previous.text + selection.text))
            }
            learningTail = (reading, selection.text, now)
        }
        ChoiceMemory.shared.remember(records)
    }
    private var backend: InputBackend?
    var isReady: Bool { backend != nil }
    @Published var snapshot = Snapshot()
    @Published var english = false
    @Published var error = ""
    private var moduleBuffer: String?
    private var moduleEnglishMode: Bool?
    private var candidateOrder: [Int] = []
    private var pendingChoices: [(reading: String, text: String)] = []
    @Published var browsing = false
    @Published var phrasesOnly = false
    @Published var browseCount = 0
    private var pool: [IndexedCandidate] = []
    // Bound optional exploration; ordinary keystrokes still request only the engine page.
    private let explorationLimit = 256
    private func candidates() -> [IndexedCandidate] {
        backend?.candidates(limit: explorationLimit) ?? []
    }
    func browse(phrases: Bool) {
        guard isReady, moduleBuffer == nil, !snapshot.input.isEmpty else { return }
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
        var committed = takeCommit()
        // Partial fallback choices retain a suffix. Explicit completion (punctuation,
        // mode change) must drain it instead of dropping the unconverted reading.
        while !snapshot.preedit.isEmpty {
            let previous = snapshot.input
            backend?.commit(); refresh()
            committed += takeCommit()
            if snapshot.input == previous { break }
        }
        snapshot.commit = committed
    }
    var pinKey: String {
        let script = Preferences.shared.simplified ? "s:" : "t:"
        switch inputMode {
        case .zhuyin: return script + snapshot.input
        case .pinyin: return "py:" + script + snapshot.input
        case .japanese: return "ja:" + snapshot.input
        }
    }
    func candidateShortcut(_ index: Int) -> String {
        (inputMode == .pinyin || moduleBuffer == "`" ? "" : "⇧") + String(index + 1)
    }
    var modeLabel: String {
        if english { return "ABC" }
        if inputMode == .japanese { return "あ" }
        return (inputMode == .pinyin ? "拼 · " : "") + (Preferences.shared.simplified ? "简" : "繁")
    }
    func isPinned(_ text: String) -> Bool { !snapshot.input.isEmpty && Preferences.shared.pins[pinKey] == text }
    func pin(_ index: Int) {
        guard !snapshot.input.isEmpty, snapshot.candidates.indices.contains(index) else { return }
        let text = snapshot.candidates[index]
        var pins = Preferences.shared.pins
        if pins[pinKey] == text { pins.removeValue(forKey: pinKey) } else { pins[pinKey] = text }
        Preferences.shared.pins = pins
        refresh()
    }
    init(_ kind: InputBackendKind = .meowpuut, mode: InputMode = .zhuyin) { configure(kind, mode: mode) }

    func selectMode(_ mode: InputMode) { configure(Preferences.shared.keyboardBackend, mode: mode) }

    func selectBackend(_ kind: InputBackendKind) {
        configure(kind, mode: inputMode)
    }
    private func configure(_ requestedKind: InputBackendKind, mode: InputMode) {
        let kind = requestedKind
        guard backend == nil || kind != backendKind || mode != inputMode else { return }
        do {
            let next: InputBackend
            next = mode == .japanese ? try JapaneseBackend() : try ChineseWithJapaneseBackend(mode: mode)
            clear(); learningTail = nil
            backend = next
            backendKind = kind
            inputMode = mode
            error = ""
            modeChanged()
        } catch {
            self.error = "無法載入 Meowpuut 詞庫。"
        }
    }
    func refresh() {
        guard let backend else { return }
        snapshot = backend.snapshot()
        if !snapshot.commit.isEmpty {
            if pendingChoices.isEmpty { learningTail = nil }
            learn(pendingChoices)
            pendingChoices = []
        } else if snapshot.preedit.isEmpty { pendingChoices = [] }
        browsing = false; pool = []
        candidateOrder = snapshot.candidates.indices.map { snapshot.page * snapshot.pageSize + $0 }
        let preferred = ChoiceMemory.shared.preferred(for: pinKey, context: learningTail.flatMap { Date().timeIntervalSince1970 - $0.time < 20 ? $0.text : nil })
        if snapshot.page == 0, !snapshot.input.isEmpty, !preferred.isEmpty {
            let available = candidates()
            var promoted: [Int] = []
            for text in preferred {
                guard let found = available.first(where: { $0.text == text }), !promoted.contains(found.index) else { continue }
                promoted.append(found.index)
            }
            let promotedSet = Set(promoted)
            let originals = zip(candidateOrder, snapshot.candidates).filter { !promotedSet.contains($0.0) }
            var visible = promoted.compactMap { id in available.first(where: { $0.index == id }) } + originals.map { IndexedCandidate(index: $0.0, text: $0.1) }
            let context = learningTail.flatMap { Date().timeIntervalSince1970 - $0.time < 20 ? $0.text : nil }
            func score(_ item: IndexedCandidate) -> Int {
                let base = snapshot.candidateWeights[item.text] ?? max(0, 4000 - item.index * 5)
                return base + ChoiceMemory.shared.boost(for: pinKey, text: item.text, context: context)
            }
            visible.sort { score($0) == score($1) ? $0.index < $1.index : score($0) > score($1) }
            if inputMode != .japanese {
                let chinese = visible.filter { backend.priorityGroup($0.index) == 0 } + visible.filter { backend.priorityGroup($0.index) == 1 }
                var japanese = visible.filter { backend.priorityGroup($0.index) == 2 }
                if let required = snapshot.requiredCandidate, let match = available.first(where: { $0.text == required }),
                   !japanese.contains(where: { $0.text == required }) { japanese.insert(match, at: 0) }
                let literal = available.filter { backend.priorityGroup($0.index) < 0 }
                let japaneseTexts = Set(snapshot.japaneseCandidates)
                visible = literal + interleaveLanguages(chinese + japanese,
                    japanese: { japaneseTexts.contains($0.text) },
                    strong: { snapshot.confidentCandidates.contains($0.text) })

            }
            candidateOrder = visible.map(\.index); snapshot.candidates = visible.map(\.text)
            candidateOrder = Array(candidateOrder.prefix(7)); snapshot.candidates = Array(snapshot.candidates.prefix(7))
            snapshot.highlighted = 0
        }
    }
    func takeCommit() -> String { let text = snapshot.commit; snapshot.commit = ""; return text }
    func clear() {
        moduleBuffer = nil
        if let previous = moduleEnglishMode { english = previous; moduleEnglishMode = nil }
        backend?.clear()
        snapshot = Snapshot()
        candidateOrder = []
        pendingChoices = []
        browsing = false; pool = []
    }
    func modeChanged() {
        guard let backend else { return }
        backend.setSimplified(Preferences.shared.simplified)
        if moduleBuffer == nil { refresh() }
    }
    func openModules() {
        guard isReady, Preferences.shared.modulesEnabled else { return }
        if moduleBuffer != nil { clear(); return }
        finishComposition()
        let committed = takeCommit()
        moduleEnglishMode = english
        english = false
        moduleBuffer = "/"
        moduleSnapshot()
        snapshot.commit = committed
    }
    @discardableResult func key(_ code: Int32, modifiers: Int32 = 0) -> Bool {
        guard let backend else { return false }
        if code == 0xff1b { learningTail = nil }
        if code == Self.modulesKey { openModules(); return true }
        if code == 32 && modifiers == 1 {
            let next = !english
            finishComposition()
            english = next
            return true
        }
        if english {
            snapshot = Snapshot()
            if (32...126).contains(code), let scalar = UnicodeScalar(UInt32(code)) {
                snapshot.commit = String(scalar); return true
            }
            return false
        }
        if code == 96, modifiers == 0, inputMode != .japanese {
            if moduleBuffer == "`" { clear(); return true }
            finishComposition()
            let committed = takeCommit()
            moduleBuffer = "`"
            moduleSnapshot()
            snapshot.commit = committed
            return true
        }
        // Zhuyin uses unshifted comma/period/semicolon as phonetic keys.
        // Shift punctuation and unused bracket/backslash keys remain unambiguous.
        let zhuyinPunctuation: [Int32: String] = [60: "，", 62: "。", 63: "？", 58: "：", 33: "！", 40: "（", 41: "）", 91: "「", 93: "」", 92: "、", 123: "『", 125: "』"]
        let pinyinPunctuation: [Int32: String] = [44: "，", 46: "。", 59: "；", 47: "？", 58: "：", 33: "！", 40: "（", 41: "）", 91: "「", 93: "」", 92: "、", 34: "“", 123: "『", 125: "』"]
        var mark: String?
        if inputMode == .pinyin {
            if modifiers == 1 {
                let unshifted: [Int32: String] = [60: ",", 62: ".", 63: "?", 58: ";", 34: "'", 123: "[", 125: "]", 124: "\\"]
                mark = unshifted[code]
                if mark == nil, let scalar = UnicodeScalar(UInt32(bitPattern: code)), !Character(scalar).isLetter && (33...126).contains(code) { mark = String(scalar) }
            } else { mark = pinyinPunctuation[code] }
        } else if inputMode == .zhuyin { mark = zhuyinPunctuation[code] }
        if moduleBuffer == nil, let mark,
           !(inputMode == .zhuyin && code == 33 && modifiers == 1 && !snapshot.candidates.isEmpty) {
            finishComposition()
            let committed = takeCommit()
            clear(); learningTail = nil
            snapshot.commit = committed + mark
            return true
        }
        // F6/F7 commit the visible Japanese reading as hiragana/katakana.
        if inputMode == .japanese, code == 0xffc3 || code == 0xffc4, !snapshot.preedit.isEmpty {
            let reading = snapshot.preedit.replacingOccurrences(of: " ", with: "")
            let text = reading.applyingTransform(.hiraganaToKatakana, reverse: code == 0xffc3) ?? reading
            clear(); snapshot.commit = text
            return true
        }
        if browsing && code == 0xff1b { refresh(); return true }
        // Recover a Latin word typed while in Chinese without changing the mode.
        if code == 0xff0d && modifiers == 1, moduleBuffer != nil || !snapshot.input.isEmpty {
            let literal = moduleBuffer ?? snapshot.input
            clear(); snapshot.commit = literal
            return true
        }
        if moduleBuffer == nil, !snapshot.candidates.isEmpty {
            if inputMode == .pinyin, modifiers == 0, (49...55).contains(code) {
                let index = Int(code - 49)
                if snapshot.candidates.indices.contains(index) { choose(index) }
                return true
            }
            if inputMode == .zhuyin, backendKind == .meowpuut, code == 32, modifiers == 0,
               let last = snapshot.input.last, " 6347".contains(last) {
                finishComposition()
                return true
            }
            if code == 0xff52 || code == 0xff54 || code == 0xff09 {
                let count = snapshot.candidates.count
                snapshot.highlighted = (snapshot.highlighted + (code == 0xff52 || (code == 0xff09 && modifiers == 1) ? count - 1 : 1)) % count
                return true
            }
            if code == 0xff0d {
                choose(snapshot.highlighted)
                if snapshot.commit.isEmpty { backend.commit(); refresh() }
                return true
            }
            if inputMode != .pinyin, modifiers == 1, let scalar = UnicodeScalar(UInt32(code)), let index = Array("!@#$%^&*(").firstIndex(of: Character(scalar)) { choose(index); return true }
        }
        if browsing, code == 0xff55 || code == 0xff56 { showBrowsePage(snapshot.page + (code == 0xff55 ? -1 : 1)); return true }
        backend.setSimplified(Preferences.shared.simplified)
        if var buffer = moduleBuffer {
            if buffer == "`", (49...55).contains(code), modifiers == 0 {
                choose(Int(code - 49)); return true
            }
            if buffer == "`", (33...126).contains(code), modifiers == 0 {
                clear(); return key(code, modifiers: modifiers)
            }
            if code == 0xff55 || code == 0xff56 { moduleSnapshot(page: snapshot.page + (code == 0xff55 ? -1 : 1)); return true }
            if inputMode != .pinyin, modifiers == 1, let scalar = UnicodeScalar(UInt32(code)), let index = Array("!@#$%^&*(").firstIndex(of: Character(scalar)) { choose(index); return true }
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
        let handled = backend.key(code, modifiers: modifiers)
        refresh()
        return handled
    }
    private func moduleSnapshot(page requestedPage: Int = 0) {
        guard let buffer = moduleBuffer else { snapshot = Snapshot(); return }
        let matches = Preferences.shared.modules.filter { buffer == $0.trigger || $0.trigger.hasPrefix(buffer) }
        let phrases = buffer == "`" ? ["，", "。", "？", "！", "、", "；", "：", "「", "」", "『", "』", "（", "）", "……", "——", "《", "》", "〈", "〉", "·", "～"] : matches.flatMap(\.phrases)
        let page = max(0, min(requestedPage, max(0, (phrases.count - 1) / 7)))
        snapshot = Snapshot(preedit: buffer == "`" ? "標點" : buffer, candidates: Array(phrases.dropFirst(page * 7).prefix(7)), page: page, lastPage: (page + 1) * 7 >= phrases.count, input: buffer)
    }
    func choose(_ index: Int) {
        guard snapshot.candidates.indices.contains(index) else { return }
        if moduleBuffer != nil {
            let text = snapshot.candidates[index]; clear(); snapshot.commit = text
        } else {
            let reading = pinKey
            let selected = snapshot.candidates[index]
            guard backend?.choose(candidateOrder[index]) == true else { error = "选词未成功，请重试。"; refresh(); return }
            if !pendingChoices.contains(where: { $0.reading == reading && $0.text == selected }) { pendingChoices.append((reading, selected)) }
            refresh()
            if snapshot.commit.isEmpty && snapshot.candidates.isEmpty && !snapshot.preedit.isEmpty {
                backend?.commit(); refresh()
            }
        }
    }
}

#if canImport(AppKit)
struct ShiftTapTracker {
    private var pressed: UInt16?
    mutating func reset() { pressed = nil }
    mutating func update(keyCode: UInt16, modifierEvent: Bool, shift: Bool, other: Bool) -> Bool {
        guard modifierEvent, !other else { reset(); return false }
        guard keyCode == 56 || keyCode == 60 else { reset(); return false }
        if shift {
            if pressed == nil { pressed = keyCode } else { reset() }
            return false
        }
        let toggle = pressed == keyCode
        reset()
        return toggle
    }
}
func inputKey(_ event: NSEvent) -> (Int32, Int32)? {
    if event.keyCode == 49, event.modifierFlags.intersection([.command, .control, .option]) == [.option] { return (Engine.modulesKey, 0) }
    if !event.modifierFlags.intersection([.command, .control, .option]).isEmpty { return nil }
    let special: [UInt16: Int32] = [36:0xff0d, 48:0xff09, 51:0xff08, 53:0xff1b, 123:0xff51, 124:0xff53, 125:0xff54, 126:0xff52, 116:0xff55, 121:0xff56, 97:0xffc3, 98:0xffc4]
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
#endif

func readingLabel(_ key: String) -> String {
    if key.hasPrefix("mix:") { return "自動 · " + key.dropFirst(4) }
    if key.hasPrefix("ja:") { return "日本語 · " + key.dropFirst(3) }
    if key.hasPrefix("py:") { return "拼音 · " + key.dropFirst(3) }
    let keys = Array("1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'")
    let symbols = Array("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙ ")
    let mapping = Dictionary(uniqueKeysWithValues: zip(keys, symbols))
    return (key.hasPrefix("s:") ? "简 · " : "繁 · ") + String(key.dropFirst(2).map { mapping[$0] ?? $0 })
}
