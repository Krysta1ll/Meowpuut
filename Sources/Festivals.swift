import CoreGraphics
import Foundation

// The festival wardrobe (docs/FESTIVALS.md): for each festival a dressed
// 米米莫 and 莫莫, a trim laid around the candidate glass and an accent
// colour. A theme is worn by the calendar only: from LEAD days before the
// festival's first day through its last day, in the device's own date.

struct FestivalTheme: Decodable, Equatable, Identifiable {
    struct Names: Decodable, Equatable {
        var hant: String
        var hans: String
        var ja: String
        var en: String
    }

    /// Insets in points: top, left, bottom, right (written as an array).
    struct Insets: Decodable, Equatable {
        var top, left, bottom, right: CGFloat

        init(top: CGFloat, left: CGFloat, bottom: CGFloat, right: CGFloat) {
            (self.top, self.left, self.bottom, self.right) = (top, left, bottom, right)
        }

        init(from decoder: Decoder) throws {
            let values = try [CGFloat](from: decoder)
            guard values.count == 4 else { throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "four insets")) }
            self.init(top: values[0], left: values[1], bottom: values[2], right: values[3])
        }
    }

    /// One cat's dressed sprite: logical size in points, and where its feet
    /// are as a fraction of the size from the top left.
    struct Sprite: Decodable, Equatable {
        var image: String
        var size: CGSize
        var anchor: CGPoint

        private enum Keys: String, CodingKey { case image, size, anchor }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: Keys.self)
            image = try c.decode(String.self, forKey: .image)
            size = try Self.pair(c.decode([CGFloat].self, forKey: .size), c, .size).size
            anchor = try Self.pair(c.decode([CGFloat].self, forKey: .anchor), c, .anchor).point
        }

        static func pair<K: CodingKey>(_ v: [CGFloat], _ c: KeyedDecodingContainer<K>, _ key: K) throws -> (size: CGSize, point: CGPoint) {
            guard v.count == 2 else { throw DecodingError.dataCorruptedError(forKey: key, in: c, debugDescription: "two numbers") }
            return (CGSize(width: v[0], height: v[1]), CGPoint(x: v[0], y: v[1]))
        }
    }

    /// The decoration around the glass. `caps` are the ends that keep their
    /// width while the middle stretches; `safeText` is where text stays
    /// clear; `rim` is where the middle of the drawn rim runs.
    struct Trim: Decodable, Equatable {
        var image: String
        var size: CGSize
        var caps: Insets
        var safeText: Insets
        var rim: Insets

        private enum Keys: String, CodingKey { case image, size, caps, safeText, rim }

        init(image: String, size: CGSize, caps: Insets, safeText: Insets, rim: Insets) {
            (self.image, self.size, self.caps, self.safeText, self.rim) = (image, size, caps, safeText, rim)
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: Keys.self)
            image = try c.decode(String.self, forKey: .image)
            size = try Sprite.pair(c.decode([CGFloat].self, forKey: .size), c, .size).size
            caps = try c.decode(Insets.self, forKey: .caps)
            safeText = try c.decode(Insets.self, forKey: .safeText)
            rim = try c.decode(Insets.self, forKey: .rim)
        }
    }

    /// `light` and `dark` are the selected capsule's top and bottom;
    /// `contrast` replaces both with Increase Contrast. All #RRGGBB.
    struct Colors: Decodable, Equatable {
        var accent: String
        var light: [String]
        var dark: [String]
        var contrast: String
    }

    /// A festival's own days in one year, yyyy-MM-dd, inclusive: written
    /// [first, main, last]. The main day is the day itself (初一, Easter
    /// Sunday, Lakshmi Puja); for a one-day festival all three are the same.
    struct Days: Decodable, Equatable {
        var first: String
        var main: String
        var last: String

        init(first: String, main: String, last: String) { (self.first, self.main, self.last) = (first, main, last) }

        init(from decoder: Decoder) throws {
            let values = try [String](from: decoder)
            guard values.count == 3, values.allSatisfy(Festivals.isDay), values[0] <= values[1], values[1] <= values[2] else {
                throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "three days in order"))
            }
            (first, main, last) = (values[0], values[1], values[2])
        }
    }

    var id: String
    var names: Names
    var mimimo: Sprite
    var momo: Sprite
    var trim: Trim
    /// The trim's ornaments cut off the rim by the importer, one cluster
    /// for each end of the bar (FestivalEndsLayout); nil in older manifests.
    var ends: Ends?
    var colors: Colors
    var days: [Days]

    struct Ends: Decodable, Equatable {
        struct Piece: Decodable, Equatable {
            var image: String
            var size: CGSize

            private enum Keys: String, CodingKey { case image, size }

            init(image: String, size: CGSize) { (self.image, self.size) = (image, size) }

            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: Keys.self)
                image = try c.decode(String.self, forKey: .image)
                size = try Sprite.pair(c.decode([CGFloat].self, forKey: .size), c, .size).size
            }
        }
        var left: Piece?
        var right: Piece?
    }

    func name(simplified: Bool) -> String { simplified ? names.hans : names.hant }
}

