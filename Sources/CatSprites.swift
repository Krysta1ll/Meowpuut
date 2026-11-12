import CoreGraphics
import Foundation

// 米米莫 and 莫莫 on the candidate bar (docs/DESIGN.md §1): the sprites, how one
// is attached to the glass by its paw anchor, the arrangements the two can be
// in, and the arrangement of the day. Geometry only, shared by the iOS
// keyboard, the iOS app's preview and the macOS candidate window.

enum KeyboardCat: String, CaseIterable { case mimimo, momo }

/// The pack's poses for the bar. In the first three the cat is behind the bar
/// with its paws over the top edge. `hold` (standing beside the bar's end)
/// needs room outside the glass that the bar does not leave, so no
/// arrangement uses it.
enum CatPose: String, CaseIterable {
    case rest, peek, lounge, hold

    var asset: String {
        switch self {
        case .rest: return "edge_rest"
        case .peek: return "edge_peek"
        case .lounge: return "edge_lounge"
        case .hold: return "side_hold"
        }
    }
}

/// One picture of a cat. Fractions are of its canvas, origin top left
/// (assets/brand/cats/cats.json; the table is in CatSpriteData.swift).
struct CatSprite: Equatable {
    /// A paw that reaches below the anchor row: centre and half width along
    /// the row, and how far it hangs under it.
    struct Paw: Equatable {
        var x: CGFloat
        var halfWidth: CGFloat
        var depth: CGFloat
    }

    var name: String
    /// Size in points at full scale.
    var size: CGSize
    /// The point that sits on the bar: on the top edge between the paws, or
    /// the paw's tip on the bar's end.
    var anchor: CGPoint
    /// The opaque part.
    var bounds: CGRect
    /// Where the body is cut flat on the anchor row. Empty for a sprite that
    /// is a whole cat.
    var cut: ClosedRange<CGFloat>
    var paws: [Paw]

    /// Cut on its anchor row: the body belongs behind the bar and only the
    /// paws in front of it. A whole cat is drawn in front.
    var behindBar: Bool { cut.upperBound > cut.lowerBound }

    /// The part drawn again in front of the glass, for a canvas of `size`:
    /// each whole paw (a round paw is about 0.7 as tall as it is wide), so an
    /// edge that rises under a paw does not cut into it. Nothing else of the
    /// cut row: its soft edge would show as a line on the glass.
    func pawOutlines(_ size: CGSize) -> [CGRect] {
        let row = anchor.y * size.height
        return paws.map { paw in
            let width = 2 * paw.halfWidth * size.width + 0.8, bottom = row + paw.depth * size.height + 0.4
            let height = max(2 * paw.depth * size.height, 0.7 * width) + 0.4
            return CGRect(x: paw.x * size.width - width / 2, y: bottom - height, width: width, height: height)
        }
    }
}

enum CatArt {
    /// The sprite a cat is drawn with in a pose. Every client asks here, so
    /// this is where another picture can be substituted (a festival's cat:
    /// return its own name, size and anchor, and the stage seats it by them).
    static func sprite(_ cat: KeyboardCat, _ pose: CatPose) -> CatSprite {
        CatSprite.all["\(cat.rawValue)_\(pose.asset)"]!
    }
}

/// The upper edge of the candidate glass (docs/DESIGN.md §2), in the glass's
/// own coordinates: x from its left end, y down from the top of its rect.
/// The same curves as CandidateGlassShape draws.
enum GlassEdge {
    private static func smoothstep(_ edge0: CGFloat, _ edge1: CGFloat, _ x: CGFloat) -> CGFloat {
        let t = min(1, max(0, (x - edge0) / (edge1 - edge0)))
        return t * t * (3 - 2 * t)
    }

    private static func smoothMin(_ a: CGFloat, _ b: CGFloat, _ k: CGFloat) -> CGFloat {
        let h = max(k - abs(a - b), 0) / k
        return min(a, b) - h * h * k / 4
    }

    /// A round end of radius `r` whose top is at `top`: the edge at `d` from the end.
    private static func end(_ d: CGFloat, radius r: CGFloat, top: CGFloat) -> CGFloat {
        let inside = max(0, min(r, d))
        return top + r - (r * r - (r - inside) * (r - inside)).squareRoot()
    }

