// Developer preview of the macOS candidate window. It uses the view and the
// panel of the installed input method.
//
//   GlassPreview                               a window to try shapes, tones and palettes
//   GlassPreview --snapshot DIR                writes PNG sheets of every state into DIR, then exits
//   GlassPreview --snapshot DIR --festivals F  the same cats wearing the outfits in folder F
//   GlassPreview --snapshot DIR --only NAME    only the sheets whose file name starts with NAME
//
// Snapshots are drawn off screen from the real layer tree, so no window opens.
import AppKit
import Metal
import QuartzCore
import SwiftUI

// MARK: Window

struct GlassPreview: View {
    @StateObject private var engine = Engine(mode: .pinyin)
    @ObservedObject private var preferences = Preferences.shared
    @State private var expanded = false
    @State private var striped = false
    @State private var floating: CandidatePanelPreview?
    private let t = InterfaceText.system

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 14) {
                Picker(t("造型", "造型"), selection: $preferences.glassShape) {
                    ForEach(GlassShape.allCases) { Text($0.name(t)).tag($0) }
                }.frame(width: 150)
                Picker(t("色系", "色系"), selection: $preferences.candidatePalette) {
                    ForEach(CandidatePalette.allCases) { Text($0.name(t)).tag($0) }
                }.frame(width: 170)
                Picker(t("玻璃", "玻璃"), selection: $preferences.glassStrength) {
                    ForEach(GlassStrength.allCases) { Text($0.name(t)).tag($0) }
                }.frame(width: 140)
            }
            HStack(spacing: 14) {
                Toggle(t("展開候選", "展开候选"), isOn: $expanded)
                Toggle(t("條紋背景", "条纹背景"), isOn: $striped)
                Button(t("浮動候選窗", "浮动候选窗")) {
                    floating?.close()
                    floating = CandidatePanelPreview(engine: engine)
                    floating?.show()
                }
            }
            ForEach([false, true], id: \.self) { dark in
                VStack(alignment: .leading, spacing: 14) {
                    ForEach([true, false], id: \.self) { cats in
                        GlassCandidateView(engine: engine, cats: cats, expanded: expanded, canExpand: true,
                                           forced: CandidateLookOverride(dark: dark),
                                           onChoose: { engine.snapshot.highlighted = $0 }, onPage: { _ in }, onToggle: { expanded.toggle() })
                    }
                }
                .padding(18).frame(maxWidth: .infinity, alignment: .leading)
                .background { PreviewBackdrop(kind: striped ? .stripes : .plain, dark: dark).clipShape(RoundedRectangle(cornerRadius: 20)) }
            }
        }
        .padding(24).frame(width: 760)
        .onAppear {
            engine.snapshot = Snapshot(preedit: "wo", candidates: ["我", "握", "窩", "臥", "沃", "を"], input: "wo",
                                       confidentCandidates: ["我", "握", "窩", "臥", "沃"], japaneseCandidates: ["を"])
        }
    }
}

/// The production panel next to the preview window, to check the context menu
/// and the glass over whatever is behind it.
final class CandidatePanelPreview {
    private let controller: CandidatePanelController
    private var expanded = false

    init(engine: Engine) {
        controller = CandidatePanelController(engine: engine)
        controller.onChoose = { engine.snapshot.highlighted = $0 }
        controller.onToggle = { [weak self] in self?.expanded.toggle(); self?.show() }
        controller.onAppearanceChange = { [weak self] in self?.show() }
    }

    func show() {
        let origin = NSApp.mainWindow?.frame.origin ?? NSPoint(x: 200, y: 400)
        let caret = NSRect(x: origin.x + 300, y: origin.y + 140, width: 1, height: 18)
        controller.show(expanded: expanded, canExpand: true, caret: caret, start: caret)
    }

    func close() { controller.hide() }
}

// MARK: Backdrops

/// What the glass floats over in previews and snapshots.
struct PreviewBackdrop: View {
    enum Kind: String { case plain, document, stripes, white, black }
    var kind: Kind
    var dark: Bool

