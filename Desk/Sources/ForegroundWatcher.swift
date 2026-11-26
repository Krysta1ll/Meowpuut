import AppKit
import CoreGraphics

// 「時間花在哪裡」(FINAL.md §11.2), the desk's half: which app is in front and whether you are at the computer, fed to
// the activity tracker (ActivityLedger.swift). Off by default (`activityStats`, docs/SETTINGS.md): while it is off
// nothing here observes, reads or writes anything, and Activity.bin is not touched.
//
// What it reads, and nothing else:
// - NSWorkspace.didActivateApplicationNotification: the app that came to the front, as its bundle id (an app without
//   one: its executable's name) and its localized name. Both stay in memory: the bundle id goes to the tracker at
//   each step, which classifies it into one of ten categories and drops it; the name is kept for the companion app's
//   status line (ActivityService.swift) and never written.
// - The system idle time: CGEventSourceSecondsSinceLastEventType(.combinedSessionState, kCGAnyInputEventType), read
//   at each step (never per event, never which event). Observed on this Mac (macOS 27.0.1, 2026-11-23): it needs no
//   permission (no prompt, Input Monitoring stays off) and sees keyboard and pointer input. kCGEventNull (.null) is
//   not "any input": it answers with the time since boot. Should it ever give nothing usable, the desk's own
//   sightings (pointer samples, pulses, a foreground change) stand in for it.
// - Full screen: the desk's own measurement (WorldSensor; the same one that puts the cats away).
// Never a window title, a URL, a document, Screen Recording, Accessibility or Input Monitoring.
//
// When it steps: on every foreground change; on every change of the switches (on/off, paused, hidden apps, full
// screen); after the tracker's wake_after_ms (about a minute) while it counts, on a timer with leeway; and, after
// the tracker asked for nothing because you were away, when input is seen again: the desk's pointer samples and
// pulses, and a one-shot global mouse monitor (mouse events need no permission). Away, it keeps no timer.

final class ForegroundWatcher {
    /// The settings that drive it (DeskPreferences).
    struct Switches: Equatable {
        /// `activityStats`.
        var recording = false
        /// `activityPaused`.
        var paused = false
        /// `activityHidden`: bundle ids never recorded.
        var hidden: [String] = []
    }

    /// Where it gets the world from; the checks replace these.
    struct Sources {
        var clock: () -> MPPetsClock = { DeskClock.now() }
        var idleSeconds: () -> Double? = ForegroundWatcher.systemIdleSeconds
        var frontmost: () -> NSRunningApplication? = { NSWorkspace.shared.frontmostApplication }
        /// NSWorkspace notifications, the tracker's timer and the mouse monitor (off in the checks).
        var observe = true
    }

    /// Input seen again wakes the tracker at most this often.
    static let inputStepMs: UInt64 = 1000

    let directory: URL
    private let sources: Sources
    private(set) var switches = Switches()
    private(set) var fullScreen = false
    /// The tracker's last answer (the checks, the logs).
    private(set) var lastOut: MPActivityStepOut?
    private(set) var stepCount = 0

    /// The ledger exists only while recording is on. `ledgerLock` guards the reference (ActivityService reads it).
    private var ledgerRef: ActivityLedger?
    private let ledgerLock = NSLock()
    var ledger: ActivityLedger? { ledgerLock.lock(); defer { ledgerLock.unlock() }; return ledgerRef }

    // Memory only, never written.
    private var appID: String?
    private var appName: String?

    private var lastInputMs: UInt64 = 0
    private var lastInputStepMs: UInt64 = 0
    /// The tracker asked for nothing because you are away: the next input steps it.
    private var waiting = false
    private var timer: DispatchSourceTimer?
    private var observers: [NSObjectProtocol] = []
    private var mouseMonitor: Any?

    init(directory: URL, sources: Sources = Sources()) {
        self.directory = directory
        self.sources = sources
    }

    deinit { end(flush: false) }

    // MARK: Switches

    func apply(_ next: Switches) {
        guard next != switches else { return }
        let was = switches
        switches = next
        if next.recording && !was.recording { begin() }
        if next.recording && next.hidden != was.hidden { ledger?.setHidden(next.hidden) }
        if next.recording || was.recording { step() }
        if was.recording && !next.recording { end(flush: true) }
    }

    /// The desk's full-screen measurement changed.
    func setFullScreen(_ on: Bool) {
        guard on != fullScreen else { return }
        fullScreen = on
        if switches.recording { step() }
    }

    /// The desk saw input (a pointer sample that moved, a pulse, the "you're back" monitor). Cheap: it steps the
    /// tracker only when it was waiting for you to come back.
    func inputSeen() {
        let now = sources.clock().mono_ms
        lastInputMs = now
        guard waiting, ledger != nil, now >= lastInputStepMs + Self.inputStepMs else { return }
        lastInputStepMs = now
        step()
    }

    /// A new foreground app (nil: none). This app's own windows (an alert) do not count as an app. Ignored while
    /// recording is off (nothing calls it then: there is no observer).
    func foreground(_ app: NSRunningApplication?) {
        guard switches.recording, app?.processIdentifier != getpid() else { return }
        appID = app.flatMap { $0.bundleIdentifier ?? $0.executableURL?.lastPathComponent }
        appName = app?.localizedName
        lastInputMs = sources.clock().mono_ms
        if switches.recording { step() }
    }

    /// A foreground app by its bundle id and name (nil: none; another user's session in front, the checks).
    func foreground(id: String?, name: String?) {
        guard switches.recording else { return }
        appID = id
        appName = name
        lastInputMs = sources.clock().mono_ms
        if switches.recording { step() }
    }

