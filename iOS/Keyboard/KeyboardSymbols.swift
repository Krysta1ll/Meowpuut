import Foundation

enum KeyboardSymbols {
    private static let punctuation: [Character: String] = [
            ",": "，", ".": "。", "?": "？", ";": "；", ":": "：", "!": "！",
            "(": "（", ")": "）", "[": "「", "]": "」", "{": "『", "}": "』",
            "\\": "、", "\"": "“"
    ]
    private static let autoReturnMarks: Set<String> = [
        "，", "。", "？", "！", "、", "；", "：",
        ",", ".", "?", "!", ";", ":", "'", "\""
    ]
    static let quickChineseMarks = ["，", "。", "？", "！", "、", "「", "」"]

    static func text(_ character: Character, chinese: Bool) -> String {
        guard chinese else { return String(character) }
        return punctuation[character] ?? String(character)
    }

    static func shouldAutoReturnToLetters(after symbol: String) -> Bool {
        autoReturnMarks.contains(symbol)
    }
}
