import AppKit

func runIndependentSmoke() -> Never {
    setbuf(stdout, nil)
    var failures = 0
    func check(_ ok: Bool, _ message: String) { print("\(ok ? "PASS" : "FAIL")：\(message)"); if !ok { failures += 1 } }
    Preferences.shared.simplified = false
    Preferences.shared.rememberChoices = false
    Preferences.shared.pins = [:]
    let engine = Engine()
    check(engine.isReady, "自研注音核心載入")
    func type(_ value: String) { for key in value.utf8 { engine.key(Int32(key)) } }
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
    check(!overflowText.isEmpty && overflowText.hasSuffix(String(overflowReading.suffix(8))), "超出組字上限後仍接收後續按鍵")

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
    engine.clear(); engine.selectMode(.pinyin); type("nadoushi")
    check(engine.snapshot.candidates.first == "那都是" && !engine.snapshot.literalCandidate, "拼音詞中ous不誤判英文")
    engine.key(32); check(engine.takeCommit() == "nadoushi ", "拼音空格保留原文及空格")
    type("emoji"); check(engine.snapshot.candidates.first == "emoji", "emoji英文優先")
    engine.key(32); check(engine.takeCommit() == "emoji ", "英文詞接空格")
    engine.clear(); type("nihao"); engine.key(32)
    check(engine.takeCommit() == "nihao ", "有中文候選時空格仍保留拼音原文")
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
        check(visible.contains { engine.snapshot.candidates[$0] == engine.snapshot.requiredCandidate }, "日語候選收合可見 " + input)
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
        if let last = visible.last {
            check(engine.snapshot.japaneseCandidates.contains(engine.snapshot.candidates[last]), "收合日語固定最右 " + input)
        }
    }
    var tap = ShiftTapTracker()
    check(!tap.update(keyCode: 56, modifierEvent: true, shift: true, other: false)
        && tap.update(keyCode: 56, modifierEvent: true, shift: false, other: false), "單按Shift釋放切換一次")
    _ = tap.update(keyCode: 56, modifierEvent: true, shift: true, other: false)
    _ = tap.update(keyCode: 0, modifierEvent: false, shift: true, other: false)
    check(!tap.update(keyCode: 56, modifierEvent: true, shift: false, other: false), "Shift打大寫不切換")
    _ = tap.update(keyCode: 60, modifierEvent: true, shift: true, other: false)
    _ = tap.update(keyCode: 55, modifierEvent: true, shift: true, other: true)
    check(!tap.update(keyCode: 60, modifierEvent: true, shift: false, other: false), "Shift組合快捷鍵不切換")
    let alternating = interleaveLanguages(["強一", "強二", "弱一", "弱二", "日一", "日二", "日三"],
        japanese: { $0.hasPrefix("日") }, strong: { $0.hasPrefix("強") })
    check(alternating == ["強一", "強二", "日一", "弱一", "日二", "弱二", "日三"], "日語與低置信中文交替")
    for input in ["a", "sushi", "watashihaneko"] {
        engine.clear(); type(input)
        let snapshot = engine.snapshot
        let visible = collapsedCandidateIndices(snapshot)
        check(visible.filter { snapshot.japaneseCandidates.contains(snapshot.candidates[$0]) }.count == 1, "收合只顯示一個日語 " + input)
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
    expect(.zhuyin, "u. cj84", "優化")
    Preferences.shared.rememberChoices = true
    for _ in 0..<3 { ChoiceMemory.shared.remember([("py:t:youhua", "油畫")]) }
    expect(.pinyin, "youhua", "優化")
    engine.clear(); type("youhua")
    check(engine.snapshot.candidates.first == "優化", "少量舊選詞不壓過高頻正確詞")
    Preferences.shared.rememberChoices = false

    engine.clear(); type("youhua")
    check(engine.snapshot.candidates.first == "優化" && engine.snapshot.japaneseCandidates.contains("ようふあ"), "優化優先且假名候選有標記")
    engine.clear(); type("kaixin")
    check(engine.snapshot.candidates.first == "開心" && engine.snapshot.candidates.contains("😊"), "詞義關聯emoji不越過中文")
    if let index = engine.snapshot.candidates.firstIndex(of: "😊") { engine.choose(index); check(engine.takeCommit() == "😊", "emoji候選可上屏") }
    engine.clear(); type("hello")
    check(engine.snapshot.candidates.first == "hello" && engine.snapshot.literalCandidate, "英文單字首選原文")
    var englishSentence = ""
    for key in " world this is a test".utf8 { engine.key(Int32(key)); englishSentence += engine.takeCommit() }
    engine.key(0xff0d); englishSentence += engine.takeCommit()
    check(englishSentence == "hello world this is a test", "英文句子空格不丟失")
    engine.clear(); engine.selectMode(.zhuyin); type("internationalization")
    check(engine.snapshot.candidates.first == "internationalization", "注音模式保留長英文")
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
    engine.clear(); engine.selectMode(.zhuyin); type("nihao")
    check(!engine.snapshot.candidates.contains("你好"), "注音不借用拼音候選")
    check(engine.snapshot.preedit != "nihao", "注音保留注音組字")
    expect(.zhuyin, "sushi", "寿司")
    engine.clear(); engine.selectMode(.pinyin); type("su3cl3")
    check(!engine.snapshot.candidates.contains("你好"), "拼音不借用注音候選")
    expect(.pinyin, "nihao", "你好")
    expect(.pinyin, "sushi", "寿司")
    expect(.pinyin, "neko", "猫")
    engine.clear(); type("sushi")
    check(engine.snapshot.candidates.first == "蘇軾", "真實中文詞優先")
    check(collapsedCandidateIndices(engine.snapshot).map { engine.snapshot.candidates[$0] }.contains("寿司"), "高可信日語保留收合位置")
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
    engine.clear(); engine.selectMode(.pinyin); type("sushix"); engine.key(0xff08)
    check(engine.snapshot.candidates.contains("寿司"), "拼音輸入退格重算")
    engine.key(0xff1b); check(engine.snapshot.preedit.isEmpty, "Escape 取消")
    engine.clear(); engine.selectMode(.pinyin); type("shi")
    engine.key(0xff56); check(engine.snapshot.page == 1 && !engine.snapshot.candidates.isEmpty, "自研拼音翻頁")
    let selected = engine.snapshot.candidates.first ?? ""
    engine.choose(0); check(engine.takeCommit() == selected, "翻頁選詞索引")
    engine.clear(); engine.selectMode(.pinyin); type("qqqqqq")
    engine.key(0xff0d, modifiers: 1); check(engine.takeCommit() == "qqqqqq", "原始字母可取回")
    engine.key(32, modifiers: 1); type("GitHub")
    check(engine.english, "英文模式")
    engine.key(32, modifiers: 1)
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
    ChoiceMemory.shared.clear()
    ChoiceMemory.shared.flush()
    try? FileManager.default.removeItem(at: Preferences.directory)
    UserDefaults(suiteName: "app.meowpuut.smoke")?.removePersistentDomain(forName: "app.meowpuut.smoke")
    exit(failures == 0 ? 0 : 1)
}
