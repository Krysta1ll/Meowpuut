import SwiftUI
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

enum Brand {
    static let paper = Color(red: 0.98, green: 0.95, blue: 0.90)
    static let accent: Color = {
        #if os(macOS)
        Color(nsColor: NSColor(name: nil) { appearance in
            if appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua {
                return NSColor(srgbRed: 1, green: 0.57, blue: 0.48, alpha: 1)
            }
            return NSColor(srgbRed: 0.72, green: 0.18, blue: 0.12, alpha: 1)
        })
        #else
        Color(uiColor: UIColor { traits in
            if traits.userInterfaceStyle == .dark {
                return UIColor(red: 1, green: 0.57, blue: 0.48, alpha: 1)
            }
            return UIColor(red: 0.72, green: 0.18, blue: 0.12, alpha: 1)
        })
        #endif
    }()
}

struct BrandMark: View {
    var size: CGFloat

    #if os(macOS)
    private static let image: NSImage? = Bundle.main.url(forResource: "MeowpuutMark", withExtension: "png")
        .flatMap { NSImage(contentsOf: $0) }
    #elseif os(iOS)
    private static let image: UIImage? = Bundle.main.url(forResource: "MeowpuutMark", withExtension: "png")
        .flatMap { UIImage(contentsOfFile: $0.path) }
    #endif

    private var image: Image {
        #if os(macOS)
        Self.image.map { Image(nsImage: $0) } ?? Image(systemName: "cat")
        #else
        Self.image.map { Image(uiImage: $0) } ?? Image(systemName: "cat")
        #endif
    }

    var body: some View {
        image
            .resizable()
            .scaledToFit()
            .padding(size * 0.07)
            .frame(width: size, height: size)
            .background(Brand.paper, in: RoundedRectangle(cornerRadius: size * 0.20))
            .accessibilityHidden(true)
    }
}
