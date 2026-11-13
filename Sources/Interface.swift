import SwiftUI
import AppKit

// The settings window: try typing, appearance, choice memory, phrase modules
// and shortcuts. Its text follows the system language (Traditional unless the
// first Chinese language in the list is Simplified).

let accent = Brand.accent

// Names of the appearance choices in both scripts.
extension GlassShape {
    func name(_ t: InterfaceText) -> String {
        switch self { case .capsule: t("膠囊", "胶囊"); case .wave: "波浪"; case .cloud: t("雲朵", "云朵") }
    }
}

extension CandidateTone {
    func name(_ t: InterfaceText) -> String {
        switch self { case .system: t("跟隨系統", "跟随系统"); case .light: t("淺色", "浅色"); case .dark: "深色" }
    }
}

extension GlassStrength {
    func name(_ t: InterfaceText) -> String {
        switch self { case .soft: "柔和"; case .balanced: t("標準", "标准"); case .vivid: "通透" }
    }
}

extension CandidatePalette {
    func name(_ t: InterfaceText) -> String {
        switch self {
        case .rose: "玫瑰奶茶"
        case .ocean: t("海鹽藍", "海盐蓝")
        case .sage: t("鼠尾草綠", "鼠尾草绿")
        case .lavender: t("霧紫", "雾紫")
        case .amber: "琥珀蜜"
        case .graphite: "石墨"
        }
    }
}

/// Applies one appearance choice from a menu: "shape:wave", "tone:dark",
/// "palette:sage", "glass:soft", "cats:off", "place:daily", "festival:on".
@discardableResult
func applyAppearance(_ command: String, to preferences: Preferences = .shared) -> Bool {
    let parts = command.split(separator: ":", maxSplits: 1).map(String.init)
    guard parts.count == 2 else { return false }
    let value = parts[1]
    switch parts[0] {
    case "shape":
        guard let shape = GlassShape(rawValue: value) else { return false }
        preferences.glassShape = shape
    case "tone":
        guard let tone = CandidateTone(rawValue: value) else { return false }
        preferences.candidateTone = tone
    case "text-size":
        guard let size = CandidateTextSize(rawValue: value) else { return false }
        preferences.candidateTextSize = size
    case "window-size":
        guard let size = CandidateWindowSize(rawValue: value) else { return false }
        preferences.candidateWindowSize = size
    case "palette":
        guard let palette = CandidatePalette(rawValue: value) else { return false }
        preferences.candidatePalette = palette
    case "glass":
        guard let strength = GlassStrength(rawValue: value) else { return false }
        preferences.glassStrength = strength
    case "cats":
        guard value == "on" || value == "off" else { return false }
        preferences.catGlassSkin = value == "on"
    case "place":
        guard value == CatSchedule.daily || CatArrangement.named(value) != nil else { return false }
        preferences.catPlacement = value
    case "festival":
        guard value == "on" || value == "off" else { return false }
        preferences.festivalOutfits = value == "on"
    default:
        return false
    }
    return true
}

final class TypingView: NSTextView {
    var engine: Engine?
    override func keyDown(with event: NSEvent) {
        guard let engine, let (key, modifiers) = inputKey(event, mode: engine.inputMode, english: engine.english, composing: !engine.snapshot.input.isEmpty) else { super.keyDown(with: event); return }
        let handled = engine.key(key, modifiers: modifiers)
        let commit = engine.takeCommit()
        if !commit.isEmpty { insertText(commit, replacementRange: selectedRange()) }
        if !handled { super.keyDown(with: event) }
    }
}

struct TypingPad: NSViewRepresentable {
    var engine: Engine
    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSScrollView()
        let text = TypingView()
        text.engine = engine; text.font = .systemFont(ofSize: 23)
        text.textContainerInset = NSSize(width: 20, height: 20)
        text.isRichText = false; text.isAutomaticQuoteSubstitutionEnabled = false
        text.isVerticallyResizable = true; text.autoresizingMask = [.width]
        text.textContainer?.widthTracksTextView = true
        text.backgroundColor = .textBackgroundColor
        scroll.documentView = text; scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        return scroll
    }
    func updateNSView(_ view: NSScrollView, context: Context) {}
}

private struct ListSelection: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @ObservedObject private var preferences = Preferences.shared

    var body: some View {
        let tint = preferences.candidatePalette.color(dark: scheme == .dark)
        RoundedRectangle(cornerRadius: 9)
            .fill(tint.opacity(reduceTransparency ? 0.6 : (scheme == .dark ? 0.30 : 0.20)))
            .overlay {
                if contrast == .increased { RoundedRectangle(cornerRadius: 9).strokeBorder(tint, lineWidth: 1) }
            }
            .allowsHitTesting(false)
    }
}

