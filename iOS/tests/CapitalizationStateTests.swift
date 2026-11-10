import Foundation

@main
struct CapitalizationStateTests {
    static func main() {
        var state = CapitalizationState()
        precondition(!state.consumeLetter(english: true))
        state.tapShift(at: 1)
        precondition(state.mode == .nextLetter && state.consumeLetter(english: true))
        precondition(state.mode == .lowercase && !state.consumeLetter(english: true))

        state.tapShift(at: 2)
        state.tapShift(at: 2.25)
        precondition(state.mode == .locked)
        precondition(state.consumeLetter(english: true) && state.consumeLetter(english: true))
        state.tapShift(at: 3)
        precondition(state.mode == .lowercase)

        state.tapShift(at: 4)
        state.tapShift(at: 5)
        precondition(state.mode == .lowercase, "slow taps should not lock")
        state.tapShift(at: 6)
        precondition(!state.consumeLetter(english: false), "pinyin stays lowercase")
        precondition(state.mode == .lowercase)
        state.tapShift(at: 6.1)
        precondition(state.mode == .nextLetter, "typing breaks the double-tap sequence")
        state.tapShift(at: 6.2)
        precondition(state.mode == .locked && !state.consumeLetter(english: false))
        precondition(state.mode == .locked && state.consumeLetter(english: true))
        state.tapShift(at: 7)
        precondition(state.mode == .lowercase)
        state.applyAutoCapitalization(true)
        precondition(state.mode == .nextLetter)
        state.applyAutoCapitalization(false)
        precondition(state.mode == .lowercase)
        precondition(CapitalizationState.shouldAutoCapitalize(rawType: 2, contextBeforeInput: nil, english: true))
        precondition(CapitalizationState.shouldAutoCapitalize(rawType: 2, contextBeforeInput: "Hello. ", english: true))
        precondition(!CapitalizationState.shouldAutoCapitalize(rawType: 2, contextBeforeInput: "Hello ", english: true))
        precondition(!CapitalizationState.shouldAutoCapitalize(rawType: 2, contextBeforeInput: nil, english: false))
        print("PASS: 單次大寫、雙擊鎖定、解鎖、自動句首大寫及中文小寫讀音")
    }
}