    var body: some View {
        ZStack {
            LinearGradient(colors: dark ? [Color(red: 0.11, green: 0.12, blue: 0.15), Color(red: 0.16, green: 0.15, blue: 0.18)]
                                        : [Color(red: 0.925, green: 0.930, blue: 0.950), Color(red: 0.870, green: 0.880, blue: 0.915)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            switch kind {
            case .plain:
                EmptyView()
            case .white:
                Color.white
            case .black:
                Color.black
            case .document:
                // Lines of text, as in an editor behind the window.
                Canvas { context, size in
                    let ink = dark ? Color.white.opacity(0.82) : Color.black.opacity(0.80)
                    var y: CGFloat = 9
                    var seed: UInt64 = 7
                    while y < size.height {
                        var x: CGFloat = 14
                        while x < size.width - 30 {
                            seed = seed &* 6364136223846793005 &+ 1442695040888963407
                            let word = CGFloat(18 + (seed >> 33) % 54)
                            context.fill(Path(roundedRect: CGRect(x: x, y: y, width: word, height: 7), cornerRadius: 2), with: .color(ink))
                            x += word + 8
                        }
                        y += 17
                    }
                }
            case .stripes:
                Canvas { context, size in
                    for x in stride(from: CGFloat(0), through: size.width + size.height, by: 24) {
                        var line = Path(); line.move(to: CGPoint(x: x, y: 0)); line.addLine(to: CGPoint(x: x - size.height, y: size.height))
                        context.stroke(line, with: .color(dark ? .white.opacity(0.25) : .black.opacity(0.20)), lineWidth: 2)
                    }
                }
            }
        }
    }
}

// MARK: Snapshots

/// One candidate window on a sheet.
private struct SheetRow: Identifiable {
    let id = UUID()
    var label: String
    var engine: Engine
    var cats = true
    var expanded = false
    /// The window is over the caret: the panel opens upward.
    var above = false
    var forced = CandidateLookOverride()
    var backdrop = PreviewBackdrop.Kind.plain
    var maxHeight = CGFloat.greatestFiniteMagnitude
}

private struct Sheet: View {
    var rows: [SheetRow]
    var dark: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(rows) { row in
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.label)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle((row.forced.dark ?? dark) ? Color.white.opacity(0.6) : Color.black.opacity(0.55))
                        .padding(.horizontal, 4)
                        .background((row.forced.dark ?? dark) ? Color.black.opacity(0.6) : Color.white.opacity(0.7), in: Capsule())
                    GlassCandidateView(engine: row.engine, cats: row.cats, expanded: row.expanded,
                                       canExpand: candidatesCanExpand(row.engine.snapshot), panelAbove: row.above, maxHeight: row.maxHeight,
                                       forced: row.forced, onChoose: { _ in }, onPage: { _ in }, onToggle: {})
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
                .frame(minWidth: 520, maxWidth: .infinity, alignment: .leading)
                .background { PreviewBackdrop(kind: row.backdrop, dark: row.forced.dark ?? dark) }
            }
        }
        .fixedSize()
    }
}

