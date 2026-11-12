import AppKit
import SwiftUI

// Checks of the macOS candidate window that need no installed input method:
// candidates on one line, Japanese mode, the keys that move and choose,
// placement by the caret, the panel that never takes the keyboard, what
// VoiceOver hears, appearance choices and holiday outfits. Nothing here puts a
// window on screen. They run with the other checks:
//
//   bash scripts/build.sh --test
//
// or, after a build, .build/Meowpuut.app/Contents/MacOS/Meowpuut --smoke

func runCandidateWindowChecks(_ h: inout SmokeHarness) {
    checkOneLine(&h)
    checkJapanese(&h)
    checkKeys(&h)
    checkPlacement(&h)
    checkPanel(&h)
    checkAnnouncements(&h)
    checkAppearance(&h)
    checkFestivals(&h)
}

private func fittingSize(_ engine: Engine, cats: Bool, expanded: Bool, maxHeight: CGFloat = .greatestFiniteMagnitude,
                         forced: CandidateLookOverride = CandidateLookOverride()) -> NSSize {
    let view = GlassCandidateView(engine: engine, cats: cats, expanded: expanded, canExpand: candidatesCanExpand(engine.snapshot),
                                  maxHeight: maxHeight, forced: forced, onChoose: { _ in }, onPage: { _ in }, onToggle: {})
    let hosting = NSHostingView(rootView: view)
    hosting.layoutSubtreeIfNeeded()
    return hosting.fittingSize
}

private func checkOneLine(_ h: inout SmokeHarness) {
    let margin = Desk.margin.top + Desk.margin.bottom
    let phrase = "今晚的月亮很美所以想和你一起散步回家然後聽一首喜歡的歌"
    let long = Snapshot(candidates: [phrase, "今晚", "今"], confidentCandidates: [phrase])
    let longLayout = CollapsedCandidateLayout(long)
    h.check(longLayout.indices.first == 0 && longLayout.truncated, "長候選留在首位並中段省略")
    h.check(longLayout.indices.contains(1), "長候選不佔滿整列，下一個候選仍可見")
    h.check((longLayout.textWidths[1] ?? 0) == CandidateMeasure.width("今晚"), "短候選不被長候選壓窄")
    h.check(collapsedCandidateShortcut(long, key: 49, modifiers: 0, shifted: false, english: false) == .choose(0), "長候選仍用 1 選取")
    h.check(candidatesCanExpand(long), "省略的候選可展開看全文")

    let engine = Engine(mode: .pinyin)
    let huge = Snapshot(candidates: [String(repeating: phrase, count: 20), "今晚"])
    for snapshot in [Snapshot(candidates: ["我", "握", "窩"]), long, huge] {
        engine.snapshot = snapshot
        for cats in [false, true] {
            let size = fittingSize(engine, cats: cats, expanded: false)
            h.check(abs(size.height - (GlassCandidateView.collapsedHeight(cats: cats) + margin)) < 0.5,
                    "收合列永遠單行\(cats ? "（雙貓）" : "（無貓）")")
            h.check(size.width <= 330 + 130 + 140, "收合列寬度有上限\(cats ? "（雙貓）" : "（無貓）")")
        }
    }
    let sentences = Array(repeating: String(repeating: "長", count: 80), count: 7)
    h.check(expandedCandidateRows(sentences).count == 7 &&
            expandedCandidateHeight(sentences) == 7 * Desk.gridRowHeight + 6 * Desk.gridRowSpacing,
            "展開的長句每個候選一行")
    h.check(expandedCandidateWidth(sentences) == Desk.gridMaxWidth && expandedCandidateWidth(sentences, limit: 300) == 300,
            "展開寬度不超過上限")
    engine.snapshot = huge
    for cats in [false, true] {
        let size = fittingSize(engine, cats: cats, expanded: true, maxHeight: 190)
        h.check(size.height <= 190.5, "展開面板不超過可用高度\(cats ? "（雙貓）" : "（無貓）")")
    }
    engine.snapshot = Snapshot(preedit: "nihao", candidates: ["你好", "妳好", "逆號", "你號", "尼好", "逆好", "你豪"], input: "nihao")
    let collapsed = fittingSize(engine, cats: true, expanded: false)
    let expanded = fittingSize(engine, cats: true, expanded: true)
    h.check(expanded.width >= collapsed.width - 0.5 && expanded.height > collapsed.height, "展開後候選列不變窄")
    for forced in [CandidateLookOverride(opaque: true), CandidateLookOverride(opaque: true, contrast: true), CandidateLookOverride(still: true)] {
        h.check(fittingSize(engine, cats: true, expanded: false, forced: forced) == collapsed, "輔助使用設定不改變版面")
    }
}

