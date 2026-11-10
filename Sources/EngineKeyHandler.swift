import Foundation
#if canImport(AppKit)
import AppKit
#endif

extension Engine {
    func openModules() {
        guard isReady, Preferences.shared.modulesEnabled else { return }
        if moduleBuffer != nil { clear(); return }
        finishComposition()
        let committed = takeCommit()
        moduleEnglishMode = english
        english = false
        moduleBuffer = "/"
        moduleSnapshot()
        snapshot.commit = committed
    }

    @discardableResult func key(_ code: Int32, modifiers: Int32 = 0) -> Bool {
        guard let backend else { return false }
        if code == 0xff1b { learningTail = nil }
        if code == Self.modulesKey { openModules(); return true }
        if code == Self.toggleEnglishKey || (inputMode != .pinyin && code == 32 && modifiers == 1) {
            let next = !english
            finishComposition()
            english = next
            if next && !predictions.isEmpty { predictions = [] }
            return true
        }
        if english {
            if !predictions.isEmpty { predictions = [] }
            if (32...126).contains(code), let scalar = UnicodeScalar(UInt32(code)) {
                var next = Snapshot()
                next.commit = String(scalar)
                snapshot = next
                return true
            }
            if snapshot != Snapshot() { snapshot = Snapshot() }
            return false
        }
        if code == Self.literalSlashKey {
            finishComposition()
            let committed = takeCommit()
            clear(); learningTail = nil
            snapshot.commit = committed + "/"
            return true
        }
        if inputMode == .pinyin, modifiers == 1, moduleBuffer == nil,
           !snapshot.input.isEmpty, let tone = [Int32(32): Int32(49), 94: 50, 35: 51, 36: 52, 38: 53][code] {
            let handled = backend.key(tone, modifiers: 0)
            refresh()
            return handled
        }
        if inputMode == .pinyin, code == 32, modifiers == 1 { return false }
        if code == 96, modifiers == 0, inputMode != .japanese {
            if moduleBuffer == "`" { clear(); return true }
            finishComposition()
            let committed = takeCommit()
            moduleBuffer = "`"
            moduleSnapshot()
            snapshot.commit = committed
            return true
        }
        let zhuyinPunctuation: [Int32: String] = [60: "，", 62: "。", 63: "？", 58: "：", 33: "！", 40: "（", 41: "）", 91: "「", 93: "」", 92: "、", 123: "『", 125: "』"]
        let pinyinPunctuation: [Int32: String] = [44: "，", 46: "。", 59: "；", 47: "？", 45: "-", 58: "：", 33: "！", 40: "（", 41: "）", 91: "「", 93: "」", 92: "、", 34: "“", 123: "『", 125: "』"]
        var mark: String?
        if inputMode == .pinyin {
            if modifiers == 1 {
                let unshifted: [Int32: String] = [60: ",", 62: ".", 63: "?", 58: ":", 34: "'", 123: "[", 125: "]", 124: "\\"]
                mark = unshifted[code]
                if mark == nil, let scalar = UnicodeScalar(UInt32(bitPattern: code)), !Character(scalar).isLetter && (33...126).contains(code) { mark = String(scalar) }
            } else {
                mark = code == 39 && snapshot.input.isEmpty ? "‘" : pinyinPunctuation[code]
            }
        } else if inputMode == .zhuyin { mark = zhuyinPunctuation[code] }
        if moduleBuffer == nil, let mark,
           !(inputMode == .zhuyin && code == 33 && modifiers == 1 && !snapshot.candidates.isEmpty) {
            finishComposition()
            let committed = takeCommit()
            clear(); learningTail = nil
            snapshot.commit = committed + mark
            return true
        }
        if inputMode == .japanese, code == 0xffc3 || code == 0xffc4, !snapshot.preedit.isEmpty {
            let reading = snapshot.preedit.replacingOccurrences(of: " ", with: "")
            let text = reading.applyingTransform(.hiraganaToKatakana, reverse: code == 0xffc3) ?? reading
            clear(); snapshot.commit = text
            return true
        }
        if browsing && code == 0xff1b { refresh(); return true }
        if code == 0xff0d && modifiers == 1, moduleBuffer != nil || !snapshot.input.isEmpty {
            let literal = moduleBuffer ?? snapshot.input
            clear(); snapshot.commit = literal
            return true
        }
        if inputMode == .pinyin, moduleBuffer == nil, modifiers == 0, (48...57).contains(code) {
            if (49...55).contains(code), snapshot.candidates.indices.contains(Int(code - 49)) {
                choose(Int(code - 49))
            } else {
                if !snapshot.input.isEmpty { finishComposition() }
                let committed = takeCommit()
                clear()
                snapshot.commit = committed + String(UnicodeScalar(UInt32(code))!)
            }
            return true
        }
        if moduleBuffer == nil, !snapshot.candidates.isEmpty {
            if inputMode == .pinyin, code == 32, modifiers == 0 {
                choose(snapshot.highlighted)
                return true
            }
            if inputMode == .zhuyin, backendKind == .meowpuut, code == 32, modifiers == 0,
               let last = snapshot.input.last, " 6347".contains(last) {
                finishComposition()
                return true
            }
            if code == 0xff52 || code == 0xff54 || code == 0xff09 {
                let count = snapshot.candidates.count
                snapshot.highlighted = (snapshot.highlighted + (code == 0xff52 || (code == 0xff09 && modifiers == 1) ? count - 1 : 1)) % count
                return true
            }
            if code == 0xff0d {
                choose(snapshot.highlighted)
                if snapshot.commit.isEmpty { backend.commit(); refresh() }
                return true
            }
            if inputMode != .pinyin, modifiers == 1, let scalar = UnicodeScalar(UInt32(code)), let index = Array("!@#$%^&*(").firstIndex(of: Character(scalar)) { choose(index); return true }
        }
        if browsing, code == 0xff55 || code == 0xff56 { showBrowsePage(snapshot.page + (code == 0xff55 ? -1 : 1)); return true }
        backend.setSimplified(Preferences.shared.simplified)
        if var buffer = moduleBuffer {
            if buffer == "`", (49...55).contains(code), modifiers == 0 {
                choose(Int(code - 49)); return true
            }
            if buffer == "`", (33...126).contains(code), modifiers == 0 {
                clear(); return key(code, modifiers: modifiers)
            }
            if code == 0xff55 || code == 0xff56 { moduleSnapshot(page: snapshot.page + (code == 0xff55 ? -1 : 1)); return true }
            if inputMode != .pinyin, modifiers == 1, let scalar = UnicodeScalar(UInt32(code)), let index = Array("!@#$%^&*(").firstIndex(of: Character(scalar)) { choose(index); return true }
            if code == 0xff52 || code == 0xff54 || code == 0xff09 {
                let count = snapshot.candidates.count
                if count > 0 { snapshot.highlighted = (snapshot.highlighted + (code == 0xff52 || (code == 0xff09 && modifiers == 1) ? count - 1 : 1)) % count }
                return true
            }
            if code == 0xff1b { clear(); return true }
            if code == 0xff08 { buffer = String(buffer.dropLast()) }
            else if code == 0xff0d || code == 32 {
                if snapshot.candidates.isEmpty { finishComposition(); if code == 32 { snapshot.commit += " " } } else { choose(snapshot.highlighted) }
                return true
            }
            else if (33...126).contains(code), let scalar = UnicodeScalar(UInt32(code)) { buffer.append(Character(scalar)) }
            else { return true }
            moduleBuffer = buffer.isEmpty ? nil : buffer
            moduleSnapshot(); return true
        }
        let handled = backend.key(code, modifiers: modifiers)
        refresh()
        return handled
    }

