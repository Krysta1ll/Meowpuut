import Foundation

/// Words the keyboard never offers by itself: the next-word predictions learned from the person's own
/// choices, and the emoji and kaomoji for a word. The words stay typeable: spelling one out in full still
/// gives it, and nothing here touches the reading-to-word choices the person made (ChoiceMemory.preferred).
///
/// The list is the engine's Resources/Lexicon/blocked-terms.txt, the same file the lexicon build uses to
/// keep these words out of phrases, completions and abbreviations. The app bundles it as never-volunteer.txt,
/// written by `scripts/blocked_terms.py --client` (scripts/build-lexicon.sh): only FNV-1a hashes of the
/// terms, so the app carries no readable list. Sections there:
///   `[zh]`        Simplified terms that match anywhere in a text (Traditional text is converted first)
///   `[zh-whole]`  terms that match only the whole text (=term in the list)
///   `[zh-keep]`   everyday words (whole texts) that hold a term and are not blocked
///   `[ja]`        kanji terms, anywhere; `[ja-whole]` katakana terms, a whole katakana run
///   `[en]`        lower-case whole words; a word that is one of them plus an ending of `endings` too
///   `[emoji-key]` keys that get no emoji
/// `init(text:)` also reads the list itself (blocked-terms.txt sections), for checks with a list of their own.
final class NeverVolunteer {
    /// Replaceable so a check can use a small list of its own.
    nonisolated(unsafe) static var shared = NeverVolunteer(contentsOf: NeverVolunteer.bundledFile())

    private var sets: [String: Set<UInt64>] = [:]
    private var longest = 0
    private var endings: [String] = []

    init(contentsOf url: URL?) {
        guard let url, let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        load(text)
    }

    init(text: String) { load(text) }

    var isEmpty: Bool { sets.values.allSatisfy(\.isEmpty) }

    /// FNV-1a 64 of the UTF-8 bytes (scripts/blocked_terms.py `fnv`).
    static func hash(_ text: String) -> UInt64 {
        var h: UInt64 = 0xCBF2_9CE4_8422_2325
        for byte in text.utf8 {
            h ^= UInt64(byte)
            h = h &* 0x100_0000_01B3
        }
        return h
    }

