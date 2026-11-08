import Foundation

// Exercise real selection rather than inserting fabricated learned records.
// The target is a non-leading full native path, independent of its wording.
func runLongSentenceCandidateSmoke(_ check: (Bool, String) -> Void) {
    let oldScript = Preferences.shared.simplified
    let oldLearning = Preferences.shared.rememberChoices
    defer {
        Preferences.shared.simplified = oldScript
        Preferences.shared.rememberChoices = oldLearning
    }
    Preferences.shared.rememberChoices = true
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        for (mode, raw) in [(InputMode.pinyin, "mingtianwoyaogenpengyouyiqichifan"),
                            (.zhuyin, "rup wu0 wu0 fu4cp3cl3ji3vu;3fm4ej/ m06n041j4")] {
            let memoryKey = (mode == .pinyin ? "py:" : "") + (simplified ? "s:" : "t:") + raw
            ChoiceMemory.shared.forget(memoryKey)
            defer { ChoiceMemory.shared.forget(memoryKey) }
            func typed() -> Engine {
                let engine = Engine(mode: mode)
                for key in raw.utf8 { engine.key(Int32(key)) }
                return engine
            }
            let initial = typed()
            let originalOrder = initial.snapshot.candidates
            guard let target = initial.snapshot.candidateDetails.dropFirst().first(where: {
                $0.text.count > 8 && $0.source == 0 && $0.metadata?.kind == MPN_CANDIDATE_PATH.rawValue &&
                $0.metadata?.remainingOffsetBytes == raw.utf8.count && $0.metadata?.remainingInput == ""
            }) else {
                check(false, "長句記憶測試須有非首位完整原生路徑：\(mode)/\(simplified)")
                continue
            }
            check(initial.snapshot.candidateWeights[target.text] == nil,
                  "長句記憶覆蓋沒有整詞權重的路徑：\(mode)/\(simplified)")
            for occurrence in 1...2 {
                let engine = typed()
                guard let index = engine.snapshot.candidates.firstIndex(of: target.text) else {
                    check(false, "再次輸入仍能選到同一長句：\(mode)/\(simplified)"); continue
                }
                engine.choose(index)
                check(engine.takeCommit() == target.text && engine.snapshot.input.isEmpty,
                      "長句實際選取提交一次並完整消耗讀音：\(mode)/\(simplified)/\(occurrence)")
                check(ChoiceMemory.shared.records[memoryKey]?.first(where: { $0.text == target.text })?.uses == occurrence,
                      "實際選詞更新長句使用次數：\(mode)/\(simplified)/\(occurrence)")
            }
            let learned = typed()
            check(ChoiceMemory.shared.personalCandidates(for: memoryKey).contains(target.text) &&
                  learned.snapshot.candidates.first == target.text,
                  "兩次選擇後長句可依個人習慣前移：\(mode)/\(simplified)")
            let learnedTarget = learned.snapshot.candidateDetails.first(where: { $0.text == target.text })
            check(learnedTarget?.source == target.source && learnedTarget?.backendIndex == target.backendIndex &&
                  learnedTarget?.metadata == target.metadata,
                  "長句記憶保留完整原生候選的來源與讀音消耗：\(mode)/\(simplified)")
            if let index = learned.snapshot.candidates.firstIndex(of: target.text) {
                learned.choose(index)
                check(learned.takeCommit() == target.text && learned.snapshot.input.isEmpty && learned.takeCommit().isEmpty,
                      "提升後選詞仍準確上屏且不重複提交：\(mode)/\(simplified)")
            }
            ChoiceMemory.shared.forget(memoryKey)
            check(typed().snapshot.candidates == originalOrder,
                  "忘記長句後恢復原生排序：\(mode)/\(simplified)")
        }
    }
    runZhuyinContinuationSmoke(check)
}

private func runZhuyinContinuationSmoke(_ check: (Bool, String) -> Void) {
    Preferences.shared.rememberChoices = false
    for simplified in [false, true] {
        Preferences.shared.simplified = simplified
        for (raw, next, completion, hant, hans, continuation) in [
            ("ji3183ul4g7", "z", ";4", "我把鑰匙", "我把钥匙", "放"),
            ("5k41p3gj dk3u3ru,4", "e", "o3", "這本書可以借", "这本书可以借", simplified ? "给" : "給"),
            ("1; ji31835k4ek72u453ru4", "v", "u84", "幫我把這個地址記", "帮我把这个地址记", "下")
        ] {
            let engine = Engine(mode: .zhuyin)
            let target = simplified ? hans : hant
            for byte in raw.utf8 { engine.key(Int32(byte)) }
            check(engine.snapshot.candidates.contains(target), "下一音節前完整候選可選：\(target)")
            for byte in next.utf8 { engine.key(Int32(byte)) }
            guard let index = engine.snapshot.candidates.firstIndex(of: target) else {
                check(false, "下一聲母不丟失同音長句前段：\(target)"); continue
            }
            let metadata = engine.snapshot.candidateDetails[index].metadata
            check(metadata?.consumedBytes == raw.utf8.count && metadata?.remainingInput == next,
                  "長句前段保留準確的選取範圍：\(target)")
            engine.choose(index)
            check(engine.takeCommit() == target && engine.snapshot.input == next && engine.takeCommit().isEmpty,
                  "選長句前段後只留下新音節：\(target)")
            for byte in completion.utf8 { engine.key(Int32(byte)) }
            guard let tail = engine.snapshot.candidates.firstIndex(of: continuation) else {
                check(false, "長句選詞後尾音可繼續完成：\(target)"); continue
            }
            engine.choose(tail)
            check(engine.takeCommit() == continuation && engine.snapshot.input.isEmpty,
                  "前段和後段可分開確認完成：\(target)")
        }
    }
}
