import Foundation

@main
struct SharedKeyboardSettingsTests {
    static func main() throws {
        var settings = SharedKeyboardSettings()
        settings.inputMode = "pinyin"
        settings.clearMemoryRequest = "request-1"
        let encoded = try JSONEncoder().encode(settings)
        let decoded = try JSONDecoder().decode(SharedKeyboardSettings.self, from: encoded)
        precondition(decoded.inputMode == "pinyin" && decoded.simplified == nil)
        precondition(decoded.catGlassSkin == nil && decoded.rememberChoices == nil)
        precondition(decoded.clearMemoryRequest == "request-1")
        let older = try JSONDecoder().decode(SharedKeyboardSettings.self, from: Data("{\"simplified\":true}".utf8))
        precondition(older.simplified == true && older.clearMemoryRequest == nil)
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("Meowpuut-WAL-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let wal = "W\t1700000000\tpy:t:nihao\t你好\nW\t1700000005\tpy:t:nihao\t你好\n"
        try Data(wal.utf8).write(to: tmp.appendingPathComponent("mimo_user_wal.log"))
        let compacted = SharedKeyboardSettings.compactWalFiles(in: tmp)
        precondition(compacted == 2)
        precondition(!FileManager.default.fileExists(atPath: tmp.appendingPathComponent("mimo_user_wal.log").path))
        precondition(FileManager.default.fileExists(atPath: tmp.appendingPathComponent("mimo_user.db").path))
        print("PASS: shared settings preserve unspecified preferences and compact WAL to mimo_user.db")
    }
}
