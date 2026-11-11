import SwiftUI
import UIKit

/// Glass panel behind the expanded candidates (one more glass while open).
struct GlassPanel: View, Equatable {
    var dark: Bool
    var accessible: Bool
    /// The palette's colour: the dark panel's rim, as on the bar.
    var tint: Color

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        ZStack {
            let fill = accessible ? (dark ? Color(red: 0.169, green: 0.153, blue: 0.161) : Color(red: 0.957, green: 0.949, blue: 0.965))
                                  : (dark ? Color(red: 0.227, green: 0.188, blue: 0.172).opacity(0.32) : Color.white.opacity(0.30))
            if accessible {
                shape.fill(fill)
            } else if #available(iOS 26, *), LiquidGlass.available {
                shape.fill(fill).glassEffect(.regular, in: shape)
            } else {
                // Before iOS 26: frosted glass, as on the bar.
                shape.fill(.thinMaterial).environment(\.colorScheme, dark ? .dark : .light)
                shape.fill(fill)
            }
            if !accessible {
                shape.fill(LinearGradient(colors: [.white.opacity(dark ? 0.06 : 0.25), .clear], startPoint: .top, endPoint: .bottom))
            }
            shape.stroke(LinearGradient(colors: dark ? [tint.blend(.white, 0.25).opacity(0.6), .clear]
                                                     : [.white.opacity(0.95), .white.opacity(0.3)],
                                        startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// iOS 26 blurs content at a scroll view's edges; the panel's own glass is enough.
struct PlainScrollEdges: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, *) { content.scrollEdgeEffectHidden(true, for: .all) } else { content }
    }
}


/// The panel under the bar: every candidate of the reading being typed, or,
/// when the ^_^ list is open and nothing is being typed, the kaomoji by group.
struct ExpandedCandidatesView: View {
    @ObservedObject var engine: Engine
    @ObservedObject var ui: KeyboardUIState
    @ObservedObject var preferences = Preferences.shared
    let actions: KeyboardActions

    private enum Filter { case all, phrases, symbols }
    /// One cell of the grid. A kaomoji from the ^_^ list has no candidate.
    private struct Entry {
        var text: String
        var candidate: IndexedCandidate? = nil
        var japanese = false
        var selected = false
        var spoken: String
    }

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var filter = Filter.all
    @State private var pool: [IndexedCandidate] = []
    @State private var deleter: RepeatDeleteDriver?
    /// The kaomoji group shown, as an index into `kaomojiGroups`.
    @State private var group = 0

    private var t: InterfaceText { InterfaceText(simplified: preferences.simplified) }
    private var dark: Bool { ui.dark }
    private var accent: Color { preferences.candidatePalette.color(dark: dark) }
    private var kaomojiList: Bool { ui.kaomojiOpen && engine.snapshot.preedit.isEmpty }
    /// The paw mark belongs to Chinese typing; in Japanese mode every candidate is Japanese.
    private var japanese: Set<String> { engine.inputMode == .japanese ? [] : Set(engine.snapshot.japaneseCandidates) }

    /// Emoji and kaomoji of the first candidate (sources -7 and -8, and -1 when it is not a kana spelling).
    private func isSymbol(_ candidate: IndexedCandidate) -> Bool {
        candidate.source == -7 || candidate.source == -8 || (candidate.source == -1 && !japanese.contains(candidate.text))
    }

    /// The bar's first page first, in the bar's order, so the panel reads
    /// on from what the bar showed.
    private var shown: [IndexedCandidate] {
        var byIndex: [Int: IndexedCandidate] = [:]
        for candidate in pool where byIndex[candidate.index] == nil { byIndex[candidate.index] = candidate }
        let ordered = ExpandedGridLayout.order(pool: pool.map(\.index), lead: engine.candidateOrder).compactMap { byIndex[$0] }
        switch filter {
        case .all: return ordered
        case .phrases: return ordered.filter { $0.text.count > 1 && !isSymbol($0) && $0.text.lowercased() != engine.snapshot.input.lowercased() }
        case .symbols: return ordered.filter(isSymbol)
        }
    }

