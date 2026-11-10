import Foundation

@main
struct InterfaceTextTests {
    static func main() {
        precondition(InterfaceText.prefersSimplified(languages: ["zh-Hans-CN", "en-US"]))
        precondition(InterfaceText.prefersSimplified(languages: ["en-US", "zh-CN"]))
        precondition(InterfaceText.prefersSimplified(languages: ["zh_SG"]))
        precondition(!InterfaceText.prefersSimplified(languages: ["zh-Hant-TW", "zh-Hans-CN"]))
        precondition(!InterfaceText.prefersSimplified(languages: ["zh-HK"]))
        precondition(!InterfaceText.prefersSimplified(languages: ["ja-JP", "en-US"]))
        let t = InterfaceText(simplified: true)
        precondition(t("換行", "换行") == "换行" && InterfaceText(simplified: false)("換行", "换行") == "換行")
        print("PASS: interface text follows the first Chinese language and the chosen script")
    }
}
