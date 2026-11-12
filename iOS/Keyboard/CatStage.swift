import SwiftUI
import UIKit

// 米米莫 and 莫莫 on the candidate glass. Where each one sits comes from
// CatSprites.swift; this file draws them and moves them. Each cat is one
// picture; breathing and the reactions to a touch are Core Animation
// transforms on its layer, run by the render server, so the keyboard's own
// process draws nothing while they move. No frame sequences are loaded.

extension CatArt {
    /// The picture for a sprite, from the asset catalog. With `CatArt.sprite`
    /// this is where a cat's look comes from: a festival that brings its own
    /// cats returns its picture here.
    static func image(_ sprite: CatSprite) -> UIImage? { UIImage(named: sprite.name) }
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

/// The layers of the cats on one stage.
final class CatLayersView: UIView {
    struct Scene: Equatable {
        var places: [CatPlacement] = []
        var glass: CGRect = .zero
        var shape: GlassShape = .capsule
        var dark = false
        /// Paw shadows on the glass; off when the glass is drawn opaque.
        var shadows = true
    }

    private struct Figure {
        var place: CatPlacement
        /// The picture, and for a cat behind the bar its paws again in front of the glass.
        var sprites: [CALayer]
    }

    private var scene = Scene()
    private var figures: [Figure] = []
    private var breathing = false
    private var lean: CGFloat = 0
    private var pressed: KeyboardCat?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        backgroundColor = .clear
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError("not used") }

    /// The glass's outline in this view, as CandidateGlassShape draws it.
    private func glassPath() -> CGPath {
        CandidateGlassShape(style: scene.shape).path(in: scene.glass).cgPath
    }

    func show(_ next: Scene) {
        guard next != scene else { return }
        scene = next
        layer.sublayers?.forEach { $0.removeFromSuperlayer() }
        figures = []
        let glass = glassPath()
        let bounds = CGRect(x: 0, y: 0, width: 4000, height: 400)
        // Paws go on after every body, so one cat's body never cuts into the other's paws.
        var front: [CALayer] = []
        for place in next.places {
            guard let image = CatArt.image(place.sprite)?.cgImage else { continue }
            func sprite() -> CALayer {
                let sprite = CALayer()
                sprite.contents = image
                sprite.contentsGravity = .resize
                sprite.minificationFilter = .trilinear
                // Transforms turn about the paws, so they stay on the edge.
                sprite.anchorPoint = place.sprite.anchor
                sprite.frame = place.frame
                sprite.actions = ["transform": NSNull(), "opacity": NSNull()]
                return sprite
            }
            let body = sprite()
            if next.dark && place.cat == .mimimo {
                // Black on a dark keyboard: a soft warm light round his outline.
                body.shadowColor = UIColor(red: 1, green: 0.93, blue: 0.84, alpha: 1).cgColor
                body.shadowOpacity = 0.6
                body.shadowRadius = 2.2
                body.shadowOffset = .zero
            }
            guard place.sprite.behindBar else {
                layer.addSublayer(body)
                figures.append(Figure(place: place, sprites: [body]))
                continue
            }
            // The body shows only outside the glass: its flat cut is behind the edge, also where the edge curves.
            let outside = CALayer()
            outside.frame = bounds
            let hole = CAShapeLayer()
            let everything = CGMutablePath()
            everything.addRect(bounds)
            everything.addPath(glass)
            hole.frame = bounds
            hole.path = everything
            hole.fillRule = .evenOdd
            outside.mask = hole
            outside.addSublayer(body)
            layer.addSublayer(outside)
            if next.shadows {
                // Where a paw rests on the glass.
                let shadows = CALayer()
                shadows.frame = bounds
                let inside = CAShapeLayer()
                inside.frame = bounds
                inside.path = glass
                shadows.mask = inside
                for paw in place.sprite.paws {
                    let width = 2 * paw.halfWidth * place.frame.width * 1.25, depth = paw.depth * place.frame.height
                    let shadow = CAGradientLayer()
                    shadow.type = .radial
                    shadow.colors = [UIColor(red: 0.18, green: 0.11, blue: 0.09, alpha: next.dark ? 0.5 : 0.26).cgColor,
                                     UIColor(red: 0.18, green: 0.11, blue: 0.09, alpha: 0).cgColor]
                    shadow.startPoint = CGPoint(x: 0.5, y: 0.5)
                    shadow.endPoint = CGPoint(x: 1, y: 1)
                    shadow.frame = CGRect(x: place.frame.minX + paw.x * place.frame.width - width / 2, y: place.anchor.y + depth - 2.2,
                                          width: width, height: 5.5)
                    shadows.addSublayer(shadow)
                }
                layer.addSublayer(shadows)
            }
            // The paws again, in front of the glass.
            let paws = sprite()
            let shape = CAShapeLayer()
            let outline = CGMutablePath()
            place.sprite.pawOutlines(place.frame.size).forEach { outline.addEllipse(in: $0) }
            shape.frame = CGRect(origin: .zero, size: place.frame.size)
            shape.path = outline
            paws.mask = shape
            front.append(paws)
            figures.append(Figure(place: place, sprites: [body, paws]))
        }
        front.forEach(layer.addSublayer)
        applyRest()
        if breathing { breathing = false; setBreathing(true) }
    }

    /// Each cat's resting transform: its tilt, a lean toward the candidates
    /// while something is being typed, and a squash under a finger.
    private func rest(_ figure: Figure) -> CATransform3D {
        let place = figure.place
        let toward: CGFloat = place.anchor.x < scene.glass.midX ? 1 : -1
        var transform = CATransform3DMakeRotation((place.tilt + lean * 2 * toward) * .pi / 180, 0, 0, 1)
        if pressed == place.cat { transform = CATransform3DScale(transform, 1.06, 0.93, 1) }
        return transform
    }

