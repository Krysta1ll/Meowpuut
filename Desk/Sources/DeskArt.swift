import AppKit
import ImageIO

// The clay cats on the desktop: the owner's own pictures (assets/brand/cats, imported by scripts/render_cats.py),
// placed, mirrored, squashed and turned about the paws; never redrawn or recoloured. The geometry is the input
// method's table, Sources/CatSpriteData.swift, compiled in as it is, plus the desk poses of Resources/Cats/desk.json
// (bundled as it is: the walk cycles of 12 frames, turn, look_up, lift, fall, momo_sleep; CLAY-POSES.md P0).
//
// Pixel skins are not drawn on the desktop yet: every skin falls back to the clay cats (PETS.md §6.3 frames and
// the 字景 2 pt cats come later).

/// The shape of the input method's sprite table (Sources/CatSprites.swift declares the same type there; the Desk
/// declares it here so that it can compile Sources/CatSpriteData.swift without the candidate-bar code).
struct CatSprite: Equatable {
    struct Paw: Equatable {
        var x: CGFloat
        var halfWidth: CGFloat
        var depth: CGFloat
    }

    var name: String
    var size: CGSize
    var anchor: CGPoint
    var bounds: CGRect
    var cut: ClosedRange<CGFloat>
    var paws: [Paw]

    var behindBar: Bool { cut.upperBound > cut.lowerBound }
}

/// The shell's side of walking. The core gives only the step parity (`phase`) and, for typing, one STEP event per
/// step. The clay walk is a cycle of `length` frames (desk.json `cycles`: 12 in the 3D set, 4 as a fallback) whose
/// key poses walk_1…4 sit on every (length / 4)th frame. A step, whatever drives it (one key while you type, one
/// core step on a stroll: the core moves the cat a whole stride per step), advances length / 4 frames, so it lands
/// on the next key pose exactly; the in-betweens are for playback by time, which the desk does not do yet (the core
/// places a cat only at steps). When a walk ends on the ground the cat shows `turn` for a moment before it sits
/// facing you.
struct DeskGait: Equatable {
    /// How long `turn` shows after the last step (three 12 Hz ticks).
    static let turnMs: Double = 250
    static let keyPoses = 4

    /// Frames in the cycle: a multiple of four, at least four.
    let length: Int
    /// 0…length-1: the frame of the cycle on show.
    private(set) var index = 0
    private(set) var turnUntil: Double = 0
    private var phase: UInt8?
    private var walking = false

    init(length: Int = 4) {
        self.length = length >= Self.keyPoses && length % Self.keyPoses == 0 ? length : Self.keyPoses
    }

    /// Frames one step advances: the distance between two key poses.
    var stepFrames: Int { length / Self.keyPoses }

    /// 0…3: walk_1…walk_4, the key pose on show (`index` when it is on one).
    var frame: Int { index / stepFrames }

    static func isWalking(_ c: MPDeskCat) -> Bool {
        let pose = Int(c.pose)
        return Int(c.body) == MP_DESK_BODY_GROUND && (pose == MP_DESK_POSE_WALK || pose == MP_DESK_POSE_TROT)
    }

    /// Once per core step, before drawing. `typingSteps`: the STEP events of that step (one per key the walker
    /// stepped with; two keys in one step are two frames even though the parity is back where it was).
    mutating func update(_ c: MPDeskCat, typingSteps: Int, nowMs: Double, motion: Bool) {
        let now = Self.isWalking(c)
        let sitting = Int(c.body) == MP_DESK_BODY_GROUND && Int(c.pose) == MP_DESK_POSE_SIT && Int(c.seat) == MP_DESK_SEAT_TOP
        if now {
            var advance = 0
            if Int(c.act) == MP_DESK_ACT_TYPING && typingSteps > 0 {
                advance = typingSteps
            } else if let phase, phase != c.phase {
                advance = 1
            }
            index = (index + advance * stepFrames) % length
            turnUntil = 0
        } else if walking && sitting && motion {
            turnUntil = nowMs + Self.turnMs
        } else if !sitting {
            turnUntil = 0
        }
        walking = now
        phase = c.phase
    }

    func turning(at nowMs: Double) -> Bool { nowMs < turnUntil }
}

/// Which picture a cat wears now, from the core's pose (design-preview/pc/pets/pets-art.js clayPose).
struct DeskLook: Equatable {
    var name: String
    /// Drawn instead when `name` is not in the bundle (a pose not imported yet).
    var fallback: String?
    /// Behind an edge: cut on the anchor row, the paws over it (cats.json cut and paws).
    var behind = false
    /// side_hold: the feet stand at the bottom of the picture.
    var side = false
    /// A walk frame: the step is in the picture (no wobble, no lift).
    var walk = false
    /// Lifted by the scruff: `lift` hangs from its anchor (the scruff), and turns about it.
    var held = false
    /// A sleeping cat breathes slowly while she purrs.
    var slowBreath = false
    /// Which way the picture faces: 1 right (walk, turn, and the front-facing poses as before), -1 left (look_up
    /// looks up to the left). The cat is mirrored when the core's facing differs.
    var native: Int8 = 1

