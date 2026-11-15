import SwiftUI
import AppKit

// Kami-Biyori pieces for the Studio window only (app page). The candidate
// window and everything it draws are not touched by any of this.
// Colours come from PaperTokens (assets/brand/paper/paper-tokens.json).

/// The fonts of the app pages, in one place. Studio window only; the candidate window and every
/// other surface keep the system font.
///
///   - Latin wordmark: Fraunces (SIL OFL 1.1), static 400 and 600 instances, Latin subset.
///   - Chinese titles and labels: jf open 粉圓 (SIL OFL 1.1, renamed "Meowpuut Paper Round"),
///     subset to the fixed Studio strings, Traditional and Simplified forms.
///
/// The files come from scripts/subset_app_fonts.py (Resources/Fonts) and are registered for this
/// process the first time a Studio view asks for a face, so nothing outside the Studio loads them.
/// A character outside the subset falls back to the system font by itself. Text the user wrote,
/// times and Simplified Chinese (粉圓 is a Traditional design) keep the system font.
enum PaperFont {
    static let roundName = "MeowpuutPaperRound-Regular"
    static let serifRegularName = "MeowpuutPaperSerif-Regular"
    static let serifSemiBoldName = "MeowpuutPaperSerif-SemiBold"
    private static let files = ["MeowpuutPaperRound", "MeowpuutPaperSerif-Regular", "MeowpuutPaperSerif-SemiBold"]

    /// True when the three faces are registered for this process.
    static let available: Bool = {
        let urls = files.compactMap { name in
            Bundle.main.url(forResource: name, withExtension: "ttf", subdirectory: "Fonts")
                ?? Bundle.main.url(forResource: name, withExtension: "ttf")
        }
        guard urls.count == files.count else { return false }
        // Already registered (a second Studio window, a preview run): the call reports an error we ignore.
        CTFontManagerRegisterFontsForURLs(urls as CFArray, .process, nil)
        return NSFont(name: roundName, size: 12) != nil && NSFont(name: serifSemiBoldName, size: 12) != nil
    }()

    private static func size(_ style: Font.TextStyle) -> CGFloat {
        switch style {
        case .largeTitle: return 26
        case .title: return 22
        case .title2: return 17
        case .title3: return 15
        case .headline, .body: return 13
        case .callout: return 12
        case .subheadline: return 11
        case .footnote, .caption: return 10
        case .caption2: return 10
        @unknown default: return 13
        }
    }

    private static func chinese(_ simplified: Bool) -> Bool { available && !simplified }

    static func wordmark(size: CGFloat) -> Font {
        available ? .custom(serifSemiBoldName, size: size) : .system(size: size, weight: .semibold, design: .serif)
    }

    static func title(_ style: Font.TextStyle = .largeTitle, simplified: Bool = InterfaceText.system.simplified) -> Font {
        chinese(simplified) ? .custom(roundName, size: size(style), relativeTo: style)
                            : .system(style, design: .rounded).weight(.semibold)
    }

    static func label(simplified: Bool = InterfaceText.system.simplified) -> Font {
        chinese(simplified) ? .custom(roundName, size: size(.caption), relativeTo: .caption)
                            : .system(.caption, design: .rounded).weight(.semibold)
    }

    /// The glyph in a stamp is one fixed Chinese character, so it takes the round face.
    static func stamp(size: CGFloat, simplified: Bool = InterfaceText.system.simplified) -> Font {
        chinese(simplified) ? .custom(roundName, size: size) : .system(size: size, weight: .bold, design: .serif)
    }
}

extension PaperPalette {
    /// The paper colours for the preferred colour system in the given appearance.
    static func current(_ palette: CandidatePalette, dark: Bool) -> PaperPalette {
        PaperTokens.palette(PaperTheme(rawValue: palette.rawValue) ?? .rose, dark: dark)
    }
}

