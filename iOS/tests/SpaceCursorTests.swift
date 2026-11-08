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
        let initialMovements = movements
        driver.touchDown(); driver.startCursor(at: 100)
        precondition(driver.moving && starts == 3)
        driver.cancelTouch()
        driver.updateCursor(at: 180); driver.startCursor(at: 100); driver.touchUpInside()
        precondition(!driver.moving && starts == 3 && spaces == 2 && movements == initialMovements,
                     "hide, field switch or lock cancels held cursor; stale callbacks cannot move or insert")
        driver.touchDown(); driver.cancelTouch(); driver.touchUpInside()
        precondition(spaces == 2, "cancelled short touch cannot become a late space")
        driver.touchDown(); driver.touchUpInside(); driver.touchUpInside()
        driver.startCursor(at: 100); driver.updateCursor(at: 200)
        precondition(spaces == 3 && starts == 3 && movements == initialMovements,
                     "duplicate release and late recognition after release must be ignored")
        driver.touchDown(); driver.startCursor(at: .nan); driver.startCursor(at: .infinity)
        precondition(starts == 3 && !driver.moving, "nonfinite origin must not start a cursor session")
        driver.startCursor(at: 100); driver.updateCursor(at: .nan); driver.updateCursor(at: .infinity)
        driver.updateCursor(at: 112); driver.endCursor(); driver.touchUpInside()
        precondition(starts == 4 && spaces == 3 && movements == initialMovements + [1],
                     "next gesture recovers after cancellation and rejects invalid coordinates")
        print("PASS: short space, hold and drag, cancellation, release")
    }
}
