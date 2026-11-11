import UIKit

/// A text field in memory. The lab's keyboard writes into it the way a real
/// keyboard writes into its host app, so typing can be scripted and checked.
/// It follows UITextDocumentProxy's rules: inserted text replaces the marked
/// text, the cursor sits after the marked text, and offsets are UTF-16 units.
final class LabDocument: NSObject, UITextDocumentProxy {
    private(set) var before = ""
    private(set) var marked = ""
    private(set) var after = ""
    private let identifier = UUID()

    /// Called after every change, on the next turn of the main queue, the
    /// way UIKit calls textDidChange.
    var onChange: (() -> Void)?
    var changes = 0

    var keyboardType: UIKeyboardType = .default
    var returnKeyType: UIReturnKeyType = .default
    var autocapitalizationType: UITextAutocapitalizationType = .sentences
    var enablesReturnKeyAutomatically = false
    var keyboardAppearance: UIKeyboardAppearance = .default

    var text: String { before + marked + after }

    func set(before: String, after: String) {
        self.before = before
        self.after = after
        marked = ""
        changed()
    }

    private func changed() {
        changes += 1
        DispatchQueue.main.async { [weak self] in self?.onChange?() }
    }

    // MARK: UITextDocumentProxy

    var documentContextBeforeInput: String? { before + marked }
    var documentContextAfterInput: String? { after }
    var selectedText: String? { nil }
    var documentInputMode: UITextInputMode? { nil }
    var documentIdentifier: UUID { identifier }
    var hasText: Bool { !text.isEmpty }

    func adjustTextPosition(byCharacterOffset offset: Int) {
        unmarkText()
        var units = offset
        while units > 0, let first = after.first {
            let width = first.utf16.count
            guard width <= units else { break }
            before.append(after.removeFirst())
            units -= width
        }
        while units < 0, let last = before.last {
            let width = last.utf16.count
            guard width <= -units else { break }
            after.insert(before.removeLast(), at: after.startIndex)
            units += width
        }
        changed()
    }

    func setMarkedText(_ markedText: String, selectedRange: NSRange) {
        marked = markedText
        changed()
    }

    func unmarkText() {
        guard !marked.isEmpty else { return }
        before += marked
        marked = ""
        changed()
    }

    func insertText(_ text: String) {
        marked = ""
        before += text
        changed()
    }

    func deleteBackward() {
        if !marked.isEmpty { marked.removeLast() }
        else if !before.isEmpty { before.removeLast() }
        changed()
    }
}
