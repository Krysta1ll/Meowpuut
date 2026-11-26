import AppKit
import ImageIO
import UniformTypeIdentifiers

// Checks of the 字景 candidate window (Sources/GlyphStrip.swift) and the offscreen shots:
//
//   .build/Meowpuut.app/Contents/MacOS/Meowpuut --glyph-shots DIR     PNGs of the window and the per-key cost
//
// The checks run with the others under --smoke.

#if MEOWPUUT_GLYPH

private let nihao = ["你好", "妳好", "你好呀", "擬好", "你", "妳", "泥"]
private let nihaoMore = ["你好", "妳好", "你好呀", "擬好", "你", "妳", "泥", "尼"]
private let sushi = ["蘇軾", "素食", "寿司", "速食", "俗世", "宿世", "蘇"]

private func specs(_ words: [String], japanese: Set<String> = []) -> [GlyphCellSpec] {
    words.enumerated().map { GlyphCellSpec(index: $0, number: String($0 + 1), text: $1, japanese: japanese.contains($1)) }
}

private func makeView(_ words: [String], japanese: Set<String> = [], selected: Int = 0, expanded: Bool = false, zhuyin: Bool = false,
                      dark: Bool = false, safe: Bool = false, scale: CGFloat = 2, place: Int32 = 1, preedit: Int = 5,
                      paging: (page: Int, last: Bool)? = nil) -> GlyphStripView {
    let view = GlyphStripView(frame: .zero)
    view.fixPlace(place)
    view.update(specs: specs(words, japanese: japanese), selected: selected, expanded: expanded, zhuyin: zhuyin,
                look: GlyphLook(dark: dark, safe: safe, still: safe), backingScale: scale, preedit: preedit, paging: paging, characters: 8)
    return view
}

private func writePNG(_ image: CGImage, to url: URL) {
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { return }
    CGImageDestinationAddImage(destination, image, nil)
    CGImageDestinationFinalize(destination)
}

private func nanoseconds(_ body: () -> Void) -> Double {
    var info = mach_timebase_info_data_t()
    mach_timebase_info(&info)
    let start = mach_absolute_time()
    body()
    return Double(mach_absolute_time() - start) * Double(info.numer) / Double(info.denom)
}

private func report(_ name: String, _ samples: [Double]) {
    let sorted = samples.sorted()
    func ms(_ v: Double) -> String { String(format: "%.3f", v / 1e6) }
    print("  \(name.padding(toLength: 46, withPad: " ", startingAt: 0)) 中位 \(ms(sorted[sorted.count / 2])) ms  p95 \(ms(sorted[sorted.count * 95 / 100]))  最大 \(ms(sorted.last ?? 0))  （\(sorted.count) 次）")
}

