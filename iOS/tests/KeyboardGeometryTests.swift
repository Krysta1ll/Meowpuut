import Foundation
import CoreGraphics

// Layout of the candidate region and the keys (docs/DESIGN.md §5–6).

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (KeyboardGeometryTests.swift:\(line))")
        exit(1)
    }
}

@main
struct KeyboardGeometryTests {
    static let phoneWidths: [CGFloat] = [320, 375, 390, 393, 402, 414, 430, 440]
    static let landscapeWidths: [CGFloat] = [568, 667, 750, 844, 852, 932, 956]
    static let padWidths: [CGFloat] = [744, 768, 820, 834, 1024, 1180, 1366]

    static func main() {
        specNumbers()
        regions()
        keyRows()
        hitTesting()
        balloons()
        heights()
        glassRims()
        narrowPad()
        pixels()
        print("PASS: region layout, key rows, hit testing without dead zones, balloons, heights, glass rims, the floating iPad keyboard and whole-pixel keys")
    }

    /// The cats line up with the glass's upper rim, wherever the shape puts it.
    static func glassRims() {
        for (width, idiom) in [(CGFloat(320), KeyboardIdiom.phone), (393, .phone), (440, .phone), (852, .phoneLandscape), (1024, .pad)] {
            let capsule = CandidateRegionLayout(width: width, idiom: idiom)
            for rim in [CGFloat(0.065), 0.16] {
                let shaped = CandidateRegionLayout(width: width, idiom: idiom, rim: rim)
                let down = rim * capsule.glass.height
                expect(shaped.glass == capsule.glass && shaped.height == capsule.height, "\(width): the glass itself does not move")
                expect(abs(shaped.mimimo.minY - capsule.mimimo.minY - down) < 0.001 && abs(shaped.momo.minY - capsule.momo.minY - down) < 0.001,
                       "\(width): both cats move down with the rim")
                expect(shaped.mimimo.maxY <= shaped.height && shaped.momo.maxY <= shaped.height, "\(width): and stay inside the region")
                // The candidates sit between the rim and the glass's bottom; the expand key is centred on them.
                expect(abs(shaped.chevron.midY - capsule.chevron.midY - down / 2) < 0.001, "\(width): the expand key follows the candidate band")
                expect(shaped.chevron.maxY <= shaped.height + 0.001, "\(width): the expand key stays inside the region")
            }
            // 米米莫's lower eyelid sits on the rim.
            let cloud = CandidateRegionLayout(width: width, idiom: idiom, rim: 0.16)
            let eye = cloud.mimimo.minY + cloud.mimimo.height * CandidateRegionLayout.mimimoEyeBottom
            expect(abs(eye - (cloud.glass.minY + 0.16 * cloud.glass.height)) < 0.001, "\(width): eyes on the cloud's base rim")
        }
    }

    /// An iPad's floating keyboard is 320 pt wide: phone layout, not iPad sizes.
    static func narrowPad() {
        expect(KeyboardIdiom.resolve(width: 320, pad: true, compactHeight: false) == .phone, "floating iPad keyboard uses the phone layout")
        expect(KeyboardIdiom.resolve(width: 744, pad: true, compactHeight: false) == .pad && KeyboardIdiom.resolve(width: 1366, pad: true, compactHeight: true) == .pad,
               "a docked iPad keyboard keeps the iPad layout")
        expect(KeyboardIdiom.resolve(width: 393, pad: false, compactHeight: false) == .phone
               && KeyboardIdiom.resolve(width: 852, pad: false, compactHeight: true) == .phoneLandscape, "phones go by their height class")
        let floating = CandidateRegionLayout(width: 320, idiom: KeyboardIdiom.resolve(width: 320, pad: true, compactHeight: false))
        expect(floating.height <= 76 && floating.row.width >= 180, "the floating keyboard has a phone-sized candidate row (\(floating.row.width))")
    }