enum SnapshotRun {
    /// Draws a view's layer tree into a bitmap without putting a window on
    /// screen. Unlike ImageRenderer or cacheDisplay this keeps Liquid Glass,
    /// blurs and shadows.
    static func render<V: View>(_ view: V) -> NSBitmapImageRep? {
        let host = NSHostingView(rootView: view)
        host.frame = NSRect(origin: .zero, size: host.fittingSize)
        let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        host.display()
        CATransaction.flush()
        // Let SwiftUI finish its layers and the glass pick up what is behind it.
        RunLoop.main.run(until: Date().addingTimeInterval(0.35))
        guard let device = MTLCreateSystemDefaultDevice(), let layer = host.layer else { return nil }
        let scale = max(2, window.backingScaleFactor)
        let width = Int((host.bounds.width * scale).rounded()), height = Int((host.bounds.height * scale).rounded())
        guard width > 0, height > 0 else { return nil }
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
        // The renderer draws on the GPU in its own time: read until two reads agree.
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
            // The renderer's first row is the bottom one; BGRA becomes RGBA.
            let source = (height - 1 - y) * width * 4
            let line = pixels + y * width * 4
            for x in 0..<width {
                line[x * 4] = bytes[source + x * 4 + 2]; line[x * 4 + 1] = bytes[source + x * 4 + 1]
                line[x * 4 + 2] = bytes[source + x * 4]; line[x * 4 + 3] = bytes[source + x * 4 + 3]
            }
        }
        rep.size = host.bounds.size
        return rep
    }

    private static func typed(_ mode: InputMode, _ keys: String, highlight: Int? = nil) -> Engine {
        let engine = Engine(mode: mode)
        for key in keys.utf8 { engine.key(Int32(key)) }
        if let highlight { engine.snapshot.highlighted = highlight }
        return engine
    }

    /// Set by --only: sheets with other names are skipped.
    static var only: String?

    private static func wanted(_ name: String) -> Bool { only.map { name.hasPrefix($0) } ?? true }

    private static func write(_ sheet: Sheet, _ directory: String, _ name: String) -> Bool {
        guard wanted(name) else { return true }
        let path = directory + "/" + name + ".png"
        guard let rep = render(sheet), let data = rep.representation(using: .png, properties: [:]) else {
            fputs("snapshot failed: \(name)\n", stderr)
            return false
        }
        do { try data.write(to: URL(fileURLWithPath: path)) } catch {
            fputs("cannot write \(path): \(error.localizedDescription)\n", stderr)
            return false
        }
        print("\(path)  \(rep.pixelsWide)x\(rep.pixelsHigh)")
        return true
    }

    /// Writes the sheets and returns the process exit code.
    static func run(_ directory: String, festivals: Bool) -> Int32 {
        setbuf(stdout, nil)
        // A stuck render must not hang whoever started it.
        DispatchQueue.global().asyncAfter(deadline: .now() + 120) {
            fputs("snapshot timed out\n", stderr)
            exit(3)
        }
        do { try FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true) } catch {
            fputs("cannot create \(directory): \(error.localizedDescription)\n", stderr)
            return 2
        }
        let preferences = Preferences.shared
        preferences.simplified = false
        preferences.rememberChoices = false
        preferences.candidatePalette = .rose
        preferences.glassStrength = .balanced
        preferences.festivalOutfits = true
        guard Engine(mode: .pinyin).isReady else {
            fputs("the lexicon is not in the preview bundle; run scripts/preview-glass.sh\n", stderr)
            return 2
        }
        var ok = true
        let mixed = "woxiangshiberusushiheramen"
        let sentence = "rup wu0 wu0 fu4cp3cl3ji3vu;3fm4ej/ m06n041j4"
        if festivals {
            for dark in [false, true] {
                preferences.glassShape = .capsule
                let look = CandidateLookOverride(dark: dark)
                let rows = [
                    SheetRow(label: "holiday outfit · nihao", engine: typed(.pinyin, "nihao"), forced: look),
                    SheetRow(label: "holiday outfit · expanded", engine: typed(.pinyin, "nihao"), expanded: true, forced: look),
                ]
                ok = write(Sheet(rows: rows, dark: dark), directory, "festival-\(dark ? "dark" : "light")") && ok
            }
            return finish(ok)
        }
        for shape in GlassShape.allCases {
            preferences.glassShape = shape
            for dark in [false, true] {
                let look = CandidateLookOverride(dark: dark)
                let rows = [
                    SheetRow(label: "nihao · pinyin, number keys 1–7", engine: typed(.pinyin, "nihao"), forced: look),
                    SheetRow(label: "sushi · one Japanese candidate with the paw", engine: typed(.pinyin, "sushi"), forced: look),
                    SheetRow(label: "su3cl3 · zhuyin, Shift + number", engine: typed(.zhuyin, "su3cl3"), forced: look),
                    SheetRow(label: "long mixed candidate, shortened in the middle", engine: typed(.pinyin, mixed), forced: look),
                    SheetRow(label: "long sentence · zhuyin", engine: typed(.zhuyin, sentence), forced: look),
                    SheetRow(label: "woxianghekafei · second candidate highlighted", engine: typed(.pinyin, "woxianghekafei", highlight: 1), forced: look),
                    SheetRow(label: "no candidates yet", engine: typed(.pinyin, "vvvvvv"), forced: look),
                    SheetRow(label: "nihao · expanded", engine: typed(.pinyin, "nihao", highlight: 4), expanded: true, forced: look),
                    SheetRow(label: "sushi · expanded", engine: typed(.pinyin, "sushi"), expanded: true, forced: look),
                    SheetRow(label: "long mixed candidate · expanded", engine: typed(.pinyin, mixed), expanded: true, forced: look),
                    SheetRow(label: "cats off · sushi", engine: typed(.pinyin, "sushi"), cats: false, forced: look),
                    SheetRow(label: "cats off · zhuyin, expanded", engine: typed(.zhuyin, "su3cl3"), cats: false, expanded: true, forced: look),
                    SheetRow(label: "sushi · Japanese mode, Shift + number, no paw", engine: typed(.japanese, "sushi", highlight: 1), forced: look),
                    SheetRow(label: "kanji · Japanese mode, expanded", engine: typed(.japanese, "kanji", highlight: 1), expanded: true, forced: look),
                    SheetRow(label: "nihao · over the caret, the panel opens upward", engine: typed(.pinyin, "nihao"), expanded: true, above: true, forced: look),
                    SheetRow(label: "80 kana · the panel stops at 560 pt", engine: typed(.japanese, String(repeating: "a", count: 80)), expanded: true, forced: look),
                ]
                ok = write(Sheet(rows: rows, dark: dark), directory, "\(shape.rawValue)-\(dark ? "dark" : "light")") && ok
            }
        }
        preferences.glassShape = .capsule
        for dark in [false, true] {
            let opaque = CandidateLookOverride(dark: dark, opaque: true)
            let contrast = CandidateLookOverride(dark: dark, opaque: true, contrast: true)
            let rows = [
                SheetRow(label: "Reduce Transparency · sushi", engine: typed(.pinyin, "sushi"), forced: opaque),
                SheetRow(label: "Reduce Transparency · expanded", engine: typed(.pinyin, "sushi"), expanded: true, forced: opaque),
                SheetRow(label: "Increase Contrast · zhuyin", engine: typed(.zhuyin, "su3cl3"), forced: contrast),
                SheetRow(label: "Increase Contrast · expanded", engine: typed(.pinyin, "sushi"), expanded: true, forced: contrast),
                SheetRow(label: "Increase Contrast · cats off", engine: typed(.pinyin, "nihao"), cats: false, forced: contrast),
                SheetRow(label: "short display · expanded panel scrolls", engine: typed(.pinyin, "nihao"), expanded: true,
                         forced: CandidateLookOverride(dark: dark), maxHeight: 190),
            ]
            ok = write(Sheet(rows: rows, dark: dark), directory, "accessibility-\(dark ? "dark" : "light")") && ok
        }
        // Light and dark glass over text and over the other tone's background.
        var rows: [SheetRow] = []
        for dark in [false, true] {
            for backdrop in [PreviewBackdrop.Kind.white, .black, .document, .stripes] {
                rows.append(SheetRow(label: "\(dark ? "dark" : "light") · over \(backdrop.rawValue)", engine: typed(.pinyin, "sushi"),
                                     forced: CandidateLookOverride(dark: dark), backdrop: backdrop))
            }
            rows.append(SheetRow(label: "\(dark ? "dark" : "light") · expanded over document", engine: typed(.pinyin, "nihao"), expanded: true,
                                 forced: CandidateLookOverride(dark: dark), backdrop: .document))
        }
        ok = write(Sheet(rows: rows, dark: false), directory, "backdrops") && ok
        for strength in GlassStrength.allCases {
            preferences.glassStrength = strength
            let rows = [false, true].flatMap { dark in
                [SheetRow(label: "glass \(strength.rawValue) · \(dark ? "dark" : "light")", engine: typed(.pinyin, "sushi"),
                          forced: CandidateLookOverride(dark: dark), backdrop: .document)]
            }
            ok = write(Sheet(rows: rows, dark: false), directory, "glass-\(strength.rawValue)") && ok
        }
        preferences.glassStrength = .balanced
        // The settings window, section by section, in both scripts.
        for (name, text) in [("hant", InterfaceText(simplified: false)), ("hans", InterfaceText(simplified: true))] {
            for section in Studio.Section.allCases where name == "hant" || section == .appearance || section == .shortcuts {
                guard wanted("settings-\(section.rawValue)-\(name)") else { continue }
                let view = Studio(section: section, text: text).frame(width: 1000, height: 700)
                let path = directory + "/settings-\(section.rawValue)-\(name).png"
                if let rep = render(view), let data = rep.representation(using: .png, properties: [:]),
                   (try? data.write(to: URL(fileURLWithPath: path))) != nil {
                    print("\(path)  \(rep.pixelsWide)x\(rep.pixelsHigh)")
                } else {
                    fputs("snapshot failed: settings-\(section.rawValue)-\(name)\n", stderr)
                    ok = false
                }
            }
        }
        rows = []
        for palette in CandidatePalette.allCases {
            preferences.candidatePalette = palette
            rows = [SheetRow(label: "palette \(palette.rawValue) · light", engine: typed(.pinyin, "nihao"), forced: CandidateLookOverride(dark: false)),
                    SheetRow(label: "palette \(palette.rawValue) · dark", engine: typed(.pinyin, "nihao"), forced: CandidateLookOverride(dark: true))]
            ok = write(Sheet(rows: rows, dark: false), directory, "palette-\(palette.rawValue)") && ok
        }
        return finish(ok)
    }

    private static func finish(_ ok: Bool) -> Int32 {
        // Snapshots use the smoke checks' separate settings; leave nothing behind.
        try? FileManager.default.removeItem(at: Preferences.directory)
        UserDefaults(suiteName: "app.meowpuut.smoke")?.removePersistentDomain(forName: "app.meowpuut.smoke")
        return ok ? 0 : 1
    }
}

