import AppKit

// Measuring the world the cats stand on (PETS.md §2.1, r2-eng.md §8.2), in desktop points: x right, y down from
// the top of the main display (the space CGWindowList reports in; the input method's pulse uses the same).
//
// Permissions: none. Other apps' windows come from CGWindowListCopyWindowInfo, and of each entry only four keys
// are ever read: kCGWindowBounds, kCGWindowLayer, kCGWindowNumber and kCGWindowOwnerPID. The pid only tells our
// own windows and the frontmost app's windows apart; it is never turned into an app. Never a window name, a title
// or an owner name. Which fields come back without Screen Recording is observed, not documented: if the bounds
// ever stop coming, there are simply no window edges and the cats keep to the Dock, the screen bottoms and home.
//
// The Dock and the menu bar come from NSScreen.frame against visibleFrame. The Dock's own width has no public
// API; without reading the Dock's preferences only the middle of it (about two icons either side) is a shelf.

struct WindowGeom: Equatable {
    var number: UInt32
    var pid: Int32
    var frame: CGRect
}

struct ScreenGeom: Equatable {
    var frame: CGRect
    var visible: CGRect
}

struct WorldInput {
    var screens: [ScreenGeom]
    /// Layer-0 windows of other processes, front to back.
    var windows: [WindowGeom]
    var frontPID: Int32
    /// The cat door (the menu bar item) x, when there is one.
    var doorX: CGFloat?
    var scale: CGFloat = 1
}

struct WorldMeasure: Equatable {
    var world: DeskWorld
    /// The frontmost window covers its whole screen (a full-screen app or a game): the cats are put away.
    var fullScreen: Bool
}

enum WorldSensor {
    /// Mac traffic lights and the right margin of a title bar are not seats (PETS.md §2.1 rule 3).
    static let titleLeft: CGFloat = 84
    static let titleRight: CGFloat = 12
    static let shortestEdge: CGFloat = 56
    static let shortestBase: CGFloat = 40

    // MARK: Coordinates

    /// Height of the main display: Cocoa's y-up screen space and the desktop's y-down space meet at its top edge.
    static var primaryHeight: CGFloat { CGDisplayBounds(CGMainDisplayID()).height }

    static func desktop(_ rect: NSRect, primaryHeight: CGFloat) -> CGRect {
        CGRect(x: rect.minX, y: primaryHeight - rect.maxY, width: rect.width, height: rect.height)
    }

    static func desktop(_ point: NSPoint, primaryHeight: CGFloat) -> CGPoint {
        CGPoint(x: point.x, y: primaryHeight - point.y)
    }

    // MARK: Reading the system

    static func screens(primaryHeight: CGFloat) -> [ScreenGeom] {
        NSScreen.screens.map { ScreenGeom(frame: desktop($0.frame, primaryHeight: primaryHeight),
                                          visible: desktop($0.visibleFrame, primaryHeight: primaryHeight)) }
    }

