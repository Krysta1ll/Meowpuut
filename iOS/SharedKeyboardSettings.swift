import Foundation

// The containing app writes only settings. Without full access the keyboard can
// read this group container, but learning remains in its private sandbox.
struct SharedKeyboardSettings: Codable {
    var inputMode: String?
    var simplified: Bool?
    // Changes whenever the app records a script choice, so the keyboard can
    // tell a new choice from one it has already applied (and that 莫莫 may
    // have since switched inside the keyboard).
    var simplifiedRevision: String?
    var catGlassSkin: Bool?
    var candidateTone: String?
    var glassStrength: String?
    var candidatePalette: String?
    var rememberChoices: Bool?
    var clearMemoryRequest: String?

    private static var file: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.krysta1ll.meowpuut.shared")?
            .appendingPathComponent("KeyboardSettings.json")
    }

    static func read() -> Self? {
        guard let file, let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }

    func save() throws {
        guard let file = Self.file else { throw CocoaError(.fileNoSuchFile) }
        try JSONEncoder().encode(self).write(to: file, options: .atomic)
    }

    // Compacts append-only mimo_user_wal.log entries into mimo_user.db inside the
    // App Group container when the Host App launches or enters background.
    @discardableResult
    static func compactUserWalIfNeeded() -> Int {
        guard let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.krysta1ll.meowpuut.shared") else {
            return 0
        }
        return compactWalFiles(in: group)
    }

    @discardableResult
    static func compactWalFiles(in directory: URL) -> Int {
        let walURL = directory.appendingPathComponent("mimo_user_wal.log")
        let dbURL = directory.appendingPathComponent("mimo_user.db")
        guard let walText = try? String(contentsOf: walURL, encoding: .utf8), !walText.isEmpty else {
            return 0
        }
        struct ChoiceItem: Codable {
            var text: String
            var uses: Int
            var lastUsed: TimeInterval
        }
        var records: [String: [ChoiceItem]] = [:]
        if let dbData = try? Data(contentsOf: dbURL),
           let decoded = try? JSONDecoder().decode([String: [ChoiceItem]].self, from: dbData) {
            records = decoded
        }
        var applied = 0
        for line in walText.split(separator: "\n") {
            let parts = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard parts.count == 4, parts[0] == "W", let ts = TimeInterval(parts[1]) else { continue }
            let reading = String(parts[2])
            let text = String(parts[3])
            guard !reading.isEmpty, !text.isEmpty else { continue }
            var list = records[reading] ?? []
            if let idx = list.firstIndex(where: { $0.text == text }) {
                list[idx].uses = min(list[idx].uses + 1, 1_000_000)
                list[idx].lastUsed = max(list[idx].lastUsed, ts)
            } else {
                list.append(ChoiceItem(text: text, uses: 1, lastUsed: ts))
            }
            list.sort { $0.uses == $1.uses ? $0.lastUsed > $1.lastUsed : $0.uses > $1.uses }
            records[reading] = Array(list.prefix(8))
            applied += 1
        }
        guard applied > 0, let encoded = try? JSONEncoder().encode(records) else { return 0 }
        if (try? encoded.write(to: dbURL, options: .atomic)) != nil {
            try? FileManager.default.removeItem(at: walURL)
        }
        return applied
    }
}