    /// The candidate the bar had selected; Return and closing the keyboard commit it.
    private var selectedIndex: Int? {
        let order = engine.candidateOrder, highlighted = engine.snapshot.highlighted
        return order.indices.contains(highlighted) ? order[highlighted] : nil
    }

    /// The recent kaomoji, when there are any, then the groups of the list.
    private var kaomojiGroups: [(title: String, items: [String])] {
        let recent = ui.kaomojiRecent.isEmpty ? [] : [(title: t("常用", "常用"), items: ui.kaomojiRecent)]
        return recent + Kaomoji.groups.map { (title: preferences.simplified ? $0.simplifiedName : $0.name, items: $0.items) }
    }

    private var entries: [Entry] {
        if kaomojiList {
            let groups = kaomojiGroups
            guard groups.indices.contains(group) else { return [] }
            return groups[group].items.enumerated().map { index, text in
                Entry(text: text, spoken: (Kaomoji.spokenName(text, simplified: preferences.simplified) ?? text) + " \(index + 1)")
            }
        }
        let selected = selectedIndex, japanese = japanese
        return shown.map {
            Entry(text: $0.text, candidate: $0, japanese: japanese.contains($0.text), selected: $0.index == selected,
                  spoken: Kaomoji.spokenName($0.text, simplified: preferences.simplified)
                      ?? $0.text + (japanese.contains($0.text) ? t("，日語", "，日语") : ""))
        }
    }

