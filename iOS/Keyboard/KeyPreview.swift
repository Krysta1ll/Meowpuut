import Foundation
import CoreGraphics

// The keyboard may be narrower than the screen (for example on iPad). Work
// in the keyboard's own coordinate space so edge keys never escape it.
enum KeyPreviewGeometry {
    static let coordinateSpace = "meowpuut.keyboard"

    static func frame(for key: CGRect, keyboardWidth: CGFloat) -> CGRect {
        let inset: CGFloat = 4
        let width = min(max(0, keyboardWidth - 2 * inset), max(46, key.width + 12))
        let height: CGFloat = 48
        let center = min(max(key.midX, inset + width / 2), max(inset + width / 2, keyboardWidth - inset - width / 2))
        return CGRect(x: center - width / 2, y: max(inset, key.minY - height + 2), width: width, height: height)
    }
}

#if canImport(SwiftUI) && canImport(UIKit)
import SwiftUI
import UIKit

// The Button continues to own the gesture: sliding out cancels both its
// normal action and its preview. The preview never participates in hit tests.
struct KeyboardCharacterPreviewStyle: ButtonStyle {
    let text: String
    let keyboardWidth: CGFloat
    let tint: Color
    let enabled: Bool
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.72 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.08), value: configuration.isPressed)
            .onChange(of: configuration.isPressed) { _, pressed in
                if pressed { UIDevice.current.playInputClick() }
            }
            .overlay(alignment: .topLeading) {
                if enabled && configuration.isPressed {
                    GeometryReader { geometry in
                        let key = geometry.frame(in: .named(KeyPreviewGeometry.coordinateSpace))
                        let bubble = KeyPreviewGeometry.frame(for: key, keyboardWidth: keyboardWidth)
                        keyPreview
                            .frame(width: bubble.width, height: bubble.height)
                            .position(x: bubble.midX - key.minX, y: bubble.midY - key.minY)
                    }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
            }
            .zIndex(configuration.isPressed ? 1 : 0)
    }

    private var keyPreview: some View {
        let accessible = reduceTransparency || contrast == .increased
        let shape = RoundedRectangle(cornerRadius: 11, style: .continuous)
        return Text(text)
            .font(.system(size: 26, weight: .medium))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .foregroundStyle(scheme == .dark ? Color.white : Color.black)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background {
                shape
                    .fill(scheme == .dark ? Color(white: 0.20) : Color(white: 0.98))
                    .opacity(accessible ? 1 : 0.92)
                    .overlay {
                        if !accessible {
                            shape.fill(LinearGradient(colors: [tint.opacity(0.18), .clear],
                                                      startPoint: .topLeading, endPoint: .bottomTrailing))
                        }
                    }
            }
            .overlay {
                shape.strokeBorder(LinearGradient(colors: [.white.opacity(scheme == .dark ? 0.55 : 0.9),
                                                            .white.opacity(0.12), .black.opacity(0.22)],
                                                   startPoint: .top, endPoint: .bottom), lineWidth: 0.8)
            }
            .shadow(color: .black.opacity(scheme == .dark ? 0.30 : 0.18), radius: 4, y: 2)
    }
}
#endif
