import Foundation

// Holiday outfits for the cats (docs/FESTIVALS.md). The bundled manifest is
// usually empty; an outfit is added shortly before its holiday.

struct FestivalOutfit: Decodable, Equatable, Identifiable {
    struct Title: Decodable, Equatable {
        var hant: String
        var hans: String
    }

    /// Images drawn on the cat's own sprite canvas.
    struct Layers: Decodable, Equatable {
        var front: String?
        var back: String?
        var frames: [String: String]?
    }

    var id: String
    var title: Title
    var start: String
    var end: String
    var cats: [String: Layers]
    var pill: String?

    /// Inclusive local-date range; dates are `yyyy-MM-dd`, so they compare as text.
    func isActive(onDay day: String) -> Bool { start <= day && day <= end }

    /// `pill` as red, green and blue from 0 to 1, when it is written #RRGGBB.
    var pillRGB: (red: Double, green: Double, blue: Double)? {
        guard let pill, pill.hasPrefix("#"), pill.utf8.count == 7, let value = UInt32(pill.dropFirst(), radix: 16) else { return nil }
        return (Double((value >> 16) & 0xff) / 255, Double((value >> 8) & 0xff) / 255, Double(value & 0xff) / 255)
    }

    /// Whether `text` is a real calendar day written yyyy-MM-dd.
    static func isDay(_ text: String) -> Bool {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard text.utf8.count == 10, parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]), (1...12).contains(month) else { return false }
        let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        let days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
        return (1...days).contains(day)
    }

    /// Dates are well formed and in order. An outfit that fails this is
    /// left out: compared as text it would never be active, or always.
    var hasValidDates: Bool { Self.isDay(start) && Self.isDay(end) && start <= end }

    func layers(for cat: String) -> Layers? { cats[cat] }
}

enum Festivals {
    /// An outfit that does not decode is skipped; the others still load.
    private struct Entry: Decodable {
        var outfit: FestivalOutfit?
        init(from decoder: Decoder) throws { outfit = try? FestivalOutfit(from: decoder) }
    }

    private struct Manifest: Decodable {
        var version: Int
        var outfits: [Entry]
    }

    static func decode(_ data: Data) -> [FestivalOutfit] {
        guard let manifest = try? JSONDecoder().decode(Manifest.self, from: data), manifest.version == 1 else { return [] }
        return manifest.outfits.compactMap(\.outfit).filter(\.hasValidDates)
    }

    /// The manifest bundled with this app (folder `Festivals`), read once.
    static let bundled: [FestivalOutfit] = {
        guard let url = directory?.appendingPathComponent("festivals.json"),
              let data = try? Data(contentsOf: url) else { return [] }
        return decode(data)
    }()

    /// Set before first use to read outfits from another folder (the lab app's fixtures).
    static var directoryOverride: URL?

    static var directory: URL? {
        directoryOverride ?? Bundle.main.url(forResource: "Festivals", withExtension: nil)
    }

    /// The Gregorian calendar in the device's time zone. Outfit dates are
    /// Gregorian whatever calendar the device is set to (Buddhist, Japanese).
    static var gregorian: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        return calendar
    }

    static func day(_ date: Date, calendar: Calendar = Festivals.gregorian) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// The first outfit whose range contains `date`.
    static func active(in outfits: [FestivalOutfit] = bundled, on date: Date = Date(), calendar: Calendar = Festivals.gregorian) -> FestivalOutfit? {
        let today = day(date, calendar: calendar)
        return outfits.first { $0.isActive(onDay: today) }
    }

    static func imageURL(outfit: FestivalOutfit, file: String) -> URL? {
        directory?.appendingPathComponent(outfit.id).appendingPathComponent(file)
    }
}
