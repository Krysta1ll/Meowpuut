import AppKit
import QuartzCore

// The shell's loop (Engine meowpuut.h "Desk", FINAL.md §4.6 item 3): measure the world, forward input, draw what
// the core says, and wake only when the core asks.
//
// - After every step: while the core wants frames (a cat held, in the air, landing, fading) a display link steps
//   every frame; otherwise one timer at wake_at, or none.
// - The tier sets the sampling: AWAKE every 100 ms, DROWSY every 500 ms (timers with leeway so the system can
//   coalesce them), OFF nothing. Each sample reads NSEvent.mouseLocation (no permission), the system idle time and
//   the world (CGWindowList bounds), and steps only when something changed: the world goes to the core only when
//   the measurement differs.
// - You away (OFF, not hidden): zero timers; a one-shot global mouse monitor (mouse events need no permission) is
//   the only thing that wakes the desk, plus the input method's pulses.
// - Full screen (the frontmost window covers its screen, or our panels are not on the active Space): the cats are
//   put away (MP_DESK_EV_HIDDEN), zero timers; Space, app and screen notifications measure again.
// - Clicks pass through a cat until the pointer has rested 250 ms on its opaque pixels (sampled at the tier's
//   rate), never while a mouse button is down or while you type; when the pointer leaves the cat it passes again.
// - After every step the input method hears which cats are out on the desk (DeskSeats, only when that changed), so
//   that in 乙 its candidate glass leaves Mimimo out while she walks your window's top edge.
// - 「時間花在哪裡」 (ForegroundWatcher, off by default) hears the switches, the full-screen state and that input was
//   seen; it keeps its own minute timer only while it counts.

final class DeskController: NSObject, NSMenuDelegate {
    struct Options {
        var settingsDirectory: URL
        /// Check the Mach service in (only when launchd started this copy for the agent).
        var listen: Bool
        var statusItem: Bool
    }

    static let dwellMs: UInt64 = 250

    let core: DeskCore
    let art = DeskArt.shared
    let settings: DeskSettingsReader
    /// 「時間花在哪裡」: does nothing until `activityStats` is on.
    let activity: ForegroundWatcher
    private let counters: DeskCountersStore
    private(set) var panels: [CatPanel] = []
    private(set) var lastMeasure: WorldMeasure?
    private(set) var windows: [WindowGeom] = []
    /// Counts of the events the core took (MP_DESK_OUT_*), for the logs and Desk/shots.
    private(set) var taken: [UInt32: Int] = [:]
    private let options: Options
    private var listener: PulseListener?
    private var activityService: ActivityService?
    private var turnRedraw: DispatchWorkItem?
    private var statusItem: NSStatusItem?
    private let statusMenu = NSMenu()
    private let scale: CGFloat = 1
    private var reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
    private var quietUntil: UInt64 = 0
    private var quietEnd: DispatchWorkItem?
    private var wasOn: Bool?
    private var measuredAt: UInt64 = 0
    private var reportedHidden = false
    private var hiddenBySpace = false
    private var offSpaceSamples = 0
    private var recovering = false
    private var lastPointer: CGPoint?
    private var lastDown = false
    private var dwellSince: [UInt64?] = [nil, nil]
    private var armed = [false, false]
    private var dragging: DeskCat?
    /// Keeps the process responsive while a cat is held.
    private var holding: NSObjectProtocol?
    private let wakeTimer = DispatchSource.makeTimerSource(queue: .main)
    private let sampleTimer = DispatchSource.makeTimerSource(queue: .main)
    private var sampleMs = 0
    private var displayLink: CADisplayLink?
    private var awayMonitor: Any?
    private var observers: [(NotificationCenter, NSObjectProtocol)] = []
    /// Steps by what asked for them (wake, frame, sample, pulse, input), for --run-seconds.
    private(set) var reasons: [String: Int] = [:]

    init(options: Options) {
        self.options = options
        settings = DeskSettingsReader(directory: options.settingsDirectory, watch: true)
        activity = ForegroundWatcher(directory: options.settingsDirectory)
        counters = DeskCountersStore(directory: options.settingsDirectory)
        let p = settings.preferences
        guard let core = DeskCore(settings: DeskCore.settings(on: p.on, quiet: false, reduceMotion: reduceMotion, typing: p.typing, scale: 1),
                                  seed: arc4random()) else { fatalError("喵撲貓窩：mp_desk_create refused the default settings") }
        self.core = core
        super.init()
    }

    // MARK: Start

