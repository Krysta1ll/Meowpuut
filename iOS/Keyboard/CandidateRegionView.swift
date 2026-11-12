import SwiftUI
import UIKit

/// Layout and appearance the controller computes; the SwiftUI side only reads it.
final class KeyboardUIState: ObservableObject {
    @Published var region = CandidateRegionLayout(width: 393, idiom: .phone)
    @Published var keysAreaHeight: CGFloat = 213
    /// Space kept clear on the left and right (the safe area of a landscape phone).
    @Published var sideInset: CGFloat = 0
    @Published var trailingInset: CGFloat = 0
    @Published var expanded = false
    @Published var dark = false
    @Published var outfit: FestivalOutfit?
    /// Where 米米莫 and 莫莫 sit today (CatSchedule); empty without the cats.
    @Published var cats: [CatPlacement] = []
    /// False while the keyboard is off screen: the cats stop moving.
    @Published var active = true
    /// English: completions and a correction for the word before the caret.
    @Published var suggestions: [String] = []
    /// Bumped when the extension reappears to rebuild the decorative layer.
    @Published var appearanceRevision = 0
    /// The kaomoji in the candidate row while the ^_^ list is open; empty when
    /// it is closed. Set when the list opens, so cells stay put while it is used.
    @Published var kaomojiRow: [String] = []
    var kaomojiOpen: Bool { !kaomojiRow.isEmpty }
}

/// What the candidate area can ask the controller to do.
struct KeyboardActions {
    var chooseVisible: (Int) -> Void = { _ in }
    var choosePool: (IndexedCandidate) -> Void = { _ in }
    var selectPrediction: (Int) -> Void = { _ in }
    var insertSymbol: (String) -> Void = { _ in }
    var insertKaomoji: (String) -> Void = { _ in }
    var toggleExpanded: () -> Void = {}
    var switchScript: () -> Bool = { false }
    var delete: (Bool) -> Void = { _ in }
    var pool: () -> [IndexedCandidate] = { [] }
}

/// Candidates answer the finger at once with a soft capsule.
struct CandidatePressStyle: ButtonStyle {
    var tint: Color
    var dark: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                Capsule().fill(tint.opacity(dark ? 0.26 : 0.18))
                    .padding(.vertical, 6)
                    .opacity(configuration.isPressed ? 1 : 0)
                    .allowsHitTesting(false)
            }
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.95 : 1)
            .animation(.spring(duration: 0.18, bounce: 0.3), value: configuration.isPressed)
    }
}

struct StripItem: Identifiable, Equatable {
    enum Target: Equatable { case visible(Int), pool(IndexedCandidate), prediction(Int), kaomoji(String) }
    var id: String
    var text: String
    var japanese: Bool
    var selected: Bool
    var width: CGFloat
    var target: Target
}

struct CandidateCell: View {
    let item: StripItem
    let fontSize: CGFloat
    let pillHeight: CGFloat
    let dark: Bool
    let palette: CandidatePalette
    var highContrast = false
    var festival: Color? = nil

    private var kaomoji: Bool { Kaomoji.contains(item.text) }

    var body: some View {
        HStack(spacing: 3) {
            Text(item.text)
                .font(.system(size: fontSize, weight: item.selected ? .medium : .regular))
                .lineLimit(1)
                .truncationMode(.middle)
            if item.japanese {
                Image(systemName: "pawprint.fill")
                    .font(.system(size: (fontSize * 0.45).rounded()))
                    .rotationEffect(.degrees(-20))
                    .opacity(item.selected ? 0.9 : 0.6)
            }
        }
        .foregroundStyle(item.selected ? Color.white : CandidateInk.text(dark: dark))
        .padding(.horizontal, CandidateStripLayout.padding)
        // A kaomoji takes the width it is drawn at: its characters come from
        // fallback fonts, and the measured width can be short of that.
        .frame(minWidth: item.width, maxWidth: kaomoji ? nil : item.width, minHeight: pillHeight, maxHeight: pillHeight)
        .fixedSize(horizontal: kaomoji, vertical: false)
        .background {
            if item.selected { CandidatePill(palette: palette, dark: dark, highContrast: highContrast, festival: festival) }
        }
    }
}

