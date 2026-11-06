import Foundation

// The containing app writes only settings. Without full access the keyboard can
// read this group container, but learning remains in its private sandbox.
struct SharedKeyboardSettings: Codable {
    var inputMode: String?
    var simplified: Bool?
    var catGlassSkin: Bool?
    var candidateTone: String?
    var glassStrength: String?
    var candidatePalette: String?
    var rememberChoices: Bool?
    var clearMemoryRequest: String?

    private static var file: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.app.meowpuut.shared")?
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