/// The candidate list under the try-typing field: one candidate per line.
struct TypingCandidates: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var engine: Engine
    @ObservedObject private var preferences = Preferences.shared
    var t: InterfaceText
    var onChoose: (Int) -> Void
    var onPage: (Int32) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                BrandMark(size: 27)
                Text(engine.snapshot.preedit.isEmpty ? "Meowpuut" : engine.snapshot.preedit)
                    .font(.system(size: 16, weight: .medium))
                    .lineLimit(1).truncationMode(.head)
                Spacer()
                Text(engine.modeLabel).font(.caption).foregroundStyle(.secondary).fixedSize()
            }
            if !engine.snapshot.candidates.isEmpty || engine.browsing {
                let marked = markedJapanese(engine.snapshot)
                VStack(spacing: 3) {
                    ForEach(Array(engine.snapshot.candidates.prefix(7).enumerated()), id: \.offset) { index, candidate in
                        let selected = index == engine.snapshot.highlighted
                        let japanese = marked.contains(candidate)
                        Button { onChoose(index) } label: {
                            HStack(spacing: 12) {
                                Text(engine.candidateShortcut(index))
                                    .font(.system(size: 11, weight: .medium, design: .rounded))
                                    .foregroundStyle(.secondary).frame(width: 26)
                                Text(candidate).font(.system(size: preferences.candidateTextSize.font + 3)).lineLimit(1).truncationMode(.middle)
                                if japanese {
                                    Image(systemName: "pawprint.fill").font(.system(size: 8))
                                        .rotationEffect(.degrees(-20)).foregroundStyle(.secondary)
                                        .accessibilityHidden(true)
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 10).padding(.vertical, 8)
                            .background { if selected { ListSelection() } }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .help(candidate)
                        .accessibilityLabel(candidate + (japanese ? t("，日語", "，日语") : ""))
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
                .animation(reduceMotion ? nil : .easeOut(duration: 0.14), value: engine.snapshot.highlighted)
                HStack {
                    Button(t("全部", "全部") + (engine.browsing && !engine.phrasesOnly ? " ✓" : "")) { engine.browse(phrases: false) }
                        .disabled(engine.snapshot.input.isEmpty)
                    Button(t("詞組", "词组") + (engine.phrasesOnly ? " ✓" : "")) { engine.browse(phrases: true) }
                        .disabled(engine.snapshot.input.isEmpty)
                    if engine.browsing {
                        Text(engine.browseCount == 256 ? t("前 256 項", "前 256 项") : t("\(engine.browseCount) 項", "\(engine.browseCount) 项"))
                            .font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                    Text(t("↑↓ 選詞 · ↵ 確認", "↑↓ 选词 · ↵ 确认")).font(.system(size: 11)).foregroundStyle(.secondary)
                    Spacer()
                    HStack(spacing: 0) {
                        Button { onPage(0xff55) } label: {
                            Image(systemName: "chevron.left").frame(width: 30, height: 28)
                        }.disabled(engine.snapshot.page == 0).accessibilityLabel(t("上一頁", "上一页"))
                        Text("\(engine.snapshot.page + 1)").font(.system(size: 11, design: .rounded))
                        Button { onPage(0xff56) } label: {
                            Image(systemName: "chevron.right").frame(width: 30, height: 28)
                        }.disabled(engine.snapshot.lastPage).accessibilityLabel(t("下一頁", "下一页"))
                    }.glassEffect(.regular, in: .capsule)
                }.buttonStyle(.plain)
            }
        }
        .padding(16)
        .background(colorScheme == .dark ? Color(nsColor: .windowBackgroundColor) : Brand.paper,
                    in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(.primary.opacity(0.08)))
    }
}

/// The candidate window as it will look, on a backdrop that shows the glass.
private struct AppearancePreview: View {
    @Environment(\.colorScheme) private var systemScheme
    @ObservedObject var preferences = Preferences.shared
    @StateObject private var sample = Engine(mode: .pinyin)
    @State private var expanded = false
    var t: InterfaceText

    private var dark: Bool {
        switch preferences.candidateTone {
        case .system: systemScheme == .dark
        case .light: false
        case .dark: true
        }
    }

    var body: some View {
        GlassCandidateView(engine: sample, cats: preferences.catGlassSkin, expanded: expanded, canExpand: true,
                           onChoose: { sample.snapshot.highlighted = $0 }, onPage: { _ in }, onToggle: { expanded.toggle() })
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(colors: dark ? [Color(red: 0.10, green: 0.12, blue: 0.17), Color(red: 0.26, green: 0.20, blue: 0.24)]
                                                      : [Brand.paper, Color(red: 0.80, green: 0.86, blue: 0.90)],
                                         startPoint: .leading, endPoint: .trailing))
            }
            .onAppear { fill() }
            .onChange(of: preferences.simplified) { _, _ in fill() }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(t("候選窗預覽", "候选窗预览"))
    }

    private func fill() {
        let words = preferences.simplified ? ["你好", "你好呀", "拟好", "逆号", "にはお"] : ["你好", "妳好", "你好呀", "擬好", "にはお"]
        sample.snapshot = Snapshot(preedit: "nihao", candidates: words, input: "nihao",
                                   confidentCandidates: Array(words.prefix(4)), japaneseCandidates: ["にはお"])
    }
}

