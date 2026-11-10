import SwiftUI
import UIKit

struct CatAppearanceIdentity: Hashable {
    let appearanceRevision: Int
    let activationRevision: Int
}

struct KeyboardGlassOutline: Shape {
    func path(in rect: CGRect) -> Path { Capsule().path(in: rect) }
}

// The cat skin's Liquid Glass bar spans the whole row, a little below the top
// edge; each cat sits inside one end of it.
struct KeyboardCatBar: InsettableShape {
    static let top: CGFloat = 8
    static let leading: CGFloat = 1
    var insetAmount: CGFloat = 0
    func path(in rect: CGRect) -> Path {
        let bar = CGRect(x: rect.minX + Self.leading, y: rect.minY + Self.top,
                         width: max(0, rect.width - Self.leading - 1), height: max(0, rect.height - Self.top - 1))
            .insetBy(dx: insetAmount, dy: insetAmount)
        return RoundedRectangle(cornerRadius: min(bar.height / 2, 21), style: .continuous).path(in: bar)
    }
    func inset(by amount: CGFloat) -> some InsettableShape {
        var copy = self
        copy.insetAmount += amount
        return copy
    }
}

// Clips the lower body to the rounded bottom of KeyboardCatBar while leaving
// the upper half open so the cats' ears and heads can rise above the top rim.
struct KeyboardCatClip: Shape {
    func path(in rect: CGRect) -> Path {
        var path = KeyboardCatBar().path(in: rect)
        path.addRect(CGRect(x: rect.minX - 8, y: rect.minY - 12,
                            width: rect.width + 16, height: KeyboardCatBar.top + 20))
        return path
    }
}

