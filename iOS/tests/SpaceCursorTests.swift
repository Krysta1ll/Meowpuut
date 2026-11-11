import Foundation

// The space bar as a cursor control (docs/DESIGN.md §6).

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (SpaceCursorTests.swift:\(line))")
        exit(1)
    }
}

@main
struct SpaceCursorTests {
    static func main() {
        expect(SpaceCursorDriver.holdDelay == 0.4 && SpaceCursorDriver.pointsPerCharacter == 9, "0.4 s hold, 9 pt a character")

        var spaces = 0
        var starts = 0
        var movements: [Int] = []
        let driver = SpaceCursorDriver(tap: { spaces += 1 }, beginCursor: { starts += 1 }, move: { movements.append($0) })
        driver.touchDown(); driver.touchUpInside()
        expect(spaces == 1 && starts == 0, "short tap inserts one space")
        driver.touchDown(); driver.startCursor(at: 100)
        driver.updateCursor(at: 108)
        expect(movements.isEmpty, "less than 9 pt stays on the same character")
        driver.updateCursor(at: 109)
        expect(movements == [1], "9 pt is one character")
        driver.updateCursor(at: 127.5); driver.updateCursor(at: 82)
        expect(movements == [1, 2, -5], "both directions, as steps from the last position")
        expect(movements.reduce(0, +) == -2, "18 pt left of the start is two characters back")
        driver.updateCursor(at: .nan); driver.updateCursor(at: .infinity)
        expect(movements.count == 3, "invalid coordinates are ignored")
        driver.endCursor(); driver.touchUpInside()
        expect(spaces == 1 && starts == 1, "a hold never adds a space")
        driver.updateCursor(at: 300)
        expect(movements.count == 3, "no movement after the finger lifts")
        driver.touchDown(); driver.cancelTouch(); driver.updateCursor(at: 200)
        expect(spaces == 1 && movements.count == 3, "a cancelled touch neither types nor moves")
        driver.touchDown(); driver.touchUpInside()
        expect(spaces == 2, "the next tap types again")
        driver.accessibilityMove(-1)
        expect(spaces == 2 && starts == 2 && movements.last == -1, "the accessible cursor action does not type")
        // A far swipe is bounded.
        let far = SpaceCursorDriver(tap: {}, beginCursor: {}, move: { movements.append($0) })
        far.touchDown(); far.startCursor(at: 0); far.updateCursor(at: 1e12)
        expect(movements.last == 512, "one event moves at most 512 characters")

        // UITextDocumentProxy counts UTF-16 units: an emoji or a rare Han
        // character is two.
        expect(CursorStep.utf16Offset(characters: 0, before: "abc", after: "def") == 0, "no step")
        expect(CursorStep.utf16Offset(characters: 2, before: "ab", after: "cde") == 2, "plain text forward")
        expect(CursorStep.utf16Offset(characters: -2, before: "你好", after: "") == -2, "Chinese backward")
        expect(CursorStep.utf16Offset(characters: -1, before: "a😀", after: "") == -2, "back over an emoji is two units")
        expect(CursorStep.utf16Offset(characters: -2, before: "a😀", after: "") == -3, "emoji and a letter")
        expect(CursorStep.utf16Offset(characters: 1, before: "", after: "𠮷野家") == 2, "forward over a rare Han character")
        expect(CursorStep.utf16Offset(characters: 1, before: "", after: "👨‍👩‍👧 ok") == 8, "a family emoji is one character, eight units")
        expect(CursorStep.utf16Offset(characters: 3, before: "", after: "a") == 3, "past the known text, single units")
        expect(CursorStep.utf16Offset(characters: -3, before: "😀", after: "") == -4, "past the known text backward")
        expect(CursorStep.utf16Offset(characters: -1, before: nil, after: nil) == -1 && CursorStep.utf16Offset(characters: 2, before: nil, after: "") == 2,
               "no context: plain offsets")
        print("PASS: space taps, hold and drag in 9 pt steps, cancellation, and UTF-16 cursor offsets")
    }
}
