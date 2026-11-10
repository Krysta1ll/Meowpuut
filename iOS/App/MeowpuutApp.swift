import SwiftUI

@main
struct MeowpuutApp: App {
    var body: some Scene {
        WindowGroup { TrialScreen() }
    }
}

private struct TrialScreen: View {
    @State private var text = ""
    @State private var showsSetup = false
    @State private var settings = SharedKeyboardSettings()
    @State private var settingsError = false
    @State private var confirmClear = false
    @Environment(\.colorScheme) private var colorScheme
    // Follows the system language: Simplified for zh-Hans, otherwise Traditional.
    private let t = InterfaceText.system

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    masthead
                    VStack(alignment: .leading, spacing: 10) {
                        Text(t("試打", "试打")).font(.headline)
                        TextEditor(text: $text)
                            .font(.title3)
                            .scrollContentBackground(.hidden)
                            .frame(minHeight: 180)
                            .padding(8)
                            .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 20))
                            .accessibilityLabel(t("試打", "试打"))
                    }
                }
                .frame(maxWidth: 580)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .background(colorScheme == .dark ? Color(uiColor: .systemBackground) : Brand.paper)
            .navigationTitle("Meowpuut")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(t("鍵盤設定", "键盘设置"), systemImage: "keyboard") { showsSetup = true }
                }
            }
            .sheet(isPresented: $showsSetup) { setup }
            .tint(Brand.accent)
            .onAppear { _ = SharedKeyboardSettings.compactUserWalIfNeeded() }
        }
    }

    private var masthead: some View {
        VStack(spacing: 4) {
            BrandMark(size: 134)
                .accessibilityHidden(true)
            Text("Meowpuut")
                .font(.system(.largeTitle, weight: .black))
                .tracking(-1.3)
            Text("ㄅ ㄆ ㄇ ㄈ")
                .font(.subheadline.monospaced())
                .tracking(2)
                .foregroundStyle(Brand.accent)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Brand.accent.opacity(0.55)).frame(width: 36, height: 2)
        }
    }

    private var setup: some View {
        NavigationStack {
            Form {
                Section(t("輸入", "输入")) {
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
                    Text(t("在鍵盤上連續輕摸右邊的莫莫五下，也能切換簡體／繁體。", "在键盘上连续轻摸右边的莫莫五下，也能切换简体／繁体。"))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section(t("外觀", "外观")) {
                    Picker(t("皮膚", "皮肤"), selection: setting(\.catGlassSkin)) {
                        Text(t("沿用鍵盤現有設定", "沿用键盘现有设置")).tag(nil as Bool?)
                        Text(t("米米莫與莫莫", "米米莫与莫莫")).tag(true as Bool?)
                        Text(t("簡潔玻璃", "简洁玻璃")).tag(false as Bool?)
                    }
                    Picker(t("色調", "色调"), selection: setting(\.candidateTone)) {
                        Text(t("沿用鍵盤現有設定", "沿用键盘现有设置")).tag(nil as String?)
                        Text(t("跟隨系統", "跟随系统")).tag("system" as String?)
                        Text(t("淺色", "浅色")).tag("light" as String?)
                        Text("深色").tag("dark" as String?)
                    }
                    Picker(t("玻璃強度", "玻璃强度"), selection: setting(\.glassStrength)) {
                        Text(t("沿用鍵盤現有設定", "沿用键盘现有设置")).tag(nil as String?)
                        Text("柔和").tag("soft" as String?)
                        Text(t("標準", "标准")).tag("balanced" as String?)
                        Text(t("鮮明", "鲜明")).tag("vivid" as String?)
                    }
                    Picker("色系", selection: setting(\.candidatePalette)) {
                        Text(t("沿用鍵盤現有設定", "沿用键盘现有设置")).tag(nil as String?)
                        Text("玫瑰奶茶").tag("rose" as String?)
                        Text(t("海鹽藍", "海盐蓝")).tag("ocean" as String?)
                        Text(t("鼠尾草綠", "鼠尾草绿")).tag("sage" as String?)
                        Text(t("霧紫", "雾紫")).tag("lavender" as String?)
                        Text("琥珀蜜").tag("amber" as String?)
                        Text("石墨").tag("graphite" as String?)
                    }
                }
                Section(t("選詞記憶", "选词记忆")) {
                    Picker(t("記住選詞", "记住选词"), selection: setting(\.rememberChoices)) {
                        Text(t("沿用鍵盤現有設定", "沿用键盘现有设置")).tag(nil as Bool?)
                        Text(t("啟用", "启用")).tag(true as Bool?)
                        Text("停用").tag(false as Bool?)
                    }
                    Button(t("清除選詞記錄", "清除选词记录"), role: .destructive) { confirmClear = true }
                    Text(t("選詞記錄保存在鍵盤的本機空間。清除會在下次開啟喵撲鍵盤時執行。", "选词记录保存在键盘的本机空间。清除会在下次打开喵扑键盘时执行。"))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section(t("加入鍵盤", "添加键盘")) {
                    Text(t("設定 → 一般 → 鍵盤 → 鍵盤 → 加入新鍵盤 → Meowpuut", "设置 → 通用 → 键盘 → 键盘 → 添加新键盘 → Meowpuut")).font(.subheadline)
                    Button(t("打開設定", "打开设置")) { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
            }
            .onAppear { settings = SharedKeyboardSettings.read() ?? SharedKeyboardSettings() }
            .alert(t("無法儲存設定", "无法保存设置"), isPresented: $settingsError) { Button("好", role: .cancel) {} }
            .confirmationDialog(t("清除選詞記錄？", "清除选词记录？"), isPresented: $confirmClear) {
                Button(t("清除選詞記錄", "清除选词记录"), role: .destructive) {
                    var next = settings
                    next.clearMemoryRequest = UUID().uuidString
                    save(next)
                }
            } message: { Text(t("下次開啟喵撲鍵盤時，將清除本機的選詞記錄。", "下次打开喵扑键盘时，将清除本机的选词记录。")) }
            .navigationTitle(t("鍵盤設定", "键盘设置"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { showsSetup = false }
                }
            }
            .tint(Brand.accent)
        }
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
        catch { settingsError = true }
    }
}
