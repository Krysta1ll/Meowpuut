import AppKit
import SwiftUI
import InputMethodKit

@objc(MeowpuutInputController)
class MeowpuutInputController: IMKInputController {
    private let engine = Engine(Preferences.shared.keyboardBackend, mode: Preferences.shared.inputMode)
    private var shiftTap = ShiftTapTracker()
    private lazy var candidates: CandidatePanelController = {
        let controller = CandidatePanelController(engine: engine)
        controller.onChoose = { [weak self] index in
            guard let self, let client = self.client() else { return }
            // A click chooses what is under it, in the row or in the panel.
            self.chooseCompactCandidate(index)
            self.deliver(client)
        }
        controller.onPage = { [weak self] key in
            guard let self, let client = self.client() else { return }
            self.engine.key(key)
            self.deliver(client)
        }
        controller.onToggle = { [weak self] in
            guard let self, let client = self.client() else { return }
            self.setCandidatesExpanded(!self.candidatesExpanded)
            self.deliver(client)
        }
        controller.onAppearanceChange = { [weak self] in
            guard let self, let client = self.client() else { return }
            self.deliver(client)
        }
        return controller
    }()
    /// The caret now (end of the marked text) and where this composition began.
    private var anchor: NSRect?
    private var compositionStart: NSRect?
    /// Where the window goes while the client reports no caret: the pointer when this composition began.
    private var pointerAnchor: NSRect?
    private var composing = false
    private var candidatesExpanded = false

    /// The menu follows the system language, like the settings window it opens.
    private var t: InterfaceText { .system }
    private var canExpandCandidates: Bool { candidatesCanExpand(engine.snapshot) }

    private func setCandidatesExpanded(_ expanded: Bool) {
        candidatesExpanded = expanded
        if expanded && compactCandidateText(engine.snapshot, at: engine.snapshot.highlighted) == nil {
            engine.snapshot.highlighted = 0
        }
        if !expanded && !collapsedCandidateIndices(engine.snapshot).contains(engine.snapshot.highlighted) {
            engine.snapshot.highlighted = 0
        }
    }

    private func caret(_ client: IMKTextInput, markedText: String? = nil) -> NSRect? {
        SystemInput.caretRect(markedText: markedText, screens: NSScreen.screens.map(\.frame)) { index in
            var rect = NSRect.zero
            _ = client.attributes(forCharacterIndex: index, lineHeightRectangle: &rect)
            return rect
        }
    }

