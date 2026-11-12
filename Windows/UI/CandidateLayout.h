#pragma once
// Platform-neutral layout of the candidate window (docs/DESIGN.md §4–§5).
// The Direct2D window, the SVG snapshot and the unit tests all use this one
// result, so they agree on shapes and positions. Text widths come from the
// measurer and differ with the font.
#include "CatSpriteData.h"

#include <memory>
#include "GlassShape.h"

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace meowpuut::ui {

// The bar's frame was laid out around the earlier cat art: a 56 DIP picture
// of 米米莫 behind the glass's left end, a 48 DIP picture of 莫莫 on its right
// end and a tail under the capsule. The design is frozen, so that art's
// measurements stay as the constants they have become. They place the glass,
// the first candidate, the expand arrow and the window's edges. The cats
// drawn now (CatSpriteData.h) feed into none of them.
struct BarFrame {
    // The two boxes the frame was measured from, one at each end of the glass.
    static constexpr float leftHeight = 56, leftWidth = leftHeight * 0.9135f;
    static constexpr float rightHeight = 48, rightWidth = rightHeight * 1.0543f;
    // How far down each box the line one DIP above the glass body crosses it.
    static constexpr float leftSeat = 0.5378f, rightSeat = 0.5003f;
    // Fractions of the left box's width: where the glass starts, where the
    // first candidate starts, and up to where the cloud has no bump.
    static constexpr float glassStart = 0.24f, rowStart = 0.92f, cloudClear = 0.79f;
    // This much of the expand arrow's box lies inside the right box.
    static constexpr float arrowInset = 14;
    // The strip under the capsule's left end; it ends one DIP below the glass.
    static constexpr float stripHeight = 21, stripAspect = 4.0205f;
};

enum class Cat { Mimimo, Momo };

// A cat's answer to a click: a transform of its picture about the paw anchor.
struct CatReaction {
    float tilt = 0;                // degrees, positive leans right
    float scaleX = 1, scaleY = 1;
    float shift = 0;               // DIPs sideways
};

// The reaction `t` of the way through (0…1): 米米莫 squashes and shakes his
// head, 莫莫 tilts hers.
CatReaction catReaction(Cat cat, float t);

// A festival day (Festival.h): the dressed cats as the bar seats them (behind
// the glass, the edge across the chest, no paws in front) and the size of the
// right end's ornament cluster in DIPs (0 for none).
struct FestivalArt {
    CatSprite mimimo{}, momo{};
    float rightWidth = 0, rightHeight = 0;
};

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
    bool decorations = true;              // false in high contrast: no cats
    std::optional<std::size_t> hovered;   // candidate under the pointer
    std::optional<std::size_t> pressed;   // candidate under a held mouse button
    CatReaction reactions[2];             // indexed by Cat; the window sets them after a click
    std::shared_ptr<const FestivalArt> festival;  // null on an ordinary day
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
    float cellPad = 9, numberGap = 5, pawGap = 3, cellGap = 2;
    float pawRatio = 0.45f;                       // paw mark size, in font sizes
    float pillRatio = 0.70f;
    float chevronWidth = 22;
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

// Where a cat's picture and its measurements come from.
struct CatArt {
    const CatSprite *sprite = nullptr;
    std::string image;             // file name in Assets
};

// The picture each cat is drawn with. A festival that brings its own sprite substitutes it here.
CatArt catArt(Cat cat);

// One cat behind the glass with its paws over the top edge
// (CatArrangement.place in Sources/CatSprites.swift).
struct CatPlacement {
    Cat cat = Cat::Mimimo;
    const CatSprite *sprite = nullptr;
    RectF frame;                   // the picture's whole canvas, one scale both ways
    PointF anchor;                 // between the paws, on the glass's edge
    RectF body() const;            // the opaque part
    RectF touch() const;           // the opaque part above the glass: what a click lands on
    RectF paw(int index) const;    // a paw's box in front of the glass, from the anchor row down
};

struct CandidateLayout {
    Metrics metrics;
    SizeF size;                    // window size in DIPs, shadow margins included
    RectF content;                 // the glass and the room kept for the cats: what must stay on screen
    GlassShape shape = GlassShape::Capsule;
    bool expanded = false;
    bool decorations = true;
    GlassOutline glass;            // the bar, or the expanded panel
    std::vector<CatPlacement> cats; // in drawing order; a cat with no level seat is left out
    std::shared_ptr<const FestivalArt> festival; // keeps the dressed sprites the cats point to
    bool dressed = false;          // the cats are the festival's
    std::optional<RectF> ornament; // the festival's cluster at the bar's right end
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
// True where the window shows something a click can land on: the glass or a
// cat. The shadow around them lets clicks through.
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
// under the caret; it goes above when there is no room below. `content` stays
// inside `work` (the monitor work area); the shadow may hang out.
PixelPoint placeNearCaret(const CandidateLayout &layout, float scale, PixelRect caret, PixelRect work);

// Keeps the head and tail of `text` around "…" so it fits `maxWidth`.
// Never cuts inside a UTF-8 sequence, between a character and its combining
// marks or variation selector, inside a joined emoji or inside a flag.
std::string ellipsizeMiddle(std::string_view text, float maxWidth, float size, bool medium, TextMeasurer &measurer);

std::vector<std::string_view> utf8CodePoints(std::string_view text);

} // namespace meowpuut::ui