    /// Key edges on whole pixels, with gaps that stay even.
    static func pixels() {
        func whole(_ value: CGFloat, _ scale: CGFloat) -> Bool { abs(value * scale - (value * scale).rounded()) < 0.001 }
        for (width, scale) in [(CGFloat(393), CGFloat(3)), (402, 3), (375, 2), (375, 3), (430, 3), (320, 2), (834, 2)] {
            for rows in [4, 5] {
                let idiom: KeyboardIdiom = width > 700 ? .pad : .phone
                let metrics = KeyMetrics(idiom: idiom, width: width, rows: rows)
                let columns = rows == 5 ? 11 : 10
                let specs = Array(repeating: KeyRowSpec(units: Array(repeating: 1, count: columns), alignment: .centered), count: rows)
                let placed = KeyGridLayout.place(rows: specs, width: width, referenceColumns: columns, metrics: metrics)
                let snapped = placed.map { $0.map { KeyGridLayout.snap($0.frame.offsetBy(dx: 0, dy: 82), scale: scale) } }
                for (r, row) in snapped.enumerated() {
                    for (c, frame) in row.enumerated() {
                        expect(whole(frame.minX, scale) && whole(frame.maxX, scale) && whole(frame.minY, scale) && whole(frame.maxY, scale),
                               "\(width)@\(scale)x: key \(r),\(c) sits on whole pixels")
                        let exact = placed[r][c].frame.offsetBy(dx: 0, dy: 82)
                        expect(abs(frame.minX - exact.minX) <= 0.5 / scale + 0.001 && abs(frame.width - exact.width) <= 1 / scale + 0.001,
                               "\(width)@\(scale)x: a key moves by half a pixel at most")
                    }
                    let gaps = zip(row, row.dropFirst()).map { $1.minX - $0.maxX }
                    expect((gaps.max() ?? 0) - (gaps.min() ?? 0) <= 1 / scale + 0.001, "\(width)@\(scale)x: gaps differ by a pixel at most")
                }
            }
        }
        expect(KeyGridLayout.snap(CGRect(x: 42.3, y: 90.5, width: 33.3, height: 42), scale: 3) == CGRect(x: 127 / 3.0, y: 272 / 3.0, width: 100 / 3.0, height: 125 / 3.0)
               || abs(KeyGridLayout.snap(CGRect(x: 42.3, y: 90.5, width: 33.3, height: 42), scale: 3).minX * 3 - 127) < 0.001, "42.3 pt is pixel 127 at 3x")
    }

    /// The numbers written in DESIGN.md §5 for a 393 pt phone.
    static func specNumbers() {
        let phone = CandidateRegionLayout(width: 393, idiom: .phone)
        expect(phone.scale == 1 && phone.height == 82, "phone region is 82 pt")
        expect(phone.glass.height == 46 && phone.glass.maxY == 80, "glass is 46 pt, 2 pt above the keys")
        expect(phone.mimimo.height == 62 && phone.mimimo.minX == 2, "米米莫 is 62 pt at the left edge")
        expect(phone.momo.height == 54 && abs(phone.momo.maxX - 389) < 0.001, "莫莫 is 54 pt, 4 pt from the right edge")
        expect(phone.tail.height == 28 && abs(phone.tail.maxY - 81) < 0.001, "tail is 28 pt and ends 1 pt below the glass")
        expect(phone.fontSize == 20 && phone.pillHeight == 32, "20 pt candidates in a 32 pt capsule")
        // His eyes rest on the glass's upper rim; her eye line is 1 pt above it.
        expect(abs(phone.mimimo.minY + 62 * CandidateRegionLayout.mimimoEyeBottom - phone.glass.minY) < 0.001, "米米莫's eyes sit on the rim")
        expect(abs(phone.momo.minY + 54 * CandidateRegionLayout.momoEyeLine - (phone.glass.minY - 1)) < 0.001, "莫莫's eye line is just above the rim")
        let landscape = CandidateRegionLayout(width: 800, idiom: .phoneLandscape)
        expect(landscape.height == 63 && landscape.glass.height == 36 && landscape.mimimo.height == 46 && landscape.momo.height == 40,
               "landscape phone numbers")
        let pad = CandidateRegionLayout(width: 820, idiom: .pad)
        expect(pad.height == 94 && pad.glass.height == 53 && pad.mimimo.height == 71 && pad.momo.height == 62 && pad.fontSize == 21,
               "iPad numbers")
        let keys = KeyMetrics(idiom: .phone, width: 393, rows: 4)
        expect(keys.sideMargin == 3 && keys.keyGap == 6 && keys.keyHeight == 42 && keys.rowGap == 11 && keys.cornerRadius == 8.5
               && keys.letterFont == 23, "phone key metrics")
        let zhuyin = KeyMetrics(idiom: .phone, width: 393, rows: 5)
        expect(zhuyin.keyHeight == 37 && zhuyin.rowGap == 7.5 && zhuyin.letterFont == 21, "Zhuyin key metrics")
    }

