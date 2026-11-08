import Foundation

@main
struct KeyboardLayoutMetricsTests {
    static func main() {
        let sizes: [(CGFloat, CGFloat, CGFloat)] = [
            (320, 568, 280), (375, 812, 286), (393, 852, 287),
            (440, 956, 292), (834, 1194, 292), (852, 393, 275), (667, 375, 263), (568, 320, 259)
        ]
        for (width, screenHeight, expected) in sizes {
            let height = KeyboardLayoutMetrics.preferredHeight(keyboardWidth: width, screenHeight: screenHeight)
            precondition(height == expected, "preferred height should fit the device and orientation")
            let threeRowsHeight = KeyboardLayoutMetrics.preferredHeight(keyboardWidth: width, screenHeight: screenHeight, rowCount: 3)
            precondition(threeRowsHeight == height - 25, "three-row mode should not stretch keys to fill four rows")
            let threeRows = KeyboardLayoutMetrics(availableHeight: threeRowsHeight, rowCount: 3, expanded: false, showsError: false)
            let fourRows = KeyboardLayoutMetrics(availableHeight: height, rowCount: 4, expanded: false, showsError: false)
            let wrappedHeight = KeyboardLayoutMetrics.preferredHeight(keyboardWidth: width, screenHeight: screenHeight, rowCount: 4, candidateBarHeight: 88)
            let wrapped = KeyboardLayoutMetrics(availableHeight: wrappedHeight, rowCount: 4, expanded: false, showsError: false, candidateBarHeight: 88)
            precondition(wrappedHeight == height + 44 && wrapped.rowHeight == fourRows.rowHeight,
                         "a wrapped candidate must not squeeze the keys")
            for contentHeight: CGFloat in [20, 44, 60, 80, 200] {
                let bar = KeyboardLayoutMetrics.candidateBarHeight(contentHeight: contentHeight, keyboardWidth: width, screenHeight: screenHeight, rowCount: 4)
                let total = KeyboardLayoutMetrics.preferredHeight(keyboardWidth: width, screenHeight: screenHeight, rowCount: 4, candidateBarHeight: bar)
                let layout = KeyboardLayoutMetrics(availableHeight: total, rowCount: 4, expanded: false, showsError: false, candidateBarHeight: bar)
                precondition((44...80).contains(bar) && layout.rowHeight == fourRows.rowHeight,
                             "candidate growth must be bounded and preserve key heights")
            }
            precondition(threeRows.rowHeight >= (screenHeight < 500 ? 38 : 44), "letter touch targets should remain usable")
            precondition(fourRows.rowHeight >= (screenHeight < 500 ? 34 : 39), "four-row keys must retain usable height")
            precondition(threeRows.rowHeight <= 50, "letter keys should not stretch vertically")
            precondition(threeRows.rowHeight - fourRows.rowHeight < 10, "different modes should have a consistent rhythm")
            let symbolsHeight = KeyboardLayoutMetrics.preferredHeight(keyboardWidth: width, screenHeight: screenHeight, rowCount: 4)
            precondition(symbolsHeight == height, "symbols must use their four-row height after switching")
        }
        precondition(KeyboardLayoutMetrics.preferredHeight(keyboardWidth: 0, screenHeight: 0) == 280)
        for height: CGFloat in [216, 260, 312, 360, 500] {
            for rows in [3, 4] {
                let layout = KeyboardLayoutMetrics(availableHeight: height, rowCount: rows, expanded: false, showsError: false)
                let used = 10 + 44 + 44 + CGFloat(rows + 1) * 5 + CGFloat(rows) * layout.rowHeight
                precondition(abs(used - height) < 0.001, "rows should fill the available height")
                precondition(layout.rowHeight > 0)
                let withError = KeyboardLayoutMetrics(availableHeight: height, rowCount: rows, expanded: false, showsError: true)
                precondition(abs((used - CGFloat(rows) * layout.rowHeight + CGFloat(rows) * withError.rowHeight + 23) - height) < 0.001)
            }
            let expanded = KeyboardLayoutMetrics(availableHeight: height, rowCount: 0, expanded: true, showsError: false)
            precondition(abs(10 + 44 + 44 + 10 + expanded.expandedHeight - height) < 0.001)
        }
        let widths: [CGFloat] = [58, 58, 120, 44, 300, 58]
        for viewport: CGFloat in [80, 160, 206, 320] {
            let pages = KeyboardLayoutMetrics.candidatePages(widths: widths, availableWidth: viewport)
            precondition(pages.flatMap { $0 } == Array(widths.indices), "candidates keep their selection order")
            for page in pages {
                let used = page.map { min(viewport, widths[$0]) }.reduce(0, +) + CGFloat(max(0, page.count - 1)) * 4
                precondition(used <= viewport + 0.001, "a page must not cut through a candidate")
            }
        }
        precondition(KeyboardLayoutMetrics.candidatePages(widths: [], availableWidth: 80).isEmpty)
        // A late Japanese result joins the first page without being pinned;
        // all Chinese options keep their relative order and their real index.
        for viewport: CGFloat in [120, 160, 206, 320] {
            for japanese in [0, 2, 5] {
                let japaneseSet: Set<Int> = [japanese, 6]
                let pages = KeyboardLayoutMetrics.compactCandidatePages(widths: [160, 54, 80, 60, 54, 90, 80], japaneseIndices: japaneseSet, availableWidth: viewport)
                let all = pages.flatMap { $0 }
                precondition(pages.first?.contains(where: { $0.index == japanese }) == true, "one Japanese interpretation must be on the first screen")
                precondition(all.filter { japaneseSet.contains($0.index) }.count == 1, "show only one Japanese interpretation while collapsed")
                precondition(all.filter { !japaneseSet.contains($0.index) }.map(\.index) == Array(0..<7).filter { !japaneseSet.contains($0) }, "Chinese candidates must keep their relative order")
                for page in pages {
                    let used = page.reduce(CGFloat.zero) { $0 + $1.width } + CGFloat(max(0, page.count - 1)) * 4
                    precondition(used <= viewport + 0.001, "compacted candidates must fit without clipping their neighbours")
                }
            }
        }
        let noJapanese = KeyboardLayoutMetrics.compactCandidatePages(widths: [48, 60, 52], japaneseIndices: [], availableWidth: 140)
        precondition(noJapanese.flatMap { $0.map(\.index) } == [0, 1, 2])
        for width: CGFloat in [0, 1, 2, 4, 5, 8, 40] {
            let pages = KeyboardLayoutMetrics.compactCandidatePages(widths: [60, 60, 60], japaneseIndices: [2], availableWidth: width)
            precondition(pages.flatMap { $0 }.allSatisfy { $0.width.isFinite && $0.width >= 0 },
                         "transient layout widths must never produce negative frames")
            for page in pages {
                let used = page.reduce(CGFloat.zero) { $0 + $1.width } + CGFloat(max(0, page.count - 1)) * 4
                precondition(used <= max(1, width), "items and spacing must fit even during attachment")
            }
        }
        let ordered = KeyboardLayoutMetrics.compactCandidatePages(widths: [44, 44, 44, 44], japaneseIndices: [1], availableWidth: 200)
        precondition(ordered.first?.map(\.index) == [0, 1, 2, 3], "preserve the ranked position when it already fits")
        print("PASS: keyboard heights and complete candidate pages at multiple sizes")
    }
}
