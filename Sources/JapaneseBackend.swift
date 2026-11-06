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

final class JapaneseBackend: InputBackend {
    private struct Entry: Decodable { let text: String; let weight: Int }
    private static let dictionary: [String: [Entry]]? = {
        guard let url = Bundle.main.url(forResource: "native-japanese", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode([String: [Entry]].self, from: data)
    }()
    private var raw = ""
    private var state = Snapshot()
    private var choices: [String] = []
    init() throws { guard Self.dictionary != nil else { throw CocoaError(.fileReadCorruptFile) } }
    func snapshot() -> Snapshot { defer { state.commit = "" }; return state }
    func candidates(limit: Int) -> [IndexedCandidate] { choices.prefix(limit).enumerated().map { IndexedCandidate(index: $0.offset, text: $0.element) } }
    func setSimplified(_ simplified: Bool) {} // Japanese spelling does not follow Chinese script settings.
    func clear() { raw = ""; state = Snapshot(); choices = [] }
    func commit() { if !choices.isEmpty { _ = choose(state.page * 7) } else { let text = raw; clear(); state.commit = text } }
    func choose(_ index: Int) -> Bool {
        guard choices.indices.contains(index) else { return false }
        let text = choices[index]; clear(); state.commit = text; return true
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
        choices = (Self.dictionary?[raw.lowercased()] ?? []).map(\.text)
        let katakana = reading.applyingTransform(.hiraganaToKatakana, reverse: false) ?? reading
        for text in [reading, katakana] where !choices.contains(text) { choices.append(text) }
        if raw == raw.uppercased(), raw != raw.lowercased() { choices.removeAll(where: { $0 == katakana }); choices.insert(katakana, at: 0) }
        state = Snapshot(preedit: reading, input: raw, japaneseCandidates: choices)
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
            guard raw.count < 80 else { return true }
            raw.append(character); refresh(); return true
        }
        if !raw.isEmpty {
            commit()
            state.commit += character == "," ? "、" : character == "." ? "。" : String(character)
            return true
        }
        return false
    }
}
