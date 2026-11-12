import CoreGraphics

// Pure geometry for the keyboard extension (docs/DESIGN.md §5). Nothing here
// touches UIKit, so it is unit tested on macOS (iOS/tests).

enum KeyboardIdiom: Equatable {
    case phone, phoneLandscape, pad

    /// Below this width an iPad keyboard is the floating one (320 pt) and
    /// is laid out like a phone's.
    static let narrowPadWidth: CGFloat = 500

    /// Landscape phones have far less vertical room; iPads are wide, except
    /// the floating keyboard.
    static func resolve(width: CGFloat, pad: Bool, compactHeight: Bool) -> KeyboardIdiom {
        if pad { return width < narrowPadWidth ? .phone : .pad }
        return compactHeight ? .phoneLandscape : .phone
    }
}

/// The bar's frame was laid out around the earlier cats: one behind the
/// glass's left end, one with her chin on its right end. The bar's design is
/// kept exactly as it was, so their sizes stay here as layout constants. The
/// cats drawn now are placed on this frame by CatSprites.swift.
private enum BarFrame {
    static let leftAspect: CGFloat = 0.9135
    static let rightAspect: CGFloat = 1.0543
    static let rightEyeLine: CGFloat = 0.50
    /// The right figure's outline below the cheek, which the expand key was tucked under.
    static func rightChinEdge(_ y: CGFloat) -> CGFloat { max(0, y - 0.66) }
}

/// The candidate region above the keys: the glass bar, the candidate row and
/// the expand key. 米米莫 and 莫莫 sit on the glass's edge (`stage(shape:)`).
struct CandidateRegionLayout: Equatable {
    /// chevron.down at 15 pt semibold, the expand key's glyph.
    static let chevronGlyph = CGSize(width: 17.5, height: 10.5)

    var width: CGFloat
    var height: CGFloat
    var scale: CGFloat
    var idiom: KeyboardIdiom
    var cats: Bool
    var glass: CGRect
    var row: CGRect
    var chevron: CGRect
    /// Width of the light band at the start of the row (light appearance); 0 without the cats.
    var lightBand: CGFloat
    var fontSize: CGFloat
    var pillHeight: CGFloat
    /// Size of the small fixed texts in the bar (status, toast, panel header).
    /// They do not follow the system text size: the bar's geometry is fixed.
    var statusFontSize: CGFloat

    /// `rim` is where the glass's upper rim sits below the top of its rect,
    /// as a fraction of the glass height: 0 for the capsule, the wave's
    /// amplitude, the height of the cloud's bumps. The expand key lines up
    /// with the band the candidates sit in.
    init(width rawWidth: CGFloat, idiom: KeyboardIdiom, cats: Bool = true, rim: CGFloat = 0) {
        let width = max(1, rawWidth.isFinite ? rawWidth : 320)
        let s: CGFloat
        let regionHeight, glassHeight, leftHeight, rightHeight, font: CGFloat
        switch idiom {
        case .phone:
            s = min(1.15, max(0.9, width / 393))
            regionHeight = 82 * s; glassHeight = 46 * s
            leftHeight = 62 * s; rightHeight = 54 * s
            font = min(21, max(19, 20 * s))
        case .phoneLandscape:
            s = 1
            regionHeight = 63; glassHeight = 36
            leftHeight = 46; rightHeight = 40
            font = 18
        case .pad:
            s = 1.15
            regionHeight = 94; glassHeight = 53
            leftHeight = 71; rightHeight = 62
            font = 21
        }
        self.width = width
        self.scale = s
        self.idiom = idiom
        self.cats = cats
        fontSize = font.rounded()
        pillHeight = (glassHeight * 0.70).rounded()
        statusFontSize = idiom == .pad ? 15 : (idiom == .phoneLandscape ? 12 : (13 * s).rounded())
        let rimY = max(0, min(0.3, rim)) * glassHeight

        if cats {
            height = regionHeight.rounded()
            let glassBottom = height - 2 * s
            let top = glassBottom - glassHeight
            let leftWidth = leftHeight * BarFrame.leftAspect
            let rightWidth = rightHeight * BarFrame.rightAspect
            let right = CGRect(x: width - 4 * s - rightWidth, y: top + rimY - 1 * s - rightHeight * BarFrame.rightEyeLine,
                               width: rightWidth, height: rightHeight)
            let glassLeft = 2 * s + 0.24 * leftWidth
            glass = CGRect(x: glassLeft, y: top, width: max(1, width - 6 * s - glassLeft), height: glassHeight)
            // Centred on the band the candidates sit in, a little low to
            // match the text's optical centre; its touch area ends at the keys.
            let chevronY = glass.midY + rimY / 2 + 2 * s
            let chevronSize = CGSize(width: 34 * s, height: min(44, glassHeight, 2 * (height - chevronY)))
            let glyphTop = (chevronY - Self.chevronGlyph.height / 2 - right.minY) / right.height
            let chin = right.minX + right.width * BarFrame.rightChinEdge(glyphTop)
            chevron = CGRect(x: chin - 2 * s - Self.chevronGlyph.width / 2 - chevronSize.width / 2, y: chevronY - chevronSize.height / 2,
                             width: chevronSize.width, height: chevronSize.height)
            let rowLeft = glass.minX + 12 * s
            row = CGRect(x: rowLeft, y: glass.minY, width: max(1, chevron.minX - rowLeft), height: glassHeight)
            lightBand = 2 * s + leftWidth + 10 * s - rowLeft
        } else {
            height = (glassHeight + 12 * s).rounded()
            glass = CGRect(x: 4 * s, y: (height - glassHeight) / 2, width: max(1, width - 8 * s), height: glassHeight)
            let chevronSize = CGSize(width: 40 * s, height: min(44, glassHeight))
            chevron = CGRect(x: glass.maxX - 6 * s - chevronSize.width, y: min(glass.midY + rimY / 2, height - chevronSize.height / 2) - chevronSize.height / 2,
                             width: chevronSize.width, height: chevronSize.height)
            row = CGRect(x: glass.minX + glassHeight * 0.3, y: glass.minY,
                         width: max(1, chevron.minX - glass.minX - glassHeight * 0.3), height: glassHeight)
            lightBand = 0
        }
    }

