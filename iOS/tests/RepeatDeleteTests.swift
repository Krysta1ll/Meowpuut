import Foundation

// Delete: once on touch-down, a pause, about twelve a second, then whole
// words (docs/DESIGN.md §6).

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (RepeatDeleteTests.swift:\(line))")
        exit(1)
    }
}

@main
struct RepeatDeleteTests {
    static func main() {
        // The numbers in the spec.
        expect(RepeatDeleteDriver.initialDelay == 0.42, "repeat starts after 0.42 s")
        expect(abs(1 / RepeatDeleteDriver.interval - 12) < 0.1, "about twelve characters a second")
        let wordsAfter = RepeatDeleteDriver.initialDelay + Double(RepeatDeleteDriver.wordThreshold) * RepeatDeleteDriver.interval
        expect(abs(wordsAfter - 2.5) < 0.15, "whole words after about 2.5 s (\(wordsAfter))")

        // With the real timer.
        var count = 0
        let driver = RepeatDeleteDriver { count += 1 }
        driver.press()
        expect(count == 1, "a tap deletes exactly once")
        driver.stop()
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        expect(count == 1, "a released tap must not repeat")

        driver.press()
        driver.press()
        expect(count == 2, "a second press while held is ignored")
        RunLoop.current.run(until: Date().addingTimeInterval(0.35))
        expect(count == 2, "nothing repeats before the pause is over")
        RunLoop.current.run(until: Date().addingTimeInterval(0.4))
        expect(count >= 4 && count <= 7, "a held key repeats about twelve times a second (\(count))")
        driver.stop()
        let stopped = count
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        expect(count == stopped, "repeats stop on release")

        // Word acceleration, tick by tick.
        var characters = 0, words = 0, clicks = 0
        let held = RepeatDeleteDriver(initialDelay: 60, interval: 60) { characters += 1 }
        held.wordAction = { words += 1 }
        held.onRepeatTick = { clicks += 1 }
        held.press()
        expect(characters == 1 && clicks == 0 && !held.deletingWords, "the first delete is the touch itself")
        for _ in 1..<RepeatDeleteDriver.wordThreshold { held.tick() }
        expect(characters == RepeatDeleteDriver.wordThreshold && words == 0 && !held.deletingWords, "characters until the threshold")
        held.tick()
        expect(words == 1 && held.deletingWords, "then the first whole word")
        held.tick(); held.tick()
        expect(words == 1, "words go slower: one every third tick")
        held.tick()
        expect(words == 2 && characters == RepeatDeleteDriver.wordThreshold, "no more single characters once words start")
        expect(clicks == characters - 1 + words, "one click for each repeated deletion")
        held.stop()
        expect(held.repeatCount == 0 && !held.deletingWords, "release starts over")
        held.press()
        held.tick()
        expect(characters == RepeatDeleteDriver.wordThreshold + 2 && words == 2, "the next hold begins with characters again")
        held.stop()

        // Without a word action it stays with characters.
        var plain = 0
        let simple = RepeatDeleteDriver(initialDelay: 60, interval: 60, wordThreshold: 3) { plain += 1 }
        simple.press()
        for _ in 0..<10 { simple.tick() }
        expect(plain == 11 && !simple.deletingWords, "no word action: characters only")
        simple.stop()
        print("PASS: delete once, repeat after the pause, whole words after the threshold, stop on release")
    }
}
