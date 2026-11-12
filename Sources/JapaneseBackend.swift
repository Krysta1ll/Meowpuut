import Foundation

// Standard romaji spelling rules, implemented here; no third-party conversion service.
enum Romaji {
    private static let kana: [String: String] = {
        var result: [String: String] = [:]
        let rows = [
            "a i u e o|あいうえお", "ka ki ku ke ko|かきくけこ", "sa shi su se so|さしすせそ",
            "ta chi tsu te to|たちつてと", "na ni nu ne no|なにぬねの", "ha hi fu he ho|はひふへほ",
            "ma mi mu me mo|まみむめも", "ya yu yo|やゆよ", "ra ri ru re ro|らりるれろ", "wa wo|わを",
            "ga gi gu ge go|がぎぐげご", "za ji zu ze zo|ざじずぜぞ", "da di du de do|だぢづでど",
            "ba bi bu be bo|ばびぶべぼ", "pa pi pu pe po|ぱぴぷぺぽ", "va vi vu ve vo|ゔぁ,ゔぃ,ゔ,ゔぇ,ゔぉ",
            "xa xi xu xe xo|ぁぃぅぇぉ", "xya xyu xyo|ゃゅょ", "xtsu|っ", "xwa|ゎ"
        ]
        for row in rows {
            let parts = row.components(separatedBy: "|")
            let values = parts[1].contains(",") ? parts[1].components(separatedBy: ",") : parts[1].map(String.init)
            for (key, value) in zip(parts[0].split(separator: " "), values) { result[String(key)] = value }
        }
        for (prefix, base) in [("ky", "き"), ("gy", "ぎ"), ("sh", "し"), ("ch", "ち"), ("ny", "に"), ("hy", "ひ"), ("by", "び"), ("py", "ぴ"), ("my", "み"), ("ry", "り"), ("j", "じ")] {
            for (vowel, small) in [("a", "ゃ"), ("u", "ゅ"), ("o", "ょ"), ("e", "ぇ")] { result[prefix + vowel] = base + small }
        }
        for (alias, standard) in ["si":"shi", "ti":"chi", "tu":"tsu", "hu":"fu", "zi":"ji", "sya":"sha", "syu":"shu", "syo":"sho", "tya":"cha", "tyu":"chu", "tyo":"cho", "cya":"cha", "cyu":"chu", "cyo":"cho", "zya":"ja", "zyu":"ju", "zyo":"jo", "jya":"ja", "jyu":"ju", "jyo":"jo", "xtu":"xtsu"] { result[alias] = result[standard] }
        for (key, value) in Array(result) where key.hasPrefix("x") { result["l" + key.dropFirst()] = value }
        for (key, value) in ["fa":"ふぁ", "fi":"ふぃ", "fe":"ふぇ", "fo":"ふぉ", "wi":"うぃ", "we":"うぇ", "who":"うぉ", "she":"しぇ", "che":"ちぇ", "thi":"てぃ", "dhi":"でぃ", "twu":"とぅ", "dwu":"どぅ", "fyu":"ふゅ", "ye":"いぇ"] { result[key] = value }
        return result
    }()

    static func reading(_ raw: String) -> String {
        let input = Array(raw.lowercased())
        var result = "", position = 0
        while position < input.count {
            let key = input[position]
            let next: Character? = position + 1 < input.count ? input[position + 1] : nil
            if key == "-" { result += "ー"; position += 1; continue }
            if key == "'" { position += 1; continue }
            if key == "n" {
                if next == "'" { result += "ん"; position += 2; continue }
                if next == nil { result += "ん"; position += 1; continue }
                if next == "n" {
                    result += "ん"
                    position += position + 2 == input.count ? 2 : 1
                    continue
                }
                if let next, !"aiueoy".contains(next) { result += "ん"; position += 1; continue }
            }
            if key == next, !"aiueon".contains(key) { result += "っ"; position += 1; continue }
            var matched = false
            for count in stride(from: min(4, input.count - position), through: 1, by: -1) {
                let token = String(input[position..<(position + count)])
                if let value = kana[token] { result += value; position += count; matched = true; break }
            }
            if !matched { result.append(key); position += 1 }
        }
        return result
    }
}

