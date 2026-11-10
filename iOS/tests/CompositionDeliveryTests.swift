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
        print("PASS: marked text preserves partial commits, continuation and cancellation")
    }
}
