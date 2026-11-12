import SwiftUI
import UIKit

/// Keyboard settings. The app only writes choices the user changes; the
/// keyboard reads them the next time it opens.
struct KeyboardSettingsView: View {
    @Binding var settings: SharedKeyboardSettings
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var saveFailed = false
    @State private var confirmClear = false
    private let t = InterfaceText.system

    private var shape: GlassShape { settings.glassShape.flatMap(GlassShape.init(rawValue:)) ?? .capsule }
    private var palette: CandidatePalette { settings.candidatePalette.flatMap(CandidatePalette.init(rawValue:)) ?? .rose }
    private var strength: GlassStrength { settings.glassStrength.flatMap(GlassStrength.init(rawValue:)) ?? .balanced }
    private var tone: CandidateTone { settings.candidateTone.flatMap(CandidateTone.init(rawValue:)) ?? .system }
    private var previewDark: Bool { tone == .dark || (tone == .system && colorScheme == .dark) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    CandidateBarPreview(shape: shape, dark: previewDark, palette: palette, strength: strength,
                                        cats: settings.catGlassSkin ?? true, simplified: settings.simplified ?? false)
                        .listRowInsets(EdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12))
                        .animation(.easeInOut(duration: 0.25), value: shape)
                }
                Section(t("外觀", "外观")) {
                    shapePicker
                    Picker(t("色調", "色调"), selection: binding(\.candidateTone, default: CandidateTone.system.rawValue)) {
                        Text(t("跟隨系統", "跟随系统")).tag("system")
                        Text(t("淺色", "浅色")).tag("light")
                        Text("深色").tag("dark")
                    }
                    palettePicker
                    VStack(alignment: .leading, spacing: 8) {
                        // A segmented picker does not show its own title.
                        Text("玻璃")
                        Picker("玻璃", selection: binding(\.glassStrength, default: GlassStrength.balanced.rawValue)) {
                            Text("柔和").tag("soft")
                            Text(t("標準", "标准")).tag("balanced")
                            Text("通透").tag("vivid")
                        }
                        .pickerStyle(.segmented)
                    }
                    .padding(.vertical, 4)
                    Toggle(t("顯示米米莫和莫莫", "显示米米莫和莫莫"), isOn: binding(\.catGlassSkin, default: true))
                    FestivalWardrobeLink(settings: $settings)
                }
                Section {
                    Picker(t("輸入方式", "输入方式"), selection: setting(\.inputMode)) {
                        Text(t("沿用鍵盤現有設定", "沿用键盘现有设置")).tag(nil as String?)
                        Text("注音").tag("zhuyin" as String?)
                        Text("拼音").tag("pinyin" as String?)
                        Text("日本語 · ローマ字").tag("japanese" as String?)
                    }
                    Picker("中文", selection: setting(\.simplified)) {
                        Text(t("沿用鍵盤現有設定", "沿用键盘现有设置")).tag(nil as Bool?)
                        Text("繁體").tag(false as Bool?)
                        Text("简体").tag(true as Bool?)
                    }
                } header: {
                    Text(t("輸入", "输入"))
                } footer: {
                    Text(t("在鍵盤上連續輕摸右邊的莫莫五下，也能切換簡體／繁體。按鍵聲音跟隨系統的「鍵盤回饋」設定。",
                           "在键盘上连续轻摸右边的莫莫五下，也能切换简体／繁体。按键声音跟随系统的“键盘反馈”设置。"))
                }
                Section {
                    Toggle(t("記住選詞", "记住选词"), isOn: binding(\.rememberChoices, default: true))
                    Button(t("清除選詞記錄", "清除选词记录"), role: .destructive) { confirmClear = true }
                } header: {
                    Text(t("選詞記憶", "选词记忆"))
                } footer: {
                    Text(t("選詞記錄只存在鍵盤的本機空間。清除會在下次開啟喵撲鍵盤時執行。", "选词记录只存在键盘的本机空间。清除会在下次打开喵扑键盘时执行。"))
                }
                Section(t("加入鍵盤", "添加键盘")) {
                    Text(t("設定 → 一般 → 鍵盤 → 鍵盤 → 新增鍵盤 → 喵撲", "设置 → 通用 → 键盘 → 键盘 → 添加新键盘 → 喵扑"))
                        .font(.subheadline)
                    Button(t("打開設定", "打开设置")) {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
                Section {
                    NavigationLink(t("隱私政策", "隐私政策")) { PrivacyView() }
                    NavigationLink(t("開源授權", "开源许可")) { LicensesView() }
                }
            }
            .navigationTitle(t("鍵盤設定", "键盘设置"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
            .alert(t("無法儲存設定", "无法保存设置"), isPresented: $saveFailed) { Button("好", role: .cancel) {} }
            .confirmationDialog(t("清除選詞記錄？", "清除选词记录？"), isPresented: $confirmClear, titleVisibility: .visible) {
                Button(t("清除選詞記錄", "清除选词记录"), role: .destructive) {
                    var next = settings
                    next.clearMemoryRequest = UUID().uuidString
                    save(next)
                    // 0.5.0 kept the records in the App Group, where the
                    // keyboard cannot delete them; the app can.
                    if let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.krysta1ll.meowpuut.shared") {
                        for name in ["mimo_user.db", "mimo_user_wal.log"] {
                            try? FileManager.default.removeItem(at: group.appendingPathComponent(name))
                        }
                    }
                }
            } message: { Text(t("下次開啟喵撲鍵盤時，將清除本機的選詞記錄。", "下次打开喵扑键盘时，将清除本机的选词记录。")) }
            .tint(Brand.accent)
        }
    }

    private var shapePicker: some View {
        HStack(spacing: 10) {
            ForEach(GlassShape.allCases) { option in
                Button {
                    var next = settings
                    next.glassShape = option.rawValue
                    save(next)
                } label: {
                    VStack(spacing: 6) {
                        LiquidGlassBar(style: option, dark: previewDark, accessible: false, strength: strength,
                                       tint: palette.color(dark: previewDark))
                            .frame(height: 28)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 8)
                            .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(previewDark ? Color(white: 0.14) : Color(red: 0.914, green: 0.910, blue: 0.937)))
                            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(option == shape ? palette.pill(dark: previewDark).bottom : Color.clear, lineWidth: 2))
                        Text(option.name(t)).font(.caption)
                            .foregroundStyle(option == shape ? Color.primary : Color.secondary)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(option.name(t))
                .accessibilityAddTraits(option == shape ? .isSelected : [])
            }
        }
        .padding(.vertical, 4)
    }

    private var palettePicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("色系")
            HStack(spacing: 12) {
                ForEach(CandidatePalette.allCases) { option in
                    Button {
                        var next = settings
                        next.candidatePalette = option.rawValue
                        save(next)
                    } label: {
                        let colors = option.pill(dark: previewDark)
                        Circle()
                            .fill(LinearGradient(colors: [colors.top, colors.bottom], startPoint: .top, endPoint: .bottom))
                            .frame(width: 30, height: 30)
                            .overlay(Circle().stroke(Color.primary.opacity(option == palette ? 0.75 : 0), lineWidth: 2).padding(-4))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(paletteName(option))
                    .accessibilityAddTraits(option == palette ? .isSelected : [])
                }
            }
            Text(paletteName(palette)).font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private func paletteName(_ palette: CandidatePalette) -> String {
        switch palette {
        case .rose: return "玫瑰奶茶"
        case .ocean: return t("海鹽藍", "海盐蓝")
        case .sage: return t("鼠尾草綠", "鼠尾草绿")
        case .lavender: return t("霧紫", "雾紫")
        case .amber: return "琥珀蜜"
        case .graphite: return "石墨"
        }
    }

    /// A non-optional view of an optional setting, showing the keyboard's default.
    private func binding<Value>(_ keyPath: WritableKeyPath<SharedKeyboardSettings, Value?>, default value: Value) -> Binding<Value> {
        Binding(get: { settings[keyPath: keyPath] ?? value }, set: { newValue in
            var next = settings
            next[keyPath: keyPath] = newValue
            save(next)
        })
    }

    private func setting<Value>(_ keyPath: WritableKeyPath<SharedKeyboardSettings, Value?>) -> Binding<Value?> {
        Binding(get: { settings[keyPath: keyPath] }, set: { value in
            var next = settings
            next[keyPath: keyPath] = value
            if (keyPath as AnyKeyPath) == \SharedKeyboardSettings.simplified {
                next.simplifiedRevision = UUID().uuidString
            }
            save(next)
        })
    }

    private func save(_ next: SharedKeyboardSettings) {
        do { try next.save(); settings = next }
        catch { settings = next; saveFailed = true }
    }
}

