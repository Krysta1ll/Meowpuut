import AppKit
import Security
import XPC

// `--smoke` (scripts/build-desk.sh --test): headless checks. No window is shown, no login item or launch agent is
// registered, no Mach service is checked in, and nothing under ~/Library is read or written.
//
// The main one runs the step loop as the shell does (a step only when the core's schedule asks, or on input) over
// a synthetic desktop for 30 simulated seconds: the cats come out and land, a typing scene sends pulses (Momo goes
// onto the candidate glass, Mimimo to the top edge of the window you type in, the clay walk frames advancing one
// per key), Momo comes back, and once you are away the loop has nothing left to wake for. Then the seats message
// to the input method (an in-process XPC round trip with the real signature checks), and 「時間花在哪裡」 (minutes
// only when it is on, never an identifier in the file, the companion's XPC read) in temporary folders.

enum DeskSmoke {
    private static var failures = 0
    private static let day: UInt32 = 20_416

    /// The seats after the typing scene (Momo on the glass, Mimimo out), for the round trip.
    private static var typingSeats = DeskSeats(mimimo: true)

    private static func check(_ ok: Bool, _ what: @autoclosure () -> String) {
        if !ok {
            failures += 1
            print("FAIL: \(what())")
        }
    }

    static func run() -> Int32 {
        world()
        let summary = stepLoop()
        art()
        gait()
        hitTests()
        settingsFile()
        pulses()
        seats()
        activity()
        signing()
        bundle()
        print(failures == 0 ? "喵撲貓窩 smoke: all checks passed. \(summary)" : "喵撲貓窩 smoke: \(failures) check(s) failed")
        return failures == 0 ? 0 : 1
    }

    // MARK: World

    /// The prototype's Mac desktop (pets-view.js, desk.rs tests `mac()`): 1440 x 900, menu bar 24, Dock 70; front to
    /// back the memo (3), the app (2) and the browser (1); the memo's app is frontmost.
    static func syntheticInput() -> WorldInput {
        let screen = ScreenGeom(frame: CGRect(x: 0, y: 0, width: 1440, height: 900), visible: CGRect(x: 0, y: 24, width: 1440, height: 806))
        return WorldInput(screens: [screen], windows: [
            WindowGeom(number: 3, pid: 300, frame: CGRect(x: 130, y: 258, width: 600, height: 392)),
            WindowGeom(number: 2, pid: 200, frame: CGRect(x: 1004, y: 562, width: 336, height: 232)),
            WindowGeom(number: 1, pid: 100, frame: CGRect(x: 700, y: 96, width: 640, height: 500)),
        ], frontPID: 300, doorX: 1180)
    }

    private static func world() {
        let input = syntheticInput()
        let m = WorldSensor.build(input)
        let w = m.world
        check(!m.fullScreen, "synthetic desktop is not full screen")
        func edge(_ owner: UInt32) -> DeskGround? { w.grounds.first { $0.owner == owner && Int($0.kind) == MP_DESK_GROUND_EDGE } }
        check(edge(3).map { ($0.x0, $0.x1, $0.y) == (214, 718, 258) } ?? false, "memo edge 214-718 at 258 (traffic lights cut): \(String(describing: edge(3)))")
        check(edge(1).map { ($0.x0, $0.x1, $0.y) == (784, 1328, 96) } ?? false, "browser edge 784-1328 at 96: \(String(describing: edge(1)))")
        check(edge(2).map { ($0.x0, $0.x1, $0.y) == (1088, 1328, 562) } ?? false, "app edge 1088-1328 at 562: \(String(describing: edge(2)))")
        check(w.grounds.allSatisfy { $0.id != 0 } && Set(w.grounds.map(\.id)).count == w.grounds.count, "ground ids unique and non-zero")
        let shelf = w.grounds.filter { Int($0.kind) == MP_DESK_GROUND_SHELF }
        check(shelf.count == 1 && shelf[0].y == 830 && shelf[0].x0 > 500 && shelf[0].x1 < 940, "the Dock's middle is one shelf at 830: \(shelf)")
        check(w.grounds.filter { Int($0.kind) == MP_DESK_GROUND_FLOOR && $0.y == 900 }.count == 2, "a floor either side of the Dock")
        check((w.doorX, w.doorY, w.wallX0, w.wallX1, w.wallTop, w.wallBottom) == (1180, 84, 0, 1440, 24, 940), "door and walls: \(w)")
        // A window above the others covering the shelf makes that part peek-only.
        var covered = input
        covered.windows.insert(WindowGeom(number: 4, pid: 400, frame: CGRect(x: 500, y: 300, width: 500, height: 560)), at: 0)
        covered.frontPID = 400
        check(WorldSensor.build(covered).world.grounds.contains { $0.flags & UInt32(MP_DESK_GROUND_PEEK_ONLY) != 0 }, "shelf under the window in use is peek-only")
        // A window in use reaching the screen's bottom everywhere: the floor stays, for peeking (never no ground).
        var bottom = input
        bottom.windows.insert(WindowGeom(number: 5, pid: 500, frame: CGRect(x: 0, y: 100, width: 1440, height: 790)), at: 0)
        bottom.frontPID = 500
        let floors = WorldSensor.build(bottom).world.grounds.filter { Int($0.kind) != MP_DESK_GROUND_EDGE }
        check(!floors.isEmpty && floors.allSatisfy { $0.flags & UInt32(MP_DESK_GROUND_PEEK_ONLY) != 0 }, "covered floors stay as peek-only: \(floors.count)")
        // Ground ids do not depend on where a piece starts.
        var moved = covered
        moved.windows[0].frame.origin.x -= 30
        let a = Set(WorldSensor.build(covered).world.grounds.filter { $0.owner == 0 }.map(\.id))
        let b = Set(WorldSensor.build(moved).world.grounds.filter { $0.owner == 0 }.map(\.id))
        check(a == b, "base ground ids stay when the window in use moves")
        // The same measurement twice is the same world (sent once).
        check(WorldSensor.build(input) == m, "a measurement is reproducible")
        // A maximized window has no edge; a window covering its screen is full screen.
        var full = input
        full.windows = [WindowGeom(number: 9, pid: 900, frame: CGRect(x: 0, y: 0, width: 1440, height: 900))]
        full.frontPID = 900
        let fm = WorldSensor.build(full)
        check(fm.fullScreen && !fm.world.grounds.contains { Int($0.kind) == MP_DESK_GROUND_EDGE }, "full-screen window: hidden, no edge")
        // Without window bounds (the fallback): only the Dock and the screen bottom.
        var none = input
        none.windows = []
        let nm = WorldSensor.build(none).world.grounds
        check(!nm.isEmpty && nm.allSatisfy { Int($0.kind) != MP_DESK_GROUND_EDGE }, "no bounds: Dock and floor only")
        check(WorldSensor.owner(at: CGPoint(x: 304, y: 330), in: input.windows) == 3, "the composition origin is in the memo")
        check(WorldSensor.owner(at: CGPoint(x: 5, y: 5), in: input.windows) == 0, "no window at the menu bar")
    }

    // MARK: The step loop

    private static func clock(_ t: UInt64) -> MPPetsClock { MPPetsClock(mono_ms: t, day: day, minute: 900) }

