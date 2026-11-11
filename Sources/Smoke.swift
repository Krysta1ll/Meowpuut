import AppKit
import InputMethodKit

struct SmokeHarness {
    let engine: Engine
    var failures = 0

    mutating func check(_ ok: Bool, _ message: String) {
        print("\(ok ? "PASS" : "FAIL")：\(message)")
        if !ok { failures += 1 }
    }

    func type(_ value: String) {
        for key in value.utf8 { engine.key(Int32(key)) }
    }

    mutating func expect(_ mode: InputMode, _ input: String, _ expected: String) {
        engine.clear(); engine.selectMode(mode); type(input)
        print("\(mode.rawValue) \(input) → \(engine.snapshot.candidates)")
        guard let index = engine.snapshot.candidates.firstIndex(of: expected) else {
            check(false, "\(expected) 首頁候選")
            return
        }
        engine.choose(index)
        check(engine.takeCommit() == expected && engine.takeCommit().isEmpty, "\(expected) 提交一次")
    }

    func physical(_ keyCode: UInt16, shift: Bool = false, option: Bool = false) -> Bool {
        let physicalCharacters: [UInt16: (String, String)] = [
            44: ("/", "?"), 42: ("\\", "|"), 27: ("-", "_"), 39: ("'", "\""), 41: (";", ":"),
            29: ("0", ")"), 18: ("1", "!"), 19: ("2", "@"), 20: ("3", "#"),
            21: ("4", "$"), 23: ("5", "%"), 22: ("6", "^"), 26: ("7", "&"),
            28: ("8", "*"), 25: ("9", "(")
        ]
        var flags: NSEvent.ModifierFlags = []
        if shift { flags.insert(.shift) }
        if option { flags.insert(.option) }
        let chars = physicalCharacters[keyCode] ?? ("", "")
        guard let event = NSEvent.keyEvent(with: .keyDown, location: .zero,
                modifierFlags: flags, timestamp: 0, windowNumber: 0,
                context: nil, characters: shift ? chars.1 : chars.0, charactersIgnoringModifiers: chars.0,
                isARepeat: false, keyCode: keyCode),
              let (key, modifiers) = inputKey(event, mode: engine.inputMode, english: engine.english, composing: !engine.snapshot.input.isEmpty) else { return false }
        return engine.key(key, modifiers: modifiers)
    }

    func layoutEvent(_ keyCode: UInt16, _ characters: String, shift: Bool = false) -> NSEvent {
        NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: shift ? [.shift] : [],
                         timestamp: 0, windowNumber: 0, context: nil, characters: characters,
                         charactersIgnoringModifiers: characters, isARepeat: false, keyCode: keyCode)!
    }
}

/// Pages through every candidate of the current input. True when the bare
/// kana spelling is listed, is neither required nor the kept Japanese cell,
/// and no Chinese candidate comes after it.
func kanaRankedLast(_ engine: Engine, _ kana: String) -> Bool {
    guard engine.snapshot.requiredCandidate != kana, engine.snapshot.compactJapanese?.text != kana else { return false }
    let japanese = Set(engine.snapshot.japaneseCandidates)
    var all = engine.snapshot.candidates
    for _ in 0..<80 where !engine.snapshot.lastPage { engine.key(0xff56); all += engine.snapshot.candidates }
    guard let at = all.firstIndex(of: kana) else { return false }
    let han = { (text: String) in text.unicodeScalars.contains { (0x3400...0x9fff).contains($0.value) || $0.value >= 0x20000 } }
    return !all[at...].contains { han($0) && !japanese.contains($0) }
}

