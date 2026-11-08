import Foundation
import SQLite3

enum InputBackendKind: String, CaseIterable, Identifiable {
    case meowpuut = "Meowpuut"
    var id: Self { self }
}

enum InputMode: String, CaseIterable, Identifiable {
    case zhuyin, pinyin, japanese
    var id: Self { self }
    var title: String {
        switch self { case .zhuyin: return "注音"; case .pinyin: return "拼音"; case .japanese: return "日本語 · ローマ字" }
    }
    var schema: String {
        switch self { case .zhuyin: return "bopomofo"; case .pinyin: return "meowpuut_pinyin"; case .japanese: return "jaroomaji" }
    }
}

protocol InputBackend: AnyObject {
    func snapshot() -> Snapshot
    func candidates(limit: Int) -> [IndexedCandidate]
    func setSimplified(_ simplified: Bool)
    func key(_ key: Int32, modifiers: Int32) -> Bool
    func clear()
    func commit()
    func choose(_ index: Int) -> Bool
    func priorityGroup(_ index: Int) -> Int
    func compactJapanese() -> IndexedCandidate?
    func beginContext(_ text: String)
}
extension InputBackend {
    func priorityGroup(_ index: Int) -> Int { 0 }
    func compactJapanese() -> IndexedCandidate? { nil }
    func beginContext(_ text: String) {}
}

final class NativeBackend: InputBackend {
    private static func load(_ name: String) -> OpaquePointer? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "tsv"),
              let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        return data.withUnsafeBytes { bytes in
            mpn_lexicon_create(bytes.bindMemory(to: CChar.self).baseAddress, bytes.count)
        }
    }
    private static let zhuyin = load("native-zhuyin")
    private static let pinyin = load("native-pinyin")
    private let session: OpaquePointer
    private let mode: InputMode
    private var punctuation = ""

    init(mode: InputMode = .zhuyin) throws {
        self.mode = mode
        guard let lexicon = mode == .pinyin ? Self.pinyin : Self.zhuyin,
              let handle = mpn_session_create(lexicon) else { throw CocoaError(.fileReadCorruptFile) }
        session = handle
        if mode == .pinyin {
            for word in JapaneseBackend.mixedWords {
                word.reading.withCString { reading in
                    word.text.withCString { text in
                        _ = mpn_add_japanese_word(handle, reading, text, Int32(word.weight))
                    }
                }
            }
        }
    }

    deinit { mpn_session_destroy(session) }
    func snapshot() -> Snapshot {
        let page = mpn_page(session)
        let size = 7
        let count = mpn_candidate_count(session)
        let start = page * size
        let end = min(count, start + size)
        let visible = (start..<end).map { candidate($0) }
        let committed = String(cString: mpn_committed(session)) + punctuation
        punctuation = ""
        mpn_clear_commit(session)
        return Snapshot(preedit: String(cString: mpn_preedit(session)), commit: committed,
                        candidates: visible.map(\.text), candidateDetails: visible, page: page, lastPage: end == count,
                        input: String(cString: mpn_input(session)), pageSize: size)
    }
    func candidates(limit: Int) -> [IndexedCandidate] {
        (0..<min(max(0, limit), mpn_candidate_count(session))).map { candidate($0) }
    }
    private func candidate(_ index: Int) -> IndexedCandidate {
        let text = String(cString: mpn_candidate(session, index))
        let input = String(cString: mpn_input(session))
        let bytes = Array(input.utf8)
        var info = MPNCandidateInfo()
        var metadata: CandidateMetadata?
        if mpn_candidate_info(session, index, &info, MemoryLayout<MPNCandidateInfo>.size) != 0,
           bytes.allSatisfy({ $0 < 128 }),
           let consumed = Int(exactly: info.consumed_bytes),
           let offset = Int(exactly: info.remaining_offset_bytes),
           consumed <= bytes.count, offset <= bytes.count {
            metadata = CandidateMetadata(kind: info.kind, scoreKind: info.score_kind, score: info.score,
                                         consumedBytes: consumed, remainingOffsetBytes: offset,
                                         remainingInput: String(decoding: bytes.dropFirst(offset), as: UTF8.self))
        }
        return IndexedCandidate(index: index, text: text, backendIndex: index, source: 0, metadata: metadata)
    }
    // The number of original ASCII input bytes selected by this candidate.
    // Full consumption distinguishes complete Chinese matches from a fallback
    // that merely converts the first syllable and leaves the rest untouched.
    func consumedInputBytes(_ index: Int) -> Int {
        guard index >= 0 else { return 0 }
        return mpn_candidate_consumed(session, index)
    }
    func setSimplified(_ simplified: Bool) { mpn_set_simplified(session, simplified ? 1 : 0) }
    // Copy immediately: the core-owned pointer expires after session mutation.
    var segmentContext: String { String(cString: mpn_segment_context(session)) }
    func setSegmentContext(_ context: String) {
        context.withCString { mpn_set_segment_context(session, $0) }
    }
    func beginContext(_ text: String) {
        guard String(cString: mpn_input(session)).isEmpty else { return }
        setSegmentContext(text)
    }
    func mixedPinyin(_ reading: String, simplified: Bool) -> String? {
        guard mode == .pinyin else { return nil }
        let result = reading.withCString { String(cString: mpn_mixed_pinyin(session, $0, simplified ? 1 : 0)) }
        return result.isEmpty ? nil : result
    }
    func mixedPinyinPrefix(_ reading: String, simplified: Bool) -> (text: String, consumed: Int)? {
        guard mode == .pinyin else { return nil }
        var consumed = 0
        let result = reading.withCString { String(cString: mpn_mixed_pinyin_prefix(session, $0, simplified ? 1 : 0, &consumed)) }
        return result.isEmpty || consumed == 0 ? nil : (result, consumed)
    }
    func key(_ key: Int32, modifiers: Int32) -> Bool {
        if mode == .pinyin, key == 44 || key == 46 || key == 59 {
            mpn_commit(session)
            punctuation = key == 44 ? "，" : key == 46 ? "。" : "；"
            return true
        }
        // Pinyin readings are case-insensitive, while the outer composition
        // keeps the original bytes for literal English and unconverted suffixes.
        let normalized = mode == .pinyin && (65...90).contains(key) ? key + 32 : key
        return mpn_key(session, normalized) != 0
    }
    func clear() { punctuation = ""; mpn_clear(session) }
    func commit() { mpn_commit(session) }
    func choose(_ index: Int) -> Bool { mpn_choose(session, index) != 0 }
}