    var body: some View {
        GeometryReader { proxy in
            let side: CGFloat = 50
            let gridWidth = max(1, proxy.size.width - side - 18)
            let columns = ExpandedGridLayout.columns(gridWidth: gridWidth, phone: ui.region.idiom == .phone)
            let columnWidth = gridWidth / CGFloat(columns)
            let font = ui.region.fontSize
            let items = entries
            let paw = CandidateTextMetrics.paw(size: font)
            let rows = ExpandedGridLayout.rows(widths: items.map {
                ExpandedGridLayout.cellWidth(text: CandidateTextMetrics.width($0.text, size: font), japanese: $0.japanese, paw: paw)
            }, columnWidth: columnWidth, columns: columns)
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    Group {
                        if kaomojiList { groupBar } else { filterBar(count: items.count) }
                    }
                    .frame(height: 36)
                    ScrollView {
                        LazyVStack(spacing: 2) {
                            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                                HStack(spacing: 0) {
                                    ForEach(row, id: \.index) { cell in
                                        cellView(items[cell.index], font: font)
                                            .frame(width: columnWidth * CGFloat(cell.columns))
                                    }
                                }
                            }
                        }
                    }
                    .scrollIndicators(.visible)
                    .modifier(PlainScrollEdges())
                    .id(kaomojiList ? "kaomoji|\(group)" : "\(engine.snapshot.input)|\(filter)|\(preferences.simplified)")
                    .overlay {
                        if items.isEmpty {
                            Text(filter == .phrases ? t("沒有詞組", "没有词组") : t("沒有候選", "没有候选"))
                                .font(.system(size: 15)).foregroundStyle(CandidateInk.secondary(dark: dark))
                        }
                    }
                }
                .frame(width: gridWidth)
                // The bar's chevron, now pointing up, closes the panel.
                VStack(spacing: 6) {
                    deleteButton
                    Spacer(minLength: 0)
                }
                .frame(width: side)
                .padding(.top, 4)
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background {
                GlassPanel(dark: dark, accessible: reduceTransparency || contrast == .increased, tint: accent)
                    .equatable()
                    .padding(.horizontal, 4)
                    .padding(.vertical, 2)
            }
        }
        .onAppear { pool = actions.pool() }
        .onChange(of: engine.snapshot.input) { _, _ in
            pool = actions.pool()
            if filter == .symbols, !pool.contains(where: isSymbol) { filter = .all }
        }
        // 莫莫 can switch the script while the reading stays: the pool is rebuilt.
        .onChange(of: preferences.simplified) { _, _ in pool = actions.pool() }
        .onDisappear { deleter?.stop() }
    }

    private func filterBar(count: Int) -> some View {
        HStack(spacing: 4) {
            chip(t("全部", "全部"), selected: filter == .all) { filter = .all }
            chip(t("詞組", "词组"), selected: filter == .phrases) { filter = .phrases }
            // Emoji and kaomoji of the first candidate, when it has any.
            if pool.contains(where: isSymbol) {
                chip(t("表情", "表情"), selected: filter == .symbols) { filter = .symbols }
            }
            Spacer(minLength: 0)
            Text("\(count)")
                .font(.system(size: 12).monospacedDigit())
                .foregroundStyle(CandidateInk.secondary(dark: dark))
                .padding(.trailing, 6)
                .accessibilityLabel(t("候選數量 \(count)", "候选数量 \(count)"))
        }
    }

    /// The kaomoji groups, in one row that scrolls sideways.
    private var groupBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(Array(kaomojiGroups.enumerated()), id: \.offset) { index, item in
                    chip(item.title, selected: group == index) { group = index }
                }
            }
        }
        .modifier(PlainScrollEdges())
    }

    private func cellView(_ entry: Entry, font: CGFloat) -> some View {
        Button {
            KeyboardFeedback.keyDown()
            if let candidate = entry.candidate { actions.choosePool(candidate) } else { actions.insertKaomoji(entry.text) }
        } label: {
            HStack(spacing: 3) {
                Text(entry.text)
                    .font(.system(size: font, weight: entry.selected ? .medium : .regular))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    // A kaomoji is drawn with fallback fonts and can come out wider
                    // than measured: it is drawn a little smaller, never cut.
                    .minimumScaleFactor(Kaomoji.contains(entry.text) ? 0.7 : 1)
                if entry.japanese {
                    Image(systemName: "pawprint.fill").font(.system(size: (font * 0.45).rounded()))
                        .rotationEffect(.degrees(-20)).opacity(0.6)
                }
            }
            .foregroundStyle(entry.selected ? Color.white : CandidateInk.text(dark: dark))
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background {
                if entry.selected {
                    CandidatePill(palette: preferences.candidatePalette, dark: dark, highContrast: contrast == .increased,
                                  festival: ui.outfit?.pillTint)
                        .padding(.vertical, 5).padding(.horizontal, 2)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(CandidatePressStyle(tint: accent, dark: dark))
        .accessibilityLabel(entry.spoken)
        .accessibilityAddTraits(entry.selected ? .isSelected : [])
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button {
            KeyboardFeedback.keyDown()
            action()
        } label: {
            Text(title)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(selected ? CandidateInk.text(dark: dark) : CandidateInk.secondary(dark: dark))
                .padding(.horizontal, 12)
                .frame(minWidth: 48, minHeight: 30)
                .background { if selected { Capsule().fill(accent.opacity(dark ? 0.26 : 0.16)) } }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // Holding delete here repeats, like the delete key.
    private var deleteButton: some View {
        Image(systemName: "delete.left")
            .font(.system(size: 17, weight: .medium))
            .foregroundStyle(CandidateInk.text(dark: dark))
            .frame(width: 44, height: 44)
            .background(Circle().fill(Color.white.opacity(dark ? 0.08 : 0.5)))
            .contentShape(Rectangle())
            .onLongPressGesture(minimumDuration: .infinity, maximumDistance: 40, perform: {}, onPressingChanged: { pressing in
                if pressing {
                    KeyboardFeedback.keyDown()
                    let driver = RepeatDeleteDriver { actions.delete(false) }
                    driver.onRepeatTick = { UIDevice.current.playInputClick() }
                    deleter = driver
                    driver.press()
                } else {
                    deleter?.stop()
                    deleter = nil
                }
            })
            .accessibilityElement()
            .accessibilityLabel(t("刪除", "删除"))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { actions.delete(false) }
    }
}
