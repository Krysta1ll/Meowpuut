import UIKit
import SwiftUI

// UIKit only plays the system click when an enabling input view is visible and
// the user has enabled keyboard clicks in system settings.
final class KeyboardFeedbackInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

final class KeyboardContext: ObservableObject {
    @Published var returnKey: UIReturnKeyType = .default
    @Published var shouldAutoCapitalize = false
    @Published var appearanceRevision = 0
    var isActionReturnKey: Bool {
        switch returnKey {
        case .search, .google, .yahoo, .send, .go, .done, .join, .next, .route, .continue, .emergencyCall:
            return true
        default:
            return false
        }
    }
}

final class KeyboardViewController: UIInputViewController {
    private let engine = Engine(Preferences.shared.keyboardBackend, mode: Preferences.shared.inputMode)
    private let keyboardContext = KeyboardContext()
    private var documentID: UUID?
    private var delivering = false
    private var compositionDelivery = CompositionDelivery()
    private var keyboardHeight: NSLayoutConstraint?
    private var visibleRowCount = 4
    private var candidateBarHeight: CGFloat = KeyboardLayoutMetrics.candidateHeight
    private var lastIdleSpaceTime: TimeInterval = 0
    override func loadView() {
        let keyboard = KeyboardFeedbackInputView(frame: .zero, inputViewStyle: .keyboard)
        inputView = keyboard
        view = keyboard
    }
    override func viewDidLoad() {
        super.viewDidLoad()
        let root = KeyboardPanel(engine: engine, context: keyboardContext, key: { [weak self] code, modifiers in
            guard let self else { return }
            if code == 32, modifiers == 0, !self.engine.english, self.engine.inputMode == .zhuyin,
               let last = self.engine.snapshot.input.last, " 6347".contains(last), !self.engine.snapshot.candidates.isEmpty {
                self.engine.finishComposition(); self.deliver(); self.updateContext(); return
            }
            let wasIdle = self.engine.snapshot.preedit.isEmpty && self.engine.moduleBuffer == nil
            if wasIdle, code == 32, modifiers == 0, self.tryDoubleSpaceShortcut() {
                self.updateContext()
                return
            }
            let handled = self.engine.key(code, modifiers: modifiers)
            self.deliver()
            if !handled && code == 0xff0d { self.textDocumentProxy.insertText("\n") }
            if !handled && code == 32 {
                self.textDocumentProxy.insertText(" ")
                self.lastIdleSpaceTime = ProcessInfo.processInfo.systemUptime
            } else if code == 32 && wasIdle && self.engine.english {
                self.lastIdleSpaceTime = ProcessInfo.processInfo.systemUptime
            } else {
                self.lastIdleSpaceTime = 0
            }
            if !handled, code > 32, code < 0xff00, let scalar = UnicodeScalar(UInt32(code)) { self.textDocumentProxy.insertText(String(scalar)) }
            self.updateContext()
        }, insertSymbol: { [weak self] text in
            guard let self else { return }
            self.lastIdleSpaceTime = 0
            // Finish composition and insert a symbol in one host transaction.
            self.engine.finishComposition(); self.deliver(extraCommit: text)
            self.updateContext()
        }, choose: { [weak self] index in
            guard let self else { return }
            self.lastIdleSpaceTime = 0
            if self.engine.snapshot.candidates.indices.contains(index) { self.engine.choose(index) }
            else if index == self.engine.snapshot.candidates.count, self.engine.snapshot.compactJapanese != nil {
                self.engine.chooseCompactJapanese()
            }
            self.deliver()
            self.updateContext()
        },
        erase: { [weak self] in self?.erase() },
        beginCursor: { [weak self] in self?.engine.finishComposition(); self?.deliver(); self?.updateContext() },
        moveCursor: { [weak self] offset in
            self?.textDocumentProxy.adjustTextPosition(byCharacterOffset: offset)
            self?.updateContext()
        },
        switchScript: { [weak self] in
            // Petting 莫莫 switches script in place; the current reading is
            // kept and its candidates are rebuilt in the new script.
            Preferences.shared.simplified.toggle()
            if let self { self.engine.modeChanged(); self.deliver() }
            return Preferences.shared.simplified
        },
        keyboardController: { [weak self] in self })
        let hosting = UIHostingController(rootView: root)
        addChild(hosting)
        hosting.view.backgroundColor = .clear
        hosting.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hosting.view)
        NSLayoutConstraint.activate([
            hosting.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            hosting.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            hosting.view.topAnchor.constraint(equalTo: view.topAnchor),
            hosting.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        hosting.didMove(toParent: self)
        let height = view.heightAnchor.constraint(equalToConstant: 280)
        height.priority = .defaultHigh
        height.isActive = true
        keyboardHeight = height
        updateContext()
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let keyboardHeight, view.bounds.width > 0 else { return }
        let screenHeight = view.window?.windowScene?.screen.bounds.height ?? UIScreen.main.bounds.height
        let height = KeyboardLayoutMetrics.preferredHeight(keyboardWidth: view.bounds.width, screenHeight: screenHeight, rowCount: visibleRowCount, candidateBarHeight: candidateBarHeight)
        if abs(keyboardHeight.constant - height) >= 1 { keyboardHeight.constant = height }
    }
    func setVisibleRowCount(_ count: Int) {
        guard visibleRowCount != count else { return }
        visibleRowCount = count
        view.setNeedsLayout()
    }
    func setCandidateBarHeight(_ height: CGFloat) {
        guard abs(candidateBarHeight - height) >= 1 else { return }
        candidateBarHeight = height
        view.setNeedsLayout()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // Recreate only the decorative layer after the extension reappears.
        // The current composition and candidate navigation stay untouched.
        keyboardContext.appearanceRevision &+= 1
        Preferences.shared.applySharedSettings()
        if engine.inputMode != Preferences.shared.inputMode {
            engine.finishComposition(); deliver()
            engine.selectMode(Preferences.shared.inputMode)
        }
        engine.modeChanged()
        updateContext()
    }
    private func deliver(extraCommit: String = "") {
        guard !delivering else { return }
        delivering = true
        defer { delivering = false }
        primaryLanguage = engine.english ? "en-US" : engine.inputMode == .japanese ? "ja-JP" : Preferences.shared.simplified ? "zh-Hans" : "zh-Hant"
        let commit = engine.takeCommit() + extraCommit
        for action in compositionDelivery.actions(commit: commit, preedit: engine.snapshot.preedit) {
            switch action {
            case .mark(let text):
                textDocumentProxy.setMarkedText(text, selectedRange: NSRange(location: text.utf16.count, length: 0))
            case .unmark:
                textDocumentProxy.unmarkText()
            case .insert(let text):
                textDocumentProxy.insertText(text)
            }
        }
    }
    private func tryDoubleSpaceShortcut() -> Bool {
        let now = ProcessInfo.processInfo.systemUptime
        guard lastIdleSpaceTime > 0, now - lastIdleSpaceTime <= 0.32,
              let before = textDocumentProxy.documentContextBeforeInput,
              before.hasSuffix(" "), before.count >= 2 else { return false }
        let preceding = before.dropLast().last!
        guard preceding.isLetter || preceding.isNumber else { return false }
        lastIdleSpaceTime = 0
        textDocumentProxy.deleteBackward()
        let period = (engine.english || preceding.isASCII) ? ". " : "。"
        textDocumentProxy.insertText(period)
        if !engine.predictions.isEmpty { engine.updatePredictions(context: "") }
        return true
    }
    private func erase() {
        lastIdleSpaceTime = 0
        if engine.snapshot.preedit.isEmpty {
            textDocumentProxy.deleteBackward()
            if !engine.predictions.isEmpty { engine.updatePredictions(context: "") }
        } else {
            engine.key(0xff08); deliver()
        }
        updateContext()
    }
    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        NativeBackend.releaseInactiveLexicons(keeping: engine.inputMode)
    }
    override func textDidChange(_ textInput: UITextInput?) {
        guard !delivering else { return }
        if compositionDelivery.hasMarkedText,
           (textDocumentProxy.documentContextBeforeInput ?? "").isEmpty,
           (textDocumentProxy.documentContextAfterInput ?? "").isEmpty {
            engine.clear()
            compositionDelivery.reset()
        }
        updateContext()
    }
    private func updateContext() {
        if let id = MPKeyboardDocumentIdentifier(textDocumentProxy) {
            let changedDocument = documentID.map { $0 != id } ?? false
            if changedDocument {
                engine.clear(); compositionDelivery.reset()
            }
            if changedDocument || (documentID == nil && !compositionDelivery.hasMarkedText && engine.snapshot.preedit.isEmpty) {
                switch textDocumentProxy.keyboardType ?? .default {
                case .URL, .emailAddress, .asciiCapable, .asciiCapableNumberPad: engine.english = true
                default: engine.english = false
                }
            }
            documentID = id
        }
        let returnType = textDocumentProxy.returnKeyType ?? .default
        if keyboardContext.returnKey != returnType { keyboardContext.returnKey = returnType }
        let autoCap = CapitalizationState.shouldAutoCapitalize(
            rawType: (textDocumentProxy.autocapitalizationType ?? .sentences).rawValue,
            contextBeforeInput: textDocumentProxy.documentContextBeforeInput,
            english: engine.english
        )
        if keyboardContext.shouldAutoCapitalize != autoCap {
            keyboardContext.shouldAutoCapitalize = autoCap
        }
    }
    override func viewWillDisappear(_ animated: Bool) {
        if let id = MPKeyboardDocumentIdentifier(textDocumentProxy), documentID == id { engine.finishComposition(); deliver() }
        else { engine.clear(); compositionDelivery.reset() }
        super.viewWillDisappear(animated)
    }
}

// Candidates answer the finger immediately with a soft tinted capsule, so a
// tap reads as a choice before the text is committed.
struct CandidatePressStyle: ButtonStyle {
    var tint: Color
    var dark: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                Capsule().fill(tint.opacity(dark ? 0.24 : 0.17))
                    .padding(.vertical, 5)
                    .opacity(configuration.isPressed ? 1 : 0)
                    .allowsHitTesting(false)
            }
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.95 : 1)
            .animation(.spring(duration: 0.18, bounce: 0.3), value: configuration.isPressed)
    }
}

struct InputModeButton: UIViewRepresentable {
    var controller: () -> UIInputViewController?
    var label = "切換鍵盤"
    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: "globe"), for: .normal)
        button.tintColor = .label
        button.accessibilityLabel = label
        if let controller = controller() {
            button.addTarget(controller, action: #selector(UIInputViewController.handleInputModeList(from:with:)), for: .allTouchEvents)
        }
        return button
    }
    func updateUIView(_ view: UIButton, context: Context) { view.accessibilityLabel = label }
}
