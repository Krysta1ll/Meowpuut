import Foundation

enum KeyboardSymbols {
    private static let punctuation: [Character: String] = [
            ",": "，", ".": "。", "?": "？", ";": "；", ":": "：", "!": "！",
            "(": "（", ")": "）", "[": "「", "]": "」", "{": "『", "}": "』",
            "\\": "、", "\"": "“"
    ]
    // Apple's keyboard goes back to letters after an apostrophe (and after
    // space). Chinese sentence marks end a clause, so they go back as well.
    // ASCII . , : stay on the symbol page: 3.14, 12:30 and 1,000 are typed
    // in one go.
    private static let autoReturnMarks: Set<String> = [
        "，", "。", "？", "！", "、", "；", "：", "'"
    ]
    static let quickChineseMarks = ["，", "。", "？", "！", "、", "「", "」"]

    static func text(_ character: Character, chinese: Bool) -> String {
        guard chinese else { return String(character) }
        return punctuation[character] ?? String(character)
    }

    /// `previous` is the character before the cursor when the symbol was
    /// typed. After a digit the page stays, also for a Chinese mark (3、4、5).
    static func shouldAutoReturnToLetters(after symbol: String, previous: Character? = nil) -> Bool {
        if let previous, previous.isNumber { return false }
        return autoReturnMarks.contains(symbol)
    }
}
