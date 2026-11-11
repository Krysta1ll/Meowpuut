import Foundation
import CoreGraphics

// The collapsed candidate row (docs/DESIGN.md §4): one line, long
// candidates shortened in the middle, at most one Japanese candidate.

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (CandidateStripLayoutTests.swift:\(line))")
        exit(1)
    }
}

/// 20 pt for a full-width character, 11 pt for a Latin one.
private func measure(_ text: String) -> CGFloat {
    text.unicodeScalars.reduce(0) { $0 + ($1.value < 0x2E80 ? 11 : 20) }
}

@main
struct CandidateStripLayoutTests {
    static let paw: CGFloat = 9

    static func main() {
        widths()
        japanese()
        order()
        grid()
        kanaSpelling()
        idleRow()
        print("PASS: one-line cells, 70 % limit, a single Japanese candidate first screen, expanded grid rows, the idle row")
    }

    /// にはお for nihao is a spelling, not a word: it stays where it is ranked.
    static func kanaSpelling() {
        let texts = ["你好", "妳好", "你", "尼", "泥", "擬", "逆", "にはお"]
        func row(promote: Bool) -> [String] {
            CandidateStripLayout.cells(texts: texts, japanese: [7], promoteJapanese: promote, available: 248, maximum: 174,
                                       paw: paw, measure: measure).map(\.text)
        }
        expect(row(promote: true) == ["你好", "にはお", "妳好", "你", "尼", "泥", "擬", "逆"], "a Japanese word moves up behind the first candidate")
        expect(row(promote: false) == texts, "a bare kana spelling keeps its place at the end")
        // The paw is part of a Japanese cell in the expanded grid too.
        let column: CGFloat = 81.25
        let plain = ExpandedGridLayout.cellWidth(text: 60, japanese: false, paw: 11)
        let marked = ExpandedGridLayout.cellWidth(text: 60, japanese: true, paw: 11)
        expect(plain == 68 && marked == 82, "three kana need 82 pt with the paw")
        expect(ExpandedGridLayout.rows(widths: [marked, plain, plain], columnWidth: column, columns: 4).first?.map(\.columns) == [2, 1, 1],
               "so they take two columns instead of being cut")
        // Six columns on a 393 pt phone (grid 325 pt): a character or a two-character word a cell.
        let six = ExpandedGridLayout.columns(gridWidth: 325, phone: true)
        expect(six == 6 && ExpandedGridLayout.columns(gridWidth: 252, phone: true) == 5,
               "six columns, five at 320 pt so a cell stays 44 pt wide")
        expect(ExpandedGridLayout.columns(gridWidth: 752, phone: false) == 11 && ExpandedGridLayout.columns(gridWidth: 300, phone: false) == 6,
               "more columns on a wide iPad keyboard")
        let char = ExpandedGridLayout.cellWidth(text: 20, japanese: false, paw: 11), word = ExpandedGridLayout.cellWidth(text: 40, japanese: false, paw: 11)
        expect(ExpandedGridLayout.rows(widths: [char, word, word, char, word, char], columnWidth: 252 / 5, columns: 5).first?.map(\.columns) == [1, 1, 1, 1, 1],
               "你 你好 … fit one column each, also at 320 pt")
        // The bar's first page leads the panel, in the bar's order.
        expect(ExpandedGridLayout.order(pool: [0, 1, 2, 3, 4, 5], lead: [1, 0, 4]) == [1, 0, 4, 2, 3, 5], "the panel reads on from the bar")
        expect(ExpandedGridLayout.order(pool: [0, 1, 2], lead: [9, 1]) == [1, 0, 2] && ExpandedGridLayout.order(pool: [], lead: [1]) == [],
               "a lead that is not in the pool is ignored")
    }

