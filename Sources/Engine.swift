import Foundation
import Combine
#if canImport(AppKit)
import AppKit
#endif

struct Snapshot: Decodable {
    var preedit = ""
    var commit = ""
    var candidates: [String] = []
    // In display order, including each candidate's backend selection index.
    var candidateDetails: [IndexedCandidate] = []
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
    // A compact-only candidate from a later page; its index belongs to the
    // backend, not to candidates or candidateDetails on the current page.
    var compactJapanese: IndexedCandidate? = nil
    // A complete mixed sentence may precede Chinese fallback candidates which
    // only convert a prefix. Pure Japanese and complete Chinese keep the usual
    // compact order. An explicit learned selection still takes precedence.
    var preferredCompleteMixed: String? = nil
    var compactIndices: [Int] {
        let japanese = Set(japaneseCandidates)
        let first = candidates.firstIndex { japanese.contains($0) }
        let chinese = candidates.indices.filter { !japanese.contains(candidates[$0]) }
        if let first, first == 0, candidates[first] == preferredCompleteMixed {
            return [first] + chinese
        }
        return chinese + (first.map { [$0] } ?? [])
    }
    var layoutCandidates: [String] { candidates.map { $0 + (japaneseCandidates.contains($0) ? "　" : "") } }
}
struct CandidateMetadata: Decodable, Equatable {
    // These fields describe generation provenance, not calibrated confidence.
    var kind: UInt32
    var scoreKind: UInt32
    var score: Double
    var consumedBytes: Int
    var remainingOffsetBytes: Int
    var remainingInput: String
}
struct IndexedCandidate: Decodable {
    // index selects through the current backend; backendIndex identifies the
    // originating engine's candidate, which can differ in a combined backend.
    var index: Int
    var text: String
    var backendIndex: Int? = nil
    var source: Int? = nil
    var metadata: CandidateMetadata? = nil
    // Selection range is separate from provenance: the mixed API supplies a
    // byte count but does not supply a native score or generation kind.
    var consumedBytes: Int? = nil
    var remainingInput: String? = nil
}

// Data modules are local, versioned JSON: no engine internals leak into extensions.
struct PhraseModule: Codable, Identifiable {
    var id: String
    var apiVersion: Int
    var name: String
    var trigger: String
    var phrases: [String]
}

enum CandidateTone: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var title: String { switch self { case .system: "跟隨系統"; case .light: "淺色"; case .dark: "深色" } }
}
enum GlassStrength: String, CaseIterable, Identifiable {
    case soft, balanced, vivid
    var id: String { rawValue }
    var title: String { switch self { case .soft: "柔和"; case .balanced: "標準"; case .vivid: "鮮明" } }
}
enum CandidatePalette: String, CaseIterable, Identifiable {
    case rose, ocean, sage, lavender, amber, graphite
    var id: String { rawValue }
    var title: String { switch self {
        case .rose: "玫瑰奶茶"; case .ocean: "海鹽藍"; case .sage: "鼠尾草綠"
        case .lavender: "霧紫"; case .amber: "琥珀蜜"; case .graphite: "石墨"
    } }
}

