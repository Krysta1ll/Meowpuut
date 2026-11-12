import UIKit

// One touch surface for every key (docs/DESIGN.md §6). Keys are layers over a
// piece of glass that takes no touches;
// this view resolves each finger to a key itself, so the gaps between keys
// have no dead zones, several fingers can overlap without losing a key, and
// visual feedback happens in the same frame as the touch.

protocol KeyboardKeysViewDelegate: AnyObject {
    /// Immediate effects: Shift, Delete, page switches.
    func keysView(_ view: KeyboardKeysView, keyDown key: KeyboardKey)
    /// The key (or one of its alternates) is typed.
    func keysView(_ view: KeyboardKeysView, commit key: KeyboardKey, alternate: String?)
    func keysView(_ view: KeyboardKeysView, delete word: Bool)
    func keysView(_ view: KeyboardKeysView, shiftReleasedAfterTyping typed: Bool)
    /// Asked when space has been held long enough to become the cursor control.
    func keysViewCanBeginCursor(_ view: KeyboardKeysView) -> Bool
    func keysViewBeginCursor(_ view: KeyboardKeysView)
    func keysView(_ view: KeyboardKeysView, moveCursor offset: Int)
    func keysViewEndCursor(_ view: KeyboardKeysView)
    /// A touch on the globe key (the system shows its list from the event).
    func keysView(_ view: KeyboardKeysView, globe event: UIEvent)
    /// The globe key activated by VoiceOver: the next keyboard.
    func keysViewAdvanceInputMode(_ view: KeyboardKeysView)
    /// After sliding from 123 to a symbol and lifting, go back to `page`.
    func keysView(_ view: KeyboardKeysView, returnToPage page: Int)
}

/// A key's glass. It remembers what it was last made for, so one handed on to
/// another key, or a key whose label changed, keeps its effect.
final class KeyGlassView: UIVisualEffectView {
    struct Look: Equatable {
        var theme: KeyboardKeyTheme
        var accent: Bool
        var radius: CGFloat
    }
    var look: Look?
}

final class KeyCapView: UIView {
    /// The key's glass. It lies under the cap, in the keys view's glass
    /// container, at the cap's frame.
    let glass: KeyGlassView
    private let label = UILabel()
    private let icon = UIImageView()
    private(set) var key: KeyboardKey
    private var theme: KeyboardKeyTheme
    private var metrics: KeyMetrics

    var pressed = false { didSet { if pressed != oldValue { refresh() } } }
    var dimmed = false {
        didSet {
            guard dimmed != oldValue else { return }
            let alpha: CGFloat = dimmed ? 0 : 1
            UIView.animate(withDuration: 0.15) { self.label.alpha = alpha; self.icon.alpha = alpha }
        }
    }

    init(key: KeyboardKey, theme: KeyboardKeyTheme, metrics: KeyMetrics, glass: KeyGlassView) {
        self.key = key
        self.theme = theme
        self.metrics = metrics
        self.glass = glass
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        layer.cornerCurve = .continuous
        layer.shadowOffset = CGSize(width: 0, height: 1)
        layer.shadowRadius = 0
        label.textAlignment = .center
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.55
        label.baselineAdjustment = .alignCenters
        icon.contentMode = .center
        addSubview(label)
        addSubview(icon)
        apply()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func update(key: KeyboardKey, theme: KeyboardKeyTheme, metrics: KeyMetrics) {
        guard key != self.key || theme != self.theme || metrics != self.metrics else { return }
        let resized = metrics != self.metrics
        self.key = key
        self.theme = theme
        self.metrics = metrics
        apply()
        if resized { setNeedsLayout() }
    }

    private func apply() {
        layer.cornerRadius = metrics.cornerRadius
        layer.shadowColor = theme.shadow.cgColor
        layer.shadowOpacity = theme.shadowOpacity
        layer.borderColor = theme.outline?.cgColor
        layer.borderWidth = theme.outline == nil ? 0 : 1
        let size = key.fontSize ?? (key.style == .character && key.isCharacter ? metrics.letterFont : metrics.utilityFont)
        let weight: UIFont.Weight = theme.bold ? .semibold : (key.style == .accent ? .medium : .regular)
        label.font = .systemFont(ofSize: size, weight: weight)
        label.text = key.label
        if #available(iOS 26, *), LiquidGlass.available {
            // Bold Text changes the labels only.
            var plain = theme
            plain.bold = false
            let look = KeyGlassView.Look(theme: plain, accent: key.style == .accent, radius: metrics.cornerRadius)
            if glass.look != look {
                glass.look = look
                let tint = theme.glassTint(for: key.style)
                let effect = tint.map { _ in UIGlassEffect(style: .regular) }
                effect?.tintColor = tint
                glass.effect = effect
                glass.cornerConfiguration = .corners(radius: .fixed(metrics.cornerRadius))
            }
        }
        refresh()
    }

