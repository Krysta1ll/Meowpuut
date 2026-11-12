import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

// Drawing for the festival wardrobe (docs/FESTIVALS.md): the art, and the
// trim laid around the candidate glass. Which theme is worn is in Festivals.swift.

enum FestivalImages {
    #if canImport(UIKit)
    /// The art ships once, in the keyboard extension's asset catalog; the
    /// app reads it from the extension it embeds.
    static let bundle: Bundle = Bundle.main.builtInPlugInsURL.flatMap { Bundle(url: $0.appendingPathComponent("MeowpuutKeyboard.appex")) } ?? .main

    /// Asked only for the theme being worn: the keyboard has a tight memory
    /// ceiling, so at most one theme's three images are in use.

    static func image(_ name: String) -> CGImage? {
        UIImage(named: name, in: bundle, with: nil)?.cgImage
    }
    #else
    /// macOS keeps @1x and @2x PNGs next to the manifest; the larger one is drawn.
    static func image(_ name: String) -> CGImage? {
        guard let folder = Festivals.directory else { return nil }
        for file in [name + "@2x.png", name + ".png"] {
            if let image = NSImage(contentsOf: folder.appendingPathComponent(file)) {
                return image.cgImage(forProposedRect: nil, context: nil, hints: nil)
            }
        }
        return nil
    }
    #endif
}

/// A theme's ornament clusters at the two ends of the candidate bar
/// (FestivalEndsLayout). Takes no touches and says nothing to VoiceOver.
struct FestivalEndsView: View {
    var placed: [(image: String, frame: CGRect)]

    var body: some View {
        Canvas { context, _ in
            for piece in placed {
                guard let image = FestivalImages.image(piece.image) else { continue }
                context.draw(Image(decorative: image, scale: 1), in: piece.frame)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

extension FestivalTheme {
    /// The dressed cat as the bar's stage takes it: seated behind the glass
    /// like the everyday poses, the edge across its chest, no paws in front.
    func catSprite(_ cat: KeyboardCat) -> CatSprite? {
        let sprite = cat == .mimimo ? mimimo : momo
        guard let bust = sprite.bust else { return nil }
        let b = bust.bounds
        return CatSprite(name: sprite.image, size: sprite.size, anchor: CGPoint(x: (bust.cut[0] + bust.cut[1]) / 2, y: bust.row),
                         bounds: CGRect(x: b[0], y: b[1], width: b[2], height: b[3]), cut: bust.cut[0]...bust.cut[1], paws: [])
    }
}

extension FestivalTheme {
    /// The selected capsule's lower colour in this appearance; CandidatePill
    /// draws its top from it. Increase Contrast keeps the palette's own pill.
    func pill(dark: Bool) -> Color? {
        guard let hex = (dark ? colors.dark : colors.light).last, hex.count == 7, let value = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        return Color(red: Double(value >> 16 & 0xFF) / 255, green: Double(value >> 8 & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }
}
