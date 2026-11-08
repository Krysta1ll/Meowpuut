import Foundation

// Only the immediately adjacent Chinese text participates in the next
// composition. Never cross a punctuation, whitespace, emoji or Latin boundary.
enum CompositionContext {
    static func precedingChinese(_ text: String?) -> String {
        guard let text else { return "" }
        var suffix: [Character] = []
        for character in text.reversed().prefix(4) {
            guard character.unicodeScalars.count == 1, let scalar = character.unicodeScalars.first,
                  (0x3400...0x9fff).contains(scalar.value) || (0x20000...0x323af).contains(scalar.value) else { break }
            suffix.append(character)
        }
        return String(suffix.reversed())
    }

    static func precedingRange(selection: NSRange) -> NSRange? {
        guard selection.location != NSNotFound, selection.location > 0, selection.length == 0 else { return nil }
        // Four supplementary Han characters occupy at most eight UTF-16 units.
        let count = min(8, selection.location)
        return NSRange(location: selection.location - count, length: count)
    }
}
