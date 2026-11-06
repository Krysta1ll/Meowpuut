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
                Section("加入鍵盤") {
                    Text("設定 → 一般 → 鍵盤 → 鍵盤 → 加入新鍵盤 → Meowpuut").font(.subheadline)
                    Button("打開設定") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
                Section("選詞記憶") { Text("在 Meowpuut 鍵盤中點設定，即可停用或清除選詞記錄。").font(.subheadline) }
            }
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
}