/// The licence and source files the keyboard ships in its Licenses folder
/// (scripts/prepare-ios.sh copies them there): the notices the data sources ask
/// for. Meowpuut's own licence comes first.
private struct LicensesView: View {
    private let t = InterfaceText.system
    private let files: [URL] = {
        guard let folder = Bundle.main.builtInPlugInsURL?.appendingPathComponent("MeowpuutKeyboard.appex/Licenses"),
              let urls = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return [] }
        return urls.sorted {
            let own = ($0.lastPathComponent.hasPrefix("Meowpuut-"), $1.lastPathComponent.hasPrefix("Meowpuut-"))
            if own.0 != own.1 { return own.0 }
            return $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending
        }
    }()

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(t("喵撲 Meowpuut", "喵扑 Meowpuut")).font(.headline)
                    Text("Copyright © 2026 Krystal and Meowpuut contributors")
                    Text(t("喵撲依 MIT 授權發佈，不提供任何擔保。授權全文是下方清單裡的 Meowpuut-MIT；其餘各項是詞庫和表情資料的來源與授權。",
                           "喵扑依 MIT 许可发布，不提供任何担保。许可全文是下方列表里的 Meowpuut-MIT；其余各项是词库和表情数据的来源与许可。"))
                }
                .font(.subheadline)
                .padding(.vertical, 4)
                Link(t("原始碼：github.com/Krysta1ll/Meowpuut", "源代码：github.com/Krysta1ll/Meowpuut"),
                     destination: URL(string: "https://github.com/Krysta1ll/Meowpuut")!)
            }
            Section {
                ForEach(files, id: \.self) { url in
                    let name = url.deletingPathExtension().lastPathComponent
                    NavigationLink(name) {
                        ScrollView {
                            Text((try? String(contentsOf: url, encoding: .utf8)) ?? "")
                                .font(.footnote.monospaced())
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding()
                        }
                        .navigationTitle(name)
                        .navigationBarTitleDisplayMode(.inline)
                    }
                }
            } footer: {
                Text(t("詞庫和表情符號資料的來源見各 SOURCE 檔。", "词库和表情符号数据的来源见各 SOURCE 文件。"))
            }
        }
        .navigationTitle(t("開源授權", "开源许可"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
