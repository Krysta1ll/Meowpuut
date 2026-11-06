// Developer preview: uses the same candidate view as the installed input method.
import AppKit
import SwiftUI

struct GlassPreview: View {
    @StateObject private var engine = Engine(mode: .pinyin)
    @ObservedObject private var preferences = Preferences.shared
    @State private var expanded = false
    @State private var detailed = false
    @State private var floating: CandidatePanelPreview?
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text("喵撲 · 材質與配色").font(.title2.weight(.medium))
                Spacer()
                Picker("色系", selection: $preferences.candidatePalette) {
                    ForEach(CandidatePalette.allCases) { Text($0.title).tag($0) }
                }.frame(width: 200)
            }
            HStack {
                Toggle("展開候選", isOn: $expanded)
                Picker("玻璃", selection: $preferences.glassStrength) {
                    ForEach(GlassStrength.allCases) { Text($0.title).tag($0) }
                }.frame(width: 150)
                Toggle("折射測試背景", isOn: $detailed)
                Button("浮動候選窗") {
                    floating?.window.close()
                    floating = CandidatePanelPreview(engine: engine)
                    floating?.show()
                }
            }
            ForEach([false, true], id: \.self) { dark in
                VStack(alignment: .leading, spacing: 18) {
                    Text(dark ? "深色 · 雙貓與簡潔" : "淺色 · 雙貓與簡潔").font(.caption).foregroundStyle(.secondary)
                    ForEach([true, false], id: \.self) { cats in
                        GlassCandidateView(engine: engine, cats: cats, expanded: expanded, canExpand: true,
                            onChoose: { engine.snapshot.highlighted = $0 }, onPage: { _ in }, onToggle: { expanded.toggle() })
                    }
                }
                .padding(24).frame(maxWidth: .infinity, alignment: .leading)
                .environment(\.colorScheme, dark ? .dark : .light)
                .background {
                    if detailed {
                        Canvas { context, size in
                            for x in stride(from: CGFloat(0), through: size.width + size.height, by: 24) {
                                var line = Path(); line.move(to: CGPoint(x: x, y: 0)); line.addLine(to: CGPoint(x: x - size.height, y: size.height))
                                context.stroke(line, with: .color(dark ? .white.opacity(0.25) : .black.opacity(0.20)), lineWidth: 2)
                            }
                        }.clipShape(RoundedRectangle(cornerRadius: 20))
                    }
                }
                .background(LinearGradient(colors: dark ? [Color(red: 0.08, green: 0.12, blue: 0.18), Color(red: 0.29, green: 0.20, blue: 0.25)] : [Brand.paper, Color(red: 0.76, green: 0.84, blue: 0.88)], startPoint: .leading, endPoint: .trailing), in: RoundedRectangle(cornerRadius: 20))
            }
        }
        .padding(24).frame(width: 720)
        .onAppear {
            preferences.candidateTone = .system
            engine.snapshot = Snapshot(preedit: "wo", candidates: ["我", "握", "窩", "臥", "沃", "を"], input: "wo", confidentCandidates: ["我", "握", "窩", "臥", "沃"], japaneseCandidates: ["を"])
        }
    }
}
// Uses the production panel style, so context menus and backdrop sampling can
// be checked without replacing the user's active input service.
final class CandidatePanelPreview {
    let window = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
    private let engine: Engine
    private var expanded = false
    init(engine: Engine) {
        self.engine = engine
        window.isOpaque = false; window.backgroundColor = .clear
        window.hasShadow = true; window.level = .popUpMenu; window.isReleasedWhenClosed = false
    }
    func show() {
        let view = GlassCandidateView(engine: engine, cats: Preferences.shared.catGlassSkin, expanded: expanded, canExpand: true,
            onChoose: { [weak self] in self?.engine.snapshot.highlighted = $0 }, onPage: { _ in },
            onToggle: { [weak self] in guard let self else { return }; self.expanded.toggle(); self.show() },
            onAppearanceChange: { [weak self] in self?.show() })
        let host = NSHostingView(rootView: view); window.contentView = host; host.layoutSubtreeIfNeeded()
        let origin = window.isVisible ? window.frame.origin : (NSApp.mainWindow?.frame.origin ?? .zero).applying(.init(translationX: 260, y: 100))
        window.setFrame(NSRect(origin: origin, size: host.fittingSize), display: true)
        window.orderFrontRegardless()
    }
}

@main enum Preview {
    static func main() {
        let app = NSApplication.shared; app.setActivationPolicy(.regular)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 700), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = "Meowpuut Glass Preview"
        window.contentView = NSHostingView(rootView: GlassPreview())
        window.center(); window.makeKeyAndOrderFront(nil); app.activate(ignoringOtherApps: true); app.run()
    }
}
