import Foundation

// Holiday outfits (docs/FESTIVALS.md): date ranges, order and the empty manifest.

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (FestivalsTests.swift:\(line))")
        exit(1)
    }
}

@main
struct FestivalsTests {
    static func date(_ text: String, hour: Int = 12, in calendar: Calendar) -> Date {
        let parts = text.split(separator: "-").map { Int($0)! }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: hour))!
    }

    static func main() {
        var taipei = Calendar(identifier: .gregorian)
        taipei.timeZone = TimeZone(identifier: "Asia/Taipei")!

        // The manifest that ships: no outfits, and that is normal.
        let empty = Festivals.decode(Data(#"{"version": 1, "outfits": []}"#.utf8))
        expect(empty.isEmpty, "an empty manifest has no outfits")
        expect(Festivals.active(in: empty, on: date("2027-02-05", in: taipei), calendar: taipei) == nil, "no outfit on any day")
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        for path in ["Festivals/festivals.json", "../assets/festivals/festivals.json"] {
            let url = root.appendingPathComponent(path)
            if let data = try? Data(contentsOf: url) {
                expect(String(data: data, encoding: .utf8)?.contains("\"version\"") == true, "\(path) is a manifest")
                let outfits = Festivals.decode(data)
                expect(outfits.allSatisfy { $0.hasValidDates && !$0.id.isEmpty }, "\(path) has valid outfits")
                // Nothing in the file was skipped for a bad date or a missing field.
                let listed = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["outfits"] as? [Any]
                expect(listed?.count == outfits.count, "\(path): every outfit decodes")
                expect(outfits.allSatisfy { $0.pill == nil || $0.pillRGB != nil }, "\(path): pill colours are #RRGGBB")
            }
        }
        // Anything unreadable counts as empty rather than failing.
        expect(Festivals.decode(Data("not json".utf8)).isEmpty && Festivals.decode(Data(#"{"version": 2, "outfits": []}"#.utf8)).isEmpty
               && Festivals.decode(Data()).isEmpty, "a broken or newer manifest shows the plain cats")

        let manifest = #"""
        {"version": 1, "outfits": [
          {"id": "lunar-new-year-2027", "title": {"hant": "春節", "hans": "春节"}, "start": "2027-02-03", "end": "2027-02-12",
           "cats": {"mimimo": {"front": "mimimo_front.png"},
                    "momo": {"front": "momo_front.png", "back": "momo_back.png", "frames": {"happy": "momo_happy.png"}}},
           "pill": "#C8463C"},
          {"id": "lantern-2027", "title": {"hant": "元宵", "hans": "元宵"}, "start": "2027-02-10", "end": "2027-02-21",
           "cats": {"momo": {"back": "lantern.png"}}},
          {"id": "one-day", "title": {"hant": "貓之日", "hans": "猫之日"}, "start": "2027-02-22", "end": "2027-02-22", "cats": {}}
        ]}
        """#
        let outfits = Festivals.decode(Data(manifest.utf8))
        expect(outfits.map(\.id) == ["lunar-new-year-2027", "lantern-2027", "one-day"], "outfits decode in manifest order")
        func active(_ day: String, hour: Int = 12) -> String? {
            Festivals.active(in: outfits, on: date(day, hour: hour, in: taipei), calendar: taipei)?.id
        }
        // Both ends are included, from the first minute to the last.
        expect(active("2027-02-02", hour: 23) == nil, "the day before: nothing")
        expect(active("2027-02-03", hour: 0) == "lunar-new-year-2027", "the first day counts from midnight")
        expect(active("2027-02-12", hour: 23) == "lunar-new-year-2027", "the last day counts until midnight")
        // Where two overlap, the one listed first wins; after it ends the other shows.
        expect(active("2027-02-10") == "lunar-new-year-2027" && active("2027-02-12") == "lunar-new-year-2027", "overlap: the earlier entry")
        expect(active("2027-02-13") == "lantern-2027" && active("2027-02-21") == "lantern-2027", "then the second outfit")
        expect(active("2027-02-22") == "one-day" && active("2027-02-23") == nil, "a one-day outfit, then nothing")
        expect(active("2028-02-05") == nil, "dates are for one year only")

        // The device's own date decides: the same moment is different days in two places.
        var honolulu = Calendar(identifier: .gregorian)
        honolulu.timeZone = TimeZone(identifier: "Pacific/Honolulu")!
        let moment = date("2027-02-03", hour: 1, in: taipei)
        expect(Festivals.active(in: outfits, on: moment, calendar: taipei)?.id == "lunar-new-year-2027", "already the 3rd in Taipei")
        expect(Festivals.active(in: outfits, on: moment, calendar: honolulu) == nil, "still the 2nd in Honolulu")
        expect(Festivals.day(moment, calendar: taipei) == "2027-02-03" && Festivals.day(moment, calendar: honolulu) == "2027-02-02", "local day text")

        // Layers.
        let newYear = outfits[0]
        expect(newYear.layers(for: "momo")?.frames?["happy"] == "momo_happy.png" && newYear.layers(for: "momo")?.back == "momo_back.png", "frames and layers per cat")
        expect(newYear.layers(for: "mimimo")?.back == nil && outfits[1].layers(for: "mimimo") == nil, "a cat without layers keeps its own look")
        expect(newYear.pill == "#C8463C" && outfits[1].pill == nil && newYear.title.hans == "春节", "optional capsule colour and both titles")
        expect(newYear.pillRGB.map { Int(($0.red * 255).rounded()) } == 0xC8 && newYear.pillRGB.map { Int(($0.blue * 255).rounded()) } == 0x3C
               && outfits[1].pillRGB == nil, "the capsule colour as numbers")

        // Outfit dates are Gregorian. A device set to the Buddhist or Japanese
        // calendar counts years differently; the default must not follow it.
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = TimeZone(identifier: "Asia/Taipei")!
        let noon = date("2027-02-05", in: taipei)
        expect(Festivals.day(noon, calendar: buddhist) == "2570-02-05", "the Buddhist calendar's year is 2570")
        expect(Festivals.gregorian.identifier == .gregorian && Festivals.gregorian.timeZone == TimeZone.current, "the default calendar is Gregorian, in the device's time zone")
        expect(Festivals.day(noon).hasPrefix("2027-02-0") && Festivals.active(in: outfits, on: noon)?.id == "lunar-new-year-2027",
               "the default finds the outfit whatever calendar the device uses")

        // Dates must be real days written yyyy-MM-dd. A badly written outfit
        // is left out, and so is one with a missing field; the rest still load.
        for good in ["2027-02-03", "2028-02-29", "2027-12-31"] { expect(FestivalOutfit.isDay(good), "\(good) is a day") }
        for bad in ["2027-2-3", "2027-02-30", "2027-13-01", "2027/02/03", "27-02-03", "2027-02-03 ", ""] { expect(!FestivalOutfit.isDay(bad), "\(bad) is not") }
        let mixed = #"""
        {"version": 1, "outfits": [
          {"id": "short-date", "title": {"hant": "甲", "hans": "甲"}, "start": "2027-2-3", "end": "2027-2-9", "cats": {}},
          {"id": "no-title", "start": "2027-02-03", "end": "2027-02-09", "cats": {}},
          {"id": "backwards", "title": {"hant": "乙", "hans": "乙"}, "start": "2027-02-09", "end": "2027-02-03", "cats": {}},
          {"id": "good", "title": {"hant": "丙", "hans": "丙"}, "start": "2027-02-03", "end": "2027-02-09", "cats": {}, "pill": "red"}
        ]}
        """#
        let kept = Festivals.decode(Data(mixed.utf8))
        expect(kept.map(\.id) == ["good"] && kept[0].pillRGB == nil, "broken outfits are skipped, the good one stays, a bad colour is ignored")
        print("PASS: empty manifest, inclusive date ranges, overlap order, local dates, the device calendar, layers and badly written outfits")
    }
}
