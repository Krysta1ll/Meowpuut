import Carbon
import Foundation
// Diagnostic twin of `Meowpuut --register` (Sources/InputSourceRegistration.swift); prints every property.
guard CommandLine.arguments.count == 2 else { fputs("需要應用程式路徑\n", stderr); exit(2) }
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let status = TISRegisterInputSource(url as CFURL)
guard status == noErr else { fputs("輸入法註冊失敗：OSStatus \(status)\n", stderr); exit(1) }
print("已註冊 Meowpuut 輸入來源。")
// Enable what macOS lets a program enable; the rest stays for System Settings.
for source in TISCreateInputSourceList([kTISPropertyBundleID as String: "app.meowpuut.inputmethod.Meowpuut"] as CFDictionary, true)?.takeRetainedValue() as? [TISInputSource] ?? [] {
    _ = TISEnableInputSource(source)
}
let filter = [kTISPropertyBundleID as String: "app.meowpuut.inputmethod.Meowpuut"] as CFDictionary
let list = TISCreateInputSourceList(filter, true)?.takeRetainedValue() as? [TISInputSource] ?? []
let count = list.count
print("目前登入工作階段發現 \(count) 個 Meowpuut 輸入來源。")
for source in list {
    for key in [kTISPropertyInputSourceID, kTISPropertyLocalizedName, kTISPropertyInputSourceType, kTISPropertyInputSourceLanguages, kTISPropertyInputSourceIsEnableCapable, kTISPropertyInputSourceIsSelectCapable, kTISPropertyInputSourceIsEnabled, kTISPropertyInputSourceIsSelected] {
        if let value = TISGetInputSourceProperty(source, key!) {
            print("\(key!): \(Unmanaged<AnyObject>.fromOpaque(value).takeUnretainedValue())")
        }
    }
}
if count == 0 { fputs("系統尚未發現輸入來源，系統入口未通過驗收。\n", stderr) }