    private static func stepLoop() -> String {
        guard let core = DeskCore(settings: DeskCore.settings(on: true, quiet: false, reduceMotion: false,
                                                               typing: UInt32(MP_DESK_TYPING_SPLIT), scale: 1), seed: 20_261_123) else {
            check(false, "mp_desk_create")
            return ""
        }
        let input = syntheticInput()
        let art = DeskArt.shared
        check(core.setWorld(WorldSensor.build(input).world), "the first world is sent")
        check(!core.setWorld(WorldSensor.build(input).world), "an unchanged world is not sent again")
        check(DeskCore.clayArt == UInt8(MP_DESK_ART_SLEEP | MP_DESK_ART_PEEK | MP_DESK_ART_SIDE | MP_DESK_ART_WALK),
              "clay has walk frames: art \(DeskCore.clayArt)")
        var t: UInt64 = 1000
        var steps = 0, quick = 0, events: [UInt32: Int] = [:]
        var looks = Set<String>()
        var gaits = DeskCat.allCases.map { DeskGait(length: DeskArt.cycleLength($0)) }
        /// Steps after which a cat was walking: the core wakes at the walk's cadence then (desk.rs schedule).
        var walkingSteps = 0

        func step() {
            let status = core.step(clock(t))
            steps += 1
            check(status == MP_DESK_OK, "mp_desk_step at \(t): \(status)")
            check(core.output.pulses_rejected == 0, "no pulse rejected at \(t)")
            for e in core.taken { events[e.kind, default: 0] += 1 }
            let typingSteps = core.taken.filter { $0.kind == UInt32(MP_DESK_OUT_STEP) }.count
            if DeskCat.allCases.contains(where: { DeskGait.isWalking(core.cat($0)) }) { walkingSteps += 1 }
            for cat in DeskCat.allCases {
                let c = core.cat(cat)
                check(c.x.isFinite && c.y.isFinite, "\(cat) position finite at \(t)")
                check(c.scale_y >= 0.94, "\(cat) squashed at most 6 % at \(t): \(c.scale_y)")
                gaits[cat.rawValue].update(c, typingSteps: typingSteps, nowMs: Double(t), motion: true)
                if let look = DeskLook.of(cat, c, gait: gaits[cat.rawValue], nowMs: Double(t)) {
                    looks.insert(look.name)
                    check(art.picture(look.name) != nil, "picture \(look.name) for \(DeskCore.name(MP_DESK_GROUP_POSE, c.pose))")
                }
            }
        }
        /// What the shell does: frames every 16 ms, else one wake, else nothing until input.
        func run(to end: UInt64, until stop: (() -> Bool)? = nil) {
            while stop?() != true {
                let next: UInt64
                if core.frames {
                    next = t + 16
                } else if core.hasWake {
                    let wake = core.output.wake_at_ms
                    check(wake > t, "wake after now at \(t)")
                    if wake < t + 83 { quick += 1 }
                    next = max(t + 1, wake)
                } else { break }
                if next > end { break }
                t = next
                step()
            }
            if stop?() != true { t = max(t, end) }
        }
        func grounded(_ cat: DeskCat) -> Bool { Int(core.cat(cat).body) == MP_DESK_BODY_GROUND && core.cat(cat).ground != 0 }

        // The cats come out of the door and find the desk.
        core.event(MP_DESK_EV_POINTER, x: 60, y: 400)
        for cat in DeskCat.allCases { core.event(MP_DESK_EV_OUT, cat, arg: 1) }
        step()
        check(DeskCat.allCases.allSatisfy { Int(core.cat($0).body) != MP_DESK_BODY_HOME }, "both cats came out")
        check(core.output.locomotion.0 == UInt8(MP_DESK_LOCO_WALK) && core.output.locomotion.1 == UInt8(MP_DESK_LOCO_WALK),
              "clay walks (locomotion WALK, not HOP)")
        run(to: 11_000)
        run(to: 14_000) { grounded(.mimimo) && grounded(.momo) }
        check(grounded(.mimimo) && grounded(.momo), "both cats grounded: \(core.cat(.mimimo).body), \(core.cat(.momo).body)")
        let outSteps = steps

        // Typing: one pulse per key, 150 ms apart, in the window whose top edge Mimimo is on (the memo when she is
        // not on an edge), the composition starting some way to her right (so that she walks a while) on a line 60 pt
        // into the window.
        let world0 = WorldSensor.build(input).world
        let mimiStart = core.cat(.mimimo)
        let mimiGround = world0.grounds.first { $0.id == mimiStart.ground }
        let onEdge = mimiGround.map { Int($0.kind) == MP_DESK_GROUND_EDGE } ?? false
        let target = input.windows.first { onEdge && $0.number == mimiGround?.owner } ?? input.windows[0]
        let caret = stride(from: target.frame.minX + 100, to: target.frame.maxX - 40, by: 20)
            .map { CGPoint(x: $0, y: target.frame.minY + 60) }
            .filter { WorldSensor.owner(at: CGPoint(x: $0.x + 4, y: $0.y + 11), in: input.windows) == target.number }
            .min { abs($0.x - CGFloat(mimiStart.x) - 160) < abs($1.x - CGFloat(mimiStart.x) - 160) } ?? CGPoint(x: 300, y: 318)
        var origin = MPPulseOrigin()
        check(mp_pulse_origin_measure(Double(caret.x), Double(caret.y), 22, &origin) == 1, "origin measured")
        let point = CGPoint(x: Double(origin.x) * 8 + 4, y: Double(origin.y) * 8 + max(4, Double(origin.line) * 4))
        let owner = WorldSensor.owner(at: point, in: input.windows)
        check(owner == target.number, "the caret's window is found from the origin: \(owner)")
        var pulse = MPPulse()
        pulse.origin_x = origin.x
        pulse.origin_y = origin.y
        pulse.line = origin.line
        var momoOnGlass = false
        let typingFrom = t
        // Mimimo's walk frame after each key that she stepped with (pose walk or trot).
        var walkFrames: [(key: Int, frame: Int, name: String)] = []
        var stepsTaken = 0
        for key in 1...33 {
            pulse.seq = UInt32(key)
            pulse.keys = UInt32(key)
            let last = key == 33
            pulse.flags = UInt32(MP_PULSE_ORIGIN) | (last ? 0 : UInt32(MP_PULSE_COMPOSING))
            pulse.commits = last ? 1 : 0
            pulse.graphemes = last ? 4 : 0
            var bytes = [UInt8](repeating: 0, count: Int(MP_PULSE_BYTES))
            check(mp_pulse_encode(&pulse, &bytes, bytes.count) == Int(MP_PULSE_BYTES), "pulse encoded")
            core.pulse(bytes, caretOwner: owner)
            let before = events[UInt32(MP_DESK_OUT_STEP), default: 0]
            step()
            let stepped = events[UInt32(MP_DESK_OUT_STEP), default: 0] - before
            stepsTaken += stepped
            let m = core.cat(.mimimo)
            if stepped > 0 && DeskGait.isWalking(m), let look = DeskLook.of(.mimimo, m, gait: gaits[0], nowMs: Double(t)) {
                walkFrames.append((key, gaits[0].frame, look.name))
            }
            if Int(core.cat(.momo).body) == MP_DESK_BODY_GLASS { momoOnGlass = true }
            run(to: t + 150)
        }
        // One key, one step, one frame: consecutive stepping keys show the next frame of the four.
        check(walkFrames.count >= 4, "Mimimo walked with the keys: \(walkFrames.count) stepping keys")
        check(zip(walkFrames, walkFrames.dropFirst()).allSatisfy { a, b in b.key != a.key + 1 || b.frame == (a.frame + 1) % DeskGait.keyPoses },
              "the walk frame advances one per key: \(walkFrames.map { "\($0.key):\($0.frame + 1)" })")
        check(Set(walkFrames.map(\.name)).count == min(4, walkFrames.count) && walkFrames.allSatisfy { $0.name.hasPrefix("mimimo_walk_") },
              "walk_1…4 all shown: \(Set(walkFrames.map(\.name)).sorted())")
        let walkNote = "\(walkFrames.count) keys walked, frames \(walkFrames.prefix(8).map { String($0.frame + 1) }.joined())…"
        // What the input method's glass hears now: Momo is on it, Mimimo out on your window's edge (or on it too).
        let seatsNow = DeskController.seats(core)
        let mimiOut = Int(core.cat(.mimimo).body) != MP_DESK_BODY_GLASS
        check(seatsNow == DeskSeats(mimimo: mimiOut, momo: false), "seats after typing: \(seatsNow.bits)")
        check(GlassSeats.showsMimimo(seatsNow, typingSeat: "split") == !mimiOut, "the glass draws Mimimo only when she is not out")
        typingSeats = seatsNow
        let typingSteps = steps - outSteps
        check(momoOnGlass && Int(core.cat(.momo).body) == MP_DESK_BODY_GLASS, "Momo is on the candidate glass after the pulses")
        check(DeskLook.of(.momo, core.cat(.momo)) == nil, "nothing is drawn for Momo on the desktop while she is on the glass")
        check(events[UInt32(MP_DESK_OUT_GLASS), default: 0] >= 1, "a GLASS event")
        let mimi = core.cat(.mimimo)
        let mimiNow = world0.grounds.first { $0.id == mimi.ground }
        if onEdge {
            check(Int(mimi.body) == MP_DESK_BODY_GROUND && mimiNow?.owner == target.number,
                  "Mimimo walks along the top edge of the window you type in: body \(mimi.body) ground \(mimi.ground)")
        } else {
            check((Int(mimi.body) == MP_DESK_BODY_GROUND && mimiNow?.owner == target.number) || Int(mimi.body) == MP_DESK_BODY_GLASS,
                  "Mimimo goes to the window's top edge or joins the glass: body \(mimi.body) ground \(mimi.ground)")
        }
        check(core.userState == MP_DESK_USER_TYPING || core.userState == MP_DESK_USER_PRESENT, "typing state")
        let typingNote = "Mimimo from (\(Int(mimiStart.x)), \(Int(mimiStart.y))) "
            + (mimiNow?.owner == target.number ? "along window \(target.number)'s top edge to x \(Int(mimi.x))" : DeskCore.name(MP_DESK_GROUP_BODY, mimi.body))
            + ", caret x \(Int(caret.x)), \(stepsTaken) steps, \(walkNote)"
        check(t - typingFrom < 6000, "the typing scene took 5 s")

        // Eight seconds after the last key Momo drops back to the desk; 30 simulated seconds in all.
        run(to: 31_000)
        run(to: 34_000) { grounded(.momo) }
        check(grounded(.momo), "Momo is back on the desk: body \(core.cat(.momo).body)")
        let thirty = steps

        // Sitting still with nobody touching anything: an event (a landing, a hop) earns 20 s of 12 Hz breathing,
        // never faster without frames; between events the core asks for a wake only when the next thing happens.
        var quietSeconds = 0, busiest = 0, quietFrames = 0, walkingBusiest = 0
        let quietFrom = t
        run(to: t + 25_000)
        let settled = t
        while t < settled + 60_000 {
            let wasFrames = core.frames
            let before = steps, walkedBefore = walkingSteps
            run(to: min(t + 1000, settled + 60_000))
            let n = steps - before
            if n == 0 { quietSeconds += 1 }
            if wasFrames || core.frames {
                quietFrames += n
            } else if walkingSteps > walkedBefore {
                walkingBusiest = max(walkingBusiest, n)
            } else {
                busiest = max(busiest, n)
            }
            if ProcessInfo.processInfo.environment["DESK_TRACE"] != nil {
                let m = core.cat(.mimimo), o = core.cat(.momo)
                print("t \(t) steps \(n) frames \(core.frames) wake \(core.hasWake ? Int64(core.output.wake_at_ms) - Int64(t) : -1) tier \(core.tier) "
                    + "mimi \(DeskCore.name(MP_DESK_GROUP_ACT, m.act))/\(DeskCore.name(MP_DESK_GROUP_POSE, m.pose)) "
                    + "momo \(DeskCore.name(MP_DESK_GROUP_ACT, o.act))/\(DeskCore.name(MP_DESK_GROUP_POSE, o.pose))")
            }
        }
        check(busiest <= 13, "without frames or a walk, never faster than the 12 Hz tick: \(busiest) in a second")
        // Walking, a wake per step: Mimimo 9 steps a second, Momo 7, plus the tick.
        check(walkingBusiest <= 9 + 7 + 13, "walking, wakes at the step cadence: \(walkingBusiest) in a second")
        check(quietSeconds >= 10, "between events the desk is silent: \(quietSeconds) silent seconds of 60")
        let quietNote = "a quiet minute (from \((settled - quietFrom) / 1000) s after the scene): \(quietSeconds) silent seconds, at most \(busiest) wakes a second "
            + "(\(walkingBusiest) while walking), \(quietFrames) frame steps"

        // Full screen: put away, nothing runs; back again.
        core.event(MP_DESK_EV_HIDDEN, arg: 1)
        step()
        check(core.isHidden && core.tier == MP_DESK_TIER_OFF && !core.hasWake && !core.frames, "hidden: no timer, no frames")
        core.event(MP_DESK_EV_HIDDEN, arg: 0)
        step()
        check(!core.isHidden, "shown again")

        // Away: the loop runs to the moment you count as away, then has nothing to wake for.
        run(to: t + 20 * 60_000) { core.userState == MP_DESK_USER_AWAY }
        check(core.userState == MP_DESK_USER_AWAY, "away after five minutes without input")
        check(core.tier == MP_DESK_TIER_OFF && !core.hasWake && !core.frames, "away: tier OFF, no wake, no frames")
        let before = steps
        run(to: t + 10 * 60_000)
        check(steps == before, "zero wakes in ten minutes away (\(steps - before))")
        return "\(thirty) steps for 30 simulated s (\(outSteps) coming out, \(typingSteps) typing; \(typingNote)), \(quick) under 83 ms; "
            + "\(quietNote); drawn: \(looks.sorted().joined(separator: " "))"
    }

