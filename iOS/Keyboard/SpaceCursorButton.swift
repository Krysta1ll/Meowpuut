import Foundation

// Touch and cursor state is separate from the view, so a held space never
// generates the normal space action on release.
final class SpaceCursorDriver {
    var tap: () -> Void
    var beginCursor: () -> Void
    var move: (Int) -> Void
    private(set) var moving = false
    private var held = false
    private var origin: Double = 0
    private var lastStep = 0
    private let pointsPerCharacter: Double = 12

    init(tap: @escaping () -> Void, beginCursor: @escaping () -> Void, move: @escaping (Int) -> Void) {
        self.tap = tap
        self.beginCursor = beginCursor
        self.move = move
    }
    func touchDown() { held = false; moving = false; lastStep = 0 }
    func startCursor(at x: Double) {
        guard !held else { return }
        held = true
        moving = true
        origin = x
        lastStep = 0
        beginCursor()
    }
    func updateCursor(at x: Double) {
        guard moving, x.isFinite else { return }
        let distance = (x - origin) / pointsPerCharacter
        // The view is far narrower than this; bounding also prevents an
        // invalid coordinate from overflowing an integer conversion.
        let step = Int(max(-512, min(512, distance)))
        let delta = step - lastStep
        guard delta != 0 else { return }
        lastStep = step
        move(delta)
    }
    func endCursor() { moving = false }
    func touchUpInside() {
        if !held { tap() }
        moving = false
    }
    func cancelTouch() { if !held { moving = false } }
    func accessibilityMove(_ offset: Int) {
        guard offset != 0 else { return }
        beginCursor()
        move(offset)
    }
}

#if canImport(UIKit)
import UIKit
import SwiftUI

private final class AccessibleSpaceButton: UIButton {
    var activate: (() -> Void)?
    override var isHighlighted: Bool {
        didSet { backgroundColor = isHighlighted ? UIColor.label.withAlphaComponent(0.12) : .clear }
    }
    override func accessibilityActivate() -> Bool {
        activate?()
        return true
    }
}

struct SpaceCursorButton: UIViewRepresentable {
    var title: String
    var text = InterfaceText(simplified: false)
    var onTap: () -> Void
    var onBeginCursor: () -> Void
    var onMove: (Int) -> Void
    var tintColor: UIColor = .label

    final class Coordinator: NSObject {
        let driver: SpaceCursorDriver
        init(tap: @escaping () -> Void, beginCursor: @escaping () -> Void, move: @escaping (Int) -> Void) {
            driver = SpaceCursorDriver(tap: tap, beginCursor: beginCursor, move: move)
        }
        @objc func touchDown() { UIDevice.current.playInputClick(); driver.touchDown() }
        @objc func touchUp() { driver.touchUpInside() }
        @objc func cancelTouch() { driver.cancelTouch() }
        @objc func accessibilityLeft(_ action: UIAccessibilityCustomAction) -> Bool { driver.accessibilityMove(-1); return true }
        @objc func accessibilityRight(_ action: UIAccessibilityCustomAction) -> Bool { driver.accessibilityMove(1); return true }
        @objc func longPress(_ gesture: UILongPressGestureRecognizer) {
            let x = Double(gesture.location(in: gesture.view).x)
            switch gesture.state {
            case .began:
                UISelectionFeedbackGenerator().selectionChanged()
                driver.startCursor(at: x)
            case .changed: driver.updateCursor(at: x)
            case .ended, .cancelled, .failed: driver.endCursor()
            default: break
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(tap: onTap, beginCursor: onBeginCursor, move: onMove) }

    func makeUIView(context: Context) -> UIButton {
        let button = AccessibleSpaceButton(type: .system)
        button.layer.cornerRadius = 8
        button.titleLabel?.font = .systemFont(ofSize: 15)
        button.titleLabel?.lineBreakMode = .byTruncatingTail
        button.accessibilityTraits.insert(.keyboardKey)
        applyAccessibility(to: button, coordinator: context.coordinator)
        button.addTarget(context.coordinator, action: #selector(Coordinator.touchDown), for: .touchDown)
        button.addTarget(context.coordinator, action: #selector(Coordinator.touchUp), for: .touchUpInside)
        button.addTarget(context.coordinator, action: #selector(Coordinator.cancelTouch), for: [.touchCancel, .touchUpOutside])
        let longPress = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.longPress(_:)))
        longPress.minimumPressDuration = 0.28
        longPress.allowableMovement = 20
        longPress.cancelsTouchesInView = true
        button.addGestureRecognizer(longPress)
        button.activate = { [weak coordinator = context.coordinator] in coordinator?.driver.tap() }
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        context.coordinator.driver.tap = onTap
        context.coordinator.driver.beginCursor = onBeginCursor
        context.coordinator.driver.move = onMove
        button.setTitle(title, for: .normal)
        button.setTitleColor(tintColor, for: .normal)
        applyAccessibility(to: button, coordinator: context.coordinator)
    }

    private func applyAccessibility(to button: UIButton, coordinator: Coordinator) {
        button.accessibilityLabel = "空格"
        button.accessibilityHint = text("長按並左右滑動以移動游標", "长按并左右滑动以移动光标")
        button.accessibilityCustomActions = [
            UIAccessibilityCustomAction(name: text("游標左移", "光标左移"), target: coordinator, selector: #selector(Coordinator.accessibilityLeft(_:))),
            UIAccessibilityCustomAction(name: text("游標右移", "光标右移"), target: coordinator, selector: #selector(Coordinator.accessibilityRight(_:)))
        ]
    }

    static func dismantleUIView(_ button: UIButton, coordinator: Coordinator) { coordinator.driver.endCursor() }
}
#endif
