#include "Blur.h"

#include <algorithm>
#include <cmath>

namespace meowpuut::ui {
namespace {

// Box sizes whose three passes approximate a Gaussian of `sigma`.
void boxesForGauss(float sigma, int sizes[3]) {
    const float ideal = std::sqrt(12 * sigma * sigma / 3 + 1);
    int lower = static_cast<int>(std::floor(ideal));
    if (lower % 2 == 0) --lower;
    const int upper = lower + 2;
    const auto w = static_cast<float>(lower);
    const float m = (12 * sigma * sigma - 3 * w * w - 12 * w - 9) / (-4 * w - 4);
    const int split = static_cast<int>(std::lround(m));
    for (int i = 0; i < 3; ++i) sizes[i] = i < split ? lower : upper;
}

// One box pass of radius `r` along every line. `step` is the distance between
// neighbours on a line and `lineStride` the distance between lines, both in
// bytes, so the same code runs along rows and along columns. Samples outside
// the image count as transparent.
void boxPass(const std::uint8_t *src, std::uint8_t *dst, int lines, int length, int lineStride, int step,
             int channels, int r) {
    if (r <= 0) {
        for (int line = 0; line < lines; ++line)
            for (int i = 0; i < length; ++i)
                for (int c = 0; c < channels; ++c)
                    dst[line * lineStride + i * step + c] = src[line * lineStride + i * step + c];
        return;
    }
    const int window = 2 * r + 1;
    const int reciprocal = (65536 + window / 2) / window; // 16.16 fixed point
    for (int line = 0; line < lines; ++line) {
        const std::uint8_t *in = src + line * lineStride;
        std::uint8_t *out = dst + line * lineStride;
        for (int c = 0; c < channels; ++c) {
            int sum = 0;
            for (int i = 0; i <= r && i < length; ++i) sum += in[i * step + c];
            for (int i = 0; i < length; ++i) {
                out[i * step + c] = static_cast<std::uint8_t>(std::min(255, (sum * reciprocal + 32768) >> 16));
                const int add = i + r + 1, drop = i - r;
                if (add < length) sum += in[add * step + c];
                if (drop >= 0) sum -= in[drop * step + c];
            }
        }
    }
}

void blur(std::vector<std::uint8_t> &pixels, int width, int height, int channels, float sigma) {
    if (sigma < 0.5f || width <= 0 || height <= 0) return;
    if (pixels.size() < static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * static_cast<std::size_t>(channels))
        return;
    int boxes[3];
    boxesForGauss(sigma, boxes);
    std::vector<std::uint8_t> scratch(pixels.size());
    for (int pass = 0; pass < 3; ++pass) {
        const int r = (boxes[pass] - 1) / 2;
        boxPass(pixels.data(), scratch.data(), height, width, width * channels, channels, channels, r); // rows
        boxPass(scratch.data(), pixels.data(), width, height, channels, width * channels, channels, r); // columns
    }
}

} // namespace

void blurPremultiplied(std::vector<std::uint8_t> &pixels, int width, int height, float sigma) {
    blur(pixels, width, height, 4, sigma);
}

AlphaMask shadowMask(const std::vector<PointF> &polygon, SizeF size, float pixelsPerDip, float sigma) {
    AlphaMask mask;
    if (polygon.size() < 3 || pixelsPerDip <= 0 || size.width <= 0 || size.height <= 0) return mask;
    mask.width = std::min(8192, static_cast<int>(std::ceil(size.width * pixelsPerDip)));
    mask.height = std::min(8192, static_cast<int>(std::ceil(size.height * pixelsPerDip)));
    mask.alpha.assign(static_cast<std::size_t>(mask.width) * static_cast<std::size_t>(mask.height), 0);

    // Scanline fill: each row is sampled at its centre; the two ends of a span
    // cover their pixels in proportion. The blur hides the remaining steps.
    std::vector<float> crossings;
    for (int row = 0; row < mask.height; ++row) {
        const float y = (static_cast<float>(row) + 0.5f) / pixelsPerDip;
        crossings.clear();
        for (std::size_t i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            const PointF &a = polygon[i], &b = polygon[j];
            if ((a.y > y) == (b.y > y)) continue;
            crossings.push_back((a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y)) * pixelsPerDip);
        }
        std::sort(crossings.begin(), crossings.end());
        std::uint8_t *line = mask.alpha.data() + static_cast<std::size_t>(row) * static_cast<std::size_t>(mask.width);
        for (std::size_t k = 0; k + 1 < crossings.size(); k += 2) {
            const float x0 = std::max(0.0f, crossings[k]);
            const float x1 = std::min(static_cast<float>(mask.width), crossings[k + 1]);
            if (x1 <= x0) continue;
            const int first = static_cast<int>(std::floor(x0));
            const int last = std::min(mask.width - 1, static_cast<int>(std::floor(x1)));
            for (int px = first; px <= last; ++px) {
                const float cover = std::min(x1, static_cast<float>(px + 1)) - std::max(x0, static_cast<float>(px));
                if (cover <= 0) continue;
                const int value = line[px] + static_cast<int>(std::lround(std::min(1.0f, cover) * 255));
                line[px] = static_cast<std::uint8_t>(std::min(255, value));
            }
        }
    }
    blur(mask.alpha, mask.width, mask.height, 1, sigma * pixelsPerDip);
    return mask;
}

} // namespace meowpuut::ui
