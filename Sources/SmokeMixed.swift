import AppKit
import CryptoKit

func runSmokeMixedAndLayout(_ h: inout SmokeHarness) {
    runClientGolden(&h)
    runJapaneseLexiconSmoke(&h)
    let engine = h.engine
    engine.clear(); h.type("youhua")
    h.check(engine.snapshot.candidates.first == "優化" && engine.snapshot.japaneseCandidates.contains("ようふあ"), "優化優先且假名候選有標記")
    engine.clear(); h.type("kaixin")
    h.check(engine.snapshot.candidates.first == "開心", "詞義關聯emoji不越過中文")
    while !engine.snapshot.lastPage && !engine.snapshot.candidates.contains("😊") { engine.key(0xff56) }
    if let index = engine.snapshot.candidates.firstIndex(of: "😊") { engine.choose(index); h.check(engine.takeCommit() == "😊", "emoji候選可上屏") }
    else { h.check(false, "emoji候選可上屏") }
    engine.clear(); h.type("hello")
    h.check(engine.snapshot.candidates.first == "hello" && engine.snapshot.literalCandidate, "英文單字首選原文")
    engine.key(32)
    h.check(engine.takeCommit() == "hello", "拼音空格輸出英文原文不添加空格")
    h.check(!engine.key(32), "無組字時空格交由應用程式輸入")
    engine.english = true
    h.check(engine.key(32) && engine.takeCommit() == " ", "英文模式空格照常輸入")
    engine.english = false
    engine.clear(); h.type("ni"); engine.browse(phrases: true)
    h.check(!engine.snapshot.candidates.contains("ni"), "詞組篩選不顯示中文讀音原文")
    engine.clear(); h.type("hello"); engine.browse(phrases: true)
    h.check(engine.snapshot.candidates.contains("hello"), "詞組篩選保留英文首選")
    engine.clear(); engine.selectMode(.zhuyin); h.type("internationalization")
    h.check(engine.snapshot.candidates.first == "internationalization", "注音模式保留長英文")
    engine.clear(); engine.selectMode(.pinyin); engine.english = false
    h.type("ma"); engine.key(32, modifiers: 1)
    h.check(engine.snapshot.input == "ma1" && !engine.english && engine.snapshot.candidates.contains("媽") && !engine.snapshot.candidates.contains("馬"), "Shift 空格標一聲，排除錯聲調詞庫補詞")
    engine.key(35, modifiers: 1)
    h.check(engine.snapshot.input == "ma3" && engine.snapshot.candidates.contains("馬") && !engine.snapshot.candidates.contains("媽"), "Shift 3 標三聲並可修正上一聲調")
    engine.key(0xff08)
    h.check(engine.snapshot.input == "ma" && engine.snapshot.candidates.contains("媽"), "退格刪除聲調恢復無調候選")
    engine.clear(); h.type("ni"); engine.key(35, modifiers: 1); h.type("hao"); engine.key(35, modifiers: 1)
    h.check(engine.snapshot.input == "ni3hao3" && engine.snapshot.candidates.contains("你好"), "多音節拼音聲調解碼")
    let toneChoice = engine.snapshot.candidates.first ?? ""
    engine.key(49)
    h.check(engine.takeCommit() == toneChoice, "帶調拼音仍用普通數字選詞")
    for (key, value) in [(Int32(94), "ma2"), (36, "ma4"), (38, "ma5")] {
        engine.clear(); h.type("ma"); engine.key(key, modifiers: 1)
        h.check(engine.snapshot.input == value, "Shift 大千聲調位置 \(value)")
    }
    engine.clear(); engine.key(35, modifiers: 1)
    h.check(engine.takeCommit() == "#", "空組字保留 Shift 英文符號")
    engine.key(Engine.toggleEnglishKey); engine.key(35, modifiers: 1)
    h.check(engine.english && engine.takeCommit() == "#", "英文模式不攔截聲調快捷鍵")
    engine.key(Engine.toggleEnglishKey)
    engine.clear(); engine.selectMode(.pinyin); h.type("nihao,")
    h.check(engine.takeCommit() == "你好，", "拼音直接輸入中文逗號")
    engine.key(46); h.check(engine.takeCommit() == "。", "無組字拼音中文句號")
    h.type("nihao"); engine.key(62, modifiers: 1)
    h.check(engine.takeCommit() == "你好.", "拼音Shift輸入英文句號")
    engine.key(63, modifiers: 1); h.check(engine.takeCommit() == "?", "拼音Shift問號保留英文符號")
    Preferences.shared.rememberChoices = true
    for _ in 0..<2 {
        h.expect(.pinyin, "miaomiao", "喵喵")
        h.expect(.pinyin, "kaixin", "開心")
    }
    engine.clear(); h.type("miaomiaokaixin")
    h.check(engine.snapshot.candidates.contains("喵喵開心"), "自動收集跨次選詞組合")
    h.check(ChoiceMemory.shared.records["ctx|喵喵|py:t:kaixin"]?.first?.uses == 2, "上下文選詞記憶")
    // Emoji and kaomoji: first in the row after a commit; a picked one leads next time.
    engine.clear(); h.type("shengrikuaile"); engine.key(32); _ = engine.takeCommit()
    h.check(Array(engine.predictions.prefix(2)) == ["🎂", "🎉"], "選詞後表情排在聯想詞前")
    h.check(engine.choosePrediction(1) == "🎉" && engine.predictions.first == "🎂" && Symbols.of("生日快樂")?.emoji.first == "🎉", "點過的表情下次排第一，聯想列不變")
    h.check(Symbols.after("我不想睡", japanese: false) == nil && Symbols.after("我想睡", japanese: false)?.word == "想睡" && Symbols.after("今天好累，", japanese: false)?.word == "好累", "句尾的詞有表情，否定句沒有")
    h.check(Symbols.of("手紙") == nil && Symbols.of("手紙", japanese: true)?.emoji.first == "✉️", "中文和日語的表情分開查")
    Preferences.shared.rememberChoices = false
    Preferences.shared.simplified = true
    h.expect(.pinyin, "zhongguo", "中国")
    h.expect(.zhuyin, "5j/4", "种")
    for (input, expected) in [("sushi", "寿司"), ("neko", "猫"), ("gakkou", "学校"), ("konnichiha", "こんにちは"), ("nihongo", "日本語"), ("ko-hi-", "コーヒー")] { h.expect(.japanese, input, expected) }
    for (input, expected) in [("konnichiha", "こんにちは"), ("shin'ya", "しんや"), ("gakkou", "がっこう"), ("kya", "きゃ"), ("xtsu", "っ"), ("n", "ん"), ("nn", "ん"), ("neko", "ねこ")] { h.check(Romaji.reading(input) == expected, "羅馬字 \(input)") }
    engine.clear(); h.type("neko"); engine.key(0xffc4); h.check(engine.takeCommit() == "ネコ", "F7 片假名")
    engine.clear(); h.type("neko"); engine.key(0xffc3); h.check(engine.takeCommit() == "ねこ", "F6 平假名")
    Preferences.shared.simplified = false
    h.expect(.zhuyin, "su3cl3", "你好")
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        engine.clear(); engine.selectMode(.zhuyin); engine.modeChanged()
        h.type("ji3ap7rup wu0 fm4d042u04u/3")
        let full = simplified ? "我们今天去看电影" : "我們今天去看電影"
        let prefix = simplified ? "我们今天" : "我們今天"
        let film = simplified ? "去看电影" : "去看電影"
        h.check(engine.snapshot.candidates.first == full, "注音完整句保留首選")
        if let index = engine.snapshot.candidates.firstIndex(of: prefix) { engine.choose(index) }
        h.check(engine.takeCommit() == prefix && engine.snapshot.input == "fm4d042u04u/3", "注音分段選詞保留完整後綴")
        if let index = engine.snapshot.candidates.firstIndex(of: film) { engine.choose(index) }
        h.check(engine.takeCommit() == film && engine.snapshot.input.isEmpty, "注音後續分段完整上屏")
        engine.clear(); h.type("su3cl3v")
        h.check(engine.snapshot.candidates.first == "你好", "注音未完成聲母保留已完成詞組")
        h.check(engine.snapshot.candidateDetails.first?.metadata?.remainingInput == "v" &&
              engine.snapshot.candidateDetails.first?.source == 0, "注音候選保留原始尾碼")
        engine.choose(0)
        h.check(engine.takeCommit() == "你好" && engine.snapshot.input == "v", "注音分段保留未完成聲母")
        h.type("u3cj0 ")
        h.check(engine.snapshot.candidates.contains(simplified ? "喜欢" : "喜歡"), "注音分段後繼續輸入")
    }
    Preferences.shared.simplified = false
    engine.clear(); engine.selectMode(.zhuyin); h.type("nihao")
    h.check(!engine.snapshot.candidates.contains("你好"), "注音不借用拼音候選")
    h.check(engine.snapshot.preedit != "nihao", "注音保留注音組字")
    for raw in ["sushi", "suki", "neko", "a"] {
        engine.clear(); engine.selectMode(.zhuyin); h.type(raw)
        h.check(engine.snapshot.japaneseCandidates.isEmpty && engine.snapshot.requiredCandidate == nil, "注音不混日語 " + raw)
        h.check(!engine.snapshot.candidates.contains("寿司") && !engine.snapshot.candidates.contains("好き"), "注音不插入日語詞 " + raw)
    }
    engine.clear(); engine.selectMode(.pinyin); h.type("su3cl3")
    h.check(!engine.snapshot.candidates.contains("你好"), "拼音不借用注音候選")
    h.expect(.pinyin, "nihao", "你好")
    for (simplified, expected) in [(false, "我喜歡你"), (true, "我喜欢你")] {
        Preferences.shared.simplified = simplified
        engine.clear(); engine.modeChanged(); h.type("woxihuannid")
        h.check(engine.snapshot.candidates.first == expected, "長句未完成尾音保留整句候選")
        engine.choose(0)
        h.check(engine.takeCommit() == expected && engine.snapshot.input == "d", "長句選詞保留未完成尾音")
        h.type("e")
        h.check(engine.snapshot.input == "de" && engine.snapshot.candidates.contains("的"), "未完成尾音可繼續輸入")
        engine.clear(); h.type("womenjintianq")
        let incompleteOnset = simplified ? "我们今天" : "我們今天"
        h.check(engine.snapshot.candidates.first == incompleteOnset, "不完整聲母優先顯示已完成句段")
        engine.choose(0)
        h.check(engine.takeCommit() == incompleteOnset && engine.snapshot.input == "q", "已完成句段保留剩餘聲母")
        engine.clear(); h.type("womenjintianbang")
        h.check(engine.snapshot.candidates.first == (simplified ? "我们今天帮" : "我們今天幫"), "完整音節不被提前切分")
        engine.clear(); h.type("womenjintianquk")
        // Where the first segment ends depends on the phrases that start with "quk": with 去看了 and
        // 去看一看 in the lexicon the engine may keep 去 open (我們今天 + "quk"). Both splits are
        // valid; the second segment then carries 去.
        let firstSegment = engine.snapshot.candidates.first ?? ""
        let keepsQu = firstSegment == (simplified ? "我们今天" : "我們今天")
        let secondSegment = (keepsQu ? "去" : "") + (simplified ? "看电影" : "看電影")
        let lastSegment = simplified ? "再见" : "再見"
        h.check(keepsQu || firstSegment == (simplified ? "我们今天去" : "我們今天去"), "長句首段候選")
        engine.choose(0)
        h.check(engine.takeCommit() == firstSegment && engine.snapshot.input == (keepsQu ? "quk" : "k"), "首段上屏後保留尾音")
        h.type("andianyingz")
        h.check(engine.snapshot.candidates.first == secondSegment, "後續句段候選")
        engine.choose(0)
        h.check(engine.takeCommit() == secondSegment && engine.snapshot.input == "z", "後續句段上屏保留尾音")
        h.type("aijian")
        if let index = engine.snapshot.candidates.firstIndex(of: lastSegment) { engine.choose(index) }
        h.check(engine.takeCommit() == lastSegment && engine.snapshot.preedit.isEmpty, "多段輸入最終完整上屏")
    }
    Preferences.shared.simplified = false
    engine.modeChanged()
    h.expect(.pinyin, "sushi", "寿司")
    h.expect(.pinyin, "neko", "猫")
    engine.clear(); h.type("wosukini")
    let mixedSnapshot = engine.snapshot
    let mixedPhraseVisible = collapsedCandidateIndices(mixedSnapshot).compactMap { compactCandidateText(mixedSnapshot, at: $0) }
    h.check(mixedSnapshot.input == "wosukini" && mixedPhraseVisible.first == "我好き你", "完整中日混句優先於單字回退且收合可見")
    h.check(mixedPhraseVisible.filter { mixedSnapshot.japaneseCandidates.contains($0) }.count == 1, "完整混句保留日語標記且收合只顯示一個")
    if let index = mixedSnapshot.candidates.firstIndex(of: "我好き你") { engine.choose(index) }
    h.check(engine.takeCommit() == "我好き你" && engine.snapshot.preedit.isEmpty, "混合詞完整上屏")
    for (reading, expected) in [("suki", "好き"), ("haoki", "好き"), ("xingku", "行く")] {
        engine.clear(); h.type(reading)
        h.check(engine.snapshot.candidates.contains(expected) && engine.snapshot.japaneseCandidates.contains(expected), "\(reading) 可選日語詞並標示語言")
    }
    h.check(!engine.snapshot.candidates.contains { text in
        text.contains("x") && text.unicodeScalars.contains { (0x3040...0x30ff).contains($0.value) }
    }, "未完成羅馬字不產生混有原文字母的假名候選")
    engine.clear(); h.type("wohaokini")
    let hybrid = engine.snapshot
    let hybridVisible = collapsedCandidateIndices(hybrid).compactMap { compactCandidateText(hybrid, at: $0) }
    h.check(hybridVisible.first == "我好き你" && hybrid.candidates.first == "我好き你" && hybridVisible.filter { hybrid.japaneseCandidates.contains($0) }.count == 1, "拼音與假名混寫完整句優於單字回退")
    engine.clear(); h.type("woxiangshiberusushiheramen")
    h.check(engine.snapshot.candidates.contains("我想食べる寿司和ラーメン"), "中日長句可包含三段日語")
    engine.clear(); h.type("wohaokiyinyue")
    h.check(engine.snapshot.candidates.contains("我好き音楽"), "日語證據下可用拼音輸入日語漢字")
    engine.clear(); h.type("woxihuanyinyue")
    h.check(engine.snapshot.candidates.first == "我喜歡音樂" && !engine.snapshot.candidates.contains("我喜歡音楽"), "純中文音樂不被日語拼音別名改寫")
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        for suffix in ["ki", "kir", "kire"] {
            engine.clear(); engine.modeChanged(); h.type("jinwandeyueliang" + suffix)
            let prefix = "今晚的月亮"
            let compact = collapsedCandidateIndices(engine.snapshot).compactMap { compactCandidateText(engine.snapshot, at: $0) }
            h.check(compact.contains(prefix), "未完成日語尾音仍顯示整段中文")
            if let index = engine.snapshot.candidates.firstIndex(of: prefix) { engine.choose(index) }
            h.check(engine.takeCommit() == prefix && engine.snapshot.input == suffix, "中日前段選詞保留未完成羅馬字")
        }
        engine.clear(); engine.modeChanged(); h.type("jinwandeyueliangkirei")
        let complete = "今晚的月亮綺麗"
        let compact = collapsedCandidateIndices(engine.snapshot).compactMap { compactCandidateText(engine.snapshot, at: $0) }
        h.check(engine.snapshot.candidates.first == complete && compact.first == complete,
              "完整中日長句優先於首字回退並收合可見")
        h.check(engine.snapshot.japaneseCandidates.contains(complete) && compact.filter { engine.snapshot.japaneseCandidates.contains($0) }.count == 1,
              "完整中日長句保留標記且收合僅一個日語")
        engine.choose(0)
        h.check(engine.takeCommit() == complete && engine.snapshot.input.isEmpty, "完整中日長句選取後準確上屏")
    }
    Preferences.shared.simplified = false; engine.clear(); engine.modeChanged()
    let mixedMemoryKey = "py:t:jinwandeyueliangkirei"
    ChoiceMemory.shared.forget(mixedMemoryKey)
    Preferences.shared.rememberChoices = true
    for _ in 0..<15 { ChoiceMemory.shared.remember([(mixedMemoryKey, "進")]) }
    h.type("jinwandeyueliangkirei")
    h.check(engine.snapshot.candidates.first == "進", "明確個人選詞保留在完整混句之前")
    ChoiceMemory.shared.forget(mixedMemoryKey)
    Preferences.shared.rememberChoices = false
    engine.clear(); h.type("hello")
    h.check(engine.snapshot.literalCandidate && engine.snapshot.candidates.first == "hello", "英文首選不被日語拼音別名改寫")
    if let native = try? NativeBackend(mode: .pinyin) {
        h.check(native.mixedPinyin("woyaoauni", simplified: false) == "我要会う你", "實際日語詞表將短讀音 au 註冊至共用核心")
        let shortPrefix = native.mixedPinyinPrefix("aunihao", simplified: false)
        h.check(shortPrefix?.text == "会う" && shortPrefix?.consumed == 2, "短日語前段選詞保留完整中文後綴")
        h.check(["audio", "auto", "autumn", "author", "authentication", "automobile"].allSatisfy { native.mixedPinyin($0, simplified: false) == nil }, "短日語讀音不把英文猜成中日混寫")
    } else { h.check(false, "短讀音日語測試引擎可載入") }
    runSmokeSegmentsAndGrid(&h)
}

