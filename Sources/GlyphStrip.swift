import AppKit
import CoreText

#if MEOWPUUT_GLYPH

// The 字景 candidate window (design-preview/pc/FINAL.md §3, wardrobe item No.04). Rust (`mp_glyph_strip_*`, the
// "Glyph strip" section of meowpuut.h) draws the land, the sun, the reflection block, the cats, the bird, the arrow
// and the frame; this file measures the words, sets them in 俐方體 11 with Core Text and puts everything on screen.
//
// Layers, bottom to top: LAND (opaque picture), OVER (reflection block, light, arrow, frame), CATS (cats, bird) and
// one small layer a candidate for its words. A letter key redraws CATS (and the words when they changed); ← → Tab
// only move `contentsRect` over the LAND strip drawn ahead (one frame a word, side by side) and redraw OVER and CATS;
// the LAND itself is drawn when the foot comes down (0.6 of the time since the key before, 60–160 ms later).
// Per-key cost on the main thread: `--glyph-shots` prints it.

/// One candidate as the strip shows it.
struct GlyphCellSpec: Equatable {
    var index: Int          // the engine's candidate index (what a click chooses)
    var number: String      // the digit of its key
    var text: String
    var japanese: Bool
}

// MARK: Type

/// 俐方體 11 ("Meowpuut Pixel A") with the fusion face behind it for what it lacks (Android ships the same files,
/// THIRD_PARTY.md §10), then the system font. Rendered with antialiasing off at 12 × k px, one mask a string.
enum GlyphType {
    private static var fonts: [Int: CTFont] = [:]
    private static var masks: [MaskKey: GlyphMask] = [:]
    private struct MaskKey: Hashable { var text: String; var k: Int }

    private static let faces: (a: CTFontDescriptor?, b: CTFontDescriptor?) = {
        func load(_ name: String, _ ext: String) -> CTFontDescriptor? {
            let url = Bundle.main.url(forResource: name, withExtension: ext, subdirectory: "Fonts")
                ?? Bundle.main.resourceURL?.appendingPathComponent("Fonts/\(name).\(ext)")
            guard let url, FileManager.default.fileExists(atPath: url.path) else { return nil }
            return (CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor])?.first
        }
        return (load("pixel_a", "ttf"), load("pixel_b", "otf"))
    }()

    /// True when the bundled faces were found (otherwise the system font stands in).
    static var bundled: Bool { faces.a != nil }

    static func font(size: CGFloat) -> CTFont {
        let key = Int(size.rounded())
        if let hit = fonts[key] { return hit }
        let system = CTFontDescriptorCreateWithNameAndSize("PingFang TC" as CFString, size)
        let font: CTFont
        if let a = faces.a {
            let cascade = [faces.b, system].compactMap { $0 }
            let descriptor = CTFontDescriptorCreateCopyWithAttributes(a, [kCTFontCascadeListAttribute: cascade] as CFDictionary)
            font = CTFontCreateWithFontDescriptor(descriptor, size, nil)
        } else {
            font = CTFontCreateWithFontDescriptor(system, size, nil)
        }
        fonts[key] = font
        return font
    }

    private static func line(_ text: String, size: CGFloat) -> CTLine {
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font(size: size),
            NSAttributedString.Key(kCTForegroundColorFromContextAttributeName as String): true,
        ]
        return CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
    }

    /// The width at 12 px, in points (what mp_glyph_strip_set_cells takes; Rust rounds it up).
    static func width(_ text: String) -> Double {
        text.isEmpty ? 0 : CTLineGetTypographicBounds(line(text, size: 12), nil, nil, nil)
    }

    /// The pixels `text` sets at 12 × k px, relative to its pen and baseline.
    static func mask(_ text: String, k: Int) -> GlyphMask {
        let key = MaskKey(text: text, k: k)
        if let hit = masks[key] { return hit }
        if masks.count > 1024 { masks.removeAll(keepingCapacity: true) }
        let advance = text.isEmpty ? 0 : CTLineGetTypographicBounds(line(text, size: CGFloat(12 * k)), nil, nil, nil)
        let left = 6 * k, above = 16 * k, below = 6 * k
        let width = Int(advance.rounded(.up)) + 2 * left, height = above + below
        var bytes = [UInt8](repeating: 0, count: width * height)
        var dots: [GlyphMask.Dot] = []
        bytes.withUnsafeMutableBytes { raw in
            guard let context = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                                          space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return }
            context.setShouldAntialias(false)
            context.setAllowsFontSmoothing(false)
            context.setShouldSmoothFonts(false)
            context.setShouldSubpixelPositionFonts(false)
            context.setShouldSubpixelQuantizeFonts(false)
            context.setFillColor(gray: 1, alpha: 1)
            context.textPosition = CGPoint(x: CGFloat(left), y: CGFloat(below))
            CTLineDraw(line(text, size: CGFloat(12 * k)), context)
        }
        for row in 0..<height {
            for column in 0..<width where bytes[row * width + column] > 127 {
                dots.append(GlyphMask.Dot(dx: Int32(column - left), dy: Int32(row - above)))
            }
        }
        // The halo: the mask at every offset (a, b) × k, once, as one list of pixels (a key then stamps that list).
        let pad = 3 * k
        let gw = width + 2 * pad, gh = height + 2 * pad
        var grid = [Bool](repeating: false, count: gw * gh)
        for dot in dots {
            for (a, b) in GlyphWords.haloOffsets {
                let x = Int(dot.dx) + left + pad + a * k, y = Int(dot.dy) + above + pad + b * k
                if x >= 0, x < gw, y >= 0, y < gh { grid[y * gw + x] = true }
            }
        }
        var halo: [GlyphMask.Dot] = []
        for y in 0..<gh { for x in 0..<gw where grid[y * gw + x] { halo.append(GlyphMask.Dot(dx: Int32(x - left - pad), dy: Int32(y - above - pad))) } }
        let mask = GlyphMask(dots: dots, halo: halo, advance: advance)
        masks[key] = mask
        return mask
    }
}