extension FestivalOutfit {
    /// The outfit's colour for the selected candidate, if it brings one.
    var pillTint: Color? { pillRGB.map { Color(red: $0.red, green: $0.green, blue: $0.blue) } }
}

struct CandidateRegionView: View {
    @ObservedObject var engine: Engine
    @ObservedObject var ui: KeyboardUIState
    @ObservedObject var preferences = Preferences.shared
    let actions: KeyboardActions

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Touches each cat has had; a change plays its reaction.
    @State private var pets: [KeyboardCat: Int] = [:]
    @State private var pressedCat: KeyboardCat?
    @State private var affection = CatAffection()
    @State private var particles: [CatParticle] = []
    @State private var toast: CatToast?
    @State private var extra: [IndexedCandidate] = []
    @State private var extraLoaded = false

    private var t: InterfaceText { InterfaceText(simplified: preferences.simplified) }
    private var dark: Bool { ui.dark }
    private var accent: Color { preferences.candidatePalette.color(dark: dark) }
    private var accessible: Bool { reduceTransparency || contrast == .increased }
    private var composing: Bool { !engine.snapshot.preedit.isEmpty }
    private var hasCandidates: Bool { !engine.snapshot.candidates.isEmpty || engine.snapshot.compactJapanese != nil }
    private var chineseActive: Bool { !engine.english && engine.inputMode != .japanese }

