import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

// Drawing for the festival wardrobe (docs/FESTIVALS.md): the art, and the
// trim laid around the candidate glass. Which theme is worn is in Festivals.swift.

enum FestivalArt {
    #if canImport(UIKit)
    /// The art ships once, in the keyboard extension's asset catalog; the
    /// app reads it from the extension it embeds.
    static let bundle: Bundle = {
        if UIImage(named: "christmas_frame", in: .main, compatibleWith: nil) != nil { return .main }
        return Bundle.main.builtInPlugInsURL.flatMap { Bundle(url: $0.appendingPathComponent("MeowpuutKeyboard.appex")) } ?? .main
    }()

    static func image(_ name: String) -> CGImage? {
        UIImage(named: name, in: bundle, compatibleWith: nil)?.cgImage
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
    var image: CGImage?

    init(trim: FestivalTheme.Trim, glass: CGRect, shape: GlassShape, image: CGImage? = nil) {
        self.trim = trim
        self.glass = glass
        self.shape = shape
        self.image = image ?? FestivalArt.image(trim.image)
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
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