/// What the person chose in the host app.
struct FestivalWardrobe: Equatable {
    /// 跟著節日換裝. Off: the plain cats, always.
    var on = true
    /// Themes switched off.
    var skipped: Set<String> = []
}

enum Festivals {
    struct Manifest: Decodable {
        var version: Int
        var leadDays: Int
        var themes: [Entry]
    }

    /// A theme that does not decode, or whose id or image names are not
    /// plain names, is left out; the others still load.
    struct Entry: Decodable {
        var theme: FestivalTheme?
        init(from decoder: Decoder) throws {
            theme = (try? FestivalTheme(from: decoder)).flatMap { theme in
                ([theme.id, theme.mimimo.image, theme.momo.image, theme.trim.image]
                 + [theme.ends?.left?.image, theme.ends?.right?.image].compactMap { $0 }).allSatisfy(Festivals.isName) ? theme : nil
            }
        }
    }

    struct Loaded: Equatable {
        var leadDays: Int
        var themes: [FestivalTheme]
        static let empty = Loaded(leadDays: 7, themes: [])
        var isEmpty: Bool { themes.isEmpty }
    }

    static func decode(_ data: Data) -> Loaded {
        guard let manifest = try? JSONDecoder().decode(Manifest.self, from: data), manifest.version == 2 else { return .empty }
        return Loaded(leadDays: manifest.leadDays, themes: manifest.themes.compactMap(\.theme))
    }

    /// The manifest bundled with this app (folder `Festivals`), read once.
    static let bundled: Loaded = {
        guard let url = directory?.appendingPathComponent("festivals.json"),
              let data = try? Data(contentsOf: url) else { return .empty }
        return decode(data)
    }()

    /// Set before first use to read the manifest from another folder (previews, the lab app).
    static var directoryOverride: URL?

    static var directory: URL? {
        // The iOS app reads the manifest from the keyboard extension it embeds.
        directoryOverride ?? Bundle.main.url(forResource: "Festivals", withExtension: nil)
            ?? Bundle.main.builtInPlugInsURL?.appendingPathComponent("MeowpuutKeyboard.appex/Festivals")
    }

    // MARK: Days

    /// The Gregorian calendar in the device's time zone. Festival dates are
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

