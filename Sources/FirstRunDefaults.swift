import Foundation

/// What a fresh install starts with, from the system language and region.
///
/// Read locally only: the region and the language list are inputs of
/// `choice(region:languages:)` and are never stored or sent. Only the result
/// (an input mode and a script) is written to the settings, once, and only
/// when nothing has been chosen yet.
enum FirstRunDefaults {
    struct Choice: Equatable {
        /// `InputMode.rawValue`: "zhuyin" or "pinyin".
        let inputMode: String
        let simplified: Bool
    }

    private static let zhuyinTraditional = Choice(inputMode: "zhuyin", simplified: false)
    private static let pinyinSimplified = Choice(inputMode: "pinyin", simplified: true)
    private static let pinyinTraditional = Choice(inputMode: "pinyin", simplified: false)

    /// The first Chinese entry of the preferred languages, as its script and region
    /// (zh-Hant-TW is Hant, TW; zh-CN is Hans, CN; plain zh is Hans).
    private static func firstChinese(_ languages: [String]) -> (traditional: Bool, region: String?)? {
        for language in languages {
            let parts = language.replacingOccurrences(of: "_", with: "-").lowercased().split(separator: "-").map(String.init)
            guard parts.first == "zh" else { continue }
            let script = parts.dropFirst().first { $0 == "hans" || $0 == "hant" }
            let region = parts.dropFirst().first { $0.count == 2 && $0 != "zh" }?.uppercased()
            let traditional = script.map { $0 == "hant" } ?? ["TW", "HK", "MO"].contains(region ?? "")
            return (traditional, region)
        }
        return nil
    }

    static func choice(region: String?, languages: [String]) -> Choice {
        let region = region?.uppercased()
        let chinese = firstChinese(languages)
        if region == "TW" || (chinese?.traditional == true && chinese?.region == "TW") { return zhuyinTraditional }
        if ["CN", "SG", "MY"].contains(region ?? "") || chinese?.traditional == false { return pinyinSimplified }
        if ["HK", "MO"].contains(region ?? "") { return pinyinTraditional }
        guard let chinese else { return pinyinSimplified }
        return chinese.traditional ? pinyinTraditional : pinyinSimplified
    }

    /// The choice for this device.
    static var current: Choice {
        choice(region: Locale.current.region?.identifier, languages: Locale.preferredLanguages)
    }
}

/// The 中日混打 switch: Japanese inside the Chinese modes. Off for new installs;
/// an install that predates the switch keeps what it had (on), written once.
enum MixJapaneseDefault {
    static func resolve(stored: Bool?, existingInstall: Bool) -> (value: Bool, needsWrite: Bool) {
        if let stored { return (stored, false) }
        return (existingInstall, true)
    }
}
