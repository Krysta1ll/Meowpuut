import AppKit
import SwiftUI

// The macOS candidate window (docs/DESIGN.md §4–5, desktop): its sizes, which
// candidates the collapsed row shows, where the window goes, and the panel
// that shows GlassCandidateView without taking the keyboard from the app.

// MARK: Sizes

/// Desktop sizes in points. The cat fractions are the anchors in
/// assets/brand/cats/cats.json.
enum Desk {
    static let glass: CGFloat = 38
    static let font: CGFloat = 15
    static let hintFont: CGFloat = 10
    static let pill: CGFloat = 27
    static let cellPadding: CGFloat = 9
    static let cellSpacing: CGFloat = 2
    static let hintGap: CGFloat = 4
    static let pawGap: CGFloat = 3
    static let pawWidth: CGFloat = 9
    static let chevronWidth: CGFloat = 22
    /// How far the expand key reaches under 莫莫's cheek.
    static let chevronTuck: CGFloat = 15
    /// Width of the number in the expanded panel: a digit, or the shift mark and a digit.
    static func gridHint(shifted: Bool) -> CGFloat { shifted ? 18 : 9 }

    static let mimimoHeight: CGFloat = 56
    static let mimimoWidth = (mimimoHeight * 0.9135).rounded()
    static let momoHeight: CGFloat = 48
    static let momoWidth = (momoHeight * 1.0543).rounded()
    static let tailHeight: CGFloat = 21
    static let tailWidth = (tailHeight * 4.02).rounded()
    /// The tail starts this far right of 米米莫's left edge, with its root inside
    /// his body, and hangs this far below the glass.
    static let tailLeft: CGFloat = 7
    static let tailDrop: CGFloat = 1

    /// The glass's upper edge below the top of 米米莫: his eyes clear the rim by a point.
    static let glassTop = (mimimoHeight * 0.538).rounded(.up) + 1
    /// The glass starts this far right of 米米莫's left edge.
    static let glassLeft = (mimimoWidth * 0.24).rounded()
    /// 莫莫's eye line sits a point above the glass; she overhangs its right end.
    static let momoTop = glassTop - 1 - (momoHeight * 0.50).rounded()
    static let momoOverhang: CGFloat = 2
    /// The first candidate starts this far inside the glass.
    static let rowLead: CGFloat = 12
    /// Room under the glass for the tail.
    static let glassBottom: CGFloat = 2
    /// Height of the bar with the cats: their heads, the glass and the room under it.
    static let catsBarHeight = glassTop + glass + glassBottom

    /// Clear space around the visible window; the shadows fall into it.
    static let margin = NSEdgeInsets(top: 2, left: 8, bottom: 14, right: 8)
    /// From the window's left edge to the first candidate.
    static func lead(cats: Bool) -> CGFloat {
        margin.left + (cats ? glassLeft + rowLead : 6)
    }

    static let gridRowHeight: CGFloat = 30
    static let gridRowSpacing: CGFloat = 3
    static let gridMaxWidth: CGFloat = 560
    static let panelCorner: CGFloat = 18
}

/// Text widths at the candidate size, measured once per string (DESIGN.md §4).
enum CandidateMeasure {
    private struct Key: Hashable {
        var text: String
        var size: CGFloat
    }
    private static var cache: [Key: CGFloat] = [:]

    static func width(_ text: String, size: CGFloat = Desk.font) -> CGFloat {
        let key = Key(text: text, size: size)
        if let hit = cache[key] { return hit }
        if cache.count > 2048 { cache.removeAll(keepingCapacity: true) }
        // Medium is the widest weight a candidate is drawn in (the selected one).
        let font = NSFont.systemFont(ofSize: size, weight: .medium)
        let width = ceil((text as NSString).size(withAttributes: [.font: font]).width)
        cache[key] = width
        return width
    }
}

// MARK: Collapsed row

/// The candidates that carry the paw: Japanese ones among Chinese ones. When
/// every candidate is Japanese, as in Japanese mode, none is marked and the
/// row takes them like any other candidate.
func markedJapanese(_ snapshot: Snapshot) -> Set<String> {
    guard let kept = snapshot.compactJapanese else {
        let japanese = Set(snapshot.japaneseCandidates)
        return snapshot.candidates.allSatisfy(japanese.contains) ? [] : japanese
    }
    return Set(snapshot.japaneseCandidates + [kept.text])
}