    /// Punctuation and status never run over the cats or the expand key.
    static func idleRow() {
        // 393 pt phone: row 248 pt, five 31 pt marks, status 74 pt.
        var fit = IdleRowLayout.fit(rowWidth: 248.4, marks: 5, markWidth: 31, fullStatus: 74, shortStatus: 41)
        expect(fit.status == .full && fit.markWidth == 31, "everything fits at 393 pt")
        // 320 pt phone: row 189.9 pt.
        fit = IdleRowLayout.fit(rowWidth: 189.9, marks: 5, markWidth: 30, fullStatus: 70, shortStatus: 39)
        expect(fit.status == .short && fit.markWidth == 30, "320 pt: the short status")
        fit = IdleRowLayout.fit(rowWidth: 189.9, marks: 5, markWidth: 30, fullStatus: 70, shortStatus: 44)
        expect(fit.status == .short && abs(fit.markWidth - 29.18) < 0.01, "then the marks narrow a little")
        expect(5 * fit.markWidth + 44 <= 189.9 + 0.001, "and the row is exactly full")
        fit = IdleRowLayout.fit(rowWidth: 150, marks: 5, markWidth: 30, fullStatus: 70, shortStatus: 44)
        expect(fit.status == .hidden && fit.markWidth == 30, "no room: the status goes, the marks stay")
        fit = IdleRowLayout.fit(rowWidth: 120, marks: 5, markWidth: 30, fullStatus: 70, shortStatus: 44)
        expect(fit.status == .hidden && fit.markWidth == 24, "the marks never run past the row")
        // English and Japanese have no marks.
        expect(IdleRowLayout.fit(rowWidth: 189.9, marks: 0, markWidth: 30, fullStatus: 60, shortStatus: 60).status == .full, "status alone fits")
    }

    static func cells(_ texts: [String], japanese: Set<Int> = [], pinned: Int? = nil, row: CGFloat = 260) -> [CandidateStripLayout.Cell] {
        CandidateStripLayout.cells(texts: texts, japanese: japanese, pinnedFirst: pinned, available: row,
                                   maximum: max(60, row * 0.70), paw: paw, measure: measure)
    }

    static func widths() {
        // Text, 12 pt each side, and the paw mark with its gap for Japanese.
        expect(CandidateStripLayout.cellWidth(text: 40, japanese: false, paw: paw, maximum: 500) == 59, "40 pt of text is a 59 pt cell (9 pt each side)")
        expect(CandidateStripLayout.cellWidth(text: 40, japanese: true, paw: paw, maximum: 500) == 71, "the paw mark adds its width and gap")
        expect(CandidateStripLayout.cellWidth(text: 40.2, japanese: false, paw: paw, maximum: 500) == 60, "widths round up")
        expect(CandidateStripLayout.spacing == 4, "4 pt between candidates")

        // A sentence-long candidate keeps to 70 % of the row and is marked to be shortened.
        let long = "今天晚上我們一起去吃寿司吧然後看電影"
        for row: CGFloat in [190, 248, 260, 300, 420] {
            let result = cells([long, "今天", "今"], row: row)
            expect(result.count == 3, "every candidate has a cell")
            expect(result[0].width == max(60, row * 0.70) && result[0].shortened, "a long candidate takes 70 % of a \(row) pt row")
            expect(result[0].text == long, "the full text stays with the cell (expanded panel and VoiceOver)")
            expect(!result[1].shortened && result[1].width == 59 && result[2].width == 39, "short candidates keep their own width")
            expect(result.allSatisfy { $0.width <= row * 0.70 + 0.001 || $0.width == 60 }, "no cell is wider than the limit")
        }
        // A long mixed candidate with the paw mark is limited the same way.
        let mixed = cells(["我想食べる寿司和ラーメン", "我"], japanese: [0], row: 248)
        expect(mixed[0].japanese && mixed[0].shortened && mixed[0].width == 248 * 0.70, "a long Japanese candidate is shortened too")
        // Narrow rows never go below a usable width.
        expect(cells(["蘇軾蘇軾蘇軾"], row: 40)[0].width == 60, "the limit never drops below 60 pt")
        expect(cells([]).isEmpty, "no candidates, no cells")
    }

