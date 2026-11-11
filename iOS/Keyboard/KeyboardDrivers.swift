import Foundation

// Timing logic for the space bar and the delete key, kept apart from the
// view so it can be tested without UIKit.

/// Short tap types a space; a held space becomes a cursor control and never
/// types on release.
final class SpaceCursorDriver {
    /// How long the space bar is held before it becomes a cursor control.
    static let holdDelay: TimeInterval = 0.4
    /// Horizontal travel that moves the cursor by one character.
    static let pointsPerCharacter: Double = 9

    var tap: () -> Void
    var beginCursor: () -> Void
    var move: (Int) -> Void
    private(set) var moving = false
    private var held = false
    private var origin: Double = 0
    private var lastStep = 0
    private let pointsPerCharacter: Double

    init(pointsPerCharacter: Double = SpaceCursorDriver.pointsPerCharacter, tap: @escaping () -> Void,
         beginCursor: @escaping () -> Void, move: @escaping (Int) -> Void) {
        self.pointsPerCharacter = pointsPerCharacter
        self.tap = tap
        self.beginCursor = beginCursor
        self.move = move
    }
    var isHeld: Bool { held }
    func touchDown() { held = false; moving = false; lastStep = 0 }
    func startCursor(at x: Double) {
        guard !held else { return }
        held = true
        moving = true
        origin = x
        lastStep = 0
        beginCursor()
    }
    func updateCursor(at x: Double) {
        guard moving, x.isFinite else { return }
        let distance = (x - origin) / pointsPerCharacter
        // Bounded so an invalid coordinate cannot overflow the conversion.
        let step = Int(max(-512, min(512, distance)))
        let delta = step - lastStep
        guard delta != 0 else { return }
        lastStep = step
        move(delta)
    }
    func endCursor() { moving = false }
    func touchUpInside() {
        if !held { tap() }
        moving = false
    }
    func cancelTouch() { if !held { moving = false } }
    func accessibilityMove(_ offset: Int) {
        guard offset != 0 else { return }
        beginCursor()
        move(offset)
    }
}

/// UITextDocumentProxy moves the cursor in UTF-16 units, so one step over an
/// emoji or a rare Han character is two units. This turns a number of
/// characters into the offset to pass, from the text around the cursor.
enum CursorStep {
    static func utf16Offset(characters: Int, before: String?, after: String?) -> Int {
        guard characters != 0 else { return 0 }
        if characters > 0 {
            guard let after, !after.isEmpty else { return characters }
            var offset = 0, taken = 0
            for character in after {
                guard taken < characters else { break }
                offset += character.utf16.count
                taken += 1
            }
            // Past the text the host reported: assume single units.
            return offset + (characters - taken)
        }
        guard let before, !before.isEmpty else { return characters }
        var offset = 0, taken = 0
        for character in before.reversed() {
            guard taken < -characters else { break }
            offset += character.utf16.count
            taken += 1
        }
        return -(offset + (-characters - taken))
    }
}

/// Delete: once on touch-down, then about twelve times a second after a
/// short pause, then whole words once the key has been held for a while.
final class RepeatDeleteDriver {
    static let initialDelay: TimeInterval = 0.42
    static let interval: TimeInterval = 0.083
    /// Character repeats before deletion switches to whole words (about 2.5 s).
    static let wordThreshold = 26
    /// A word goes on every third tick, so a release lands where intended.
    static let ticksPerWord = 3

    var action: () -> Void
    var wordAction: (() -> Void)?
    var onRepeatTick: (() -> Void)?
    private var timer: Timer?
    private(set) var repeatCount = 0
    private let initialDelay: TimeInterval
    private let interval: TimeInterval
    private let wordThreshold: Int

    init(initialDelay: TimeInterval = RepeatDeleteDriver.initialDelay, interval: TimeInterval = RepeatDeleteDriver.interval,
         wordThreshold: Int = RepeatDeleteDriver.wordThreshold, action: @escaping () -> Void) {
        self.initialDelay = initialDelay
        self.interval = interval
        self.wordThreshold = wordThreshold
        self.action = action
    }

    var deletingWords: Bool { wordAction != nil && repeatCount >= wordThreshold }

    func press() {
        guard timer == nil else { return }
        repeatCount = 0
        let next = Timer(timeInterval: interval, repeats: true) { [weak self] _ in self?.tick() }
        next.fireDate = Date().addingTimeInterval(initialDelay)
        timer = next
        RunLoop.main.add(next, forMode: .common)
        action()
    }

    /// One repeat. The timer calls this; tests call it directly.
    func tick() {
        repeatCount += 1
        if let word = wordAction, repeatCount >= wordThreshold {
            guard (repeatCount - wordThreshold) % Self.ticksPerWord == 0 else { return }
            onRepeatTick?()
            word()
        } else {
            onRepeatTick?()
            action()
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        repeatCount = 0
    }

    deinit { timer?.invalidate() }
}