/// The candidates the collapsed row shows. The view draws exactly these and
/// the number keys choose from exactly these, in this order.
struct CollapsedCandidateLayout: Equatable {
    /// Positions in engine order. `snapshot.candidates.count` stands for the
    /// Japanese candidate that is not on this page.
    let indices: [Int]
    /// Text width given to each shown candidate.
    let textWidths: [Int: CGFloat]
    /// A shown candidate is shortened; the expanded panel has the whole text.
    let truncated: Bool

    /// Number, gaps and padding around one candidate when budgeting the row.
    static let chrome: CGFloat = 32
    /// The paw mark after a Japanese candidate, with its gap.
    static let paw: CGFloat = Desk.pawGap + Desk.pawWidth
    /// A long candidate counts as at most this share of the row, so the next one still gets in.
    static let longShare: CGFloat = 0.70
    /// The row grows to this width before a long candidate is shortened.
    static let rowLimit: CGFloat = 440

    private struct Key: Equatable {
        var candidates: [String]
        var japanese: [String]
        var compact: String?
        var confident: [String]
        var required: String?
        var page: Int
        var budget: CGFloat?
    }
    private static var memo: (key: Key, layout: CollapsedCandidateLayout)?

    init(_ snapshot: Snapshot, budget: CGFloat? = nil) {
        // The view and key handling ask several times per keystroke.
        let key = Key(candidates: snapshot.candidates, japanese: snapshot.japaneseCandidates,
                      compact: snapshot.compactJapanese?.text, confident: snapshot.confidentCandidates,
                      required: snapshot.requiredCandidate, page: snapshot.page, budget: budget)
        if let memo = Self.memo, memo.key == key { self = memo.layout; return }
        self.init(measuring: snapshot, budget: budget)
        Self.memo = (key, self)
    }

    private init(measuring snapshot: Snapshot, budget: CGFloat?) {
        let candidates = snapshot.candidates + (snapshot.compactJapanese.map { [$0.text] } ?? [])
        let japanese = markedJapanese(snapshot)
        let widths = candidates.map { CandidateMeasure.width($0) + (japanese.contains($0) ? Self.paw : 0) }
        guard !widths.isEmpty else { indices = []; textWidths = [:]; truncated = false; return }
        let confidence = Set(snapshot.confidentCandidates)
        let trusted = snapshot.candidates.prefix(7).filter { confidence.contains($0) }.count
        let reservedJapanese = candidates.firstIndex { japanese.contains($0) }
            ?? snapshot.requiredCandidate.flatMap { candidates.firstIndex(of: $0) }
        // How many short candidates the row takes: more when the engine is sure of them.
        let base = max(110, budget ?? (trusted >= 5 ? 330 : trusted >= 3 ? 290 : 240))
        let cap = max(40, base * Self.longShare - Self.chrome)
        func total(_ ids: [Int]) -> CGFloat {
            ids.reduce(0) { $0 + min(widths[$1], cap) + Self.chrome }
        }
        var selected: [Int] = []
        for index in candidates.indices where !japanese.contains(candidates[index]) {
            if !selected.isEmpty && (selected.count >= (trusted >= 5 ? 6 : 5) || total(selected + [index]) > base) { break }
            selected.append(index)
        }
        // One Japanese candidate keeps a place and does not push Chinese out.
        var available = base
        if let index = reservedJapanese, snapshot.page == 0 || !japanese.isEmpty, !selected.contains(index) {
            selected.append(index)
            if budget == nil { available += min(130, widths[index] + Self.chrome) }
        }
        if selected.isEmpty { selected = [0] }
        selected.sort()
        // Long candidates may widen the row up to its limit; past that they share what is left, shortest first.
        if budget == nil { available = max(available, Self.rowLimit) }
        var remaining = max(0, available - CGFloat(selected.count) * Self.chrome)
        var allocation: [Int: CGFloat] = [:]
        let shortestFirst = selected.sorted { widths[$0] < widths[$1] }
        for (offset, index) in shortestFirst.enumerated() {
            let share = floor(remaining / CGFloat(shortestFirst.count - offset))
            let width = min(widths[index], share)
            allocation[index] = width
            remaining -= width
        }
        indices = selected
        textWidths = allocation
        truncated = selected.contains { (allocation[$0] ?? 0) < widths[$0] }
    }
}

func compactCandidateText(_ snapshot: Snapshot, at index: Int) -> String? {
    if snapshot.candidates.indices.contains(index) { return snapshot.candidates[index] }
    if index == snapshot.candidates.count { return snapshot.compactJapanese?.text }
    return nil
}

