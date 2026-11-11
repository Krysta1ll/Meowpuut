import UIKit
import SwiftUI

private func hex(_ value: UInt32, _ alpha: CGFloat = 1) -> UIColor {
    UIColor(red: CGFloat((value >> 16) & 0xff) / 255, green: CGFloat((value >> 8) & 0xff) / 255,
            blue: CGFloat(value & 0xff) / 255, alpha: alpha)
}

/// Key colours (docs/DESIGN.md §5). Keys are drawn like the system keyboard's
/// on iOS 27: every key but the accent one is a light fill over the system's
/// own translucent backdrop (white in light, a faint white in dark), measured
/// against Apple's Pinyin keyboard. With Reduce Transparency or Increase
/// Contrast (`opaque`) the keys are solid and an opaque base is painted
/// under them; Increase Contrast also adds a key outline and a deeper accent
/// under the white Return label. Bold Text makes the labels heavier.
struct KeyboardKeyTheme: Equatable {
    var dark: Bool
    var highContrast: Bool
    var palette: CandidatePalette
    var bold = false
    var opaque = false

    var characterFill: UIColor {
        if opaque { return dark ? hex(0x5E5D63) : .white }
        return dark ? UIColor(white: 1, alpha: 0.18) : UIColor(white: 1, alpha: 0.9)
    }
    var characterPressed: UIColor {
        if opaque { return dark ? hex(0x7B7A80) : hex(0xDCDBE3) }
        return dark ? UIColor(white: 1, alpha: 0.36) : UIColor(white: 1, alpha: 0.45)
    }
    // As on the system keyboard, Shift, Delete and the bottom row look like the letters.
    var utilityFill: UIColor { opaque ? (dark ? hex(0x3C3B40) : hex(0xCFCED8)) : characterFill }
    var utilityPressed: UIColor { opaque ? (dark ? hex(0x5E5D63) : .white) : characterPressed }
    var accentFill: UIColor { UIColor(highContrast ? palette.contrastPill : palette.pill(dark: dark).bottom) }
    var accentPressed: UIColor { UIColor(highContrast ? palette.pill(dark: false).bottom : palette.pill(dark: dark).top) }
    var text: UIColor { dark ? .white : .black }
    var accentText: UIColor { .white }
    var disabledText: UIColor { text.withAlphaComponent(0.32) }
    var shadow: UIColor { .black }
    // A shadow would show through a translucent key.
    var shadowOpacity: Float { opaque ? (dark ? 0.55 : 0.22) : 0 }
    var outline: UIColor? { highContrast ? (dark ? hex(0xBDBCC4) : hex(0x55545C)) : nil }
    /// Key balloons and the long-press row stay solid over the keys below them.
    var balloonFill: UIColor { dark ? hex(0x6C6C70) : .white }
    var balloonOutline: UIColor { dark ? UIColor.white.withAlphaComponent(0.10) : UIColor.black.withAlphaComponent(0.06) }
    var selection: UIColor { accentFill }

    func fill(for style: KeyboardKey.Style, pressed: Bool) -> UIColor {
        switch style {
        case .character: return pressed ? characterPressed : characterFill
        case .utility: return pressed ? utilityPressed : utilityFill
        case .accent: return pressed ? accentPressed : accentFill
        }
    }
}

/// What lies under the keys and the glass. A system keyboard gets the
/// system's own translucent keyboard backdrop, so nothing is painted. With
/// Reduce Transparency or Increase Contrast an opaque pearl (light) or
/// graphite (dark) base is painted instead. The lab app has no system
/// backdrop; it puts a stand-in behind the keyboard (iOS/Lab).
struct KeyboardBackground: View {
    var dark: Bool
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        if reduceTransparency || contrast == .increased {
            LinearGradient(colors: dark
                           ? [Color(red: 0.145, green: 0.141, blue: 0.157), Color(red: 0.102, green: 0.098, blue: 0.110)]
                           : [Color(red: 0.914, green: 0.910, blue: 0.937), Color(red: 0.851, green: 0.847, blue: 0.878)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

/// Key feedback is the system click, which follows the user's keyboard
/// sound setting. iOS plays haptics from a keyboard extension only with Full
/// Access, and this keyboard does not ask for it.
enum KeyboardFeedback {
    static func keyDown() {
        UIDevice.current.playInputClick()
    }
}