    func start() {
        panels = DeskCat.allCases.map { cat in
            let panel = CatPanel(cat: cat)
            panel.view.controller = self
            return panel
        }
        if options.statusItem { makeStatusItem() }
        settings.onChange = { [weak self] in self?.applySettings() }
        wakeTimer.setEventHandler { [weak self] in self?.step("wake") }
        wakeTimer.schedule(deadline: .distantFuture)
        wakeTimer.activate()
        sampleTimer.setEventHandler { [weak self] in self?.sample() }
        sampleTimer.schedule(deadline: .distantFuture)
        sampleTimer.activate()
        observe()
        adoptLedger()
        listener = PulseListener(machService: options.listen ? DeskPulseChannel.machService : nil) { [weak self] in self?.receive($0) }
        listener?.start()
        let activity = activity
        activityService = ActivityService(machService: options.listen ? ActivityService.machService : nil) { activity.current(includeApp: $0) }
        activityService?.start()
        _ = measure()
        let height = WorldSensor.primaryHeight
        let p = WorldSensor.desktop(NSEvent.mouseLocation, primaryHeight: height)
        lastPointer = p
        core.event(MP_DESK_EV_POINTER, x: Double(p.x), y: Double(p.y))
        applySettings()
    }

    func stop() {
        counters.flushIfDue(nowMs: DeskClock.monoMs, force: true)
        activity.stop()
        activityService?.stop()
        activityService = nil
        listener?.publish(DeskSeats())
        removeAwayMonitor()
        displayLink?.invalidate()
    }

