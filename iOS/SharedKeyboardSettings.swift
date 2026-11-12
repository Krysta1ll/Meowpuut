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
    /// "daily", or the id of the arrangement the user pinned (CatArrangement).
    var catPlacement: String?
    var candidateTone: String?
    var glassStrength: String?
    var candidatePalette: String?
    var glassShape: String?
    var festivals: Bool?
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
}