    /// Longest a single candidate may be before it is shortened in the middle.
    var maximumCandidateWidth: CGFloat { max(60, row.width * 0.70) }

    /// The bar as the cats see it (CatSprites.swift). They take the height
    /// above the glass less 1.5 pt: a keyboard cannot draw above its own top
    /// edge, and a cat breathes.
    func stage(shape: GlassShape) -> CatStage {
        let band = GlassEdge.band(shape, height: glass.height)
        let middle = glass.minY + (band.top + band.bottom) / 2
        return CatStage(size: CGSize(width: width, height: height), glass: glass, shape: shape, unit: scale,
                        room: glass.minY - 1.5,
                        row: CGRect(x: row.minX, y: middle - pillHeight / 2, width: row.width, height: pillHeight),
                        arrow: CGRect(x: chevron.midX - Self.chevronGlyph.width / 2, y: chevron.midY - Self.chevronGlyph.height / 2,
                                      width: Self.chevronGlyph.width, height: Self.chevronGlyph.height))
    }
}

/// Key metrics in the proportions of Apple's keyboard (DESIGN.md §5).
struct KeyMetrics: Equatable {
    var sideMargin: CGFloat
    var keyGap: CGFloat
    var keyHeight: CGFloat
    var rowGap: CGFloat
    var topPadding: CGFloat
    var bottomPadding: CGFloat
    var cornerRadius: CGFloat
    var letterFont: CGFloat
    var utilityFont: CGFloat

    init(idiom: KeyboardIdiom, width: CGFloat, rows: Int) {
        let dense = rows >= 5
        switch idiom {
        case .phone:
            let s = min(1.06, max(0.94, width / 393))
            sideMargin = 3; keyGap = 6
            keyHeight = ((dense ? 37 : 42) * s).rounded()
            rowGap = dense ? 7.5 : 11
            topPadding = dense ? 6 : 8; bottomPadding = 4
            cornerRadius = 8.5; letterFont = dense ? 21 : 23; utilityFont = 15
        case .phoneLandscape:
            sideMargin = 3; keyGap = 6
            keyHeight = dense ? 28 : 32
            rowGap = dense ? 5 : 6
            topPadding = 5; bottomPadding = 3
            cornerRadius = 7; letterFont = dense ? 19 : 21; utilityFont = 14
        case .pad:
            sideMargin = 6; keyGap = 9
            keyHeight = dense ? 46 : 52
            rowGap = dense ? 8 : 10
            topPadding = 8; bottomPadding = 6
            cornerRadius = 9; letterFont = dense ? 23 : 25; utilityFont = 17
        }
    }

    func areaHeight(rows: Int) -> CGFloat {
        topPadding + CGFloat(rows) * keyHeight + CGFloat(max(0, rows - 1)) * rowGap + bottomPadding
    }
}

/// One key's place: what is drawn and the larger area that answers touches.
struct KeyPlacement: Equatable {
    var row: Int
    var column: Int
    var frame: CGRect
    var hitFrame: CGRect
}

enum KeyRowAlignment: Equatable {
    /// Keys use the reference unit width and are centred (letters row 2).
    case centered
    /// Keys stretch to fill (bottom row, symbol row 3).
    case justified
    /// First and last keys sit at the edges, the rest are centred between
    /// them at the reference width (Shift … Delete on the letters).
    case splitEdges
}

