import Foundation

// 「時間花在哪裡」's ledger (FINAL.md §11.3; Engine meowpuut.h "Activity", crates/meowpuut-activity): for each of the
// last 400 local days, the active minutes of ten app categories and the parts of the day they fell in. Nothing in
// the file is text: no app names, bundle ids, window titles, addresses, songs or times (ledger.rs has the layout).
// The tracker inside the handle classifies the foreground app's bundle id and drops it; the display name stays in
// memory for the companion app's status line and is never written.
//
// File: Activity.bin next to PetJournal.bin (~/Library/Application Support/Meowpuut), lock Activity.lock. Same
// recipe as Sources/PetLedger.swift: nothing per event; the first unflushed minute arms one timer (`batchSeconds`),
// and the desk also flushes when recording stops, before sleep and when it quits. A flush is: lock, read the file,
// add this process's delta, merge in the live state, write a temporary file, rename it over the file, merge the
// result back, unlock. Clearing raises the epoch, so an older copy cannot bring the minutes back.

final class ActivityLedger {
    static let fileName = "Activity.bin"
    static let lockName = "Activity.lock"
    /// The longest a counted minute waits before it is written.
    static let batchSeconds = 300

    let directory: URL
    private let handle: OpaquePointer
    /// Guards `handle`, `dirty` and `pending`.
    private let lock = NSLock()
    private var dirty = false
    private var pending: DispatchWorkItem?
    private let queue = DispatchQueue(label: "app.meowpuut.desk.activity-ledger", qos: .utility)

    var fileURL: URL { directory.appendingPathComponent(Self.fileName) }

    init(directory: URL) {
        self.directory = directory
        let data = (try? Data(contentsOf: directory.appendingPathComponent(Self.fileName))) ?? Data()
        var status: Int32 = 0
        handle = data.withUnsafeBytes { raw in
            mp_activity_from_bytes(raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &status)
        }
    }

    deinit { mp_activity_free(handle) }

    // MARK: The tracker

    /// One tracker step (ForegroundWatcher). `appID` is classified inside and dropped; `appName` is kept in memory for
    /// `current`. Minutes it adds arm the batch timer. Nil on an error (nothing changed then).
    func step(clock: MPPetsClock, appID: String?, appName: String?, idleSeconds: UInt32, flags: UInt32) -> MPActivityStepOut? {
        var out = MPActivityStepOut()
        let status: Int32 = Self.withOptionalCString(appID) { id in
            Self.withOptionalCString(appName) { name in
                var input = MPActivityInput(struct_size: MemoryLayout<MPActivityInput>.size, clock: clock, app_id: id, app_name: name,
                                            idle_seconds: idleSeconds, flags: flags)
                lock.lock()
                defer { lock.unlock() }
                return mp_activity_step(handle, &input, &out, MemoryLayout<MPActivityStepOut>.size)
            }
        }
        guard status == MP_ACTIVITY_OK else { return nil }
        if out.minutes > 0 { markDirty() }
        return out
    }

    /// The user's "do not record" list (bundle ids): those apps count nowhere and show as hidden.
    func setHidden(_ ids: [String]) {
        let kept = Array(ids.prefix(Int(MP_ACTIVITY_MAX_RULES)))
        let copies = kept.map { strdup($0) }
        defer { copies.forEach { free($0) } }
        let rules = copies.map { MPActivityRule(id: $0.map { UnsafePointer($0) }, target: UInt32(MP_ACTIVITY_HIDDEN), reserved: 0) }
        lock.lock()
        _ = rules.withUnsafeBufferPointer { mp_activity_set_rules(handle, $0.baseAddress, $0.count) }
        lock.unlock()
    }

    /// What the companion app's status line may show now (category, and the display name only while active). Any thread.
    func current() -> MPActivityCurrent {
        var out = MPActivityCurrent()
        lock.lock()
        _ = mp_activity_current(handle, &out, MemoryLayout<MPActivityCurrent>.size)
        lock.unlock()
        return out
    }

