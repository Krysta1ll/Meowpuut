import Foundation

// Runs on the main run loop so a lift, cancelled touch, or removed view can
// stop the timer before it performs another deletion.
final class RepeatDeleteDriver {
    var action: () -> Void
    private var timer: Timer?

    init(action: @escaping () -> Void) { self.action = action }

    func press() {
        guard timer == nil else { return }
        let next = Timer(timeInterval: 0.075, repeats: true) { [weak self] _ in self?.action() }
        next.fireDate = Date().addingTimeInterval(0.42)
        timer = next
        RunLoop.main.add(next, forMode: .common)
        action()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    deinit { timer?.invalidate() }
}

#if canImport(UIKit)
import UIKit
import SwiftUI

private final class AccessibleDeleteButton: UIButton {
    var activate: (() -> Void)?
    override func accessibilityActivate() -> Bool {
        activate?()
        return true
    }
}

struct RepeatDeleteButton: UIViewRepresentable {
    var action: () -> Void
    var tintColor: UIColor = .label

    final class Coordinator: NSObject {
        let driver: RepeatDeleteDriver
        init(action: @escaping () -> Void) {
            driver = RepeatDeleteDriver(action: action)
            super.init()
            for name in [KeyboardViewController.interactionCancellationNotification, UIApplication.willResignActiveNotification, UIScene.willDeactivateNotification] {
                NotificationCenter.default.addObserver(self, selector: #selector(stopped), name: name, object: nil)
            }
        }
        deinit { NotificationCenter.default.removeObserver(self) }
        @objc func pressed() {
            UIDevice.current.playInputClick()
            driver.press()
        }
        @objc func stopped() { driver.stop() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(action: action) }

    func makeUIView(context: Context) -> UIButton {
        let button = AccessibleDeleteButton(type: .system)
        button.setImage(UIImage(systemName: "delete.left"), for: .normal)
        button.accessibilityLabel = "刪除"
        button.addTarget(context.coordinator, action: #selector(Coordinator.pressed), for: .touchDown)
        button.addTarget(context.coordinator, action: #selector(Coordinator.stopped),
                         for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
        button.activate = { [weak coordinator = context.coordinator] in coordinator?.driver.action() }
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        context.coordinator.driver.action = action
        button.tintColor = tintColor
    }

    static func dismantleUIView(_ button: UIButton, coordinator: Coordinator) {
        coordinator.driver.stop()
    }
}
#endif