    private func applyRest() {
        for figure in figures {
            for sprite in figure.sprites { sprite.transform = rest(figure) }
        }
    }

    private func animateRest() {
        for figure in figures {
            let target = rest(figure)
            for sprite in figure.sprites {
                let move = CABasicAnimation(keyPath: "transform")
                move.fromValue = sprite.presentation()?.transform ?? sprite.transform
                move.toValue = target
                move.duration = 0.22
                move.timingFunction = CAMediaTimingFunction(name: .easeOut)
                sprite.transform = target
                sprite.add(move, forKey: "rest")
            }
        }
    }

    /// `amount` is 1 while something is being typed.
    func setLean(_ amount: CGFloat, animated: Bool) {
        guard amount != lean else { return }
        lean = amount
        if animated { animateRest() } else { applyRest() }
    }

    func setPressed(_ cat: KeyboardCat?, animated: Bool) {
        guard cat != pressed else { return }
        pressed = cat
        if animated { animateRest() } else { applyRest() }
    }

    /// A slow breath: the body rises 1.8 % from the paws and settles, 5.2 s a
    /// cycle. The two cats breathe out of step.
    func setBreathing(_ on: Bool) {
        guard on != breathing else { return }
        breathing = on
        for (index, figure) in figures.enumerated() {
            // The body breathes; the paws in front stay where they are.
            guard let body = figure.sprites.first else { continue }
            guard on else { body.removeAnimation(forKey: "breath"); continue }
            // Added to the resting transform, so a tilt or a press shows through.
            let rise = CABasicAnimation(keyPath: "transform.scale.y")
            rise.fromValue = 0
            rise.toValue = 0.018
            rise.isAdditive = true
            let narrow = CABasicAnimation(keyPath: "transform.scale.x")
            narrow.fromValue = 0
            narrow.toValue = -0.006
            narrow.isAdditive = true
            let breath = CAAnimationGroup()
            breath.animations = [rise, narrow]
            breath.duration = 2.6
            breath.autoreverses = true
            breath.repeatCount = .infinity
            breath.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            breath.timeOffset = Double(index) * 1.7
            body.add(breath, forKey: "breath")
        }
    }

    /// 米米莫 ducks and shakes his head; 莫莫 nuzzles into the hand. Both
    /// turn about their paws and stay under their resting height while they
    /// turn: a keyboard cannot draw above its own top edge, and the ears are
    /// 1.5 pt under it. With `still` (Reduce Motion) the cat only dims for a
    /// moment.
    func react(_ cat: KeyboardCat, still: Bool) {
        for figure in figures where figure.place.cat == cat {
            for sprite in figure.sprites {
                if still {
                    let dim = CAKeyframeAnimation(keyPath: "opacity")
                    dim.values = [1, 0.72, 1]
                    dim.duration = 0.3
                    sprite.add(dim, forKey: "react")
                    continue
                }
                func track(_ path: String, _ values: [CGFloat], _ times: [Double], _ duration: Double) -> CAKeyframeAnimation {
                    let track = CAKeyframeAnimation(keyPath: path)
                    track.values = values
                    track.keyTimes = times.map { NSNumber(value: $0) }
                    track.duration = duration
                    track.timingFunctions = Array(repeating: CAMediaTimingFunction(name: .easeInEaseOut), count: values.count - 1)
                    // Added to the resting transform.
                    track.isAdditive = true
                    return track
                }
                let radians = CGFloat.pi / 180
                let group = CAAnimationGroup()
                if cat == .mimimo {
                    group.duration = 0.6
                    group.animations = [
                        track("transform.scale.y", [0, -0.10, -0.07, 0], [0, 0.13, 0.57, 1], 0.6),
                        track("transform.scale.x", [0, 0.07, 0.05, 0], [0, 0.13, 0.57, 1], 0.6),
                        track("transform.rotation.z", [0, -5 * radians, 4 * radians, -2 * radians, 0], [0, 0.2, 0.45, 0.7, 1], 0.6),
                    ]
                } else {
                    group.duration = 0.9
                    group.animations = [
                        track("transform.scale.y", [0, -0.08, -0.03, 0], [0, 0.1, 0.5, 1], 0.9),
                        track("transform.scale.x", [0, 0.05, 0.02, 0], [0, 0.1, 0.5, 1], 0.9),
                        track("transform.rotation.z", [0, 6 * radians, 5 * radians, 4 * radians, 0], [0, 0.2, 0.35, 0.5, 1], 0.9),
                    ]
                }
                sprite.add(group, forKey: "react")
            }
        }
    }
}

/// The cats in the candidate area. `pets` counts the touches each cat got;
/// a change plays its reaction. `active` is false while the keyboard is off
/// screen, and with Reduce Motion nothing moves.
struct CatStageView: UIViewRepresentable {
    var scene: CatLayersView.Scene
    var active: Bool
    var composing: Bool
    var pressed: KeyboardCat?
    var pets: [KeyboardCat: Int]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    final class Coordinator {
        var pets: [KeyboardCat: Int] = [:]
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> CatLayersView {
        context.coordinator.pets = pets
        return CatLayersView()
    }

    func updateUIView(_ view: CatLayersView, context: Context) {
        view.show(scene)
        let moving = active && !reduceMotion
        view.setLean(composing && !reduceMotion ? 1 : 0, animated: moving)
        view.setPressed(reduceMotion ? nil : pressed, animated: moving)
        view.setBreathing(moving)
        for cat in KeyboardCat.allCases where pets[cat] != context.coordinator.pets[cat] {
            if active { view.react(cat, still: reduceMotion) }
        }
        context.coordinator.pets = pets
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
