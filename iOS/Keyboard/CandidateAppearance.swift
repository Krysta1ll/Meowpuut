import SwiftUI
import UIKit

private struct CatAppearanceIdentity: Hashable {
    let appearanceRevision: Int
    let activationRevision: Int
}

// Draw the cats as vector shapes rather than Canvas surfaces. Canvas-backed
// details have disappeared after the keyboard extension resumes on device.
private struct CatFeature: Shape {
    enum Kind { case creamFace, muzzle, creamEyes, mouth, darkEyes, pupils, pupilGlints, whiskers }
    let kind: Kind

    func path(in rect: CGRect) -> Path {
        var path = Path()
        switch kind {
        case .creamFace:
            path.addEllipse(in: CGRect(x: 0, y: 0, width: 34, height: 27))
        case .muzzle:
            path.addEllipse(in: CGRect(x: 7, y: 6, width: 23, height: 16))
        case .creamEyes:
            for x in [10.0, 25.0] { path.addEllipse(in: CGRect(x: x, y: 8, width: 3, height: 6)) }
        case .mouth:
            path.move(to: CGPoint(x: 15, y: 17))
            path.addQuadCurve(to: CGPoint(x: 19, y: 16), control: CGPoint(x: 18, y: 19))
            path.addQuadCurve(to: CGPoint(x: 22, y: 18), control: CGPoint(x: 19, y: 19))
        case .darkEyes:
            for x in [3.0, 21.0] {
                path.move(to: CGPoint(x: x, y: 8))
                path.addLine(to: CGPoint(x: x + 10, y: x < 10 ? 11 : 4))
                path.addQuadCurve(to: CGPoint(x: x, y: 8), control: CGPoint(x: x + 8, y: 21))
                path.closeSubpath()
            }
        case .pupils:
            for x in [3.0, 21.0] { path.addEllipse(in: CGRect(x: x + 5, y: 6, width: 2.6, height: 8)) }
        case .pupilGlints:
            for x in [3.0, 21.0] { path.addEllipse(in: CGRect(x: x + 5.8, y: 8.6, width: 0.8, height: 1.3)) }
        case .whiskers:
            for y in [18.0, 22.0] {
                path.move(to: CGPoint(x: 0, y: y + 2))
                path.addQuadCurve(to: CGPoint(x: 7, y: y), control: CGPoint(x: 3.2, y: y + 0.9))
            }
        }
        return path.applying(CGAffineTransform(scaleX: rect.width / 34, y: rect.height / 28))
            .offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

private struct KeyboardCatFace: View {
    let cream: Bool
    var body: some View {
        ZStack {
            if cream {
                CatFeature(kind: .creamFace).fill(LinearGradient(colors: [Brand.paper, Color(red: 0.77, green: 0.69, blue: 0.61)], startPoint: .topLeading, endPoint: .bottomTrailing))
                CatFeature(kind: .muzzle).fill(Color(red: 0.62, green: 0.49, blue: 0.40).opacity(0.42))
                CatFeature(kind: .creamEyes).fill(Color(red: 0.26, green: 0.44, blue: 0.56))
                CatFeature(kind: .mouth).stroke(Color(white: 0.09), style: StrokeStyle(lineWidth: 1, lineCap: .round))
            } else {
                CatFeature(kind: .darkEyes).fill(LinearGradient(colors: [Brand.paper, Color(red: 0.88, green: 0.82, blue: 0.71)], startPoint: .topLeading, endPoint: .bottomTrailing))
                CatFeature(kind: .pupils).fill(LinearGradient(colors: [Color(red: 0.76, green: 0.61, blue: 0.29), Color(red: 0.43, green: 0.31, blue: 0.11)], startPoint: .top, endPoint: .bottom))
                    .clipShape(CatFeature(kind: .darkEyes))
                CatFeature(kind: .pupilGlints).fill(Brand.paper.opacity(0.65))
                    .clipShape(CatFeature(kind: .darkEyes))
                CatFeature(kind: .whiskers).stroke(Brand.paper.opacity(0.8), style: StrokeStyle(lineWidth: 0.9, lineCap: .round))
            }
        }.accessibilityHidden(true).allowsHitTesting(false)
    }
}

// A quiet edge on the ears separates dark fur from dark glass without
// changing the original silhouette or covering the candidate row.
private struct KeyboardDarkCatRim: Shape {
    func path(in rect: CGRect) -> Path {
        var edge = Path()
        edge.move(to: CGPoint(x: 4, y: 15))
        edge.addQuadCurve(to: CGPoint(x: 10, y: 1.8), control: CGPoint(x: 5, y: 2))
        edge.addLine(to: CGPoint(x: 20, y: 7.5))
        edge.addQuadCurve(to: CGPoint(x: 29, y: 1.8), control: CGPoint(x: 26, y: 0.8))
        edge.addQuadCurve(to: CGPoint(x: 35, y: 9), control: CGPoint(x: 32, y: 2))
        return edge.offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

private struct KeyboardCatEars: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width
        var ears = Path()
        ears.move(to: CGPoint(x: w - 36, y: 14))
        ears.addQuadCurve(to: CGPoint(x: w - 27, y: 1), control: CGPoint(x: w - 32, y: 1))
        ears.addLine(to: CGPoint(x: w - 19, y: 8))
        ears.addQuadCurve(to: CGPoint(x: w - 9, y: 2), control: CGPoint(x: w - 12, y: 1))
        ears.addQuadCurve(to: CGPoint(x: w - 5, y: 17), control: CGPoint(x: w - 5, y: 5))
        ears.closeSubpath()
        return ears.offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

private struct KeyboardCatTail: Shape {
    func path(in rect: CGRect) -> Path {
        let reach = min(94.0, rect.width * 0.32)
        var tail = Path()
        tail.move(to: CGPoint(x: reach, y: rect.height - 3))
        tail.addCurve(to: CGPoint(x: 17, y: rect.height - 5), control1: CGPoint(x: reach * 0.65, y: rect.height), control2: CGPoint(x: 35, y: rect.height - 2))
        tail.addQuadCurve(to: CGPoint(x: 22, y: rect.height - 11), control: CGPoint(x: 9, y: rect.height - 13))
        return tail.offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

private struct KeyboardGlassOutline: Shape {
    let cats: Bool
    func path(in rect: CGRect) -> Path {
        guard cats else { return Capsule().path(in: rect) }
        let w = rect.width, h = rect.height
        return Path { p in
            p.move(to: CGPoint(x: 2, y: 20))
            p.addQuadCurve(to: CGPoint(x: 6, y: 9), control: CGPoint(x: 2, y: 12))
            p.addQuadCurve(to: CGPoint(x: 10, y: 1), control: CGPoint(x: 6, y: 0))
            p.addLine(to: CGPoint(x: 20, y: 7))
            p.addQuadCurve(to: CGPoint(x: 29, y: 1), control: CGPoint(x: 26, y: 0))
            p.addQuadCurve(to: CGPoint(x: 35, y: 8), control: CGPoint(x: 32, y: 1))
            p.addLine(to: CGPoint(x: w - 34, y: 8))
            p.addQuadCurve(to: CGPoint(x: w - 28, y: 1), control: CGPoint(x: w - 32, y: 1))
            p.addLine(to: CGPoint(x: w - 19, y: 7))
            p.addQuadCurve(to: CGPoint(x: w - 9, y: 1), control: CGPoint(x: w - 11, y: 0))
            p.addQuadCurve(to: CGPoint(x: w - 5, y: 10), control: CGPoint(x: w - 5, y: 1))
            p.addQuadCurve(to: CGPoint(x: w - 2, y: h - 20), control: CGPoint(x: w - 2, y: 13))
            p.addQuadCurve(to: CGPoint(x: w - 22, y: h - 1), control: CGPoint(x: w - 2, y: h - 1))
            p.addLine(to: CGPoint(x: 22, y: h - 1))
            p.addQuadCurve(to: CGPoint(x: 2, y: 20), control: CGPoint(x: 2, y: h - 1))
            p.closeSubpath()
        }
    }
}

// Small highlights follow the quiet part of the silhouette, leaving the
// characters and the cats' faces unobstructed.
private struct KeyboardGlassEdgeLight: Shape {
    let cats: Bool
    func path(in rect: CGRect) -> Path {
        let left: CGFloat = cats ? 38 : min(rect.height / 2, rect.width / 4)
        let right = max(left, rect.width - left)
        let top: CGFloat = cats ? 9 : 1.5
        var path = Path()
        path.move(to: CGPoint(x: left, y: top))
        path.addLine(to: CGPoint(x: right, y: top))
        path.move(to: CGPoint(x: left, y: rect.height - 2))
        path.addLine(to: CGPoint(x: right, y: rect.height - 2))
        return path.offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

struct KeyboardCandidateGlass: ViewModifier {
    var cats: Bool
    var dark: Bool
    var strength: GlassStrength
    var tint: Color
    var appearanceRevision: Int
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.scenePhase) private var scenePhase
    @State private var activationRevision = 0

    private var glass: Glass {
        guard !reduceTransparency, contrast != .increased else { return .identity }
        return switch strength {
        case .soft: .regular.tint(tint.opacity(0.04))
        case .balanced: .clear.tint(tint.opacity(0.035))
        case .vivid: .clear.tint(tint.opacity(0.01))
        }
    }
    private var shade: Double {
        switch strength { case .soft: 0.40; case .balanced: 0.30; case .vivid: 0.18 }
    }
    func body(content: Content) -> some View {
        content
            // The system's automatic scroll-edge blur can cover candidate text
            // in this short horizontal strip; the glass belongs behind the text.
            .scrollEdgeEffectHidden(true, for: .all)
            .padding(.leading, cats ? 35 : 4)
            .padding(.trailing, cats ? 35 : 4)
            .background {
                KeyboardGlassOutline(cats: cats)
                    .fill((dark ? Color.black : Brand.paper).opacity(reduceTransparency || contrast == .increased ? 1 : shade))
                    .glassEffect(glass, in: KeyboardGlassOutline(cats: cats))
                    .overlay {
                        if !reduceTransparency && contrast != .increased {
                            // A restrained colour wash belongs to the material,
                            // behind the candidates rather than above their text.
                            KeyboardGlassOutline(cats: cats)
                                .fill(LinearGradient(colors: [Color.white.opacity(dark ? 0.075 : 0.18),
                                                              tint.opacity(dark ? 0.065 : 0.045),
                                                              Color.black.opacity(dark ? 0.08 : 0.025)],
                                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                        }
                    }
                    .allowsHitTesting(false)
            }
            .clipShape(KeyboardGlassOutline(cats: cats))
            .overlay {
                if cats {
                    GeometryReader { geometry in
                        ZStack {
                            // The dark coat turns gradually into glass, ending
                            // before the first candidate's text begins.
                            LinearGradient(stops: [
                                .init(color: Color(white: 0.025).opacity(0.98), location: 0),
                                .init(color: Color(white: 0.045).opacity(0.96), location: 0.38),
                                .init(color: Color(white: 0.08).opacity(0.72), location: 0.66),
                                .init(color: .clear, location: 1)
                            ], startPoint: .leading, endPoint: .trailing)
                                .frame(width: 38).frame(maxWidth: .infinity, alignment: .leading)
                            KeyboardDarkCatRim().stroke(LinearGradient(colors: [.white.opacity(dark ? 0.25 : 0.32), .white.opacity(0.03)],
                                                                      startPoint: .topLeading, endPoint: .bottomTrailing),
                                                        style: StrokeStyle(lineWidth: 0.55, lineCap: .round, lineJoin: .round))
                            KeyboardCatEars().fill(LinearGradient(
                                colors: [Color(red: 0.43, green: 0.35, blue: 0.32).opacity(0.88), Brand.paper.opacity(0.22)],
                                startPoint: UnitPoint(x: 1, y: 1 / max(1, geometry.size.height)),
                                endPoint: UnitPoint(x: 1 - 23 / max(23, geometry.size.width), y: 23 / max(1, geometry.size.height))))
                            KeyboardCatFace(cream: false).frame(width: 29, height: 24).position(x: 18, y: geometry.size.height / 2 + 1)
                            KeyboardCatFace(cream: true).frame(width: 31, height: 25).position(x: geometry.size.width - 19, y: geometry.size.height / 2 + 1)
                            KeyboardCatTail().stroke((dark ? Color(white: 0.15) : Brand.paper).opacity(0.9), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                            if !dark {
                                KeyboardCatTail().trim(from: 0.9, to: 1)
                                    .stroke(Color(red: 0.39, green: 0.30, blue: 0.25), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            }
                        }.clipShape(KeyboardGlassOutline(cats: true))
                    }
                    .id(CatAppearanceIdentity(appearanceRevision: appearanceRevision, activationRevision: activationRevision))
                    .allowsHitTesting(false).accessibilityHidden(true)
                }
            }
            .overlay {
                // The outer rim and a narrow inner glint give the glass a
                // polished edge without introducing another blur layer.
                ZStack {
                    KeyboardGlassOutline(cats: cats)
                        .stroke(LinearGradient(colors: [Color.white.opacity(dark ? 0.45 : 0.82),
                                                        Color.white.opacity(dark ? 0.10 : 0.25),
                                                        Color.black.opacity(dark ? 0.22 : 0.16)],
                                               startPoint: .top, endPoint: .bottom), lineWidth: 0.7)
                    if !reduceTransparency && contrast != .increased {
                        KeyboardGlassEdgeLight(cats: cats)
                            .stroke(LinearGradient(colors: [.clear, Color.white.opacity(dark ? 0.30 : 0.48),
                                                            Color.white.opacity(dark ? 0.10 : 0.26), .clear],
                                                   startPoint: .leading, endPoint: .trailing),
                                    style: StrokeStyle(lineWidth: 0.7, lineCap: .round))
                    }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { activationRevision &+= 1 }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
                activationRevision &+= 1
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
                activationRevision &+= 1
            }
    }
}
