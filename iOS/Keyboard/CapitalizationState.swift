import Foundation

/// A single Shift applies to one letter; a quick second tap locks uppercase.
struct CapitalizationState: Equatable {
    enum Mode { case lowercase, nextLetter, locked }
    /// Two taps this close together lock uppercase (docs/DESIGN.md §6).
    static let doubleTapInterval: TimeInterval = 0.3

    private(set) var mode: Mode = .lowercase
    private var lastShiftTime: TimeInterval?
    /// `nextLetter` came from a Shift tap, not from the start of a sentence.
    private var manual = false
    /// The user turned off the capital offered at the start of a sentence;
    /// it is not offered again until a letter is typed or the context changes.
    private var declinedAutomatic = false

    mutating func tapShift(at time: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        // The second of two quick taps locks, whatever the first one did
        // (it may have turned off the automatic capital at a sentence start).
        if mode != .locked, let previous = lastShiftTime, time >= previous, time - previous <= Self.doubleTapInterval {
            mode = .locked
            manual = true
            lastShiftTime = nil
            return
        }
        switch mode {
        case .lowercase:
            mode = .nextLetter
            manual = true
            lastShiftTime = time
        case .nextLetter:
            mode = .lowercase
            declinedAutomatic = !manual
            manual = false
            lastShiftTime = time
        case .locked:
            mode = .lowercase
            manual = false
            lastShiftTime = nil
        }
    }

    /// Consume one alphabetic key and report whether it is typed in uppercase.
    /// English letters follow Shift. With `english` false the caller decides
    /// what an uppercase reading means; the one-letter Shift is still used up.
    mutating func consumeLetter(english: Bool) -> Bool {
        let uppercase = english && mode != .lowercase
        if mode == .nextLetter { mode = .lowercase; manual = false }
        lastShiftTime = nil
        declinedAutomatic = false
        return uppercase
    }

    /// Whether Shift is on in any form (one letter or locked).
    var isActive: Bool { mode != .lowercase }

    /// Synchronize Shift state with the host text field's auto-capitalization context
    /// without interfering with a Shift the user tapped or with Caps Lock.
    mutating func applyAutoCapitalization(_ shouldCapitalize: Bool) {
        guard mode != .locked else { return }
        if shouldCapitalize {
            if mode == .lowercase, !declinedAutomatic {
                mode = .nextLetter
                manual = false
            }
        } else {
            declinedAutomatic = false
            if mode == .nextLetter && !manual { mode = .lowercase }
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