    static func top(_ style: GlassShape, size: CGSize, x: CGFloat) -> CGFloat {
        let width = size.width, height = size.height
        let fromEnd = min(x, width - x)
        switch style {
        case .capsule:
            return end(fromEnd, radius: height / 2, top: 0)
        case .wave:
            let amplitude = 0.065 * height
            let radius = max(1, height / 2 - amplitude)
            guard fromEnd >= radius else { return end(fromEnd, radius: radius, top: amplitude) }
            let u = (x - radius) / max(1, width - 2 * radius)
            let envelope = smoothstep(0, 0.12, u) * smoothstep(0, 0.12, 1 - u)
            let waves = max(2, (width / 170).rounded())
            return amplitude - amplitude * sin(2 * .pi * waves * u) * envelope
        case .cloud:
            let base = 0.16 * height
            let radius = (height - base) / 2
            guard fromEnd >= radius else { return end(fromEnd, radius: radius, top: base) }
            let smoothing = 0.10 * height
            var y = base
            let bumps: [(x: CGFloat, radius: CGFloat, peak: CGFloat)] = [(0.15, 0.58, 0), (0.47, 0.75, 0.05), (0.77, 0.56, 0.07)]
            for bump in bumps {
                let r = bump.radius * height
                let cy = bump.peak * height + r
                let reach = max(0, r * r - (cy - base) * (cy - base)).squareRoot() + smoothing + 2
                let lower = radius + reach, upper = width - radius - reach
                let cx = upper > lower ? min(max(bump.x * width, lower), upper) : width / 2
                let dx = x - cx
                guard abs(dx) < r else { continue }
                let arc: CGFloat = cy - (r * r - dx * dx).squareRoot()
                y = smoothMin(y, arc, smoothing)
            }
            return y
        }
    }

    /// How far the edge rises above its lowest point between two x
    /// positions: anywhere on the stretch, and over its middle half. A cat
    /// behind the bar is seated on the lowest point, so this much of it is
    /// hidden by the glass.
    static func rise(_ style: GlassShape, size: CGSize, from: CGFloat, to: CGFloat) -> (low: CGFloat, most: CGFloat, middle: CGFloat) {
        let quarter = (to - from) / 4
        var low = -CGFloat.greatestFiniteMagnitude, high = CGFloat.greatestFiniteMagnitude, middle = CGFloat.greatestFiniteMagnitude
        var x = from
        while true {
            let at = min(x, to)
            let y = top(style, size: size, x: at)
            low = max(low, y); high = min(high, y)
            if at >= from + quarter && at <= to - quarter { middle = min(middle, y) }
            if x >= to { break }
            x += 1
        }
        return (low, low - high, max(0, low - middle))
    }

    /// The band the candidates sit in, from the top of the glass's rect
    /// (CandidateGlassShape.contentBand).
    static func band(_ style: GlassShape, height: CGFloat) -> (top: CGFloat, bottom: CGFloat) {
        switch style {
        case .capsule: return (0, height)
        case .wave: return (0.065 * height, height * (1 - 0.065))
        case .cloud: return (0.16 * height, height)
        }
    }

    /// Radius of the glass's round ends.
    static func endRadius(_ style: GlassShape, height: CGFloat) -> CGFloat {
        switch style {
        case .capsule: return height / 2
        case .wave: return height / 2 - 0.065 * height
        case .cloud: return 0.42 * height
        }
    }
}

/// The bar as the cats see it.
struct CatStage: Equatable {
    /// The area the cats are drawn in; nothing of a cat leaves it.
    var size: CGSize
    var glass: CGRect
    var shape: GlassShape
    /// 1 on a 393 pt phone; gaps and tolerances scale with it.
    var unit: CGFloat
    /// The height a cat on the top edge may rise above the glass's rect. It
    /// sets the size of every top pose, the same for the three shapes.
    var room: CGFloat
    /// What is drawn on the glass: the band the candidates and the selected
    /// capsule take, and the expand key's arrow. No cat covers either.
    var row: CGRect
    var arrow: CGRect
}

