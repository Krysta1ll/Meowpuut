import UIKit

/// Scripted fingers for the lab app. Steps are separated by `;` and run on
/// the real touch surface (KeyboardKeysView), one after another:
///
///   d1:q        finger 1 comes down on the key labelled q
///   m1:w        finger 1 moves to the key labelled w
///   m1:+27,0    finger 1 moves 27 pt to the right of where it is
///   u1          finger 1 lifts where it is
///   c1          finger 1 is cancelled by the system
///   tap:q       down and up on q with finger 9
///   keys:nihao  one tap for each letter
///   w300        wait 300 ms
///   pick:0      choose the first candidate in the collapsed row
///   suggest:0   tap the first next word, or in English the first suggestion
///   expand      open the expanded candidates; collapse closes them
///   caret:-5    the user taps elsewhere in the field: the marked text
///               becomes plain text and the caret moves 5 UTF-16 units left
///   clear       the host empties the field (a chat app after Send)
///
/// Keys without a label are named shift, delete, space, return, symbols
/// (the page key), toggle (ABC / 拼) and globe. When the script ends, the
/// document and the keyboard's state are written to tmp/<resultFile>.
final class LabScript {
    private let keyboard: KeyboardViewController
    private var steps: [String]
    private let loadSeconds: Double
    private let resultFile: String?
    private var strokeSeconds: [Double] = []
    private var renderSeconds: [Double] = []
    private var layoutSeconds: [Double] = []
    private var errors: [String] = []

    init(keyboard: KeyboardViewController, steps: String, loadSeconds: Double, resultFile: String?) {
        self.keyboard = keyboard
        self.steps = steps.split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        self.loadSeconds = loadSeconds
        self.resultFile = resultFile
        // keys:abc becomes a tap for each character.
        self.steps = self.steps.flatMap { step -> [String] in
            guard step.hasPrefix("keys:") else { return [step] }
            return step.dropFirst(5).map { "tap:\($0)" }
        }
    }