    static func of(_ cat: DeskCat, _ c: MPDeskCat, gait: DeskGait = DeskGait(), nowMs: Double = 0) -> DeskLook? {
        let body = Int(c.body), seat = Int(c.seat), pose = Int(c.pose)
        if body == MP_DESK_BODY_HOME || body == MP_DESK_BODY_GLASS || pose == MP_DESK_POSE_NONE { return nil }
        let n = cat.asset
        if seat == MP_DESK_SEAT_SIDE || pose == MP_DESK_POSE_SIDE { return DeskLook(name: "\(n)_side_hold", side: true) }
        if seat == MP_DESK_SEAT_BEHIND || pose == MP_DESK_POSE_PEEK || pose == MP_DESK_POSE_REST || pose == MP_DESK_POSE_LOUNGE {
            let part = pose == MP_DESK_POSE_REST ? "rest" : pose == MP_DESK_POSE_LOUNGE ? "lounge" : "peek"
            return DeskLook(name: "\(n)_edge_\(part)", behind: true)
        }
        let mimi = cat == .mimimo
        let idle = "\(n)_idle"
        switch pose {
        case MP_DESK_POSE_HAPPY: return DeskLook(name: "\(n)_happy")
        case MP_DESK_POSE_SLEEP:
            return DeskLook(name: "\(n)_sleep", fallback: mimi ? nil : "momo_happy", slowBreath: Int(c.react) == MP_DESK_REACT_PURR)
        case MP_DESK_POSE_PRESS, MP_DESK_POSE_WAVE: return DeskLook(name: mimi ? "mimimo_press" : "momo_wave")
        // hop_2 (Mimimo's jump) is CLAY-POSES P1: her idle until then.
        case MP_DESK_POSE_JUMP: return DeskLook(name: mimi ? idle : "momo_jump")
        case MP_DESK_POSE_AIR: return DeskLook(name: "\(n)_fall", fallback: idle)
        case MP_DESK_POSE_WALK, MP_DESK_POSE_TROT:
            return DeskLook(name: DeskArt.walkFrame(cat, gait.index, length: gait.length), fallback: idle, walk: true)
        case MP_DESK_POSE_HELD: return DeskLook(name: "\(n)_lift", fallback: idle, held: true)
        case MP_DESK_POSE_LOOK: return DeskLook(name: "\(n)_look_up", fallback: idle, native: -1)
        case MP_DESK_POSE_SIT where gait.turning(at: nowMs) && seat == MP_DESK_SEAT_TOP:
            return DeskLook(name: "\(n)_turn", fallback: idle)
        // sit, stretch, prep (hop_1 is P1), land and the poses clay has no picture for (loaf, groom, back).
        default: return DeskLook(name: idle)
        }
    }
}

final class DeskArt {
    struct Picture {
        let sprite: CatSprite
        let image: CGImage
        /// Alpha of every pixel, top row first.
        let alpha: [UInt8]
        let width: Int
        let height: Int
        /// The point the core's x/y stands for, as fractions of the picture (y down): between the paws on the
        /// anchor row; for side_hold the feet at the bottom of the opaque part. For `lift` it is the scruff (the
        /// anchor), which the panel hangs below the pointer.
        let foot: CGPoint

        /// The cat's own pixels (render_cats.py counts alpha 128 and up as the cat): soft edges never catch a click.
        func opaque(u: CGFloat, v: CGFloat) -> Bool {
            guard u >= 0, v >= 0, u < 1, v < 1 else { return false }
            let x = Int(u * CGFloat(width)), y = Int(v * CGFloat(height))
            return alpha[y * width + x] >= 128
        }
    }

    static let shared = DeskArt(bundle: .main)
    static let tableName = "desk"

    let bundle: Bundle
    /// The desk poses of desk.json (size, anchor, bounds), by name; empty without the file.
    let poses: [String: CatSprite]
    /// Walk cycles from desk.json ("mimimo_walk": its frames in order, 12 names).
    let cycles: [String: [String]]
    private var cache: [String: Picture] = [:]

    init(bundle: Bundle) {
        self.bundle = bundle
        let table = bundle.url(forResource: Self.tableName, withExtension: "json").flatMap { try? Data(contentsOf: $0) }
            .flatMap { Self.parse($0) }
        poses = table?.poses ?? [:]
        cycles = table?.cycles ?? [:]
    }

