import Foundation

// Interface text in both Chinese scripts. The keyboard follows the script the
// user is typing in, so a switch made by petting 莫莫 changes its labels at
// once; the containing app follows the system language.
struct InterfaceText {
    var simplified: Bool

    func callAsFunction(_ traditional: String, _ simplifiedText: String) -> String {
        simplified ? simplifiedText : traditional
    }

    static var system: InterfaceText {
        InterfaceText(simplified: prefersSimplified(languages: Locale.preferredLanguages))
    }

    // The first Chinese entry in the user's language list decides; other
    // languages fall back to Traditional, the project's original script.
    static func prefersSimplified(languages: [String]) -> Bool {
        for language in languages {
            let tag = language.lowercased().replacingOccurrences(of: "_", with: "-")
            guard tag == "zh" || tag.hasPrefix("zh-") else { continue }
            if tag.contains("-hans") { return true }
            if tag.contains("-hant") { return false }
            return ["zh-cn", "zh-sg", "zh-my"].contains { tag.hasPrefix($0) }
        }
        return false
    }
}
