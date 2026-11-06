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

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    masthead
                    VStack(alignment: .leading, spacing: 10) {
                        Text("試打").font(.headline)
                        TextEditor(text: $text)
                            .font(.title3)
                            .scrollContentBackground(.hidden)
                            .frame(minHeight: 180)
                            .padding(8)
                            .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 20))
                            .accessibilityLabel("試打")
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
                    Button("鍵盤設定", systemImage: "keyboard") { showsSetup = true }
                }
            }
            .sheet(isPresented: $showsSetup) { setup }
            .tint(Brand.accent)
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
                Section("輸入") {
                    Picker("輸入方式", selection: setting(\.inputMode)) {
                        Text("沿用鍵盤現有設定").tag(nil as String?)
                        Text("注音").tag("zhuyin" as String?)
                        Text("拼音").tag("pinyin" as String?)
                        Text("日本語 · ローマ字").tag("japanese" as String?)
                    }
                    Picker("中文", selection: setting(\.simplified)) {
                        Text("沿用鍵盤現有設定").tag(nil as Bool?)
                        Text("繁體").tag(false as Bool?)
                        Text("简体").tag(true as Bool?)
                    }
                }
                Section("外觀") {
                    Picker("皮膚", selection: setting(\.catGlassSkin)) {
                        Text("沿用鍵盤現有設定").tag(nil as Bool?)
                        Text("雙貓玻璃").tag(true as Bool?)
                        Text("簡潔玻璃").tag(false as Bool?)
                    }
                    Picker("色調", selection: setting(\.candidateTone)) {
                        Text("沿用鍵盤現有設定").tag(nil as String?)
                        Text("跟隨系統").tag("system" as String?)
                        Text("淺色").tag("light" as String?)
                        Text("深色").tag("dark" as String?)
                    }
                    Picker("玻璃強度", selection: setting(\.glassStrength)) {
                        Text("沿用鍵盤現有設定").tag(nil as String?)
                        Text("柔和").tag("soft" as String?)
                        Text("標準").tag("balanced" as String?)
                        Text("鮮明").tag("vivid" as String?)
                    }
                    Picker("色系", selection: setting(\.candidatePalette)) {
                        Text("沿用鍵盤現有設定").tag(nil as String?)
                        Text("玫瑰奶茶").tag("rose" as String?)
                        Text("海鹽藍").tag("ocean" as String?)
                        Text("鼠尾草綠").tag("sage" as String?)
                        Text("霧紫").tag("lavender" as String?)
                        Text("琥珀蜜").tag("amber" as String?)
                        Text("石墨").tag("graphite" as String?)
                    }
                }
                Section("選詞記憶") {
                    Picker("記住選詞", selection: setting(\.rememberChoices)) {
                        Text("沿用鍵盤現有設定").tag(nil as Bool?)
                        Text("啟用").tag(true as Bool?)
                        Text("停用").tag(false as Bool?)
                    }
                    Button("清除選詞記錄", role: .destructive) { confirmClear = true }
                    Text("選詞記錄保存在鍵盤的本機空間。清除會在下次開啟喵撲鍵盤時執行。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("加入鍵盤") {
                    Text("設定 → 一般 → 鍵盤 → 鍵盤 → 加入新鍵盤 → Meowpuut").font(.subheadline)
                    Button("打開設定") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
            }
            .onAppear { settings = SharedKeyboardSettings.read() ?? SharedKeyboardSettings() }
            .alert("無法儲存設定", isPresented: $settingsError) { Button("好", role: .cancel) {} }
            .confirmationDialog("清除選詞記錄？", isPresented: $confirmClear) {
                Button("清除選詞記錄", role: .destructive) {
                    var next = settings
                    next.clearMemoryRequest = UUID().uuidString
                    save(next)
                }
            } message: { Text("下次開啟喵撲鍵盤時，將清除本機的選詞記錄。") }
            .navigationTitle("鍵盤設定")
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
            save(next)
        })
    }

    private func save(_ next: SharedKeyboardSettings) {
        do { try next.save(); settings = next }
        catch { settingsError = true }
    }
}
