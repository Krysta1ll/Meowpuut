import Foundation
import CoreGraphics

// Which keys each layout has, how they are labelled, and that every point
// of every layout answers with a key (docs/DESIGN.md §6).

private func expect(_ condition: Bool, _ message: @autoclosure () -> String, line: UInt = #line) {
    if !condition {
        print("FAIL: \(message()) (KeyboardKeysTests.swift:\(line))")
        exit(1)
    }
}

private func context(_ mode: InputMode, english: Bool = false, page: Int = 0, shift: KeyboardKeyContext.Shift = .off,
                     simplified: Bool = false, globe: Bool = false, returnTitle: String = "換行",
                     accent: Bool = false, enabled: Bool = true, spaceTitle: String = "空格") -> KeyboardKeyContext {
    KeyboardKeyContext(mode: mode, english: english, page: page, shift: shift, simplified: simplified, needsGlobe: globe,
                       spaceTitle: spaceTitle, returnTitle: returnTitle, returnAccent: accent, returnEnabled: enabled)
}

private func count(_ set: KeyboardKeySet, _ matches: (KeyboardKey.Action) -> Bool) -> Int {
    set.rows.joined().filter { matches($0.action) }.count
}

@main
struct KeyboardKeysTests {
    static func main() {
        everyLayout()
        letters()
        zhuyin()
        symbols()
        labels()
        hitTesting()
        print("PASS: key sets for every mode and page, Shift labels, and a key under every point")
    }

    static func everyLayout() {
        for mode in InputMode.allCases {
            for english in [false, true] {
                for page in 0...2 {
                    for globe in [false, true] {
                        let set = KeyboardKeyBuilder.build(context(mode, english: english, page: page, globe: globe))
                        let name = "\(mode) english=\(english) page=\(page)"
                        expect(set.rows.count == set.specs.count, "\(name): one layout row for each row of keys")
                        for (row, spec) in zip(set.rows, set.specs) {
                            expect(row.count == spec.units.count, "\(name): a width for every key")
                        }
                        expect(count(set) { $0 == .space } == 1 && count(set) { $0 == .returnKey } == 1 && count(set) { $0 == .delete } == 1,
                               "\(name): one space, one return, one delete")
                        expect(count(set) { $0 == .globe } == (globe ? 1 : 0), "\(name): Globe only when iOS asks for it")
                        expect(set.rows.joined().allSatisfy { !$0.accessibilityLabel.isEmpty }, "\(name): every key has a spoken label")
                        expect(set.rows.joined().allSatisfy { !$0.isCharacter || !$0.label.isEmpty }, "\(name): character keys show a character")
                        expect(set.rows.joined().allSatisfy { $0.isCharacter == $0.preview }, "\(name): only character keys show a balloon")
                        // Space and Return are in the bottom row, with space the flexible key.
                        let bottom = set.rows.last!
                        expect(bottom.contains { $0.action == .space } && bottom.last?.action == .returnKey, "\(name): bottom row ends with Return")
                        expect(set.specs.last!.units.filter { $0 == 0 }.count == 1, "\(name): space takes the remaining width")
                    }
                }
            }
        }
    }