func collapsedCandidateCount(_ candidates: [String]) -> Int {
    CollapsedCandidateLayout(Snapshot(candidates: candidates)).indices.count
}

func collapsedCandidateIndices(_ snapshot: Snapshot) -> [Int] {
    CollapsedCandidateLayout(snapshot).indices
}

/// The row hides something: more candidates, more pages or a shortened text.
func candidatesCanExpand(_ snapshot: Snapshot) -> Bool {
    guard !snapshot.candidates.isEmpty else { return false }
    let layout = CollapsedCandidateLayout(snapshot)
    return layout.truncated || layout.indices.filter { $0 < snapshot.candidates.count }.count < snapshot.candidates.count ||
        !snapshot.lastPage || snapshot.page > 0
}

enum CollapsedShortcut: Equatable {
    case pass, choose(Int), expand
}

/// The position a number key stands for, from 0: 1–8 as typed, or with Shift
/// in the modes that choose that way.
private func shortcutOrdinal(key: Int32, modifiers: Int32, shifted: Bool) -> Int? {
    if shifted, modifiers == 1, let scalar = UnicodeScalar(UInt32(bitPattern: key)) {
        return Array("!@#$%^&*").firstIndex(of: Character(scalar))
    }
    if !shifted, modifiers == 0, (49...56).contains(key) { return Int(key - 49) }
    return nil
}

/// What a number key does on the collapsed row: the n-th shown candidate.
/// A number past the row opens the panel instead of choosing something unseen.
func collapsedCandidateShortcut(_ snapshot: Snapshot, key: Int32, modifiers: Int32,
                                shifted: Bool, english: Bool) -> CollapsedShortcut {
    guard !english, !snapshot.candidates.isEmpty,
          let ordinal = shortcutOrdinal(key: key, modifiers: modifiers, shifted: shifted) else { return .pass }
    let visible = collapsedCandidateIndices(snapshot)
    if visible.indices.contains(ordinal) { return .choose(visible[ordinal]) }
    return snapshot.candidates.indices.contains(ordinal) ? .expand : .pass
}

/// Where the highlight goes on the collapsed row: Tab, Right and Down step
/// forward, Shift+Tab, Left and Up step back, and both ends wrap.
func collapsedCandidateMove(_ snapshot: Snapshot, key: Int32, modifiers: Int32) -> Int? {
    let sideways = key == 0xff51 || key == 0xff53
    guard !snapshot.candidates.isEmpty,
          modifiers == 0 || (modifiers == 1 && (key == 0xff09 || sideways)),
          sideways || [Int32(0xff52), 0xff54, 0xff09].contains(key) else { return nil }
    let visible = collapsedCandidateIndices(snapshot)
    guard !visible.isEmpty else { return nil }
    let current = visible.firstIndex(of: snapshot.highlighted) ?? 0
    let previous = key == 0xff52 || key == 0xff51 || (key == 0xff09 && modifiers == 1)
    return visible[(current + (previous ? visible.count - 1 : 1)) % visible.count]
}

// MARK: Expanded panel

/// Number, gaps and padding around one candidate in the expanded panel.
private let gridChrome: CGFloat = 40

/// The expanded panel's candidates in order: the page, then on the first page
/// the Japanese candidate the row kept from a later page. A marked candidate
/// gets a full-width space, which is the room its paw takes.
func expandedCandidates(_ snapshot: Snapshot) -> [String] {
    let marked = markedJapanese(snapshot)
    return (snapshot.candidates + (snapshot.compactJapanese.map { [$0.text] } ?? [])).map { $0 + (marked.contains($0) ? "　" : "") }
}

/// The cell a number key chooses in the expanded panel, the kept Japanese candidate included.
func expandedCandidateShortcut(_ snapshot: Snapshot, key: Int32, modifiers: Int32, shifted: Bool) -> Int? {
    guard let ordinal = shortcutOrdinal(key: key, modifiers: modifiers, shifted: shifted),
          compactCandidateText(snapshot, at: ordinal) != nil else { return nil }
    return ordinal
}

