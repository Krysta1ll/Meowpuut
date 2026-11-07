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
        print("PASS: 中文標點、英語符號與數字符號")
    }
}
