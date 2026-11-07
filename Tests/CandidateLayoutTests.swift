import AppKit
import SwiftUI

@main enum CandidateLayoutTests {
    static func main() {
        let short = Snapshot(candidates: ["我", "握", "窩"])
        let shortLayout = CollapsedCandidateLayout(short)
        precondition(collapsedCandidateHeight(short, layout: shortLayout) == 33)

        let phrase = "今晚的月亮很美所以想和你一起散步回家然後聽一首喜歡的歌"
        let long = Snapshot(candidates: [phrase, "今晚", "今"], confidentCandidates: [phrase])
        let longLayout = CollapsedCandidateLayout(long)
        precondition(longLayout.indices.first == 0)
        precondition(collapsedCandidateHeight(long, layout: longLayout) > 33)
        precondition(collapsedCandidateShortcut(long, key: 49, modifiers: 0, shifted: false, english: false) == .choose(0))

        let huge = Snapshot(candidates: [String(repeating: phrase, count: 20)])
        precondition(collapsedCandidateHeight(huge, layout: CollapsedCandidateLayout(huge)) == 112)
        precondition(expandedCandidateHeight(huge.layoutCandidates) == 220)

        let screen = NSRect(x: 0, y: 0, width: 800, height: 600)
        for y: CGFloat in [0, 100, 290, 400, 580] {
            let caret = NSRect(x: 780, y: y, width: 2, height: 20)
            let available = candidatePanelAvailableHeight(anchor: caret, bounds: screen)
            let frame = candidateFrame(size: NSSize(width: 300, height: min(available, 220)), anchor: caret, bounds: screen)
            precondition(screen.contains(frame))
            precondition(!frame.intersects(caret))
        }
        _ = NSApplication.shared
        let engine = Engine(mode: .pinyin)
        engine.snapshot = huge
        for cats in [false, true] {
            for expanded in [false, true] {
                let view = GlassCandidateView(engine: engine, cats: cats, expanded: expanded, canExpand: true,
                    maxPanelHeight: 150, onChoose: { _ in }, onPage: { _ in }, onToggle: {})
                let hosting = NSHostingView(rootView: view)
                hosting.layoutSubtreeIfNeeded()
                precondition(hosting.fittingSize.height <= 150.5, "candidate panel exceeds available height")
            }
        }
        print("Candidate layout checks passed")
    }
}
