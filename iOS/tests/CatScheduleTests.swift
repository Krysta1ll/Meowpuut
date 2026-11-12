import Foundation
import CoreGraphics

// Which arrangement the cats are in (CatSchedule): one for each day by the
// device's own date, never the day before's, chosen among the arrangements
// that fit the bar; or the one the user pinned.

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (CatScheduleTests.swift:\(line))")
        exit(1)
    }
}

@main
struct CatScheduleTests {
    static func date(_ text: String, hour: Int = 12, minute: Int = 0, in calendar: Calendar) -> Date {
        let parts = text.split(separator: "-").map { Int($0)! }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: hour, minute: minute))!
    }

    static func stage(_ width: CGFloat, _ idiom: KeyboardIdiom, _ shape: GlassShape) -> CatStage {
        CandidateRegionLayout(width: width, idiom: idiom, rim: GlassEdge.band(shape, height: 1).top).stage(shape: shape)
    }

    static func main() {
        var taipei = Calendar(identifier: .gregorian)
        taipei.timeZone = TimeZone(identifier: "Asia/Taipei")!
        var honolulu = Calendar(identifier: .gregorian)
        honolulu.timeZone = TimeZone(identifier: "Pacific/Honolulu")!

        // Day numbers: whole local days, the same from the first minute to the last.
        expect(CatSchedule.day(date("2001-01-01", in: taipei), calendar: taipei) == 0, "1 January 2001 is day 0")
        let today = CatSchedule.day(date("2026-11-12", in: taipei), calendar: taipei)
        expect(today == 9446, "12 November 2026 is day 9446 (got \(today))")
        expect(CatSchedule.day(date("2026-11-12", hour: 0, in: taipei), calendar: taipei) == today
               && CatSchedule.day(date("2026-11-12", hour: 23, minute: 59, in: taipei), calendar: taipei) == today, "one number all day")
        expect(CatSchedule.day(date("2026-11-13", hour: 0, in: taipei), calendar: taipei) == today + 1, "the next at midnight")
        expect(CatSchedule.day(date("2028-02-29", in: taipei), calendar: taipei) - CatSchedule.day(date("2028-02-28", in: taipei), calendar: taipei) == 1
               && CatSchedule.day(date("2028-03-01", in: taipei), calendar: taipei) - CatSchedule.day(date("2028-02-29", in: taipei), calendar: taipei) == 1,
               "leap days count")
        // The device's own date decides: one moment is two days in two places.
        let moment = date("2026-11-12", hour: 1, in: taipei)
        expect(CatSchedule.day(moment, calendar: honolulu) == today - 1, "still the 11th in Honolulu")
        // A device set to the Buddhist calendar counts the same days.
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = taipei.timeZone
        expect(CatSchedule.day(moment, calendar: buddhist) == today, "the calendar the device shows does not matter")

        // The place in the pool: the same for a day however often it is asked,
        // never the day before's, and every place comes up.
        for count in 1...12 {
            var seen = Set<Int>()
            var previous = -1
            for day in -400...4000 {
                let index = CatSchedule.index(day: day, count: count)
                expect(index >= 0 && index < count, "day \(day) of \(count): index \(index)")
                expect(index == CatSchedule.index(day: day, count: count), "day \(day) of \(count) is stable")
                if count > 1 && day > -400 { expect(index != previous, "day \(day) of \(count) repeats the day before") }
                previous = index
                seen.insert(index)
            }
            expect(seen.count == count, "all \(count) places are used")
        }
        expect(CatSchedule.index(day: 5, count: 0) == 0, "an empty pool does not crash")
        // Over a year no arrangement is left out or shown much more than the others.
        var shown = [Int: Int]()
        for day in today..<(today + 365) { shown[CatSchedule.index(day: day, count: 8), default: 0] += 1 }
        expect(shown.count == 8 && shown.values.min()! >= 22 && shown.values.max()! <= 80, "a year uses all eight: \(shown.values.sorted())")

        // The pool is the arrangements that fit the bar: all eight on a phone's
        // capsule, the peeking ones on a landscape phone, fewer on the cloud.
        func pool(_ stage: CatStage) -> [String] { CatArrangement.all.filter { $0.place(on: stage) != nil }.map(\.id) }
        let phone = stage(402, .phone, .capsule), landscape = stage(750, .phoneLandscape, .capsule), cloud = stage(402, .phone, .cloud)
        expect(pool(phone).count == 8 && pool(landscape) == ["ends", "left", "right"], "phone \(pool(phone)), landscape \(pool(landscape))")
        expect(pool(cloud).count < 8 && pool(cloud).contains("ends") && !pool(cloud).contains("lounge"), "cloud \(pool(cloud))")

        // Every day's choice comes from the pool of the bar it is shown on, and changes from one day to the next there.
        for bar in [phone, landscape, cloud, stage(320, .phone, .wave), stage(1024, .pad, .cloud)] {
            let fits = pool(bar)
            var previous = ""
            for day in today..<(today + 120) {
                let choice = CatSchedule.arrangement(setting: CatSchedule.daily, day: day, on: bar)!
                expect(fits.contains(choice.arrangement.id) && choice.places.count == 2, "day \(day): \(choice.arrangement.id) is not in \(fits)")
                expect(choice.arrangement.id == fits[CatSchedule.index(day: day, count: fits.count)], "the choice follows the date")
                expect(choice.arrangement.id != previous, "day \(day) shows \(previous) again")
                previous = choice.arrangement.id
            }
        }
        // The same day gives the same arrangement on two bars with the same pool (the keyboard and the app's preview).
        expect(CatSchedule.arrangement(setting: CatSchedule.daily, day: today, on: phone)!.arrangement
               == CatSchedule.arrangement(setting: CatSchedule.daily, day: today, on: stage(393, .phone, .capsule))!.arrangement,
               "the settings preview shows what the keyboard shows")

        // A pinned arrangement is shown every day; where it does not fit, the first that does.
        for day in [today, today + 1, today + 40] {
            expect(CatSchedule.arrangement(setting: "lounge", day: day, on: phone)!.arrangement.id == "lounge", "pinned")
            expect(CatSchedule.arrangement(setting: "lounge", day: day, on: landscape)!.arrangement.id == "ends", "pinned, but it does not fit: the first that does")
            expect(CatSchedule.arrangement(setting: "no-such-arrangement", day: day, on: phone)!.arrangement.id == "ends", "an unknown id")
        }
        // No room at all (a bar a few points wide): nothing is shown.
        let tiny = CatStage(size: CGSize(width: 40, height: 80), glass: CGRect(x: 2, y: 34, width: 36, height: 44), shape: .capsule,
                            unit: 1, room: 32, row: CGRect(x: 10, y: 40, width: 20, height: 32), arrow: CGRect(x: 30, y: 50, width: 6, height: 6))
        expect(CatSchedule.arrangement(setting: CatSchedule.daily, day: today, on: tiny) == nil, "a bar with no room shows no cats")
        print("PASS: day numbers by the device's date, a stable choice that never repeats the day before, pools by size class and shape, pinning and its fallback")
    }
}

private func == (a: (arrangement: CatArrangement, places: [CatPlacement])?, b: (arrangement: CatArrangement, places: [CatPlacement])?) -> Bool {
    a?.arrangement == b?.arrangement && a?.places == b?.places
}
