import AppKit

func runSmokePinyinAndMemory(_ h: inout SmokeHarness) {
    let engine = h.engine
    for input in ["watashihaneko", "ashitahaame", "gakkou", "shin'ya"] {
        engine.clear(); h.type(input)
        let kana = Romaji.reading(input)
        let visible = collapsedCandidateIndices(engine.snapshot)
        h.check(visible.contains { compactCandidateText(engine.snapshot, at: $0) == engine.snapshot.requiredCandidate }, "日語候選收合可見 " + input)
        h.check(engine.snapshot.requiredCandidate != nil && engine.snapshot.japaneseCandidates.contains(kana), "完整羅馬音標記 " + input)
        h.check(kanaRankedLast(engine, kana), "假名拼寫排在所有中文之後 " + input)
        engine.clear(); h.type(input)
        if let index = engine.snapshot.candidates.firstIndex(of: kana) {
            engine.choose(index)
            h.check(engine.takeCommit() == kana, "貓爪不隨文字上屏 " + input)
        }
    }
    engine.clear(); h.type("sushi")
    h.check(engine.snapshot.requiredCandidate == "寿司" && collapsedCandidateIndices(engine.snapshot)
        .contains { compactCandidateText(engine.snapshot, at: $0) == "寿司" }, "sushi 收合仍見寿司")
    h.check(kanaRankedLast(engine, "すし"), "sushi 的假名拼寫排在所有中文之後")
    engine.clear(); h.type("haluo")
    h.check(engine.snapshot.requiredCandidate == nil && engine.snapshot.compactJapanese == nil
        && !engine.snapshot.candidates.prefix(3).contains("はぅお"), "haluo 前幾格不放假名拼寫")
    h.check(kanaRankedLast(engine, "はぅお"), "haluo 的假名拼寫排在所有中文之後")
    let crowdedJapanese = Snapshot(candidates: ["甲", "乙", "丙", "丁", "戊", "己", "あ"],
        requiredCandidate: "甲", confidentCandidates: ["甲", "乙", "丙", "丁", "戊", "己"], japaneseCandidates: ["あ"])
    h.check(collapsedCandidateIndices(crowdedJapanese).contains(6), "中文同形詞不占用日語保留位置")
    let chineseOnly = Snapshot(candidates: ["我", "握", "窩", "臥", "沃"])
    let mixedSingle = Snapshot(candidates: ["我", "握", "を", "窩", "ヲ", "臥", "沃"], japaneseCandidates: ["を", "ヲ"])
    let mixedVisible = collapsedCandidateIndices(mixedSingle).map { mixedSingle.candidates[$0] }
    h.check(mixedVisible.filter { !mixedSingle.japaneseCandidates.contains($0) }.count == collapsedCandidateIndices(chineseOnly).count, "單字日語不擠掉中文位置")
    h.check(mixedVisible.filter { mixedSingle.japaneseCandidates.contains($0) }.count == 1, "單字最多一個日語")
    engine.clear(); engine.selectMode(.pinyin); h.type("d")
    h.check(engine.snapshot.candidates.first != "d" && engine.snapshot.candidates.contains("的"), "單字母d優先中文")
    h.check(engine.snapshot.candidates.contains("d"), "單字母仍可選英文")
    if let index = engine.snapshot.candidates.firstIndex(of: "d") {
        engine.choose(index); h.check(engine.takeCommit() == "d", "單字母英文可上屏")
    }
    engine.clear(); engine.selectMode(.zhuyin); h.type("d")
    h.check(engine.snapshot.candidates.first != "d" && engine.snapshot.candidates.contains("可"), "注音單聲母也優先中文")
    engine.clear(); engine.selectMode(.pinyin)
    h.expect(.pinyin, "miao", "喵")
    h.expect(.pinyin, "muao", "喵")
    h.expect(.zhuyin, "aul ", "喵")
    for input in ["wo", "muao", "sushi"] {
        engine.clear(); engine.selectMode(.pinyin); h.type(input)
        let visible = collapsedCandidateIndices(engine.snapshot)
        h.check((!engine.snapshot.candidates.contains(where: { engine.snapshot.japaneseCandidates.contains($0) }) && engine.snapshot.compactJapanese == nil) ||
              visible.contains(where: { compactCandidateText(engine.snapshot, at: $0).map(engine.snapshot.japaneseCandidates.contains) ?? false }),
              "收合保留日語候選 " + input)
    }
    let shownOrder = Snapshot(candidates: ["感", "乾", "がん", "敢", "趕", "柑", "ガン"], japaneseCandidates: ["がん", "ガン"])
    let shownIndices = collapsedCandidateIndices(shownOrder)
    h.check(shownIndices.contains(2) && shownIndices == shownIndices.sorted() &&
          shownIndices.filter { shownOrder.japaneseCandidates.contains(shownOrder.candidates[$0]) }.count == 1,
          "收合保留日語並遵循引擎次序")
    h.check(shownIndices.firstIndex(of: 2).map { $0 < shownIndices.count - 1 } == true,
          "收合日語不固定排在最後")
    let laterJapanese = Snapshot(candidates: ["甲", "乙", "丙", "丁", "戊", "己", "庚"],
        japaneseCandidates: ["寿司"], compactJapanese: IndexedCandidate(index: 9, text: "寿司", source: 1))
    let laterIndices = collapsedCandidateIndices(laterJapanese)
    let laterSlot = laterJapanese.candidates.count
    h.check(laterIndices.contains(laterSlot) && compactCandidateText(laterJapanese, at: laterSlot) == "寿司" &&
          laterIndices.filter { $0 < laterSlot }.count == collapsedCandidateIndices(Snapshot(candidates: laterJapanese.candidates)).count,
          "收合保留後頁日語且不減少中文")
    if let position = laterIndices.firstIndex(of: laterSlot) {
        h.check(collapsedCandidateShortcut(laterJapanese, key: Int32(49 + position), modifiers: 0, shifted: false, english: false) == .choose(laterSlot),
              "後頁日語快捷鍵對應收合候選")
    } else { h.check(false, "後頁日語快捷鍵對應收合候選") }
    for (ordinal, expected) in shownIndices.enumerated() {
        h.check(collapsedCandidateShortcut(shownOrder, key: Int32(49 + ordinal), modifiers: 0, shifted: false, english: false) == .choose(expected),
              "收合拼音顯示序號對應候選 \(ordinal + 1)")
        let shiftedKey = Int32(Array("!@#$%^&".utf8)[ordinal])
        h.check(collapsedCandidateShortcut(shownOrder, key: shiftedKey, modifiers: 1, shifted: true, english: false) == .choose(expected),
              "收合注音顯示序號對應候選 \(ordinal + 1)")
        var navigating = shownOrder
        navigating.highlighted = expected
        h.check(collapsedCandidateMove(navigating, key: 0xff09, modifiers: 0) == shownIndices[(ordinal + 1) % shownIndices.count] &&
              collapsedCandidateMove(navigating, key: 0xff52, modifiers: 0) == shownIndices[(ordinal + shownIndices.count - 1) % shownIndices.count] &&
              collapsedCandidateMove(navigating, key: 0xff09, modifiers: 1) == shownIndices[(ordinal + shownIndices.count - 1) % shownIndices.count],
              "收合方向選擇遵循可見順序 \(ordinal + 1)")
    }
    h.check(collapsedCandidateShortcut(shownOrder, key: 49, modifiers: 0, shifted: false, english: true) == .pass &&
          collapsedCandidateShortcut(shownOrder, key: 49, modifiers: 0, shifted: true, english: false) == .pass &&
          collapsedCandidateShortcut(shownOrder, key: 33, modifiers: 1, shifted: false, english: false) == .pass,
          "英文與注音聲調及拼音聲調不攔截")
    let hiddenOrder = Snapshot(candidates: ["長詞一二三四五六", "長詞七八九十", "長詞其他", "長詞四", "長詞五", "長詞六", "長詞七"])
    let hiddenCount = collapsedCandidateIndices(hiddenOrder).count
    h.check(hiddenCount < 7 && collapsedCandidateShortcut(hiddenOrder, key: Int32(49 + hiddenCount), modifiers: 0, shifted: false, english: false) == .expand,
          "未顯示候選的數字先展開避免誤選")
    for input in ["gan", "jixu", "suki"] {
        engine.clear(); engine.selectMode(.pinyin); h.type(input)
        let snapshot = engine.snapshot
        let visible = collapsedCandidateIndices(snapshot)
        let japaneseVisible = visible.filter { compactCandidateText(snapshot, at: $0).map(snapshot.japaneseCandidates.contains) ?? false }
        h.check(japaneseVisible.count == (snapshot.compactJapanese != nil || snapshot.candidates.contains(where: { snapshot.japaneseCandidates.contains($0) }) ? 1 : 0) &&
              visible == visible.sorted(), "真實拼音收合保留日語並遵循引擎次序 \(input)")
        if let position = japaneseVisible.first.flatMap({ visible.firstIndex(of: $0) }) {
            let expected = compactCandidateText(snapshot, at: visible[position]) ?? ""
            if case .choose(let index) = collapsedCandidateShortcut(snapshot, key: Int32(49 + position), modifiers: 0, shifted: false, english: false) {
                if index == snapshot.candidates.count { engine.chooseCompactJapanese() } else { engine.choose(index) }
                h.check(engine.takeCommit() == expected, "真實日語按可見序號上屏 \(input)")
            } else { h.check(false, "真實日語按可見序號上屏 \(input)") }
        }
    }
    engine.clear(); engine.selectMode(.pinyin); h.type("gan")
    let arrowSnapshot = engine.snapshot
    let arrowVisible = collapsedCandidateIndices(arrowSnapshot)
    if let target = collapsedCandidateMove(arrowSnapshot, key: 0xff52, modifiers: 0), arrowVisible.last == target {
        let expected = compactCandidateText(arrowSnapshot, at: target) ?? ""
        engine.snapshot.highlighted = target
        if target == arrowSnapshot.candidates.count { engine.chooseCompactJapanese() } else { engine.key(0xff0d) }
        h.check(engine.takeCommit() == expected, "收合上鍵高亮末項後 Enter 選中同一詞")
    } else { h.check(false, "收合上鍵高亮末項後 Enter 選中同一詞") }
    for toggle in [false, true] {
        engine.clear(); engine.english = false; engine.selectMode(.pinyin); h.type("hai")
        if let candidate = engine.snapshot.compactJapanese {
            engine.snapshot.highlighted = engine.snapshot.candidates.count
            if toggle { engine.key(Engine.toggleEnglishKey) } else { engine.key(44) }
            let expected = candidate.text + (toggle ? "" : "，")
            h.check(engine.takeCommit() == expected && engine.snapshot.input.isEmpty && (!toggle || engine.english),
                  toggle ? "高亮頁外日語後切中英提交同一候選" : "高亮頁外日語後輸入標點提交同一候選")
        } else { h.check(false, "頁外日語候選可供完成組字測試") }
    }
    engine.english = false
    engine.clear(); engine.selectMode(.zhuyin); h.type("su3cl3")
    if case .choose(let target) = collapsedCandidateShortcut(engine.snapshot, key: 33, modifiers: 1, shifted: true, english: false) {
        engine.choose(target)
        h.check(engine.takeCommit() == "你好", "注音收合 Shift 加可見序號實際上屏")
    } else { h.check(false, "注音收合 Shift 加可見序號實際上屏") }
    engine.clear(); engine.selectMode(.pinyin); h.type("shi"); engine.key(0xff56)
    let expandedFirst = engine.snapshot.candidates.first ?? ""
    engine.key(49)
    h.check(!expandedFirst.isEmpty && engine.takeCommit() == expandedFirst, "展開翻頁數字仍按頁內原順序上屏")
    var tap = ShiftTapTracker()
    h.check(!tap.update(keyCode: 56, modifierEvent: true, shift: true, other: false)
        && tap.update(keyCode: 56, modifierEvent: true, shift: false, other: false), "單按Shift釋放切換一次")
    _ = tap.update(keyCode: 56, modifierEvent: true, shift: true, other: false)
    _ = tap.update(keyCode: 0, modifierEvent: false, shift: true, other: false)
    h.check(!tap.update(keyCode: 56, modifierEvent: true, shift: false, other: false), "Shift打大寫不切換")
    _ = tap.update(keyCode: 60, modifierEvent: true, shift: true, other: false)
    _ = tap.update(keyCode: 55, modifierEvent: true, shift: true, other: true)
    h.check(!tap.update(keyCode: 60, modifierEvent: true, shift: false, other: false), "Shift組合快捷鍵不切換")
    var inferredTap = ShiftTapTracker()
    h.check(!inferredTap.update(keyCode: 0, modifierEvent: true, shift: true, other: false)
        && inferredTap.update(keyCode: 0, modifierEvent: true, shift: false, other: false), "無實體鍵碼的 Shift 事件可切換")
    var mixedTap = ShiftTapTracker()
    h.check(!mixedTap.update(keyCode: 60, modifierEvent: true, shift: true, other: false)
        && mixedTap.update(keyCode: 0, modifierEvent: true, shift: false, other: false), "Shift 釋放事件缺鍵碼仍可切換")
    var shortcutTap = ShiftTapTracker()
    _ = shortcutTap.update(keyCode: 0, modifierEvent: true, shift: true, other: false)
    _ = shortcutTap.update(keyCode: 12, modifierEvent: false, shift: true, other: false)
    h.check(!shortcutTap.update(keyCode: 0, modifierEvent: true, shift: false, other: false), "缺鍵碼的 Shift 組合鍵不誤切換")
    engine.clear(); engine.selectMode(.pinyin); engine.english = false
    engine.key(Engine.toggleEnglishKey)
    engine.key(Int32(Character("a").asciiValue!))
    h.check(engine.english && engine.takeCommit() == "a", "切換英文後直接輸入字母")
    engine.key(Engine.toggleEnglishKey); h.type("nihao")
    h.check(!engine.english && engine.snapshot.candidates.first == "你好", "再次切換回拼音")
    if let expandedBackend = try? ChineseWithJapaneseBackend(mode: .pinyin) {
        engine.clear(); engine.selectMode(.pinyin); h.type("gan")
        for byte in "gan".utf8 { _ = expandedBackend.key(Int32(byte), modifiers: 0) }
        h.check(engine.snapshot.candidates == expandedBackend.snapshot().candidates, "展開候選保留後端排序")
    } else { h.check(false, "展開候選排序引擎可載入") }
    for input in ["sushi", "watashihaneko"] {
        engine.clear(); h.type(input)
        let snapshot = engine.snapshot
        let visible = collapsedCandidateIndices(snapshot)
        h.check(visible.filter { compactCandidateText(snapshot, at: $0).map(snapshot.japaneseCandidates.contains) ?? false }.count == 1, "收合只顯示一個日語 " + input)
        var japanese = Set(snapshot.candidates.filter { snapshot.japaneseCandidates.contains($0) })
        for _ in 0..<40 where japanese.count < 2 && !engine.snapshot.lastPage {
            engine.key(0xff56)
            japanese.formUnion(engine.snapshot.candidates.filter { snapshot.japaneseCandidates.contains($0) })
        }
        h.check(japanese.count >= 2, "其餘日語仍可展開翻頁 " + input)
    }
    engine.clear(); h.type("hell")
    h.check(engine.snapshot.requiredCandidate == nil, "不完整羅馬音不強制日語候選")
    h.expect(.japanese, "sora", "空")
    h.expect(.japanese, "me", "目")
    for (input, expected) in [("oshikatsu", "推し活"), ("tuishihuo", "推し活"), ("seiyuu", "声優"), ("seiyu", "声優"),
                              ("doujinshi", "同人誌"), ("haishin", "配信"), ("utaite", "歌い手"),
                              ("shiawase", "幸せ"), ("bangumi", "番組")] {
        h.expect(.japanese, input, expected)
    }
    engine.clear(); engine.selectMode(.pinyin); h.type("woxihuanseiyuu")
    h.check(engine.snapshot.candidates.contains("我喜歡声優"), "新增日語詞可用於中日混合長句")
    engine.clear(); h.type("tuishihuo")
    h.check(engine.snapshot.candidates.contains("推し活") && engine.snapshot.japaneseCandidates.contains("推し活"), "拼音別寫可選標記日語的推し活")
    let oshiVisible = collapsedCandidateIndices(engine.snapshot)
    let oshiJapanese = oshiVisible.filter { compactCandidateText(engine.snapshot, at: $0).map(engine.snapshot.japaneseCandidates.contains) ?? false }
    h.check(oshiJapanese.count == 1 && oshiVisible == oshiVisible.sorted(), "推し活候選收合保留一個日語並遵循引擎次序")
    if engine.snapshot.candidates.contains(where: { !engine.snapshot.japaneseCandidates.contains($0) && $0.unicodeScalars.contains { (0x4e00...0x9fff).contains(Int($0.value)) } }), let first = oshiVisible.first {
        h.check(!engine.snapshot.japaneseCandidates.contains(engine.snapshot.candidates[first]), "推し活別寫有中文時中文優先")
    }
    engine.clear(); h.type("woxihuantuishihuo")
    h.check(engine.snapshot.candidates.contains("我喜歡推し活"), "推し活別寫可用於中日混合句")
    Preferences.shared.simplified = true; engine.modeChanged()
    engine.clear(); h.type("woxihuantuishihuo")
    h.check(engine.snapshot.candidates.contains("我喜欢推し活"), "推し活別寫可用於簡體中日混合句")
    Preferences.shared.simplified = false; engine.modeChanged()
    h.expect(.zhuyin, "u. cj84", "優化")
    engine.clear(); engine.selectMode(.pinyin)
    Preferences.shared.rememberChoices = true
    ChoiceMemory.shared.forget("py:t:bang")
    for _ in 0..<15 { ChoiceMemory.shared.remember([("py:t:bang", "浜")]) }
    engine.clear(); h.type("bang")
    h.check(engine.snapshot.candidates.first == "浜", "個人選詞仍可提升較少用的字")
    let savedBangUses = ChoiceMemory.shared.records["py:t:bang"]?.first?.uses
    Preferences.shared.rememberChoices = false; engine.refresh()
    h.check(engine.snapshot.input == "bang" && engine.snapshot.candidates.first == "幫", "停用記憶即時恢復常用字且保留組字")
    h.check(ChoiceMemory.shared.records["py:t:bang"]?.first?.uses == savedBangUses, "停用記憶不刪除個人記錄")
    Preferences.shared.rememberChoices = true; engine.refresh()
    h.check(engine.snapshot.input == "bang" && engine.snapshot.candidates.first == "浜", "重新啟用記憶可即時恢復個人選詞")
    ChoiceMemory.shared.forget("py:t:bang"); engine.refresh()
    h.check(engine.snapshot.input == "bang" && engine.snapshot.candidates.first == "幫", "清除記錄即時更新候選")
    engine.clear(); h.type("nihao"); engine.choose(0)
    Preferences.shared.rememberChoices = false; engine.refresh(); engine.refresh()
    let queuedCommit = engine.takeCommit()
    h.check(queuedCommit == "你好" && engine.takeCommit().isEmpty, "記憶刷新不遺失或重複待上屏文字")
    engine.clear(); h.type("nihaox")
    let prefixIndex = engine.snapshot.candidates.firstIndex(of: "你好")
    h.check(prefixIndex != nil, "未完成後綴保留可選詞組")
    if let prefixIndex {
        engine.choose(prefixIndex)
        Preferences.shared.rememberChoices = true; engine.refresh(); engine.refresh()
        let partialCommit = engine.takeCommit()
        h.check(partialCommit == "你好" && engine.takeCommit().isEmpty && engine.snapshot.input == "x", "記憶刷新保留部分上屏和未完成後綴")
        engine.key(Int32(Character("i").asciiValue!))
        h.check(engine.snapshot.input == "xi", "記憶刷新後可繼續輸入後綴")
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
        h.check(mixedCase.takeCommit() == "你好" && mixedCase.snapshot.input == "X", "大小寫部分選詞保留原始後綴（\(script)）")
        h.check(ChoiceMemory.shared.records[prefix]?.contains(where: { $0.text == "你好" }) == true, "大小寫拼音共用已選詞記憶（\(script)）")
        h.check(ChoiceMemory.shared.records[unfinished]?.contains(where: { $0.text == "你好" }) != true, "大小寫未完成後綴不納入記憶（\(script)）")
        for key in [prefix, unfinished] { ChoiceMemory.shared.forget(key) }
    }
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        Preferences.shared.rememberChoices = true
        let script = simplified ? "s:" : "t:"
        let phrase = simplified ? "我喜欢" : "我喜歡"
        let whole = simplified ? "我喜欢我" : "我喜歡我"
        let selectedKey = "py:" + script + "woxihuan"
        let unfinishedKey = selectedKey + "w"
        let wholeKey = selectedKey + "wo"
        for key in [selectedKey, unfinishedKey, wholeKey, "py:" + script + "wo"] { ChoiceMemory.shared.forget(key) }
        let segmented = Engine(.meowpuut, mode: .pinyin)
        // woxihuann reads as a whole now (我喜歡你, n as the initial of 你), so the
        // unfinished initial is w here.
        for key in "woxihuanw".utf8 { segmented.key(Int32(key)) }
        let choice = segmented.snapshot.candidates.firstIndex(of: phrase)
        h.check(choice != nil, "拼音未完成聲母可選前段（\(script)）")
        if let choice {
            segmented.choose(choice)
            h.check(segmented.takeCommit() == phrase && segmented.snapshot.input == "w", "拼音部分選詞保留聲母（\(script)）")
            h.check(ChoiceMemory.shared.records[selectedKey]?.contains(where: { $0.text == phrase }) == true, "記憶只記已選拼音（\(script)）")
            h.check(ChoiceMemory.shared.records[unfinishedKey]?.contains(where: { $0.text == phrase }) != true, "未完成後綴不納入記憶（\(script)）")
            segmented.key(Int32(Character("o").asciiValue!))
            if let next = segmented.snapshot.candidates.firstIndex(of: "我") { segmented.choose(next) }
            h.check(segmented.takeCommit() == "我" && segmented.snapshot.input.isEmpty, "剩餘拼音可完成上屏（\(script)）")
            h.check(ChoiceMemory.shared.records[wholeKey]?.contains(where: { $0.text == whole }) == true, "多段選詞記憶完整句（\(script)）")
        }
        for key in [selectedKey, unfinishedKey, wholeKey, "py:" + script + "wo", "ctx|" + phrase + "|py:" + script + "wo"] { ChoiceMemory.shared.forget(key) }

        let zhuyinKey = script + "jivu"
        let zhuyinUnfinished = zhuyinKey + "c"
        let zhuyinWhole = zhuyinKey + "cj0 "
        let nextKey = script + "cj0 "
        let last = simplified ? "欢" : "歡"
        for key in [zhuyinKey, zhuyinUnfinished, zhuyinWhole, nextKey] { ChoiceMemory.shared.forget(key) }
        let zhuyin = Engine(.meowpuut, mode: .zhuyin)
        for key in "jivuc".utf8 { zhuyin.key(Int32(key)) }
        let zhuyinChoice = zhuyin.snapshot.candidates.firstIndex(of: "我喜")
        h.check(zhuyinChoice != nil, "注音未完成聲母可選前段（\(script)）")
        if let zhuyinChoice {
            zhuyin.choose(zhuyinChoice)
            h.check(zhuyin.takeCommit() == "我喜" && zhuyin.snapshot.input == "c", "注音部分選詞保留聲母（\(script)）")
            h.check(ChoiceMemory.shared.records[zhuyinKey]?.contains(where: { $0.text == "我喜" }) == true, "記憶只記已選注音（\(script)）")
            h.check(ChoiceMemory.shared.records[zhuyinUnfinished]?.contains(where: { $0.text == "我喜" }) != true, "未完成注音不納入記憶（\(script)）")
            for key in "j0 ".utf8 { zhuyin.key(Int32(key)) }
            if let next = zhuyin.snapshot.candidates.firstIndex(of: last) { zhuyin.choose(next) }
            h.check(zhuyin.takeCommit() == last && zhuyin.snapshot.input.isEmpty, "剩餘注音可完成上屏（\(script)）")
            h.check(ChoiceMemory.shared.records[zhuyinWhole]?.contains(where: { $0.text == ("我喜" + last) }) == true, "注音多段選詞記憶完整詞（\(script)）")
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
        h.check(prefix != nil, "拼音部分選詞可取得上下文（\(script)）")
        if let prefix {
            contextual.choose(prefix)
            h.check(contextual.takeCommit() == "我喜" && contextual.snapshot.input == "h", "選擇前段保留拼音與已選文字（\(script)）")
            for key in "uan".utf8 { contextual.key(Int32(key)) }
            h.check(contextual.snapshot.candidates.contains(like), "重建後依前段顯示後續詞（\(script)）")
            contextual.key(0xff08); contextual.key(Int32(Character("n").asciiValue!))
            h.check(contextual.snapshot.input == "huan" && contextual.snapshot.candidates.contains(like), "拼音重播保留分段上下文（\(script)）")
            contextual.clear()
            for key in "huan".utf8 { contextual.key(Int32(key)) }
            h.check(contextual.snapshot.candidates == uncontextualized, "清除後上下文不洩漏到新組字（\(script)）")
        }
        let memoryKey = "py:" + script + "huan"
        ChoiceMemory.shared.forget(memoryKey)
        Preferences.shared.rememberChoices = true
        for _ in 0..<20 { ChoiceMemory.shared.remember([(memoryKey, replace)]) }
        let remembered = Engine(.meowpuut, mode: .pinyin)
        for key in "woxih".utf8 { remembered.key(Int32(key)) }
        if let prefix = remembered.snapshot.candidates.firstIndex(of: "我喜") { remembered.choose(prefix); _ = remembered.takeCommit() }
        for key in "uan".utf8 { remembered.key(Int32(key)) }
        h.check(remembered.snapshot.candidates.first == replace, "分段上下文保留個人選詞優先（\(script)）")
        ChoiceMemory.shared.forget(memoryKey)
    }
    Preferences.shared.simplified = false
    engine.clear()
    Preferences.shared.rememberChoices = true
    for _ in 0..<3 { ChoiceMemory.shared.remember([("py:t:youhua", "油畫")]) }
    h.expect(.pinyin, "youhua", "優化")
    engine.clear(); h.type("youhua")
    h.check(engine.snapshot.candidates.first == "優化", "少量舊選詞不壓過高頻正確詞")
    Preferences.shared.rememberChoices = false
}
