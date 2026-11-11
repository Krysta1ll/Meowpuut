import Foundation

// A host model follows UITextDocumentProxy's documented marked-range rules.
private struct Host {
    private(set) var text = ""
    private var prefix = ""
    private var marked: String?

    mutating func apply(_ actions: [CompositionDelivery.Action]) {
        for action in actions {
            switch action {
            case .mark(let value): marked = value
            case .unmark:
                prefix += marked ?? ""
                marked = nil
            case .insert(let value):
                precondition(marked == nil)
                prefix += value
            }
        }
        text = prefix + (marked ?? "")
    }
}

@main
struct CompositionDeliveryTests {
    static func main() {
        var delivery = CompositionDelivery()
        var host = Host()
        func send(_ commit: String, _ preedit: String) -> [CompositionDelivery.Action] {
            let actions = delivery.actions(commit: commit, preedit: preedit)
            host.apply(actions)
            return actions
        }

        _ = send("", "ㄋㄧˇㄏㄠˇㄒ")
        precondition(host.text == "ㄋㄧˇㄏㄠˇㄒ")
        // Selecting a partial candidate must never unmark and immediately
        // start a new marked range in a separate host process.
        let partial = send("你好", "ㄒ")
        precondition(partial == [.mark("你好ㄒ")])
        precondition(host.text == "你好ㄒ")
        _ = send("", "ㄒㄧˇㄏㄨㄢ")
        precondition(host.text == "你好ㄒㄧˇㄏㄨㄢ")
        let finish = send("喜歡", "")
        precondition(finish == [.mark("你好喜歡"), .unmark])
        precondition(host.text == "你好喜歡" && !delivery.hasMarkedText)

        _ = send("", "ㄋㄧˇㄏㄠˇㄒ")
        _ = send("你好", "ㄒ")
        // Removing or cancelling the remainder retains the chosen prefix.
        _ = send("", "")
        precondition(host.text == "你好喜歡你好" && !delivery.hasMarkedText)
        _ = send("。", "")
        precondition(host.text == "你好喜歡你好。")

        // Successive partial commits are kept in order and finalised once.
        _ = send("", "ㄨㄛˇㄇㄣ˙ㄐ")
        _ = send("我們", "ㄐ")
        _ = send("今天", "ㄑ")
        _ = send("去", "")
        precondition(host.text == "你好喜歡你好。我們今天去")

        // Punctuation or emoji committed together with the last segment must
        // be kept in the same final update, including multi-unit Unicode.
        _ = send("", "ㄋㄧˇㄏㄠˇㄒ")
        _ = send("你好", "ㄒ")
        _ = send("喜歡🐈", "")
        precondition(host.text == "你好喜歡你好。我們今天去你好喜歡🐈")

        // Switching documents discards the local bookkeeping only; it must
        // never deliver the previous document's prefix to the new document.
        _ = send("", "ㄅㄆ")
        _ = send("字", "ㄆ")
        let oldDocument = host.text
        delivery.reset()
        var newDocument = Host()
        newDocument.apply(delivery.actions(commit: "", preedit: ""))
        precondition(host.text == oldDocument && newDocument.text.isEmpty)
        newDocument.apply(delivery.actions(commit: "新", preedit: ""))
        precondition(newDocument.text == "新" && !delivery.hasMarkedText)
        // What the host should show before the caret while a reading is open.
        var typing = CompositionDelivery()
        _ = typing.actions(commit: "", preedit: "n")
        _ = typing.actions(commit: "", preedit: "ni")
        _ = typing.actions(commit: "", preedit: "nih")
        precondition(typing.agrees(withContextBefore: "今天nih"), "the marked text is before the caret")
        precondition(!typing.agrees(withContextBefore: "今天ni"), "an earlier report is left to the controller's wait")
        precondition(typing.agrees(withContextBefore: "ih"), "or only the tail of the context")
        precondition(!typing.agrees(withContextBefore: ""), "caret at the start of the field: the marked text is not before it")
        precondition(!typing.agrees(withContextBefore: "今"), "caret moved into earlier text")
        precondition(!typing.agrees(withContextBefore: "今天nih貼上的字"), "something was pasted after it")
        // Only the current marked text counts: a tap between 你 and 好 leaves a
        // context that ends with an earlier commit.
        _ = typing.actions(commit: "你", preedit: "h")
        _ = typing.actions(commit: "好", preedit: "")
        _ = typing.actions(commit: "", preedit: "w")
        precondition(typing.agrees(withContextBefore: "今天你好w"), "the new reading")
        precondition(!typing.agrees(withContextBefore: "今天你") && !typing.agrees(withContextBefore: "今天你好"), "a caret moved before it")
        typing.reset()
        precondition(typing.shown.isEmpty && !typing.agrees(withContextBefore: "wh"), "a reset forgets it")
        print("PASS: marked text preserves partial commits, continuation and cancellation; stale context is recognised")
        // Syllable breaks as Apple shows them (U+2006 pinyin, U+2004 zhuyin).
        let p = "\u{2006}", z = "\u{2004}"
        for (typed, shown) in [("nihao", "ni\(p)hao"), ("nih", "ni\(p)h"), ("xian", "xian"), ("fangan", "fang\(p)an"),
                               ("xi'an", "xi'an"), ("ni3hao", "ni3\(p)hao"), ("Nihao", "Ni\(p)hao"), ("NIHAO", "NIHAO"),
                               ("iphone", "iphone"), ("hello", "hello"), ("zhonghuarenmin", "zhong\(p)hua\(p)ren\(p)min")] {
            precondition(SyllableBreaks.pinyin(typed) == shown, "\(typed) is shown as \(SyllableBreaks.pinyin(typed))")
        }
        precondition(SyllableBreaks.zhuyin("ㄋㄧˇㄏㄠˇ") == "ㄋㄧˇ\(z)ㄏㄠˇ" && SyllableBreaks.zhuyin("ㄇㄣ˙ㄑㄩˋ") == "ㄇㄣ˙\(z)ㄑㄩˋ"
                     && SyllableBreaks.zhuyin("ㄋㄧㄏ") == "ㄋㄧ\(z)ㄏ" && SyllableBreaks.zhuyin("ㄇㄚˉ") == "ㄇㄚˉ")
        // The marked text with breaks is what the host shows, so it still agrees with the context.
        var spaced = CompositionDelivery()
        _ = spaced.actions(commit: "", preedit: SyllableBreaks.pinyin("nihao"))
        precondition(spaced.agrees(withContextBefore: "今天ni\(p)hao"))
        print("PASS: syllable breaks in the marked text")
    }
}
