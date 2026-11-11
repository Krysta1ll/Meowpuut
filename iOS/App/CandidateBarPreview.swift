import SwiftUI

extension GlassShape {
    /// The shape's name in the app's interface language.
    func name(_ t: InterfaceText) -> String {
        switch self {
        case .capsule: return t("膠囊", "胶囊")
        case .wave: return "波浪"
        case .cloud: return t("雲朵", "云朵")
        }
    }
}

/// A still picture of the keyboard's candidate glass with the chosen look.
/// It uses the keyboard's own geometry and glass, so it matches what the
/// keyboard draws, also with Reduce Transparency or Increase Contrast.
struct CandidateBarPreview: View {
    var shape: GlassShape
    var dark: Bool
    var palette: CandidatePalette
    var strength: GlassStrength
    var cats: Bool
    var simplified: Bool

    private var candidates: [String] { simplified ? ["你好", "你好呀", "您好"] : ["你好", "妳好", "你好呀"] }
    /// The width the card was given.
    @State private var card: CGFloat = 353
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        // Laid out at a phone's width, then scaled to the card, so the row
        // holds what it holds on a phone.
        let width = max(393, card)
        let scale = min(1, card / width)
        let layout = CandidateRegionLayout(width: width, idiom: .phone, cats: cats,
                                           rim: CandidateGlassShape.contentBand(shape, height: 1).top)
        let keyWidth = (width - 6 - 9 * 6) / 10
        let band = CandidateGlassShape.contentBand(shape, height: layout.glass.height)
        let accessible = reduceTransparency || contrast == .increased
        return ZStack(alignment: .topLeading) {
            if cats {
                Image("mimimo_open").resizable().interpolation(.high)
                    .frame(width: layout.mimimo.width, height: layout.mimimo.height)
                    .position(x: layout.mimimo.midX, y: layout.mimimo.midY)
            }
            LiquidGlassBar(style: shape, dark: dark, accessible: accessible, strength: strength,
                           tint: palette.color(dark: dark))
                .frame(width: layout.glass.width, height: layout.glass.height)
                .position(x: layout.glass.midX, y: layout.glass.midY)
            if cats && !accessible && !dark {
                // The keyboard's light band over 米米莫's body (CandidateRegionView).
                let span = layout.mimimo.maxX + 10 * layout.scale - layout.row.minX
                Capsule().fill(Color.white.opacity(0.5))
                    .frame(width: span, height: layout.pillHeight)
                    .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.6),
                                                 .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
                    .position(x: layout.row.minX + span / 2, y: layout.glass.minY + (band.top + band.bottom) / 2)
            }
            HStack(spacing: CandidateStripLayout.spacing) {
                ForEach(Array(candidates.enumerated()), id: \.offset) { index, text in
                    // The keyboard's own cell width for this text.
                    let cell = CandidateStripLayout.cellWidth(text: CandidateTextMetrics.width(text, size: layout.fontSize),
                                                              japanese: false, paw: 0, maximum: layout.maximumCandidateWidth)
                    Text(text)
                        .font(.system(size: layout.fontSize, weight: index == 0 ? .medium : .regular))
                        .foregroundStyle(index == 0 ? Color.white : CandidateInk.text(dark: dark))
                        .lineLimit(1)
                        .frame(width: cell, height: layout.pillHeight)
                        .background { if index == 0 { CandidatePill(palette: palette, dark: dark, highContrast: contrast == .increased) } }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 2)
            .frame(width: layout.row.width, height: band.bottom - band.top, alignment: .leading)
            .clipped()
            // The keyboard fades the row out before the expand key.
            .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.94),
                                         .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
            .position(x: layout.row.midX, y: layout.glass.minY + (band.top + band.bottom) / 2)
            Image(systemName: "chevron.down")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(CandidateInk.text(dark: dark).opacity(0.85))
                .position(x: layout.chevron.midX, y: layout.chevron.midY)
            if cats && shape == .capsule {
                Image("mimimo_tail").resizable().interpolation(.high)
                    .frame(width: layout.tail.width, height: layout.tail.height)
                    .position(x: layout.tail.midX, y: layout.tail.midY)
            }
            if cats && !accessible {
                // The same soft shadow under her chin as in the keyboard.
                Ellipse()
                    .fill(Color(red: 0.25, green: 0.16, blue: 0.12).opacity(dark ? 0.35 : 0.16))
                    .frame(width: layout.momo.width * 0.62, height: layout.momo.height * 0.16)
                    .blur(radius: 4)
                    .position(x: layout.momo.midX - layout.momo.width * 0.04, y: layout.momo.maxY - layout.momo.height * 0.06)
            }
            if cats {
                Image("momo_open").resizable().interpolation(.high)
                    .frame(width: layout.momo.width, height: layout.momo.height)
                    .position(x: layout.momo.midX, y: layout.momo.midY)
            }
            // One row of keys under the glass.
            HStack(spacing: 6) {
                ForEach(Array("qwertyuiop"), id: \.self) { letter in
                    Text(String(letter))
                        .font(.system(size: 21))
                        .foregroundStyle(dark ? Color.white : Color(red: 0.094, green: 0.094, blue: 0.106))
                        .frame(width: keyWidth, height: 40)
                        .background(RoundedRectangle(cornerRadius: 8.5, style: .continuous)
                            .fill(dark ? Color(red: 0.369, green: 0.365, blue: 0.388) : .white)
                            .shadow(color: .black.opacity(dark ? 0.5 : 0.2), radius: 0, y: 1))
                }
            }
            .frame(width: width)
            .position(x: width / 2, y: layout.height + 8 + 20)
        }
        // The candidate area, the top of the first row of keys and a margin.
        .frame(width: width, height: layout.height + 56, alignment: .topLeading)
        .scaleEffect(scale, anchor: .topLeading)
        .frame(width: width * scale, height: (layout.height + 56) * scale, alignment: .topLeading)
        // Room above the ears.
        .padding(.top, 8)
        .frame(minWidth: 0, maxWidth: .infinity)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { card = max(240, min(480, $0)) }
        .background(KeyboardPreviewBackground(dark: dark))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Color.primary.opacity(0.06)))
        .environment(\.colorScheme, dark ? .dark : .light)
        .accessibilityElement()
        .accessibilityLabel(InterfaceText.system("候選列預覽：", "候选列预览：") + shape.name(InterfaceText.system))
    }
}

private struct KeyboardPreviewBackground: View {
    var dark: Bool
    var body: some View {
        LinearGradient(colors: dark
                       ? [Color(red: 0.145, green: 0.141, blue: 0.157), Color(red: 0.102, green: 0.098, blue: 0.110)]
                       : [Color(red: 0.914, green: 0.910, blue: 0.937), Color(red: 0.851, green: 0.847, blue: 0.878)],
                       startPoint: .top, endPoint: .bottom)
    }
}
