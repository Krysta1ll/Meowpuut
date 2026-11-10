import SwiftUI
import UIKit

extension KeyboardCandidateGlass {
    func expression(_ cat: KeyboardCat) -> CatExpression {
        CatExpression(happy: cat == .mimimo ? (mimimoHappy || mimimoBlink) : (momoHappy || momoBlink),
                      look: composing ? 1 : 0,
                      blush: cat == .momo ? momoBlush : 0)
    }

    func sprite(_ cat: KeyboardCat) -> some View {
        KeyboardCatSprite(cat: cat, expression: expression(cat),
                          motion: cat == .mimimo ? mimimoMotion : momoMotion,
                          pressed: cat == .mimimo ? mimimoPressed : momoPressed)
    }

    func placed<V: View>(_ cat: KeyboardCat, _ view: V) -> some View {
        view
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: cat == .mimimo ? .bottomLeading : .bottomTrailing)
            // Tucked inside the glass's rounded ends, with ears peeking above the top rim.
            .padding(.leading, cat == .mimimo ? 2 : 0)
            .padding(.trailing, cat == .momo ? 3 : 0)
            .padding(.bottom, 1)
    }

    // The Liquid Glass bar with a soft subsurface colour aura from each cat at
    // its two ends. Keeping sharp sprite edges out of the backdrop prevents the
    // glass perimeter lens from smearing dark ear or paw pixels along the rim.
    var catScene: some View {
        ZStack {
            KeyboardCatBar()
                .fill((dark ? Color.black : Color.white).opacity(opaque ? 0.94 : (dark ? 0.22 : 0.52)))
                .glassEffect(glass, in: KeyboardCatBar())
                .shadow(color: .black.opacity(dark ? 0.28 : 0.08), radius: 5, y: 2)
            if !opaque {
                KeyboardCatBar()
                    .fill(LinearGradient(colors: [Color.white.opacity(dark ? 0.09 : 0.42),
                                                  tint.opacity(dark ? 0.07 : 0.05),
                                                  Color.black.opacity(dark ? 0.12 : 0.03)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                ZStack {
                    placed(.mimimo, colourBleed(.mimimo))
                    placed(.momo, colourBleed(.momo))
                }
                .clipShape(KeyboardCatBar())
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    // Each cat's ears and face emerge crisply at the top of the bar while its
    // lower chest melts into the Liquid Glass and catches the capsule's lower
    // specular sheen and rim highlight.
    var catHeads: some View {
        ZStack {
            ZStack {
                placed(.mimimo, sprite(.mimimo).mask { glassMeltMask(.mimimo) })
                placed(.momo, sprite(.momo).mask { glassMeltMask(.momo) })
            }
            .clipShape(KeyboardCatClip())
            if !opaque {
                catGlassCradle
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    // Fully crisp across the ears and face; across the lower chest, the outer
    // body stays anchored to the bottom of the capsule while melting smoothly
    // inward toward the candidates and into the glass glaze.
    func glassMeltMask(_ cat: KeyboardCat) -> some View {
        ZStack {
            LinearGradient(stops: [
                .init(color: .black, location: 0),
                .init(color: .black, location: 0.58),
                .init(color: .black.opacity(0.84), location: 0.78),
                .init(color: .black.opacity(0.52), location: 0.93),
                .init(color: .black.opacity(0.22), location: 1.0)
            ], startPoint: .top, endPoint: .bottom)
            // Soft diagonal melt on the inner lower chest toward the candidate strip.
            LinearGradient(stops: [
                .init(color: .clear, location: 0),
                .init(color: .clear, location: 0.52),
                .init(color: .black.opacity(0.55), location: 0.80),
                .init(color: .black.opacity(0.92), location: 1.0)
            ], startPoint: cat == .mimimo ? .leading : .trailing,
               endPoint: cat == .mimimo ? .trailing : .leading)
            .mask {
                LinearGradient(stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .clear, location: 0.48),
                    .init(color: .black, location: 0.82),
                    .init(color: .black, location: 1.0)
                ], startPoint: .top, endPoint: .bottom)
            }
            .blendMode(.destinationOut)
        }
        .compositingGroup()
        .frame(width: cat.width, height: cat.height)
    }

    // Glossy Liquid Glass glaze and rim light that cradle the submerged lower
    // chest while parting around the cats' heads at the top-left and top-right.
    var catGlassCradle: some View {
        GeometryReader { geometry in
            let w = geometry.size.width
            let leftHead = KeyboardCat.mimimo.width
            let rightHead = KeyboardCat.momo.width + 1
            let glazeColor = dark ? Color(red: 0.16, green: 0.15, blue: 0.18) : Color.white
            ZStack {
                // Translucent glass glaze rising over the lower half of the bar so the
                // cats' lower chests read as submerged inside the Liquid Glass material.
                KeyboardCatBar()
                    .fill(LinearGradient(stops: [
                        .init(color: .clear, location: 0),
                        .init(color: .clear, location: 0.48),
                        .init(color: glazeColor.opacity(dark ? 0.22 : 0.28), location: 0.72),
                        .init(color: glazeColor.opacity(dark ? 0.42 : 0.52), location: 0.92),
                        .init(color: Color.white.opacity(dark ? 0.20 : 0.62), location: 1.0)
                    ], startPoint: .top, endPoint: .bottom))
                // Inner specular highlight along the lower curve of the capsule.
                KeyboardCatBar()
                    .inset(by: 1.1)
                    .stroke(LinearGradient(stops: [
                        .init(color: .clear, location: 0),
                        .init(color: .clear, location: 0.55),
                        .init(color: Color.white.opacity(dark ? 0.16 : 0.55), location: 1.0)
                    ], startPoint: .top, endPoint: .bottom), lineWidth: 0.9)
                // Polished capsule rim, masked out only along the top edge where the heads rise above the bar.
                KeyboardCatBar()
                    .stroke(LinearGradient(colors: [Color.white.opacity(dark ? 0.48 : 0.92),
                                                    Color.white.opacity(dark ? 0.14 : 0.36),
                                                    Color.black.opacity(dark ? 0.26 : 0.12)],
                                           startPoint: .top, endPoint: .bottom), lineWidth: 0.85)
                    .mask {
                        ZStack {
                            Color.black
                            VStack(spacing: 0) {
                                HStack(spacing: 0) {
                                    LinearGradient(stops: [
                                        .init(color: .black, location: 0),
                                        .init(color: .black, location: 0.78),
                                        .init(color: .clear, location: 1.0)
                                    ], startPoint: .leading, endPoint: .trailing)
                                    .frame(width: leftHead + 6)
                                    Spacer(minLength: 0)
                                    LinearGradient(stops: [
                                        .init(color: .clear, location: 0),
                                        .init(color: .black, location: 0.22),
                                        .init(color: .black, location: 1.0)
                                    ], startPoint: .leading, endPoint: .trailing)
                                    .frame(width: rightHead + 6)
                                }
                                .frame(height: KeyboardCatBar.top + 20)
                                .mask {
                                    LinearGradient(stops: [
                                        .init(color: .black, location: 0),
                                        .init(color: .black, location: 0.72),
                                        .init(color: .clear, location: 1.0)
                                    ], startPoint: .top, endPoint: .bottom)
                                }
                                Spacer(minLength: 0)
                            }
                            .frame(width: w)
                            .blendMode(.destinationOut)
                        }
                        .compositingGroup()
                    }
            }
        }
    }

    // A faint wash of each cat's colour glowing in the glass around its body,
    // so the bar picks up the cat instead of meeting it at an edge.
    func colourBleed(_ cat: KeyboardCat) -> some View {
        Ellipse()
            .fill(cat == .mimimo ? Color(red: 0.15, green: 0.16, blue: 0.22).opacity(dark ? 0.38 : 0.18)
                                 : Color(red: 0.96, green: 0.86, blue: 0.74).opacity(dark ? 0.22 : 0.42))
            .frame(width: cat.width * 1.45, height: cat.height * 0.72)
            .blur(radius: 9)
            .offset(x: cat == .mimimo ? 2 : -2, y: 6)
    }
}