    static func regions() {
        let cases = phoneWidths.map { ($0, KeyboardIdiom.phone) } + landscapeWidths.map { ($0, .phoneLandscape) }
            + padWidths.map { ($0, .pad) }
        for (width, idiom) in cases {
            let layout = CandidateRegionLayout(width: width, idiom: idiom)
            let bounds = CGRect(x: 0, y: 0, width: width, height: layout.height)
            expect(layout.height == layout.height.rounded(), "whole-point height at \(width)")
            expect(bounds.contains(layout.glass), "glass inside the region at \(width)")
            expect(layout.mimimo.minY >= -0.5 && layout.mimimo.minX >= 0, "米米莫's ears are not cut off at \(width)")
            expect(layout.momo.minY >= -0.5 && layout.momo.maxX <= width, "莫莫 is not cut off at \(width)")
            expect(layout.mimimo.minX < layout.glass.minX && layout.glass.minX < layout.mimimo.maxX, "the glass covers part of 米米莫 at \(width)")
            expect(layout.momo.maxX >= layout.glass.maxX && layout.momo.minX < layout.glass.maxX, "莫莫 rests on the right end at \(width)")
            // Row, expand key and 莫莫 from left to right, without overlap.
            expect(layout.row.minX >= layout.glass.minX && layout.row.maxX <= layout.chevron.minX + 0.001, "row ends at the expand key at \(width)")
            expect(layout.chevron.maxX <= layout.glass.maxX, "expand key is on the glass at \(width)")
            expect(layout.chevron.width >= 30 && layout.chevron.height >= 36, "expand key is large enough to tap at \(width)")
            expect(layout.row.minX < layout.mimimo.maxX - 12 * layout.scale && layout.row.width >= width * 0.7,
                   "candidates start over 米米莫 and get the glass's inner width at \(width) (\(layout.row.width))")
            expect(layout.pillHeight <= layout.glass.height - 8, "the selected capsule fits inside the glass at \(width)")
            expect(layout.maximumCandidateWidth == max(60, layout.row.width * 0.70), "one candidate takes at most 70 % of the row")
            // The tail starts at his body and stays inside the left part of the glass.
            expect(layout.tail.minX >= layout.mimimo.minX && layout.tail.maxX < layout.glass.minX + layout.glass.width * 0.45,
                   "tail ends in the left part of the glass at \(width)")
            expect(layout.tail.maxY <= layout.height + 4, "tail stays above the keys at \(width)")

            let plain = CandidateRegionLayout(width: width, idiom: idiom, cats: false)
            expect(plain.height < layout.height && plain.mimimo == .zero && plain.momo == .zero, "without cats the region is lower")
            expect(plain.row.maxX <= plain.chevron.minX + 0.001 && plain.chevron.maxX <= plain.glass.maxX, "plain row and expand key fit")
        }
        // Scale follows the width between the limits.
        expect(CandidateRegionLayout(width: 320, idiom: .phone).scale == 0.9, "scale stops at 0.9")
        expect(CandidateRegionLayout(width: 500, idiom: .phone).scale == 1.15, "scale stops at 1.15")
        expect(CandidateRegionLayout(width: .nan, idiom: .phone).width == 320, "an invalid width falls back to 320")
    }

