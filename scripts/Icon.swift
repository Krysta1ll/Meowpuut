import AppKit
import ImageIO
import UniformTypeIdentifiers

// The icon art (assets/brand/AppIcon.png, a full square: the two cats' heads close up) sits next to
// the mark that is passed in.
let markURL = URL(fileURLWithPath: CommandLine.arguments[1])
guard let art = NSImage(contentsOf: markURL.deletingLastPathComponent().appendingPathComponent("AppIcon.png")) else {
    fputs("無法讀取 Meowpuut 圖示\n", stderr)
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
    if rounded {
        // macOS: the art inside the system's rounded tile (824 of 1024 points), with a soft shadow.
        // iOS takes the full opaque square and rounds it itself.
        let side = CGFloat(pixels) * 824 / 1024
        let tile = NSRect(x: (CGFloat(pixels) - side) / 2, y: (CGFloat(pixels) - side) / 2, width: side, height: side)
        let shape = NSBezierPath(roundedRect: tile, xRadius: side * 0.225, yRadius: side * 0.225)
        NSGraphicsContext.saveGraphicsState()
        let shadow = NSShadow()
        shadow.shadowColor = NSColor.black.withAlphaComponent(0.28)
        shadow.shadowBlurRadius = CGFloat(pixels) * 0.022
        shadow.shadowOffset = NSSize(width: 0, height: -CGFloat(pixels) * 0.012)
        shadow.set()
        NSColor.white.setFill()
        shape.fill()
        NSGraphicsContext.restoreGraphicsState()
        shape.addClip()
        art.draw(in: tile, from: .zero, operation: .sourceOver, fraction: 1)
    } else {
        art.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1)
    }
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

        // A single open cat silhouette remains legible at menu-bar scale.
        context.move(to: CGPoint(x: 2.0, y: 8.5))
        context.addLine(to: CGPoint(x: 2.1, y: 13.4))
        context.addQuadCurve(to: CGPoint(x: 5.5, y: 11.3), control: CGPoint(x: 2.4, y: 14.1))
        context.addQuadCurve(to: CGPoint(x: 10.5, y: 11.3), control: CGPoint(x: 8, y: 12.2))
        context.addQuadCurve(to: CGPoint(x: 13.9, y: 13.4), control: CGPoint(x: 13.6, y: 14.1))
        context.addLine(to: CGPoint(x: 14, y: 8.5))
        context.addCurve(to: CGPoint(x: 2, y: 8.5), control1: CGPoint(x: 15.5, y: 0.8), control2: CGPoint(x: 0.5, y: 0.8))
        context.closePath()
        context.strokePath()
        for x in [5.5, 10.5] {
            context.fillEllipse(in: CGRect(x: x - 0.65, y: 6.7, width: 1.3, height: 2.0))
        }
        guard let glyph = context.makeImage() else { throw CocoaError(.fileWriteUnknown) }
        let rep = NSBitmapImageRep(cgImage: glyph)
        precondition(rep.colorAt(x: 0, y: 0)!.alphaComponent == 0, "Template icon must have transparent corners")
        let coverage = (0..<pixels).flatMap { y in (0..<pixels).map { x in rep.colorAt(x: x, y: y)!.alphaComponent } }.filter { $0 > 0.5 }.count
        precondition(coverage > pixels * pixels / 10 && coverage < pixels * pixels / 2, "Cat glyph must not become an opaque square")
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
    try inputMenuIcon().write(to: destination.deletingLastPathComponent().appendingPathComponent("MeowpuutCatTemplate.tiff"))
    for size in [16, 32, 128, 256, 512] {
        for scale in [1, 2] {
            let suffix = scale == 2 ? "@2x" : ""
            let file = "icon_\(size)x\(size)\(suffix).png"
            try renderIcon(pixels: size * scale, rounded: true).write(to: destination.appendingPathComponent(file))
        }
    }
}
