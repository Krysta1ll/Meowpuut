import UIKit
import SwiftUI

private final class KeyboardContext: ObservableObject {
    @Published var returnTitle = "換行"
}

final class KeyboardViewController: UIInputViewController {
    private let engine = Engine(Preferences.shared.keyboardBackend, mode: Preferences.shared.inputMode)
    private let keyboardContext = KeyboardContext()
    private var documentID: UUID?
    private var delivering = false
    private var hasMarkedText = false
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
            self.engine.finishComposition(); self.deliver()
            self.textDocumentProxy.insertText(text)
        }, choose: { [weak self] index in self?.engine.choose(index); self?.deliver() },
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
        let height = view.heightAnchor.constraint(equalToConstant: 312)
        height.priority = .defaultHigh
        height.isActive = true
        updateContext()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        Preferences.shared.applySharedSettings()
        if engine.inputMode != Preferences.shared.inputMode {
            engine.finishComposition(); deliver()
            engine.selectMode(Preferences.shared.inputMode)
        }
        engine.modeChanged()
        updateContext()
    }
    private func deliver() {
        guard !delivering else { return }
        delivering = true
        defer { delivering = false }
        primaryLanguage = engine.english ? "en-US" : engine.inputMode == .japanese ? "ja-JP" : Preferences.shared.simplified ? "zh-Hans" : "zh-Hant"
        let commit = engine.takeCommit()
        if !commit.isEmpty {
            if hasMarkedText {
                textDocumentProxy.setMarkedText(commit, selectedRange: NSRange(location: commit.utf16.count, length: 0))
                textDocumentProxy.unmarkText()
                hasMarkedText = false
            } else { textDocumentProxy.insertText(commit) }
        }
        if !engine.snapshot.preedit.isEmpty {
            let text = engine.snapshot.preedit
            textDocumentProxy.setMarkedText(text, selectedRange: NSRange(location: text.utf16.count, length: 0))
            hasMarkedText = true
        } else if hasMarkedText {
            textDocumentProxy.setMarkedText("", selectedRange: NSRange(location: 0, length: 0))
            textDocumentProxy.unmarkText()
            hasMarkedText = false
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
                engine.clear(); hasMarkedText = false
            }
            // The proxy may acquire its first identifier after the first key.
            // That is attachment to the same document, not a document switch.
            if changedDocument || (documentID == nil && !hasMarkedText && engine.snapshot.preedit.isEmpty) {
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
        else { engine.clear(); hasMarkedText = false }
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
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
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
        VStack(spacing: 5) {
                HStack(spacing: 0) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            ForEach(expanded ? Array(engine.snapshot.candidates.indices) : chineseCandidateIndices, id: \.self) { index in
                                candidateButton(index)
                            }
                        }
                    }
                    if !expanded, let index = japaneseCandidateIndex {
                        candidateButton(index).frame(maxWidth: 125)
                    }
                    Button {
                        expanded.toggle()
                        if expanded { engine.browse(phrases: false) }
                        else if engine.browsing { engine.refresh() }
                    } label: { Image(systemName: expanded ? "chevron.up" : "chevron.down").frame(width: 44, height: 44) }
                        .disabled(engine.snapshot.candidates.isEmpty && !expanded).accessibilityLabel(expanded ? "收合候選詞" : "展開候選詞")
                }
                .frame(height: 44)
                .modifier(KeyboardCandidateGlass(cats: preferences.catGlassSkin, dark: scheme == .dark,
                                                  strength: preferences.glassStrength, tint: accent))
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: expanded)
                if expanded { expandedCandidates }
                else if symbolsPage > 0 || engine.english || engine.inputMode != .zhuyin {
                    ForEach(englishRows, id: \.self) { row in
                      GlassEffectContainer(spacing: 0) {
                        HStack(spacing: 0) {
                            if row == "zxcvbnm" { keycap(capitalization.mode == .locked ? "⇪" : "⇧", role: .utility, rowKey: true) { capitalization.tapShift() } }
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
                            if row == englishRows.last { deleteKey(height: rowHeight, rowKey: true) }
                        }
                        .padding(.horizontal, symbolsPage == 0 && row == "asdfghjkl" ? 18 : 0)
                      }
                    }
                } else {
                    ForEach(zhuyin.indices, id: \.self) { row in
                      GlassEffectContainer(spacing: 0) {
                        HStack(spacing: 0) {
                            ForEach(zhuyin[row], id: \.1) { symbol, raw in keycap(symbol, rowKey: true) { key(Int32(raw.utf8.first!), 0) } }
                            if row == zhuyin.count - 1 { deleteKey(height: rowHeight, rowKey: true) }
                        }
                        .padding(.horizontal, (row == 1 || row == 2) ? 18 : 0)
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
            if !engine.error.isEmpty { Text(engine.error).font(.caption).foregroundStyle(.red) }
        }.padding(.horizontal, 5).padding(.vertical, 5)
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
        if engine.inputMode == .pinyin { return "原文＋空格" }
        if engine.inputMode == .japanese { return "選詞" }
        if let last = engine.snapshot.input.last, " 6347".contains(last) { return "選詞" }
        return "一聲"
    }
    private var japaneseCandidateIndex: Int? {
        engine.snapshot.compactIndices.first { engine.snapshot.japaneseCandidates.contains(engine.snapshot.candidates[$0]) }
    }
    private var chineseCandidateIndices: [Int] {
        engine.snapshot.compactIndices.filter { !engine.snapshot.japaneseCandidates.contains(engine.snapshot.candidates[$0]) }
    }
    private func candidateButton(_ index: Int) -> some View {
        Button { choose(index) } label: {
            candidateLabel(engine.snapshot.candidates[index]).font(.title3).lineLimit(1)
                .padding(.horizontal, 9).frame(minWidth: 44, minHeight: 34)
                .background(index == engine.snapshot.highlighted ? accent.opacity(0.14) : .clear, in: Capsule())
                .frame(minHeight: 44)
        }.buttonStyle(.plain)
            .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])
    }
    private var expandedCandidates: some View {
        VStack(spacing: 4) {
            HStack {
                Button { engine.browse(phrases: false) } label: { Text("全部").frame(minWidth: 44, minHeight: 44) }
                Button { engine.browse(phrases: true) } label: { Text("詞組").frame(minWidth: 44, minHeight: 44) }
                Spacer()
                Button { key(0xff55, 0) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.disabled(engine.snapshot.page == 0)
                Text("\(engine.snapshot.page + 1)").font(.caption.monospacedDigit())
                Button { key(0xff56, 0) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }.disabled(engine.snapshot.lastPage)
            }
            ScrollView {
                LazyVGrid(columns: [.init(.flexible()), .init(.flexible())], spacing: 4) {
                    ForEach(Array(engine.snapshot.candidates.enumerated()), id: \.offset) { index, text in
                        Button { choose(index) } label: {
                            candidateLabel(text).frame(maxWidth: .infinity, minHeight: 44).lineLimit(2)
                        }
                        .buttonStyle(.plain)
                        .background(Color(UIColor.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 9))
                    }
                }
            }
        }.frame(height: 196)
    }
    private func candidateLabel(_ text: String) -> some View {
        HStack(spacing: 4) {
            Text(text)
            if engine.snapshot.japaneseCandidates.contains(text) {
                Image(systemName: "pawprint.fill").font(.system(size: 9)).rotationEffect(.degrees(-20))
                    .foregroundStyle(.secondary).accessibilityLabel("日語候選")
            }
        }
    }
    private enum KeyRole { case character, utility }
    private var rowHeight: CGFloat {
        (engine.english || engine.inputMode != .zhuyin) && symbolsPage == 0 ? 50 : 46
    }
    private func deleteKey(height: CGFloat = 44, rowKey: Bool = false) -> some View {
        RepeatDeleteButton(action: erase, tintColor: scheme == .dark ? .white : .black)
            .frame(width: 44, height: height)
            .background(keySurface(.utility), in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).fill(accent.opacity(0.045)).allowsHitTesting(false))
            .glassEffect(keyGlass, in: .rect(cornerRadius: 8))
            .padding(.horizontal, rowKey ? 2 : 0)
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
                .frame(height: rowKey ? rowHeight : 44)
                .frame(width: width)
                .background(keySurface(role), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).fill(accent.opacity(reduceTransparency ? 0.02 : 0.065)).allowsHitTesting(false))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(.white.opacity(scheme == .dark ? 0.06 : 0.48), lineWidth: 0.5))
                .shadow(color: .black.opacity(scheme == .dark ? 0.16 : 0.10), radius: 0.5, y: 1)
                .glassEffect(keyGlass, in: .rect(cornerRadius: 8))
                .padding(.horizontal, rowKey ? 2 : 0)
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(text)
    }
}
