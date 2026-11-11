#pragma once
// Portable 2D geometry for the candidate window. No platform headers: the same
// code lays out the Direct2D window on Windows and the SVG snapshots and unit
// tests on other systems. All values are device-independent pixels (DIPs).
#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace meowpuut::ui {

struct PointF {
    float x = 0, y = 0;
};

struct SizeF {
    float width = 0, height = 0;
};

struct RectF {
    float x = 0, y = 0, width = 0, height = 0;
    float left() const { return x; }
    float top() const { return y; }
    float right() const { return x + width; }
    float bottom() const { return y + height; }
    float midX() const { return x + width / 2; }
    float midY() const { return y + height / 2; }
    bool empty() const { return width <= 0 || height <= 0; }
    bool contains(PointF p) const { return p.x >= x && p.x < right() && p.y >= y && p.y < bottom(); }
    RectF inset(float dx, float dy) const { return {x + dx, y + dy, width - 2 * dx, height - 2 * dy}; }
    RectF offset(float dx, float dy) const { return {x + dx, y + dy, width, height}; }
};

inline RectF unite(const RectF &a, const RectF &b) {
    if (a.empty()) return b;
    if (b.empty()) return a;
    const float l = std::min(a.x, b.x), t = std::min(a.y, b.y);
    return {l, t, std::max(a.right(), b.right()) - l, std::max(a.bottom(), b.bottom()) - t};
}

// A closed outline made of cubic Bézier segments. Every renderer (Direct2D
// path geometry, SVG path data) consumes this one representation.
struct BezierPath {
    PointF start;
    struct Cubic { PointF c1, c2, end; };
    std::vector<Cubic> segments;
    bool empty() const { return segments.empty(); }
};

struct Circle {
    float cx = 0, cy = 0, r = 0;
};

inline float clampf(float v, float lo, float hi) { return std::max(lo, std::min(hi, v)); }

inline float smoothstep(float edge0, float edge1, float x) {
    const float t = clampf((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3 - 2 * t);
}

} // namespace meowpuut::ui