    func stop() {
        if switches.recording { step() }
        end(flush: true)
    }

    // MARK: What the companion app may read

    /// The status line's input (any thread): the category while you are active in a recorded app, and the app's
    /// display name only when `includeApp` (the companion's 「顯示 App 名稱」) and the tracker allows it (active, not a
    /// hidden app). Recording off: OFF and nothing else.
    func current(includeApp: Bool) -> MPActivityCurrent {
        var out = MPActivityCurrent()
        out.state = UInt32(MP_ACTIVITY_NOW_OFF)
        out.category = UInt32(MP_ACTIVITY_NO_CATEGORY)
        guard let ledger else { return out }
        out = ledger.current()
        if !includeApp { withUnsafeMutableBytes(of: &out.app) { raw in for i in raw.indices { raw[i] = 0 } } }
        return out
    }

    // MARK: Stepping

    @discardableResult
    func step() -> MPActivityStepOut? {
        guard let ledger else { return nil }
        var flags: UInt32 = 0
        if switches.recording { flags |= UInt32(MP_ACTIVITY_RECORDING) }
        if switches.paused { flags |= UInt32(MP_ACTIVITY_PAUSED) }
        if fullScreen { flags |= UInt32(MP_ACTIVITY_FULLSCREEN) }
        let clock = sources.clock()
        let out = ledger.step(clock: clock, appID: switches.recording ? appID : nil, appName: switches.recording ? appName : nil,
                              idleSeconds: idleSeconds(now: clock.mono_ms), flags: flags)
        stepCount += 1
        lastOut = out
        schedule(out)
        return out
    }

    /// Seconds since the last keyboard or pointer input: the system's, or else the desk's own sightings.
    private func idleSeconds(now: UInt64) -> UInt32 {
        if let seconds = sources.idleSeconds(), seconds.isFinite, seconds >= 0 {
            return UInt32(min(seconds.rounded(.down), Double(UInt32.max)))
        }
        return UInt32(clamping: (now >= lastInputMs ? now - lastInputMs : 0) / 1000)
    }

    /// kCGAnyInputEventType (~0) in the combined session state; nil when the answer is not a time.
    static func systemIdleSeconds() -> Double? {
        guard let any = CGEventType(rawValue: ~0) else { return nil }
        let seconds = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: any)
        return seconds.isFinite && seconds >= 0 ? seconds : nil
    }

    private func schedule(_ out: MPActivityStepOut?) {
        let wake = out?.wake_after_ms ?? 0
        // Away from a recorded app: no timer, wait for input. Paused, a hidden app, no app: a switch or a foreground
        // change is the next thing that matters.
        waiting = switches.recording && wake == 0 && out?.state == UInt32(MP_ACTIVITY_NOW_IDLE) && appID != nil
        guard sources.observe else { return }
        if wake > 0 {
            if timer == nil {
                let t = DispatchSource.makeTimerSource(queue: .main)
                t.setEventHandler { [weak self] in self?.step() }
                t.activate()
                timer = t
            }
            timer?.schedule(deadline: .now() + .milliseconds(Int(wake)), repeating: .never, leeway: .seconds(5))
        } else {
            timer?.schedule(deadline: .distantFuture)
        }
        if waiting { installMouseMonitor() } else { removeMouseMonitor() }
    }

    // MARK: Observing (only while recording)

    private func begin() {
        let ledger = ActivityLedger(directory: directory)
        ledger.setHidden(switches.hidden)
        ledgerLock.lock(); ledgerRef = ledger; ledgerLock.unlock()
        lastInputMs = sources.clock().mono_ms
        let app = sources.frontmost()
        if app?.processIdentifier != getpid() {
            appID = app.flatMap { $0.bundleIdentifier ?? $0.executableURL?.lastPathComponent }
            appName = app?.localizedName
        }
        guard sources.observe else { return }
        let center = NSWorkspace.shared.notificationCenter
        observers.append(center.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] note in
            self?.foreground(note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication)
        })
        // Another user's session in front: no app of yours; back: the frontmost again.
        observers.append(center.addObserver(forName: NSWorkspace.sessionDidResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.foreground(id: nil, name: nil)
        })
        observers.append(center.addObserver(forName: NSWorkspace.sessionDidBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.foreground(self?.sources.frontmost())
        })
        // Before sleep: count up to now and write.
        observers.append(center.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            self?.step()
            self?.ledger?.flush(wait: false)
        })
    }

    private func end(flush: Bool) {
        for observer in observers { NSWorkspace.shared.notificationCenter.removeObserver(observer) }
        observers = []
        timer?.cancel()
        timer = nil
        removeMouseMonitor()
        waiting = false
        if flush { ledger?.flush(wait: true) }
        ledgerLock.lock(); ledgerRef = nil; ledgerLock.unlock()
        appID = nil
        appName = nil
    }

    private func installMouseMonitor() {
        guard mouseMonitor == nil else { return }
        mouseMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.mouseMoved, .leftMouseDown, .rightMouseDown, .otherMouseDown, .scrollWheel]) { [weak self] _ in
            self?.removeMouseMonitor()
            self?.inputSeen()
        }
    }

    private func removeMouseMonitor() {
        if let mouseMonitor { NSEvent.removeMonitor(mouseMonitor) }
        mouseMonitor = nil
    }

    // MARK: Clearing

    /// 「清除時間紀錄」: every day of Activity.bin, whether or not recording is on (the file may be there from before).
    static func clear(directory: URL, live: ActivityLedger?) {
        if let live {
            live.clear()
        } else if FileManager.default.fileExists(atPath: directory.appendingPathComponent(ActivityLedger.fileName).path) {
            ActivityLedger(directory: directory).clear()
        }
    }
}
