import AppKit

/// Whether the app being typed in has a full-screen (or borderless full-screen) window in front: its front window
/// covers a whole display. FINAL.md §3: 字景 then shows its one-line game strip and the glass puts its cats away.
/// The same measurement as the desk's (Desk/Sources/WorldSensor.swift: the front window's bounds equal a screen's).
/// Needs no Screen Recording or Accessibility permission: only bounds, layer and owner of each window are read
/// (`CGWindowListCopyWindowInfo`), never a title. Asked once when a composition starts, not per key.
enum FullScreenProbe {
    struct Window: Equatable {
        var pid: Int32
        var frame: CGRect
    }

    /// The pure part. `windows` are layer-0 windows front to back, `screens` the displays' frames, both in the same
    /// top-left coordinates; the front window of `frontPID` is the one that counts.
    static func isFullScreen(windows: [Window], screens: [CGRect], frontPID: Int32) -> Bool {
        guard let front = windows.first(where: { $0.pid == frontPID }) else { return false }
        return screens.contains { s in
            abs(front.frame.minX - s.minX) < 1 && abs(front.frame.minY - s.minY) < 1
                && abs(front.frame.maxX - s.maxX) < 1 && abs(front.frame.maxY - s.maxY) < 1
        }
    }

    /// Reads the system now.
    static func frontIsFullScreen() -> Bool {
        guard let front = NSWorkspace.shared.frontmostApplication?.processIdentifier,
              front != ProcessInfo.processInfo.processIdentifier,
              let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { return false }
        var windows: [Window] = []
        for entry in list {
            guard (entry[kCGWindowLayer as String] as? Int) == 0,
                  let pid = entry[kCGWindowOwnerPID as String] as? Int32,
                  let bounds = entry[kCGWindowBounds as String] as? NSDictionary,
                  let frame = CGRect(dictionaryRepresentation: bounds as CFDictionary) else { continue }
            windows.append(Window(pid: pid, frame: frame))
        }
        // Cocoa's y-up screen space and the window list's y-down space meet at the top of the main display.
        let height = CGDisplayBounds(CGMainDisplayID()).height
        let screens = NSScreen.screens.map { CGRect(x: $0.frame.minX, y: height - $0.frame.maxY, width: $0.frame.width, height: $0.frame.height) }
        return isFullScreen(windows: windows, screens: screens, frontPID: front)
    }
}
