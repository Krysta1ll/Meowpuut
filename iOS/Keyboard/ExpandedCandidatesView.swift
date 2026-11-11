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

struct ExpandedCandidatesView: View {
    @ObservedObject var engine: Engine
    @ObservedObject var ui: KeyboardUIState
    @ObservedObject var preferences = Preferences.shared
    let actions: KeyboardActions

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var phrasesOnly = false
    @State private var pool: [IndexedCandidate] = []
    @State private var deleter: RepeatDeleteDriver?

    private var t: InterfaceText { InterfaceText(simplified: preferences.simplified) }
    private var dark: Bool { ui.dark }
    private var accent: Color { preferences.candidatePalette.color(dark: dark) }

    /// The bar's first page first, in the bar's order, so the panel reads
    /// on from what the bar showed.
    private var shown: [IndexedCandidate] {
        var byIndex: [Int: IndexedCandidate] = [:]
        for candidate in pool where byIndex[candidate.index] == nil { byIndex[candidate.index] = candidate }
        let ordered = ExpandedGridLayout.order(pool: pool.map(\.index), lead: engine.candidateOrder).compactMap { byIndex[$0] }
        return phrasesOnly ? ordered.filter { $0.text.count > 1 && $0.text.lowercased() != engine.snapshot.input.lowercased() } : ordered
    }

    /// The candidate the bar had selected; Return and closing the keyboard commit it.
    private var selectedIndex: Int? {
        let order = engine.candidateOrder, highlighted = engine.snapshot.highlighted
        return order.indices.contains(highlighted) ? order[highlighted] : nil
    }

    var body: some View {
        GeometryReader { proxy in
            let side: CGFloat = 50
            let gridWidth = max(1, proxy.size.width - side - 18)
            let columns = ExpandedGridLayout.columns(gridWidth: gridWidth, phone: ui.region.idiom == .phone)
            let columnWidth = gridWidth / CGFloat(columns)
            let font = ui.region.fontSize
            let items = shown
            let selected = selectedIndex
            // The paw mark belongs to Chinese typing; in Japanese mode every candidate is Japanese.
            let japanese = engine.inputMode == .japanese ? Set<String>() : Set(engine.snapshot.japaneseCandidates)
            let paw = CandidateTextMetrics.paw(size: font)
            let rows = ExpandedGridLayout.rows(widths: items.map {
                ExpandedGridLayout.cellWidth(text: CandidateTextMetrics.width($0.text, size: font),
                                             japanese: japanese.contains($0.text), paw: paw)
            }, columnWidth: columnWidth, columns: columns)
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    HStack(spacing: 4) {
                        filter(t("全部", "全部"), phrases: false)
                        filter(t("詞組", "词组"), phrases: true)
                        Spacer(minLength: 0)
                        Text("\(items.count)")
                            .font(.system(size: 12).monospacedDigit())
                            .foregroundStyle(CandidateInk.secondary(dark: dark))
                            .padding(.trailing, 6)
                            .accessibilityLabel(t("候選數量 \(items.count)", "候选数量 \(items.count)"))
                    }
                    .frame(height: 36)
                    ScrollView {
                        LazyVStack(spacing: 2) {
                            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                                HStack(spacing: 0) {
                                    ForEach(row, id: \.index) { cell in
                                        cellView(items[cell.index], first: items[cell.index].index == selected,
                                                 japanese: japanese.contains(items[cell.index].text), font: font)
                                            .frame(width: columnWidth * CGFloat(cell.columns))
                                    }
                                }
                            }
                        }
                    }
                    .scrollIndicators(.visible)
                    .modifier(PlainScrollEdges())
                    .id("\(engine.snapshot.input)|\(phrasesOnly)|\(preferences.simplified)")
                    .overlay {
                        if items.isEmpty {
                            Text(phrasesOnly ? t("沒有詞組", "没有词组") : t("沒有候選", "没有候选"))
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
        .onChange(of: engine.snapshot.input) { _, _ in pool = actions.pool() }
        // 莫莫 can switch the script while the reading stays: the pool is rebuilt.
        .onChange(of: preferences.simplified) { _, _ in pool = actions.pool() }
        .onDisappear { deleter?.stop() }
    }

    private func cellView(_ candidate: IndexedCandidate, first: Bool, japanese: Bool, font: CGFloat) -> some View {
        Button {
            KeyboardFeedback.keyDown()
            actions.choosePool(candidate)
        } label: {
            HStack(spacing: 3) {
                Text(candidate.text)
                    .font(.system(size: font, weight: first ? .medium : .regular))
                    .lineLimit(1)
                    .truncationMode(.middle)
                if japanese {
                    Image(systemName: "pawprint.fill").font(.system(size: (font * 0.45).rounded()))
                        .rotationEffect(.degrees(-20)).opacity(0.6)
                }
            }
            .foregroundStyle(first ? Color.white : CandidateInk.text(dark: dark))
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background {
                if first {
                    CandidatePill(palette: preferences.candidatePalette, dark: dark, highContrast: contrast == .increased,
                                  festival: ui.outfit?.pillTint)
                        .padding(.vertical, 5).padding(.horizontal, 2)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(CandidatePressStyle(tint: accent, dark: dark))
        .accessibilityLabel(candidate.text + (japanese ? t("，日語", "，日语") : ""))
        .accessibilityAddTraits(first ? .isSelected : [])
    }

    private func filter(_ title: String, phrases: Bool) -> some View {
        Button {
            KeyboardFeedback.keyDown()
            phrasesOnly = phrases
        } label: {
            Text(title)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(phrasesOnly == phrases ? CandidateInk.text(dark: dark) : CandidateInk.secondary(dark: dark))
                .padding(.horizontal, 12)
                .frame(minWidth: 48, minHeight: 30)
                .background { if phrasesOnly == phrases { Capsule().fill(accent.opacity(dark ? 0.26 : 0.16)) } }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(phrasesOnly == phrases ? .isSelected : [])
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