struct KeyRowSpec: Equatable {
    /// Relative widths; 0 marks the single flexible key (space).
    var units: [CGFloat]
    var alignment: KeyRowAlignment
    /// Extra gap before the first and after the last key (shift and delete).
    var edgeGap: CGFloat = 0
}

enum KeyGridLayout {
    /// Lays out rows inside `area` (keys-area coordinates, origin at its top).
    /// `referenceColumns` sets the unit width: 10 for letters, 11 for Zhuyin.
    /// Hit frames tile the whole area: horizontally to the middle of each gap
    /// (and to the area's edges at the ends of a row), vertically to the
    /// middle of the row gap. No touch ever lands in a dead zone.
    static func place(rows: [KeyRowSpec], width: CGFloat, referenceColumns: Int, metrics: KeyMetrics) -> [[KeyPlacement]] {
        let usable = max(1, width - 2 * metrics.sideMargin)
        let unit = max(1, (usable - CGFloat(referenceColumns - 1) * metrics.keyGap) / CGFloat(referenceColumns))
        var result: [[KeyPlacement]] = []
        let totalHeight = metrics.areaHeight(rows: rows.count)
        for (r, spec) in rows.enumerated() {
            let y = metrics.topPadding + CGFloat(r) * (metrics.keyHeight + metrics.rowGap)
            let hitTop = r == 0 ? 0 : y - metrics.rowGap / 2
            let hitBottom = r == rows.count - 1 ? totalHeight : y + metrics.keyHeight + metrics.rowGap / 2
            var widths: [CGFloat]
            let gaps = CGFloat(max(0, spec.units.count - 1)) * metrics.keyGap + 2 * spec.edgeGap
            switch spec.alignment {
            case .centered:
                widths = spec.units.map { $0 * unit + max(0, $0 - 1) * metrics.keyGap }
            case .justified:
                let fixed = spec.units.filter { $0 > 0 }.map { $0 * unit + max(0, $0 - 1) * metrics.keyGap }.reduce(0, +)
                let flexibleCount = spec.units.filter { $0 == 0 }.count
                if flexibleCount > 0 {
                    let flexible = max(unit, (usable - gaps - fixed) / CGFloat(flexibleCount))
                    widths = spec.units.map { $0 == 0 ? flexible : $0 * unit + max(0, $0 - 1) * metrics.keyGap }
                } else {
                    // Scale every key so the row fills the width exactly.
                    let natural = fixed
                    let factor = natural > 0 ? max(0.5, (usable - gaps) / natural) : 1
                    widths = spec.units.map { ($0 * unit + max(0, $0 - 1) * metrics.keyGap) * factor }
                }
            case .splitEdges:
                widths = spec.units.map { $0 * unit + max(0, $0 - 1) * metrics.keyGap }
            }
            var placements: [KeyPlacement] = []
            if spec.alignment == .splitEdges, widths.count >= 3 {
                let inner = Array(widths.dropFirst().dropLast())
                let innerWidth = inner.reduce(0, +) + CGFloat(inner.count - 1) * metrics.keyGap
                var x = metrics.sideMargin + (usable - innerWidth) / 2
                placements.append(KeyPlacement(row: r, column: 0, frame: CGRect(x: metrics.sideMargin, y: y, width: widths[0], height: metrics.keyHeight), hitFrame: .zero))
                for (offset, w) in inner.enumerated() {
                    let frame = CGRect(x: x, y: y, width: w, height: metrics.keyHeight)
                    placements.append(KeyPlacement(row: r, column: offset + 1, frame: frame, hitFrame: .zero))
                    x += w + metrics.keyGap
                }
                let last = widths[widths.count - 1]
                placements.append(KeyPlacement(row: r, column: widths.count - 1,
                                               frame: CGRect(x: metrics.sideMargin + usable - last, y: y, width: last, height: metrics.keyHeight),
                                               hitFrame: .zero))
            } else {
                let rowWidth = widths.reduce(0, +) + gaps
                var x = metrics.sideMargin + max(0, (usable - rowWidth) / 2) + spec.edgeGap
                for (c, w) in widths.enumerated() {
                    let frame = CGRect(x: x, y: y, width: w, height: metrics.keyHeight)
                    placements.append(KeyPlacement(row: r, column: c, frame: frame, hitFrame: frame))
                    x += w + metrics.keyGap
                }
            }
            // Tile the row horizontally.
            for c in placements.indices {
                let left = c == 0 ? 0 : (placements[c - 1].frame.maxX + placements[c].frame.minX) / 2
                let right = c == placements.count - 1 ? width : (placements[c].frame.maxX + placements[c + 1].frame.minX) / 2
                placements[c].hitFrame = CGRect(x: left, y: hitTop, width: max(0, right - left), height: hitBottom - hitTop)
            }
            result.append(placements)
        }
        return result
    }

