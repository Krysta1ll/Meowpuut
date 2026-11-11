import SwiftUI
import UIKit

// 米米莫 and 莫莫 on the candidate glass: sprites, expressions, petting.
// Every motion is event-driven (a blink timer, a pet, a commit), so an idle
// keyboard does not redraw continuously.

enum KeyboardCat: String {
    case mimimo, momo

    /// Cheek centres (fractions of the sprite) for the blush, from the eyes'
    /// positions in assets/brand/cats.
    var cheeks: [UnitPoint] {
        self == .mimimo ? [UnitPoint(x: 0.40, y: 0.62), UnitPoint(x: 0.71, y: 0.585)]
                        : [UnitPoint(x: 0.23, y: 0.595), UnitPoint(x: 0.585, y: 0.672)]
    }
}

enum CatFrame: String, CaseIterable { case open, happy, blink }

private struct CatMotion {
    var scale: CGFloat = 1
    var squash: CGFloat = 1
    var tilt: Double = 0
    var lift: CGFloat = 0
}

/// Outfit images exported by scripts/render_cats.py (@3x), cached.
enum FestivalArt {
    private static var cache: [String: UIImage] = [:]

    static func image(_ outfit: FestivalOutfit, _ file: String) -> UIImage? {
        let key = outfit.id + "/" + file
        if let hit = cache[key] { return hit }
        guard let url = Festivals.imageURL(outfit: outfit, file: file),
              let data = try? Data(contentsOf: url), let image = UIImage(data: data, scale: 3) else { return nil }
        cache[key] = image
        return image
    }
}

struct CatSpriteView: View {
    let cat: KeyboardCat
    var frame: CatFrame = .open
    var motion = 0
    var pressed = false
    var look: CGFloat = 0
    var blush: Double = 0
    var outfit: FestivalOutfit? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var layers: FestivalOutfit.Layers? { outfit?.layers(for: cat.rawValue) }

    private func image(_ frame: CatFrame) -> Image {
        if let outfit, let file = layers?.frames?[frame.rawValue], let art = FestivalArt.image(outfit, file) {
            return Image(uiImage: art)
        }
        return Image("\(cat.rawValue)_\(frame.rawValue)")
    }

    private func overlay(_ file: String?) -> Image? {
        guard let outfit, let file, let art = FestivalArt.image(outfit, file) else { return nil }
        return Image(uiImage: art)
    }

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            ZStack {
                if let back = overlay(layers?.back) { back.resizable().interpolation(.high) }
                // The open frame stays opaque underneath; the other frames
                // fade in over it. Fading it out as well would let the
                // glass show through the cat for the length of a blink.
                image(.open).resizable().interpolation(.high)
                ForEach([CatFrame.happy, .blink], id: \.self) { candidate in
                    image(candidate).resizable().interpolation(.high)
                        .opacity(frame == candidate ? 1 : 0)
                }
                if blush > 0 {
                    ForEach(Array(cat.cheeks.enumerated()), id: \.offset) { _, cheek in
                        Ellipse()
                            .fill(Color(red: 0.96, green: 0.52, blue: 0.60).opacity(blush * (cat == .mimimo ? 0.55 : 0.45)))
                            .frame(width: size.width * 0.15, height: size.width * 0.085)
                            .blur(radius: 1.4)
                            .position(x: size.width * cheek.x, y: size.height * cheek.y)
                    }
                }
                if let front = overlay(layers?.front) { front.resizable().interpolation(.high) }
            }
        }
        .animation(.easeInOut(duration: 0.08), value: frame)
        .animation(.easeOut(duration: 0.2), value: blush)
        .scaleEffect(x: pressed && !reduceMotion ? 1.06 : 1, y: pressed && !reduceMotion ? 0.93 : 1, anchor: .bottom)
        .brightness(pressed && reduceMotion ? 0.06 : 0)
        .animation(.spring(duration: 0.2, bounce: 0.4), value: pressed)
        // A slight lean toward the candidates while composing. 米米莫's ears
        // reach the keyboard's top edge, and nothing can be drawn above it,
        // so he also settles 2 % lower: the raised ear stays inside.
        .scaleEffect(y: cat == .mimimo && !reduceMotion ? 1 - 0.02 * look : 1, anchor: .bottom)
        .rotationEffect(.degrees(reduceMotion ? 0 : Double(look) * (cat == .mimimo ? 2.5 : -2.5)), anchor: .bottom)
        .animation(.easeOut(duration: 0.3), value: look)
        .keyframeAnimator(initialValue: CatMotion(), trigger: reduceMotion ? 0 : motion) { content, m in
            content
                .scaleEffect(x: m.scale / m.squash, y: m.scale * m.squash, anchor: .bottom)
                .rotationEffect(.degrees(m.tilt), anchor: .bottom)
                .offset(y: m.lift)
        } keyframes: { _ in
            // 米米莫 ducks and shakes his head; 莫莫 nuzzles slowly into the hand.
            // His ears end at the keyboard's top edge, and a keyboard cannot
            // draw above it, so his motion never rises over his resting
            // height: he stays ducked while his head turns and comes back up
            // without overshoot. 莫莫 has 6 pt above her and keeps a small lift.
            let mimimo = cat == .mimimo
            KeyframeTrack(\.squash) {
                if mimimo {
                    LinearKeyframe(0.90, duration: 0.08, timingCurve: .easeOut)
                    LinearKeyframe(0.93, duration: 0.26)
                    LinearKeyframe(1, duration: 0.24, timingCurve: .easeOut)
                } else {
                    CubicKeyframe(0.9, duration: 0.08)
                    SpringKeyframe(1, duration: 0.4, spring: .bouncy)
                }
            }
            KeyframeTrack(\.lift) {
                CubicKeyframe(0, duration: 0.08)
                CubicKeyframe(mimimo ? 0 : -1.5, duration: mimimo ? 0.12 : 0.16)
                SpringKeyframe(0, duration: mimimo ? 0.38 : 0.5, spring: .bouncy)
            }
            KeyframeTrack(\.tilt) {
                if mimimo {
                    LinearKeyframe(-5, duration: 0.10, timingCurve: .easeOut)
                    LinearKeyframe(4, duration: 0.12, timingCurve: .easeInOut)
                    LinearKeyframe(-2, duration: 0.11, timingCurve: .easeInOut)
                    LinearKeyframe(0, duration: 0.13, timingCurve: .easeOut)
                } else {
                    CubicKeyframe(6, duration: 0.18)
                    CubicKeyframe(5, duration: 0.12)
                    CubicKeyframe(4, duration: 0.12)
                    SpringKeyframe(0, duration: 0.45, spring: .smooth)
                }
            }
            KeyframeTrack(\.scale) {
                CubicKeyframe(1, duration: 0.08)
                CubicKeyframe(mimimo ? 1 : 1.03, duration: 0.12)
                SpringKeyframe(1, duration: 0.4, spring: .bouncy)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// A cat that blinks on its own: every 2.8 to 6.4 s for 120 ms, and now and
/// then 莫莫 blinks twice. The blink lives in this view, so it redraws the
/// sprite and nothing else in the candidate area.
struct LiveCatView: View {
    let cat: KeyboardCat
    var happy = false
    var motion = 0
    var pressed = false
    var look: CGFloat = 0
    var blush: Double = 0
    var outfit: FestivalOutfit? = nil
    @State private var blink = false

    var body: some View {
        CatSpriteView(cat: cat, frame: happy ? .happy : (blink ? .blink : .open), motion: motion,
                      pressed: pressed, look: look, blush: blush, outfit: outfit)
            .task { await live() }
    }

    private func live() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(Double.random(in: 2.8...6.4)))
            guard !Task.isCancelled else { return }
            await blinkOnce()
            if cat == .momo, Double.random(in: 0...1) < 0.25 {
                try? await Task.sleep(for: .milliseconds(180))
                await blinkOnce()
            }
        }
    }

    private func blinkOnce() async {
        blink = true
        try? await Task.sleep(for: .milliseconds(120))
        blink = false
    }
}

