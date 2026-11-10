import SwiftUI

// Shared palette for both candidate skins; paired values keep light and dark readable.
extension CandidatePalette {
    func color(dark: Bool) -> Color {
        let rgb: (Double, Double, Double) = switch self {
        case .rose: dark ? (0.90, 0.59, 0.53) : (0.57, 0.27, 0.24)
        case .ocean: dark ? (0.44, 0.73, 0.94) : (0.16, 0.40, 0.65)
        case .sage: dark ? (0.61, 0.78, 0.63) : (0.28, 0.46, 0.33)
        case .lavender: dark ? (0.76, 0.66, 0.94) : (0.46, 0.33, 0.65)
        case .amber: dark ? (0.94, 0.75, 0.42) : (0.59, 0.38, 0.12)
        case .graphite: dark ? (0.76, 0.80, 0.85) : (0.32, 0.37, 0.43)
        }
        return Color(red: rgb.0, green: rgb.1, blue: rgb.2)
    }
}
