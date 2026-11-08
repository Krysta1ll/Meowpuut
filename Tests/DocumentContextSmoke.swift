import Foundation

func runDocumentContextSmoke(_ check: (Bool, String) -> Void) {
    let oldScript = Preferences.shared.simplified
    let oldLearning = Preferences.shared.rememberChoices
    defer {
        Preferences.shared.simplified = oldScript
        Preferences.shared.rememberChoices = oldLearning
    }
    Preferences.shared.rememberChoices = false
    let memoryRevision = ChoiceMemory.shared.revision
    for (input, expected) in [(nil, ""), ("", ""), ("甲乙丙丁戊", "乙丙丁戊"),
                              ("前句。環", "環"), ("環 ", ""), ("環\n", ""),
                              ("環abc", ""), ("環🐈", ""), ("🐈頭", "頭"),
                              ("𠮷野家", "𠮷野家")] as [(String?, String)] {
        check(CompositionContext.precedingChinese(input) == expected, "游標語境只保留相鄰漢字：\(input ?? "nil")")
    }
    for selection in [NSRange(location: NSNotFound, length: 0), NSRange(location: 0, length: 0), NSRange(location: 5, length: 2)] {
        check(CompositionContext.precedingRange(selection: selection) == nil, "無效位置、開頭或選區不查詢語境")
    }
    check(CompositionContext.precedingRange(selection: NSRange(location: 100, length: 0)) == NSRange(location: 92, length: 8), "宿主查詢限制八個 UTF-16 單位")
    check(CompositionContext.precedingRange(selection: NSRange(location: 3, length: 0)) == NSRange(location: 0, length: 3), "短前文不越過文件起點")
    var changedRankings = 0
    for mode in [InputMode.pinyin, .zhuyin] {
        for simplified in [false, true] {
            Preferences.shared.simplified = simplified
            for (left, pinyin, zhuyin) in [(simplified ? "环" : "環", "jing", "ru/4"),
                                            (simplified ? "头" : "頭", "fa", "z"),
                                            (simplified ? "银" : "銀", "hang", "c;6")] {
                let raw = mode == .pinyin ? pinyin : zhuyin
                let engine = Engine(mode: mode)
                guard let reference = try? NativeBackend(mode: mode) else {
                    check(false, "語境參照核心載入"); continue
                }
                reference.setSimplified(simplified)
                reference.beginContext(left)
                for byte in raw.utf8 { _ = reference.key(Int32(byte), modifiers: 0); _ = engine.key(Int32(byte)) }
                let baseline = engine.snapshot.candidates
                engine.clear()
                engine.prepareDocumentContext(before: left)
                for byte in raw.utf8 { _ = engine.key(Int32(byte)) }
                let expected = reference.snapshot().candidates.first
                check(expected != nil && engine.snapshot.candidates.first == expected,
                      "\(mode.rawValue) \(simplified ? "简" : "繁") 游標語境傳入候選排序：\(left)/\(raw)")
                if engine.snapshot.candidates.first != baseline.first { changedRankings += 1 }
                let active = engine.snapshot.candidates
                check(!engine.needsDocumentContext(for: 97), "組字期間不再次向宿主讀取語境")
                engine.prepareDocumentContext(before: "無關文字")
                engine.refresh()
                check(engine.snapshot.candidates == active, "組字途中不把自己的標記或其他前文重新當語境")
                _ = engine.key(0xff08)
                // Removing the only key ends the composition. A real host
                // supplies its context again when the next composition starts.
                if engine.needsDocumentContext(for: Int32(raw.utf8.last!)) {
                    engine.prepareDocumentContext(before: left)
                }
                _ = engine.key(Int32(raw.utf8.last!))
                check(engine.snapshot.candidates == active, "刪改讀音後重建保留同一段語境")
                engine.choose(0)
                check(engine.takeCommit() == expected && engine.snapshot.preedit.isEmpty, "語境排序不改變候選選取及讀音消耗")
                engine.clear()
                engine.prepareDocumentContext(before: nil)
                for byte in raw.utf8 { _ = engine.key(Int32(byte)) }
                check(engine.snapshot.candidates == baseline, "無可用前文時回到原排序，不沿用其他欄位語境")
                engine.clear()
                check(!engine.needsDocumentContext(for: 0xff08) && !engine.needsDocumentContext(for: 32), "空閒刪除和空格不觸發宿主語境查詢")
            }
        }
    }
    check(changedRankings > 0, "游標語境實際改變候選順位")
    check(ChoiceMemory.shared.revision == memoryRevision, "宿主前文不寫入個人詞庫")
    runLearningOrderSmoke(check)
    runModeDeliverySmoke(check)
}

