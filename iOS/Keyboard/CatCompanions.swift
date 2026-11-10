import SwiftUI

// 米米莫 (left) and 莫莫 (right) are kawaii "mochi" mascots: chubby loaf
// silhouettes with soft plush shading, meant to be recognisable at a glance
// rather than realistic. 米米莫 is a black cat with a smug half-lidded stare;
// 莫莫 is a seal-point Siamese with a dark mask and blue eyes. Each has an
// open-eyed and a happy "^ ^" frame. The bar places one copy of a cat behind
// its Liquid Glass and one in front, masked, so the body sits inside the
// glass while the face comes out of it.
// Both copies are stateless views of the same expression and motion trigger,
// which keeps them in step. Every motion is event-driven (a blink timer, a
// pet, a commit) so an idle keyboard does not redraw continuously.

enum KeyboardCat {
    case mimimo, momo

    // Painted height in points; width follows the sprite's aspect ratio.
    var height: CGFloat { self == .mimimo ? 45 : 44 }
    var aspect: CGFloat { self == .mimimo ? 0.859 : 0.818 }   // from the keyed sprites
    var width: CGFloat { (height * aspect).rounded() }

    fileprivate var openImage: String { self == .mimimo ? "mimimo_open" : "momo_open" }
    fileprivate var happyImage: String { self == .mimimo ? "mimimo_happy" : "momo_happy" }
}

// What the cat is doing right now. Kept small so animation stays cheap.
struct CatExpression: Equatable {
    var happy = false        // eyes closed "^ ^" (also used for a blink)
    var look: CGFloat = 0    // −1 … 1: a slight lean toward the candidates
    var blush: Double = 0
}

// A one-shot body motion for each pet or nudge.
private struct CatMotion {
    var scale: CGFloat = 1
    var squash: CGFloat = 1
    var tilt: Double = 0
    var lift: CGFloat = 0
}

struct KeyboardCatSprite: View {
    let cat: KeyboardCat
    var expression = CatExpression()
    var motion = 0      // increments trigger one hop/nuzzle
    var pressed = false // squashed like a cushion under the finger
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Image(cat.openImage).resizable().interpolation(.high).scaledToFit()
                .opacity(expression.happy ? 0 : 1)
            Image(cat.happyImage).resizable().interpolation(.high).scaledToFit()
                .opacity(expression.happy ? 1 : 0)
        }
        .frame(width: cat.width, height: cat.height)
        .animation(.easeInOut(duration: 0.08), value: expression.happy)
        .scaleEffect(x: pressed && !reduceMotion ? 1.06 : 1, y: pressed && !reduceMotion ? 0.92 : 1, anchor: .bottom)
        .brightness(pressed && reduceMotion ? 0.06 : 0)
        .animation(.spring(duration: 0.2, bounce: 0.4), value: pressed)
        // Leaning toward the candidates while the user composes.
        .rotationEffect(.degrees(Double(expression.look) * (cat == .mimimo ? 2 : -2)), anchor: .bottom)
        .animation(.spring(duration: 0.4, bounce: 0.3), value: expression.look)
        .keyframeAnimator(initialValue: CatMotion(), trigger: reduceMotion ? 0 : motion) { content, m in
            content
                .scaleEffect(x: m.scale / m.squash, y: m.scale * m.squash, anchor: .bottom)
                .rotationEffect(.degrees(m.tilt), anchor: .bottom)
                .offset(y: m.lift)
        } keyframes: { _ in
            // 米米莫 hops and shakes his head; 莫莫 nuzzles slowly into the hand.
            let mimimo = cat == .mimimo
            KeyframeTrack(\.squash) {
                CubicKeyframe(0.9, duration: 0.08)
                SpringKeyframe(1, duration: 0.4, spring: .bouncy)
            }
            KeyframeTrack(\.lift) {
                CubicKeyframe(0, duration: 0.08)
                CubicKeyframe(mimimo ? -4 : -1.5, duration: mimimo ? 0.12 : 0.16)
                SpringKeyframe(0, duration: mimimo ? 0.38 : 0.5, spring: .bouncy)
            }
            KeyframeTrack(\.tilt) {
                CubicKeyframe(mimimo ? -6 : 7, duration: mimimo ? 0.1 : 0.18)
                CubicKeyframe(mimimo ? 5 : 5, duration: 0.12)
                CubicKeyframe(mimimo ? -2.5 : 4, duration: mimimo ? 0.11 : 0.12)
                SpringKeyframe(0, duration: mimimo ? 0.25 : 0.45, spring: mimimo ? .snappy : .smooth)
            }
            KeyframeTrack(\.scale) {
                CubicKeyframe(1, duration: 0.08)
                CubicKeyframe(mimimo ? 1.06 : 1.05, duration: 0.12)
                SpringKeyframe(1, duration: 0.4, spring: .bouncy)
            }
        }
        .overlay(alignment: .center) {
            // A soft blush wash over the cheeks while being petted.
            if expression.blush > 0 {
                HStack(spacing: cat.width * 0.34) {
                    ForEach(0..<2, id: \.self) { _ in
                        Ellipse().fill(Color(red: 0.96, green: 0.52, blue: 0.60).opacity(expression.blush * 0.45))
                            .frame(width: cat.width * 0.16, height: cat.width * 0.09).blur(radius: 1.4)
                    }
                }
                .offset(x: cat == .mimimo ? cat.width * 0.03 : -cat.width * 0.03, y: -cat.height * 0.02)
                .animation(.easeOut(duration: 0.2), value: expression.blush)
            }
        }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

// The touch zones are clear; the painted cats sit behind and in front of the
// glass. The style reports its pressed state so both copies can squash.
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

// A small heart, paw or note that floats up from the cat and fades.
struct CatParticleView: View {
    let particle: CatParticle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var launched = false

    var body: some View {
        Image(systemName: particle.symbol)
            .font(.system(size: particle.size, weight: .bold))
            .foregroundStyle(particle.tint)
            .shadow(color: .black.opacity(0.18), radius: 1, y: 0.5)
            .scaleEffect(launched ? 1 : 0.4)
            .rotationEffect(.degrees(launched ? Double(particle.drift) * 1.4 : 0))
            .offset(x: launched && !reduceMotion ? particle.drift : 0, y: launched && !reduceMotion ? -15 : -4)
            .opacity(launched ? 0 : 1)
            .onAppear {
                withAnimation(.easeOut(duration: 0.9)) { launched = true }
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

struct CatToast: Identifiable, Equatable {
    let id = UUID()
    let symbol: String
    let text: String
    var hearts: Int? = nil   // filled hearts while 莫莫 is being petted
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
                .font(.subheadline.weight(toast.emphasis ? .semibold : .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .padding(.horizontal, 13)
        .frame(height: 30)
        // A small Liquid Glass capsule with an opaque enough base to stay
        // legible over the candidates it briefly covers.
        .background {
            Capsule().fill(dark ? Color(white: 0.12).opacity(0.72) : Brand.paper.opacity(0.78))
                .overlay(Capsule().fill(tint.opacity(toast.emphasis ? 0.16 : 0.07)))
        }
        .glassEffect(.regular.tint(tint.opacity(toast.emphasis ? 0.22 : 0.10)), in: .capsule)
        .shadow(color: .black.opacity(dark ? 0.3 : 0.12), radius: 6, y: 2)
        .allowsHitTesting(false)
    }
}