/// Paper with a little grain. Solid paper (no grain) under Reduce Transparency or Increase Contrast.
struct PaperBackground: View {
    var palette: PaperPalette
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    // Drawn at half size (scale 2) so one 128 px tile covers 64 pt: finer grain, no blur on Retina.
    private static let grain: Image? = Bundle.main.url(forResource: PaperTokens.grainImageName, withExtension: "png")
        .flatMap { NSImage(contentsOf: $0) }
        .flatMap { $0.cgImage(forProposedRect: nil, context: nil, hints: nil) }
        .map { Image(decorative: $0, scale: 2) }

    var body: some View {
        let plain = reduceTransparency || contrast == .increased
        ZStack {
            if plain {
                palette.paper.color
            } else {
                LinearGradient(colors: [palette.paper.color, palette.paper2.color.opacity(0.55)], startPoint: .top, endPoint: .bottom)
                if let grain = Self.grain {
                    grain.renderingMode(.template).resizable(resizingMode: .tile)
                        .foregroundStyle(palette.grain.color)
                }
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

/// A group heading: ink strip, paper-coloured text.
struct LabelStrip: View {
    var title: String
    var palette: PaperPalette
    var simplified = InterfaceText.system.simplified
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        Text(title)
            .font(PaperFont.label(simplified: simplified))
            .foregroundStyle(palette.paper.color)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(palette.ink.color, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            .overlay {
                if contrast == .increased {
                    RoundedRectangle(cornerRadius: 4, style: .continuous).strokeBorder(palette.paper.color.opacity(0.6), lineWidth: 1)
                }
            }
            .accessibilityAddTraits(.isHeader)
    }
}

/// A dashed stitch line, 4 on / 3 off.
struct StitchDivider: View {
    var palette: PaperPalette
    var body: some View {
        Line()
            .stroke(palette.inkSecondary.color.opacity(0.7), style: StrokeStyle(lineWidth: 1, lineCap: .round, dash: [4, 3]))
            .frame(height: 1)
            .accessibilityHidden(true)
    }
    private struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            var p = Path(); p.move(to: CGPoint(x: rect.minX, y: rect.midY)); p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY)); return p
        }
    }
}

/// "Meowpuut" in Fraunces, with 喵撲 beside it.
struct PaperWordmark: View {
    var palette: PaperPalette
    var chinese: String
    var simplified = InterfaceText.system.simplified
    @ScaledMetric(relativeTo: .title2) private var size: CGFloat = 23

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Meowpuut").font(PaperFont.wordmark(size: size)).foregroundStyle(palette.ink.color)
                .lineLimit(1).minimumScaleFactor(0.6)
            Text(chinese).font(PaperFont.label(simplified: simplified)).foregroundStyle(palette.inkSecondary.color).lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Meowpuut " + chinese)
        .accessibilityAddTraits(.isHeader)
    }
}

/// One finished thing: a red seal and a sentence.
struct StampEvent: Equatable {
    let id = UUID()
    let glyph: String
    let message: String
}

struct StampToast: View {
    var event: StampEvent
    var palette: PaperPalette
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    var body: some View {
        HStack(spacing: 10) {
            Text(event.glyph)
                .font(PaperFont.stamp(size: 20)).foregroundStyle(palette.stamp.color)
                .frame(width: 36, height: 36)
                .overlay(RoundedRectangle(cornerRadius: 5, style: .continuous).strokeBorder(palette.stamp.color, lineWidth: 2))
                .rotationEffect(.degrees(shown || reduceMotion ? -2 : -3))
                .scaleEffect(shown || reduceMotion ? 1 : 1.12)
                .accessibilityHidden(true)
            Text(event.message).font(.callout).foregroundStyle(palette.ink.color)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(palette.paper.color, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(palette.ink.color.opacity(0.18)))
        .shadow(color: palette.shadowWarm.color, radius: 8, y: 3)
        .opacity(shown ? 1 : 0)
        .onAppear {
            // 140 ms scale-in; under Reduce Motion only a 120 ms fade.
            withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .easeOut(duration: 0.14)) { shown = true }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(event.message)
    }
}