struct GlyphMask {
    struct Dot { var dx: Int32; var dy: Int32 }
    var dots: [Dot]
    /// `dots` grown by the halo's offsets.
    var halo: [Dot]
    var advance: Double
}

/// A candidate's words (number, text) as pixels: the halo first (the text again at every offset (a, b) × k with
/// 0 < a² + b² ≤ 10), then the ink.
enum GlyphWords {
    static let haloOffsets: [(Int, Int)] = {
        var list: [(Int, Int)] = []
        for a in -3...3 { for b in -3...3 where (a != 0 || b != 0) && a * a + b * b <= 10 { list.append((a, b)) } }
        return list
    }()

    static func premultiplied(_ argb: UInt32) -> UInt32 {
        let a = argb >> 24
        if a == 255 { return argb }
        func scale(_ v: UInt32) -> UInt32 { (v * a + 127) / 255 }
        return a << 24 | scale(argb >> 16 & 255) << 16 | scale(argb >> 8 & 255) << 8 | scale(argb & 255)
    }

    private static var images: [String: CGImage] = [:]

    /// The image of some words: `width` × `height` points at `k` pixels a point, `baseline` points from its top. The
    /// same words in the same colours at the same place are the same image.
    static func image(parts all: [(text: String, pen: Double, color: UInt32)], halo: UInt32, width: Int, height: Int, baseline: Double, k: Int) -> CGImage? {
        let parts = all.filter { !$0.text.isEmpty }
        let key = parts.map { "\($0.text)@\($0.pen)#\($0.color)" }.joined(separator: "|") + "|\(halo)|\(width)x\(height)|\(baseline)|\(k)"
        if let hit = images[key] { return hit }
        let w = width * k, h = height * k
        let base = Int((baseline * Double(k)).rounded())
        let buffer = UnsafeMutablePointer<UInt32>.allocate(capacity: w * h)
        buffer.initialize(repeating: 0, count: w * h)
        func stamp(_ dots: [GlyphMask.Dot], pen: Int, color: UInt32) {
            let paint = premultiplied(color)
            for dot in dots {
                let x = pen + Int(dot.dx), y = base + Int(dot.dy)
                if x >= 0, x < w, y >= 0, y < h { buffer[y * w + x] = paint }
            }
        }
        let masks = parts.map { ($0, GlyphType.mask($0.text, k: k), Int(($0.pen * Double(k)).rounded())) }
        if halo != 0 { for (_, mask, pen) in masks { stamp(mask.halo, pen: pen, color: halo) } }
        for (part, mask, pen) in masks { stamp(mask.dots, pen: pen, color: part.color) }
        let image = GlyphPixels.image(buffer, width: w, height: h)
        if let image {
            if images.count > 400 { images.removeAll(keepingCapacity: true) }
            images[key] = image
        }
        return image
    }
}

enum GlyphPixels {
    /// A BGRA premultiplied image over `buffer` (allocated with `UnsafeMutablePointer<UInt32>`; freed with the image).
    static func image(_ buffer: UnsafeMutablePointer<UInt32>, width: Int, height: Int) -> CGImage? {
        guard let provider = CGDataProvider(dataInfo: nil, data: buffer, size: width * height * 4,
                                            releaseData: { _, data, _ in UnsafeMutableRawPointer(mutating: data).deallocate() }) else {
            buffer.deallocate(); return nil
        }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }
}

// MARK: The look

struct GlyphLook: Equatable {
    var dark: Bool
    var safe: Bool
    var still: Bool
    var level = 2

    /// The system's appearance and accessibility settings; the candidate tone of the settings wins over the appearance.
    static func current(appearance: NSAppearance?, tone: CandidateTone) -> GlyphLook {
        let workspace = NSWorkspace.shared
        let dark: Bool
        switch tone {
        case .light: dark = false
        case .dark: dark = true
        case .system: dark = (appearance ?? NSApp?.effectiveAppearance ?? NSAppearance.currentDrawing()).bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        }
        return GlyphLook(dark: dark, safe: workspace.accessibilityDisplayShouldIncreaseContrast || workspace.accessibilityDisplayShouldReduceTransparency,
                         still: workspace.accessibilityDisplayShouldReduceMotion)
    }
}