    /// Days since 1970-01-01 of a yyyy-MM-dd day (proleptic Gregorian).
    static func number(_ day: String) -> Int {
        let p = day.split(separator: "-").compactMap { Int($0) }
        let y = p[1] <= 2 ? p[0] - 1 : p[0], m = p[1], d = p[2]
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
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

    /// An id or image name: 1 to 64 ASCII letters, digits, `_` or `-`, so
    /// it can become a file name on every platform.
    static func isName(_ text: String) -> Bool {
        (1...64).contains(text.utf8.count) && text.utf8.allSatisfy { c in
            (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c == 0x5F || c == 0x2D
        }
    }

    // MARK: Which theme

    /// The theme worn on `today` (yyyy-MM-dd), from the date and the
    /// switches alone. Each theme is worn from `leadDays` before its first
    /// day through its last. Where several are, in this order:
    ///
    /// 1. A festival on its main day. Every festival gets its main day,
    ///    also inside a longer festival, which resumes the day after.
    /// 2. A festival on its other own days. The one that began last wins
    ///    (a short festival inside a long one is worn whole); then the one
    ///    that ends first.
    /// 3. A festival in its lead-up week: the one that begins soonest.
    ///
    /// Festivals that share a main day and stand on the same step take
    /// turns by day, in list order (中秋 and 秋夕).
    static func theme(onDay today: String, in loaded: Loaded = bundled, wardrobe: FestivalWardrobe) -> FestivalTheme? {
        guard wardrobe.on else { return nil }
        let now = number(today)
        var pool: [(step: Int, theme: FestivalTheme, days: FestivalTheme.Days)] = []
        for theme in loaded.themes where !wardrobe.skipped.contains(theme.id) {
            for days in theme.days where number(days.first) - loaded.leadDays <= now && today <= days.last {
                pool.append((today == days.main ? 1 : today >= days.first ? 2 : 3, theme, days))
            }
        }
        guard let step = pool.map(\.step).min() else { return nil }
        let standing = pool.filter { $0.step == step }
        let leader = standing.min { a, b in
            switch step {
            case 2: return (b.days.first, a.days.last) < (a.days.first, b.days.last)
            case 3: return a.days.first < b.days.first
            default: return false
            }
        }!
        let turns = standing.filter { $0.days.main == leader.days.main }
        return turns[now % turns.count].theme
    }

    static func theme(on date: Date = Date(), in loaded: Loaded = bundled, wardrobe: FestivalWardrobe,
                      calendar: Calendar = Festivals.gregorian) -> FestivalTheme? {
        theme(onDay: day(date, calendar: calendar), in: loaded, wardrobe: wardrobe)
    }

    /// Themes whose worn range has begun at least once between `since` and
    /// `today`, in manifest order: the ones the settings may list. A
    /// festival still to come is not shown anywhere.
    static func met(since: String, today: String, in loaded: Loaded = bundled) -> [FestivalTheme] {
        loaded.themes.filter { theme in
            theme.days.contains { number($0.first) - loaded.leadDays <= number(today) && $0.last >= since }
        }
    }
}

// MARK: Trim geometry

/// Where a theme's trim goes around the candidate glass. The trim is drawn
/// for a capsule: its rim is laid on the glass outline, scaled as a whole
/// (never stretched vertically), with only the middle of the image stretched
/// sideways. The trim is decoration: it takes no touches and is drawn in
/// front of the glass and behind the candidates.
struct FestivalTrimLayout: Equatable {
    /// The trim image's rect in the bar.
    var frame: CGRect
    /// Bar points per trim point.
    var scale: CGFloat
    /// Whether the stretched middle (the long straight rim) is drawn. The
    /// wave and the cloud have edges of their own there, so only the ends are.
    var middle: Bool
    /// Where text stays clear of the ornaments (safeText, in bar points).
    var textArea: CGRect

    /// The trim's width in its own points; the middle slice stretches to it.
    var width: CGFloat { frame.width / scale }

    /// Where the capsule the trim was drawn for sits on this glass shape.
    static func capsule(of glass: CGRect, shape: GlassShape) -> CGRect {
        let band = contentBand(shape, height: glass.height)
        return CGRect(x: glass.minX, y: glass.minY + band.top, width: glass.width, height: band.bottom - band.top)
    }

    // The same bands as CandidateGlassShape.contentBand (GlassShapes.swift),
    // which needs SwiftUI; kept here so the geometry builds without it.
    static func contentBand(_ shape: GlassShape, height: CGFloat) -> (top: CGFloat, bottom: CGFloat) {
        switch shape {
        case .capsule: return (0, height)
        case .wave: return (0.065 * height, height * (1 - 0.065))
        case .cloud: return (0.16 * height, height)
        }
    }

    /// Nil when the bar is too short for the two ends side by side.
    static func place(_ trim: FestivalTheme.Trim, glass: CGRect, shape: GlassShape) -> FestivalTrimLayout? {
        let target = capsule(of: glass, shape: shape)
        let rimHeight = trim.size.height - trim.rim.top - trim.rim.bottom
        guard target.height > 0, target.width > 0, rimHeight > 0 else { return nil }
        let k = target.height / rimHeight
        let frame = CGRect(x: target.minX - trim.rim.left * k, y: target.minY - trim.rim.top * k,
                           width: target.width + (trim.rim.left + trim.rim.right) * k, height: trim.size.height * k)
        guard frame.width / k >= trim.caps.left + trim.caps.right else { return nil }
        let s = trim.safeText
        let text = CGRect(x: frame.minX + s.left * k, y: frame.minY + s.top * k,
                          width: frame.width - (s.left + s.right) * k, height: frame.height - (s.top + s.bottom) * k)
        return FestivalTrimLayout(frame: frame, scale: k, middle: shape == .capsule, textArea: text)
    }

    /// The three slices in the trim's own points: source rect in the image
    /// and destination rect in the frame (before `scale`).
    /// `pixels` is the image's pixels per trim point; the caps are cut on
    /// whole pixels so the slices meet without a seam.
    func slices(_ trim: FestivalTheme.Trim, pixels: CGFloat = 1) -> [(source: CGRect, destination: CGRect)] {
        let h = trim.size.height
        let left = (trim.caps.left * pixels).rounded() / pixels, right = (trim.caps.right * pixels).rounded() / pixels
        var out = [(CGRect(x: 0, y: 0, width: left, height: h), CGRect(x: 0, y: 0, width: left, height: h)),
                   (CGRect(x: trim.size.width - right, y: 0, width: right, height: h),
                    CGRect(x: width - right, y: 0, width: right, height: h))]
        if middle {
            out.insert((CGRect(x: left, y: 0, width: trim.size.width - left - right, height: h),
                        CGRect(x: left, y: 0, width: width - left - right, height: h)), at: 1)
        }
        return out
    }
}

// MARK: The ends of the bar

/// Where a theme's two ornament clusters go on the candidate bar. The bar
/// has room only at its ends: on the left between the screen edge and the
/// first candidate, on the right between the expand arrow and the screen
/// edge. Each cluster sits on the glass's lower edge, as tall as the glass
/// at most, and shrinks to fit its gap; it may hang past the glass's end.
/// A cluster that would come out under 60 % of the glass's height is left
/// out: too small to read.
enum FestivalEndsLayout {
    /// `left` is where the first candidate starts, `right` where the
    /// expand arrow's glyph ends; `width` is the bar's.
    static func place(_ ends: FestivalTheme.Ends, glass: CGRect, shape: GlassShape, width: CGFloat,
                      left: CGFloat, right: CGFloat) -> [(image: String, frame: CGRect)] {
        let band = FestivalTrimLayout.contentBand(shape, height: glass.height)
        let tallest = band.bottom - band.top
        let bottom = glass.minY + band.bottom + 1
        var out: [(String, CGRect)] = []
        if let piece = ends.left {
            let start = max(1, glass.minX - 10)
            let k = min(tallest / piece.size.height, (left - 2 - start) / piece.size.width)
            if piece.size.height * k >= 0.6 * tallest {
                out.append((piece.image, CGRect(x: start, y: bottom - piece.size.height * k,
                                                width: piece.size.width * k, height: piece.size.height * k)))
            }
        }
        if let piece = ends.right {
            let end = min(width - 1, glass.maxX + 4)
            let k = min(tallest / piece.size.height, (end - right - 2) / piece.size.width)
            if piece.size.height * k >= 0.6 * tallest {
                out.append((piece.image, CGRect(x: end - piece.size.width * k, y: bottom - piece.size.height * k,
                                                width: piece.size.width * k, height: piece.size.height * k)))
            }
        }
        return out
    }
}

// MARK: Old overlay outfits

// The cat code still passes the old per-cat overlay outfit around; the
// worn theme rides in it (`theme`, with the appearance it was resolved for)
// until the cats take FestivalTheme directly. No overlay layers ship, so
// `layers` and `imageURL` find nothing.
struct FestivalOutfit: Equatable, Identifiable {
    struct Title: Equatable { var hant: String; var hans: String }
    struct Layers: Equatable { var front: String?; var back: String?; var frames: [String: String]? }
    var id: String
    var title: Title
    var cats: [String: Layers]
    var pill: String?
    var theme: FestivalTheme?
    var dark = false

    init(theme: FestivalTheme, dark: Bool) {
        (id, title, cats, pill) = (theme.id, Title(hant: theme.names.hant, hans: theme.names.hans), [:], nil)
        (self.theme, self.dark) = (theme, dark)
    }

    /// The selected capsule's lower colour for this appearance; the pill
    /// draws its top from it. Increase Contrast keeps the palette's own pill.
    var pillRGB: (red: Double, green: Double, blue: Double)? {
        guard let colors = theme?.colors, let hex = (dark ? colors.dark : colors.light).last,
              let value = UInt32(hex.dropFirst(), radix: 16), hex.count == 7 else { return nil }
        return (Double(value >> 16 & 0xFF) / 255, Double(value >> 8 & 0xFF) / 255, Double(value & 0xFF) / 255)
    }

    func layers(for cat: String) -> Layers? { cats[cat] }
}

extension Festivals {
    static func active() -> FestivalOutfit? { nil }
    static func imageURL(outfit: FestivalOutfit, file: String) -> URL? { nil }
}
