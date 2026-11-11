import Foundation

// Shift, Caps Lock and the capital at the start of a sentence (docs/DESIGN.md §6).

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (CapitalizationStateTests.swift:\(line))")
        exit(1)
    }
}

@main
struct CapitalizationStateTests {
    static func main() {
        expect(CapitalizationState.doubleTapInterval == 0.3, "two taps within 0.3 s lock")

        var state = CapitalizationState()
        expect(!state.isActive && !state.consumeLetter(english: true), "starts in lowercase")
        state.tapShift(at: 1)
        expect(state.mode == .nextLetter && state.isActive && state.consumeLetter(english: true), "one tap capitalises one letter")
        expect(state.mode == .lowercase && !state.consumeLetter(english: true), "then back to lowercase")

        state.tapShift(at: 2)
        state.tapShift(at: 2.25)
        expect(state.mode == .locked, "a quick second tap locks")
        expect(state.consumeLetter(english: true) && state.consumeLetter(english: true), "Caps Lock stays on while typing")
        state.tapShift(at: 3)
        expect(state.mode == .lowercase, "a tap turns Caps Lock off")
        state.tapShift(at: 3.1)
        expect(state.mode == .nextLetter, "a tap right after unlocking is a single Shift, not a lock")

        state = CapitalizationState()
        state.tapShift(at: 4)
        state.tapShift(at: 5)
        expect(state.mode == .lowercase, "slow taps turn Shift on and off")
        state.tapShift(at: 5.31)
        expect(state.mode == .nextLetter, "just over 0.3 s is not a double tap")
        state.tapShift(at: 5.6)
        expect(state.mode == .locked, "within 0.3 s it is")
        state.tapShift(at: 9)

        // Readings (pinyin, romaji): the caller decides what a capital
        // means; a single Shift is still used up by the letter.
        state.tapShift(at: 10)
        expect(state.isActive && !state.consumeLetter(english: false), "consumeLetter reports capitals for English only")
        expect(state.mode == .lowercase, "the Shift is used up")
        state.tapShift(at: 10.1)
        expect(state.mode == .nextLetter, "typing breaks the double-tap sequence")
        state.tapShift(at: 10.2)
        expect(state.mode == .locked && !state.consumeLetter(english: false) && state.mode == .locked, "Caps Lock survives letters in a reading")
        expect(state.consumeLetter(english: true), "and capitalises English")
        state.tapShift(at: 11)
        expect(state.mode == .lowercase, "unlocked")

        // The capital at the start of a sentence.
        state = CapitalizationState()
        state.applyAutoCapitalization(true)
        expect(state.mode == .nextLetter, "a sentence starts with a capital")
        state.applyAutoCapitalization(false)
        expect(state.mode == .lowercase, "it goes away when the context changes")
        state.applyAutoCapitalization(true)
        expect(state.consumeLetter(english: true) && state.mode == .lowercase, "it applies to one letter")

        // Turning it off by hand: it is not offered again for the same place.
        state = CapitalizationState()
        state.applyAutoCapitalization(true)
        state.tapShift(at: 20)
        expect(state.mode == .lowercase, "a tap declines the automatic capital")
        state.applyAutoCapitalization(true)
        expect(state.mode == .lowercase, "it stays declined")
        expect(!state.consumeLetter(english: true), "the letter is lowercase")
        state.applyAutoCapitalization(true)
        expect(state.mode == .nextLetter, "the next sentence gets its capital again")

        // Double tap at a sentence start locks, as on the system keyboard.
        state = CapitalizationState()
        state.applyAutoCapitalization(true)
        state.tapShift(at: 30)
        state.tapShift(at: 30.2)
        expect(state.mode == .locked, "double tap from the automatic capital locks")
        state.applyAutoCapitalization(false)
        state.applyAutoCapitalization(true)
        expect(state.mode == .locked, "context changes leave Caps Lock alone")

        // A Shift the user tapped is not cancelled by the context.
        state = CapitalizationState()
        state.tapShift(at: 40)
        state.applyAutoCapitalization(false)
        expect(state.mode == .nextLetter, "a manual Shift survives a context update")

        // When a sentence starts.
        func auto(_ type: Int, _ context: String?, english: Bool = true) -> Bool {
            CapitalizationState.shouldAutoCapitalize(rawType: type, contextBeforeInput: context, english: english)
        }
        expect(auto(2, nil) && auto(2, "") && auto(2, "Hello. ") && auto(2, "Hi!\n") && auto(2, "好。 "), "sentence starts")
        expect(!auto(2, "Hello ") && !auto(2, "Hello.") && !auto(2, "Hello, "), "not in the middle of a sentence")
        expect(auto(1, "Hello ") && !auto(1, "Hell") && auto(3, "anything"), "words and all-characters modes")
        expect(!auto(0, nil) && !auto(2, nil, english: false) && !auto(3, "x", english: false), "never for Chinese or when the field turns it off")
        print("PASS: single Shift, double-tap lock, unlock, sentence capitals and declining them")
    }
}