    static func japanese() {
        // Several Japanese readings: only the first one is shown in the row.
        let texts = ["蘇軾", "寿司", "素食", "すし", "速食", "スシ", "鮨"]
        let result = cells(texts, japanese: [1, 3, 5, 6])
        expect(result.filter(\.japanese).map(\.text) == ["寿司"], "one Japanese candidate in the collapsed row")
        expect(result.map(\.text) == ["蘇軾", "寿司", "素食", "速食"], "Chinese candidates keep their ranked order")
        expect(result.map(\.index) == [0, 1, 2, 4], "cells remember which candidate they are")
        // Only Japanese candidates: still one.
        expect(cells(["すし", "スシ"], japanese: [0, 1]).map(\.text) == ["すし"], "all Japanese: the first one")
        // No Japanese: nothing is dropped.
        expect(cells(["你好", "妳好", "你號"]).count == 3, "Chinese only: all shown")
        // In Japanese mode the view passes no Japanese indices, so everything shows.
        expect(cells(["寿司", "すし", "鮨"], japanese: []).count == 3, "Japanese mode shows every candidate")
    }

    static func order() {
        // A Japanese candidate ranked late moves up behind the first candidate
        // when it would otherwise be off the first screen.
        let widths: [CGFloat] = [160, 54, 80, 60, 54, 90, 80]
        for available: CGFloat in [120, 160, 206, 320] {
            for japanese in [2, 5] {
                let result = CandidateStripLayout.order(widths: widths, japanese: [japanese, 6], available: available, spacing: 6)
                expect(result.filter { [japanese, 6].contains($0) } == [japanese], "one Japanese candidate at \(available)")
                expect(result.filter { ![japanese, 6].contains($0) } == [0, 1, 2, 3, 4, 5].filter { $0 != japanese }, "Chinese order is kept at \(available)")
                let position = result.firstIndex(of: japanese)!
                let used = result.prefix(position + 1).reduce(CGFloat(0)) { $0 + widths[$1] } + CGFloat(position) * 6
                expect(position == 1 || used <= available, "the Japanese candidate is on the first screen at \(available)")
            }
        }
        expect(CandidateStripLayout.order(widths: [44, 44, 44, 44], japanese: [1], available: 200, spacing: 6) == [0, 1, 2, 3],
               "a Japanese candidate that already fits stays where it is")
        expect(CandidateStripLayout.order(widths: [44, 44, 44, 44], japanese: [3], available: 100, spacing: 6) == [0, 3, 1, 2],
               "one that does not fit moves to second place")
        expect(CandidateStripLayout.order(widths: [60, 60, 60], japanese: [0, 2], available: 300, spacing: 6) == [0, 1],
               "a Japanese candidate ranked first stays first")
        // A complete mixed sentence the engine prefers is pinned to the front.
        expect(CandidateStripLayout.order(widths: [60, 60, 60], japanese: [2], available: 300, spacing: 6, pinnedFirst: 2) == [2, 0, 1],
               "a pinned candidate leads the row")
        expect(CandidateStripLayout.order(widths: [], japanese: [], available: 100, spacing: 6).isEmpty, "empty in, empty out")
    }

    static func grid() {
        // Expanded panel: each candidate takes the columns its text needs.
        let rows = ExpandedGridLayout.rows(widths: [40, 40, 40, 40, 130, 40, 300, 40], columnWidth: 60, columns: 4)
        expect(rows.map { $0.map(\.index) } == [[0, 1, 2, 3], [4, 5], [6], [7]], "candidates fill rows in ranked order")
        expect(rows.allSatisfy { $0.reduce(0) { $0 + $1.columns } == 4 }, "every row is full width")
        expect(rows[1].map(\.columns) == [3, 1] && rows[2][0].columns == 4, "wide candidates take more columns, never more than a row")
        expect(ExpandedGridLayout.rows(widths: [10], columnWidth: 0, columns: 4).isEmpty && ExpandedGridLayout.rows(widths: [], columnWidth: 60, columns: 4).isEmpty,
               "nothing to lay out")
    }
}