    override func recognizedEvents(_ sender: Any!) -> Int {
        Int(NSEvent.EventTypeMask.keyDown.rawValue | NSEvent.EventTypeMask.flagsChanged.rawValue)
    }
    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event else { return false }
        let shiftOnly = shiftTap.update(keyCode: event.keyCode, modifierEvent: event.type == .flagsChanged,
            shift: event.modifierFlags.contains(.shift),
            other: !event.modifierFlags.intersection([.command, .control, .option]).isEmpty)
        // Some clients omit the sender on modifier events; IMK still retains
        // the currently active text client.
        let activeClient = (sender as? IMKTextInput) ?? self.client()
        if event.type == .flagsChanged {
            if shiftOnly, engine.inputMode == .pinyin, let activeClient {
                engine.key(Engine.toggleEnglishKey)
                setCandidatesExpanded(false)
                deliver(activeClient)
                return true
            }
            return false
        }
        guard event.type == .keyDown, let client = activeClient else { return false }
        if event.modifierFlags.intersection([.command, .control, .option, .shift]) == [.control, .option],
           let index = [UInt16(18), 19, 20].firstIndex(of: event.keyCode) {
            changeInputMode(InputMode.allCases[index], client: client)
            return true
        }
        if !engine.snapshot.candidates.isEmpty,
           event.modifierFlags.intersection([.command, .control, .option, .shift]) == [.option],
           event.keyCode == 123 || event.keyCode == 124 {
            engine.key(event.keyCode == 123 ? 0xff55 : 0xff56)
            setCandidatesExpanded(true)
            deliver(client)
            return true
        }
        guard let (key, modifiers) = inputKey(event, mode: engine.inputMode, english: engine.english, composing: !engine.snapshot.input.isEmpty) else { return false }
        if modifiers == 0, key == 0xff1b, candidatesExpanded {
            setCandidatesExpanded(false)
            deliver(client)
            return true
        }
        if modifiers == 0, key == 0xff54, !candidatesExpanded, canExpandCandidates {
            setCandidatesExpanded(true)
            deliver(client)
            return true
        }
        if candidatesExpanded, [Int32(0xff51), 0xff52, 0xff53, 0xff54].contains(key) {
            let current = engine.snapshot.highlighted
            let next = expandedCandidateMove(expandedCandidates(engine.snapshot), from: current, key: key)
            if next == current && (key == 0xff54 || key == 0xff53) && !engine.snapshot.lastPage {
                engine.key(0xff56)
            } else if next == current && (key == 0xff52 || key == 0xff51) && engine.snapshot.page > 0 {
                engine.key(0xff55)
                engine.snapshot.highlighted = max(0, engine.snapshot.candidates.count - 1)
            } else { engine.snapshot.highlighted = next }
            deliver(client)
            return true
        }
        if candidatesExpanded, !engine.english {
            let cells = expandedCandidates(engine.snapshot).count
            if key == 0xff09, cells > 0 {
                // Tab steps through every cell of the panel and wraps.
                engine.snapshot.highlighted = (min(engine.snapshot.highlighted, cells - 1) + (modifiers == 1 ? cells - 1 : 1)) % cells
                deliver(client)
                return true
            }
            if let index = expandedCandidateShortcut(engine.snapshot, key: key, modifiers: modifiers,
                                                     shifted: engine.candidateShortcut(0).hasPrefix("⇧")) {
                let previousInput = engine.snapshot.input
                chooseCompactCandidate(index)
                if engine.snapshot.input != previousInput { setCandidatesExpanded(false) }
                deliver(client)
                return true
            }
        }
        if !candidatesExpanded {
            if let next = collapsedCandidateMove(engine.snapshot, key: key, modifiers: modifiers), !engine.english {
                engine.snapshot.highlighted = next
                deliver(client)
                return true
            }
            switch collapsedCandidateShortcut(engine.snapshot, key: key, modifiers: modifiers,
                                              shifted: engine.candidateShortcut(0).hasPrefix("⇧"), english: engine.english) {
            case .choose(let index):
                let previousInput = engine.snapshot.input
                chooseCompactCandidate(index)
                if engine.snapshot.input != previousInput { setCandidatesExpanded(false) }
                deliver(client)
                return true
            case .expand:
                setCandidatesExpanded(true)
                deliver(client)
                return true
            case .pass: break
            }
        }
        // Enter or Space on the kept Japanese candidate, in the row or in the panel.
        let snapshot = engine.snapshot
        if snapshot.compactJapanese != nil && snapshot.highlighted == snapshot.candidates.count &&
            modifiers == 0 && (key == 0xff0d || (key == 32 && engine.inputMode == .pinyin)) {
            chooseCompactCandidate(snapshot.highlighted)
            deliver(client)
            return true
        }
        if confirmJapaneseCandidate(engine, key: key, modifiers: modifiers) {
            candidates.candidateChosen()
            deliver(client)
            return true
        }
        // No caret moves inside the reading, so Left and Right stay here while composing
        // instead of reaching the app with the marked text open.
        if !engine.snapshot.preedit.isEmpty, key == 0xff51 || key == 0xff53 { return true }
        let previousInput = engine.snapshot.input
        let handled = engine.key(key, modifiers: modifiers)
        if handled && (key == 32 || key == 0xff0d) { candidates.candidateChosen() }
        if handled && engine.snapshot.input != previousInput { setCandidatesExpanded(false) }
        if handled && (key == 0xff52 || key == 0xff54 || key == 0xff09 || key == 0xff55 || key == 0xff56) &&
            (!collapsedCandidateIndices(engine.snapshot).contains(engine.snapshot.highlighted) ||
             engine.snapshot.page > 0) && canExpandCandidates {
            setCandidatesExpanded(true)
        }
        deliver(client)
        return handled
    }
    private func chooseCompactCandidate(_ index: Int) {
        if index == engine.snapshot.candidates.count, engine.snapshot.compactJapanese != nil {
            engine.chooseCompactJapanese()
        } else {
            engine.choose(index)
        }
        candidates.candidateChosen()
    }
    private func deliver(_ client: IMKTextInput) {
        let commit = engine.takeCommit()
        if !commit.isEmpty { client.insertText(commit, replacementRange: NSRange(location: NSNotFound, length: 0)) }
        let preedit = engine.snapshot.preedit
        if !commit.isEmpty || preedit.isEmpty { setCandidatesExpanded(false) }
        // Before the marked text goes in, the caret is where this composition starts.
        if !preedit.isEmpty && (!composing || !commit.isEmpty) {
            compositionStart = caret(client)
            anchor = compositionStart
        }
        composing = !preedit.isEmpty
        client.setMarkedText(preedit, selectionRange: NSRange(location: (preedit as NSString).length, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        guard composing else {
            anchor = nil
            compositionStart = nil
            pointerAnchor = nil
            candidates.hide()
            return
        }
        anchor = caret(client, markedText: preedit) ?? anchor
        if let start = compositionStart, let anchor, !candidateSameLine(start, anchor) {
            // The text scrolled or wrapped: ask again where the marked text begins.
            compositionStart = caret(client) ?? start
        }
        // Without a usable caret the window goes to the pointer and stays there
        // for this composition. The height stands for one line of text.
        if anchor == nil, pointerAnchor == nil {
            pointerAnchor = NSRect(origin: NSEvent.mouseLocation, size: NSSize(width: 0, height: 18))
        }
        guard let rect = anchor ?? pointerAnchor else { return }
        candidates.show(expanded: candidatesExpanded, canExpand: canExpandCandidates, caret: rect, start: compositionStart)
    }

    override func deactivateServer(_ sender: Any!) {
        shiftTap.reset()
        if let client = self.client() {
            // Keep the selected text when focus changes instead of dropping a composition.
            if engine.isReady { engine.finishComposition(); deliver(client) }
        }
        ChoiceMemory.shared.flush()
        engine.clear(); anchor = nil; compositionStart = nil; pointerAnchor = nil; composing = false; setCandidatesExpanded(false); candidates.hide()
    }
    override func commitComposition(_ sender: Any!) {
        if let client = self.client(), engine.isReady { engine.finishComposition(); deliver(client) }
    }
    override func activateServer(_ sender: Any!) {
        shiftTap.reset()
        engine.selectMode(Preferences.shared.inputMode)
        engine.modeChanged()
    }
    override func menu() -> NSMenu! {
        let t = self.t
        let preferences = Preferences.shared
        let menu = NSMenu()
        menu.addItem(withTitle: t("Meowpuut 設定…", "Meowpuut 设置…"), action: #selector(openSettings), keyEquivalent: "")
        menu.items.first?.target = self
        let item = menu.addItem(withTitle: preferences.simplified ? "切换为繁体" : "切換為簡體", action: #selector(toggleScript), keyEquivalent: "")
        item.target = self
        item.isEnabled = engine.inputMode != .japanese
        menu.addItem(.separator())
        for (index, mode) in InputMode.allCases.enumerated() {
            let choice = menu.addItem(withTitle: mode.title + "  ⌃⌥" + String(index + 1), action: #selector(switchInputMode(_:)), keyEquivalent: "")
            choice.target = self
            choice.representedObject = mode.rawValue
            choice.state = engine.inputMode == mode ? .on : .off
        }
        menu.addItem(.separator())
        for (title, key, on) in [(t("米米莫與莫莫", "米米莫与莫莫"), "cats", preferences.catGlassSkin),
                                 (t("節日裝扮", "节日装扮"), "festival", preferences.festivalOutfits)] {
            let toggle = menu.addItem(withTitle: title, action: #selector(switchAppearance(_:)), keyEquivalent: "")
            toggle.target = self
            toggle.representedObject = key + ":" + (on ? "off" : "on")
            toggle.state = on ? .on : .off
        }
        let groups: [(String, [(String, String, Bool)])] = [
            (t("造型", "造型"), GlassShape.allCases.map { ($0.name(t), "shape:" + $0.rawValue, $0 == preferences.glassShape) }),
            (t("色調", "色调"), CandidateTone.allCases.map { ($0.name(t), "tone:" + $0.rawValue, $0 == preferences.candidateTone) }),
            (t("色系", "色系"), CandidatePalette.allCases.map { ($0.name(t), "palette:" + $0.rawValue, $0 == preferences.candidatePalette) }),
            (t("玻璃", "玻璃"), GlassStrength.allCases.map { ($0.name(t), "glass:" + $0.rawValue, $0 == preferences.glassStrength) }),
        ]
        for (title, values) in groups {
            let parent = menu.addItem(withTitle: title, action: nil, keyEquivalent: "")
            let submenu = NSMenu(title: title)
            for (name, value, on) in values {
                let item = submenu.addItem(withTitle: name, action: #selector(switchAppearance(_:)), keyEquivalent: "")
                item.target = self; item.representedObject = value
                item.state = on ? .on : .off
            }
            parent.submenu = submenu
        }
        return menu
    }
    @objc private func switchAppearance(_ sender: Any) {
        guard let item = SystemInput.menuItem(from: sender), let value = item.representedObject as? String else { return }
        applyAppearance(value)
        if let client = self.client() { deliver(client) }
    }
    private func changeInputMode(_ mode: InputMode, client: IMKTextInput) {
        if composing { engine.finishComposition(); deliver(client) }
        engine.selectMode(mode)
        if engine.isReady && engine.inputMode == mode {
            Preferences.shared.inputMode = mode
            engine.english = false
        } else { NSSound.beep() }
        deliver(client)
    }
    @objc private func switchInputMode(_ sender: Any) {
        guard let item = SystemInput.menuItem(from: sender), let name = item.representedObject as? String, let mode = InputMode(rawValue: name),
              let client = self.client() else { return }
        changeInputMode(mode, client: client)
    }
    @objc func openSettings() { (NSApp.delegate as? Application)?.showStudio() }
    @objc func toggleScript() { Preferences.shared.simplified.toggle(); engine.modeChanged(); if let client = self.client() { deliver(client) } }
}

final class Application: NSObject, NSApplicationDelegate {
    var server: IMKServer?
    var window: NSWindow?
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { showStudio(); return true }
    func startInputServer() -> Bool {
        let bundle = Bundle.main
        guard let connection = bundle.object(forInfoDictionaryKey: "InputMethodConnectionName") as? String,
              let bundleID = bundle.bundleIdentifier,
              let server = IMKServer(name: connection, bundleIdentifier: bundleID) else {
            NSLog("Meowpuut: 無法啟動系統輸入服務")
            return false
        }
        self.server = server
        return true
    }
    func applicationDidFinishLaunching(_ notification: Notification) {
        if CommandLine.arguments.contains("--smoke-languages") { smoke(); return }
        if CommandLine.arguments.contains("--smoke") { smoke(); return }
        if Preferences.enginePreview || CommandLine.arguments.contains("--settings") { showStudio() }
    }
    func showStudio() {
        NSApp.setActivationPolicy(.regular)
        if window == nil {
            let t = InterfaceText.system
            window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1040, height: 710), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
            window?.title = Preferences.enginePreview ? t("Meowpuut 引擎試打", "Meowpuut 引擎试打") : "Meowpuut"
            window?.contentView = NSHostingView(rootView: Studio()); window?.center()
            window?.isReleasedWhenClosed = false
            let menu = NSMenu(); let appItem = NSMenuItem(); menu.addItem(appItem)
            let submenu = NSMenu(); submenu.addItem(withTitle: t("結束 Meowpuut", "退出 Meowpuut"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"); appItem.submenu = submenu
            let edit = NSMenuItem(); menu.addItem(edit); edit.submenu = NSMenu(title: t("編輯", "编辑"))
            for (title, action, key) in [(t("拷貝", "拷贝"), "copy:", "c"), (t("貼上", "粘贴"), "paste:", "v"), (t("全選", "全选"), "selectAll:", "a")] {
                edit.submenu?.addItem(withTitle: title, action: Selector(action), keyEquivalent: key)
            }
            NSApp.mainMenu = menu
        }
        window?.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
    }
    func smoke() {
        runIndependentSmoke()
    }

}
let app = NSApplication.shared
let delegate = Application()
app.delegate = delegate
if !Preferences.testing, !Preferences.enginePreview, !delegate.startInputServer() { exit(1) }
app.run()