// MARK: The view

private final class GlyphCellElement: NSAccessibilityElement {
    var press: () -> Void = {}
    override func accessibilityPerformPress() -> Bool { press(); return true }
}

final class GlyphStripView: NSView {
    private let handle: OpaquePointer
    /// The strip is used on one thread at a time: the main thread's calls and the background frames take this.
    private let lock = NSLock()
    private let buildQueue = DispatchQueue(label: "meowpuut.glyph.long", qos: .userInitiated)

    private let landLayer = CALayer(), overLayer = CALayer(), catsLayer = CALayer()
    private let pagerLayer = CALayer()
    private let shadowLayer = CAShapeLayer()
    /// Expanded with more pages: the page and whether it is the last. The glass has page buttons under its panel; the
    /// strip has `< 2 >` at the foot of its frame, in the same type.
    private var pager: (page: Int, last: Bool)?
    /// The two buttons' rectangles, in strip points, for a click.
    private var pagerHit: (prev: CGRect, next: CGRect)?
    private var wordLayers: [CALayer] = []
    private var wordKeys: [String] = []

    var onChoose: (Int) -> Void = { _ in }
    var onToggle: () -> Void = {}
    /// 0xff55 (Page Up) or 0xff56 (Page Down): the same keys the glass's page buttons send.
    var onPage: (Int32) -> Void = { _ in }
    /// The right-click menu of the glass window.
    var menuBuilder: (() -> NSMenu)?
    var canExpand = false
    var isExpanded: Bool { expanded }

    // What the strip was given.
    private var look: GlyphLook?
    private var scale: CGFloat = 0
    private var specs: [GlyphCellSpec] = []
    private var zhuyin = false
    /// A full-screen app is in front: the one-line strip, nothing moves (FINAL.md §3).
    private var game = false
    private var expanded = false
    private var selected = 0
    private var lastPreedit = -1
    private var lastKey: CFAbsoluteTime = 0
    private(set) var k = 1
    private var backing: CGFloat = 1
    private var pixel = (w: 0, h: 0)
    private(set) var cells: [MPGlyphStripCell] = []

    // The land drawn ahead.
    private var epoch = 0
    private var landSticky = false
    private var longImage: CGImage?
    private var longEpoch = -1
    private var longCount = 0
    private var buildWork: DispatchWorkItem?

    private var landWork: DispatchWorkItem?
    private var timer: Timer?
    private(set) var shown = false

    /// Counters for the smoke checks.
    private(set) var landRenders = 0

    override var isFlipped: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override init(frame: NSRect) {
        handle = mp_glyph_strip_create()
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = nil
        prepare(shadowLayer)
        shadowLayer.actions?["path"] = NSNull(); shadowLayer.actions?["fillColor"] = NSNull()
        self.layer?.addSublayer(shadowLayer)
        for layer in [landLayer, overLayer, catsLayer, pagerLayer] { prepare(layer); self.layer?.addSublayer(layer) }
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError("not used") }

    deinit {
        timer?.invalidate()
        lock.lock(); mp_glyph_strip_free(handle); lock.unlock()
    }

    private func prepare(_ layer: CALayer) {
        layer.anchorPoint = .zero
        layer.magnificationFilter = .nearest
        layer.minificationFilter = .nearest
        layer.isOpaque = false
        layer.actions = ["contents": NSNull(), "contentsRect": NSNull(), "bounds": NSNull(), "position": NSNull(), "hidden": NSNull()]
    }

    /// k and the strip's size in view points for a display of this backing scale.
    private var factor: CGFloat { CGFloat(k) / backing }
    /// The size the view needs, in points.
    var plainSize: CGSize { CGSize(width: CGFloat(pixel.w) / backing, height: CGFloat(pixel.h) / backing) }
    /// The prototype's hard drop shadow (`drop-shadow(2px 2px 0 …)`): the strip's stepped outline again, 2 pt right and
    /// 2 pt down, no blur, black at 0.2 (0.6 by night). The window has room for it; the window's own shadow stays off.
    var shadowInset: CGFloat { 2 * factor }
    /// The size the window needs: the strip and its shadow.
    var stripSize: CGSize { CGSize(width: plainSize.width + shadowInset, height: plainSize.height + shadowInset) }
    /// From the view's left edge to the first candidate.
    var firstLead: CGFloat { cells.first.map { CGFloat($0.x) * factor } ?? 14 }

    // MARK: Update

