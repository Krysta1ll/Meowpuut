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
}
extension InputBackend {
    func priorityGroup(_ index: Int) -> Int { 0 }
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
    }

    deinit { mpn_session_destroy(session) }
    func snapshot() -> Snapshot {
        let page = mpn_page(session)
        let size = 7
        let count = mpn_candidate_count(session)
        let start = page * size
        let end = min(count, start + size)
        let visible = (start..<end).map { String(cString: mpn_candidate(session, $0)) }
        let committed = String(cString: mpn_committed(session)) + punctuation
        punctuation = ""
        mpn_clear_commit(session)
        return Snapshot(preedit: String(cString: mpn_preedit(session)), commit: committed,
                        candidates: visible, page: page, lastPage: end == count,
                        input: String(cString: mpn_input(session)), pageSize: size)
    }
    func candidates(limit: Int) -> [IndexedCandidate] {
        (0..<min(limit, mpn_candidate_count(session))).map {
            IndexedCandidate(index: $0, text: String(cString: mpn_candidate(session, $0)))
        }
    }
    func setSimplified(_ simplified: Bool) { mpn_set_simplified(session, simplified ? 1 : 0) }
    func key(_ key: Int32, modifiers: Int32) -> Bool {
        if mode == .pinyin, key == 44 || key == 46 || key == 59 {
            mpn_commit(session)
            punctuation = key == 44 ? "，" : key == 46 ? "。" : "；"
            return true
        }
        return mpn_key(session, key) != 0
    }
    func clear() { punctuation = ""; mpn_clear(session) }
    func commit() { mpn_commit(session) }
    func choose(_ index: Int) -> Bool { mpn_choose(session, index) != 0 }
}