private func checkJapanese(_ h: inout SmokeHarness) {
    // Japanese mode marks every candidate as Japanese; the row takes them like any other.
    let words = ["寿司", "鮨", "すし", "スシ"]
    let all = Snapshot(preedit: "すし", candidates: words, input: "sushi", japaneseCandidates: words)
    h.check(collapsedCandidateIndices(all) == [0, 1, 2, 3] && markedJapanese(all).isEmpty, "日語模式收合列顯示多個候選，不加貓爪")
    h.check(collapsedCandidateShortcut(all, key: 64, modifiers: 1, shifted: true, english: false) == .choose(1) &&
            collapsedCandidateMove(all, key: 0xff09, modifiers: 0) == 1, "日語模式可用 Shift＋2 選第二個，Tab 移到下一個")
    let mixed = Snapshot(candidates: ["蘇軾", "素食", "寿司"], japaneseCandidates: ["寿司"])
    h.check(markedJapanese(mixed) == ["寿司"] && expandedCandidates(mixed).last == "寿司　", "中日混排時只有日語候選帶貓爪")

    let engine = Engine(mode: .japanese)
    func type(_ keys: String) { for key in keys.utf8 { engine.key(Int32(key)) } }
    type("kanji")
    if engine.snapshot.candidates.count > 1 {
        engine.snapshot.highlighted = 1
        let second = engine.snapshot.candidates[1]
        h.check(!confirmJapaneseCandidate(engine, key: 97, modifiers: 0) && !confirmJapaneseCandidate(engine, key: 32, modifiers: 1) &&
                engine.snapshot.highlighted == 1, "日語模式的字母與 Shift＋空格不當作確認")
        h.check(confirmJapaneseCandidate(engine, key: 32, modifiers: 0) && engine.takeCommit() == second, "日語模式空格送出選中的候選")
        type("kanji")
        engine.snapshot.highlighted = 1
        let again = engine.snapshot.candidates.count > 1 ? engine.snapshot.candidates[1] : ""
        h.check(confirmJapaneseCandidate(engine, key: 46, modifiers: 0) && engine.takeCommit() == again + "。" && engine.snapshot.preedit.isEmpty,
                "日語模式句號送出選中的候選再加句號")
    } else { h.check(false, "日語模式 kanji 有多個候選") }
    engine.clear()

    let kana = String(repeating: "あ", count: 80)
    let wide = Engine(mode: .japanese)
    wide.snapshot = Snapshot(preedit: kana, candidates: [kana, "ア"], input: "a", japaneseCandidates: [kana, "ア"])
    for cats in [false, true] {
        let collapsed = fittingSize(wide, cats: cats, expanded: false)
        let expanded = fittingSize(wide, cats: cats, expanded: true)
        let cap = Desk.gridMaxWidth + Desk.margin.left + Desk.margin.right + (cats ? Desk.glassLeft + Desk.momoOverhang : 0)
        h.check(expanded.width <= max(collapsed.width, cap) + 0.5, "展開面板不被長候選撐寬\(cats ? "（雙貓）" : "（無貓）")")
    }
}