/// Reports its pressed state so the sprite can squash under the finger.
struct CatPetButtonStyle: ButtonStyle {
    var onPress: (Bool) -> Void
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .onChange(of: configuration.isPressed) { _, pressed in onPress(pressed) }
    }
}

struct CatParticle: Identifiable, Equatable {
    let id = UUID()
    let symbol: String
    let cat: KeyboardCat
    let size: CGFloat
    let drift: CGFloat
    let tint: Color
}

/// A small heart, paw or note that floats up from a cat and fades.
struct CatParticleView: View {
    let particle: CatParticle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var launched = false

    var body: some View {
        Image(systemName: particle.symbol)
            .font(.system(size: particle.size, weight: .bold))
            .foregroundStyle(particle.tint)
            .shadow(color: .black.opacity(0.18), radius: 1, y: 0.5)
            .scaleEffect(launched || reduceMotion ? 1 : 0.4)
            .rotationEffect(.degrees(launched && !reduceMotion ? Double(particle.drift) * 1.4 : 0))
            .offset(x: launched && !reduceMotion ? particle.drift : 0, y: launched && !reduceMotion ? -16 : -4)
            .opacity(launched ? 0 : 1)
            .onAppear { withAnimation(.easeOut(duration: 0.9)) { launched = true } }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

struct CatToast: Identifiable, Equatable {
    let id = UUID()
    let symbol: String
    let text: String
    var hearts: Int? = nil
    var emphasis = false
}

struct CatToastView: View {
    let toast: CatToast
    let dark: Bool
    let tint: Color

    var body: some View {
        HStack(spacing: 6) {
            if let hearts = toast.hearts {
                HStack(spacing: 2) {
                    ForEach(0..<CatAffection.petsToSwitch, id: \.self) { index in
                        Image(systemName: index < hearts ? "heart.fill" : "heart")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(index < hearts ? Color(red: 0.93, green: 0.42, blue: 0.50) : Color.secondary)
                    }
                }
            } else {
                Image(systemName: toast.symbol)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(toast.emphasis ? Color(red: 0.93, green: 0.42, blue: 0.50) : tint)
                    .symbolEffect(.bounce, value: toast.id)
            }
            Text(toast.text)
                .font(.system(size: 15, weight: toast.emphasis ? .semibold : .medium))
                .foregroundStyle(CandidateInk.text(dark: dark))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .padding(.horizontal, 13)
        .frame(height: 30)
        // A plain capsule: the candidate glass stays the keyboard's only glass.
        .background {
            Capsule().fill(dark ? Color(white: 0.16).opacity(0.96) : Color.white.opacity(0.96))
                .overlay(Capsule().fill(tint.opacity(toast.emphasis ? 0.14 : 0.06)))
                .overlay(Capsule().strokeBorder(Color.white.opacity(dark ? 0.12 : 0.7), lineWidth: 0.5))
        }
        .shadow(color: .black.opacity(dark ? 0.3 : 0.12), radius: 6, y: 2)
        .allowsHitTesting(false)
    }
}
