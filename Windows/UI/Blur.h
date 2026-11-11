#pragma once
// Gaussian-like blur (three box passes) for the two soft things the window
// draws: the frosted copy of 米米莫 behind the glass and the drop shadow.
// Both are small, so this runs on the CPU and needs no Direct2D effects.
#include "Geometry.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace meowpuut::ui {

// `pixels` holds width*height*4 bytes of premultiplied BGRA, rows tightly
// packed. `sigma` is the Gaussian sigma in pixels; below 0.5 nothing changes.
void blurPremultiplied(std::vector<std::uint8_t> &pixels, int width, int height, float sigma);

struct AlphaMask {
    int width = 0, height = 0;
    std::vector<std::uint8_t> alpha; // one byte per pixel, row by row
};

// The shadow of a closed polygon: its coverage, blurred. The mask spans
// `size` DIPs at `pixelsPerDip`; `sigma` is in DIPs. A shadow has no detail,
// so one pixel per DIP is enough at any display scale.
AlphaMask shadowMask(const std::vector<PointF> &polygon, SizeF size, float pixelsPerDip, float sigma);

} // namespace meowpuut::ui