    /// Starts once the engine has loaded, unless the script is meant to
    /// type while it is still loading.
    func start(early: Bool) {
        if early || keyboard.labReady { run() } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.02) { [self] in start(early: false) }
        }
    }

    func run() {
        guard !steps.isEmpty else { finish(); return }
        let step = steps.removeFirst()
        var pause = 0.03
        if step.hasPrefix("w"), let ms = Double(step.dropFirst()) {
            pause = ms / 1000
        } else if step.hasPrefix("tap:") {
            let name = String(step.dropFirst(4))
            touch(.down, finger: 9, target: name)
            // A short hold, then the lift, as a separate turn of the run loop.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) { [self] in
                touch(.up, finger: 9, target: nil)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) { [self] in run() }
            }
            return
        } else if step.hasPrefix("pick:"), let index = Int(step.dropFirst(5)) {
            keyboard.labChoose(index)
        } else if step.hasPrefix("suggest:"), let index = Int(step.dropFirst(8)) {
            keyboard.labPrediction(index)
        } else if step == "expand" {
            keyboard.labExpand(true)
        } else if step == "collapse" {
            keyboard.labExpand(false)
        } else if step.hasPrefix("caret:"), let offset = Int(step.dropFirst(6)) {
            keyboard.labDocument.adjustTextPosition(byCharacterOffset: offset)
        } else if step == "clear" {
            keyboard.labDocument.set(before: "", after: "")
        } else if let kind = step.first, "dmuc".contains(kind) {
            let rest = step.dropFirst()
            let parts = rest.split(separator: ":", maxSplits: 1).map(String.init)
            guard let finger = Int(parts.first ?? "") else { errors.append("bad step \(step)"); return next(pause) }
            let target = parts.count > 1 ? parts[1] : nil
            switch kind {
            case "d": touch(.down, finger: finger, target: target)
            case "m": touch(.move, finger: finger, target: target)
            case "u": touch(.up, finger: finger, target: target)
            default: touch(.cancel, finger: finger, target: nil)
            }
        } else {
            errors.append("unknown step \(step)")
        }
        next(pause)
    }

    private func next(_ pause: Double) {
        DispatchQueue.main.asyncAfter(deadline: .now() + pause) { [self] in run() }
    }

    private func point(_ target: String?, finger: Int) -> CGPoint? {
        let keys = keyboard.labKeys
        guard let target else { return keys.labPoint(finger: finger) }
        if target.hasPrefix("+") || target.hasPrefix("-") {
            let values = target.split(separator: ",").compactMap { Double($0) }
            guard values.count == 2, let from = keys.labPoint(finger: finger) else { return nil }
            return CGPoint(x: from.x + values[0], y: from.y + values[1])
        }
        if target.hasPrefix("@") {
            let values = target.dropFirst().split(separator: ",").compactMap { Double($0) }
            guard values.count == 2 else { return nil }
            return CGPoint(x: values[0], y: values[1])
        }
        return keys.labCenter(of: target)
    }

    private func touch(_ phase: KeyboardKeysView.LabTouchPhase, finger: Int, target: String?) {
        guard let point = point(target, finger: finger) else {
            if phase != .cancel { errors.append("no key \(target ?? "?") for finger \(finger)") }
            if phase == .cancel { keyboard.labKeys.labTouch(.cancel, finger: finger, at: .zero) }
            return
        }
        let started = CACurrentMediaTime()
        keyboard.labKeys.labTouch(phase, finger: finger, at: point)
        guard phase == .down || phase == .up else { return }
        let handled = CACurrentMediaTime()
        strokeSeconds.append(handled - started)
        // What the stroke costs to draw: SwiftUI update, layout and commit.
        keyboard.view.setNeedsLayout()
        keyboard.view.layoutIfNeeded()
        layoutSeconds.append(CACurrentMediaTime() - handled)
        CATransaction.flush()
        renderSeconds.append(CACurrentMediaTime() - handled)
    }

    private func finish() {
        // Wait for anything typed early, and for the last textDidChange.
        guard keyboard.labReady else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.02) { [self] in finish() }
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [self] in write() }
    }

    private func footprint() -> Int {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Int(info.phys_footprint) : 0
    }

    private func write() {
        let document = keyboard.labDocument
        let engine = keyboard.labEngine
        let sorted = strokeSeconds.sorted()
        let region = keyboard.labRegion
        let result: [String: Any] = [
            "text": document.text,
            "before": document.before,
            "marked": document.marked,
            "after": document.after,
            "preedit": engine.snapshot.preedit,
            "candidates": engine.snapshot.candidates,
            "japanese": engine.snapshot.japaneseCandidates,
            "compactJapanese": engine.snapshot.compactJapanese?.text ?? "",
            "highlighted": engine.snapshot.highlighted,
            "predictions": engine.predictions,
            "suggestions": keyboard.labSuggestions,
            "english": engine.english,
            "mode": engine.inputMode.rawValue,
            "shift": keyboard.labShift,
            "page": keyboard.labPage,
            "expanded": keyboard.labExpanded,
            "balloons": keyboard.labKeys.labVisibleBalloons,
            "touches": keyboard.labKeys.labActiveTouches,
            "errors": errors,
            "strokes": sorted.count,
            "strokeMs": strokeSeconds.map { ($0 * 100_000).rounded() / 100 },
            "renderMs": renderSeconds.map { ($0 * 100_000).rounded() / 100 },
            "layoutMs": layoutSeconds.map { ($0 * 100_000).rounded() / 100 },
            "lexiconHeapMB": Double(NativeBackend.loadedLexiconHeapBytes) / 1_048_576,
            "strokeMedianMs": sorted.isEmpty ? 0 : sorted[sorted.count / 2] * 1000,
            "strokeMaxMs": (sorted.last ?? 0) * 1000,
            "loadMs": loadSeconds * 1000,
            "footprintMB": Double(footprint()) / 1_048_576,
            "keyboardHeight": Double(keyboard.view.bounds.height),
            "regionHeight": Double(region.height),
            "width": Double(region.width)
        ]
        guard let name = resultFile, !name.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: result, options: [.sortedKeys, .prettyPrinted]) else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? data.write(to: url, options: .atomic)
    }
}