/// Japanese mode: Space, or a mark typed after the reading, confirms the
/// highlighted candidate. The backend would take the first one; it never
/// sees the highlight.
func confirmJapaneseCandidate(_ engine: Engine, key: Int32, modifiers: Int32) -> Bool {
    let snapshot = engine.snapshot
    guard engine.inputMode == .japanese, !engine.english, engine.moduleBuffer == nil,
          snapshot.candidates.indices.contains(snapshot.highlighted),
          (32...126).contains(key), let scalar = UnicodeScalar(UInt32(key)) else { return false }
    let character = Character(scalar)
    if key == 32 {
        // Shift+Space switches to ABC.
        guard modifiers == 0 else { return false }
    } else if character.isLetter || "'-".contains(character) || (modifiers == 1 && "!@#$%^&*(".contains(character)) {
        // Letters go on with the reading; Shift and a number chooses.
        return false
    }
    engine.choose(snapshot.highlighted)
    guard !engine.snapshot.commit.isEmpty else { return true }
    if key != 32 { engine.snapshot.commit += character == "," ? "、" : character == "." ? "。" : String(character) }
    return true
}

private func gridWidths(_ candidates: [String]) -> [CGFloat] {
    candidates.prefix(8).map { CandidateMeasure.width($0) + gridChrome }
}

/// Width of the expanded grid: three columns of short words, two of medium
/// ones, or one line per long candidate. Every candidate stays on one line.
func expandedCandidateWidth(_ candidates: [String], limit: CGFloat = Desk.gridMaxWidth) -> CGFloat {
    let widths = gridWidths(candidates)
    let longest = widths.max() ?? 60
    let columns = CGFloat(min(3, widths.count))
    if longest <= 85 { return max(170, min(263, longest * columns + max(0, columns - 1) * 4)) }
    if longest <= 120 { return max(180, longest * 2 + 4) }
    return min(max(180, limit), max(180, longest))
}

func expandedCandidateRows(_ candidates: [String], limit: CGFloat = Desk.gridMaxWidth) -> [[Int]] {
    let widths = gridWidths(candidates)
    let width = expandedCandidateWidth(candidates, limit: limit)
    var rows: [[Int]] = []
    var row: [Int] = []
    for index in widths.indices {
        let proposed = row + [index]
        let cell = (width - CGFloat(proposed.count - 1) * 4) / CGFloat(proposed.count)
        if !row.isEmpty && (proposed.count > 3 || proposed.contains(where: { widths[$0] > cell })) {
            rows.append(row); row = []
        }
        row.append(index)
    }
    if !row.isEmpty { rows.append(row) }
    return rows
}

func expandedCandidateHeight(_ candidates: [String]) -> CGFloat {
    let rows = CGFloat(expandedCandidateRows(candidates).count)
    return rows * Desk.gridRowHeight + max(0, rows - 1) * Desk.gridRowSpacing
}

func expandedCandidateMove(_ candidates: [String], from index: Int, key: Int32) -> Int {
    guard !candidates.isEmpty else { return 0 }
    let rows = expandedCandidateRows(candidates)
    guard let row = rows.firstIndex(where: { $0.contains(index) }), let column = rows[row].firstIndex(of: index) else { return 0 }
    if key == 0xff51 || key == 0xff53 { return min(candidates.count - 1, max(0, index + (key == 0xff51 ? -1 : 1))) }
    let nextRow = min(rows.count - 1, max(0, row + (key == 0xff52 ? -1 : 1)))
    return rows[nextRow][min(column, rows[nextRow].count - 1)]
}

// MARK: Placement

private let candidateAnchorGap: CGFloat = 6

/// The display the caret is on: the one that contains its centre, otherwise
/// the nearest one. Rects are in global screen coordinates.
func candidateDisplay(for anchor: NSRect, screens: [NSRect]) -> Int? {
    let point = NSPoint(x: anchor.midX, y: anchor.midY)
    if let index = screens.firstIndex(where: { $0.contains(point) }) { return index }
    func distance(_ rect: NSRect) -> CGFloat {
        let dx = max(rect.minX - point.x, 0, point.x - rect.maxX)
        let dy = max(rect.minY - point.y, 0, point.y - rect.maxY)
        return dx * dx + dy * dy
    }
    return screens.indices.min { distance(screens[$0]) < distance(screens[$1]) }
}

/// Room for the window under and over the caret line.
func candidateRoom(anchor: NSRect, bounds: NSRect) -> (below: CGFloat, above: CGFloat) {
    (max(0, anchor.minY - bounds.minY - candidateAnchorGap), max(0, bounds.maxY - anchor.maxY - candidateAnchorGap))
}

func candidatePanelAvailableHeight(anchor: NSRect, bounds: NSRect) -> CGFloat {
    let room = candidateRoom(anchor: anchor, bounds: bounds)
    return max(room.below, room.above)
}

