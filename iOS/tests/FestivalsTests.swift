import Foundation

// The festival wardrobe (docs/FESTIVALS.md): the shipped manifest, the
// festival days against the system calendars, which theme is worn on a
// given day, the themes already met, and the trim's three-slice geometry.

private var failures = 0
private var checks = 0

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    checks += 1
    if !condition {
        print("FAIL: \(message()) (FestivalsTests.swift:\(line))")
        failures += 1
    }
}

@main
struct FestivalsTests {
    static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    static func main() {
        let loaded = manifest()
        dates(loaded)
        colours(loaded)
        selection(loaded)
        met(loaded)
        worn(loaded)
        decoding()
        geometry(loaded)
        if failures > 0 { print("FAIL: \(failures) of \(checks) checks"); exit(1) }
        print("PASS: \(checks) checks: manifest, dates, colours, selection, met, decoding, trim geometry")
    }

    // MARK: The shipped manifest

    static func manifest() -> Festivals.Loaded {
        let paths = ["assets/festivals/festivals.json", "iOS/Festivals/festivals.json", "Resources/Festivals/festivals.json",
                     "Android/app/src/main/assets/festivals/festivals.json", "Windows/UI/Assets/festivals/festivals.json"]
        let files = paths.map { try? Data(contentsOf: root.appendingPathComponent($0)) }
        expect(files.allSatisfy { $0 != nil && $0 == files[0] }, "every client ships the same manifest")
        let loaded = Festivals.decode(files[0] ?? Data())
        let listed = ((try? JSONSerialization.jsonObject(with: files[0] ?? Data())) as? [String: Any])?["themes"] as? [Any]
        expect(loaded.themes.count == 24 && listed?.count == 24, "24 themes, every one decodes")
        expect(loaded.leadDays == 7, "worn from seven days before")
        expect(Set(loaded.themes.map(\.id)).count == 24, "ids are unique")
        for theme in loaded.themes {
            expect(theme.days.count == 5 && theme.days.map { String($0.first.prefix(4)) } == ["2026", "2027", "2028", "2029", "2030"],
                   "\(theme.id): one entry for each year 2026–2030")
            expect([theme.names.hant, theme.names.hans, theme.names.ja, theme.names.en].allSatisfy { !$0.isEmpty }, "\(theme.id): four names")
            expect(theme.mimimo.size == CGSize(width: 64, height: 80) && theme.momo.anchor == CGPoint(x: 0.5, y: 0.92),
                   "\(theme.id): cat size and foot anchor")
            expect(theme.trim.size == CGSize(width: 360, height: 80) && theme.trim.caps.left > 50 && theme.trim.caps.left < 90,
                   "\(theme.id): trim size and caps")
            expect(theme.trim.rim.top > 5 && theme.trim.rim.top < 20 && theme.trim.rim.bottom > 5 && theme.trim.rim.bottom < 20,
                   "\(theme.id): rim measured")
            for part in ["mimimo", "momo", "frame"] {
                let name = "\(theme.id)_\(part)"
                let set = root.appendingPathComponent("iOS/Festivals.xcassets/\(name).imageset")
                expect(FileManager.default.fileExists(atPath: set.appendingPathComponent("\(name)@3x.png").path)
                       && FileManager.default.fileExists(atPath: set.appendingPathComponent("\(name)@2x.png").path)
                       && !FileManager.default.fileExists(atPath: set.appendingPathComponent("\(name)@1x.png").path),
                       "\(name): @2x and @3x in the iOS catalog, no @1x")
            }
        }
        return loaded
    }

    // MARK: Festival days

