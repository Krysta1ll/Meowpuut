import Foundation

// The settings of the macOS input method live in one JSON file, `settings.json` in `Preferences.directory`, so that a
// separate companion app can read and write them (docs/SETTINGS.md). The keys, types and defaults are those the
// input method always stored in its UserDefaults; `Preferences` reads and writes through this class with the same
// calls (`object(forKey:)`, `set(_:forKey:)` ...).
//
// Privacy: settings only, never typed text. The one value that holds words is `candidatePins` (reading to the word
// the user pinned), which was in UserDefaults before and is kept here as it was.
//
// Writing: a change is held for `delay` seconds and written once (menu clicks and sliders do not write per event),
// and on `flush`. A write is: lock `settings.lock`, read the file, apply only the keys this process changed, write a
// temporary file, rename it over `settings.json`, unlock. So a key written by the companion app in the meantime is
// not overwritten by a key this process did not touch.
//
// Reading: the file is read at start and again when it changes: a directory watch, a distributed notification
// (`SettingsStore.changedNotification`, no payload) and `refreshIfChanged()` on activation all end in one mtime/size
// check, so the file is parsed only when it really changed. A corrupt or missing file keeps the values in memory.

final class SettingsStore {
    static let fileName = "settings.json"
    static let lockName = "settings.lock"
    /// Posted (no userInfo, no object) on `DistributedNotificationCenter` by a writer that wants the input method to re-read.
    static let changedNotification = Notification.Name("app.meowpuut.settings.changed")
    static let schema = 1

    let directory: URL
    private var values: [String: Any] = [:]
    /// Keys changed here and not yet written; nil value = removed.
    private var pending: [String: Any?] = [:]
    private var timer: DispatchWorkItem?
    private var signature: String?
    private let delay: TimeInterval
    private let lock = NSLock()
    private var source: DispatchSourceFileSystemObject?
    private var observer: NSObjectProtocol?
    /// Called on the main queue after another process changed the file and the values were replaced.
    var onExternalChange: (() -> Void)?

    var fileURL: URL { directory.appendingPathComponent(Self.fileName) }