// Small highlights follow the quiet part of the silhouette, leaving the
// characters unobstructed.
struct KeyboardGlassEdgeLight: Shape {
    func path(in rect: CGRect) -> Path {
        let left: CGFloat = min(rect.height / 2, rect.width / 4)
        let right = max(left, rect.width - left)
        var path = Path()
        path.move(to: CGPoint(x: left, y: 1.5))
        path.addLine(to: CGPoint(x: right, y: 1.5))
        path.move(to: CGPoint(x: left, y: rect.height - 2))
        path.addLine(to: CGPoint(x: right, y: rect.height - 2))
        return path.offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

struct KeyboardCandidateGlass: ViewModifier {
    // Each cat owns its end of the bar. Candidates are inset further than the
    // touch zones reach, so a pet can never land on a candidate and a
    // candidate swipe never starts on a cat.
    static let leadingInset: CGFloat = KeyboardCat.mimimo.width + 6
    static let trailingInset: CGFloat = KeyboardCat.momo.width + 7
    static var horizontalInsets: CGFloat { leadingInset + trailingInset }
    // The cat skin's bar is a little taller so the cats can sit in it.
    static let catBarHeight: CGFloat = 50

    var cats: Bool
    var dark: Bool
    var strength: GlassStrength
    var tint: Color
    var appearanceRevision: Int
    var composing = false
    var simplified = false
    var chineseActive = true
    // Switches Chinese script and returns whether it is now simplified.
    var switchScript: (() -> Bool)? = nil
    @Environment(\.accessibilityReduceTransparency) var reduceTransparency
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @Environment(\.colorSchemeContrast) var contrast
    @Environment(\.scenePhase) var scenePhase
    @State var activationRevision = 0
    @State var mimimoBlink = false
    @State var momoBlink = false
    @State var mimimoJoy = 0
    @State var momoJoy = 0
    @State var mimimoHappy = false
    @State var momoHappy = false
    @State var mimimoMotion = 0
    @State var momoMotion = 0
    @State var mimimoPressed = false
    @State var momoPressed = false
    @State var momoBlush: Double = 0
    @State var affection = CatAffection()
    @State var particles: [CatParticle] = []
    @State var toast: CatToast?

    var opaque: Bool { reduceTransparency || contrast == .increased }
    var glass: Glass {
        guard !opaque else { return .identity }
        return switch strength {
        case .soft: .regular.tint(tint.opacity(0.06))
        case .balanced: .clear.tint(tint.opacity(0.08))
        case .vivid: .clear.tint(tint.opacity(0.03))
        }
    }
    var shade: Double {
        switch strength { case .soft: 0.40; case .balanced: 0.30; case .vivid: 0.18 }
    }
    var identity: CatAppearanceIdentity {
        CatAppearanceIdentity(appearanceRevision: appearanceRevision, activationRevision: activationRevision)
    }

    func body(content: Content) -> some View {
        content
            // The system's automatic scroll-edge blur can cover candidate text
            // in this short horizontal strip; the glass belongs behind the text.
            .scrollEdgeEffectHidden(true, for: .all)
            .padding(.leading, cats ? Self.leadingInset : 4)
            .padding(.trailing, cats ? Self.trailingInset : 4)
            // Centre the candidates on the lowered bar.
            .offset(y: cats ? KeyboardCatBar.top / 2 : 0)
            .background { if cats { catScene.id(identity) } else { plainGlass } }
            .clipShape(cats ? AnyShape(Rectangle().inset(by: -8)) : AnyShape(KeyboardGlassOutline()))
            .overlay { if cats { catHeads.id(identity) } }
            // The touch zones are each cat's end of the bar, outside the
            // candidate scroll view and the expand key.
            .overlay(alignment: .leading) { if cats { petZone(.mimimo) } }
            .overlay(alignment: .trailing) { if cats { petZone(.momo) } }
            .overlay {
                if cats {
                    GeometryReader { geometry in
                        ForEach(particles) { particle in
                            CatParticleView(particle: particle)
                                .position(x: particle.cat == .mimimo ? KeyboardCat.mimimo.width * 0.78
                                                                     : geometry.size.width - 2 - KeyboardCat.momo.width * 0.78,
                                          y: 6)
                        }
                    }
                    .allowsHitTesting(false).accessibilityHidden(true)
                }
            }
            .overlay {
                // Shown between the cats and never hit-testable, so a toast
                // cannot swallow a candidate tap.
                if let toast {
                    CatToastView(toast: toast, dark: dark, tint: tint)
                        .padding(.leading, cats ? Self.leadingInset : 8)
                        .padding(.trailing, cats ? Self.trailingInset + 2 : 8)
                        .offset(y: cats ? KeyboardCatBar.top / 2 : 0)
                        .id(toast.id)
                        .transition(reduceMotion ? .opacity : .scale(scale: 0.86).combined(with: .opacity))
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .animation(.spring(duration: 0.3, bounce: 0.3), value: toast)
            .task(id: toast?.id) {
                guard let current = toast else { return }
                try? await Task.sleep(for: .seconds(current.emphasis ? 2.4 : 1.4))
                if !Task.isCancelled, toast?.id == current.id { toast = nil }
            }
            .task(id: momoJoy) {
                guard momoJoy > 0 else { return }
                momoHappy = true
                try? await Task.sleep(for: .milliseconds(900))
                if !Task.isCancelled { momoHappy = false }
            }
            .task(id: mimimoJoy) {
                guard mimimoJoy > 0 else { return }
                mimimoHappy = true
                try? await Task.sleep(for: .milliseconds(700))
                if !Task.isCancelled { mimimoHappy = false }
            }
            .task(id: momoMotion) {
                // 莫莫's blush fades once the petting stops.
                try? await Task.sleep(for: .seconds(2.2))
                if !Task.isCancelled { momoBlush = 0 }
            }
            .task(id: cats) { if cats { await live(.mimimo) } }
            .task(id: cats) { if cats { await live(.momo) } }
            .onChange(of: composing) { wasComposing, isComposing in
                // 米米莫 gives a little hop whenever text is committed.
                if wasComposing && !isComposing { mimimoMotion &+= 1 }
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

    var plainGlass: some View {
        ZStack {
            KeyboardGlassOutline()
                .fill((dark ? Color.black : Brand.paper).opacity(opaque ? 1 : shade))
                .glassEffect(glass, in: KeyboardGlassOutline())
            if !opaque {
                // A restrained colour wash belongs to the material, behind
                // the candidates rather than above their text.
                KeyboardGlassOutline()
                    .fill(LinearGradient(colors: [Color.white.opacity(dark ? 0.075 : 0.18),
                                                  tint.opacity(dark ? 0.065 : 0.045),
                                                  Color.black.opacity(dark ? 0.08 : 0.025)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
            }
            // The outer rim and a narrow inner glint give the glass a
            // polished edge without introducing another blur layer.
            KeyboardGlassOutline()
                .stroke(LinearGradient(colors: [Color.white.opacity(dark ? 0.45 : 0.82),
                                                Color.white.opacity(dark ? 0.10 : 0.25),
                                                Color.black.opacity(dark ? 0.22 : 0.16)],
                                       startPoint: .top, endPoint: .bottom), lineWidth: 0.7)
            if !opaque {
                KeyboardGlassEdgeLight()
                    .stroke(LinearGradient(colors: [.clear, Color.white.opacity(dark ? 0.30 : 0.48),
                                                    Color.white.opacity(dark ? 0.10 : 0.26), .clear],
                                           startPoint: .leading, endPoint: .trailing),
                            style: StrokeStyle(lineWidth: 0.7, lineCap: .round))
            }
        }
        .allowsHitTesting(false)
    }

    // Natural, irregular blinks; now and then 莫莫 gives a slow double blink.
    func live(_ cat: KeyboardCat) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(Double.random(in: 2.8...6.4)))
            guard !Task.isCancelled else { return }
            await blink(cat)
            if cat == .momo, Double.random(in: 0...1) < 0.25 {
                try? await Task.sleep(for: .milliseconds(180))
                await blink(cat)
            }
        }
    }

    func blink(_ cat: KeyboardCat) async {
        if cat == .mimimo { mimimoBlink = true } else { momoBlink = true }
        try? await Task.sleep(for: .milliseconds(120))
        if cat == .mimimo { mimimoBlink = false } else { momoBlink = false }
    }

    func petZone(_ cat: KeyboardCat) -> some View {
        Button { pet(cat) } label: {
            Color.clear
                .frame(width: cat == .mimimo ? Self.leadingInset - 4 : KeyboardCat.momo.width)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(CatPetButtonStyle { pressed in
            if cat == .mimimo { mimimoPressed = pressed } else { momoPressed = pressed }
        })
        .accessibilityLabel(cat == .mimimo ? "摸摸米米莫" : "摸摸莫莫")
        .accessibilityHint(cat == .momo ? (simplified ? "连续轻点五下，切换为繁體" : "連續輕點五下，切換為简体") : "")
    }

    func pet(_ cat: KeyboardCat) {
        UIDevice.current.playInputClick()
        switch cat {
        case .mimimo:
            // Mischievous and a little aloof: sometimes he smiles, sometimes
            // he just hops and keeps his smug look.
            mimimoMotion &+= 1
            if Bool.random() { mimimoJoy &+= 1 }
            let symbol = ["pawprint.fill", "music.note", "questionmark", "exclamationmark", "sparkle"].randomElement() ?? "pawprint.fill"
            emit(CatParticle(symbol: symbol, cat: .mimimo, size: 10, drift: CGFloat.random(in: 4...10),
                             tint: Color(red: 0.86, green: 0.66, blue: 0.24)))
        case .momo:
            momoMotion &+= 1
            momoJoy &+= 1
            switch affection.pet() {
            case .pleased(let count):
                momoBlush = Double(count) / Double(CatAffection.petsToSwitch)
                emit(CatParticle(symbol: "heart.fill", cat: .momo, size: 8 + CGFloat(count) * 1.5,
                                 drift: -CGFloat.random(in: 4...10), tint: Color(red: 0.95, green: 0.45, blue: 0.55)))
                if count >= 2 {
                    let left = CatAffection.petsToSwitch - count
                    toast = CatToast(symbol: "heart.fill",
                                     text: simplified ? "再摸 \(left) 下，切换为繁體" : "再摸 \(left) 下，切換為简体",
                                     hearts: count)
                }
            case .happy:
                momoBlush = 1
                celebrate()
            case .resting:
                momoBlush = 1
                emit(CatParticle(symbol: "heart.fill", cat: .momo, size: 9, drift: -CGFloat.random(in: 4...10),
                                 tint: Color(red: 0.95, green: 0.45, blue: 0.55)))
            }
        }
    }

    func celebrate() {
        guard let switchScript else { return }
        let nowSimplified = switchScript()
        var text = nowSimplified ? "莫莫开心！已切换为简体中文" : "莫莫開心！已切換為繁體中文"
        if !chineseActive { text += nowSimplified ? "（中文输入生效）" : "（中文輸入生效）" }
        toast = CatToast(symbol: "checkmark.circle.fill", text: text, emphasis: true)
        UIAccessibility.post(notification: .announcement, argument: text)
        for index in 0..<3 {
            emit(CatParticle(symbol: index == 1 ? "sparkles" : "heart.fill", cat: .momo, size: 10 + CGFloat(index) * 2,
                             drift: -CGFloat(4 + index * 5), tint: Color(red: 0.95, green: 0.45, blue: 0.55)))
        }
        // 米米莫 notices and perks up too.
        mimimoMotion &+= 1
        mimimoJoy &+= 1
    }

    func emit(_ particle: CatParticle) {
        particles.append(particle)
        if particles.count > 8 { particles.removeFirst(particles.count - 8) }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))
            particles.removeAll { $0.id == particle.id }
        }
    }
}
