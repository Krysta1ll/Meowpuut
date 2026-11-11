import CoreGraphics

// What each key is and does. Layout lives in KeyboardGeometry, drawing and
// touches in KeyboardKeysView; the controller turns actions into input.

struct KeyboardKey: Equatable {
    enum Action: Equatable {
        case engine(Int32)      // a raw ASCII key for the engine (letters, Zhuyin)
        case text(String)       // inserted as is (symbol pages)
        case shift, delete, space, returnKey, globe, toggleEnglish
        case dismiss            // iPad: hides the keyboard
        case page(Int)          // 0 letters, 1 symbols, 2 more symbols
    }
    enum Style: Equatable { case character, utility, accent }

    var action: Action
    var label: String
    var icon: String? = nil
    var style: Style = .character
    /// Long-press options. The first one is the key itself.
    var alternates: [String] = []
    var preview = true
    var enabled = true
    var fontSize: CGFloat? = nil
    var accessibilityLabel: String
    /// Spoken after the label when the key has a state (Shift).
    var accessibilityValue: String? = nil

    var isCharacter: Bool {
        switch action { case .engine, .text: return true; default: return false }
    }
    /// Keys typed when the finger lifts; a second finger landing commits them early.
    var commitsOnRelease: Bool {
        switch action { case .engine, .text, .space, .returnKey, .toggleEnglish: return true; default: return false }
    }
}

struct KeyboardKeySet: Equatable {
    enum Kind: Equatable { case letters, zhuyin, symbols(Int) }
    var kind: Kind
    var rows: [[KeyboardKey]]
    var specs: [KeyRowSpec]
    var referenceColumns: Int

    var rowCount: Int { rows.count }
}

/// Everything that changes which keys are shown or how they are labelled.
struct KeyboardKeyContext: Equatable {
    enum Shift: Equatable { case off, once, locked }
    var mode: InputMode
    var english: Bool
    var page: Int
    var shift: Shift
    var simplified: Bool
    var needsGlobe: Bool
    var spaceTitle: String
    var returnTitle: String
    var returnAccent: Bool
    var returnEnabled: Bool
    /// Full-width iPad keyboard: a key to hide it, as Apple's has.
    var pad = false
}

enum KeyboardKeyBuilder {
    static let zhuyin: [[(String, String)]] = [
        [("ㄅ","1"),("ㄉ","2"),("ˇ","3"),("ˋ","4"),("ㄓ","5"),("ˊ","6"),("˙","7"),("ㄚ","8"),("ㄞ","9"),("ㄢ","0"),("ㄦ","-")],
        [("ㄆ","q"),("ㄊ","w"),("ㄍ","e"),("ㄐ","r"),("ㄔ","t"),("ㄗ","y"),("ㄧ","u"),("ㄛ","i"),("ㄟ","o"),("ㄣ","p")],
        [("ㄇ","a"),("ㄋ","s"),("ㄎ","d"),("ㄑ","f"),("ㄕ","g"),("ㄘ","h"),("ㄨ","j"),("ㄜ","k"),("ㄠ","l"),("ㄤ",";")],
        [("ㄈ","z"),("ㄌ","x"),("ㄏ","c"),("ㄒ","v"),("ㄖ","b"),("ㄙ","n"),("ㄩ","m"),("ㄝ",","),("ㄡ","."),("ㄥ","/")]
    ]

    // Apple's accented letters, shown on a long press in English after the
    // letter itself.
    static let accents: [Character: [String]] = [
        "a": ["à", "á", "â", "ä", "æ", "ã", "å", "ā"], "c": ["ç", "ć", "č"],
        "e": ["è", "é", "ê", "ë", "ē", "ė", "ę"], "i": ["î", "ï", "í", "ī", "į", "ì"],
        "l": ["ł"], "n": ["ñ", "ń"], "o": ["ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"],
        "s": ["ß", "ś", "š"], "u": ["û", "ü", "ù", "ú", "ū"], "y": ["ÿ"], "z": ["ž", "ź", "ż"]
    ]