private func checkKeys(_ h: inout SmokeHarness) {
    let row = Snapshot(candidates: ["我", "握", "窩"], confidentCandidates: ["我", "握", "窩"])
    h.check(collapsedCandidateMove(row, key: 0xff53, modifiers: 0) == 1 && collapsedCandidateMove(row, key: 0xff51, modifiers: 0) == 2 &&
            collapsedCandidateMove(row, key: 0xff53, modifiers: 1) == 1, "收合列用左右鍵移動選中")
    h.check(collapsedCandidateMove(row, key: 0xff54, modifiers: 1) == nil && collapsedCandidateMove(Snapshot(preedit: "v"), key: 0xff53, modifiers: 0) == nil,
            "Shift＋下與沒有候選時不在收合列移動")

    // The Japanese candidate the row keeps from a later page is the panel's last cell.
    let page = ["甲", "乙", "丙", "丁", "戊", "己", "庚"]
    let kept = Snapshot(candidates: page, lastPage: false, japaneseCandidates: ["寿司"], compactJapanese: IndexedCandidate(index: 9, text: "寿司", source: 1))
    let cells = expandedCandidates(kept)
    h.check(cells.count == 8 && cells.last == "寿司　" && expandedCandidateRows(cells).flatMap { $0 } == Array(0..<8), "展開面板包含收合列保留的日語候選")
    h.check(expandedCandidateShortcut(kept, key: 56, modifiers: 0, shifted: false) == 7 &&
            expandedCandidateShortcut(kept, key: 51, modifiers: 0, shifted: false) == 2 &&
            expandedCandidateShortcut(Snapshot(candidates: page), key: 56, modifiers: 0, shifted: false) == nil, "展開後數字鍵選到對應的格子")
    h.check(expandedCandidateMove(cells, from: 6, key: 0xff53) == 7 && expandedCandidateMove(cells, from: 7, key: 0xff53) == 7 &&
            expandedCandidateMove(cells, from: 7, key: 0xff52) == 4, "方向鍵可移到保留的日語候選")
    h.check(expandedCandidateShortcut(Snapshot(candidates: ["一", "二", "三"]), key: 64, modifiers: 1, shifted: true) == 1 &&
            expandedCandidateShortcut(Snapshot(candidates: ["一", "二", "三"]), key: 50, modifiers: 0, shifted: true) == nil &&
            expandedCandidateShortcut(Snapshot(candidates: ["一", "二", "三"]), key: 52, modifiers: 0, shifted: false) == nil, "展開後只有畫出來的編號可選")
}

private func checkAnnouncements(_ h: inout SmokeHarness) {
    let engine = Engine(mode: .pinyin)
    engine.snapshot = Snapshot(preedit: "dede", candidates: ["的", "得"], input: "dede")
    let controller = CandidatePanelController(engine: engine)
    h.check(controller.nextAnnouncement(expanded: false) == "的" && controller.nextAnnouncement(expanded: false) == nil, "同一個候選只報讀一次")
    controller.candidateChosen()
    h.check(controller.nextAnnouncement(expanded: false) == "的", "選字後下一組首選同字也會報讀")
    let opened = controller.nextAnnouncement(expanded: true)
    h.check(opened?.contains("展") == true && opened?.contains("的") == false, "展開時報讀")
    engine.snapshot.page = 1
    h.check(controller.nextAnnouncement(expanded: true) == "的", "翻頁後報讀新一頁的首選")
    let closed = controller.nextAnnouncement(expanded: false)
    h.check(closed?.contains("收") == true, "收起時報讀")
}