    func moduleSnapshot(page requestedPage: Int = 0) {
        guard let buffer = moduleBuffer else { snapshot = Snapshot(); return }
        let matches = Preferences.shared.modules.filter { buffer == $0.trigger || $0.trigger.hasPrefix(buffer) }
        let phrases = buffer == "`" ? ["，", "。", "？", "！", "、", "；", "：", "「", "」", "『", "』", "（", "）", "……", "——", "《", "》", "〈", "〉", "·", "～", "/", "\\", "-", "_", "?", ";", ":"] : matches.flatMap(\.phrases)
        let page = max(0, min(requestedPage, max(0, (phrases.count - 1) / 7)))
        snapshot = Snapshot(preedit: buffer == "`" ? "標點" : buffer, candidates: Array(phrases.dropFirst(page * 7).prefix(7)), page: page, lastPage: (page + 1) * 7 >= phrases.count, input: buffer)
    }

    func choose(_ index: Int) {
        guard snapshot.candidates.indices.contains(index) else { return }
        if moduleBuffer != nil {
            let text = snapshot.candidates[index]; clear(); snapshot.commit = text
        } else {
            chooseBackendCandidate(index: candidateOrder[index], text: snapshot.candidates[index])
        }
    }

    func chooseCompactJapanese() {
        guard let candidate = snapshot.compactJapanese else { return }
        chooseBackendCandidate(index: candidate.index, text: candidate.text)
    }

    func choosePrediction(_ index: Int) -> String? {
        guard predictions.indices.contains(index) else { return nil }
        let chosen = predictions[index]
        let nextContext = predictionContext + chosen
        updatePredictions(context: nextContext)
        return chosen
    }

    func chooseBackendCandidate(index: Int, text: String) {
        let reading = pinKey
        guard backend?.choose(index) == true else { error = "選詞未成功，請重試。"; refresh(); return }
        if !pendingChoices.contains(where: { $0.reading == reading && $0.text == text }) { pendingChoices.append((reading, text)) }
        refresh()
        if snapshot.commit.isEmpty && snapshot.candidates.isEmpty && !snapshot.preedit.isEmpty {
            backend?.commit(); refresh()
        }
    }
}