final class Preferences: ObservableObject {
    static let shared = Preferences()
    static let testing = CommandLine.arguments.contains("--smoke-languages") || CommandLine.arguments.contains("--smoke")
    static let enginePreview = Bundle.main.bundleIdentifier == "app.meowpuut.EnginePreview"
    private let defaults = Preferences.testing ? UserDefaults(suiteName: "app.meowpuut.smoke")! : UserDefaults.standard
    @Published var simplified: Bool { didSet { defaults.set(simplified, forKey: "simplified") } }
    @Published var modulesEnabled: Bool { didSet { defaults.set(modulesEnabled, forKey: "modulesEnabled") } }
    private(set) var learningRevision: UInt64 = 0
    @Published var rememberChoices: Bool {
        didSet {
            defaults.set(rememberChoices, forKey: "rememberChoices")
            if oldValue != rememberChoices { learningRevision &+= 1 }
        }
    }
    @Published var catGlassSkin: Bool { didSet { defaults.set(catGlassSkin, forKey: "catGlassSkin") } }
    @Published var candidateTone: CandidateTone { didSet { defaults.set(candidateTone.rawValue, forKey: "candidateTone") } }
    @Published var glassStrength: GlassStrength { didSet { defaults.set(glassStrength.rawValue, forKey: "glassStrength") } }
    @Published var candidatePalette: CandidatePalette { didSet { defaults.set(candidatePalette.rawValue, forKey: "candidatePalette") } }
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
        #if os(iOS)
        catGlassSkin = defaults.object(forKey: "catGlassSkin") as? Bool ?? true
        #else
        catGlassSkin = defaults.bool(forKey: "catGlassSkin")
        #endif
        candidateTone = CandidateTone(rawValue: defaults.string(forKey: "candidateTone") ?? "") ?? .system
        glassStrength = GlassStrength(rawValue: defaults.string(forKey: "glassStrength") ?? "") ?? .balanced
        candidatePalette = CandidatePalette(rawValue: defaults.string(forKey: "candidatePalette") ?? "") ?? .rose
        simplified = defaults.bool(forKey: "simplified")
        modulesEnabled = defaults.object(forKey: "modulesEnabled") as? Bool ?? true
        rememberChoices = defaults.object(forKey: "rememberChoices") as? Bool ?? (Self.testing || !Self.enginePreview)
        pins = defaults.dictionary(forKey: "candidatePins") as? [String: String] ?? [:]
        reload()
    }
    #if os(iOS)
    // Only explicit choices from the containing app override existing keyboard
    // preferences. The keyboard never writes to the shared group container.
    func applySharedSettings() {
        guard let settings = SharedKeyboardSettings.read() else { return }
        if let value = settings.inputMode, let mode = InputMode(rawValue: value) { inputMode = mode }
        if let value = settings.simplified, simplified != value { simplified = value }
        if let value = settings.catGlassSkin, catGlassSkin != value { catGlassSkin = value }
        if let value = settings.rememberChoices, rememberChoices != value { rememberChoices = value }
        if let value = settings.candidateTone, let tone = CandidateTone(rawValue: value), candidateTone != tone { candidateTone = tone }
        if let value = settings.glassStrength, let strength = GlassStrength(rawValue: value), glassStrength != strength { glassStrength = strength }
        if let value = settings.candidatePalette, let palette = CandidatePalette(rawValue: value), candidatePalette != palette { candidatePalette = palette }
        if let request = settings.clearMemoryRequest, request != defaults.string(forKey: "lastClearMemoryRequest") {
            ChoiceMemory.shared.clear()
            if ChoiceMemory.shared.flush() { defaults.set(request, forKey: "lastClearMemoryRequest") }
        }
    }
    #endif
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
    static let toggleEnglishKey: Int32 = 0x100001
    static let modulesKey: Int32 = 0x100000 // Explicit command; '/' is the ㄥ key.
    static let literalSlashKey: Int32 = 0x100002
    @Published private(set) var inputMode: InputMode = .zhuyin
    @Published private(set) var backendKind: InputBackendKind = .meowpuut
    private var learningTail: (reading: String, text: String, time: TimeInterval)?
    private var memoryContextRevision = ChoiceMemory.shared.contextRevision
    private var learningPreferenceRevision = Preferences.shared.learningRevision
    // A document/focus boundary must not join unrelated selections into a
    // personal phrase. Ordinary composition clearing retains the same-field tail.
    func resetLearningContext() { learningTail = nil }
    private func synchronizeLearningContext() {
        let memoryRevision = ChoiceMemory.shared.contextRevision
        let preferenceRevision = Preferences.shared.learningRevision
        if memoryRevision != memoryContextRevision || preferenceRevision != learningPreferenceRevision || !Preferences.shared.rememberChoices {
            resetLearningContext()
        }
        memoryContextRevision = memoryRevision
        learningPreferenceRevision = preferenceRevision
    }
    private func learn(_ selections: [(reading: String, text: String)]) {
        synchronizeLearningContext()
        guard Preferences.shared.rememberChoices else { return }
        var records: [(reading: String, text: String)] = []
        for selection in selections {
            var reading = selection.reading
            let suffix = reading.hasPrefix("py:") ? snapshot.input.lowercased() : snapshot.input
            if !suffix.isEmpty && reading.hasSuffix(suffix) { reading = String(reading.dropLast(suffix.count)) }
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
    private func candidates() -> [IndexedCandidate] {
        // Each source is bounded when choices are built. Do not truncate their
        // combined result: that would make later exact matches unreachable.
        backend?.candidates(limit: .max) ?? []
    }
    func browse(phrases: Bool) {
        guard isReady, moduleBuffer == nil, !snapshot.input.isEmpty else { return }
        if !browsing { pool = candidates() }
        browsing = true; phrasesOnly = phrases
        showBrowsePage(0)
    }
    private func showBrowsePage(_ requestedPage: Int) {
        // A Chinese reading's raw Latin input is a fallback, not a phrase.
        // Keep it when the engine explicitly identifies the input as English.
        let items = phrasesOnly ? pool.filter {
            $0.text.count > 1 && (snapshot.literalCandidate || $0.text.lowercased() != snapshot.input.lowercased())
        } : pool
        browseCount = items.count
        let page = max(0, min(requestedPage, max(0, (items.count - 1) / 7)))
        let start = min(page * 7, items.count)
        let visible = Array(items.dropFirst(start).prefix(7))
        candidateOrder = visible.map(\.index)
        snapshot.candidates = visible.map(\.text)
        snapshot.candidateDetails = visible
        snapshot.page = page; snapshot.lastPage = start + 7 >= items.count; snapshot.highlighted = 0
    }
    func finishComposition() {
        if moduleBuffer != nil {
            let raw = moduleBuffer ?? ""; clear(); snapshot.commit = raw; return
        }
        if !snapshot.candidates.isEmpty {
            if snapshot.highlighted == snapshot.candidates.count, snapshot.compactJapanese != nil {
                chooseCompactJapanese()
            } else {
                choose(snapshot.highlighted)
            }
        }
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
    var needsDocumentContext: Bool {
        !english && inputMode != .japanese && moduleBuffer == nil && snapshot.preedit.isEmpty
    }
    func needsDocumentContext(for key: Int32) -> Bool {
        guard needsDocumentContext else { return false }
        if inputMode == .pinyin { return (65...90).contains(key) || (97...122).contains(key) }
        guard key > 0, key < 128, let scalar = UnicodeScalar(UInt32(key)) else { return false }
        return "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-".contains(Character(scalar))
    }
    func prepareDocumentContext(before text: String?) {
        guard needsDocumentContext else { return }
        // This is ranking evidence only. It never enters learningTail or the
        // user's saved choices, and it is replaced at each new composition.
        backend?.beginContext(CompositionContext.precedingChinese(text))
    }
    var pinKey: String {
        let script = Preferences.shared.simplified ? "s:" : "t:"
        switch inputMode {
        case .zhuyin: return script + snapshot.input
        case .pinyin: return "py:" + script + snapshot.input.lowercased()
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
            #if os(macOS)
            next = mode == .japanese ? try JapaneseBackend() : try ChineseWithJapaneseBackend(mode: mode, literalSpaceAddsSpace: false)
            #else
            next = mode == .japanese ? try JapaneseBackend() : try ChineseWithJapaneseBackend(mode: mode)
            #endif
            // The host may finish composition before changing mode but deliver
            // its result afterward. Reset the reading, not that pending result.
            let committed = snapshot.commit
            clear(); learningTail = nil
            snapshot.commit = committed
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
        synchronizeLearningContext()
        guard moduleBuffer == nil, let backend else { return }
        let pendingCommit = snapshot.commit
        snapshot = backend.snapshot()
        let newCommit = snapshot.commit
        // A settings change can re-rank while the client still holds an
        // undelivered commit. Deliver each part once, in its original order.
        snapshot.commit = pendingCommit + newCommit
        if !newCommit.isEmpty {
            if pendingChoices.isEmpty { learningTail = nil }
            learn(pendingChoices)
            pendingChoices = []
        } else if snapshot.preedit.isEmpty { pendingChoices = [] }
        browsing = false; pool = []
        candidateOrder = snapshot.candidates.indices.map { snapshot.page * snapshot.pageSize + $0 }
        if snapshot.candidateDetails.count != snapshot.candidates.count {
            snapshot.candidateDetails = zip(candidateOrder, snapshot.candidates).map {
                IndexedCandidate(index: $0.0, text: $0.1, backendIndex: $0.0, source: inputMode == .japanese ? 1 : nil)
            }
        }
        let preferred = ChoiceMemory.shared.preferred(for: pinKey, context: learningTail.flatMap { Date().timeIntervalSince1970 - $0.time < 20 ? $0.text : nil })
        if snapshot.page == 0, !snapshot.input.isEmpty, !preferred.isEmpty {
            let available = candidates()
            var promoted: [Int] = []
            for text in preferred {
                guard let found = available.first(where: { $0.text == text }), !promoted.contains(found.index) else { continue }
                promoted.append(found.index)
            }
            var visible = snapshot.candidateDetails
            for id in promoted where !visible.contains(where: { $0.index == id }) {
                if let item = available.first(where: { $0.index == id }) { visible.append(item) }
            }
            // Only compare dictionary weights for candidates in that same
            // dictionary. Learning can adjust that ranking without allowing a
            // single Japanese choice to overtake a strong Chinese match.
            let context = learningTail.flatMap { Date().timeIntervalSince1970 - $0.time < 20 ? $0.text : nil }
            let slots = visible.indices.filter { snapshot.candidateWeights[visible[$0].text] != nil }
            var ranked = slots.map { visible[$0] }
            let boosts = Dictionary(uniqueKeysWithValues: ranked.map {
                ($0.index, ChoiceMemory.shared.boost(for: pinKey, text: $0.text, context: context))
            })
            // Keep the producing engine's context order. Only an actually
            // remembered candidate may move forward; re-sorting every word by
            // its isolated frequency would undo the context ranking even when
            // the saved word no longer exists in the dictionary.
            for item in ranked where boosts[item.index, default: 0] > 0 {
                guard var position = ranked.firstIndex(where: { $0.index == item.index }) else { continue }
                let score = snapshot.candidateWeights[item.text, default: 0] + boosts[item.index, default: 0]
                while position > 0 {
                    let previous = ranked[position - 1]
                    let previousScore = snapshot.candidateWeights[previous.text, default: 0] + boosts[previous.index, default: 0]
                    guard score > previousScore else { break }
                    ranked.swapAt(position, position - 1)
                    position -= 1
                }
            }
            for (slot, item) in zip(slots, ranked) { visible[slot] = item }
            candidateOrder = visible.map(\.index); snapshot.candidates = visible.map(\.text)
            candidateOrder = Array(candidateOrder.prefix(7)); snapshot.candidates = Array(snapshot.candidates.prefix(7))
            snapshot.candidateDetails = Array(visible.prefix(7))
            snapshot.highlighted = 0
        }
        if snapshot.page == 0, inputMode == .pinyin,
           !snapshot.candidates.contains(where: { snapshot.japaneseCandidates.contains($0) }) {
            snapshot.compactJapanese = backend.compactJapanese()
        } else {
            snapshot.compactJapanese = nil
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
        if code == Self.toggleEnglishKey || (inputMode != .pinyin && code == 32 && modifiers == 1) {
            let next = !english
            finishComposition()
            english = next
            resetLearningContext()
            return true
        }
        if english {
            let pendingCommit = snapshot.commit
            snapshot = Snapshot()
            snapshot.commit = pendingCommit
            if (32...126).contains(code), let scalar = UnicodeScalar(UInt32(code)) {
                snapshot.commit += String(scalar); return true
            }
            return false
        }
        if code == Self.literalSlashKey {
            finishComposition()
            let committed = takeCommit()
            clear(); learningTail = nil
            snapshot.commit = committed + "/"
            return true
        }
        // Shift + Da-Chien tone positions are independent of ordinary digit selection.
        if inputMode == .pinyin, modifiers == 1, moduleBuffer == nil,
           !snapshot.input.isEmpty, let tone = [Int32(32): Int32(49), 94: 50, 35: 51, 36: 52, 38: 53][code] {
            let handled = backend.key(tone, modifiers: 0)
            refresh()
            return handled
        }
        if inputMode == .pinyin, code == 32, modifiers == 1 { return false }
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
        let pinyinPunctuation: [Int32: String] = [44: "，", 46: "。", 59: "；", 47: "？", 45: "-", 58: "：", 33: "！", 40: "（", 41: "）", 91: "「", 93: "」", 92: "、", 34: "“", 123: "『", 125: "』"]
        var mark: String?
        if inputMode == .pinyin {
            if modifiers == 1 {
                let unshifted: [Int32: String] = [60: ",", 62: ".", 63: "?", 58: ":", 34: "'", 123: "[", 125: "]", 124: "\\"]
                mark = unshifted[code]
                if mark == nil, let scalar = UnicodeScalar(UInt32(bitPattern: code)), !Character(scalar).isLetter && (33...126).contains(code) { mark = String(scalar) }
            } else {
                // Apostrophes split syllables in an active reading; on their
                // own they should not start an unselectable composition.
                mark = code == 39 && snapshot.input.isEmpty ? "‘" : pinyinPunctuation[code]
            }
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
        if inputMode == .pinyin, moduleBuffer == nil, modifiers == 0, (48...57).contains(code) {
            if (49...55).contains(code), snapshot.candidates.indices.contains(Int(code - 49)) {
                choose(Int(code - 49))
            } else {
                if !snapshot.input.isEmpty { finishComposition() }
                let committed = takeCommit()
                clear()
                snapshot.commit = committed + String(UnicodeScalar(UInt32(code))!)
            }
            return true
        }
        if moduleBuffer == nil, !snapshot.candidates.isEmpty {
            if inputMode == .pinyin, code == 32, modifiers == 0 {
                choose(snapshot.highlighted)
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
        // The host handles unconsumed deletion, whitespace and navigation.
        // Its insertion point is no longer known to be adjacent to our last word.
        if !handled { resetLearningContext() }
        return handled
    }
    private func moduleSnapshot(page requestedPage: Int = 0) {
        guard let buffer = moduleBuffer else { snapshot = Snapshot(); return }
        let matches = Preferences.shared.modules.filter { buffer == $0.trigger || $0.trigger.hasPrefix(buffer) }
        let phrases = buffer == "`" ? ["，", "。", "？", "！", "、", "；", "：", "「", "」", "『", "』", "（", "）", "……", "——", "《", "》", "〈", "〉", "·", "～", "/", "\\", "-", "_", "?", ";", ":"] : matches.flatMap(\.phrases)
        let page = max(0, min(requestedPage, max(0, (phrases.count - 1) / 7)))
        snapshot = Snapshot(preedit: buffer == "`" ? "標點" : buffer, candidates: Array(phrases.dropFirst(page * 7).prefix(7)), page: page, lastPage: (page + 1) * 7 >= phrases.count, input: buffer)
    }
    func choose(_ index: Int) {
        guard snapshot.candidates.indices.contains(index) else { return }
        if moduleBuffer != nil {
            let text = snapshot.candidates[index]; clear(); snapshot.commit = text
        } else {
            chooseBackendCandidate(index: candidateOrder[index], text: snapshot.candidates[index])
        }
    }
    func chooseCompactJapanese() {
        guard let candidate = snapshot.compactJapanese else { return }
        chooseBackendCandidate(index: candidate.index, text: candidate.text)
    }
    private func chooseBackendCandidate(index: Int, text: String) {
        let reading = pinKey
        guard backend?.choose(index) == true else { error = "选词未成功，请重试。"; refresh(); return }
        if !pendingChoices.contains(where: { $0.reading == reading && $0.text == text }) { pendingChoices.append((reading, text)) }
        refresh()
        if snapshot.commit.isEmpty && snapshot.candidates.isEmpty && !snapshot.preedit.isEmpty {
            backend?.commit(); refresh()
        }
    }
}

#if canImport(AppKit)
struct ShiftTapTracker {
    private var pressed: UInt16?
    private var previousShift = false
    mutating func reset() { pressed = nil; previousShift = false }
    mutating func update(keyCode: UInt16, modifierEvent: Bool, shift: Bool, other: Bool) -> Bool {
        let wasShift = previousShift
        previousShift = shift
        guard modifierEvent, !other else { pressed = nil; return false }
        // Some clients report modifier transitions with key code zero.
        let effectiveKey: UInt16
        if keyCode == 56 || keyCode == 60 { effectiveKey = keyCode }
        else if keyCode == 0, wasShift != shift { effectiveKey = pressed ?? 56 }
        else { pressed = nil; return false }
        if shift {
            if !wasShift { pressed = effectiveKey }
            else if pressed != effectiveKey { pressed = nil }
            return false
        }
        let toggle = wasShift && pressed == effectiveKey
        pressed = nil
        return toggle
    }
}
func inputKey(_ event: NSEvent, mode: InputMode = .zhuyin, english: Bool = false, composing: Bool = false) -> (Int32, Int32)? {
    if event.keyCode == 49, event.modifierFlags.intersection([.command, .control, .option]) == [.option] { return (Engine.modulesKey, 0) }
    if event.modifierFlags.intersection([.command, .control, .option, .shift]) == [.option],
       (mode == .zhuyin && !english && event.keyCode == 44) || event.charactersIgnoringModifiers == "/" {
        return (Engine.literalSlashKey, 0)
    }
    if !event.modifierFlags.intersection([.command, .control, .option]).isEmpty { return nil }
    let special: [UInt16: Int32] = [36:0xff0d, 76:0xff0d, 48:0xff09, 51:0xff08, 53:0xff1b, 123:0xff51, 124:0xff53, 125:0xff54, 126:0xff52, 116:0xff55, 121:0xff56, 97:0xffc3, 98:0xffc4]
    if let key = special[event.keyCode] { return (key, event.modifierFlags.contains(.shift) ? 1 : 0) }
    let usKeys: [UInt16:String] = [0:"a",1:"s",2:"d",3:"f",4:"h",5:"g",6:"z",7:"x",8:"c",9:"v",11:"b",12:"q",13:"w",14:"e",15:"r",16:"y",17:"t",18:"1",19:"2",20:"3",21:"4",22:"6",23:"5",24:"=",25:"9",26:"7",27:"-",28:"8",29:"0",30:"]",31:"o",32:"u",33:"[",34:"i",35:"p",37:"l",38:"j",39:"'",40:"k",41:";",42:"\\",43:",",44:"/",45:"n",46:"m",47:".",49:" ",50:"`"]
    let shifted = ["1":"!", "2":"@", "3":"#", "4":"$", "5":"%", "6":"^", "7":"&", "8":"*", "9":"(", "0":")", "-":"_", "=":"+", "[":"{", "]":"}", ";":":", "'":"\"", ",":"<", ".":">", "/":"?", "\\":"|", "`":"~"]
    let physicalTone = mode == .pinyin && !english && composing && event.modifierFlags.contains(.shift) && [UInt16(49), 22, 20, 21, 26].contains(event.keyCode)
    if english || (mode != .zhuyin && !physicalTone) {
        // Passing through non-ASCII, composed sequences and dead keys lets the
        // client apply the active macOS keyboard layout without losing text.
        guard let characters = event.characters, characters.unicodeScalars.count == 1,
              let scalar = characters.unicodeScalars.first, (32...126).contains(scalar.value) else { return nil }
        return (Int32(scalar.value), event.modifierFlags.contains(.shift) ? 1 : 0)
    }
    let character = usKeys[event.keyCode].map { key -> String in
        let shift = event.modifierFlags.contains(.shift)
        if key.uppercased() != key.lowercased() { return shift != event.modifierFlags.contains(.capsLock) ? key.uppercased() : key }
        return shift ? shifted[key] ?? key : key
    }
    let code = (character ?? event.characters)?.unicodeScalars.first.map { Int32($0.value) }
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
