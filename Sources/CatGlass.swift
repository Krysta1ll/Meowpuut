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

struct CatCandidateView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @ObservedObject var engine: Engine
    var expanded: Bool
    var canExpand: Bool
    var onChoose: (Int) -> Void
    var onPage: (Int32) -> Void
    var onToggle: () -> Void

    var body: some View {
        CandidateStrip(engine: engine, onChoose: onChoose, onPage: onPage,
                       compact: true, expanded: expanded)
            .foregroundStyle(.white)
            .environment(\.colorScheme, .dark)
            .padding(.leading, expanded ? 12 : 39)
            .padding(.trailing, expanded ? 12 : 35)
            .padding(.top, 23)
            .padding(.bottom, 8)
            .background {
                CatGlassOutline()
                    .fill(LinearGradient(stops: [
                        .init(color: Color(white: 0.04).opacity(reduceTransparency ? 1 : 0.78), location: 0),
                        .init(color: Color(white: 0.10).opacity(reduceTransparency ? 1 : 0.32), location: 0.28),
                        .init(color: Color(white: 0.16).opacity(reduceTransparency ? 1 : 0.23), location: 0.82),
                        .init(color: Brand.paper.opacity(reduceTransparency ? 1 : 0.42), location: 1)
                    ], startPoint: .leading, endPoint: .trailing))
            }
            .glassEffect(.clear, in: CatGlassOutline())
            .overlay {
                GeometryReader { geometry in
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
                            Gradient(colors: [Brand.paper.opacity(0.95), Brand.paper.opacity(0.35)]),
                            startPoint: CGPoint(x: w, y: h), endPoint: CGPoint(x: 28, y: h - 9)))
                        context.stroke(tail, with: .color(Brand.paper.opacity(0.55)), lineWidth: 0.65)
                    }
                    CatFace(cream: false).frame(width: 34, height: 28).position(x: 25, y: 29)
                    CatFace(cream: true).frame(width: 33, height: 27).position(x: w - 25, y: 32)
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
                            .foregroundStyle(Color(white: 0.20))
                            .frame(width: 25, height: 23)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, 5)
                    .padding(.bottom, 5)
                    .accessibilityLabel(expanded ? "收起候選" : "展開候選")
                }
            }
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
                    context.fill(Path(ellipseIn: CGRect(x: x, y: 8, width: 3, height: 6)), with: .color(ink))
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
                    context.fill(Path(ellipseIn: CGRect(x: x + 5, y: 6, width: 2.6, height: 8)), with: .color(ink))
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
