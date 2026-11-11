import CoreGraphics

// Order of the collapsed candidate strip. Pure, so it is tested on macOS.
enum CandidateStripLayout {
    /// Gap between candidates and the padding on each side of a candidate's text (docs/DESIGN.md §4).
    static let spacing: CGFloat = 4
    static let padding: CGFloat = 9

    /// Keeps the engine's ranking but shows only the first Japanese
    /// candidate, and makes sure it is on the first screen: if it would be
    /// scrolled out of view it moves up behind the leading candidate.
    /// With `promote` false it stays where it is ranked (a kana spelling
    /// that is not a word should not push a Chinese candidate out of view).
    static func order(widths: [CGFloat], japanese: Set<Int>, available: CGFloat, spacing: CGFloat,
                      pinnedFirst: Int? = nil, promote: Bool = true) -> [Int] {
        guard !widths.isEmpty else { return [] }
        let firstJapanese = pinnedFirst ?? widths.indices.first { japanese.contains($0) }
        var indices = widths.indices.filter { !japanese.contains($0) || $0 == firstJapanese }
        if let pinnedFirst, let at = indices.firstIndex(of: pinnedFirst), at != 0 {
            indices.remove(at: at)
            indices.insert(pinnedFirst, at: 0)
            return indices
        }
        guard promote, let jp = firstJapanese, let at = indices.firstIndex(of: jp), at > 1 else { return indices }
        var used: CGFloat = 0
        for (offset, index) in indices.enumerated() {
            used += (offset == 0 ? 0 : spacing) + widths[index]
            if index == jp { break }
        }
        if used > available {
            indices.remove(at: at)
            indices.insert(jp, at: 1)
        }
        return indices
    }

    /// Strip cell width: text, padding on each side and the paw mark.
    static func cellWidth(text: CGFloat, japanese: Bool, paw: CGFloat, maximum: CGFloat) -> CGFloat {
        min(maximum, naturalWidth(text: text, japanese: japanese, paw: paw))
    }

    /// The width a cell would take with no limit.
    static func naturalWidth(text: CGFloat, japanese: Bool, paw: CGFloat) -> CGFloat {
        (text + 2 * padding + (japanese ? paw + 3 : 0)).rounded(.up) + 1
    }

    /// One candidate in the collapsed row. Every cell is a single line; a
    /// cell wider than `maximum` keeps that width and is drawn with an
    /// ellipsis in the middle.
    struct Cell: Equatable {
        var index: Int
        var text: String
        var japanese: Bool
        var width: CGFloat
        var shortened: Bool
    }

    /// The collapsed row for a list of candidates: ranked order, at most one
    /// Japanese candidate (`japanese` holds their indices), every width
    /// measured and capped at `maximum`.
    static func cells(texts: [String], japanese: Set<Int>, pinnedFirst: Int? = nil, promoteJapanese: Bool = true,
                      available: CGFloat, maximum: CGFloat, paw: CGFloat, measure: (String) -> CGFloat) -> [Cell] {
        let natural = texts.indices.map { naturalWidth(text: measure(texts[$0]), japanese: japanese.contains($0), paw: paw) }
        let widths = natural.map { min(maximum, $0) }
        return order(widths: widths, japanese: japanese, available: available, spacing: spacing, pinnedFirst: pinnedFirst,
                     promote: promoteJapanese).map {
            Cell(index: $0, text: texts[$0], japanese: japanese.contains($0), width: widths[$0], shortened: natural[$0] > maximum)
        }
    }
}

/// The idle row: quick punctuation on the left, the input status on the
/// right. Nothing in it may run over the cats or the expand key, so on a
/// short row the status is abbreviated, then the marks narrow a little, and
/// last the status is left out.
enum IdleRowLayout {
    enum Status: Equatable { case full, short, hidden }