/// PNGs for light, dark, safe, expanded and both k, and the main-thread cost of a key. Returns the exit status.
func runGlyphShots(directory: String) -> Int32 {
    let out = URL(fileURLWithPath: directory)
    try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
    print("字型：\(GlyphType.bundled ? "俐方體 11（隨 App）" : "找不到，用系統字")")
    for scale: CGFloat in [1, 2] {
        let k = Int(scale)
        func shot(_ name: String, _ view: GlyphStripView) {
            _ = view.finishLongStrip(timeout: 2)
            guard let image = view.snapshot() else { print("沒有畫出 \(name)"); return }
            let url = out.appendingPathComponent("glyph-\(name)-k\(k).png")
            writePNG(image, to: url)
            print(url.path, "\(image.width)x\(image.height)")
        }
        shot("light", makeView(nihao, scale: scale))
        shot("dark", makeView(nihao, dark: true, scale: scale))
        shot("safe", makeView(nihao, safe: true, scale: scale))
        shot("expanded-light", makeView(nihaoMore, selected: 5, expanded: true, scale: scale))
        shot("expanded-dark", makeView(nihaoMore, selected: 5, expanded: true, dark: true, scale: scale))
        shot("paged", makeView(nihaoMore, selected: 2, expanded: true, scale: scale, paging: (1, false)))
        shot("paged-dark", makeView(nihaoMore, selected: 2, expanded: true, dark: true, scale: scale, paging: (0, true)))
        let game = GlyphStripView(frame: .zero)
        game.update(specs: specs(nihao), selected: 0, expanded: false, zhuyin: false, look: GlyphLook(dark: false, safe: false, still: false),
                    backingScale: scale, preedit: 5, game: true)
        shot("game", game)
        shot("sushi", makeView(sushi, japanese: ["寿司"], selected: 2, scale: scale))
        shot("zhuyin", makeView(nihao, zhuyin: true, scale: scale))
        // ← → Tab: the strip over the land drawn ahead, the sun behind the fourth word.
        let moved = makeView(nihao, scale: scale)
        _ = moved.finishLongStrip()
        moved.update(specs: specs(nihao), selected: 3, expanded: false, zhuyin: false, look: GlyphLook(dark: false, safe: false, still: false),
                     backingScale: scale, preedit: 5)
        shot("moved", moved)
    }

    print("每鍵在主執行緒的成本（k = 2，發行版建置）：")
    let look = GlyphLook(dark: false, safe: false, still: false)
    let view = makeView(nihao, scale: 2, preedit: 1)
    _ = view.finishLongStrip()
    let typed = [["你", "尼", "泥"], ["你好", "妳好", "你好呀", "擬好", "你", "妳", "泥"], nihao, ["你好嗎", "你好", "妳好", "擬好嗎"], nihaoMore]
    var letter: [Double] = [], arrow: [Double] = [], arrowOther: [Double] = []
    // Warm up the masks once; "cold" is the first key of a word nobody has typed before.
    for round in 0..<60 {
        let words = typed[round % typed.count]
        letter.append(nanoseconds {
            view.update(specs: specs(words), selected: 0, expanded: false, zhuyin: false, look: look, backingScale: 2, preedit: 2 + round % 4)
        })
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))   // the foot comes down, the land is drawn
    }
    view.update(specs: specs(nihao), selected: 0, expanded: false, zhuyin: false, look: look, backingScale: 2, preedit: 5)
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    var ready = view.finishLongStrip()
    for step in 0..<200 {
        arrow.append(nanoseconds {
            view.update(specs: specs(nihao), selected: (step + 1) % nihao.count, expanded: false, zhuyin: false, look: look, backingScale: 2, preedit: 5)
        })
        if step % 20 == 19 { RunLoop.current.run(until: Date().addingTimeInterval(0.3)); ready = view.finishLongStrip() }
    }
    for step in 0..<40 {
        arrowOther.append(nanoseconds {
            view.update(specs: specs(nihao), selected: (step + 1) % nihao.count, expanded: false, zhuyin: false, look: look, backingScale: 2, preedit: 5)
        })
        view.advance()
    }
    report("字母鍵（候選換了：文字、OVER、CATS、必要時 LAND）", letter)
    report("← → Tab（只移 contentsRect，重畫 OVER、CATS）", arrow)
    report("← → Tab 緊接在地面或計時之後（長條失效時補畫一格）", arrowOther)
    print("  長條在 ← → Tab 前\(ready ? "已" : "未")備好；LAND 共畫 \(view.landRenders) 次")
    return 0
}