/// Under the caret when the window fits there, otherwise on the side with
/// more room. `keeping` holds the side already in use while it still fits.
func candidateGoesBelow(height: CGFloat, anchor: NSRect, bounds: NSRect, keeping below: Bool? = nil) -> Bool {
    let room = candidateRoom(anchor: anchor, bounds: bounds)
    if let below, (below ? room.below : room.above) >= height { return below }
    return room.below >= height || (room.above < height && room.below >= room.above)
}

/// The window frame. `margin` is the clear space inside `size` around what is
/// visible; `lead` is the distance from the window's left edge to the first
/// candidate, which lines up with `x` (the caret when nil).
func candidateFrame(size: NSSize, anchor: NSRect, bounds: NSRect, margin: NSEdgeInsets = NSEdgeInsets(),
                    lead: CGFloat = 0, x: CGFloat? = nil, keeping below: Bool? = nil) -> NSRect {
    let visibleHeight = size.height - margin.top - margin.bottom
    let useBelow = candidateGoesBelow(height: visibleHeight, anchor: anchor, bounds: bounds, keeping: below)
    let y = useBelow ? anchor.minY - candidateAnchorGap + margin.top - size.height
                     : anchor.maxY + candidateAnchorGap - margin.bottom
    let left = (x ?? anchor.minX) - lead
    // The visible part stays on the display; the clear margin may hang over.
    let minX = bounds.minX - margin.left, maxX = bounds.maxX - size.width + margin.right
    let minY = bounds.minY - margin.bottom, maxY = bounds.maxY - size.height + margin.top
    return NSRect(x: max(minX, min(left, maxX)), y: max(minY, min(y, maxY)), width: size.width, height: size.height)
}

/// Two caret rectangles on one line of text.
func candidateSameLine(_ a: NSRect, _ b: NSRect) -> Bool {
    abs(a.midY - b.midY) < max(a.height, b.height) / 2
}

/// Where the row starts: at the start of the composition while the caret is
/// on that line, so the candidates hold still as the text grows. When the
/// caret runs past the window's right end the window follows it.
func candidateAnchorX(start: NSRect?, caret: NSRect, length: Int, rowWidth: CGFloat) -> CGFloat {
    guard let start, candidateSameLine(start, caret),
          start.minX <= caret.minX + 1,
          caret.minX - start.minX <= CGFloat(max(1, length)) * 40 + 60 else { return caret.minX }
    return max(start.minX, caret.minX - max(60, rowWidth - 24))
}

// MARK: Panel

/// Borderless and never key or main: the text field keeps the keyboard.
final class CandidatePanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

/// A click chooses on the first press although the input method is never the active app.
final class CandidateHostingView: NSHostingView<GlassCandidateView> {
    required init(rootView: GlassCandidateView) { super.init(rootView: rootView) }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError("not used") }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

struct CandidateScreen {
    var frame: NSRect
    var visibleFrame: NSRect

    static var current: [CandidateScreen] {
        NSScreen.screens.map { CandidateScreen(frame: $0.frame, visibleFrame: $0.visibleFrame) }
    }
}

/// Owns the candidate panel: builds it once, fits it to the candidates and
/// puts it by the caret.
final class CandidatePanelController {
    let engine: Engine
    private(set) var panel: CandidatePanel?
    private(set) var hosting: CandidateHostingView?
    private var below: Bool?
    private var placedLine: NSRect?
    private var spoken = ""
    private var spokenPage = 0
    private var spokenExpanded = false
    var onChoose: (Int) -> Void = { _ in }
    var onPage: (Int32) -> Void = { _ in }
    var onToggle: () -> Void = {}
    var onAppearanceChange: () -> Void = {}

    init(engine: Engine) { self.engine = engine }

    var isVisible: Bool { panel?.isVisible ?? false }