// Whole-word evidence comes only from the independently authored seed vocabulary.
final class WordEvidence {
    static let shared = WordEvidence()
    private var db: OpaquePointer?
    private var hasSimplifiedWeight = false
    private var simplifiedWeightNotNull = false
    init(path: String? = Bundle.main.path(forResource: "language-evidence", ofType: "sqlite")) {
        guard let path else { return }
        if sqlite3_open_v2(path, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nil) != SQLITE_OK {
            sqlite3_close(db); db = nil
            return
        }
        var statement: OpaquePointer?
        if sqlite3_prepare_v2(db, "PRAGMA table_info(lexicon)", -1, &statement, nil) == SQLITE_OK {
            while sqlite3_step(statement) == SQLITE_ROW {
                if let name = sqlite3_column_text(statement, 1), String(cString: name) == "hans_weight" {
                    hasSimplifiedWeight = true
                    simplifiedWeightNotNull = sqlite3_column_int(statement, 3) != 0
                    break
                }
            }
        }
        sqlite3_finalize(statement)
    }
    deinit { sqlite3_close(db) }
    func dictionary(input: String, mode: InputMode, simplified: Bool) -> [String: Int] {
        guard let db, !input.isEmpty else { return [:] }
        // Keep explicit syllable separators until after alias expansion.  In
        // particular lu'e must remain lu + e rather than becoming lüe.
        let normalized = mode == .pinyin ? input.lowercased()
            .replacingOccurrences(of: "ü", with: "v")
            .replacingOccurrences(of: "u:", with: "v") : input
        func withoutPinyinBoundaries(_ value: String) -> String {
            value.replacingOccurrences(of: "[1-5 '’]", with: "", options: .regularExpression)
        }
        let reading = mode == .pinyin ? withoutPinyinBoundaries(normalized) : normalized
        let canonical = mode == .pinyin
            ? withoutPinyinBoundaries(normalized.replacingOccurrences(of: "([nl])ue", with: "$1ve", options: .regularExpression))
            : reading
        let readings = canonical == reading ? [reading] : [reading, canonical]
        let column = mode == .pinyin ? "reading" : (input.contains(where: { " 6347".contains($0) }) ? "phonetic" : "bare")
        let text = simplified ? "hans" : "hant"
        let weight = simplified && hasSimplifiedWeight
            ? (simplifiedWeightNotNull ? "hans_weight" : "COALESCE(hans_weight,weight)") : "weight"
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT \(text),\(weight),toned FROM lexicon WHERE \(column)=? ORDER BY \(weight) DESC", -1, &statement, nil) == SQLITE_OK else { return [:] }
        defer { sqlite3_finalize(statement) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        var constraints: [Int: Character] = [:]
        var position = 0
        if mode == .pinyin {
            for ch in normalized {
                if "12345".contains(ch) { constraints[position] = ch }
                else if ch == "'" || ch == "’" { constraints[position] = "'" }
                else if ch.isLetter { position += 1 }
            }
        }
        var result: [String: Int] = [:]
        for queryReading in readings {
            sqlite3_reset(statement)
            queryReading.withCString { _ = sqlite3_bind_text(statement, 1, $0, -1, transient) }
            var matchedWords = Set<String>()
            while matchedWords.count < 2048 && sqlite3_step(statement) == SQLITE_ROW {
                if !constraints.isEmpty {
                    guard let value = sqlite3_column_text(statement, 2) else { continue }
                    var tones: [Int: Character] = [:]
                    var offset = 0
                    for ch in String(cString: value) {
                        if "12345".contains(ch) { tones[offset] = ch }
                        else if ch.isLetter { offset += 1 }
                    }
                    // An apostrophe requires a syllable boundary, but does
                    // not constrain its tone. Stripping it for the lookup
                    // must not make xi'an match the single syllable xian.
                    if !constraints.allSatisfy({ entry in
                        guard let tone = tones[entry.key] else { return false }
                        return entry.value == "'" || tone == entry.value
                    }) { continue }
                }

                if let word = sqlite3_column_text(statement, 0) {
                    let value = String(cString: word)
                    matchedWords.insert(value)
                    result[value] = max(result[value] ?? 0, Int(sqlite3_column_int64(statement, 1)))
                }
            }
        }
        return result
    }
    func words(language: String, input: String, candidates: [IndexedCandidate] = []) -> [String: Int] {
        guard let db else { return [:] }
        var reading = input.lowercased().replacingOccurrences(of: "ü", with: "v")
            .replacingOccurrences(of: "[0-5 '\u{2019}]", with: "", options: .regularExpression)
        if language == "ja" {
            reading = reading.replacingOccurrences(of: "nn(?=[bcdfghjklmpqrstvwxz]|$)", with: "n", options: .regularExpression)
        }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT word,weight FROM words WHERE language=? AND reading=?", -1, &statement, nil) == SQLITE_OK else { return [:] }
        defer { sqlite3_finalize(statement) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        language.withCString { _ = sqlite3_bind_text(statement, 1, $0, -1, transient) }
        reading.withCString { _ = sqlite3_bind_text(statement, 2, $0, -1, transient) }
        var result: [String: Int] = [:]
        while sqlite3_step(statement) == SQLITE_ROW {
            if let word = sqlite3_column_text(statement, 0) { result[String(cString: word)] = Int(sqlite3_column_int64(statement, 1)) }
        }
        if language == "zh", !candidates.isEmpty {
            var vocabulary: OpaquePointer?
            if sqlite3_prepare_v2(db, "SELECT weight FROM words WHERE language='zh' AND reading='' AND word=?", -1, &vocabulary, nil) == SQLITE_OK {
                defer { sqlite3_finalize(vocabulary) }
                for item in candidates {
                    sqlite3_reset(vocabulary)
                    item.text.withCString { _ = sqlite3_bind_text(vocabulary, 1, $0, -1, transient) }
                    if sqlite3_step(vocabulary) == SQLITE_ROW { result[item.text] = max(result[item.text] ?? 0, Int(sqlite3_column_int64(vocabulary, 0))) }
                }
            }
        }
        return result
    }
}

// Bounded, platform-neutral English evidence; no service call on the typing path.
enum EnglishEvidence {
    private static let words = Set("am me us my he she his her him they them their all no so any how why where when if then also learning learned beautiful important amazing wonderful emoji emojis emoticon emoticons markdown screenshot screenshots configuration compatibility hello world this that these those is are was were a an the and or but for from with without please thanks thank you your we our it its to of in on at as be been not can could would should will have has had do does did need want use using test testing search music anime download update install settings keyboard input method candidate candidates development developer programming program application applications information international internationalization performance optimization computer computers macos windows iphone internet website browser code swift python javascript typescript function return const import class string github codex openai meowpuut good morning night today tomorrow really working work works example examples english sentence sentences word words".split(separator: " ").map(String.init))
    static func prefers(_ raw: String, context: Bool, chinese: Bool, japanese: Bool) -> Bool {
        guard raw.count > 1, raw.count <= 128, raw.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || $0 == 39 }), !japanese else { return false }
        let lower = raw.lowercased()
        if context && words.contains(lower) { return true }
        guard !chinese else { return false }
        if words.contains(lower) && (raw.count >= 4 || context || raw == "I" || lower == "the") { return true }
        guard raw.count >= 6 else { return false }
        let chars = Array(raw)
        if zip(chars, chars.dropFirst()).contains(where: { $0.isLowercase && $1.isUppercase }) { return true }
        let ending = ["tion", "ment", "ness", "ful", "less", "ous", "ally", "able", "ible", "ity"].contains(where: lower.hasSuffix)
        let cluster = ["ght", "ck", "str", "spl", "ll", "tt", "ff", "pp", "mm", "dd"].contains(where: lower.contains)
        return ending || cluster
    }
}

