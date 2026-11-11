import Foundation
import SQLite3

// Parallel interpretations stay local. A selected partial conversion delegates to
// its original engine so the unconverted suffix is never silently discarded.
final class ChineseWithJapaneseBackend: InputBackend {
    private struct Choice { let text: String; let source: Int; let index: Int; let metadata: CandidateMetadata? }

    private let engines: [InputBackend]
    private var raw = ""
    private var state = Snapshot()
    private var choices: [Choice] = []
    private var locked: Int?
    private var simplified = false
    private var exactChinese = Set<String>()
    /// Bare kana spelling of the pinyin letters (hiragana, katakana); ranked last.
    private var bareKana: [String] = []
    // Rank of each Japanese word of the current input: 20000 + weight for a reviewed word,
    // the weight alone for a word of the large list (placed after every Chinese candidate).
    private var japaneseRank: [String: Int] = [:]
    // Pure-kanji words on an input that is also a Chinese reading: shown at the end of the
    // list, never in the collapsed row (owner decision 2026-11-11).
    private var lateJapanese = Set<String>()
    private var englishContext = false
    private var segmentContext = ""
    /// Emoji and kaomoji of the first candidate, as listed among the choices.
    private var symbols: Symbols.Found?
    private var rememberedChoicesEnabled = Preferences.shared.rememberChoices
    private var memoryRevision = ChoiceMemory.shared.revision