@main enum Preview {
    static func main() {
        let arguments = CommandLine.arguments
        if let index = arguments.firstIndex(of: "--snapshot") {
            guard arguments.count > index + 1 else {
                fputs("usage: GlassPreview --snapshot DIR [--festivals DIR] [--only NAME]\n", stderr)
                exit(2)
            }
            // The engine keeps its settings and choice memory apart from the
            // user's when it is started with --smoke, so start again with it.
            if !arguments.contains("--smoke") {
                var argv = (arguments + ["--smoke"]).map { strdup($0) }
                argv.append(nil)
                execv(Bundle.main.executablePath ?? arguments[0], argv)
                fputs("cannot restart with --smoke\n", stderr)
                exit(2)
            }
            var festivals = false
            if let at = arguments.firstIndex(of: "--festivals"), arguments.count > at + 1 {
                Festivals.directoryOverride = URL(fileURLWithPath: arguments[at + 1], isDirectory: true)
                festivals = true
            }
            if let at = arguments.firstIndex(of: "--only"), arguments.count > at + 1 { SnapshotRun.only = arguments[at + 1] }
            _ = NSApplication.shared
            exit(SnapshotRun.run(arguments[index + 1], festivals: festivals))
        }
        let app = NSApplication.shared; app.setActivationPolicy(.regular)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 760, height: 640), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = "Meowpuut Glass Preview"
        window.contentView = NSHostingView(rootView: GlassPreview())
        window.center(); window.makeKeyAndOrderFront(nil); app.activate(ignoringOtherApps: true); app.run()
    }
}