private func runModeDeliverySmoke(_ check: (Bool, String) -> Void) {
    Preferences.shared.rememberChoices = false
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        for (mode, reading) in [(InputMode.pinyin, "nihaox"), (.zhuyin, "su3cl3v")] {
            let engine = Engine(mode: mode)
            for byte in reading.utf8 { engine.key(Int32(byte)) }
            engine.finishComposition()
            let completed = engine.snapshot.commit
            check(!completed.isEmpty && engine.snapshot.preedit.isEmpty, "切換前完整保留前段及未轉換尾音")
            let next: InputMode = mode == .pinyin ? .zhuyin : .pinyin
            engine.selectMode(next)
            check(engine.inputMode == next && engine.snapshot.commit == completed,
                  "切換輸入方式不丟失等待送出的文字：\(mode)/\(simplified)")
            engine.selectMode(.japanese); engine.selectMode(next)
            check(engine.takeCommit() == completed && engine.takeCommit().isEmpty,
                  "連續切換模式後已完成文字只送出一次")
            let nextReading = next == .pinyin ? "nihao" : "su3cl3"
            for byte in nextReading.utf8 { engine.key(Int32(byte)) }
            engine.finishComposition()
            check(engine.takeCommit() == "你好", "新模式可繼續組字，不夾帶上一段文字")
        }
    }
}

private func runLearningOrderSmoke(_ check: (Bool, String) -> Void) {
    Preferences.shared.rememberChoices = true
    for mode in [InputMode.pinyin, .zhuyin] {
        for simplified in [false, true] {
            Preferences.shared.simplified = simplified
            for (left, pinyin, zhuyin) in [(simplified ? "环" : "環", "jing", "ru/4"),
                                            (simplified ? "头" : "頭", "fa", "z"),
                                            (simplified ? "银" : "銀", "hang", "c;6")] {
                let raw = mode == .pinyin ? pinyin : zhuyin
                let session = Engine(mode: mode)
                let key = (mode == .pinyin ? "py:" : "") + (simplified ? "s:" : "t:") + raw
                ChoiceMemory.shared.forget(key)
                session.prepareDocumentContext(before: left)
                for byte in raw.utf8 { session.key(Int32(byte)) }
                let baseline = session.snapshot.candidates
                guard let first = baseline.first else { check(false, "記憶排序參照候選存在"); continue }
                // A stale dictionary entry can remain in saved history after
                // a dictionary update. It must not reshuffle other candidates.
                ChoiceMemory.shared.remember([(key, "詞庫已不再收錄的舊候選")])
                session.refresh()
                check(session.snapshot.candidates == baseline, "不可選的舊記憶不重排語境候選：\(mode)/\(simplified)/\(raw)")
                ChoiceMemory.shared.forget(key)
                // Reinforcing the already-leading choice must not promote
                // unrelated high-frequency homophones above it.
                ChoiceMemory.shared.remember([(key, first)])
                session.refresh()
                check(session.snapshot.candidates == baseline, "記住原首選不打亂其餘語境次序：\(mode)/\(simplified)/\(raw)")
                let displayed = session.snapshot.candidates
                session.refresh(); session.refresh()
                check(session.snapshot.candidates == displayed, "重複刷新不使學習候選漂移")
                session.choose(0)
                check(session.takeCommit() == first, "學習排序後候選索引仍提交畫面文字")
                ChoiceMemory.shared.forget(key)
            }
        }
    }
}
