import Carbon
import Foundation
guard CommandLine.arguments.count == 2 else { fputs("需要应用路径\n", stderr); exit(2) }
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let status = TISRegisterInputSource(url as CFURL)
guard status == noErr else { fputs("输入法注册失败：OSStatus \(status)\n", stderr); exit(1) }
print("已注册 Meowpuut 输入源。")
let filter = [kTISPropertyBundleID as String: "app.meowpuut.inputmethod.Meowpuut"] as CFDictionary
let list = TISCreateInputSourceList(filter, true)?.takeRetainedValue()
let count = list.map { CFArrayGetCount($0) } ?? 0
print("当前登录会话发现 \(count) 个 Meowpuut 输入源。")
if let list {
    for index in 0..<count {
        let source = unsafeBitCast(CFArrayGetValueAtIndex(list, index), to: TISInputSource.self)
        for key in [kTISPropertyInputSourceID, kTISPropertyLocalizedName, kTISPropertyInputSourceType, kTISPropertyInputSourceLanguages, kTISPropertyInputSourceIsEnableCapable, kTISPropertyInputSourceIsSelectCapable, kTISPropertyInputSourceIsEnabled, kTISPropertyInputSourceIsSelected] {
            if let value = TISGetInputSourceProperty(source, key!) {
                print("\(key!): \(Unmanaged<AnyObject>.fromOpaque(value).takeUnretainedValue())")
            }
        }
    }
}
if count == 0 { fputs("系统尚未发现输入源，系统入口未通过验收。\n", stderr) }
