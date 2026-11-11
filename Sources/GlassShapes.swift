import SwiftUI

// The candidate glass outlines and material (docs/DESIGN.md §2–3), shared by
// the iOS keyboard, the iOS app's preview and the macOS candidate window.

private func smoothstep(_ edge0: CGFloat, _ edge1: CGFloat, _ x: CGFloat) -> CGFloat {
    let t = min(1, max(0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)
}

// Polynomial smooth minimum; rounds the valleys between cloud bumps.
private func smoothMin(_ a: CGFloat, _ b: CGFloat, _ k: CGFloat) -> CGFloat {
    let h = max(k - abs(a - b), 0) / k
    return min(a, b) - h * h * k / 4
}

struct CandidateGlassShape: InsettableShape {
    var style: GlassShape
    var insetAmount: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: insetAmount, dy: insetAmount)
        guard r.width > 2, r.height > 2 else { return Path() }
        switch style {
        case .capsule: return Path(roundedRect: r, cornerRadius: r.height / 2, style: .continuous)
        case .wave:
            // Wave count and height come from the outline itself, so strokes
            // inset from it follow the same curve.
            return Self.wave(in: r, waves: Self.waveCount(width: rect.width), amplitude: 0.065 * rect.height)
        case .cloud: return Self.cloud(in: r)
        }
    }

    func inset(by amount: CGFloat) -> CandidateGlassShape {
        var copy = self
        copy.insetAmount += amount
        return copy
    }

    /// The vertical band candidates sit in (relative to the shape's rect):
    /// the cloud's bumps rise above its stadium base.
    static func contentBand(_ style: GlassShape, height: CGFloat) -> (top: CGFloat, bottom: CGFloat) {
        switch style {
        case .capsule: return (0, height)
        case .wave: return (0.065 * height, height * (1 - 0.065))
        case .cloud: return (0.16 * height, height)
        }
    }

    // Two parallel sine edges with a smooth envelope so they meet the round
    // ends without a kink.
    static func wave(in r: CGRect) -> Path {
        wave(in: r, waves: waveCount(width: r.width), amplitude: 0.065 * r.height)
    }

    static func waveCount(width: CGFloat) -> CGFloat { max(2, (width / 170).rounded()) }

    static func wave(in r: CGRect, waves: CGFloat, amplitude: CGFloat) -> Path {
        let height = r.height, width = r.width
        let radius = max(1, height / 2 - amplitude)
        let straight = max(1, width - 2 * radius)
        func offset(_ x: CGFloat) -> CGFloat {
            let u = (x - radius) / straight
            let envelope = smoothstep(0, 0.12, u) * smoothstep(0, 0.12, 1 - u)
            return -amplitude * sin(2 * .pi * waves * u) * envelope
        }
        let steps = 64
        var path = Path()
        for i in 0...steps {
            let x = radius + straight * CGFloat(i) / CGFloat(steps)
            let point = CGPoint(x: r.minX + x, y: r.minY + amplitude + offset(x))
            if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.addArc(center: CGPoint(x: r.minX + width - radius, y: r.midY), radius: radius,
                    startAngle: .degrees(-90), endAngle: .degrees(90), clockwise: false)
        for i in stride(from: steps, through: 0, by: -1) {
            let x = radius + straight * CGFloat(i) / CGFloat(steps)
            path.addLine(to: CGPoint(x: r.minX + x, y: r.minY + height - amplitude + offset(x)))
        }
        path.addArc(center: CGPoint(x: r.minX + radius, y: r.midY), radius: radius,
                    startAngle: .degrees(90), endAngle: .degrees(270), clockwise: false)
        path.closeSubpath()
        return path
    }

    // A stadium base with three bumps along the top, joined by a smooth
    // minimum instead of sharp cusps.
    static func cloud(in r: CGRect) -> Path {
        let height = r.height, width = r.width
        let base = 0.16 * height
        let radius = (height - base) / 2
        let centerY = base + radius
        let smoothing = 0.10 * height
        let bumps: [(x: CGFloat, radius: CGFloat, peak: CGFloat)] = [(0.15, 0.58, 0), (0.47, 0.75, 0.05), (0.77, 0.56, 0.07)]
        let circles: [(cx: CGFloat, r: CGFloat, cy: CGFloat)] = bumps.map { bump in
            let r = bump.radius * height
            let cy = bump.peak * height + r
            // Keep each bump's reach inside the straight section.
            let reach = sqrt(max(0, r * r - (cy - base) * (cy - base))) + smoothing + 2
            let lower = radius + reach, upper = width - radius - reach
            let cx = upper > lower ? min(max(bump.x * width, lower), upper) : width / 2
            return (cx, r, cy)
        }
        func top(_ x: CGFloat) -> CGFloat {
            var y = base
            for circle in circles {
                let dx = x - circle.cx
                guard abs(dx) < circle.r else { continue }
                y = smoothMin(y, circle.cy - sqrt(circle.r * circle.r - dx * dx), smoothing)
            }
            return y
        }
        let steps = 96
        let straight = max(1, width - 2 * radius)
        var path = Path()
        for i in 0...steps {
            let x = radius + straight * CGFloat(i) / CGFloat(steps)
            let point = CGPoint(x: r.minX + x, y: r.minY + top(x))
            if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.addArc(center: CGPoint(x: r.minX + width - radius, y: r.minY + centerY), radius: radius,
                    startAngle: .degrees(-90), endAngle: .degrees(90), clockwise: false)
        path.addLine(to: CGPoint(x: r.minX + radius, y: r.maxY))
        path.addArc(center: CGPoint(x: r.minX + radius, y: r.minY + centerY), radius: radius,
                    startAngle: .degrees(90), endAngle: .degrees(270), clockwise: false)
        path.closeSubpath()
        return path
    }
}

extension CandidatePalette {
    /// The selected-candidate capsule: top and bottom of its gradient.
    func pill(dark: Bool) -> (top: Color, bottom: Color) {
        func rgb(_ hex: UInt32) -> Color {
            Color(red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255, blue: Double(hex & 0xff) / 255)
        }
        let pair: (UInt32, UInt32) = switch (self, dark) {
        case (.rose, false): (0xAB8689, 0x946D71)
        case (.rose, true): (0xA88D8C, 0x8C716F)
        case (.ocean, false): (0x7892AD, 0x5C7A9B)
        case (.ocean, true): (0x6F8FAE, 0x587894)
        case (.sage, false): (0x7F9583, 0x647D69)
        case (.sage, true): (0x7F9984, 0x66806B)
        case (.lavender, false): (0x9A88B3, 0x816EA0)
        case (.lavender, true): (0x8F7FA8, 0x75668D)
        case (.amber, false): (0xA78B60, 0x927242)
        case (.amber, true): (0xA88B63, 0x8C714D)
        case (.graphite, false): (0x8B9099, 0x717781)
        case (.graphite, true): (0x7E848D, 0x666C75)
        }
        return (rgb(pair.0), rgb(pair.1))
    }

    /// The capsule's fill when Increase Contrast is on: the palette's deep
    /// tone, at least 5:1 under white text in every palette.
    var contrastPill: Color { color(dark: false) }
}

/// Candidate text colours (DESIGN.md §4).
enum CandidateInk {
    static func text(dark: Bool) -> Color {
        dark ? Color(red: 0.961, green: 0.937, blue: 0.914) : Color(red: 0.227, green: 0.200, blue: 0.204)
    }
    static func secondary(dark: Bool) -> Color {
        dark ? Color(red: 0.80, green: 0.76, blue: 0.73) : Color(red: 0.478, green: 0.439, blue: 0.447)
    }
}

/// The selected candidate's rose capsule with a hairline of light on top.
/// With Increase Contrast it is the palette's deep tone. During a holiday
/// the outfit may bring its own colour (docs/FESTIVALS.md).
struct CandidatePill: View {
    var palette: CandidatePalette
    var dark: Bool
    var highContrast = false
    var festival: Color? = nil

    private var colors: (top: Color, bottom: Color) {
        if highContrast { return (palette.contrastPill, palette.contrastPill) }
        if let festival { return (festival.opacity(0.86), festival) }
        return palette.pill(dark: dark)
    }

    var body: some View {
        let colors = colors
        Capsule(style: .continuous)
            .fill(LinearGradient(colors: [colors.top, colors.bottom], startPoint: .top, endPoint: .bottom))
            .overlay {
                // A bead of tinted glass: light on its upper edge, and the
                // light that crossed it gathered along the lower edge.
                Capsule(style: .continuous).inset(by: 0.5)
                    .stroke(LinearGradient(colors: [.white.opacity(dark ? 0.34 : 0.55), .white.opacity(0)],
                                           startPoint: .top, endPoint: .center), lineWidth: 0.8)
                Capsule(style: .continuous).inset(by: 1.2)
                    .stroke(LinearGradient(colors: [.white.opacity(0), .white.opacity(dark ? 0.16 : 0.26)],
                                           startPoint: .center, endPoint: .bottom), lineWidth: 1.6)
                    .blur(radius: 0.8)
                    .clipShape(Capsule(style: .continuous))
            }
            .shadow(color: colors.bottom.opacity(dark ? 0.30 : 0.38), radius: 3.5, y: 1.5)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// The candidate glass: native Liquid Glass carrying a little of the palette,
/// with light at both ends of its rim, a bright band where the thick edge
/// bends light, an iridescent caustic along the lower rim and the caustic's
/// colours showing faintly through its lower half. Equatable so a keystroke that
/// only changes candidates never redraws it.
struct LiquidGlassBar: View, Equatable {
    var style: GlassShape
    var dark: Bool
    var accessible: Bool
    var strength: GlassStrength
    var tint: Color

    private var shape: CandidateGlassShape { CandidateGlassShape(style: style) }
    private static let smoke = Color(red: 0.227, green: 0.188, blue: 0.172)

    /// The glass itself. Liquid Glass exists from iOS 26 and macOS 26; older
    /// systems get frosted glass, a blur material under the same tint, with
    /// every painted layer of the bar unchanged (docs/DESIGN.md §3).
    @ViewBuilder private var pane: some View {
        if accessible {
            shape.fill(bodyFill)
        } else if #available(iOS 26, macOS 26, *), LiquidGlass.available {
            let base: Glass = strength == .vivid ? .clear : .regular
            shape.fill(bodyFill)
                .glassEffect(dark ? base.tint(Self.smoke.opacity(0.55)) : base.tint(tint.opacity(0.07)), in: shape)
        } else {
            shape.fill(.ultraThinMaterial).environment(\.colorScheme, dark ? .dark : .light)
            shape.fill(dark ? Self.smoke.opacity(0.5) : Color.white.opacity(0.36))
            shape.fill(bodyFill)
            if !dark { shape.fill(tint.opacity(0.07)) }
        }
    }

    private var bodyFill: Color {
        if accessible { return dark ? Color(red: 0.169, green: 0.153, blue: 0.161) : Color(red: 0.957, green: 0.949, blue: 0.965) }
        let amount: Double = switch strength { case .soft: 0.34; case .balanced: 0.20; case .vivid: 0.08 }
        return dark ? Self.smoke.opacity(amount + 0.12) : Color.white.opacity(amount)
    }

    /// Light: a pastel spectrum. Dark: the palette, between two lighter tones of itself.
    private var caustic: LinearGradient {
        let colors: [Color]
        if dark {
            colors = [tint, tint.blend(.white, 0.45), tint.blend(.black, 0.12), tint.blend(.white, 0.45)]
        } else {
            colors = ([0xFFB8D2, 0xFFD9B0, 0xFFF4B8, 0xC9F2DD, 0xBFD9FF, 0xDCC8FF] as [UInt32]).map {
                Color(red: Double($0 >> 16) / 255, green: Double(($0 >> 8) & 0xff) / 255, blue: Double($0 & 0xff) / 255)
            }
        }
        return LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing)
    }

    var body: some View {
        let lowerHalf = LinearGradient(colors: [.clear, .black], startPoint: .center, endPoint: .bottom)
        ZStack {
            if !accessible {
                // A soft contact shadow that makes the glass float.
                shape.fill(Color(red: 0.24, green: 0.16, blue: 0.27).opacity(dark ? 0 : 0.13))
                    .blur(radius: 7).offset(y: 5).padding(.horizontal, 6)
                if dark {
                    shape.fill(Color.black.opacity(0.5)).blur(radius: 8).offset(y: 5).padding(.horizontal, 6)
                }
                // The caustic's colours, soft, under the lower half of the body:
                // seen through the glass they are a faint wash. The mask ends at
                // the bar's frame, so nothing is coloured below the bar.
                shape.fill(caustic).opacity(dark ? 0.20 : 0.30)
                    .blur(radius: 7).offset(y: 6).padding(.horizontal, 14)
                    .mask(shape.fill(.black).blur(radius: 7).offset(y: 6).padding(.horizontal, 14).mask(lowerHalf))
            }
            pane
            if !accessible {
                if dark { shape.fill(tint.opacity(0.07)) }
                shape.fill(LinearGradient(colors: [.white.opacity(dark ? 0.09 : 0.32), .white.opacity(dark ? 0 : 0.05)],
                                          startPoint: .top, endPoint: .bottom))
                // The thick edge bends light: a soft bright band just inside the rim.
                shape.inset(by: 3)
                    .stroke(Color.white.opacity(dark ? 0.07 : 0.38), lineWidth: 3.5)
                    .blur(radius: 1.8)
                    .clipShape(shape)
                // Iridescent caustic along the lower rim, fading upward.
                shape.inset(by: 2)
                    .stroke(caustic, lineWidth: 2.4)
                    .opacity(dark ? 0.6 : 0.8)
                    .mask(LinearGradient(stops: [.init(color: .clear, location: 0.35), .init(color: .black, location: 1)],
                                         startPoint: .top, endPoint: .bottom))
                // Inner thickness: light along the top inside edge.
                shape.inset(by: 1.2)
                    .stroke(LinearGradient(stops: [.init(color: .white.opacity(dark ? 0.24 : 0.9), location: 0),
                                                   .init(color: .white.opacity(0), location: 0.45)],
                                           startPoint: .top, endPoint: .bottom), lineWidth: 1.4)
                // Specular hotspot on the upper left.
                shape.inset(by: 1.5)
                    .stroke(Color.white.opacity(dark ? 0.4 : 0.9), lineWidth: 2)
                    .mask(RadialGradient(colors: [.black, .clear], center: UnitPoint(x: 0.12, y: 0.0), startRadius: 0, endRadius: 70))
            }
            // The polished rim catches light at both ends: brightest at the
            // upper left, again at the lower right.
            let rim = dark ? tint.blend(.white, 0.25) : Color.white
            shape.stroke(LinearGradient(stops: [.init(color: rim.opacity(dark ? 0.95 : 1), location: 0),
                                                .init(color: rim.opacity(dark ? 0.16 : 0.4), location: 0.55),
                                                .init(color: rim.opacity(dark ? 0.5 : 0.8), location: 1)],
                                        startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.5)
            if !accessible {
                // The glass is thick: a soft shade inside its lower edge and a
                // faint darker line under the rim.
                shape.inset(by: 3)
                    .stroke(Color(red: 0.30, green: 0.22, blue: 0.34).opacity(dark ? 0.0 : 0.07), lineWidth: 5)
                    .blur(radius: 2.5)
                    .mask(lowerHalf)
                    .clipShape(shape)
                shape.stroke(Color.black.opacity(dark ? 0.35 : 0.08), lineWidth: 0.5).offset(y: 0.75)
                    .mask(lowerHalf)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Liquid Glass exists from iOS 26 and macOS 26; earlier systems get frosted
/// glass (docs/DESIGN.md §3). The iOS lab app shows that look on a newer
/// system with `-frosted YES`.
enum LiquidGlass {
    static let available: Bool = {
        #if KEYBOARD_LAB
        if UserDefaults.standard.bool(forKey: "frosted") { return false }
        #endif
        if #available(iOS 26, macOS 26, *) { return true }
        return false
    }()
}

extension Color {
    /// This colour moved toward another in sRGB. (`Color.mix` needs iOS 18.)
    func blend(_ other: Color, _ amount: Double) -> Color {
        let a = resolve(in: EnvironmentValues()), b = other.resolve(in: EnvironmentValues())
        let t = Float(amount)
        return Color(.sRGB, red: Double(a.red + (b.red - a.red) * t), green: Double(a.green + (b.green - a.green) * t),
                     blue: Double(a.blue + (b.blue - a.blue) * t), opacity: Double(a.opacity + (b.opacity - a.opacity) * t))
    }
}

extension View {
    /// Glass behind a small control: Liquid Glass from iOS 26 and macOS 26, a
    /// blur material on earlier systems.
    @ViewBuilder func controlGlass<S: Shape>(in shape: S, interactive: Bool = false) -> some View {
        if #available(iOS 26, macOS 26, *) {
            glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            background(.ultraThinMaterial, in: shape)
        }
    }
}