// The Japanese word list mimo_jp.bin (format MIMO-JP-1), read in place by the core.
// One lookup gives the words stored for exactly that input (crates/meowpuut-core/src/japanese.rs).
enum JapaneseLexicon {
    struct Word { let text: String; let weight: Int; let flags: UInt32; let kind: UInt32 }
    static let pinyinMode = Int32(MPN_JP_MODE_PINYIN), japaneseMode = Int32(MPN_JP_MODE_JAPANESE)
    static let reviewedFlag = UInt32(MPN_JP_FLAG_REVIEWED)
    static let kanjiAliasKind = UInt32(MPN_JP_KIND_ALIAS_KANJI)

    // The mapping stays for the life of the process; the core reads it in place.
    private static let data: NSData? = Bundle.main.url(forResource: "mimo_jp", withExtension: "bin")
        .flatMap { try? NSData(contentsOf: $0, options: .alwaysMapped) }
    static let shared: OpaquePointer? = {
        guard let data, let handle = mpn_japanese_open(data.bytes, data.length, 0) else { return nil }
        // Make the pages resident off the main thread: madvise is only a hint, the reads are not.
        DispatchQueue.global(qos: .utility).async {
            let start = UnsafeMutableRawPointer(mutating: data.bytes)
            _ = madvise(start, data.length, MADV_WILLNEED)
            var sum: UInt8 = 0
            for offset in stride(from: 0, to: data.length, by: 16_384) { sum &+= data.bytes.load(fromByteOffset: offset, as: UInt8.self) }
            _ = sum
        }
        return handle
    }()
    // One query, used on the main thread only, like the session.
    private static let query = mpn_japanese_query_create()

    static func words(_ input: String, mode: Int32, limit: Int) -> [Word] {
        guard let handle = shared, let query, !input.isEmpty else { return [] }
        let count = mpn_japanese_lookup(query, handle, input, mode, limit)
        return (0..<count).compactMap { index in
            guard let text = mpn_japanese_text(query, index) else { return nil }
            var info = MPNJapaneseInfo()
            guard mpn_japanese_info(query, index, &info, MemoryLayout<MPNJapaneseInfo>.size) != 0 else { return nil }
            return Word(text: String(cString: text), weight: Int(info.weight), flags: info.flags, kind: info.kind)
        }
    }

    // Reviewed section: the first word of every reading of the small reviewed list (383 today).
    private static let reviewedRows: [(reading: String, text: String, weight: Int, pool: Bool)] = {
        guard let handle = shared else { return [] }
        var rows: [(reading: String, text: String, weight: Int, pool: Bool)] = []
        var reading = [CChar](repeating: 0, count: 97), text = [CChar](repeating: 0, count: 129)
        for index in 0..<mpn_japanese_reviewed_count(handle) {
            var weight: Int32 = 0, flags: UInt32 = 0
            guard mpn_japanese_reviewed_get(handle, index, &reading, reading.count, &text, text.count, &weight, &flags) != 0 else { continue }
            rows.append((String(cString: reading), String(cString: text), Int(weight), flags & UInt32(MPN_JP_REVIEWED_POOL) != 0))
        }
        return rows
    }()
    static let reviewed: [String: (text: String, weight: Int)] =
        Dictionary(reviewedRows.map { ($0.reading, (text: $0.text, weight: $0.weight)) }, uniquingKeysWith: { first, _ in first })
    // Rows for mixed sentences, as the core accepts them (mpn_add_japanese_word).
    static let mixedWords: [(reading: String, text: String, weight: Int)] =
        reviewedRows.filter(\.pool).map { (reading: $0.reading, text: $0.text, weight: $0.weight) }.sorted { $0.reading < $1.reading }
    // Reviewed readings spelled the way the old SQLite rows were stored (apostrophes removed).
    static let reviewedEvidence = Set(reviewedRows.map { $0.reading.replacingOccurrences(of: "'", with: "") })
}

