import Foundation
import SQLite3

enum InputBackendKind: String, CaseIterable, Identifiable {
    case meowpuut = "Meowpuut"
    var id: Self { self }
}

enum InputMode: String, CaseIterable, Identifiable {
    case zhuyin, pinyin, japanese
    case nineKey = "nine-key"
    static var allCases: [Self] {
        #if os(iOS)
        [.zhuyin, .pinyin, .japanese, .nineKey]
        #else
        [.zhuyin, .pinyin, .japanese]
        #endif
    }
    var id: Self { self }
    var title: String {
        switch self {
        case .zhuyin: return "注音"
        case .pinyin: return "拼音"
        case .nineKey: return Preferences.shared.simplified ? "九键拼音" : "九鍵拼音"
        case .japanese: return "日本語 · ローマ字"
        }
    }
    var schema: String {
        switch self {
        case .zhuyin: return "bopomofo"
        case .pinyin: return "meowpuut_pinyin"
        case .nineKey: return "meowpuut_nine_key"
        case .japanese: return "jaroomaji"
        }
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
    func predict(context: String, limit: Int) -> [String]
}
extension InputBackend {
    func priorityGroup(_ index: Int) -> Int { 0 }
    func compactJapanese() -> IndexedCandidate? { nil }
    func predict(context: String, limit: Int) -> [String] { [] }
}

/// A read-only mapping of a whole file, unmapped on deinit. The lexicon's
/// pages then count as file-backed memory instead of heap. A file in use must
/// be replaced by rename, not rewritten in place.
final class MappedFile {
    let base: UnsafeMutableRawPointer
    let size: Int

    init?(url: URL) {
        let fd = open(url.path, O_RDONLY)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_size > 0 else { return nil }
        let size = Int(info.st_size)
        guard let base = mmap(nil, size, PROT_READ, MAP_PRIVATE, fd, 0), base != MAP_FAILED else { return nil }
        self.base = base
        self.size = size
    }

    deinit { munmap(base, size) }
}

final class NativeBackend: InputBackend {
    private static let lock = NSLock()
    private static var zhuyinLexicon: OpaquePointer?
    private static var pinyinLexicon: OpaquePointer?

