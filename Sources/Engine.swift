import Foundation
import Combine
#if canImport(AppKit)
import AppKit
#endif

struct Snapshot: Decodable, Equatable {
    var preedit = ""
    var commit = ""
    var candidates: [String] = []
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
    var compactJapanese: IndexedCandidate? = nil
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
    var kind: UInt32
    var scoreKind: UInt32
    var score: Double
    var consumedBytes: Int
    var remainingOffsetBytes: Int
    var remainingInput: String
}

struct IndexedCandidate: Decodable, Equatable {
    var index: Int
    var text: String
    var backendIndex: Int? = nil
    var source: Int? = nil
    var metadata: CandidateMetadata? = nil
    var consumedBytes: Int? = nil
    var remainingInput: String? = nil
}

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
    @Published var catGlassSkin: Bool { didSet { defaults.set(catGlassSkin, forKey: "catGlassSkin") } }
    /// Where the cats sit: `CatSchedule.daily`, or one arrangement's id.
    @Published var catPlacement: String { didSet { defaults.set(catPlacement, forKey: "catPlacement") } }
    @Published var candidateTone: CandidateTone { didSet { defaults.set(candidateTone.rawValue, forKey: "candidateTone") } }
    @Published var glassStrength: GlassStrength { didSet { defaults.set(glassStrength.rawValue, forKey: "glassStrength") } }
    @Published var candidatePalette: CandidatePalette { didSet { defaults.set(candidatePalette.rawValue, forKey: "candidatePalette") } }
    @Published var glassShape: GlassShape { didSet { defaults.set(glassShape.rawValue, forKey: "glassShape") } }
    // Holiday outfits for the cats (docs/FESTIVALS.md).
    @Published var festivalOutfits: Bool { didSet { defaults.set(festivalOutfits, forKey: "festivalOutfits") } }
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
        // 米米莫 and 莫莫 are the default on every platform.
        catGlassSkin = defaults.object(forKey: "catGlassSkin") as? Bool ?? true
        catPlacement = defaults.string(forKey: "catPlacement") ?? CatSchedule.daily
        glassShape = GlassShape(rawValue: defaults.string(forKey: "glassShape") ?? "") ?? .capsule
        festivalOutfits = defaults.object(forKey: "festivalOutfits") as? Bool ?? true
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
    func applySharedSettings() {
        guard let settings = SharedKeyboardSettings.read() else { return }
        if let value = settings.inputMode, let mode = InputMode(rawValue: value) { inputMode = mode }
        if let value = settings.simplified {
            let marker = settings.simplifiedRevision ?? (value ? "simplified" : "traditional")
            if marker != defaults.string(forKey: "appliedSimplifiedRevision") {
                defaults.set(marker, forKey: "appliedSimplifiedRevision")
                if simplified != value { simplified = value }
            }
        }
        if let value = settings.catGlassSkin, catGlassSkin != value { catGlassSkin = value }
        if let value = settings.catPlacement, catPlacement != value { catPlacement = value }
        if let value = settings.rememberChoices, rememberChoices != value { rememberChoices = value }
        if let value = settings.candidateTone, let tone = CandidateTone(rawValue: value), candidateTone != tone { candidateTone = tone }
        if let value = settings.glassStrength, let strength = GlassStrength(rawValue: value), glassStrength != strength { glassStrength = strength }
        if let value = settings.candidatePalette, let palette = CandidatePalette(rawValue: value), candidatePalette != palette { candidatePalette = palette }
        if let value = settings.glassShape, let shape = GlassShape(rawValue: value), glassShape != shape { glassShape = shape }
        if let value = settings.festivals, festivalOutfits != value { festivalOutfits = value }
        if let request = settings.clearMemoryRequest, request != defaults.string(forKey: "lastClearMemoryRequest") {
            ChoiceMemory.shared.clear()
            Kaomoji.forgetRecent()
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
    static let modulesKey: Int32 = 0x100000
    static let literalSlashKey: Int32 = 0x100002
    @Published private(set) var inputMode: InputMode = .zhuyin
    @Published private(set) var backendKind: InputBackendKind = .meowpuut
    var learningTail: (reading: String, text: String, time: TimeInterval)?
    func learn(_ selections: [(reading: String, text: String)]) {
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
    var backend: InputBackend?
    var isReady: Bool { backend != nil }
    @Published var snapshot = Snapshot()
    @Published var predictions: [String] = []
    private(set) var predictionContext = ""
    /// The emoji and kaomoji that lead `predictions`, and the word they are for.
    private(set) var predictionSymbols: Symbols.Found?
    @Published var english = false
    @Published var error = ""
    var moduleBuffer: String?
    var moduleEnglishMode: Bool?
    var candidateOrder: [Int] = []
    var pendingChoices: [(reading: String, text: String)] = []
    @Published var browsing = false
    @Published var phrasesOnly = false
    @Published var browseCount = 0
    var pool: [IndexedCandidate] = []
    let explorationLimit = 256
    func candidates() -> [IndexedCandidate] {
        backend?.candidates(limit: explorationLimit) ?? []
    }
    func browse(phrases: Bool) {
        guard isReady, moduleBuffer == nil, !snapshot.input.isEmpty else { return }
        if !browsing { pool = candidates() }
        browsing = true; phrasesOnly = phrases
        showBrowsePage(0)
    }
    func showBrowsePage(_ requestedPage: Int) {
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
    /// Starts without a backend; `install` supplies one later. The iOS
    /// keyboard uses this to draw its keys before the lexicon is parsed.
    init(deferredMode mode: InputMode) { inputMode = mode }
    /// Lets go of the backend and shows `mode` until `install` supplies the
    /// next one. The iOS keyboard uses this when the mode was changed in the
    /// app: the old lexicon is released before the new one is parsed.
    func unload(for mode: InputMode) {
        clear(); learningTail = nil
        backend = nil
        inputMode = mode
    }
    func selectMode(_ mode: InputMode) { configure(Preferences.shared.keyboardBackend, mode: mode) }
    func selectBackend(_ kind: InputBackendKind) { configure(kind, mode: inputMode) }
    private func configure(_ requestedKind: InputBackendKind, mode: InputMode) {
        let kind = requestedKind
        guard backend == nil || kind != backendKind || mode != inputMode else { return }
        do {
            install(try Self.makeBackend(mode: mode), kind: kind, mode: mode)
        } catch {
            self.error = Self.loadFailedMessage
        }
    }
    static var loadFailedMessage: String {
        Preferences.shared.simplified ? "无法加载喵扑词库。" : "無法載入喵撲詞庫。"
    }
    /// Builds the backend for a mode. The lexicon is parsed here, the slow
    /// part of starting up, so this may run off the main thread. With `warm`
    /// it also types and clears a short reading, so the first real key does
    /// not pay for opening the word database.
    static func makeBackend(mode: InputMode, warm: Bool = false) throws -> InputBackend {
        let next: InputBackend
        #if os(macOS)
        next = mode == .japanese ? try JapaneseBackend() : try ChineseWithJapaneseBackend(mode: mode, literalSpaceAddsSpace: false, emojiCell: false)
        #else
        if mode == .japanese {
            let japanese = try JapaneseBackend()
            japanese.symbolCells = true
            next = japanese
        } else {
            next = try ChineseWithJapaneseBackend(mode: mode)
        }
        #endif
        if warm {
            for scalar in (mode == .zhuyin ? "su3" : "ni").unicodeScalars { _ = next.key(Int32(scalar.value), modifiers: 0) }
            _ = next.snapshot()
            _ = next.candidates(limit: 32)
            next.clear()
            _ = next.snapshot()
        }
        return next
    }
    /// Makes a backend from `makeBackend` the current one (main thread).
    func install(_ next: InputBackend, kind: InputBackendKind = .meowpuut, mode: InputMode) {
        clear(); learningTail = nil
        backend = next
        backendKind = kind
        inputMode = mode
        error = ""
        modeChanged()
    }
    func refresh() {
        guard moduleBuffer == nil, let backend else { return }
        let pendingCommit = snapshot.commit
        snapshot = backend.snapshot()
        let newCommit = snapshot.commit
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
            let context = learningTail.flatMap { Date().timeIntervalSince1970 - $0.time < 20 ? $0.text : nil }
            let slots = visible.indices.filter { snapshot.candidateWeights[visible[$0].text] != nil }
            let ranked = slots.map { visible[$0] }.sorted { a, b in
                let aScore = snapshot.candidateWeights[a.text, default: 0] + ChoiceMemory.shared.boost(for: pinKey, text: a.text, context: context)
                let bScore = snapshot.candidateWeights[b.text, default: 0] + ChoiceMemory.shared.boost(for: pinKey, text: b.text, context: context)
                return aScore == bScore ? a.index < b.index : aScore > bScore
            }
            for (slot, item) in zip(slots, ranked) { visible[slot] = item }
            candidateOrder = Array(visible.map(\.index).prefix(7))
            snapshot.candidates = Array(visible.map(\.text).prefix(7))
            snapshot.candidateDetails = Array(visible.prefix(7))
            snapshot.highlighted = 0
        }
        if snapshot.page == 0, inputMode == .pinyin,
           !snapshot.candidates.contains(where: { snapshot.japaneseCandidates.contains($0) }) {
            snapshot.compactJapanese = backend.compactJapanese()
        } else {
            snapshot.compactJapanese = nil
        }
        if !newCommit.isEmpty && snapshot.preedit.isEmpty {
            updatePredictions(context: newCommit)
        } else if !snapshot.preedit.isEmpty && !predictions.isEmpty {
            predictions = []
        }
    }
    func updatePredictions(context: String) {
        let clean = String(context.suffix(8)).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !english, snapshot.preedit.isEmpty, let backend, !clean.isEmpty else {
            if !predictions.isEmpty { predictions = [] }
            predictionContext = ""; predictionSymbols = nil
            return
        }
        // One emoji of the word just typed comes first (生日快樂 🎂); a second emoji and one
        // kaomoji come after the third word that may follow, so the next words stay in reach.
        // After a single character (有, 上, typed one by one) nothing leads: 🈶 or 👆 in the
        // first cell would only push the next words away.
        predictionSymbols = Symbols.after(clean, japanese: inputMode == .japanese)
        let leading = clean.count > 1 ? 1 : 0
        var merged = predictionSymbols.map { Array($0.emoji.prefix(leading)) } ?? []
        let later = predictionSymbols.map { Array($0.emoji.dropFirst(leading).prefix(2 - leading) + $0.kaomoji.prefix(1)) } ?? []
        let lead = merged.count
        let limit = 8 + lead + later.count
        if inputMode != .japanese,
           clean.unicodeScalars.contains(where: { (0x3400...0x9fff).contains($0.value) || (0x20000...0x323af).contains($0.value) }) {
            for item in ChoiceMemory.shared.contextualPredictions(after: clean, limit: 3) + backend.predict(context: clean, limit: 8)
            where !merged.contains(item) {
                merged.append(item)
            }
            for mark in ["，", "。", "？", "！"] where merged.count < limit && !merged.contains(mark) {
                merged.append(mark)
            }
        }
        merged.insert(contentsOf: later.filter { !merged.contains($0) }, at: min(merged.count, lead + 3))
        predictionContext = merged.isEmpty ? "" : clean
        let next = Array(merged.prefix(limit))
        if predictions != next { predictions = next }
    }
    func takeCommit() -> String { let text = snapshot.commit; snapshot.commit = ""; return text }
    func clear() {
        moduleBuffer = nil
        if let previous = moduleEnglishMode { english = previous; moduleEnglishMode = nil }
        backend?.clear()
        if snapshot != Snapshot() { snapshot = Snapshot() }
        if !predictions.isEmpty { predictions = [] }
        predictionContext = ""; predictionSymbols = nil
        candidateOrder = []
        pendingChoices = []
        browsing = false; pool = []
    }
    func modeChanged() {
        guard let backend else { return }
        backend.setSimplified(Preferences.shared.simplified)
        if moduleBuffer == nil { refresh() }
    }
}
