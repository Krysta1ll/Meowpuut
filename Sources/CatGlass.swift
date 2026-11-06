import AppKit
import SwiftUI

// The outline is also the native glass boundary, including both pairs of ears.
private struct CatGlassOutline: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width
        let h = rect.height
        return Path { p in
            p.move(to: CGPoint(x: 4, y: 32))
            p.addQuadCurve(to: CGPoint(x: 8, y: 19), control: CGPoint(x: 4, y: 24))
            p.addCurve(to: CGPoint(x: 12, y: 3), control1: CGPoint(x: 7, y: 6), control2: CGPoint(x: 8, y: 1))
            p.addLine(to: CGPoint(x: 27, y: 12))
            p.addCurve(to: CGPoint(x: 39, y: 1), control1: CGPoint(x: 32, y: 10), control2: CGPoint(x: 35, y: -1))
            p.addQuadCurve(to: CGPoint(x: 48, y: 21), control: CGPoint(x: 43, y: 3))
            p.addCurve(to: CGPoint(x: w - 49, y: 22), control1: CGPoint(x: 65, y: 27), control2: CGPoint(x: w - 69, y: 24))
            p.addLine(to: CGPoint(x: w - 41, y: 9))
            p.addQuadCurve(to: CGPoint(x: w - 31, y: 20), control: CGPoint(x: w - 39, y: 7))
            p.addLine(to: CGPoint(x: w - 16, y: 11))
            p.addQuadCurve(to: CGPoint(x: w - 13, y: 30), control: CGPoint(x: w - 10, y: 9))
            p.addCurve(to: CGPoint(x: w - 2, y: h - 22), control1: CGPoint(x: w, y: 36), control2: CGPoint(x: w, y: h - 35))
            p.addQuadCurve(to: CGPoint(x: w - 24, y: h - 1), control: CGPoint(x: w, y: h))
            p.addLine(to: CGPoint(x: 25, y: h - 1))
            p.addQuadCurve(to: CGPoint(x: 2, y: h - 23), control: CGPoint(x: 0, y: h))
            p.closeSubpath()
        }
    }
}

private struct CandidateGlassOutline: Shape {
    var cats: Bool
    var expanded: Bool
    func path(in rect: CGRect) -> Path {
        cats ? CatGlassOutline().path(in: rect) : RoundedRectangle(cornerRadius: expanded ? 16 : rect.height / 2).path(in: rect)
    }
}

