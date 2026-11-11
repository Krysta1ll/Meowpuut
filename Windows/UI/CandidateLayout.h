#pragma once
// Platform-neutral layout of the candidate window (docs/DESIGN.md §4–§5).
// The Direct2D window, the SVG snapshot and the unit tests all use this one
// result, so they agree on shapes and positions. Text widths come from the
// measurer and differ with the font.
#include "GlassShape.h"

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace meowpuut::ui {

// Anchors from assets/brand/cats/cats.json (fractions of the sprite box).
struct CatAnchors {
    static constexpr float mimimoAspect = 0.9135f;
    static constexpr float mimimoEyeBottom = 0.5378f;
    // Measured on mimimo_open.png: the span of his two eyes, and how far right
    // his body reaches below the eyes.
    static constexpr float mimimoEyesLeft = 0.33f, mimimoEyesRight = 0.79f;
    static constexpr float mimimoBodyRight = 0.92f;
    static constexpr float momoAspect = 1.0543f;
    static constexpr float momoEyeLine = 0.5003f;
    static constexpr float tailAspect = 4.0205f;
};

enum class CatFace { Open, Happy, Blink };

struct CandidateViewModel {
    std::vector<std::string> candidates;  // the current page, in engine order
    std::vector<bool> japanese;           // parallel to candidates
    std::size_t highlighted = 0;
    std::size_t page = 0;
    bool lastPage = true;
    bool expanded = false;
    bool shiftNumbers = false;            // Zhuyin selects with Shift+digit: label "⇧1"
    std::string preedit;                  // shown in the expanded header
    std::string modeLabel;                // e.g. "拼 · 繁"
    GlassShape shape = GlassShape::Capsule;
    bool decorations = true;              // false in high contrast: no cats, no tail
    std::optional<std::size_t> hovered;   // candidate under the pointer
    std::optional<std::size_t> pressed;   // candidate under a held mouse button
    CatFace mimimoFace = CatFace::Open;
    CatFace momoFace = CatFace::Open;
};

class TextMeasurer {
public:
    virtual ~TextMeasurer() = default;
    // Advance width of one line of UTF-8 text, in DIPs.
    virtual float measure(std::string_view utf8, float size, bool medium) = 0;
};

struct LayoutLimits {
    float maxWidth = 960;  // the monitor work area, in DIPs
};

// The desktop numbers of docs/DESIGN.md §5; what differs on Windows is listed there.
struct Metrics {
    float font = 15, numberFont = 11, headerFont = 12;
    float barHeight = 38;                         // the body band candidates sit in
    float mimimoHeight = 56, momoHeight = 48, tailHeight = 21;
    float cellPad = 9, numberGap = 5, pawGap = 3, cellGap = 2;
    float pawRatio = 0.45f;                       // paw mark size, in font sizes
    float pillRatio = 0.70f;
    float chevronWidth = 22, chevronUnderMomo = 14; // this much of the chevron box is under 莫莫's cheek
    float padLeft = 14, padTop = 4, padRight = 14, padBottom = 18; // room for the drop shadow
    float minRow = 120;                           // a short page still reads as a bar
    // One candidate is cut in the middle past this width or past this share
    // of the row, whichever is smaller.
    float longCandidateMax = 440, ellipsisRatio = 0.70f;
    // Expanded panel
    float panelCorner = 18, panelMinWidth = 320, panelMaxWidth = 560;
    float gridCellHeight = 30, gridRowGap = 4, gridColumnGap = 4, gridMinCell = 108;
    float headerHeight = 24, footerHeight = 30;
};

struct CandidateCell {
    std::size_t index = 0;
    RectF frame;           // hit area
    RectF pill;            // selection / hover capsule
    std::string number;    // "1", "⇧1"
    float numberX = 0, numberWidth = 0;
    std::string text;      // possibly middle-ellipsised
    float textX = 0, textWidth = 0;
    float centerY = 0;     // vertical centre of the text line
    bool japanese = false;
    RectF paw;             // the paw marker box, when japanese
    bool truncated = false;
};

struct CandidateLayout {
    Metrics metrics;
    SizeF size;                    // window size in DIPs, shadow margins included
    RectF content;                 // glass, cats and tail: what must stay on screen
    GlassShape shape = GlassShape::Capsule;
    bool expanded = false;
    bool decorations = true;
    GlassOutline glass;            // the bar, or the expanded panel
    RectF mimimo, momo, tail;
    bool showTail = false;
    RectF momoContactShadow;
    std::vector<CandidateCell> cells;
    RectF chevron;                 // expand / collapse
    bool chevronUp = false;
    // Aligned to the caret: the left edge of the first candidate. The expanded
    // panel keeps the same value, so the glass does not move when it opens.
    float anchorX = 0;
    // Expanded only
    std::string preedit, modeLabel;
    float headerY = 0;             // centre line of the header
    float preeditX = 0, modeRight = 0;
    RectF prevPage, nextPage;
    bool canPrev = false, canNext = false;
    std::string pageLabel;
    PointF pageLabelCenter;
};

enum class HitKind { None, Candidate, Expand, PrevPage, NextPage, Mimimo, Momo };
struct Hit {
    HitKind kind = HitKind::None;
    std::size_t index = 0;
};

CandidateLayout layoutCandidates(const CandidateViewModel &model, TextMeasurer &measurer,
                                 const LayoutLimits &limits = {}, const Metrics &metrics = {});
Hit hitTest(const CandidateLayout &layout, PointF point);
// True where the window shows something a click can land on: the glass, a cat
// or the tail. The shadow around them lets clicks through.
bool coversPoint(const CandidateLayout &layout, PointF point);

// Screen coordinates in physical pixels.
struct PixelRect {
    long left = 0, top = 0, right = 0, bottom = 0;
};
struct PixelPoint {
    long x = 0, y = 0;
};

// Top-left corner for a window showing `layout` at `scale` pixels per DIP.
// `anchorX` lines up with the caret's left edge and the window sits
// under the caret; it goes above when there is no room below. The glass and
// the cats stay inside `work` (the monitor work area); the shadow may hang out.
PixelPoint placeNearCaret(const CandidateLayout &layout, float scale, PixelRect caret, PixelRect work);

// Keeps the head and tail of `text` around "…" so it fits `maxWidth`.
// Never cuts inside a UTF-8 sequence, between a character and its combining
// marks or variation selector, inside a joined emoji or inside a flag.
std::string ellipsizeMiddle(std::string_view text, float maxWidth, float size, bool medium, TextMeasurer &measurer);

std::vector<std::string_view> utf8CodePoints(std::string_view text);

} // namespace meowpuut::ui