private func checkPlacement(_ h: inout SmokeHarness) {
    let screen = NSRect(x: 0, y: 0, width: 800, height: 600)
    var contained = true, clear = true
    for y: CGFloat in [0, 100, 290, 400, 580] {
        let caret = NSRect(x: 780, y: y, width: 2, height: 20)
        let available = candidatePanelAvailableHeight(anchor: caret, bounds: screen)
        let frame = candidateFrame(size: NSSize(width: 300, height: min(available, 220)), anchor: caret, bounds: screen)
        contained = contained && screen.contains(frame)
        clear = clear && !frame.intersects(caret)
    }
    h.check(contained, "候選窗留在螢幕內")
    h.check(clear, "候選窗不蓋住游標所在行")

    let size = NSSize(width: 360, height: 90)
    let margin = Desk.margin
    let middle = NSRect(x: 200, y: 300, width: 1, height: 18)
    let below = candidateFrame(size: size, anchor: middle, bounds: screen, margin: margin, lead: 32)
    h.check(below.maxY - margin.top == middle.minY - 6, "游標下方有空間時放在下方，距離 6 pt")
    h.check(below.minX + 32 == middle.minX, "第一個候選對齊游標")
    let low = NSRect(x: 200, y: 40, width: 1, height: 18)
    let above = candidateFrame(size: size, anchor: low, bounds: screen, margin: margin, lead: 32)
    h.check(above.minY + margin.bottom == low.maxY + 6, "下方放不下時改到游標上方")
    let right = candidateFrame(size: size, anchor: NSRect(x: 790, y: 300, width: 1, height: 18), bounds: screen, margin: margin, lead: 32)
    h.check(right.maxX - margin.right == screen.maxX, "靠右時整個候選列仍在螢幕內")
    let left = candidateFrame(size: size, anchor: NSRect(x: 2, y: 300, width: 1, height: 18), bounds: screen, margin: margin, lead: 32)
    h.check(left.minX + margin.left == screen.minX, "靠左時整個候選列仍在螢幕內")

    // Expanding keeps the side already in use while it fits.
    let tight = NSRect(x: 200, y: 260, width: 1, height: 18)
    h.check(candidateGoesBelow(height: 250, anchor: tight, bounds: screen, keeping: true), "展開時留在原本那一側")
    h.check(!candidateGoesBelow(height: 300, anchor: tight, bounds: screen, keeping: true), "原本那一側放不下才換邊")

    let displays = [NSRect(x: 0, y: 0, width: 1440, height: 900), NSRect(x: 1440, y: -180, width: 1920, height: 1080),
                    NSRect(x: 0, y: 900, width: 1440, height: 900)]
    h.check(candidateDisplay(for: NSRect(x: 2000, y: 100, width: 1, height: 18), screens: displays) == 1 &&
            candidateDisplay(for: NSRect(x: 300, y: 1200, width: 1, height: 18), screens: displays) == 2 &&
            candidateDisplay(for: NSRect(x: 300, y: 200, width: 1, height: 18), screens: displays) == 0, "候選窗用游標所在的螢幕")
    h.check(candidateDisplay(for: NSRect(x: 1500, y: -300, width: 1, height: 18), screens: displays) == 1 &&
            candidateDisplay(for: NSRect(x: 100, y: 100, width: 1, height: 18), screens: []) == nil, "游標在螢幕外時用最近的螢幕")

    let start = NSRect(x: 100, y: 300, width: 1, height: 18)
    h.check(candidateAnchorX(start: start, caret: NSRect(x: 148, y: 300, width: 1, height: 18), length: 6, rowWidth: 300) == 100,
            "同一行打字時候選窗不跟著游標移動")
    h.check(candidateAnchorX(start: start, caret: NSRect(x: 40, y: 280, width: 1, height: 18), length: 30, rowWidth: 300) == 40,
            "換行後候選窗回到游標位置")
    h.check(candidateAnchorX(start: start, caret: NSRect(x: 500, y: 300, width: 1, height: 18), length: 30, rowWidth: 300) == 500 - 276,
            "游標超出候選窗右端時候選窗跟上")
    h.check(candidateAnchorX(start: nil, caret: NSRect(x: 77, y: 300, width: 1, height: 18), length: 2, rowWidth: 300) == 77 &&
            candidateAnchorX(start: NSRect(x: 900, y: 300, width: 1, height: 18), caret: NSRect(x: 77, y: 300, width: 1, height: 18), length: 2, rowWidth: 300) == 77,
            "起點不可靠時以游標為準")
}

