import SwiftUI
import UIKit

// Development app: shows the real keyboard controller under a message field,
// configured from launch arguments, for simulator screenshots and scripted
// typing. It is a separate target and never ships.
//
//   xcrun simctl launch booted com.krysta1ll.meowpuut.ios.lab \
//     -glassShape wave -candidateTone dark -inputMode pinyin -type sushi
//
// Preferences read UserDefaults, so `-glassShape`, `-candidateTone`,
// `-candidatePalette`, `-catGlassSkin`, `-simplified` and `-inputMode` apply
// directly. Lab-only:
//   -type <text>          type through the engine, without touches
//   -english YES          start in English
//   -page <1|2>           open a symbol page
//   -expand YES           open the expanded candidates
//   -kaomoji YES          open the ^_^ list (with -expand YES, its panel)
//   -commit YES           after -type, commit the first candidate (the row of next words)
//   -press <label>        hold a key (balloon); with -alternates YES, its long-press row
//   -document <a|b>       text before and after the cursor
//   -returnKey <name>     search, send, go, done, next
//   -keyboardType <url|number>
//   -autocapitalization <none|words|sentences|all>
//   -globe YES            show the Globe key
//   -festivalTest YES     read outfits from the LabFestivals folder
//   -landscape YES        start in landscape (the screenshot comes out turned)
//   -plain YES            the glass without the cats
//   -keyboardWidth <pt>   a narrower keyboard, centred (320: a small phone, or an iPad's floating keyboard)
//   -touchScript <steps>  scripted fingers on the real touch surface (LabScript)
//   -touchScriptEarly YES start the script while the lexicon is still loading
//   -resultFile <name>    write what the script produced to tmp/<name> as JSON

@main
struct KeyboardLabApp: App {
    @UIApplicationDelegateAdaptor(LabAppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup { LabScreen().ignoresSafeArea() }
    }
}

final class LabAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UserDefaults.standard.bool(forKey: "landscape") ? .landscapeRight : .portrait
    }
}

struct LabScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> LabViewController { LabViewController() }
    func updateUIViewController(_ controller: LabViewController, context: Context) {}
}

struct LabComposer: View {
    var before: String
    var marked: String
    var after: String
    var dark: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "plus")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(dark ? Color.white : Color(white: 0.25))
                .frame(width: 38, height: 38)
                .controlGlass(in: Circle())
            HStack(spacing: 0) {
                Text("\(Text(before))\(Text(marked).underline())").font(.system(size: 18)).lineLimit(1).truncationMode(.head)
                    .foregroundStyle(dark ? Color.white : Color.black)
                Rectangle().fill(Color.blue).frame(width: 2, height: 22).padding(.horizontal, 1)
                Text(after).font(.system(size: 18)).lineLimit(1)
                    .foregroundStyle(dark ? Color.white : Color.black)
                Spacer(minLength: 4)
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(.white, Color(red: 0.16, green: 0.47, blue: 0.98))
            }
            .padding(.leading, 14)
            .padding(.trailing, 4)
            .frame(height: 40)
            .controlGlass(in: Capsule())
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}

final class LabViewController: UIViewController {
    private var keyboard: KeyboardViewController!
    private var composer: UIHostingController<LabComposer>?
    private let floor = UIView()
    private var script: LabScript?
    private var loadSeconds = 0.0
    private var dark = false

