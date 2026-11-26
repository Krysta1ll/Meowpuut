import AppKit
import Carbon.HIToolbox
import Combine
import Security
import os

// The input method -> 喵撲貓窩 (Meowpuut Desk, the desk-pet process) channel: design-preview/pc/FINAL.md §4.6
// item 1 and §7.1. The pulse format is Engine meowpuut.h "Pulse" (crates/meowpuut-pets/src/pulse.rs). This file
// is compiled into both: the input method sends (DeskPulseSender), Desk/Sources/PulseService.swift listens.
//
// - The channel is a LaunchAgent MachServices name. launchd reserves it for 喵撲貓窩's job, so no other process
//   can register it first (a CFMessagePort name can be taken); it is reached with NSXPCConnection. Never Darwin
//   notifications (anyone can listen) and never CFMessagePort.
// - Both ends check the other's code signature (DeskPulseChannel.requirement): the bundle identifier, and once the
//   build is signed with a Developer ID, the same Team ID as this process. The system applies the input method's
//   check to what comes back, not to what goes out, so no pulse leaves before 喵撲貓窩 has answered on the
//   connection: the input method first sends `hello` (no content), 喵撲貓窩 answers with the seats, and only a
//   connection whose answer passed the check carries pulses (the smoke checks an impostor gets none).
// - What leaves the input method: 36 fixed bytes. Running counters (keys, commits, committed graphemes), whether a
//   composition is open, the composition origin on an 8 pt grid (measured when a composition starts or moves to
//   a new line, never per key), the screen, and two bits for the candidate window (expanded, above the line).
//   Never text, never which key, never the exact caret, never a time.
// - The key path counts and encodes into a 36-byte slot allocated once, then signals a dispatch source; it does
//   not wait and does not allocate. Connecting and sending happen on a private serial queue. Pulses that pile up
//   before the queue runs are coalesced into the newest: the counters are running totals.
// - Nothing is sent while secure event input is on (a password field has the keyboard; macOS also switches to an
//   ASCII input source there, so this input method is not even active), while 桌面貓 is off, or under --smoke.
// - Without 喵撲貓窩 (not installed, login item not registered) the connection is invalidated and pulses are
//   dropped; a new connection is tried at most every `retrySeconds`.
// - The other direction, on the same connection (the input method exports DeskSeatReceiving on it): 4 fixed bytes
//   saying which cats are out on the desk (DeskSeats), so that in 乙 the candidate glass leaves out Mimimo while
//   she walks the top edge of your window (FINAL.md §4.2). Nothing else comes back: not where a cat is, not what
//   she does. 喵撲貓窩 sends it when the connection opens and when the seats change; the input method takes it only
//   over this connection, whose peer passed the signature check above. Without 喵撲貓窩 (or once it goes away) the
//   glass draws both cats, as it always did.

/// The input method's messages: `hello` opens the connection (nothing in it; 喵撲貓窩 answers with the seats), then
/// pulses, each exactly MP_PULSE_BYTES as mp_pulse_encode wrote them. No replies.
@objc(MPDeskPulseReceiving) protocol DeskPulseReceiving {
    func hello()
    func pulse(_ bytes: Data)
}

/// 喵撲貓窩 -> input method: a DeskSeats message, exactly DeskSeats.byteCount bytes. No reply.
@objc(MPDeskSeatReceiving) protocol DeskSeatReceiving {
    func seats(_ bytes: Data)
}

/// Which cats are out on the desk (drawn by 喵撲貓窩, not on the candidate glass): magic 9C B2, version 1, then one
/// byte with bit 0 = Mimimo and bit 1 = Momo. Any other length, magic, version or bit is not a message.
struct DeskSeats: Equatable {
    static let byteCount = 4
    static let magic: [UInt8] = [0x9C, 0xB2]
    static let version: UInt8 = 1
    static let mimimoBit: UInt8 = 1
    static let momoBit: UInt8 = 2

    private(set) var bits: UInt8 = 0

    init(mimimo: Bool = false, momo: Bool = false) {
        bits = (mimimo ? Self.mimimoBit : 0) | (momo ? Self.momoBit : 0)
    }

    init?(_ data: Data) {
        let b = [UInt8](data)
        guard b.count == Self.byteCount, b[0] == Self.magic[0], b[1] == Self.magic[1], b[2] == Self.version,
              b[3] & ~(Self.mimimoBit | Self.momoBit) == 0 else { return nil }
        bits = b[3]
    }

    var mimimoOnDesk: Bool { bits & Self.mimimoBit != 0 }
    var momoOnDesk: Bool { bits & Self.momoBit != 0 }
    var data: Data { Data(Self.magic + [Self.version, bits]) }
}

/// The input method's side (main thread): what 喵撲貓窩 last said on the open connection; nil without one.
final class GlassSeats: ObservableObject {
    static let shared = GlassSeats()

    @Published private(set) var desk: DeskSeats?

    func update(_ seats: DeskSeats?) {
        if seats != desk { desk = seats }
    }

