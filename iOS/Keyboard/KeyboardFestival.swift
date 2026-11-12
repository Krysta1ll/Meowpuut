import Foundation

/// The festival theme the keyboard wears today (docs/FESTIVALS.md): by the
/// device's date, the 跟著節日換裝 switch and the themes switched off in the
/// app, which the keyboard reads from the shared settings and never writes.
enum KeyboardFestival {
    #if KEYBOARD_LAB
    /// The lab app only (-festivalDay, -festivalTheme): look at another day,
    /// or wear one theme whatever the date. Not compiled into the keyboard.
    static var labDay: String?
    static var labTheme: String?
    #endif

    static func today(_ preferences: Preferences = .shared, date: Date = Date()) -> FestivalTheme? {
        #if KEYBOARD_LAB
        if let id = labTheme { return Festivals.bundled.themes.first { $0.id == id } }
        if let day = labDay { return Festivals.theme(onDay: day, wardrobe: preferences.festivalWardrobe) }
        #endif
        #if FESTIVAL_SHOTS
        // Screenshot builds only (scripts/ios-real-keyboard-shots.sh with
        // FESTIVAL_SHOTS=1): the plan's settings may name a day to show.
        if let day = shotDay { return Festivals.theme(onDay: day, wardrobe: preferences.festivalWardrobe) }
        #endif
        return Festivals.theme(on: date, wardrobe: preferences.festivalWardrobe)
    }

    #if FESTIVAL_SHOTS
    private static var shotDay: String? {
        let file = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.krysta1ll.meowpuut.shared")?
            .appendingPathComponent("KeyboardSettings.json")
        let json = file.flatMap { try? Data(contentsOf: $0) }.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
        return json?["festivalShotDay"] as? String
    }
    #endif
}
