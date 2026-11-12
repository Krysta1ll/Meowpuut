import AppKit
import SwiftUI

// What the macOS candidate window draws (docs/DESIGN.md): the Liquid Glass
// bar with 米米莫 and 莫莫 on its edge, and the panel that opens under it, or
// over it when the window is above the caret. The candidates in the row, their order and
// their number keys come from CollapsedCandidateLayout (CandidateWindow.swift),
// which key handling uses too.

/// Cat sprites copied into Contents/Resources by scripts/build.sh.
enum DesktopCatArt {
    private static var cache: [String: NSImage] = [:]

    static func image(_ name: String) -> NSImage? {
        if let hit = cache[name] { return hit }
        let image = Bundle.main.image(forResource: NSImage.Name(name))
        if let image { cache[name] = image }
        return image
    }

    /// The picture for a sprite. With `CatArt.sprite` this is where a cat's
    /// look comes from: a festival that brings its own cats returns its picture here.
    static func picture(_ sprite: CatSprite) -> Image {
        image(sprite.name).map { Image(nsImage: $0) } ?? Image(systemName: "cat")
    }

    /// Holiday outfit layers (exported at 2x by scripts/render_cats.py).
    static func festival(_ outfit: FestivalOutfit, _ file: String) -> NSImage? {
        let key = outfit.id + "/" + file
        if let hit = cache[key] { return hit }
        guard let url = Festivals.imageURL(outfit: outfit, file: file), let image = NSImage(contentsOf: url) else { return nil }
        if let rep = image.representations.first { image.size = NSSize(width: CGFloat(rep.pixelsWide) / 2, height: CGFloat(rep.pixelsHigh) / 2) }
        cache[key] = image
        return image
    }
}

/// How the window is drawn. The installed input method follows the system
/// settings; the preview and the snapshots set each one to check that path.
struct CandidateLook: Equatable {
    var dark = false
    /// Reduce Transparency or Increase Contrast: opaque glass, no caustic.
    var opaque = false
    /// Increase Contrast: darker outlines and full-strength secondary text.
    var contrast = false
    /// Reduce Motion: the cats do not breathe; only fades remain.
    var still = false

    var text: Color {
        if contrast { return dark ? .white : .black }
        return CandidateInk.text(dark: dark)
    }
    var secondary: Color {
        if contrast { return text.opacity(0.85) }
        return CandidateInk.secondary(dark: dark)
    }
    var outline: Color { (dark ? Color.white : Color.black).opacity(0.7) }

    /// A tone under the glass. On the desktop anything can be behind the
    /// window: without this, dark glass over a white page (or light glass
    /// over a black one) turns mid grey and the candidates lose contrast.
    func floor(_ strength: GlassStrength) -> Color {
        let amount: Double = switch (strength, dark) {
        case (.soft, false): 0.62
        case (.balanced, false): 0.52
        case (.vivid, false): 0.36
        case (.soft, true): 0.74
        case (.balanced, true): 0.64
        case (.vivid, true): 0.46
        }
        return dark ? Color(red: 0.169, green: 0.153, blue: 0.161).opacity(amount) : Color.white.opacity(amount)
    }
}

struct CandidateLookOverride: Equatable {
    var dark: Bool?
    var opaque: Bool?
    var contrast: Bool?
    var still: Bool?
}

/// The selected candidate's capsule. A holiday outfit may bring its own colour.
private struct DeskPill: View {
    var palette: CandidatePalette
    var festival: Color?
    var look: CandidateLook

