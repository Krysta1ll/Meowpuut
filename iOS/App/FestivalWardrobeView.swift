import SwiftUI

/// The 節日衣櫥 row in the keyboard settings and the page it opens
/// (docs/FESTIVALS.md). The page lists only the festivals this person has
/// already met, so nothing still to come is given away.
struct FestivalWardrobeLink: View {
    @Binding var settings: SharedKeyboardSettings
    private let t = InterfaceText.system

    var body: some View {
        NavigationLink(t("節日衣櫥", "节日衣橱")) { FestivalWardrobeView(settings: $settings) }
    }
}

enum FestivalWardrobeSince {
    private static let key = "festivalWardrobeSince"

    /// The first day this app had the wardrobe, written once at launch. The
    /// keyboard cannot write to the shared container without Full Access, so
    /// the app keeps this and works out from the calendar what has been met.
    static func record(today: String = Festivals.day(Date())) {
        if UserDefaults.standard.string(forKey: key) == nil { UserDefaults.standard.set(today, forKey: key) }
    }

    static var day: String { UserDefaults.standard.string(forKey: key) ?? Festivals.day(Date()) }
}

struct FestivalWardrobeView: View {
    @Binding var settings: SharedKeyboardSettings
    @State private var saveFailed = false
    private let t = InterfaceText.system

    private var on: Bool { settings.festivals ?? true }
    private var skipped: Set<String> { Set(settings.festivalsSkipped ?? []) }

    var body: some View {
        Form {
            Section {
                Toggle(t("跟著節日換裝", "跟着节日换装"), isOn: Binding(get: { on }, set: { value in
                    var next = settings
                    next.festivals = value
                    save(next)
                }))
            } footer: {
                Text(t("節日期間，米米莫和莫莫會換上節日的衣服。關閉後一直穿平常的樣子。",
                       "节日期间，米米莫和莫莫会换上节日的衣服。关闭后一直穿平常的样子。"))
            }
            if on {
                Section {
                    ForEach(Festivals.met(since: FestivalWardrobeSince.day, today: Festivals.day(Date()))) { theme in
                        Toggle(theme.name(simplified: t.simplified), isOn: Binding(get: { !skipped.contains(theme.id) }, set: { wear in
                            var next = settings
                            var ids = skipped
                            if wear { ids.remove(theme.id) } else { ids.insert(theme.id) }
                            next.festivalsSkipped = ids.sorted()
                            save(next)
                        }))
                    }
                } footer: {
                    Text(t("還沒遇到的節日不會列出。", "还没遇到的节日不会列出。"))
                }
            }
        }
        .navigationTitle(t("節日衣櫥", "节日衣橱"))
        .navigationBarTitleDisplayMode(.inline)
        .alert(t("無法儲存設定", "无法保存设置"), isPresented: $saveFailed) { Button("好", role: .cancel) {} }
    }

    private func save(_ next: SharedKeyboardSettings) {
        do { try next.save(); settings = next } catch { saveFailed = true }
    }
}