    /// FINAL.md §4.2: whether the candidate glass draws Mimimo. Momo is always drawn there while you type (in both
    /// seats the first key brings her onto the glass, drawn here in that same frame, never waiting for 喵撲貓窩).
    /// Mimimo is left off only in 乙 (`typingSeat` split, the default) while 喵撲貓窩 says she is out on the desk;
    /// when she cannot reach your window she joins the glass and the next message says so. In 甲 (`glass`) both
    /// sit on the glass from the first key, so the message is not waited for.
    static func showsMimimo(_ seats: DeskSeats?, typingSeat: String?) -> Bool {
        guard let seats, typingSeat != "glass" else { return true }
        return !seats.mimimoOnDesk
    }
}

/// The object the input method exports on its connection.
final class DeskSeatInbox: NSObject, DeskSeatReceiving {
    private let deliver: (DeskSeats) -> Void

    init(deliver: @escaping (DeskSeats) -> Void) { self.deliver = deliver }

    /// On the connection's queue: anything that is not one well-formed message is dropped.
    func seats(_ bytes: Data) {
        guard let seats = DeskSeats(bytes) else { return }
        deliver(seats)
    }
}

enum DeskPulseChannel {
    /// The MachServices name in 喵撲貓窩's LaunchAgent (Desk/LaunchAgents/app.meowpuut.desk.plist).
    static let machService = "app.meowpuut.desk.pulse"
    static let deskIdentifier = "app.meowpuut.desk"
    static let inputMethodIdentifier = "app.meowpuut.inputmethod.Meowpuut"

    /// The code requirement a peer must satisfy: its signing identifier, and with `team` (this process's own Team
    /// ID, so a Developer ID build accepts only the same team) an Apple-issued certificate of that team.
    static func requirement(identifier: String, team: String?) -> String {
        let base = "identifier \"\(identifier)\""
        guard let team, !team.isEmpty, team.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return base }
        return base + " and anchor apple generic and certificate leaf[subject.OU] = \"\(team)\""
    }

    /// The Team ID this process is signed with; nil for an ad-hoc or unsigned (local) build.
    static let ownTeam: String? = {
        var code: SecCode?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code else { return nil }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else { return nil }
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(staticCode, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let fields = info as? [String: Any] else { return nil }
        return fields[kSecCodeInfoTeamIdentifier as String] as? String
    }()
}

final class DeskPulseSender {
    /// The input method's sender. Off until the settings say 桌面貓 is on (and always off under --smoke). What
    /// 喵撲貓窩 answers goes to GlassSeats (dropped while off).
    static let shared: DeskPulseSender = DeskPulseSender(seats: { seats in
        DispatchQueue.main.async { GlassSeats.shared.update(DeskPulseSender.shared.enabled ? seats : nil) }
    })
    static let retrySeconds: UInt64 = 5

    /// Main thread. False: publish does nothing.
    var enabled = false

    // Key-path state, main thread only.
    private var pulse = MPPulse()
    private var origin = MPPulseOrigin()
    private var hasOrigin = false

    // The hand-off slot: written on the main thread, read on `queue`, under `lock`. Both allocated once.
    private let slot: UnsafeMutablePointer<UInt8>
    private let lock: UnsafeMutablePointer<os_unfair_lock>
    private let queue = DispatchQueue(label: "app.meowpuut.desk-pulse", qos: .utility)
    private let signal: DispatchSourceUserDataOr
    private let sink: ((Data) -> Void)?
    private let secureInput: () -> Bool
    private let endpoint: NSXPCListenerEndpoint?
    private let peer: String
    private let inbox: DeskSeatInbox
    private let seats: (DeskSeats?) -> Void

    // Queue state.
    private var connection: NSXPCConnection?
    private var retryAt: UInt64 = 0
    /// `hello` went out on this connection (again after an interruption).
    private var greeted = false
    /// 喵撲貓窩 answered on this connection, through the signature check: pulses may go out.
    private var verified = false

    /// `sink` replaces the connection (checks); `secureInput` says whether secure event input is on. `endpoint` and
    /// `peer` replace the Mach service and 喵撲貓窩's signing identifier (the in-process round trip of the checks).
    /// `seats` gets each message 喵撲貓窩 sends back, and nil when the connection goes away (on the queue).
    init(sink: ((Data) -> Void)? = nil, secureInput: @escaping () -> Bool = { IsSecureEventInputEnabled() },
         endpoint: NSXPCListenerEndpoint? = nil, peer: String = DeskPulseChannel.deskIdentifier,
         seats: @escaping (DeskSeats?) -> Void = { _ in }) {
        self.sink = sink
        self.secureInput = secureInput
        self.endpoint = endpoint
        self.peer = peer
        self.seats = seats
        let queue = queue
        var heard: ((DeskSeats) -> Void)?
        inbox = DeskSeatInbox { heard?($0) }
        slot = .allocate(capacity: Int(MP_PULSE_BYTES))
        slot.initialize(repeating: 0, count: Int(MP_PULSE_BYTES))
        lock = .allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
        signal = DispatchSource.makeUserDataOrSource(queue: queue)
        signal.setEventHandler { [weak self] in self?.drain() }
        signal.activate()
        // An answer that passed the check: the connection is 喵撲貓窩's; the newest pulse goes out now.
        heard = { [weak self] message in
            seats(message)
            queue.async {
                guard let self, !self.verified, self.connection != nil else { return }
                self.verified = true
                self.drain()
            }
        }
    }

