import Foundation

// The keyboard's system-supplied height may differ across devices and rotations.
// Divide only the space belonging to this extension; the Globe bar is iOS-owned.
struct KeyboardLayoutMetrics {
    struct CandidateItem: Equatable {
        let index: Int
        let width: CGFloat
    }
    static let padding: CGFloat = 5
    static let spacing: CGFloat = 5
    static let candidateHeight: CGFloat = 44
    static let bottomHeight: CGFloat = 44
    static let errorHeight: CGFloat = 18

    // The height belongs to the extension only; iOS draws the Globe bar below it.
    // Keep narrow phones compact without allowing a full-width iPad keyboard to
    // grow indefinitely. Landscape phones have much less vertical room.
    static func preferredHeight(keyboardWidth: CGFloat, screenHeight: CGFloat, rowCount: Int = 4, candidateBarHeight: CGFloat = candidateHeight) -> CGFloat {
        guard keyboardWidth.isFinite, screenHeight.isFinite, keyboardWidth > 0, screenHeight > 0 else { return 280 }
        let fourRowHeight: CGFloat
        if screenHeight < 500 {
            fourRowHeight = min(275, max(259, (screenHeight * 0.70).rounded()))
        } else {
            fourRowHeight = min(292, max(280, (280 + (keyboardWidth - 320) * 0.10).rounded()))
        }
        // A three-row letter keyboard needs less height than four-row Zhuyin
        // and symbols. Keeping the same total height made its keycaps tall.
        return fourRowHeight - (rowCount == 3 ? 25 : 0) + max(0, candidateBarHeight - candidateHeight)
    }

    static func candidateBarHeight(contentHeight: CGFloat, keyboardWidth: CGFloat, screenHeight: CGFloat, rowCount: Int) -> CGFloat {
        let base = preferredHeight(keyboardWidth: keyboardWidth, screenHeight: screenHeight, rowCount: rowCount)
        let limit = min(80, candidateHeight + max(0, screenHeight * 0.75 - base))
        return min(limit, max(candidateHeight, contentHeight))
    }

    let availableHeight: CGFloat
    let rowCount: Int
    let expanded: Bool
    let showsError: Bool
    var candidateBarHeight: CGFloat = Self.candidateHeight

    private var usableHeight: CGFloat {
        let reservedError = showsError ? Self.errorHeight + Self.spacing : 0
        return max(0, availableHeight - 2 * Self.padding - reservedError)
    }
    var rowHeight: CGFloat {
        guard !expanded, rowCount > 0 else { return 0 }
        let gaps = CGFloat(rowCount + 1) * Self.spacing
        return max(0, (usableHeight - candidateBarHeight - Self.bottomHeight - gaps) / CGFloat(rowCount))
    }
    var expandedHeight: CGFloat {
        guard expanded else { return 0 }
        return max(0, usableHeight - candidateBarHeight - Self.bottomHeight - 2 * Self.spacing)
    }

    // Whole candidates stay on one page; oversized candidates wrap in the view.
    static func candidatePages(widths: [CGFloat], availableWidth: CGFloat) -> [[Int]] {
        guard !widths.isEmpty else { return [] }
        let width = max(1, availableWidth)
        var pages: [[Int]] = []
        var page: [Int] = []
        var used: CGFloat = 0
        for (index, raw) in widths.enumerated() {
            let item = min(width, max(0, raw.isFinite ? raw : width))
            let next = used + (page.isEmpty ? 0 : 4) + item
            if !page.isEmpty && next > width {
                pages.append(page)
                page = []
                used = 0
            }
            used += (page.isEmpty ? 0 : 4) + item
            page.append(index)
        }
        if !page.isEmpty { pages.append(page) }
        return pages
    }

    // Keep the engine's ranking, with one Japanese interpretation in the
    // collapsed strip. If it would be offscreen, bring it behind the leading
    // candidate and share the first row rather than pinning it to the edge.
    static func compactCandidatePages(widths: [CGFloat], japaneseIndices: Set<Int>, availableWidth: CGFloat) -> [[CandidateItem]] {
        guard !widths.isEmpty else { return [] }
        let width = max(1, availableWidth)
        let japanese = widths.indices.first { japaneseIndices.contains($0) }
        var indices = widths.indices.filter { !japaneseIndices.contains($0) || $0 == japanese }
        var effective = widths.map { min(width, max(0, $0.isFinite ? $0 : width)) }

        // During attachment/rotation GeometryReader can briefly report zero
        // width. Two items cannot share a page narrower than their fixed gap.
        if width > 4, let japanese, let first = indices.first, first != japanese {
            let initial = candidatePages(widths: indices.map { effective[$0] }, availableWidth: width)
            if !(initial.first ?? []).contains(where: { indices[$0] == japanese }) {
                indices.removeAll { $0 == japanese }
                indices.insert(japanese, at: 1)
                if effective[first] + 4 + effective[japanese] > width {
                    let half = max(0, (width - 4) / 2)
                    effective[first] = min(effective[first], half)
                    effective[japanese] = min(effective[japanese], width - 4 - effective[first])
                }
            }
        }
        return candidatePages(widths: indices.map { effective[$0] }, availableWidth: width).map { page in
            page.map { CandidateItem(index: indices[$0], width: effective[indices[$0]]) }
        }
    }
}