    private let mode: InputMode
    private let literalSpaceAddsSpace: Bool
    /// iOS: an emoji cell after the first candidate and a kaomoji cell after the third.
    /// macOS keeps its numbered list as it was, with the symbols after the words.
    private let emojiCell: Bool
    init(mode: InputMode, literalSpaceAddsSpace: Bool = true, emojiCell: Bool = true) throws {
        self.mode = mode
        self.literalSpaceAddsSpace = literalSpaceAddsSpace
        self.emojiCell = emojiCell
        let side = try JapaneseBackend()
        side.pinyinSide = true
        engines = [try NativeBackend(mode: mode), side]
    }
    func snapshot() -> Snapshot {
        if rememberedChoicesEnabled != Preferences.shared.rememberChoices || memoryRevision != ChoiceMemory.shared.revision {
            rememberedChoicesEnabled = Preferences.shared.rememberChoices
            memoryRevision = ChoiceMemory.shared.revision
            if locked == nil && !raw.isEmpty {
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
              let index = choices.firstIndex(where: { state.japaneseCandidates.contains($0.text) && !bareKana.contains($0.text) && !lateJapanese.contains($0.text) }) else { return nil }
        let choice = choices[index]
        return IndexedCandidate(index: index, text: choice.text, backendIndex: choice.index, source: choice.source, metadata: choice.metadata)
    }
    func predict(context: String, limit: Int) -> [String] { engines[0].predict(context: context, limit: limit) }
    func setSimplified(_ value: Bool) {
        guard simplified != value else { return }
        simplified = value
        engines[0].setSimplified(value)
        if locked != nil { readLocked() } else if !raw.isEmpty { rebuild() }
    }
    func clear() {
        engines.forEach { $0.clear() }
        raw = ""; state = Snapshot(); choices = []; locked = nil; englishContext = false; segmentContext = ""; symbols = nil
    }
    func commit() {
        if let locked { engines[locked].commit(); readLocked() }
        else if !choices.isEmpty { _ = choose(0) }
        // Zhuyin raw is key codes (1qaz); the reading shown is the bopomofo.
        else { let text = state.commit + (mode == .pinyin ? raw : state.preedit); clear(); state.commit = text }
    }
    func choose(_ index: Int) -> Bool {
        if let locked {
            let handled = engines[locked].choose(index); readLocked(); return handled
        }
        guard choices.indices.contains(index) else { return false }
        let choice = choices[index]
        if choice.source == -6 {
            guard choice.index > 0, choice.index < raw.utf8.count,
                  raw.utf8.allSatisfy({ $0 < 128 }) else { return false }
            segmentContext = ""
            raw = String(raw.dropFirst(choice.index))
            rebuild()
            state.commit += choice.text
            return true
        }
        if choice.source < 0 {
            if let symbols, symbols.all.contains(choice.text) { Symbols.remember(choice.text, for: symbols.word) }
            let committed = state.commit + choice.text
            clear(); state.commit = committed; return true
        }
        guard engines[choice.source].choose(choice.index) else { return false }
        let savedContext = choice.source == 0 ? (engines[0] as? NativeBackend)?.segmentContext ?? "" : ""
        locked = choice.source
        readLocked()
        if mode == .pinyin, choice.source == 0, !state.input.isEmpty {
            let coreSuffix = state.input, committed = state.commit
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
            // Incremental: the core pops one key instead of re-decoding every
            // prefix (held delete used to be O(N²)). rebuild verifies the core
            // input and falls back to a full replay if they ever diverge.
            raw.removeLast(); rebuild(appending: 0xff08); return true
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
    private static let evidence: OpaquePointer? = {
        var db: OpaquePointer?
        guard let path = Bundle.main.path(forResource: "language-evidence", ofType: "sqlite"),
              sqlite3_open_v2(path, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK else { return nil }
        return db
    }()
    /// Zhuyin typed as initials only (ㄋㄏ for 你好), the way pinyin takes nh.
    /// Words with one syllable per initial, each starting with it; an eighth of
    /// the word's weight, as the pinyin abbreviation rows get.
    private static func zhuyinAbbreviations(_ keys: String, simplified: Bool) -> [String: Int] {
        guard (2...4).contains(keys.count), keys.allSatisfy({ "1qaz2wsxedcrfv5tgbyhn".contains($0) }) else { return [:] }
        var statement: OpaquePointer?
        let sql = "SELECT \(simplified ? "hans" : "hant"),weight FROM lexicon WHERE phonetic GLOB ? AND length(hant)=\(keys.count) ORDER BY weight DESC LIMIT 32"
        guard sqlite3_prepare_v2(evidence, sql, -1, &statement, nil) == SQLITE_OK else { return [:] }
        defer { sqlite3_finalize(statement) }
        // phonetic is the key codes with a tone after each syllable: su3cl3 for 你好.
        _ = sqlite3_bind_text(statement, 1, keys.map { "\($0)*[ 6347]" }.joined(), -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        var result: [String: Int] = [:]
        while sqlite3_step(statement) == SQLITE_ROW {
            let word = String(cString: sqlite3_column_text(statement, 0))
            result[word] = max(result[word] ?? 0, Int(sqlite3_column_int64(statement, 1)) / 8)
        }
        return result
    }
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
            let expected = mode == .pinyin ? raw.lowercased() : raw
            if index == 0, let key, (backend.key(key, modifiers: 0) || key == 0xff08),
               (backend as? NativeBackend)?.currentInput == expected {
            } else if index == 1, mode == .pinyin, raw.count <= 64, let key, key != 0xff08, backend.key(key, modifiers: 0) {
            } else {
                backend.clear()
                if index == 0, !segmentContext.isEmpty { (backend as? NativeBackend)?.setSegmentContext(segmentContext) }
                if index == 0 || (mode == .pinyin && raw.count <= 64) {
                    for scalar in raw.unicodeScalars { _ = backend.key(Int32(scalar.value), modifiers: 0) }
                }
            }
            snapshots.append(backend.snapshot())
        }
        let pinyinFirst = mode == .pinyin
        // iOS Zhuyin keys show only bopomofo, so their key codes are never
        // English there (bpckbp is ㄖㄣㄏㄜㄖㄣ). A Mac keyboard has the letters
        // on its keys, and Zhuyin users type English words with them.
        #if os(iOS)
        let englishKeys = pinyinFirst
        #else
        let englishKeys = true
        #endif
        let hasTones = pinyinFirst && raw.contains { "12345".contains($0) }
        let primary = 0
        var pools = engines.map { $0.candidates(limit: 256) }
        for index in pools.indices where !snapshots[index].commit.isEmpty || snapshots[index].input.isEmpty { pools[index] = [] }
        let unmatchedPinyin = pinyinFirst && snapshots[primary].input != raw.lowercased()
        if unmatchedPinyin { pools[primary] = [] }
        let nativeMetadata = Dictionary(uniqueKeysWithValues: pools[0].compactMap { item in
            item.metadata.map { (item.index, $0) }
        })
        choices = []
        func append(_ text: String, source: Int, index: Int) {
            var metadata = source == 0 ? nativeMetadata[index] : nil
            if let offset = metadata?.remainingOffsetBytes, raw.utf8.allSatisfy({ $0 < 128 }), offset <= raw.utf8.count {
                metadata?.remainingInput = String(decoding: raw.utf8.dropFirst(offset), as: UTF8.self)
            }
            guard !text.isEmpty, !choices.contains(where: {
                guard $0.text == text else { return false }
                let previousOffset = $0.source == 0 ? $0.metadata?.remainingOffsetBytes : ($0.source == -6 ? $0.index : raw.utf8.count)
                let newOffset = source == 0 ? metadata?.remainingOffsetBytes : (source == -6 ? index : raw.utf8.count)
                guard let previousOffset, let newOffset else { return $0.source == source && $0.index == index }
                return previousOffset == newOffset
            }) else { return }
            choices.append(Choice(text: text, source: source, index: index, metadata: metadata))
        }
        var chinese = pools[primary]
        var dictionary = unmatchedPinyin ? [:] : WordEvidence.shared.dictionary(input: raw, mode: mode, simplified: simplified)
        if !pinyinFirst {
            dictionary.merge(Self.zhuyinAbbreviations(raw, simplified: simplified)) { exact, _ in exact }
        }
        let knownChinese = dictionary
        exactChinese = Set(dictionary.keys)
        let nativeTexts = Set(chinese.map(\.text))
        let additions = dictionary.keys.filter { !nativeTexts.contains($0) }.sorted { dictionary[$0] == dictionary[$1] ? $0 < $1 : dictionary[$0, default: 0] > dictionary[$1, default: 0] }
        if pinyinFirst {
            chinese = chinese.filter { knownChinese[$0.text] != nil } + chinese.filter { knownChinese[$0.text] == nil }
        }
        // Japanese words from mimo_jp.bin, already gated for pinyin mode by the compile step.
        let japaneseWords = pinyinFirst ? JapaneseLexicon.words(raw, mode: JapaneseLexicon.pinyinMode, limit: 5) : []
        var knownJapanese: [String: Int] = [:]
        lateJapanese = []
        let chineseTexts = nativeTexts.union(dictionary.keys)
        // The kana strings of the reading are offered anyway, in their own place.
        let sideReading = snapshots[1].preedit.replacingOccurrences(of: " ", with: "")
        let kanaTexts: Set<String> = [sideReading, sideReading.applyingTransform(.hiraganaToKatakana, reverse: false) ?? sideReading]
        for word in japaneseWords where knownJapanese[word.text] == nil {
            let reviewed = word.flags & JapaneseLexicon.reviewedFlag != 0
            // A word of the large list with the text of a Chinese candidate adds nothing; the Chinese one stays.
            if !reviewed && (chineseTexts.contains(word.text) || kanaTexts.contains(word.text)) { continue }
            knownJapanese[word.text] = (reviewed ? 20000 : 0) + word.weight
            if !reviewed && word.kind == JapaneseLexicon.kanjiAliasKind && !dictionary.isEmpty { lateJapanese.insert(word.text) }
        }
        japaneseRank = knownJapanese
        // Words left out above must not come back through the side engine's list.
        let droppedJapanese = Set(japaneseWords.map(\.text)).subtracting(knownJapanese.keys)
        let japanese = pools[1].filter { knownJapanese[$0.text] != nil && !lateJapanese.contains($0.text) }
            .sorted { (knownJapanese[$0.text] ?? 0) > (knownJapanese[$1.text] ?? 0) }
        var required: String?
        // Same evidence as the old SQLite query: a reviewed reading, apostrophes, tones and nn read loosely.
        let evidence = raw.lowercased().replacingOccurrences(of: "ü", with: "v")
            .replacingOccurrences(of: "[0-5 '\u{2019}]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "nn(?=[bcdfghjklmpqrstvwxz]|$)", with: "n", options: .regularExpression)
        let literal = unmatchedPinyin || (englishKeys && !hasTones && EnglishEvidence.prefers(raw, context: englishContext,
            chinese: pinyinFirst && !dictionary.isEmpty,
            japanese: pinyinFirst && JapaneseLexicon.reviewedEvidence.contains(evidence)))
        let mixed = pinyinFirst && !hasTones ? mixedCandidate(raw) : nil
        // A Japanese word for the whole input wins over one for its start
        // (arigatou is ありがとう, not ありがとう with u left over).
        let mixedPrefix = pinyinFirst && !hasTones && knownJapanese.isEmpty
            ? (engines[0] as? NativeBackend)?.mixedPinyinPrefix(raw.lowercased(), simplified: simplified) : nil
        if literal { append(raw, source: -2, index: 0) }
        let script = simplified ? "s:" : "t:"
        let memoryKey = (pinyinFirst ? "py:" : "") + script + (pinyinFirst ? raw.lowercased() : raw)
        let personalCandidates = ChoiceMemory.shared.personalCandidates(for: memoryKey)
        for text in personalCandidates {
            append(text, source: -3, index: 0)
            exactChinese.insert(text)
        }
        let supplementFirst = chinese.first.map { dictionary[$0.text] == nil } ?? true
        if supplementFirst { for text in additions.prefix(7) { append(text, source: -3, index: 0) } }
        let chineseSource = primary
        for item in chinese.prefix(2) { append(item.text, source: chineseSource, index: item.index) }
        if let mixed {
            append(mixed, source: -5, index: 0)
            required = mixed
        }
        if let prefix = mixedPrefix {
            append(prefix.text, source: -6, index: prefix.consumed)
            if required == nil && choices.contains(where: { $0.source == -6 && $0.text == prefix.text }) {
                required = prefix.text
            }
        }
        // Japanese in pinyin. A word the Japanese word list knows for this
        // reading stays required, so the collapsed row keeps it with its paw
        // (sushi -> 寿司).
        if let item = japanese.first {
            append(item.text, source: 1, index: item.index)
            if required == nil { required = item.text }
        }
        // The bare kana spelling of the letters (はぅお for haluo) stays a
        // candidate but comes after every Chinese candidate, in the row and
        // in the panel (owner's rule, 2026-11-11). It is never required and
        // never the kept Japanese cell (compactJapanese); tier() below puts it
        // behind all Chinese and Japanese words. Japanese mode is unchanged.
        let reading = snapshots[1].preedit.replacingOccurrences(of: " ", with: "")
        let japaneseReading = pinyinFirst && !hasTones && !reading.isEmpty && reading.unicodeScalars.allSatisfy { (0x3040...0x30ff).contains($0.value) }
        bareKana = japaneseReading ? [reading, reading.applyingTransform(.hiraganaToKatakana, reverse: false) ?? reading]
            .filter { knownJapanese[$0] == nil } : [] // はい for hai is a word, not a bare spelling
        for text in bareKana { append(text, source: -1, index: 0) }
        // Short letters as typed (ok, ai). Zhuyin raw input is key codes (sc for ㄋㄏ), never offered.
        if pinyinFirst && raw.count <= 3 && raw.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) }) {
            append(raw, source: -4, index: 0)
        }
        for item in chinese.dropFirst(2).prefix(1) { append(item.text, source: chineseSource, index: item.index) }
        if !supplementFirst { for text in additions.prefix(7) { append(text, source: -3, index: 0) } }
        for item in chinese.dropFirst(3) { append(item.text, source: chineseSource, index: item.index) }
        if japaneseReading {
            for item in pools[1].dropFirst() where !lateJapanese.contains(item.text) && !droppedJapanese.contains(item.text) {
                append(item.text, source: 1, index: item.index)
            }
        } else {
            for item in japanese.dropFirst() { append(item.text, source: 1, index: item.index) }
        }
        for item in pools[1] where lateJapanese.contains(item.text) { append(item.text, source: 1, index: item.index) }
        state = Snapshot(preedit: pinyinFirst || literal ? raw : snapshots[0].preedit, commit: pendingCommit, input: raw, requiredCandidate: required, literalCandidate: literal, confidentCandidates: dictionary.filter { $0.value >= 2000 }.map(\.key), candidateWeights: dictionary)
        var japaneseTexts = choices.filter { $0.source == 1 }.map(\.text) + bareKana
        if let mixed { japaneseTexts.append(mixed) }
        if let mixedPrefix { japaneseTexts.append(mixedPrefix.text) }
        state.japaneseCandidates = Array(Set(japaneseTexts)).filter { !exactChinese.contains($0) }
        let strongTexts = Set(state.confidentCandidates)
        let remembered = Set(personalCandidates)
        func tier(_ choice: Choice) -> Int {
            if choice.source == -2 { return 0 }
            if remembered.contains(choice.text) && strongTexts.isEmpty { return 1 }
            if strongTexts.contains(choice.text) { return 2 }
            // End of the list in pinyin: Chinese candidates (tier 3, with reviewed Japanese words),
            // then words of the large Japanese list, then late pure-kanji aliases (both tier 4, in
            // that append order), then the bare kana spelling (tier 5), then short letters as
            // typed (tier 6). Emoji and kaomoji are put in before tier 5 further down.
            if choice.source == -4 { return 6 }
            if choice.source == -1 { return bareKana.contains(choice.text) ? 5 : 4 }
            if choice.source == 1, let rank = japaneseRank[choice.text], rank < 20000 { return 4 }
            return 3
        }
        choices = choices.enumerated().sorted {
            let a = tier($0.element), b = tier($1.element)
            return a == b ? $0.offset < $1.offset : a < b
        }.map(\.element)
        if pinyinFirst, !literal, dictionary.isEmpty, personalCandidates.isEmpty,
           let mixed, let native = engines[0] as? NativeBackend,
           chinese.allSatisfy({ $0.text.count == 1 && native.consumedInputBytes($0.index) < raw.utf8.count }),
           let index = choices.firstIndex(where: { $0.source == -5 && $0.text == mixed }) {
            choices.insert(choices.remove(at: index), at: 0)
            state.preferredCompleteMixed = mixed
        }
        // Chinese with an English word for the whole input (這個app很好用) comes before
        // the letters as typed, which stay right behind it.
        if literal, pinyinFirst, let first = chinese.first, let native = engines[0] as? NativeBackend,
           native.consumedInputBytes(first.index) == raw.utf8.count,
           first.text.unicodeScalars.contains(where: { $0.isASCII && $0.properties.isAlphabetic }),
           first.text.unicodeScalars.contains(where: { $0.value >= 0x3400 }),
           let index = choices.firstIndex(where: { $0.source == primary && $0.index == first.index }) {
            choices.insert(choices.remove(at: index), at: 0)
            state.literalCandidate = false // space takes the first candidate, not the letters
        }
        // A reviewed Japanese word or a pinyin-kana mix for the whole input
        // (koohii コーヒー, itsumo いつも, woyaoauni 我要会う你) comes before a
        // Chinese reading made with English pieces (快哦耦合II, IT蘇摩, 我要au你).
        // An English word alone (game, karaoke) stays first.
        if pinyinFirst, !literal, let first = choices.first, first.source == primary,
           first.text.unicodeScalars.contains(where: { $0.isASCII && $0.properties.isAlphabetic }),
           first.text.unicodeScalars.contains(where: { $0.value >= 0x3400 }),
           let index = choices.firstIndex(where: { choice in
               (choice.source == -5 || (choice.source == 1 && (japaneseRank[choice.text] ?? 0) >= 20000))
                   && !choice.text.unicodeScalars.contains(where: { $0.isASCII })
           }) {
            choices.insert(choices.remove(at: index), at: 0)
        }
        // Emoji and kaomoji of the first candidate (開心 😊 (*´▽`*)) follow every word; only
        // the bare kana and the letters as typed come later (source -1).
        symbols = literal ? nil : choices.first.flatMap { Symbols.of($0.text, japanese: state.japaneseCandidates.contains($0.text)) }
        if let symbols {
            let listed = Set(choices.map(\.text))
            choices.insert(contentsOf: symbols.all.filter { !listed.contains($0) }.map { Choice(text: $0, source: -1, index: 0, metadata: nil) },
                           at: choices.firstIndex { tier($0) >= 5 } ?? choices.count)
        }
        // One letter (a, I) is also an English word: the letter as typed stays
        // on the first page, ahead of the bare kana. The one exception to the
        // kana-before-letters order above.
        if raw.count == 1 {
            let rawChoices = choices.filter { $0.source == -4 }
            choices.removeAll { $0.source == -4 }
            choices.insert(contentsOf: rawChoices, at: min(6, choices.count))
        }
        // iOS: the first emoji comes right after the first candidate (你好 👋), as on Apple's
        // keyboard (source -7), and the first kaomoji after the third word (source -8), so
        // the words before it keep their places. Neither is ever first: space and Return
        // keep the word. A single character gets no kaomoji cell: its row is where the
        // other characters of the reading are picked (the kaomoji stay in the panel, and
        // lead the row once the character is typed).
        if emojiCell, let symbols {
            if let emoji = symbols.emoji.first {
                choices.removeAll { $0.text == emoji }
                choices.insert(Choice(text: emoji, source: -7, index: 0, metadata: nil), at: 1)
            }
            if let kaomoji = symbols.kaomoji.first, symbols.word.count > 1 {
                choices.removeAll { $0.text == kaomoji }
                choices.insert(Choice(text: kaomoji, source: -8, index: 0, metadata: nil), at: min(choices.count, symbols.emoji.isEmpty ? 3 : 4))
            }
        }
        if state.preedit.isEmpty { state.preedit = pinyinFirst ? raw : zhuyinText(raw) }
        showPage(0)
    }
}
