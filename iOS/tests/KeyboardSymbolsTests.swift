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
        // Back to letters after a Chinese sentence mark or an apostrophe.
        for mark in ["，", "。", "？", "！", "、", "；", "：", "'"] {
            precondition(KeyboardSymbols.shouldAutoReturnToLetters(after: mark), "\(mark) returns to letters")
            precondition(KeyboardSymbols.shouldAutoReturnToLetters(after: mark, previous: "好"))
        }
        // 3.14, 12:30, 1,000 and "?!" are typed without leaving the symbol page.
        for mark in [".", ",", ":", ";", "?", "!", "\"", "1", "-", "@"] {
            precondition(!KeyboardSymbols.shouldAutoReturnToLetters(after: mark), "\(mark) stays on the symbol page")
        }
        // After a digit nothing leaves the page (3、4、5 and it's 5's).
        precondition(!KeyboardSymbols.shouldAutoReturnToLetters(after: "、", previous: "3"))
        precondition(!KeyboardSymbols.shouldAutoReturnToLetters(after: "'", previous: "5"))
        print("PASS: 中文標點、英語符號、符號頁何時回到字母")
    }
}