    /// Days with minutes, oldest first (the checks; the companion app reads the file itself).
    func days() -> [MPActivityDay] {
        lock.lock()
        defer { lock.unlock() }
        let count = mp_activity_days(handle, nil, 0)
        var days = [MPActivityDay](repeating: MPActivityDay(), count: count)
        let written = days.withUnsafeMutableBufferPointer { mp_activity_days(handle, $0.baseAddress, $0.count) }
        return Array(days.prefix(min(count, written)))
    }

    /// Forgets every day and writes at once (「清除時間紀錄」).
    func clear() {
        lock.lock()
        mp_activity_clear(handle)
        dirty = true
        lock.unlock()
        flush(wait: true)
    }

    // MARK: Writing

    private func markDirty() {
        lock.lock()
        dirty = true
        let arm = pending == nil
        if arm {
            let work = DispatchWorkItem { [weak self] in self?.timerFired() }
            pending = work
            DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(Self.batchSeconds), execute: work)
        }
        lock.unlock()
    }

    private func timerFired() {
        lock.lock(); pending = nil; lock.unlock()
        flush(wait: false)
    }

    /// Writes the unflushed minutes; with `wait` the file is written before this returns. A clean ledger writes nothing.
    func flush(wait: Bool) {
        lock.lock()
        pending?.cancel()
        pending = nil
        guard dirty else { lock.unlock(); return }
        dirty = false
        var delta = [UInt8](repeating: 0, count: Int(MP_ACTIVITY_DELTA_BYTES))
        var live = [UInt8](repeating: 0, count: Int(MP_ACTIVITY_STATE_BYTES))
        let deltaCount = mp_activity_take_delta(handle, &delta, delta.count)
        let liveCount = mp_activity_to_bytes(handle, &live, live.count)
        guard deltaCount == delta.count, liveCount == live.count else { dirty = true; lock.unlock(); return }
        lock.unlock()
        let directory = directory
        let job = { [self] in
            let merged = Self.write(delta: delta, live: live, directory: directory)
            lock.lock()
            if let merged {
                _ = merged.withUnsafeBufferPointer { mp_activity_merge(handle, $0.baseAddress, $0.count) }
            } else { dirty = true }
            lock.unlock()
        }
        if wait { queue.sync(execute: job) } else { queue.async(execute: job) }
    }

    /// Under the file lock: read, add the delta, merge the live state, write a temporary file (owner only), rename it
    /// over the file. Returns the merged state.
    static func write(delta: [UInt8], live: [UInt8], directory: URL) -> [UInt8]? {
        do { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) } catch { return nil }
        let descriptor = open(directory.appendingPathComponent(lockName).path, O_CREAT | O_RDWR, 0o600)
        guard descriptor >= 0 else { return nil }
        defer { flock(descriptor, LOCK_UN); close(descriptor) }
        guard flock(descriptor, LOCK_EX) == 0 else { return nil }

        let file = directory.appendingPathComponent(fileName)
        let onDisk = (try? Data(contentsOf: file)) ?? Data()
        var status: Int32 = 0
        let disk = onDisk.withUnsafeBytes { raw in
            mp_activity_from_bytes(raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &status)
        }
        defer { mp_activity_free(disk) }
        _ = delta.withUnsafeBufferPointer { mp_activity_apply_delta(disk, $0.baseAddress, $0.count) }
        _ = live.withUnsafeBufferPointer { mp_activity_merge(disk, $0.baseAddress, $0.count) }
        var merged = [UInt8](repeating: 0, count: Int(MP_ACTIVITY_STATE_BYTES))
        guard mp_activity_to_bytes(disk, &merged, merged.count) == merged.count else { return nil }
        let temporary = directory.appendingPathComponent("\(fileName).tmp\(getpid())")
        guard FileManager.default.createFile(atPath: temporary.path, contents: Data(merged), attributes: [.posixPermissions: 0o600]) else { return nil }
        if rename(temporary.path, file.path) == 0 { return merged }
        try? FileManager.default.removeItem(at: temporary)
        return nil
    }

    private static func withOptionalCString<T>(_ text: String?, _ body: (UnsafePointer<CChar>?) -> T) -> T {
        guard let text else { return body(nil) }
        return text.withCString { body($0) }
    }
}