/// One cat's place, worked out for a stage.
struct CatPlacement: Equatable {
    var cat: KeyboardCat
    var pose: CatPose
    var sprite: CatSprite
    /// The sprite's whole canvas.
    var frame: CGRect
    /// Where the sprite's anchor sits: on the glass's edge.
    var anchor: CGPoint
    /// Degrees, about the anchor.
    var tilt: CGFloat

    var scale: CGFloat { frame.width / sprite.size.width }

    /// The opaque part, before the tilt.
    var body: CGRect {
        CGRect(x: frame.minX + sprite.bounds.minX * frame.width, y: frame.minY + sprite.bounds.minY * frame.height,
               width: sprite.bounds.width * frame.width, height: sprite.bounds.height * frame.height)
    }

    /// The box around the opaque part once it is tilted.
    var visible: CGRect {
        guard tilt != 0 else { return body }
        let turn = CGAffineTransform(translationX: anchor.x, y: anchor.y).rotated(by: tilt * .pi / 180)
            .translatedBy(x: -anchor.x, y: -anchor.y)
        return body.applying(turn)
    }

    /// What of the cat is in front of the glass: the paws that hang over the
    /// edge, or all of a cat that stands in front of the bar.
    var inFront: [CGRect] {
        guard sprite.behindBar else { return [visible] }
        return sprite.paws.map { paw in
            CGRect(x: frame.minX + (paw.x - paw.halfWidth) * frame.width - 0.5, y: anchor.y,
                   width: 2 * paw.halfWidth * frame.width + 1, height: paw.depth * frame.height + 0.5)
        }
    }

    func covers(_ stage: CatStage) -> Bool {
        inFront.contains { $0.intersects(stage.row) || $0.intersects(stage.arrow) }
    }

    /// Where a tap pets this cat: its picture widened to 44 pt, from the top
    /// of the stage down to the glass's edge.
    func touch(on stage: CatStage) -> CGRect {
        let box = visible
        let width = max(44, box.width)
        let x = min(max(0, box.midX - width / 2), max(0, stage.size.width - width))
        return CGRect(x: x, y: 0, width: width, height: max(1, anchor.y))
    }
}

/// A way the two cats sit on the bar. The list is curated: the keyboard only
/// ever shows one of these, never free coordinates.
struct CatArrangement: Equatable, Identifiable {
    struct Seat: Equatable {
        /// Seats on `left` are packed along the top edge from the glass's
        /// left end inward in the order listed, on `right` from its right end.
        enum Side: Equatable { case left, right }
        var cat: KeyboardCat
        var pose: CatPose
        var side: Side
        /// Degrees about the paws; positive leans to the right.
        var tilt: CGFloat = 0
        /// How far this cat's span on the edge overlaps the one listed before
        /// it on the same side (points at unit 1). Negative leaves a gap.
        var overlap: CGFloat = 0
    }

    var id: String
    /// In drawing order: a later cat is in front of an earlier one.
    var seats: [Seat]

    static let all: [CatArrangement] = [
        CatArrangement(id: "ends", seats: [Seat(cat: .mimimo, pose: .peek, side: .left), Seat(cat: .momo, pose: .peek, side: .right)]),
        CatArrangement(id: "left", seats: [Seat(cat: .mimimo, pose: .peek, side: .left, tilt: 3),
                                            Seat(cat: .momo, pose: .peek, side: .left, tilt: -4, overlap: -6)]),
        CatArrangement(id: "right", seats: [Seat(cat: .momo, pose: .peek, side: .right, tilt: -3),
                                             Seat(cat: .mimimo, pose: .peek, side: .right, tilt: 4, overlap: -6)]),
        CatArrangement(id: "lounge", seats: [Seat(cat: .mimimo, pose: .lounge, side: .left), Seat(cat: .momo, pose: .lounge, side: .right)]),
        CatArrangement(id: "rest", seats: [Seat(cat: .mimimo, pose: .rest, side: .left), Seat(cat: .momo, pose: .rest, side: .right)]),
        CatArrangement(id: "mimimoLounge", seats: [Seat(cat: .mimimo, pose: .lounge, side: .left), Seat(cat: .momo, pose: .peek, side: .right)]),
        CatArrangement(id: "momoLounge", seats: [Seat(cat: .mimimo, pose: .peek, side: .left), Seat(cat: .momo, pose: .lounge, side: .right)]),
        CatArrangement(id: "company", seats: [Seat(cat: .momo, pose: .lounge, side: .right),
                                               Seat(cat: .mimimo, pose: .peek, side: .right, overlap: 2)]),
    ]

