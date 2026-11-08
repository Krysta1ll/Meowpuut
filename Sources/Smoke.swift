import AppKit
import InputMethodKit
import SQLite3

func runIndependentSmoke() -> Never {
    setbuf(stdout, nil)
    var failures = 0
    func check(_ ok: Bool, _ message: String) { print("\(ok ? "PASS" : "FAIL")：\(message)"); if !ok { failures += 1 } }
    let menuItem = NSMenuItem(title: "測試皮膚", action: nil, keyEquivalent: "")
    menuItem.representedObject = true
    check(SystemInput.menuItem(from: [kIMKCommandMenuItemName: menuItem]) === menuItem, "系統輸入選單解析 IMK 指令字典")
    check(SystemInput.menuItem(from: menuItem) === menuItem && SystemInput.menuItem(from: [:]) == nil, "選單兼容直接點選並拒絕空指令")
    let hostText = NSTextView(frame: .zero)
    hostText.string = "保留原文"
    hostText.setSelectedRange(NSRange(location: 0, length: 4))
    func markHost(_ text: String, wasComposing: Bool, didCommit: Bool = false) {
        SystemInput.updateMarkedText(text, wasComposing: wasComposing, didCommit: didCommit) { text, selection in
            hostText.setMarkedText(text, selectedRange: selection, replacementRange: NSRange(location: NSNotFound, length: 0))
        }
    }
    markHost("", wasComposing: false)
    check(hostText.string == "保留原文" && hostText.selectedRange() == NSRange(location: 0, length: 4), "空閒輸入法不刪除宿主選區")
    markHost("ni", wasComposing: false)
    check(hostText.string == "ni" && hostText.hasMarkedText(), "新組字替換選區並標記")
    markHost("", wasComposing: true)
    check(hostText.string.isEmpty && !hostText.hasMarkedText(), "取消組字可移除自己的標記文字")
    markHost("ni", wasComposing: false)
    hostText.insertText("你", replacementRange: NSRange(location: NSNotFound, length: 0))
    hostText.setSelectedRange(NSRange(location: 0, length: 1))
    markHost("", wasComposing: true, didCommit: true)
    check(hostText.string == "你" && hostText.selectedRange() == NSRange(location: 0, length: 1), "提交後不以空標記覆寫宿主選區")
    hostText.setSelectedRange(NSRange(location: 1, length: 0))
    markHost("ㄋㄧ🐈", wasComposing: true, didCommit: true)
    check(hostText.string == "你ㄋㄧ🐈" && hostText.selectedRange().location == 5, "分段提交後可標記 UTF-16 後綴")
    let testScreens = [NSRect(x: 0, y: 0, width: 1000, height: 1000)]
    let caret = NSRect(x: 180, y: 170, width: 1, height: 20)
    var queried: [Int] = []
    let initial = SystemInput.caretRect(markedText: nil, screens: testScreens) { index in queried.append(index); return caret }
    check(initial == caret && queried == [0], "沒有組字時查詢目前選區")
    queried = []
    let end = SystemInput.caretRect(markedText: "你好🐈", screens: testScreens) { index in queried.append(index); return caret }
    check(end == caret && queried == [4], "組字使用 UTF-16 末尾定位游標")
    queried = []
    let fallback = SystemInput.caretRect(markedText: "你好🐈", screens: testScreens) { index in
        queried.append(index)
        return index == 4 ? .zero : caret
    }
    check(fallback == caret && queried == [4, 2], "末尾無效時回退到最後完整字元")
    queried = []
    let invalid = SystemInput.caretRect(markedText: "你好", screens: testScreens) { index in
        queried.append(index)
        return NSRect(x: 2000, y: 2000, width: 1, height: 20)
    }
    check(invalid == nil && queried == [2, 1], "座標無效時保留先前錨點")
    Preferences.shared.simplified = false
    Preferences.shared.rememberChoices = false
    Preferences.shared.pins = [:]
    let appearanceDefaults = UserDefaults(suiteName: "app.meowpuut.smoke")!
    Preferences.shared.candidateTone = .light
    Preferences.shared.candidatePalette = .sage
    Preferences.shared.glassStrength = .vivid
    check(appearanceDefaults.string(forKey: "glassStrength") == "vivid", "玻璃強度會儲存")
    check(appearanceDefaults.string(forKey: "candidateTone") == "light" && appearanceDefaults.string(forKey: "candidatePalette") == "sage", "深淺色調與色系分開持久儲存")
    Preferences.shared.catGlassSkin = true
    check(Preferences.shared.candidateTone == .light && Preferences.shared.candidatePalette == .sage, "切換皮膚保留共用配色")
    let engine = Engine()
    check(engine.isReady, "自研注音核心載入")
    func type(_ value: String) { for key in value.utf8 { engine.key(Int32(key)) } }
    let physicalCharacters: [UInt16: (String, String)] = [
        44: ("/", "?"), 42: ("\\", "|"), 27: ("-", "_"), 39: ("'", "\""), 41: (";", ":"),
        29: ("0", ")"), 18: ("1", "!"), 19: ("2", "@"), 20: ("3", "#"),
        21: ("4", "$"), 23: ("5", "%"), 22: ("6", "^"), 26: ("7", "&"),
        28: ("8", "*"), 25: ("9", "(")
    ]
    func physical(_ keyCode: UInt16, shift: Bool = false, option: Bool = false) -> Bool {
        var flags: NSEvent.ModifierFlags = []
        if shift { flags.insert(.shift) }
        if option { flags.insert(.option) }
        let chars = physicalCharacters[keyCode] ?? ("", "")
        guard let event = NSEvent.keyEvent(with: .keyDown, location: .zero,
                modifierFlags: flags, timestamp: 0, windowNumber: 0,
                context: nil, characters: shift ? chars.1 : chars.0, charactersIgnoringModifiers: chars.0,
                isARepeat: false, keyCode: keyCode), let (key, modifiers) = inputKey(event, mode: engine.inputMode, english: engine.english, composing: !engine.snapshot.input.isEmpty) else { return false }
        return engine.key(key, modifiers: modifiers)
    }
    func layoutEvent(_ keyCode: UInt16, _ characters: String, shift: Bool = false) -> NSEvent {
        NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: shift ? [.shift] : [],
                         timestamp: 0, windowNumber: 0, context: nil, characters: characters,
                         charactersIgnoringModifiers: characters, isARepeat: false, keyCode: keyCode)!
    }
    let azerty = layoutEvent(0, "q")
    check(inputKey(azerty, mode: .pinyin)?.0 == 113 && inputKey(azerty, mode: .pinyin, english: true)?.0 == 113, "拼音與英文跟隨實際鍵盤佈局")
    check(inputKey(azerty, mode: .zhuyin)?.0 == 97, "注音保留大千實體鍵位")
    check(inputKey(layoutEvent(0, "é"), mode: .pinyin) == nil && inputKey(layoutEvent(0, ""), mode: .pinyin, english: true) == nil, "非 ASCII 與死鍵交給系統")
    check(inputKey(layoutEvent(22, "§", shift: true), mode: .pinyin, composing: true)?.0 == 94, "拼音聲調保留大千實體快捷鍵")
    check(inputKey(layoutEvent(22, "§", shift: true), mode: .pinyin) == nil, "沒有組字時非 ASCII 標點交給系統")
    check(inputKey(layoutEvent(76, "\r"), mode: .pinyin)?.0 == 0xff0d, "小鍵盤 Enter 映射為確認")
    engine.selectMode(.pinyin)
    if let (key, modifiers) = inputKey(azerty, mode: .pinyin) { engine.key(key, modifiers: modifiers) }
    check(engine.snapshot.input == "q", "非 US 按鍵實際輸入拼音字母")
    engine.clear(); type("nihao")
    if let (key, modifiers) = inputKey(layoutEvent(76, "\r"), mode: .pinyin, composing: true) { engine.key(key, modifiers: modifiers) }
    check(engine.takeCommit() == "你好", "小鍵盤 Enter 實際確認中文")
    engine.clear()
    func expect(_ mode: InputMode, _ input: String, _ expected: String) {
        engine.clear(); engine.selectMode(mode); type(input)
        print("\(mode.rawValue) \(input) → \(engine.snapshot.candidates)")
        guard let index = engine.snapshot.candidates.firstIndex(of: expected) else { check(false, "\(expected) 首頁候選"); return }
        engine.choose(index)
        check(engine.takeCommit() == expected && engine.takeCommit().isEmpty, "\(expected) 提交一次")
    }
    for (input, expected) in [("su3cl3", "你好"), ("sucl", "你好"), ("5j/4", "重")] { expect(.zhuyin, input, expected) }
    for (input, expected) in [("nihao", "你好"), ("nvhai", "女孩"), ("xi'an", "西安"), ("nh", "你好"), ("sushi", "蘇軾"), ("zhongguo", "中國"), ("woxianghekafei", "我想喝咖啡")] { expect(.pinyin, input, expected) }
    expect(.zhuyin, "c04y4", "漢字")
    expect(.zhuyin, "ji3ap7rup wu0 fm4d042u04u/3", "我們今天去看電影")
    expect(.zhuyin, "rup wu0 wu0 fu4cp3cl3ji3vu;3fm4ej/ m06n041j4", "今天天氣很好我想去公園散步")
    engine.clear(); engine.selectMode(.zhuyin)
    let longReading = String(repeating: "su3cl3", count: 16)
    type(longReading)
    check(engine.snapshot.input == longReading, "長句超過80鍵不丟失輸入")
    check(engine.snapshot.candidates.contains(String(repeating: "你好", count: 16)), "32字長句可選")
    engine.key(0xff0d)
    check(engine.takeCommit() == String(repeating: "你好", count: 16), "32字長句回車上屏")
    expect(.zhuyin, "su3cl3", "你好")
    engine.clear(); engine.selectMode(.pinyin); type("shi")
    for _ in 0..<4 { engine.key(0xff56) }
    check(engine.snapshot.page == 4 && !engine.snapshot.candidates.isEmpty, "候選可翻到第29項之後")
    let fifthPage = engine.snapshot.candidates.first ?? ""
    engine.choose(0)
    check(!fifthPage.isEmpty && engine.takeCommit() == fifthPage, "第五頁索引可正確上屏")
    engine.clear(); engine.selectMode(.zhuyin)
    var overflowText = ""
    let overflowReading = String(repeating: "su3cl3", count: 44)
    for key in overflowReading.utf8 { engine.key(Int32(key)); overflowText += engine.takeCommit() }
    engine.key(0xff0d, modifiers: 1); overflowText += engine.takeCommit()
    let limit = Int(mpn_max_input_bytes())
    let zhuyinSymbols: [Character: String] = ["s": "ㄋ", "u": "ㄧ", "3": "ˇ", "c": "ㄏ", "l": "ㄠ"]
    let originalPrefix = overflowReading.prefix(limit).map { zhuyinSymbols[$0] ?? String($0) }.joined()
    check(overflowText.hasPrefix(originalPrefix) && overflowText.hasSuffix(String(overflowReading.suffix(8))),
          "超出上限保留原注音並接收後續按鍵")

    // Exercise the platform-neutral wrapper directly without consuming each
    // snapshot: committed readings must survive replay, selection and cancel.
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
            check(pinyinState.commit == rawPinyin && pinyinState.input == "ni" && pinyin.snapshot().commit.isEmpty,
                  "拼音滿長度保留原讀音、續打且只上屏一次")
            let zhuyin = try atLimit(.zhuyin, key: 115)
            zhuyin.setSimplified(simplified)
            _ = zhuyin.key(117, modifiers: 0); _ = zhuyin.key(51, modifiers: 0)
            let zhuyinState = zhuyin.snapshot()
            check(zhuyinState.commit == rawZhuyin && zhuyinState.input == "u3" && zhuyin.snapshot().commit.isEmpty,
                  "注音滿長度保留原讀音、續打帶調且只上屏一次")
        }
        let pinyinTone = try atLimit(.pinyin, key: 118)
        _ = pinyinTone.key(49, modifiers: 0)
        let pinyinToneState = pinyinTone.snapshot()
        check(pinyinToneState.commit == rawPinyin + "1" && pinyinToneState.input.isEmpty, "拼音上限的聲調接在原讀音後")
        let zhuyinTone = try atLimit(.zhuyin, key: 115)
        _ = zhuyinTone.key(54, modifiers: 0)
        let zhuyinToneState = zhuyinTone.snapshot()
        check(zhuyinToneState.commit == rawZhuyin + "ˊ" && zhuyinToneState.input.isEmpty, "注音上限的聲調接在原讀音後")
        let zhuyinSpace = try atLimit(.zhuyin, key: 115)
        _ = zhuyinSpace.key(32, modifiers: 0)
        let zhuyinSpaceState = zhuyinSpace.snapshot()
        check(zhuyinSpaceState.commit == rawZhuyin + "ˉ" && zhuyinSpaceState.input.isEmpty, "注音上限的一聲不會丟失")
        for mode in [InputMode.pinyin, .zhuyin] {
            let separator = try atLimit(mode, key: mode == .pinyin ? 118 : 115)
            _ = separator.key(39, modifiers: 0)
            let separatorState = separator.snapshot()
            check(separatorState.commit == (mode == .pinyin ? rawPinyin + "'" : rawZhuyin + " ") && separatorState.input.isEmpty,
                  "拼音和注音上限的分隔符不會丟失")
        }
        for addsSpace in [false, true] {
            let space = try atLimit(.pinyin, key: 118, literalSpaceAddsSpace: addsSpace)
            _ = space.key(32, modifiers: 0)
            check(space.snapshot().commit == rawPinyin + (addsSpace ? " " : ""), "滿長度保留各平台拼音空格設定")
        }
        let pending = try atLimit(.pinyin, key: 118, count: 2 * limit)
        _ = pending.key(118, modifiers: 0)
        let pendingState = pending.snapshot()
        check(pendingState.commit == rawPinyin + rawPinyin && pendingState.input == "v", "兩次上限切段保留尚未交付的文字")
        let cancel = try atLimit(.pinyin, key: 118)
        _ = cancel.key(110, modifiers: 0); _ = cancel.key(0xff1b, modifiers: 0)
        let cancelState = cancel.snapshot()
        check(cancelState.commit == rawPinyin && cancelState.input.isEmpty, "取消新組字不會丟棄已切段原文")
        let noCandidate = try atLimit(.pinyin, key: 118)
        for _ in 0..<6 { _ = noCandidate.key(118, modifiers: 0) }
        check(noCandidate.candidates(limit: 1).isEmpty, "滿長度後仍可辨識無候選的後綴")
        noCandidate.commit()
        let noCandidateState = noCandidate.snapshot()
        check(noCandidateState.commit == rawPinyin + "vvvvvv" && noCandidateState.input.isEmpty,
              "確認無候選後綴保留先前未交付的原讀音")
        let choice = try atLimit(.pinyin, key: 118)
        _ = choice.key(110, modifiers: 0); _ = choice.key(105, modifiers: 0)
        if let candidate = choice.candidates(limit: 256).first(where: { $0.text == "你" }) {
            _ = choice.choose(candidate.index)
            let choiceState = choice.snapshot()
            check(choiceState.commit == rawPinyin + "你" && choiceState.input.isEmpty, "選擇後綴候選不會覆蓋已切段原文")
        } else { check(false, "切段後可選後綴候選") }
        let locked = try ChineseWithJapaneseBackend(mode: .zhuyin)
        for key in "su3cl3v".utf8 { _ = locked.key(Int32(key), modifiers: 0) }
        if let candidate = locked.candidates(limit: 256).first(where: { $0.text == "你好" }) {
            _ = locked.choose(candidate.index)
            let selected = locked.snapshot()
            check(selected.commit == "你好" && selected.input == "v", "注音部分選詞保留剩餘組字")
            for _ in 1..<limit { _ = locked.key(118, modifiers: 0) }
            _ = locked.key(117, modifiers: 0); _ = locked.key(51, modifiers: 0)
            let lockedState = locked.snapshot()
            check(lockedState.commit == String(repeating: "ㄒ", count: limit) && lockedState.input == "u3",
                  "注音部分選詞後滿長度仍保留讀音與新鍵")
        } else { check(false, "注音部分選詞候選可用") }
    } catch { check(false, "組字上限測試引擎可載入") }

    engine.clear(); engine.selectMode(.zhuyin); type("su3cl3"); engine.key(60, modifiers: 1)
    check(engine.takeCommit() == "你好，", "注音接中文逗號並上屏")
    type("ji3ap7rup wu0 fm4d042u04u/3"); engine.key(62, modifiers: 1)
    check(engine.takeCommit() == "我們今天去看電影。", "注音長句接中文句號")
    type("su3cl3"); engine.key(63, modifiers: 1)
    check(engine.takeCommit() == "你好？", "注音接中文問號")
    engine.key(91); check(engine.takeCommit() == "「", "中文引號不作注音鍵")
    engine.key(93); check(engine.takeCommit() == "」", "中文右引號")
    type("su3cl3"); engine.key(96)
    check(engine.takeCommit() == "你好" && engine.snapshot.candidates.contains("；"), "標點選單先提交組字")
    engine.key(54); check(engine.takeCommit() == "；" && engine.snapshot.preedit.isEmpty, "標點選單數字選擇")
    engine.key(96); engine.key(0xff56); engine.key(49)
    check(engine.takeCommit() == "「", "標點選單翻頁索引")
    engine.key(96); engine.key(0xff1b)
    check(engine.snapshot.preedit.isEmpty && engine.takeCommit().isEmpty, "取消標點不插入文字")
    type("su3cl3"); engine.key(33, modifiers: 1)
    check(engine.takeCommit() == "你好", "Shift+1仍然選第一候選")
    engine.key(33, modifiers: 1); check(engine.takeCommit() == "！", "沒有組字時Shift+1輸入驚嘆號")
    engine.clear(); engine.selectMode(.pinyin); type("nihao")
    check(engine.candidateShortcut(0) == "1", "拼音顯示數字選詞")
    engine.key(49); check(engine.takeCommit() == "你好", "拼音數字1直接上屏")
    type("sushi")
    let thirdPinyin = engine.snapshot.candidates[2]
    engine.key(51); check(engine.takeCommit() == thirdPinyin, "拼音數字3選詞不當聲調")
    type("shi"); engine.key(0xff56)
    let nextPinyin = engine.snapshot.candidates[0]
    engine.key(49); check(engine.takeCommit() == nextPinyin, "拼音換頁後數字索引正確")
    engine.clear(); type("nihap")
    check(engine.snapshot.candidates.first == "你好", "常見鄰鍵誤拼優先於無意義拼接")
    engine.key(49); check(engine.takeCommit() == "你好", "誤拼候選數字選取")
    engine.clear(); type("nihaozzzv")
    engine.key(49)
    check(engine.takeCommit() == "你" && engine.snapshot.input == "haozzzv", "單字回退保留未轉換拼音")
    engine.clear(); engine.selectMode(.zhuyin); type("su3v")
    engine.choose(0)
    check(engine.takeCommit() == "你" && engine.snapshot.input == "v", "注音單字回退保留後續注音")
    engine.clear(); engine.selectMode(.pinyin); type("wox")
    check(engine.snapshot.input == "wox" && engine.snapshot.candidates.first == "我想", "未完成拼音補全常用詞")
    engine.clear(); type("vvvvvv")
    check(engine.snapshot.candidates.isEmpty && engine.snapshot.preedit == "vvvvvv", "零候選仍保留組字")
    engine.key(0xff08); check(engine.snapshot.preedit == "vvvvv", "零候選可退格")
    engine.key(0xff1b); check(engine.snapshot.preedit.isEmpty, "取消後清空輸入欄")
    engine.selectMode(.zhuyin); type("su3cl3")
    check(engine.snapshot.candidates.first == "你好" && engine.candidateShortcut(0) == "⇧1", "注音數字仍作聲調")
    engine.clear(); type("su"); engine.key(32)
    check(engine.snapshot.input == "su " && engine.takeCommit().isEmpty, "注音空格仍輸入一聲，不提早選詞")
    engine.clear(); engine.selectMode(.pinyin); type("nadoushi")
    check(engine.snapshot.candidates.first == "那都是" && !engine.snapshot.literalCandidate, "拼音詞中ous不誤判英文")
    engine.key(32); check(engine.takeCommit() == "那都是", "拼音空格選中第一候選")
    type("emoji"); check(engine.snapshot.candidates.first == "emoji", "emoji英文優先")
    engine.key(32); check(engine.takeCommit() == "emoji", "macOS 拼音空格保留英文不加空格")
    engine.clear(); type("nihao"); engine.key(32)
    check(engine.takeCommit() == "你好", "拼音空格選中中文候選")
    engine.clear(); type("sushi")
    engine.key(0xff54)
    let highlighted = engine.snapshot.candidates[engine.snapshot.highlighted]
    engine.key(32)
    check(engine.takeCommit() == highlighted, "拼音空格選中目前高亮的候選")
    engine.clear(); type("nihaox")
    if let prefix = engine.snapshot.candidates.firstIndex(of: "你好") {
        let details = engine.snapshot.candidateDetails
        check(details.count == engine.snapshot.candidates.count && details[prefix].text == "你好" &&
              details[prefix].source == 0 && details[prefix].backendIndex != nil &&
              details[prefix].metadata?.remainingInput == "x", "拼音候選保留後端索引與原始尾碼")
        engine.snapshot.highlighted = prefix
        engine.key(32)
        check(engine.takeCommit() == "你好" && engine.snapshot.input == "x", "空格選取部分詞組時保留未完成讀音")
    } else { check(false, "空格部分詞組候選可用") }
    engine.clear(); type("vvvvvv"); engine.key(32)
    check(engine.takeCommit() == "vvvvvv", "無候選時空格保留原文且不加空格")
    if let defaultSpace = try? ChineseWithJapaneseBackend(mode: .pinyin) {
        for key in "nihao".utf8 { _ = defaultSpace.key(Int32(key), modifiers: 0) }
        _ = defaultSpace.key(32, modifiers: 0)
        check(defaultSpace.snapshot().commit == "nihao ", "共用引擎保留其他平台的原有空格行為")
    } else { check(false, "共用引擎可載入") }
    engine.clear(); type("nihao"); engine.key(0xff0d)
    check(engine.takeCommit() == "你好", "Enter仍確認中文")
    for (input, kana) in [("a", "あ"), ("ka", "か"), ("shi", "し"), ("n", "ん"), ("xa", "ぁ")] {
        engine.clear(); type(input)
        var found = engine.snapshot.candidates.contains(kana)
        for _ in 0..<40 where !found && !engine.snapshot.lastPage {
            engine.key(0xff56); found = engine.snapshot.candidates.contains(kana)
        }
        check(found, "短羅馬音候選可展開翻頁 " + input)
    }
    engine.clear(); type("k")
    check(engine.snapshot.candidates.contains("k"), "未完成字母保留原文候選")
    engine.clear(); type("youhua")
    check(engine.snapshot.candidates.first == "優化", "完整羅馬音仍以中文優先")
    check(engine.snapshot.requiredCandidate == "ようふあ", "完整羅馬音保留假名候選")
    for input in ["watashihaneko", "ashitahaame", "gakkou", "shin'ya"] {
        engine.clear(); type(input)
        let kana = Romaji.reading(input)
        check(engine.snapshot.requiredCandidate != nil && engine.snapshot.japaneseCandidates.contains(kana), "完整羅馬音標記 " + input)
        let visible = collapsedCandidateIndices(engine.snapshot)
        check(visible.contains { compactCandidateText(engine.snapshot, at: $0) == engine.snapshot.requiredCandidate }, "日語候選收合可見 " + input)
        if let index = engine.snapshot.candidates.firstIndex(of: kana) {
            engine.choose(index)
            check(engine.takeCommit() == kana, "貓爪不隨文字上屏 " + input)
        }
    }
    let crowdedJapanese = Snapshot(candidates: ["甲", "乙", "丙", "丁", "戊", "己", "あ"],
        requiredCandidate: "甲", confidentCandidates: ["甲", "乙", "丙", "丁", "戊", "己"], japaneseCandidates: ["あ"])
    check(collapsedCandidateIndices(crowdedJapanese).contains(6), "中文同形詞不占用日語保留位置")
    let chineseOnly = Snapshot(candidates: ["我", "握", "窩", "臥", "沃"])
    let mixedSingle = Snapshot(candidates: ["我", "握", "を", "窩", "ヲ", "臥", "沃"], japaneseCandidates: ["を", "ヲ"])
    let mixedVisible = collapsedCandidateIndices(mixedSingle).map { mixedSingle.candidates[$0] }
    check(mixedVisible.filter { !mixedSingle.japaneseCandidates.contains($0) }.count == collapsedCandidateIndices(chineseOnly).count, "單字日語不擠掉中文位置")
    check(mixedVisible.filter { mixedSingle.japaneseCandidates.contains($0) }.count == 1, "單字最多一個日語")
    engine.clear(); engine.selectMode(.pinyin); type("d")
    check(engine.snapshot.candidates.first != "d" && engine.snapshot.candidates.contains("的"), "單字母d優先中文")
    check(engine.snapshot.candidates.contains("d"), "單字母仍可選英文")
    if let index = engine.snapshot.candidates.firstIndex(of: "d") {
        engine.choose(index); check(engine.takeCommit() == "d", "單字母英文可上屏")
    }
    engine.clear(); engine.selectMode(.zhuyin); type("d")
    check(engine.snapshot.candidates.first != "d" && engine.snapshot.candidates.contains("可"), "注音單聲母也優先中文")
    engine.clear(); engine.selectMode(.pinyin)
    expect(.pinyin, "miao", "喵")
    expect(.pinyin, "muao", "喵")
    expect(.zhuyin, "aul ", "喵")
    for input in ["wo", "muao", "sushi"] {
        engine.clear(); engine.selectMode(.pinyin); type(input)
        let visible = collapsedCandidateIndices(engine.snapshot)
        check((!engine.snapshot.candidates.contains(where: { engine.snapshot.japaneseCandidates.contains($0) }) && engine.snapshot.compactJapanese == nil) ||
              visible.contains(where: { compactCandidateText(engine.snapshot, at: $0).map(engine.snapshot.japaneseCandidates.contains) ?? false }),
              "收合保留日語候選 " + input)
    }
    let shownOrder = Snapshot(candidates: ["感", "乾", "がん", "敢", "趕", "柑", "ガン"], japaneseCandidates: ["がん", "ガン"])
    let shownIndices = collapsedCandidateIndices(shownOrder)
    check(shownIndices.contains(2) && shownIndices == shownIndices.sorted() &&
          shownIndices.filter { shownOrder.japaneseCandidates.contains(shownOrder.candidates[$0]) }.count == 1,
          "收合保留日語並遵循引擎次序")
    check(shownIndices.firstIndex(of: 2).map { $0 < shownIndices.count - 1 } == true,
          "收合日語不固定排在最後")
    let laterJapanese = Snapshot(candidates: ["甲", "乙", "丙", "丁", "戊", "己", "庚"],
        japaneseCandidates: ["寿司"], compactJapanese: IndexedCandidate(index: 9, text: "寿司", source: 1))
    let laterIndices = collapsedCandidateIndices(laterJapanese)
    let laterSlot = laterJapanese.candidates.count
    check(laterIndices.contains(laterSlot) && compactCandidateText(laterJapanese, at: laterSlot) == "寿司" &&
          laterIndices.filter { $0 < laterSlot }.count == collapsedCandidateIndices(Snapshot(candidates: laterJapanese.candidates)).count,
          "收合保留後頁日語且不減少中文")
    if let position = laterIndices.firstIndex(of: laterSlot) {
        check(collapsedCandidateShortcut(laterJapanese, key: Int32(49 + position), modifiers: 0, shifted: false, english: false) == .choose(laterSlot),
              "後頁日語快捷鍵對應收合候選")
    } else { check(false, "後頁日語快捷鍵對應收合候選") }
    for (ordinal, expected) in shownIndices.enumerated() {
        check(collapsedCandidateShortcut(shownOrder, key: Int32(49 + ordinal), modifiers: 0, shifted: false, english: false) == .choose(expected),
              "收合拼音顯示序號對應候選 \(ordinal + 1)")
        let shiftedKey = Int32(Array("!@#$%^&".utf8)[ordinal])
        check(collapsedCandidateShortcut(shownOrder, key: shiftedKey, modifiers: 1, shifted: true, english: false) == .choose(expected),
              "收合注音顯示序號對應候選 \(ordinal + 1)")
        var navigating = shownOrder
        navigating.highlighted = expected
        check(collapsedCandidateMove(navigating, key: 0xff09, modifiers: 0) == shownIndices[(ordinal + 1) % shownIndices.count] &&
              collapsedCandidateMove(navigating, key: 0xff52, modifiers: 0) == shownIndices[(ordinal + shownIndices.count - 1) % shownIndices.count] &&
              collapsedCandidateMove(navigating, key: 0xff09, modifiers: 1) == shownIndices[(ordinal + shownIndices.count - 1) % shownIndices.count],
              "收合方向選擇遵循可見順序 \(ordinal + 1)")
    }
    check(collapsedCandidateShortcut(shownOrder, key: 49, modifiers: 0, shifted: false, english: true) == .pass &&
          collapsedCandidateShortcut(shownOrder, key: 49, modifiers: 0, shifted: true, english: false) == .pass &&
          collapsedCandidateShortcut(shownOrder, key: 33, modifiers: 1, shifted: false, english: false) == .pass,
          "英文與注音聲調及拼音聲調不攔截")
    let hiddenOrder = Snapshot(candidates: ["長詞一二三四五六", "長詞七八九十", "長詞其他", "長詞四", "長詞五", "長詞六", "長詞七"])
    let hiddenCount = collapsedCandidateIndices(hiddenOrder).count
    check(hiddenCount < 7 && collapsedCandidateShortcut(hiddenOrder, key: Int32(49 + hiddenCount), modifiers: 0, shifted: false, english: false) == .expand,
          "未顯示候選的數字先展開避免誤選")
    for input in ["gan", "jixu", "suki"] {
        engine.clear(); engine.selectMode(.pinyin); type(input)
        let snapshot = engine.snapshot
        let visible = collapsedCandidateIndices(snapshot)
        let japaneseVisible = visible.filter { compactCandidateText(snapshot, at: $0).map(snapshot.japaneseCandidates.contains) ?? false }
        check(japaneseVisible.count == (snapshot.compactJapanese != nil || snapshot.candidates.contains(where: { snapshot.japaneseCandidates.contains($0) }) ? 1 : 0) &&
              visible == visible.sorted(), "真實拼音收合保留日語並遵循引擎次序 \(input)")
        if let position = japaneseVisible.first.flatMap({ visible.firstIndex(of: $0) }) {
            let expected = compactCandidateText(snapshot, at: visible[position]) ?? ""
            if case .choose(let index) = collapsedCandidateShortcut(snapshot, key: Int32(49 + position), modifiers: 0, shifted: false, english: false) {
                if index == snapshot.candidates.count { engine.chooseCompactJapanese() } else { engine.choose(index) }
                check(engine.takeCommit() == expected, "真實日語按可見序號上屏 \(input)")
            } else { check(false, "真實日語按可見序號上屏 \(input)") }
        }
    }
    engine.clear(); engine.selectMode(.pinyin); type("gan")
    let arrowSnapshot = engine.snapshot
    let arrowVisible = collapsedCandidateIndices(arrowSnapshot)
    if let target = collapsedCandidateMove(arrowSnapshot, key: 0xff52, modifiers: 0), arrowVisible.last == target {
        let expected = compactCandidateText(arrowSnapshot, at: target) ?? ""
        engine.snapshot.highlighted = target
        if target == arrowSnapshot.candidates.count { engine.chooseCompactJapanese() } else { engine.key(0xff0d) }
        check(engine.takeCommit() == expected, "收合上鍵高亮末項後 Enter 選中同一詞")
    } else { check(false, "收合上鍵高亮末項後 Enter 選中同一詞") }
    // Punctuation and mode switches finish the highlighted candidate inside
    // Engine, without passing through the macOS compact-row shortcut handler.
    for toggle in [false, true] {
        engine.clear(); engine.english = false; engine.selectMode(.pinyin); type("gan")
        if let candidate = engine.snapshot.compactJapanese {
            engine.snapshot.highlighted = engine.snapshot.candidates.count
            if toggle { engine.key(Engine.toggleEnglishKey) } else { engine.key(44) }
            let expected = candidate.text + (toggle ? "" : "，")
            check(engine.takeCommit() == expected && engine.snapshot.input.isEmpty && (!toggle || engine.english),
                  toggle ? "高亮頁外日語後切中英提交同一候選" : "高亮頁外日語後輸入標點提交同一候選")
        } else { check(false, "頁外日語候選可供完成組字測試") }
    }
    engine.english = false
    engine.clear(); engine.selectMode(.zhuyin); type("su3cl3")
    if case .choose(let target) = collapsedCandidateShortcut(engine.snapshot, key: 33, modifiers: 1, shifted: true, english: false) {
        engine.choose(target)
        check(engine.takeCommit() == "你好", "注音收合 Shift 加可見序號實際上屏")
    } else { check(false, "注音收合 Shift 加可見序號實際上屏") }
    engine.clear(); engine.selectMode(.pinyin); type("shi"); engine.key(0xff56)
    let expandedFirst = engine.snapshot.candidates.first ?? ""
    engine.key(49)
    check(!expandedFirst.isEmpty && engine.takeCommit() == expandedFirst, "展開翻頁數字仍按頁內原順序上屏")
    var tap = ShiftTapTracker()
    check(!tap.update(keyCode: 56, modifierEvent: true, shift: true, other: false)
        && tap.update(keyCode: 56, modifierEvent: true, shift: false, other: false), "單按Shift釋放切換一次")
    _ = tap.update(keyCode: 56, modifierEvent: true, shift: true, other: false)
    _ = tap.update(keyCode: 0, modifierEvent: false, shift: true, other: false)
    check(!tap.update(keyCode: 56, modifierEvent: true, shift: false, other: false), "Shift打大寫不切換")
    _ = tap.update(keyCode: 60, modifierEvent: true, shift: true, other: false)
    _ = tap.update(keyCode: 55, modifierEvent: true, shift: true, other: true)
    check(!tap.update(keyCode: 60, modifierEvent: true, shift: false, other: false), "Shift組合快捷鍵不切換")
    var inferredTap = ShiftTapTracker()
    check(!inferredTap.update(keyCode: 0, modifierEvent: true, shift: true, other: false)
        && inferredTap.update(keyCode: 0, modifierEvent: true, shift: false, other: false), "無實體鍵碼的 Shift 事件可切換")
    var mixedTap = ShiftTapTracker()
    check(!mixedTap.update(keyCode: 60, modifierEvent: true, shift: true, other: false)
        && mixedTap.update(keyCode: 0, modifierEvent: true, shift: false, other: false), "Shift 釋放事件缺鍵碼仍可切換")
    var shortcutTap = ShiftTapTracker()
    _ = shortcutTap.update(keyCode: 0, modifierEvent: true, shift: true, other: false)
    _ = shortcutTap.update(keyCode: 12, modifierEvent: false, shift: true, other: false)
    check(!shortcutTap.update(keyCode: 0, modifierEvent: true, shift: false, other: false), "缺鍵碼的 Shift 組合鍵不誤切換")
    engine.clear(); engine.selectMode(.pinyin); engine.english = false
    engine.key(Engine.toggleEnglishKey)
    engine.key(Int32(Character("a").asciiValue!))
    check(engine.english && engine.takeCommit() == "a", "切換英文後直接輸入字母")
    engine.key(Engine.toggleEnglishKey); type("nihao")
    check(!engine.english && engine.snapshot.candidates.first == "你好", "再次切換回拼音")
    if let expandedBackend = try? ChineseWithJapaneseBackend(mode: .pinyin) {
        engine.clear(); engine.selectMode(.pinyin); type("gan")
        for byte in "gan".utf8 { _ = expandedBackend.key(Int32(byte), modifiers: 0) }
        check(engine.snapshot.candidates == expandedBackend.snapshot().candidates, "展開候選保留後端排序")
    } else { check(false, "展開候選排序引擎可載入") }
    for input in ["a", "sushi", "watashihaneko"] {
        engine.clear(); type(input)
        let snapshot = engine.snapshot
        let visible = collapsedCandidateIndices(snapshot)
        check(visible.filter { compactCandidateText(snapshot, at: $0).map(snapshot.japaneseCandidates.contains) ?? false }.count == 1, "收合只顯示一個日語 " + input)
        var japanese = Set(snapshot.candidates.filter { snapshot.japaneseCandidates.contains($0) })
        for _ in 0..<40 where japanese.count < 2 && !engine.snapshot.lastPage {
            engine.key(0xff56)
            japanese.formUnion(engine.snapshot.candidates.filter { snapshot.japaneseCandidates.contains($0) })
        }
        check(japanese.count >= 2, "其餘日語仍可展開翻頁 " + input)
    }
    engine.clear(); type("hell")
    check(engine.snapshot.requiredCandidate == nil, "不完整羅馬音不強制日語候選")
    expect(.japanese, "sora", "空")
    expect(.japanese, "me", "目")
    for (input, expected) in [("oshikatsu", "推し活"), ("tuishihuo", "推し活"), ("seiyuu", "声優"), ("seiyu", "声優"),
                              ("doujinshi", "同人誌"), ("haishin", "配信"), ("utaite", "歌い手"),
                              ("shiawase", "幸せ"), ("bangumi", "番組")] {
        expect(.japanese, input, expected)
    }
    engine.clear(); engine.selectMode(.pinyin); type("woxihuanseiyuu")
    check(engine.snapshot.candidates.contains("我喜歡声優"), "新增日語詞可用於中日混合長句")
    engine.clear(); type("tuishihuo")
    check(engine.snapshot.candidates.contains("推し活") && engine.snapshot.japaneseCandidates.contains("推し活"), "拼音別寫可選標記日語的推し活")
    let oshiVisible = collapsedCandidateIndices(engine.snapshot)
    let oshiJapanese = oshiVisible.filter { compactCandidateText(engine.snapshot, at: $0).map(engine.snapshot.japaneseCandidates.contains) ?? false }
    check(oshiJapanese.count == 1 && oshiVisible == oshiVisible.sorted(), "推し活候選收合保留一個日語並遵循引擎次序")
    if engine.snapshot.candidates.contains(where: { !engine.snapshot.japaneseCandidates.contains($0) && $0.unicodeScalars.contains { (0x4e00...0x9fff).contains(Int($0.value)) } }), let first = oshiVisible.first {
        check(!engine.snapshot.japaneseCandidates.contains(engine.snapshot.candidates[first]), "推し活別寫有中文時中文優先")
    }
    engine.clear(); type("woxihuantuishihuo")
    check(engine.snapshot.candidates.contains("我喜歡推し活"), "推し活別寫可用於中日混合句")
    Preferences.shared.simplified = true; engine.modeChanged()
    engine.clear(); type("woxihuantuishihuo")
    check(engine.snapshot.candidates.contains("我喜欢推し活"), "推し活別寫可用於簡體中日混合句")
    Preferences.shared.simplified = false; engine.modeChanged()
    expect(.zhuyin, "u. cj84", "優化")
    engine.clear(); engine.selectMode(.pinyin)
    Preferences.shared.rememberChoices = true
    ChoiceMemory.shared.forget("py:t:bang")
    for _ in 0..<15 { ChoiceMemory.shared.remember([("py:t:bang", "浜")]) }
    engine.clear(); type("bang")
    check(engine.snapshot.candidates.first == "浜", "個人選詞仍可提升較少用的字")
    let savedBangUses = ChoiceMemory.shared.records["py:t:bang"]?.first?.uses
    Preferences.shared.rememberChoices = false; engine.refresh()
    check(engine.snapshot.input == "bang" && engine.snapshot.candidates.first == "幫", "停用記憶即時恢復常用字且保留組字")
    check(ChoiceMemory.shared.records["py:t:bang"]?.first?.uses == savedBangUses, "停用記憶不刪除個人記錄")
    Preferences.shared.rememberChoices = true; engine.refresh()
    check(engine.snapshot.input == "bang" && engine.snapshot.candidates.first == "浜", "重新啟用記憶可即時恢復個人選詞")
    ChoiceMemory.shared.forget("py:t:bang"); engine.refresh()
    check(engine.snapshot.input == "bang" && engine.snapshot.candidates.first == "幫", "清除記錄即時更新候選")
    engine.clear(); type("nihao"); engine.choose(0)
    Preferences.shared.rememberChoices = false; engine.refresh(); engine.refresh()
    let queuedCommit = engine.takeCommit()
    check(queuedCommit == "你好" && engine.takeCommit().isEmpty, "記憶刷新不遺失或重複待上屏文字")
    engine.clear(); type("nihaox")
    let prefixIndex = engine.snapshot.candidates.firstIndex(of: "你好")
    check(prefixIndex != nil, "未完成後綴保留可選詞組")
    if let prefixIndex {
        engine.choose(prefixIndex)
        Preferences.shared.rememberChoices = true; engine.refresh(); engine.refresh()
        let partialCommit = engine.takeCommit()
        check(partialCommit == "你好" && engine.takeCommit().isEmpty && engine.snapshot.input == "x", "記憶刷新保留部分上屏和未完成後綴")
        engine.key(Int32(Character("i").asciiValue!))
        check(engine.snapshot.input == "xi", "記憶刷新後可繼續輸入後綴")
    }
    // Learn only the selected reading, not the unfinished suffix. The test
    // preferences and memory directory are isolated by the --smoke flag.
    do {
        Preferences.shared.rememberChoices = true
        let file = Preferences.directory.appendingPathComponent("memory-regression.json")
        let memory = ChoiceMemory(file: file)
        memory.remember([("py:s:ni", "你"), ("ctx|我|py:s:ni", "你"), ("ctx|他|py:s:ni", "你"), ("py:s:hao", "好")])
        memory.remember([("ctx|我|py:s:ni", "你")])
        check(memory.preferred(for: "py:s:ni", context: "我").contains("你"), "個人詞庫上下文聯想已記錄")
        memory.forget("py:s:ni")
        let forgotten = ChoiceMemory(file: file)
        check(memory.preferred(for: "py:s:ni", context: "我").isEmpty &&
              forgotten.preferred(for: "py:s:ni", context: "我").isEmpty &&
              forgotten.records["ctx|他|py:s:ni"] == nil && forgotten.records["py:s:hao"] != nil,
              "忘記讀音同步移除其上下文記錄並持久化，保留其他讀音")
        memory.remember([("py:s:ni", "你")])
        memory.clear()
        check(ChoiceMemory(file: file).records.isEmpty, "清空詞庫立即落盤，取消尚未寫入的舊記錄")
        let stored = ["py:s:ni": [RememberedChoice(text: "你", uses: Int.max, lastUsed: 3),
                                   RememberedChoice(text: "你", uses: 2, lastUsed: 2),
                                   RememberedChoice(text: "", uses: 1, lastUsed: 1),
                                   RememberedChoice(text: "妳", uses: -1, lastUsed: 1)]]
        try! JSONEncoder().encode(stored).write(to: file)
        let normalized = ChoiceMemory(file: file)
        normalized.remember([("py:s:ni", "你")])
        check(normalized.records["py:s:ni"]?.count == 1 && normalized.records["py:s:ni"]?.first?.uses == 1_000_000,
              "舊個人詞庫合併重複、排除空值並限制計數，繼續選詞不溢出")
        normalized.clear()
        let damaged = Data("{\"py:s:ni\":".utf8)
        try! damaged.write(to: file)
        let unreadable = ChoiceMemory(file: file)
        unreadable.remember([("py:s:ni", "你")])
        check(!unreadable.flush() && (try? Data(contentsOf: file)) == damaged,
              "讀取損壞的個人詞庫後，普通保存不覆寫原始檔案")
        unreadable.clear()
        check(ChoiceMemory(file: file).records.isEmpty && unreadable.error.isEmpty,
              "明確清空可恢復損壞的個人詞庫")
        try? FileManager.default.removeItem(at: file)
    }
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        Preferences.shared.rememberChoices = true
        for (mode, first, second) in [(InputMode.pinyin, "wo", "ni"), (.zhuyin, "ji3", "su3")] {
            let script = simplified ? "s:" : "t:"
            let prefix = (mode == .pinyin ? "py:" : "") + script
            let phraseKey = prefix + first + second
            let contextKey = "ctx|我|" + prefix + second
            for boundary in ["adjacent", "document", "delete", "move", "space", "english", "forget", "memoryclear", "remembertoggle"] {
                for key in [phraseKey, contextKey] { ChoiceMemory.shared.forget(key) }
                let session = Engine(.meowpuut, mode: mode)
                for key in first.utf8 { session.key(Int32(key)) }
                if let index = session.snapshot.candidates.firstIndex(of: "我") { session.choose(index) }
                check(session.takeCommit() == "我", "上下文邊界測試前段上屏 \(mode)/\(script)/\(boundary)")
                switch boundary {
                case "document": session.clear(); session.resetLearningContext()
                case "delete": check(!session.key(0xff08), "空組字刪除交還宿主")
                case "move": check(!session.key(0xff51), "空組字游標移動交還宿主")
                case "space": check(!session.key(32), "空組字空格交還宿主")
                case "english": session.key(Engine.toggleEnglishKey); session.key(Engine.toggleEnglishKey)
                case "forget": ChoiceMemory.shared.forget(prefix + first)
                case "memoryclear": ChoiceMemory.shared.clear()
                case "remembertoggle": Preferences.shared.rememberChoices = false; Preferences.shared.rememberChoices = true
                default: break
                }
                for key in second.utf8 { session.key(Int32(key)) }
                if let index = session.snapshot.candidates.firstIndex(of: "你") { session.choose(index) }
                check(session.takeCommit() == "你", "上下文邊界測試後段上屏 \(mode)/\(script)/\(boundary)")
                let combined = ChoiceMemory.shared.records[phraseKey]?.contains(where: { $0.text == "我你" }) == true
                let contextual = ChoiceMemory.shared.records[contextKey]?.contains(where: { $0.text == "你" }) == true
                check(combined == (boundary == "adjacent") && contextual == (boundary == "adjacent"),
                      "僅連續選詞學習聯想 \(mode)/\(script)/\(boundary)")
                for key in [prefix + first, prefix + second, phraseKey, contextKey] { ChoiceMemory.shared.forget(key) }
            }
        }
    }
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        Preferences.shared.rememberChoices = true
        let script = simplified ? "s:" : "t:"
        let prefix = "py:" + script + "nihao"
        let unfinished = prefix + "x"
        for key in [prefix, unfinished] { ChoiceMemory.shared.forget(key) }
        let mixedCase = Engine(.meowpuut, mode: .pinyin)
        for key in "NiHAOX".utf8 { mixedCase.key(Int32(key)) }
        if let choice = mixedCase.snapshot.candidates.firstIndex(of: "你好") { mixedCase.choose(choice) }
        check(mixedCase.takeCommit() == "你好" && mixedCase.snapshot.input == "X", "大小寫部分選詞保留原始後綴（\(script)）")
        check(ChoiceMemory.shared.records[prefix]?.contains(where: { $0.text == "你好" }) == true, "大小寫拼音共用已選詞記憶（\(script)）")
        check(ChoiceMemory.shared.records[unfinished]?.contains(where: { $0.text == "你好" }) != true, "大小寫未完成後綴不納入記憶（\(script)）")
        for key in [prefix, unfinished] { ChoiceMemory.shared.forget(key) }
    }
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        Preferences.shared.rememberChoices = true
        let script = simplified ? "s:" : "t:"
        let phrase = simplified ? "我喜欢" : "我喜歡"
        let whole = simplified ? "我喜欢你" : "我喜歡你"
        let selectedKey = "py:" + script + "woxihuan"
        let unfinishedKey = selectedKey + "n"
        let wholeKey = selectedKey + "ni"
        for key in [selectedKey, unfinishedKey, wholeKey, "py:" + script + "ni"] { ChoiceMemory.shared.forget(key) }
        let segmented = Engine(.meowpuut, mode: .pinyin)
        for key in "woxihuann".utf8 { segmented.key(Int32(key)) }
        let choice = segmented.snapshot.candidates.firstIndex(of: phrase)
        check(choice != nil, "拼音未完成聲母可選前段（\(script)）")
        if let choice {
            segmented.choose(choice)
            check(segmented.takeCommit() == phrase && segmented.snapshot.input == "n", "拼音部分選詞保留聲母（\(script)）")
            check(ChoiceMemory.shared.records[selectedKey]?.contains(where: { $0.text == phrase }) == true, "記憶只記已選拼音（\(script)）")
            check(ChoiceMemory.shared.records[unfinishedKey]?.contains(where: { $0.text == phrase }) != true, "未完成後綴不納入記憶（\(script)）")
            segmented.key(Int32(Character("i").asciiValue!))
            if let next = segmented.snapshot.candidates.firstIndex(of: "你") { segmented.choose(next) }
            check(segmented.takeCommit() == "你" && segmented.snapshot.input.isEmpty, "剩餘拼音可完成上屏（\(script)）")
            check(ChoiceMemory.shared.records[wholeKey]?.contains(where: { $0.text == whole }) == true, "多段選詞記憶完整句（\(script)）")
        }
        for key in [selectedKey, unfinishedKey, wholeKey, "py:" + script + "ni", "ctx|" + phrase + "|py:" + script + "ni"] { ChoiceMemory.shared.forget(key) }

        let zhuyinKey = script + "jivu"
        let zhuyinUnfinished = zhuyinKey + "c"
        let zhuyinWhole = zhuyinKey + "cj0 "
        let nextKey = script + "cj0 "
        let last = simplified ? "欢" : "歡"
        for key in [zhuyinKey, zhuyinUnfinished, zhuyinWhole, nextKey] { ChoiceMemory.shared.forget(key) }
        let zhuyin = Engine(.meowpuut, mode: .zhuyin)
        for key in "jivuc".utf8 { zhuyin.key(Int32(key)) }
        let zhuyinChoice = zhuyin.snapshot.candidates.firstIndex(of: "我喜")
        check(zhuyinChoice != nil, "注音未完成聲母可選前段（\(script)）")
        if let zhuyinChoice {
            zhuyin.choose(zhuyinChoice)
            check(zhuyin.takeCommit() == "我喜" && zhuyin.snapshot.input == "c", "注音部分選詞保留聲母（\(script)）")
            check(ChoiceMemory.shared.records[zhuyinKey]?.contains(where: { $0.text == "我喜" }) == true, "記憶只記已選注音（\(script)）")
            check(ChoiceMemory.shared.records[zhuyinUnfinished]?.contains(where: { $0.text == "我喜" }) != true, "未完成注音不納入記憶（\(script)）")
            for key in "j0 ".utf8 { zhuyin.key(Int32(key)) }
            if let next = zhuyin.snapshot.candidates.firstIndex(of: last) { zhuyin.choose(next) }
            check(zhuyin.takeCommit() == last && zhuyin.snapshot.input.isEmpty, "剩餘注音可完成上屏（\(script)）")
            check(ChoiceMemory.shared.records[zhuyinWhole]?.contains(where: { $0.text == ("我喜" + last) }) == true, "注音多段選詞記憶完整詞（\(script)）")
        }
        for key in [zhuyinKey, zhuyinUnfinished, zhuyinWhole, nextKey, "ctx|我喜|" + nextKey] { ChoiceMemory.shared.forget(key) }
    }
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        Preferences.shared.rememberChoices = false
        let script = simplified ? "s:" : "t:"
        let like = simplified ? "欢" : "歡"
        let replace = simplified ? "换" : "換"
        let control = Engine(.meowpuut, mode: .pinyin)
        for key in "huan".utf8 { control.key(Int32(key)) }
        let uncontextualized = control.snapshot.candidates
        let contextual = Engine(.meowpuut, mode: .pinyin)
        for key in "woxih".utf8 { contextual.key(Int32(key)) }
        let prefix = contextual.snapshot.candidates.firstIndex(of: "我喜")
        check(prefix != nil, "拼音部分選詞可取得上下文（\(script)）")
        if let prefix {
            contextual.choose(prefix)
            check(contextual.takeCommit() == "我喜" && contextual.snapshot.input == "h", "選擇前段保留拼音與已選文字（\(script)）")
            for key in "uan".utf8 { contextual.key(Int32(key)) }
            check(contextual.snapshot.candidates.contains(like), "重建後依前段顯示後續詞（\(script)）")
            // Backspace forces the native backend to clear and replay the
            // remainder; the same composition must retain its context.
            contextual.key(0xff08); contextual.key(Int32(Character("n").asciiValue!))
            check(contextual.snapshot.input == "huan" && contextual.snapshot.candidates.contains(like), "拼音重播保留分段上下文（\(script)）")
            contextual.clear()
            for key in "huan".utf8 { contextual.key(Int32(key)) }
            check(contextual.snapshot.candidates == uncontextualized, "清除後上下文不洩漏到新組字（\(script)）")
        }
        let memoryKey = "py:" + script + "huan"
        ChoiceMemory.shared.forget(memoryKey)
        Preferences.shared.rememberChoices = true
        for _ in 0..<20 { ChoiceMemory.shared.remember([(memoryKey, replace)]) }
        let remembered = Engine(.meowpuut, mode: .pinyin)
        for key in "woxih".utf8 { remembered.key(Int32(key)) }
        if let prefix = remembered.snapshot.candidates.firstIndex(of: "我喜") { remembered.choose(prefix); _ = remembered.takeCommit() }
        for key in "uan".utf8 { remembered.key(Int32(key)) }
        check(remembered.snapshot.candidates.first == replace, "分段上下文保留個人選詞優先（\(script)）")
        ChoiceMemory.shared.forget(memoryKey)
    }
    Preferences.shared.simplified = false
    engine.clear()
    Preferences.shared.rememberChoices = true
    for _ in 0..<3 { ChoiceMemory.shared.remember([("py:t:youhua", "油畫")]) }
    expect(.pinyin, "youhua", "優化")
    engine.clear(); type("youhua")
    check(engine.snapshot.candidates.first == "優化", "少量舊選詞不壓過高頻正確詞")
    Preferences.shared.rememberChoices = false

    engine.clear(); type("youhua")
    check(engine.snapshot.candidates.first == "優化" && engine.snapshot.japaneseCandidates.contains("ようふあ"), "優化優先且假名候選有標記")
    engine.clear(); type("kaixin")
    check(engine.snapshot.candidates.first == "開心", "詞義關聯emoji不越過中文")
    while !engine.snapshot.lastPage && !engine.snapshot.candidates.contains("😊") { engine.key(0xff56) }
    if let index = engine.snapshot.candidates.firstIndex(of: "😊") { engine.choose(index); check(engine.takeCommit() == "😊", "emoji候選可上屏") }
    else { check(false, "emoji候選可上屏") }
    engine.clear(); type("hello")
    check(engine.snapshot.candidates.first == "hello" && engine.snapshot.literalCandidate, "英文單字首選原文")
    engine.key(32)
    check(engine.takeCommit() == "hello", "拼音空格輸出英文原文不添加空格")
    check(!engine.key(32), "無組字時空格交由應用程式輸入")
    Preferences.shared.simplified = true
    engine.clear(); type("nihao"); engine.key(Engine.toggleEnglishKey)
    engine.key(97); engine.key(98)
    check(engine.takeCommit() == "你好ab", "切英文後保留未交付中文並按順序輸出")
    engine.key(Engine.toggleEnglishKey)
    Preferences.shared.simplified = false
    engine.english = true
    check(engine.key(32) && engine.takeCommit() == " ", "英文模式空格照常輸入")
    engine.english = false
    engine.clear(); type("ni"); engine.browse(phrases: true)
    check(!engine.snapshot.candidates.contains("ni"), "詞組篩選不顯示中文讀音原文")
    // A long homophone list must remain browseable and select through the
    // original backend index, including evidence-only additions.
    let previousRememberChoices = Preferences.shared.rememberChoices
    Preferences.shared.rememberChoices = false
    Preferences.shared.simplified = false
    let homophones = Engine(.meowpuut, mode: .pinyin)
    for key in "ji".utf8 { homophones.key(Int32(key)) }
    homophones.browse(phrases: false)
    check(homophones.browseCount > 256, "單字同音候選可展開超過 256 項")
    var lastPageText: String?
    var foundJi = false
    while true {
        if homophones.snapshot.page >= 37, lastPageText == nil {
            lastPageText = homophones.snapshot.candidates.first
        }
        if homophones.snapshot.candidates.contains("蹟") { foundJi = true }
        if homophones.snapshot.lastPage { break }
        homophones.key(0xff56)
    }
    check(foundJi, "完整拼音同音候選保留字典補充的蹟")
    homophones.browse(phrases: false)
    while homophones.snapshot.page < 37 && !homophones.snapshot.lastPage { homophones.key(0xff56) }
    if let text = lastPageText, homophones.snapshot.candidates.first == text {
        homophones.choose(0)
        check(homophones.takeCommit() == text, "第 256 項後的候選使用正確索引上屏")
    } else { check(false, "第 256 項後的候選可正常翻頁") }
    Preferences.shared.rememberChoices = previousRememberChoices

    // Verify both the migrated SQLite schema and existing single-weight databases.
    for schema in ["legacy", "nullable", "notnull"] {
        let dualWeight = schema != "legacy"
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("meowpuut-evidence-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: path) }
        var fixture: OpaquePointer?
        if sqlite3_open(path.path, &fixture) == SQLITE_OK {
            let extraColumn = dualWeight ? (schema == "notnull" ? ",hans_weight INTEGER NOT NULL" : ",hans_weight INTEGER") : ""
            let extraColumns = dualWeight ? ",hans_weight" : ""
            let extraSimple = dualWeight ? ",1234" : ""
            let extraFallback = dualWeight ? (schema == "nullable" ? ",NULL" : ",600") : ""
            let extraCommon = dualWeight ? ",1000" : ""
            let extraTarget = dualWeight ? ",9000" : ""
            let extraLow = dualWeight ? ",1" : ""
            let sql = """
                CREATE TABLE lexicon(reading TEXT, phonetic TEXT, bare TEXT, hant TEXT, hans TEXT, weight INTEGER, toned TEXT\(extraColumn));
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) VALUES('ni','su3','su','妳','你',7777,'ni3'\(extraSimple));
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) VALUES('ni','su3','su','泥','泥',600,'ni3'\(extraFallback));
                WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<2050)
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) SELECT 'ce','c','c','繁'||i,'简'||i,1000,'ce4'\(extraCommon) FROM n;
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) VALUES('ce','c','c','目標','目标',1,'ce4'\(extraTarget));
                WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<2050)
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) SELECT 'xian','x','x','不符'||i,'不符'||i,1000,'xian1'\(extraCommon) FROM n;
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) VALUES('xian','x','x','西安','西安',1,'xi1an1'\(extraLow));
                WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<2050)
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) SELECT 'du','d','d','繁複'||i,'相同',1000,'du1'\(extraCommon) FROM n;
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) VALUES('du','d','d','不同','不同',1,'du1'\(extraLow));
                INSERT INTO lexicon(reading,phonetic,bare,hant,hans,weight,toned\(extraColumns)) VALUES('du','d','d','繁高','相同',1500,'du1'\(dualWeight ? ",1500" : ""));
                """
            let created = sqlite3_exec(fixture, sql, nil, nil, nil) == SQLITE_OK
            sqlite3_close(fixture)
            check(created, "建立雙權重與舊版詞庫測試資料")
            if created {
                let evidence = WordEvidence(path: path.path)
                check(evidence.dictionary(input: "ni", mode: .pinyin, simplified: false)["妳"] == 7777, "繁體查詢保留繁體詞頻")
                check(evidence.dictionary(input: "ni", mode: .pinyin, simplified: true)["你"] == (dualWeight ? 1234 : 7777), "簡體查詢使用簡體詞頻並兼容舊詞庫")
                check(evidence.dictionary(input: "su3", mode: .zhuyin, simplified: true)["泥"] == 600, "簡體詞頻缺值回退原詞頻")
                let target = evidence.dictionary(input: "ce", mode: .pinyin, simplified: true)["目标"]
                check(dualWeight ? target == 9000 : target == nil, "簡體詞頻在候選數量截斷前排序")
                check(evidence.dictionary(input: "xian", mode: .pinyin, simplified: true)["西安"] == nil &&
                      evidence.dictionary(input: "xi'an", mode: .pinyin, simplified: true)["西安"] == 1 &&
                      evidence.dictionary(input: "xi1an1", mode: .pinyin, simplified: true)["西安"] == 1,
                      "先檢查音節再限制候選數量")
                let distinct = evidence.dictionary(input: "du", mode: .pinyin, simplified: true)
                check(distinct["相同"] == 1500 && distinct["不同"] == 1, "候選上限依不同顯示詞計數並保留最高權重")
            }
        } else {
            sqlite3_close(fixture)
            check(false, "建立詞庫測試資料")
        }
    }

    // SQLite stores the canonical lve/nve spelling, but users can type lue/nue.
    // Query both readings without turning an explicit lu'e into lüe.
    Preferences.shared.rememberChoices = false
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        let evidence = WordEvidence.shared
        let lue = evidence.dictionary(input: "lue", mode: .pinyin, simplified: simplified)
        check(lue["略"] != nil && lue["蓼莪"] != nil && (lue["略"] ?? 0) > (lue["蓼莪"] ?? 0), "lue 同時保留略與 lu＋e 詞庫證據")
        check(evidence.dictionary(input: "lue4", mode: .pinyin, simplified: simplified)["略"] != nil &&
              evidence.dictionary(input: "lue4", mode: .pinyin, simplified: simplified)["蓼莪"] == nil, "lue 帶調過濾保留原音節聲調")
        check(evidence.dictionary(input: "nu:e4", mode: .pinyin, simplified: simplified)["虐"] != nil &&
              evidence.dictionary(input: "nve4", mode: .pinyin, simplified: simplified)["虐"] != nil, "ü 冒號與 v 詞庫證據一致")
        check(evidence.dictionary(input: "lu'e", mode: .pinyin, simplified: simplified)["略"] == nil &&
              evidence.dictionary(input: "nu'e", mode: .pinyin, simplified: simplified)["虐"] == nil, "撇號分隔的 lu＋e 與 nu＋e 不改讀音")
        let unseparated = evidence.dictionary(input: "xian", mode: .pinyin, simplified: simplified)
        let separated = evidence.dictionary(input: "xi'an", mode: .pinyin, simplified: simplified)
        let toneSeparated = evidence.dictionary(input: "xi1an1", mode: .pinyin, simplified: simplified)
        check(unseparated["先"] != nil && separated["西安"] != nil && separated["先"] == nil &&
              toneSeparated["西安"] != nil && toneSeparated["先"] == nil, "詞庫補充遵守撇號與聲調的音節邊界")
        let boundaryProbe = Engine(.meowpuut, mode: .pinyin)
        for key in "xi'an".utf8 { boundaryProbe.key(Int32(key)) }
        check(boundaryProbe.snapshot.candidates.first == "西安" && !boundaryProbe.snapshot.candidates.contains("先"), "撇號輸入不補入不符音節的整段候選")
        for (raw, expected) in [("lue", "略"), ("lve", "略"), ("nue", "虐"), ("nve", "虐"),
                                ("shenglue", "省略"), ("shenglve", "省略"), ("sheng'lue", "省略")] {
            let probe = Engine(.meowpuut, mode: .pinyin)
            for key in raw.utf8 { probe.key(Int32(key)) }
            let matched = probe.snapshot.input == raw && probe.snapshot.candidates.first == expected
            check(matched, "拼音別名與整句排序 \(raw)")
            if matched {
                probe.choose(0)
                check(probe.takeCommit() == expected && probe.snapshot.input.isEmpty, "拼音別名原始輸入完整上屏 \(raw)")
            }
        }
        for (raw, unwanted) in [("lu'e", "略"), ("nu'e", "虐")] {
            let probe = Engine(.meowpuut, mode: .pinyin)
            for key in raw.utf8 { probe.key(Int32(key)) }
            check(probe.snapshot.input == raw && !probe.snapshot.candidates.contains(unwanted), "實際組字保留撇號邊界 \(raw)")
        }
        for (raw, expected) in [("xm,4", "略"), ("sm,4", "虐")] {
            let probe = Engine(.meowpuut, mode: .zhuyin)
            for key in raw.utf8 { probe.key(Int32(key)) }
            check(probe.snapshot.candidates.contains(expected), "拼音別名修復不影響注音 \(expected)")
        }
    }
    Preferences.shared.simplified = false
    Preferences.shared.rememberChoices = previousRememberChoices
    engine.clear(); type("hello"); engine.browse(phrases: true)
    check(engine.snapshot.candidates.contains("hello"), "詞組篩選保留英文首選")
    engine.clear(); engine.selectMode(.zhuyin); type("internationalization")
    check(engine.snapshot.candidates.first == "internationalization", "注音模式保留長英文")
    engine.clear(); engine.selectMode(.pinyin); engine.english = false
    type("ma"); engine.key(32, modifiers: 1)
    check(engine.snapshot.input == "ma1" && !engine.english && engine.snapshot.candidates.contains("媽") && !engine.snapshot.candidates.contains("馬"), "Shift 空格標一聲，排除錯聲調詞庫補詞")
    engine.key(35, modifiers: 1)
    check(engine.snapshot.input == "ma3" && engine.snapshot.candidates.contains("馬") && !engine.snapshot.candidates.contains("媽"), "Shift 3 標三聲並可修正上一聲調")
    engine.key(0xff08)
    check(engine.snapshot.input == "ma" && engine.snapshot.candidates.contains("媽"), "退格刪除聲調恢復無調候選")
    engine.clear(); type("ni"); engine.key(35, modifiers: 1); type("hao"); engine.key(35, modifiers: 1)
    check(engine.snapshot.input == "ni3hao3" && engine.snapshot.candidates.contains("你好"), "多音節拼音聲調解碼")
    let toneChoice = engine.snapshot.candidates.first ?? ""
    engine.key(49)
    check(engine.takeCommit() == toneChoice, "帶調拼音仍用普通數字選詞")
    for (key, value) in [(Int32(94), "ma2"), (36, "ma4"), (38, "ma5")] {
        engine.clear(); type("ma"); engine.key(key, modifiers: 1)
        check(engine.snapshot.input == value, "Shift 大千聲調位置 \(value)")
    }
    engine.clear(); engine.key(35, modifiers: 1)
    check(engine.takeCommit() == "#", "空組字保留 Shift 英文符號")
    engine.key(Engine.toggleEnglishKey); engine.key(35, modifiers: 1)
    check(engine.english && engine.takeCommit() == "#", "英文模式不攔截聲調快捷鍵")
    engine.key(Engine.toggleEnglishKey)
    engine.clear(); engine.selectMode(.pinyin); type("nihao,")
    check(engine.takeCommit() == "你好，", "拼音直接輸入中文逗號")
    engine.key(46); check(engine.takeCommit() == "。", "無組字拼音中文句號")
    type("nihao"); engine.key(62, modifiers: 1)
    check(engine.takeCommit() == "你好.", "拼音Shift輸入英文句號")
    engine.key(63, modifiers: 1); check(engine.takeCommit() == "?", "拼音Shift問號保留英文符號")
    Preferences.shared.rememberChoices = true
    for _ in 0..<2 {
        expect(.pinyin, "miaomiao", "喵喵")
        expect(.pinyin, "kaixin", "開心")
    }
    engine.clear(); type("miaomiaokaixin")
    check(engine.snapshot.candidates.contains("喵喵開心"), "自動收集跨次選詞組合")
    check(ChoiceMemory.shared.records["ctx|喵喵|py:t:kaixin"]?.first?.uses == 2, "上下文選詞記憶")
    Preferences.shared.rememberChoices = false
    Preferences.shared.simplified = true
    expect(.pinyin, "zhongguo", "中国")
    expect(.zhuyin, "5j/4", "种")
    for (input, expected) in [("sushi", "寿司"), ("neko", "猫"), ("gakkou", "学校"), ("konnichiha", "こんにちは"), ("nihongo", "日本語"), ("ko-hi-", "コーヒー")] { expect(.japanese, input, expected) }
    for (input, expected) in [("konnichiha", "こんにちは"), ("shin'ya", "しんや"), ("gakkou", "がっこう"), ("kya", "きゃ"), ("xtsu", "っ"), ("n", "ん"), ("nn", "ん"), ("neko", "ねこ")] { check(Romaji.reading(input) == expected, "羅馬字 \(input)") }
    engine.clear(); type("neko"); engine.key(0xffc4); check(engine.takeCommit() == "ネコ", "F7 片假名")
    engine.clear(); type("neko"); engine.key(0xffc3); check(engine.takeCommit() == "ねこ", "F6 平假名")
    Preferences.shared.simplified = false
    expect(.zhuyin, "su3cl3", "你好")
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        engine.clear(); engine.selectMode(.zhuyin); engine.modeChanged()
        type("ji3ap7rup wu0 fm4d042u04u/3")
        let full = simplified ? "我们今天去看电影" : "我們今天去看電影"
        let prefix = simplified ? "我们今天去" : "我們今天去"
        let film = simplified ? "看电影" : "看電影"
        check(engine.snapshot.candidates.first == full, "注音完整句保留首選")
        if let index = engine.snapshot.candidates.firstIndex(of: prefix) { engine.choose(index) }
        check(engine.takeCommit() == prefix && engine.snapshot.input == "d042u04u/3", "注音分段選詞保留完整後綴")
        if let index = engine.snapshot.candidates.firstIndex(of: film) { engine.choose(index) }
        check(engine.takeCommit() == film && engine.snapshot.input.isEmpty, "注音後續分段完整上屏")
        engine.clear(); type("su3cl3v")
        check(engine.snapshot.candidates.first == "你好", "注音未完成聲母保留已完成詞組")
        check(engine.snapshot.candidateDetails.first?.metadata?.remainingInput == "v" &&
              engine.snapshot.candidateDetails.first?.source == 0, "注音候選保留原始尾碼")
        engine.choose(0)
        check(engine.takeCommit() == "你好" && engine.snapshot.input == "v", "注音分段保留未完成聲母")
        type("u3cj0 ")
        check(engine.snapshot.candidates.contains(simplified ? "喜欢" : "喜歡"), "注音分段後繼續輸入")
    }
    Preferences.shared.simplified = false
    engine.clear(); engine.selectMode(.zhuyin); type("nihao")
    check(!engine.snapshot.candidates.contains("你好"), "注音不借用拼音候選")
    check(engine.snapshot.preedit != "nihao", "注音保留注音組字")
    for raw in ["sushi", "suki", "neko", "a"] {
        engine.clear(); engine.selectMode(.zhuyin); type(raw)
        check(engine.snapshot.japaneseCandidates.isEmpty && engine.snapshot.requiredCandidate == nil, "注音不混日語 " + raw)
        check(!engine.snapshot.candidates.contains("寿司") && !engine.snapshot.candidates.contains("好き"), "注音不插入日語詞 " + raw)
    }
    engine.clear(); engine.selectMode(.pinyin); type("su3cl3")
    check(!engine.snapshot.candidates.contains("你好"), "拼音不借用注音候選")
    expect(.pinyin, "nihao", "你好")
    for (simplified, expected) in [(false, "我喜歡你"), (true, "我喜欢你")] {
        Preferences.shared.simplified = simplified
        engine.clear(); engine.modeChanged(); type("woxihuannid")
        check(engine.snapshot.candidates.first == expected, "長句未完成尾音保留整句候選")
        engine.choose(0)
        check(engine.takeCommit() == expected && engine.snapshot.input == "d", "長句選詞保留未完成尾音")
        type("e")
        check(engine.snapshot.input == "de" && engine.snapshot.candidates.contains("的"), "未完成尾音可繼續輸入")
        engine.clear(); type("womenjintianq")
        let incompleteOnset = simplified ? "我们今天" : "我們今天"
        check(engine.snapshot.candidates.first == incompleteOnset, "不完整聲母優先顯示已完成句段")
        engine.choose(0)
        check(engine.takeCommit() == incompleteOnset && engine.snapshot.input == "q", "已完成句段保留剩餘聲母")
        engine.clear(); type("womenjintianbang")
        check(engine.snapshot.candidates.first == (simplified ? "我们今天帮" : "我們今天幫"), "完整音節不被提前切分")
        engine.clear(); type("womenjintianquk")
        let firstSegment = simplified ? "我们今天去" : "我們今天去"
        let secondSegment = simplified ? "看电影" : "看電影"
        let lastSegment = simplified ? "再见" : "再見"
        check(engine.snapshot.candidates.first == firstSegment, "長句首段候選")
        engine.choose(0)
        check(engine.takeCommit() == firstSegment && engine.snapshot.input == "k", "首段上屏後保留尾音")
        type("andianyingz")
        check(engine.snapshot.candidates.first == secondSegment, "後續句段候選")
        engine.choose(0)
        check(engine.takeCommit() == secondSegment && engine.snapshot.input == "z", "後續句段上屏保留尾音")
        type("aijian")
        if let index = engine.snapshot.candidates.firstIndex(of: lastSegment) { engine.choose(index) }
        check(engine.takeCommit() == lastSegment && engine.snapshot.preedit.isEmpty, "多段輸入最終完整上屏")
    }
    Preferences.shared.simplified = false
    engine.modeChanged()
    expect(.pinyin, "sushi", "寿司")
    expect(.pinyin, "neko", "猫")
    engine.clear(); type("wosukini")
    let mixedSnapshot = engine.snapshot
    let mixedPhraseVisible = collapsedCandidateIndices(mixedSnapshot).compactMap { compactCandidateText(mixedSnapshot, at: $0) }
    check(mixedSnapshot.input == "wosukini" && mixedPhraseVisible.first == "我好き你", "完整中日混句優先於單字回退且收合可見")
    check(mixedPhraseVisible.filter { mixedSnapshot.japaneseCandidates.contains($0) }.count == 1, "完整混句保留日語標記且收合只顯示一個")
    if let index = mixedSnapshot.candidates.firstIndex(of: "我好き你") { engine.choose(index) }
    check(engine.takeCommit() == "我好き你" && engine.snapshot.preedit.isEmpty, "混合詞完整上屏")
    for (reading, expected) in [("suki", "好き"), ("haoki", "好き"), ("xingku", "行く")] {
        engine.clear(); type(reading)
        check(engine.snapshot.candidates.contains(expected) && engine.snapshot.japaneseCandidates.contains(expected), "\(reading) 可選日語詞並標示語言")
    }
    check(!engine.snapshot.candidates.contains { text in
        text.contains("x") && text.unicodeScalars.contains { (0x3040...0x30ff).contains($0.value) }
    }, "未完成羅馬字不產生混有原文字母的假名候選")
    engine.clear(); type("wohaokini")
    let hybrid = engine.snapshot
    let hybridVisible = collapsedCandidateIndices(hybrid).compactMap { compactCandidateText(hybrid, at: $0) }
    check(hybridVisible.first == "我好き你" && hybrid.candidates.first == "我好き你" && hybridVisible.filter { hybrid.japaneseCandidates.contains($0) }.count == 1, "拼音與假名混寫完整句優於單字回退")
    engine.clear(); type("woxiangshiberusushiheramen")
    check(engine.snapshot.candidates.contains("我想食べる寿司和ラーメン"), "中日長句可包含三段日語")
    engine.clear(); type("wohaokiyinyue")
    check(engine.snapshot.candidates.contains("我好き音楽"), "日語證據下可用拼音輸入日語漢字")
    engine.clear(); type("woxihuanyinyue")
    check(engine.snapshot.candidates.first == "我喜歡音樂" && !engine.snapshot.candidates.contains("我喜歡音楽"), "純中文音樂不被日語拼音別名改寫")
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        for suffix in ["ki", "kir", "kire"] {
            engine.clear(); engine.modeChanged(); type("jinwandeyueliang" + suffix)
            let prefix = "今晚的月亮"
            let compact = collapsedCandidateIndices(engine.snapshot).compactMap { compactCandidateText(engine.snapshot, at: $0) }
            check(compact.contains(prefix), "未完成日語尾音仍顯示整段中文")
            if let index = engine.snapshot.candidates.firstIndex(of: prefix) { engine.choose(index) }
            check(engine.takeCommit() == prefix && engine.snapshot.input == suffix, "中日前段選詞保留未完成羅馬字")
        }
        engine.clear(); engine.modeChanged(); type("jinwandeyueliangkirei")
        // The Japanese dictionary word retains its own spelling in both modes.
        let complete = "今晚的月亮綺麗"
        let compact = collapsedCandidateIndices(engine.snapshot).compactMap { compactCandidateText(engine.snapshot, at: $0) }
        check(engine.snapshot.candidates.first == complete && compact.first == complete,
              "完整中日長句優先於首字回退並收合可見")
        check(engine.snapshot.japaneseCandidates.contains(complete) && compact.filter { engine.snapshot.japaneseCandidates.contains($0) }.count == 1,
              "完整中日長句保留標記且收合僅一個日語")
        engine.choose(0)
        check(engine.takeCommit() == complete && engine.snapshot.input.isEmpty, "完整中日長句選取後準確上屏")
    }
    Preferences.shared.simplified = false; engine.clear(); engine.modeChanged()
    let mixedMemoryKey = "py:t:jinwandeyueliangkirei"
    ChoiceMemory.shared.forget(mixedMemoryKey)
    Preferences.shared.rememberChoices = true
    for _ in 0..<15 { ChoiceMemory.shared.remember([(mixedMemoryKey, "進")]) }
    type("jinwandeyueliangkirei")
    check(engine.snapshot.candidates.first == "進", "明確個人選詞保留在完整混句之前")
    ChoiceMemory.shared.forget(mixedMemoryKey)
    Preferences.shared.rememberChoices = false
    engine.clear(); type("hello")
    check(engine.snapshot.literalCandidate && engine.snapshot.candidates.first == "hello", "英文首選不被日語拼音別名改寫")
    if let native = try? NativeBackend(mode: .pinyin) {
        check(native.mixedPinyin("woyaoauni", simplified: false) == "我要会う你", "實際日語詞表將短讀音 au 註冊至共用核心")
        let shortPrefix = native.mixedPinyinPrefix("aunihao", simplified: false)
        check(shortPrefix?.text == "会う" && shortPrefix?.consumed == 2, "短日語前段選詞保留完整中文後綴")
        check(["audio", "auto", "autumn", "author", "authentication", "automobile"].allSatisfy { native.mixedPinyin($0, simplified: false) == nil }, "短日語讀音不把英文猜成中日混寫")
    } else { check(false, "短讀音日語測試引擎可載入") }
    if let segments = try? ChineseWithJapaneseBackend(mode: .pinyin) {
        func enterSegmentReading(_ text: String) {
            segments.clear()
            for key in text.utf8 { _ = segments.key(Int32(key), modifiers: 0) }
        }
        func selectSegment(_ text: String) -> Bool {
            guard let candidate = segments.candidates(limit: 256).first(where: { $0.text == text }) else { return false }
            return segments.choose(candidate.index)
        }
        enterSegmentReading("wosukinihao")
        check(selectSegment("我好き"), "可選完整中日前半段")
        var segmentState = segments.snapshot()
        check(segmentState.commit == "我好き" && segmentState.input == "nihao" && segmentState.candidates.contains("你好"), "選中混合前半後保留中文後綴")
        check(selectSegment("你好"), "可繼續選擇中文後半段")
        segmentState = segments.snapshot()
        check(segmentState.commit == "你好" && segmentState.input.isEmpty, "分段選詞各自上屏一次")
        enterSegmentReading("niHAO")
        check(selectSegment("你好"), "大小寫混合拼音可選完整詞")
        segmentState = segments.snapshot()
        check(segmentState.commit == "你好" && segmentState.input.isEmpty, "大小寫拼音選詞不丟字符")
        enterSegmentReading("NiHAOX")
        check(selectSegment("你好"), "大小寫拼音可選部分詞")
        segmentState = segments.snapshot()
        check(segmentState.commit == "你好" && segmentState.input == "X", "部分選詞保留原始大小寫後綴")
        for key in "iao".utf8 { _ = segments.key(Int32(key), modifiers: 0) }
        segmentState = segments.snapshot()
        check(segmentState.input == "Xiao" && segments.candidates(limit: 256).contains(where: { $0.text == "小" }), "保留大寫後綴可繼續組字")
        enterSegmentReading("HelloWORLD")
        _ = segments.key(32, modifiers: 0)
        segmentState = segments.snapshot()
        check(segmentState.commit == "HelloWORLD " && segmentState.input.isEmpty, "英文原文保留大小寫")
        enterSegmentReading("ni''hao")
        segmentState = segments.snapshot()
        check(segmentState.input == "ni''hao" && segmentState.candidates.first == "ni''hao", "無效分隔符保留完整原文候選")
        for _ in 0..<4 { _ = segments.key(0xff08, modifiers: 0) }
        for key in "hao".utf8 { _ = segments.key(Int32(key), modifiers: 0) }
        check(selectSegment("你好"), "刪除多餘分隔符後恢復中文候選")
        segmentState = segments.snapshot()
        check(segmentState.commit == "你好" && segmentState.input.isEmpty, "修正後中文正常上屏")
        enterSegmentReading("ni''hao")
        _ = segments.key(32, modifiers: 0)
        segmentState = segments.snapshot()
        check(segmentState.commit == "ni''hao " && segmentState.input.isEmpty, "無效分隔符原文可安全上屏")
        enterSegmentReading("xi'an")
        check(selectSegment("西安"), "有效拼音分隔符仍可轉換")
        segmentState = segments.snapshot()
        check(segmentState.commit == "西安" && segmentState.input.isEmpty, "有效分隔符正確消耗")
        enterSegmentReading("WoHAoKiNiHao")
        check(selectSegment("我好き"), "大小寫混合讀音可選中日前段")
        segmentState = segments.snapshot()
        check(segmentState.commit == "我好き" && segmentState.input == "NiHao", "中日前段保留後綴原始大小寫")
        check(selectSegment("你好"), "混合大小寫後綴可繼續轉換")
        segmentState = segments.snapshot()
        check(segmentState.commit == "你好" && segmentState.input.isEmpty, "大小寫混合中日分段上屏一次")
        enterSegmentReading("wohaokinihao")
        check(selectSegment("我好き"), "拼音假名混寫可選前半段")
        segmentState = segments.snapshot()
        check(segmentState.commit == "我好き" && segmentState.input == "nihao", "拼音假名前半保留後續讀音")
        check(selectSegment("你好"), "拼音假名混寫可繼續選中文")
        segmentState = segments.snapshot()
        check(segmentState.commit == "你好" && segmentState.input.isEmpty, "拼音假名分段只各上屏一次")
        enterSegmentReading("aunihao")
        check(selectSegment("会う"), "短讀音日語可選前半段")
        segmentState = segments.snapshot()
        check(segmentState.commit == "会う" && segmentState.input == "nihao" && segments.candidates(limit: 256).contains(where: { $0.text == "你好" }), "短讀音日語保留並重建中文後綴")
        check(selectSegment("你好"), "短讀音後可繼續選中文")
        segmentState = segments.snapshot()
        check(segmentState.commit == "你好" && segmentState.input.isEmpty, "短讀音分段不漏字且各自上屏一次")
        enterSegmentReading("sukinihao")
        check(selectSegment("好き"), "可選日語前半段")
        segmentState = segments.snapshot()
        check(segmentState.commit == "好き" && segmentState.input == "nihao", "日語前半段不吞中文讀音")
        enterSegmentReading("wosukini")
        check(selectSegment("我"), "可選中文前半段")
        segmentState = segments.snapshot()
        check(segmentState.commit == "我" && segmentState.input == "sukini" && segments.candidates(limit: 256).contains(where: { $0.text == "好き你" }), "中文前半後恢復中日混合候選")
        check(selectSegment("好き你"), "可繼續選擇日中後半段")
        segmentState = segments.snapshot()
        check(segmentState.commit == "好き你" && segmentState.input.isEmpty, "中日分段完成不丟字")
        enterSegmentReading("nihao")
        segmentState = segments.snapshot()
        check(segmentState.candidates.first == "你好" && !segmentState.japaneseCandidates.contains("你好"), "純中文首選不受混合影響")
    } else { check(false, "分段測試引擎可載入") }
    engine.clear(); type("wosukini")
    if let index = engine.snapshot.candidates.firstIndex(of: "我") { engine.choose(index) }
    check(!engine.takeCommit().isEmpty && !engine.snapshot.input.isEmpty && "wosukini".hasSuffix(engine.snapshot.input), "混合讀音部分選詞保留未轉換後綴")
    engine.clear(); type("wosukini"); engine.key(0xff08)
    check(engine.snapshot.input == "wosukin", "混合輸入退格保留原文")
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        engine.clear(); engine.modeChanged(); type("wosukin")
        let partialMixed = engine.snapshot
        let macPartial = collapsedCandidateIndices(partialMixed).compactMap { compactCandidateText(partialMixed, at: $0) }
        let iosPartial = partialMixed.compactIndices.map { partialMixed.candidates[$0] }
        check(macPartial.contains("我好き") && iosPartial.contains("我好き"), "未完成中文後綴時收合欄保留可選中日前段")
        check(macPartial.filter { partialMixed.japaneseCandidates.contains($0) } == ["我好き"] &&
              iosPartial.filter { partialMixed.japaneseCandidates.contains($0) } == ["我好き"],
              "已記錄中日前段優先於推測假名且收合只顯示一個日語")
        if let ordinal = macPartial.firstIndex(of: "我好き"),
           let index = partialMixed.candidates.firstIndex(of: "我好き") {
            let shortcut = collapsedCandidateShortcut(partialMixed, key: Int32(49 + ordinal),
                                                      modifiers: 0, shifted: false, english: false)
            check(shortcut == .choose(index), "中日前段的收合編號對應可見候選")
            if case .choose(let selected) = shortcut { engine.choose(selected) }
        } else { check(false, "中日前段具有可用收合編號") }
        check(engine.takeCommit() == "我好き" && engine.snapshot.input == "n", "選中日片段僅消耗其讀音並保留未完成後綴")
        type("i")
        check(engine.snapshot.candidates.contains("你"), "保留的後綴可以繼續選字")
    }
    Preferences.shared.simplified = false; engine.clear(); engine.modeChanged()
    engine.clear(); type("nihaosuki")
    check(engine.snapshot.candidates.contains("你好好き"), "混合詞支持中文前綴")
    engine.clear(); type("wosukihanyu")
    check(engine.snapshot.candidates.contains("我好き漢語"), "混合詞支持繁體中文後綴")
    Preferences.shared.simplified = true
    engine.clear(); engine.modeChanged(); type("wosukihanyu")
    check(engine.snapshot.candidates.contains("我好き汉语"), "混合詞支持簡體中文後綴")
    Preferences.shared.simplified = false
    engine.clear(); engine.modeChanged(); type("nihao")
    check(engine.snapshot.candidates.first == "你好" && engine.snapshot.requiredCandidate != "你好", "純中文不受混合候選影響")
    engine.clear(); type("sushi")
    check(engine.snapshot.candidates.first == "蘇軾", "真實中文詞優先")
    check(collapsedCandidateIndices(engine.snapshot).compactMap { compactCandidateText(engine.snapshot, at: $0) }.contains("寿司"), "高可信日語保留收合位置")
    Preferences.shared.rememberChoices = true
    let reading = engine.pinKey
    if let index = engine.snapshot.candidates.firstIndex(of: "寿司") { engine.choose(index); _ = engine.takeCommit() }
    engine.clear(); type("sushi")
    check(engine.snapshot.candidates.first == "蘇軾" && ChoiceMemory.shared.preferred(for: reading).contains("寿司"), "日語記憶不越過中文")
    ChoiceMemory.shared.flush()
    check(ChoiceMemory().preferred(for: reading).contains("寿司"), "記憶可重新載入")
    engine.clear(); type("nihao.")
    check(engine.takeCommit() == "你好。", "拼音標點不丟字")
    engine.clear(); engine.selectMode(.pinyin); type("nihao,")
    check(engine.takeCommit() == "你好，", "自研拼音標點不丟字")
    engine.clear(); engine.selectMode(.pinyin)
    check(physical(41, shift: true) && engine.takeCommit() == ":", "拼音 Shift 分號鍵輸入英文冒號")
    type("nihao")
    check(physical(41, shift: true) && engine.takeCommit() == "你好:", "拼音組字後英文冒號不丟字")
    type("nihao")
    check(engine.key(58) && engine.takeCommit() == "你好：", "拼音直接輸入中文冒號")
    engine.key(96)
    while !engine.snapshot.lastPage && !engine.snapshot.candidates.contains(";") { engine.key(0xff56) }
    if let index = engine.snapshot.candidates.firstIndex(of: ";") { engine.choose(index) }
    check(engine.takeCommit() == ";", "標點選單仍可輸入英文分號")
    check(physical(44) && engine.takeCommit() == "？", "拼音斜線鍵直接輸入中文問號")
    type("nihao")
    check(physical(44, option: true) && engine.takeCommit() == "你好/", "拼音 Option 加斜線提交中文並輸入英文斜線")
    check(physical(44, shift: true) && engine.takeCommit() == "?", "拼音 Shift 加斜線保留英文問號")
    type("nihao")
    check(physical(42, shift: true) && engine.takeCommit() == "你好\\", "拼音 Shift 加反斜線提交英文反斜線")
    type("nihao")
    check(physical(27) && engine.takeCommit() == "你好-", "拼音減號不會被誤當組字")
    check(physical(27, shift: true) && engine.takeCommit() == "_", "拼音下劃線可直接輸入")
    check(physical(39) && engine.takeCommit() == "‘", "空組字的引號可上屏")
    let digitKeys: [UInt16] = [29,18,19,20,21,23,22,26,28,25]
    var digits = ""
    for key in digitKeys { _ = physical(key); digits += engine.takeCommit() }
    check(digits == "0123456789" && engine.snapshot.preedit.isEmpty, "拼音空組字的所有數字直接上屏")
    type("nihao")
    check(physical(28) && engine.takeCommit() == "你好8", "拼音組字後的非選詞數字保留")
    type("vvvvvv")
    check(physical(18) && engine.takeCommit() == "vvvvvv1", "無候選時數字不會誤當聲調或吞字")
    type("woxianghekafei")
    let missingDigit = engine.snapshot.candidates.count + 1
    check((1...9).contains(missingDigit) && physical(digitKeys[missingDigit]) && engine.takeCommit() == "我想喝咖啡\(missingDigit)", "選詞位置不存在時數字不會被吞掉")
    engine.clear(); engine.selectMode(.zhuyin)
    check(physical(41, shift: true) && engine.takeCommit() == "：", "注音 Shift 分號鍵輸入中文冒號")
    type("su3cl3")
    check(physical(41, shift: true) && engine.takeCommit() == "你好：", "注音組字後冒號不丟字")
    check(physical(44) && engine.snapshot.input == "/" && !engine.snapshot.preedit.isEmpty, "注音斜線鍵仍輸入ㄥ")
    engine.clear(); type("su3cl3")
    check(physical(44, shift: true) && engine.takeCommit() == "你好？", "注音 Shift 加斜線仍輸入中文問號")
    type("su3cl3")
    check(physical(44, option: true) && engine.takeCommit() == "你好/", "注音 Option 加斜線直接輸入英文斜線")
    engine.key(96)
    while !engine.snapshot.lastPage && !engine.snapshot.candidates.contains("/") { engine.key(0xff56) }
    if let index = engine.snapshot.candidates.firstIndex(of: "/") { engine.choose(index) }
    check(engine.takeCommit() == "/", "注音標點選單可輸入英文斜線")
    engine.clear(); engine.selectMode(.pinyin); engine.key(Engine.toggleEnglishKey)
    check(physical(41, shift: true) && engine.takeCommit() == ":", "英文模式 Shift 分號鍵輸入冒號")
    check(physical(44) && engine.takeCommit() == "/" && physical(44, shift: true) && engine.takeCommit() == "?", "英文模式保留斜線與問號實體鍵")
    check(!physical(44, option: true), "英文模式保留系統 Option 加斜線")
    engine.key(Engine.toggleEnglishKey)
    engine.clear(); engine.selectMode(.pinyin); type("sushix"); engine.key(0xff08)
    check(engine.snapshot.candidates.contains("寿司"), "拼音輸入退格重算")
    engine.key(0xff1b); check(engine.snapshot.preedit.isEmpty, "Escape 取消")
    engine.clear(); engine.selectMode(.pinyin); type("shi")
    engine.key(0xff56); check(engine.snapshot.page == 1 && !engine.snapshot.candidates.isEmpty, "自研拼音翻頁")
    let selected = engine.snapshot.candidates.first ?? ""
    engine.choose(0); check(engine.takeCommit() == selected, "翻頁選詞索引")
    engine.clear(); engine.selectMode(.pinyin); type("qqqqqq")
    engine.key(0xff0d, modifiers: 1); check(engine.takeCommit() == "qqqqqq", "原始字母可取回")
    engine.key(Engine.toggleEnglishKey); type("GitHub")
    check(engine.english, "英文模式")
    engine.key(Engine.toggleEnglishKey)
    engine.clear(); engine.openModules(); type("hello"); check(!engine.snapshot.candidates.isEmpty, "模組候選")
    let longRow = Snapshot(candidates: ["這是一個很長的中文候選詞", "另一個很長的中文候選詞", "こんにちは"], requiredCandidate: "こんにちは")
    check(collapsedCandidateIndices(longRow) == [0, 2], "長詞保留中文與日文")
    check(collapsedCandidateCount(["你", "妳", "擬", "泥"]) == 4, "短詞自適應候選數")
    check(collapsedCandidateCount([]) == 0, "空候選不佔位置")
    let reliable = Snapshot(candidates: ["優化", "油畫", "油花", "油滑", "有話", "有化"], confidentCandidates: ["優化", "油畫", "油花", "油滑", "有話", "有化"])
    check(collapsedCandidateIndices(reliable).count > collapsedCandidateCount(reliable.candidates), "可靠候選多時適度放寬")

    let shortGrid = ["你好", "妳好", "逆號", "尼好", "你豪", "你毫", "你耗"]
    check(expandedCandidateRows(shortGrid) == [[0, 1, 2], [3, 4, 5], [6]], "短詞展開三欄且排序不變")
    check(expandedCandidateWidth(shortGrid) < 246 && expandedCandidateWidth([String(repeating: "長", count: 16)]) > expandedCandidateWidth(shortGrid), "展開寬度依詞長變化")

    let finalPage = ["斡", "玩偶", "圍毆", "握手"]
    check(expandedCandidateRows(finalPage).first == [0, 1, 2], "尾頁短詞可同列")
    check(expandedCandidateRowHeight(finalPage, row: [0, 1, 2]) >= 28, "候選文字與選中背景共用列高")
    let mixedGrid = ["你好", "妳好", String(repeating: "長", count: 20), "你好"]
    check(expandedCandidateRows(mixedGrid) == [[0, 1], [2], [3]], "長詞獨立整行不截斷")
    check(expandedCandidateRows([]).isEmpty, "空候選無展開行")
    check(expandedCandidateHeight(Array(repeating: String(repeating: "長", count: 80), count: 7)) == 220, "長句展開限高並可捲動")
    check(expandedCandidateMove(shortGrid, from: 1, key: 0xff54) == 4 && expandedCandidateMove(shortGrid, from: 4, key: 0xff52) == 1, "上下鍵依欄移動")
    check(expandedCandidateMove(shortGrid, from: 5, key: 0xff54) == 6 && expandedCandidateMove(shortGrid, from: 0, key: 0xff51) == 0, "末行與邊界不越界")

    let reserved = CollapsedCandidateLayout(longRow, budget: 230)
    let japaneseWidth = ceil(("こんにちは" as NSString).size(withAttributes: [.font: NSFont.systemFont(ofSize: 14)]).width)
    check(reserved.textWidths[2] == japaneseWidth, "短日語不被長中文等比例壓窄")
    check(reserved.truncated, "長詞可展開閱讀")
    let singleLong = CollapsedCandidateLayout(Snapshot(candidates: [String(repeating: "長", count: 40)]))
    check(singleLong.indices == [0] && singleLong.truncated, "單一長候選也可展開")
    let laterPage = Snapshot(candidates: ["甲", "乙", "丙", "丁", "戊", "寿司"], page: 1, requiredCandidate: "寿司")
    check(!collapsedCandidateIndices(laterPage).contains(5), "後續頁不插入首屏日語保留位")
    for candidates in [["你", "妳", "擬", "泥", "妮", "倪", "尼"], longRow.candidates, ["蘇軾", "素食", "寿司"], [String(repeating: "長", count: 40)]] {
        let layout = CollapsedCandidateLayout(Snapshot(candidates: candidates, requiredCandidate: candidates.contains("寿司") ? "寿司" : nil))
        let width = 14 + CGFloat(layout.indices.count * 34 + max(0, layout.indices.count - 1) * 4) + layout.textWidths.values.reduce(0, +)
        check(width <= 230 && layout.indices.first == 0 && Set(layout.indices).count == layout.indices.count, "候選寬度有界、首選保留且索引唯一")
    }

    engine.clear(); engine.selectMode(.pinyin)
    var times: [Double] = []
    for word in ["sushi", "nihao", "su3cl3", "woxianghekafei", "konnichiha"] {
        engine.clear()
        for key in word.utf8 {
            let start = CFAbsoluteTimeGetCurrent(); engine.key(Int32(key)); times.append((CFAbsoluteTimeGetCurrent() - start) * 1000)
        }
    }
    times.sort(); print(String(format: "Pinyin key latency median %.2f ms / P95 %.2f ms", times[times.count / 2], times[times.count * 95 / 100]))
    check(Bundle.main.url(forResource: "Rime", withExtension: nil) == nil, "應用不含第三方引擎資料目錄")
    runDocumentContextSmoke(check)
    runLongSentenceCandidateSmoke(check)
    let typingEngine = Engine(mode: .pinyin)
    let typingView = TypingView(frame: .zero)
    typingView.engine = typingEngine
    typingView.string = "前文舊詞後文"
    typingView.setSelectedRange(NSRange(location: 2, length: 2))
    for byte in "nihao".utf8 { typingEngine.key(Int32(byte)) }
    typingEngine.finishComposition(); typingEngine.selectMode(.zhuyin)
    typingView.deliverPendingCommit()
    check(typingView.string == "前文你好後文", "試打頁切換模式立即送出文字並正確替換選區")
    typingView.deliverPendingCommit()
    check(typingView.string == "前文你好後文", "試打頁重繪不重複送出已完成文字")
    ChoiceMemory.shared.clear()
    ChoiceMemory.shared.flush()
    try? FileManager.default.removeItem(at: Preferences.directory)
    UserDefaults(suiteName: "app.meowpuut.smoke")?.removePersistentDomain(forName: "app.meowpuut.smoke")
    exit(failures == 0 ? 0 : 1)
}