    /// Widths include each part's own padding. `markWidth` is one mark's natural width.
    static func fit(rowWidth: CGFloat, marks: Int, markWidth: CGFloat, fullStatus: CGFloat, shortStatus: CGFloat,
                    minimumMarkWidth: CGFloat = 26) -> (status: Status, markWidth: CGFloat) {
        let count = CGFloat(marks)
        if count * markWidth + fullStatus <= rowWidth { return (.full, markWidth) }
        if count * markWidth + shortStatus <= rowWidth { return (.short, markWidth) }
        guard marks > 0 else { return (.hidden, markWidth) }
        let narrowed = (rowWidth - shortStatus) / count
        if narrowed >= minimumMarkWidth { return (.short, narrowed) }
        return (.hidden, min(markWidth, rowWidth / count))
    }
}

/// Rows of the expanded grid: each candidate takes as many columns as its
/// text needs, in ranked order; the last cell of a row fills what is left.
enum ExpandedGridLayout {
    struct Cell: Equatable { var index: Int; var columns: Int }

    /// What a cell needs: its text, 4 pt on each side and, for a Japanese
    /// candidate, the paw mark and the 3 pt before it. A two-character word
    /// (about 40 pt at 20 pt) then fits one column on every phone.
    static func cellWidth(text: CGFloat, japanese: Bool, paw: CGFloat) -> CGFloat {
        text + 8 + (japanese ? paw + 3 : 0)
    }

    /// The bar's first page leads, in the bar's order, then the rest of
    /// the pool as ranked. `lead` and `pool` hold candidate indices.
    static func order(pool: [Int], lead: [Int]) -> [Int] {
        let present = Set(pool)
        let first = lead.filter { present.contains($0) }
        let taken = Set(first)
        return first + pool.filter { !taken.contains($0) }
    }

    /// Six columns as on Apple's keyboard; on a narrow phone fewer, so a
    /// single character keeps a cell at least 44 pt wide. A full-width iPad
    /// keyboard has more, about 64 pt each.
    static func columns(gridWidth: CGFloat, phone: Bool) -> Int {
        phone ? min(6, Int(gridWidth / 44)) : max(6, Int(gridWidth / 64))
    }

    static func rows(widths: [CGFloat], columnWidth: CGFloat, columns: Int) -> [[Cell]] {
        guard columns > 0, columnWidth > 0 else { return [] }
        var rows: [[Cell]] = []
        var row: [Cell] = []
        var used = 0
        for (index, width) in widths.enumerated() {
            let need = min(columns, max(1, Int((width / columnWidth).rounded(.up))))
            if used + need > columns, !row.isEmpty {
                row[row.count - 1].columns += columns - used
                rows.append(row)
                row = []
                used = 0
            }
            row.append(Cell(index: index, columns: need))
            used += need
        }
        if !row.isEmpty {
            row[row.count - 1].columns += columns - used
            rows.append(row)
        }
        return rows
    }
}

#if canImport(UIKit)
import UIKit

/// Text widths for candidate cells, cached so unchanged candidates are not
/// measured again on the next keystroke.
enum CandidateTextMetrics {
    private static var cache: [String: CGFloat] = [:]
    private static var pawCache: [CGFloat: CGFloat] = [:]

    static func width(_ text: String, size: CGFloat) -> CGFloat {
        let key = "\(size)|" + text
        if let hit = cache[key] { return hit }
        // Medium weight is the widest the cell will draw (the selected one).
        let width = ceil((text as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: size, weight: .medium)]).width)
        if cache.count > 2048 { cache.removeAll(keepingCapacity: true) }
        cache[key] = width
        return width
    }

    static func paw(size: CGFloat) -> CGFloat {
        if let hit = pawCache[size] { return hit }
        let config = UIImage.SymbolConfiguration(pointSize: (size * 0.45).rounded(), weight: .regular)
        let width = ceil(UIImage(systemName: "pawprint.fill", withConfiguration: config)?.size.width ?? size * 0.5)
        pawCache[size] = width
        return width
    }
}
#endif
