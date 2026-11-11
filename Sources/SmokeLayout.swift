import AppKit

func runSmokePunctuationAndGrid(_ h: inout SmokeHarness) {
    let engine = h.engine
    engine.clear(); h.type("nihao.")
    h.check(engine.takeCommit() == "你好。", "拼音標點不丟字")
    engine.clear(); engine.selectMode(.pinyin); h.type("nihao,")
    h.check(engine.takeCommit() == "你好，", "自研拼音標點不丟字")
    engine.clear(); engine.selectMode(.pinyin)
    h.check(h.physical(41, shift: true) && engine.takeCommit() == ":", "拼音 Shift 分號鍵輸入英文冒號")
    h.type("nihao")
    h.check(h.physical(41, shift: true) && engine.takeCommit() == "你好:", "拼音組字後英文冒號不丟字")
    h.type("nihao")
    h.check(engine.key(58) && engine.takeCommit() == "你好：", "拼音直接輸入中文冒號")
    engine.key(96)
    while !engine.snapshot.lastPage && !engine.snapshot.candidates.contains(";") { engine.key(0xff56) }
    if let index = engine.snapshot.candidates.firstIndex(of: ";") { engine.choose(index) }
    h.check(engine.takeCommit() == ";", "標點選單仍可輸入英文分號")
    h.check(h.physical(44) && engine.takeCommit() == "？", "拼音斜線鍵直接輸入中文問號")
    h.type("nihao")
    h.check(h.physical(44, option: true) && engine.takeCommit() == "你好/", "拼音 Option 加斜線提交中文並輸入英文斜線")
    h.check(h.physical(44, shift: true) && engine.takeCommit() == "?", "拼音 Shift 加斜線保留英文問號")
    h.type("nihao")
    h.check(h.physical(42, shift: true) && engine.takeCommit() == "你好\\", "拼音 Shift 加反斜線提交英文反斜線")
    h.type("nihao")
    h.check(h.physical(27) && engine.takeCommit() == "你好-", "拼音減號不會被誤當組字")
    h.check(h.physical(27, shift: true) && engine.takeCommit() == "_", "拼音下劃線可直接輸入")
    h.check(h.physical(39) && engine.takeCommit() == "‘", "空組字的引號可上屏")
    let digitKeys: [UInt16] = [29,18,19,20,21,23,22,26,28,25]
    var digits = ""
    for key in digitKeys { _ = h.physical(key); digits += engine.takeCommit() }
    h.check(digits == "0123456789" && engine.snapshot.preedit.isEmpty, "拼音空組字的所有數字直接上屏")
    h.type("nihao")
    h.check(h.physical(28) && engine.takeCommit() == "你好8", "拼音組字後的非選詞數字保留")
    h.type("vvvvvv")
    h.check(h.physical(18) && engine.takeCommit() == "vvvvvv1", "無候選時數字不會誤當聲調或吞字")
    h.type("woxianghekafei")
    let missingDigit = engine.snapshot.candidates.count + 1
    h.check((1...9).contains(missingDigit) && h.physical(digitKeys[missingDigit]) && engine.takeCommit() == "我想喝咖啡\(missingDigit)", "選詞位置不存在時數字不會被吞掉")
    engine.clear(); engine.selectMode(.zhuyin)
    h.check(h.physical(41, shift: true) && engine.takeCommit() == "：", "注音 Shift 分號鍵輸入中文冒號")
    h.type("su3cl3")
    h.check(h.physical(41, shift: true) && engine.takeCommit() == "你好：", "注音組字後冒號不丟字")
    h.check(h.physical(44) && engine.snapshot.input == "/" && !engine.snapshot.preedit.isEmpty, "注音斜線鍵仍輸入ㄥ")
    engine.clear(); h.type("su3cl3")
    h.check(h.physical(44, shift: true) && engine.takeCommit() == "你好？", "注音 Shift 加斜線仍輸入中文問號")
    h.type("su3cl3")
    h.check(h.physical(44, option: true) && engine.takeCommit() == "你好/", "注音 Option 加斜線直接輸入英文斜線")
    engine.key(96)
    while !engine.snapshot.lastPage && !engine.snapshot.candidates.contains("/") { engine.key(0xff56) }
    if let index = engine.snapshot.candidates.firstIndex(of: "/") { engine.choose(index) }
    h.check(engine.takeCommit() == "/", "注音標點選單可輸入英文斜線")
    engine.clear(); engine.selectMode(.pinyin); engine.key(Engine.toggleEnglishKey)
    h.check(h.physical(41, shift: true) && engine.takeCommit() == ":", "英文模式 Shift 分號鍵輸入冒號")
    h.check(h.physical(44) && engine.takeCommit() == "/" && h.physical(44, shift: true) && engine.takeCommit() == "?", "英文模式保留斜線與問號實體鍵")
    h.check(!h.physical(44, option: true), "英文模式保留系統 Option 加斜線")
    engine.key(Engine.toggleEnglishKey)
    engine.clear(); engine.selectMode(.pinyin); h.type("sushix"); engine.key(0xff08)
    h.check(engine.snapshot.candidates.contains("寿司"), "拼音輸入退格重算")
    engine.key(0xff1b); h.check(engine.snapshot.preedit.isEmpty, "Escape 取消")
    engine.clear(); engine.selectMode(.pinyin); h.type("shi")
    engine.key(0xff56); h.check(engine.snapshot.page == 1 && !engine.snapshot.candidates.isEmpty, "自研拼音翻頁")
    let selected = engine.snapshot.candidates.first ?? ""
    engine.choose(0); h.check(engine.takeCommit() == selected, "翻頁選詞索引")
    engine.clear(); engine.selectMode(.pinyin); h.type("qqqqqq")
    engine.key(0xff0d, modifiers: 1); h.check(engine.takeCommit() == "qqqqqq", "原始字母可取回")
    engine.key(Engine.toggleEnglishKey); h.type("GitHub")
    h.check(engine.english, "英文模式")
    engine.key(Engine.toggleEnglishKey)
    engine.clear(); engine.openModules(); h.type("hello"); h.check(!engine.snapshot.candidates.isEmpty, "模組候選")
    let longRow = Snapshot(candidates: ["這是一個很長的中文候選詞", "另一個很長的中文候選詞", "こんにちは"], requiredCandidate: "こんにちは")
    h.check(collapsedCandidateIndices(longRow) == [0, 2], "長詞保留中文與日文")
    h.check(collapsedCandidateCount(["你", "妳", "擬", "泥"]) == 4, "短詞自適應候選數")
    h.check(collapsedCandidateCount([]) == 0, "空候選不佔位置")
    let reliable = Snapshot(candidates: ["優化", "油畫", "油花", "油滑", "有話", "有化"], confidentCandidates: ["優化", "油畫", "油花", "油滑", "有話", "有化"])
    h.check(collapsedCandidateIndices(reliable).count > collapsedCandidateCount(reliable.candidates), "可靠候選多時適度放寬")

    let shortGrid = ["你好", "妳好", "逆號", "尼好", "你豪", "你毫", "你耗"]
    h.check(expandedCandidateRows(shortGrid) == [[0, 1, 2], [3, 4, 5], [6]], "短詞展開三欄且排序不變")
    h.check(expandedCandidateWidth(shortGrid) < 246 && expandedCandidateWidth([String(repeating: "長", count: 16)]) > expandedCandidateWidth(shortGrid), "展開寬度依詞長變化")

    let finalPage = ["斡", "玩偶", "圍毆", "握手"]
    h.check(expandedCandidateRows(finalPage).first == [0, 1, 2], "尾頁短詞可同列")
    h.check(Desk.gridRowHeight >= Desk.pill && expandedCandidateHeight(finalPage) == 2 * Desk.gridRowHeight + Desk.gridRowSpacing, "候選文字與選中背景共用列高")
    let mixedGrid = ["你好", "妳好", String(repeating: "長", count: 20), "你好"]
    h.check(expandedCandidateRows(mixedGrid) == [[0, 1], [2], [3]], "長詞獨立整行不截斷")
    h.check(expandedCandidateRows([]).isEmpty, "空候選無展開行")
    h.check(expandedCandidateHeight(Array(repeating: String(repeating: "長", count: 80), count: 7)) == 7 * Desk.gridRowHeight + 6 * Desk.gridRowSpacing, "長句展開仍是每個候選一行")
    h.check(expandedCandidateMove(shortGrid, from: 1, key: 0xff54) == 4 && expandedCandidateMove(shortGrid, from: 4, key: 0xff52) == 1, "上下鍵依欄移動")
    h.check(expandedCandidateMove(shortGrid, from: 5, key: 0xff54) == 6 && expandedCandidateMove(shortGrid, from: 0, key: 0xff51) == 0, "末行與邊界不越界")

    let reserved = CollapsedCandidateLayout(longRow, budget: 230)
    let japaneseWidth = CandidateMeasure.width("こんにちは")
    h.check(reserved.textWidths[2] == japaneseWidth, "短日語不被長中文等比例壓窄")
    h.check(reserved.truncated, "長詞可展開閱讀")
    let singleLong = CollapsedCandidateLayout(Snapshot(candidates: [String(repeating: "長", count: 40)]))
    h.check(singleLong.indices == [0] && singleLong.truncated, "單一長候選也可展開")
    let laterPage = Snapshot(candidates: ["甲", "乙", "丙", "丁", "戊", "寿司"], page: 1, requiredCandidate: "寿司")
    h.check(!collapsedCandidateIndices(laterPage).contains(5), "後續頁不插入首屏日語保留位")
    for candidates in [["你", "妳", "擬", "泥", "妮", "倪", "尼"], longRow.candidates, ["蘇軾", "素食", "寿司"], [String(repeating: "長", count: 40)]] {
        let layout = CollapsedCandidateLayout(Snapshot(candidates: candidates, requiredCandidate: candidates.contains("寿司") ? "寿司" : nil))
        let width = layout.textWidths.values.reduce(0, +) + CGFloat(layout.indices.count) * CollapsedCandidateLayout.chrome
        h.check(width <= CollapsedCandidateLayout.rowLimit && layout.indices.first == 0 && Set(layout.indices).count == layout.indices.count, "候選寬度有界、首選保留且索引唯一")
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
    h.check(Bundle.main.url(forResource: "Rime", withExtension: nil) == nil, "應用不含第三方引擎資料目錄")
}
