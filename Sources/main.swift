import AppKit
import SwiftUI
import InputMethodKit

@objc(MeowpuutInputController)
class MeowpuutInputController: IMKInputController {
    private let engine = Engine(Preferences.shared.keyboardBackend, mode: Preferences.shared.inputMode)
    private var shiftTap = ShiftTapTracker()
    private var panel: NSPanel?
    private var catHosting: NSHostingView<GlassCandidateView>?
    private var displayedCatSkin = false
    private var anchor: NSRect?
    private var composing = false
    private var lastPanelAnchor: NSRect?
    private var candidatesExpanded = false {
        didSet {
            if let catHosting { catHosting.rootView.expanded = candidatesExpanded }

        }
    }

    private var canExpandCandidates: Bool {
        let snapshot = engine.snapshot
        guard !snapshot.candidates.isEmpty else { return false }
        let layout = CollapsedCandidateLayout(snapshot)
        return layout.truncated || layout.indices.filter { $0 < snapshot.candidates.count }.count < snapshot.candidates.count ||
            !snapshot.lastPage || snapshot.page > 0
    }

    private func setCandidatesExpanded(_ expanded: Bool) {
        candidatesExpanded = expanded
        if expanded && engine.snapshot.highlighted >= engine.snapshot.candidates.count {
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
        guard let (key, modifiers) = inputKey(event, mode: engine.inputMode, english: engine.english, composing: !engine.snapshot.input.isEmpty) else {
            // Shortcuts and layout-specific input belong to the host, which may
            // move the selection or insert text we cannot use as learning context.
            engine.resetLearningContext()
            return false
        }
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
        if candidatesExpanded, modifiers == 0, [Int32(0xff51), 0xff52, 0xff53, 0xff54].contains(key) {
            let current = engine.snapshot.highlighted
            let next = expandedCandidateMove(engine.snapshot.layoutCandidates, from: current, key: key)
            if next == current && (key == 0xff54 || key == 0xff53) && !engine.snapshot.lastPage {
                engine.key(0xff56)
            } else if next == current && (key == 0xff52 || key == 0xff51) && engine.snapshot.page > 0 {
                engine.key(0xff55)
                engine.snapshot.highlighted = max(0, engine.snapshot.candidates.count - 1)
            } else { engine.snapshot.highlighted = next }
            deliver(client)
            return true
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
            let snapshot = engine.snapshot
            if snapshot.compactJapanese != nil && snapshot.highlighted == snapshot.candidates.count &&
                modifiers == 0 && (key == 0xff0d || (key == 32 && engine.inputMode == .pinyin)) {
                chooseCompactCandidate(snapshot.highlighted)
                deliver(client)
                return true
            }
        }
        let previousInput = engine.snapshot.input
        if engine.needsDocumentContext(for: key) { engine.prepareDocumentContext(before: SystemInput.precedingContext(client)) }
        let handled = engine.key(key, modifiers: modifiers)
        if handled && engine.snapshot.input != previousInput { setCandidatesExpanded(false) }
        if handled && (key == 0xff52 || key == 0xff09 || key == 0xff55 || key == 0xff56) &&
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
    }
    private func deliver(_ client: IMKTextInput) {
        let commit = engine.takeCommit()
        if !commit.isEmpty { client.insertText(commit, replacementRange: NSRange(location: NSNotFound, length: 0)) }
        let preedit = engine.snapshot.preedit
        if !commit.isEmpty || preedit.isEmpty { setCandidatesExpanded(false) }
        if !preedit.isEmpty && (!composing || !commit.isEmpty) { anchor = caret(client) }
        SystemInput.updateMarkedText(preedit, wasComposing: composing, didCommit: !commit.isEmpty) { text, selection in
            client.setMarkedText(text, selectionRange: selection, replacementRange: NSRange(location: NSNotFound, length: 0))
        }
        composing = !preedit.isEmpty
        guard composing else {
            anchor = nil
            panel?.orderOut(nil)
            return
        }
        anchor = caret(client, markedText: preedit) ?? anchor
        let catSkin = Preferences.shared.catGlassSkin
        if panel != nil && displayedCatSkin != catSkin {
            panel?.orderOut(nil)
            panel = nil
            catHosting = nil
        }
        displayedCatSkin = catSkin
        deliverCandidatePanel(client)
    }

    private func deliverCandidatePanel(_ client: IMKTextInput) {
        if catHosting == nil {
            let view = GlassCandidateView(engine: engine, cats: Preferences.shared.catGlassSkin, expanded: candidatesExpanded, canExpand: canExpandCandidates,
                onChoose: { [weak self] index in
                    guard let self, let client = self.client() else { return }
                    if self.candidatesExpanded { self.engine.choose(index) }
                    else { self.chooseCompactCandidate(index) }
                    self.deliver(client)
                }, onPage: { [weak self] key in
                    guard let self, let client = self.client() else { return }
                    self.engine.key(key)
                    self.deliver(client)
                }, onToggle: { [weak self] in
                    guard let self, let client = self.client() else { return }
                    self.setCandidatesExpanded(!self.candidatesExpanded)
                    self.deliver(client)
                }, onAppearanceChange: { [weak self] in
                    guard let self, let client = self.client() else { return }
                    self.deliver(client)
                })
            let hosting = NSHostingView(rootView: view)
            let window = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel],
                                 backing: .buffered, defer: false)
            window.level = .popUpMenu
            window.isOpaque = false
            window.backgroundColor = .clear
            window.hasShadow = true
            window.contentView = hosting
            catHosting = hosting
            panel = window
        }
        guard let panel, let catHosting else { return }
        catHosting.rootView.cats = Preferences.shared.catGlassSkin
        catHosting.rootView.canExpand = canExpandCandidates
        let rect = anchor ?? NSRect(origin: NSEvent.mouseLocation, size: .zero)
        let point = NSPoint(x: rect.midX, y: rect.midY)
        guard let screen = NSScreen.screens.first(where: { $0.frame.contains(point) }) ?? NSScreen.main else { return }
        catHosting.rootView.maxPanelHeight = candidatePanelAvailableHeight(anchor: rect, bounds: screen.visibleFrame)
        catHosting.layoutSubtreeIfNeeded()
        let frame = candidateFrame(size: catHosting.fittingSize, anchor: rect, bounds: screen.visibleFrame)
        presentPanel(panel, frame: frame, anchor: rect)
    }

    // Shared by both skins and input modes. Retarget one native animation, never
    // enqueue timers; caret jumps remain immediate and Reduce Motion is respected.
    private func presentPanel(_ panel: NSPanel, frame: NSRect, anchor: NSRect, layout: (Bool) -> Void = { _ in }) {
        let nearby = lastPanelAnchor.map { abs($0.minX - anchor.minX) < 8 && abs($0.minY - anchor.minY) < 8 } ?? false
        lastPanelAnchor = anchor
        let animate = panel.isVisible && nearby && panel.frame.size != frame.size && !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        NSAnimationContext.runAnimationGroup { context in
            context.duration = animate ? 0.18 : 0
            context.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            layout(animate)
            if animate { panel.animator().setFrame(frame, display: true) }
            else if panel.frame != frame { panel.setFrame(frame, display: true) }
        }
        if !panel.isVisible {
            let motion = !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            panel.alphaValue = motion ? 0 : 1
            panel.orderFrontRegardless()
            if motion {
                NSAnimationContext.runAnimationGroup { context in
                    context.duration = 0.12
                    panel.animator().alphaValue = 1
                }
            }
        }
    }

    override func deactivateServer(_ sender: Any!) {
        shiftTap.reset()
        if let client = self.client() {
            // Keep the selected text when focus changes instead of dropping a composition.
            if engine.isReady { engine.finishComposition(); deliver(client) }
        }
        ChoiceMemory.shared.flush()
        engine.clear(); engine.resetLearningContext()
        anchor = nil; composing = false; setCandidatesExpanded(false); panel?.orderOut(nil)
    }
    override func commitComposition(_ sender: Any!) {
        if let client = self.client(), engine.isReady { engine.finishComposition(); deliver(client) }
    }
    override func activateServer(_ sender: Any!) {
        shiftTap.reset()
        engine.resetLearningContext()
        engine.selectMode(Preferences.shared.inputMode)
        engine.modeChanged()
    }
    override func menu() -> NSMenu! {
        let menu = NSMenu()
        menu.addItem(withTitle: "Meowpuut 设置…", action: #selector(openSettings), keyEquivalent: "")
        menu.items.first?.target = self
        let item = menu.addItem(withTitle: Preferences.shared.simplified ? "切换为繁體" : "切换为简体", action: #selector(toggleScript), keyEquivalent: "")
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
        for (title, cats) in [("皮膚 · 雙貓玻璃", true), ("皮膚 · 簡潔玻璃", false)] {
            let choice = menu.addItem(withTitle: title, action: #selector(switchSkin(_:)), keyEquivalent: "")
            choice.target = self
            choice.representedObject = cats
            choice.state = Preferences.shared.catGlassSkin == cats ? .on : .off
        }
        for (title, values) in [("色調", CandidateTone.allCases.map { ($0.title, "tone:" + $0.rawValue) }),
                                ("玻璃強度", GlassStrength.allCases.map { ($0.title, "glass:" + $0.rawValue) }),
                                ("色系", CandidatePalette.allCases.map { ($0.title, "palette:" + $0.rawValue) })] {
            let parent = menu.addItem(withTitle: title, action: nil, keyEquivalent: "")
            let submenu = NSMenu(title: title)
            for (name, value) in values {
                let item = submenu.addItem(withTitle: name, action: #selector(switchAppearance(_:)), keyEquivalent: "")
                item.target = self; item.representedObject = value
                item.state = (value == "tone:" + Preferences.shared.candidateTone.rawValue || value == "glass:" + Preferences.shared.glassStrength.rawValue || value == "palette:" + Preferences.shared.candidatePalette.rawValue) ? .on : .off
            }
            parent.submenu = submenu
        }
        return menu
    }
    @objc private func switchAppearance(_ sender: Any) {
        guard let item = SystemInput.menuItem(from: sender), let value = item.representedObject as? String else { return }
        if value.hasPrefix("tone:"), let tone = CandidateTone(rawValue: String(value.dropFirst(5))) { Preferences.shared.candidateTone = tone }
        if value.hasPrefix("glass:"), let strength = GlassStrength(rawValue: String(value.dropFirst(6))) { Preferences.shared.glassStrength = strength }
        if value.hasPrefix("palette:"), let palette = CandidatePalette(rawValue: String(value.dropFirst(8))) { Preferences.shared.candidatePalette = palette }
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
    @objc private func switchSkin(_ sender: Any) {
        guard let item = SystemInput.menuItem(from: sender), let cats = item.representedObject as? Bool else { return }
        Preferences.shared.catGlassSkin = cats
        if let client = self.client() { deliver(client) }
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
            window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1040, height: 710), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
            window?.title = Preferences.enginePreview ? "Meowpuut 引擎試打" : "Meowpuut"
            window?.contentView = NSHostingView(rootView: Studio()); window?.center()
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
        runIndependentSmoke()
    }

}
let app = NSApplication.shared
let delegate = Application()
app.delegate = delegate
if !Preferences.testing, !Preferences.enginePreview, !delegate.startInputServer() { exit(1) }
app.run()