    static func letters() {
        for mode in [InputMode.pinyin, .japanese] {
            for english in [false, true] {
                let lower = KeyboardKeyBuilder.build(context(mode, english: english))
                expect(lower.kind == .letters && lower.referenceColumns == 10, "letters layout")
                expect(lower.rows[0].map(\.label).joined() == "qwertyuiop" && lower.rows[1].map(\.label).joined() == "asdfghjkl", "QWERTY rows")
                expect(lower.rows[2].first?.action == .shift && lower.rows[2].last?.action == .delete, "Shift and Delete end the third row")
                expect(lower.rows[2].dropFirst().dropLast().map(\.label).joined() == "zxcvbnm", "third-row letters")
                for shift in [KeyboardKeyContext.Shift.once, .locked] {
                    let upper = KeyboardKeyBuilder.build(context(mode, english: english, shift: shift))
                    expect(upper.rows[0].map(\.label).joined() == "QWERTYUIOP", "Shift shows capitals in \(mode), english=\(english)")
                    // The key still sends the lowercase code; the controller applies Shift.
                    expect(upper.rows[0].first?.action == .engine(Int32(UInt8(ascii: "q"))), "codes stay lowercase")
                    expect(upper.rows[2].first?.style == .character, "an active Shift key is lit")
                    expect(upper.rows[2].first?.icon == (shift == .locked ? "capslock.fill" : "shift.fill"), "Shift icon shows its state")
                }
                expect(lower.rows[2].first?.icon == "shift" && lower.rows[2].first?.style == .utility, "Shift off")
                // Accented letters on a long press, in English only.
                let e = lower.rows[0][2]
                expect(e.alternates.isEmpty != english, "accents only in English")
                if english {
                    // The key itself comes first: a long press released without sliding types it.
                    expect(e.alternates.first == "e" && e.alternates[1] == "è" && e.alternates.contains("é"), "e, then its accents")
                    let upperE = KeyboardKeyBuilder.build(context(mode, english: true, shift: .once)).rows[0][2]
                    expect(upperE.alternates.first == "E" && upperE.alternates[1] == "È", "capitals have capital accents")
                    expect(lower.rows[0][0].alternates.isEmpty, "q has no accents and no popup")
                }
                // Bottom row: symbols, language toggle, space, return.
                let bottom = lower.rows[3]
                expect(bottom.first?.action == .page(1) && bottom[1].action == .toggleEnglish, "symbols and language keys")
                expect(bottom[1].label == (english ? (mode == .pinyin ? "拼" : "あ") : "ABC"), "the toggle names the other language")
                expect(bottom[1].accessibilityLabel == (english ? (mode == .pinyin ? "回到拼音" : "回到日本語") : "英文"), "and says where it goes")
            }
        }
        let globe = KeyboardKeyBuilder.build(context(.pinyin, globe: true)).rows[3]
        expect(globe.map(\.action) == [.page(1), .toggleEnglish, .globe, .space, .returnKey], "Globe sits before space")
    }

    static func zhuyin() {
        let set = KeyboardKeyBuilder.build(context(.zhuyin))
        expect(set.kind == .zhuyin && set.referenceColumns == 11 && set.rows.count == 5, "Zhuyin has four rows of symbols and a bottom row")
        expect(set.rows.map(\.count) == [11, 10, 10, 11, 4], "Zhuyin row lengths")
        expect(set.rows[0].map(\.label).joined() == "ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ", "first Zhuyin row")
        // Standard (大千) placement: each symbol sends the matching Latin key.
        let codes = set.rows.prefix(4).map { row in
            String(String.UnicodeScalarView(row.compactMap { key -> Unicode.Scalar? in
                if case .engine(let code) = key.action { return Unicode.Scalar(UInt32(code)) }
                return nil
            }))
        }
        expect(codes == ["1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./"], "Zhuyin keys send the standard codes")
        expect(set.rows[3].last?.action == .delete, "Delete ends the fourth row")
        expect(count(set) { $0 == .shift } == 0, "Zhuyin has no Shift")
        // The tone keys show only a mark; VoiceOver gets the tone's name.
        expect(set.rows[0].map(\.accessibilityLabel) == ["ㄅ", "ㄉ", "三聲", "四聲", "ㄓ", "二聲", "輕聲", "ㄚ", "ㄞ", "ㄢ", "ㄦ"], "tone keys are named")
        expect(KeyboardKeyBuilder.build(context(.zhuyin, simplified: true)).rows[0][2].accessibilityLabel == "三声", "in the typing script")
        expect(KeyboardKeyBuilder.build(context(.zhuyin, english: true)).rows[3][1].accessibilityLabel == "回到注音", "the toggle goes back to Zhuyin")
        // English from Zhuyin uses the letters layout.
        expect(KeyboardKeyBuilder.build(context(.zhuyin, english: true)).kind == .letters, "English from Zhuyin is QWERTY")
    }