/// The checks that run with --smoke.
func runGlyphSmoke(_ h: inout SmokeHarness) {
    h.check(CandidateSkin.stored(nil) == .glass && CandidateSkin.stored("glyph") == .glyph && CandidateSkin.stored("pixel") == .glass,
            "候選窗皮膚：沒有或不認得的值是玻璃，glyph 是字景")
    h.check(mp_glyph_strip_k_for(1) == 1 && mp_glyph_strip_k_for(2) == 2 && mp_glyph_strip_k_for(1.25) == 2 && mp_glyph_strip_k_for(1.75) == 2 && mp_glyph_strip_k_for(2.5) == 3,
            "字景的整數倍 k：100% → 1、200% → 2、250% → 3")
    h.check(GlyphType.bundled, "字景的字型（俐方體 11）在 App 裡")
    let one = GlyphType.mask("你", k: 1), two = GlyphType.mask("你", k: 2)
    h.check(!one.dots.isEmpty && two.dots.count == one.dots.count * 4, "俐方體在 k = 2 是 k = 1 的整數倍放大（不抗鋸齒）")
    h.check(GlyphType.width("你好") == 26 && GlyphType.width("1") > 0, "候選字在 12 px 量得整數點寬")
    for scale: CGFloat in [1, 2] {
        let view = makeView(nihao, japanese: ["泥"], scale: scale)
        let k = Int(scale)
        h.check(view.k == k && view.cells.count == 7 && view.plainSize.height == 72, "字景條 k = \(k)、高 72 pt、七個字")
        let image = view.snapshot()
        var ink = 0
        if let image, let data = image.dataProvider?.data, let bytes = CFDataGetBytePtr(data) {
            for i in stride(from: 3, to: CFDataGetLength(data), by: 4) where bytes[i] > 0 { ink += 1 }
        }
        h.check(image != nil && ink > image!.width * image!.height / 2, "字景條畫出不透明的風景與文字")
    }
    let dark = makeView(nihao, dark: true), light = makeView(nihao)
    let sample = { (view: GlyphStripView) -> UInt32 in
        guard let data = view.snapshot()?.dataProvider?.data, let bytes = CFDataGetBytePtr(data) else { return 0 }
        return bytes.withMemoryRebound(to: UInt32.self, capacity: 1) { $0[10 * view.k * 2 + 4] }
    }
    h.check(sample(dark) != sample(light), "字景條的深色與淺色不同")
    let menu = GlyphMenu.build(expanded: false, canExpand: true, onToggle: {}, onChange: {})
    h.check(menu.items.count == 11 && menu.items.first?.isEnabled == true && menu.items.dropFirst(5).allSatisfy { $0.submenu != nil || $0.title == "節日裝扮" || $0.title == "节日装扮" },
            "字景的右鍵選單與玻璃的一樣（展開、貓、位置、節日、造型、字級、窗大小、色調、色系、玻璃）")
    // Full screen (FINAL §3): the front window of the typed-in app covers a display.
    let screen = CGRect(x: 0, y: 0, width: 1512, height: 982)
    let big = FullScreenProbe.Window(pid: 7, frame: screen), small = FullScreenProbe.Window(pid: 7, frame: CGRect(x: 100, y: 80, width: 900, height: 600))
    let other = FullScreenProbe.Window(pid: 9, frame: screen)
    h.check(FullScreenProbe.isFullScreen(windows: [big], screens: [screen], frontPID: 7)
            && !FullScreenProbe.isFullScreen(windows: [small, big], screens: [screen], frontPID: 7)
            && !FullScreenProbe.isFullScreen(windows: [other, small], screens: [screen], frontPID: 7)
            && !FullScreenProbe.isFullScreen(windows: [], screens: [screen], frontPID: 7),
            "全螢幕偵測：前景 App 的最前視窗蓋滿一個螢幕才算，別的 App 或小視窗不算")
    let game = GlyphStripView(frame: .zero)
    game.update(specs: specs(nihao), selected: 0, expanded: false, zhuyin: false, look: GlyphLook(dark: false, safe: false, still: false),
                backingScale: 2, preedit: 5, game: true)
    h.check(game.plainSize.height == 24 && game.cells.count == 7 && game.shadowInset > 0, "全螢幕時字景收成一行（高 24 pt），硬投影照留")
    let tall = makeView(nihaoMore, expanded: true)
    h.check(tall.plainSize.height > 72, "展開（↓）時字景條向下長")
    let still = makeView(nihao, safe: true)
    still.advance()
    h.check(still.plainSize.height == 72 && still.cells.first?.halo_color == 0, "安全畫法：不動、字在紙上沒有外框")
}

#endif