private func runSmokeSegmentsAndGrid(_ h: inout SmokeHarness) {
    let engine = h.engine
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
        h.check(selectSegment("我好き"), "可選完整中日前半段")
        var segmentState = segments.snapshot()
        h.check(segmentState.commit == "我好き" && segmentState.input == "nihao" && segmentState.candidates.contains("你好"), "選中混合前半後保留中文後綴")
        h.check(selectSegment("你好"), "可繼續選擇中文後半段")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "你好" && segmentState.input.isEmpty, "分段選詞各自上屏一次")
        enterSegmentReading("niHAO")
        h.check(selectSegment("你好"), "大小寫混合拼音可選完整詞")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "你好" && segmentState.input.isEmpty, "大小寫拼音選詞不丟字符")
        enterSegmentReading("NiHAOX")
        h.check(selectSegment("你好"), "大小寫拼音可選部分詞")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "你好" && segmentState.input == "X", "部分選詞保留原始大小寫後綴")
        for key in "iao".utf8 { _ = segments.key(Int32(key), modifiers: 0) }
        segmentState = segments.snapshot()
        h.check(segmentState.input == "Xiao" && segments.candidates(limit: 256).contains(where: { $0.text == "小" }), "保留大寫後綴可繼續組字")
        enterSegmentReading("HelloWORLD")
        _ = segments.key(32, modifiers: 0)
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "HelloWORLD " && segmentState.input.isEmpty, "英文原文保留大小寫")
        enterSegmentReading("ni''hao")
        segmentState = segments.snapshot()
        h.check(segmentState.input == "ni''hao" && segmentState.candidates.first == "ni''hao", "無效分隔符保留完整原文候選")
        for _ in 0..<4 { _ = segments.key(0xff08, modifiers: 0) }
        for key in "hao".utf8 { _ = segments.key(Int32(key), modifiers: 0) }
        h.check(selectSegment("你好"), "刪除多餘分隔符後恢復中文候選")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "你好" && segmentState.input.isEmpty, "修正後中文正常上屏")
        enterSegmentReading("ni''hao")
        _ = segments.key(32, modifiers: 0)
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "ni''hao " && segmentState.input.isEmpty, "無效分隔符原文可安全上屏")
        enterSegmentReading("xi'an")
        h.check(selectSegment("西安"), "有效拼音分隔符仍可轉換")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "西安" && segmentState.input.isEmpty, "有效分隔符正確消耗")
        enterSegmentReading("WoHAoKiNiHao")
        h.check(selectSegment("我好き"), "大小寫混合讀音可選中日前段")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "我好き" && segmentState.input == "NiHao", "中日前段保留後綴原始大小寫")
        h.check(selectSegment("你好"), "混合大小寫後綴可繼續轉換")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "你好" && segmentState.input.isEmpty, "大小寫混合中日分段上屏一次")
        enterSegmentReading("wohaokinihao")
        h.check(selectSegment("我好き"), "拼音假名混寫可選前半段")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "我好き" && segmentState.input == "nihao", "拼音假名前半保留後續讀音")
        h.check(selectSegment("你好"), "拼音假名混寫可繼續選中文")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "你好" && segmentState.input.isEmpty, "拼音假名分段只各上屏一次")
        enterSegmentReading("aunihao")
        h.check(selectSegment("会う"), "短讀音日語可選前半段")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "会う" && segmentState.input == "nihao" && segments.candidates(limit: 256).contains(where: { $0.text == "你好" }), "短讀音日語保留並重建中文後綴")
        h.check(selectSegment("你好"), "短讀音後可繼續選中文")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "你好" && segmentState.input.isEmpty, "短讀音分段不漏字且各自上屏一次")
        enterSegmentReading("sukinihao")
        h.check(selectSegment("好き"), "可選日語前半段")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "好き" && segmentState.input == "nihao", "日語前半段不吞中文讀音")
        enterSegmentReading("wosukini")
        h.check(selectSegment("我"), "可選中文前半段")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "我" && segmentState.input == "sukini" && segments.candidates(limit: 256).contains(where: { $0.text == "好き你" }), "中文前半後恢復中日混合候選")
        h.check(selectSegment("好き你"), "可繼續選擇日中後半段")
        segmentState = segments.snapshot()
        h.check(segmentState.commit == "好き你" && segmentState.input.isEmpty, "中日分段完成不丟字")
        enterSegmentReading("nihao")
        segmentState = segments.snapshot()
        h.check(segmentState.candidates.first == "你好" && !segmentState.japaneseCandidates.contains("你好"), "純中文首選不受混合影響")
    } else { h.check(false, "分段測試引擎可載入") }
    engine.clear(); h.type("wosukini")
    if let index = engine.snapshot.candidates.firstIndex(of: "我") { engine.choose(index) }
    h.check(!engine.takeCommit().isEmpty && !engine.snapshot.input.isEmpty && "wosukini".hasSuffix(engine.snapshot.input), "混合讀音部分選詞保留未轉換後綴")
    engine.clear(); h.type("wosukini"); engine.key(0xff08)
    h.check(engine.snapshot.input == "wosukin", "混合輸入退格保留原文")
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        engine.clear(); engine.modeChanged(); h.type("wosukin")
        let partialMixed = engine.snapshot
        let macPartial = collapsedCandidateIndices(partialMixed).compactMap { compactCandidateText(partialMixed, at: $0) }
        let iosPartial = partialMixed.compactIndices.map { partialMixed.candidates[$0] }
        h.check(macPartial.contains("我好き") && iosPartial.contains("我好き"), "未完成中文後綴時收合欄保留可選中日前段")
        h.check(macPartial.filter { partialMixed.japaneseCandidates.contains($0) } == ["我好き"] &&
              iosPartial.filter { partialMixed.japaneseCandidates.contains($0) } == ["我好き"],
              "已記錄中日前段優先於推測假名且收合只顯示一個日語")
        if let ordinal = macPartial.firstIndex(of: "我好き"),
           let index = partialMixed.candidates.firstIndex(of: "我好き") {
            let shortcut = collapsedCandidateShortcut(partialMixed, key: Int32(49 + ordinal),
                                                      modifiers: 0, shifted: false, english: false)
            h.check(shortcut == .choose(index), "中日前段的收合編號對應可見候選")
            if case .choose(let selected) = shortcut { engine.choose(selected) }
        } else { h.check(false, "中日前段具有可用收合編號") }
        h.check(engine.takeCommit() == "我好き" && engine.snapshot.input == "n", "選中日片段僅消耗其讀音並保留未完成後綴")
        h.type("i")
        h.check(engine.snapshot.candidates.contains("你"), "保留的後綴可以繼續選字")
    }
    Preferences.shared.simplified = false; engine.clear(); engine.modeChanged()
    engine.clear(); h.type("nihaosuki")
    h.check(engine.snapshot.candidates.contains("你好好き"), "混合詞支持中文前綴")
    engine.clear(); h.type("wosukihanyu")
    h.check(engine.snapshot.candidates.contains("我好き漢語"), "混合詞支持繁體中文後綴")
    Preferences.shared.simplified = true
    engine.clear(); engine.modeChanged(); h.type("wosukihanyu")
    h.check(engine.snapshot.candidates.contains("我好き汉语"), "混合詞支持簡體中文後綴")
    Preferences.shared.simplified = false
    engine.clear(); engine.modeChanged(); h.type("nihao")
    h.check(engine.snapshot.candidates.first == "你好" && engine.snapshot.requiredCandidate != "你好", "純中文不受混合候選影響")
    engine.clear(); h.type("sushi")
    h.check(engine.snapshot.candidates.first == "蘇軾", "真實中文詞優先")
    h.check(collapsedCandidateIndices(engine.snapshot).compactMap { compactCandidateText(engine.snapshot, at: $0) }.contains("寿司"), "高可信日語保留收合位置")
    Preferences.shared.rememberChoices = true
    let reading = engine.pinKey
    if let index = engine.snapshot.candidates.firstIndex(of: "寿司") { engine.choose(index); _ = engine.takeCommit() }
    engine.clear(); h.type("sushi")
    h.check(engine.snapshot.candidates.first == "蘇軾" && ChoiceMemory.shared.preferred(for: reading).contains("寿司"), "日語記憶不越過中文")
    ChoiceMemory.shared.flush()
    h.check(ChoiceMemory().preferred(for: reading).contains("寿司"), "記憶可重新載入")
    runSmokePunctuationAndGrid(&h)
}