    private func build() -> (CandidatePanel, CandidateHostingView) {
        if let panel, let hosting { return (panel, hosting) }
        let view = GlassCandidateView(engine: engine, cats: Preferences.shared.catGlassSkin, expanded: false, canExpand: false,
            onChoose: { [weak self] in self?.onChoose($0) }, onPage: { [weak self] in self?.onPage($0) },
            onToggle: { [weak self] in self?.onToggle() }, onAppearanceChange: { [weak self] in self?.onAppearanceChange() })
        let hosting = CandidateHostingView(rootView: view)
        let window = CandidatePanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel],
                                    backing: .buffered, defer: false)
        window.level = .popUpMenu
        window.isOpaque = false
        window.backgroundColor = .clear
        // The view draws its own shadows; a window shadow would lag behind the changing outline.
        window.hasShadow = false
        window.hidesOnDeactivate = false
        window.becomesKeyOnlyIfNeeded = true
        window.isReleasedWhenClosed = false
        window.animationBehavior = .none
        // Over full-screen apps and on every Space, and not part of window cycling.
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        window.contentView = hosting
        self.hosting = hosting
        panel = window
        return (window, hosting)
    }

    /// Fits the panel to the current candidates and returns its frame by the
    /// caret. `start` is where the composition began; `caret` is where it ends now.
    @discardableResult
    func layout(expanded: Bool, canExpand: Bool, caret: NSRect, start: NSRect?, screens: [CandidateScreen]) -> NSRect? {
        let (panel, hosting) = build()
        guard let index = candidateDisplay(for: caret, screens: screens.map(\.frame)) else { return nil }
        let bounds = screens[index].visibleFrame
        let cats = Preferences.shared.catGlassSkin
        // A new line starts over; on the same line the window stays on its side.
        if let line = placedLine, !candidateSameLine(line, caret) { below = nil }
        placedLine = caret
        let room = candidateRoom(anchor: caret, bounds: bounds)
        let collapsed = GlassCandidateView.collapsedHeight(cats: cats)
        let wanted = collapsed + (expanded ? GlassCandidateView.minimumPanelHeight : 0)
        let useBelow = candidateGoesBelow(height: wanted, anchor: caret, bounds: bounds, keeping: below)
        below = useBelow
        var view = hosting.rootView
        view.cats = cats
        view.expanded = expanded
        view.canExpand = canExpand
        // Over the caret the panel opens upward, so the bar stays by the line being typed.
        view.panelAbove = !useBelow
        view.maxHeight = (useBelow ? room.below : room.above) + Desk.margin.top + Desk.margin.bottom
        view.maxWidth = bounds.width + Desk.margin.left + Desk.margin.right
        hosting.rootView = view
        panel.title = InterfaceText.system("喵撲候選", "喵扑候选")
        hosting.layoutSubtreeIfNeeded()
        let size = hosting.fittingSize
        let lead = Desk.lead(cats: cats)
        let rowWidth = size.width - lead - Desk.margin.right
        let x = candidateAnchorX(start: start, caret: caret, length: (engine.snapshot.preedit as NSString).length, rowWidth: rowWidth)
        let frame = candidateFrame(size: size, anchor: caret, bounds: bounds, margin: Desk.margin, lead: lead, x: x, keeping: useBelow)
        if panel.frame != frame { panel.setFrame(frame, display: true) }
        return frame
    }

    /// Shows the panel without activating anything. Frames change at once: a
    /// window that eases after every key reads as lag.
    func show(expanded: Bool, canExpand: Bool, caret: NSRect, start: NSRect?, screens: [CandidateScreen] = CandidateScreen.current) {
        guard layout(expanded: expanded, canExpand: canExpand, caret: caret, start: start, screens: screens) != nil,
              let panel, let hosting else { return }
        hosting.rootView.visible = true
        if !panel.isVisible {
            panel.alphaValue = 0
            panel.orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { context in
                context.duration = 0.08
                panel.animator().alphaValue = 1
            }
        }
        announce(expanded: expanded)
    }

    func hide() {
        guard let panel else { return }
        hosting?.rootView.visible = false
        panel.orderOut(nil)
        below = nil
        placedLine = nil
        spoken = ""
        spokenPage = 0
        spokenExpanded = false
    }

    /// A candidate was chosen: the next list is read even when its first candidate has the same text.
    func candidateChosen() { spoken = "" }

    /// What VoiceOver should hear now, nil when nothing changed: the panel
    /// opening or closing, then the highlighted candidate.
    func nextAnnouncement(expanded: Bool) -> String? {
        let snapshot = engine.snapshot
        let t = InterfaceText.system
        var parts: [String] = []
        if expanded != spokenExpanded { parts.append(expanded ? t("候選已展開", "候选已展开") : t("候選已收起", "候选已收起")) }
        spokenExpanded = expanded
        if let text = compactCandidateText(snapshot, at: snapshot.highlighted), text != spoken || snapshot.page != spokenPage {
            parts.append(text)
            spoken = text
            spokenPage = snapshot.page
        }
        return parts.isEmpty ? nil : parts.joined(separator: "，")
    }

    /// The panel never has focus, so VoiceOver reads nothing in it by itself.
    private func announce(expanded: Bool) {
        guard NSWorkspace.shared.isVoiceOverEnabled, let text = nextAnnouncement(expanded: expanded) else { return }
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: text, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }
}