    static func keyRows() {
        let letters = [KeyRowSpec(units: Array(repeating: 1, count: 10), alignment: .centered),
                       KeyRowSpec(units: Array(repeating: 1, count: 9), alignment: .centered),
                       KeyRowSpec(units: [1.32] + Array(repeating: 1, count: 7) + [1.32], alignment: .splitEdges),
                       KeyRowSpec(units: [1.25, 1.25, 0, 2.25], alignment: .justified)]
        for width in phoneWidths + landscapeWidths + padWidths {
            let idiom: KeyboardIdiom = padWidths.contains(width) ? .pad : (landscapeWidths.contains(width) ? .phoneLandscape : .phone)
            let metrics = KeyMetrics(idiom: idiom, width: width, rows: 4)
            let rows = KeyGridLayout.place(rows: letters, width: width, referenceColumns: 10, metrics: metrics)
            expect(rows.map(\.count) == [10, 9, 9, 4], "every key is placed")
            for row in rows {
                for (a, b) in zip(row, row.dropFirst()) {
                    expect(b.frame.minX - a.frame.maxX >= metrics.keyGap - 0.001, "keys do not overlap at \(width)")
                }
                expect(row.first!.frame.minX >= metrics.sideMargin - 0.001 && row.last!.frame.maxX <= width - metrics.sideMargin + 0.001,
                       "keys stay inside the side margins at \(width)")
            }
            // Top row fills the width; the second row is centred under it.
            expect(abs(rows[0].first!.frame.minX - metrics.sideMargin) < 0.001 && abs(rows[0].last!.frame.maxX - (width - metrics.sideMargin)) < 0.001,
                   "top row fills the width at \(width)")
            expect(abs((rows[1].first!.frame.minX - 0) - (width - rows[1].last!.frame.maxX)) < 0.001, "second row is centred at \(width)")
            // Shift and Delete sit at the edges, the letters keep the unit width.
            expect(abs(rows[2].first!.frame.minX - metrics.sideMargin) < 0.001 && abs(rows[2].last!.frame.maxX - (width - metrics.sideMargin)) < 0.001,
                   "Shift and Delete sit at the edges at \(width)")
            expect(abs(rows[2][1].frame.width - rows[0][0].frame.width) < 0.001, "third-row letters are as wide as the others")
            // The space bar takes what is left of the bottom row.
            expect(rows[3][2].frame.width > rows[3][3].frame.width, "space is the widest key at \(width)")
            expect(abs(rows[3].last!.frame.maxX - (width - metrics.sideMargin)) < 0.001, "bottom row fills the width at \(width)")
        }
    }

    /// The key whose frame is nearest to `x` in a row.
    static func nearest(_ row: [KeyPlacement], x: CGFloat) -> Set<Int> {
        func distance(_ key: KeyPlacement) -> CGFloat { x < key.frame.minX ? key.frame.minX - x : max(0, x - key.frame.maxX) }
        let best = row.map(distance).min()!
        return Set(row.filter { distance($0) <= best + 0.0001 }.map(\.column))
    }

    static func hitTesting() {
        let specs = [KeyRowSpec(units: Array(repeating: 1, count: 11), alignment: .centered),
                     KeyRowSpec(units: Array(repeating: 1, count: 10), alignment: .centered),
                     KeyRowSpec(units: [1.32] + Array(repeating: 1, count: 7) + [1.32], alignment: .splitEdges),
                     KeyRowSpec(units: [1.32, 1.4, 1.4, 1.4, 1.4, 1.4, 1.32], alignment: .justified),
                     KeyRowSpec(units: [1.25, 1.25, 1.25, 0, 2.25], alignment: .justified)]
        for width in phoneWidths + landscapeWidths + padWidths {
            let idiom: KeyboardIdiom = padWidths.contains(width) ? .pad : (landscapeWidths.contains(width) ? .phoneLandscape : .phone)
            let metrics = KeyMetrics(idiom: idiom, width: width, rows: specs.count)
            let rows = KeyGridLayout.place(rows: specs, width: width, referenceColumns: 11, metrics: metrics)
            let height = metrics.areaHeight(rows: specs.count)
            var y: CGFloat = 0
            while y < height {
                // Rows meet in the middle of the gap between them.
                let distances = rows.map { row -> CGFloat in
                    let frame = row[0].frame
                    return y < frame.minY ? frame.minY - y : max(0, y - frame.maxY)
                }
                let best = distances.min()!
                let nearestRows = Set(rows.indices.filter { distances[$0] <= best + 0.01 })
                var x: CGFloat = 0
                while x < width {
                    guard let hit = KeyGridLayout.key(at: CGPoint(x: x, y: y), in: rows) else {
                        print("FAIL: dead zone at \(x), \(y) for width \(width)")
                        exit(1)
                    }
                    expect(nearestRows.contains(hit.row), "point \(x), \(y) belongs to the nearest row at \(width)")
                    expect(nearest(rows[hit.row], x: x).contains(hit.column), "point \(x), \(y) belongs to the nearest key at \(width)")
                    x += 2.3
                }
                y += 1.7
            }
            // Above the area nothing is hit. The view takes touches down to its
            // bottom edge, so that edge and anything below go to the last row.
            expect(KeyGridLayout.key(at: CGPoint(x: 10, y: -1), in: rows) == nil, "above the keys is not a key")
            expect(KeyGridLayout.key(at: CGPoint(x: 10, y: height), in: rows)?.row == specs.count - 1
                   && KeyGridLayout.key(at: CGPoint(x: 10, y: height + 0.5), in: rows)?.row == specs.count - 1, "the bottom edge belongs to the last row")
            let topLeft = KeyGridLayout.key(at: .zero, in: rows)!, bottomRight = KeyGridLayout.key(at: CGPoint(x: width - 0.01, y: height - 0.01), in: rows)!
            expect(topLeft == (0, 0) && bottomRight == (specs.count - 1, specs.last!.units.count - 1), "corners belong to the corner keys")
            // Touches beside the keys (the safe-area margin of a landscape phone) go to the end keys.
            expect(KeyGridLayout.key(at: CGPoint(x: -40, y: 10), in: rows)! == (0, 0), "left of the keys is the first key")
            expect(KeyGridLayout.key(at: CGPoint(x: width + 40, y: 10), in: rows)! == (0, 10), "right of the keys is the last key")
        }
    }

