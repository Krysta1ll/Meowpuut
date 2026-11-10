import Foundation

@main
struct KeyboardSymbolsTests {
    static func main() {
        for (input, expected) in [(Character(","), "，"), (".", "。"), ("?", "？"), (":", "："), (";", "；"), ("[", "「"), ("}", "』")] {
            precondition(KeyboardSymbols.text(input, chinese: true) == expected)
            precondition(KeyboardSymbols.text(input, chinese: false) == String(input))
        }
        for input in [Character("1"), "/", "€"] {
            precondition(KeyboardSymbols.text(input, chinese: true) == String(input))
        }
        precondition(KeyboardSymbols.shouldAutoReturnToLetters(after: "，"))
        precondition(KeyboardSymbols.shouldAutoReturnToLetters(after: "."))
        precondition(!KeyboardSymbols.shouldAutoReturnToLetters(after: "1"))
        print("PASS: 中文標點、英語符號、自動返回與數字符號")
    }
}