// Client goldens: the whole candidate list of the macOS backend for the inputs
// in Tests/golden/client-inputs.txt. Nothing happens unless one of the first two
// variables is set, so a plain --smoke run does not read or write the golden.
//   MEOWPUUT_GOLDEN_WRITE=1       write Tests/golden/client-candidates.tsv
//   MEOWPUUT_GOLDEN_CHECK=1       compare by the rules below
//   MEOWPUUT_GOLDEN_CHECK=strict  every list must be identical
//   MEOWPUUT_GOLDEN_DIR=path      golden directory (default: Tests/golden next to .build)
// A golden row is mode, script, keys, then every candidate as source[j]:text,
// where j marks a candidate flagged Japanese.
// Rules for pinyin with CHECK=1 (a Japanese word may be added, Chinese must not move):
//   1. the list without the Japanese-flagged candidates is identical;
//   2. candidates of sources 0, -2 and -3 keep their index;
//   3. candidates of sources -5 and -6 are identical;
//   4. candidates flagged Japanese in the golden are still there, flagged, in the same order.
// Zhuyin: the whole list is identical.
private struct GoldenItem: Equatable {
    let source: Int
    let japanese: Bool
    let text: String
    var token: String { "\(source)\(japanese ? "j" : ""):\(text)" }
    init(source: Int, japanese: Bool, text: String) {
        self.source = source; self.japanese = japanese
        self.text = text.replacingOccurrences(of: "\t", with: "\\t").replacingOccurrences(of: "\n", with: "\\n")
    }
    init?(token: Substring) {
        guard let colon = token.firstIndex(of: ":") else { return nil }
        var head = token[..<colon]
        japanese = head.hasSuffix("j")
        if japanese { head.removeLast() }
        guard let source = Int(head) else { return nil }
        self.source = source
        text = String(token[token.index(after: colon)...])
    }
}

