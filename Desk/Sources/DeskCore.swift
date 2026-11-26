import Foundation

// 喵撲貓窩's side of the desk-pet core: Engine meowpuut.h "Desk" (crates/meowpuut-pets/src/desk.rs). The core
// decides where each cat is, which pose it wears and when it next needs looking at; this file only carries the
// world, the pulses and the events across in one mp_desk_step per update, on the main thread.

enum DeskCat: Int, CaseIterable {
    case mimimo = 0, momo = 1

    /// MP_PETS_MIMIMO / MP_PETS_MOMO.
    var pet: UInt32 { UInt32(self == .mimimo ? MP_PETS_MIMIMO : MP_PETS_MOMO) }
    /// The asset name prefix of the clay art (assets/brand/cats).
    var asset: String { self == .mimimo ? "mimimo" : "momo" }
    /// Body boxes at the medium size (desk.rs Settings::default).
    var body: CGSize { self == .mimimo ? CGSize(width: 34, height: 52) : CGSize(width: 35, height: 52) }
}

/// The core's clock: monotonic ms since boot (never wall time), the local day index and minute of the day.
enum DeskClock {
    static var monoMs: UInt64 { UInt64(max(0, ProcessInfo.processInfo.systemUptime) * 1000) }

    static func now(date: Date = Date(), uptime: TimeInterval = ProcessInfo.processInfo.systemUptime,
                    calendar: Calendar = .current) -> MPPetsClock {
        let local = date.timeIntervalSince1970 + TimeInterval(calendar.timeZone.secondsFromGMT(for: date))
        let days = (local / 86_400).rounded(.down)
        let minute = ((local - days * 86_400) / 60).rounded(.down)
        return MPPetsClock(mono_ms: UInt64(max(0, uptime) * 1000),
                           day: days >= 1 && days < Double(UInt32.max) ? UInt32(days) : 0,
                           minute: UInt32(min(1439, max(0, minute))))
    }
}

/// MPDeskGround with equality, so a measurement that did not change is not sent.
struct DeskGround: Equatable {
    var id: UInt32
    var kind: UInt32
    var x0, x1, y, ox: Double
    var owner: UInt32
    var flags: UInt32

    var c: MPDeskGround { MPDeskGround(id: id, kind: kind, x0: x0, x1: x1, y: y, ox: ox, owner: owner, flags: flags) }
}

struct DeskSide: Equatable {
    var dir: Int32
    var ground: UInt32
    var standX: Double

    var c: MPDeskSide { MPDeskSide(dir: dir, ground: ground, stand_x: standX) }
}

/// What the shell measured (desktop points, y down from the top of the main display).
struct DeskWorld: Equatable {
    var grounds: [DeskGround] = []
    var sides: [DeskSide] = []
    var doorX = 700.0, doorY = 30.0, doorVY = 0.0, homeX = 700.0
    var wallX0 = 0.0, wallX1 = 1440.0, wallTop = 24.0, wallBottom = 900.0
}

final class DeskCore {
    /// The clay cats as they ship (FINAL.md §4.4–4.5): sleep, hiding behind an edge, holding a window's side, and
    /// since CLAY-POSES' second batch walking (walk_1…4): MP_DESK_ART_WALK, so the core walks them with your typing
    /// and between places instead of hopping. A bundle without the walk frames keeps the hop (ART_CLAY, 100). The
    /// pixel skins would draw LOAF|SLEEP|BACK|WALK|PEEK, but they fall back to clay here.
    static let clayArt = UInt8(MP_DESK_ART_SLEEP | MP_DESK_ART_PEEK | MP_DESK_ART_SIDE)
        | (DeskArt.shared.hasWalkFrames ? UInt8(MP_DESK_ART_WALK) : 0)

    static func settings(on: Bool, quiet: Bool, reduceMotion: Bool, typing: UInt32, scale: Double) -> MPDeskSettings {
        var s = MPDeskSettings()
        s.mode = UInt32(!on ? MP_DESK_MODE_OFF : quiet ? MP_DESK_MODE_QUIET : MP_DESK_MODE_FULL)
        s.flags = reduceMotion ? UInt32(MP_DESK_REDUCE_MOTION) : 0
        s.typing = typing
        s.art = (clayArt, clayArt)
        // `size` is an array of anonymous structs, which Swift does not import: four doubles (w, h of Mimimo, then
        // of Momo) right after `reserved`, at offset 16 of the 48-byte struct.
        let sizes = [DeskCat.mimimo.body.width, DeskCat.mimimo.body.height, DeskCat.momo.body.width, DeskCat.momo.body.height]
        let at = MemoryLayout<MPDeskSettings>.offset(of: \MPDeskSettings.reserved)! + 2
        precondition(at == 16 && MemoryLayout<MPDeskSettings>.size == 48, "MPDeskSettings layout changed")
        withUnsafeMutableBytes(of: &s) { raw in
            for (i, v) in sizes.enumerated() { raw.storeBytes(of: Double(v) * scale, toByteOffset: at + 8 * i, as: Double.self) }
        }
        return s
    }

    private var handle: OpaquePointer
    private var settings: MPDeskSettings
    private let seed: UInt32
    private(set) var output = MPDeskOutput()
    /// The events the last step took (MP_DESK_OUT_*).
    private(set) var taken: [MPDeskEventOut] = []
    private(set) var steps = 0
    private(set) var lastStatus: Int32 = 0
    private var sentWorld: DeskWorld?
    private var pendingWorld: DeskWorld?
    private var pulses: [MPDeskPulse] = []
    private var events: [MPDeskEvent] = []
    private var eventsOut = [MPDeskEventOut](repeating: MPDeskEventOut(), count: 64)

