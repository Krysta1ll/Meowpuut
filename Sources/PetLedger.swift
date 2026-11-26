import AppKit

// The word-count ledger of this Mac, shared in shape with iOS and Android
// (Engine/crates/meowpuut-pets, `mp_pets_*`; the same file as iOS' PetJournal).
// It keeps numbers only: how many characters were committed, which local days
// had typing, a size band per day, which cats were around, the part of the
// day and where 字景's journey had got to that day (a 4-bit place, worked out
// from the character count when the file is written). Never the text, an app
// name or a timestamp. The text is counted and dropped in `committed(_:)`.
//
// Privacy: nothing is written per key. The first unflushed change arms one
// timer; the file is written when it fires (60 s), when the input method goes
// idle (deactivateServer) and when the app quits. Only a dirty ledger writes.
// Several processes can flush at once, so a flush is: lock, read the file,
// add this process's increments, merge in the live state, write a temporary
// file, rename it over the file, merge the result back, unlock.

final class PetLedger {
    static let fileName = "PetJournal.bin"
    static let lockName = "PetJournal.lock"
    /// The longest a counted change waits before it is written.
    static let batchSeconds = 60

    /// The ledger of the running input method; its file is in `Preferences.directory`
    /// (a temporary folder under `--smoke`).
    static let shared: PetLedger = {
        let ledger = PetLedger(directory: Preferences.directory)
        NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: nil) { _ in
            ledger.flush(wait: true)
        }
        return ledger
    }()

    let directory: URL
    private let handle: OpaquePointer
    /// Guards `handle`, `dirty` and `pending`.
    private let lock = NSLock()
    private var dirty = false
    /// The clock of the last commit since the last flush: the flush records the journey's place on its day.
    private var lastCommit: MPPetsClock?
    private var pending: DispatchWorkItem?
    private let queue = DispatchQueue(label: "meowpuut.petledger", qos: .utility)

    init(directory: URL) {
        self.directory = directory
        let data = (try? Data(contentsOf: directory.appendingPathComponent(Self.fileName))) ?? Data()
        var status: Int32 = 0
        handle = data.withUnsafeBytes { raw in
            mp_pets_from_bytes(raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &status)
        }
    }

    deinit { mp_pets_free(handle) }

    // MARK: Counting

    /// Counts the graphemes of a commit and returns the count (the desk-pet pulse reuses it). The text is not kept.
    /// Cheap: one count, one call into the rules, at most one timer.
    @discardableResult
    func committed(_ text: String) -> Int {
        let count = text.count
        guard count > 0 else { return 0 }
        var ambience = MPPetsAmbience(flags: 0, quiet_ms: .max, mode: UInt32(MP_PETS_MODE_OFF),
                                      cats: Preferences.shared.catGlassSkin ? 3 : 0)
        var clock = Self.clock()
        var plan = MPPetsPlan()
        lock.lock()
        _ = mp_pets_decide(handle, UInt32(MP_PETS_COMMIT), UInt32(clamping: count), &ambience, &clock, 0, &plan)
        dirty = true
        lastCommit = clock
        let arm = pending == nil
        if arm {
            let work = DispatchWorkItem { [weak self] in self?.timerFired() }
            pending = work
            DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(Self.batchSeconds), execute: work)
        }
        lock.unlock()
        return count
    }

    /// Forgets everything and writes at once; the raised epoch keeps another process from bringing the old numbers back.
    func clear() {
        lock.lock()
        mp_pets_clear(handle)
        dirty = true
        lastCommit = nil // forgotten: no place for it either
        lock.unlock()
        flush(wait: true)
    }

    // MARK: Read-outs

    /// Characters committed, all time (the header of the state; never on the key path).
    var totalCharacters: UInt64 {
        lock.lock(); defer { lock.unlock() }
        return charactersLocked()
    }

    private func charactersLocked() -> UInt64 {
        var bytes = [UInt8](repeating: 0, count: Int(MP_PETS_STATE_BYTES))
        guard mp_pets_to_bytes(handle, &bytes, bytes.count) == bytes.count else { return 0 }
        return Self.header(of: bytes)?.graphemes ?? 0
    }

    /// Each kept day's place, oldest day first: a place index of 字景's journey, or `MP_PETS_PLACE_UNKNOWN` for a
    /// day from before places were kept.
    var dayPlaces: [UInt8] {
        lock.lock(); defer { lock.unlock() }
        var places = [UInt8](repeating: 0, count: 400)
        let count = min(400, mp_pets_mark_places(handle, &places, places.count))
        return Array(places.prefix(count))
    }

    /// The number of local days with typing.
    var dayCount: Int {
        lock.lock(); defer { lock.unlock() }
        var raw = [MPPetsMark](repeating: MPPetsMark(), count: 400)
        return min(400, mp_pets_marks(handle, &raw, raw.count))
    }

    // MARK: Writing

    private func timerFired() {
        lock.lock(); pending = nil; lock.unlock()
        flushNow()
    }

    /// Writes on the ledger's queue and returns at once.
    func flushNow() { flush(wait: false) }

    /// Writes the unflushed increments; with `wait` the file is written before this returns.
    func flush(wait: Bool) {
        lock.lock()
        pending?.cancel()
        pending = nil
        guard dirty else { lock.unlock(); return }
        dirty = false
        // The day's place, once a flush (never per key): where the journey has got to for the characters committed,
        // on the day of the last commit. The ledger keeps the furthest place of a day.
        if var when = lastCommit {
            let characters = Int64(clamping: charactersLocked())
            _ = mp_pets_reach_place(handle, &when, mp_glyph_place_of_characters(characters))
            lastCommit = nil
        }
        var delta = [UInt8](repeating: 0, count: Int(MP_PETS_DELTA_BYTES))
        var live = [UInt8](repeating: 0, count: Int(MP_PETS_STATE_BYTES))
        let deltaCount = mp_pets_take_delta(handle, &delta, delta.count)
        let liveCount = mp_pets_to_bytes(handle, &live, live.count)
        guard deltaCount == delta.count, liveCount == live.count else { dirty = true; lock.unlock(); return }
        lock.unlock()
        let directory = directory
        let job = { [self] in
            let merged = Self.write(delta: delta, live: live, directory: directory)
            lock.lock()
            if let merged {
                _ = merged.withUnsafeBufferPointer { mp_pets_merge(handle, $0.baseAddress, $0.count) }
            } else { dirty = true }
            lock.unlock()
        }
        if wait { queue.sync(execute: job) } else { queue.async(execute: job) }
    }

    /// Under the file lock: read, add the delta, merge the live state, write a temporary file, rename it over the
    /// file. Returns the merged state.
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
            mp_pets_from_bytes(raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &status)
        }
        defer { mp_pets_free(disk) }
        _ = delta.withUnsafeBufferPointer { mp_pets_apply_delta(disk, $0.baseAddress, $0.count) }
        _ = live.withUnsafeBufferPointer { mp_pets_merge(disk, $0.baseAddress, $0.count) }
        var merged = [UInt8](repeating: 0, count: Int(MP_PETS_STATE_BYTES))
        guard mp_pets_to_bytes(disk, &merged, merged.count) == merged.count else { return nil }
        let temporary = directory.appendingPathComponent("\(fileName).tmp\(getpid())")
        guard (try? Data(merged).write(to: temporary)) != nil else { return nil }
        if rename(temporary.path, file.path) == 0 { return merged }
        try? FileManager.default.removeItem(at: temporary)
        return nil
    }

    // MARK: Clock and header

    /// The clock the rules expect: milliseconds since boot, the local day index since 1970 and the minute of the day.
    static func clock(date: Date = Date(), uptime: TimeInterval = ProcessInfo.processInfo.systemUptime,
                      calendar: Calendar = .current) -> MPPetsClock {
        let local = date.timeIntervalSince1970 + TimeInterval(calendar.timeZone.secondsFromGMT(for: date))
        let days = (local / 86_400).rounded(.down)
        let minute = ((local - days * 86_400) / 60).rounded(.down)
        return MPPetsClock(mono_ms: UInt64(max(0, uptime) * 1000),
                           day: days >= 1 && days < Double(UInt32.max) ? UInt32(days) : 0,
                           minute: UInt32(min(1439, max(0, minute))))
    }

    /// The header of a state (crates/meowpuut-pets/src/codec.rs), the same in versions 1 and 2 (2 adds each day's
    /// place): magic 9C A7, the version, a reserved byte, the epoch u32 at 4 and the committed graphemes u64 at 8,
    /// little-endian. Nil for anything else.
    static func header(of bytes: [UInt8]) -> (epoch: UInt32, graphemes: UInt64)? {
        guard bytes.count == Int(MP_PETS_STATE_BYTES), bytes.starts(with: [0x9C, 0xA7]), (1...2).contains(bytes[2])
        else { return nil }
        let epoch = bytes[4..<8].reversed().reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        let graphemes = bytes[8..<16].reversed().reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        return (epoch, graphemes)
    }
}