// Parallel interpretations stay local. A selected partial conversion delegates to
// its original engine so the unconverted suffix is never silently discarded.
final class ChineseWithJapaneseBackend: InputBackend {
    private struct Choice { let text: String; let source: Int; let index: Int; let metadata: CandidateMetadata? }
    private static let associations: [String: [String]] = {
        guard let url = Bundle.main.url(forResource: "associations", withExtension: "json"),
              let data = try? Data(contentsOf: url), let values = try? JSONDecoder().decode([String: [String]].self, from: data) else { return [:] }
        return values
    }()

    private let engines: [InputBackend]
    private var raw = ""
    private var state = Snapshot()
    private var choices: [Choice] = []
    private var locked: Int?
    private var simplified = false
    private var exactChinese = Set<String>()
    private var englishContext = false
    // The primary backend is replayed to keep Chinese and Japanese candidates
    // in sync. Preserve only this composition's selected Chinese prefix.
    private var segmentContext = ""
    private var rememberedChoicesEnabled = Preferences.shared.rememberChoices
    private var memoryRevision = ChoiceMemory.shared.revision

    private let mode: InputMode
    private let literalSpaceAddsSpace: Bool
    init(mode: InputMode, literalSpaceAddsSpace: Bool = true) throws {
        self.mode = mode
        self.literalSpaceAddsSpace = literalSpaceAddsSpace
        engines = [try NativeBackend(mode: mode), try JapaneseBackend()]
    }
    func snapshot() -> Snapshot {
        if rememberedChoicesEnabled != Preferences.shared.rememberChoices || memoryRevision != ChoiceMemory.shared.revision {
            rememberedChoicesEnabled = Preferences.shared.rememberChoices
            memoryRevision = ChoiceMemory.shared.revision
            if locked == nil && !raw.isEmpty {
                // An explicit settings refresh may happen before the client has
                // consumed a pending commit. Re-rank the reading, not its text.
                let pendingCommit = state.commit
                rebuild()
                state.commit = pendingCommit
            }
        }
        defer { state.commit = "" }
        return state
    }
    func priorityGroup(_ index: Int) -> Int {
        guard locked == nil, choices.indices.contains(index) else { return 0 }
        if choices[index].source == -2 { return -1 }
        if choices[index].source == -3 { return 0 }
        if choices[index].source == 1 || choices[index].source == -1 || choices[index].source == -4 || choices[index].source == -5 || choices[index].source == -6 { return 2 }
        return mode != .pinyin || exactChinese.contains(choices[index].text) ? 0 : 1
    }
    func candidates(limit: Int) -> [IndexedCandidate] {
        if let locked { return engines[locked].candidates(limit: limit) }
        return choices.prefix(max(0, limit)).enumerated().map { offset, choice in
            IndexedCandidate(index: offset, text: choice.text, backendIndex: choice.index, source: choice.source, metadata: choice.metadata)
        }
    }
    func compactJapanese() -> IndexedCandidate? {
        guard locked == nil,
              let index = choices.firstIndex(where: { state.japaneseCandidates.contains($0.text) }) else { return nil }
        let choice = choices[index]
        return IndexedCandidate(index: index, text: choice.text, backendIndex: choice.index, source: choice.source, metadata: choice.metadata)
    }
    func setSimplified(_ value: Bool) {
        guard simplified != value else { return }
        simplified = value
        engines[0].setSimplified(value)
        if locked != nil { readLocked() } else if !raw.isEmpty { rebuild() }
    }
    func beginContext(_ text: String) {
        guard raw.isEmpty, state.preedit.isEmpty, locked == nil else { return }
        segmentContext = text
        engines[0].beginContext(text)
    }
    func clear() {
        engines.forEach { $0.clear() }
        raw = ""; state = Snapshot(); choices = []; locked = nil; englishContext = false; segmentContext = ""
    }
    func commit() {
        if let locked { engines[locked].commit(); readLocked() }
        else if !choices.isEmpty { _ = choose(0) }
        else { let text = state.commit + raw; clear(); state.commit = text }
    }
    func choose(_ index: Int) -> Bool {
        if let locked {
            let handled = engines[locked].choose(index); readLocked(); return handled
        }
        guard choices.indices.contains(index) else { return false }
        let choice = choices[index]
        if choice.source == -6 {
            // The portable core reports an ASCII byte count, valid for this
            // all-ASCII reading. Never consume the unconverted suffix.
            guard choice.index > 0, choice.index < raw.utf8.count,
                  raw.utf8.allSatisfy({ $0 < 128 }) else { return false }
            segmentContext = ""
            raw = String(raw.dropFirst(choice.index))
            rebuild()
            state.commit += choice.text
            return true
        }
        if choice.source < 0 {
            let committed = state.commit + choice.text
            clear(); state.commit = committed; return true
        }
        guard engines[choice.source].choose(choice.index) else { return false }
        let savedContext = choice.source == 0 ? (engines[0] as? NativeBackend)?.segmentContext ?? "" : ""
        locked = choice.source
        readLocked()
        if mode == .pinyin, choice.source == 0, !state.input.isEmpty {
            // A Chinese prefix may leave a Japanese or mixed suffix. Resume
            // both interpreters while retaining the pending commit.
            let coreSuffix = state.input, committed = state.commit
            // The core receives lowercase pinyin; preserve the user's original
            // case for the portion that was not selected.
            let originalSuffix = String(raw.suffix(coreSuffix.count))
            let suffix = originalSuffix.lowercased() == coreSuffix ? originalSuffix : coreSuffix
            segmentContext = savedContext
            raw = suffix; locked = nil
            rebuild()
            state.commit = committed
        }
        return true
    }
    private func readLocked() {
        guard let locked else { return }
        let pendingCommit = state.commit
        state = engines[locked].snapshot()
        state.commit = pendingCommit + state.commit
        if state.preedit.isEmpty { self.locked = nil; raw = ""; choices = []; segmentContext = ""; engines.forEach { $0.clear() } }
    }
    func key(_ key: Int32, modifiers: Int32) -> Bool {
        if mode == .pinyin, (49...53).contains(key) {
            if let locked {
                guard locked == 0, let last = state.input.last, last.isLetter || "12345".contains(last) else { return false }
                if "12345".contains(last) { _ = engines[locked].key(0xff08, modifiers: 0) }
                let handled = engines[locked].key(key, modifiers: 0)
                readLocked()
                return handled
            }
            guard let last = raw.last, last.isLetter || "12345".contains(last) else { return false }
            if "12345".contains(last) { raw.removeLast() }
            else if raw.utf8.count >= mpn_max_input_bytes() {
                let committed = state.commit + raw + String(UnicodeScalar(UInt32(key))!)
                clear(); state.commit = committed
                return true
            }
            raw.append(Character(UnicodeScalar(UInt32(key))!))
            rebuild()
            return true
        }
        if key == 32, mode == .pinyin, !state.input.isEmpty {
            let text = state.commit + state.input + (literalSpaceAddsSpace ? " " : "")
            clear(); englishContext = true; state.commit = text
            return true
        }
        if let locked {
            let handled = engines[locked].key(key, modifiers: modifiers)
            readLocked(); return handled
        }
        if key == 0xff1b {
            let handled = !raw.isEmpty, pendingCommit = state.commit
            clear(); state.commit = pendingCommit
            return handled
        }
        if key == 0xff08 {
            guard !raw.isEmpty else { return false }
            raw.removeLast(); rebuild(); return true
        }
        if key == 0xff55 || key == 0xff56 {
            guard !choices.isEmpty else { return false }
            showPage(state.page + (key == 0xff55 ? -1 : 1)); return true
        }
        if key == 0xff0d || key == 32 {
            guard !raw.isEmpty else { return false }
            if key == 32, state.literalCandidate {
                let text = state.commit + raw + " "
                clear(); englishContext = true; state.commit = text
                return true
            }
            if key == 32, mode != .pinyin, let last = raw.last, !" 6347".contains(last) {
                if raw.utf8.count >= mpn_max_input_bytes() {
                    let committed = state.commit + state.preedit + "ˉ"
                    clear(); state.commit = committed
                    return true
                }
                raw.append(" "); rebuild(appending: 32); return true
            }
            commit(); return true
        }
        // Keep normal punctuation behavior in the Chinese interpreter.
        guard let scalar = UnicodeScalar(UInt32(bitPattern: key)), key < 128 else { return false }
        let character = Character(scalar)
        if mode == .pinyin && !raw.isEmpty && ",.;".contains(character) {
            commit()
            state.commit += character == "," ? "，" : character == "." ? "。" : "；"
            return true
        }
        if "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890'-,./;".contains(character) {
            if raw.utf8.count >= mpn_max_input_bytes() {
                let valid = mode == .pinyin
                    ? "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'".contains(character)
                    : "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347'".contains(character)
                guard valid else { return false }
                // A tone or separator still belongs to the full reading. Do
                // not start a new composition with an orphaned boundary.
                let boundary: String? = mode == .pinyin
                    ? (character == "'" ? "'" : nil)
                    : ["6": "ˊ", "3": "ˇ", "4": "ˋ", "7": "˙", "'": " "][character]
                if let boundary {
                    if mode == .pinyin, let last = raw.last, "12345'".contains(last) { return false }
                    if mode == .zhuyin, let last = raw.last, " 6347'".contains(last) { return true }
                    let committed = state.commit + (mode == .pinyin ? raw : state.preedit) + boundary
                    clear(); state.commit = committed
                    return true
                }
                let committed = state.commit + (mode == .pinyin ? raw : state.preedit)
                clear()
                raw.append(character); rebuild(appending: key)
                state.commit = committed + state.commit
                return true
            }
            raw.append(character); rebuild(appending: key)
            return true
        }
        if raw.isEmpty { return false }
        // Preserve an unfinished reading on punctuation; don't swallow the punctuation.
        commit()
        if !state.preedit.isEmpty { engines[locked ?? 0].commit(); readLocked() }
        state.commit += String(character)
        return true
    }
    private func showPage(_ requested: Int) {
        let page = max(0, min(requested, max(0, (choices.count - 1) / 7)))
        let visible = choices.enumerated().dropFirst(page * 7).prefix(7).map { offset, choice in
            IndexedCandidate(index: offset, text: choice.text, backendIndex: choice.index, source: choice.source, metadata: choice.metadata)
        }
        state.candidates = visible.map(\.text)
        state.candidateDetails = visible
        state.page = page; state.pageSize = 7; state.lastPage = (page + 1) * 7 >= choices.count
        state.highlighted = 0
    }
    // Mixed segmentation is shared with other platforms in the portable core.
    private func mixedCandidate(_ input: String) -> String? {
        let reading = input.lowercased()
        guard JapaneseBackend.knownWord(reading) == nil else { return nil }
        return (engines[0] as? NativeBackend)?.mixedPinyin(reading, simplified: simplified)
    }
    private func rebuild(appending key: Int32? = nil) {
        rememberedChoicesEnabled = Preferences.shared.rememberChoices
        memoryRevision = ChoiceMemory.shared.revision
        let pendingCommit = state.commit
        guard !raw.isEmpty else { clear(); state.commit = pendingCommit; return }
        var snapshots: [Snapshot] = []
        for (index, backend) in engines.enumerated() {
            if index == 0, let key {
                _ = backend.key(key, modifiers: 0)
            } else {
                backend.clear()
                if index == 0, !segmentContext.isEmpty {
                    (backend as? NativeBackend)?.setSegmentContext(segmentContext)
                }
                if index == 0 || (mode == .pinyin && raw.count <= 64) {
                    for scalar in raw.unicodeScalars { _ = backend.key(Int32(scalar.value), modifiers: 0) }
                }
            }
            snapshots.append(backend.snapshot())
        }
        let pinyinFirst = mode == .pinyin
        let hasTones = pinyinFirst && raw.contains { "12345".contains($0) }
        let primary = 0
        var pools = engines.map { $0.candidates(limit: 2048) }
        for index in pools.indices where !snapshots[index].commit.isEmpty || snapshots[index].input.isEmpty { pools[index] = [] }
        // If a key was rejected (for example a second apostrophe), the native
        // reading no longer represents all typed bytes. Do not let a native
        // candidate silently discard the unmatched part on selection.
        let unmatchedPinyin = pinyinFirst && snapshots[primary].input != raw.lowercased()
        if unmatchedPinyin { pools[primary] = [] }
        let nativeMetadata = Dictionary(uniqueKeysWithValues: pools[0].compactMap { item in
            item.metadata.map { (item.index, $0) }
        })
        choices = []
        struct ChoiceKey: Hashable {
            let text: String
            let remainingOffset: Int?
            let source: Int?
            let index: Int?
        }
        var seenChoices = Set<ChoiceKey>()
        func append(_ text: String, source: Int, index: Int) {
            var metadata = source == 0 ? nativeMetadata[index] : nil
            if let offset = metadata?.remainingOffsetBytes, raw.utf8.allSatisfy({ $0 < 128 }), offset <= raw.utf8.count {
                metadata?.remainingInput = String(decoding: raw.utf8.dropFirst(offset), as: UTF8.self)
            }
            // Equal-looking native candidates may consume different prefixes.
            let offset = source == 0 ? metadata?.remainingOffsetBytes : (source == -6 ? index : raw.utf8.count)
            let key = ChoiceKey(text: text, remainingOffset: offset,
                                source: offset == nil ? source : nil,
                                index: offset == nil ? index : nil)
            guard !text.isEmpty, seenChoices.insert(key).inserted else { return }
            choices.append(Choice(text: text, source: source, index: index, metadata: metadata))
        }
        // A bounded Chinese shortlist leads every page-one result. Japanese is supplementary,
        // never an automatic language switch or a learned replacement for Chinese first choice.
        var chinese = pools[primary]
        let dictionary = unmatchedPinyin ? [:] : WordEvidence.shared.dictionary(input: raw, mode: mode, simplified: simplified)
        let knownChinese = dictionary
        exactChinese = Set(dictionary.keys)
        let nativeTexts = Set(chinese.map(\.text))
        let additions = dictionary.keys.filter { !nativeTexts.contains($0) }.sorted { dictionary[$0] == dictionary[$1] ? $0 < $1 : dictionary[$0, default: 0] > dictionary[$1, default: 0] }
        if pinyinFirst {
            chinese = chinese.filter { knownChinese[$0.text] != nil } + chinese.filter { knownChinese[$0.text] == nil }
        }
        let knownJapanese = pinyinFirst ? WordEvidence.shared.words(language: "ja", input: raw) : [:]
        let japanese = pools[1].filter { knownJapanese[$0.text] != nil }
            .sorted { (knownJapanese[$0.text] ?? 0) > (knownJapanese[$1.text] ?? 0) }
        var required: String?
        let literal = unmatchedPinyin || (!hasTones && EnglishEvidence.prefers(raw, context: englishContext,
            chinese: pinyinFirst && !dictionary.isEmpty,
            japanese: !knownJapanese.isEmpty))
        // Literal English stays first; mixed readings remain selectable.
        let mixed = pinyinFirst && !hasTones ? mixedCandidate(raw) : nil
        let mixedPrefix = pinyinFirst && !hasTones
            ? (engines[0] as? NativeBackend)?.mixedPinyinPrefix(raw.lowercased(), simplified: simplified) : nil
        if literal { append(raw, source: -2, index: 0) }
        let script = simplified ? "s:" : "t:"
        let memoryKey = (pinyinFirst ? "py:" : "") + script + (pinyinFirst ? raw.lowercased() : raw)
        let personalCandidates = ChoiceMemory.shared.personalCandidates(for: memoryKey)
        for text in personalCandidates {
            // A remembered sentence can already be a native decoding path.
            // Preserve its selection metadata; equal-looking partial choices
            // must not be mistaken for this complete reading.
            if let native = chinese.first(where: {
                $0.text == text && $0.metadata?.consumedBytes == raw.utf8.count &&
                $0.metadata?.remainingOffsetBytes == raw.utf8.count
            }) {
                append(text, source: primary, index: native.index)
            } else {
                append(text, source: -3, index: 0)
            }
            exactChinese.insert(text)
        }
        let supplementFirst = chinese.first.map { dictionary[$0.text] == nil } ?? true
        if supplementFirst { for text in additions.prefix(7) { append(text, source: -3, index: 0) } }
        let chineseSource = primary
        for item in chinese.prefix(2) { append(item.text, source: chineseSource, index: item.index) }
        let reading = snapshots[1].preedit.replacingOccurrences(of: " ", with: "")
        let japaneseReading = pinyinFirst && !hasTones && !reading.isEmpty && reading.unicodeScalars.allSatisfy { (0x3040...0x30ff).contains($0.value) }
        if let mixed {
            append(mixed, source: -5, index: 0)
            required = mixed
        }
        if let prefix = mixedPrefix {
            append(prefix.text, source: -6, index: prefix.consumed)
            // A recorded mixed prefix is selectable without consuming the
            // unfinished suffix. Keep it ahead of speculative kana so the
            // compact row's one Japanese slot does not hide that choice.
            if required == nil && choices.contains(where: { $0.source == -6 && $0.text == prefix.text }) {
                required = prefix.text
            }
        }
        if japaneseReading || !japanese.isEmpty {
            // Keep only the leading whole-reading Japanese interpretation before kana alternatives.
            if let item = japanese.first {
                append(item.text, source: 1, index: item.index)
                if required == nil { required = item.text }
            }
        }
        if japaneseReading {
            if required == nil { required = reading }
            append(reading, source: -1, index: 0)
            append(reading.applyingTransform(.hiraganaToKatakana, reverse: false) ?? reading, source: -1, index: 0)
        }
        if raw.count <= 3 && raw.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) }) {
            append(raw, source: -4, index: 0)
        }
        // Remaining Chinese choices stay available by expanding/paging, without flooding the compact row.
        for item in chinese.dropFirst(2).prefix(1) { append(item.text, source: chineseSource, index: item.index) }
        if !supplementFirst { for text in additions.prefix(7) { append(text, source: -3, index: 0) } }
        if !literal, let first = chinese.first {
            for symbol in (Self.associations[first.text] ?? []).prefix(2) { append(symbol, source: -1, index: 0) }
        }
        for item in chinese.dropFirst(3) { append(item.text, source: chineseSource, index: item.index) }
        // Keep every indexed exact match available to paging. The leading seven
        // supplements retain their usual placement; the rest follow native words.
        for text in additions.dropFirst(7) { append(text, source: -3, index: 0) }
        if japaneseReading {
            for item in pools[1].dropFirst() { append(item.text, source: 1, index: item.index) }
        } else {
            // Mixed Chinese-pinyin/Japanese aliases can contain characters
            // that are not a complete romaji reading. Keep recorded words,
            // but don't surface the converter's partially raw kana guesses.
            for item in japanese.dropFirst() { append(item.text, source: 1, index: item.index) }
        }
        state = Snapshot(preedit: pinyinFirst || literal ? raw : snapshots[0].preedit, commit: pendingCommit, input: raw, requiredCandidate: required, literalCandidate: literal, confidentCandidates: dictionary.filter { $0.value >= 2000 }.map(\.key), candidateWeights: dictionary)
        var japaneseTexts = choices.filter { $0.source == 1 }.map(\.text)
        if japaneseReading {
            japaneseTexts.append(reading)
            japaneseTexts.append(reading.applyingTransform(.hiraganaToKatakana, reverse: false) ?? reading)
        }
        if let mixed { japaneseTexts.append(mixed) }
        if let mixedPrefix { japaneseTexts.append(mixedPrefix.text) }
        state.japaneseCandidates = Array(Set(japaneseTexts)).filter { !exactChinese.contains($0) }
        let strongTexts = Set(state.confidentCandidates)
        let remembered = Set(personalCandidates)
        // Stable evidence tiers avoid comparing scores from separate engines.
        // Within each tier retain the producing engines' order. Kana generated
        // solely from transliteration is weaker than dictionary-backed words.
        func tier(_ choice: Choice) -> Int {
            if choice.source == -2 { return 0 }
            if remembered.contains(choice.text) && strongTexts.isEmpty { return 1 }
            if strongTexts.contains(choice.text) { return 2 }
            if choice.source == -4 { return 5 }
            if choice.source == -1 { return 4 }
            return 3
        }
        choices = choices.enumerated().sorted {
            let a = tier($0.element), b = tier($1.element)
            return a == b ? $0.offset < $1.offset : a < b
        }.map(\.element)
        // A full mixed sentence is more useful than single-character Chinese
        // fallbacks that leave most of the reading unconverted. Never displace
        // a complete Chinese candidate, literal English, or learned choice.
        if pinyinFirst, !literal, dictionary.isEmpty, personalCandidates.isEmpty,
           let mixed, let native = engines[0] as? NativeBackend,
           chinese.allSatisfy({ $0.text.count == 1 && native.consumedInputBytes($0.index) < raw.utf8.count }),
           let index = choices.firstIndex(where: { $0.source == -5 && $0.text == mixed }) {
            choices.insert(choices.remove(at: index), at: 0)
            state.preferredCompleteMixed = mixed
        }
        if raw.count == 1 {
            let rawChoices = choices.filter { $0.source == -4 }
            choices.removeAll { $0.source == -4 }
            choices.insert(contentsOf: rawChoices, at: min(6, choices.count))
        }
        if state.preedit.isEmpty { state.preedit = raw }
        showPage(0)
    }
}