struct Studio: View {
    enum Section: String, CaseIterable, Identifiable {
        case typing, appearance, memory, modules, shortcuts
        var id: String { rawValue }
        var symbol: String {
            switch self {
            case .typing: "keyboard"; case .appearance: "paintpalette"; case .memory: "text.alignleft"
            case .modules: "square.stack.3d.up"; case .shortcuts: "command"
            }
        }
        func title(_ t: InterfaceText) -> String {
            switch self {
            case .typing: t("試打", "试打"); case .appearance: t("外觀", "外观"); case .memory: t("選詞記憶", "选词记忆")
            case .modules: t("短語模組", "短语模块"); case .shortcuts: t("快捷鍵", "快捷键")
            }
        }
    }

    @Environment(\.colorScheme) private var colorScheme
    @StateObject var engine = Engine()
    @ObservedObject var preferences = Preferences.shared
    @ObservedObject var memory = ChoiceMemory.shared
    @State private var confirmingMemoryClear = false
    @State private var section: Section
    @State private var inputSourceStatus = ""
    private let t: InterfaceText

    /// The window opens on try typing in the system's script; the preview's snapshots open the others.
    init(section: Section = .typing, text: InterfaceText = .system) {
        _section = State(initialValue: section)
        t = text
    }

    private var version: String {
        guard let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String else { return "" }
        return t("窗臺", "窗台") + " · " + build
    }