    private func load(_ text: String) {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: true).map { $0.trimmingCharacters(in: .whitespaces) }
        if lines.contains(where: { $0.hasPrefix("longest ") }) {
            loadHashed(lines)
        } else {
            loadPlain(lines)
        }
    }

    /// never-volunteer.txt: per section one hash (16 hex digits) per line.
    private func loadHashed(_ lines: [String]) {
        var section = ""
        for line in lines where !line.isEmpty && !line.hasPrefix("#") {
            if line.hasPrefix("longest ") {
                longest = Int(line.dropFirst(8)) ?? 0
            } else if line.hasPrefix("endings ") {
                endings = line.dropFirst(8).split(separator: " ").map(String.init)
            } else if line.hasPrefix("["), line.hasSuffix("]") {
                section = String(line.dropFirst().dropLast())
            } else if let value = UInt64(line, radix: 16) {
                sets[section, default: []].insert(value)
            }
        }
    }

    /// The list itself (blocked-terms.txt): `[zh]` (a term written =term is whole-word), `[zh-keep]`,
    /// `[zh-drop]` (counted as `[zh]`), `[ja]`, `[en]`, `[emoji-key]`. A file without sections is all `[zh]`.
    private func loadPlain(_ lines: [String]) {
        var section = "zh"
        endings = ["s", "ic", "ist", "ists", "ism", "isms", "ite", "ites"]
        func add(_ name: String, _ term: String) {
            sets[name, default: []].insert(Self.hash(term))
            if name == "zh" || name == "ja" { longest = max(longest, term.unicodeScalars.count) }
        }
        for line in lines where !line.isEmpty && !line.hasPrefix("#") {
            let words = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            if let first = words.first, first.hasPrefix("["), let close = first.firstIndex(of: "]") {
                // What follows the section name on its line is a description.
                section = String(first[first.index(after: first.startIndex)..<close]).lowercased()
                continue
            }
            for word in words {
                switch section {
                case "zh", "zh-drop":
                    if word.hasPrefix("=") { add("zh-whole", String(word.dropFirst())) } else { add("zh", word) }
                case "zh-keep":
                    add("zh-keep", word)
                case "ja":
                    add(Self.isKatakana(word) ? "ja-whole" : "ja", word)
                case "en":
                    if !word.contains("_") { add("en", word.lowercased().replacingOccurrences(of: "-", with: "")) }
                case "emoji-key":
                    add("emoji-key", word)
                default:
                    break
                }
            }
        }
    }

    private static func bundledFile() -> URL? {
        if let url = Bundle.main.url(forResource: "never-volunteer", withExtension: "txt") { return url }
        // The checks that run outside an app bundle (--smoke, unit tests) read the lexicon folder they were given.
        if let folder = ProcessInfo.processInfo.environment["MEOWPUUT_LEXICON_DIR"] {
            let url = URL(fileURLWithPath: folder).appendingPathComponent("never-volunteer.txt")
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        return nil
    }

    private static func isKatakana(_ word: String) -> Bool {
        !word.isEmpty && word.unicodeScalars.allSatisfy { (0x30A0...0x30FF).contains($0.value) }
    }

    private static func isKatakanaScalar(_ scalar: Unicode.Scalar) -> Bool {
        (0x30A0...0x30FF).contains(scalar.value)
    }

    private static func hasChinese(_ text: String) -> Bool {
        text.unicodeScalars.contains { (0x3400...0x9fff).contains($0.value) || (0x20000...0x323af).contains($0.value) }
    }

    private func has(_ section: String, _ text: String) -> Bool {
        sets[section]?.contains(Self.hash(text)) ?? false
    }

    /// Whether a part of `text` (up to `longest` scalars) is in `section`.
    private func holds(_ section: String, in text: String) -> Bool {
        guard let set = sets[section], !set.isEmpty else { return false }
        let scalars = Array(text.unicodeScalars)
        for start in scalars.indices {
            var part = String.UnicodeScalarView()
            for scalar in scalars[start..<min(scalars.count, start + max(longest, 1))] {
                part.append(scalar)
                if set.contains(Self.hash(String(part))) { return true }
            }
        }
        return false
    }

    /// Whether `text` holds a listed word and so must not be offered by the keyboard on its own.
    func blocks(_ text: String) -> Bool {
        if isEmpty || text.isEmpty { return false }
        if Self.hasChinese(text) {
            let simplified = text.applyingTransform(StringTransform("Traditional-Simplified"), reverse: false) ?? text
            // An everyday word that holds a term (印度支那) is not blocked, in either script.
            if !has("zh-keep", text) && !has("zh-keep", simplified) {
                for form in Set([text, simplified]) where has("zh-whole", form) || holds("zh", in: form) { return true }
            }
        }
        if sets["ja-whole"]?.isEmpty == false, text.unicodeScalars.contains(where: Self.isKatakanaScalar) {
            let scalars = Array(text.unicodeScalars)
            var runStart: Int?
            for index in 0...scalars.count {
                let inRun = index < scalars.count && Self.isKatakanaScalar(scalars[index])
                if inRun, runStart == nil { runStart = index }
                if !inRun, let start = runStart {
                    if has("ja-whole", String(String.UnicodeScalarView(scalars[start..<index]))) { return true }
                    runStart = nil
                }
            }
        }
        if sets["en"]?.isEmpty == false {
            let lowered = text.lowercased()
            if lowered.unicodeScalars.contains(where: { $0.isASCII && CharacterSet.letters.contains($0) }) {
                for word in lowered.split(whereSeparator: { !$0.isLetter && $0 != "-" }) {
                    let plain = word.replacingOccurrences(of: "-", with: "")
                    if has("en", plain) { return true }
                    for ending in endings where plain.hasSuffix(ending) && plain.count > ending.count {
                        if has("en", String(plain.dropLast(ending.count))) { return true }
                    }
                }
            }
        }
        // Japanese terms with kanji, or in mixed spelling.
        return holds("ja", in: text)
    }

    /// Whether the word is a key that gets no emoji or kaomoji.
    func blocksSymbols(for word: String) -> Bool {
        if isEmpty { return false }
        if has("emoji-key", word) { return true }
        let simplified = word.applyingTransform(StringTransform("Traditional-Simplified"), reverse: false) ?? word
        return has("emoji-key", simplified) || blocks(word)
    }

    /// An emoji that stands for a state: regional-indicator flags and the crossed Japanese flags.
    static func isNeverSymbol(_ symbol: String) -> Bool {
        symbol.unicodeScalars.contains { (0x1F1E6...0x1F1FF).contains($0.value) || $0.value == 0x1F38C || $0.value == 0x262D }
    }
}
