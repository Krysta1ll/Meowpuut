import SwiftUI

private struct KeyboardCatFace: View {
    let cream: Bool
    var body: some View {
        Canvas { context, size in
            context.scaleBy(x: size.width / 34, y: size.height / 28)
            if cream {
                context.fill(Path(ellipseIn: CGRect(x: 0, y: 0, width: 34, height: 27)),
                             with: .linearGradient(Gradient(colors: [Brand.paper, Color(red: 0.77, green: 0.69, blue: 0.61)]), startPoint: .zero, endPoint: CGPoint(x: 34, y: 28)))
                context.fill(Path(ellipseIn: CGRect(x: 7, y: 6, width: 23, height: 16)), with: .color(Color(red: 0.62, green: 0.49, blue: 0.40).opacity(0.42)))
                for x in [10.0, 25.0] {
                    context.fill(Path(ellipseIn: CGRect(x: x, y: 8, width: 3, height: 6)), with: .color(Color(red: 0.26, green: 0.44, blue: 0.56)))
                }
                var mouth = Path()
                mouth.move(to: CGPoint(x: 15, y: 17))
                mouth.addQuadCurve(to: CGPoint(x: 19, y: 16), control: CGPoint(x: 18, y: 19))
                mouth.addQuadCurve(to: CGPoint(x: 22, y: 18), control: CGPoint(x: 19, y: 19))
                context.stroke(mouth, with: .color(Color(white: 0.09)), style: StrokeStyle(lineWidth: 1.1, lineCap: .round))
            } else {
                for x in [3.0, 21.0] {
                    var eye = Path()
                    eye.move(to: CGPoint(x: x, y: 8))
                    eye.addLine(to: CGPoint(x: x + 10, y: x < 10 ? 11 : 4))
                    eye.addQuadCurve(to: CGPoint(x: x, y: 8), control: CGPoint(x: x + 8, y: 21))
                    context.fill(eye, with: .color(Brand.paper))
                    var pupil = context
                    pupil.clip(to: eye)
                    pupil.fill(Path(ellipseIn: CGRect(x: x + 5, y: 6, width: 2.6, height: 8)), with: .color(Color(red: 0.56, green: 0.42, blue: 0.16)))
                }
                for y in [18.0, 22.0] {
                    var whisker = Path()
                    whisker.move(to: CGPoint(x: 0, y: y + 2))
                    whisker.addLine(to: CGPoint(x: 7, y: y))
                    context.stroke(whisker, with: .color(Brand.paper.opacity(0.8)), style: StrokeStyle(lineWidth: 1, lineCap: .round))
                }
            }
        }.accessibilityHidden(true).allowsHitTesting(false)
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

struct KeyboardCandidateGlass: ViewModifier {
    var cats: Bool
    var dark: Bool
    var strength: GlassStrength
    var tint: Color
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

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
            .padding(.leading, cats ? 35 : 4)
            .padding(.trailing, cats ? 35 : 4)
            .background {
                KeyboardGlassOutline(cats: cats)
                    .fill((dark ? Color.black : Brand.paper).opacity(reduceTransparency || contrast == .increased ? 1 : shade))
                    .glassEffect(glass, in: KeyboardGlassOutline(cats: cats))
            }
            .clipShape(KeyboardGlassOutline(cats: cats))
            .overlay {
                if cats {
                    GeometryReader { geometry in
                        ZStack {
                            // The dark coat fades into the glass before the first candidate.
                            LinearGradient(colors: [.black.opacity(0.96), .black.opacity(0.82), .clear], startPoint: .leading, endPoint: .trailing)
                                .frame(width: 42).frame(maxWidth: .infinity, alignment: .leading)
                            Canvas { context, size in
                                var ears = Path()
                                ears.move(to: CGPoint(x: size.width - 36, y: 14))
                                ears.addQuadCurve(to: CGPoint(x: size.width - 27, y: 1), control: CGPoint(x: size.width - 32, y: 1))
                                ears.addLine(to: CGPoint(x: size.width - 19, y: 8))
                                ears.addQuadCurve(to: CGPoint(x: size.width - 9, y: 2), control: CGPoint(x: size.width - 12, y: 1))
                                ears.addQuadCurve(to: CGPoint(x: size.width - 5, y: 17), control: CGPoint(x: size.width - 5, y: 5))
                                ears.closeSubpath()
                                context.fill(ears, with: .linearGradient(
                                    Gradient(colors: [Color(red: 0.43, green: 0.35, blue: 0.32).opacity(0.88), Brand.paper.opacity(0.22)]),
                                    startPoint: CGPoint(x: size.width, y: 1), endPoint: CGPoint(x: size.width - 23, y: 23)))
                            }
                            KeyboardCatFace(cream: false).frame(width: 29, height: 24).position(x: 18, y: geometry.size.height / 2 + 1)
                            KeyboardCatFace(cream: true).frame(width: 31, height: 25).position(x: geometry.size.width - 19, y: geometry.size.height / 2 + 1)
                            Canvas { context, size in
                                let reach = min(94.0, size.width * 0.32)
                                var tail = Path()
                                tail.move(to: CGPoint(x: reach, y: size.height - 3))
                                tail.addCurve(to: CGPoint(x: 17, y: size.height - 5), control1: CGPoint(x: reach * 0.65, y: size.height), control2: CGPoint(x: 35, y: size.height - 2))
                                tail.addQuadCurve(to: CGPoint(x: 22, y: size.height - 11), control: CGPoint(x: 9, y: size.height - 13))
                                context.stroke(tail, with: .color((dark ? Color(white: 0.15) : Brand.paper).opacity(0.9)), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                                if !dark {
                                    context.stroke(tail.trimmedPath(from: 0.9, to: 1), with: .color(Color(red: 0.39, green: 0.30, blue: 0.25)), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                                }
                            }
                        }.clipShape(KeyboardGlassOutline(cats: true))
                    }.allowsHitTesting(false).accessibilityHidden(true)
                }
            }
            .overlay {
                // A fine rim separates the system glass from similarly coloured keycaps.
                KeyboardGlassOutline(cats: cats)
                    .stroke(LinearGradient(colors: [Color.white.opacity(dark ? 0.34 : 0.78),
                                                    Color.white.opacity(dark ? 0.08 : 0.24),
                                                    Color.black.opacity(dark ? 0.18 : 0.14)],
                                           startPoint: .top, endPoint: .bottom), lineWidth: 0.7)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
    }
}
