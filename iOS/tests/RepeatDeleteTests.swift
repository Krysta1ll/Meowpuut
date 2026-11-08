import Foundation

@main
struct RepeatDeleteTests {
    static func main() {
        var count = 0
        let driver = RepeatDeleteDriver { count += 1 }
        driver.press()
        precondition(count == 1, "a tap deletes exactly once")
        driver.stop()
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        precondition(count == 1, "a released tap must not repeat")

        driver.press()
        driver.press()
        precondition(count == 2, "a duplicate press must not duplicate a deletion")
        RunLoop.current.run(until: Date().addingTimeInterval(0.7))
        precondition(count >= 4, "a held press should repeat")
        driver.stop()
        let stopped = count
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        precondition(count == stopped, "repeats must stop after release")
        var cancelled = 0
        var reentrant: RepeatDeleteDriver!
        reentrant = RepeatDeleteDriver {
            cancelled += 1
            // A focus check inside the action can synchronously cancel the
            // driver before the timer is due (as the controller now does).
            reentrant.stop()
        }
        reentrant.press()
        RunLoop.current.run(until: Date().addingTimeInterval(0.55))
        precondition(cancelled == 1, "document switch during first delete prevents all repeats")
        reentrant.press(); reentrant.stop()
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        precondition(cancelled == 2, "new press works after cancellation and hide still stops it")
        reentrant = nil
        print("PASS: 短按單次、長按連刪、鬆開停止")
    }
}
