import Foundation

/// A single Shift applies to one letter; a quick second tap locks uppercase.
struct CapitalizationState: Equatable {
    enum Mode { case lowercase, nextLetter, locked }
    private(set) var mode: Mode = .lowercase
    private var lastShiftTime: TimeInterval?
    private static let doubleTapInterval: TimeInterval = 0.35

    mutating func tapShift(at time: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        switch mode {
        case .lowercase:
            mode = .nextLetter
            lastShiftTime = time
        case .nextLetter:
            if let previous = lastShiftTime, time >= previous,
               time - previous <= Self.doubleTapInterval {
                mode = .locked
            } else {
                mode = .lowercase
            }
            lastShiftTime = nil
        case .locked:
            mode = .lowercase
            lastShiftTime = nil
        }
    }

    /// Consume one alphabetic key. Chinese and Japanese readings stay lowercase;
    /// Shift changes the committed letter's case only in English mode.
    mutating func consumeLetter(english: Bool) -> Bool {
        let uppercase = english && mode != .lowercase
        if mode == .nextLetter { mode = .lowercase }
        lastShiftTime = nil
        return uppercase
    }
}