    // MARK: Art and hit tests

    private static func art() {
        let desk = ["turn", "look_up", "lift", "fall"]
        let walks = DeskCat.allCases.flatMap { DeskArt.shared.cycles["\($0.asset)_walk"] ?? [] }
        let names = walks + (["idle", "press", "happy", "sleep", "edge_rest", "edge_peek", "edge_lounge", "side_hold"] + desk).map { "mimimo_\($0)" }
            + (["idle", "wave", "happy", "jump", "sleep", "edge_rest", "edge_peek", "edge_lounge", "side_hold"] + desk).map { "momo_\($0)" }
        check(DeskArt.shared.poses.count == 33 && DeskArt.shared.cycles.count == 2 && walks.count == 24,
              "desk.json in the bundle: \(DeskArt.shared.poses.count) poses, \(walks.count) walk frames")
        check(DeskCat.allCases.allSatisfy { DeskArt.shared.walkLength($0) == 12 }, "the walk cycles are 12 frames")
        check(DeskArt.shared.hasWalkFrames, "every walk frame is in the bundle")
        for (name, size) in [("mimimo_walk_1", CGSize(width: 96, height: 64)), ("momo_lift", CGSize(width: 64, height: 80)), ("momo_sleep", CGSize(width: 64, height: 64))] {
            check(DeskArt.shared.sprite(name)?.size == size, "\(name) is \(size.width) x \(size.height) pt")
        }
        check(DeskArt.shared.sprite("mimimo_lift")?.anchor == CGPoint(x: 0.5, y: 0.06) && DeskArt.shared.sprite("momo_walk_3")?.anchor == CGPoint(x: 0.5, y: 0.9),
              "anchors: the scruff for lift, the feet for the rest")
        // momo_lift is the 3D render and hangs from the rig's own scruff, lower than the nominal point.
        if let scruff = DeskArt.shared.sprite("momo_lift")?.anchor {
            check(abs(scruff.x - 0.4986) < 1e-4 && abs(scruff.y - 0.1278) < 1e-4, "momo_lift's anchor is the rig's scruff: \(scruff)")
        } else {
            check(false, "momo_lift is in desk.json")
        }
        check(DeskArt.shared.picture("momo_lift")?.foot == DeskArt.shared.sprite("momo_lift")?.anchor, "the lifted cat hangs from that anchor")
        check(DeskArt.parse(Data("{}".utf8)) == nil && DeskArt.parse(Data(#"{"poses":{"x":{"size":[0,1],"anchor":[0,0],"bounds":[0,0,1,1]}}}"#.utf8))?.poses.isEmpty == true,
              "a broken desk.json gives no poses")
        for name in names {
            guard let p = DeskArt.shared.picture(name) else { check(false, "\(name) loads from the bundle"); continue }
            check(p.width == Int(p.sprite.size.width * 2) && p.height == Int(p.sprite.size.height * 2), "\(name) is the @2x picture")
            check(!p.opaque(u: 0.01, v: 0.01), "\(name) corner is clear")
            let b = p.sprite.bounds
            let probes: [(CGFloat, CGFloat)] = [(0, 0), (-0.08, 0), (0.08, 0), (0, -0.08), (0, 0.08)]
            check(probes.contains { p.opaque(u: b.midX + $0.0, v: b.midY + $0.1) }, "\(name) has a body")
        }
        // Every pose and seat maps to a picture.
        for cat in DeskCat.allCases {
            for pose in 1...21 {
                for seat in 0...2 {
                    var c = MPDeskCat()
                    c.body = UInt8(MP_DESK_BODY_GROUND)
                    c.pose = UInt8(pose)
                    c.seat = UInt8(seat)
                    let look = DeskLook.of(cat, c)
                    // Its own picture, never a fallback: every pose clay draws is in the bundle now.
                    check(look.flatMap { DeskArt.shared.picture($0.name) } != nil, "\(cat) pose \(pose) seat \(seat): \(look?.name ?? "-")")
                }
            }
            var home = MPDeskCat()
            home.body = UInt8(MP_DESK_BODY_HOME)
            check(DeskLook.of(cat, home) == nil, "a cat at home is not drawn")
            var c = MPDeskCat()
            c.body = UInt8(MP_DESK_BODY_GROUND)
            for (pose, name) in [(MP_DESK_POSE_SLEEP, "sleep"), (MP_DESK_POSE_AIR, "fall"), (MP_DESK_POSE_HELD, "lift"), (MP_DESK_POSE_LOOK, "look_up"),
                                 (MP_DESK_POSE_WALK, "walk_1"), (MP_DESK_POSE_TROT, "walk_1")] {
                c.pose = UInt8(pose)
                check(DeskLook.of(cat, c)?.name == "\(cat.asset)_\(name)", "\(cat.asset) \(DeskCore.name(MP_DESK_GROUP_POSE, c.pose)) wears \(name)")
            }
        }
        // Without its picture a look falls back (a bundle from before the poses).
        check(DeskArt.shared.picture(for: DeskLook(name: "momo_nonesuch", fallback: "momo_idle"))?.sprite.name == "momo_idle", "a missing pose falls back")
    }

    /// The gait: one step (a STEP event while typing, a parity change on a stroll) advances length / 4 frames, so it lands
    /// on the next of walk_1…4; then `turn`.
    private static func gait() {
        let cycle = DeskArt.shared.cycles["mimimo_walk"] ?? []
        check(cycle.count == 12, "mimimo's cycle has 12 frames: \(cycle.count)")
        var g = DeskGait(length: cycle.count)
        var c = MPDeskCat()
        c.body = UInt8(MP_DESK_BODY_GROUND)
        c.pose = UInt8(MP_DESK_POSE_SIT)
        g.update(c, typingSteps: 0, nowMs: 0, motion: true)
        c.pose = UInt8(MP_DESK_POSE_WALK)
        c.act = UInt8(MP_DESK_ACT_STROLL)
        var seen: [Int] = [], shown: [String] = []
        for i in 1...6 {
            c.phase ^= 1
            g.update(c, typingSteps: 0, nowMs: Double(i * 100), motion: true)
            g.update(c, typingSteps: 0, nowMs: Double(i * 100 + 50), motion: true)  // a wake without a step
            seen.append(g.index)
            shown.append(DeskLook.of(.mimimo, c, gait: g, nowMs: 0)?.name ?? "-")
        }
        check(seen == [3, 6, 9, 0, 3, 6], "a stroll: one step is 3 frames of 12, none between: \(seen)")
        check(shown == ["mimimo_walk_2", "mimimo_walk_3", "mimimo_walk_4", "mimimo_walk_1", "mimimo_walk_2", "mimimo_walk_3"],
              "steps land on the key poses, never an in-between: \(shown)")
        c.act = UInt8(MP_DESK_ACT_TYPING)
        // Keys: one step each, the frame of the key, the same four poses in a 12-frame cycle.
        var typed: [String] = []
        for _ in 1...8 {
            g.update(c, typingSteps: 1, nowMs: 700, motion: true)
            typed.append(DeskLook.of(.mimimo, c, gait: g, nowMs: 0)?.name ?? "-")
        }
        check(typed == ["mimimo_walk_4", "mimimo_walk_1", "mimimo_walk_2", "mimimo_walk_3", "mimimo_walk_4", "mimimo_walk_1", "mimimo_walk_2", "mimimo_walk_3"],
              "one key, one step: \(typed)")
        check(typed.allSatisfy { !$0.contains("_f") }, "no in-between on the typing path")
        g.update(c, typingSteps: 2, nowMs: 700, motion: true)
        check(g.index == 0 && g.frame == 0, "two keys in one update are two steps (the parity is back where it was): \(g.index)")
        // The 4-frame fallback: no cycle in desk.json (or one of a length that is not a multiple of four).
        var four = DeskGait(length: 7)
        check(four.length == 4 && four.stepFrames == 1 && DeskGait(length: 12).stepFrames == 3, "a cycle length that is not a multiple of four falls back to 4")
        let empty = DeskArt(bundle: Bundle(path: NSTemporaryDirectory())!)  // a folder without desk.json
        check(empty.walkLength(.mimimo) == 4 && empty.walkName(.mimimo, 5, length: 4) == "mimimo_walk_2" && empty.walkName(.momo, -1, length: 4) == "momo_walk_4",
              "no desk.json: four frames walk_1…4, wrapping")
        c.act = UInt8(MP_DESK_ACT_STROLL)
        c.pose = UInt8(MP_DESK_POSE_SIT)
        four.update(c, typingSteps: 0, nowMs: 0, motion: true)
        c.pose = UInt8(MP_DESK_POSE_WALK)
        var fourSeen: [Int] = []
        for _ in 1...5 {
            c.phase ^= 1
            four.update(c, typingSteps: 0, nowMs: 1, motion: true)
            fourSeen.append(four.index)
        }
        check(fourSeen == [1, 2, 3, 0, 1], "a 4-frame cycle advances one frame per step: \(fourSeen)")
        c.pose = UInt8(MP_DESK_POSE_SIT)
        g.update(c, typingSteps: 0, nowMs: 800, motion: true)
        check(DeskLook.of(.momo, c, gait: g, nowMs: 900)?.name == "momo_turn", "the walk ends with turn")
        check(DeskLook.of(.momo, c, gait: g, nowMs: 800 + DeskGait.turnMs)?.name == "momo_idle", "then she sits facing you")
        var still = DeskGait()
        c.pose = UInt8(MP_DESK_POSE_WALK)
        still.update(c, typingSteps: 1, nowMs: 0, motion: false)
        c.pose = UInt8(MP_DESK_POSE_SIT)
        still.update(c, typingSteps: 0, nowMs: 100, motion: false)
        check(!still.turning(at: 150), "Reduce Motion: no turn")
        check(DeskArt.walkFrame(.mimimo, 15, length: 12) == cycle[3], "frames wrap")
        check(DeskArt.walkFrame(.momo, -1, length: 12) == DeskArt.shared.cycles["momo_walk"]?[11], "a negative frame wraps to the last")
    }

    private static func hitTests() {
        let frame = CatPanel.Frame(primaryHeight: 900)
        let mimi = CatPanel(cat: .mimimo)
        var c = MPDeskCat()
        c.body = UInt8(MP_DESK_BODY_GROUND)
        c.pose = UInt8(MP_DESK_POSE_SIT)
        c.seat = UInt8(MP_DESK_SEAT_TOP)
        c.facing = 1
        c.x = 500
        c.y = 500
        c.scale_x = 1
        c.scale_y = 1
        check(mimi.layout(c, art: .shared, frame: frame), "Mimimo laid out")
        check(!mimi.shown, "laying out does not show the panel")
        check(mimi.opaque(atDesktop: CGPoint(x: 500, y: 475), primaryHeight: 900), "Mimimo's body takes a hit")
        check(!mimi.opaque(atDesktop: CGPoint(x: 440, y: 475), primaryHeight: 900), "beside Mimimo passes through")
        check(!mimi.opaque(atDesktop: CGPoint(x: 500, y: 520), primaryHeight: 900), "under her paws (the shadow) passes through")
        c.facing = -1
        mimi.layout(c, art: .shared, frame: frame)
        check(mimi.opaque(atDesktop: CGPoint(x: 500, y: 475), primaryHeight: 900), "mirrored, still a hit")
        c.tilt = 18
        mimi.layout(c, art: .shared, frame: frame)
        check(mimi.opaque(atDesktop: CGPoint(x: 500, y: 480), primaryHeight: 900), "tilted, still a hit")

        // Walking: the frames face right and are mirrored for left; she still takes a hit on her body.
        var w = c
        w.tilt = 0
        w.pose = UInt8(MP_DESK_POSE_WALK)
        w.facing = -1
        let walker = CatPanel(cat: .mimimo)
        check(walker.layout(w, art: .shared, frame: frame) && walker.picture?.sprite.name == "mimimo_walk_1", "walk_1 laid out")
        check(walker.opaque(atDesktop: CGPoint(x: 500, y: 480), primaryHeight: 900), "a walking cat takes a hit")
        let leftSnap = walker.snapshot()
        w.facing = 1
        walker.layout(w, art: .shared, frame: frame)
        let rightSnap = walker.snapshot()
        check(leftSnap != nil && rightSnap != nil && Self.mirrored(leftSnap!, rightSnap!), "walking left is walking right mirrored")
        // Lifted: lift hangs from its scruff, 0.82 of the body above the paws' point (desk.rs drag).
        var held = c
        held.tilt = 0
        held.body = UInt8(MP_DESK_BODY_HELD)
        held.pose = UInt8(MP_DESK_POSE_HELD)
        held.scale_y = 1.05
        let lifted = CatPanel(cat: .momo)
        check(lifted.layout(held, art: .shared, frame: frame) && lifted.picture?.sprite.name == "momo_lift", "lift laid out")
        let scruffY = 500 - 0.82 * DeskCat.momo.body.height
        check(lifted.opaque(atDesktop: CGPoint(x: 500, y: scruffY + 26), primaryHeight: 900), "the lifted body hangs under the scruff")
        check(!lifted.opaque(atDesktop: CGPoint(x: 500, y: scruffY - 12), primaryHeight: 900), "nothing above the scruff")

        let momo = CatPanel(cat: .momo)
        var b = MPDeskCat()
        b.body = UInt8(MP_DESK_BODY_GROUND)
        b.pose = UInt8(MP_DESK_POSE_PEEK)
        b.seat = UInt8(MP_DESK_SEAT_BEHIND)
        b.facing = 1
        b.x = 500
        b.y = 500
        b.scale_x = 1
        b.scale_y = 1
        check(momo.layout(b, art: .shared, frame: frame) && momo.maskPath != nil, "Momo behind an edge is cut")
        check(momo.opaque(atDesktop: CGPoint(x: 500, y: 480), primaryHeight: 900), "her head over the edge takes a hit")
        check(!momo.opaque(atDesktop: CGPoint(x: 500, y: 510), primaryHeight: 900), "the body behind the edge passes through")
        check(momo.snapshot() != nil && mimi.snapshot() != nil, "panels draw a snapshot")
        // DESK_SHOTS=DIR keeps these two pictures (a tilted Mimimo, Momo peeking over an edge) for a look.
        if let dir = ProcessInfo.processInfo.environment["DESK_SHOTS"] {
            let url = URL(fileURLWithPath: dir, isDirectory: true)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            if let image = mimi.snapshot() { DeskShots.write(image, to: url.appendingPathComponent("smoke-mimimo-tilt.png")) }
            if let image = momo.snapshot() { DeskShots.write(image, to: url.appendingPathComponent("smoke-momo-peek.png")) }
            if let image = leftSnap { DeskShots.write(image, to: url.appendingPathComponent("smoke-mimimo-walk-left.png")) }
            if let image = lifted.snapshot() { DeskShots.write(image, to: url.appendingPathComponent("smoke-momo-lift.png")) }
            // Four steps of each cat, as the panel draws them (one frame per step), facing right then left.
            for cat in DeskCat.allCases {
                for facing: Int8 in [1, -1] {
                    let panel = CatPanel(cat: cat)
                    var step = w
                    step.facing = facing
                    step.act = UInt8(MP_DESK_ACT_STROLL)
                    for n in 1...4 {
                        step.phase ^= 1
                        if panel.layout(step, art: .shared, frame: frame), let image = panel.snapshot() {
                            let name = "smoke-\(cat.asset)-walk-\(facing > 0 ? "right" : "left")-\(n)-\(panel.picture?.sprite.name ?? "-").png"
                            DeskShots.write(image, to: url.appendingPathComponent(name))
                        }
                    }
                }
            }
            var lounge = b
            lounge.pose = UInt8(MP_DESK_POSE_LOUNGE)
            if momo.layout(lounge, art: .shared, frame: frame), let image = momo.snapshot() {
                DeskShots.write(image, to: url.appendingPathComponent("smoke-momo-lounge.png"))
            }
        }
    }

    /// Two snapshots are each other's mirror image (alpha only, a pixel or two of resampling allowed).
    private static func mirrored(_ a: CGImage, _ b: CGImage) -> Bool {
        func alpha(_ image: CGImage) -> [UInt8]? {
            let w = image.width, h = image.height
            var rgba = [UInt8](repeating: 0, count: w * h * 4)
            let ok = rgba.withUnsafeMutableBytes { raw -> Bool in
                guard let context = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                              space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
                context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
                return true
            }
            return ok ? stride(from: 3, to: rgba.count, by: 4).map { rgba[$0] } : nil
        }
        guard a.width == b.width, a.height == b.height, let pa = alpha(a), let pb = alpha(b) else { return false }
        let w = a.width, h = a.height
        var differ = 0, opaque = 0
        for y in 0..<h {
            for x in 0..<w {
                let l = pa[y * w + x] >= 128, r = pb[y * w + (w - 1 - x)] >= 128
                if l || r { opaque += 1 }
                if l != r { differ += 1 }
            }
        }
        return opaque > 0 && Double(differ) / Double(opaque) < 0.03
    }

    // MARK: Settings

    private static func settingsFile() {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MeowpuutDesk-Smoke-\(getpid())")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let reader = DeskSettingsReader(directory: directory, watch: false)
        check(reader.preferences == DeskPreferences() && reader.preferences.on, "no file: defaults (on, split)")
        check((try? FileManager.default.contentsOfDirectory(atPath: directory.path))?.isEmpty == true, "the desk never creates settings.json")
        let json = #"{"schema":1,"settings":{"deskPets":false,"typingSeat":"glass","inputMode":"zhuyin"}}"#
        try? Data(json.utf8).write(to: reader.fileURL)
        reader.refresh()
        check(!reader.preferences.on && reader.preferences.typing == UInt32(MP_DESK_TYPING_GLASS), "deskPets off, typingSeat glass read")
        check(!DeskPreferences(settings: ["catGlassSkin": false]).on, "cats off altogether: no desk cats")
        check(DeskPreferences(settings: ["typingSeat": 3, "deskPets": "yes"]) == DeskPreferences(), "wrong types are defaults")
        // 「時間花在哪裡」: off unless written, and the hidden list cleaned.
        check(DeskPreferences().activity == ForegroundWatcher.Switches() && !DeskPreferences(settings: [:]).activityStats, "activity stats off by default")
        let a = DeskPreferences(settings: ["activityStats": true, "activityPaused": true,
                                           "activityHidden": ["com.example.Secret", " ", 7, "COM.EXAMPLE.SECRET", "org.example.Other"]])
        check(a.activity == ForegroundWatcher.Switches(recording: true, paused: true, hidden: ["com.example.Secret", "org.example.Other"]),
              "activityStats, activityPaused, activityHidden read: \(a.activity)")
        check(!DeskPreferences(settings: ["activityStats": "on", "activityHidden": "x"]).activityStats, "wrong types are off")
    }

    // MARK: Pulses

    private static func pulses() {
        var received: [Data] = []
        let lock = NSLock()
        let arrived = DispatchSemaphore(value: 0)
        let sender = DeskPulseSender(sink: { data in
            lock.lock(); received.append(data); lock.unlock()
            arrived.signal()
        }, secureInput: { false })
        sender.enabled = true
        sender.key()
        sender.key()
        sender.measured(NSRect(x: 300, y: 500, width: 2, height: 22))
        sender.committed(graphemes: 3)
        sender.publish(composing: true, expanded: true, above: false)
        check(arrived.wait(timeout: .now() + 2) == .success, "the pulse left the key path")
        lock.lock()
        let first = received.first
        lock.unlock()
        var decoded = MPPulse()
        let status = first.map { data in data.withUnsafeBytes { mp_pulse_decode($0.bindMemory(to: UInt8.self).baseAddress, $0.count, &decoded) } }
        check(status == Int32(MP_PULSE_OK), "the pulse decodes")
        let height = CGDisplayBounds(CGMainDisplayID()).height
        check(decoded.seq == 1 && decoded.keys == 2 && decoded.commits == 1 && decoded.graphemes == 3, "counters \(decoded)")
        check(decoded.flags == UInt32(MP_PULSE_COMPOSING | MP_PULSE_ORIGIN | MP_PULSE_EXPANDED), "flags \(decoded.flags)")
        check(decoded.origin_x == 37 && Double(decoded.origin_y) == ((height - 522) / 8).rounded(.down) && decoded.line == 3,
              "origin on the 8 pt grid, y down: \(decoded.origin_x), \(decoded.origin_y), \(decoded.line)")
        // A burst coalesces into the newest.
        for _ in 0..<5 { sender.publish(composing: false, expanded: true, above: true) }
        let deadline = Date().addingTimeInterval(2)
        var newest = MPPulse()
        while Date() < deadline {
            _ = arrived.wait(timeout: .now() + 0.05)
            lock.lock()
            let last = received.last
            lock.unlock()
            if let last { _ = last.withUnsafeBytes { mp_pulse_decode($0.bindMemory(to: UInt8.self).baseAddress, $0.count, &newest) } }
            if newest.seq == 6 { break }
        }
        check(newest.seq == 6 && newest.flags == UInt32(MP_PULSE_ORIGIN), "the newest pulse arrives; candidate bits only while composing")
        // Off, and secure input: nothing leaves.
        lock.lock(); let count = received.count; lock.unlock()
        sender.enabled = false
        sender.publish(composing: true, expanded: false, above: false)
        let secure = DeskPulseSender(sink: { data in lock.lock(); received.append(data); lock.unlock() }, secureInput: { true })
        secure.enabled = true
        secure.key()
        secure.publish(composing: true, expanded: false, above: false)
        Thread.sleep(forTimeInterval: 0.3)
        lock.lock(); let after = received.count; lock.unlock()
        check(after == count, "nothing is sent when off or under secure input")

        // The listening end drops anything that is not one well-formed pulse.
        var delivered: [[UInt8]] = []
        let listener = PulseListener(machService: nil) { delivered.append($0) }
        if let first {
            listener.pulse(first)
            listener.pulse(first.prefix(35))
            var torn = first
            torn[10] ^= 0x40
            listener.pulse(torn)
        }
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        check(delivered.count == 1, "the listener passes one good pulse of three: \(delivered.count)")
    }

    // MARK: Seats (喵撲貓窩 -> input method)

    /// Spins the main run loop until `done` or the time is up (deliveries hop to the main queue).
    private static func wait(_ seconds: Double, until done: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while !done() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        return done()
    }

    private static func seats() {
        // The message: 4 bytes, nothing else accepted.
        let both = DeskSeats(mimimo: true, momo: true)
        check(both.data == Data([0x9C, 0xB2, 1, 3]) && DeskSeats(both.data) == both, "seats encode as 9C B2 01 03")
        for bad in [Data([0x9C, 0xB2, 1]), Data([0x9C, 0xB2, 1, 1, 0]), Data([0x9C, 0xB0, 1, 1]), Data([0x9C, 0xB2, 2, 1]), Data([0x9C, 0xB2, 1, 4])] {
            check(DeskSeats(bad) == nil, "not a seats message: \([UInt8](bad))")
        }
        // The glass: both cats without 喵撲貓窩 or in 甲; Momo alone while Mimimo is out in 乙.
        check(GlassSeats.showsMimimo(nil, typingSeat: nil), "no desk: both on the glass")
        check(!GlassSeats.showsMimimo(DeskSeats(mimimo: true), typingSeat: nil) && !GlassSeats.showsMimimo(DeskSeats(mimimo: true), typingSeat: "split"),
              "乙: Mimimo out on the desk is not on the glass")
        check(GlassSeats.showsMimimo(DeskSeats(mimimo: true), typingSeat: "glass"), "甲: both on the glass from the first key")
        check(GlassSeats.showsMimimo(DeskSeats(momo: true), typingSeat: "split"), "Mimimo not out: on the glass")

        // The round trip over a real connection: an anonymous listener in this process, the same signature checks
        // both ways (this app's own identifier stands in for the input method's).
        let own = DeskPulseChannel.deskIdentifier
        var pulses = 0
        let listener = PulseListener(listener: NSXPCListener.anonymous(), peer: own) { _ in pulses += 1 }
        listener.start()
        let lock = NSLock()
        var heard: [DeskSeats?] = []
        let sender = DeskPulseSender(secureInput: { false }, endpoint: listener.endpointForChecks, peer: own) { seats in
            lock.lock(); heard.append(seats); lock.unlock()
        }
        func last() -> [DeskSeats?] { lock.lock(); defer { lock.unlock() }; return heard }
        sender.enabled = true
        sender.key()
        sender.publish(composing: true, expanded: false, above: false)
        check(wait(3) { pulses == 1 && last().count == 1 }, "a pulse arrives and the seats come back at once: \(pulses) pulse(s), \(last().count) message(s)")
        check(last().first == .some(DeskSeats()), "nothing out yet: \(String(describing: last().first))")
        listener.publish(typingSeats)
        check(wait(3) { last().count == 2 }, "a change of seats reaches the input method")
        check(last().last == .some(typingSeats), "the seats after typing arrive as sent: \(String(describing: last().last))")
        let glass = GlassSeats()
        if let message = last().last { glass.update(message) }
        check(GlassSeats.showsMimimo(glass.desk, typingSeat: "split") == !typingSeats.mimimoOnDesk, "the glass follows the message")
        listener.publish(typingSeats)
        sender.key()
        sender.publish(composing: true, expanded: false, above: false)
        check(wait(3) { pulses == 2 } && last().count == 2, "unchanged seats are not sent again")

        // Signed as someone else: refused before any message (the input method's identifier is not this app's).
        var strangers = 0
        let picky = PulseListener(listener: NSXPCListener.anonymous(), peer: DeskPulseChannel.inputMethodIdentifier) { _ in strangers += 1 }
        picky.start()
        var refused: [DeskSeats?] = []
        let stranger = DeskPulseSender(secureInput: { false }, endpoint: picky.endpointForChecks, peer: own) { seats in
            lock.lock(); refused.append(seats); lock.unlock()
        }
        stranger.enabled = true
        stranger.key()
        stranger.publish(composing: true, expanded: false, above: false)
        _ = wait(1) { strangers > 0 }
        lock.lock(); let refusedNow = refused; lock.unlock()
        check(strangers == 0 && !refusedNow.contains { $0 != nil }, "a peer that is not the input method is refused: \(strangers) pulse(s), \(refusedNow)")
        // And the input method's side refuses a desk that is not signed as the desk.
        var fooled = 0
        let wrongDesk = DeskPulseSender(secureInput: { false }, endpoint: listener.endpointForChecks, peer: DeskPulseChannel.inputMethodIdentifier) { seats in
            if seats != nil { lock.lock(); fooled += 1; lock.unlock() }
        }
        let before = pulses
        wrongDesk.enabled = true
        wrongDesk.key()
        wrongDesk.publish(composing: false, expanded: false, above: false)
        _ = wait(1) { pulses > before }
        lock.lock(); let fooledNow = fooled; lock.unlock()
        check(pulses == before && fooledNow == 0, "the input method talks only to a peer signed as 喵撲貓窩: \(pulses - before) pulse(s) out, \(fooledNow) seat message(s) in")
    }

    // MARK: 「時間花在哪裡」

    private static func activity() {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MeowpuutDesk-Activity-\(getpid())")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent(ActivityLedger.fileName)
        let bundleID = "com.microsoft.VSCode", appName = "Visual Studio Code"
        var now: UInt64 = 10_000_000
        var idle: Double? = 4
        func clock() -> MPPetsClock { MPPetsClock(mono_ms: now, day: day, minute: 900) }
        let watcher = ForegroundWatcher(directory: directory, sources: .init(clock: clock, idleSeconds: { idle }, frontmost: { nil }, observe: false))
        func minutes(_ ledger: ActivityLedger?) -> [UInt16] {
            guard let d = ledger?.days().first(where: { $0.day == day }) else { return [] }
            return withUnsafeBytes(of: d.minutes) { Array($0.bindMemory(to: UInt16.self)) }
        }
        let code = Int(mp_activity_classify(bundleID, nil, 0))
        check(code == MP_ACTIVITY_CAT_CODE, "\(bundleID) is code: \(code)")

        // Off (the default): the app changes, time passes, nothing is stepped, read or written.
        watcher.foreground(id: bundleID, name: appName)
        for _ in 0..<5 { now += 60_000; watcher.inputSeen(); watcher.step() }
        check(watcher.ledger == nil && watcher.stepCount == 0 && !FileManager.default.fileExists(atPath: file.path), "off: no tracker, no steps, no file")
        check(watcher.current(includeApp: true).state == UInt32(MP_ACTIVITY_NOW_OFF), "off: the companion hears OFF")

        // On: ten minutes in the editor, a step a minute as the timer would.
        watcher.apply(.init(recording: true))
        watcher.foreground(id: bundleID, name: appName)
        check(watcher.lastOut?.state == UInt32(MP_ACTIVITY_NOW_ACTIVE) && watcher.lastOut?.wake_after_ms == 60_000, "on: active, a minute timer")
        for _ in 0..<10 { now += 60_000; watcher.step() }
        let counted = minutes(watcher.ledger)
        check(counted.count == 10 && counted[code] >= 9 && counted[code] <= 10 && counted.enumerated().allSatisfy { $0.offset == code || $0.element == 0 },
              "ten minutes of code: \(counted)")
        var current = watcher.current(includeApp: true)
        let name = withUnsafeBytes(of: &current.app) { String(cString: $0.bindMemory(to: CChar.self).baseAddress!) }
        check(current.state == UInt32(MP_ACTIVITY_NOW_ACTIVE) && current.category == UInt32(code) && name == appName, "the status line: code, \(name)")
        var bare = watcher.current(includeApp: false)
        check(withUnsafeBytes(of: &bare.app) { $0.allSatisfy { $0 == 0 } } && bare.category == UInt32(code), "without 顯示 App 名稱 no name leaves")
        // Not written per minute: only the batch (or a switch, sleep, quitting) writes.
        check(!FileManager.default.fileExists(atPath: file.path), "minutes are batched, not written as they come")

        // Away: past the threshold nothing counts and no timer is kept; input wakes it.
        idle = 400
        now += 60_000
        watcher.step()
        check(watcher.lastOut?.state == UInt32(MP_ACTIVITY_NOW_IDLE) && watcher.lastOut?.wake_after_ms == 0, "away: idle, no timer")
        let awaySteps = watcher.stepCount
        now += 600_000
        idle = 0
        watcher.inputSeen()
        check(watcher.stepCount == awaySteps + 1 && watcher.lastOut?.state == UInt32(MP_ACTIVITY_NOW_ACTIVE), "back: the first input steps it")
        watcher.inputSeen()
        check(watcher.stepCount == awaySteps + 1, "counting again: input alone does not step")

        // Paused, and a hidden app: nothing counts.
        let before = minutes(watcher.ledger)[code]
        watcher.apply(.init(recording: true, paused: true))
        for _ in 0..<3 { now += 60_000; watcher.step() }
        check(watcher.lastOut?.state == UInt32(MP_ACTIVITY_NOW_PAUSED), "paused")
        watcher.apply(.init(recording: true, hidden: [bundleID.lowercased()]))
        for _ in 0..<3 { now += 60_000; watcher.step() }
        check(watcher.lastOut?.state == UInt32(MP_ACTIVITY_NOW_HIDDEN) && watcher.current(includeApp: true).app.0 == 0, "a hidden app: hidden, no name")
        check(minutes(watcher.ledger)[code] <= before + 1, "paused and hidden add nothing: \(before) -> \(minutes(watcher.ledger)[code])")

        // Off again: counted up to now and written once; then nothing at all.
        watcher.apply(.init(recording: false))
        check(watcher.ledger == nil, "off: the tracker is gone")
        let bytes = (try? Data(contentsOf: file)) ?? Data()
        check(bytes.count == Int(MP_ACTIVITY_STATE_BYTES), "Activity.bin written once at the switch: \(bytes.count) bytes")
        for needle in [bundleID, bundleID.lowercased(), appName, "VSCode"] {
            check(bytes.range(of: Data(needle.utf8)) == nil && bytes.range(of: needle.data(using: .utf16LittleEndian)!) == nil,
                  "the file never holds \(needle)")
        }
        let mode = (try? FileManager.default.attributesOfItem(atPath: file.path))?[.posixPermissions] as? Int
        check(mode == 0o600, "Activity.bin is the owner's only: \(String(mode ?? 0, radix: 8))")
        let stamp = (try? FileManager.default.attributesOfItem(atPath: file.path))?[.modificationDate] as? Date
        watcher.foreground(id: "com.apple.TextEdit", name: "TextEdit")
        for _ in 0..<3 { now += 60_000; watcher.inputSeen() }
        check(((try? FileManager.default.attributesOfItem(atPath: file.path))?[.modificationDate] as? Date) == stamp, "off: the file is left alone")
        let reread = ActivityLedger(directory: directory)
        check(minutes(reread)[code] == minutes(reread).reduce(0, +) && minutes(reread)[code] >= 9, "the file holds the minutes: \(minutes(reread))")

        // Clearing: every day goes, and a copy from before (another process still counting) cannot bring them back.
        ForegroundWatcher.clear(directory: directory, live: nil)
        check(ActivityLedger(directory: directory).days().isEmpty, "cleared")
        let recording = UInt32(MP_ACTIVITY_RECORDING)
        _ = reread.step(clock: clock(), appID: bundleID, appName: nil, idleSeconds: 1, flags: recording)
        now += 120_000
        _ = reread.step(clock: clock(), appID: bundleID, appName: nil, idleSeconds: 1, flags: recording)
        reread.flush(wait: true)
        let afterClear = minutes(ActivityLedger(directory: directory)).reduce(0, +)
        check(afterClear == 0 && reread.days().isEmpty, "a copy from before the clear does not bring minutes back: \(afterClear)")

        // The companion's read: a version-1 request answered with MPActivityCurrent, nothing else.
        var active = MPActivityCurrent()
        active.state = UInt32(MP_ACTIVITY_NOW_ACTIVE)
        active.category = UInt32(code)
        let request = xpc_dictionary_create(nil, nil, 0)
        xpc_dictionary_set_uint64(request, "v", 1)
        check(ActivityService.reply(to: request, current: { _ in active }) == nil, "a request that expects no reply gets none")
        check(ActivityService.reply(to: xpc_dictionary_create(nil, nil, 0), current: { _ in active }) == nil, "no version, no answer")
        let service = ActivityService(anonymousPeer: DeskPulseChannel.deskIdentifier) { includeApp in
            var c = active
            if includeApp { withUnsafeMutableBytes(of: &c.app) { $0.copyBytes(from: Array(appName.utf8)) } }
            return c
        }
        check(service.guarded, "the companion's channel has its signature requirement")
        service.start()
        func ask(_ endpoint: xpc_endpoint_t?, app: Bool) -> MPActivityCurrent? {
            guard let endpoint else { return nil }
            let connection = xpc_connection_create_from_endpoint(endpoint)
            xpc_connection_set_event_handler(connection) { _ in }
            _ = xpc_connection_set_peer_code_signing_requirement(connection, DeskPulseChannel.requirement(identifier: DeskPulseChannel.deskIdentifier, team: DeskPulseChannel.ownTeam))
            xpc_connection_resume(connection)
            defer { xpc_connection_cancel(connection) }
            let message = xpc_dictionary_create(nil, nil, 0)
            xpc_dictionary_set_uint64(message, "v", 1)
            xpc_dictionary_set_bool(message, "app", app)
            let done = DispatchSemaphore(value: 0)
            var answer: MPActivityCurrent?
            xpc_connection_send_message_with_reply(connection, message, DispatchQueue.global()) { reply in
                answer = ActivityService.current(from: reply)
                done.signal()
            }
            _ = done.wait(timeout: .now() + 2)
            return answer
        }
        var got = ask(service.endpoint, app: true)
        let gotName = got.map { c in withUnsafeBytes(of: c.app) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) } }
        check(got?.state == UInt32(MP_ACTIVITY_NOW_ACTIVE) && got?.category == UInt32(code) && gotName == appName, "the companion reads code and the name: \(gotName ?? "-")")
        got = ask(service.endpoint, app: false)
        check(got.map { c in withUnsafeBytes(of: c.app) { $0.allSatisfy { $0 == 0 } } } == true, "without asking for the name, none")
        service.stop()
        let companionOnly = ActivityService(anonymousPeer: ActivityService.companionIdentifier) { _ in active }
        companionOnly.start()
        check(ask(companionOnly.endpoint, app: true) == nil, "a peer not signed as the companion gets nothing")
        companionOnly.stop()

        // The idle API on this Mac (FINAL §11.5): what it says without any permission asked for.
        let seconds = ForegroundWatcher.systemIdleSeconds()
        check(seconds != nil, "the system idle time is readable")
        print("idle: \(seconds.map { String(format: "%.1f s", $0) } ?? "none") since the last input; "
            + "Input Monitoring \(CGPreflightListenEventAccess() ? "granted" : "not granted (not needed)"); "
            + "kCGEventNull would say \(String(format: "%.0f s", CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: .null)))")
    }