    private func observe() {
        let workspace = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.activeSpaceDidChangeNotification, NSWorkspace.didActivateApplicationNotification,
                     NSWorkspace.didWakeNotification, NSWorkspace.screensDidWakeNotification] {
            let token = workspace.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                // Another Space or app: whether our panels are on the active Space is asked again.
                self?.hiddenBySpace = false
                self?.offSpaceSamples = 0
                self?.remeasure()
            }
            observers.append((workspace, token))
        }
        let motion = workspace.addObserver(forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            self.applySettings()
        }
        observers.append((workspace, motion))
        let screens = NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
            self?.remeasure()
        }
        observers.append((NotificationCenter.default, screens))
    }

    /// Today's shared limits (moments, duets, the greeting) from the ledger the input method writes. Read only.
    private func adoptLedger() {
        let file = settings.directory.appendingPathComponent("PetJournal.bin")
        if let data = try? Data(contentsOf: file), data.count == Int(MP_PETS_STATE_BYTES) {
            core.adopt(data)
            let epoch = UInt32(data[4]) | (UInt32(data[5]) << 8) | (UInt32(data[6]) << 16) | (UInt32(data[7]) << 24)
            counters.syncEpoch(epoch)
        }
    }

    private func applySettings() {
        let p = settings.preferences
        let quiet = DeskClock.monoMs < quietUntil
        core.setSettings(DeskCore.settings(on: p.on, quiet: quiet, reduceMotion: reduceMotion, typing: p.typing, scale: Double(scale)),
                         clock: DeskClock.now())
        if wasOn != p.on {
            wasOn = p.on
            if p.on { counters.recordOuting() }
            for cat in DeskCat.allCases { core.event(MP_DESK_EV_OUT, cat, arg: p.on ? 1 : 0) }
        }
        activity.apply(p.activity)
        step()
    }

    // MARK: The step

    func step(_ reason: String = "input") {
        reasons[reason, default: 0] += 1
        let status = core.step(DeskClock.now())
        if status == MP_DESK_E_PANIC && !recovering {
            // The core contained a failure and was recreated with both cats home: bring them out again (once).
            recovering = true
            defer { recovering = false }
            wasOn = nil
            applySettings()
            return
        }
        render(typingSteps: core.taken.filter { $0.kind == UInt32(MP_DESK_OUT_STEP) }.count)
        for e in core.taken { taken[e.kind, default: 0] += 1 }
        if !core.taken.isEmpty { counters.record(events: core.taken) }
        counters.flushIfDue(nowMs: DeskClock.monoMs)
        reschedule()
    }

    /// `typingSteps`: the STEP events of the step just taken (each advances the walker's frame); 0 for a redraw.
    private func render(typingSteps: Int) {
        let now = DeskClock.monoMs
        let ticking = core.hasWake && core.output.wake_at_ms <= now + 100
        let frame = CatPanel.Frame(k: scale, motion: !reduceMotion, ticking: ticking, nowMs: Double(now),
                                   primaryHeight: WorldSensor.primaryHeight, typingSteps: typingSteps)
        for panel in panels {
            if !core.isHidden, panel.layout(core.cat(panel.cat), art: art, frame: frame) {
                panel.show()
            } else {
                if panel.shown { disarm(panel.cat) }
                panel.hide()
            }
        }
        // The input method's glass: which cats are out here (none while they are put away).
        listener?.publish(Self.seats(core))
        // A `turn` after a walk ends on its own time, even when the core has nothing to wake for then.
        let turnEnd = panels.map(\.gait.turnUntil).max() ?? 0
        turnRedraw?.cancel()
        turnRedraw = nil
        if turnEnd > Double(now) {
            let redraw = DispatchWorkItem { [weak self] in self?.render(typingSteps: 0) }
            turnRedraw = redraw
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(turnEnd - Double(now)) + 1), execute: redraw)
        }
    }

    /// Which cats are out on the desk for the input method: not home, not on the glass, not put away.
    static func seats(_ core: DeskCore) -> DeskSeats {
        guard !core.isHidden else { return DeskSeats() }
        func out(_ cat: DeskCat) -> Bool {
            let body = Int(core.cat(cat).body)
            return body != MP_DESK_BODY_HOME && body != MP_DESK_BODY_GLASS
        }
        return DeskSeats(mimimo: out(.mimimo), momo: out(.momo))
    }

    private func reschedule() {
        let out = core.output
        if core.frames || dragging != nil { startDisplayLink() } else { stopDisplayLink() }
        if core.hasWake && !core.frames {
            let now = DeskClock.monoMs
            let delay = out.wake_at_ms > now ? out.wake_at_ms - now : 0
            let leeway = delay < 300 ? 5 : Int(min(1000, delay / 8))
            wakeTimer.schedule(deadline: .now() + .milliseconds(Int(min(delay, 86_400_000))), repeating: .never, leeway: .milliseconds(leeway))
        } else {
            wakeTimer.schedule(deadline: .distantFuture)
        }
        let ms = core.tier == MP_DESK_TIER_AWAKE ? 100 : core.tier == MP_DESK_TIER_DROWSY ? 500 : 0
        if ms != sampleMs {
            sampleMs = ms
            if ms > 0 {
                sampleTimer.schedule(deadline: .now() + .milliseconds(ms), repeating: .milliseconds(ms), leeway: .milliseconds(max(10, ms / 10)))
            } else {
                sampleTimer.schedule(deadline: .distantFuture)
            }
        }
        let away = core.tier == MP_DESK_TIER_OFF && !core.isHidden && core.userState == MP_DESK_USER_AWAY
        if away { installAwayMonitor() } else { removeAwayMonitor() }
    }

    private func startDisplayLink() {
        guard displayLink == nil, let screen = NSScreen.screens.first else { return }
        let link = screen.displayLink(target: self, selector: #selector(frameTick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func frameTick(_ link: CADisplayLink) { step("frame") }

    // MARK: Sensing

    /// Measures the world; true when something the core must hear changed (the world, full screen).
    private func measure() -> Bool {
        let input = WorldSensor.capture(ownPID: getpid(), doorX: doorX(), scale: scale)
        windows = input.windows
        measuredAt = DeskClock.monoMs
        let measured = WorldSensor.build(input)
        lastMeasure = measured
        var changed = core.setWorld(measured.world)
        // Our panels join every Space except full-screen ones: shown but not on the active Space (twice in a row,
        // so a panel just ordered in does not count) means a full-screen Space.
        if panels.contains(where: { $0.shown && $0.panel.isVisible && !$0.panel.isOnActiveSpace }) {
            offSpaceSamples += 1
            if offSpaceSamples >= 2 { hiddenBySpace = true }
        } else {
            offSpaceSamples = 0
        }
        let hidden = measured.fullScreen || hiddenBySpace
        if hidden != reportedHidden {
            reportedHidden = hidden
            core.event(MP_DESK_EV_HIDDEN, arg: hidden ? 1 : 0)
            changed = true
        }
        activity.setFullScreen(hidden)
        return changed
    }

    private func remeasure() {
        if measure() { step() }
    }

    /// One tier tick: the pointer, the idle time, the world, the dwell.
    private func sample() {
        let now = DeskClock.monoMs
        let height = WorldSensor.primaryHeight
        var why: [String] = []
        if measure() { why.append("world") }
        let p = WorldSensor.desktop(NSEvent.mouseLocation, primaryHeight: height)
        let down = NSEvent.pressedMouseButtons & 1 != 0
        if p != lastPointer || down != lastDown {
            lastPointer = p
            lastDown = down
            core.event(MP_DESK_EV_POINTER, arg: down ? 1 : 0, x: Double(p.x), y: Double(p.y))
            why.append("pointer")
            activity.inputSeen()
        } else if let idle = ForegroundWatcher.systemIdleSeconds() {
            // Activity the pointer and the pulses did not show (typing with another input method, say).
            let idleMs = UInt32(clamping: Int((idle * 1000).rounded()))
            if idleMs < UInt32(sampleMs + 50) {
                core.event(MP_DESK_EV_IDLE, arg: idleMs)
                why.append("idle")
                activity.inputSeen()
            }
        }
        if dwell(at: p, down: down, now: now, primaryHeight: height) { why.append("dwell") }
        if !why.isEmpty { step("sample:" + why.joined(separator: "+")) }
    }

    /// The 250 ms rest that makes a cat clickable (PETS.md §2.5). True when it queued an event.
    private func dwell(at p: CGPoint, down: Bool, now: UInt64, primaryHeight: CGFloat) -> Bool {
        var queued = false
        let typing = core.userState == MP_DESK_USER_TYPING
        for panel in panels where dragging != panel.cat {
            let i = panel.cat.rawValue
            if !down && !typing && panel.shown && panel.opaque(atDesktop: p, primaryHeight: primaryHeight) {
                guard let since = dwellSince[i] else { dwellSince[i] = now; continue }
                if !armed[i] && now - since >= Self.dwellMs {
                    armed[i] = true
                    panel.panel.ignoresMouseEvents = false
                    core.event(MP_DESK_EV_NOTICE, panel.cat, x: Double(p.x))
                    queued = true
                }
            } else {
                dwellSince[i] = nil
                if armed[i] { disarm(panel.cat) }
            }
        }
        return queued
    }

    private func disarm(_ cat: DeskCat) {
        armed[cat.rawValue] = false
        dwellSince[cat.rawValue] = nil
        if dragging != cat { panels[cat.rawValue].panel.ignoresMouseEvents = true }
    }

    private func installAwayMonitor() {
        guard awayMonitor == nil else { return }
        awayMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.mouseMoved, .leftMouseDown, .rightMouseDown, .otherMouseDown, .scrollWheel]) { [weak self] _ in
            self?.removeAwayMonitor()
            self?.sample()
        }
    }

    private func removeAwayMonitor() {
        if let awayMonitor { NSEvent.removeMonitor(awayMonitor) }
        awayMonitor = nil
    }

    // MARK: Pulses

    private func receive(_ bytes: [UInt8]) {
        var pulse = MPPulse()
        guard mp_pulse_decode(bytes, bytes.count, &pulse) == MP_PULSE_OK else { return }
        if DeskClock.monoMs - measuredAt > 100 { _ = measure() }
        var owner: UInt32 = 0
        if pulse.flags & UInt32(MP_PULSE_ORIGIN) != 0 {
            // The middle of the origin's grid cell, half a line down: inside the window holding the composition.
            let point = CGPoint(x: Double(pulse.origin_x) * 8 + 4, y: Double(pulse.origin_y) * 8 + max(4, Double(pulse.line) * 4))
            owner = WorldSensor.owner(at: point, in: windows)
        }
        core.pulse(bytes, caretOwner: owner)
        step("pulse")
        activity.inputSeen()
    }

    // MARK: Pointer on a cat (the panel takes clicks only after the dwell)

    func pointerMovedOverPanel(_ cat: DeskCat) {
        guard dragging != cat else { return }
        let p = WorldSensor.desktop(NSEvent.mouseLocation, primaryHeight: WorldSensor.primaryHeight)
        if !panels[cat.rawValue].opaque(atDesktop: p, primaryHeight: WorldSensor.primaryHeight) { disarm(cat) }
    }

    func mouseDown(_ cat: DeskCat, _ event: NSEvent) {
        let p = WorldSensor.desktop(NSEvent.mouseLocation, primaryHeight: WorldSensor.primaryHeight)
        guard panels[cat.rawValue].opaque(atDesktop: p, primaryHeight: WorldSensor.primaryHeight) else { disarm(cat); return }
        if event.clickCount >= 2 {
            core.event(MP_DESK_EV_CARD, cat)
            step()
            return
        }
        dragging = cat
        holding = ProcessInfo.processInfo.beginActivity(options: .userInitiated, reason: "喵撲貓窩: a cat is held")
        core.event(MP_DESK_EV_PRESS, cat, x: Double(p.x), y: Double(p.y))
        step()
    }

    func mouseDragged(_ cat: DeskCat) {
        guard dragging == cat else { return }
        let p = WorldSensor.desktop(NSEvent.mouseLocation, primaryHeight: WorldSensor.primaryHeight)
        core.event(MP_DESK_EV_DRAG, cat, x: Double(p.x), y: Double(p.y))
        step()
    }

    func mouseUp(_ cat: DeskCat) {
        guard dragging == cat else { return }
        dragging = nil
        if let holding { ProcessInfo.processInfo.endActivity(holding) }
        holding = nil
        core.event(MP_DESK_EV_RELEASE, cat)
        step()
        pointerMovedOverPanel(cat)
    }

    func rightMouseDown(_ cat: DeskCat, _ event: NSEvent, in view: NSView) {
        let menu = NSMenu()
        fill(menu)
        NSMenu.popUpContextMenu(menu, with: event, for: view)
        disarm(cat)
    }

    // MARK: Menus (the cat's right-click menu and the cat door in the menu bar)

    private func makeStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        let image = NSImage(named: "MeowpuutCatTemplate") ?? NSImage(systemSymbolName: "pawprint", accessibilityDescription: nil)
        image?.isTemplate = true
        item.button?.image = image
        item.button?.setAccessibilityLabel(InterfaceText.system("喵撲貓窩", "喵扑猫窝"))
        statusMenu.delegate = self
        item.menu = statusMenu
        statusItem = item
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        fill(menu)
    }

    private func fill(_ menu: NSMenu) {
        let t = InterfaceText.system
        let on = settings.preferences.on
        let out = DeskCat.allCases.contains { Int(core.cat($0).body) != MP_DESK_BODY_HOME }
        let home = DeskCat.allCases.contains { Int(core.cat($0).body) == MP_DESK_BODY_HOME }
        let items: [(String, Selector, Bool)] = [
            (t("叫回家", "叫回家"), #selector(callHome), on && out),
            (t("出門", "出门"), #selector(comeOut), on && home),
            (t("安靜模式（一小時）", "安静模式（一小时）"), #selector(toggleQuiet), on),
        ]
        for (title, action, enabled) in items {
            let item = menu.addItem(withTitle: title, action: enabled ? action : nil, keyEquivalent: "")
            item.target = self
        }
        menu.items.last?.state = DeskClock.monoMs < quietUntil ? .on : .off
        // 「時間花在哪裡」: clearing, whenever there is something to clear (recording on, or a file from before).
        if settings.preferences.activityStats
            || FileManager.default.fileExists(atPath: settings.directory.appendingPathComponent(ActivityLedger.fileName).path) {
            menu.addItem(.separator())
            menu.addItem(withTitle: t("清除時間紀錄…", "清除时间记录…"), action: #selector(clearActivity), keyEquivalent: "").target = self
        }
        menu.addItem(.separator())
        menu.addItem(withTitle: t("設定…", "设置…"), action: #selector(openSettings), keyEquivalent: "").target = self
    }

    /// 「時間花在哪裡」's minutes, every day, after asking (it cannot be undone).
    @objc private func clearActivity() {
        let t = InterfaceText.system
        let alert = NSAlert()
        alert.messageText = t("清除時間紀錄？", "清除时间记录？")
        alert.informativeText = t("每一天各類別的分鐘數都會刪除，不能復原。", "每一天各类别的分钟数都会删除，不能恢复。")
        alert.addButton(withTitle: t("清除紀錄", "清除记录"))
        alert.addButton(withTitle: t("取消", "取消"))
        alert.buttons.first?.hasDestructiveAction = true
        NSApp.activate()
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        ForegroundWatcher.clear(directory: settings.directory, live: activity.ledger)
    }

    @objc private func callHome() {
        for cat in DeskCat.allCases { core.event(MP_DESK_EV_OUT, cat, arg: 0) }
        step()
    }

    @objc private func comeOut() {
        for cat in DeskCat.allCases { core.event(MP_DESK_EV_OUT, cat, arg: 1) }
        step()
    }

    /// Quiet for an hour (PETS.md §5): only what you start, the greeting and dozing.
    @objc private func toggleQuiet() {
        quietEnd?.cancel()
        quietEnd = nil
        if DeskClock.monoMs < quietUntil {
            quietUntil = 0
        } else {
            quietUntil = DeskClock.monoMs + 3_600_000
            let end = DispatchWorkItem { [weak self] in self?.applySettings() }
            quietEnd = end
            DispatchQueue.main.asyncAfter(deadline: .now() + 3600, execute: end)
        }
        applySettings()
    }

    /// The settings live in the input method's app for now (the companion app later).
    @objc private func openSettings() {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: DeskPulseChannel.inputMethodIdentifier) else { return }
        NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration())
    }

    private func doorX() -> CGFloat? {
        guard let frame = statusItem?.button?.window?.frame, frame.width > 0 else { return nil }
        return frame.midX
    }
}