    static func named(_ id: String) -> CatArrangement? { all.first { $0.id == id } }

    /// The smallest a cat is drawn: under this it no longer reads as a face.
    static let smallest: CGFloat = 0.45

    /// Each cat's place on a stage, or nil when this arrangement does not
    /// fit it: a cat would be smaller than `smallest`, would not find a level
    /// stretch of edge, would cover the candidates or would leave the stage.
    /// A sprite with a cut sits behind the edge with its paws over it; a
    /// whole cat (empty cut, such as a festival's sitting cat) stands on the
    /// edge with its anchor at its feet.
    func place(on stage: CatStage, sprite: (KeyboardCat, CatPose) -> CatSprite = CatArt.sprite,
               smallest: CGFloat = Self.smallest) -> [CatPlacement]? {
        let unit = stage.unit, glass = stage.glass
        // How far into the round end a cat's cut may start, and how much of a
        // cat the edge may hide: at its sides, and under its face.
        let cornerReach = 4 * unit, hiddenSide = 4 * unit, hiddenFace = 1.5 * unit
        var placed: [CatPlacement?] = Array(repeating: nil, count: seats.count)

        /// What of a cat meets the edge, in fractions of its width: the cut, or a whole cat's opaque width.
        func footing(_ sprite: CatSprite) -> ClosedRange<CGFloat> { sprite.behindBar ? sprite.cut : sprite.bounds.minX...sprite.bounds.maxX }
        func span(_ sprite: CatSprite, _ scale: CGFloat) -> CGFloat { (footing(sprite).upperBound - footing(sprite).lowerBound) * sprite.size.width * scale }

        /// A cat on the top edge whose footing starts at `left` (in glass coordinates).
        func top(_ seat: Seat, _ art: CatSprite, _ scale: CGFloat, left: CGFloat) -> CatPlacement? {
            let width = art.size.width * scale, height = art.size.height * scale
            let edge = GlassEdge.rise(stage.shape, size: glass.size, from: left, to: left + span(art, scale))
            // A whole cat is in front of the glass, so a rise under it shows as feet sunk into the edge.
            guard edge.most <= (art.behindBar ? hiddenSide : hiddenFace), edge.middle <= hiddenFace else { return nil }
            let x = glass.minX + left - footing(art).lowerBound * width
            // A paw sinks into the glass by as much as the edge rises under it.
            for paw in art.paws {
                let centre = x - glass.minX + paw.x * width, half = paw.halfWidth * width
                let under = GlassEdge.rise(stage.shape, size: glass.size, from: centre - half, to: centre + half)
                guard edge.low - (under.low - under.most) <= hiddenFace else { return nil }
            }
            let anchor = CGPoint(x: x + art.anchor.x * width, y: glass.minY + edge.low)
            let place = CatPlacement(cat: seat.cat, pose: seat.pose, sprite: art,
                                     frame: CGRect(x: x, y: anchor.y - art.anchor.y * height, width: width, height: height),
                                     anchor: anchor, tilt: seat.tilt)
            return place.covers(stage) ? nil : place
        }

        for side in [Seat.Side.left, .right] {
            let group = seats.indices.filter { seats[$0].side == side }
            guard !group.isEmpty else { continue }
            let arts = group.map { sprite(seats[$0].cat, seats[$0].pose) }
            // The cat's height above its paws fills the room; a tilted cat's far corner rises, so it is a little smaller.
            let scales = zip(group, arts).map { index, art in
                let rise = abs(sin(seats[index].tilt * .pi / 180)) * art.bounds.width * art.size.width / 2
                return min(1, stage.room / ((art.anchor.y - art.bounds.minY) * art.size.height + rise))
            }
            guard scales.allSatisfy({ $0 >= smallest }) else { return nil }
            // Offsets of each cat's cut from the start of the group, in the order listed.
            var offsets: [CGFloat] = [], length: CGFloat = 0
            for (n, index) in group.enumerated() {
                let start = n == 0 ? 0 : length - seats[index].overlap * unit
                offsets.append(start)
                length = start + span(arts[n], scales[n])
            }
            let radius = GlassEdge.endRadius(stage.shape, height: glass.height)
            // The group slides inward from its end until every cat has a level stretch.
            // A pair that slides further no longer sits at its end; one cat may go a little further.
            var slide: CGFloat = 0
            let limit = glass.width * (group.count > 1 ? 0.2 : 0.3)
            search: while true {
                var found: [CatPlacement] = []
                for (n, index) in group.enumerated() {
                    let left: CGFloat
                    if side == .left {
                        left = radius - cornerReach + slide + offsets[n]
                    } else {
                        left = glass.width - radius + cornerReach - slide - offsets[n] - span(arts[n], scales[n])
                    }
                    guard let place = top(seats[index], arts[n], scales[n], left: left) else { break }
                    found.append(place)
                }
                if found.count == group.count {
                    for (n, index) in group.enumerated() { placed[index] = found[n] }
                    break search
                }
                slide += 2 * unit
                if slide > limit { return nil }
            }
        }

        let places = placed.compactMap { $0 }
        guard places.count == seats.count else { return nil }
        let bounds = CGRect(origin: .zero, size: stage.size)
        guard places.allSatisfy({ bounds.contains($0.visible.insetBy(dx: 0.01, dy: 0.01)) && !$0.covers(stage) }) else { return nil }
        // Cats on different sides keep apart; the ones listed together overlap as written.
        for a in places.indices {
            for b in places.indices where b > a && seats[a].side != seats[b].side {
                guard !places[a].visible.insetBy(dx: -4 * unit, dy: 0).intersects(places[b].visible) else { return nil }
            }
        }
        return places
    }
}