    // MARK: Signing and the bundle

    private static func signing() {
        for team in [nil, "ABCDE12345"] {
            for identifier in [DeskPulseChannel.inputMethodIdentifier, DeskPulseChannel.deskIdentifier] {
                let text = DeskPulseChannel.requirement(identifier: identifier, team: team)
                var requirement: SecRequirement?
                check(SecRequirementCreateWithString(text as CFString, [], &requirement) == errSecSuccess, "requirement compiles: \(text)")
            }
        }
        check(!DeskPulseChannel.requirement(identifier: "a", team: "x\" or true").contains("anchor"), "a malformed team is not pasted in")
        check(DeskPulseChannel.requirement(identifier: "a", team: "ABCDE12345").contains("certificate leaf[subject.OU] = \"ABCDE12345\""), "team clause")
    }

    private static func bundle() {
        let main = Bundle.main
        check(main.bundleIdentifier == DeskPulseChannel.deskIdentifier, "bundle id \(main.bundleIdentifier ?? "-")")
        check(main.object(forInfoDictionaryKey: "LSUIElement") as? Bool == true, "an agent app (LSUIElement)")
        let plist = main.bundleURL.appendingPathComponent("Contents/Library/LaunchAgents/\(DeskAgent.plistName)")
        guard let data = try? Data(contentsOf: plist),
              let agent = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] else {
            check(false, "the LaunchAgent plist is in the bundle")
            return
        }
        check(agent["Label"] as? String == DeskAgent.label, "agent label")
        check((agent["MachServices"] as? [String: Any])?[DeskPulseChannel.machService] as? Bool == true, "the Mach service is declared")
        check((agent["MachServices"] as? [String: Any])?[ActivityService.machService] as? Bool == true, "the companion's Mach service is declared")
        let program = agent["BundleProgram"] as? String ?? ""
        check(FileManager.default.isExecutableFile(atPath: main.bundleURL.appendingPathComponent(program).path), "BundleProgram \(program) exists")
        check(agent["LimitLoadToSessionType"] as? String == "Aqua", "runs in the user's GUI session")
        var code: SecCode?
        var staticCode: SecStaticCode?
        var info: CFDictionary?
        if SecCodeCopySelf([], &code) == errSecSuccess, let code, SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode,
           SecCodeCopySigningInformation(staticCode, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess {
            let fields = info as? [String: Any]
            check(fields?[kSecCodeInfoIdentifier as String] as? String == DeskPulseChannel.deskIdentifier, "signed as \(DeskPulseChannel.deskIdentifier)")
            print("signing: identifier \(fields?[kSecCodeInfoIdentifier as String] as? String ?? "-"), team \(DeskPulseChannel.ownTeam ?? "none (ad hoc)")")
        } else {
            check(false, "own signature readable")
        }
    }
}
