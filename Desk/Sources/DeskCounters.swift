import Foundation

/// Monotonic desk-pet achievement counters written in batches to `DeskCounters.json` beside `PetJournal.bin`.
/// Contains only unsigned numbers and the ledger epoch—no text, no timestamps, no app names.
final class DeskCountersStore {
    static let fileName = "DeskCounters.json"
    static let flushIntervalMs: UInt64 = 60_000

    struct Snapshot: Codable, Equatable {
        var epoch: UInt32 = 0
        var outings: UInt32 = 0
        var purrs: UInt32 = 0
        var rides: UInt32 = 0
        var caught: UInt32 = 0
        var found: UInt32 = 0
        var roof: UInt32 = 0
        var steps: UInt64 = 0
        var nose: UInt32 = 0
        var moved: UInt32 = 0
    }

    private let fileURL: URL
    private(set) var snapshot = Snapshot()
    private var dirty = false
    private var lastFlushMs: UInt64 = 0

    init(directory: URL) {
        self.fileURL = directory.appendingPathComponent(Self.fileName)
        if let data = try? Data(contentsOf: fileURL),
           let loaded = try? JSONDecoder().decode(Snapshot.self, from: data) {
            snapshot = loaded
        }
    }

    func syncEpoch(_ epoch: UInt32) {
        guard epoch != 0 && epoch != snapshot.epoch else { return }
        if snapshot.epoch != 0 && epoch > snapshot.epoch {
            snapshot = Snapshot(epoch: epoch)
            dirty = true
        } else {
            snapshot.epoch = epoch
            dirty = true
        }
    }

    func recordOuting() {
        snapshot.outings &+= 1
        dirty = true
    }

    func recordCaught() {
        snapshot.caught &+= 1
        dirty = true
    }

    func record(events: [MPDeskEventOut]) {
        for e in events {
            switch Int(e.kind) {
            case MP_DESK_OUT_PURR: snapshot.purrs &+= 1; dirty = true
            case MP_DESK_OUT_RODE: snapshot.rides &+= 1; dirty = true
            case MP_DESK_OUT_FOUND: snapshot.found &+= 1; dirty = true
            case MP_DESK_OUT_ROOF: snapshot.roof &+= 1; dirty = true
            case MP_DESK_OUT_STEP: snapshot.steps &+= 1; dirty = true
            default: break
            }
        }
    }

    func flushIfDue(nowMs: UInt64, force: Bool = false) {
        guard dirty else { return }
        guard force || lastFlushMs == 0 || nowMs &- lastFlushMs >= Self.flushIntervalMs else { return }
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        let dir = fileURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let tmp = dir.appendingPathComponent("\(Self.fileName).tmp.\(getpid())")
        do {
            try data.write(to: tmp, options: .atomic)
            _ = try FileManager.default.replaceItemAt(fileURL, withItemAt: tmp)
            dirty = false
            lastFlushMs = nowMs
        } catch {
            try? FileManager.default.removeItem(at: tmp)
        }
    }
}
