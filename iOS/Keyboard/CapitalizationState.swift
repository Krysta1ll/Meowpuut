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

    /// Synchronize Shift state with the host text field's auto-capitalization context
    /// without interfering with manual double-tap Caps Lock.
    mutating func applyAutoCapitalization(_ shouldCapitalize: Bool) {
        guard mode != .locked else { return }
        if shouldCapitalize {
            if mode == .lowercase {
                mode = .nextLetter
                lastShiftTime = nil
            }
        } else if mode == .nextLetter && lastShiftTime == nil {
            mode = .lowercase
        }
    }

    static func shouldAutoCapitalize(rawType: Int, contextBeforeInput: String?, english: Bool) -> Bool {
        guard english else { return false }
        switch rawType {
        case 3: // .allCharacters
            return true
        case 1: // .words
            guard let context = contextBeforeInput, !context.isEmpty else { return true }
            return context.last?.isWhitespace == true || context.last?.isNewline == true
        case 2: // .sentences
            guard let context = contextBeforeInput, !context.isEmpty else { return true }
            if context.last?.isNewline == true { return true }
            let trimmed = context.replacingOccurrences(of: "[ \\t]+$", with: "", options: .regularExpression)
            guard trimmed.count < context.count, let terminal = trimmed.last else { return false }
            return ".!?。！？".contains(terminal)
        default:
            return false
        }
    }
}
