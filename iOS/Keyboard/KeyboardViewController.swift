import UIKit
import SwiftUI
import Combine

// UIKit only plays the system click when an enabling input view is visible and
// the user has enabled keyboard clicks in system settings.
final class KeyboardFeedbackInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

final class KeyboardContext {
    var returnKey: UIReturnKeyType = .default
    var shouldAutoCapitalize = false
    var isActionReturnKey: Bool {
        switch returnKey {
        case .search, .google, .yahoo, .send, .go, .done, .join, .next, .route, .continue, .emergencyCall:
            return true
        default:
            return false
        }
    }
}

struct KeyboardRootView: View {
    @ObservedObject var engine: Engine
    @ObservedObject var ui: KeyboardUIState
    let actions: KeyboardActions

    var body: some View {
        ZStack(alignment: .top) {
            KeyboardBackground(dark: ui.dark, glass: ui.region.glass.offsetBy(dx: ui.sideInset, dy: 0))
            VStack(spacing: 0) {
                CandidateRegionView(engine: engine, ui: ui, actions: actions)
                    .frame(height: ui.region.height)
                    .id(ui.appearanceRevision)
                if ui.expanded {
                    ExpandedCandidatesView(engine: engine, ui: ui, actions: actions)
                        .frame(height: ui.keysAreaHeight)
                        .transition(.opacity)
                }
                Spacer(minLength: 0)
            }
            .padding(.leading, ui.sideInset)
            .padding(.trailing, ui.trailingInset)
            .animation(UIAccessibility.isReduceMotionEnabled ? nil : .easeInOut(duration: 0.18), value: ui.expanded)
            if !engine.error.isEmpty {
                Text(engine.error)
                    .font(.system(size: 12)).foregroundStyle(.red)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Capsule().fill(Color(white: ui.dark ? 0.12 : 0.98)))
                    .padding(.top, 2)
                    .allowsHitTesting(false)
            }
        }
        .environment(\.colorScheme, ui.dark ? .dark : .light)
        // Candidates read left to right, also on a phone set to Arabic or Hebrew.
        .environment(\.layoutDirection, .leftToRight)
    }
}

// UITextChecker runs on its own queue: its first use loads the spelling data
// and every call opens the user dictionary, so no key waits for it.
private let spellingQueue = DispatchQueue(label: "app.meowpuut.spelling", qos: .userInitiated)
private let spellingChecker = UITextChecker()   // used on spellingQueue only

/// The checker's first guess when the word is misspelt, then completions.
private func spellingSuggestions(for word: String) -> [String] {
    var cells: [String] = []
    let range = NSRange(location: 0, length: word.utf16.count)
    if spellingChecker.rangeOfMisspelledWord(in: word, range: range, startingAt: 0, wrap: false, language: "en_US").location != NSNotFound,
       let guess = spellingChecker.guesses(forWordRange: range, in: word, language: "en_US")?.first {
        cells.append(guess)
    }
    for completion in spellingChecker.completions(forPartialWordRange: range, in: word, language: "en_US") ?? []
    where cells.count < 6 && completion.lowercased() != word.lowercased() && !cells.contains(completion) {
        cells.append(word.first!.isUppercase ? completion.prefix(1).uppercased() + completion.dropFirst() : completion)
    }
    return cells
}

final class KeyboardViewController: UIInputViewController, KeyboardKeysViewDelegate {
    // The engine starts without its lexicon; loadEngine parses it off the
    // main thread so the keys are on screen first. Settings from the app are
    // applied before the mode is read: they may change it, and the first
    // keys drawn and the lexicon parsed should both be for the right mode.
    private let engine: Engine = {
        Preferences.shared.applySharedSettings()
        return Engine(deferredMode: Preferences.shared.inputMode)
    }()
    private var engineLoading = false
    private var pendingInput: [() -> Void] = []
    private var appliedSettingsOnLoad = false
    private let keyboardContext = KeyboardContext()
    private let ui = KeyboardUIState()
    private let keysView = KeyboardKeysView()
    private var hosting: UIHostingController<KeyboardRootView>?
    private var heightConstraint: NSLayoutConstraint?
    /// When the view was first seen smaller than the height asked for.
    private var shortSince: TimeInterval?
    private var capitalization = CapitalizationState()
    private var shiftHeld = false
    private var page = 0
    private var documentID: UUID?
    private var delivering = false
    private var compositionDelivery = CompositionDelivery()
    /// The word the shown suggestions are for; the same word is not checked twice.
    private var checkedWord: String?
    /// The last insertion was a tapped suggestion and its space.
    private var suggestionSpace = false
    /// When this keyboard last wrote to the host.
    private var lastDeliveryTime: TimeInterval = 0
    /// The host has shown this keyboard's marked text in the text before the
    /// caret, so a context without it means something else changed the text.
    private var hostShowsMarkedText = false
    private var lastIdleSpaceTime: TimeInterval = 0
    private var observers = Set<AnyCancellable>()
    private var keySetContext: KeyboardKeyContext?
    private var keySet: KeyboardKeySet?
    private var festivalHour = -1
    private var festivalOutfit: FestivalOutfit?
    /// The day the cats' arrangement is for. It is read when the keyboard
    /// appears, so the cats never move while someone is typing.
    private var catDay = CatSchedule.day()
    private struct CatInputs: Equatable {
        var region: CandidateRegionLayout
        var shape: GlassShape
        var setting: String
        var day: Int
    }
    private var catInputs: CatInputs?
    private var t: InterfaceText { InterfaceText(simplified: Preferences.shared.simplified) }

