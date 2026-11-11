#include "Theme.h"

namespace meowpuut::ui {
namespace {
// Selection colours: the same pairs as CandidatePalette.pill(dark:) in
// Sources/GlassShapes.swift.
struct PillPair { Color lightTop, lightBottom, darkTop, darkBottom; };

PillPair pillFor(Palette palette) {
    switch (palette) {
    case Palette::Ocean:
        return {Color::rgb(0x7892AD), Color::rgb(0x5C7A9B), Color::rgb(0x6F8FAE), Color::rgb(0x587894)};
    case Palette::Sage:
        return {Color::rgb(0x7F9583), Color::rgb(0x647D69), Color::rgb(0x7F9984), Color::rgb(0x66806B)};
    case Palette::Lavender:
        return {Color::rgb(0x9A88B3), Color::rgb(0x816EA0), Color::rgb(0x8F7FA8), Color::rgb(0x75668D)};
    case Palette::Amber:
        return {Color::rgb(0xA78B60), Color::rgb(0x927242), Color::rgb(0xA88B63), Color::rgb(0x8C714D)};
    case Palette::Graphite:
        return {Color::rgb(0x8B9099), Color::rgb(0x717781), Color::rgb(0x7E848D), Color::rgb(0x666C75)};
    case Palette::Rose:
        break;
    }
    return {Color::rgb(0xAB8689), Color::rgb(0x946D71), Color::rgb(0xA88D8C), Color::rgb(0x8C716F)};
}

const Color kClear{0, 0, 0, 0};
} // namespace

Palette parsePalette(std::string_view name) {
    if (name == "ocean") return Palette::Ocean;
    if (name == "sage") return Palette::Sage;
    if (name == "lavender") return Palette::Lavender;
    if (name == "amber") return Palette::Amber;
    if (name == "graphite") return Palette::Graphite;
    return Palette::Rose;
}

Theme makeTheme(Appearance appearance, Palette palette, bool transparencyEnabled, bool highContrast,
                Color systemWindow, Color systemText, Color systemHighlight, Color systemHighlightText,
                Color systemGrayText) {
    Theme t;
    t.appearance = appearance;
    t.highContrast = highContrast;
    t.opaque = highContrast || !transparencyEnabled;
    const bool dark = appearance == Appearance::Dark;
    const auto pill = pillFor(palette);

    if (highContrast) {
        t.shadow = t.keyShadow = kClear;
        t.glassTop = t.glassBottom = systemWindow;
        t.rimTint = t.rimShade = t.lipStart = t.lipEnd = t.highlight = kClear;
        t.edge = systemText;
        t.causticCount = 0;
        t.separator = systemText;
        t.text = t.secondaryText = t.number = t.chevron = t.paw = systemText;
        t.disabledText = systemGrayText;
        t.pillTop = t.pillBottom = systemHighlight;
        t.pillText = systemHighlightText;
        t.pillHighlight = kClear;
        t.hover = systemHighlight.withAlpha(0.35f);
        t.pressed = systemHighlight.withAlpha(0.55f);
        t.contactShadow = kClear;
        return t;
    }

    if (dark) {
        t.shadow = Color{0, 0, 0, 0.45f};
        t.keyShadow = Color{0, 0, 0, 0.30f};
        t.glassTop = Color::rgb(0x483C37, 0.98f);
        t.glassBottom = Color::rgb(0x382E2A, 0.98f);
        t.rimTint = Color::rgb(0x3A302C, 0.58f);
        t.rimShade = Color{0, 0, 0, 0.20f};
        t.edge = Color::rgb(0x120C0A, 0.50f);
        t.lipStart = Color::rgb(0xE7A877, 0.85f);
        t.lipEnd = Color::rgb(0xE7A877, 0.15f);
        t.highlight = Color::rgb(0xFFE2C4, 0.55f);
        t.caustic[0] = Color::rgb(0xFFB27A);
        t.caustic[1] = Color::rgb(0xFFD4A0);
        t.caustic[2] = Color::rgb(0xE8956A);
        t.causticCount = 3;
        t.causticOpacity = 0.50f;
        t.frostOpacity = 0.32f;
        t.text = Color::rgb(0xF5EFE9);
        t.secondaryText = Color::rgb(0xF5EFE9, 0.62f);
        t.number = Color::rgb(0xF5EFE9, 0.50f);
        t.disabledText = Color::rgb(0xF5EFE9, 0.28f);
        t.separator = Color::rgb(0xF5EFE9, 0.12f);
        t.pillTop = pill.darkTop;
        t.pillBottom = pill.darkBottom;
        t.hover = Color{1, 1, 1, 0.08f};
        t.pressed = Color{1, 1, 1, 0.14f};
        t.chevron = Color::rgb(0xEDE6E0, 0.92f);
        t.paw = Color::rgb(0xF5EFE9, 0.62f);
        t.contactShadow = Color{0, 0, 0, 0.35f};
    } else {
        t.shadow = Color::rgb(0x3C2846, 0.12f);
        t.keyShadow = Color::rgb(0x3C2846, 0.10f);
        t.glassTop = Color::rgb(0xFAF9FC, 0.985f);
        t.glassBottom = Color::rgb(0xEFEDF4, 0.985f);
        t.rimTint = Color{1, 1, 1, 0.42f};
        t.rimShade = Color::rgb(0x4A3A5E, 0.07f);
        t.edge = Color::rgb(0x46385A, 0.17f);
        t.lipStart = Color{1, 1, 1, 0.95f};
        t.lipEnd = Color{1, 1, 1, 0.30f};
        t.highlight = Color{1, 1, 1, 0.95f};
        const std::uint32_t pastel[] = {0xFFB8D2, 0xFFD9B0, 0xFFF4B8, 0xC9F2DD, 0xBFD9FF, 0xDCC8FF};
        for (int i = 0; i < 6; ++i) t.caustic[i] = Color::rgb(pastel[i]);
        t.causticCount = 6;
        t.causticOpacity = 0.55f;
        t.frostOpacity = 0.14f;
        t.text = Color::rgb(0x3A3334);
        t.secondaryText = Color::rgb(0x7A7072);
        t.number = Color::rgb(0x3A3334, 0.45f);
        t.disabledText = Color::rgb(0x3A3334, 0.28f);
        t.separator = Color::rgb(0x3A3334, 0.08f);
        t.pillTop = pill.lightTop;
        t.pillBottom = pill.lightBottom;
        t.hover = Color::rgb(0x3A3334, 0.06f);
        t.pressed = Color::rgb(0x3A3334, 0.11f);
        t.chevron = Color::rgb(0x5A5253);
        t.paw = Color::rgb(0x7A7072);
        t.contactShadow = Color::rgb(0x503C3C, 0.16f);
    }
    // With transparency off the glass is one flat colour (DESIGN.md §3): the
    // border stays, the clear rim, the caustic and the frosted cat go.
    if (t.opaque) {
        t.glassTop = t.glassBottom = dark ? Color::rgb(0x2B2729) : Color::rgb(0xF4F2F6);
        t.rimTint = t.rimShade = kClear;
        t.causticCount = 0;
        t.frostOpacity = 0;
    }
    t.pillText = Color{1, 1, 1, 1};
    t.pillHighlight = Color{1, 1, 1, dark ? 0.22f : 0.38f};
    return t;
}

} // namespace meowpuut::ui
