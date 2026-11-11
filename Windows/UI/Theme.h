#pragma once
// Colour tokens of docs/DESIGN.md §3–§5 for the candidate window.
#include <cstdint>
#include <string_view>

namespace meowpuut::ui {

struct Color {
    float r = 0, g = 0, b = 0, a = 1;
    static constexpr Color rgb(std::uint32_t hex, float alpha = 1) {
        return {static_cast<float>((hex >> 16) & 0xff) / 255.0f, static_cast<float>((hex >> 8) & 0xff) / 255.0f,
                static_cast<float>(hex & 0xff) / 255.0f, alpha};
    }
    constexpr Color withAlpha(float alpha) const { return {r, g, b, alpha}; }
};

enum class Appearance { Light, Dark };
enum class Palette { Rose, Ocean, Sage, Lavender, Amber, Graphite };

Palette parsePalette(std::string_view name);

// A layered window gets no live backdrop blur. Text behind a see-through bar
// would run into the candidates, so the body is nearly opaque and only the rim
// lets the background (and 米米莫) show through.
struct Theme {
    Appearance appearance = Appearance::Light;
    bool opaque = false;         // transparency off or high contrast: flat fill, no rim, no caustic
    bool highContrast = false;   // system colours, no decoration

    Color shadow;                // soft drop shadow under the glass
    float shadowBlur = 12, shadowOffsetY = 5;
    Color keyShadow;             // tight shadow that lifts the bar off a white page
    float keyShadowBlur = 3, keyShadowOffsetY = 1;
    Color tint;                  // the palette's colour in this appearance (`P` in DESIGN.md §3)
    float tintOpacity = 0;       // how much of it lies over the glass
    Color glassTop, glassBottom; // body, top to bottom
    float rimWidth = 3;          // clear band between the outline and the body
    Color rimTint;               // what the rim lays over the background
    Color rimShade;              // thin line where the body meets the rim
    Color refraction;            // soft bright band where the body meets the rim
    Color edge;                  // hairline on the outline
    Color lipStart, lipMid, lipEnd; // lip just inside the outline: top-left, 55% of the way, bottom-right
    Color highlight;             // short specular arc at the top-left
    Color caustic[6];            // band of colour along the lower rim
    int causticCount = 6;
    float causticOpacity = 0.8f;
    float spillOpacity = 0;      // the caustic's colours on the lower half of the body
    float frostOpacity = 0;      // the blurred copy of 米米莫 on the body
    Color separator;             // header rule in the expanded panel

    Color text, secondaryText, number, disabledText;
    Color pillTop, pillBottom, pillText;
    Color pillHighlight;         // hairline of light on the pill's upper edge
    Color pillGlow;              // soft light inside its lower edge
    float pillShadowOpacity = 0; // its shadow, in pillBottom's colour
    Color hover, pressed;        // pointer-over and mouse-down washes
    Color chevron;
    Color paw;
    Color contactShadow;         // under 莫莫's chin on the glass
};

Theme makeTheme(Appearance appearance, Palette palette, bool transparencyEnabled, bool highContrast,
                Color systemWindow = {}, Color systemText = {}, Color systemHighlight = {},
                Color systemHighlightText = {}, Color systemGrayText = {});

} // namespace meowpuut::ui