    var body: some View {
        let layout = ui.region
        let band = CandidateGlassShape.contentBand(preferences.glassShape, height: layout.glass.height)
        let stage = layout.stage(shape: preferences.glassShape)
        ZStack(alignment: .topLeading) {
            LiquidGlassBar(style: preferences.glassShape, dark: dark, accessible: accessible,
                           strength: preferences.glassStrength, tint: accent)
                .equatable()
                .frame(width: layout.glass.width, height: layout.glass.height)
                .position(x: layout.glass.midX, y: layout.glass.midY)
            if layout.cats && !accessible && !dark {
                // The soft light band at the start of the row (CandidateBarPreview draws the same).
                let span = layout.lightBand
                Capsule().fill(Color.white.opacity(0.5))
                    .frame(width: span, height: layout.pillHeight)
                    .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.6),
                                                 .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
                    .position(x: layout.row.minX + span / 2, y: layout.glass.minY + (band.top + band.bottom) / 2)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            barTrim(layout)
            // Under the row and the expand key: where a cat's touch area meets them, the tap is theirs.
            ForEach(ui.cats, id: \.cat) { place in petZone(place.cat, rect: place.touch(on: stage)) }
            rowContent(layout)
                .frame(width: layout.row.width, height: band.bottom - band.top)
                .position(x: layout.row.midX, y: layout.glass.minY + (band.top + band.bottom) / 2)
            if !ui.cats.isEmpty {
                CatStageView(scene: CatLayersView.Scene(places: ui.cats, glass: layout.glass, shape: preferences.glassShape,
                                                        dark: dark, shadows: !accessible),
                             active: ui.active, composing: composing, pressed: pressedCat, pets: pets)
                    .frame(width: layout.width, height: layout.height)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            chevron(layout)
            ForEach(particles) { particle in
                // From the cat's brow upward. A keyboard cannot draw above
                // its own top edge, so the flight (16 pt) ends inside it.
                let box = ui.cats.first { $0.cat == particle.cat }?.visible ?? .zero
                CatParticleView(particle: particle)
                    .position(x: box.midX, y: max(17 + particle.size / 2, box.minY + box.height * 0.3))
            }
            if let toast {
                CatToastView(toast: toast, dark: dark, tint: accent)
                    .frame(maxWidth: layout.row.width)
                    .position(x: layout.row.midX, y: layout.glass.midY)
                    .id(toast.id)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.86).combined(with: .opacity))
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .frame(width: layout.width, height: layout.height, alignment: .topLeading)
        .animation(.spring(duration: 0.3, bounce: 0.3), value: toast)
        .task(id: toast?.id) {
            guard let current = toast else { return }
            try? await Task.sleep(for: .seconds(current.emphasis ? 2.4 : 1.4))
            if !Task.isCancelled, toast?.id == current.id { toast = nil }
        }
        .onChange(of: composing) { was, now in
            // 米米莫 ducks and shakes his head whenever text is committed.
            if was && !now { pets[.mimimo, default: 0] &+= 1 }
        }
        // The rest of the pool belongs to one reading in one script. 莫莫
        // can switch the script while the reading stays.
        .onChange(of: engine.snapshot.input) { _, _ in resetExtra() }
        .onChange(of: preferences.simplified) { _, _ in resetExtra() }
    }

    private func resetExtra() {
        extra = []
        extraLoaded = false
    }

    // MARK: Row

    /// One unseen cell that keeps the strip's views built while it is idle.
    private static let placeholder = StripItem(id: "s0", text: "好", japanese: true, selected: true, width: 64,
                                               target: .visible(-1))

    private func rowContent(_ layout: CandidateRegionLayout) -> some View {
        let candidates = hasCandidates && !ui.expanded
        // The ^_^ list takes the row while nothing is being typed.
        let kaomoji = !candidates && !ui.expanded && !composing && ui.kaomojiOpen
        let words = engine.english ? ui.suggestions : engine.predictions
        let predictions = !candidates && !kaomoji && !ui.expanded && !composing && !words.isEmpty
        let showsStrip = candidates || kaomoji || predictions
        let list = candidates ? items(layout) : kaomoji ? kaomojiItems(layout)
            : predictions ? predictionItems(layout, words) : [Self.placeholder]
        return ZStack {
            // The strip stays in place, hidden, while there is nothing to
            // show: the first candidates then fill it in instead of building it.
            strip(layout, list: list)
                .opacity(showsStrip ? 1 : 0)
                .allowsHitTesting(showsStrip)
                .accessibilityHidden(!showsStrip)
            if ui.expanded {
                let title = composing ? engine.snapshot.preedit : t("顏文字", "颜文字")
                HStack {
                    Text(title)
                        .font(.system(size: layout.fontSize - 2, weight: .medium))
                        .foregroundStyle(CandidateInk.secondary(dark: dark))
                        .lineLimit(1).truncationMode(.head)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 12)
                .accessibilityLabel(composing ? t("輸入中：", "输入中：") + title : title)
            } else if !showsStrip {
                idle(layout)
            }
        }
    }

    /// Words that may follow what was just typed, or in English the words
    /// the one being typed may become, in the same strip.
    private func predictionItems(_ layout: CandidateRegionLayout, _ words: [String]) -> [StripItem] {
        let size = layout.fontSize
        return words.enumerated().map { index, word in
            let width = CandidateStripLayout.cellWidth(text: CandidateTextMetrics.width(word, size: size), japanese: false,
                                                       paw: 0, maximum: layout.maximumCandidateWidth)
            return StripItem(id: "s\(index)", text: word, japanese: false, selected: false, width: max(44, width),
                             target: .prediction(index))
        }
    }

    /// The ^_^ list: recent kaomoji, then the first of every group.
    private func kaomojiItems(_ layout: CandidateRegionLayout) -> [StripItem] {
        ui.kaomojiRow.enumerated().map { index, text in
            let width = CandidateStripLayout.cellWidth(text: CandidateTextMetrics.width(text, size: layout.fontSize), japanese: false,
                                                       paw: 0, maximum: layout.maximumCandidateWidth)
            return StripItem(id: "s\(index)", text: text, japanese: false, selected: false, width: max(44, width),
                             target: .kaomoji(text))
        }
    }

    private func items(_ layout: CandidateRegionLayout) -> [StripItem] {
        let snapshot = engine.snapshot
        let size = layout.fontSize
        let paw = CandidateTextMetrics.paw(size: size)
        // The paw mark and the one-Japanese-candidate rule belong to Chinese
        // typing. In Japanese mode every candidate is Japanese: show them all.
        let japaneseTexts = engine.inputMode == .japanese ? [] : Set(snapshot.japaneseCandidates)
        var texts = snapshot.candidates
        var japanese = Set(texts.indices.filter { japaneseTexts.contains(texts[$0]) })
        if let compact = snapshot.compactJapanese {
            texts.append(compact.text)
            japanese.insert(texts.count - 1)
        }
        let pinned = snapshot.candidates.first.flatMap { $0 == snapshot.preferredCompleteMixed ? 0 : nil }
        // Only a Japanese word is moved up into view. The bare kana spelling
        // of the reading (source -1: にはお for nihao) keeps its ranked place
        // at the end of the row.
        let firstJapanese = texts.indices.first { japanese.contains($0) }
        let source = firstJapanese.flatMap { index in
            snapshot.candidateDetails.indices.contains(index) ? snapshot.candidateDetails[index].source : snapshot.compactJapanese?.source
        }
        let cells = CandidateStripLayout.cells(texts: texts, japanese: japanese, pinnedFirst: pinned,
                                               promoteJapanese: source != -1,
                                               available: layout.row.width, maximum: layout.maximumCandidateWidth,
                                               paw: paw) { CandidateTextMetrics.width($0, size: size) }
        // Cells are identified by their place in the row, so a keystroke
        // changes their text instead of replacing every cell.
        var result = cells.enumerated().map { slot, cell in
            StripItem(id: "s\(slot)", text: cell.text, japanese: cell.japanese,
                      selected: cell.index == snapshot.highlighted, width: cell.width, target: .visible(cell.index))
        }
        // Past the first page, like the system keyboard: the rest of the
        // ranked pool, without further Japanese readings.
        var seen = Set(texts)
        for candidate in extra where !seen.contains(candidate.text) && !japaneseTexts.contains(candidate.text) {
            seen.insert(candidate.text)
            let width = CandidateStripLayout.cellWidth(text: CandidateTextMetrics.width(candidate.text, size: size),
                                                       japanese: false, paw: paw, maximum: layout.maximumCandidateWidth)
            result.append(StripItem(id: "s\(result.count)", text: candidate.text, japanese: false,
                                    selected: false, width: width, target: .pool(candidate)))
        }
        return result
    }

    private func strip(_ layout: CandidateRegionLayout, list: [StripItem]) -> some View {
        let input = engine.snapshot.input
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: CandidateStripLayout.spacing) {
                    ForEach(list) { item in
                        Button {
                            KeyboardFeedback.keyDown()
                            switch item.target {
                            case .visible(let index): actions.chooseVisible(index)
                            case .pool(let candidate): actions.choosePool(candidate)
                            case .prediction(let index): actions.selectPrediction(index)
                            case .kaomoji(let text): actions.insertKaomoji(text)
                            }
                        } label: {
                            CandidateCell(item: item, fontSize: layout.fontSize, pillHeight: layout.pillHeight,
                                          dark: dark, palette: preferences.candidatePalette,
                                          highContrast: contrast == .increased, festival: ui.outfit?.pillTint)
                                .frame(maxHeight: .infinity)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(CandidatePressStyle(tint: accent, dark: dark))
                        .accessibilityLabel(label(for: item))
                        .accessibilityAddTraits(item.selected ? .isSelected : [])
                        .id(item.id)
                    }
                    if !extraLoaded {
                        // A new marker for each reading, so it reports again
                        // when it is on screen for the next one.
                        Color.clear.frame(width: 1, height: 1)
                            .id("more|\(preferences.simplified)|" + input)
                            .onAppear { loadMore() }
                    }
                }
                .padding(.horizontal, 2)
            }
            .modifier(PlainScrollEdges())
            .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.94),
                                         .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
            // A new reading, a new script or new predictions start from the left.
            .onChange(of: input) { _, _ in proxy.scrollTo("s0", anchor: .leading) }
            .onChange(of: preferences.simplified) { _, _ in proxy.scrollTo("s0", anchor: .leading) }
            .onChange(of: engine.predictions) { _, _ in proxy.scrollTo("s0", anchor: .leading) }
            .onChange(of: ui.suggestions) { _, _ in proxy.scrollTo("s0", anchor: .leading) }
            .onChange(of: ui.kaomojiRow) { _, _ in proxy.scrollTo("s0", anchor: .leading) }
        }
    }

    private func label(for item: StripItem) -> String {
        // A kaomoji is spoken as its group; its characters read as noise.
        if let kaomoji = Kaomoji.spokenName(item.text, simplified: preferences.simplified) { return kaomoji }
        if case .prediction = item.target { return (engine.english ? t("建議：", "建议：") : t("聯想詞：", "联想词：")) + item.text }
        return item.text + (item.japanese ? t("，日語", "，日语") : "")
    }

    private func loadMore() {
        guard !extraLoaded, composing else { return }
        extraLoaded = true
        let shown = Set(engine.candidateOrder + (engine.snapshot.compactJapanese.map { [$0.index] } ?? []))
        extra = actions.pool().filter { !shown.contains($0.index) }
    }

    private var idleStatus: String {
        if engine.english { return "English" }
        switch engine.inputMode {
        case .japanese: return "日本語"
        case .pinyin: return preferences.simplified ? "拼音 · 简体" : "拼音 · 繁體"
        case .zhuyin: return preferences.simplified ? "注音 · 简体" : "注音 · 繁體"
        }
    }

    /// The status where the row is short (a 320 pt keyboard).
    private var shortIdleStatus: String {
        guard chineseActive else { return idleStatus }
        return (engine.inputMode == .pinyin ? "拼 · " : "注 · ") + (preferences.simplified ? "简" : "繁")
    }

    private func markName(_ mark: String) -> String {
        switch mark {
        case "，": return t("逗號", "逗号")
        case "。": return t("句號", "句号")
        case "？": return t("問號", "问号")
        case "！": return t("驚嘆號", "感叹号")
        case "、": return t("頓號", "顿号")
        default: return mark
        }
    }

    /// Nothing is being typed and nothing is suggested: quick punctuation
    /// for Chinese, and what the keyboard is set to.
    private func idle(_ layout: CandidateRegionLayout) -> some View {
        let marks = chineseActive ? ["，", "。", "？", "！", "、"] : []
        let size = layout.statusFontSize
        // Text, its 0.5 pt tracking and the 6 pt after it.
        func width(_ text: String) -> CGFloat { CandidateTextMetrics.width(text, size: size) + 0.5 * CGFloat(text.count) + 8 }
        let fit = IdleRowLayout.fit(rowWidth: layout.row.width, marks: marks.count, markWidth: max(30, layout.fontSize * 1.55),
                                    fullStatus: width(idleStatus), shortStatus: width(shortIdleStatus))
        let status = fit.status == .full ? idleStatus : shortIdleStatus
        return HStack(spacing: 0) {
            if chineseActive {
                ForEach(marks, id: \.self) { mark in
                    Button {
                        KeyboardFeedback.keyDown()
                        actions.insertSymbol(mark)
                    } label: {
                        Text(mark)
                            .font(.system(size: layout.fontSize - 1, weight: .medium))
                            .foregroundStyle(CandidateInk.text(dark: dark).opacity(0.8))
                            .frame(width: fit.markWidth)
                            .frame(maxHeight: .infinity)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(CandidatePressStyle(tint: accent, dark: dark))
                    // The mark's name alone: VoiceOver reads a bare mark as a pause.
                    .accessibilityLabel(markName(mark))
                }
            }
            Spacer(minLength: 0)
            if fit.status != .hidden {
                Text(status)
                    .font(.system(size: size, weight: .medium)).tracking(0.5)
                    .lineLimit(1)
                    .fixedSize()
                    .foregroundStyle(CandidateInk.secondary(dark: dark))
                    .contentTransition(.numericText())
                    .animation(reduceMotion ? nil : .snappy, value: status)
                    .padding(.trailing, 6)
                    .allowsHitTesting(false)
                    .accessibilityLabel(t("目前輸入：", "当前输入：") + idleStatus)
            }
        }
    }

    private func chevron(_ layout: CandidateRegionLayout) -> some View {
        Button {
            KeyboardFeedback.keyDown()
            actions.toggleExpanded()
        } label: {
            Image(systemName: "chevron.down")
                .font(.system(size: 15, weight: .semibold))
                .rotationEffect(.degrees(ui.expanded ? 180 : 0))
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: ui.expanded)
                .foregroundStyle(CandidateInk.text(dark: dark).opacity(hasCandidates || ui.kaomojiOpen || ui.expanded ? 0.85 : 0.25))
                .frame(width: layout.chevron.width, height: layout.chevron.height)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!hasCandidates && !ui.kaomojiOpen && !ui.expanded)
        .position(x: layout.chevron.midX, y: layout.chevron.midY)
        .accessibilityLabel(ui.kaomojiOpen && !composing ? (ui.expanded ? t("收合顏文字", "收起颜文字") : t("展開顏文字", "展开颜文字"))
                            : (ui.expanded ? t("收合候選詞", "收起候选词") : t("展開候選詞", "展开候选词")))
    }

    // MARK: Cats

    /// A decoration laid round the glass's ends (a festival's frame trim)
    /// goes here: over the glass, under the candidates and the cats. It must
    /// not take touches. Nothing is drawn for now.
    @ViewBuilder private func barTrim(_ layout: CandidateRegionLayout) -> some View {
        if let theme = ui.outfit?.theme {
            // Faded only under the arrow itself, not its whole touch area.
            let glyph = CandidateRegionLayout.chevronGlyph
            FestivalTrimView(trim: theme.trim, glass: layout.glass, shape: preferences.glassShape,
                             clear: [CGRect(x: layout.chevron.midX - glyph.width / 2 - 2, y: layout.chevron.midY - glyph.height / 2 - 2,
                                            width: glyph.width + 4, height: glyph.height + 4)])
        }
    }

    private func petZone(_ cat: KeyboardCat, rect: CGRect) -> some View {
        Button { pet(cat) } label: {
            Color.clear.frame(width: max(1, rect.width), height: max(1, rect.height)).contentShape(Rectangle())
        }
        .buttonStyle(CatPetButtonStyle { pressed in
            if pressed { pressedCat = cat } else if pressedCat == cat { pressedCat = nil }
        })
        .position(x: rect.midX, y: rect.midY)
        .accessibilityLabel(cat == .mimimo ? "摸摸米米莫" : "摸摸莫莫")
        .accessibilityHint(cat == .momo ? (preferences.simplified ? "连续轻点五下，切换为繁体" : "連續輕點五下，切換為簡體") : "")
    }

    private func pet(_ cat: KeyboardCat) {
        UIDevice.current.playInputClick()
        switch cat {
        case .mimimo:
            pets[.mimimo, default: 0] &+= 1
            let symbol = ["pawprint.fill", "music.note", "questionmark", "exclamationmark", "sparkle"].randomElement() ?? "pawprint.fill"
            emit(CatParticle(symbol: symbol, cat: .mimimo, size: 10, drift: CGFloat.random(in: 4...10),
                             tint: Color(red: 0.86, green: 0.66, blue: 0.24)))
        case .momo:
            pets[.momo, default: 0] &+= 1
            switch affection.pet() {
            case .pleased(let count):
                emit(CatParticle(symbol: "heart.fill", cat: .momo, size: 8 + CGFloat(count) * 1.5,
                                 drift: -CGFloat.random(in: 4...10), tint: Color(red: 0.95, green: 0.45, blue: 0.55)))
                if count >= 2 {
                    let left = CatAffection.petsToSwitch - count
                    // The row of five hearts needs a row wide enough for it and the text.
                    toast = CatToast(symbol: "heart.fill",
                                     text: preferences.simplified ? "再摸 \(left) 下，切换为繁体" : "再摸 \(left) 下，切換為簡體",
                                     hearts: ui.region.row.width >= 230 ? count : nil)
                }
            case .happy:
                celebrate()
            case .resting:
                emit(CatParticle(symbol: "heart.fill", cat: .momo, size: 9, drift: -CGFloat.random(in: 4...10),
                                 tint: Color(red: 0.95, green: 0.45, blue: 0.55)))
            }
        }
    }

    private func celebrate() {
        let nowSimplified = actions.switchScript()
        // Short enough for one line on a 320 pt keyboard. In English or
        // Japanese the switch applies to Chinese, and the wording says so.
        let text: String
        if chineseActive { text = nowSimplified ? "已切换为简体中文" : "已切換為繁體中文" }
        else { text = nowSimplified ? "中文已切换为简体" : "中文已切換為繁體" }
        toast = CatToast(symbol: "checkmark.circle.fill", text: text, emphasis: true)
        UIAccessibility.post(notification: .announcement, argument: text)
        for index in 0..<3 {
            emit(CatParticle(symbol: index == 1 ? "sparkles" : "heart.fill", cat: .momo, size: 10 + CGFloat(index) * 2,
                             drift: -CGFloat(4 + index * 5), tint: Color(red: 0.95, green: 0.45, blue: 0.55)))
        }
        // 米米莫 notices too.
        pets[.mimimo, default: 0] &+= 1
    }

    private func emit(_ particle: CatParticle) {
        particles.append(particle)
        if particles.count > 8 { particles.removeFirst(particles.count - 8) }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))
            particles.removeAll { $0.id == particle.id }
        }
    }
}
