import UIKit
import SwiftUI

// UIKit only plays the system click when an enabling input view is visible and
// the user has enabled keyboard clicks in system settings.
private final class KeyboardFeedbackInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

private final class KeyboardContext: ObservableObject {
    @Published var returnTitle = "換行"
    @Published var appearanceRevision = 0
}

final class KeyboardViewController: UIInputViewController {
    private let engine = Engine(Preferences.shared.keyboardBackend, mode: Preferences.shared.inputMode)
    private let keyboardContext = KeyboardContext()
    private var documentID: UUID?
    private var delivering = false
    private var compositionDelivery = CompositionDelivery()
    private var keyboardHeight: NSLayoutConstraint?
    private var visibleRowCount = 4
    private var candidateBarHeight: CGFloat = KeyboardLayoutMetrics.candidateHeight
    override func loadView() {
        let keyboard = KeyboardFeedbackInputView(frame: .zero, inputViewStyle: .keyboard)
        inputView = keyboard
        view = keyboard
    }
    override func viewDidLoad() {
        super.viewDidLoad()
        let root = KeyboardPanel(engine: engine, context: keyboardContext, key: { [weak self] code, modifiers in
            guard let self else { return }
            if code == 32, modifiers == 0, !self.engine.english, self.engine.inputMode == .zhuyin,
               let last = self.engine.snapshot.input.last, " 6347".contains(last), !self.engine.snapshot.candidates.isEmpty {
                self.engine.finishComposition(); self.deliver(); return
            }
            let handled = self.engine.key(code, modifiers: modifiers)
            self.deliver()
            if !handled && code == 0xff0d { self.textDocumentProxy.insertText("\n") }
            if !handled && code == 32 { self.textDocumentProxy.insertText(" ") }
            if !handled, code > 32, code < 0xff00, let scalar = UnicodeScalar(UInt32(code)) { self.textDocumentProxy.insertText(String(scalar)) }
        }, insertSymbol: { [weak self] text in
            guard let self else { return }
            // Finish composition and insert a symbol in one host transaction.
            self.engine.finishComposition(); self.deliver(extraCommit: text)
        }, choose: { [weak self] index in
            guard let self else { return }
            if self.engine.snapshot.candidates.indices.contains(index) { self.engine.choose(index) }
            else if index == self.engine.snapshot.candidates.count, self.engine.snapshot.compactJapanese != nil {
                self.engine.chooseCompactJapanese()
            }
            self.deliver()
        },
        erase: { [weak self] in self?.erase() },
        beginCursor: { [weak self] in self?.engine.finishComposition(); self?.deliver() },
        moveCursor: { [weak self] offset in self?.textDocumentProxy.adjustTextPosition(byCharacterOffset: offset) },
        keyboardController: { [weak self] in self })
        let hosting = UIHostingController(rootView: root)
        addChild(hosting)
        hosting.view.backgroundColor = .clear
        hosting.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hosting.view)
        NSLayoutConstraint.activate([
            hosting.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            hosting.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            hosting.view.topAnchor.constraint(equalTo: view.topAnchor),
            hosting.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        hosting.didMove(toParent: self)
        let height = view.heightAnchor.constraint(equalToConstant: 280)
        height.priority = .defaultHigh
        height.isActive = true
        keyboardHeight = height
        updateContext()
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let keyboardHeight, view.bounds.width > 0 else { return }
        let screenHeight = view.window?.windowScene?.screen.bounds.height ?? UIScreen.main.bounds.height
        let height = KeyboardLayoutMetrics.preferredHeight(keyboardWidth: view.bounds.width, screenHeight: screenHeight, rowCount: visibleRowCount, candidateBarHeight: candidateBarHeight)
        if abs(keyboardHeight.constant - height) >= 1 { keyboardHeight.constant = height }
    }
    func setVisibleRowCount(_ count: Int) {
        guard visibleRowCount != count else { return }
        visibleRowCount = count
        view.setNeedsLayout()
    }
    func setCandidateBarHeight(_ height: CGFloat) {
        guard abs(candidateBarHeight - height) >= 1 else { return }
        candidateBarHeight = height
        view.setNeedsLayout()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // Recreate only the decorative layer after the extension reappears.
        // The current composition and candidate navigation stay untouched.
        keyboardContext.appearanceRevision &+= 1
        Preferences.shared.applySharedSettings()
        if engine.inputMode != Preferences.shared.inputMode {
            engine.finishComposition(); deliver()
            engine.selectMode(Preferences.shared.inputMode)
        }
        engine.modeChanged()
        updateContext()
    }
    private func deliver(extraCommit: String = "") {
        guard !delivering else { return }
        delivering = true
        defer { delivering = false }
        primaryLanguage = engine.english ? "en-US" : engine.inputMode == .japanese ? "ja-JP" : Preferences.shared.simplified ? "zh-Hans" : "zh-Hant"
        let commit = engine.takeCommit() + extraCommit
        for action in compositionDelivery.actions(commit: commit, preedit: engine.snapshot.preedit) {
            switch action {
            case .mark(let text):
                textDocumentProxy.setMarkedText(text, selectedRange: NSRange(location: text.utf16.count, length: 0))
            case .unmark:
                textDocumentProxy.unmarkText()
            case .insert(let text):
                textDocumentProxy.insertText(text)
            }
        }
    }
    private func erase() {
        if engine.snapshot.preedit.isEmpty { textDocumentProxy.deleteBackward() }
        else { engine.key(0xff08); deliver() }
    }
    override func textDidChange(_ textInput: UITextInput?) {
        guard !delivering else { return }
        updateContext()
    }
    private func updateContext() {
        if let id = MPKeyboardDocumentIdentifier(textDocumentProxy) {
            let changedDocument = documentID.map { $0 != id } ?? false
            if changedDocument {
                engine.clear(); compositionDelivery.reset()
            }
            // The proxy may acquire its first identifier after the first key.
            // That is attachment to the same document, not a document switch.
            if changedDocument || (documentID == nil && !compositionDelivery.hasMarkedText && engine.snapshot.preedit.isEmpty) {
                switch textDocumentProxy.keyboardType ?? .default {
                case .URL, .emailAddress, .asciiCapable, .asciiCapableNumberPad: engine.english = true
                default: engine.english = false
                }
            }
            documentID = id
        }
        switch textDocumentProxy.returnKeyType ?? .default {
        case .search: keyboardContext.returnTitle = "搜尋"
        case .send: keyboardContext.returnTitle = "傳送"
        case .go: keyboardContext.returnTitle = "前往"
        case .done: keyboardContext.returnTitle = "完成"
        case .next: keyboardContext.returnTitle = "下一步"
        default: keyboardContext.returnTitle = "換行"
        }
    }
    override func viewWillDisappear(_ animated: Bool) {
        if let id = MPKeyboardDocumentIdentifier(textDocumentProxy), documentID == id { engine.finishComposition(); deliver() }
        else { engine.clear(); compositionDelivery.reset() }
        super.viewWillDisappear(animated)
    }
}

private struct InputModeButton: UIViewRepresentable {
    var controller: () -> UIInputViewController?
    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: "globe"), for: .normal)
        button.tintColor = .label
        button.accessibilityLabel = "切換鍵盤"
        if let controller = controller() {
            button.addTarget(controller, action: #selector(UIInputViewController.handleInputModeList(from:with:)), for: .allTouchEvents)
        }
        return button
    }
    func updateUIView(_ view: UIButton, context: Context) {}
}