    /// Gives the strip what is on the candidate list now and draws what changed.
    func update(specs newSpecs: [GlyphCellSpec], selected newSelected: Int, expanded newExpanded: Bool, zhuyin newZhuyin: Bool,
                look newLook: GlyphLook, backingScale: CGFloat, preedit: Int, paging: (page: Int, last: Bool)? = nil, game: Bool = false, characters: Int64? = nil, now: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()) {
        lock.lock()
        var structural = false
        // A change of look, size or distance draws the land at once; the words of a key do not (see `Cause.typing`).
        var hard = false
        pager = paging
        if let characters { mp_glyph_strip_set_characters(handle, characters); structural = true; hard = true }
        if look != newLook {
            look = newLook
            mp_glyph_strip_set_theme(handle, newLook.dark ? 1 : 0, Int32(newLook.level), newLook.still ? 1 : 0, newLook.safe ? 1 : 0)
            structural = true; hard = true
        }
        let scaleNow = max(1, backingScale)
        if scale != scaleNow {
            scale = scaleNow
            backing = scaleNow
            let before = k
            k = Int(max(1, mp_glyph_strip_set_scale(handle, Double(scaleNow))))
            if k != before { structural = true; hard = true }
        }
        if specs != newSpecs || zhuyin != newZhuyin || self.game != game {
            specs = newSpecs
            zhuyin = newZhuyin
            self.game = game
            // The masks (and their halos) are made in the same pass as the widths, so the key's drawing finds them.
            let measured = newSpecs.map { spec -> MPGlyphStripCellIn in
                _ = GlyphType.mask(spec.number, k: k); _ = GlyphType.mask(spec.text, k: k)
                return MPGlyphStripCellIn(number_w: GlyphType.width(spec.number), text_w: GlyphType.width(spec.text),
                                          paw_w: spec.japanese ? Double(MP_GLYPH_STRIP_PAW_W) : 0)
            }
            mp_glyph_strip_set_options(handle, (newZhuyin ? UInt32(MP_GLYPH_STRIP_ZHUYIN) : 0) | (game ? UInt32(MP_GLYPH_STRIP_GAME) : 0), 0)
            _ = measured.withUnsafeBufferPointer { mp_glyph_strip_set_cells(handle, $0.baseAddress, $0.count) }
            structural = true
        }
        if expanded != newExpanded { expanded = newExpanded; mp_glyph_strip_expand(handle, newExpanded ? 1 : 0); structural = true; hard = true }
        var cause = Cause.other
        if !structural {
            if selected != newSelected { cause = .select }
        }
        if selected != newSelected || structural { selected = newSelected; mp_glyph_strip_select(handle, max(0, newSelected)) }
        // The key: a longer composition is a letter, a shorter one a delete.
        var typed = false
        if lastPreedit >= 0, preedit != lastPreedit {
            mp_glyph_strip_key(handle, preedit > lastPreedit ? UInt32(MP_GLYPH_STRIP_KEY_LETTER) : UInt32(MP_GLYPH_STRIP_KEY_DELETE))
            if preedit > lastPreedit { typed = true }
            if !structural { cause = .key } else if !hard { cause = .typing }
        }
        lastPreedit = preedit
        let date = Date()
        let calendar = Calendar.current
        mp_glyph_strip_set_clock(handle, Int32(calendar.component(.hour, from: date) * 100 + calendar.component(.minute, from: date)),
                                 Int64(date.timeIntervalSince1970 * 1000))
        lock.unlock()
        shown = true
        redraw(cause)
        if typed || landDeferred {
            let interval = lastKey > 0 ? now - lastKey : 0.2
            lastKey = now
            scheduleLand(after: min(0.16, max(0.06, interval * 0.6)))
        }
        ensureTimer()
    }

    /// `select`: ← → Tab, only the sun moved. `key`: a key that left the words as they were. `typing`: a key that changed
    /// the words: LAND is not drawn now (the ground moves when the foot comes down, `scheduleLand`), only the words, OVER
    /// and CATS. `other`: everything else draws what is dirty.
    private enum Cause { case select, key, typing, other }
    /// The land is behind the words: it is drawn when the foot comes down (or at the next tick).
    private var landDeferred = false

    /// Forgets the typing: the next composition starts a new rhythm.
    func hide() {
        shown = false
        lastPreedit = -1
        lastKey = 0
        landWork?.cancel(); landWork = nil
        buildWork?.cancel(); buildWork = nil
        timer?.invalidate(); timer = nil
    }

    // MARK: Time