private func checkPanel(_ h: inout SmokeHarness) {
    let engine = Engine(mode: .pinyin)
    engine.snapshot = Snapshot(preedit: "sushi", candidates: ["蘇軾", "素食", "速食", "蘇式", "俗世", "寿司", "俗事"], input: "sushi", japaneseCandidates: ["寿司"])
    let controller = CandidatePanelController(engine: engine)
    let screens = [CandidateScreen(frame: NSRect(x: 0, y: 0, width: 1440, height: 900), visibleFrame: NSRect(x: 0, y: 70, width: 1440, height: 805)),
                   CandidateScreen(frame: NSRect(x: 1440, y: 0, width: 1920, height: 1080), visibleFrame: NSRect(x: 1440, y: 0, width: 1920, height: 1055))]
    func visible(_ frame: NSRect) -> NSRect {
        NSRect(x: frame.minX + Desk.margin.left, y: frame.minY + Desk.margin.bottom,
               width: frame.width - Desk.margin.left - Desk.margin.right, height: frame.height - Desk.margin.top - Desk.margin.bottom)
    }
    let caret = NSRect(x: 400, y: 500, width: 1, height: 18)
    guard let frame = controller.layout(expanded: false, canExpand: true, caret: caret, start: caret, screens: screens),
          let panel = controller.panel, let hosting = controller.hosting else {
        h.check(false, "候選面板可建立"); return
    }
    h.check(!panel.canBecomeKey && !panel.canBecomeMain && panel.styleMask.contains(.nonactivatingPanel) &&
            panel.styleMask.contains(.borderless) && !panel.hidesOnDeactivate, "候選面板不搶鍵盤焦點")
    h.check(panel.level == .popUpMenu && panel.collectionBehavior.contains(.canJoinAllSpaces) &&
            panel.collectionBehavior.contains(.fullScreenAuxiliary), "候選面板可出現在全螢幕與所有桌面")
    h.check(!panel.isVisible && !controller.isVisible, "排版不會把視窗放上螢幕")
    h.check(hosting.acceptsFirstMouse(for: nil), "第一下點擊就能選字")
    h.check(panel.frame == frame && screens[0].visibleFrame.contains(visible(frame)) && visible(frame).maxY == caret.minY - 6,
            "面板放在游標下方")
    h.check(frame.minX + Desk.lead(cats: Preferences.shared.catGlassSkin) == caret.minX, "面板的第一個候選對齊游標")

    let second = NSRect(x: 3000, y: 30, width: 1, height: 18)
    if let other = controller.layout(expanded: false, canExpand: true, caret: second, start: second, screens: screens) {
        h.check(screens[1].visibleFrame.contains(visible(other)) && visible(other).minY == second.maxY + 6,
                "游標在第二個螢幕底部時，面板在該螢幕並翻到上方")
    } else { h.check(false, "第二個螢幕可排版") }

    if let collapsed = controller.layout(expanded: false, canExpand: true, caret: caret, start: caret, screens: screens),
       let expanded = controller.layout(expanded: true, canExpand: true, caret: caret, start: caret, screens: screens) {
        h.check(expanded.maxY == collapsed.maxY && expanded.height > collapsed.height && expanded.minX == collapsed.minX,
                "展開時候選列留在原位，面板往下長")
    } else { h.check(false, "展開可排版") }

    // Over the caret the panel opens upward and the bar stays by the caret.
    let low = NSRect(x: 400, y: 100, width: 1, height: 18)
    if let collapsed = controller.layout(expanded: false, canExpand: true, caret: low, start: low, screens: screens),
       let expanded = controller.layout(expanded: true, canExpand: true, caret: low, start: low, screens: screens) {
        h.check(visible(collapsed).minY == low.maxY + 6 && expanded.minY == collapsed.minY && expanded.height > collapsed.height &&
                hosting.rootView.panelAbove, "游標上方展開時候選列留在原位，面板往上長")
    } else { h.check(false, "上方展開可排版") }
    controller.hide()
    // Room under the caret for the bar but not for the panel: the window moves over the caret and stays there.
    let tight = NSRect(x: 400, y: 176, width: 1, height: 18)
    if let collapsed = controller.layout(expanded: false, canExpand: true, caret: tight, start: tight, screens: screens),
       let expanded = controller.layout(expanded: true, canExpand: true, caret: tight, start: tight, screens: screens),
       let after = controller.layout(expanded: false, canExpand: true, caret: tight, start: tight, screens: screens) {
        h.check(visible(collapsed).maxY == tight.minY - 6 && visible(expanded).minY == tight.maxY + 6 && hosting.rootView.panelAbove &&
                visible(after).minY == tight.maxY + 6, "下方放不下面板時換到上方，收起後留在上方")
    } else { h.check(false, "換邊展開可排版") }
    controller.hide()

    let wasCats = Preferences.shared.catGlassSkin
    Preferences.shared.catGlassSkin = false
    if let plain = controller.layout(expanded: false, canExpand: true, caret: caret, start: caret, screens: screens) {
        h.check(plain.height < frame.height && plain.minX + Desk.lead(cats: false) == caret.minX, "關掉貓後面板變矮並仍對齊游標")
    } else { h.check(false, "無貓可排版") }
    Preferences.shared.catGlassSkin = wasCats
    controller.hide()
}