private struct KeyboardPanel: View {
    @ObservedObject var engine: Engine
    @ObservedObject var context: KeyboardContext
    @ObservedObject var preferences = Preferences.shared
    var key: (Int32, Int32) -> Void
    var insertSymbol: (String) -> Void
    var choose: (Int) -> Void
    var erase: () -> Void
    var beginCursor: () -> Void
    var moveCursor: (Int) -> Void
    var keyboardController: () -> UIInputViewController?
    @State private var expanded = false
    @State private var capitalization = CapitalizationState()
    @State private var symbolsPage = 0
    @State private var availableHeight: CGFloat = 312
    @State private var availableWidth: CGFloat = 320
    @State private var candidatePage: Int? = 0
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    private var scheme: ColorScheme {
        switch preferences.candidateTone {
        case .system: systemScheme
        case .light: .light
        case .dark: .dark
        }
    }
    private var accent: Color { preferences.candidatePalette.color(dark: scheme == .dark) }
    private let zhuyin: [[(String, String)]] = [
        [("ㄅ","1"),("ㄉ","2"),("ˇ","3"),("ˋ","4"),("ㄓ","5"),("ˊ","6"),("˙","7"),("ㄚ","8"),("ㄞ","9"),("ㄢ","0"),("ㄦ","-")],
        [("ㄆ","q"),("ㄊ","w"),("ㄍ","e"),("ㄐ","r"),("ㄔ","t"),("ㄗ","y"),("ㄧ","u"),("ㄛ","i"),("ㄟ","o"),("ㄣ","p")],
        [("ㄇ","a"),("ㄋ","s"),("ㄎ","d"),("ㄑ","f"),("ㄕ","g"),("ㄘ","h"),("ㄨ","j"),("ㄜ","k"),("ㄠ","l"),("ㄤ",";")],
        [("ㄈ","z"),("ㄌ","x"),("ㄏ","c"),("ㄒ","v"),("ㄖ","b"),("ㄙ","n"),("ㄩ","m"),("ㄝ",","),("ㄡ","."),("ㄥ","/")]
    ]
    var body: some View {
      GeometryReader { geometry in
        let keyPitch = max(0, geometry.size.width - 10) / 10
        let sideKeyWidth = max(0, keyPitch * 1.5 - 4)
        VStack(spacing: 5) {
                HStack(spacing: 0) {
                  GeometryReader { candidateArea in
                    let initial = candidatePages(width: candidateArea.size.width)
                    let pageWidth = max(1, candidateArea.size.width - (initial.count > 1 ? 12 : 0))
                    let pages = candidatePages(width: pageWidth)
                    HStack(spacing: 0) {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 0) {
                                ForEach(pages.indices, id: \.self) { page in
                                    HStack(spacing: 4) {
                                        ForEach(pages[page], id: \.index) { item in
                                            candidateButton(item.index)
                                                .frame(width: item.width)
                                                .clipped()
                                        }
                                        Spacer(minLength: 0)
                                    }
                                    .frame(width: pageWidth, height: candidateBarHeight, alignment: .leading)
                                    .id(page)
                                }
                            }.scrollTargetLayout()
                        }
                        .frame(width: pageWidth, height: candidateBarHeight)
                        .scrollTargetBehavior(.paging)
                        .scrollPosition(id: $candidatePage)
                        if pages.count > 1 {
                            Image(systemName: (candidatePage ?? 0) < pages.count - 1 ? "chevron.right" : "chevron.left")
                                .font(.system(size: 9, weight: .semibold)).foregroundStyle(.secondary)
                                .frame(width: 12, height: candidateBarHeight).allowsHitTesting(false).accessibilityHidden(true)
                            }
                        }
                  }.frame(height: candidateBarHeight)
                    Button {
                        expanded.toggle()
                        if expanded { engine.browse(phrases: false) }
                        else if engine.browsing { engine.refresh() }
                    } label: { Image(systemName: expanded ? "chevron.up" : "chevron.down").frame(width: 44, height: 44) }
                        .disabled(engine.snapshot.candidates.isEmpty && !expanded).accessibilityLabel(expanded ? "收合候選詞" : "展開候選詞")
                }
                // A taller plain-glass capsule has larger curved ends. Inset
                // content so those ends cannot clip a wrapped first or last row.
                .padding(.horizontal, preferences.catGlassSkin ? 0 : max(0, (candidateBarHeight - 44) / 2))
                .frame(height: candidateBarHeight)
                .modifier(KeyboardCandidateGlass(cats: preferences.catGlassSkin, dark: scheme == .dark,
                                                  strength: preferences.glassStrength, tint: accent,
                                                  appearanceRevision: context.appearanceRevision))
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: expanded)
                if expanded { expandedCandidates.transition(.opacity) }
                else if symbolsPage > 0 || engine.english || engine.inputMode != .zhuyin {
                    ForEach(englishRows, id: \.self) { row in
                      GlassEffectContainer(spacing: 0) {
                        HStack(spacing: 0) {
                            if row == "zxcvbnm" { keycap(capitalization.mode == .locked ? "⇪" : "⇧", width: sideKeyWidth, role: .utility, rowKey: true) { capitalization.tapShift() } }
                            if symbolsPage > 0 && row == englishRows.last { keycap(symbolsPage == 1 ? "#+=" : "123", rowKey: true) { symbolsPage = symbolsPage == 1 ? 2 : 1 } }
                            ForEach(Array(row), id: \.self) { character in
                                let text = symbolsPage > 0
                                    ? KeyboardSymbols.text(character, chinese: !engine.english && engine.inputMode != .japanese)
                                    : (engine.english && capitalization.mode != .lowercase ? String(character).uppercased() : String(character))
                                keycap(text, rowKey: true) {
                                    if symbolsPage > 0 { insertSymbol(text) }
                                    else {
                                        let output = capitalization.consumeLetter(english: engine.english) ? String(character).uppercased() : String(character)
                                        key(Int32(output.unicodeScalars.first!.value), 0)
                                    }
                                }
                            }
                            if row == englishRows.last { deleteKey(height: rowHeight, rowKey: true, width: row == "zxcvbnm" ? sideKeyWidth : nil) }
                        }
                        .padding(.horizontal, symbolsPage == 0 && row == "asdfghjkl" ? keyPitch / 2 : 0)
                      }
                    }
                } else {
                    ForEach(zhuyin.indices, id: \.self) { row in
                      GlassEffectContainer(spacing: 0) {
                        HStack(spacing: 0) {
                            ForEach(zhuyin[row], id: \.1) { symbol, raw in keycap(symbol, rowKey: true) { key(Int32(raw.utf8.first!), 0) } }
                            if row == zhuyin.count - 1 { deleteKey(height: rowHeight, rowKey: true) }
                        }
                        .padding(.horizontal, (row == 1 || row == 2) ? max(0, geometry.size.width - 10) / 22 : 0)
                      }
                    }
                }
            GlassEffectContainer(spacing: 0) {
              HStack(spacing: 4) {
                if keyboardController()?.needsInputModeSwitchKey == true { InputModeButton(controller: keyboardController).frame(width: 44, height: 44) }
                keycap(engine.english ? (engine.inputMode == .pinyin ? "拼" : engine.inputMode == .japanese ? "あ" : "ㄅ") : "ABC", width: 44, role: .utility) { key(Engine.toggleEnglishKey, 0) }
                keycap(symbolsPage == 0 ? "符號" : (!engine.english && engine.inputMode == .zhuyin ? "注音" : "字母"), width: 44, role: .utility) {
                    symbolsPage = symbolsPage == 0 ? 1 : 0
                }
                SpaceCursorButton(title: spaceTitle, onTap: { key(32, 0) }, onBeginCursor: beginCursor,
                                  onMove: moveCursor, tintColor: scheme == .dark ? .white : .black)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(keySurface(.character), in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).fill(accent.opacity(reduceTransparency ? 0.02 : 0.065)).allowsHitTesting(false))
                    .glassEffect(keyGlass, in: .rect(cornerRadius: 8))
                if expanded { deleteKey() }
                keycap(engine.snapshot.preedit.isEmpty ? context.returnTitle : "確認", width: 72, role: .utility) { key(0xff0d, 0) }
              }
            }
            if !engine.error.isEmpty { Text(engine.error).font(.caption).lineLimit(1).foregroundStyle(.red).frame(height: KeyboardLayoutMetrics.errorHeight) }
        }.animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: expanded)
            .padding(.horizontal, 5).padding(.vertical, 5)
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .top)
            .background {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(colors: scheme == .dark
                        ? [Color(white: 0.18), Color(white: 0.11)]
                        : [Color(white: 0.91), Color(white: 0.81)],
                        startPoint: .topLeading, endPoint: .bottomTrailing))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(LinearGradient(colors: [accent.opacity(reduceTransparency ? 0.025 : 0.10), .clear],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                    }
                    .glassEffect(.regular.tint(accent.opacity(0.04)), in: .rect(cornerRadius: 18))
            }.tint(accent)
            .environment(\.colorScheme, scheme)
            .onChange(of: engine.snapshot.preedit) { _, text in if text.isEmpty { expanded = false } }
            .onChange(of: engine.snapshot.input) { _, text in
                // Editing while expanded refreshes the engine and leaves browse mode.
                // Rebuild the filtered pool for the remaining composition.
                if expanded, !text.isEmpty, !engine.browsing {
                    engine.browse(phrases: engine.phrasesOnly)
                }
            }
            .onChange(of: engine.snapshot.candidates) { _, _ in candidatePage = 0 }
            .onAppear {
                availableHeight = geometry.size.height
                availableWidth = geometry.size.width
                (keyboardController() as? KeyboardViewController)?.setVisibleRowCount(visibleRowCount)
                (keyboardController() as? KeyboardViewController)?.setCandidateBarHeight(candidateBarHeight)
            }
            .onChange(of: geometry.size.height) { _, height in availableHeight = height }
            .onChange(of: geometry.size.width) { _, width in availableWidth = width }
            .onChange(of: visibleRowCount) { _, count in
                (keyboardController() as? KeyboardViewController)?.setVisibleRowCount(count)
            }
            .onChange(of: candidateBarHeight) { _, height in
                (keyboardController() as? KeyboardViewController)?.setCandidateBarHeight(height)
            }
      }.coordinateSpace(name: KeyPreviewGeometry.coordinateSpace)
    }
    private var englishRows: [String] {
        switch symbolsPage {
        case 1: return ["1234567890", "-/:;()$&@", ".,?!'\"", "[]{}#%_="]
        case 2: return ["1234567890", "^*+|\\~`<>", "€£¥•…", ".,?!'\""]
        default: return ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
        }
    }
    private var spaceTitle: String {
        if engine.snapshot.preedit.isEmpty { return "空格" }
        if engine.snapshot.preedit.hasPrefix("/") { return "選詞" }
        if engine.inputMode == .pinyin { return engine.snapshot.candidates.isEmpty ? "原文＋空格" : "選詞" }
        if engine.inputMode == .japanese { return "選詞" }
        if let last = engine.snapshot.input.last, " 6347".contains(last) { return "選詞" }
        return "一聲"
    }
    private func candidateText(_ index: Int) -> String {
        if engine.snapshot.candidates.indices.contains(index) { return engine.snapshot.candidates[index] }
        return engine.snapshot.compactJapanese?.text ?? ""
    }
    private func isJapaneseCandidate(_ index: Int) -> Bool {
        index >= engine.snapshot.candidates.count || engine.snapshot.japaneseCandidates.contains(candidateText(index))
    }
    private func candidateWidth(_ index: Int) -> CGFloat {
        let text = candidateText(index) as NSString
        let font = UIFont.preferredFont(forTextStyle: .title3)
        let marker: CGFloat = isJapaneseCandidate(index) ? 13 : 0
        return max(44, ceil(text.size(withAttributes: [.font: font]).width) + 18 + marker)
    }
    private func candidatePages(width: CGFloat) -> [[KeyboardLayoutMetrics.CandidateItem]] {
        var widths = engine.snapshot.candidates.indices.map(candidateWidth)
        if expanded {
            return KeyboardLayoutMetrics.candidatePages(widths: widths, availableWidth: width).map { page in
                page.map { KeyboardLayoutMetrics.CandidateItem(index: $0, width: min(width, widths[$0])) }
            }
        }
        let japanese = Set(engine.snapshot.candidates.indices.filter {
            engine.snapshot.japaneseCandidates.contains(engine.snapshot.candidates[$0])
        })
        var japaneseIndices = japanese
        if engine.snapshot.compactJapanese != nil {
            japaneseIndices.insert(widths.count)
            widths.append(candidateWidth(widths.count))
        }
        return KeyboardLayoutMetrics.compactCandidatePages(widths: widths, japaneseIndices: japaneseIndices, availableWidth: width)
    }
    private var candidateBarHeight: CGFloat {
        if expanded { return KeyboardLayoutMetrics.candidateHeight }
        // Measure with the same usable width as the compact candidate row.
        let catInsets: CGFloat = preferences.catGlassSkin ? 70 : 8
        let width = max(44, availableWidth - 10 - catInsets - 44 - 12)
        let font = UIFont.preferredFont(forTextStyle: .title3)
        let heights = candidatePages(width: width).flatMap { $0 }.map { item -> CGFloat in
            let index = item.index
            let text = candidateText(index) as NSString
            let marker: CGFloat = isJapaneseCandidate(index) ? 13 : 0
            let bounds = text.boundingRect(with: CGSize(width: max(1, item.width - 18 - marker), height: .greatestFiniteMagnitude),
                                           options: [.usesLineFragmentOrigin, .usesFontLeading],
                                           attributes: [.font: font], context: nil)
            return ceil(bounds.height) + 14
        }
        // Keep the keyboard compact; very long text remains scrollable. On a
        // landscape phone the keyboard must also leave room for the host app.
        let screenHeight = keyboardController()?.view.window?.windowScene?.screen.bounds.height ?? UIScreen.main.bounds.height
        return KeyboardLayoutMetrics.candidateBarHeight(contentHeight: heights.max() ?? 44,
                                                        keyboardWidth: availableWidth, screenHeight: screenHeight,
                                                        rowCount: visibleRowCount)
    }
    private func selectCandidate(_ index: Int) {
        choose(index)
        expanded = false
    }
    private var selectionGlass: Glass {
        switch preferences.glassStrength {
        case .soft: .regular.tint(accent.opacity(0.10))
        case .balanced: .clear.tint(accent.opacity(0.16))
        case .vivid: .clear.tint(accent.opacity(0.20))
        }
    }
    private var candidateSelection: some View {
        let accessible = reduceTransparency || contrast == .increased
        let opacity: Double = switch preferences.glassStrength {
        case .soft: 0.34
        case .balanced: 0.29
        case .vivid: 0.24
        }
        return ZStack {
            if accessible {
                // A solid base keeps text legible when translucent material is disabled.
                Capsule().fill(scheme == .dark ? Color(white: 0.20) : Color(white: 0.94))
                Capsule().fill(accent.opacity(scheme == .dark ? 0.38 : 0.25))
            } else {
                // The system glass is isolated on an empty capsule behind the
                // label; candidate text remains outside its foreground effect.
                Capsule().fill(.clear)
                    .glassEffect(selectionGlass, in: .capsule)
                Capsule().fill(LinearGradient(stops: [
                    .init(color: accent.opacity(opacity * 0.55), location: 0),
                    .init(color: accent.opacity(opacity * 0.28), location: 0.55),
                    .init(color: accent.opacity(opacity * 0.16), location: 1)
                ], startPoint: .topLeading, endPoint: .bottomTrailing))
                Capsule().inset(by: 1).strokeBorder(LinearGradient(colors: [
                    .white.opacity(scheme == .dark ? 0.26 : 0.42), .clear,
                    .white.opacity(scheme == .dark ? 0.17 : 0.25)
                ], startPoint: .top, endPoint: .bottom), lineWidth: 0.65)
            }
            Capsule().strokeBorder(LinearGradient(colors: [.white.opacity(scheme == .dark ? 0.42 : 0.66),
                                                           .white.opacity(scheme == .dark ? 0.10 : 0.18),
                                                           .black.opacity(scheme == .dark ? 0.18 : 0.12)],
                                                  startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 0.7)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
    private func candidateButton(_ index: Int) -> some View {
        Button { selectCandidate(index) } label: {
            ScrollView(.vertical, showsIndicators: false) {
                candidateLabel(candidateText(index), japanese: isJapaneseCandidate(index)).font(.title3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 9).padding(.vertical, 5)
            }
                .scrollEdgeEffectHidden(true, for: .all)
                .frame(minWidth: 44, minHeight: 34, maxHeight: candidateBarHeight - 10)
                // The cat silhouette starts 8pt down across the middle of the
                // bar. Keep the capsule within that contour rather than let
                // the outer clip flatten its top edge; the 44pt touch area is
                // unchanged.
                .background { if index == engine.snapshot.highlighted { candidateSelection.padding(.vertical, 3).offset(y: 1.5) } }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }.buttonStyle(.plain)
            .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])
    }
    private var expandedCandidates: some View {
        VStack(spacing: 0) {
            HStack(spacing: 2) {
                expandedFilter("全部", phrases: false)
                expandedFilter("詞組", phrases: true)
                Spacer(minLength: 4)
                Button { key(0xff55, 0) } label: {
                    Image(systemName: "chevron.left").frame(width: 44, height: 44)
                }
                .disabled(engine.snapshot.page == 0)
                .accessibilityLabel("上一頁")
                Text("\(engine.snapshot.page + 1) / \(max(1, (engine.browseCount + 6) / 7))")
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    .fixedSize().accessibilityIdentifier("expanded.page")
                Button { key(0xff56, 0) } label: {
                    Image(systemName: "chevron.right").frame(width: 44, height: 44)
                }
                .disabled(engine.snapshot.lastPage)
                .accessibilityLabel("下一頁")
            }
            .font(.subheadline.weight(.medium))
            .buttonStyle(.plain)
            .padding(.horizontal, 4)

            Rectangle().fill(.primary.opacity(scheme == .dark ? 0.10 : 0.08))
                .frame(height: 0.5).padding(.horizontal, 12)

            GeometryReader { area in
                // Show whole rows at every keyboard height; scrolling reveals the rest.
                let rows = max(1, floor((area.size.height + 2) / 46))
                let listHeight = min(area.size.height, rows * 46 - 2)
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(expandedCandidateRows(width: area.size.width), id: \.self) { row in
                            HStack(spacing: 2) {
                                ForEach(row, id: \.self) { index in
                                    let text = engine.snapshot.candidates[index]
                                    Button { selectCandidate(index) } label: {
                                        candidateLabel(text)
                                            .font(.system(size: 18))
                                            .fixedSize(horizontal: false, vertical: true)
                                            .padding(.horizontal, 12).padding(.vertical, 8)
                                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])
                                    .background {
                                        if index == engine.snapshot.highlighted {
                                            candidateSelection.padding(.vertical, 3)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 5)
                }
                .scrollIndicators(.visible)
                .scrollEdgeEffectHidden(true, for: .all)
                // A new reading, filter or page starts at its first candidate.
                .id("\(engine.snapshot.input)|\(engine.phrasesOnly)|\(engine.snapshot.page)")
                .accessibilityIdentifier("expanded.candidates")
                .frame(height: listHeight, alignment: .top)
            }
            .overlay {
                if engine.browsing && engine.browseCount == 0 {
                    Text(engine.phrasesOnly ? "暫無詞組" : "暫無候選")
                        .font(.subheadline).foregroundStyle(.secondary)
                        .allowsHitTesting(false)
                }
            }

        }
        .background {
            RoundedRectangle(cornerRadius: 15, style: .continuous)
                .fill(scheme == .dark ? Color.white.opacity(0.045) : Color.white.opacity(0.22))
                .overlay {
                    RoundedRectangle(cornerRadius: 15, style: .continuous)
                        .strokeBorder(.white.opacity(scheme == .dark ? 0.12 : 0.38), lineWidth: 0.6)
                }
                .allowsHitTesting(false)
        }
        .frame(height: layout.expandedHeight)
    }
    // Give long candidates their own row while keeping short ones compact.
    private func expandedCandidateRows(width: CGFloat) -> [[Int]] {
        let columnWidth = max(0, (width - 12) / 2)
        let font = UIFont.systemFont(ofSize: 18)
        var rows: [[Int]] = []
        var pending: Int?
        for (index, text) in engine.snapshot.candidates.enumerated() {
            let marker: CGFloat = engine.snapshot.japaneseCandidates.contains(text) ? 13 : 0
            let needed = (text as NSString).size(withAttributes: [.font: font]).width + 24 + marker
            if needed > columnWidth {
                if let short = pending { rows.append([short]); pending = nil }
                rows.append([index])
            } else if let short = pending {
                rows.append([short, index]); pending = nil
            } else {
                pending = index
            }
        }
        if let short = pending { rows.append([short]) }
        return rows
    }
    private func expandedFilter(_ title: String, phrases: Bool) -> some View {
        Button { engine.browse(phrases: phrases) } label: {
            Text(title)
                .foregroundStyle(engine.phrasesOnly == phrases ? Color.primary : Color.secondary)
                .frame(minWidth: 48, minHeight: 44)
                .background {
                    if engine.phrasesOnly == phrases {
                        Capsule().fill(accent.opacity(scheme == .dark ? 0.24 : 0.17))
                            .padding(.vertical, 7)
                    }
                }
                .contentShape(Rectangle())
        }
        .accessibilityAddTraits(engine.phrasesOnly == phrases ? .isSelected : [])
    }
    private func candidateLabel(_ text: String, japanese: Bool? = nil) -> some View {
        HStack(spacing: 4) {
            Text(text)
            if japanese ?? engine.snapshot.japaneseCandidates.contains(text) {
                Image(systemName: "pawprint.fill").font(.system(size: 9)).rotationEffect(.degrees(-20))
                    .foregroundStyle(.secondary).accessibilityLabel("日語候選")
            }
        }
    }
    private enum KeyRole { case character, utility }
    private var layout: KeyboardLayoutMetrics {
        return KeyboardLayoutMetrics(availableHeight: availableHeight,
                                     rowCount: visibleRowCount,
                                     expanded: expanded, showsError: !engine.error.isEmpty,
                                     candidateBarHeight: candidateBarHeight)
    }
    private var visibleRowCount: Int {
        symbolsPage > 0 || engine.english || engine.inputMode != .zhuyin ? englishRows.count : zhuyin.count
    }
    private var rowHeight: CGFloat {
        layout.rowHeight
    }
    private func deleteKey(height: CGFloat = 44, rowKey: Bool = false, width: CGFloat? = nil) -> some View {
        let inset: CGFloat = rowKey ? 2 : 0
        return RepeatDeleteButton(action: erase, tintColor: scheme == .dark ? .white : .black)
            .frame(maxWidth: rowKey && width == nil ? .infinity : nil)
            .frame(width: width.map { $0 + 2 * inset } ?? (rowKey ? nil : 44), height: height)
            .background {
                RoundedRectangle(cornerRadius: 8)
                    .fill(keySurface(.utility))
                    .overlay(RoundedRectangle(cornerRadius: 8).fill(accent.opacity(0.045)))
                    .padding(.horizontal, inset)
                    .padding(.vertical, rowKey ? 2 : 0)
                    .allowsHitTesting(false)
            }
            // Apply glass to the whole key so it cannot cover the UIKit image.
            .glassEffect(keyGlass, in: .rect(cornerRadius: 8))
    }
    private var keyGlass: Glass {
        guard !reduceTransparency else { return .identity }
        switch preferences.glassStrength {
        case .soft: return .regular.tint(accent.opacity(0.025)).interactive()
        case .balanced: return .clear.tint(accent.opacity(0.04)).interactive()
        case .vivid: return .clear.tint(accent.opacity(0.075)).interactive()
        }
    }
    private func keySurface(_ role: KeyRole) -> Color {
        if scheme == .dark { return Color(white: role == .character ? 0.27 : 0.20) }
        return Color(white: role == .character ? 0.98 : 0.76)
    }
    private func keycap(_ text: String, width: CGFloat? = nil, role: KeyRole = .character, rowKey: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.system(size: text.count > 1 ? 15 : 21, weight: .regular)).lineLimit(1).minimumScaleFactor(0.8)
                .frame(maxWidth: width == nil ? .infinity : nil)
                .frame(height: rowKey ? max(0, rowHeight - 4) : 44)
                .frame(width: width)
                .background(keySurface(role), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).fill(accent.opacity(reduceTransparency ? 0.02 : 0.065)).allowsHitTesting(false))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(.white.opacity(scheme == .dark ? 0.06 : 0.48), lineWidth: 0.5))
                .shadow(color: .black.opacity(scheme == .dark ? 0.16 : 0.10), radius: 0.5, y: 1)
                .glassEffect(keyGlass, in: .rect(cornerRadius: 8))
                .frame(height: rowKey ? rowHeight : 44)
                .padding(.horizontal, rowKey ? 2 : 0)
                .contentShape(Rectangle())
        }.buttonStyle(KeyboardCharacterPreviewStyle(text: text, keyboardWidth: availableWidth,
                                                    tint: accent, enabled: rowKey && role == .character))
            .accessibilityLabel(text)
    }
}