struct GlassCandidateView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.colorScheme) private var systemScheme
    @ObservedObject private var preferences = Preferences.shared
    @ObservedObject var engine: Engine
    var cats = true
    var expanded: Bool
    var canExpand: Bool
    var onChoose: (Int) -> Void
    var onPage: (Int32) -> Void
    var onToggle: () -> Void
    var onAppearanceChange: () -> Void = {}

    private var scheme: ColorScheme {
        switch preferences.candidateTone {
        case .system: systemScheme
        case .light: .light
        case .dark: .dark
        }
    }
    private var dark: Bool { scheme == .dark }
    private var tint: Color { preferences.candidatePalette.color(dark: dark) }
    private var glass: Glass {
        switch preferences.glassStrength {
        case .soft: .regular.tint(tint.opacity(0.04))
        case .balanced: .clear.tint(tint.opacity(0.035))
        case .vivid: .clear.tint(tint.opacity(0.01))
        }
    }
    private var shade: Double {
        switch preferences.glassStrength { case .soft: 0.40; case .balanced: 0.30; case .vivid: 0.18 }
    }
    var body: some View {
        CandidateStrip(engine: engine, onChoose: onChoose, onPage: onPage,
                       compact: true, expanded: expanded)
            .foregroundStyle(dark ? Color.white : Color(white: 0.12))
            .padding(.leading, cats ? (expanded ? 12 : 39) : 3)
            .padding(.trailing, cats ? (expanded ? 12 : 35) : (canExpand || expanded ? 25 : 3))
            .padding(.top, cats ? 23 : 3)
            .padding(.bottom, cats ? 8 : 6)
            .background {
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        CandidateGlassOutline(cats: cats, expanded: expanded)
                            .fill(dark ? Color.black.opacity(reduceTransparency || contrast == .increased ? 1 : shade)
                                       : Brand.paper.opacity(reduceTransparency || contrast == .increased ? 1 : shade + 0.02))
                        if cats {
                            // Fixed optical width: typing a longer word must not stretch the cat's shading.
                            LinearGradient(stops: [
                                .init(color: .black.opacity(0.90), location: 0),
                                .init(color: .black.opacity(0.78), location: 0.42),
                                .init(color: .black.opacity(0.24), location: 0.78),
                                .init(color: .clear, location: 1)
                            ], startPoint: .leading, endPoint: .trailing)
                            .frame(width: 64, height: geometry.size.height)
                        }
                    }
                    .clipShape(CandidateGlassOutline(cats: cats, expanded: expanded))
                }
            }
            .glassEffect(glass, in: CandidateGlassOutline(cats: cats, expanded: expanded))
            .overlay {
                if cats { GeometryReader { geometry in
                    let w = geometry.size.width
                    let h = geometry.size.height
                    Canvas { context, _ in
                        let tail = Path { p in
                            p.move(to: CGPoint(x: 28, y: h - 3))
                            p.addCurve(to: CGPoint(x: w - 25, y: h - 10),
                                       control1: CGPoint(x: 70, y: h - 15), control2: CGPoint(x: w - 73, y: h + 8))
                            p.addCurve(to: CGPoint(x: w - 15, y: 34),
                                       control1: CGPoint(x: w + 1, y: h - 28), control2: CGPoint(x: w - 7, y: 42))
                            p.addCurve(to: CGPoint(x: w - 5, y: h - 19),
                                       control1: CGPoint(x: w + 1, y: 49), control2: CGPoint(x: w - 1, y: h - 31))
                            p.addQuadCurve(to: CGPoint(x: w - 25, y: h - 2), control: CGPoint(x: w - 5, y: h - 2))
                            p.closeSubpath()
                        }
                        context.fill(tail, with: .linearGradient(
                            Gradient(colors: dark ? [Color(white: 0.08).opacity(0.70), Color(white: 0.16).opacity(0.06)] : [Brand.paper.opacity(reduceTransparency ? 1 : 0.40), Brand.paper.opacity(reduceTransparency ? 1 : 0.025)]),
                            startPoint: CGPoint(x: w, y: h), endPoint: CGPoint(x: 28, y: h - 9)))
                        context.stroke(tail, with: .color((dark ? Color(white: 0.65) : Brand.paper).opacity(0.20)), lineWidth: 0.45)
                    }
                    CatFace(cream: false).frame(width: 32, height: 26).position(x: 25, y: 31)
                    CatFace(cream: true).frame(width: 33, height: 27).position(x: w - 25, y: 32)
                }
                .clipShape(CatGlassOutline())
                .allowsHitTesting(false)
                .accessibilityHidden(true) }
            }
            .overlay {
                GeometryReader { geometry in
                    Canvas { context, size in
                        let h = size.height
                        let reach = min(cats ? 112.0 : 76.0, size.width * 0.38)
                        let curl = Path { p in
                            p.move(to: CGPoint(x: reach, y: h - 3.5))
                            p.addCurve(to: CGPoint(x: 20, y: h - 5), control1: CGPoint(x: reach * 0.65, y: h - 1.5), control2: CGPoint(x: 39, y: h - 2))
                            p.addCurve(to: CGPoint(x: 16, y: h - (cats ? 15 : 10)), control1: CGPoint(x: 10, y: h - 7), control2: CGPoint(x: 10, y: h - (cats ? 15 : 10)))
                            p.addQuadCurve(to: CGPoint(x: 23, y: h - (cats ? 12 : 8)), control: CGPoint(x: 22, y: h - (cats ? 17 : 12)))
                        }
                        let tailColor = dark ? Color(white: 0.12) : Brand.paper
                        context.stroke(curl, with: .color(tailColor.opacity(0.90)), style: StrokeStyle(lineWidth: cats ? 4 : 2.8, lineCap: .round))
                        context.stroke(curl, with: .linearGradient(Gradient(colors: [Color.white.opacity(dark ? 0.30 : 0.75), .clear]), startPoint: CGPoint(x: 14, y: h - 15), endPoint: CGPoint(x: reach, y: h)), style: StrokeStyle(lineWidth: 0.55, lineCap: .round))
                        if !dark {
                            context.stroke(curl.trimmedPath(from: 0.90, to: 1), with: .color(Color(red: 0.39, green: 0.30, blue: 0.25).opacity(0.75)), style: StrokeStyle(lineWidth: cats ? 3.4 : 2.4, lineCap: .round))
                        }
                    }
                    .clipShape(CandidateGlassOutline(cats: cats, expanded: expanded))
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            .overlay(alignment: .bottomTrailing) {
                if canExpand || expanded {
                    Button(action: onToggle) {
                        Image(systemName: "chevron.down")
                            .rotationEffect(.degrees(expanded ? 180 : 0))
                            .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: expanded)
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(dark ? Brand.paper.opacity(0.90) : Color(white: 0.25))
                            .frame(width: 25, height: 23)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, 5)
                    .padding(.bottom, 5)
                    .accessibilityLabel(expanded ? "收起候選" : "展開候選")
                }
            }
            .contentShape(CandidateGlassOutline(cats: cats, expanded: expanded))
            .contextMenu {
                Button(expanded ? "收起候選" : "展開候選", action: onToggle)
                    .disabled(!canExpand && !expanded)
                Divider()
                Menu("皮膚") {
                    Button("雙貓玻璃") { preferences.catGlassSkin = true; onAppearanceChange() }
                    Button("簡潔玻璃") { preferences.catGlassSkin = false; onAppearanceChange() }
                }
                Menu("色調") {
                    ForEach(CandidateTone.allCases) { tone in
                        Button { preferences.candidateTone = tone; onAppearanceChange() } label: {
                            if preferences.candidateTone == tone { Label(tone.title, systemImage: "checkmark") }
                            else { Text(tone.title) }
                        }
                    }
                }
                Menu("玻璃強度") {
                    ForEach(GlassStrength.allCases) { strength in
                        Button { preferences.glassStrength = strength; onAppearanceChange() } label: {
                            if preferences.glassStrength == strength { Label(strength.title, systemImage: "checkmark") }
                            else { Text(strength.title) }
                        }
                    }
                }
                Menu("色系") {
                    ForEach(CandidatePalette.allCases) { palette in
                        Button { preferences.candidatePalette = palette; onAppearanceChange() } label: {
                            if preferences.candidatePalette == palette { Label(palette.title, systemImage: "checkmark") }
                            else { Text(palette.title) }
                        }
                    }
                }
            }
            .environment(\.colorScheme, scheme)
            .fixedSize()
    }
}