    static let symbolAlternates: [String: [String]] = [
        "-": ["-", "–", "—", "•"], "/": ["/", "\\"], "$": ["$", "¢", "£", "€", "¥", "₩"],
        "&": ["&", "§"], "\"": ["\"", "“", "”", "„", "»", "«"], ".": [".", "…"],
        "?": ["?", "¿"], "!": ["!", "¡"], "'": ["'", "‘", "’", "`"], "%": ["%", "‰"],
        "0": ["0", "°"], "=": ["=", "≠", "≈"],
        "。": ["。", "．"], "，": ["，", "、"], "“": ["“", "”", "「", "」", "『", "』"],
        "「": ["「", "『", "“", "‘"], "」": ["」", "』", "”", "’"],
        "（": ["（", "【", "〔", "《"], "）": ["）", "】", "〕", "》"], "¥": ["¥", "$", "€", "£"],
        "ー": ["ー", "-", "～"], "、": ["、", "，"]
    ]

    /// What VoiceOver says for the Zhuyin tone keys, which show only a mark.
    static func toneName(_ mark: String, _ t: InterfaceText) -> String? {
        switch mark {
        case "ˊ": return t("二聲", "二声")
        case "ˇ": return t("三聲", "三声")
        case "ˋ": return t("四聲", "四声")
        case "˙": return t("輕聲", "轻声")
        default: return nil
        }
    }

    static func build(_ context: KeyboardKeyContext) -> KeyboardKeySet {
        let t = InterfaceText(simplified: context.simplified)
        let chinese = !context.english && context.mode != .japanese
        if context.page > 0 { return symbols(context, page: context.page, chinese: chinese, t: t) }
        if !context.english && context.mode == .zhuyin { return zhuyinSet(context, t: t) }
        return letters(context, t: t)
    }

    private static func bottomRow(_ context: KeyboardKeyContext, t: InterfaceText, first: KeyboardKey, second: KeyboardKey?) -> ([KeyboardKey], KeyRowSpec) {
        var keys = [first]
        var units: [CGFloat] = [1.25]
        if let second { keys.append(second); units.append(1.25) }
        if context.needsGlobe {
            keys.append(KeyboardKey(action: .globe, label: "", icon: "globe", style: .utility, preview: false,
                                    accessibilityLabel: t("切換鍵盤", "切换键盘")))
            units.append(1.25)
        }
        // While a reading is open the key shows what space will do (一聲,
        // 選詞); VoiceOver says it after the key's name.
        let idle = context.spaceTitle == "空格" || context.spaceTitle == "space"
        keys.append(KeyboardKey(action: .space, label: context.spaceTitle, style: .character, preview: false,
                                fontSize: 16, accessibilityLabel: "空格", accessibilityValue: idle ? nil : context.spaceTitle))
        units.append(0)
        keys.append(KeyboardKey(action: .returnKey, label: context.returnTitle,
                                style: context.returnAccent ? .accent : .utility, preview: false,
                                enabled: context.returnEnabled, fontSize: 16, accessibilityLabel: context.returnTitle))
        units.append(2.25)
        if context.pad {
            keys.append(KeyboardKey(action: .dismiss, label: "", icon: "keyboard.chevron.compact.down", style: .utility,
                                    preview: false, accessibilityLabel: t("隱藏鍵盤", "隐藏键盘")))
            units.append(1.25)
        }
        return (keys, KeyRowSpec(units: units, alignment: .justified))
    }

    private static func toggleKey(_ context: KeyboardKeyContext, t: InterfaceText) -> KeyboardKey {
        let label: String
        if context.english {
            label = context.mode == .pinyin ? "拼" : context.mode == .japanese ? "あ" : "ㄅ"
        } else {
            label = "ABC"
        }
        let spoken: String
        if context.english {
            spoken = context.mode == .pinyin ? "回到拼音" : context.mode == .japanese ? "回到日本語" : "回到注音"
        } else {
            spoken = "英文"
        }
        return KeyboardKey(action: .toggleEnglish, label: label, style: .utility, preview: false, fontSize: 16,
                           accessibilityLabel: spoken)
    }

