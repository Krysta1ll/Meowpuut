import Foundation
import Carbon
import InputMethodKit

enum SystemInput {
    // IMK offsets are UTF-16 positions relative to the marked text. Some
    // clients reject the insertion point at the end; try the last complete
    // grapheme in that case and otherwise retain the caller's previous anchor.
    static func caretRect(markedText: String?, screens: [NSRect], query: (Int) -> NSRect?) -> NSRect? {
        var indices = [0]
        if let markedText, !markedText.isEmpty {
            let text = markedText as NSString
            let last = text.rangeOfComposedCharacterSequence(at: text.length - 1).location
            indices = [text.length, last]
        }
        var visited = Set<Int>()
        for index in indices where visited.insert(index).inserted {
            guard let rect = query(index), rect.origin.x.isFinite, rect.origin.y.isFinite,
                  rect.width.isFinite, rect.width >= 0, rect.height.isFinite, rect.height > 0,
                  screens.contains(where: { $0.contains(NSPoint(x: rect.midX, y: rect.midY)) }) else { continue }
            return rect
        }
        return nil
    }

    // IMK dispatches text-input menu actions with a command dictionary, while
    // ordinary AppKit menus pass the item itself.
    static func menuItem(from sender: Any) -> NSMenuItem? {
        if let item = sender as? NSMenuItem { return item }
        return (sender as? [String: Any])?[kIMKCommandMenuItemName] as? NSMenuItem
    }

    static let bundleID = "app.meowpuut.inputmethod.Meowpuut"
    static func enable() throws {
        let location = Bundle.main.bundleURL
        let userDirectory = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Input Methods").path + "/"
        guard location.path.hasPrefix(userDirectory) || location.path.hasPrefix("/Library/Input Methods/") else {
            throw NSError(domain: bundleID, code: 1, userInfo: [NSLocalizedDescriptionKey: "請先安裝 Meowpuut，再啟用輸入法。"])
        }
        let registration = TISRegisterInputSource(location as CFURL)
        guard registration == noErr else { throw failure(registration) }
        let filter = [kTISPropertyBundleID as String: bundleID] as CFDictionary
        guard let sources = TISCreateInputSourceList(filter, true)?.takeRetainedValue() as? [TISInputSource], !sources.isEmpty else {
            throw NSError(domain: bundleID, code: 2, userInfo: [NSLocalizedDescriptionKey: "系統未發現 Meowpuut 輸入來源。"])
        }
        for source in sources {
            let status = TISEnableInputSource(source)
            guard status == noErr else { throw failure(status) }
        }
    }
    private static func failure(_ status: OSStatus) -> NSError {
        NSError(domain: bundleID, code: Int(status), userInfo: [NSLocalizedDescriptionKey: "啟用失敗（\(status)）。"])
    }
}