private func goldenDifference(pinyin: Bool, strict: Bool, golden: [GoldenItem], now: [GoldenItem]) -> String? {
    if strict || !pinyin { return golden == now ? nil : "list differs" }
    if golden.filter({ !$0.japanese }) != now.filter({ !$0.japanese }) { return "rule 1: Chinese list differs" }
    for (index, item) in golden.enumerated() where [0, -2, -3].contains(item.source) {
        guard index < now.count, now[index].source == item.source, now[index].text == item.text else {
            return "rule 2: \(item.token) left index \(index)"
        }
    }
    let mixed: (GoldenItem) -> Bool = { $0.source == -5 || $0.source == -6 }
    if golden.filter(mixed) != now.filter(mixed) { return "rule 3: mixed sentence or prefix differs" }
    var rest = now.filter(\.japanese).map(\.text)[...]
    for text in golden.filter(\.japanese).map(\.text) {
        guard let found = rest.firstIndex(of: text) else { return "rule 4: \(text) missing or out of order" }
        rest = rest[rest.index(after: found)...]
    }
    return nil
}

private func runClientGolden(_ h: inout SmokeHarness) {
    let environment = ProcessInfo.processInfo.environment
    let write = environment["MEOWPUUT_GOLDEN_WRITE"] == "1"
    let checkMode = environment["MEOWPUUT_GOLDEN_CHECK"]
    guard write || checkMode != nil else { return }
    let directory = environment["MEOWPUUT_GOLDEN_DIR"].map { URL(fileURLWithPath: $0) }
        ?? Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tests/golden")
    let goldenURL = directory.appendingPathComponent("client-candidates.tsv")
    guard let inputText = try? String(contentsOf: directory.appendingPathComponent("client-inputs.txt"), encoding: .utf8),
          let pinyin = try? Engine.makeBackend(mode: .pinyin), let zhuyin = try? Engine.makeBackend(mode: .zhuyin) else {
        h.check(false, "候選基準：讀不到 \(directory.path)/client-inputs.txt 或引擎無法載入")
        return
    }
    let digest = Bundle.main.url(forResource: "mimo_base", withExtension: "bin")
        .flatMap { try? Data(contentsOf: $0, options: .mappedIfSafe) }
        .map { SHA256.hash(data: $0).map { String(format: "%02x", $0) }.joined() } ?? "none"
    let symbols = Array("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙")
    let keyForSymbol = Dictionary(uniqueKeysWithValues: zip(symbols, Array("1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347".utf8)))
    // The lists must not depend on what earlier checks left in the choice memory.
    let savedSimplified = Preferences.shared.simplified, savedRemember = Preferences.shared.rememberChoices
    Preferences.shared.simplified = false; Preferences.shared.rememberChoices = false
    defer { Preferences.shared.simplified = savedSimplified; Preferences.shared.rememberChoices = savedRemember }
    var rows: [(id: String, pinyin: Bool, items: [GoldenItem])] = []
    var left = 0
    for line in inputText.split(separator: "\n") where !line.hasPrefix("#") {
        let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
        guard fields.count == 3, fields[0] == "pinyin" || fields[0] == "zhuyin" else {
            h.check(false, "候選基準：client-inputs.txt 格式錯誤：\(line)")
            return
        }
        let isPinyin = fields[0] == "pinyin"
        let keys: [UInt8] = isPinyin ? Array(fields[2].utf8) : fields[2].compactMap { keyForSymbol[$0] }
        let backend = isPinyin ? pinyin : zhuyin
        backend.clear(); backend.setSimplified(fields[1] == "s")
        for key in keys { _ = backend.key(Int32(key), modifiers: 0) }
        let state = backend.snapshot()
        if state.input != String(decoding: keys, as: UTF8.self) { left += 1 }
        let japanese = Set(state.japaneseCandidates)
        rows.append((String(line), isPinyin, backend.candidates(limit: 4096).map {
            GoldenItem(source: $0.source ?? 99, japanese: japanese.contains($0.text), text: $0.text)
        }))
    }
    pinyin.clear(); zhuyin.clear()
    let counts = "拼音 \(rows.filter(\.pinyin).count) 項，注音 \(rows.filter { !$0.pinyin }.count) 項"
    if write {
        var text = "# Candidate lists of the macOS backend. Written by --smoke with MEOWPUUT_GOLDEN_WRITE=1.\n"
        text += "# mimo_base.bin sha256 \(digest)\n"
        text += "# mode, script, keys, then every candidate as source[j]:text (j: flagged Japanese)\n"
        for row in rows { text += ([row.id] + row.items.map(\.token)).joined(separator: "\t") + "\n" }
        let written = (try? text.write(to: goldenURL, atomically: true, encoding: .utf8)) != nil
        h.check(written, "候選基準已寫入 \(goldenURL.path)：\(counts)；\(left) 項輸入未留在組字中")
    }
    guard let checkMode else { return }
    guard let goldenText = try? String(contentsOf: goldenURL, encoding: .utf8) else {
        h.check(false, "候選基準：讀不到 \(goldenURL.path)")
        return
    }
    var golden: [String: [GoldenItem]] = [:]
    var goldenDigest = ""
    for line in goldenText.split(separator: "\n") {
        if line.hasPrefix("# mimo_base.bin sha256 ") { goldenDigest = String(line.dropFirst(23)) }
        if line.hasPrefix("#") { continue }
        let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
        guard fields.count >= 3 else { continue }
        golden[fields[0...2].joined(separator: "\t")] = fields.dropFirst(3).compactMap { GoldenItem(token: $0) }
    }
    let strict = checkMode == "strict"
    var failures: [String] = []
    var identical = 0
    for row in rows {
        guard let expected = golden[row.id] else { failures.append("\(row.id): 基準中沒有這一項"); continue }
        if expected == row.items { identical += 1; continue }
        if let reason = goldenDifference(pinyin: row.pinyin, strict: strict, golden: expected, now: row.items) {
            failures.append("\(row.id): \(reason)\n    - \(expected.map(\.token).joined(separator: " "))\n    + \(row.items.map(\.token).joined(separator: " "))")
        }
    }
    if goldenDigest != digest {
        print("候選基準：詞庫已變更（基準 mimo_base.bin \(goldenDigest)，現在 \(digest)）；差異可能來自詞庫")
    }
    for failure in failures.prefix(10) { print("候選基準差異 " + failure) }
    h.check(failures.isEmpty, "候選基準\(strict ? "（完全相同）" : "")：\(counts)；完全相同 \(identical) 項，不符 \(failures.count) 項")
}

