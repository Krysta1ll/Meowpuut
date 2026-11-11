// Development only: still images of every festival theme on the candidate
// bar, for looking at the art at real size before a festival comes round.
// The bar is the shared glass (GlassShapes.swift) with the trim
// (FestivalTrim.swift) and the dressed cats; candidates are sample text in
// the phone layout. Built and run by scripts/preview-festivals.sh.
//
//   FestivalPreview MANIFEST_DIR OUT_DIR [--only ID]
import AppKit
import Metal
import QuartzCore
import SwiftUI

private let assets = URL(fileURLWithPath: CommandLine.arguments[1])

private func art(_ theme: FestivalTheme, _ name: String) -> CGImage? {
    NSImage(contentsOf: assets.appendingPathComponent(theme.id).appendingPathComponent(name + ".png"))?
        .cgImage(forProposedRect: nil, context: nil, hints: nil)
}

/// The phone bar at a given width: glass, cats on its rim, sample candidates.
private struct Bar: View {
    var theme: FestivalTheme
    var shape: GlassShape
    var dark: Bool
    var width: CGFloat = 393
    var glassHeight: CGFloat = 46
    var showSafeArea = false
    var selected = 0

    private var height: CGFloat { glassHeight + 96 }
    private var glass: CGRect {
        // iOS KeyboardGeometry, phone with cats: glass from 米米莫's body to 6 pt from the edge.
        let left = 2 + 0.24 * 62 * 0.9135
        return CGRect(x: left, y: height - 4 - glassHeight, width: width - 6 - left, height: glassHeight)
    }

    var body: some View {
        let glass = glass
        let band = FestivalTrimLayout.contentBand(shape, height: glass.height)
        let pill = dark ? theme.colors.dark : theme.colors.light
        ZStack(alignment: .topLeading) {
            (dark ? Color(red: 0.145, green: 0.141, blue: 0.157) : Color(red: 0.914, green: 0.910, blue: 0.937))
            LiquidGlassBar(style: shape, dark: dark, accessible: false, strength: .balanced, tint: CandidatePalette.rose.color(dark: dark))
                .frame(width: glass.width, height: glass.height)
                .offset(x: glass.minX, y: glass.minY)
            // The arrow, and the first candidate when it has no capsule, keep the trim off them.
            let chevron = CGRect(x: glass.maxX - 52, y: glass.minY + band.top, width: 34, height: band.bottom - band.top)
            let first = CGRect(x: glass.minX + 12, y: glass.minY + band.top, width: 60, height: band.bottom - band.top)
            FestivalTrimView(trim: theme.trim, glass: glass, shape: shape, clear: selected == 0 ? [chevron] : [chevron, first],
                             image: art(theme, theme.trim.image))
                .frame(width: width, height: height)
            if showSafeArea, let layout = FestivalTrimLayout.place(theme.trim, glass: glass, shape: shape) {
                Rectangle().stroke(Color.red, lineWidth: 0.5)
                    .frame(width: layout.textArea.width, height: layout.textArea.height)
                    .offset(x: layout.textArea.minX, y: layout.textArea.minY)
            }
            HStack(spacing: 6) {
                ForEach(Array(["你好", "妳好", "擬好", "泥嚎", "你號"].enumerated()), id: \.offset) { index, word in
                    Text(word)
                        .font(.system(size: 20))
                        .foregroundStyle(index == selected ? Color.white : (dark ? Color(white: 0.96) : Color(red: 0.227, green: 0.2, blue: 0.204)))
                        .padding(.horizontal, 10)
                        .frame(height: (glassHeight * 0.7).rounded())
                        .background {
                            if index == selected {
                                Capsule().fill(LinearGradient(colors: [hex(pill[0]), hex(pill[1])], startPoint: .top, endPoint: .bottom))
                            }
                        }
                }
            }
            .frame(height: band.bottom - band.top)
            .offset(x: glass.minX + 12, y: glass.minY + band.top)
            Image(systemName: "chevron.down")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(dark ? Color.white.opacity(0.8) : Color.black.opacity(0.55))
                .frame(width: 34, height: band.bottom - band.top)
                .offset(x: glass.maxX - 52, y: glass.minY + band.top)
            cat(theme.mimimo, x: glass.minX + 40, rim: glass.minY + band.top)
            cat(theme.momo, x: glass.maxX - 44, rim: glass.minY + band.top)
        }
        .frame(width: width, height: height)
    }

    /// A cat with its feet on the glass's upper rim.
    @ViewBuilder private func cat(_ sprite: FestivalTheme.Sprite, x: CGFloat, rim: CGFloat) -> some View {
        if let image = art(theme, sprite.image) {
            Image(decorative: image, scale: 3)
                .resizable()
                .frame(width: sprite.size.width, height: sprite.size.height)
                .offset(x: x - sprite.anchor.x * sprite.size.width, y: rim + 3 - sprite.anchor.y * sprite.size.height)
        }
    }