final class JapaneseBackend: InputBackend {
    // Return only independently recorded complete words; kana guesses do not
    // provide enough evidence to split a mixed-language composition.
    static func knownWord(_ reading: String) -> (text: String, weight: Int)? { JapaneseLexicon.reviewed[reading] }
    static var mixedWords: [(reading: String, text: String, weight: Int)] { JapaneseLexicon.mixedWords }
    // Set by ChineseWithJapaneseBackend: the side engine of pinyin mode asks for gated words only.
    var pinyinSide = false
    /// iOS: an emoji cell after the first candidate and a kaomoji cell after the third,
    /// as in Chinese typing. Without it the symbols only end the list.
    var symbolCells = false
    private var symbols: Symbols.Found?
    private var raw = ""
    private var state = Snapshot()
    private var choices: [String] = []
    // A bundle without mimo_jp.bin fails here, as a bundle without the old JSON did.
    init() throws { guard JapaneseLexicon.shared != nil else { throw CocoaError(.fileReadCorruptFile) } }
    func snapshot() -> Snapshot { defer { state.commit = "" }; return state }
    func candidates(limit: Int) -> [IndexedCandidate] {
        let marks = Set(symbols?.all ?? [])
        return choices.prefix(limit).enumerated().map {
            IndexedCandidate(index: $0.offset, text: $0.element, source: $0.offset > 0 && marks.contains($0.element) ? -1 : nil)
        }
    }
    func setSimplified(_ simplified: Bool) {} // Japanese spelling does not follow Chinese script settings.
    func clear() { raw = ""; state = Snapshot(); choices = []; symbols = nil }
    func commit() { if !choices.isEmpty { _ = choose(state.page * 7) } else { let text = raw; clear(); state.commit = text } }
    func choose(_ index: Int) -> Bool {
        guard choices.indices.contains(index) else { return false }
        let text = choices[index]
        if index > 0, let symbols, symbols.all.contains(text) { Symbols.remember(text, for: symbols.word) }
        clear(); state.commit = text; return true
    }
    private func page(_ requested: Int) {
        state.page = max(0, min(requested, max(0, (choices.count - 1) / 7)))
        state.candidates = Array(choices.dropFirst(state.page * 7).prefix(7))
        state.lastPage = (state.page + 1) * 7 >= choices.count
        state.highlighted = 0
    }
    private func refresh() {
        guard !raw.isEmpty else { clear(); return }
        let reading = Romaji.reading(raw)
        choices = JapaneseLexicon.words(raw, mode: pinyinSide ? JapaneseLexicon.pinyinMode : JapaneseLexicon.japaneseMode,
                                        limit: pinyinSide ? 5 : 64).map(\.text)
        let katakana = reading.applyingTransform(.hiraganaToKatakana, reverse: false) ?? reading
        for text in [reading, katakana] where !choices.contains(text) { choices.append(text) }
        if raw == raw.uppercased(), raw != raw.lowercased() { choices.removeAll(where: { $0 == katakana }); choices.insert(katakana, at: 0) }
        // Emoji and kaomoji of the first word end the list (すし 🍣). Never first: space and
        // Return keep the word. The cells follow the rules of Chinese typing, and wait for
        // the third letter: one or two letters (me 目, te 手) are mostly the start of a
        // longer word, and a cell coming and going there would move the candidates.
        symbols = pinyinSide ? nil : choices.first.flatMap { Symbols.of($0, japanese: true) }
        if let symbols {
            let cells = symbolCells && raw.count >= 3
            var rest = symbols.all.filter { !choices.contains($0) }
            if cells, symbols.word.count > 1, let kaomoji = symbols.kaomoji.first, let at = rest.firstIndex(of: kaomoji) {
                rest.remove(at: at); choices.insert(kaomoji, at: min(choices.count, 3))
            }
            if cells, let emoji = symbols.emoji.first, let at = rest.firstIndex(of: emoji) {
                rest.remove(at: at); choices.insert(emoji, at: 1)
            }
            choices += rest
        }
        // Japanese mode: nothing is flagged, so the collapsed row shows every word, not one.
        state = Snapshot(preedit: reading, input: raw, japaneseCandidates: pinyinSide ? choices : [])
        page(0)
    }
    func key(_ key: Int32, modifiers: Int32) -> Bool {
        if key == 0xff1b { let handled = !raw.isEmpty; clear(); return handled }
        if key == 0xff08 { guard !raw.isEmpty else { return false }; raw.removeLast(); refresh(); return true }
        if key == 0xff55 || key == 0xff56 { guard !raw.isEmpty else { return false }; page(state.page + (key == 0xff55 ? -1 : 1)); return true }
        if key == 0xff0d || key == 32 { guard !raw.isEmpty else { return false }; commit(); return true }
        guard (32...126).contains(key), let scalar = UnicodeScalar(UInt32(key)) else { return false }
        let character = Character(scalar)
        if "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'-".contains(character) {
            // At 80 letters the conversion so far is typed and a new reading starts.
            var pending = ""
            if raw.count >= 80 { commit(); pending = state.commit }
            raw.append(character); refresh(); state.commit = pending; return true
        }
        if !raw.isEmpty {
            commit()
            state.commit += character == "," ? "、" : character == "." ? "。" : String(character)
            return true
        }
        return false
    }
}
