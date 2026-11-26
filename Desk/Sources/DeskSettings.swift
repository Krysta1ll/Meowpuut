import Foundation

// The settings 喵撲貓窩 follows: the input method's settings.json (docs/SETTINGS.md), read only: 桌面貓, the typing
// seat and the three 「時間花在哪裡」 keys (activityStats, activityPaused, activityHidden). The Desk never
// creates or writes that file (the input method's first run migrates its old settings only when the file is
// missing). It is re-read when its folder changes or when a writer posts app.meowpuut.settings.changed.

struct DeskPreferences: Equatable {
    /// `deskPets`: cats on the desktop (default on).
    var deskPets = true
    /// `catGlassSkin` (米米莫與莫莫): with the cats switched off altogether there are none on the desktop either.
    var cats = true
    /// `typingSeat`: split (乙, default: Momo on the candidate glass, Mimimo walks your window's top edge) or
    /// glass (甲: both on the glass while you type).
    var typing = UInt32(MP_DESK_TYPING_SPLIT)
    /// `activityStats`: 「時間花在哪裡」 (FINAL.md §11, Steam version): count active minutes per app category (default off).
    var activityStats = false
    /// `activityPaused`: counting paused by the user (default off).
    var activityPaused = false
    /// `activityHidden`: bundle ids never recorded (default none).
    var activityHidden: [String] = []

    var on: Bool { deskPets && cats }

    /// What ForegroundWatcher follows.
    var activity: ForegroundWatcher.Switches {
        ForegroundWatcher.Switches(recording: activityStats, paused: activityPaused, hidden: activityHidden)
    }

    init() {}

    /// From the `settings` object of settings.json. A wrong type or an unknown value is the default.
    init(settings: [String: Any]?) {
        guard let settings else { return }
        deskPets = settings["deskPets"] as? Bool ?? true
        cats = settings["catGlassSkin"] as? Bool ?? true
        typing = UInt32((settings["typingSeat"] as? String) == "glass" ? MP_DESK_TYPING_GLASS : MP_DESK_TYPING_SPLIT)
        activityStats = settings["activityStats"] as? Bool ?? false
        activityPaused = settings["activityPaused"] as? Bool ?? false
        // At most MP_ACTIVITY_MAX_RULES ids of at most 255 bytes, no blanks, no repeats, in the order written.
        var seen = Set<String>()
        activityHidden = ((settings["activityHidden"] as? [Any]) ?? []).compactMap { $0 as? String }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && $0.utf8.count <= 255 && seen.insert($0.lowercased()).inserted }
        activityHidden = Array(activityHidden.prefix(Int(MP_ACTIVITY_MAX_RULES)))
    }
}

final class DeskSettingsReader {
    /// Where the input method keeps its settings (Preferences.directory for the shipping input method).
    static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Meowpuut")
    }

    let directory: URL
    var fileURL: URL { directory.appendingPathComponent(SettingsStore.fileName) }
    private(set) var preferences: DeskPreferences
    /// Called on the main queue when the preferences changed.
    var onChange: (() -> Void)?
    private var signature: String?
    private var source: DispatchSourceFileSystemObject?
    private var observer: NSObjectProtocol?

    init(directory: URL, watch: Bool) {
        self.directory = directory
        preferences = DeskPreferences(settings: SettingsStore.read(directory.appendingPathComponent(SettingsStore.fileName)))
        signature = Self.signature(of: directory.appendingPathComponent(SettingsStore.fileName))
        if watch { startWatching() }
    }

    deinit {
        source?.cancel()
        if let observer { DistributedNotificationCenter.default().removeObserver(observer) }
    }

    /// Re-reads when the file is not what was read last. A missing or broken file keeps the preferences.
    func refresh() {
        let now = Self.signature(of: fileURL)
        guard now != signature else { return }
        signature = now
        guard let settings = SettingsStore.read(fileURL) else { return }
        let next = DeskPreferences(settings: settings)
        guard next != preferences else { return }
        preferences = next
        onChange?()
    }

    private func startWatching() {
        let descriptor = open(directory.path, O_EVTONLY)
        if descriptor >= 0 {
            let source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: descriptor, eventMask: [.write, .rename, .delete], queue: .main)
            source.setEventHandler { [weak self] in self?.refresh() }
            source.setCancelHandler { close(descriptor) }
            source.resume()
            self.source = source
        }
        observer = DistributedNotificationCenter.default().addObserver(forName: SettingsStore.changedNotification, object: nil, queue: .main) { [weak self] _ in
            self?.refresh()
        }
    }

    private static func signature(of file: URL) -> String? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: file.path) else { return nil }
        let time = (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return "\(time)-\(attributes[.size] as? Int ?? 0)"
    }
}
