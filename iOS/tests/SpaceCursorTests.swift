import Foundation

@main
struct SpaceCursorTests {
    static func main() {
        var spaces = 0
        var starts = 0
        var movements: [Int] = []
        let driver = SpaceCursorDriver(tap: { spaces += 1 }, beginCursor: { starts += 1 }, move: { movements.append($0) })
        driver.touchDown(); driver.touchUpInside()
        precondition(spaces == 1 && starts == 0, "short tap inserts one space")
        driver.touchDown(); driver.startCursor(at: 100)
        driver.updateCursor(at: 110)
        precondition(movements.isEmpty, "small movement stays within a character")
        driver.updateCursor(at: 125); driver.updateCursor(at: 88)
        precondition(movements == [2, -3], "both directions use relative deltas")
        driver.endCursor(); driver.touchUpInside()
        precondition(spaces == 1 && starts == 1, "long press never adds a space")
        driver.touchDown(); driver.cancelTouch(); driver.updateCursor(at: 200)
        precondition(spaces == 1 && movements == [2, -3], "cancelled touch doesn't type or move")
        driver.touchDown(); driver.touchUpInside()
        precondition(spaces == 2, "subsequent tap still works")
        driver.accessibilityMove(-1)
        precondition(spaces == 2 && starts == 2 && movements.last == -1, "accessible cursor action must not type")
        print("PASS: short space, hold and drag, cancellation, release")
    }
}
