import AppKit
import SwiftUI
import InputMethodKit

@objc(MeowpuutInputController)
class MeowpuutInputController: IMKInputController {
    private let engine = Engine()
    private var panel: NSPanel?
    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event, event.type == .keyDown, let client = sender as? IMKTextInput, let (key, modifiers) = rimeKey(event) else { return false }
        let handled = engine.key(key, modifiers: modifiers)
        deliver(client)
        return handled
    }
    private func deliver(_ client: IMKTextInput) {
        let commit = engine.takeCommit()
        if !commit.isEmpty { client.insertText(commit, replacementRange: NSRange(location: NSNotFound, length: 0)) }
        let preedit = engine.snapshot.preedit
        client.setMarkedText(preedit, selectionRange: NSRange(location: (preedit as NSString).length, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        guard !preedit.isEmpty else { panel?.orderOut(nil); return }
        var rect = NSRect.zero
        let selection = client.selectedRange()
        _ = client.attributes(forCharacterIndex: selection.location == NSNotFound ? 0 : selection.location, lineHeightRectangle: &rect)
        if panel == nil {
            panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 420, height: 390), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            panel?.level = .popUpMenu; panel?.isOpaque = false; panel?.backgroundColor = .clear; panel?.hasShadow = true
        }
        panel?.contentView = NSHostingView(rootView: CandidateStrip(engine: engine, onChoose: { [weak self] index in
            self?.engine.choose(index); self?.deliver(client)
        }, onPage: { [weak self] key in self?.engine.key(key); self?.deliver(client) }))
        let screen = NSScreen.screens.first(where: { $0.frame.intersects(rect) }) ?? NSScreen.main
        let bounds = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1200, height: 800)
        let height = CGFloat(100 + engine.snapshot.candidates.count * 54)
        let y = rect.minY - height - 6 >= bounds.minY ? rect.minY - height - 6 : rect.maxY + 6
        panel?.setFrame(NSRect(x: max(bounds.minX, min(rect.minX, bounds.maxX - 420)), y: max(bounds.minY, min(y, bounds.maxY - height)), width: 420, height: height), display: true)
        panel?.orderFrontRegardless()
    }
    override func deactivateServer(_ sender: Any!) {
        if let client = self.client() {
            // Keep the selected text when focus changes instead of dropping a composition.
            if engine.session != 0 { engine.finishComposition(); deliver(client) }
        }
        engine.clear(); panel?.orderOut(nil)
    }
    override func commitComposition(_ sender: Any!) {
        if let client = self.client(), engine.session != 0 { engine.finishComposition(); deliver(client) }
    }
    override func activateServer(_ sender: Any!) { engine.modeChanged() }
    override func menu() -> NSMenu! {
        let menu = NSMenu()
        menu.addItem(withTitle: "Meowpuut 设置…", action: #selector(openSettings), keyEquivalent: "")
        menu.items.first?.target = self
        let item = menu.addItem(withTitle: Preferences.shared.simplified ? "切换为繁體" : "切换为简体", action: #selector(toggleScript), keyEquivalent: "")
        item.target = self
        return menu
    }
    @objc func openSettings() { (NSApp.delegate as? Application)?.showStudio() }
    @objc func toggleScript() { Preferences.shared.simplified.toggle(); engine.modeChanged(); if let client = self.client() { deliver(client) } }
}

final class Application: NSObject, NSApplicationDelegate {
    var server: IMKServer?
    var window: NSWindow?
    func applicationWillTerminate(_ notification: Notification) { if Engine.initialized { mp_stop() } }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { showStudio(); return true }
    func applicationDidFinishLaunching(_ notification: Notification) {
        if CommandLine.arguments.contains("--smoke") { smoke(); return }
        server = IMKServer(name: "Meowpuut_Connection", bundleIdentifier: "app.meowpuut.inputmethod.Meowpuut")
        if CommandLine.arguments.contains("--settings") { showStudio() }
    }
    func showStudio() {
        NSApp.setActivationPolicy(.regular)
        if window == nil {
            window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1040, height: 710), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
            window?.title = "Meowpuut"; window?.contentView = NSHostingView(rootView: Studio()); window?.center()
            window?.isReleasedWhenClosed = false
            let menu = NSMenu(); let appItem = NSMenuItem(); menu.addItem(appItem)
            let submenu = NSMenu(); submenu.addItem(withTitle: "退出 Meowpuut", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"); appItem.submenu = submenu
            let edit = NSMenuItem(); menu.addItem(edit); edit.submenu = NSMenu(title: "编辑")
            for (title, action, key) in [("复制", "copy:", "c"), ("粘贴", "paste:", "v"), ("全选", "selectAll:", "a")] {
                edit.submenu?.addItem(withTitle: title, action: Selector(action), keyEquivalent: key)
            }
            NSApp.mainMenu = menu
        }
        window?.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
    }
    func smoke() {
        setbuf(stdout, nil)
        func finish(_ code: Int32) -> Never { mp_stop(); try? FileManager.default.removeItem(at: Preferences.directory); UserDefaults(suiteName: "app.meowpuut.smoke")?.removePersistentDomain(forName: "app.meowpuut.smoke"); exit(code) }
        let started = CFAbsoluteTimeGetCurrent()
        let engine = Engine()
        let initializationMs = (CFAbsoluteTimeGetCurrent() - started) * 1000
        guard engine.session != 0 else { fputs("\(engine.error)\n", stderr); finish(1) }
        var typedCommits = ""
        var keyTimes: [Double] = []
        func type(_ text: String) {
            typedCommits = ""
            for byte in text.utf8 {
                let start = CFAbsoluteTimeGetCurrent()
                engine.key(Int32(byte))
                keyTimes.append((CFAbsoluteTimeGetCurrent() - start) * 1000)
                typedCommits += engine.snapshot.commit
            }
        }
        guard NSClassFromString("MeowpuutInputController") != nil else { finish(19) }
        let physical = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil, characters: "ㄋ", charactersIgnoringModifiers: "ㄋ", isARepeat: false, keyCode: 1)!
        guard rimeKey(physical)?.0 == 115 else { finish(20) }
        Preferences.shared.simplified = false
        type("su3cl3")
        print("你好候选：\(engine.snapshot.candidates)")
        guard engine.snapshot.candidates.contains("你好") else { finish(2) }
        let pinned = engine.snapshot.candidates[1]
        engine.pin(1)
        guard engine.snapshot.candidates.first == pinned, engine.snapshot.highlighted == 0 else { finish(9) }
        engine.key(0xff0d)
        let pinnedCommit = engine.takeCommit()
        print("固定首选：\(pinned)，提交：\(pinnedCommit)，组合：\(engine.snapshot.preedit)")
        guard pinnedCommit == pinned, engine.takeCommit().isEmpty else { finish(10) }
        engine.clear(); type("su3cl3")
        guard engine.snapshot.candidates.first == pinned else { finish(11) }
        engine.pin(0)
        engine.browse(phrases: true)
        guard engine.browsing, engine.snapshot.candidates.allSatisfy({ $0.count > 1 }) else { finish(14) }
        let reading = engine.snapshot.preedit
        engine.key(0xff1b)
        guard !engine.browsing, engine.snapshot.preedit == reading else { finish(26) }
        engine.browse(phrases: false)
        if engine.browseCount > 7 {
            engine.key(0xff56)
            let fromNextPage = engine.snapshot.candidates[0]
            engine.pin(0)
            guard engine.snapshot.page == 0, engine.snapshot.candidates.first == fromNextPage else { finish(15) }
            engine.key(0xff0d)
            let recalled = engine.takeCommit()
            print("跨页首选：\(fromNextPage)，整句提交：\(recalled)")
            guard recalled.hasPrefix(fromNextPage) else { finish(16) }
            engine.clear(); type("su3cl3")
            guard engine.snapshot.candidates.first == fromNextPage else { finish(17) }
            engine.pin(0)
        }
        engine.clear(); type("su3cl3")
        engine.clear(); type("sucl")
        guard engine.snapshot.candidates.contains("你好") else { finish(28) }
        print("无声调输入：\(engine.snapshot.candidates)")
        guard readingLabel("t:su3cl3") == "繁 · ㄋㄧˇㄏㄠˇ" else { finish(29) }
        if !engine.snapshot.lastPage {
            engine.key(0xff56)
            guard engine.snapshot.page == 1, !engine.snapshot.candidates.isEmpty else { finish(12) }
            engine.key(0xff55)
            guard engine.snapshot.page == 0 else { finish(13) }
        }
        engine.clear(); type("5j/4") // ㄓㄨㄥˋ → 重 / 种 / 中
        let traditional = engine.snapshot.candidates
        Preferences.shared.simplified = true; engine.modeChanged()
        let simplified = engine.snapshot.candidates
        print("繁體：\(traditional)；简体：\(simplified)")
        guard traditional != simplified, engine.snapshot.preedit.contains("ㄓ") else { finish(3) }
        engine.key(32, modifiers: 1)
        guard engine.english, !engine.snapshot.commit.isEmpty, engine.snapshot.preedit.isEmpty else { finish(4) }
        guard engine.key(97), engine.takeCommit() == "a" else { finish(5) }
        engine.key(32, modifiers: 1)
        guard !engine.english else { finish(6) }
        for literal in ["GitHub", "https://example.com", "name@example.com"] {
            engine.clear(); type(literal); engine.key(0xff0d)
            let committed = typedCommits + engine.takeCommit()
            print("英文直输：\(literal) → \(committed)")
            guard committed == literal else { finish(18) }
        }
        engine.clear(); type("hello"); engine.key(0xff0d, modifiers: 1)
        guard engine.takeCommit() == "hello", !engine.english else { finish(27) }
        engine.clear(); type("/hello")
        guard !engine.snapshot.candidates.isEmpty else { finish(7) }
        engine.choose(0)
        guard !engine.snapshot.commit.isEmpty else { finish(8) }
        _ = engine.takeCommit()
        engine.key(47); engine.key(0xff08)
        guard engine.snapshot.preedit.isEmpty, engine.snapshot.candidates.isEmpty else { finish(21) }
        type("/unknown"); engine.key(32)
        guard engine.takeCommit() == "/unknown " else { finish(22) }
        let module = PhraseModule(id: "smoke.pages", apiVersion: 1, name: "模块分页", trigger: "/pages", phrases: (1...12).map { "回复\($0)" })
        let file = Preferences.shared.moduleDirectory.appendingPathComponent("pages.json")
        do { try JSONEncoder().encode(module).write(to: file) } catch { finish(23) }
        Preferences.shared.reload()
        type("/pages"); engine.key(0xff56)
        guard engine.snapshot.page == 1, engine.snapshot.candidates.first == "回复8" else { finish(24) }
        engine.choose(0)
        guard engine.takeCommit() == "回复8" else { finish(25) }
        Preferences.shared.simplified = false
        let timings = keyTimes.sorted()
        print(String(format: "ARM64 核心按键：中位 %.2f ms，P95 %.2f ms；临时词库冷初始化 %.0f ms（不含系统跨应用开销）", timings[timings.count / 2], timings[min(timings.count - 1, Int(Double(timings.count) * 0.95))], initializationMs))
        print("PASS：真实注音、繁简切换、保留组合、中英切换、模块选词、固定首选与回车一致、翻页、提交只消费一次")
        finish(0)
    }
}
let app = NSApplication.shared
let delegate = Application()
app.delegate = delegate
app.run()
