import AppKit
import SwiftUI
import InputMethodKit

private func candidateFrame(size: NSSize, anchor: NSRect, bounds: NSRect) -> NSRect {
    let gap: CGFloat = 6
    let below = anchor.minY - size.height - gap
    let above = anchor.maxY + gap
    let y = below >= bounds.minY || above + size.height > bounds.maxY ? below : above
    return NSRect(x: max(bounds.minX, min(anchor.minX, bounds.maxX - size.width)),
                  y: max(bounds.minY, min(y, bounds.maxY - size.height)),
                  width: size.width, height: size.height)
}

@objc(MeowpuutInputController)
class MeowpuutInputController: IMKInputController {
    private let engine = Engine(Preferences.shared.keyboardBackend, mode: Preferences.shared.inputMode)
    private var shiftTap = ShiftTapTracker()
    private var panel: NSPanel?
    private var catHosting: NSHostingView<CatCandidateView>?
    private var displayedCatSkin = false
    private var candidateGlass: NSGlassEffectView?
    private var toggleGlass: NSGlassEffectView?
    private var anchor: NSRect?
    private var composing = false
    private var lastPanelAnchor: NSRect?
    private var candidatesExpanded = false {
        didSet {
            if let catHosting { catHosting.rootView.expanded = candidatesExpanded }
            guard candidatesExpanded != oldValue,
                  let hosting = candidateGlass?.contentView as? NSHostingView<CandidateStrip> else { return }
            hosting.rootView.expanded = candidatesExpanded
            updateToggle()
        }
    }

    private var canExpandCandidates: Bool {
        let snapshot = engine.snapshot
        guard !snapshot.candidates.isEmpty else { return false }
        let layout = CollapsedCandidateLayout(snapshot)
        return layout.truncated || layout.indices.count < snapshot.candidates.count ||
            !snapshot.lastPage || snapshot.page > 0
    }

    private func setCandidatesExpanded(_ expanded: Bool) {
        candidatesExpanded = expanded
        if !expanded && !collapsedCandidateIndices(engine.snapshot).contains(engine.snapshot.highlighted) {
            engine.snapshot.highlighted = 0
        }
    }

    private func updateToggle() {
        guard let button = toggleGlass?.contentView as? NSButton else { return }
        let title = candidatesExpanded ? "收起候選" : "展開候選"
        let symbol = candidatesExpanded ? "chevron.up" : "chevron.down"
        button.image = NSImage(systemSymbolName: symbol, accessibilityDescription: title)?
            .withSymbolConfiguration(.init(pointSize: 10, weight: .semibold))
        button.toolTip = candidatesExpanded ? "收起候選（Esc）" : "展開候選（↓）"
        button.setAccessibilityLabel(title)
    }

    @objc private func toggleCandidates(_ sender: NSButton) {
        guard let client = self.client() else { return }
        setCandidatesExpanded(!candidatesExpanded)
        deliver(client)
    }

    private func caret(_ client: IMKTextInput) -> NSRect? {
        // This index is relative to the marked text. Zero also works before marking.
        var rect = NSRect.zero
        _ = client.attributes(forCharacterIndex: 0, lineHeightRectangle: &rect)
        guard rect.width.isFinite, rect.width >= 0, rect.height.isFinite, rect.height > 0,
              NSScreen.screens.contains(where: { $0.frame.contains(NSPoint(x: rect.midX, y: rect.midY)) })
        else { return nil }
        return rect
    }