    /// A rectangle with its four edges moved to the nearest whole pixel.
    /// Neighbouring keys share an edge position, so gaps stay within a pixel
    /// of each other.
    static func snap(_ rect: CGRect, scale: CGFloat) -> CGRect {
        guard scale > 0 else { return rect }
        func pixel(_ value: CGFloat) -> CGFloat { (value * scale).rounded() / scale }
        let minX = pixel(rect.minX), maxX = pixel(rect.maxX), minY = pixel(rect.minY), maxY = pixel(rect.maxY)
        return CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }

    /// The key under a point, or nil above the area. A point on or below the
    /// bottom edge belongs to the last row.
    static func key(at point: CGPoint, in placements: [[KeyPlacement]]) -> (row: Int, column: Int)? {
        let lastRow = placements.count - 1
        for (index, row) in placements.enumerated() {
            guard let first = row.first, point.y >= first.hitFrame.minY,
                  point.y < first.hitFrame.maxY || index == lastRow else { continue }
            for key in row where point.x >= key.hitFrame.minX && point.x < key.hitFrame.maxX {
                return (key.row, key.column)
            }
            // Past either end of the row: the nearest end key.
            return point.x < first.hitFrame.minX ? (first.row, first.column) : row.last.map { ($0.row, $0.column) }
        }
        return nil
    }
}

/// The balloon a phone shows above a pressed character key: the key's own
/// stem widening into a rounded bubble, kept inside the keyboard at both edges.
enum KeyBalloonGeometry {
    static func bubble(for key: CGRect, keyboardWidth: CGFloat, idiom: KeyboardIdiom) -> CGRect {
        let flare: CGFloat = idiom == .phoneLandscape ? 8 : 12
        let height: CGFloat = idiom == .phoneLandscape ? 40 : 54
        let width = min(keyboardWidth - 4, key.width + 2 * flare)
        var x = key.midX - width / 2
        x = min(max(2, x), keyboardWidth - 2 - width)
        return CGRect(x: x, y: key.minY - height + 2, width: width, height: height)
    }

    static func path(key: CGRect, bubble: CGRect, keyRadius: CGFloat) -> CGPath {
        let path = CGMutablePath()
        let neck = min(14, key.height * 0.34)
        let r = min(12, bubble.width / 4)
        let k = min(keyRadius, key.width / 3)
        // Key stem, bottom-left going clockwise in y-down space.
        path.move(to: CGPoint(x: key.minX + k, y: key.maxY))
        path.addQuadCurve(to: CGPoint(x: key.minX, y: key.maxY - k), control: CGPoint(x: key.minX, y: key.maxY))
        path.addLine(to: CGPoint(x: key.minX, y: key.minY + neck * 0.3))
        path.addCurve(to: CGPoint(x: bubble.minX, y: bubble.maxY - neck * 0.25),
                      control1: CGPoint(x: key.minX, y: key.minY - neck * 0.35),
                      control2: CGPoint(x: bubble.minX, y: bubble.maxY + neck * 0.1))
        path.addLine(to: CGPoint(x: bubble.minX, y: bubble.minY + r))
        path.addQuadCurve(to: CGPoint(x: bubble.minX + r, y: bubble.minY), control: CGPoint(x: bubble.minX, y: bubble.minY))
        path.addLine(to: CGPoint(x: bubble.maxX - r, y: bubble.minY))
        path.addQuadCurve(to: CGPoint(x: bubble.maxX, y: bubble.minY + r), control: CGPoint(x: bubble.maxX, y: bubble.minY))
        path.addLine(to: CGPoint(x: bubble.maxX, y: bubble.maxY - neck * 0.25))
        path.addCurve(to: CGPoint(x: key.maxX, y: key.minY + neck * 0.3),
                      control1: CGPoint(x: bubble.maxX, y: bubble.maxY + neck * 0.1),
                      control2: CGPoint(x: key.maxX, y: key.minY - neck * 0.35))
        path.addLine(to: CGPoint(x: key.maxX, y: key.maxY - k))
        path.addQuadCurve(to: CGPoint(x: key.maxX - k, y: key.maxY), control: CGPoint(x: key.maxX, y: key.maxY))
        path.closeSubpath()
        return path
    }
}

/// Total keyboard height for the current keys (the Globe bar below belongs to iOS).
enum KeyboardHeight {
    static func preferred(region: CandidateRegionLayout, metrics: KeyMetrics, rows: Int) -> CGFloat {
        (region.height + metrics.areaHeight(rows: rows)).rounded()
    }
}
