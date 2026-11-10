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
    private var selectedPrefix = ""

    mutating func actions(commit: String, preedit: String) -> [Action] {
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
    }
}