    override func recognizedEvents(_ sender: Any!) -> Int {
        Int(NSEvent.EventTypeMask.keyDown.rawValue | NSEvent.EventTypeMask.flagsChanged.rawValue)
    }
    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event, let client = sender as? IMKTextInput else { return false }
        let shiftOnly = shiftTap.update(keyCode: event.keyCode, modifierEvent: event.type == .flagsChanged,
            shift: event.modifierFlags.contains(.shift),
            other: !event.modifierFlags.intersection([.command, .control, .option, .capsLock]).isEmpty)
        if event.type == .flagsChanged {
            if shiftOnly, engine.inputMode == .pinyin {
                engine.key(32, modifiers: 1)
                setCandidatesExpanded(false)
                deliver(client)
                return true
            }
            return false
        }
        guard event.type == .keyDown else { return false }
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
        guard let (key, modifiers) = inputKey(event) else { return false }
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
        let previousInput = engine.snapshot.input
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
    private func deliver(_ client: IMKTextInput) {
        let commit = engine.takeCommit()
        if !commit.isEmpty { client.insertText(commit, replacementRange: NSRange(location: NSNotFound, length: 0)) }
        let preedit = engine.snapshot.preedit
        if !commit.isEmpty || preedit.isEmpty { setCandidatesExpanded(false) }
        if !preedit.isEmpty && (!composing || !commit.isEmpty) { anchor = caret(client) }
        composing = !preedit.isEmpty
        client.setMarkedText(preedit, selectionRange: NSRange(location: (preedit as NSString).length, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        guard composing else {
            anchor = nil
            panel?.orderOut(nil)
            return
        }
        anchor = caret(client) ?? anchor
        let catSkin = Preferences.shared.catGlassSkin
        if panel != nil && displayedCatSkin != catSkin {
            panel?.orderOut(nil)
            panel = nil
            catHosting = nil
            candidateGlass = nil
            toggleGlass = nil
        }
        displayedCatSkin = catSkin
        if catSkin {
            deliverCatPanel(client)
            return
        }
        if panel == nil {
            panel = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            panel?.level = .popUpMenu; panel?.isOpaque = false; panel?.backgroundColor = .clear; panel?.hasShadow = true
            let glass = NSGlassEffectView()
            glass.style = .clear
            glass.cornerRadius = 13
            glass.effectIsInteractive = true
            glass.contentView = NSHostingView(rootView: CandidateStrip(engine: engine, onChoose: { [weak self] index in
                guard let self, let client = self.client() else { return }
                self.engine.choose(index)
                self.deliver(client)
            }, onPage: { [weak self] key in
                guard let self, let client = self.client() else { return }
                self.engine.key(key)
                self.deliver(client)
            }, compact: true, expanded: candidatesExpanded))
            candidateGlass = glass

            let lens = NSGlassEffectView()
            lens.style = .clear
            lens.cornerRadius = 14.5
            lens.effectIsInteractive = true
            let button = NSButton(title: "", target: self, action: #selector(toggleCandidates(_:)))
            button.isBordered = false
            button.imagePosition = .imageOnly
            lens.contentView = button
            toggleGlass = lens
            updateToggle()

            let surfaces = NSView()
            surfaces.addSubview(glass)
            surfaces.addSubview(lens)
            let container = NSGlassEffectContainerView()
            container.spacing = 7
            container.contentView = surfaces
            panel?.contentView = container
        }
        guard let panel, let glass = candidateGlass, let lens = toggleGlass,
              let content = glass.contentView else { return }
        content.layoutSubtreeIfNeeded()
        let size = content.fittingSize
        glass.cornerRadius = candidatesExpanded ? 15 : size.height / 2
        let showLens = candidatesExpanded || canExpandCandidates
        let lensSide: CGFloat = 29
        let gap: CGFloat = 4
        let panelSize = NSSize(width: size.width + (showLens ? gap + lensSide : 0), height: size.height)
        let pointer = NSEvent.mouseLocation
        let rect = anchor ?? NSRect(origin: pointer, size: .zero)
        let point = NSPoint(x: rect.midX, y: rect.midY)
        guard let screen = NSScreen.screens.first(where: { $0.frame.contains(point) }) ?? NSScreen.main else { return }
        let frame = candidateFrame(size: panelSize, anchor: rect, bounds: screen.visibleFrame)
        presentPanel(panel, frame: frame, anchor: rect) { animated in
            let glassFrame = NSRect(origin: .zero, size: size)
            let lensFrame = NSRect(x: size.width + gap, y: size.height - lensSide - 2, width: lensSide, height: lensSide)
            if animated { glass.animator().frame = glassFrame; lens.animator().frame = lensFrame }
            else { glass.frame = glassFrame; lens.frame = lensFrame }
            lens.isHidden = !showLens
        }
    }

    private func deliverCatPanel(_ client: IMKTextInput) {
        if catHosting == nil {
            let view = CatCandidateView(engine: engine, expanded: candidatesExpanded, canExpand: canExpandCandidates,
                onChoose: { [weak self] index in
                    guard let self, let client = self.client() else { return }
                    self.engine.choose(index)
                    self.deliver(client)
                }, onPage: { [weak self] key in
                    guard let self, let client = self.client() else { return }
                    self.engine.key(key)
                    self.deliver(client)
                }, onToggle: { [weak self] in
                    guard let self, let client = self.client() else { return }
                    self.setCandidatesExpanded(!self.candidatesExpanded)
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
        catHosting.rootView.canExpand = canExpandCandidates
        catHosting.layoutSubtreeIfNeeded()
        let rect = anchor ?? NSRect(origin: NSEvent.mouseLocation, size: .zero)
        let point = NSPoint(x: rect.midX, y: rect.midY)
        guard let screen = NSScreen.screens.first(where: { $0.frame.contains(point) }) ?? NSScreen.main else { return }
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
        engine.clear(); anchor = nil; composing = false; setCandidatesExpanded(false); panel?.orderOut(nil)
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
        menu.addItem(.separator())
        for (title, cats) in [("皮膚 · 雙貓玻璃", true), ("皮膚 · 簡潔玻璃", false)] {
            let choice = menu.addItem(withTitle: title, action: #selector(switchSkin(_:)), keyEquivalent: "")
            choice.target = self
            choice.representedObject = cats
            choice.state = Preferences.shared.catGlassSkin == cats ? .on : .off
        }
        return menu
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
    @objc private func switchInputMode(_ item: NSMenuItem) {
        guard let name = item.representedObject as? String, let mode = InputMode(rawValue: name),
              let client = self.client() else { return }
        changeInputMode(mode, client: client)
    }
    @objc private func switchSkin(_ item: NSMenuItem) {
        guard let cats = item.representedObject as? Bool else { return }
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