    private func hex(_ text: String) -> Color {
        let v = UInt32(text.dropFirst(), radix: 16) ?? 0
        return Color(red: Double(v >> 16 & 0xff) / 255, green: Double(v >> 8 & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }
}

private struct Sheet: View {
    var theme: FestivalTheme

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(theme.names.hant)  \(theme.id)").font(.system(size: 13, weight: .semibold)).padding(8)
            ForEach(GlassShape.allCases) { shape in
                HStack(spacing: 0) {
                    Bar(theme: theme, shape: shape, dark: false)
                    Bar(theme: theme, shape: shape, dark: true)
                }
            }
            HStack(spacing: 0) {
                Bar(theme: theme, shape: .capsule, dark: false, selected: 1)
                Bar(theme: theme, shape: .capsule, dark: true, selected: 1)
            }
            Text("landscape (glass 36 pt) · narrow 320 pt phone · iPad (glass 53 pt) · safe text area in red").font(.system(size: 11)).padding(8)
            HStack(alignment: .bottom, spacing: 0) {
                Bar(theme: theme, shape: .capsule, dark: false, width: 520, glassHeight: 36, showSafeArea: true)
                Bar(theme: theme, shape: .capsule, dark: true, width: 320, glassHeight: 41.4, showSafeArea: true)
            }
            Bar(theme: theme, shape: .capsule, dark: false, width: 840, glassHeight: 53, showSafeArea: true)
        }
        .background(Color.white)
        .fixedSize()
    }
}

/// Draws the layer tree off screen; unlike ImageRenderer this keeps the glass
/// (the same approach as scripts/GlassPreview.swift).
private func render<V: View>(_ view: V, scale: CGFloat) -> NSBitmapImageRep? {
    let host = NSHostingView(rootView: view)
    host.frame = NSRect(origin: .zero, size: host.fittingSize)
    let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
    window.contentView = host
    host.layoutSubtreeIfNeeded()
    host.display()
    CATransaction.flush()
    RunLoop.main.run(until: Date().addingTimeInterval(0.35))
    guard let device = MTLCreateSystemDefaultDevice(), let layer = host.layer else { return nil }
    let width = Int((host.bounds.width * scale).rounded()), height = Int((host.bounds.height * scale).rounded())
    let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: width, height: height, mipmapped: false)
    descriptor.usage = [.renderTarget, .shaderRead]
    descriptor.storageMode = .shared
    guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
    let renderer = CARenderer(mtlTexture: texture, options: nil)
    renderer.bounds = CGRect(x: 0, y: 0, width: width, height: height)
    let root = CALayer()
    root.bounds = renderer.bounds
    root.anchorPoint = .zero
    root.sublayerTransform = CATransform3DMakeScale(scale, scale, 1)
    root.addSublayer(layer)
    renderer.layer = root
    CATransaction.flush()
    renderer.beginFrame(atTime: CACurrentMediaTime(), timeStamp: nil)
    renderer.addUpdate(renderer.bounds)
    renderer.render()
    renderer.endFrame()
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    var previous = [UInt8]()
    for _ in 0..<40 {
        usleep(20_000)
        texture.getBytes(&bytes, bytesPerRow: width * 4, from: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0)
        if bytes == previous { break }
        previous = bytes
    }
    guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8,
                                     samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                     bytesPerRow: width * 4, bitsPerPixel: 32), let pixels = rep.bitmapData else { return nil }
    for y in 0..<height {
        let source = (height - 1 - y) * width * 4
        let line = pixels + y * width * 4
        for x in 0..<width {
            line[x * 4] = bytes[source + x * 4 + 2]; line[x * 4 + 1] = bytes[source + x * 4 + 1]
            line[x * 4 + 2] = bytes[source + x * 4]; line[x * 4 + 3] = bytes[source + x * 4 + 3]
        }
    }
    return rep
}

@main
struct FestivalPreview {
    static func main() {
        setbuf(stdout, nil)
        let arguments = CommandLine.arguments
        guard arguments.count >= 3 else {
            fputs("usage: FestivalPreview MANIFEST_DIR OUT_DIR [--only ID]\n", stderr)
            exit(2)
        }
        _ = NSApplication.shared
        Festivals.directoryOverride = assets
        let out = arguments[2]
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
        let only = arguments.firstIndex(of: "--only").map { arguments[$0 + 1] }
        var failed = false
        for (index, theme) in Festivals.bundled.themes.enumerated() where only == nil || only == theme.id {
            let path = String(format: "%@/%02d-%@.png", out, index + 1, theme.id)
            guard let rep = render(Sheet(theme: theme), scale: 3), let data = rep.representation(using: .png, properties: [:]),
                  (try? data.write(to: URL(fileURLWithPath: path))) != nil else {
                fputs("failed: \(theme.id)\n", stderr)
                failed = true
                continue
            }
            print(path)
        }
        exit(failed ? 1 : 0)
    }
}
