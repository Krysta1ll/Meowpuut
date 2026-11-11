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
        print("PASS: shared settings preserve unspecified preferences")
    }
}
