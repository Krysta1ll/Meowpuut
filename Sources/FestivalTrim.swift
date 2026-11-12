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

/// The trim of a theme around the candidate glass. It fills the bar, draws
/// at `glass` (in its own coordinates), takes no touches and says nothing
/// to VoiceOver. Placed in front of the glass and behind the candidates.
struct FestivalTrimView: View {
    var trim: FestivalTheme.Trim
    var glass: CGRect
    var shape: GlassShape
    /// Where the trim must not compete with the bar's own content, such as
    /// the expand arrow or a candidate that falls outside the trim's text
    /// area: the trim fades out there, with a soft edge.
    var clear: [CGRect] = []
    var image: CGImage?

    init(trim: FestivalTheme.Trim, glass: CGRect, shape: GlassShape, clear: [CGRect] = [], image: CGImage? = nil) {
        self.trim = trim
        self.glass = glass
        self.shape = shape
        self.clear = clear
        self.image = image ?? FestivalImages.image(trim.image)
    }

    var body: some View {
        Canvas { context, _ in
            guard let image, let layout = FestivalTrimLayout.place(trim, glass: glass, shape: shape) else { return }
            // Pixels per trim point in this image (@2x or @3x).
            let pixels = CGFloat(image.width) / trim.size.width
            for slice in layout.slices(trim, pixels: pixels) {
                let source = CGRect(x: (slice.source.minX * pixels).rounded(), y: 0,
                                    width: (slice.source.width * pixels).rounded(), height: CGFloat(image.height))
                guard let piece = image.cropping(to: source) else { continue }
                let destination = CGRect(x: layout.frame.minX + slice.destination.minX * layout.scale, y: layout.frame.minY,
                                         width: slice.destination.width * layout.scale, height: layout.frame.height)
                context.draw(Image(decorative: piece, scale: 1), in: destination)
            }
            // Without the middle, each end's piece of straight rim fades out
            // towards the centre instead of stopping at a cut.
            context.blendMode = .destinationOut
            if !layout.middle {
                for (cap, leftEnd) in [(trim.caps.left, true), (trim.caps.right, false)] {
                    let fade = cap * 0.45 * layout.scale
                    let edge = leftEnd ? layout.frame.minX + cap * layout.scale : layout.frame.maxX - cap * layout.scale
                    let rect = CGRect(x: leftEnd ? edge - fade : edge, y: layout.frame.minY, width: fade, height: layout.frame.height)
                    context.fill(Path(rect), with: .linearGradient(Gradient(colors: [.clear, .black]),
                                                                    startPoint: CGPoint(x: leftEnd ? rect.minX : rect.maxX, y: 0),
                                                                    endPoint: CGPoint(x: leftEnd ? rect.maxX : rect.minX, y: 0)))
                }
            }
            for rect in clear where !layout.textArea.contains(rect) {
                context.drawLayer { layer in
                    layer.addFilter(.blur(radius: 5))
                    layer.fill(Path(roundedRect: rect.insetBy(dx: -2, dy: -2), cornerRadius: 8), with: .color(.black.opacity(0.85)))
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
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