#if canImport(AppKit)
struct ShiftTapTracker {
    private var pressed: UInt16?
    private var previousShift = false
    mutating func reset() { pressed = nil; previousShift = false }
    mutating func update(keyCode: UInt16, modifierEvent: Bool, shift: Bool, other: Bool) -> Bool {
        let wasShift = previousShift
        previousShift = shift
        guard modifierEvent, !other else { pressed = nil; return false }
        let effectiveKey: UInt16
        if keyCode == 56 || keyCode == 60 { effectiveKey = keyCode }
        else if keyCode == 0, wasShift != shift { effectiveKey = pressed ?? 56 }
        else { pressed = nil; return false }
        if shift {
            if !wasShift { pressed = effectiveKey }
            else if pressed != effectiveKey { pressed = nil }
            return false
        }
        let toggle = wasShift && pressed == effectiveKey
        pressed = nil
        return toggle
    }
}

func inputKey(_ event: NSEvent, mode: InputMode = .zhuyin, english: Bool = false, composing: Bool = false) -> (Int32, Int32)? {
    if event.keyCode == 49, event.modifierFlags.intersection([.command, .control, .option]) == [.option] { return (Engine.modulesKey, 0) }
    if event.modifierFlags.intersection([.command, .control, .option, .shift]) == [.option],
       (mode == .zhuyin && !english && event.keyCode == 44) || event.charactersIgnoringModifiers == "/" {
        return (Engine.literalSlashKey, 0)
    }
    if !event.modifierFlags.intersection([.command, .control, .option]).isEmpty { return nil }
    let special: [UInt16: Int32] = [36:0xff0d, 76:0xff0d, 48:0xff09, 51:0xff08, 53:0xff1b, 123:0xff51, 124:0xff53, 125:0xff54, 126:0xff52, 116:0xff55, 121:0xff56, 97:0xffc3, 98:0xffc4]
    if let key = special[event.keyCode] { return (key, event.modifierFlags.contains(.shift) ? 1 : 0) }
    let usKeys: [UInt16:String] = [0:"a",1:"s",2:"d",3:"f",4:"h",5:"g",6:"z",7:"x",8:"c",9:"v",11:"b",12:"q",13:"w",14:"e",15:"r",16:"y",17:"t",18:"1",19:"2",20:"3",21:"4",22:"6",23:"5",24:"=",25:"9",26:"7",27:"-",28:"8",29:"0",30:"]",31:"o",32:"u",33:"[",34:"i",35:"p",37:"l",38:"j",39:"'",40:"k",41:";",42:"\\",43:",",44:"/",45:"n",46:"m",47:".",49:" ",50:"`"]
    let shifted = ["1":"!", "2":"@", "3":"#", "4":"$", "5":"%", "6":"^", "7":"&", "8":"*", "9":"(", "0":")", "-":"_", "=":"+", "[":"{", "]":"}", ";":":", "'":"\"", ",":"<", ".":">", "/":"?", "\\":"|", "`":"~"]
    let physicalTone = mode == .pinyin && !english && composing && event.modifierFlags.contains(.shift) && [UInt16(49), 22, 20, 21, 26].contains(event.keyCode)
    if english || (mode != .zhuyin && !physicalTone) {
        guard let characters = event.characters, characters.unicodeScalars.count == 1,
              let scalar = characters.unicodeScalars.first, (32...126).contains(scalar.value) else { return nil }
        return (Int32(scalar.value), event.modifierFlags.contains(.shift) ? 1 : 0)
    }
    let character = usKeys[event.keyCode].map { key -> String in
        let shift = event.modifierFlags.contains(.shift)
        if key.uppercased() != key.lowercased() { return shift != event.modifierFlags.contains(.capsLock) ? key.uppercased() : key }
        return shift ? shifted[key] ?? key : key
    }
    let code = (character ?? event.characters)?.unicodeScalars.first.map { Int32($0.value) }
    return code.map { ($0, event.modifierFlags.contains(.shift) ? 1 : 0) }
}
#endif

func readingLabel(_ key: String) -> String {
    if key.hasPrefix("mix:") { return "自動 · " + key.dropFirst(4) }
    if key.hasPrefix("ja:") { return "日本語 · " + key.dropFirst(3) }
    if key.hasPrefix("py:") { return "拼音 · " + key.dropFirst(3) }
    let keys = Array("1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'")
    let symbols = Array("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙ ")
    let mapping = Dictionary(uniqueKeysWithValues: zip(keys, symbols))
    return (key.hasPrefix("s:") ? "简 · " : "繁 · ") + String(key.dropFirst(2).map { mapping[$0] ?? $0 })
}