    init?(settings: MPDeskSettings, seed: UInt32) {
        var s = settings
        guard let handle = mp_desk_create(&s, seed) else { return nil }
        self.handle = handle
        self.settings = settings
        self.seed = seed
        pulses.reserveCapacity(Int(MP_DESK_MAX_PULSES))
        events.reserveCapacity(32)
    }

    deinit { mp_desk_free(handle) }

    // MARK: Input, applied at the next step

    /// Queues the world when it differs from the last one sent. True when it did.
    @discardableResult
    func setWorld(_ world: DeskWorld) -> Bool {
        guard world != (pendingWorld ?? sentWorld) else { return false }
        pendingWorld = world
        return true
    }

    var world: DeskWorld? { pendingWorld ?? sentWorld }

    /// A pulse as the input method wrote it; `caretOwner` is the window holding the composition origin (0: unknown).
    func pulse(_ bytes: [UInt8], caretOwner: UInt32) {
        guard bytes.count == Int(MP_PULSE_BYTES) else { return }
        if pulses.count == Int(MP_DESK_MAX_PULSES) { pulses.removeFirst() }
        var p = MPDeskPulse()
        withUnsafeMutableBytes(of: &p.bytes) { $0.copyBytes(from: bytes) }
        p.caret_owner = caretOwner
        pulses.append(p)
    }

    func event(_ kind: Int, _ cat: DeskCat? = nil, arg: UInt32 = 0, x: Double = 0, y: Double = 0) {
        guard events.count < Int(MP_DESK_MAX_EVENTS) else { return }
        events.append(MPDeskEvent(kind: UInt32(kind), cat: cat?.pet ?? 0, arg: arg, reserved: 0, x: x, y: y))
    }

    var hasInput: Bool { pendingWorld != nil || !pulses.isEmpty || !events.isEmpty }

    func setSettings(_ s: MPDeskSettings, clock: MPPetsClock) {
        var s = s, clock = clock
        if mp_desk_set_settings(handle, &clock, &s) == MP_DESK_OK { settings = s }
    }

    /// Today's shared limits from the ledger file (PetJournal.bin, read only).
    @discardableResult
    func adopt(_ state: Data) -> Bool {
        state.withUnsafeBytes { mp_desk_adopt(handle, $0.bindMemory(to: UInt8.self).baseAddress, $0.count) } == MP_DESK_OK
    }

    // MARK: The step

    /// One update with everything queued. On an error nothing changed and the queue is kept, except for a contained
    /// panic: the core is recreated (both cats home) and the last world is sent again.
    @discardableResult
    func step(_ clock: MPPetsClock) -> Int32 {
        var clock = clock
        var input = MPDeskInput()
        input.struct_size = MemoryLayout<MPDeskInput>.size
        let world = pendingWorld
        let grounds = (world?.grounds ?? []).prefix(Int(MP_DESK_MAX_GROUNDS)).map(\.c)
        let sides = (world?.sides ?? []).prefix(Int(MP_DESK_MAX_SIDES)).map(\.c)
        if let world {
            input.flags = UInt32(MP_DESK_WORLD_SET)
            input.doors = MPDeskDoors(out_x: world.doorX, out_y: world.doorY, out_vy: world.doorVY, home_x: world.homeX)
            input.walls = MPDeskWalls(x0: world.wallX0, x1: world.wallX1, top: world.wallTop, bottom: world.wallBottom)
        }
        var out = MPDeskOutput()
        let status = grounds.withUnsafeBufferPointer { g in
            sides.withUnsafeBufferPointer { s in
                pulses.withUnsafeBufferPointer { p in
                    events.withUnsafeBufferPointer { e in
                        eventsOut.withUnsafeMutableBufferPointer { o in
                            input.grounds = g.baseAddress
                            input.ground_count = g.count
                            input.sides = s.baseAddress
                            input.side_count = s.count
                            input.pulses = p.baseAddress
                            input.pulse_count = p.count
                            input.events = e.baseAddress
                            input.event_count = e.count
                            input.events_out = o.baseAddress
                            input.events_out_capacity = o.count
                            return mp_desk_step(handle, &clock, &input, &out, MemoryLayout<MPDeskOutput>.size)
                        }
                    }
                }
            }
        }
        lastStatus = status
        steps += 1
        if status == MP_DESK_OK {
            output = out
            taken = Array(eventsOut.prefix(Int(out.event_count)))
            if let world { sentWorld = world }
            pendingWorld = nil
            pulses.removeAll(keepingCapacity: true)
            events.removeAll(keepingCapacity: true)
        } else if status == MP_DESK_E_PANIC {
            mp_desk_free(handle)
            var s = settings
            if let fresh = mp_desk_create(&s, seed) { handle = fresh }
            pendingWorld = sentWorld
            sentWorld = nil
            pulses.removeAll(keepingCapacity: true)
            events.removeAll(keepingCapacity: true)
            taken = []
        } else {
            // Bad input (never expected): drop it rather than fail every step after.
            pulses.removeAll(keepingCapacity: true)
            events.removeAll(keepingCapacity: true)
            taken = []
        }
        return status
    }

    func cat(_ cat: DeskCat) -> MPDeskCat { cat == .mimimo ? output.cats.0 : output.cats.1 }

    var frames: Bool { output.flags & UInt32(MP_DESK_OUT_FRAMES) != 0 }
    var hasWake: Bool { output.flags & UInt32(MP_DESK_OUT_HAS_WAKE) != 0 }
    var isHidden: Bool { output.flags & UInt32(MP_DESK_OUT_IS_HIDDEN) != 0 }
    var tier: Int { Int(output.tier) }
    var userState: Int { Int(output.user_state) }

    static func name(_ group: Int, _ value: UInt8) -> String {
        mp_desk_enum_name(UInt32(group), UInt32(value)).map { String(cString: $0) } ?? "?"
    }
}