func runIndependentSmoke() -> Never {
    setbuf(stdout, nil)
    // The checks take a few seconds. A hang must not block whoever started them.
    DispatchQueue.global().asyncAfter(deadline: .now() + 300) {
        print("FAIL：檢查逾時")
        exit(3)
    }
    let menuItem = NSMenuItem(title: "測試皮膚", action: nil, keyEquivalent: "")
    menuItem.representedObject = true
    var h = SmokeHarness(engine: Engine())
    h.check(SystemInput.menuItem(from: [kIMKCommandMenuItemName: menuItem]) === menuItem, "系統輸入選單解析 IMK 指令字典")
    h.check(SystemInput.menuItem(from: menuItem) === menuItem && SystemInput.menuItem(from: [:]) == nil, "選單兼容直接點選並拒絕空指令")
    let testScreens = [NSRect(x: 0, y: 0, width: 1000, height: 1000)]
    let caret = NSRect(x: 180, y: 170, width: 1, height: 20)
    var queried: [Int] = []
    let initial = SystemInput.caretRect(markedText: nil, screens: testScreens) { index in queried.append(index); return caret }
    h.check(initial == caret && queried == [0], "沒有組字時查詢目前選區")
    queried = []
    let end = SystemInput.caretRect(markedText: "你好🐈", screens: testScreens) { index in queried.append(index); return caret }
    h.check(end == caret && queried == [4], "組字使用 UTF-16 末尾定位游標")
    queried = []
    let fallback = SystemInput.caretRect(markedText: "你好🐈", screens: testScreens) { index in
        queried.append(index)
        return index == 4 ? .zero : caret
    }
    h.check(fallback == caret && queried == [4, 2], "末尾無效時回退到最後完整字元")
    queried = []
    let invalid = SystemInput.caretRect(markedText: "你好", screens: testScreens) { index in
        queried.append(index)
        return NSRect(x: 2000, y: 2000, width: 1, height: 20)
    }
    h.check(invalid == nil && queried == [2, 1], "座標無效時保留先前錨點")
    Preferences.shared.simplified = false
    Preferences.shared.rememberChoices = false
    Preferences.shared.pins = [:]
    let appearanceDefaults = UserDefaults(suiteName: "app.meowpuut.smoke")!
    Preferences.shared.candidateTone = .light
    Preferences.shared.candidatePalette = .sage
    Preferences.shared.glassStrength = .vivid
    h.check(appearanceDefaults.string(forKey: "glassStrength") == "vivid", "玻璃強度會儲存")
    h.check(appearanceDefaults.string(forKey: "candidateTone") == "light" && appearanceDefaults.string(forKey: "candidatePalette") == "sage", "深淺色調與色系分開持久儲存")
    Preferences.shared.catGlassSkin = true
    h.check(Preferences.shared.candidateTone == .light && Preferences.shared.candidatePalette == .sage, "切換皮膚保留共用配色")
    let engine = h.engine
    h.check(engine.isReady, "自研注音核心載入")
    let azerty = h.layoutEvent(0, "q")
    h.check(inputKey(azerty, mode: .pinyin)?.0 == 113 && inputKey(azerty, mode: .pinyin, english: true)?.0 == 113, "拼音與英文跟隨實際鍵盤佈局")
    h.check(inputKey(azerty, mode: .zhuyin)?.0 == 97, "注音保留大千實體鍵位")
    h.check(inputKey(h.layoutEvent(0, "é"), mode: .pinyin) == nil && inputKey(h.layoutEvent(0, ""), mode: .pinyin, english: true) == nil, "非 ASCII 與死鍵交給系統")
    h.check(inputKey(h.layoutEvent(22, "§", shift: true), mode: .pinyin, composing: true)?.0 == 94, "拼音聲調保留大千實體快捷鍵")
    h.check(inputKey(h.layoutEvent(22, "§", shift: true), mode: .pinyin) == nil, "沒有組字時非 ASCII 標點交給系統")
    h.check(inputKey(h.layoutEvent(76, "\r"), mode: .pinyin)?.0 == 0xff0d, "小鍵盤 Enter 映射為確認")
    engine.selectMode(.pinyin)
    if let (key, modifiers) = inputKey(azerty, mode: .pinyin) { engine.key(key, modifiers: modifiers) }
    h.check(engine.snapshot.input == "q", "非 US 按鍵實際輸入拼音字母")
    engine.clear(); h.type("nihao")
    if let (key, modifiers) = inputKey(h.layoutEvent(76, "\r"), mode: .pinyin, composing: true) { engine.key(key, modifiers: modifiers) }
    h.check(engine.takeCommit() == "你好", "小鍵盤 Enter 實際確認中文")
    engine.clear()
    for (input, expected) in [("su3cl3", "你好"), ("sucl", "你好"), ("5j/4", "重")] { h.expect(.zhuyin, input, expected) }
    for (input, expected) in [("nihao", "你好"), ("nvhai", "女孩"), ("xi'an", "西安"), ("nh", "你好"), ("sushi", "蘇軾"), ("zhongguo", "中國"), ("woxianghekafei", "我想喝咖啡")] { h.expect(.pinyin, input, expected) }
    h.expect(.zhuyin, "c04y4", "漢字")
    h.expect(.zhuyin, "ji3ap7rup wu0 fm4d042u04u/3", "我們今天去看電影")
    h.expect(.zhuyin, "rup wu0 wu0 fu4cp3cl3ji3vu;3fm4ej/ m06n041j4", "今天天氣很好我想去公園散步")
    engine.clear(); engine.selectMode(.zhuyin)
    let longReading = String(repeating: "su3cl3", count: 16)
    h.type(longReading)
    h.check(engine.snapshot.input == longReading, "長句超過80鍵不丟失輸入")
    h.check(engine.snapshot.candidates.contains(String(repeating: "你好", count: 16)), "32字長句可選")
    engine.key(0xff0d)
    h.check(engine.takeCommit() == String(repeating: "你好", count: 16), "32字長句回車上屏")
    h.expect(.zhuyin, "su3cl3", "你好")
    engine.clear(); engine.selectMode(.pinyin); h.type("shi")
    for _ in 0..<4 { engine.key(0xff56) }
    h.check(engine.snapshot.page == 4 && !engine.snapshot.candidates.isEmpty, "候選可翻到第29項之後")
    let fifthPage = engine.snapshot.candidates.first ?? ""
    engine.choose(0)
    h.check(!fifthPage.isEmpty && engine.takeCommit() == fifthPage, "第五頁索引可正確上屏")
    engine.clear(); engine.selectMode(.zhuyin)
    var overflowText = ""
    let overflowReading = String(repeating: "su3cl3", count: 44)
    for key in overflowReading.utf8 { engine.key(Int32(key)); overflowText += engine.takeCommit() }
    engine.key(0xff0d, modifiers: 1); overflowText += engine.takeCommit()
    let limit = Int(mpn_max_input_bytes())
    let zhuyinSymbols: [Character: String] = ["s": "ㄋ", "u": "ㄧ", "3": "ˇ", "c": "ㄏ", "l": "ㄠ"]
    let originalPrefix = overflowReading.prefix(limit).map { zhuyinSymbols[$0] ?? String($0) }.joined()
    h.check(overflowText.hasPrefix(originalPrefix) && overflowText.hasSuffix(String(overflowReading.suffix(8))),
          "超出上限保留原注音並接收後續按鍵")

    do {
        func atLimit(_ mode: InputMode, key: UInt8, count: Int? = nil,
                     literalSpaceAddsSpace: Bool = true) throws -> ChineseWithJapaneseBackend {
            let backend = try ChineseWithJapaneseBackend(mode: mode, literalSpaceAddsSpace: literalSpaceAddsSpace)
            for _ in 0..<(count ?? limit) { _ = backend.key(Int32(key), modifiers: 0) }
            return backend
        }
        let rawPinyin = String(repeating: "v", count: limit)
        let rawZhuyin = String(repeating: "ㄋ", count: limit)
        for simplified in [false, true] {
            let pinyin = try atLimit(.pinyin, key: 118)
            pinyin.setSimplified(simplified)
            _ = pinyin.key(110, modifiers: 0); _ = pinyin.key(105, modifiers: 0)
            let pinyinState = pinyin.snapshot()
            h.check(pinyinState.commit == rawPinyin && pinyinState.input == "ni" && pinyin.snapshot().commit.isEmpty,
                  "拼音滿長度保留原讀音、續打且只上屏一次")
            let zhuyin = try atLimit(.zhuyin, key: 115)
            zhuyin.setSimplified(simplified)
            _ = zhuyin.key(117, modifiers: 0); _ = zhuyin.key(51, modifiers: 0)
            let zhuyinState = zhuyin.snapshot()
            h.check(zhuyinState.commit == rawZhuyin && zhuyinState.input == "u3" && zhuyin.snapshot().commit.isEmpty,
                  "注音滿長度保留原讀音、續打帶調且只上屏一次")
        }
        let pinyinTone = try atLimit(.pinyin, key: 118)
        _ = pinyinTone.key(49, modifiers: 0)
        let pinyinToneState = pinyinTone.snapshot()
        h.check(pinyinToneState.commit == rawPinyin + "1" && pinyinToneState.input.isEmpty, "拼音上限的聲調接在原讀音後")
        let zhuyinTone = try atLimit(.zhuyin, key: 115)
        _ = zhuyinTone.key(54, modifiers: 0)
        let zhuyinToneState = zhuyinTone.snapshot()
        h.check(zhuyinToneState.commit == rawZhuyin + "ˊ" && zhuyinToneState.input.isEmpty, "注音上限的聲調接在原讀音後")
        let zhuyinSpace = try atLimit(.zhuyin, key: 115)
        _ = zhuyinSpace.key(32, modifiers: 0)
        let zhuyinSpaceState = zhuyinSpace.snapshot()
        h.check(zhuyinSpaceState.commit == rawZhuyin + "ˉ" && zhuyinSpaceState.input.isEmpty, "注音上限的一聲不會丟失")
        for mode in [InputMode.pinyin, .zhuyin] {
            let separator = try atLimit(mode, key: mode == .pinyin ? 118 : 115)
            _ = separator.key(39, modifiers: 0)
            let separatorState = separator.snapshot()
            h.check(separatorState.commit == (mode == .pinyin ? rawPinyin + "'" : rawZhuyin + " ") && separatorState.input.isEmpty,
                  "拼音和注音上限的分隔符不會丟失")
        }
        for addsSpace in [false, true] {
            let space = try atLimit(.pinyin, key: 118, literalSpaceAddsSpace: addsSpace)
            _ = space.key(32, modifiers: 0)
            h.check(space.snapshot().commit == rawPinyin + (addsSpace ? " " : ""), "滿長度保留各平台拼音空格設定")
        }
        let pending = try atLimit(.pinyin, key: 118, count: 2 * limit)
        _ = pending.key(118, modifiers: 0)
        let pendingState = pending.snapshot()
        h.check(pendingState.commit == rawPinyin + rawPinyin && pendingState.input == "v", "兩次上限切段保留尚未交付的文字")
        let cancel = try atLimit(.pinyin, key: 118)
        _ = cancel.key(110, modifiers: 0); _ = cancel.key(0xff1b, modifiers: 0)
        let cancelState = cancel.snapshot()
        h.check(cancelState.commit == rawPinyin && cancelState.input.isEmpty, "取消新組字不會丟棄已切段原文")
        let noCandidate = try atLimit(.pinyin, key: 118)
        for _ in 0..<6 { _ = noCandidate.key(118, modifiers: 0) }
        h.check(noCandidate.candidates(limit: 1).isEmpty, "滿長度後仍可辨識無候選的後綴")
        noCandidate.commit()
        let noCandidateState = noCandidate.snapshot()
        h.check(noCandidateState.commit == rawPinyin + "vvvvvv" && noCandidateState.input.isEmpty,
              "確認無候選後綴保留先前未交付的原讀音")
        let choice = try atLimit(.pinyin, key: 118)
        _ = choice.key(110, modifiers: 0); _ = choice.key(105, modifiers: 0)
        if let candidate = choice.candidates(limit: 256).first(where: { $0.text == "你" }) {
            _ = choice.choose(candidate.index)
            let choiceState = choice.snapshot()
            h.check(choiceState.commit == rawPinyin + "你" && choiceState.input.isEmpty, "選擇後綴候選不會覆蓋已切段原文")
        } else { h.check(false, "切段後可選後綴候選") }
        let locked = try ChineseWithJapaneseBackend(mode: .zhuyin)
        for key in "su3cl3v".utf8 { _ = locked.key(Int32(key), modifiers: 0) }
        if let candidate = locked.candidates(limit: 256).first(where: { $0.text == "你好" }) {
            _ = locked.choose(candidate.index)
            let selected = locked.snapshot()
            h.check(selected.commit == "你好" && selected.input == "v", "注音部分選詞保留剩餘組字")
            for _ in 1..<limit { _ = locked.key(118, modifiers: 0) }
            _ = locked.key(117, modifiers: 0); _ = locked.key(51, modifiers: 0)
            let lockedState = locked.snapshot()
            h.check(lockedState.commit == String(repeating: "ㄒ", count: limit) && lockedState.input == "u3",
                  "注音部分選詞後滿長度仍保留讀音與新鍵")
        } else { h.check(false, "注音部分選詞候選可用") }
    } catch { h.check(false, "組字上限測試引擎可載入") }

    engine.clear(); engine.selectMode(.zhuyin); h.type("su3cl3"); engine.key(60, modifiers: 1)
    h.check(engine.takeCommit() == "你好，", "注音接中文逗號並上屏")
    h.type("ji3ap7rup wu0 fm4d042u04u/3"); engine.key(62, modifiers: 1)
    h.check(engine.takeCommit() == "我們今天去看電影。", "注音長句接中文句號")
    h.type("su3cl3"); engine.key(63, modifiers: 1)
    h.check(engine.takeCommit() == "你好？", "注音接中文問號")
    engine.key(91); h.check(engine.takeCommit() == "「", "中文引號不作注音鍵")
    engine.key(93); h.check(engine.takeCommit() == "」", "中文右引號")
    h.type("su3cl3"); engine.key(96)
    h.check(engine.takeCommit() == "你好" && engine.snapshot.candidates.contains("；"), "標點選單先提交組字")
    engine.key(54); h.check(engine.takeCommit() == "；" && engine.snapshot.preedit.isEmpty, "標點選單數字選擇")
    engine.key(96); engine.key(0xff56); engine.key(49)
    h.check(engine.takeCommit() == "「", "標點選單翻頁索引")
    engine.key(96); engine.key(0xff1b)
    h.check(engine.snapshot.preedit.isEmpty && engine.takeCommit().isEmpty, "取消標點不插入文字")
    h.type("su3cl3"); engine.key(33, modifiers: 1)
    h.check(engine.takeCommit() == "你好", "Shift+1仍然選第一候選")
    engine.key(33, modifiers: 1); h.check(engine.takeCommit() == "！", "沒有組字時Shift+1輸入驚嘆號")
    engine.clear(); engine.selectMode(.pinyin); h.type("nihao")
    h.check(engine.candidateShortcut(0) == "1", "拼音顯示數字選詞")
    engine.key(49); h.check(engine.takeCommit() == "你好", "拼音數字1直接上屏")
    h.type("sushi")
    let thirdPinyin = engine.snapshot.candidates[2]
    engine.key(51); h.check(engine.takeCommit() == thirdPinyin, "拼音數字3選詞不當聲調")
    h.type("shi"); engine.key(0xff56)
    let nextPinyin = engine.snapshot.candidates[0]
    engine.key(49); h.check(engine.takeCommit() == nextPinyin, "拼音換頁後數字索引正確")
    engine.clear(); h.type("nihap")
    h.check(engine.snapshot.candidates.first == "你好", "常見鄰鍵誤拼優先於無意義拼接")
    engine.key(49); h.check(engine.takeCommit() == "你好", "誤拼候選數字選取")
    engine.clear(); h.type("nihaozzzv")
    engine.key(49)
    h.check(engine.takeCommit() == "你" && engine.snapshot.input == "haozzzv", "單字回退保留未轉換拼音")
    engine.clear(); engine.selectMode(.zhuyin); h.type("su3v")
    engine.choose(0)
    h.check(engine.takeCommit() == "你" && engine.snapshot.input == "v", "注音單字回退保留後續注音")
    engine.clear(); engine.selectMode(.pinyin); h.type("wox")
    h.check(engine.snapshot.input == "wox" && engine.snapshot.candidates.first == "我想", "未完成拼音補全常用詞")
    engine.clear(); h.type("vvvvvv")
    h.check(engine.snapshot.candidates.isEmpty && engine.snapshot.preedit == "vvvvvv", "零候選仍保留組字")
    engine.key(0xff08); h.check(engine.snapshot.preedit == "vvvvv", "零候選可退格")
    engine.key(0xff1b); h.check(engine.snapshot.preedit.isEmpty, "取消後清空輸入欄")
    engine.selectMode(.zhuyin); h.type("su3cl3")
    h.check(engine.snapshot.candidates.first == "你好" && engine.candidateShortcut(0) == "⇧1", "注音數字仍作聲調")
    engine.clear(); h.type("su"); engine.key(32)
    h.check(engine.snapshot.input == "su " && engine.takeCommit().isEmpty, "注音空格仍輸入一聲，不提早選詞")
    engine.clear(); engine.selectMode(.pinyin); h.type("nadoushi")
    h.check(engine.snapshot.candidates.first == "那都是" && !engine.snapshot.literalCandidate, "拼音詞中ous不誤判英文")
    engine.key(32); h.check(engine.takeCommit() == "那都是", "拼音空格選中第一候選")
    h.type("emoji"); h.check(engine.snapshot.candidates.first == "emoji", "emoji英文優先")
    engine.key(32); h.check(engine.takeCommit() == "emoji", "macOS 拼音空格保留英文不加空格")
    engine.clear(); h.type("nihao"); engine.key(32)
    h.check(engine.takeCommit() == "你好", "拼音空格選中中文候選")
    engine.clear(); h.type("sushi")
    engine.key(0xff54)
    let highlighted = engine.snapshot.candidates[engine.snapshot.highlighted]
    engine.key(32)
    h.check(engine.takeCommit() == highlighted, "拼音空格選中目前高亮的候選")
    engine.clear(); h.type("nihaox")
    if let prefix = engine.snapshot.candidates.firstIndex(of: "你好") {
        let details = engine.snapshot.candidateDetails
        h.check(details.count == engine.snapshot.candidates.count && details[prefix].text == "你好" &&
              details[prefix].source == 0 && details[prefix].backendIndex != nil &&
              details[prefix].metadata?.remainingInput == "x", "拼音候選保留後端索引與原始尾碼")
        engine.snapshot.highlighted = prefix
        engine.key(32)
        h.check(engine.takeCommit() == "你好" && engine.snapshot.input == "x", "空格選取部分詞組時保留未完成讀音")
    } else { h.check(false, "空格部分詞組候選可用") }
    engine.clear(); h.type("vvvvvv"); engine.key(32)
    h.check(engine.takeCommit() == "vvvvvv", "無候選時空格保留原文且不加空格")
    if let defaultSpace = try? ChineseWithJapaneseBackend(mode: .pinyin) {
        for key in "nihao".utf8 { _ = defaultSpace.key(Int32(key), modifiers: 0) }
        _ = defaultSpace.key(32, modifiers: 0)
        h.check(defaultSpace.snapshot().commit == "nihao ", "共用引擎保留其他平台的原有空格行為")
    } else { h.check(false, "共用引擎可載入") }
    engine.clear(); h.type("nihao"); engine.key(0xff0d)
    h.check(engine.takeCommit() == "你好", "Enter仍確認中文")
    // Pinyin keeps the bare kana spelling, after every Chinese candidate.
    for (input, kana) in [("a", "あ"), ("ka", "か"), ("shi", "し"), ("n", "ん"), ("xa", "ぁ")] {
        engine.clear(); h.type(input)
        h.check(kanaRankedLast(engine, kana), "假名拼寫排在所有中文之後 " + input)
    }
    engine.clear(); h.type("k")
    h.check(engine.snapshot.candidates.contains("k"), "未完成字母保留原文候選")
    engine.clear(); h.type("youhua")
    h.check(engine.snapshot.candidates.first == "優化", "完整羅馬音仍以中文優先")
    h.check(kanaRankedLast(engine, "ようふあ"), "完整羅馬音的假名拼寫排在所有中文之後")

    runSmokePinyinAndMemory(&h)
    runSmokeMixedAndLayout(&h)
    runCandidateWindowChecks(&h)

    ChoiceMemory.shared.clear()
    ChoiceMemory.shared.flush()
    try? FileManager.default.removeItem(at: Preferences.directory)
    UserDefaults(suiteName: "app.meowpuut.smoke")?.removePersistentDomain(forName: "app.meowpuut.smoke")
    print(h.failures == 0 ? "全部通過" : "\(h.failures) 項未通過")
    exit(h.failures == 0 ? 0 : 1)
}