    /// desk.json as render_cats.py --desk writes it: "poses" {name: {size [w, h], anchor [x, y], bounds [x0, y0, x1,
    /// y1]}} in fractions of the canvas, and "cycles" {id: [names]}. Entries that do not parse are left out.
    static func parse(_ data: Data) -> (poses: [String: CatSprite], cycles: [String: [String]])? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = root["poses"] as? [String: Any] else { return nil }
        func numbers(_ value: Any?, _ count: Int) -> [CGFloat]? {
            guard let list = value as? [NSNumber], list.count == count else { return nil }
            let values = list.map { CGFloat($0.doubleValue) }
            return values.allSatisfy(\.isFinite) ? values : nil
        }
        var poses: [String: CatSprite] = [:]
        for (name, value) in raw {
            guard let entry = value as? [String: Any], let size = numbers(entry["size"], 2), size[0] > 0, size[1] > 0,
                  let anchor = numbers(entry["anchor"], 2), let b = numbers(entry["bounds"], 4), b[2] > b[0], b[3] > b[1] else { continue }
            poses[name] = CatSprite(name: name, size: CGSize(width: size[0], height: size[1]), anchor: CGPoint(x: anchor[0], y: anchor[1]),
                                    bounds: CGRect(x: b[0], y: b[1], width: b[2] - b[0], height: b[3] - b[1]), cut: 0...0, paws: [])
        }
        let cycles = (root["cycles"] as? [String: [String]]) ?? [:]
        return (poses, cycles)
    }

    /// Frames in a cat's walk cycle: desk.json's when it is a multiple of four, else 4 (walk_1…4).
    func walkLength(_ cat: DeskCat) -> Int {
        guard let count = cycles["\(cat.asset)_walk"]?.count, count >= DeskGait.keyPoses, count % DeskGait.keyPoses == 0 else {
            return DeskGait.keyPoses
        }
        return count
    }

    /// The picture of a cat's walk at `frame` (0…length-1) of a cycle of `length` frames: desk.json's cycle when it
    /// has that length, else walk_1…walk_4 (the 4-frame fallback).
    func walkName(_ cat: DeskCat, _ frame: Int, length: Int) -> String {
        let n = max(length, 1)
        let index = ((frame % n) + n) % n
        if let cycle = cycles["\(cat.asset)_walk"], cycle.count == n, n >= DeskGait.keyPoses { return cycle[index] }
        return "\(cat.asset)_walk_\(index % DeskGait.keyPoses + 1)"
    }

    static func cycleLength(_ cat: DeskCat) -> Int { shared.walkLength(cat) }
    static func walkFrame(_ cat: DeskCat, _ frame: Int, length: Int = 4) -> String { shared.walkName(cat, frame, length: length) }

    /// Every walk frame of both cats is in the bundle: the core may walk them (MP_DESK_ART_WALK) instead of hopping.
    var hasWalkFrames: Bool {
        DeskCat.allCases.allSatisfy { cat in
            let length = walkLength(cat)
            return (0..<length).allSatisfy { picture(walkName(cat, $0, length: length)) != nil }
        }
    }

    func sprite(_ name: String) -> CatSprite? { poses[name] ?? CatSprite.all[name] }

    /// The picture for a look: its own, or the fallback when its pose is not in the bundle.
    func picture(for look: DeskLook) -> Picture? {
        picture(look.name) ?? look.fallback.flatMap { picture($0) }
    }

    func picture(_ name: String) -> Picture? {
        if let cached = cache[name] { return cached }
        guard let sprite = sprite(name),
              let url = bundle.url(forResource: name + "@2x", withExtension: "png") ?? bundle.url(forResource: name, withExtension: "png"),
              let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        let width = image.width, height = image.height
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = rgba.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        let alpha = stride(from: 3, to: rgba.count, by: 4).map { rgba[$0] }
        let foot = name.hasSuffix("side_hold") ? CGPoint(x: 0.5, y: sprite.bounds.maxY) : sprite.anchor
        let picture = Picture(sprite: sprite, image: image, alpha: alpha, width: width, height: height, foot: foot)
        cache[name] = picture
        return picture
    }

    /// side_hold, from the table (its anchor is the holding paw): where the cat stands relative to the window's side
    /// (desktop x; Mimimo holds a window's left side and stands left of it, Momo the right side) and how high the
    /// holding paw is above the feet.
    static func sideHold(_ cat: DeskCat, scale: CGFloat) -> (standOffset: CGFloat, pawHeight: CGFloat) {
        guard let s = CatSprite.all["\(cat.asset)_side_hold"] else { return (cat == .mimimo ? -22.5 : 23.9, 28) }
        return ((0.5 - s.anchor.x) * s.size.width * scale, (s.bounds.maxY - s.anchor.y) * s.size.height * scale)
    }
}