    static func dates(_ loaded: Festivals.Loaded) {
        func theme(_ id: String) -> FestivalTheme { loaded.themes.first { $0.id == id }! }
        func shift(_ day: String, _ n: Int) -> String { text(Festivals.number(day) + n) }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        func date(_ day: String) -> Date { utc.date(byAdding: .day, value: Festivals.number(day), to: Date(timeIntervalSince1970: 43200))! }
        func parts(_ calendar: Calendar, _ day: String) -> DateComponents {
            calendar.dateComponents([.month, .day, .isLeapMonth], from: date(day))
        }
        func lunar(_ day: String, _ month: Int, _ d: Int, zone: String = "Asia/Shanghai") -> Bool {
            var chinese = Calendar(identifier: .chinese)
            chinese.timeZone = TimeZone(identifier: zone)!
            let p = parts(chinese, day)
            return p.month == month && p.day == d && p.isLeapMonth == false
        }
        for days in theme("spring_festival").days {
            expect(lunar(days.main, 1, 1) && shift(days.first, 1) == days.main && lunar(days.last, 1, 7), "春節 \(days.main): 除夕 to 初七")
        }
        for (id, month, day) in [("lantern_festival", 1, 15), ("dragon_boat", 5, 5), ("mid_autumn", 8, 15), ("qixi", 7, 7), ("double_ninth", 9, 9)] {
            for days in theme(id).days { expect(days.first == days.last && lunar(days.main, month, day), "\(id) \(days.main): lunar \(month)/\(day)") }
        }
        for days in theme("chuseok").days {
            expect(lunar(days.first, 8, 14, zone: "Asia/Seoul") && lunar(days.main, 8, 15, zone: "Asia/Seoul") && lunar(days.last, 8, 16, zone: "Asia/Seoul"),
                   "秋夕 \(days.main): 八月十四 to 十六 in Korean time")
        }
        var hebrew = Calendar(identifier: .hebrew)
        hebrew.timeZone = utc.timeZone
        for days in theme("hanukkah").days {
            let p = parts(hebrew, days.first)
            expect(p.month == 3 && p.day == 25 && days.main == days.first && shift(days.first, 7) == days.last, "光明節 \(days.first): 25 Kislev, eight days")
        }
        var islamic = Calendar(identifier: .islamicUmmAlQura)
        islamic.timeZone = utc.timeZone
        for days in theme("eid").days {
            let p = parts(islamic, days.first)
            expect(p.month == 10 && p.day == 1 && days.main == days.first && shift(days.first, 2) == days.last, "開齋節 \(days.first): 1–3 Shawwal")
        }
        var persian = Calendar(identifier: .persian)
        persian.timeZone = TimeZone(identifier: "Asia/Tehran")!
        for days in theme("nowruz").days {
            let p = parts(persian, days.main)
            expect(p.month == 1 && p.day == 1, "諾魯孜節 \(days.main): 1 Farvardin")
        }
        for days in theme("easter").days {
            let year = Int(days.main.prefix(4))!
            let a = year % 19, b = year / 100, c = year % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3
            let h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451
            let easter = String(format: "%04d-%02d-%02d", year, (h + l - 7 * m + 114) / 31, (h + l - 7 * m + 114) % 31 + 1)
            expect(days.main == easter && days.first == days.last, "復活節 \(year): \(easter)")
        }
        for days in theme("midsummer").days {
            let weekday = utc.component(.weekday, from: date(days.main))
            let day = Int(days.main.suffix(2))!
            expect(weekday == 6 && days.main.dropFirst(5).hasPrefix("06") && (19...25).contains(day) && shift(days.main, 1) == days.last,
                   "仲夏節 \(days.main): the Friday 19–25 June, then Saturday")
        }
        for (id, monthDay) in [("tanabata", "07-07"), ("halloween", "10-31"), ("christmas", "12-25"), ("new_year", "01-01"),
                               ("valentine", "02-14"), ("white_day", "03-14"), ("hinamatsuri", "03-03"), ("childrens_day", "05-05")] {
            expect(theme(id).days.allSatisfy { $0.first == $0.last && $0.main.hasSuffix(monthDay) }, "\(id) on \(monthDay)")
        }
        expect(theme("songkran").days.allSatisfy { $0.main.hasSuffix("04-13") && $0.last.hasSuffix("04-15") }, "潑水節 13–15 April")
        // From Drik Panchang (New Delhi) and the December solstice in UTC+8 (Hong Kong Observatory, USNO).
        expect(theme("diwali").days.map(\.main) == ["2026-11-08", "2027-10-29", "2028-10-17", "2029-11-05", "2030-10-26"], "排燈節 Lakshmi Puja")
        expect(theme("holi").days.map(\.main) == ["2026-03-04", "2027-03-22", "2028-03-11", "2029-03-01", "2030-03-20"]
               && theme("holi").days.allSatisfy { shift($0.first, 1) == $0.main }, "灑紅節: Holika Dahan, then Rangwali Holi")
        expect(theme("winter_solstice").days.map(\.main) == ["2026-12-22", "2027-12-22", "2028-12-21", "2029-12-21", "2030-12-22"], "冬至")
    }

