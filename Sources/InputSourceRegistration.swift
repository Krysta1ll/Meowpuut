import Carbon
import Foundation

/// Adds the input source to the running login session without a logout: register the bundle with
/// Text Input Sources, then enable the input modes where macOS allows it. Idempotent; a second call
/// finds everything registered and enabled and does nothing. The Studio calls it on first launch,
/// and `Meowpuut --register` calls it for the installer's postinstall.
enum InputSourceRegistration {
    static let bundleID = "app.meowpuut.inputmethod.Meowpuut"

    enum Outcome: Equatable {
        /// The bundle is not in an Input Methods folder, so macOS would not accept it.
        case wrongLocation
        /// Registered and every input mode is enabled (or was already).
        case enabled(modes: Int)
        /// Registered, but macOS kept some modes disabled; the user adds them in System Settings.
        case registered(modes: Int, enabled: Int)
        /// Registration failed or the system lists no sources: the logout instruction applies.
        case needsLogout(status: OSStatus)
    }

    /// Only `~/Library/Input Methods` and `/Library/Input Methods` are scanned by macOS.
    static func isInputMethodsFolder(_ url: URL) -> Bool {
        let folder = url.standardizedFileURL.deletingLastPathComponent().path
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        return folder == "/Library/Input Methods" || folder == home + "/Library/Input Methods"
    }

    @discardableResult
    static func register(bundleURL: URL, enableCapable: (TISInputSource) -> Bool = { _ in true }) -> Outcome {
        guard isInputMethodsFolder(bundleURL) else { return .wrongLocation }
        let status = TISRegisterInputSource(bundleURL as CFURL)
        guard status == noErr else { return .needsLogout(status: status) }
        let sources = sourceList()
        guard !sources.isEmpty else { return .needsLogout(status: noErr) }
        var enabled = 0
        for source in sources {
            if boolean(source, kTISPropertyInputSourceIsEnabled) { enabled += 1; continue }
            guard boolean(source, kTISPropertyInputSourceIsEnableCapable), enableCapable(source) else { continue }
            if TISEnableInputSource(source) == noErr { enabled += 1 }
        }
        return enabled == sources.count ? .enabled(modes: sources.count) : .registered(modes: sources.count, enabled: enabled)
    }

    static func sourceList() -> [TISInputSource] {
        let filter = [kTISPropertyBundleID as String: bundleID] as CFDictionary
        return TISCreateInputSourceList(filter, true)?.takeRetainedValue() as? [TISInputSource] ?? []
    }

    private static func boolean(_ source: TISInputSource, _ key: CFString) -> Bool {
        guard let value = TISGetInputSourceProperty(source, key) else { return false }
        return CFBooleanGetValue(Unmanaged<CFBoolean>.fromOpaque(value).takeUnretainedValue())
    }

    /// Studio first launch: try once per installed version, quietly, so a user who removes the
    /// input source on purpose is not given it back at every launch.
    static func registerOnFirstLaunch(defaults: UserDefaults = .standard) {
        let url = Bundle.main.bundleURL
        guard isInputMethodsFolder(url) else { return }
        let version = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? ""
        guard defaults.string(forKey: "registeredInputSourceBuild") != version else { return }
        switch register(bundleURL: url) {
        case .enabled, .registered: defaults.set(version, forKey: "registeredInputSourceBuild")
        case .wrongLocation, .needsLogout: break
        }
    }

    /// `Meowpuut --register [path]`: prints the result and returns the exit code
    /// (0 registered, 1 registration failed so the user must log out, 2 not in an Input Methods folder).
    static func runCommandLine(_ arguments: [String]) -> Int32 {
        let rest = arguments.drop { $0 != "--register" }.dropFirst()
        let url = rest.first.map { URL(fileURLWithPath: $0) } ?? Bundle.main.bundleURL
        switch register(bundleURL: url) {
        case .wrongLocation:
            fputs("喵撲不在「輸入法」資料夾裡，系統不會掃描它。\n", stderr); return 2
        case .enabled(let modes):
            print("已註冊並啟用 Meowpuut 輸入來源（\(modes) 個）。"); return 0
        case .registered(let modes, let enabled):
            print("已註冊 Meowpuut（啟用 \(enabled)／\(modes) 個）；其餘請到系統設定 → 鍵盤 → 文字輸入加入。"); return 0
        case .needsLogout(let status):
            fputs("系統尚未接受輸入來源（OSStatus \(status)）。請登出再登入。\n", stderr); return 1
        }
    }
}