    private static func letters(_ context: KeyboardKeyContext, t: InterfaceText) -> KeyboardKeySet {
        // Shift shows capitals in every letters layout: English text, a
        // reading typed in capitals in pinyin, katakana first in Japanese.
        let upper = context.shift != .off
        func letter(_ c: Character) -> KeyboardKey {
            let text = upper ? String(c).uppercased() : String(c)
            let accented = context.english ? (accents[c] ?? []).map { upper ? $0.uppercased() : $0 } : []
            let alternates = accented.isEmpty ? [] : [text] + accented
            return KeyboardKey(action: .engine(Int32(c.asciiValue ?? 32)), label: text, alternates: alternates,
                               accessibilityLabel: text)
        }
        let rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"].map { Array($0).map(letter) }
        let shiftIcon: String = switch context.shift { case .off: "shift"; case .once: "shift.fill"; case .locked: "capslock.fill" }
        let shiftState: String = switch context.shift {
        case .off: t("關", "关")
        case .once: t("開", "开")
        case .locked: t("大寫鎖定", "大写锁定")
        }
        let shift = KeyboardKey(action: .shift, label: "", icon: shiftIcon,
                                style: context.shift == .off ? .utility : .character, preview: false,
                                accessibilityLabel: "Shift", accessibilityValue: shiftState)
        let delete = KeyboardKey(action: .delete, label: "", icon: "delete.left", style: .utility, preview: false,
                                 accessibilityLabel: t("刪除", "删除"))
        let symbolsKey = KeyboardKey(action: .page(1), label: context.english ? "123" : t("符號", "符号"), style: .utility,
                                     preview: false, fontSize: 16, accessibilityLabel: t("符號", "符号"))
        let bottom = bottomRow(context, t: t, first: symbolsKey, second: toggleKey(context, t: t))
        return KeyboardKeySet(kind: .letters,
                              rows: [rows[0], rows[1], [shift] + rows[2] + [delete], bottom.0],
                              specs: [KeyRowSpec(units: Array(repeating: 1, count: 10), alignment: .centered),
                                      KeyRowSpec(units: Array(repeating: 1, count: 9), alignment: .centered),
                                      KeyRowSpec(units: [1.32] + Array(repeating: 1, count: 7) + [1.32], alignment: .splitEdges),
                                      bottom.1],
                              referenceColumns: 10)
    }

    private static func zhuyinSet(_ context: KeyboardKeyContext, t: InterfaceText) -> KeyboardKeySet {
        var rows: [[KeyboardKey]] = zhuyin.map { row in
            row.map { symbol, raw in
                KeyboardKey(action: .engine(Int32(raw.utf8.first ?? 32)), label: symbol, fontSize: 21,
                            accessibilityLabel: toneName(symbol, t) ?? symbol)
            }
        }
        rows[3].append(KeyboardKey(action: .delete, label: "", icon: "delete.left", style: .utility, preview: false,
                                   accessibilityLabel: t("刪除", "删除")))
        let symbolsKey = KeyboardKey(action: .page(1), label: t("符號", "符号"), style: .utility, preview: false, fontSize: 16,
                                     accessibilityLabel: t("符號", "符号"))
        let bottom = bottomRow(context, t: t, first: symbolsKey, second: toggleKey(context, t: t))
        rows.append(bottom.0)
        return KeyboardKeySet(kind: .zhuyin, rows: rows,
                              specs: [KeyRowSpec(units: Array(repeating: 1, count: 11), alignment: .centered),
                                      KeyRowSpec(units: Array(repeating: 1, count: 10), alignment: .centered),
                                      KeyRowSpec(units: Array(repeating: 1, count: 10), alignment: .centered),
                                      KeyRowSpec(units: Array(repeating: 1, count: 11), alignment: .centered),
                                      bottom.1],
                              referenceColumns: 11)
    }

