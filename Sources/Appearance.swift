import Foundation

// Appearance settings shared by the macOS input method, the iOS keyboard and
// the iOS app's preview. docs/DESIGN.md describes what each one looks like.

enum CandidateTone: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var title: String { switch self { case .system: "跟隨系統"; case .light: "淺色"; case .dark: "深色" } }
}

enum GlassStrength: String, CaseIterable, Identifiable {
    case soft, balanced, vivid
    var id: String { rawValue }
    var title: String { switch self { case .soft: "柔和"; case .balanced: "標準"; case .vivid: "通透" } }
}

enum GlassShape: String, CaseIterable, Identifiable {
    case capsule, wave, cloud
    var id: String { rawValue }
    var title: String { switch self { case .capsule: "膠囊"; case .wave: "波浪"; case .cloud: "雲朵" } }
}

enum CandidatePalette: String, CaseIterable, Identifiable {
    case rose, ocean, sage, lavender, amber, graphite
    var id: String { rawValue }
    var title: String { switch self {
        case .rose: "玫瑰奶茶"; case .ocean: "海鹽藍"; case .sage: "鼠尾草綠"
        case .lavender: "霧紫"; case .amber: "琥珀蜜"; case .graphite: "石墨"
    } }
}

/// Typing sounds (docs/SOUNDS.md). The Mac has no system key click, so it
/// offers 關閉 and the built-in sets.
enum KeySound: String, CaseIterable {
    case off, system, water, marimba, glass, felt, penta, harmonic, tap, thock

    var isCustom: Bool { self != .off && self != .system }
    var usesKeyPosition: Bool { self == .penta || self == .thock }

    func title(_ t: (String, String) -> String) -> String {
        switch self {
        case .off: t("關閉", "关闭")
        case .system: t("系統", "系统")
        case .water: "水滴"
        case .marimba: "木琴"
        case .glass: "玻璃"
        case .felt: t("毛氈", "毛毡")
        case .penta: t("五聲", "五声")
        case .harmonic: "泛音"
        case .tap: t("輕敲", "轻敲")
        case .thock: t("沉韻", "沉韵")
        }
    }
}
