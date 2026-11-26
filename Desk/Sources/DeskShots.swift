import AppKit
import ImageIO
import UniformTypeIdentifiers

// `--shots DIR`: what the cats look like, without Screen Recording. Each visible panel is drawn from its own
// layers into a PNG, and a map of the measured world (screens, window rectangles without titles, the grounds) with
// the cats on it is drawn at half size. Local files for checking the shell; nothing is sent anywhere.

enum DeskShots {
    static func write(_ image: CGImage, to url: URL) {
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(destination, image, nil)
        CGImageDestinationFinalize(destination)
    }

    /// Writes `<tag>-mimimo.png`, `<tag>-momo.png` (visible panels only) and `<tag>-desk.png`; returns their names.
    @discardableResult
    static func capture(_ controller: DeskController, to directory: URL, tag: String) -> [String] {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var names: [String] = []
        for panel in controller.panels where panel.shown {
            if let image = panel.snapshot() {
                let name = "\(tag)-\(panel.cat.asset).png"
                write(image, to: directory.appendingPathComponent(name))
                names.append(name)
            }
        }
        if let map = deskMap(controller) {
            let name = "\(tag)-desk.png"
            write(map, to: directory.appendingPathComponent(name))
            names.append(name)
        }
        return names
    }

    /// The world as the core sees it, half size, y down like the desktop.
    static func deskMap(_ controller: DeskController, scale: CGFloat = 0.5) -> CGImage? {
        let height = WorldSensor.primaryHeight
        let screens = WorldSensor.screens(primaryHeight: height)
        guard let world = controller.lastMeasure?.world, !screens.isEmpty else { return nil }
        let union = screens.map(\.frame).reduce(screens[0].frame) { $0.union($1) }
        let width = Int(union.width * scale), pixels = Int(union.height * scale)
        guard let context = CGContext(data: nil, width: width, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        // Desktop coordinates (y down) onto the bitmap.
        context.translateBy(x: 0, y: CGFloat(pixels))
        context.scaleBy(x: scale, y: -scale)
        context.translateBy(x: -union.minX, y: -union.minY)
        context.setFillColor(NSColor(calibratedRed: 0.93, green: 0.92, blue: 0.89, alpha: 1).cgColor)
        context.fill(union)
        for s in screens {
            context.setFillColor(NSColor(calibratedRed: 0.86, green: 0.88, blue: 0.91, alpha: 1).cgColor)
            context.fill(s.frame)
            context.setFillColor(NSColor(white: 0.75, alpha: 1).cgColor)
            context.fill(CGRect(x: s.frame.minX, y: s.frame.minY, width: s.frame.width, height: s.visible.minY - s.frame.minY))
            context.fill(CGRect(x: s.frame.minX, y: s.visible.maxY, width: s.frame.width, height: s.frame.maxY - s.visible.maxY))
        }
        // Windows back to front: rectangles only.
        for w in controller.windows.reversed() {
            context.setFillColor(NSColor(white: 0.98, alpha: 1).cgColor)
            context.fill(w.frame)
            context.setStrokeColor(NSColor(white: 0.55, alpha: 1).cgColor)
            context.setLineWidth(2)
            context.stroke(w.frame)
        }
        for g in world.grounds {
            let peek = g.flags & UInt32(MP_DESK_GROUND_PEEK_ONLY) != 0
            let color: NSColor
            switch Int(g.kind) {
            case MP_DESK_GROUND_EDGE: color = .systemGreen
            case MP_DESK_GROUND_SHELF: color = .systemBlue
            case MP_DESK_GROUND_HOME: color = .systemPink
            default: color = .brown
            }
            context.setStrokeColor(color.withAlphaComponent(peek ? 0.45 : 0.9).cgColor)
            context.setLineWidth(6)
            context.setLineDash(phase: 0, lengths: peek ? [12, 8] : [])
            context.strokeLineSegments(between: [CGPoint(x: g.x0, y: g.y), CGPoint(x: g.x1, y: g.y)])
        }
        context.setLineDash(phase: 0, lengths: [])
        context.setFillColor(NSColor.systemOrange.cgColor)
        context.fillEllipse(in: CGRect(x: world.doorX - 6, y: world.wallTop - 6, width: 12, height: 12))
        // The cats where their panels are (their own drawing, flipped back upright).
        for panel in controller.panels where panel.shown {
            guard let image = panel.snapshot() else { continue }
            let top = height - panel.origin.y - CatPanel.size.height
            context.saveGState()
            context.translateBy(x: panel.origin.x, y: top + CatPanel.size.height)
            context.scaleBy(x: 1, y: -1)
            context.draw(image, in: CGRect(origin: .zero, size: CatPanel.size))
            context.restoreGState()
        }
        return context.makeImage()
    }
}
