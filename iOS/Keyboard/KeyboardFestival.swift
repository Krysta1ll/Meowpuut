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
        return Festivals.theme(on: date, wardrobe: preferences.festivalWardrobe)
    }
}