private struct CatFace: View {
    let cream: Bool
    var body: some View {
        Canvas { context, size in
            context.scaleBy(x: size.width / 34, y: size.height / 28)
            let ink = Color(white: 0.09)
            if cream {
                context.fill(Path(ellipseIn: CGRect(x: 0, y: 0, width: 34, height: 27)),
                             with: .linearGradient(Gradient(colors: [Brand.paper, Color(red: 0.77, green: 0.69, blue: 0.61)]),
                                                   startPoint: .zero, endPoint: CGPoint(x: 34, y: 28)))
                context.fill(Path(ellipseIn: CGRect(x: 7, y: 6, width: 23, height: 16)),
                             with: .color(Color(red: 0.62, green: 0.49, blue: 0.40).opacity(0.42)))
                for x in [10.0, 25.0] {
                    context.fill(Path(ellipseIn: CGRect(x: x, y: 8, width: 3, height: 6)),
                                 with: .color(Color(red: 0.26, green: 0.44, blue: 0.56)))
                }
                var mouth = Path()
                mouth.move(to: CGPoint(x: 15, y: 17))
                mouth.addQuadCurve(to: CGPoint(x: 19, y: 16), control: CGPoint(x: 18, y: 19))
                mouth.addQuadCurve(to: CGPoint(x: 22, y: 18), control: CGPoint(x: 19, y: 19))
                context.stroke(mouth, with: .color(ink), style: StrokeStyle(lineWidth: 1.1, lineCap: .round))
            } else {
                for x in [3.0, 21.0] {
                    var eye = Path()
                    eye.move(to: CGPoint(x: x, y: 8))
                    eye.addLine(to: CGPoint(x: x + 10, y: x < 10 ? 11 : 4))
                    eye.addQuadCurve(to: CGPoint(x: x, y: 8), control: CGPoint(x: x + 8, y: 21))
                    context.fill(eye, with: .color(Brand.paper))
                    // The old pupil extended above its almond-shaped eye. Clip only the pupil,
                    // not the glass surface, so the lens keeps its native edge rendering.
                    var pupil = context
                    pupil.clip(to: eye)
                    pupil.fill(Path(ellipseIn: CGRect(x: x + 5, y: 6, width: 2.6, height: 8)),
                               with: .color(Color(red: 0.56, green: 0.42, blue: 0.16)))
                }
                for y in [18.0, 22.0] {
                    var whisker = Path()
                    whisker.move(to: CGPoint(x: 0, y: y + 2))
                    whisker.addLine(to: CGPoint(x: 7, y: y))
                    context.stroke(whisker, with: .color(Brand.paper.opacity(0.8)),
                                   style: StrokeStyle(lineWidth: 1, lineCap: .round))
                }
            }
        }
    }
}
