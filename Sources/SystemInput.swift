import Foundation
import Carbon

enum SystemInput {
    static let bundleID = "app.meowpuut.inputmethod.Meowpuut"
    static func enable() throws {
        let location = Bundle.main.bundleURL
        let userDirectory = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Input Methods").path + "/"
        guard location.path.hasPrefix(userDirectory) || location.path.hasPrefix("/Library/Input Methods/") else {
            throw NSError(domain: bundleID, code: 1, userInfo: [NSLocalizedDescriptionKey: "请先安装 Meowpuut，再启用输入法。"])
        }
        let registration = TISRegisterInputSource(location as CFURL)
        guard registration == noErr else { throw failure(registration) }
        let filter = [kTISPropertyBundleID as String: bundleID] as CFDictionary
        guard let sources = TISCreateInputSourceList(filter, true)?.takeRetainedValue(), CFArrayGetCount(sources) > 0 else {
            throw NSError(domain: bundleID, code: 2, userInfo: [NSLocalizedDescriptionKey: "系统未发现 Meowpuut 输入源。"])
        }
        for index in 0..<CFArrayGetCount(sources) {
            let source = unsafeBitCast(CFArrayGetValueAtIndex(sources, index), to: TISInputSource.self)
            let status = TISEnableInputSource(source)
            guard status == noErr else { throw failure(status) }
        }
    }
    private static func failure(_ status: OSStatus) -> NSError {
        NSError(domain: bundleID, code: Int(status), userInfo: [NSLocalizedDescriptionKey: "启用失败（\(status)）。"])
    }
}
