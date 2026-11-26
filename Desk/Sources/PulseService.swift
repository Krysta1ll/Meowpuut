import Foundation
import ServiceManagement

// The listening end of the input method's pulses (Sources/DeskPulse.swift has the channel's rules).
//
// 喵撲貓窩 is a LaunchAgent (Contents/Library/LaunchAgents/app.meowpuut.desk.plist) registered with
// SMAppService.agent(plistName:) — only when started with --register, never by the build or the checks. launchd
// starts it at login and reserves the MachServices name app.meowpuut.desk.pulse for it; only the copy that
// launchd started (XPC_SERVICE_NAME is the job's label) checks the name in. Every connection must come from code
// signed as the input method (identifier app.meowpuut.inputmethod.Meowpuut, and once this build carries a Developer
// ID Team ID, that same team); others are refused by the system before any message arrives. Over the same connection
// 喵撲貓窩 tells the input method which cats are out on the desk (DeskSeats, 4 bytes; Sources/DeskPulse.swift).
//
// The same job also checks in app.meowpuut.desk.activity (ActivityService.swift): the companion app's read of the
// current activity category, for its Steam status line.

enum DeskAgent {
    static let label = "app.meowpuut.desk"
    static let plistName = "app.meowpuut.desk.plist"

    /// This process was started by launchd for the agent (not from Finder, a terminal or the checks).
    static var launchedByLaunchd: Bool { ProcessInfo.processInfo.environment["XPC_SERVICE_NAME"] == label }

    /// The login item is registered and allowed (launchd runs, or will run, its own copy).
    static var registered: Bool { SMAppService.agent(plistName: plistName).status == .enabled }

    /// `--register` / `--unregister`: the login item and its Mach service. Returns the exit status.
    static func run(register: Bool) -> Int32 {
        let service = SMAppService.agent(plistName: plistName)
        do {
            if register { try service.register() } else { try service.unregister() }
        } catch {
            FileHandle.standardError.write(Data("喵撲貓窩：\(register ? "註冊" : "取消註冊")失敗：\(error.localizedDescription)\n".utf8))
            return 1
        }
        let status: String
        switch service.status {
        case .enabled: status = "enabled"
        case .requiresApproval: status = "requires approval (System Settings > General > Login Items)"
        case .notRegistered: status = "not registered"
        case .notFound: status = "not found"
        @unknown default: status = "unknown"
        }
        print("喵撲貓窩 \(label): \(status)")
        return 0
    }
}

final class PulseListener: NSObject, NSXPCListenerDelegate, DeskPulseReceiving {
    private let listener: NSXPCListener?
    private let deliver: ([UInt8]) -> Void
    /// The input method's open connections (main thread): they hear the seats.
    private var peers: [NSXPCConnection] = []
    /// What the input method was last told (main thread).
    private(set) var seats = DeskSeats()

    /// `machService` nil: no listener (a copy launchd did not start, the checks); `pulse(_:)` still works.
    convenience init(machService: String?, deliver: @escaping ([UInt8]) -> Void) {
        self.init(listener: machService.map { NSXPCListener(machServiceName: $0) }, peer: DeskPulseChannel.inputMethodIdentifier,
                  deliver: deliver)
    }

    /// `peer`: the signing identifier every connection must have (the input method; the checks' in-process round trip
    /// uses an anonymous listener and this app's own identifier).
    init(listener: NSXPCListener?, peer: String, deliver: @escaping ([UInt8]) -> Void) {
        self.deliver = deliver
        self.listener = listener
        super.init()
        if let listener {
            listener.setConnectionCodeSigningRequirement(DeskPulseChannel.requirement(identifier: peer, team: DeskPulseChannel.ownTeam))
            listener.delegate = self
        }
    }

    func start() { listener?.resume() }

    /// The checks: how to reach an anonymous listener.
    var endpointForChecks: NSXPCListenerEndpoint? { listener?.endpoint }

    func listener(_ listener: NSXPCListener, shouldAcceptNewConnection connection: NSXPCConnection) -> Bool {
        connection.exportedInterface = NSXPCInterface(with: DeskPulseReceiving.self)
        connection.exportedObject = self
        connection.remoteObjectInterface = NSXPCInterface(with: DeskSeatReceiving.self)
        let id = ObjectIdentifier(connection)
        connection.invalidationHandler = { [weak self] in
            DispatchQueue.main.async { self?.peers.removeAll { ObjectIdentifier($0) == id } }
        }
        connection.resume()
        // A new connection hears the seats at once (the first key's glass needs them).
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.peers.append(connection)
            Self.tell(connection, self.seats)
        }
        return true
    }

    /// Main thread, after every step: the seats go to the input method when they changed.
    func publish(_ seats: DeskSeats) {
        guard seats != self.seats else { return }
        self.seats = seats
        for peer in peers { Self.tell(peer, seats) }
    }

    private static func tell(_ connection: NSXPCConnection, _ seats: DeskSeats) {
        (connection.remoteObjectProxyWithErrorHandler { _ in } as? DeskSeatReceiving)?.seats(seats.data)
    }

    /// The connection opened (accepting it already sent the seats); nothing in it.
    func hello() {}

    /// On the connection's queue: anything that is not one well-formed pulse is dropped here.
    func pulse(_ bytes: Data) {
        guard bytes.count == Int(MP_PULSE_BYTES) else { return }
        var decoded = MPPulse()
        let status = bytes.withUnsafeBytes { mp_pulse_decode($0.bindMemory(to: UInt8.self).baseAddress, $0.count, &decoded) }
        guard status == MP_PULSE_OK else { return }
        let raw = [UInt8](bytes)
        DispatchQueue.main.async { [deliver] in deliver(raw) }
    }
}