// Romaji readings of 200 heavy words of mimo_jp.bin that are not keys of native-japanese.json.
private let derivedReadings = """
        suru aru naru dekiru kore kono nai sore sono baai kansuru iu ii mou iru kochira koko okonau jibun
        hitsuyou ooi okeru tsukau hito riyou jouhou motsu taisuru kakunin nan yoi kangaeru kotonaru kanou
        kanjiru soshite yoku houhou mae shoukai tokuni konkai shouhin sarani nin yori totemo fukumu deru dou
        onaji watakushi saabisu mieru naiyou kounyuu taiou ika korera mondai tsukaeru ore kiji erabu settei
        teikyou kakaru deeta hyouji kigyou joutai wakaru kanri ukeru mada jigyou dare kouka osusume kankei
        sonna sugu yaru basho kankyou zehi shikkari itadakeru kekka geemu shikashi katsudou tanoshimeru
        genzai kanousei hairu chiiki henkou tanoshimu sanka juuyou ichiban ooku motto ataeru saito keiken
        soudan okyakusama jissai demo josei seikatsu mataha hyouka kenkyuu joukyou dezain sentaku saigo
        hanashi hajimete gijutsu bubun pointo subete hanbai setsumei kawaru dono imi komento tsugi jidai
        ibento dasu kaihatsu sadameru oyobi shakai tsuyoi dore kaisai touroku chuui gazou ookina sonzai aite
        kojin mezasu mokuteki kojinjouhou shien soko rikai dochira korekara jishin kantan kihon sukunai
        warui shisutemu hoka sakusei shimesu konna zen kouji sagasu tsuika ieru tsuduku saisho hataraku
        itadaku shousai eikyou katsuyou kikan tsutaeru kodomo tokuchou riyuu ippan taiken anzen mazu
        shitsumon saizu jisshi motomeru toukou zentai manabu taisetsu ninki naze eru
    """.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init)