    override func viewDidLoad() {
        super.viewDidLoad()
        let args = UserDefaults.standard
        if args.bool(forKey: "festivalTest") {
            Festivals.directoryOverride = Bundle.main.url(forResource: "LabFestivals", withExtension: nil)
        }
        dark = args.string(forKey: "candidateTone") == "dark"
        overrideUserInterfaceStyle = dark ? .dark : .light
        // Set on every launch, so one run does not carry over into the next.
        Preferences.shared.catGlassSkin = !args.bool(forKey: "plain")
        view.backgroundColor = dark ? UIColor(white: 0.04, alpha: 1) : UIColor(red: 0.96, green: 0.96, blue: 0.975, alpha: 1)

        // Stand-in for the system keyboard backdrop, which a real keyboard
        // gets from iOS and paints nothing over: behind the keyboard and the
        // strip under it where iOS draws the Globe and dictation keys.
        // Colours of the iOS 27 backdrop over a light page (Apple's Pinyin keyboard).
        floor.backgroundColor = dark ? UIColor(white: 0.216, alpha: 1)
                                     : UIColor(red: 0.886, green: 0.890, blue: 0.910, alpha: 1)
        floor.layer.cornerRadius = 28
        floor.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        floor.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(floor)

        // Loading the lexicon is the slow part of opening the keyboard.
        let started = CFAbsoluteTimeGetCurrent()
        keyboard = KeyboardViewController()
        loadSeconds = CFAbsoluteTimeGetCurrent() - started
        configure(keyboard.labDocument, args)
        keyboard.labNeedsGlobe = args.bool(forKey: "globe")

        addChild(keyboard)
        let keys = keyboard.view!
        keys.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(keys)
        keyboard.didMove(toParent: self)

        let composer = UIHostingController(rootView: LabComposer(before: "", marked: "", after: "", dark: dark))
        composer.view.backgroundColor = .clear
        composer.view.translatesAutoresizingMaskIntoConstraints = false
        addChild(composer)
        view.addSubview(composer.view)
        composer.didMove(toParent: self)
        self.composer = composer

        let narrow = args.double(forKey: "keyboardWidth")
        NSLayoutConstraint.activate(narrow > 0
            ? [keys.widthAnchor.constraint(equalToConstant: narrow), keys.centerXAnchor.constraint(equalTo: view.centerXAnchor)]
            : [keys.leadingAnchor.constraint(equalTo: view.leadingAnchor), keys.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
        NSLayoutConstraint.activate([
            keys.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            floor.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            floor.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            floor.topAnchor.constraint(equalTo: keys.topAnchor),
            floor.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            composer.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            composer.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            composer.view.bottomAnchor.constraint(equalTo: keys.topAnchor, constant: -6),
        ])

        let update = keyboard.labDocument.onChange
        keyboard.labDocument.onChange = { [weak self] in
            update?()
            self?.showDocument()
        }
    }

    private func configure(_ document: LabDocument, _ args: UserDefaults) {
        if let text = args.string(forKey: "document") {
            let parts = text.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            document.set(before: parts.first ?? "", after: parts.count > 1 ? parts[1] : "")
        }
        switch args.string(forKey: "returnKey") {
        case "search": document.returnKeyType = .search
        case "send": document.returnKeyType = .send
        case "go": document.returnKeyType = .go
        case "done": document.returnKeyType = .done
        case "next": document.returnKeyType = .next
        default: break
        }
        switch args.string(forKey: "autocapitalization") {
        case "none": document.autocapitalizationType = .none
        case "words": document.autocapitalizationType = .words
        case "all": document.autocapitalizationType = .allCharacters
        default: break
        }
        switch args.string(forKey: "keyboardType") {
        case "url": document.keyboardType = .URL
        case "number": document.keyboardType = .numberPad
        default: break
        }
        document.enablesReturnKeyAutomatically = args.bool(forKey: "returnAutomatic")
    }

    private func showDocument() {
        let document = keyboard.labDocument
        composer?.rootView = LabComposer(before: document.before, marked: document.marked, after: document.after, dark: dark)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        let args = UserDefaults.standard
        keyboard.labWhenReady { [self] in
            if args.bool(forKey: "english") { keyboard.labToggleEnglish() }
            keyboard.labRefresh()
            if let text = args.string(forKey: "type"), !text.isEmpty { keyboard.labType(text) }
            if args.bool(forKey: "commit") { keyboard.labChoose(0) }
            if args.bool(forKey: "kaomoji") { keyboard.labOpenKaomoji() }
            let page = args.integer(forKey: "page")
            if page > 0 { keyboard.labPage(page) }
            if args.bool(forKey: "expand") { keyboard.labExpand() }
            if let label = args.string(forKey: "press") { keyboard.labPress(label, alternates: args.bool(forKey: "alternates")) }
            showDocument()
        }
        if let steps = args.string(forKey: "touchScript"), !steps.isEmpty {
            let script = LabScript(keyboard: keyboard, steps: steps, loadSeconds: loadSeconds,
                                   resultFile: args.string(forKey: "resultFile"))
            self.script = script
            // `-touchScriptEarly YES` types at once, while the lexicon is
            // still loading; otherwise the first finger waits for the engine.
            let early = args.bool(forKey: "touchScriptEarly")
            DispatchQueue.main.asyncAfter(deadline: .now() + (early ? 0 : 0.4)) { script.start(early: early) }
        }
    }
}