    /// `legacy` is the old UserDefaults domain; with no file yet, the keys in `keys` are copied from it once.
    init(directory: URL, legacy: [String: Any] = [:], keys: [String] = [], delay: TimeInterval = 0.4, watch: Bool = true) {
        self.directory = directory
        self.delay = delay
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            var copied: [String: Any] = [:]
            for key in keys { if let value = legacy[key], JSONSerialization.isValidJSONObject([value]) { copied[key] = value } }
            values = copied
            writeMigrated()
        } else if let parsed = Self.read(fileURL) { values = parsed }
        signature = Self.signature(of: fileURL)
        if watch { startWatching() }
    }

    deinit {
        source?.cancel()
        if let observer { DistributedNotificationCenter.default().removeObserver(observer) }
    }

    // MARK: Reading values (the UserDefaults calls Preferences uses)

    func object(forKey key: String) -> Any? { lock.lock(); defer { lock.unlock() }; return values[key] }
    func string(forKey key: String) -> String? { object(forKey: key) as? String }
    func bool(forKey key: String) -> Bool { (object(forKey: key) as? Bool) ?? false }
    func stringArray(forKey key: String) -> [String]? { object(forKey: key) as? [String] }
    func dictionary(forKey key: String) -> [String: Any]? { object(forKey: key) as? [String: Any] }
    var allKeys: [String] { lock.lock(); defer { lock.unlock() }; return Array(values.keys) }

    // MARK: Writing values

    /// An unchanged value is not a change. Values that JSON cannot hold are ignored.
    func set(_ value: Any?, forKey key: String) {
        lock.lock()
        if let value {
            guard JSONSerialization.isValidJSONObject([value]), !Self.equal(values[key], value) else { lock.unlock(); return }
            values[key] = value
            pending[key] = .some(value)
        } else {
            guard values[key] != nil else { lock.unlock(); return }
            values[key] = nil
            pending[key] = .some(nil)
        }
        let arm = timer == nil
        if arm {
            let work = DispatchWorkItem { [weak self] in self?.flush() }
            timer = work
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
        }
        lock.unlock()
    }

    /// Writes the held changes now.
    func flush() {
        lock.lock()
        timer?.cancel(); timer = nil
        let changes = pending
        pending = [:]
        lock.unlock()
        guard !changes.isEmpty else { return }
        guard let merged = Self.write(changes: changes, directory: directory) else {
            lock.lock()
            for (key, value) in changes where pending[key] == nil { pending[key] = .some(value) }
            lock.unlock()
            return
        }
        lock.lock()
        // The file's other keys come in too; keys changed again since stay as they are here.
        for (key, value) in merged where pending[key] == nil { values[key] = value }
        signature = Self.signature(of: fileURL)
        lock.unlock()
    }

    // MARK: Following the file

    /// Re-reads the file when it is not what was last read or written here. Returns whether values changed.
    @discardableResult
    func refreshIfChanged() -> Bool {
        let now = Self.signature(of: fileURL)
        lock.lock()
        let unchanged = now == signature
        lock.unlock()
        if unchanged { return false }
        guard let parsed = Self.read(fileURL) else {
            // Corrupt or gone: keep what is in memory, and ask again only after the file changes again.
            lock.lock(); signature = now; lock.unlock()
            return false
        }
        lock.lock()
        signature = now
        var next = parsed
        for (key, value) in pending { next[key] = value ?? nil }
        let changed = !Self.equal(next, values)
        values = next
        lock.unlock()
        if changed { onExternalChange?() }
        return changed
    }

    private func startWatching() {
        let descriptor = open(directory.path, O_EVTONLY)
        if descriptor >= 0 {
            let source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: descriptor, eventMask: [.write, .rename, .delete], queue: .main)
            source.setEventHandler { [weak self] in self?.refreshIfChanged() }
            source.setCancelHandler { close(descriptor) }
            source.resume()
            self.source = source
        }
        observer = DistributedNotificationCenter.default().addObserver(forName: Self.changedNotification, object: nil, queue: .main) { [weak self] _ in
            self?.refreshIfChanged()
        }
    }

    // MARK: File

    private func writeMigrated() {
        _ = Self.write(changes: values.mapValues { .some($0) }, directory: directory)
    }

    /// Under the file lock: read, apply `changes`, write a temporary file, rename it over the file. Returns the file's settings.
    static func write(changes: [String: Any?], directory: URL) -> [String: Any]? {
        do { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) } catch { return nil }
        let descriptor = open(directory.appendingPathComponent(lockName).path, O_CREAT | O_RDWR, 0o600)
        guard descriptor >= 0 else { return nil }
        defer { flock(descriptor, LOCK_UN); close(descriptor) }
        guard flock(descriptor, LOCK_EX) == 0 else { return nil }
        let file = directory.appendingPathComponent(fileName)
        var settings = read(file) ?? [:]
        for (key, value) in changes { settings[key] = value ?? nil }
        let document: [String: Any] = ["schema": schema, "settings": settings]
        guard let data = try? JSONSerialization.data(withJSONObject: document, options: [.prettyPrinted, .sortedKeys]) else { return nil }
        let temporary = directory.appendingPathComponent("\(fileName).tmp\(getpid())")
        guard (try? data.write(to: temporary)) != nil else { return nil }
        if rename(temporary.path, file.path) == 0 { return settings }
        try? FileManager.default.removeItem(at: temporary)
        return nil
    }

    /// The `settings` object of a version-1 file; nil when missing, not JSON or not the right shape.
    static func read(_ file: URL) -> [String: Any]? {
        guard let data = try? Data(contentsOf: file), data.count <= 1_048_576,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let settings = root["settings"] as? [String: Any] else { return nil }
        return settings
    }

    private static func signature(of file: URL) -> String? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: file.path) else { return nil }
        let time = (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return "\(time)-\(attributes[.size] as? Int ?? 0)"
    }

    private static func equal(_ a: Any?, _ b: Any?) -> Bool {
        guard let a, let b else { return a == nil && b == nil }
        return (a as AnyObject).isEqual(b)
    }
}