    #if KEYBOARD_LAB
    // The lab app has no host text field: a document in memory stands in for
    // it, so scripted typing can be checked and shown.
    let labDocument = LabDocument()
    var labNeedsGlobe = false
    override var textDocumentProxy: UITextDocumentProxy { labDocument }
    override var needsInputModeSwitchKey: Bool { labNeedsGlobe }
    #endif

    override func loadView() {
        let keyboard = KeyboardFeedbackInputView(frame: .zero, inputViewStyle: .keyboard)
        inputView = keyboard
        view = keyboard
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        let hosting = UIHostingController(rootView: KeyboardRootView(engine: engine, ui: ui, actions: makeActions()))
        hosting.view.backgroundColor = .clear
        hosting.safeAreaRegions = []
        hosting.sizingOptions = []
        addChild(hosting)
        // Pinned with constraints, not an autoresizing mask: UIKit reports the
        // keyboard's height (the constraint below) to the host app only when a
        // subview of the input view takes part in Auto Layout. Without one the
        // host never hears of it and keeps its default height (216 pt on an
        // iPhone in portrait).
        hosting.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hosting.view)
        NSLayoutConstraint.activate([
            hosting.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            hosting.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            hosting.view.topAnchor.constraint(equalTo: view.topAnchor),
            hosting.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        hosting.didMove(toParent: self)
        self.hosting = hosting

        keysView.delegate = self
        keysView.frame = view.bounds
        view.addSubview(keysView)

        let height = view.heightAnchor.constraint(equalToConstant: 291)
        height.priority = UILayoutPriority(999)
        height.isActive = true
        heightConstraint = height

        Preferences.shared.objectWillChange
            .sink { [weak self] _ in DispatchQueue.main.async { self?.refreshAppearance() } }
            .store(in: &observers)
        for name in [UIAccessibility.darkerSystemColorsStatusDidChangeNotification,
                     UIAccessibility.boldTextStatusDidChangeNotification,
                     UIAccessibility.reduceTransparencyStatusDidChangeNotification] {
            NotificationCenter.default.publisher(for: name)
                .sink { [weak self] _ in self?.relayout() }
                .store(in: &observers)
        }
        registerForTraitChanges([UITraitUserInterfaceStyle.self, UITraitVerticalSizeClass.self,
                                 UITraitHorizontalSizeClass.self, UITraitUserInterfaceIdiom.self]) {
            (controller: KeyboardViewController, _: UITraitCollection) in
            controller.refreshAppearance()
        }
        #if KEYBOARD_LAB
        labDocument.onChange = { [weak self] in self?.textDidChange(nil) }
        #endif
        // The app's settings were applied when the engine was created.
        appliedSettingsOnLoad = true
        loadEngine(mode: Preferences.shared.inputMode)
        updateContext()
        refreshAppearance()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // Rebuild only the decorative layer after the extension reappears;
        // the composition and candidates stay as they were.
        ui.appearanceRevision &+= 1
        ui.active = true
        catDay = CatSchedule.day()
        #if KEYBOARD_LAB
        // `-catDay <n>` shows the arrangement of another day.
        if let day = UserDefaults.standard.string(forKey: "catDay").flatMap({ Int($0) }) { catDay = day }
        #endif
        shortSince = nil
        if appliedSettingsOnLoad { appliedSettingsOnLoad = false } else { Preferences.shared.applySharedSettings() }
        let wanted = Preferences.shared.inputMode
        if engine.isReady, engine.inputMode != wanted {
            // The mode was changed in the app. Let go of the old lexicon
            // before the new one is parsed, off the main thread; keys typed
            // meanwhile wait in pendingInput.
            engine.finishComposition(); deliver()
            engine.unload(for: wanted)
            NativeBackend.releaseInactiveLexicons(keeping: wanted)
            loadEngine(mode: wanted)
        } else if engine.isReady {
            engine.modeChanged()
        } else {
            // Still loading, or an earlier load failed: show the wanted
            // mode's keys, and try again if nothing is loading.
            if engine.inputMode != wanted { engine.unload(for: wanted) }
            if !engineLoading { loadEngine(mode: wanted) }
        }
        updateContext()
        refreshAppearance()
    }

    // MARK: Engine loading

    /// Parses the lexicon off the main thread and types a short reading into
    /// the new backend, so neither opening the keyboard nor the first key
    /// waits for it. Keys pressed before it is ready wait in `pendingInput`.
    private func loadEngine(mode: InputMode) {
        engineLoading = true
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let backend = try? Engine.makeBackend(mode: mode, warm: true)
            DispatchQueue.main.async { self?.engineLoaded(backend, mode: mode) }
        }
    }