/// Which arrangement is shown: one for each day, or the one the user pinned.
enum CatSchedule {
    /// The setting's value for a new arrangement every day.
    static let daily = "daily"

    /// Days since 1 January 2001 in the calendar's time zone.
    static func day(_ date: Date = Date(), calendar: Calendar = .current) -> Int {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let parts = gregorian.dateComponents([.year, .month, .day], from: date)
        // Days from the civil date (Howard Hinnant's algorithm), then to the 2001 epoch.
        let month = parts.month ?? 1, year = (parts.year ?? 2001) - (month <= 2 ? 1 : 0)
        let era = (year >= 0 ? year : year - 399) / 400
        let yearOfEra = year - era * 400
        let dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + (parts.day ?? 1) - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097 + dayOfEra - 730_791
    }

    private static func hash(_ day: Int) -> UInt64 {
        var z = UInt64(bitPattern: Int64(day)) &+ 0x9E37_79B9_7F4A_7C15
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// The place in a pool of `count` for a day: the same all day, and never
    /// the day before's. Even days take what the date's hash gives; an odd
    /// day takes what its hash gives among the places its two neighbours left.
    static func index(day: Int, count: Int) -> Int {
        guard count > 1 else { return 0 }
        if count == 2 { return ((day % 2) + 2) % 2 }
        func even(_ d: Int) -> Int { Int(hash(d) % UInt64(count)) }
        if day % 2 == 0 { return even(day) }
        let taken = Set([even(day - 1), even(day + 1)])
        let free = (0..<count).filter { !taken.contains($0) }
        return free[Int(hash(day) % UInt64(free.count))]
    }

    /// The arrangement to show and each cat's place. `setting` is `daily` or
    /// an arrangement's id; one that does not fit this stage gives way to
    /// the first that does.
    static func arrangement(setting: String, day: Int, on stage: CatStage,
                            sprite: (KeyboardCat, CatPose) -> CatSprite = CatArt.sprite,
                            smallest: CGFloat = CatArrangement.smallest) -> (arrangement: CatArrangement, places: [CatPlacement])? {
        let pool = CatArrangement.all.compactMap { arrangement in
            arrangement.place(on: stage, sprite: sprite, smallest: smallest).map { (arrangement: arrangement, places: $0) }
        }
        guard !pool.isEmpty else { return nil }
        if setting == daily { return pool[index(day: day, count: pool.count)] }
        return pool.first { $0.arrangement.id == setting } ?? pool[0]
    }
}
