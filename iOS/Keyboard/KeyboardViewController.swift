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
        }, choose: { [weak self] index in self?.engine.choose(index); self?.deliver() },
        erase: { [weak self] in self?.erase() }, refresh: { [weak self] in self?.engine.modeChanged(); self?.deliver() },
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
        let height = view.heightAnchor.constraint(equalToConstant: 360)
        height.priority = .defaultHigh
        height.isActive = true
        updateContext()
    }
    private func deliver() {
        guard !delivering else { return }
        delivering = true
        defer { delivering = false }
        primaryLanguage = engine.english ? "en-US" : Preferences.shared.simplified ? "zh-Hans" : "zh-Hant"
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
        let id = textDocumentProxy.documentIdentifier
        if documentID != id {
            engine.clear(); hasMarkedText = false
            switch textDocumentProxy.keyboardType ?? .default {
            case .URL, .emailAddress, .asciiCapable, .asciiCapableNumberPad: engine.english = true
            default: engine.english = false
            }
        }
        documentID = id
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
        if documentID == textDocumentProxy.documentIdentifier { engine.finishComposition(); deliver() }
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
    @ObservedObject var memory = ChoiceMemory.shared
    var key: (Int32, Int32) -> Void
    var choose: (Int) -> Void
    var erase: () -> Void
    var refresh: () -> Void
    var keyboardController: () -> UIInputViewController?
    @State private var expanded = false
    @State private var settings = false
    @State private var confirmingClear = false
    @State private var uppercase = false
    @State private var symbolsPage = 0
    private let accent = Brand.accent
    private let zhuyin: [[(String, String)]] = [
        [("ㄅ","1"),("ㄉ","2"),("ˇ","3"),("ˋ","4"),("ㄓ","5"),("ˊ","6"),("˙","7"),("ㄚ","8"),("ㄞ","9"),("ㄢ","0")],
        [("ㄆ","q"),("ㄊ","w"),("ㄍ","e"),("ㄐ","r"),("ㄔ","t"),("ㄗ","y"),("ㄧ","u"),("ㄛ","i"),("ㄟ","o"),("ㄣ","p")],
        [("ㄇ","a"),("ㄋ","s"),("ㄎ","d"),("ㄑ","f"),("ㄕ","g"),("ㄘ","h"),("ㄨ","j"),("ㄜ","k"),("ㄠ","l"),("ㄤ",";")],
        [("ㄈ","z"),("ㄌ","x"),("ㄏ","c"),("ㄒ","v"),("ㄖ","b"),("ㄙ","n"),("ㄩ","m"),("ㄝ",","),("ㄡ","."),("ㄥ","/")]
    ]
    var body: some View {
        VStack(spacing: 5) {
            HStack {
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(engine.snapshot.preedit.isEmpty ? engine.modeLabel : engine.snapshot.preedit)
                        .font(.subheadline).lineLimit(1)
                }
                HStack(spacing: 0) {
                    Button { preferences.simplified.toggle(); refresh() } label: {
                        Text(preferences.simplified ? "简" : "繁").frame(width: 44, height: 44)
                    }.accessibilityLabel("切換繁簡")
                    Button { key(Engine.modulesKey, 0) } label: {
                        Image(systemName: "text.bubble").frame(width: 44, height: 44)
                    }.accessibilityLabel("常用短語")
                    Button { settings.toggle(); expanded = false } label: {
                        Image(systemName: settings ? "keyboard" : "gearshape").frame(width: 44, height: 44)
                    }.accessibilityLabel(settings ? "返回鍵盤" : "鍵盤設定")
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .capsule)
            }.frame(height: 44)
            if settings { settingsPanel }
            else {
                HStack(spacing: 0) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            ForEach(expanded ? Array(engine.snapshot.candidates.indices) : engine.snapshot.compactIndices, id: \.self) { index in
                                let text = engine.snapshot.candidates[index]
                                Button { choose(index) } label: {
                                    candidateLabel(text).font(.title3).padding(.horizontal, 10).frame(minWidth: 44, minHeight: 44)
                                        .background(index == engine.snapshot.highlighted ? accent.opacity(0.14) : .clear, in: RoundedRectangle(cornerRadius: 9))
                                }.buttonStyle(.plain)
                                    .accessibilityAddTraits(index == engine.snapshot.highlighted ? .isSelected : [])
                            }
                        }
                    }
                    Button {
                        expanded.toggle()
                        if expanded { engine.browse(phrases: false) }
                    } label: { Image(systemName: expanded ? "chevron.up" : "chevron.down").frame(width: 44, height: 44) }
                        .disabled(engine.snapshot.candidates.isEmpty && !expanded).accessibilityLabel("展開候選詞")
                }.frame(height: 44)
                if expanded { expandedCandidates }
                else if engine.english || engine.inputMode != .zhuyin {
                    ForEach(englishRows, id: \.self) { row in
                        HStack(spacing: 4) {
                            if row == "zxcvbnm" { keycap(uppercase ? "⇪" : "⇧") { uppercase.toggle() } }
                            if symbolsPage > 0 && row == englishRows.last { keycap(symbolsPage == 1 ? "#+=" : "123") { symbolsPage = symbolsPage == 1 ? 2 : 1 } }
                            ForEach(Array(row), id: \.self) { character in
                                keycap(uppercase ? String(character).uppercased() : String(character)) {
                                    let text = uppercase ? String(character).uppercased() : String(character)
                                    key(Int32(text.unicodeScalars.first!.value), 0)
                                }
                            }
                        }
                    }
                } else {
                    ForEach(zhuyin.indices, id: \.self) { row in
                        HStack(spacing: 4) {
                            ForEach(zhuyin[row], id: \.1) { symbol, raw in keycap(symbol) { key(Int32(raw.utf8.first!), 0) } }
                        }
                    }
                }
            }
            HStack(spacing: 4) {
                if keyboardController()?.needsInputModeSwitchKey == true { InputModeButton(controller: keyboardController).frame(width: 44, height: 44) }
                keycap(engine.english ? "ㄅ" : "ABC", width: 44) { key(32, 1) }
                keycap(engine.english ? (symbolsPage == 0 ? "123" : "ABC") : "ㄦ", width: 44) {
                    if engine.english { symbolsPage = symbolsPage == 0 ? 1 : 0 } else { key(45, 0) }
                }
                keycap(spaceTitle) { key(32, 0) }
                Button(action: erase) { Image(systemName: "delete.left").frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel("刪除")
                keycap(engine.snapshot.preedit.isEmpty ? context.returnTitle : "確認", width: 52) { key(0xff0d, 0) }
            }
            if !engine.error.isEmpty { Text(engine.error).font(.caption).foregroundStyle(.red) }
        }.padding(.horizontal, 5).padding(.vertical, 5)
            .background(Color(UIColor.systemGroupedBackground)).tint(accent)
            .onChange(of: engine.snapshot.preedit) { _, text in if text.isEmpty { expanded = false } }
    }
    private var englishRows: [String] {
        switch symbolsPage {
        case 1: return ["1234567890", "-/:;()$&@", ".,?!'\"", "[]{}#%_="]
        case 2: return ["1234567890", "^*+|\\~`<>", "€£¥•…", ".,?!'\""]
        default: return ["1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"]
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
                        HStack(spacing: 0) {
                            Button { choose(index) } label: { candidateLabel(text).frame(maxWidth: .infinity, minHeight: 44).lineLimit(2) }.buttonStyle(.plain)
                            Button { engine.pin(index) } label: { Image(systemName: engine.isPinned(text) ? "pin.fill" : "pin").frame(width: 44, height: 44) }.accessibilityLabel("固定首選")
                        }.background(Color(UIColor.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 9))
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
    private var settingsPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("輸入方式", selection: Binding(get: { engine.inputMode }, set: { mode in
                engine.finishComposition(); refresh(); engine.selectMode(mode); Preferences.shared.inputMode = mode; refresh()
            })) {
                ForEach(InputMode.allCases) { Text($0.title).tag($0) }
            }
            Toggle("記住選詞", isOn: $preferences.rememberChoices).onChange(of: preferences.rememberChoices) { _, _ in engine.refresh() }
            Text("已記住 \(memory.records.count) 組注音").font(.footnote).foregroundStyle(.secondary)
            if confirmingClear {
                HStack {
                    Text("清除選詞記錄？").font(.subheadline)
                    Button("清除", role: .destructive) { memory.clear(); engine.refresh(); confirmingClear = false }
                    Button("取消") { confirmingClear = false }
                }.frame(minHeight: 44)
            } else { Button("清除選詞記錄") { confirmingClear = true }.frame(minHeight: 44) }
            if !memory.error.isEmpty { Text(memory.error).font(.caption).foregroundStyle(.red) }
            Spacer()
        }.padding(10).frame(height: 240)
    }
    private func keycap(_ text: String, width: CGFloat? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.system(size: text.count > 1 ? 15 : 21)).lineLimit(1)
                .frame(maxWidth: width == nil ? .infinity : nil, minHeight: 44)
                .frame(width: width)
                .background(Color(UIColor.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 7))
        }.buttonStyle(.plain).accessibilityLabel(text)
    }
}
