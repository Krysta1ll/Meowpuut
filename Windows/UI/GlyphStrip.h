#pragma once
// The 字景 candidate strip of the Windows candidate window (FINAL.md §3), the portable half: no Windows headers, so
// the host tools and tests run it with Core Text standing in for DirectWrite.
//
// Rust (mp_glyph_strip_*, the `glyph` feature of meowpuut-ffi) draws everything but the candidate text. This class
// is the platform's part of the contract in meowpuut.h:
//
//   * it measures each cell's number and text at 12 px through a GlyphTextEngine and hands the widths over;
//   * it keeps the long-strip atlas: one LAND frame per candidate with the sun behind that word, the selected one
//     drawn at once, the others a frame per tick while the strip is idle. ← → Tab then only copies a slice of the
//     atlas into the window's bitmap, draws OVER and CATS (a few hundredths of a millisecond in Rust) over it, and
//     sets the words from cached masks; nothing is rasterised and no land is drawn on the key path;
//   * a letter key changes the candidates, but the ground only moves at the land step 60-160 ms later: until then the
//     key draws OVER, CATS and the words over the previous LAND (cropped, or mirrored at the right if the strip grew),
//     and the new LAND is drawn when land() is due, not on the key;
//   * it keeps the clock: the land follows a letter key 0.6 of the time since the key before (60-160 ms) later, the
//     cats and the bird tick at 12 Hz while the strip is busy, nothing at rest (nextDelayMs / tick).
//
// One thread; the handle is not shared.
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace meowpuut::ui {

// The pixels one line of aliased text covers: 1 per pixel, relative to the pen on the baseline (device pixels; y
// down, so `top` is negative above the baseline).
struct GlyphMask {
    int left = 0, top = 0, width = 0, height = 0;
    std::vector<std::uint8_t> on;
};

// The platform's text: Cubic 11 (and Fusion Pixel for what it lacks) with DirectWrite on Windows.
class GlyphTextEngine {
public:
    virtual ~GlyphTextEngine() = default;
    // The advance of one line at 12 px, in points (fractions are rounded up by Rust).
    virtual double measure(std::string_view utf8) = 0;
    // The line at 12 * k px, aliased, with the pen on the baseline at (0, 0). False when it cannot be drawn.
    virtual bool raster(std::string_view utf8, unsigned k, GlyphMask &out) = 0;
};

enum class GlyphKey : unsigned { Letter = 0, Delete = 1, Space = 2, Return = 3, Other = 4 };

struct GlyphLook {
    bool dark = false;
    int level = 1;      // 0 輕量 ... 3 極致
    bool still = false; // reduce animations
    bool safe = false;  // more contrast / less transparency
};

struct GlyphSize {
    double width = 0, height = 0; // points
    unsigned k = 1;
    // The strip itself, in pixels, and the window that holds it: the strip plus the hard drop shadow of the prototype
    // (2 pt right and down, no blur; black at 0.2, 0.6 at night), so the window is 2 * k pixels larger each way. The
    // strip sits at the window's top-left; hit(), the caret and the work area all go by the strip.
    unsigned stripWidth = 0, stripHeight = 0;
    unsigned pixelWidth = 0, pixelHeight = 0;
};

enum class GlyphHitKind { None, Candidate, Arrow };
struct GlyphHit {
    GlyphHitKind kind = GlyphHitKind::None;
    std::size_t index = 0;
};

class GlyphStripView {
public:
    explicit GlyphStripView(GlyphTextEngine &text);
    ~GlyphStripView();
    GlyphStripView(const GlyphStripView &) = delete;
    GlyphStripView &operator=(const GlyphStripView &) = delete;

    bool valid() const;

    void setLook(const GlyphLook &look);
    // The display's scale (1 = 100 %): returns k, the device pixels a point.
    unsigned setScale(double scale);
    unsigned k() const;
    // The ledger's committed characters: how far the journey has gone (mp_glyph_strip_set_characters).
    void setDistance(std::int64_t committedCharacters);
    // hm = hh * 100 + mm; nowMs the wall clock.
    void setClock(int hm, std::int64_t nowMs);
    void setZhuyin(bool on);
    // Borderless full screen: the one line, nothing moves.
    void setGame(bool on);
    void setExpanded(bool on);
    // The words of the page (UTF-8) and which are Japanese; `selected` is the highlighted one.
    void setCandidates(const std::vector<std::string> &texts, const std::vector<bool> &japanese, std::size_t selected);
    void select(std::size_t index);
    std::size_t selected() const;
    // A key was typed. Letter, Delete, Space and Return schedule the land (see nextDelayMs).
    void key(GlyphKey kind, std::int64_t nowMs);

    GlyphSize size();
    // The strip's left edge to the first word's number, in points: what the caret lines up with.
    double firstWordX() const;

    // Draws the whole strip (land slice, OVER, CATS, then the words) into a top-down premultiplied BGRA bitmap of at
    // least size() pixels. False when the bitmap is too small or the strip is not valid.
    bool present(std::uint8_t *bgra, std::size_t strideBytes, unsigned width, unsigned height);

    // Milliseconds until tick() has something to do, or -1 at rest.
    int nextDelayMs(std::int64_t nowMs);
    // The due work: the land, a 12 Hz step, one more atlas frame. True when the picture changed: present again.
    bool tick(std::int64_t nowMs);
    // The window is hidden: the atlas is let go (the words' masks are kept).
    void idle();

    // A point in the strip, in points from its top-left.
    GlyphHit hit(double xPt, double yPt) const;

    struct Stats {
        std::size_t atlasFrames = 0, atlasBytes = 0, masks = 0;
    };
    Stats stats() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace meowpuut::ui