    /// Traditional text quotes with 「」 and prices in $; Simplified uses “” and ¥.
    /// Japanese has full-width marks and ー, as on Apple's Romaji keyboard.
    static func symbolRows(page: Int, chinese: Bool, simplified: Bool = true, japanese: Bool = false) -> [[String]] {
        if japanese {
            return page == 1
                ? [Array("1234567890").map(String.init), ["ー", "/", "：", "；", "（", "）", "¥", "@", "「", "」"], ["。", "、", "？", "！", "・", "."]]
                : [["【", "】", "｛", "｝", "#", "%", "^", "*", "+", "="], ["_", "—", "＼", "｜", "～", "『", "』", "€", "&", "♪"], ["…", "、", "^_^", "？", "！", "'"]]
        }
        if page == 1, chinese, !simplified {
            return [Array("1234567890").map(String.init), ["-", "/", "：", "；", "（", "）", "$", "@", "「", "」"], ["。", "，", "、", "？", "！", "."]]
        }
        switch (page, chinese) {
        case (1, false): return [Array("1234567890").map(String.init), ["-", "/", ":", ";", "(", ")", "$", "&", "@", "\""], [".", ",", "?", "!", "'"]]
        case (_, false): return [["[", "]", "{", "}", "#", "%", "^", "*", "+", "="], ["_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"], [".", ",", "?", "!", "'"]]
        case (1, true): return [Array("1234567890").map(String.init), ["-", "/", "：", "；", "（", "）", "¥", "@", "“", "”"], ["。", "，", "、", "？", "！", "."]]
        default: return [["【", "】", "｛", "｝", "#", "%", "^", "*", "+", "="], ["_", "—", "＼", "｜", "～", "《", "》", "€", "&", "·"], ["…", "，", "^_^", "？", "！", "'"]]
        }
    }

    private static func symbols(_ context: KeyboardKeyContext, page: Int, chinese: Bool, t: InterfaceText) -> KeyboardKeySet {
        let source = symbolRows(page: page, chinese: chinese, simplified: context.simplified,
                                japanese: !context.english && context.mode == .japanese)
        func key(_ text: String) -> KeyboardKey {
            KeyboardKey(action: .text(text), label: text, alternates: symbolAlternates[text] ?? [],
                        fontSize: text.count > 1 ? 16 : 21, accessibilityLabel: text)
        }
        let other = KeyboardKey(action: .page(page == 1 ? 2 : 1), label: page == 1 ? "#+=" : "123", style: .utility,
                                preview: false, fontSize: 16, accessibilityLabel: page == 1 ? t("更多符號", "更多符号") : t("數字", "数字"))
        let delete = KeyboardKey(action: .delete, label: "", icon: "delete.left", style: .utility, preview: false,
                                 accessibilityLabel: t("刪除", "删除"))
        let lettersLabel: String
        if context.english { lettersLabel = "ABC" }
        else { lettersLabel = context.mode == .zhuyin ? "注音" : context.mode == .japanese ? "かな" : "拼音" }
        let spokenLetters = context.english ? "字母" : context.mode == .japanese ? "假名" : lettersLabel
        let back = KeyboardKey(action: .page(0), label: lettersLabel, style: .utility, preview: false, fontSize: 16,
                               accessibilityLabel: spokenLetters)
        let bottom = bottomRow(context, t: t, first: back, second: nil)
        let third = [other] + source[2].map(key) + [delete]
        let middleUnits = (CGFloat(10) - 2 * 1.32) / CGFloat(source[2].count)
        return KeyboardKeySet(kind: .symbols(page),
                              rows: [source[0].map(key), source[1].map(key), third, bottom.0],
                              specs: [KeyRowSpec(units: Array(repeating: 1, count: 10), alignment: .centered),
                                      KeyRowSpec(units: Array(repeating: 1, count: 10), alignment: .centered),
                                      KeyRowSpec(units: [1.32] + Array(repeating: middleUnits, count: source[2].count) + [1.32], alignment: .justified),
                                      bottom.1],
                              referenceColumns: 10)
    }
}
