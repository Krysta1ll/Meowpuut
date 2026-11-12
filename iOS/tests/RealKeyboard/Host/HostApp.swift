import UIKit

// Host for pictures of a real system keyboard: one text field over a page.
// REALKB_KEYBOARD picks the keyboard by input mode identifier prefix (the
// extension's bundle id, or e.g. zh_Hans-Pinyin), so the globe key is never
// needed. REALKB_PAGE is light, dark or colour: what shows through the
// keyboard's translucent backdrop. A label reports the keyboard frame.
// REALKB_HOST=accessory gives the field a bar above the keyboard, as chat
// apps have. REALKB_APPEARANCE=dark asks for a dark keyboard, as some apps do.

func env(_ name: String) -> String { ProcessInfo.processInfo.environment[name] ?? "" }

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication,
                     configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: nil, sessionRole: session.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = HostController()
        window.makeKeyAndVisible()
        self.window = window
    }
}

final class HostField: UITextView {
    override var textInputMode: UITextInputMode? {
        let wanted = env("REALKB_KEYBOARD")
        if !wanted.isEmpty, let mode = UITextInputMode.activeInputModes.first(where: { Self.identifier($0).hasPrefix(wanted) }) {
            return mode
        }
        return super.textInputMode
    }
    static func identifier(_ mode: UITextInputMode?) -> String {
        (mode?.value(forKey: "identifier") as? String) ?? ""
    }
}

final class HostController: UIViewController {
    let field = HostField()
    let info = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        let page = env("REALKB_PAGE")
        view.backgroundColor = page == "dark" ? .black : .white
        if page == "colour" {
            // Strong colours under the keyboard, to see what the backdrop lets through.
            let stripes = UIStackView(arrangedSubviews: [UIColor.systemRed, .systemOrange, .systemYellow, .systemGreen, .systemBlue, .systemPurple].map {
                let v = UIView(); v.backgroundColor = $0; return v
            })
            stripes.axis = .horizontal
            stripes.distribution = .fillEqually
            stripes.frame = view.bounds
            stripes.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(stripes)
        }
        field.font = .systemFont(ofSize: 20)
        field.backgroundColor = .clear
        field.textColor = page == "dark" ? .white : .black
        field.accessibilityIdentifier = "field"
        if env("REALKB_APPEARANCE") == "dark" { field.keyboardAppearance = .dark }
        field.autocorrectionType = .no
        field.translatesAutoresizingMaskIntoConstraints = false
        info.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
        info.textColor = .gray
        info.numberOfLines = 3
        info.accessibilityIdentifier = "info"
        info.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(field)
        view.addSubview(info)
        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            field.topAnchor.constraint(equalTo: g.topAnchor, constant: 8),
            field.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 12),
            field.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -12),
            field.heightAnchor.constraint(equalToConstant: 80),
            info.topAnchor.constraint(equalTo: field.bottomAnchor, constant: 4),
            info.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 12),
            info.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -12),
        ])
        if env("REALKB_HOST") == "accessory" {
            let bar = UILabel(frame: CGRect(x: 0, y: 0, width: 0, height: 48))
            bar.text = "  accessory"
            bar.backgroundColor = .systemGray5
            field.inputAccessoryView = bar
        }
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardFrame(_:)),
                                               name: UIResponder.keyboardDidChangeFrameNotification, object: nil)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        field.becomeFirstResponder()
    }

    @objc func keyboardFrame(_ note: Notification) {
        let frame = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect) ?? .zero
        let text = "keyboard \(HostField.identifier(field.textInputMode)) frame \(frame) screen \(view.window?.screen.bounds ?? .zero)"
        info.text = text
        info.accessibilityLabel = text
    }
}