    private func engineLoaded(_ backend: InputBackend?, mode: InputMode) {
        let wanted = Preferences.shared.inputMode
        if backend != nil, wanted != mode, !engine.isReady {
            // The mode was changed in the app while this one was loading.
            loadEngine(mode: wanted)
            return
        }
        engineLoading = false
        if !engine.isReady {
            if let backend { engine.install(backend, mode: mode) } else { engine.error = Engine.loadFailedMessage }
        }
        updateContext()
        refreshAppearance()
        let queued = pendingInput
        pendingInput = []
        queued.forEach { $0() }
    }

    /// Runs input now, or after the engine has loaded, in the order typed.
    private func whenReady(_ action: @escaping () -> Void) {
        if engineLoading || !pendingInput.isEmpty { pendingInput.append(action) } else { action() }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // The system's edge gestures delay touches near the screen edges,
        // which makes the bottom row and the outer keys feel late.
        view.window?.gestureRecognizers?.forEach { $0.delaysTouchesBegan = false }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        relayout()
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        relayout()
    }

    // MARK: Layout

    /// An iPad's floating keyboard is 320 pt wide and uses the phone layout.
    private func idiom(width: CGFloat) -> KeyboardIdiom {
        KeyboardIdiom.resolve(width: width, pad: traitCollection.userInterfaceIdiom == .pad,
                              compactHeight: traitCollection.verticalSizeClass == .compact)
    }

    private var keyContext: KeyboardKeyContext {
        let composing = !engine.snapshot.preedit.isEmpty
        let shift: KeyboardKeyContext.Shift = switch capitalization.mode {
        case .lowercase: shiftHeld ? .once : .off
        case .nextLetter: .once
        case .locked: .locked
        }
        let automatic = textDocumentProxy.enablesReturnKeyAutomatically ?? false
        return KeyboardKeyContext(mode: engine.inputMode, english: engine.english, page: page, shift: shift,
                                  simplified: Preferences.shared.simplified, needsGlobe: needsInputModeSwitchKey,
                                  spaceTitle: spaceTitle, returnTitle: composing ? t("確認", "确认") : returnTitle,
                                  returnAccent: composing || keyboardContext.isActionReturnKey,
                                  returnEnabled: composing || !automatic || textDocumentProxy.hasText,
                                  pad: idiom(width: view.bounds.width - view.safeAreaInsets.left - view.safeAreaInsets.right) == .pad,
                                  kaomoji: ui.kaomojiOpen)
    }