    private func refresh() {
        layer.backgroundColor = theme.fill(for: key.style, pressed: pressed).cgColor
        let ink = !key.enabled ? theme.disabledText : (key.style == .accent ? theme.accentText : theme.text)
        label.textColor = ink
        if let name = key.icon {
            let filled = pressed && name == "delete.left" ? "delete.left.fill" : name
            let config = UIImage.SymbolConfiguration(pointSize: metrics.utilityFont + 3, weight: theme.bold ? .semibold : .regular)
            icon.image = UIImage(systemName: filled, withConfiguration: config)
            icon.tintColor = ink
            icon.isHidden = false
        } else {
            icon.image = nil
            icon.isHidden = true
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        label.frame = bounds.insetBy(dx: 2, dy: 0)
        icon.frame = bounds
        layer.shadowPath = UIBezierPath(roundedRect: bounds, cornerRadius: metrics.cornerRadius).cgPath
    }
}

/// The magnified key above a pressed character on phones.
final class KeyBalloonView: UIView {
    private let shape = CAShapeLayer()
    private let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        layer.addSublayer(shape)
        shape.shadowOffset = CGSize(width: 0, height: 1.5)
        shape.shadowRadius = 2.5
        shape.lineWidth = 0.5
        label.textAlignment = .center
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.5
        addSubview(label)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func show(text: String, key: CGRect, keyboardWidth: CGFloat, idiom: KeyboardIdiom, theme: KeyboardKeyTheme, metrics: KeyMetrics) {
        let bubble = KeyBalloonGeometry.bubble(for: key, keyboardWidth: keyboardWidth, idiom: idiom)
        let bounds = bubble.union(key)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        frame = bounds
        let local = CGAffineTransform(translationX: -bounds.minX, y: -bounds.minY)
        let path = KeyBalloonGeometry.path(key: key.applying(local), bubble: bubble.applying(local), keyRadius: metrics.cornerRadius)
        shape.path = path
        shape.shadowPath = path
        shape.fillColor = theme.balloonFill.cgColor
        shape.strokeColor = theme.balloonOutline.cgColor
        shape.shadowColor = UIColor.black.cgColor
        shape.shadowOpacity = theme.dark ? 0.5 : 0.22
        CATransaction.commit()
        let size: CGFloat = idiom == .phoneLandscape ? 28 : (text.count > 1 ? 24 : 34)
        label.font = .systemFont(ofSize: size, weight: theme.bold ? .semibold : .regular)
        label.textColor = theme.text
        label.text = text
        label.frame = bubble.applying(local).insetBy(dx: 3, dy: 4)
        isHidden = false
    }
}

/// Long-press alternates (accented letters, punctuation variants). The first
/// option is the key itself and opens under the finger, so lifting without
/// sliding types the plain key.
final class KeyAlternatesView: UIView {
    private let shape = CAShapeLayer()
    private var labels: [UILabel] = []
    private let highlight = CALayer()
    private(set) var options: [String] = []
    private(set) var selected = -1
    private var cellWidth: CGFloat = 0
    /// Left edge of cell 0 for following the finger (keys view space). The
    /// pressed key's middle maps to the middle of its own cell, also when
    /// the popup had to move to stay inside the keyboard.
    private var selectionOrigin: CGFloat = 0
    private var keyFrame: CGRect = .zero
    private var theme = KeyboardKeyTheme(dark: false, highContrast: false, palette: .rose)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        layer.addSublayer(shape)
        layer.addSublayer(highlight)
        shape.shadowOffset = CGSize(width: 0, height: 1.5)
        shape.shadowRadius = 3
        shape.lineWidth = 0.5
        highlight.cornerCurve = .continuous
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// `base` starts with the key itself.
    func show(options base: [String], key: CGRect, keyboardWidth: CGFloat, theme: KeyboardKeyTheme, metrics: KeyMetrics) {
        // Grow toward the middle of the keyboard. A popup that grows to the
        // left lists its options right to left, so the key itself stays
        // over the pressed key. The middle key grows to the right.
        let growsLeft = key.midX > keyboardWidth / 2 + 0.5
        options = growsLeft ? base.reversed() : base
        let home = growsLeft ? options.count - 1 : 0
        self.theme = theme
        keyFrame = key
        selected = -1
        labels.forEach { $0.removeFromSuperview() }
        cellWidth = max(30, min(key.width, (keyboardWidth - 8) / CGFloat(max(1, options.count))))
        let width = cellWidth * CGFloat(options.count) + 8
        let height: CGFloat = 50
        var x = growsLeft ? key.maxX + 4 - width : key.minX - 4
        x = min(max(2, x), keyboardWidth - 2 - width)
        frame = CGRect(x: x, y: key.minY - height - 4, width: width, height: height)
        selectionOrigin = key.midX - (CGFloat(home) + 0.5) * cellWidth
        let path = UIBezierPath(roundedRect: bounds, cornerRadius: 11).cgPath
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        shape.path = path
        shape.shadowPath = path
        shape.fillColor = theme.balloonFill.cgColor
        shape.strokeColor = theme.balloonOutline.cgColor
        shape.shadowColor = UIColor.black.cgColor
        shape.shadowOpacity = theme.dark ? 0.5 : 0.25
        highlight.backgroundColor = theme.selection.cgColor
        highlight.cornerRadius = 8
        CATransaction.commit()
        labels = options.enumerated().map { index, text in
            let label = UILabel(frame: CGRect(x: 4 + CGFloat(index) * cellWidth, y: 4, width: cellWidth, height: height - 8))
            label.text = text
            label.textAlignment = .center
            label.font = .systemFont(ofSize: 24)
            label.adjustsFontSizeToFitWidth = true
            label.minimumScaleFactor = 0.5
            addSubview(label)
            return label
        }
        select(index: home)
        isHidden = false
    }

