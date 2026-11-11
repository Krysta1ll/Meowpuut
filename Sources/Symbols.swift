import Foundation

/// Emoji and kaomoji for words: associations.json, built by
/// scripts/build-emoji-associations.py from Resources/Lexicon/emoji.tsv,
/// kaomoji.tsv and the CLDR emoji annotations. Chinese and Japanese words are
/// looked up apart, because the same characters can mean different things
/// (手紙 is a letter in Japanese).
enum Symbols {
    struct Found {
        var word: String
        var emoji: [String]
        var kaomoji: [String]
        var all: [String] { emoji + kaomoji }
    }

    // {"zh": {word: [[emoji], [kaomoji]]}, "ja": {...}}; the kaomoji list is left out when empty.
    private static let table: [String: [String: [[String]]]] = {
        guard let url = Bundle.main.url(forResource: "associations", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let values = try? JSONDecoder().decode([String: [String: [[String]]]].self, from: data) else { return [:] }
        return values
    }()

    /// The symbols of a word, best first; the ones the user picked for this
    /// word before lead.
    static func of(_ word: String, japanese: Bool = false) -> Found? {
        guard let entry = table[japanese ? "ja" : "zh"]?[word] else { return nil }
        let picked = ChoiceMemory.shared.preferred(for: "sym:" + word)
        func ordered(_ list: [String]) -> [String] {
            picked.isEmpty ? list : picked.filter(list.contains) + list.filter { !picked.contains($0) }
        }
        // A lone emoji whose default is the text glyph (🌧, ❤) gets the selector that asks for the emoji.
        let emoji = entry[0].map { symbol -> String in
            let scalars = symbol.unicodeScalars
            return scalars.count == 1 && !scalars.first!.properties.isEmojiPresentation ? symbol + "\u{FE0F}" : symbol
        }
        return Found(word: word, emoji: ordered(emoji), kaomoji: ordered(entry.count > 1 ? entry[1] : []))
    }

    /// The symbols for text that was just typed: the text itself, then its
    /// endings of two or more characters, longest first (我今天好累 has 好累).
    /// An ending right after 不 or 沒 is skipped: 不想睡 is not 想睡. Japanese
    /// is matched as a whole only; a kana ending is too often part of another
    /// word (たい in 食べたい).
    static func after(_ text: String, japanese: Bool) -> Found? {
        let characters = Array(text.trimmingCharacters(in: .punctuationCharacters.union(.whitespacesAndNewlines)))
        guard !characters.isEmpty else { return nil }
        let whole = String(characters)
        if japanese { return of(whole, japanese: true) }
        if let found = of(whole) { return found }
        for length in stride(from: characters.count - 1, through: 2, by: -1) {
            let start = characters.count - length
            if characters[max(0, start - 3)..<start].contains(where: { "不沒没".contains($0) }) { continue }
            if let found = of(String(characters[start...])) { return found }
        }
        return of(whole, japanese: true)
    }

    /// The user picked `symbol` for `word`: it comes first for that word from now on.
    static func remember(_ symbol: String, for word: String) {
        ChoiceMemory.shared.remember([(reading: "sym:" + word, text: symbol)])
    }
}
