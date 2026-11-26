import Foundation
import XPC

// app.meowpuut.desk.activity: the companion app's read of "what am I doing now" for its Steam status line (FINAL.md
// §11.2; Engine meowpuut.h mp_activity_presence). A second MachServices name of 喵撲貓窩's LaunchAgent, reserved by
// launchd for this job; only the copy launchd started checks it in.
//
// Why XPC and not a file: the display name must never be written anywhere (FINAL §11.3), and a file in the user's
// folder is readable by every process of that user. Here the system checks every request's code signature before it
// arrives (xpc_connection_set_peer_code_signing_requirement on the listener): signing identifier app.meowpuut.companion
// and, once the build is signed with a Developer ID, this process's own Team ID. Others' requests are dropped. The
// companion should require the desk's signature the same way (identifier app.meowpuut.desk).
//
// Plain libxpc dictionaries, so the companion (Tauri, Rust) can call it with the C API:
//   request  {"v": uint64 1, "app": bool}            "app": the companion's 「顯示 App 名稱」 is on
//   reply    {"v": uint64 1, "current": data[272]}   an MPActivityCurrent as mp_activity_current wrote it: state,
//                                                    category, flags (full screen) and the app's display name, which is
//                                                    "" unless "app" was true and you are active in a recorded app.
// With 「時間花在哪裡」 off the reply is state OFF, category 255, no name. Nothing else can be asked: no history, no
// minutes, no bundle id. No reply is sent to anything that is not a version-1 request.

final class ActivityService {
    static let machService = "app.meowpuut.desk.activity"
    static let companionIdentifier = "app.meowpuut.companion"
    static let version: UInt64 = 1

    private let queue = DispatchQueue(label: "app.meowpuut.desk.activity-service", qos: .utility)
    private let listener: xpc_connection_t?
    private let current: (Bool) -> MPActivityCurrent
    /// The requirement compiled and was set (false: the listener is not started).
    private(set) var guarded = false

    /// `machService` nil: no listener (a copy launchd did not start).
    init(machService: String?, peer: String = ActivityService.companionIdentifier, current: @escaping (Bool) -> MPActivityCurrent) {
        self.current = current
        let queue = queue
        listener = machService.map { xpc_connection_create_mach_service($0, queue, UInt64(XPC_CONNECTION_MACH_SERVICE_LISTENER)) }
        configure(peer: peer)
    }

    /// The checks: an anonymous listener in this process, reached through `endpoint`.
    init(anonymousPeer peer: String, current: @escaping (Bool) -> MPActivityCurrent) {
        self.current = current
        listener = xpc_connection_create(nil, queue)
        configure(peer: peer)
    }

    var endpoint: xpc_endpoint_t? { listener.map { xpc_endpoint_create($0) } }

    private func configure(peer: String) {
        guard let listener else { return }
        let requirement = DeskPulseChannel.requirement(identifier: peer, team: DeskPulseChannel.ownTeam)
        guarded = xpc_connection_set_peer_code_signing_requirement(listener, requirement) == 0
        xpc_connection_set_event_handler(listener) { [weak self] event in
            guard xpc_get_type(event) == XPC_TYPE_CONNECTION else { return }
            self?.accept(event)
        }
    }

    /// Never without the signature check.
    func start() {
        guard let listener, guarded else { return }
        xpc_connection_resume(listener)
    }

    func stop() {
        if let listener { xpc_connection_cancel(listener) }
    }

    private func accept(_ peer: xpc_connection_t) {
        xpc_connection_set_target_queue(peer, queue)
        xpc_connection_set_event_handler(peer) { [weak self] message in
            guard let self, xpc_get_type(message) == XPC_TYPE_DICTIONARY,
                  let reply = Self.reply(to: message, current: self.current) else { return }
            xpc_connection_send_message(peer, reply)
        }
        xpc_connection_resume(peer)
    }

    /// The answer to one request, or nil for anything that is not a version-1 request expecting a reply.
    static func reply(to message: xpc_object_t, current: (Bool) -> MPActivityCurrent) -> xpc_object_t? {
        guard xpc_get_type(message) == XPC_TYPE_DICTIONARY, xpc_dictionary_get_uint64(message, "v") == version,
              let reply = xpc_dictionary_create_reply(message) else { return nil }
        var now = current(xpc_dictionary_get_bool(message, "app"))
        xpc_dictionary_set_uint64(reply, "v", version)
        withUnsafeBytes(of: &now) { raw in
            if let base = raw.baseAddress { xpc_dictionary_set_data(reply, "current", base, raw.count) }
        }
        return reply
    }

    /// Reads a reply's MPActivityCurrent (the checks; the companion does the same in Rust).
    static func current(from reply: xpc_object_t) -> MPActivityCurrent? {
        guard xpc_get_type(reply) == XPC_TYPE_DICTIONARY, xpc_dictionary_get_uint64(reply, "v") == version else { return nil }
        var length = 0
        guard let bytes = xpc_dictionary_get_data(reply, "current", &length), length == MemoryLayout<MPActivityCurrent>.size else { return nil }
        var out = MPActivityCurrent()
        withUnsafeMutableBytes(of: &out) { $0.copyMemory(from: UnsafeRawBufferPointer(start: bytes, count: length)) }
        return out
    }
}