    static func symbols() {
        let chinese = KeyboardKeyBuilder.build(context(.pinyin, page: 1))
        expect(chinese.kind == .symbols(1) && chinese.rows[0].map(\.label).joined() == "1234567890", "numbers on the first page")
        expect(chinese.rows[2].map(\.label).contains("。") && chinese.rows[1].map(\.label).contains("：") , "Chinese punctuation in Chinese modes")
        let english = KeyboardKeyBuilder.build(context(.pinyin, english: true, page: 1))
        expect(english.rows[2].map(\.label).contains(".") && !english.rows[2].map(\.label).contains("。"), "Latin punctuation in English")
        let japanese = KeyboardKeyBuilder.build(context(.japanese, page: 1))
        expect(japanese.rows[1].first?.label == "ー" && japanese.rows[2].map(\.label).contains("、")
               && !japanese.rows[2].map(\.label).contains("，"), "Japanese has ー and Japanese marks")
        // Page keys: #+= and 123 switch pages, the first bottom key goes back to letters.
        expect(chinese.rows[2].first?.action == .page(2) && chinese.rows[2].first?.label == "#+=", "first page leads to the second")
        let more = KeyboardKeyBuilder.build(context(.pinyin, page: 2))
        expect(more.kind == .symbols(2) && more.rows[2].first?.action == .page(1) && more.rows[2].first?.label == "123", "second page leads back")
        expect(chinese.rows[3].first?.action == .page(0), "bottom-left returns to letters")
        expect(chinese.rows[3].first?.label == "拼音" && KeyboardKeyBuilder.build(context(.zhuyin, page: 1)).rows[3].first?.label == "注音"
               && japanese.rows[3].first?.label == "かな" && english.rows[3].first?.label == "ABC", "the return key names the layout")
        expect(count(chinese) { $0 == .shift } == 0 && count(chinese) { $0 == .toggleEnglish } == 0, "symbol pages have no Shift or language key")
        expect(KeyboardKeyBuilder.build(context(.zhuyin, page: 1)).rows[3].first?.accessibilityLabel == "注音"
               && japanese.rows[3].first?.accessibilityLabel == "假名" && english.rows[3].first?.accessibilityLabel == "字母",
               "and is spoken as that layout")
        // Traditional quotes with 「」 and prices in $; Simplified keeps “” and ¥.
        let traditional = chinese.rows[1].map(\.label), simplified = KeyboardKeyBuilder.build(context(.pinyin, page: 1, simplified: true)).rows[1].map(\.label)
        expect(traditional == ["-", "/", "：", "；", "（", "）", "$", "@", "「", "」"], "Traditional symbol row")
        expect(simplified == ["-", "/", "：", "；", "（", "）", "¥", "@", "“", "”"], "Simplified symbol row")
        expect(KeyboardKeyBuilder.build(context(.zhuyin, page: 1)).rows[1].map(\.label) == traditional, "Zhuyin uses the Traditional row")
        // Long-press variants. Every list starts with the key itself.
        let dollar = english.rows[1].first { $0.label == "$" }
        expect(dollar?.alternates.contains("€") == true, "currency variants on a long press")
        let corner = chinese.rows[1].first { $0.label == "「" }
        expect(corner?.alternates == ["「", "『", "“", "‘"], "the other quotes are under 「")
        for set in [chinese, english, more, japanese, KeyboardKeyBuilder.build(context(.japanese, page: 2)), KeyboardKeyBuilder.build(context(.pinyin, page: 1, simplified: true)),
                    KeyboardKeyBuilder.build(context(.pinyin, english: true, page: 2))] {
            for key in set.rows.joined() where !key.alternates.isEmpty {
                expect(key.alternates.first == key.label, "\(key.label): the key itself is the first option")
            }
        }
        expect(chinese.rows.joined().allSatisfy { key in
            guard case .text(let text) = key.action else { return true }
            return text == key.label
        }, "a symbol key types what it shows")
    }

    static func labels() {
        let traditional = KeyboardKeyBuilder.build(context(.pinyin))
        let simplified = KeyboardKeyBuilder.build(context(.pinyin, simplified: true))
        expect(traditional.rows[3][0].label == "符號" && simplified.rows[3][0].label == "符号", "labels follow the script")
        expect(traditional.rows[2].last?.accessibilityLabel == "刪除" && simplified.rows[2].last?.accessibilityLabel == "删除", "spoken labels follow the script")
        // Shift says its state.
        for (shift, value) in [(KeyboardKeyContext.Shift.off, "關"), (.once, "開"), (.locked, "大寫鎖定")] {
            let key = KeyboardKeyBuilder.build(context(.pinyin, english: true, shift: shift)).rows[2][0]
            expect(key.accessibilityLabel == "Shift" && key.accessibilityValue == value, "Shift reads out \(value)")
        }
        // Space says what it will do while a reading is open.
        let idleSpace = KeyboardKeyBuilder.build(context(.zhuyin)).rows[4].first { $0.action == .space }
        let toneSpace = KeyboardKeyBuilder.build(context(.zhuyin, spaceTitle: "一聲")).rows[4].first { $0.action == .space }
        expect(idleSpace?.accessibilityLabel == "空格" && idleSpace?.accessibilityValue == nil, "idle space has no value")
        expect(toneSpace?.accessibilityLabel == "空格" && toneSpace?.accessibilityValue == "一聲", "space reads out 一聲")
        // Return: title, accent while composing or for an action, disabled when the field wants text first.
        let search = KeyboardKeyBuilder.build(context(.pinyin, returnTitle: "搜尋", accent: true)).rows[3].last!
        expect(search.label == "搜尋" && search.style == .accent && search.enabled, "action Return is accented")
        let disabled = KeyboardKeyBuilder.build(context(.pinyin, enabled: false)).rows[3].last!
        expect(!disabled.enabled && disabled.style == .utility, "Return can be disabled")
        // Same inputs, same keys: the controller relies on this to skip rebuilding.
        expect(KeyboardKeyBuilder.build(context(.pinyin)) == KeyboardKeyBuilder.build(context(.pinyin)), "building is deterministic")
        expect(context(.pinyin) == context(.pinyin) && context(.pinyin) != context(.pinyin, shift: .once), "contexts compare by value")
    }