    private func scheduleLand(after delay: TimeInterval) {
        landWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.shown else { return }
            self.lock.lock(); let moved = mp_glyph_strip_land(self.handle); self.lock.unlock()
            self.landWork = nil
            if moved == 1 || self.landDeferred { self.redraw(.other) }
            self.landDeferred = false
            self.ensureTimer()
        }
        landWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func ensureTimer() {
        lock.lock(); let busy = mp_glyph_strip_busy(handle) != 0; lock.unlock()
        guard busy, shown else { if !busy { timer?.invalidate(); timer = nil }; return }
        guard timer == nil else { return }
        let timer = Timer(timeInterval: 1.0 / 12, repeats: true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func tick() {
        guard shown else { timer?.invalidate(); timer = nil; return }
        lock.lock()
        let date = Date()
        let calendar = Calendar.current
        mp_glyph_strip_set_clock(handle, Int32(calendar.component(.hour, from: date) * 100 + calendar.component(.minute, from: date)),
                                 Int64(date.timeIntervalSince1970 * 1000))
        _ = mp_glyph_strip_step(handle)
        let busy = mp_glyph_strip_busy(handle) != 0
        lock.unlock()
        redraw(.other)
        if !busy { timer?.invalidate(); timer = nil }
    }

    /// Draws a tick or a key from outside (the smoke checks).
    func advance() { tick() }

    /// A fixed place (an index 0...11) instead of the journey's (the prototype's shots, the smoke checks).
    func fixPlace(_ place: Int32) {
        lock.lock(); mp_glyph_strip_set_place(handle, place); lock.unlock()
    }

    /// The layers of the view as one image (the smoke checks): the view's own layer tree at `backing` pixels a point.
    func snapshot() -> CGImage? {
        let w = Int(stripSize.width * backing), h = Int(stripSize.height * backing)
        guard w > 0, h > 0, let layer,
              let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else { return nil }
        context.scaleBy(x: backing, y: backing)
        // The view is flipped: lay the layer tree out from the top.
        context.translateBy(x: 0, y: stripSize.height)
        context.scaleBy(x: 1, y: -1)
        layer.render(in: context)
        return context.makeImage()
    }

    // MARK: Drawing

    private func render(_ layers: UInt32, frame: Int32, width: Int, height: Int) -> CGImage? {
        let buffer = UnsafeMutablePointer<UInt32>.allocate(capacity: width * height)
        buffer.initialize(repeating: 0, count: width * height)
        let status = mp_glyph_strip_render(handle, layers, frame, UInt32(MP_GLYPH_STRIP_BGRA_PREMUL),
                                           UnsafeMutableRawPointer(buffer).assumingMemoryBound(to: UInt8.self), width * 4, UInt32(width), UInt32(height))
        guard status == MP_GLYPH_STRIP_OK else { buffer.deallocate(); return nil }
        return GlyphPixels.image(buffer, width: width, height: height)
    }

    private func redraw(_ cause: Cause) {
        lock.lock()
        var size = MPGlyphStripSize()
        guard mp_glyph_strip_size(handle, &size) == MP_GLYPH_STRIP_OK else { lock.unlock(); return }
        let (w, h) = (Int(size.pixel_width), Int(size.pixel_height))
        var dirty = size.dirty
        var landDraw = false, landReal = false
        if (w, h) != pixel {
            pixel = (w, h); dirty |= UInt32(MP_GLYPH_STRIP_ALL)
            // The old picture stays at its own size until the ground comes down.
            if cause == .typing { landDeferred = true; landReal = true } else { landDraw = true; landReal = true }
        }
        if dirty & UInt32(MP_GLYPH_STRIP_LAND) != 0 {
            switch cause {
            // Only the sun moved: the land drawn ahead has the frame; without it, one frame now.
            case .typing: landDeferred = true; landReal = true
            case .select: if longReady { landSticky = true } else { landDraw = true }
            // A key does not move the land (the foot comes down later); the flag is what a select left behind.
            case .key where landSticky: break
            default: landDraw = true; landReal = true
            }
        }
        if landReal { epoch += 1; longImage = nil; landSticky = false }
        let landNow = landDraw
        if landNow { landDeferred = false }
        let landNeeded = landReal
        var land: CGImage?, over: CGImage?, cats: CGImage?
        if landNow { land = render(UInt32(MP_GLYPH_STRIP_LAND), frame: -1, width: w, height: h); landRenders += 1 }
        if dirty & UInt32(MP_GLYPH_STRIP_OVER) != 0 { over = render(UInt32(MP_GLYPH_STRIP_OVER), frame: -1, width: w, height: h) }
        if dirty & UInt32(MP_GLYPH_STRIP_CATS) != 0 { cats = render(UInt32(MP_GLYPH_STRIP_CATS), frame: -1, width: w, height: h) }
        let count = Int(mp_glyph_strip_cell_count(handle))
        var read: [MPGlyphStripCell] = []
        for index in 0..<count {
            var cell = MPGlyphStripCell()
            if mp_glyph_strip_cell(handle, index, &cell) == MP_GLYPH_STRIP_OK { read.append(cell) }
        }
        lock.unlock()
        cells = read

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let f = factor
        updateShadow(w: CGFloat(w) / backing, h: CGFloat(h) / backing, f: f)
        let bounds = CGRect(x: 0, y: 0, width: CGFloat(w) / backing, height: CGFloat(h) / backing)
        for layer in [landLayer, overLayer, catsLayer] where layer.bounds != CGRect(origin: .zero, size: bounds.size) {
            layer.bounds = CGRect(origin: .zero, size: bounds.size)
            layer.position = .zero
        }
        for layer in [landLayer, overLayer, catsLayer] { layer.contentsScale = backing }
        landLayer.contentsGravity = landDeferred ? .topLeft : .resize
        if let land { landLayer.contents = land; landLayer.contentsRect = CGRect(x: 0, y: 0, width: 1, height: 1) }
        if let over { overLayer.contents = over }
        if let cats { catsLayer.contents = cats }
        if longReady, let longImage {
            let n = CGFloat(longCount)
            landLayer.contents = longImage
            landLayer.contentsRect = CGRect(x: CGFloat(min(selected, longCount - 1)) / n, y: 0, width: 1 / n, height: 1)
        }
        updateWords(f: f)
        updatePager(f: f)
        CATransaction.commit()
        if landNeeded { scheduleLongBuild() }
        invalidateAccessibility()
    }

    private var longReady: Bool { longImage != nil && longEpoch == epoch && longCount == specs.count && longCount > 0 }

    private func updateWords(f: CGFloat) {
        while wordLayers.count > cells.count {
            wordLayers.removeLast().removeFromSuperlayer(); wordKeys.removeLast()
        }
        while wordLayers.count < cells.count {
            let layer = CALayer(); prepare(layer)
            self.layer?.addSublayer(layer); wordLayers.append(layer); wordKeys.append("")
        }
        for (i, cell) in cells.enumerated() where i < specs.count {
            let spec = specs[i]
            let x0 = Int(cell.x.rounded(.down)) - 4, y0 = Int(cell.y.rounded(.down)) - 13
            let width = Int(cell.w.rounded(.up)) + 8, height = 26
            let key = "\(spec.number)|\(spec.text)|\(k)|\(x0),\(y0),\(width)|\(cell.number_x)|\(cell.text_x)|\(cell.baseline)|\(cell.number_color)|\(cell.text_color)|\(cell.halo_color)"
            let layer = wordLayers[i]
            layer.frame = CGRect(x: CGFloat(x0) * f, y: CGFloat(y0) * f, width: CGFloat(width) * f, height: CGFloat(height) * f)
            layer.contentsScale = backing
            guard wordKeys[i] != key else { continue }
            wordKeys[i] = key
            layer.contents = GlyphWords.image(parts: [(spec.number, cell.number_x - Double(x0), cell.number_color),
                                                      (spec.text, cell.text_x - Double(x0), cell.text_color)],
                                              halo: cell.halo_color, width: width, height: height,
                                              baseline: cell.baseline - Double(y0), k: k)
        }
    }

    /// The stepped outline (two steps of 2 pt at each corner, as `stepped_span` in the core) at the strip's size, offset.
    private func updateShadow(w: CGFloat, h: CGFloat, f: CGFloat) {
        let key = "\(w)x\(h)@\(f)"
        if shadowKey != key {
            shadowKey = key
            let s = 2 * f
            let path = CGMutablePath()
            let points: [(CGFloat, CGFloat)] = [(2, 0), (w / s - 2, 0), (w / s - 2, 1), (w / s - 1, 1), (w / s - 1, 2), (w / s, 2),
                                                (w / s, h / s - 2), (w / s - 1, h / s - 2), (w / s - 1, h / s - 1), (w / s - 2, h / s - 1),
                                                (w / s - 2, h / s), (2, h / s), (2, h / s - 1), (1, h / s - 1), (1, h / s - 2), (0, h / s - 2),
                                                (0, 2), (1, 2), (1, 1), (2, 1)]
            for (i, point) in points.enumerated() {
                let p = CGPoint(x: point.0 * s, y: point.1 * s)
                if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
            }
            path.closeSubpath()
            shadowLayer.path = path
            shadowLayer.frame = CGRect(x: 2 * f, y: 2 * f, width: w, height: h)
        }
        shadowLayer.fillColor = CGColor(gray: 0, alpha: look?.dark == true ? 0.6 : 0.2)
    }
    private var shadowKey = ""

    private func updatePager(f: CGFloat) {
        guard expanded, let pager, let reference = cells.indices.first(where: { !cells[$0].isSelectedCell }).map({ cells[$0] }) ?? cells.first else {
            pagerLayer.contents = nil; pagerHit = nil; return
        }
        let wl = GlyphType.width("<"), wn = GlyphType.width(String(pager.page + 1)), wr = GlyphType.width(">")
        let total = Int((wl + 5 + wn + 5 + wr).rounded(.up))
        let mid = Double(pixel.h) / Double(k) - 10
        let x0 = Int(Double(pixel.w) / Double(k)) - total - 18, y0 = Int(mid) - 13
        let ink = reference.text_color, dim = (ink & 0x00FF_FFFF) | 0x6600_0000
        let width = total + 8, height = 26
        pagerLayer.frame = CGRect(x: CGFloat(x0 - 4) * f, y: CGFloat(y0) * f, width: CGFloat(width) * f, height: CGFloat(height) * f)
        pagerLayer.contentsScale = backing
        pagerLayer.contents = GlyphWords.image(parts: [("<", 4, pager.page == 0 ? dim : ink), (String(pager.page + 1), 4 + wl + 5, ink),
                                                       (">", 4 + wl + 5 + wn + 5, pager.last ? dim : ink)],
                                               halo: reference.halo_color, width: width, height: height,
                                               baseline: mid + (reference.baseline - reference.y) - Double(y0), k: k)
        let px = Double(x0)
        pagerHit = (CGRect(x: px - 4, y: mid - 11, width: wl + 14, height: 22),
                    CGRect(x: px + wl + 5 + wn + 5 - 6, y: mid - 11, width: wr + 16, height: 22))
    }

    // MARK: The land drawn ahead

    /// Draws the land with the sun behind each word, side by side, when the land has been still for a moment.
    private func scheduleLongBuild() {
        buildWork?.cancel()
        guard specs.count > 1 else { return }
        let want = epoch, count = specs.count, (w, h) = pixel
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let total = w * count
            let buffer = UnsafeMutablePointer<UInt32>.allocate(capacity: total * h)
            buffer.initialize(repeating: 0, count: total * h)
            for index in 0..<count {
                self.lock.lock()
                let current = self.epoch == want && self.specs.count == count && self.pixel == (w, h)
                if current {
                    _ = mp_glyph_strip_render(self.handle, UInt32(MP_GLYPH_STRIP_LAND), Int32(index), UInt32(MP_GLYPH_STRIP_BGRA_PREMUL),
                                              UnsafeMutableRawPointer(buffer + index * w).assumingMemoryBound(to: UInt8.self), total * 4, UInt32(w), UInt32(h))
                }
                self.lock.unlock()
                if !current { buffer.deallocate(); return }
            }
            let image = GlyphPixels.image(buffer, width: total, height: h)
            DispatchQueue.main.async {
                guard self.epoch == want, self.specs.count == count, self.pixel == (w, h), let image else { return }
                self.longImage = image
                self.longEpoch = want
                self.longCount = count
                CATransaction.begin(); CATransaction.setDisableActions(true)
                self.landLayer.contents = image
                self.landLayer.contentsRect = CGRect(x: CGFloat(min(self.selected, count - 1)) / CGFloat(count), y: 0, width: 1 / CGFloat(count), height: 1)
                CATransaction.commit()
            }
        }
        buildWork = work
        buildQueue.asyncAfter(deadline: .now() + 0.25, execute: work)
    }

    /// Waits for the land drawn ahead (the smoke checks).
    func finishLongStrip(timeout: TimeInterval = 5) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while !longReady && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.02)) }
        return longReady
    }

    // MARK: Mouse

    override func mouseDown(with event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        let f = factor
        let x = Double(p.x / f), y = Double(p.y / f)
        if let pager, let hit = pagerHit {
            let point = CGPoint(x: x, y: y)
            if hit.prev.contains(point) { if pager.page > 0 { onPage(0xff55) }; return }
            if hit.next.contains(point) { if !pager.last { onPage(0xff56) }; return }
        }
        for (i, cell) in cells.enumerated() where i < specs.count {
            if x >= cell.hit_x, x < cell.hit_x + cell.hit_w, y >= cell.hit_y, y < cell.hit_y + cell.hit_h { onChoose(specs[i].index); return }
        }
        // The arrow at the right end of the first line.
        if canExpand, x >= Double(pixel.w) / Double(k) - 34, y < 72 { onToggle() }
    }

    override func menu(for event: NSEvent) -> NSMenu? { menuBuilder?() }

    // MARK: Accessibility (the candidate list VoiceOver reads)

    private var elements: [GlyphCellElement] = []

    private func invalidateAccessibility() { elements = [] }

    override func isAccessibilityElement() -> Bool { false }
    override func accessibilityRole() -> NSAccessibility.Role? { .list }
    override func accessibilityLabel() -> String? { InterfaceText.system("候選字", "候选字") }
    override func accessibilityChildren() -> [Any]? {
        if elements.isEmpty {
            let t = InterfaceText.system
            let f = factor
            elements = specs.enumerated().compactMap { i, spec in
                guard i < cells.count else { return nil }
                let cell = cells[i]
                let element = GlyphCellElement()
                element.setAccessibilityRole(.button)
                element.setAccessibilityParent(self)
                element.setAccessibilityLabel(spec.text + (spec.japanese ? t("，日語", "，日语") : ""))
                element.setAccessibilityHelp(t("按 \(spec.number) 選這個", "按 \(spec.number) 选这个"))
                element.setAccessibilitySelected(i == selected)
                element.setAccessibilityFrameInParentSpace(CGRect(x: cell.hit_x * Double(f), y: cell.hit_y * Double(f),
                                                                  width: cell.hit_w * Double(f), height: cell.hit_h * Double(f)))
                let index = spec.index
                element.press = { [weak self] in self?.onChoose(index) }
                return element
            }
            if expanded, let pager, let hit = pagerHit {
                for (rect, label, key, enabled) in [(hit.prev, t("上一頁", "上一页"), Int32(0xff55), pager.page > 0),
                                                    (hit.next, t("下一頁", "下一页"), Int32(0xff56), !pager.last)] where enabled {
                    let element = GlyphCellElement()
                    element.setAccessibilityRole(.button)
                    element.setAccessibilityParent(self)
                    element.setAccessibilityLabel(label)
                    element.setAccessibilityFrameInParentSpace(CGRect(x: rect.minX * Double(f), y: rect.minY * Double(f),
                                                                      width: rect.width * Double(f), height: rect.height * Double(f)))
                    element.press = { [weak self] in self?.onPage(key) }
                    elements.append(element)
                }
            }
        }
        return elements
    }
}