    private static func load(_ name: String, pinyin: Bool) -> OpaquePointer? {
        // The file in the bundle is read-only: the engine reads the mapping in
        // place and keeps it until the lexicon and every session using it are
        // destroyed; then it calls the release callback, which unmaps it.
        if let bundled = Bundle.main.url(forResource: "mimo_base", withExtension: "bin"),
           let file = MappedFile(url: bundled) {
            let context = Unmanaged.passRetained(file).toOpaque()
            let lex = mpn_lexicon_create_from_base_in_place(
                file.base.assumingMemoryBound(to: CChar.self), file.size, pinyin ? 1 : 0,
                { context in
                    guard let context else { return }
                    Unmanaged<MappedFile>.fromOpaque(context).release()
                }, context)
            if let lex { return lex }
        }
        if let binURL = Bundle.main.url(forResource: name, withExtension: "bin"),
           let data = try? Data(contentsOf: binURL, options: .mappedIfSafe), !data.isEmpty {
            let lex = data.withUnsafeBytes { raw -> OpaquePointer? in
                guard let ptr = raw.baseAddress?.assumingMemoryBound(to: CChar.self) else { return nil }
                return mpn_lexicon_create(ptr, raw.count)
            }
            if let lex { return lex }
        }
        guard let url = Bundle.main.url(forResource: name, withExtension: "tsv"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let byteCount = text.utf8.count
        return text.withCString { cstr in
            mpn_lexicon_create(cstr, byteCount)
        }
    }
    /// Attaches the bundled word-pair table, read in place like the bundled
    /// lexicon and unmapped by the release callback. The engine refuses a
    /// table made for another lexicon; that lexicon then ranks without pairs.
    private static func withPairs(_ lexicon: OpaquePointer) -> OpaquePointer {
        if let url = Bundle.main.url(forResource: "mimo_pairs", withExtension: "bin"),
           let file = MappedFile(url: url) {
            _ = mpn_lexicon_attach_pairs(
                lexicon, file.base.assumingMemoryBound(to: CChar.self), file.size,
                { context in
                    guard let context else { return }
                    Unmanaged<MappedFile>.fromOpaque(context).release()
                }, Unmanaged.passRetained(file).toOpaque())
        }
        return lexicon
    }
    /// A session on the lexicon for `mode`, loading it if needed. The session
    /// is made under the lock, so releaseInactiveLexicons cannot destroy the
    /// lexicon before the session holds it.
    private static func makeSession(for mode: InputMode) -> OpaquePointer? {
        lock.lock()
        defer { lock.unlock() }
        let lexicon: OpaquePointer?
        if mode == .pinyin || mode == .nineKey {
            if pinyinLexicon == nil { pinyinLexicon = load("native-pinyin", pinyin: true).map(withPairs) }
            lexicon = pinyinLexicon
        } else {
            if zhuyinLexicon == nil { zhuyinLexicon = load("native-zhuyin", pinyin: false).map(withPairs) }
            lexicon = zhuyinLexicon
        }
        return lexicon.flatMap { mode == .nineKey ? mpn_nine_key_session_create($0) : mpn_session_create($0) }
    }
    /// Heap held by the lexicons that are loaded now (memory diagnostics).
    static var loadedLexiconHeapBytes: Int {
        lock.lock()
        defer { lock.unlock() }
        return [zhuyinLexicon, pinyinLexicon].compactMap { $0 }.reduce(0) { $0 + mpn_lexicon_heap_bytes($1) }
    }
    static func releaseInactiveLexicons(keeping activeMode: InputMode?) {
        lock.lock()
        defer { lock.unlock() }
        if activeMode != .pinyin, activeMode != .nineKey, let lex = pinyinLexicon {
            mpn_lexicon_destroy(lex)
            pinyinLexicon = nil
        }
        if activeMode != .zhuyin, let lex = zhuyinLexicon {
            mpn_lexicon_destroy(lex)
            zhuyinLexicon = nil
        }
    }
    private let session: OpaquePointer
    private let mode: InputMode
    private var punctuation = ""

    init(mode: InputMode = .zhuyin) throws {
        self.mode = mode
        #if os(iOS)
        Self.releaseInactiveLexicons(keeping: mode)
        #endif
        guard let handle = Self.makeSession(for: mode) else { throw CocoaError(.fileReadCorruptFile) }
        session = handle
        if mode == .pinyin || mode == .nineKey, let japanese = JapaneseLexicon.shared {
            _ = mpn_session_add_japanese_mixed(handle, japanese)
        }
    }

    deinit { mpn_session_destroy(session) }
    func snapshot() -> Snapshot {
        let page = mpn_page(session)
        let size = 7
        let count = mpn_candidate_count(session)
        let start = page * size
        let end = min(count, start + size)
        let input = String(cString: mpn_input(session))
        let bytes = Array(input.utf8)
        let visible = (start..<end).map { candidate($0, bytes: bytes) }
        var japanese: [String] = []
        var weights: [String: Int] = [:]
        if mode == .nineKey {
            var info = MPNCandidateInfo()
            for index in 0..<count {
                guard mpn_candidate_info(session, index, &info, MemoryLayout<MPNCandidateInfo>.size) != 0,
                      info.consumed_bytes == UInt64(bytes.count) else { continue }
                let text = String(cString: mpn_candidate(session, index))
                if info.kind == MPN_CANDIDATE_JAPANESE.rawValue { japanese.append(text) }
                else { weights[text] = Int((info.score * 700).rounded()) }
            }
        }
        let committed = String(cString: mpn_committed(session)) + punctuation
        punctuation = ""
        mpn_clear_commit(session)
        return Snapshot(preedit: String(cString: mpn_preedit(session)), commit: committed,
                        candidates: visible.map(\.text), candidateDetails: visible, page: page, lastPage: end == count,
                        input: input, pageSize: size, candidateWeights: weights, japaneseCandidates: japanese,
                        preferredCompleteMixed: visible.first.flatMap { $0.source == 1 ? $0.text : nil })
    }
    func candidates(limit: Int) -> [IndexedCandidate] {
        let bytes = Array(String(cString: mpn_input(session)).utf8)
        return (0..<min(max(0, limit), mpn_candidate_count(session))).map { candidate($0, bytes: bytes) }
    }
    private func candidate(_ index: Int, bytes: [UInt8]) -> IndexedCandidate {
        let text = String(cString: mpn_candidate(session, index))
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
        let japanese = mode == .nineKey && info.kind == MPN_CANDIDATE_JAPANESE.rawValue
        return IndexedCandidate(index: index, text: text, backendIndex: index, source: japanese ? 1 : 0, metadata: metadata)
    }
    func consumedInputBytes(_ index: Int) -> Int {
        guard index >= 0 else { return 0 }
        return mpn_candidate_consumed(session, index)
    }
    func setSimplified(_ simplified: Bool) { mpn_set_simplified(session, simplified ? 1 : 0) }
    var segmentContext: String { String(cString: mpn_segment_context(session)) }
    var currentInput: String { String(cString: mpn_input(session)) }
    func setSegmentContext(_ context: String) {
        context.withCString { mpn_set_segment_context(session, $0) }
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
        let normalized = mode == .pinyin && (65...90).contains(key) ? key + 32 : key
        return mpn_key(session, normalized) != 0
    }
    func clear() { punctuation = ""; mpn_clear(session) }
    func commit() { mpn_commit(session) }
    func choose(_ index: Int) -> Bool { mpn_choose(session, index) != 0 }
    func predict(context: String, limit: Int) -> [String] {
        guard !context.isEmpty, limit > 0 else { return [] }
        let count = context.withCString { mpn_predict(session, $0, limit) }
        guard count > 0 else { return [] }
        return (0..<count).compactMap { index in
            let text = String(cString: mpn_prediction(session, index))
            return text.isEmpty ? nil : text
        }
    }
}

final class WordEvidence {
    static let shared = WordEvidence()
    private var db: OpaquePointer?
    private init() {
        guard let path = Bundle.main.path(forResource: "language-evidence", ofType: "sqlite") else { return }
        if sqlite3_open_v2(path, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX, nil) != SQLITE_OK {
            sqlite3_close(db); db = nil
        }
    }
    deinit { sqlite3_close(db) }
    func dictionary(input: String, mode: InputMode, simplified: Bool) -> [String: Int] {
        guard let db, !input.isEmpty else { return [:] }
        let reading = mode == .pinyin ? input.lowercased().replacingOccurrences(of: "[1-5 '’]", with: "", options: .regularExpression) : input
        let column = mode == .pinyin ? "reading" : (input.contains(where: { " 6347".contains($0) }) ? "phonetic" : "bare")
        let text = simplified ? "hans" : "hant"
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT \(text),weight,toned FROM lexicon WHERE \(column)=? ORDER BY weight DESC LIMIT 256", -1, &statement, nil) == SQLITE_OK else { return [:] }
        defer { sqlite3_finalize(statement) }
        var constraints: [Int: Character] = [:]
        var position = 0
        if mode == .pinyin {
            for ch in input.lowercased() {
                if "12345".contains(ch) { constraints[position] = ch }
                else if ch.isLetter { position += 1 }
            }
        }
        var result: [String: Int] = [:]
        reading.withCString { readingCStr in
            _ = sqlite3_bind_text(statement, 1, readingCStr, -1, nil)
            while sqlite3_step(statement) == SQLITE_ROW {
                if !constraints.isEmpty {
                    guard let value = sqlite3_column_text(statement, 2) else { continue }
                    var tones: [Int: Character] = [:]
                    var offset = 0
                    for ch in String(cString: value) {
                        if "12345".contains(ch) { tones[offset] = ch }
                        else if ch.isLetter { offset += 1 }
                    }
                    if !constraints.allSatisfy({ tones[$0.key] == $0.value }) { continue }
                }
                if let word = sqlite3_column_text(statement, 0) {
                    let value = String(cString: word)
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
        var result: [String: Int] = [:]
        language.withCString { langCStr in
            reading.withCString { readCStr in
                _ = sqlite3_bind_text(statement, 1, langCStr, -1, nil)
                _ = sqlite3_bind_text(statement, 2, readCStr, -1, nil)
                while sqlite3_step(statement) == SQLITE_ROW {
                    if let word = sqlite3_column_text(statement, 0) {
                        result[String(cString: word)] = Int(sqlite3_column_int64(statement, 1))
                    }
                }
            }
        }
        if language == "zh", !candidates.isEmpty {
            var vocabulary: OpaquePointer?
            if sqlite3_prepare_v2(db, "SELECT weight FROM words WHERE language='zh' AND reading='' AND word=?", -1, &vocabulary, nil) == SQLITE_OK {
                defer { sqlite3_finalize(vocabulary) }
                for item in candidates {
                    sqlite3_reset(vocabulary)
                    item.text.withCString { itemCStr in
                        _ = sqlite3_bind_text(vocabulary, 1, itemCStr, -1, nil)
                        if sqlite3_step(vocabulary) == SQLITE_ROW {
                            result[item.text] = max(result[item.text] ?? 0, Int(sqlite3_column_int64(vocabulary, 0)))
                        }
                    }
                }
            }
        }
        return result
    }
}

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