    static func text(_ number: Int) -> String {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        return Festivals.day(Date(timeIntervalSince1970: TimeInterval(number) * 86400 + 43200), calendar: utc)
    }

    // MARK: Colours

    static func colours(_ loaded: Festivals.Loaded) {
        func contrast(_ hex: String) -> Double {
            guard hex.count == 7, let v = UInt32(hex.dropFirst(), radix: 16) else { return 0 }
            func channel(_ c: UInt32) -> Double {
                let x = Double(c) / 255
                return x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4)
            }
            let l = 0.2126 * channel(v >> 16 & 0xff) + 0.7152 * channel(v >> 8 & 0xff) + 0.0722 * channel(v & 0xff)
            return 1.05 / (l + 0.05)
        }
        // docs/DESIGN.md §4: white text on the capsule.
        for theme in loaded.themes {
            let c = theme.colors
            expect(c.light.count == 2 && contrast(c.light[0]) >= 3.2 && contrast(c.light[1]) >= 4.0, "\(theme.id): light capsule contrast")
            expect(c.dark.count == 2 && c.dark.allSatisfy { contrast($0) >= 3.0 }, "\(theme.id): dark capsule contrast")
            expect(contrast(c.contrast) >= 5.0 && contrast(c.accent) > 1, "\(theme.id): Increase Contrast capsule")
        }
    }

    // MARK: Which theme is worn

    static func selection(_ loaded: Festivals.Loaded) {
        let on = FestivalWardrobe()
        func worn(_ day: String, _ wardrobe: FestivalWardrobe = FestivalWardrobe()) -> String? {
            Festivals.theme(onDay: day, in: loaded, wardrobe: wardrobe)?.id
        }
        func span(_ from: String, _ to: String) -> [String] {
            (Festivals.number(from)...Festivals.number(to)).map(text)
        }
        // The next three from 2026-11-12, by hand: seven days before the first day through the last.
        expect(worn("2026-11-12") == nil && worn("2026-11-27") == nil, "nothing on an ordinary day")
        expect(span("2026-11-28", "2026-12-12").allSatisfy { worn($0) == "hanukkah" } && worn("2026-12-13") == nil,
               "光明節 2026: lead-up from 11-28 (first day 12-05) through 12-12")
        expect(span("2026-12-15", "2026-12-22").allSatisfy { worn($0) == "winter_solstice" }, "冬至 2026 takes its lead-up and its day")
        expect(span("2026-12-23", "2026-12-25").allSatisfy { worn($0) == "christmas" }, "聖誕節 2026 after 冬至, through 12-25")
        expect(span("2026-12-26", "2027-01-01").allSatisfy { worn($0) == "new_year" }, "新年 lead-up and day")
        expect(span("2027-01-29", "2027-02-12").allSatisfy { worn($0) == "spring_festival" } && worn("2027-01-28") == nil,
               "春節 2027: 除夕 02-05 minus seven = 01-29, through 初七 02-12")
        // Without the solstice, 聖誕節 has its whole lead-up week.
        let noSolstice = FestivalWardrobe(on: true, skipped: ["winter_solstice"])
        expect(span("2026-12-18", "2026-12-25").allSatisfy { worn($0, noSolstice) == "christmas" } && worn("2026-12-17", noSolstice) == nil,
               "聖誕節 2026 alone: 12-18 through 12-25")

        // A one-day festival inside a longer one gets its day; the longer one resumes.
        expect(worn("2027-10-29") == "diwali" && worn("2027-10-31") == "halloween" && worn("2027-10-30") == "diwali", "排燈節 2027, then 萬聖節's own day")
        expect(worn("2029-02-14") != "spring_festival" && worn("2029-02-15") == "eid" && worn("2029-02-17") == "spring_festival",
               "2029: 開齋節 and 情人節 share 02-14 inside 春節; 開齋節 keeps its days, 春節 resumes")
        expect(Set(["2029-02-14"].compactMap { worn($0) }).isSubset(of: ["eid", "valentine"]), "02-14-2029 goes to one of the two sharing it")
        expect(worn("2030-02-03") == "spring_festival" && worn("2030-02-04") == "eid" && worn("2030-02-06") == "eid" && worn("2030-02-07") == "spring_festival",
               "2030: 開齋節 inside 春節")
        expect(worn("2027-03-21") == "nowruz" && worn("2027-03-22") == "holi" && worn("2027-03-28") == "easter", "March 2027: each on its own main day")
        expect(worn("2028-04-13") == "songkran" && worn("2028-04-15") == "songkran" && worn("2028-04-16") == "easter", "2028: 潑水節 beats 復活節's lead-up")
        // Own days beat a lead-up week; the nearest festival leads the lead-up.
        expect(worn("2027-09-14") == "chuseok", "秋夕's first day beats 中秋's lead-up")
        expect(worn("2027-03-02") == "hinamatsuri" && worn("2027-03-04") == "eid", "lead-up goes to the nearest festival")
        // 中秋 and 秋夕 share a main day: they take turns by day, one change a day at most.
        for year in ["2026-09-25", "2027-09-15", "2028-10-03", "2029-09-22", "2030-09-12"] {
            let shared = span(text(Festivals.number(year) - 7), text(Festivals.number(year) - 2))
            let ids = shared.compactMap { worn($0) }
            expect(Set(ids) == ["mid_autumn", "chuseok"] && zip(ids, ids.dropFirst()).allSatisfy { $0 != $1 },
                   "\(year.prefix(4)): 中秋 and 秋夕 alternate in their shared lead-up")
            expect(["mid_autumn", "chuseok"].contains(worn(year) ?? ""), "\(year.prefix(4)): one of them on the main day")
        }
        // Every festival is worn on its main day, unless another festival has the same main day.
        for theme in loaded.themes {
            for days in theme.days {
                let sharing = loaded.themes.filter { other in other.id != theme.id && other.days.contains { $0.main == days.main } }
                if sharing.isEmpty { expect(worn(days.main) == theme.id, "\(theme.id) worn on its main day \(days.main)") }
            }
        }
        // Switches: off means the next candidate; the master off means nothing ever.
        expect(worn("2027-10-31", FestivalWardrobe(on: true, skipped: ["halloween"])) == "diwali", "a skipped theme gives way to the next")
        expect(worn("2027-02-01", FestivalWardrobe(on: true, skipped: ["spring_festival"])) == nil, "skipping the only one leaves the plain cats")
        let everyDay = span("2026-01-01", "2030-12-31")
        expect(everyDay.allSatisfy { worn($0, FestivalWardrobe(on: false)) == nil }, "master off: plain cats every day")
        // Only by the calendar: every day of 2026–2030 has at most one theme and it is inside its own worn range.
        expect(everyDay.allSatisfy { day in
            guard let id = worn(day), let theme = loaded.themes.first(where: { $0.id == id }) else { return true }
            return theme.days.contains { Festivals.number($0.first) - 7 <= Festivals.number(day) && day <= $0.last }
        }, "a theme is only worn inside its own range")
        expect(on.on && on.skipped.isEmpty, "on by default, nothing skipped")

        // The device's own date decides: the same moment is different days in two places.
        var taipei = Calendar(identifier: .gregorian), honolulu = Calendar(identifier: .gregorian)
        taipei.timeZone = TimeZone(identifier: "Asia/Taipei")!
        honolulu.timeZone = TimeZone(identifier: "Pacific/Honolulu")!
        let moment = taipei.date(from: DateComponents(year: 2026, month: 11, day: 28, hour: 1))!
        expect(Festivals.theme(on: moment, in: loaded, wardrobe: on, calendar: taipei)?.id == "hanukkah"
               && Festivals.theme(on: moment, in: loaded, wardrobe: on, calendar: honolulu) == nil, "local day, not UTC")
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = taipei.timeZone
        expect(Festivals.day(moment, calendar: buddhist).hasPrefix("2569") && Festivals.gregorian.identifier == .gregorian,
               "the default calendar is Gregorian whatever the device uses")
    }

    // MARK: Already met

    static func met(_ loaded: Festivals.Loaded) {
        func ids(_ since: String, _ today: String) -> [String] { Festivals.met(since: since, today: today, in: loaded).map(\.id) }
        expect(ids("2026-11-12", "2026-11-12").isEmpty, "first day: nothing met yet, nothing listed")
        expect(ids("2026-11-12", "2026-11-28") == ["hanukkah"], "光明節 is met once its lead-up begins")
        expect(ids("2026-11-12", "2027-01-29") == ["spring_festival", "hanukkah", "christmas", "new_year", "winter_solstice"],
               "met in manifest order, including 聖誕節 whose own lead-up was worn by 冬至")
        expect(ids("2026-11-12", "2026-12-31").contains("christmas") && !ids("2026-11-12", "2026-12-31").contains("halloween"),
               "a festival that ended before the first launch is not met")
        expect(ids("2026-11-08", "2026-11-12") == ["diwali"], "one running at the first launch counts")
    }

    // MARK: Every day of 2026–2030

    /// The theme worn on every day of 2026–2030, with all themes on and with
    /// four switched off. Android and Windows compare their own selection
    /// with this file, so the three cannot drift apart. After a deliberate
    /// change: FESTIVALS_WRITE_WORN=1 bash scripts/test-ios.sh --only Festivals
    static let wornFile = root.appendingPathComponent("Android/app/src/test/resources/festivals/worn-2026-2030.txt")
    static let wornSkipped: Set<String> = ["winter_solstice", "chuseok", "spring_festival", "halloween"]

    static func worn(_ loaded: Festivals.Loaded) {
        var lines = ["# day, worn with every theme on, worn with these off: \(wornSkipped.sorted().joined(separator: " "))"]
        for n in Festivals.number("2026-01-01")...Festivals.number("2030-12-31") {
            let day = text(n)
            let all = Festivals.theme(onDay: day, in: loaded, wardrobe: FestivalWardrobe())?.id ?? "-"
            let some = Festivals.theme(onDay: day, in: loaded, wardrobe: FestivalWardrobe(skipped: wornSkipped))?.id ?? "-"
            lines.append("\(day) \(all) \(some)")
        }
        let written = lines.joined(separator: "\n") + "\n"
        if ProcessInfo.processInfo.environment["FESTIVALS_WRITE_WORN"] != nil { try? written.write(to: wornFile, atomically: true, encoding: .utf8) }
        expect((try? String(contentsOf: wornFile, encoding: .utf8)) == written, "\(wornFile.lastPathComponent) matches the selection")
    }

    // MARK: Decoding

    static func decoding() {
        expect(Festivals.decode(Data("not json".utf8)).isEmpty && Festivals.decode(Data()).isEmpty
               && Festivals.decode(Data(#"{"version": 1, "outfits": []}"#.utf8)).isEmpty, "unreadable or old manifests mean no themes")
        let fixture = try? Data(contentsOf: root.appendingPathComponent("Android/app/src/test/resources/festivals/fixture.json"))
        let loaded = Festivals.decode(fixture ?? Data())
        expect(loaded.themes.map(\.id) == ["alpha", "beta", "gamma", "delta"] && loaded.leadDays == 7,
               "the fixture: a theme with a missing field or a path for an image name is skipped, the rest load")
        let alpha = loaded.themes.first
        expect(alpha?.trim.caps == FestivalTheme.Insets(top: 0, left: 70, bottom: 0, right: 70)
               && alpha?.trim.safeText.left == 90 && alpha?.momo.image == "alpha_momo" && alpha?.names.ja == "アルファ"
               && alpha?.days == [FestivalTheme.Days(first: "2027-02-05", main: "2027-02-06", last: "2027-02-12")]
               && alpha?.colors.dark == ["#AA0000", "#990000"], "the fixture's fields")
        // The same cases are in Android's LogicTest and Windows' layout_test.
        func worn(_ day: String, _ wardrobe: FestivalWardrobe = FestivalWardrobe()) -> String {
            Festivals.theme(onDay: day, in: loaded, wardrobe: wardrobe)?.id ?? "-"
        }
        let days = ["2027-01-28", "2027-01-29", "2027-02-02", "2027-02-06", "2027-02-07", "2027-02-09", "2027-02-10",
                    "2027-02-12", "2027-02-13", "2027-02-14", "2027-02-15"]
        expect(days.map { worn($0) } == ["-", "alpha", "alpha", "alpha", "alpha", "delta", "alpha", "alpha", "beta", "gamma", "-"],
               "the fixture's days: \(days.map { worn($0) })")
        expect(worn("2027-02-13", FestivalWardrobe(skipped: ["beta"])) == "gamma" && worn("2027-02-14", FestivalWardrobe(skipped: ["beta"])) == "gamma"
               && worn("2027-02-06", FestivalWardrobe(skipped: ["alpha"])) == "delta" && worn("2027-02-06", FestivalWardrobe(on: false)) == "-",
               "the fixture with switches")
        func met(_ since: String, _ today: String) -> [String] { Festivals.met(since: since, today: today, in: loaded).map(\.id) }
        expect(met("2027-02-01", "2027-02-01") == ["alpha"] && met("2027-02-01", "2027-02-07") == ["alpha", "beta", "gamma", "delta"]
               && met("2027-02-13", "2027-02-13") == ["beta", "gamma"] && met("2027-03-01", "2028-02-07") == ["beta"],
               "the fixture's met lists")
        for bad in ["2027-2-3", "2027-02-30", "2027-13-01", "27-02-03", ""] { expect(!Festivals.isDay(bad), "\(bad) is not a day") }
        expect(Festivals.isDay("2028-02-29") && Festivals.number("1970-01-01") == 0 && Festivals.number("2027-03-01") - Festivals.number("2027-02-28") == 1
               && Festivals.number("2028-03-01") - Festivals.number("2028-02-28") == 2, "day numbers")
    }

    // MARK: Trim geometry

    static func geometry(_ loaded: Festivals.Loaded) {
        // The ornament clusters at the ends of a 390 pt phone bar: glass, first candidate at 27.4, arrow glyph ending at 340.2.
        let bar = CGRect(x: 15.5, y: 33.4, width: 368.6, height: 45.6)
        for theme in loaded.themes {
            guard let ends = theme.ends else { expect(false, "\(theme.id): ornaments cut by the importer"); continue }
            for shape in [GlassShape.capsule, .wave, .cloud] {
                let placed = FestivalEndsLayout.place(ends, glass: bar, shape: shape, width: 390, left: 27.4, right: 340.2)
                expect(placed.count == 1 && placed.allSatisfy { $0.frame.minX >= 342 && $0.frame.maxX <= 389 && $0.frame.maxY <= bar.maxY + 1.01
                                                                 && $0.frame.height >= 0.6 * 0.87 * bar.height && $0.frame.height <= bar.height + 0.01 },
                       "\(theme.id), \(shape): one cluster, between the arrow and the screen edge, on the glass, readable: \(placed.map(\.frame))")
            }
        }
        let tiny = FestivalTheme.Ends(left: .init(image: "l", size: CGSize(width: 50, height: 55)), right: nil)
        expect(FestivalEndsLayout.place(tiny, glass: bar, shape: .capsule, width: 390, left: 27.4, right: 340.2).isEmpty,
               "a cluster that would be too small to read at the left end is left out")
        let trim = FestivalTheme.Trim(image: "t", size: CGSize(width: 360, height: 80),
                                      caps: .init(top: 0, left: 70, bottom: 0, right: 70),
                                      safeText: .init(top: 18, left: 90, bottom: 18, right: 90),
                                      rim: .init(top: 10, left: 10, bottom: 10, right: 10))
        // iPhone 16 bar: glass 371 × 46 at (15.6, 34).
        let glass = CGRect(x: 15.6, y: 34, width: 371.4, height: 46)
        guard let capsule = FestivalTrimLayout.place(trim, glass: glass, shape: .capsule) else { return expect(false, "capsule trim placed") }
        let k = 46.0 / 60
        expect(abs(capsule.scale - k) < 1e-9 && abs(capsule.frame.height - 80 * k) < 1e-9, "scaled as a whole so the rim meets the glass; no vertical stretch")
        expect(abs(capsule.frame.minY - (34 - 10 * k)) < 1e-9 && abs(capsule.frame.minX - (15.6 - 10 * k)) < 1e-9
               && abs(capsule.frame.maxX - (glass.maxX + 10 * k)) < 1e-9, "the rim lies on the glass outline")
        let slices = capsule.slices(trim, pixels: 3)
        expect(slices.count == 3 && slices[0].destination.width == 70 && slices[2].destination.width == 70
               && abs(slices[1].destination.width - (capsule.width - 140)) < 1e-9 && abs(slices[2].destination.maxX - capsule.width) < 1e-9,
               "three slices: caps keep their width, the middle stretches")
        expect(slices.allSatisfy { $0.source.height == 80 && $0.destination.height == 80 }, "slices are never stretched vertically")
        expect(abs(capsule.textArea.minX - (capsule.frame.minX + 90 * k)) < 1e-9 && abs(capsule.textArea.height - 44 * k) < 1e-9, "safe text area")
        let fractional = FestivalTheme.Trim(image: "t", size: trim.size, caps: .init(top: 0, left: 64.78, bottom: 0, right: 64.78),
                                            safeText: trim.safeText, rim: trim.rim)
        let cut = FestivalTrimLayout.place(fractional, glass: glass, shape: .capsule)!.slices(fractional, pixels: 3)
        expect(cut[0].source.width * 3 == (64.78 * 3).rounded() && cut[0].destination.maxX == cut[1].destination.minX
               && cut[1].destination.maxX == cut[2].destination.minX, "caps cut on whole pixels, slices meet without a gap")
        // Wave and cloud: fitted to the capsule inside their outline; only the ends drawn.
        let wave = FestivalTrimLayout.place(trim, glass: glass, shape: .wave)!
        expect(!wave.middle && wave.slices(trim).count == 2 && abs(wave.frame.height - 80 * 46 * 0.87 / 60) < 1e-9, "wave: ends only, fitted inside the waves")
        let cloud = FestivalTrimLayout.place(trim, glass: glass, shape: .cloud)!
        expect(!cloud.middle && abs(cloud.frame.minY - (34 + 0.16 * 46 - 10 * cloud.scale)) < 1e-9, "cloud: ends only, on the stadium under the bumps")
        // Landscape and iPad bars, and a bar too short for both ends.
        expect(FestivalTrimLayout.place(trim, glass: CGRect(x: 4, y: 12, width: 700, height: 36), shape: .capsule)?.scale == 0.6, "landscape")
        expect(FestivalTrimLayout.place(trim, glass: CGRect(x: 4, y: 12, width: 1000, height: 53), shape: .capsule) != nil, "iPad")
        expect(FestivalTrimLayout.place(trim, glass: CGRect(x: 0, y: 0, width: 80, height: 46), shape: .capsule) == nil, "too short: no trim")
        // Every shipped trim fits a 320 pt phone.
        let small = CGRect(x: 13, y: 30, width: 300, height: 41.4)
        expect(loaded.themes.allSatisfy { FestivalTrimLayout.place($0.trim, glass: small, shape: .capsule) != nil }, "every trim fits a 320 pt phone")
    }
}
