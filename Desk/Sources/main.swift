import AppKit

// 喵撲貓窩 (Meowpuut Desk): the desk-pet process of the three (FINAL.md §7.1). A login item (a LaunchAgent
// registered with SMAppService), never started by Steam and never calling the Steam API; it reads the ledger and
// the settings, draws the two clay cats on the desktop and listens for the input method's pulses (and tells it which
// cats are out). With 「時間花在哪裡」 on (FINAL.md §11, off by default) it also counts minutes per app category into
// Activity.bin and answers the companion app's question "what am I doing now" (ActivityService.swift).
//
//   Meowpuut Desk                       run (the copy launchd starts also listens for pulses)
//   Meowpuut Desk --register            register the login item and its Mach service, then exit
//   Meowpuut Desk --unregister          remove them, then exit
//   Meowpuut Desk --smoke               headless checks (scripts/build-desk.sh --test), then exit
//   --settings-dir DIR                  read settings.json (and PetJournal.bin), and keep Activity.bin, in DIR
//                                       instead of ~/Library/Application Support/Meowpuut
//   --run-seconds N                     quit after N seconds
//   --shots DIR                         with --run-seconds: snapshots of the cats and the world map into DIR

let arguments = CommandLine.arguments

func argument(after flag: String) -> String? {
    guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
    return arguments[index + 1]
}

if arguments.contains("--register") { exit(DeskAgent.run(register: true)) }
if arguments.contains("--unregister") { exit(DeskAgent.run(register: false)) }

final class DeskAppDelegate: NSObject, NSApplicationDelegate {
    var controller: DeskController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        if arguments.contains("--smoke") { exit(DeskSmoke.run()) }
        let agent = DeskAgent.launchedByLaunchd
        // One desk at a time. The login item's copy (the one with the pulses) wins: it asks a copy started by hand to
        // quit, and a copy started by hand leaves when another one runs or the login item is registered.
        let others = NSRunningApplication.runningApplications(withBundleIdentifier: DeskPulseChannel.deskIdentifier)
            .filter { $0.processIdentifier != getpid() }
        if agent {
            others.forEach { $0.terminate() }
        } else if !others.isEmpty || DeskAgent.registered {
            NSLog("喵撲貓窩 is already running (or registered as a login item)")
            exit(0)
        }
        let directory = argument(after: "--settings-dir").map { URL(fileURLWithPath: $0, isDirectory: true) } ?? DeskSettingsReader.defaultDirectory
        let controller = DeskController(options: .init(settingsDirectory: directory, listen: agent, statusItem: true))
        self.controller = controller
        controller.start()
        if let text = argument(after: "--run-seconds"), let seconds = Double(text) {
            let shots = argument(after: "--shots").map { URL(fileURLWithPath: $0, isDirectory: true) }
            if let shots {
                var tag = 0
                for at in stride(from: 2.0, to: seconds, by: max(2, seconds / 5)) {
                    DispatchQueue.main.asyncAfter(deadline: .now() + at) {
                        tag += 1
                        let names = DeskShots.capture(controller, to: shots, tag: String(format: "%02d-%02.0fs", tag, at))
                        let out = controller.core.output, now = DeskClock.monoMs
                        print("shots at \(Int(at)) s: \(names.joined(separator: ", ")); tier \(out.tier) frames \(controller.core.frames) "
                            + "hidden \(controller.core.isHidden) wake \(controller.core.hasWake ? "in \(Int64(out.wake_at_ms) - Int64(now)) ms" : "none") user \(out.user_state) steps \(controller.core.steps)")
                        for cat in DeskCat.allCases {
                            let c = controller.core.cat(cat)
                            print("  \(cat.asset): body \(DeskCore.name(MP_DESK_GROUP_BODY, c.body)) act \(DeskCore.name(MP_DESK_GROUP_ACT, c.act)) "
                                + "pose \(DeskCore.name(MP_DESK_GROUP_POSE, c.pose)) at (\(Int(c.x)), \(Int(c.y))) ground \(c.ground)")
                        }
                    }
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
                if let shots { DeskShots.capture(controller, to: shots, tag: "end") }
                print("steps \(controller.core.steps) by \(controller.reasons.sorted { $0.key < $1.key }), events taken \(controller.taken.sorted { $0.key < $1.key })")
                let a = controller.activity
                print("activity: \(a.switches.recording ? "on" : "off"), \(a.stepCount) tracker steps, state \(a.lastOut.map { String($0.state) } ?? "-"), "
                    + "category \(a.lastOut.flatMap { mp_activity_category_code($0.category) }.map { String(cString: $0) } ?? "-"), full screen \(a.fullScreen)")
                controller.stop()
                NSApp.terminate(nil)
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) { controller?.stop() }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let delegate = DeskAppDelegate()
app.delegate = delegate
app.run()
