import Foundation

// Keep selected segments and the remaining reading in one marked range until
// the composition ends. This avoids unmarking and immediately remarking text
// in the host's separate process, where the latter can replace the selection.
struct CompositionDelivery {
    enum Action: Equatable {
        case mark(String)
        case unmark
        case insert(String)
    }

    private(set) var hasMarkedText = false
    private(set) var documentID: UUID?
    private(set) var isSuspended = false
    private var selectedPrefix = ""

    // Check at the moment of input as well as in UIKit's callbacks. Focus can
    // move before textDidChange arrives; an old prefix belongs to the old field.
    @discardableResult mutating func attach(to id: UUID?) -> Bool {
        guard let id else {
            if documentID != nil { isSuspended = true }
            return false
        }
        let changed = documentID.map { $0 != id } ?? false
        if changed { reset() }
        documentID = id
        isSuspended = false
        return changed
    }

    // During lock/hide UIKit can temporarily detach the proxy. Keep the local
    // reading, but never send its selected prefix to an unidentified document.
    mutating func suspend() { isSuspended = true }

    mutating func actions(commit: String, preedit: String) -> [Action] {
        guard !isSuspended else { return [] }
        selectedPrefix += commit
        if !preedit.isEmpty {
            hasMarkedText = true
            return [.mark(selectedPrefix + preedit)]
        }
        let completed = selectedPrefix
        selectedPrefix = ""
        if hasMarkedText {
            hasMarkedText = false
            return [.mark(completed), .unmark]
        }
        return completed.isEmpty ? [] : [.insert(completed)]
    }

    mutating func reset() {
        selectedPrefix = ""
        hasMarkedText = false
        isSuspended = false
    }
}