// Whole-word evidence comes only from the independently authored seed vocabulary.
final class WordEvidence {
    static let shared = WordEvidence()
    private var db: OpaquePointer?
    private init() {
        guard let path = Bundle.main.path(forResource: "language-evidence", ofType: "sqlite") else { return }
        if sqlite3_open_v2(path, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nil) != SQLITE_OK {
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
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        reading.withCString { _ = sqlite3_bind_text(statement, 1, $0, -1, transient) }
        var constraints: [Int: Character] = [:]
        var position = 0
        if mode == .pinyin {
            for ch in input.lowercased() {
                if "12345".contains(ch) { constraints[position] = ch }
                else if ch.isLetter { position += 1 }
            }
        }
        var result: [String: Int] = [:]
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

            if let word = sqlite3_column_text(statement, 0) { let value = String(cString: word); result[value] = max(result[value] ?? 0, Int(sqlite3_column_int64(statement, 1))) }
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
    private struct Choice { let text: String; let source: Int; let index: Int }
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

    private let mode: InputMode
    init(mode: InputMode) throws {
        self.mode = mode
        engines = [try NativeBackend(mode: mode), try JapaneseBackend()]
    }
    func snapshot() -> Snapshot { defer { state.commit = "" }; return state }
    func priorityGroup(_ index: Int) -> Int {
        guard locked == nil, choices.indices.contains(index) else { return 0 }
        if choices[index].source == -2 { return -1 }
        if choices[index].source == -3 { return 0 }
        if choices[index].source == 1 || choices[index].source == -1 || choices[index].source == -4 || choices[index].source == -5 { return 2 }
        return mode != .pinyin || exactChinese.contains(choices[index].text) ? 0 : 1
    }
    func candidates(limit: Int) -> [IndexedCandidate] {
        if let locked { return engines[locked].candidates(limit: limit) }
        return choices.prefix(limit).enumerated().map { IndexedCandidate(index: $0.offset, text: $0.element.text) }
    }
    func setSimplified(_ value: Bool) {
        guard simplified != value else { return }
        simplified = value
        engines[0].setSimplified(value)
        if locked != nil { readLocked() } else if !raw.isEmpty { rebuild() }
    }
    func clear() {
        engines.forEach { $0.clear() }
        raw = ""; state = Snapshot(); choices = []; locked = nil; englishContext = false
    }
    func commit() {
        if let locked { engines[locked].commit(); readLocked() }
        else if !choices.isEmpty { _ = choose(0) }
        else { let text = raw; clear(); state.commit = text }
    }
    func choose(_ index: Int) -> Bool {
        if let locked {
            let handled = engines[locked].choose(index); readLocked(); return handled
        }
        guard choices.indices.contains(index) else { return false }
        let choice = choices[index]
        if choice.source < 0 {
            clear(); state.commit = choice.text; return true
        }
        guard engines[choice.source].choose(choice.index) else { return false }
        locked = choice.source
        readLocked()
        return true
    }
    private func readLocked() {
        guard let locked else { return }
        state = engines[locked].snapshot()
        if state.preedit.isEmpty { self.locked = nil; raw = ""; choices = []; engines.forEach { $0.clear() } }
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
            raw.append(Character(UnicodeScalar(UInt32(key))!))
            rebuild()
            return true
        }
        if key == 32, mode == .pinyin, !state.input.isEmpty {
            let text = state.input + " "
            clear(); englishContext = true; state.commit = text
            return true
        }
        if let locked {
            let handled = engines[locked].key(key, modifiers: modifiers)
            readLocked(); return handled
        }
        if key == 0xff1b { let handled = !raw.isEmpty; clear(); return handled }
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
                let text = raw + " "
                clear(); englishContext = true; state.commit = text
                return true
            }
            if key == 32, mode != .pinyin, let last = raw.last, !" 6347".contains(last) {
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
            var carriedCommit = ""
            if raw.count >= 256 {
                repeat {
                    let previous = state.input
                    commit()
                    carriedCommit += state.commit
                    if !state.preedit.isEmpty && state.input == previous {
                        carriedCommit += state.input
                        clear()
                    }
                } while !state.preedit.isEmpty
                clear()
            }
            raw.append(character); rebuild(appending: key)
            state.commit = carriedCommit
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
        state.candidates = choices.dropFirst(page * 7).prefix(7).map(\.text)
        state.page = page; state.pageSize = 7; state.lastPage = (page + 1) * 7 >= choices.count
        state.highlighted = 0
    }
    // Keep mixed readings conservative: a complete Japanese dictionary word
    // must be surrounded by complete, high-confidence Chinese readings.
    private func mixedCandidate(_ input: String) -> String? {
        let chars = Array(input.lowercased())
        guard (5...24).contains(chars.count), chars.allSatisfy({ $0.isASCII && $0.isLetter }),
              JapaneseBackend.knownWord(input.lowercased()) == nil else { return nil }
        var chinese: [String: (String, Int)?] = [:]
        func word(_ reading: String) -> (String, Int)? {
            if reading.isEmpty { return ("", 0) }
            if let cached = chinese[reading] { return cached }
            let entries = WordEvidence.shared.dictionary(input: reading, mode: .pinyin, simplified: simplified)
            let best = entries.filter { $0.value >= 2000 }.sorted {
                $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value
            }.first.map { ($0.key, $0.value) }
            chinese.updateValue(best, forKey: reading)
            return best
        }
        var best: (text: String, score: Int)?
        for start in 0..<(chars.count - 2) {
            for end in (start + 3)...min(chars.count, start + 10) {
                guard start > 0 || end < chars.count,
                      let japanese = JapaneseBackend.knownWord(String(chars[start..<end])), japanese.weight >= 4000,
                      let before = word(String(chars[..<start])), let after = word(String(chars[end...])) else { continue }
                let candidate = (text: before.0 + japanese.text + after.0,
                                 score: before.1 + japanese.weight + after.1)
                if best == nil || candidate.score > best!.score { best = candidate }
            }
        }
        return best?.text
    }
    private func rebuild(appending key: Int32? = nil) {
        guard !raw.isEmpty else { clear(); return }
        var snapshots: [Snapshot] = []
        for (index, backend) in engines.enumerated() {
            if index == 0, let key {
                _ = backend.key(key, modifiers: 0)
            } else {
                backend.clear()
                if index == 0 || (mode == .pinyin && raw.count <= 64) {
                    for scalar in raw.unicodeScalars { _ = backend.key(Int32(scalar.value), modifiers: 0) }
                }
            }
            snapshots.append(backend.snapshot())
        }
        let pinyinFirst = mode == .pinyin
        let hasTones = pinyinFirst && raw.contains { "12345".contains($0) }
        let primary = 0
        var pools = engines.map { $0.candidates(limit: 256) }
        for index in pools.indices where !snapshots[index].commit.isEmpty || snapshots[index].input.isEmpty { pools[index] = [] }
        choices = []
        func append(_ text: String, source: Int, index: Int) {
            guard !text.isEmpty, !choices.contains(where: { $0.text == text }) else { return }
            choices.append(Choice(text: text, source: source, index: index))
        }
        // A bounded Chinese shortlist leads every page-one result. Japanese is supplementary,
        // never an automatic language switch or a learned replacement for Chinese first choice.
        var chinese = pools[primary]
        let dictionary = WordEvidence.shared.dictionary(input: raw, mode: mode, simplified: simplified)
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
        let literal = !hasTones && EnglishEvidence.prefers(raw, context: englishContext,
            chinese: pinyinFirst && !dictionary.isEmpty,
            japanese: !knownJapanese.isEmpty)
        let mixed = pinyinFirst && !hasTones && !literal ? mixedCandidate(raw) : nil
        if literal { append(raw, source: -2, index: 0) }
        let script = simplified ? "s:" : "t:"
        let memoryKey = (pinyinFirst ? "py:" : "") + script + raw
        for text in ChoiceMemory.shared.personalCandidates(for: memoryKey) {
            append(text, source: -3, index: 0)
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
        if japaneseReading {
            // Keep only the leading whole-reading Japanese interpretation before kana alternatives.
            if let item = japanese.first {
                append(item.text, source: 1, index: item.index)
                if required == nil { required = item.text }
            }
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
        if japaneseReading {
            for item in pools[1].dropFirst() { append(item.text, source: 1, index: item.index) }
        }
        if let required, let index = choices.firstIndex(where: { $0.text == required }), index > (literal ? 3 : 2) {
            let candidate = choices.remove(at: index)
            choices.insert(candidate, at: literal ? 3 : 2)
        }
        state = Snapshot(preedit: pinyinFirst || literal ? raw : snapshots[0].preedit, input: raw, requiredCandidate: required, literalCandidate: literal, confidentCandidates: dictionary.filter { $0.value >= 2000 }.map(\.key), candidateWeights: dictionary)
        state.japaneseCandidates = Array(Set(japanese.map(\.text) + (japaneseReading ? [reading, reading.applyingTransform(.hiraganaToKatakana, reverse: false) ?? reading] : []) + (mixed.map { [$0] } ?? []))).filter { !exactChinese.contains($0) }
        let japaneseTexts = Set(state.japaneseCandidates)
        let strongTexts = Set(state.confidentCandidates)
        let literals = choices.filter { $0.source == -2 }
        choices = literals + interleaveLanguages(choices.filter { $0.source != -2 },
            japanese: { japaneseTexts.contains($0.text) },
            strong: { strongTexts.contains($0.text) })
        if raw.count == 1 {
            let rawChoices = choices.filter { $0.source == -4 }
            choices.removeAll { $0.source == -4 }
            choices.insert(contentsOf: rawChoices, at: min(6, choices.count))
        }
        if state.preedit.isEmpty { state.preedit = raw }
        showPage(0)
    }
}