    static func hitTesting() {
        let widths: [(CGFloat, KeyboardIdiom)] = [(320, .phone), (375, .phone), (393, .phone), (440, .phone),
                                                  (667, .phoneLandscape), (852, .phoneLandscape), (768, .pad), (1024, .pad)]
        for mode in InputMode.allCases {
            for english in [false, true] {
                for page in 0...2 {
                    for globe in [false, true] {
                        let set = KeyboardKeyBuilder.build(context(mode, english: english, page: page, globe: globe))
                        for (width, idiom) in widths {
                            let metrics = KeyMetrics(idiom: idiom, width: width, rows: set.rowCount)
                            let placed = KeyGridLayout.place(rows: set.specs, width: width, referenceColumns: set.referenceColumns, metrics: metrics)
                            let height = metrics.areaHeight(rows: set.rowCount)
                            let name = "\(mode) english=\(english) page=\(page) width=\(width)"
                            if mode == .zhuyin, !english, page > 0 {
                                // A four-row symbol page in Zhuyin's five-row area, as the
                                // controller lays it out: taller keys, and what rounding
                                // leaves over goes under the last row.
                                let base = KeyMetrics(idiom: idiom, width: width, rows: 5)
                                let area = base.areaHeight(rows: 5)
                                var stretched = metrics
                                stretched.keyHeight = ((area - stretched.topPadding - stretched.bottomPadding
                                                        - CGFloat(set.rowCount - 1) * stretched.rowGap) / CGFloat(set.rowCount)).rounded(.down)
                                stretched.bottomPadding += max(0, area - stretched.areaHeight(rows: set.rowCount))
                                let rows = KeyGridLayout.place(rows: set.specs, width: width, referenceColumns: set.referenceColumns, metrics: stretched)
                                expect(abs(rows.last!.first!.hitFrame.maxY - area) < 0.001, "\(name): the last row's touch area ends at the area's bottom")
                                for y in [area - 0.25, area] {
                                    expect(KeyGridLayout.key(at: CGPoint(x: width / 2, y: y), in: rows)?.row == set.rowCount - 1,
                                           "\(name): the bottom edge belongs to the last row")
                                }
                            }
                            for (r, row) in placed.enumerated() {
                                expect(row.count == set.rows[r].count, "\(name): every key has a place")
                                for key in row {
                                    // The middle of a key is that key, and the key is big enough to hit.
                                    let hit = KeyGridLayout.key(at: CGPoint(x: key.frame.midX, y: key.frame.midY), in: placed)
                                    expect(hit?.row == key.row && hit?.column == key.column, "\(name): a key answers its own centre")
                                    expect(key.frame.width >= 22 && key.frame.height >= 26, "\(name): key \(key.row),\(key.column) is \(key.frame.size)")
                                    expect(key.frame.minX >= 0 && key.frame.maxX <= width + 0.001 && key.frame.maxY <= height, "\(name): keys stay inside")
                                    expect(key.hitFrame.contains(key.frame.insetBy(dx: 0.01, dy: 0.01)), "\(name): the touch area covers the key")
                                }
                                // Touch areas tile the row from edge to edge.
                                expect(row.first!.hitFrame.minX == 0 && abs(row.last!.hitFrame.maxX - width) < 0.001, "\(name): touch areas reach both edges")
                                for (a, b) in zip(row, row.dropFirst()) {
                                    expect(abs(a.hitFrame.maxX - b.hitFrame.minX) < 0.001, "\(name): no gap between touch areas")
                                }
                            }
                            expect(placed.first!.first!.hitFrame.minY == 0 && abs(placed.last!.first!.hitFrame.maxY - height) < 0.001,
                                   "\(name): touch areas reach top and bottom")
                            var y: CGFloat = 0.5
                            while y < height {
                                var x: CGFloat = 0.5
                                while x < width {
                                    expect(KeyGridLayout.key(at: CGPoint(x: x, y: y), in: placed) != nil, "\(name): dead zone at \(x), \(y)")
                                    x += 3.1
                                }
                                y += 2.3
                            }
                        }
                    }
                }
            }
        }
    }
}