extension MPGlyphStripCell {
    var isSelectedCell: Bool { flags & UInt32(MP_GLYPH_STRIP_CELL_SELECTED) != 0 }
}

// MARK: The right-click menu (the glass window's, item for item)

private final class MenuBlock: NSObject {
    let run: () -> Void
    init(_ run: @escaping () -> Void) { self.run = run }
    @objc func fire() { run() }
}

enum GlyphMenu {
    static func build(expanded: Bool, canExpand: Bool, onToggle: @escaping () -> Void, onChange: @escaping () -> Void) -> NSMenu {
        let t = InterfaceText.system
        let preferences = Preferences.shared
        let menu = NSMenu()
        menu.autoenablesItems = false
        @discardableResult
        func add(_ to: NSMenu, _ title: String, checked: Bool = false, enabled: Bool = true, _ run: @escaping () -> Void) -> NSMenuItem {
            let block = MenuBlock(run)
            let item = NSMenuItem(title: title, action: #selector(MenuBlock.fire), keyEquivalent: "")
            item.target = block
            item.representedObject = block
            item.state = checked ? .on : .off
            item.isEnabled = enabled
            to.addItem(item)
            return item
        }
        func submenu(_ title: String) -> NSMenu {
            let sub = NSMenu(title: title)
            let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            item.submenu = sub
            menu.addItem(item)
            return sub
        }
        func choices<Value: Equatable>(_ title: String, _ all: [Value], _ name: (Value) -> String, _ current: Value, _ set: @escaping (Value) -> Void) {
            let sub = submenu(title)
            for value in all { add(sub, name(value), checked: value == current) { set(value); onChange() } }
        }
        add(menu, expanded ? t("收起候選", "收起候选") : t("展開候選", "展开候选"), enabled: canExpand || expanded, onToggle)
        menu.addItem(.separator())
        add(menu, t("米米莫與莫莫", "米米莫与莫莫"), checked: preferences.catGlassSkin) { preferences.catGlassSkin.toggle(); onChange() }
        let place = submenu("位置")
        add(place, t("每天換", "每天换"), checked: preferences.catPlacement == CatSchedule.daily) { preferences.catPlacement = CatSchedule.daily; onChange() }
        for arrangement in CatArrangement.all {
            add(place, arrangement.name(t), checked: preferences.catPlacement == arrangement.id) { preferences.catPlacement = arrangement.id; onChange() }
        }
        add(menu, t("節日裝扮", "节日装扮"), checked: preferences.festivalOutfits) { preferences.festivalOutfits.toggle(); onChange() }
        choices(t("造型", "造型"), GlassShape.allCases, { $0.name(t) }, preferences.glassShape) { preferences.glassShape = $0 }
        choices(t("候選字級", "候选字号"), CandidateTextSize.allCases, { $0.name(t) }, preferences.candidateTextSize) { preferences.candidateTextSize = $0 }
        choices(t("候選窗大小", "候选窗大小"), CandidateWindowSize.allCases, { $0.name(t) }, preferences.candidateWindowSize) { preferences.candidateWindowSize = $0 }
        choices(t("色調", "色调"), CandidateTone.allCases, { $0.name(t) }, preferences.candidateTone) { preferences.candidateTone = $0 }
        choices(t("色系", "色系"), CandidatePalette.allCases, { $0.name(t) }, preferences.candidatePalette) { preferences.candidatePalette = $0 }
        choices(t("玻璃", "玻璃"), GlassStrength.allCases, { $0.name(t) }, preferences.glassStrength) { preferences.glassStrength = $0 }
        return menu
    }
}

// MARK: From the engine's snapshot

enum GlyphCandidates {
    /// The words the strip shows for a snapshot: the collapsed row's candidates in their order, or the panel's first eight.
    static func specs(_ snapshot: Snapshot, expanded: Bool) -> (specs: [GlyphCellSpec], selected: Int) {
        let marked = markedJapanese(snapshot)
        func shorten(_ text: String, _ limit: Int) -> String {
            text.count <= limit ? text : String(text.prefix(limit - 1)) + "…"
        }
        var list: [GlyphCellSpec] = []
        var chosen = 0
        if expanded {
            let total = snapshot.candidates.count + (snapshot.compactJapanese == nil ? 0 : 1)
            for index in 0..<min(8, total) {
                guard let text = compactCandidateText(snapshot, at: index) else { continue }
                list.append(GlyphCellSpec(index: index, number: String(index + 1), text: shorten(text, 10), japanese: marked.contains(text)))
            }
            chosen = list.firstIndex { $0.index == snapshot.highlighted } ?? 0
        } else {
            for (ordinal, index) in CollapsedCandidateLayout(snapshot).indices.enumerated() {
                guard let text = compactCandidateText(snapshot, at: index) else { continue }
                list.append(GlyphCellSpec(index: index, number: String(ordinal + 1), text: shorten(text, 12), japanese: marked.contains(text)))
            }
            chosen = list.firstIndex { $0.index == snapshot.highlighted } ?? 0
        }
        return (list, chosen)
    }
}

#endif