    var body: some View {
        // Increase Contrast: the palette's deep tone under the white text, whatever the holiday colour, and an outline.
        CandidatePill(palette: palette, dark: look.dark, highContrast: look.contrast, festival: festival)
            .overlay {
                if look.contrast { Capsule(style: .continuous).stroke(look.outline, lineWidth: 1) }
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// The number key in front of a candidate. "⇧1" is drawn as a small shift mark and the digit.
private struct DeskHint: View {
    var label: String
    var selected: Bool
    var look: CandidateLook

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0.5) {
            if label.hasPrefix("⇧") {
                Image(systemName: "shift").font(.system(size: 7.5, weight: .semibold)).opacity(0.85)
            }
            Text(label.hasPrefix("⇧") ? String(label.dropFirst()) : label)
                .font(.system(size: Desk.hintFont, weight: .medium, design: .rounded))
        }
        .foregroundStyle(selected ? Color.white.opacity(look.contrast ? 1 : 0.88) : look.secondary)
        .fixedSize()
        .accessibilityHidden(true)
    }
}

/// A candidate under the pointer gets a pale capsule; a press shrinks it a little.
private struct DeskCellStyle: ButtonStyle {
    var tint: Color
    var look: CandidateLook
    var selected: Bool
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if !selected {
                    Capsule(style: .continuous)
                        .fill(tint.opacity(configuration.isPressed ? (look.dark ? 0.30 : 0.22) : (look.dark ? 0.18 : 0.12)))
                        .opacity(hovering || configuration.isPressed ? 1 : 0)
                }
            }
            .scaleEffect(configuration.isPressed && !look.still ? 0.96 : 1)
            .animation(look.still ? nil : .easeOut(duration: 0.1), value: configuration.isPressed)
            .onHover { hovering = $0 }
    }
}