    deinit {
        signal.cancel()
        slot.deallocate()
        lock.deallocate()
    }

    // MARK: Key path (main thread)

    /// One key went down (any key; never which).
    func key() { pulse.keys &+= 1 }

    /// A commit of `graphemes` characters (the count PetLedger already made; the text never comes here).
    func committed(graphemes: Int) {
        pulse.commits &+= 1
        pulse.graphemes &+= UInt32(clamping: graphemes)
    }

    /// The input method measured where the composition starts (Cocoa screen coordinates). Called only when that
    /// measurement is made (a composition begins or moves to a new line), not per key. Nil keeps the last origin.
    func measured(_ start: NSRect?) {
        guard let start else { return }
        // Desktop points, y down from the top of the main display: the space CGWindowList reports in.
        let height = CGDisplayBounds(CGMainDisplayID()).height
        var measured = MPPulseOrigin()
        guard mp_pulse_origin_measure(Double(start.minX), Double(height - start.maxY), Double(start.height), &measured) == 1 else { return }
        origin = measured
        hasOrigin = true
        var display: CGDirectDisplayID = 0
        var count: UInt32 = 0
        if CGGetDisplaysWithPoint(CGPoint(x: start.midX, y: height - start.midY), 1, &display, &count) == .success, count > 0 {
            pulse.display = display
        }
    }

    /// Encodes the current state into the slot and wakes the queue. No waiting, no allocation.
    func publish(composing: Bool, expanded: Bool, above: Bool) {
        guard enabled else { return }
        pulse.seq &+= 1
        var flags: UInt32 = 0
        if composing {
            flags |= UInt32(MP_PULSE_COMPOSING)
            if expanded { flags |= UInt32(MP_PULSE_EXPANDED) }
            if above { flags |= UInt32(MP_PULSE_ABOVE) }
        }
        if hasOrigin {
            flags |= UInt32(MP_PULSE_ORIGIN)
            pulse.origin_x = origin.x
            pulse.origin_y = origin.y
            pulse.line = origin.line
        }
        pulse.flags = flags
        os_unfair_lock_lock(lock)
        _ = mp_pulse_encode(&pulse, slot, Int(MP_PULSE_BYTES))
        os_unfair_lock_unlock(lock)
        signal.or(data: 1)
    }

    // MARK: Sending (queue)

    private func drain() {
        var bytes = Data(count: Int(MP_PULSE_BYTES))
        os_unfair_lock_lock(lock)
        bytes.withUnsafeMutableBytes { $0.copyMemory(from: UnsafeRawBufferPointer(start: slot, count: Int(MP_PULSE_BYTES))) }
        os_unfair_lock_unlock(lock)
        if secureInput() { return }
        if let sink { sink(bytes); return }
        guard let remote = remote() else { return }
        if !greeted {
            greeted = true
            remote.hello()
        }
        // Not answered yet: dropped (the counters are running totals; the answer sends the newest).
        if verified { remote.pulse(bytes) }
    }

    private func remote() -> DeskPulseReceiving? {
        if connection == nil {
            let now = DispatchTime.now().uptimeNanoseconds
            guard now >= retryAt else { return nil }
            retryAt = now + Self.retrySeconds * 1_000_000_000
            let connection = endpoint.map { NSXPCConnection(listenerEndpoint: $0) }
                ?? NSXPCConnection(machServiceName: DeskPulseChannel.machService, options: [])
            connection.remoteObjectInterface = NSXPCInterface(with: DeskPulseReceiving.self)
            connection.exportedInterface = NSXPCInterface(with: DeskSeatReceiving.self)
            connection.exportedObject = inbox
            connection.setCodeSigningRequirement(DeskPulseChannel.requirement(identifier: peer, team: DeskPulseChannel.ownTeam))
            // 喵撲貓窩 quit or crashed: both cats are on the glass again, and it must answer again before pulses.
            connection.interruptionHandler = { [weak self, weak connection, seats] in
                seats(nil)
                self?.queue.async {
                    guard let self, self.connection === connection else { return }
                    self.greeted = false
                    self.verified = false
                }
            }
            // Not running, not registered or not signed as expected: forget it and try again later.
            connection.invalidationHandler = { [weak self, weak connection, seats] in
                seats(nil)
                self?.queue.async {
                    guard let self, self.connection === connection else { return }
                    self.connection = nil
                    self.greeted = false
                    self.verified = false
                }
            }
            connection.resume()
            self.connection = connection
        }
        return connection?.remoteObjectProxyWithErrorHandler { _ in } as? DeskPulseReceiving
    }
}