    private func relayout() {
        // A landscape phone has rounded corners and a sensor housing at the
        // sides. The keyboard stays inside the safe area on each side; on a
        // landscape iPhone both sides are equal, so it stays centred, and a
        // screen with a cutout on one side only loses room on that side.
        let insets = view.safeAreaInsets
        let left = insets.left.rounded(), right = insets.right.rounded()
        let width = view.bounds.width - left - right
        guard width > 0 else { return }
        let prefs = Preferences.shared
        var idiom = idiom(width: width)
        if ui.sideInset != left { ui.sideInset = left }
        if ui.trailingInset != right { ui.trailingInset = right }
        let keysFrame = CGRect(x: left, y: 0, width: width, height: view.bounds.height)
        if keysView.frame != keysFrame { keysView.frame = keysFrame }
        keysView.touchOutset = max(left, right)
        let rim = CandidateGlassShape.contentBand(prefs.glassShape, height: 1).top
        var region = CandidateRegionLayout(width: width, idiom: idiom, cats: prefs.catGlassSkin, rim: rim)
        let keySet = currentKeySet()
        // One height per input mode: Zhuyin's four rows of keys set it for its
        // symbol pages too, so switching pages never makes the keyboard jump.
        let baseRows = engine.inputMode == .zhuyin && !engine.english ? 5 : 4
        let base = KeyMetrics(idiom: idiom, width: width, rows: baseRows)
        var area = base.areaHeight(rows: baseRows)
        // The height constraint asks iOS for this height, and iOS gives it a
        // moment after the view is on screen; until then the view has the
        // system's default height. The keys are drawn at full size meanwhile.
        // Only a height that stays too small is fitted into: a smaller bar
        // and shorter keys, and in landscape the bar without the cats, so
        // every row stays above the system's globe bar.
        let wanted = (region.height + area).rounded()
        let room = (view.bounds.height - insets.bottom).rounded(.down)
        if view.window != nil, room > 0, wanted > room + 0.5 {
            let now = ProcessInfo.processInfo.systemUptime
            if shortSince == nil {
                shortSince = now
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.relayout() }
            }
            if now - (shortSince ?? now) >= 0.25 {
                if idiom == .phone {
                    idiom = .phoneLandscape
                    region = CandidateRegionLayout(width: width, idiom: idiom, cats: prefs.catGlassSkin, rim: rim)
                } else if idiom == .phoneLandscape {
                    region = CandidateRegionLayout(width: width, idiom: idiom, cats: false, rim: rim)
                }
                area = max(1, room - region.height)
            }
        } else {
            shortSince = nil
        }
        var metrics = KeyMetrics(idiom: idiom, width: width, rows: keySet.rowCount)
        metrics.keyHeight = max(1, (area - metrics.topPadding - metrics.bottomPadding
                                    - CGFloat(keySet.rowCount - 1) * metrics.rowGap) / CGFloat(keySet.rowCount)).rounded(.down)
        // What rounding leaves over goes under the last row, so its touch
        // area still ends at the bottom edge.
        metrics.bottomPadding += max(0, area - metrics.areaHeight(rows: keySet.rowCount))
        if ui.region != region { ui.region = region }
        if ui.keysAreaHeight != area { ui.keysAreaHeight = area }
        placeCats(region)
        let theme = KeyboardKeyTheme(dark: ui.dark, highContrast: UIAccessibility.isDarkerSystemColorsEnabled,
                                     palette: prefs.candidatePalette, bold: UIAccessibility.isBoldTextEnabled,
                                     opaque: UIAccessibility.isReduceTransparencyEnabled || UIAccessibility.isDarkerSystemColorsEnabled,
                                     liquid: LiquidGlass.available)
        keysView.configure(keySet: keySet, metrics: metrics, idiom: idiom, theme: theme, width: width, areaTop: region.height)
        if let heightConstraint, abs(heightConstraint.constant - wanted) >= 0.5 { heightConstraint.constant = wanted }
    }

    private func updateKeys() { relayout() }

    /// Today's arrangement on this bar, worked out again only when the bar,
    /// the shape, the setting or the day changes.
    private func placeCats(_ region: CandidateRegionLayout) {
        let prefs = Preferences.shared
        let inputs = CatInputs(region: region, shape: prefs.glassShape, setting: prefs.catPlacement, day: catDay)
        guard inputs != catInputs else { return }
        catInputs = inputs
        let places = region.cats
            ? CatSchedule.arrangement(setting: inputs.setting, day: catDay, on: region.stage(shape: inputs.shape))?.places ?? [] : []
        if ui.cats != places { ui.cats = places }
    }

    /// The keys for the current state. Most keystrokes change nothing here,
    /// so the last set is kept and compared by its inputs.
    private func currentKeySet() -> KeyboardKeySet {
        let context = keyContext
        if let keySet, keySetContext == context { return keySet }
        let built = KeyboardKeyBuilder.build(context)
        keySetContext = context
        keySet = built
        return built
    }

    /// Today's outfit, looked up at most once an hour. The bundled manifest
    /// is usually empty, and then there is nothing to look up.
    private func activeOutfit() -> FestivalOutfit? {
        guard Preferences.shared.festivalOutfits, !Festivals.bundled.isEmpty else { return nil }
        let hour = Int(Date().timeIntervalSinceReferenceDate / 3600)
        if hour != festivalHour {
            festivalHour = hour
            festivalOutfit = Festivals.active()
        }
        return festivalOutfit
    }

    private func refreshAppearance() {
        let prefs = Preferences.shared
        let dark: Bool = switch prefs.candidateTone {
        case .system: traitCollection.userInterfaceStyle == .dark || textDocumentProxy.keyboardAppearance == .dark
        case .light: false
        case .dark: true
        }
        if ui.dark != dark { ui.dark = dark }
        let outfit = activeOutfit()
        if ui.outfit != outfit { ui.outfit = outfit }
        relayout()
    }

    private func setExpanded(_ expanded: Bool) {
        guard ui.expanded != expanded else { return }
        let motion = !UIAccessibility.isReduceMotionEnabled
        // No withAnimation here: called from UIKit it flushes pending SwiftUI
        // updates before the new value is set, leaving the bar stale. The
        // root view animates on the value instead.
        ui.expanded = expanded
        if expanded {
            keysView.cancelAllTouches()
            keysView.isHidden = true
        } else {
            keysView.isHidden = false
            // Glass under a fading parent is drawn wrong: glass keys come back at once.
            if LiquidGlass.available { return }
            keysView.alpha = 0
            // The keys take touches while they fade in.
            UIView.animate(withDuration: motion ? 0.18 : 0, delay: 0, options: [.allowUserInteraction, .beginFromCurrentState]) {
                self.keysView.alpha = 1
            }
        }
    }

    /// The ^_^ list: kaomoji in the candidate row, all of them by group in the
    /// panel. Opening it commits what is being typed. It stays open while
    /// kaomoji are tapped and closes with the next key that types.
    private func setKaomoji(_ open: Bool) {
        guard open != ui.kaomojiOpen else { return }
        if open {
            engine.finishComposition(); deliver()
            if !engine.predictions.isEmpty { engine.updatePredictions(context: "") }
            updateContext()
            ui.kaomojiRow = Kaomoji.row(recent: Preferences.shared.rememberChoices ? Kaomoji.recent : [])
        } else {
            ui.kaomojiRow = []
            setExpanded(false)
        }
    }

    /// A kaomoji picked from the candidates counts as recently used in the ^_^ list.
    private func noteKaomoji(_ text: String) {
        if Kaomoji.contains(text) { Kaomoji.used(text) }
    }

    // MARK: Actions from the candidate area

    private func makeActions() -> KeyboardActions {
        KeyboardActions(
            chooseVisible: { [weak self] index in
                guard let self else { return }
                self.lastIdleSpaceTime = 0
                self.clearError()
                if self.engine.snapshot.candidates.indices.contains(index) {
                    self.noteKaomoji(self.engine.snapshot.candidates[index])
                    self.engine.choose(index)
                }
                else if index == self.engine.snapshot.candidates.count, self.engine.snapshot.compactJapanese != nil {
                    self.engine.chooseCompactJapanese()
                }
                self.deliver()
                self.updateContext()
                self.updateKeys()
            },
            choosePool: { [weak self] candidate in
                guard let self else { return }
                self.lastIdleSpaceTime = 0
                self.clearError()
                self.noteKaomoji(candidate.text)
                self.engine.chooseBackendCandidate(index: candidate.index, text: candidate.text)
                self.setExpanded(false)
                self.deliver()
                self.updateContext()
                self.updateKeys()
            },
            selectPrediction: { [weak self] index in
                guard let self else { return }
                if self.engine.english { self.chooseSuggestion(index); return }
                guard let text = self.engine.choosePrediction(index) else { return }
                self.noteKaomoji(text)
                self.insertText(text, keepPredictions: true)
            },
            insertSymbol: { [weak self] text in self?.whenReady { self?.insertText(text) } },
            insertKaomoji: { [weak self] text in
                self?.whenReady {
                    self?.insertText(text)
                    Kaomoji.used(text)
                }
            },
            toggleExpanded: { [weak self] in
                guard let self else { return }
                self.setExpanded(!self.ui.expanded)
            },
            switchScript: { [weak self] in
                // Petting 莫莫 switches script in place; the current reading is
                // kept and its candidates are rebuilt in the new script.
                Preferences.shared.simplified.toggle()
                if let self { self.engine.modeChanged(); self.deliver(); self.updateKeys() }
                return Preferences.shared.simplified
            },
            delete: { [weak self] word in self?.whenReady { self?.erase(word: word) } },
            pool: { [weak self] in self?.engine.candidates() ?? [] }
        )
    }

    // MARK: KeyboardKeysViewDelegate

    func keysView(_ view: KeyboardKeysView, keyDown key: KeyboardKey) {
        switch key.action {
        case .shift:
            capitalization.tapShift()
            shiftHeld = true
            updateKeys()
        case .page(let target):
            page = target
            updateKeys()
        default:
            break
        }
    }

    func keysView(_ view: KeyboardKeysView, commit key: KeyboardKey, alternate: String?) {
        whenReady { [weak self] in self?.commit(key, alternate: alternate) }
    }

    private func commit(_ key: KeyboardKey, alternate: String?) {
        let afterSuggestion = suggestionSpace
        suggestionSpace = false
        // Any key that types closes the kaomoji list; Delete is not one of them.
        if key.action != .kaomoji { setKaomoji(false) }
        switch key.action {
        case .kaomoji:
            setKaomoji(!ui.kaomojiOpen)
        case .engine(let code):
            if let alternate {
                _ = capitalization.consumeLetter(english: engine.english)
                insertText(alternate)
                break
            }
            // Shift raises letters in every letters layout. In English it
            // is plain text. In pinyin one Shift keeps the capital in the
            // reading (iPhone, Hello), and Caps Lock types capitals as they
            // are (NBA). In Japanese, capitals put katakana first. Zhuyin
            // keys have no case.
            let letters = engine.english || engine.inputMode != .zhuyin
            let upper = letters && (capitalization.isActive || shiftHeld)
            let locked = capitalization.mode == .locked
            _ = capitalization.consumeLetter(english: engine.english)
            var value = code
            if upper, let scalar = UnicodeScalar(UInt32(code)), let raised = String(scalar).uppercased().unicodeScalars.first {
                value = Int32(raised.value)
            }
            if upper, locked, !engine.english, engine.inputMode == .pinyin, let scalar = UnicodeScalar(UInt32(value)) {
                insertText(String(scalar))
            } else {
                type(value)
            }
        case .text(let text):
            // Japanese: ー goes into the open reading (ge, ー, mu is ゲーム), then back to letters.
            if alternate == nil, text == "ー", engine.inputMode == .japanese, !engine.english, !engine.snapshot.preedit.isEmpty {
                type(45)
                page = 0
                break
            }
            // The space a suggestion added goes before a mark (because.), as on iOS.
            if afterSuggestion, ".,?!:;".contains(alternate ?? text), textDocumentProxy.documentContextBeforeInput?.last == " " {
                textDocumentProxy.deleteBackward()
            }
            let previous = textDocumentProxy.documentContextBeforeInput?.last
            insertText(alternate ?? text)
            // A long-press variant follows its key's rule.
            if KeyboardSymbols.shouldAutoReturnToLetters(after: text, previous: previous) { page = 0 }
        case .space:
            type(32)
            if page > 0 { page = 0 }
        case .returnKey:
            // Pinyin: 確認 commits the letters as typed (nihao, iphone), as Apple's
            // does; space picks the first candidate. Zhuyin commits the highlighted one.
            let letters = engine.inputMode == .pinyin && !engine.english && engine.moduleBuffer == nil && !engine.snapshot.input.isEmpty
            type(0xff0d, modifiers: letters ? 1 : 0)
        case .toggleEnglish:
            page = 0
            type(Engine.toggleEnglishKey)
        case .dismiss:
            engine.finishComposition(); deliver()
            #if !KEYBOARD_LAB
            dismissKeyboard()
            #endif
        default:
            break
        }
        updateKeys()
    }

    func keysView(_ view: KeyboardKeysView, delete word: Bool) {
        whenReady { [weak self] in self?.erase(word: word) }
    }

    func keysView(_ view: KeyboardKeysView, shiftReleasedAfterTyping typed: Bool) {
        shiftHeld = false
        // Shift held while typing applies only to those letters.
        if typed && capitalization.mode != .locked { capitalization = CapitalizationState() }
        updateKeys()
    }

    func keysViewCanBeginCursor(_ view: KeyboardKeysView) -> Bool {
        engine.snapshot.preedit.isEmpty && engine.moduleBuffer == nil && pendingInput.isEmpty
    }

    func keysViewBeginCursor(_ view: KeyboardKeysView) {
        whenReady { [weak self] in
            guard let self else { return }
            self.engine.finishComposition()
            self.deliver()
            self.updateContext()
        }
    }

    func keysView(_ view: KeyboardKeysView, moveCursor offset: Int) {
        whenReady { [weak self] in
            guard let self else { return }
            let proxy = self.textDocumentProxy
            let units = CursorStep.utf16Offset(characters: offset, before: proxy.documentContextBeforeInput,
                                               after: proxy.documentContextAfterInput)
            proxy.adjustTextPosition(byCharacterOffset: units)
        }
    }

    func keysViewEndCursor(_ view: KeyboardKeysView) {
        updateContext()
        updateKeys()
    }

    func keysView(_ view: KeyboardKeysView, globe event: UIEvent) {
        #if !KEYBOARD_LAB
        handleInputModeList(from: keysView, with: event)
        #endif
    }

    func keysViewAdvanceInputMode(_ view: KeyboardKeysView) {
        #if !KEYBOARD_LAB
        advanceToNextInputMode()
        #endif
    }

    func keysView(_ view: KeyboardKeysView, returnToPage page: Int) {
        self.page = page
        updateKeys()
    }

    // MARK: Input

    /// The error line goes away with the next key or choice.
    private func clearError() {
        if !engine.error.isEmpty, engine.isReady { engine.error = "" }
    }

    private func type(_ code: Int32, modifiers: Int32 = 0) {
        clearError()
        if code == 32, modifiers == 0, !engine.english, engine.inputMode == .zhuyin,
           let last = engine.snapshot.input.last, " 6347".contains(last), !engine.snapshot.candidates.isEmpty {
            engine.finishComposition(); deliver(); updateContext(); return
        }
        let wasIdle = engine.snapshot.preedit.isEmpty && engine.moduleBuffer == nil
        if wasIdle, code == 32, modifiers == 0, tryDoubleSpaceShortcut() {
            updateContext()
            return
        }
        let handled = engine.key(code, modifiers: modifiers)
        deliver()
        if !handled && code == 0xff0d { textDocumentProxy.insertText("\n") }
        if !handled && code == 32 {
            textDocumentProxy.insertText(" ")
            lastIdleSpaceTime = ProcessInfo.processInfo.systemUptime
        } else if code == 32 && wasIdle && engine.english {
            lastIdleSpaceTime = ProcessInfo.processInfo.systemUptime
        } else {
            lastIdleSpaceTime = 0
        }
        if !handled, code > 32, code < 0xff00, let scalar = UnicodeScalar(UInt32(code)) {
            textDocumentProxy.insertText(String(scalar))
        }
        updateContext()
    }

    /// Finishes the composition and inserts text in one host transaction.
    private func insertText(_ text: String, keepPredictions: Bool = false) {
        lastIdleSpaceTime = 0
        engine.finishComposition()
        deliver(extraCommit: text)
        if !keepPredictions, !engine.predictions.isEmpty, !text.unicodeScalars.contains(where: { (0x3400...0x9fff).contains($0.value) }) {
            engine.updatePredictions(context: "")
        }
        updateContext()
        updateKeys()
    }

    private func deliver(extraCommit: String = "") {
        guard !delivering else { return }
        delivering = true
        defer { delivering = false }
        primaryLanguage = engine.english ? "en-US" : engine.inputMode == .japanese ? "ja-JP" : Preferences.shared.simplified ? "zh-Hans" : "zh-Hant"
        let commit = engine.takeCommit() + extraCommit
        var preedit = engine.snapshot.preedit
        if !engine.english, engine.moduleBuffer == nil {
            if engine.inputMode == .pinyin { preedit = SyllableBreaks.pinyin(preedit) }
            if engine.inputMode == .zhuyin { preedit = SyllableBreaks.zhuyin(preedit) }
        }
        let actions = compositionDelivery.actions(commit: commit, preedit: preedit)
        if !actions.isEmpty { lastDeliveryTime = ProcessInfo.processInfo.systemUptime }
        for action in actions {
            switch action {
            case .mark(let text):
                textDocumentProxy.setMarkedText(text, selectedRange: NSRange(location: text.utf16.count, length: 0))
            case .unmark:
                textDocumentProxy.unmarkText()
            case .insert(let text):
                textDocumentProxy.insertText(text)
            }
        }
        if engine.snapshot.preedit.isEmpty, !ui.kaomojiOpen { setExpanded(false) }
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

    private func erase(word: Bool) {
        lastIdleSpaceTime = 0
        suggestionSpace = false
        clearError()
        if engine.snapshot.preedit.isEmpty {
            if word { deleteWordBackward() } else { textDocumentProxy.deleteBackward() }
            if !engine.predictions.isEmpty { engine.updatePredictions(context: "") }
        } else {
            engine.key(0xff08)
            deliver()
        }
        updateContext()
        updateKeys()
    }

    /// Deletes trailing spaces and the word before them (a Chinese word by the
    /// system's word boundaries, not a single character).
    private func deleteWordBackward() {
        guard let before = textDocumentProxy.documentContextBeforeInput, !before.isEmpty else {
            textDocumentProxy.deleteBackward()
            return
        }
        var text = Substring(before)
        var count = 0
        while let last = text.last, last.isWhitespace { text.removeLast(); count += 1 }
        let rest = String(text)
        var word = rest.isEmpty ? 0 : 1
        rest.enumerateSubstrings(in: rest.startIndex..<rest.endIndex, options: [.byWords, .reverse]) { substring, range, _, stop in
            if let substring, range.upperBound == rest.endIndex { word = max(1, substring.count) }
            stop = true
        }
        for _ in 0..<max(1, count + word) { textDocumentProxy.deleteBackward() }
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        NativeBackend.releaseInactiveLexicons(keeping: engine.inputMode)
    }

    override func textDidChange(_ textInput: UITextInput?) {
        guard !delivering else { return }
        dropStaleComposition()
        updateContext()
        refreshAppearance()
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        guard !delivering else { return }
        dropStaleComposition()
    }

    /// The host changed the text or moved the caret under an open composition
    /// (a tap elsewhere in the field, paste, undo, Send emptying the field).
    /// The host has already turned the marked text into plain text, so the
    /// reading is dropped without writing anything; otherwise the next key
    /// would mark the whole reading again at the new caret.
    private func dropStaleComposition() {
        guard compositionDelivery.hasMarkedText else { return }
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput
        if let before, compositionDelivery.agrees(withContextBefore: before) {
            hostShowsMarkedText = true
            return
        }
        // Some hosts never report the marked text; there is nothing to compare then.
        guard hostShowsMarkedText else { return }
        // The host reports its text a little late. Right after a key the
        // context may still be the one from before it.
        let settled = ProcessInfo.processInfo.systemUptime - lastDeliveryTime > 0.3
        guard !proxy.hasText || (settled && before != nil) else { return }
        engine.clear()
        compositionDelivery.reset()
        setExpanded(false)
        updateKeys()
    }

    private func updateContext() {
        if let id = MPKeyboardDocumentIdentifier(textDocumentProxy) {
            let changedDocument = documentID.map { $0 != id } ?? false
            if changedDocument {
                // Another text field: start with nothing open.
                engine.clear(); compositionDelivery.reset()
                hostShowsMarkedText = false
                setKaomoji(false)
                setExpanded(false)
                capitalization = CapitalizationState()
            }
            if changedDocument || (documentID == nil && !compositionDelivery.hasMarkedText && engine.snapshot.preedit.isEmpty) {
                switch textDocumentProxy.keyboardType ?? .default {
                case .URL, .emailAddress, .asciiCapable, .asciiCapableNumberPad, .numberPad, .decimalPad: engine.english = true
                default: engine.english = false
                }
                page = numberField ? 1 : 0
            }
            documentID = id
            if changedDocument { updateKeys() }
        }
        updateSuggestions()
        keyboardContext.returnKey = textDocumentProxy.returnKeyType ?? .default
        let autoCap = CapitalizationState.shouldAutoCapitalize(
            rawType: (textDocumentProxy.autocapitalizationType ?? .sentences).rawValue,
            contextBeforeInput: textDocumentProxy.documentContextBeforeInput,
            english: engine.english
        )
        let before = capitalization
        keyboardContext.shouldAutoCapitalize = autoCap
        capitalization.applyAutoCapitalization(autoCap)
        if capitalization != before { updateKeys() }
    }

    /// Codes, amounts and postcodes: the keyboard opens on the digits page.
    private var numberField: Bool {
        switch textDocumentProxy.keyboardType ?? .default {
        case .numberPad, .decimalPad, .asciiCapableNumberPad, .numbersAndPunctuation: return true
        default: return false
        }
    }

    /// English: completions of the word before the caret and, when it is
    /// misspelt, the checker's first guess in front. UITextChecker needs no
    /// Full Access. Only a tap replaces the word; space and Return type as usual.
    private func updateSuggestions() {
        let word = engine.english ? wordBeforeCaret.flatMap { $0.isEmpty ? nil : $0 } : nil
        guard word != checkedWord else { return }
        checkedWord = word
        guard let word else {
            if !ui.suggestions.isEmpty { ui.suggestions = [] }
            return
        }
        spellingQueue.async { [weak self] in
            let cells = spellingSuggestions(for: word)
            DispatchQueue.main.async {
                // A result for a word no longer before the caret is dropped.
                guard let self, self.checkedWord == word, self.ui.suggestions != cells else { return }
                self.ui.suggestions = cells
            }
        }
    }

    /// The letters (and apostrophes) right before the caret.
    private var wordBeforeCaret: String? {
        textDocumentProxy.documentContextBeforeInput.map { String($0.reversed().prefix { $0.isASCII && ($0.isLetter || $0 == "'") }.reversed()) }
    }

    private func chooseSuggestion(_ index: Int) {
        guard ui.suggestions.indices.contains(index), let word = wordBeforeCaret else { return }
        lastIdleSpaceTime = 0
        for _ in word { textDocumentProxy.deleteBackward() }
        textDocumentProxy.insertText(ui.suggestions[index] + " ")
        suggestionSpace = true
        updateContext()
        updateKeys()
    }

    override func viewWillDisappear(_ animated: Bool) {
        keysView.cancelAllTouches()
        if let id = MPKeyboardDocumentIdentifier(textDocumentProxy), documentID == id { engine.finishComposition(); deliver() }
        else { engine.clear(); compositionDelivery.reset() }
        setKaomoji(false)
        setExpanded(false)
        // The keyboard comes back on letters, as the system keyboard does,
        // or on the digits in a number field.
        page = numberField ? 1 : 0
        if capitalization.mode == .locked { capitalization = CapitalizationState() }
        super.viewWillDisappear(animated)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        ui.active = false
    }

    #if KEYBOARD_LAB
    /// Types through the same path as the keys (lab app screenshots only).
    func labType(_ text: String) {
        for scalar in text.unicodeScalars { type(Int32(scalar.value)) }
        updateKeys()
    }
    /// Runs lab setup after the engine has loaded.
    func labWhenReady(_ action: @escaping () -> Void) { whenReady(action) }
    var labReady: Bool { !engineLoading && pendingInput.isEmpty }
    func labExpand(_ expanded: Bool = true) { setExpanded(expanded) }
    func labPage(_ page: Int) { self.page = page; updateKeys() }
    func labToggleEnglish() { type(Engine.toggleEnglishKey); updateKeys() }
    func labPress(_ label: String, alternates: Bool) { keysView.labPress(label: label, alternates: alternates) }
    func labChoose(_ index: Int) { makeActions().chooseVisible(index) }
    func labPrediction(_ index: Int) { makeActions().selectPrediction(index) }
    func labKaomoji(_ index: Int) { if ui.kaomojiRow.indices.contains(index) { makeActions().insertKaomoji(ui.kaomojiRow[index]) } }
    func labOpenKaomoji() { setKaomoji(true); updateKeys() }
    var labKaomojiRow: [String] { ui.kaomojiRow }
    var labSuggestions: [String] { ui.suggestions }
    func labRefresh() { updateContext(); updateKeys() }
    var labKeys: KeyboardKeysView { keysView }
    var labEngine: Engine { engine }
    var labShift: String {
        switch capitalization.mode { case .lowercase: return "off"; case .nextLetter: return "once"; case .locked: return "locked" }
    }
    var labPage: Int { page }
    var labExpanded: Bool { ui.expanded }
    var labRegion: CandidateRegionLayout { ui.region }
    var labCats: [CatPlacement] { ui.cats }
    #endif

    // MARK: Labels

    private var spaceTitle: String {
        let choose = t("選詞", "选词")
        if engine.snapshot.preedit.isEmpty { return engine.english ? "space" : "空格" }
        if engine.snapshot.preedit.hasPrefix("/") { return choose }
        if engine.inputMode == .pinyin { return engine.snapshot.candidates.isEmpty ? "原文＋空格" : choose }
        if engine.inputMode == .japanese { return choose }
        if let last = engine.snapshot.input.last, " 6347".contains(last) { return choose }
        return t("一聲", "一声")
    }

    private var returnTitle: String {
        switch keyboardContext.returnKey {
        case .search, .google, .yahoo: return t("搜尋", "搜索")
        case .send: return t("傳送", "发送")
        case .go: return "前往"
        case .done: return "完成"
        case .next: return "下一步"
        case .join: return "加入"
        case .route: return t("路線", "路线")
        case .emergencyCall: return t("緊急呼叫", "紧急呼叫")
        case .continue: return t("繼續", "继续")
        default: return t("換行", "换行")
        }
    }
}