struct GlassCandidateView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.colorScheme) private var systemScheme
    @ObservedObject private var preferences = Preferences.shared
    @ObservedObject var engine: Engine
    var cats: Bool
    var expanded: Bool
    var canExpand: Bool
    /// The panel opens over the bar: the window is above the caret.
    var panelAbove: Bool
    /// Height and width the whole window may take, clear margin included.
    var maxHeight: CGFloat
    var maxWidth: CGFloat
    /// False while the panel is hidden, so the cats stop breathing.
    var visible: Bool
    /// The day the cats' arrangement is for; set when the panel comes up, so
    /// they never move while something is being typed.
    var catDay = CatSchedule.day()
    var forced: CandidateLookOverride
    var onChoose: (Int) -> Void
    var onPage: (Int32) -> Void
    var onToggle: () -> Void
    var onAppearanceChange: () -> Void

    init(engine: Engine, cats: Bool = true, expanded: Bool, canExpand: Bool, panelAbove: Bool = false,
         maxHeight: CGFloat = .greatestFiniteMagnitude, maxWidth: CGFloat = .greatestFiniteMagnitude,
         visible: Bool = true, forced: CandidateLookOverride = CandidateLookOverride(),
         onChoose: @escaping (Int) -> Void, onPage: @escaping (Int32) -> Void, onToggle: @escaping () -> Void,
         onAppearanceChange: @escaping () -> Void = {}) {
        self.engine = engine
        self.cats = cats
        self.expanded = expanded
        self.canExpand = canExpand
        self.panelAbove = panelAbove
        self.maxHeight = maxHeight
        self.maxWidth = maxWidth
        self.visible = visible
        self.forced = forced
        self.onChoose = onChoose
        self.onPage = onPage
        self.onToggle = onToggle
        self.onAppearanceChange = onAppearanceChange
    }

    /// Visible height of the collapsed window, without the clear margin.
    static func collapsedHeight(cats: Bool) -> CGFloat { cats ? Desk.catsBarHeight : Desk.glass + 8 }
    /// The least the expanded panel needs: three rows and the page buttons.
    static let minimumPanelHeight: CGFloat = panelGap + 16 + 3 * Desk.gridRowHeight + 2 * Desk.gridRowSpacing + 26
    private static let panelGap: CGFloat = 6

    private var look: CandidateLook {
        let scheme: ColorScheme = switch preferences.candidateTone {
        case .system: systemScheme
        case .light: .light
        case .dark: .dark
        }
        let highContrast = forced.contrast ?? (contrast == .increased)
        return CandidateLook(dark: forced.dark ?? (scheme == .dark),
                             opaque: forced.opaque ?? (reduceTransparency || highContrast),
                             contrast: highContrast,
                             still: forced.still ?? reduceMotion)
    }
    /// Labels and menus follow the system language, like the settings window; only candidates follow the output script.
    private var t: InterfaceText { .system }
    private var palette: CandidatePalette { preferences.candidatePalette }
    private var outfit: FestivalOutfit? { preferences.festivalOutfits ? Festivals.active() : nil }
    private var style: GlassShape { preferences.glassShape }

    var body: some View {
        let look = self.look
        VStack(alignment: .leading, spacing: Self.panelGap) {
            if !panelAbove { bar(look) }
            if expanded && !engine.snapshot.candidates.isEmpty {
                let chrome = Desk.margin.top + Desk.margin.bottom + Self.collapsedHeight(cats: cats) + Self.panelGap
                DeskExpandedPanel(engine: engine, look: look, palette: palette, festivalPill: outfit?.pillColor,
                                  strength: preferences.glassStrength, t: t,
                                  maximumHeight: max(60, maxHeight - chrome),
                                  maximumWidth: maxWidth - Desk.margin.left - Desk.margin.right - (cats ? Desk.glassLeft + Desk.momoOverhang : 0),
                                  onChoose: onChoose, onPage: onPage)
                    .padding(.leading, cats ? Desk.glassLeft : 0)
                    .padding(.trailing, cats ? Desk.momoOverhang : 0)
                    .transition(.opacity)
            }
            if panelAbove { bar(look) }
        }
        .padding(EdgeInsets(top: Desk.margin.top, leading: Desk.margin.left, bottom: Desk.margin.bottom, trailing: Desk.margin.right))
        .fixedSize()
        .environment(\.colorScheme, look.dark ? .dark : .light)
        .contextMenu { menu }
    }

    // MARK: Bar

    private struct Cell {
        var index: Int
        var text: String
        var japanese: Bool
        /// Set when the text is shortened to this width.
        var width: CGFloat?
    }

    @ViewBuilder
    private func bar(_ look: CandidateLook) -> some View {
        if cats { catsBar(look) } else { plainBar(look) }
    }

    private var cells: [Cell] {
        let snapshot = engine.snapshot
        let layout = CollapsedCandidateLayout(snapshot)
        let japaneseTexts = markedJapanese(snapshot)
        return layout.indices.compactMap { index in
            guard let text = compactCandidateText(snapshot, at: index) else { return nil }
            let japanese = japaneseTexts.contains(text)
            let full = CandidateMeasure.width(text)
            let given = (layout.textWidths[index] ?? full) - (japanese ? CollapsedCandidateLayout.paw : 0)
            return Cell(index: index, text: text, japanese: japanese, width: given < full ? max(Desk.font * 2, given) : nil)
        }
    }

    @ViewBuilder
    private func candidateRow(_ look: CandidateLook) -> some View {
        if engine.snapshot.candidates.isEmpty {
            Text(engine.snapshot.preedit)
                .font(.system(size: Desk.font))
                .foregroundStyle(look.text)
                .lineLimit(1).truncationMode(.head)
                .frame(minWidth: 96, maxWidth: 260, alignment: .leading)
                .padding(.horizontal, Desk.cellPadding)
                .accessibilityLabel(t("輸入中：", "输入中：") + engine.snapshot.preedit)
        } else {
            HStack(spacing: Desk.cellSpacing) {
                ForEach(Array(cells.enumerated()), id: \.element.index) { ordinal, cell in
                    candidate(cell, shortcut: engine.candidateShortcut(ordinal), look: look)
                }
            }
            .animation(look.still ? nil : .easeOut(duration: 0.12), value: engine.snapshot.highlighted)
        }
    }

    /// The row and the expand key. While the panel is open the row shows what
    /// is being typed, so only the panel carries numbers; the hidden row
    /// keeps the bar as wide as it was.
    private func row(_ look: CandidateLook) -> some View {
        HStack(spacing: 0) {
            if expanded && !engine.snapshot.candidates.isEmpty {
                candidateRow(look)
                    .hidden()
                    .accessibilityHidden(true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay {
                        HStack(spacing: 8) {
                            Text(engine.snapshot.preedit)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(look.text)
                                .lineLimit(1).truncationMode(.head)
                                .accessibilityLabel(t("輸入中：", "输入中：") + engine.snapshot.preedit)
                            Spacer(minLength: 0)
                            Text(engine.modeLabel)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(look.secondary)
                                .fixedSize()
                        }
                        .padding(.horizontal, Desk.cellPadding)
                    }
            } else {
                candidateRow(look)
            }
            if canExpand || expanded {
                Button(action: onToggle) {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .bold))
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                        .animation(look.still ? nil : .easeInOut(duration: 0.18), value: expanded)
                        .foregroundStyle(look.text.opacity(look.contrast ? 1 : 0.72))
                        .frame(width: Desk.chevronWidth, height: Desk.pill)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(expanded ? t("收起候選", "收起候选") : t("展開候選", "展开候选"))
                .help(expanded ? t("收起候選（Esc）", "收起候选（Esc）") : t("展開候選（↓）", "展开候选（↓）"))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(t("候選字", "候选字"))
    }

    private func glassBar(_ look: CandidateLook) -> some View {
        let shape = CandidateGlassShape(style: style)
        return ZStack {
            if look.opaque {
                // The opaque bar still floats: the shared material leaves its shadow out here.
                shape.fill(Color(red: 0.24, green: 0.16, blue: 0.27).opacity(look.dark ? 0 : 0.14))
                    .blur(radius: 6).offset(y: 4).padding(.horizontal, 6)
                if look.dark { shape.fill(Color.black.opacity(0.45)).blur(radius: 7).offset(y: 4).padding(.horizontal, 6) }
            } else {
                // A thin shadow all round keeps the outline on a white page.
                shape.fill(Color.black.opacity(look.dark ? 0.35 : 0.13)).blur(radius: 1.3).offset(y: 0.6)
                shape.fill(look.floor(preferences.glassStrength))
            }
            LiquidGlassBar(style: style, dark: look.dark, accessible: look.opaque,
                           strength: preferences.glassStrength, tint: palette.color(dark: look.dark))
                .equatable()
            if look.contrast { shape.stroke(look.outline, lineWidth: 1) }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// The cats on a bar this wide. The day's arrangement is chosen among
    /// those that fit a bar of ordinary width, so it stays the same while the
    /// bar grows and shrinks with the candidates; on a bar too short for it,
    /// the first arrangement that fits is shown.
    private func catPlaces(width: CGFloat, chevron: Bool) -> [CatPlacement] {
        let stage = Desk.stage(width: width, shape: style, chevron: chevron)
        let usual = Desk.stage(width: 360, shape: style, chevron: true)
        let today = CatSchedule.arrangement(setting: preferences.catPlacement, day: catDay, on: usual)?.arrangement
        return today?.place(on: stage) ?? CatArrangement.all.lazy.compactMap { $0.place(on: stage) }.first ?? []
    }

    private func catsBar(_ look: CandidateLook) -> some View {
        let band = CandidateGlassShape.contentBand(style, height: Desk.glass)
        let glassInsets = EdgeInsets(top: Desk.glassTop, leading: Desk.glassLeft, bottom: Desk.glassBottom, trailing: Desk.momoOverhang)
        let chevron = canExpand || expanded
        return row(look)
            .frame(height: band.bottom - band.top)
            .frame(maxWidth: expanded ? .infinity : nil, alignment: .leading)
            .padding(.leading, Desk.glassLeft + Desk.rowLead)
            .padding(.trailing, chevron ? Desk.rowTrail : Desk.rowTrailPlain)
            .padding(.top, Desk.glassTop + band.top)
            .padding(.bottom, Desk.glass - band.bottom + Desk.glassBottom)
            .background { glassBar(look).padding(glassInsets) }
            // The cats in front: behind the bar with their paws over its edge, or standing at its end.
            .overlay {
                GeometryReader { proxy in
                    CatFigures(places: catPlaces(width: proxy.size.width, chevron: chevron), size: proxy.size,
                               glass: CGRect(x: Desk.glassLeft, y: Desk.glassTop, width: proxy.size.width - Desk.glassLeft - Desk.momoOverhang, height: Desk.glass),
                               shape: style, dark: look.dark, shadows: !look.opaque, breathing: visible && !look.still,
                               picture: DesktopCatArt.picture)
                }
            }
    }

    private func plainBar(_ look: CandidateLook) -> some View {
        let band = CandidateGlassShape.contentBand(style, height: Desk.glass)
        return row(look)
            .frame(height: band.bottom - band.top)
            .frame(maxWidth: expanded ? .infinity : nil, alignment: .leading)
            .padding(.horizontal, 6)
            .padding(.top, band.top)
            .padding(.bottom, Desk.glass - band.bottom)
            .background { glassBar(look) }
            .padding(.vertical, 4)
    }

    private func candidate(_ cell: Cell, shortcut: String, look: CandidateLook) -> some View {
        let selected = cell.index == engine.snapshot.highlighted
        let spoken = shortcut.hasPrefix("⇧") ? "Shift " + shortcut.dropFirst() : shortcut
        return Button { onChoose(cell.index) } label: {
            HStack(spacing: 0) {
                DeskHint(label: shortcut, selected: selected, look: look)
                    .padding(.trailing, Desk.hintGap)
                // The medium weight sizes the cell, so the row does not shift as the highlight moves.
                Text(cell.text)
                    .font(.system(size: Desk.font, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(width: cell.width, alignment: .leading)
                    .fixedSize()
                    .hidden()
                    .overlay(alignment: .leading) {
                        Text(cell.text)
                            .font(.system(size: Desk.font, weight: selected ? .medium : .regular))
                            .foregroundStyle(selected ? Color.white : look.text)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                if cell.japanese {
                    Image(systemName: "pawprint.fill").font(.system(size: (Desk.font * 0.45).rounded()))
                        .rotationEffect(.degrees(-20))
                        .foregroundStyle(selected ? Color.white.opacity(0.88) : look.secondary)
                        .frame(width: Desk.pawWidth)
                        .padding(.leading, Desk.pawGap)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, Desk.cellPadding)
            .frame(height: Desk.pill)
            .background { if selected { DeskPill(palette: palette, festival: outfit?.pillColor, look: look) } }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(DeskCellStyle(tint: palette.color(dark: look.dark), look: look, selected: selected))
        .help(cell.text)
        .accessibilityLabel(cell.text + (cell.japanese ? t("，日語", "，日语") : ""))
        .accessibilityHint(t("按 \(spoken) 選這個", "按 \(spoken) 选这个"))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Menu

    private func check<Value: Equatable>(_ title: String, _ value: Value, _ current: Value, _ choose: @escaping () -> Void) -> some View {
        Button { choose(); onAppearanceChange() } label: {
            if value == current { Label(title, systemImage: "checkmark") } else { Text(title) }
        }
    }

    @ViewBuilder
    private var menu: some View {
        Button(expanded ? t("收起候選", "收起候选") : t("展開候選", "展开候选"), action: onToggle)
            .disabled(!canExpand && !expanded)
        Divider()
        Toggle(t("米米莫與莫莫", "米米莫与莫莫"), isOn: Binding(get: { preferences.catGlassSkin },
                                                         set: { preferences.catGlassSkin = $0; onAppearanceChange() }))
        Menu("位置") {
            check(t("每天換", "每天换"), CatSchedule.daily, preferences.catPlacement) { preferences.catPlacement = CatSchedule.daily }
            ForEach(CatArrangement.all) { arrangement in
                check(arrangement.name(t), arrangement.id, preferences.catPlacement) { preferences.catPlacement = arrangement.id }
            }
        }
        Toggle(t("節日裝扮", "节日装扮"), isOn: Binding(get: { preferences.festivalOutfits },
                                                   set: { preferences.festivalOutfits = $0; onAppearanceChange() }))
        Menu(t("造型", "造型")) {
            ForEach(GlassShape.allCases) { shape in
                check(shape.name(t), shape, preferences.glassShape) { preferences.glassShape = shape }
            }
        }
        Menu(t("色調", "色调")) {
            ForEach(CandidateTone.allCases) { tone in
                check(tone.name(t), tone, preferences.candidateTone) { preferences.candidateTone = tone }
            }
        }
        Menu(t("色系", "色系")) {
            ForEach(CandidatePalette.allCases) { palette in
                check(palette.name(t), palette, preferences.candidatePalette) { preferences.candidatePalette = palette }
            }
        }
        Menu(t("玻璃", "玻璃")) {
            ForEach(GlassStrength.allCases) { strength in
                check(strength.name(t), strength, preferences.glassStrength) { preferences.glassStrength = strength }
            }
        }
    }
}

extension FestivalOutfit {
    /// The outfit's colour for the selected candidate ("#RRGGBB"), when it has one.
    var pillColor: Color? {
        guard let pill, pill.hasPrefix("#"), pill.count == 7, let value = UInt32(pill.dropFirst(), radix: 16) else { return nil }
        return Color(red: Double((value >> 16) & 0xff) / 255, green: Double((value >> 8) & 0xff) / 255, blue: Double(value & 0xff) / 255)
    }
}

/// The glass behind the expanded candidates: the bar's material on a rounded rectangle.
private struct DeskPanelGlass: View, Equatable {
    var look: CandidateLook
    var strength: GlassStrength
    /// The palette's colour: the dark panel's rim, as on the bar.
    var tint: Color

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Desk.panelCorner, style: .continuous)
        let dark = look.dark
        let smoke = Color(red: 0.227, green: 0.188, blue: 0.172)
        let rim = tint.blend(.white, 0.25)
        let amount: Double = switch strength { case .soft: 0.34; case .balanced: 0.20; case .vivid: 0.08 }
        ZStack {
            shape.fill(Color(red: 0.24, green: 0.16, blue: 0.27).opacity(dark ? 0 : 0.13))
                .blur(radius: 8).offset(y: 5).padding(.horizontal, 6)
            if dark { shape.fill(Color.black.opacity(0.5)).blur(radius: 9).offset(y: 5).padding(.horizontal, 6) }
            if look.opaque {
                shape.fill(dark ? Color(red: 0.169, green: 0.153, blue: 0.161) : Color(red: 0.957, green: 0.949, blue: 0.965))
            } else {
                shape.fill(Color.black.opacity(dark ? 0.35 : 0.13)).blur(radius: 1.3).offset(y: 0.6)
                shape.fill(look.floor(strength))
                shape.fill(dark ? smoke.opacity(amount + 0.12) : Color.white.opacity(amount))
                    .glassEffect(dark ? Glass.regular.tint(smoke.opacity(0.55)) : Glass.regular, in: shape)
                shape.fill(LinearGradient(colors: [.white.opacity(dark ? 0.07 : 0.26), .white.opacity(dark ? 0 : 0.05)],
                                          startPoint: .top, endPoint: .bottom))
            }
            shape.stroke(LinearGradient(colors: dark ? [rim.opacity(0.85), rim.opacity(0.18)] : [Color.white, Color.white.opacity(0.45)],
                                        startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.2)
            if look.contrast { shape.stroke(look.outline, lineWidth: 1) }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The expanded candidates: every candidate of the page on one line each,
/// short words three to a row, then the Japanese candidate the row kept from
/// a later page. Arrow keys move through the same rows.
private struct DeskExpandedPanel: View {
    @ObservedObject var engine: Engine
    var look: CandidateLook
    var palette: CandidatePalette
    var festivalPill: Color?
    var strength: GlassStrength
    var t: InterfaceText
    var maximumHeight: CGFloat
    var maximumWidth: CGFloat
    var onChoose: (Int) -> Void
    var onPage: (Int32) -> Void

    private static let padding: CGFloat = 8

    var body: some View {
        let snapshot = engine.snapshot
        let candidates = expandedCandidates(snapshot)
        let limit = max(180, min(Desk.gridMaxWidth, maximumWidth) - Self.padding * 2)
        let rows = expandedCandidateRows(candidates, limit: limit)
        let width = expandedCandidateWidth(candidates, limit: limit)
        let paged = snapshot.page > 0 || !snapshot.lastPage
        let gridHeight = CGFloat(rows.count) * Desk.gridRowHeight + CGFloat(max(0, rows.count - 1)) * Desk.gridRowSpacing
        let room = maximumHeight - Self.padding * 2 - (paged ? 26 : 0)
        let japanese = markedJapanese(snapshot)
        VStack(spacing: 4) {
            if gridHeight > room {
                // Only a short display gets here: whole rows, and the highlight stays in view.
                let stride = Desk.gridRowHeight + Desk.gridRowSpacing
                let shown = max(1, floor((room + Desk.gridRowSpacing) / stride))
                ScrollViewReader { proxy in
                    ScrollView(.vertical) { grid(rows, japanese: japanese) }
                        .frame(height: shown * stride - Desk.gridRowSpacing)
                        .onChange(of: snapshot.highlighted) { _, index in proxy.scrollTo(index) }
                        .onChange(of: snapshot.page) { _, _ in proxy.scrollTo(0, anchor: .top) }
                }
            } else {
                grid(rows, japanese: japanese)
            }
            if paged {
                HStack(spacing: 10) {
                    page(0xff55, symbol: "chevron.left", label: t("上一頁", "上一页")).disabled(snapshot.page == 0)
                    Text("\(snapshot.page + 1)").font(.system(size: 10, weight: .medium, design: .rounded))
                        .foregroundStyle(look.secondary)
                        .accessibilityLabel(t("第 \(snapshot.page + 1) 頁", "第 \(snapshot.page + 1) 页"))
                    page(0xff56, symbol: "chevron.right", label: t("下一頁", "下一页")).disabled(snapshot.lastPage)
                }
                .frame(height: 22)
            }
        }
        .padding(Self.padding)
        // The ideal width stops the window at the computed width; a longer candidate is shortened in the middle.
        .frame(minWidth: width + Self.padding * 2, idealWidth: width + Self.padding * 2, maxWidth: .infinity)
        .background { DeskPanelGlass(look: look, strength: strength, tint: palette.color(dark: look.dark)).equatable() }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(t("全部候選", "全部候选"))
    }

    private func grid(_ rows: [[Int]], japanese: Set<String>) -> some View {
        VStack(alignment: .leading, spacing: Desk.gridRowSpacing) {
            ForEach(rows.indices, id: \.self) { row in
                HStack(spacing: 4) {
                    ForEach(rows[row], id: \.self) { index in
                        cell(index, japanese: japanese)
                    }
                }
            }
        }
        .animation(look.still ? nil : .easeOut(duration: 0.12), value: engine.snapshot.highlighted)
    }

    private func cell(_ index: Int, japanese: Set<String>) -> some View {
        let text = compactCandidateText(engine.snapshot, at: index) ?? ""
        let selected = index == engine.snapshot.highlighted
        let shortcut = engine.candidateShortcut(index)
        let spoken = shortcut.hasPrefix("⇧") ? "Shift " + shortcut.dropFirst() : shortcut
        let marked = japanese.contains(text)
        return Button { onChoose(index) } label: {
            HStack(spacing: 0) {
                DeskHint(label: shortcut, selected: selected, look: look)
                    .frame(width: Desk.gridHint(shifted: shortcut.hasPrefix("⇧")), alignment: .trailing)
                    .padding(.trailing, 5)
                Text(text)
                    .font(.system(size: Desk.font, weight: selected ? .medium : .regular))
                    .foregroundStyle(selected ? Color.white : look.text)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if marked {
                    Image(systemName: "pawprint.fill").font(.system(size: (Desk.font * 0.45).rounded()))
                        .rotationEffect(.degrees(-20))
                        .foregroundStyle(selected ? Color.white.opacity(0.88) : look.secondary)
                        .frame(width: Desk.pawWidth)
                        .padding(.leading, Desk.pawGap)
                        .accessibilityHidden(true)
                }
            }
            .padding(.leading, shortcut.hasPrefix("⇧") ? 6 : 9).padding(.trailing, 10)
            .frame(height: Desk.pill)
            .background { if selected { DeskPill(palette: palette, festival: festivalPill, look: look) } }
            .frame(maxWidth: .infinity, minHeight: Desk.gridRowHeight, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(text)
        .id(index)
        .accessibilityLabel(text + (marked ? t("，日語", "，日语") : ""))
        .accessibilityHint(t("按 \(spoken) 選這個", "按 \(spoken) 选这个"))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func page(_ key: Int32, symbol: String, label: String) -> some View {
        Button { onPage(key) } label: {
            HStack(spacing: 3) {
                if key == 0xff55 { Image(systemName: symbol) }
                Text(label)
                if key == 0xff56 { Image(systemName: symbol) }
            }
            .font(.system(size: 10, weight: .medium))
            .foregroundStyle(look.secondary)
            .padding(.horizontal, 6).frame(height: 22)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .help(label + (key == 0xff55 ? "（⌥← / Page Up）" : "（⌥→ / Page Down）"))
    }
}
