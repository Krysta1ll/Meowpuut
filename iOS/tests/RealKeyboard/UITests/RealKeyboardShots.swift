import XCTest

/// Takes the pictures listed in the plan file (REALKB_PLAN, JSON written by
/// scripts/ios-real-keyboard-shots.sh) into REALKB_OUT. Each entry:
///   name, keyboard (input mode identifier prefix), page (light/dark/colour),
///   host (accessory: a bar above the keyboard), landscape, taps (key or
///   button labels, in order), turn (landscape/portrait: rotate with the
///   keyboard open, after the taps), settings (written to REALKB_SETTINGS,
///   the keyboard's shared settings file, before launch).
final class RealKeyboardShots: XCTestCase {
    func env(_ name: String) -> String { ProcessInfo.processInfo.environment[name] ?? "" }

    func testShots() throws {
        let data = try Data(contentsOf: URL(fileURLWithPath: env("REALKB_PLAN")))
        let plan = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [[String: Any]])
        let out = env("REALKB_OUT")
        var log: [String] = []
        for shot in plan {
            let name = shot["name"] as? String ?? "shot"
            if let settings = shot["settings"], !env("REALKB_SETTINGS").isEmpty {
                try JSONSerialization.data(withJSONObject: settings).write(to: URL(fileURLWithPath: env("REALKB_SETTINGS")))
            }
            XCUIDevice.shared.orientation = shot["landscape"] as? Bool == true ? .landscapeLeft : .portrait
            let app = XCUIApplication()
            app.launchEnvironment["REALKB_KEYBOARD"] = shot["keyboard"] as? String ?? ""
            app.launchEnvironment["REALKB_PAGE"] = shot["page"] as? String ?? "light"
            app.launchEnvironment["REALKB_HOST"] = shot["host"] as? String ?? ""
            app.launch()
            XCTAssertTrue(app.textViews["field"].waitForExistence(timeout: 20))
            Thread.sleep(forTimeInterval: 2.5)
            for label in shot["taps"] as? [String] ?? [] {
                let candidates = [app.keys[label], app.buttons[label], app.descendants(matching: .any)[label]]
                guard let element = candidates.first(where: { $0.exists && $0.isHittable }) else {
                    log.append("\(name): no element \(label)")
                    continue
                }
                element.tap()
                Thread.sleep(forTimeInterval: 0.35)
            }
            if let turn = shot["turn"] as? String {
                XCUIDevice.shared.orientation = turn == "landscape" ? .landscapeLeft : .portrait
            }
            Thread.sleep(forTimeInterval: 1.2)
            try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(out)/\(name).png"))
            log.append("\(name): \(app.staticTexts["info"].label)")
            app.terminate()
        }
        XCUIDevice.shared.orientation = .portrait
        try log.joined(separator: "\n").write(toFile: "\(out)/shots-log.txt", atomically: true, encoding: .utf8)
    }
}
