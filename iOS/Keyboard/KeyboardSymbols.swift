import Foundation

enum KeyboardSymbols {
    private static let punctuation: [Character: String] = [
            ",": "，", ".": "。", "?": "？", ";": "；", ":": "：", "!": "！",
            "(": "（", ")": "）", "[": "「", "]": "」", "{": "『", "}": "』",
            "\\": "、", "\"": "“"
    ]

    static func text(_ character: Character, chinese: Bool) -> String {
        guard chinese else { return String(character) }
        return punctuation[character] ?? String(character)
    }
}
