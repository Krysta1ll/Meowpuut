import UIKit
import SwiftUI

private func hex(_ value: UInt32, _ alpha: CGFloat = 1) -> UIColor {
    UIColor(red: CGFloat((value >> 16) & 0xff) / 255, green: CGFloat((value >> 8) & 0xff) / 255,
            blue: CGFloat(value & 0xff) / 255, alpha: alpha)
}

/// Key colours (docs/DESIGN.md §5). Keys are opaque, so Reduce Transparency
/// changes nothing here; Increase Contrast adds a key outline and a deeper
/// accent under the white Return label, and Bold Text makes the labels heavier.
struct KeyboardKeyTheme: Equatable {
    var dark: Bool
    var highContrast: Bool
    var palette: CandidatePalette
    var bold = false

    var characterFill: UIColor { dark ? hex(0x5E5D63) : .white }
    var characterPressed: UIColor { dark ? hex(0x7B7A80) : hex(0xDCDBE3) }
    var utilityFill: UIColor { dark ? hex(0x3C3B40) : hex(0xCFCED8) }
    var utilityPressed: UIColor { dark ? hex(0x5E5D63) : .white }
    var accentFill: UIColor { UIColor(highContrast ? palette.contrastPill : palette.pill(dark: dark).bottom) }
    var accentPressed: UIColor { UIColor(highContrast ? palette.pill(dark: false).bottom : palette.pill(dark: dark).top) }
    var text: UIColor { dark ? .white : hex(0x18181B) }
    var accentText: UIColor { .white }
    var disabledText: UIColor { text.withAlphaComponent(0.32) }
    var shadow: UIColor { .black }
    var shadowOpacity: Float { dark ? 0.55 : 0.22 }
    var outline: UIColor? { highContrast ? (dark ? hex(0xBDBCC4) : hex(0x55545C)) : nil }
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

/// The pearl (light) or graphite (dark) ground behind keys and glass.
struct KeyboardBackground: View {
    var dark: Bool

    var body: some View {
        LinearGradient(colors: dark
                       ? [Color(red: 0.145, green: 0.141, blue: 0.157), Color(red: 0.102, green: 0.098, blue: 0.110)]
                       : [Color(red: 0.914, green: 0.910, blue: 0.937), Color(red: 0.851, green: 0.847, blue: 0.878)],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
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