    /// Layer-0 windows of other processes, front to back. Reads only bounds, layer, number and owner pid.
    static func windows(excluding ownPID: Int32) -> [WindowGeom] {
        guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) else { return [] }
        var result: [WindowGeom] = []
        for index in 0..<CFArrayGetCount(list) {
            guard let raw = CFArrayGetValueAtIndex(list, index) else { continue }
            let entry = unsafeBitCast(raw, to: CFDictionary.self)
            guard int32(entry, kCGWindowLayer) == 0,
                  let pid = int32(entry, kCGWindowOwnerPID), pid != ownPID,
                  let number = int32(entry, kCGWindowNumber),
                  let boundsValue = value(entry, kCGWindowBounds),
                  CFGetTypeID(boundsValue) == CFDictionaryGetTypeID(),
                  let frame = CGRect(dictionaryRepresentation: unsafeBitCast(boundsValue, to: CFDictionary.self)) else { continue }
            result.append(WindowGeom(number: UInt32(bitPattern: number), pid: pid, frame: frame))
        }
        return result
    }

    private static func value(_ entry: CFDictionary, _ key: CFString) -> CFTypeRef? {
        guard let raw = CFDictionaryGetValue(entry, Unmanaged.passUnretained(key).toOpaque()) else { return nil }
        return Unmanaged<CFTypeRef>.fromOpaque(raw).takeUnretainedValue()
    }

    private static func int32(_ entry: CFDictionary, _ key: CFString) -> Int32? {
        guard let number = value(entry, key), CFGetTypeID(number) == CFNumberGetTypeID() else { return nil }
        var result: Int32 = 0
        return CFNumberGetValue(unsafeBitCast(number, to: CFNumber.self), .sInt32Type, &result) ? result : nil
    }

    static func capture(ownPID: Int32, doorX: CGFloat?, scale: CGFloat) -> WorldInput {
        let height = primaryHeight
        return WorldInput(screens: screens(primaryHeight: height), windows: windows(excluding: ownPID),
                          frontPID: NSWorkspace.shared.frontmostApplication?.processIdentifier ?? -1,
                          doorX: doorX, scale: scale)
    }

    // MARK: The model (pure)

    /// The window holding a point (the composition origin): the frontmost that contains it, 0 for none.
    static func owner(at point: CGPoint, in windows: [WindowGeom]) -> UInt32 {
        windows.first { $0.frame.contains(point) }?.number ?? 0
    }

    static func build(_ input: WorldInput) -> WorldMeasure {
        let k = input.scale
        let band = max(DeskCat.mimimo.body.height, DeskCat.momo.body.height) * k + 6
        let windows = input.windows.filter { $0.frame.width >= 60 && $0.frame.height >= 40 }
        var grounds: [DeskGround] = []

        // Window top edges: room for a cat above (under the menu bar), minus the parts behind windows in front.
        for (z, w) in windows.enumerated() {
            let top = CGPoint(x: w.frame.midX, y: w.frame.minY + 1)
            guard let screen = input.screens.first(where: { $0.frame.contains(top) }),
                  w.frame.minY - band >= screen.visible.minY else { continue }
            let y = w.frame.minY
            let ahead = windows[..<z].filter { $0.frame.minY < y + 4 && $0.frame.maxY > y - band }
                .map { ($0.frame.minX - 2, $0.frame.maxX + 2) }
            let start = max(w.frame.minX + titleLeft, screen.frame.minX), end = min(w.frame.maxX - titleRight, screen.frame.maxX)
            for (i, s) in subtract([(start, end)], ahead).filter({ $0.1 - $0.0 >= shortestEdge }).enumerated() {
                grounds.append(DeskGround(id: groundID([1, Int(w.number), i]), kind: UInt32(MP_DESK_GROUND_EDGE),
                                          x0: s.0, x1: s.1, y: y, ox: w.frame.minX, owner: w.number,
                                          flags: UInt32(MP_DESK_GROUND_BEHIND)))
            }
        }

        // The base of each screen: the Dock's middle as a shelf, the screen's bottom beside it as floor. Above a
        // base, the part under the window in use (the frontmost app's front window) is only for peeking (PETS.md
        // §2.1 rule 4; the prototype dropped the floor there, which left nothing under a cat).
        let front = windows.first { $0.pid == input.frontPID }
        var bases: [DeskGround] = []
        for (n, s) in input.screens.enumerated() {
            let dockBottom = s.frame.maxY - s.visible.maxY
            let dockLeft = s.visible.minX - s.frame.minX, dockRight = s.frame.maxX - s.visible.maxX
            var parts: [(kind: Int32, x0: CGFloat, x1: CGFloat, y: CGFloat)] = []
            let x0 = s.frame.minX + 14 + max(0, dockLeft), x1 = s.frame.maxX - 14 - max(0, dockRight)
            if dockBottom >= 16 {
                // About two icons either side of the middle are surely Dock; the floor keeps well clear of the
                // rest of it, whose width is unknown.
                let half = min(s.frame.width / 2 - 40, 2.5 * dockBottom)
                let clear = max(half + 8 + 1.5 * dockBottom, 0.3 * s.frame.width)
                parts.append((Int32(MP_DESK_GROUND_SHELF), s.frame.midX - half + 14, s.frame.midX + half - 14, s.visible.maxY))
                parts.append((Int32(MP_DESK_GROUND_FLOOR), x0, s.frame.midX - clear, s.frame.maxY))
                parts.append((Int32(MP_DESK_GROUND_FLOOR), s.frame.midX + clear, x1, s.frame.maxY))
            } else {
                parts.append((Int32(MP_DESK_GROUND_FLOOR), x0, x1, s.frame.maxY))
            }
            for (p, part) in parts.enumerated() {
                let shelf = part.kind == Int32(MP_DESK_GROUND_SHELF)
                let cover: [(CGFloat, CGFloat)] = front.map { f in
                    f.frame.maxY > part.y - band && f.frame.minY < part.y ? [(f.frame.minX, f.frame.maxX)] : []
                } ?? []
                // Ids name the piece (screen, part, free or under the window, which piece), not where it starts, so
                // moving the window in use does not take the ground from under a cat.
                for (i, seg) in subtract([(part.x0, part.x1)], cover).filter({ $0.1 - $0.0 >= shortestBase }).enumerated() {
                    bases.append(DeskGround(id: groundID([2, n, p, 0, i]), kind: UInt32(part.kind),
                                            x0: seg.0, x1: seg.1, y: part.y, ox: 0, owner: 0,
                                            flags: shelf ? UInt32(MP_DESK_GROUND_BEHIND) : 0))
                }
                // Under the window in use a cat may pass, and only peeks over the edge when it stops (the Dock's
                // glass, or the screen's bottom edge), so a window reaching down there never sends the cats home.
                for (i, seg) in intersect([(part.x0, part.x1)], cover).enumerated() where seg.1 - seg.0 >= shortestBase {
                    bases.append(DeskGround(id: groundID([2, n, p, 1, i]), kind: UInt32(part.kind),
                                            x0: seg.0, x1: seg.1, y: part.y, ox: 0, owner: 0,
                                            flags: UInt32(MP_DESK_GROUND_BEHIND | MP_DESK_GROUND_PEEK_ONLY)))
                }
            }
        }
        grounds += bases

        // Window sides a clay cat can hold (side_hold): a side that comes down near a base, with room beside it.
        var sides: [DeskSide] = []
        let left = DeskArt.sideHold(.mimimo, scale: k), right = DeskArt.sideHold(.momo, scale: k)
        for w in windows {
            for g in bases where g.flags & UInt32(MP_DESK_GROUND_PEEK_ONLY) == 0 {
                let pawY = g.y - Double(left.pawHeight)
                guard Double(w.frame.minY) < pawY - 6, Double(w.frame.maxY) > pawY + 4 else { continue }
                let blockedLeft = windows.contains { o in o != w && o.frame.minX < w.frame.minX && o.frame.maxX > w.frame.minX - 40
                    && Double(o.frame.minY) < g.y && Double(o.frame.maxY) > pawY }
                let blockedRight = windows.contains { o in o != w && o.frame.maxX > w.frame.maxX && o.frame.minX < w.frame.maxX + 40
                    && Double(o.frame.minY) < g.y && Double(o.frame.maxY) > pawY }
                let l = Double(w.frame.minX + left.standOffset), r = Double(w.frame.maxX + right.standOffset)
                if !blockedLeft, l > g.x0 + 10, l < g.x1 - 10 { sides.append(DeskSide(dir: -1, ground: g.id, standX: l)) }
                if !blockedRight, r > g.x0 + 10, r < g.x1 - 10 { sides.append(DeskSide(dir: 1, ground: g.id, standX: r)) }
            }
        }

        var world = DeskWorld()
        world.grounds = Array(grounds.prefix(Int(MP_DESK_MAX_GROUNDS)))
        world.sides = Array(sides.prefix(Int(MP_DESK_MAX_SIDES)))
        if let main = input.screens.first {
            let doorX = input.doorX ?? main.frame.maxX - 220
            world.doorX = Double(doorX)
            world.doorY = Double(main.visible.minY + 2 + band)
            world.homeX = Double(doorX)
            world.wallX0 = Double(input.screens.map(\.frame.minX).min() ?? 0)
            world.wallX1 = Double(input.screens.map(\.frame.maxX).max() ?? 1440)
            world.wallTop = Double(input.screens.map(\.visible.minY).min() ?? 24)
            world.wallBottom = Double((input.screens.map(\.frame.maxY).max() ?? 900) + 40)
        }

        let fullScreen = front.map { f in
            input.screens.contains { s in
                abs(f.frame.minX - s.frame.minX) < 1 && abs(f.frame.minY - s.frame.minY) < 1
                    && abs(f.frame.maxX - s.frame.maxX) < 1 && abs(f.frame.maxY - s.frame.maxY) < 1
            }
        } ?? false
        return WorldMeasure(world: world, fullScreen: fullScreen)
    }

    // MARK: Segments

    static func subtract(_ segments: [(CGFloat, CGFloat)], _ cuts: [(CGFloat, CGFloat)]) -> [(Double, Double)] {
        var result = segments
        for (a, b) in cuts {
            var next: [(CGFloat, CGFloat)] = []
            for (x0, x1) in result {
                if b <= x0 || a >= x1 { next.append((x0, x1)); continue }
                if a > x0 { next.append((x0, a)) }
                if b < x1 { next.append((b, x1)) }
            }
            result = next
        }
        return result.map { (Double($0.0), Double($0.1)) }
    }

    static func intersect(_ segments: [(CGFloat, CGFloat)], _ cuts: [(CGFloat, CGFloat)]) -> [(Double, Double)] {
        var result: [(Double, Double)] = []
        for (x0, x1) in segments {
            for (a, b) in cuts {
                let l = max(x0, a), r = min(x1, b)
                if r > l { result.append((Double(l), Double(r))) }
            }
        }
        return result
    }

    /// A ground id that stays the same while the same thing is measured (FNV-1a of its parts; never 0).
    static func groundID(_ parts: [Int]) -> UInt32 {
        var hash: UInt32 = 0x811C_9DC5
        for part in parts {
            withUnsafeBytes(of: Int64(part).littleEndian) { bytes in
                for byte in bytes { hash = (hash ^ UInt32(byte)) &* 0x0100_0193 }
            }
        }
        return hash == 0 ? 1 : hash
    }
}