// mimo_jp.bin on macOS (design T7, T9, T10; xuexiao follows the owner decision of 2026-11-11).
private func runJapaneseLexiconSmoke(_ h: inout SmokeHarness) {
    let savedSimplified = Preferences.shared.simplified, savedRemember = Preferences.shared.rememberChoices
    Preferences.shared.simplified = false; Preferences.shared.rememberChoices = false
    defer { Preferences.shared.simplified = savedSimplified; Preferences.shared.rememberChoices = savedRemember }
    // T7: knownWord gives the first entry of native-japanese.json, and nothing for other words.
    if let url = Bundle.main.url(forResource: "native-japanese", withExtension: "json"),
       let data = try? Data(contentsOf: url),
       let legacy = try? JSONSerialization.jsonObject(with: data) as? [String: [[String: Any]]] {
        let same = legacy.allSatisfy { reading, entries in
            guard let first = entries.first, let known = JapaneseBackend.knownWord(reading) else { return false }
            return known.text == first["text"] as? String && known.weight == first["weight"] as? Int
        }
        let unknown = derivedReadings.filter { legacy[$0] == nil }
        h.check(same && unknown.count == 200 && unknown.allSatisfy { JapaneseBackend.knownWord($0) == nil },
                "日語詞表：\(legacy.count) 個已審讀音照舊，\(unknown.count) 個大詞表讀音不算已審")
        h.check(JapaneseBackend.mixedWords.count <= 512 && JapaneseBackend.mixedWords.allSatisfy { legacy[$0.reading] != nil }, "混打詞池只含已審詞")
    } else {
        h.check(false, "日語詞表：讀不到 native-japanese.json")
    }
    guard let pinyin = try? Engine.makeBackend(mode: .pinyin), let japaneseMode = try? Engine.makeBackend(mode: .japanese) else {
        h.check(false, "日語詞表：引擎無法載入"); return
    }
    func run(_ backend: InputBackend, _ input: String, simplified: Bool = false) -> (list: [IndexedCandidate], state: Snapshot) {
        backend.clear(); backend.setSimplified(simplified)
        for key in input.utf8 { _ = backend.key(Int32(key), modifiers: 0) }
        return (backend.candidates(limit: 4096), backend.snapshot())
    }
    func derived(_ input: String) -> [String] {
        JapaneseLexicon.words(input, mode: JapaneseLexicon.pinyinMode, limit: 5)
            .filter { $0.flags & JapaneseLexicon.reviewedFlag == 0 }.map(\.text)
    }
    // Index of the first Japanese item must be past every Chinese one (engine, literal, additions).
    func afterChinese(_ list: [IndexedCandidate], _ text: String) -> Bool {
        guard let index = list.firstIndex(where: { $0.text == text }) else { return false }
        return !list.enumerated().contains { $0.offset > index && [0, -2, -3].contains($0.element.source ?? 99) }
    }
    // T9, pinyin mode.
    for input in ["shi", "kan", "tai", "men", "gon", "sho"] {
        h.check(derived(input).isEmpty, "拼音不出大詞表日語詞 " + input)
    }
    for input in ["tamen", "taiwan"] {
        _ = run(pinyin, input)
        h.check(pinyin.compactJapanese().map { derived(input).contains($0.text) } != true, "收合列不出大詞表日語詞 " + input)
    }
    for (input, word) in [("kawaii", "可愛い"), ("taberu", "食べる"), ("oishii", "美味しい"), ("arigatou", "ありがとう"),
                          ("sanpo", "散歩"), ("kaisha", "会社"), ("sanbu", "散歩"), ("shiberu", "食べる"),
                          ("yinyue", "音楽"), ("zhuoru", "着る")] {
        let (list, state) = run(pinyin, input)
        h.check(list.contains { $0.text == word } && state.japaneseCandidates.contains(word), "拼音可打日語詞 \(input) \(word)")
    }
    do {
        let (list, state) = run(pinyin, "shiji")
        h.check(state.japaneseCandidates.contains("指示") && afterChinese(list, "指示") && pinyin.compactJapanese()?.text == "指示",
                "shiji：指示在所有中文候選之後並進收合列")
        let kaishi = run(pinyin, "kaishi")
        h.check(!kaishi.state.japaneseCandidates.contains("開始"), "kaishi：開始不標日語")
        // 学校 for xuexiao is a reviewed alias and keeps today's place; in Simplified it is the Chinese word.
        let traditional = run(pinyin, "xuexiao")
        h.check(traditional.list.first?.text == "學校" && traditional.state.japaneseCandidates.contains("学校"), "xuexiao 繁體：學校第一，学校照舊")
        let simplified = run(pinyin, "xuexiao", simplified: true)
        h.check(simplified.list.filter { $0.text == "学校" }.count == 1 && !simplified.state.japaneseCandidates.contains("学校"),
                "xuexiao 簡體：学校只出現一次，是中文詞")
        // A pure-kanji alias of the large list on a Chinese reading: after every Chinese candidate, not in the collapsed row.
        var late = 0
        for input in ["dushu", "tushuguan", "huishe", "taiwan", "shishi"] {
            let word = JapaneseLexicon.words(input, mode: JapaneseLexicon.pinyinMode, limit: 5)
                .first { $0.flags & JapaneseLexicon.reviewedFlag == 0 && $0.kind == JapaneseLexicon.kanjiAliasKind }
            guard let word else { continue }
            let (list, state) = run(pinyin, input)
            let simplifiedList = run(pinyin, input, simplified: true).list
            let shown = list.contains { $0.text == word.text }
            if shown { late += 1 }
            h.check((!shown || (afterChinese(list, word.text) && state.japaneseCandidates.contains(word.text))) &&
                    pinyin.compactJapanese()?.text != word.text && simplifiedList.filter { $0.text == word.text }.count <= 1,
                    "\(input)：\(word.text) 在中文之後，不進收合列")
        }
        h.check(late >= 2, "拼音打日語漢字詞（中文同音時排在最後）：\(late) 例")
        let sushi = run(pinyin, "sushi")
        h.check(sushi.list.first?.text == "蘇軾" && sushi.state.japaneseCandidates.contains("寿司") && pinyin.compactJapanese()?.text == "寿司",
                "sushi：蘇軾第一，寿司在收合列")
        let emoji = run(pinyin, "kaixin")
        h.check(emoji.list.contains { $0.text == "😊" } && emoji.list.first?.text == "開心", "內建 emoji 照常")
        let english = run(pinyin, "hello")
        h.check(english.list.first?.text == "hello", "英文建議照常")
    }
    // T10, Japanese mode.
    let sensei = run(japaneseMode, "sensei")
    h.check(sensei.list.first?.text == "先生" && JapaneseLexicon.words("sensei", mode: JapaneseLexicon.japaneseMode, limit: 64).count >= 3,
            "日語模式 sensei：先生第一，至少三個詞")
    for (input, word) in [("sinbun", "新聞"), ("shinbun", "新聞"), ("sinnbun", "新聞"), ("ko-hi-", "コーヒー"), ("koohii", "コーヒー")] {
        h.check(run(japaneseMode, input).list.contains { $0.text == word }, "日語模式 \(input) \(word)")
    }
    for (input, word) in [("ra-men", "ラーメン"), ("ge-mu", "ゲーム"), ("toukyou", "東京"), ("tokyo", "東京")] {
        let list = run(japaneseMode, input).list
        let first = JapaneseLexicon.words(input, mode: JapaneseLexicon.japaneseMode, limit: 64).first
        h.check(list.first?.text == word && list.filter { $0.text == word }.count == 1 && first.map { $0.flags & JapaneseLexicon.reviewedFlag != 0 } == true,
                "日語模式 \(input)：\(word) 第一且只一次")
    }
    let shi = run(japaneseMode, "shi")
    h.check(JapaneseLexicon.words("shi", mode: JapaneseLexicon.japaneseMode, limit: 64).count >= 5 && shi.state.japaneseCandidates.isEmpty &&
            collapsedCandidateIndices(shi.state).count > 1, "日語模式 shi：五個以上的詞，收合列不只一個")
    pinyin.clear(); japaneseMode.clear()
}
