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

// The candidate glass outline (docs/DESIGN.md §2). Dark is an appearance,
// not a shape: every outline has a dark variant.
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