    /// Follows the finger (a point in the keys view's space). Well below the
    /// key or above the popup nothing is selected, and lifting types nothing.
    func select(at point: CGPoint) {
        guard !options.isEmpty else { return }
        if point.y > keyFrame.maxY + keyFrame.height || point.y < frame.minY - keyFrame.height {
            select(index: -1)
            return
        }
        let index = Int(floor((point.x - selectionOrigin) / cellWidth))
        select(index: min(options.count - 1, max(0, index)))
    }

    /// Selects the option `cells` to the right of the key's own (lab screenshots).
    func select(offset cells: Int) {
        select(at: CGPoint(x: keyFrame.midX + CGFloat(cells) * cellWidth, y: keyFrame.midY))
    }

    private func select(index: Int) {
        guard index != selected else { return }
        selected = index
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        highlight.isHidden = index < 0
        if index >= 0 {
            highlight.frame = CGRect(x: 4 + CGFloat(index) * cellWidth + 1, y: 5, width: cellWidth - 2, height: bounds.height - 10)
        }
        CATransaction.commit()
        for (i, label) in labels.enumerated() { label.textColor = i == index ? theme.accentText : theme.text }
    }

    var selectedText: String? { options.indices.contains(selected) ? options[selected] : nil }
}

private final class KeyAccessibilityElement: UIAccessibilityElement {
    var activate: (() -> Void)?
    override func accessibilityActivate() -> Bool {
        activate?()
        return true
    }
}

final class KeyboardKeysView: UIView {
    weak var delegate: KeyboardKeysViewDelegate?

    private(set) var keySet: KeyboardKeySet?
    private(set) var placements: [[KeyPlacement]] = []
    private var caps: [[KeyCapView]] = []
    /// Holds every key's glass under the caps, so iOS draws them as one group.
    private let glassBoard = UIVisualEffectView()
    private(set) var theme = KeyboardKeyTheme(dark: false, highContrast: false, palette: .rose)
    private(set) var metrics = KeyMetrics(idiom: .phone, width: 393, rows: 4)
    private(set) var idiom: KeyboardIdiom = .phone
    private var layoutWidth: CGFloat = 0
    /// Where the keys area begins inside this view; balloons may rise above it.
    private(set) var areaTop: CGFloat = 0
    /// Margin beside the keys that still answers touches (landscape phones).
    var touchOutset: CGFloat = 0
    private var accessibilityCache: [KeyAccessibilityElement]?
    /// What is drawn for each key: its placement snapped to whole pixels.
    private var drawFrames: [[CGRect]] = []

    /// `slide` phases start on Shift or a page key and may end on a character.
    private enum Phase { case key, alternates, cursor, delete, shift, pageSlide(origin: Int), globe, inert }

    /// A finger, or a numbered finger in a lab script.
    private enum TouchID: Hashable {
        case touch(ObjectIdentifier)
        case script(Int)
    }