    static func balloons() {
        for idiom in [KeyboardIdiom.phone, .phoneLandscape] {
            for width: CGFloat in idiom == .phone ? phoneWidths : landscapeWidths {
                let metrics = KeyMetrics(idiom: idiom, width: width, rows: 4)
                let rows = KeyGridLayout.place(rows: [KeyRowSpec(units: Array(repeating: 1, count: 10), alignment: .centered)],
                                               width: width, referenceColumns: 10, metrics: metrics)
                for key in rows[0] {
                    let bubble = KeyBalloonGeometry.bubble(for: key.frame, keyboardWidth: width, idiom: idiom)
                    expect(bubble.minX >= 2 && bubble.maxX <= width - 2, "balloon stays inside the keyboard at \(width)")
                    expect(bubble.width >= key.frame.width && bubble.maxY <= key.frame.minY + 2.001, "balloon is wider than its key and sits above it")
                    expect(bubble.intersects(key.frame.insetBy(dx: 0, dy: -4)), "balloon is attached to its key")
                    let path = KeyBalloonGeometry.path(key: key.frame, bubble: bubble, keyRadius: metrics.cornerRadius)
                    let box = path.boundingBoxOfPath
                    expect(box.minX >= 0 && box.maxX <= width && !path.isEmpty, "balloon outline stays on screen at \(width)")
                    expect(path.contains(CGPoint(x: key.frame.midX, y: key.frame.midY)) && path.contains(CGPoint(x: bubble.midX, y: bubble.midY)),
                           "outline covers the key and the bubble")
                }
                // Edge keys: the bubble moves inward instead of leaving the screen.
                let first = KeyBalloonGeometry.bubble(for: rows[0][0].frame, keyboardWidth: width, idiom: idiom)
                let middle = KeyBalloonGeometry.bubble(for: rows[0][4].frame, keyboardWidth: width, idiom: idiom)
                expect(first.midX > rows[0][0].frame.midX && abs(middle.midX - rows[0][4].frame.midX) < 0.001, "edge balloons move inward")
            }
        }
    }

    static func heights() {
        // Letters and symbols share one height; Zhuyin is taller by its extra row.
        for width in phoneWidths {
            let region = CandidateRegionLayout(width: width, idiom: .phone)
            let four = KeyboardHeight.preferred(region: region, metrics: KeyMetrics(idiom: .phone, width: width, rows: 4), rows: 4)
            let five = KeyboardHeight.preferred(region: region, metrics: KeyMetrics(idiom: .phone, width: width, rows: 5), rows: 5)
            expect(four >= 270 && four <= 320, "letters height \(four) at \(width)")
            expect(five > four && five - four <= 20, "Zhuyin adds little height at \(width)")
        }
        expect(KeyboardHeight.preferred(region: CandidateRegionLayout(width: 393, idiom: .phone),
                                        metrics: KeyMetrics(idiom: .phone, width: 393, rows: 4), rows: 4) == 295, "393 pt phone is 295 pt tall")
        for width in landscapeWidths {
            let region = CandidateRegionLayout(width: width, idiom: .phoneLandscape)
            let four = KeyboardHeight.preferred(region: region, metrics: KeyMetrics(idiom: .phoneLandscape, width: width, rows: 4), rows: 4)
            expect(four <= 220, "landscape keeps room for the text above (\(four))")
        }
    }
}
