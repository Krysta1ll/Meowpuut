import AppKit
import ImageIO
import UniformTypeIdentifiers

let markURL = URL(fileURLWithPath: CommandLine.arguments[1])
guard let mark = NSImage(contentsOf: markURL) else {
    fputs("無法讀取 Meowpuut 標誌\n", stderr)
    exit(1)
}

func renderIcon(pixels: Int, rounded: Bool) throws -> Data {
    let alpha: CGImageAlphaInfo = rounded ? .premultipliedLast : .noneSkipLast
    guard let context = CGContext(
        data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: alpha.rawValue
    ) else { throw CocoaError(.fileWriteUnknown) }
    let rect = NSRect(x: 0, y: 0, width: pixels, height: pixels)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
    NSGraphicsContext.current?.imageInterpolation = .high
    NSColor(srgbRed: 0.93, green: 0.89, blue: 0.85, alpha: 1).setFill()
    if rounded {
        let inset = CGFloat(pixels) * 0.05
        let radius = CGFloat(pixels) * 0.21
        NSBezierPath(roundedRect: rect.insetBy(dx: inset, dy: inset), xRadius: radius, yRadius: radius).fill()
    } else {
        rect.fill()
    }
    let inset = CGFloat(pixels) * 0.08
    mark.draw(in: rect.insetBy(dx: inset, dy: inset), from: .zero, operation: .sourceOver, fraction: 1)
    NSGraphicsContext.restoreGraphicsState()
    let data = NSMutableData()
    guard let image = context.makeImage(),
          let encoder = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)
    else { throw CocoaError(.fileWriteUnknown) }
    CGImageDestinationAddImage(encoder, image, nil)
    guard CGImageDestinationFinalize(encoder) else { throw CocoaError(.fileWriteUnknown) }
    return data as Data
}

// The input menu templates the alpha channel. Keep the paper background out of this glyph.
func inputMenuIcon() throws -> Data {
    let image = NSImage(size: NSSize(width: 16, height: 16))
    for scale in [1, 2] {
        let pixels = 16 * scale
        guard let context = CGContext(data: nil, width: pixels, height: pixels,
                                      bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { throw CocoaError(.fileWriteUnknown) }
        context.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
        context.setStrokeColor(NSColor.black.cgColor)
        context.setFillColor(NSColor.black.cgColor)
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.setLineWidth(1.15)

        // Two overlapping heads, with the nearer cat interrupting the rear silhouette.
        context.move(to: CGPoint(x: 6.3, y: 3.1))
        context.addQuadCurve(to: CGPoint(x: 1.7, y: 8.3), control: CGPoint(x: 0.5, y: 3.5))
        context.addLine(to: CGPoint(x: 1.9, y: 13.4))
        context.addLine(to: CGPoint(x: 5.0, y: 11.2))
        context.addQuadCurve(to: CGPoint(x: 7.2, y: 11.5), control: CGPoint(x: 6, y: 11.5))
        context.addLine(to: CGPoint(x: 9.3, y: 14.6))
        context.addLine(to: CGPoint(x: 10.1, y: 10.4))
        context.strokePath()

        context.move(to: CGPoint(x: 7.7, y: 7))
        context.addLine(to: CGPoint(x: 7.8, y: 10.1))
        context.addLine(to: CGPoint(x: 10.3, y: 8.5))
        context.addQuadCurve(to: CGPoint(x: 12, y: 8.6), control: CGPoint(x: 11.1, y: 8.8))
        context.addLine(to: CGPoint(x: 14.5, y: 10.1))
        context.addLine(to: CGPoint(x: 14.6, y: 6.7))
        context.addCurve(to: CGPoint(x: 7.7, y: 7), control1: CGPoint(x: 17, y: 1), control2: CGPoint(x: 4.5, y: 1))
        context.strokePath()

        for center in [CGPoint(x: 4.2, y: 7.8), CGPoint(x: 6.3, y: 8.4),
                       CGPoint(x: 9.8, y: 5.8), CGPoint(x: 12.7, y: 5.8)] {
            context.fillEllipse(in: CGRect(x: center.x - 0.49, y: center.y - 0.65, width: 0.98, height: 1.3))
        }
        guard let glyph = context.makeImage() else { throw CocoaError(.fileWriteUnknown) }
        let rep = NSBitmapImageRep(cgImage: glyph)
        rep.size = image.size
        image.addRepresentation(rep)
    }
    guard let tiff = image.tiffRepresentation else { throw CocoaError(.fileWriteUnknown) }
    return tiff
}

let destination = URL(fileURLWithPath: CommandLine.arguments[2])
try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
if CommandLine.arguments.contains("--ios") {
    try renderIcon(pixels: 1024, rounded: false).write(to: destination.appendingPathComponent("AppIcon.png"))
} else {
    try inputMenuIcon().write(to: destination.deletingLastPathComponent().appendingPathComponent("MeowpuutInputGlyph.tiff"))
    for size in [16, 32, 128, 256, 512] {
        for scale in [1, 2] {
            let suffix = scale == 2 ? "@2x" : ""
            let file = "icon_\(size)x\(size)\(suffix).png"
            try renderIcon(pixels: size * scale, rounded: true).write(to: destination.appendingPathComponent(file))
        }
    }
}