    private final class Track {
        var position: (row: Int, column: Int)
        var key: KeyboardKey
        var phase: Phase
        var point: CGPoint
        var committed = false
        var typedWhileHeld = false
        var timer: Timer?
        var balloon: KeyBalloonView?
        var alternates: KeyAlternatesView?
        var cursor: SpaceCursorDriver?
        var deleter: RepeatDeleteDriver?
        /// The character key under a finger that came down on Shift or a page key.
        var slide: (row: Int, column: Int)?
        init(position: (row: Int, column: Int), key: KeyboardKey, phase: Phase, point: CGPoint) {
            self.position = position
            self.key = key
            self.phase = phase
            self.point = point
        }
    }

    private var tracks: [TouchID: Track] = [:]
    private var order: [TouchID] = []
    private var spareBalloons: [KeyBalloonView] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isExclusiveTouch = false
        clipsToBounds = false
        backgroundColor = .clear
        isAccessibilityElement = false
        if #available(iOS 26, *), LiquidGlass.available {
            let container = UIGlassContainerEffect()
            container.spacing = 0
            glassBoard.effect = container
        }
        glassBoard.isUserInteractionEnabled = false
        glassBoard.frame = bounds
        glassBoard.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(glassBoard)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // The system's edge gestures hold back touches near the screen edges,
        // which makes the outer keys and the bottom row answer late.
        window?.gestureRecognizers?.forEach { $0.delaysTouchesBegan = false }
        // The screen's scale is known now.
        if window != nil { layoutCaps() }
    }

    var currentPage: Int {
        if case .symbols(let page)? = keySet?.kind { return page }
        return 0
    }

    /// Installs a key set. Label-only changes (Shift, script) update the
    /// existing caps; a different layout rebuilds them.
    func configure(keySet: KeyboardKeySet, metrics: KeyMetrics, idiom: KeyboardIdiom, theme: KeyboardKeyTheme,
                   width: CGFloat, areaTop: CGFloat) {
        let sameShape = self.keySet.map { $0.kind == keySet.kind && $0.specs == keySet.specs && $0.rows.map(\.count) == keySet.rows.map(\.count) } ?? false
        let sameGeometry = sameShape && metrics == self.metrics && width == layoutWidth && areaTop == self.areaTop
        let sameKeys = sameGeometry && self.keySet == keySet
        if sameKeys && theme == self.theme && idiom == self.idiom { return }
        self.keySet = keySet
        self.theme = theme
        self.idiom = idiom
        // The glass follows the keyboard's own light or dark setting, which
        // can differ from the system's.
        overrideUserInterfaceStyle = theme.dark ? .dark : .light
        self.areaTop = areaTop
        if !sameGeometry {
            self.metrics = metrics
            layoutWidth = width
            placements = KeyGridLayout.place(rows: keySet.specs, width: width, referenceColumns: keySet.referenceColumns, metrics: metrics)
        }
        if sameShape && caps.count == keySet.rows.count {
            for (r, row) in keySet.rows.enumerated() {
                for (c, key) in row.enumerated() { caps[r][c].update(key: key, theme: theme, metrics: metrics) }
            }
        } else {
            // The old keys' glass goes to the new keys: building an effect
            // view for every key on each page change would be felt.
            var spare = caps.flatMap { $0 }.map(\.glass)
            caps.flatMap { $0 }.forEach { $0.removeFromSuperview() }
            caps = keySet.rows.map { row in
                row.map { key in
                    let glass = spare.popLast() ?? {
                        let view = KeyGlassView()
                        view.isUserInteractionEnabled = false
                        glassBoard.contentView.addSubview(view)
                        return view
                    }()
                    let cap = KeyCapView(key: key, theme: theme, metrics: metrics, glass: glass)
                    insertSubview(cap, aboveSubview: glassBoard)
                    return cap
                }
            }
            spare.forEach { $0.removeFromSuperview() }
            // A held key from the old layout no longer matches what is drawn.
            for track in tracks.values where !isSlide(track) {
                retire(track)
            }
        }
        if !sameGeometry || !sameShape { layoutCaps() }
        if idiom != .pad, spareBalloons.isEmpty, !tracks.values.contains(where: { $0.balloon != nil }) {
            // The first key press should not pay for building the balloon.
            let balloon = KeyBalloonView()
            balloon.isHidden = true
            addSubview(balloon)
            spareBalloons.append(balloon)
        }
        if !sameShape {
            accessibilityCache = nil
            if UIAccessibility.isVoiceOverRunning {
                UIAccessibility.post(notification: .layoutChanged, argument: nil)
            }
        } else if !sameKeys {
            // The same keys with new labels or sizes (Shift, the space and
            // Return titles): VoiceOver keeps its elements and its focus.
            refreshAccessibility()
        }
    }

    /// Stops whatever a track was doing, without typing anything.
    private func retire(_ track: Track) {
        track.timer?.invalidate()
        track.deleter?.stop()
        if case .cursor = track.phase {
            track.cursor?.endCursor()
            setDimmed(false)
            delegate?.keysViewEndCursor(self)
        }
        hideVisuals(track)
        track.phase = .inert
    }

    /// Ends every touch without typing (the keyboard is going away).
    func cancelAllTouches() {
        for id in order { cancel(id, event: nil) }
    }

    private func isSlide(_ track: Track) -> Bool {
        switch track.phase {
        case .pageSlide, .shift: return true
        default: return false
        }
    }

    private func layoutCaps() {
        // Key edges land on whole pixels, so caps and labels stay sharp at
        // widths where a key is a fraction of a point wide (393, 402 pt).
        // Touch areas keep the exact placements.
        let scale = traitCollection.displayScale > 0 ? traitCollection.displayScale : 2
        drawFrames = placements.map { row in
            row.map { KeyGridLayout.snap($0.frame.offsetBy(dx: 0, dy: areaTop), scale: scale) }
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for (r, row) in caps.enumerated() where r < drawFrames.count {
            for (c, cap) in row.enumerated() where c < drawFrames[r].count {
                cap.frame = drawFrames[r][c]
                cap.glass.frame = drawFrames[r][c]
            }
        }
        CATransaction.commit()
        refreshAccessibility()
    }

    func setDimmed(_ dimmed: Bool) {
        caps.flatMap { $0 }.forEach { $0.dimmed = dimmed && $0.key.action != .space }
    }

    // MARK: Hit testing

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        !isHidden && point.y >= areaTop && point.y <= bounds.maxY
            && point.x >= -touchOutset && point.x <= bounds.maxX + touchOutset
    }

    private func position(at point: CGPoint) -> (row: Int, column: Int)? {
        KeyGridLayout.key(at: CGPoint(x: point.x, y: point.y - areaTop), in: placements)
    }

    private func key(at position: (row: Int, column: Int)) -> KeyboardKey? {
        guard let rows = keySet?.rows, rows.indices.contains(position.row), rows[position.row].indices.contains(position.column) else { return nil }
        return rows[position.row][position.column]
    }

    private func cap(at position: (row: Int, column: Int)) -> KeyCapView? {
        guard caps.indices.contains(position.row), caps[position.row].indices.contains(position.column) else { return nil }
        return caps[position.row][position.column]
    }

    private func frame(at position: (row: Int, column: Int)) -> CGRect? {
        guard drawFrames.indices.contains(position.row), drawFrames[position.row].indices.contains(position.column) else { return nil }
        return drawFrames[position.row][position.column]
    }

    // MARK: Touches

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches.sorted(by: { $0.timestamp < $1.timestamp }) {
            down(.touch(ObjectIdentifier(touch)), at: touch.location(in: self), event: event)
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { move(.touch(ObjectIdentifier(touch)), to: touch.location(in: self), event: event) }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches.sorted(by: { $0.timestamp < $1.timestamp }) {
            up(.touch(ObjectIdentifier(touch)), at: touch.location(in: self), event: event)
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { cancel(.touch(ObjectIdentifier(touch)), event: event) }
    }

    private func down(_ id: TouchID, at point: CGPoint, event: UIEvent?) {
        guard tracks[id] == nil, position(at: point) != nil else { return }
        // While space moves the cursor the key labels are hidden, and other
        // fingers type nothing.
        if tracks.values.contains(where: { if case .cursor = $0.phase { return true } else { return false } }) { return }
        // Rollover: a new finger types whatever the earlier fingers are on.
        for earlier in order { if let track = tracks[earlier] { commitEarly(track) } }
        // That can change the keys (the English toggle, space on a symbol
        // page), so the new finger's key is looked up afterwards.
        guard let position = position(at: point), let key = key(at: position) else { return }
        let track = Track(position: position, key: key, phase: .key, point: point)
        tracks[id] = track
        order.append(id)
        begin(track, event: event)
    }

    private func move(_ id: TouchID, to point: CGPoint, event: UIEvent?) {
        guard let track = tracks[id] else { return }
        track.point = point
        switch track.phase {
        case .key:
            if track.key.isCharacter, !track.committed, let next = position(at: point),
               next != track.position, let nextKey = key(at: next), nextKey.isCharacter {
                hideVisuals(track)
                track.position = next
                track.key = nextKey
                pressVisuals(track)
                scheduleAlternates(track)
            }
        case .alternates:
            track.alternates?.select(at: point)
        case .cursor:
            track.cursor?.updateCursor(at: Double(point.x))
        case .pageSlide, .shift:
            // From Shift or a page key the finger may slide onto a character.
            // Once that character is typed the finger only holds its key.
            if track.committed { break }
            let next = position(at: point)
            let nextKey = next.flatMap { key(at: $0) }
            if let next, let nextKey, nextKey.isCharacter, nextKey.enabled {
                if track.slide.map({ $0 != next }) ?? true {
                    hideVisuals(track)
                    track.slide = next
                    track.position = next
                    track.key = nextKey
                    pressVisuals(track)
                }
            } else if track.slide != nil {
                hideVisuals(track)
                track.slide = nil
            }
        case .globe:
            if let event { delegate?.keysView(self, globe: event) }
        case .delete, .inert:
            break
        }
    }

    private func up(_ id: TouchID, at point: CGPoint, event: UIEvent?) {
        guard let track = tracks[id] else { return }
        track.point = point
        switch track.phase {
        case .key:
            if !track.committed {
                if track.key.isCharacter || track.key.action == .space {
                    commit(track, alternate: nil)
                } else if let hit = position(at: point), hit == track.position {
                    // Return and the English toggle need the finger to stay on them.
                    commit(track, alternate: nil)
                }
            }
        case .alternates:
            // The key's own cell types the key as a tap would (Shift included).
            if !track.committed, let text = track.alternates?.selectedText {
                commit(track, alternate: text == track.key.label ? nil : text)
            }
        case .cursor:
            track.cursor?.endCursor()
            setDimmed(false)
            delegate?.keysViewEndCursor(self)
        case .delete:
            track.deleter?.stop()
        case .shift:
            if track.slide != nil, !track.committed { commit(track, alternate: nil) }
            delegate?.keysView(self, shiftReleasedAfterTyping: track.typedWhileHeld)
        case .pageSlide(let origin):
            if track.slide != nil, !track.committed { commit(track, alternate: nil) }
            // A page key held while something was typed, by this finger
            // sliding or by another finger, works like Shift: lifting it
            // goes back to the page it came from.
            if track.typedWhileHeld {
                // Going back rebuilds the keys; type what other fingers hold first.
                for other in order where other != id { if let pending = tracks[other] { commitEarly(pending) } }
                delegate?.keysView(self, returnToPage: origin)
            }
        case .globe:
            if let event { delegate?.keysView(self, globe: event) }
        case .inert:
            break
        }
        finish(id)
    }

    private func cancel(_ id: TouchID, event: UIEvent?) {
        guard let track = tracks[id] else { return }
        switch track.phase {
        case .cursor:
            track.cursor?.endCursor()
            setDimmed(false)
            delegate?.keysViewEndCursor(self)
        case .delete: track.deleter?.stop()
        case .shift: delegate?.keysView(self, shiftReleasedAfterTyping: track.typedWhileHeld)
        case .globe:
            // Without an event the keyboard is going away: nothing to tell the system.
            if let event { delegate?.keysView(self, globe: event) }
        default: break
        }
        finish(id)
    }

    private func finish(_ id: TouchID) {
        guard let track = tracks.removeValue(forKey: id) else { return }
        order.removeAll { $0 == id }
        track.timer?.invalidate()
        hideVisuals(track)
    }

    private func schedule(_ track: Track, after delay: TimeInterval, _ block: @escaping (Track) -> Void) {
        track.timer?.invalidate()
        let timer = Timer(timeInterval: delay, repeats: false) { [weak track] _ in
            if let track { block(track) }
        }
        // Common modes: the timer also fires while a candidate list is scrolling.
        RunLoop.main.add(timer, forMode: .common)
        track.timer = timer
    }

    private func begin(_ track: Track, event: UIEvent?) {
        guard track.key.enabled else { track.phase = .inert; return }
        KeyboardFeedback.keyDown()
        switch track.key.action {
        case .engine, .text:
            pressVisuals(track)
            scheduleAlternates(track)
        case .space:
            cap(at: track.position)?.pressed = true
            let driver = SpaceCursorDriver(tap: {}, beginCursor: {}, move: { [weak self] offset in
                guard let self else { return }
                self.delegate?.keysView(self, moveCursor: offset)
            })
            driver.touchDown()
            track.cursor = driver
            schedule(track, after: SpaceCursorDriver.holdDelay) { [weak self] track in
                guard let self, !track.committed, case .key = track.phase else { return }
                // While a reading is open, space picks a word or adds a tone.
                // Holding it does not change that.
                guard self.delegate?.keysViewCanBeginCursor(self) ?? true else { return }
                track.phase = .cursor
                self.cap(at: track.position)?.pressed = false
                self.setDimmed(true)
                self.delegate?.keysViewBeginCursor(self)
                // Start from where the finger is now, not where it came down.
                driver.startCursor(at: Double(track.point.x))
            }
        case .delete:
            track.phase = .delete
            cap(at: track.position)?.pressed = true
            let deleter = RepeatDeleteDriver { [weak self] in
                guard let self else { return }
                self.delegate?.keysView(self, delete: false)
            }
            deleter.wordAction = { [weak self] in
                guard let self else { return }
                self.delegate?.keysView(self, delete: true)
            }
            deleter.onRepeatTick = { UIDevice.current.playInputClick() }
            track.deleter = deleter
            deleter.press()
        case .shift:
            track.phase = .shift
            delegate?.keysView(self, keyDown: track.key)
        case .returnKey, .toggleEnglish, .dismiss, .kaomoji:
            cap(at: track.position)?.pressed = true
        case .page:
            track.phase = .pageSlide(origin: currentPage)
            delegate?.keysView(self, keyDown: track.key)
        case .globe:
            track.phase = .globe
            if let event { delegate?.keysView(self, globe: event) }
        }
    }

    /// Types a held key early because another finger came down.
    private func commitEarly(_ track: Track) {
        guard !track.committed else { return }
        switch track.phase {
        case .key:
            guard track.key.commitsOnRelease else { return }
            if !track.key.isCharacter && track.key.action != .space {
                // Return and the English toggle count only while the finger is still on them.
                guard let hit = position(at: track.point), hit == track.position else { return }
            }
            track.timer?.invalidate()
            hideVisuals(track)
            track.phase = .inert
            commit(track, alternate: nil)
        case .shift, .pageSlide:
            // A finger that slid from Shift or a page key onto a character:
            // type the character now. The finger keeps holding Shift or the
            // page until it lifts.
            guard track.slide != nil else { return }
            hideVisuals(track)
            commit(track, alternate: nil)
        default:
            break
        }
    }

    private func commit(_ track: Track, alternate: String?) {
        track.committed = true
        for other in tracks.values {
            switch other.phase {
            case .shift, .pageSlide: other.typedWhileHeld = true
            default: break
            }
        }
        delegate?.keysView(self, commit: track.key, alternate: alternate)
    }

    private func scheduleAlternates(_ track: Track) {
        track.timer?.invalidate()
        guard !track.key.alternates.isEmpty else { return }
        schedule(track, after: 0.45) { [weak self] track in
            guard let self, !track.committed, case .key = track.phase,
                  let frame = self.frame(at: track.position) else { return }
            self.hideVisuals(track)
            let view = KeyAlternatesView()
            self.addSubview(view)
            view.show(options: track.key.alternates, key: frame, keyboardWidth: self.bounds.width, theme: self.theme, metrics: self.metrics)
            track.alternates = view
            track.phase = .alternates
        }
    }

    private func pressVisuals(_ track: Track) {
        guard let frame = frame(at: track.position) else { return }
        if idiom == .pad || !track.key.preview {
            cap(at: track.position)?.pressed = true
            return
        }
        let balloon = spareBalloons.popLast() ?? KeyBalloonView()
        if balloon.superview == nil { addSubview(balloon) }
        bringSubviewToFront(balloon)
        balloon.show(text: track.key.label, key: frame, keyboardWidth: bounds.width, idiom: idiom, theme: theme, metrics: metrics)
        track.balloon = balloon
    }

    private func hideVisuals(_ track: Track) {
        cap(at: track.position)?.pressed = false
        if let balloon = track.balloon {
            balloon.isHidden = true
            spareBalloons.append(balloon)
            track.balloon = nil
        }
        track.alternates?.removeFromSuperview()
        track.alternates = nil
    }

    #if KEYBOARD_LAB
    /// Shows the balloon (or alternates) for a key as if it were held.
    func labPress(label: String, alternates: Bool) {
        for (r, row) in caps.enumerated() {
            for (c, cap) in row.enumerated() where cap.key.label.lowercased() == label.lowercased() {
                let track = Track(position: (r, c), key: cap.key, phase: .key, point: cap.center)
                if alternates, !cap.key.alternates.isEmpty, let frame = frame(at: (r, c)) {
                    let view = KeyAlternatesView()
                    addSubview(view)
                    view.show(options: cap.key.alternates, key: frame, keyboardWidth: bounds.width, theme: theme, metrics: metrics)
                    view.select(offset: 1)
                } else {
                    pressVisuals(track)
                }
                return
            }
        }
    }

    enum LabTouchPhase { case down, move, up, cancel }

    /// A scripted finger: the same code path as a real touch.
    func labTouch(_ phase: LabTouchPhase, finger: Int, at point: CGPoint) {
        switch phase {
        case .down: down(.script(finger), at: point, event: nil)
        case .move: move(.script(finger), to: point, event: nil)
        case .up: up(.script(finger), at: point, event: nil)
        case .cancel: cancel(.script(finger), event: nil)
        }
    }

    /// Where a scripted finger last was.
    func labPoint(finger: Int) -> CGPoint? { tracks[.script(finger)]?.point }

    /// The centre of a key, by its label or by a name for keys without one
    /// (shift, delete, space, return, symbols, letters, toggle, globe, kaomoji).
    /// `symbols` is the first page key in reading order, `letters` the key back to the letters.
    func labCenter(of name: String) -> CGPoint? {
        guard let rows = keySet?.rows else { return nil }
        for (r, row) in rows.enumerated() {
            for (c, key) in row.enumerated() {
                let matches: Bool
                switch (name, key.action) {
                case ("letters", .page(let page)):
                    matches = page == 0
                case ("shift", .shift), ("delete", .delete), ("space", .space), ("return", .returnKey),
                     ("symbols", .page), ("toggle", .toggleEnglish), ("globe", .globe), ("kaomoji", .kaomoji):
                    matches = true
                default:
                    // Letters match in either case: Shift changes the label.
                    matches = key.isCharacter && (key.label == name || (name.count == 1 && key.label.lowercased() == name.lowercased()))
                }
                if matches, let frame = frame(at: (r, c)) { return CGPoint(x: frame.midX, y: frame.midY) }
            }
        }
        return nil
    }

    var labVisibleBalloons: Int { subviews.filter { $0 is KeyBalloonView && !$0.isHidden }.count }
    var labActiveTouches: Int { tracks.count }
    #endif

    // MARK: Accessibility

    override var accessibilityElements: [Any]? {
        get {
            if let accessibilityCache { return accessibilityCache }
            var elements: [KeyAccessibilityElement] = []
            for (r, row) in caps.enumerated() {
                for (c, cap) in row.enumerated() {
                    let element = KeyAccessibilityElement(accessibilityContainer: self)
                    element.activate = { [weak self] in self?.activate(row: r, column: c) }
                    describe(cap, in: element)
                    elements.append(element)
                }
            }
            accessibilityCache = elements
            return elements
        }
        set { }
    }

    private func describe(_ cap: KeyCapView, in element: KeyAccessibilityElement) {
        let key = cap.key
        element.accessibilityLabel = key.accessibilityLabel
        element.accessibilityValue = key.accessibilityValue
        element.accessibilityTraits = key.enabled ? .keyboardKey : [.keyboardKey, .notEnabled]
        element.accessibilityFrameInContainerSpace = cap.frame
        // The long-press alternates, as VoiceOver actions (swipe up or down).
        element.accessibilityCustomActions = key.alternates.filter { $0 != key.label }.map { text in
            UIAccessibilityCustomAction(name: text) { [weak self] _ in
                guard let self else { return false }
                UIDevice.current.playInputClick()
                self.delegate?.keysView(self, commit: key, alternate: text)
                return true
            }
        }
    }

    /// Updates the existing elements, so VoiceOver keeps its focus and reads
    /// the new state of the key it is on (Shift on or off).
    private func refreshAccessibility() {
        guard let elements = accessibilityCache else { return }
        let all = caps.flatMap { $0 }
        guard all.count == elements.count else { accessibilityCache = nil; return }
        for (cap, element) in zip(all, elements) { describe(cap, in: element) }
    }

    private func activate(row: Int, column: Int) {
        guard let key = key(at: (row, column)), key.enabled else { return }
        UIDevice.current.playInputClick()
        switch key.action {
        case .delete: delegate?.keysView(self, delete: false)
        case .shift:
            // A VoiceOver activation is a whole tap: press and release.
            delegate?.keysView(self, keyDown: key)
            delegate?.keysView(self, shiftReleasedAfterTyping: false)
        case .page: delegate?.keysView(self, keyDown: key)
        case .globe: delegate?.keysViewAdvanceInputMode(self)
        default: delegate?.keysView(self, commit: key, alternate: nil)
        }
    }
}
