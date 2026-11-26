#pragma once
// The 字景 strip's text on Windows: DirectWrite through a GDI-interop bitmap render target in aliased mode (CPU only,
// no GPU device), in Cubic 11 (俐方體 11, the family "Meowpuut Pixel A") at 12 x k px, with Fusion Pixel ("Meowpuut
// Pixel B") for what Cubic 11 lacks and the system's fallback for the rest. Both fonts are SIL OFL 1.1 files next to
// the DLL (Assets\fonts, THIRD_PARTY.md §10), loaded privately into an isolated DirectWrite factory's own font
// collection: nothing is installed, and no other program sees them.
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include "GlyphStrip.h"

#include <memory>
#include <string>

namespace meowpuut::ui {

class DirectWriteGlyphText : public GlyphTextEngine {
public:
    // `fontDirectory` holds pixel_a.ttf and (optionally) pixel_b.otf. Null when DirectWrite or pixel_a.ttf is missing.
    static std::unique_ptr<DirectWriteGlyphText> create(const std::wstring &fontDirectory);
    ~DirectWriteGlyphText() override;

    double measure(std::string_view utf8) override;
    bool raster(std::string_view utf8, unsigned k, GlyphMask &out) override;
    // Simplified Chinese picks zh-CN for the system fallback's glyph forms. Drops the caches when it changes.
    void setSimplified(bool on);

private:
    DirectWriteGlyphText();
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace meowpuut::ui
#endif