private func checkAppearance(_ h: inout SmokeHarness) {
    let preferences = Preferences.shared
    let defaults = UserDefaults(suiteName: "app.meowpuut.smoke")!
    let saved = (preferences.glassShape, preferences.candidateTone, preferences.candidatePalette, preferences.glassStrength,
                 preferences.catGlassSkin, preferences.festivalOutfits)
    var applied = true
    for command in ["shape:cloud", "tone:dark", "palette:amber", "glass:soft", "cats:off", "festival:off"] {
        applied = applyAppearance(command) && applied
    }
    h.check(applied && preferences.glassShape == .cloud && preferences.candidateTone == .dark && preferences.candidatePalette == .amber &&
            preferences.glassStrength == .soft && !preferences.catGlassSkin && !preferences.festivalOutfits, "選單可改造型、色調、色系、玻璃、貓與節日裝扮")
    h.check(defaults.string(forKey: "glassShape") == "cloud" && defaults.string(forKey: "candidateTone") == "dark" &&
            defaults.string(forKey: "candidatePalette") == "amber" && defaults.string(forKey: "glassStrength") == "soft" &&
            defaults.object(forKey: "catGlassSkin") as? Bool == false && defaults.object(forKey: "festivalOutfits") as? Bool == false,
            "外觀設定會儲存")
    h.check(!applyAppearance("shape:star") && !applyAppearance("cats:maybe") && !applyAppearance("shape") && preferences.glassShape == .cloud,
            "無效的外觀指令不改設定")
    (preferences.glassShape, preferences.candidateTone, preferences.candidatePalette, preferences.glassStrength,
     preferences.catGlassSkin, preferences.festivalOutfits) = saved

    let hant = InterfaceText(simplified: false), hans = InterfaceText(simplified: true)
    h.check(GlassShape.allCases.map { $0.name(hant) } == ["膠囊", "波浪", "雲朵"] && GlassShape.allCases.map { $0.name(hans) } == ["胶囊", "波浪", "云朵"] &&
            CandidateTone.allCases.map { $0.name(hant) } == ["跟隨系統", "淺色", "深色"] && CandidateTone.allCases.map { $0.name(hans) } == ["跟随系统", "浅色", "深色"],
            "造型與色調有繁簡名稱")
    // White text on the Increase Contrast capsule.
    func luminance(_ color: Color) -> CGFloat {
        guard let rgb = NSColor(color).usingColorSpace(.sRGB) else { return 1 }
        func linear(_ value: CGFloat) -> CGFloat { value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(rgb.redComponent) + 0.7152 * linear(rgb.greenComponent) + 0.0722 * linear(rgb.blueComponent)
    }
    h.check(CandidatePalette.allCases.allSatisfy { 1.05 / (luminance($0.contrastPill) + 0.05) >= 4.5 }, "增加對比時選中膠囊上的白字對比至少 4.5")
    h.check(CandidatePalette.allCases.allSatisfy { !$0.name(hant).isEmpty && !$0.name(hans).isEmpty } &&
            CandidatePalette.ocean.name(hans) == "海盐蓝" && GlassStrength.balanced.name(hans) == "标准", "色系與玻璃有繁簡名稱")
}

private func checkFestivals(_ h: inout SmokeHarness) {
    // The wardrobe ships in the app; the logic itself is in iOS/tests/FestivalsTests.swift.
    let bundled = Bundle.main.url(forResource: "Festivals", withExtension: nil)?.appendingPathComponent("festivals.json")
    let loaded = bundled.flatMap { try? Data(contentsOf: $0) }.map { Festivals.decode($0) }
    h.check(loaded?.themes.count == 24, "內建節日衣櫥有 24 套")
    h.check(Festivals.theme(onDay: "2026-12-25", in: loaded ?? .empty, wardrobe: FestivalWardrobe())?.id == "christmas" &&
            Festivals.theme(onDay: "2026-12-25", in: loaded ?? .empty, wardrobe: FestivalWardrobe(on: false)) == nil, "節日衣櫥照日期換裝，關閉後不換")
    h.check(Festivals.decode(Data(#"{"version":1,"outfits":[]}"#.utf8)).isEmpty && Festivals.decode(Data("oops".utf8)).isEmpty, "看不懂的節日清單當作沒有")
}
