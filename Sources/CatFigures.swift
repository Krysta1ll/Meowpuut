import SwiftUI

// 米米莫 and 莫莫 drawn with SwiftUI on a stage (CatSprites.swift): the iOS
// app's settings preview and the macOS candidate window. The iOS keyboard
// draws the same picture with layers (iOS/Keyboard/CatStage.swift).

extension CatArrangement {
    /// The arrangement's name in the settings.
    func name(_ t: InterfaceText) -> String {
        switch id {
        case "ends": return t("兩端探頭", "两端探头")
        case "left": return t("左邊作伴", "左边作伴")
        case "right": return t("右邊作伴", "右边作伴")
        case "lounge": return t("兩端趴著", "两端趴着")
        case "rest": return t("兩端搭邊", "两端搭边")
        case "mimimoLounge": return t("米米莫趴著", "米米莫趴着")
        case "momoLounge": return t("莫莫趴著", "莫莫趴着")
        case "company": return t("趴著作伴", "趴着作伴")
        default: return id
        }
    }
}

/// Everything but the glass.
private struct OutsideGlass: Shape {
    var style: GlassShape
    var glass: CGRect

    func path(in rect: CGRect) -> Path {
        var path = Path(rect)
        path.addPath(CandidateGlassShape(style: style).path(in: glass))
        return path
    }
}

/// What of a sprite is in front of the glass (CatSprite.pawOutlines).
private struct PawsShape: Shape {
    var sprite: CatSprite

    func path(in rect: CGRect) -> Path {
        var path = Path()
        sprite.pawOutlines(rect.size).forEach { path.addEllipse(in: $0.offsetBy(dx: rect.minX, dy: rect.minY)) }
        return path
    }
}

struct CatFigures: View {
    var places: [CatPlacement]
    /// The stage's size; the glass rect is in the same coordinates.
    var size: CGSize
    var glass: CGRect
    var shape: GlassShape
    var dark: Bool
    /// Paw shadows on the glass; off when the glass is drawn opaque.
    var shadows = true
    /// A slow breath while the window is up and motion is allowed.
    var breathing = false
    /// The picture for a sprite: from the asset catalog on iOS, from the bundle on macOS.
    var picture: (CatSprite) -> Image
    @State private var breath = false

    private func sprite(_ place: CatPlacement, breathes: Bool) -> some View {
        let anchor = UnitPoint(x: place.sprite.anchor.x, y: place.sprite.anchor.y)
        return picture(place.sprite).resizable().interpolation(.high)
            .frame(width: place.frame.width, height: place.frame.height)
            .scaleEffect(x: breathes && breath ? 0.994 : 1, y: breathes && breath ? 1.018 : 1, anchor: anchor)
            .rotationEffect(.degrees(place.tilt), anchor: anchor)
    }

    /// The body, with the paw shadows under it; the paws in front come later.
    private func figure(_ place: CatPlacement) -> some View {
        // Black on a dark background: a soft warm light round 米米莫's outline.
        let glow = dark && place.cat == .mimimo ? Color(red: 1, green: 0.93, blue: 0.84).opacity(0.6) : Color.clear
        let body = sprite(place, breathes: true).shadow(color: glow, radius: 2.2)
            .position(x: place.frame.midX, y: place.frame.midY)
        return ZStack(alignment: .topLeading) {
            if place.sprite.behindBar {
                body.frame(width: size.width, height: size.height)
                    .mask(OutsideGlass(style: shape, glass: glass).fill(style: FillStyle(eoFill: true)))
                if shadows {
                    ForEach(Array(place.sprite.paws.enumerated()), id: \.offset) { _, paw in
                        let width = 2 * paw.halfWidth * place.frame.width * 1.25
                        Ellipse()
                            .fill(RadialGradient(colors: [Color(red: 0.18, green: 0.11, blue: 0.09).opacity(dark ? 0.5 : 0.26), .clear],
                                                 center: .center, startRadius: 0, endRadius: width / 2))
                            .frame(width: width, height: 5.5)
                            .position(x: place.frame.minX + paw.x * place.frame.width,
                                      y: place.anchor.y + paw.depth * place.frame.height + 0.55)
                    }
                    .frame(width: size.width, height: size.height)
                    .mask(CandidateGlassShape(style: shape).path(in: glass).fill())
                }
            } else {
                body
            }
        }
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(places, id: \.cat) { figure($0) }
            // Every cat's paws after every body, so one cat's body never cuts into the other's paws.
            ForEach(places.filter(\.sprite.behindBar), id: \.cat) { place in
                sprite(place, breathes: false).mask(PawsShape(sprite: place.sprite))
                    .position(x: place.frame.midX, y: place.frame.midY)
            }
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .onChange(of: breathing, initial: true) { _, on in
            withAnimation(on ? .easeInOut(duration: 2.6).repeatForever(autoreverses: true) : .easeOut(duration: 0.2)) { breath = on }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