    var body: some View {
        NavigationSplitView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 10) {
                    BrandMark(size: 36)
                    Text("Meowpuut").font(.system(size: 22, weight: .black)).tracking(-0.9)
                }.padding(.top, 20)
                ForEach(Section.allCases) { item in
                    Button { section = item } label: {
                        Label(item.title(t), systemImage: item.symbol)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                            .background(section == item ? accent.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 10))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(section == item ? .isSelected : [])
                }
                Spacer()
                Text((version.isEmpty ? "" : version + " ") + t("測試版", "测试版")).font(.caption2).foregroundStyle(.secondary)
            }.padding(20).navigationSplitViewColumnWidth(220)
        } detail: {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text(section.title(t)).font(.system(size: 28, weight: .semibold))
                    switch section {
                    case .typing: typing
                    case .appearance: appearance
                    case .memory: memorySection
                    case .modules: modules
                    case .shortcuts: shortcuts
                    }
                    Spacer(minLength: 0)
                    Button(t("第三方授權", "第三方许可")) {
                        if let url = Bundle.main.resourceURL?.appendingPathComponent("Licenses") { NSWorkspace.shared.open(url) }
                    }.font(.caption).buttonStyle(.link)
                }.padding(36).padding(.top, 16).frame(maxWidth: .infinity, alignment: .topLeading)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(colorScheme == .dark ? Color(nsColor: .windowBackgroundColor) : Brand.paper)
        }.tint(accent).frame(minWidth: 920, minHeight: 650)
        .onChange(of: preferences.simplified) { _, _ in engine.modeChanged() }
        .onChange(of: preferences.rememberChoices) { _, _ in engine.refresh() }
        .confirmationDialog(t("清除選詞記錄？", "清除选词记录？"), isPresented: $confirmingMemoryClear) {
            Button(t("清除記錄", "清除记录"), role: .destructive) { memory.clear(); engine.refresh() }
            Button("取消", role: .cancel) {}
        } message: { Text(t("固定的首選會保留。", "固定的首选会保留。")) }
    }

    @ViewBuilder private var typing: some View {
        Picker(t("輸入方式", "输入方式"), selection: Binding(get: { engine.inputMode }, set: { mode in
            engine.finishComposition(); engine.selectMode(mode)
        })) {
            ForEach(InputMode.allCases) { Text($0.title).tag($0) }
        }
        HStack {
            Picker(t("輸出字形", "输出字形"), selection: $preferences.simplified) {
                Text("繁體中文").tag(false); Text("简体中文").tag(true)
            }.pickerStyle(.segmented).fixedSize()
            Spacer()
            Text(engine.english ? "ABC" : engine.inputMode.title).font(.callout.weight(.medium)).foregroundStyle(accent)
        }
        TypingPad(engine: engine).frame(height: 140).clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(.primary.opacity(0.08)))
        TypingCandidates(engine: engine, t: t, onChoose: { index in
            engine.choose(index)
            if let text = NSApp.keyWindow?.firstResponder as? TypingView { text.insertText(engine.takeCommit(), replacementRange: text.selectedRange()) }
        }, onPage: { key in engine.key(key) })
        Text(t("注音用大千鍵位，空格標一聲。注音和日語用 Shift＋數字選詞。\n拼音用數字選詞，單按 Shift 切換中英。Tab 或方向鍵移動，Enter 確認。",
               "注音用大千键位，空格标一声。注音和日语用 Shift＋数字选词。\n拼音用数字选词，单按 Shift 切换中英。Tab 或方向键移动，Enter 确认。"))
            .font(.caption).foregroundStyle(.secondary).lineSpacing(6)
        if !engine.error.isEmpty { Text(engine.error).foregroundStyle(.red) }
    }

    @ViewBuilder private var appearance: some View {
        AppearancePreview(t: t)
        appearanceGroup(t("候選窗", "候选窗")) {
            appearanceRow(t("造型", "造型")) {
                Picker(t("造型", "造型"), selection: $preferences.glassShape) {
                    ForEach(GlassShape.allCases) { Text($0.name(t)).tag($0) }
                }.pickerStyle(.segmented)
            }
            Divider()
            appearanceRow(t("色調", "色调")) {
                Picker(t("色調", "色调"), selection: $preferences.candidateTone) {
                    ForEach(CandidateTone.allCases) { Text($0.name(t)).tag($0) }
                }.pickerStyle(.segmented)
            }
            Divider()
            appearanceRow(t("候選字級", "候选字号")) {
                Picker(t("候選字級", "候选字号"), selection: $preferences.candidateTextSize) {
                    ForEach(CandidateTextSize.allCases) { Text($0.name(t)).tag($0) }
                }.pickerStyle(.segmented)
            }
            Divider()
            appearanceRow(t("候選窗大小", "候选窗大小")) {
                Picker(t("候選窗大小", "候选窗大小"), selection: $preferences.candidateWindowSize) {
                    ForEach(CandidateWindowSize.allCases) { Text($0.name(t)).tag($0) }
                }.pickerStyle(.segmented)
            }
            Divider()
            appearanceRow(t("色系", "色系")) {
                Picker(t("色系", "色系"), selection: $preferences.candidatePalette) {
                    ForEach(CandidatePalette.allCases) { Text($0.name(t)).tag($0) }
                }
            }
            Divider()
            appearanceRow(t("玻璃", "玻璃")) {
                Picker(t("玻璃", "玻璃"), selection: $preferences.glassStrength) {
                    ForEach(GlassStrength.allCases) { Text($0.name(t)).tag($0) }
                }.pickerStyle(.segmented)
            }
        }
        appearanceGroup(t("米米莫與莫莫", "米米莫与莫莫")) {
            appearanceRow(t("顯示小貓", "显示小猫")) {
                Toggle(t("顯示小貓", "显示小猫"), isOn: $preferences.catGlassSkin).toggleStyle(.switch).tint(accent)
            }
            Divider()
            appearanceRow("位置") {
                Picker("位置", selection: $preferences.catPlacement) {
                    Text(t("每天換", "每天换")).tag(CatSchedule.daily)
                    ForEach(CatArrangement.all) { Text($0.name(t)).tag($0.id) }
                }.disabled(!preferences.catGlassSkin)
            }
            Divider()
            appearanceRow(t("節日裝扮", "节日装扮")) {
                Toggle(t("節日裝扮", "节日装扮"), isOn: $preferences.festivalOutfits)
                    .toggleStyle(.switch).tint(accent)
            }
            if let theme = Festivals.theme(wardrobe: preferences.festivalWardrobe) {
                Text(t("現在是「\(theme.names.hant)」的裝扮。", "现在是“\(theme.names.hans)”的装扮。"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func appearanceGroup<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: 6, content: content)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(.background.opacity(0.55), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private func appearanceRow<Control: View>(_ title: String, @ViewBuilder control: () -> Control) -> some View {
        HStack {
            Text(title).accessibilityHidden(true)
            Spacer(minLength: 14)
            control().labelsHidden().fixedSize()
        }.frame(minHeight: 23)
    }

    @ViewBuilder private var memorySection: some View {
        Toggle(t("記住選詞", "记住选词"), isOn: $preferences.rememberChoices)
        HStack {
            Text(t("已記住 \(memory.records.count) 組讀音", "已记住 \(memory.records.count) 组读音")).font(.caption).foregroundStyle(.secondary)
            Spacer()
            Button(t("清除選詞記錄", "清除选词记录")) { confirmingMemoryClear = true }.disabled(memory.records.isEmpty && memory.error.isEmpty)
        }
        if !memory.error.isEmpty { Text(memory.error).foregroundStyle(.red) }
        if !memory.records.isEmpty {
            LazyVStack(spacing: 8) {
                ForEach(memory.records.keys.filter { !$0.hasPrefix("ctx|") }.sorted(), id: \.self) { key in
                    HStack {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(memory.records[key]?.first?.text ?? "").font(.system(size: 18))
                            Text(readingLabel(key)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(t("忘記", "忘记")) { memory.forget(key); engine.refresh() }
                    }.padding(14).background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }

    @ViewBuilder private var modules: some View {
        Toggle(t("使用本機短語模組", "使用本机短语模块"), isOn: $preferences.modulesEnabled)
        ForEach(preferences.modules) { module in
            HStack {
                Image(systemName: "text.bubble").foregroundStyle(accent).font(.title2)
                VStack(alignment: .leading) {
                    Text(module.name).font(.headline)
                    Text(t("\(module.trigger) · \(module.phrases.count) 條短語", "\(module.trigger) · \(module.phrases.count) 条短语")).foregroundStyle(.secondary)
                }
                Spacer(); Text("API v\(module.apiVersion)").font(.caption).foregroundStyle(.secondary)
            }.padding(18).background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
        }
        HStack {
            Button(t("打開模組資料夾", "打开模块文件夹")) { NSWorkspace.shared.open(preferences.moduleDirectory) }
            Button(t("重新載入", "重新加载")) { preferences.reload() }
        }
        if !preferences.moduleError.isEmpty { Text(preferences.moduleError).foregroundStyle(.red) }
        Text(t("加入或修改模組後，按「重新載入」。", "添加或修改模块后，点“重新加载”。")).font(.caption).foregroundStyle(.secondary)
    }

    @ViewBuilder private var shortcuts: some View {
        let rows: [(String, String)] = [
            ("⌃⌥1 / 2 / 3", t("注音／拼音／日語", "注音／拼音／日语")),
            ("1–7", t("拼音選詞", "拼音选词")),
            ("⇧1–7", t("注音、日語選詞", "注音、日语选词")),
            ("Tab / ⇧Tab · → / ←", t("下一個／上一個候選", "下一个／上一个候选")),
            ("Enter", t("確認候選；拼音、日語也可按空格", "确认候选；拼音、日语也可按空格")),
            ("⇧Enter", t("輸出原始字母", "输出原始字母")),
            ("↓ / Esc", t("展開／收起候選", "展开／收起候选")),
            ("↑ ↓ ← →", t("展開後在候選間移動", "展开后在候选间移动")),
            ("⌥← / ⌥→ · Page Up / Page Down", t("翻頁", "翻页")),
            ("Shift", t("拼音單按切換中英", "拼音单按切换中英")),
            ("⌥Space", t("本機短語", "本机短语")),
        ]
        ForEach(rows, id: \.0) { key, action in
            HStack { Text(key).font(.system(.body, design: .monospaced)); Spacer(); Text(action).foregroundStyle(.secondary) }.padding(.vertical, 6)
        }
        HStack {
            Button(t("啟用 Meowpuut", "启用 Meowpuut")) {
                do { try SystemInput.enable(); inputSourceStatus = t("已加入輸入法列表。", "已添加到输入法列表。") }
                catch { inputSourceStatus = error.localizedDescription }
            }
            Button(t("打開鍵盤設定", "打开键盘设置")) { NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Keyboard-Settings.extension")!) }
        }
        if !inputSourceStatus.isEmpty { Text(inputSourceStatus).font(.caption).foregroundStyle(.secondary) }
    }
}
