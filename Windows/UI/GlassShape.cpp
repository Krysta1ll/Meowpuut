#include "GlassShape.h"

#include <cmath>

namespace meowpuut::ui {
namespace {
constexpr float kPi = 3.14159265358979f;

struct Bump { float fx, radius, peak; }; // fraction of width; radius and peak in bar heights
constexpr Bump kBumps[] = {{0.15f, 0.58f, 0.00f}, {0.47f, 0.75f, 0.05f}, {0.77f, 0.56f, 0.07f}};
constexpr float kCloudStretchFrom = 8.0f; // bar width, in bar heights, where the bumps start to stretch

float smin(float a, float b, float k) {
    if (k <= 0) return std::min(a, b);
    const float h = clampf(0.5f + 0.5f * (b - a) / k, 0, 1);
    return b * (1 - h) + a * h - k * h * (1 - h);
}

// Top/bottom of a rounded rectangle `body` with corner radius `r` at x.
float roundedTop(const RectF &body, float r, float x) {
    const float dl = body.x + r - x, dr = x - (body.right() - r);
    const float d = std::max(dl, dr);
    if (d <= 0) return body.y;
    const float dd = std::min(d, r);
    return body.y + r - std::sqrt(std::max(0.0f, r * r - dd * dd));
}
float roundedBottom(const RectF &body, float r, float x) {
    return body.y + body.bottom() - roundedTop(body, r, x);
}

// x positions: angular steps across each corner (where the outline turns
// vertical) and even steps along the straight run between them.
std::vector<float> sampleXs(const RectF &body, float r) {
    std::vector<float> xs;
    const int cornerSteps = 22;
    for (int i = 0; i <= cornerSteps; ++i) {
        const float t = static_cast<float>(i) / cornerSteps * (kPi / 2);
        xs.push_back(body.x + r - r * std::cos(t));
    }
    const float runStart = body.x + r, runEnd = body.right() - r;
    const float run = runEnd - runStart;
    const int runSteps = std::max(2, static_cast<int>(std::ceil(run / 1.5f)));
    for (int i = 1; i < runSteps; ++i) xs.push_back(runStart + run * static_cast<float>(i) / static_cast<float>(runSteps));
    for (int i = cornerSteps; i >= 0; --i) {
        const float t = static_cast<float>(i) / cornerSteps * (kPi / 2);
        xs.push_back(body.right() - r + r * std::cos(t));
    }
    return xs;
}

// Catmull–Rom through the polygon with chord-scaled tangents gives a smooth
// closed Bézier outline without overshooting uneven sample spacing.
BezierPath smoothClosed(const std::vector<PointF> &pts) {
    BezierPath path;
    const std::size_t n = pts.size();
    if (n < 3) return path;
    path.start = pts[0];
    const auto dist = [](PointF a, PointF b) { return std::hypot(b.x - a.x, b.y - a.y); };
    for (std::size_t i = 0; i < n; ++i) {
        const PointF p0 = pts[(i + n - 1) % n], p1 = pts[i], p2 = pts[(i + 1) % n], p3 = pts[(i + 2) % n];
        const float d01 = std::max(1e-4f, dist(p0, p1)), d12 = std::max(1e-4f, dist(p1, p2)), d23 = std::max(1e-4f, dist(p2, p3));
        const float s1 = d12 / (d01 + d12) / 1.5f, s2 = d12 / (d12 + d23) / 1.5f;
        const PointF c1{p1.x + (p2.x - p0.x) * s1, p1.y + (p2.y - p0.y) * s1};
        const PointF c2{p2.x - (p3.x - p1.x) * s2, p2.y - (p3.y - p1.y) * s2};
        path.segments.push_back({c1, c2, p2});
    }
    return path;
}

struct Envelope {
    RectF body;
    float radius = 0;
    // Wave: vertical offset of both edges along the straight run.
    float waveAmp = 0;
    int waves = 0;
    // Cloud: bumps above the body.
    bool cloud = false;
    float barHeight = 0;
    float clearLeft = 0;
    RectF box;

    float offset(float x) const {
        if (waveAmp <= 0) return 0;
        const float runStart = body.x + radius, run = body.width - 2 * radius;
        if (run <= 0) return 0;
        const float u = clampf((x - runStart) / run, 0, 1);
        const float env = smoothstep(0, 0.12f, u) * smoothstep(0, 0.12f, 1 - u);
        return -waveAmp * std::sin(2 * kPi * static_cast<float>(waves) * u) * env;
    }
    float top(float x) const {
        float y = roundedTop(body, radius, x) + offset(x);
        if (cloud) {
            // On a bar wider than a phone's the bumps stretch sideways, so
            // each keeps about the share of the width it has there.
            const float stretch = std::max(1.0f, box.width / (kCloudStretchFrom * barHeight));
            for (const auto &bump : kBumps) {
                const float r = bump.radius * barHeight;
                const float cy = box.y + (bump.peak + bump.radius) * barHeight;
                // Keep each bump's reach inside the straight section and off
                // the cat's eyes (same rule as Sources/GlassShapes.swift).
                const float half = std::sqrt(std::max(0.0f, r * r - (cy - body.y) * (cy - body.y)));
                const float reach = half * stretch + 0.10f * barHeight + 2;
                const float lower = box.x + std::max(radius, clearLeft) + reach, upper = box.right() - radius - reach;
                const float cx = upper > lower ? clampf(box.x + bump.fx * box.width, lower, upper) : box.midX();
                const float dx = (x - cx) / stretch;
                if (std::fabs(dx) >= r) continue;
                const float by = cy - std::sqrt(r * r - dx * dx);
                y = smin(y, by, 0.10f * barHeight);
            }
            y = std::max(y, box.y);
        }
        return y;
    }
    float bottom(float x) const { return roundedBottom(body, radius, x) + offset(x); }
};

// Unit normal at vertex i pointing into a clockwise polygon (y grows down).
PointF inwardNormal(const std::vector<PointF> &poly, std::size_t i) {
    const auto &a = poly[i == 0 ? poly.size() - 1 : i - 1];
    const auto &b = poly[(i + 1) % poly.size()];
    const float nx = -(b.y - a.y), ny = b.x - a.x;
    const float len = std::hypot(nx, ny);
    if (len < 1e-4f) return {0, 0};
    return {nx / len, ny / len};
}

GlassOutline build(const Envelope &env) {
    GlassOutline out;
    out.bounds = env.box;
    out.body = env.body;
    out.radius = env.radius;
    const auto xs = sampleXs(env.body, env.radius);
    std::vector<PointF> poly;
    poly.reserve(xs.size() * 2);
    for (float x : xs) poly.push_back({x, env.top(x)});
    // Right side: a vertical run when the corner radius is below half height.
    const float xr = xs.back();
    if (env.bottom(xr) - env.top(xr) > 0.5f) poly.push_back({xr, env.bottom(xr)});
    for (auto it = xs.rbegin() + 1; it != xs.rend(); ++it) poly.push_back({*it, env.bottom(*it)});
    const float xl = xs.front();
    if (env.bottom(xl) - env.top(xl) <= 0.5f) poly.pop_back(); // closing point equals the start
    // Drop consecutive duplicates; Catmull–Rom needs distinct neighbours.
    std::vector<PointF> clean;
    for (const auto &p : poly)
        if (clean.empty() || std::hypot(p.x - clean.back().x, p.y - clean.back().y) > 0.05f) clean.push_back(p);
    if (clean.size() > 1 && std::hypot(clean.front().x - clean.back().x, clean.front().y - clean.back().y) < 0.05f)
        clean.pop_back();
    out.polygon = clean;
    out.path = smoothClosed(clean);
    return out;
}
} // namespace

GlassShape parseGlassShape(std::string_view name) {
    if (name == "wave") return GlassShape::Wave;
    if (name == "cloud") return GlassShape::Cloud;
    return GlassShape::Capsule;
}

const char *glassShapeName(GlassShape shape) {
    switch (shape) {
    case GlassShape::Wave: return "wave";
    case GlassShape::Cloud: return "cloud";
    case GlassShape::Capsule: break;
    }
    return "capsule";
}

float waveAmplitude(float barHeight) { return 0.065f * barHeight; }
float cloudRise(float barHeight) { return 0.16f * barHeight; }

GlassOutline makeBarOutline(GlassShape shape, RectF rect, float clearLeft) {
    Envelope env;
    env.box = rect;
    env.barHeight = rect.height;
    env.clearLeft = clearLeft;
    switch (shape) {
    case GlassShape::Capsule:
        env.body = rect;
        break;
    case GlassShape::Wave:
        env.waveAmp = waveAmplitude(rect.height);
        env.waves = std::max(2, static_cast<int>(std::lround(rect.width / 170.0f)));
        env.body = rect.inset(0, env.waveAmp);
        break;
    case GlassShape::Cloud:
        env.cloud = true;
        env.body = {rect.x, rect.y + cloudRise(rect.height), rect.width, rect.height - cloudRise(rect.height)};
        break;
    }
    env.radius = std::min(env.body.height / 2, env.body.width / 2);
    return build(env);
}

GlassOutline makePanelOutline(GlassShape shape, RectF rect, float cornerRadius, float barHeight, float clearLeft) {
    Envelope env;
    env.box = rect;
    env.barHeight = barHeight;
    env.clearLeft = clearLeft;
    switch (shape) {
    case GlassShape::Capsule:
        env.body = rect;
        break;
    case GlassShape::Wave:
        env.waveAmp = waveAmplitude(barHeight);
        env.waves = std::max(2, static_cast<int>(std::lround(rect.width / 170.0f)));
        env.body = rect.inset(0, env.waveAmp);
        break;
    case GlassShape::Cloud:
        env.cloud = true;
        env.body = {rect.x, rect.y + cloudRise(barHeight), rect.width, rect.height - cloudRise(barHeight)};
        break;
    }
    env.radius = std::min({cornerRadius, env.body.height / 2, env.body.width / 2});
    return build(env);
}

GlassOutline insetOutline(const GlassOutline &outline, float distance) {
    GlassOutline out;
    out.bounds = outline.bounds.inset(distance, distance);
    out.body = outline.body.inset(distance, distance);
    out.radius = std::max(0.0f, outline.radius - distance);
    const auto &poly = outline.polygon;
    if (poly.size() < 3) return out;
    out.polygon.reserve(poly.size());
    for (std::size_t i = 0; i < poly.size(); ++i) {
        const PointF n = inwardNormal(poly, i);
        const PointF p{poly[i].x + n.x * distance, poly[i].y + n.y * distance};
        if (out.polygon.empty() || std::hypot(p.x - out.polygon.back().x, p.y - out.polygon.back().y) > 0.05f)
            out.polygon.push_back(p);
    }
    out.path = smoothClosed(out.polygon);
    return out;
}

std::vector<PointF> outlineRun(const GlassOutline &outline, OutlineEdge edge, float inset, float from, float to) {
    std::vector<PointF> out;
    const auto &poly = outline.polygon;
    if (poly.size() < 3) return out;
    // The polygon starts at the left extreme, runs along the top to the right
    // extreme and comes back along the bottom.
    std::size_t rightmost = 0;
    for (std::size_t i = 1; i < poly.size(); ++i)
        if (poly[i].x > poly[rightmost].x) rightmost = i;
    const float x0 = outline.bounds.x + from * outline.bounds.width;
    const float x1 = outline.bounds.x + to * outline.bounds.width;
    const auto take = [&](std::size_t i) {
        const auto &p = poly[i];
        if (p.x < x0 || p.x > x1) return;
        const PointF n = inwardNormal(poly, i);
        if (n.x == 0 && n.y == 0) return;
        out.push_back({p.x + n.x * inset, p.y + n.y * inset});
    };
    if (edge == OutlineEdge::Top) {
        for (std::size_t i = 0; i <= rightmost; ++i) take(i);
    } else {
        // The polygon walks the bottom right to left; read it backwards.
        for (std::size_t i = poly.size() - 1; i > rightmost; --i) take(i);
    }
    return out;
}

std::vector<PointF> outlineHighlight(const GlassOutline &outline, float inset, float from, float to) {
    return outlineRun(outline, OutlineEdge::Top, inset, from, to);
}

bool outlineContains(const GlassOutline &outline, PointF p) {
    const auto &poly = outline.polygon;
    bool inside = false;
    for (std::size_t i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
        const auto &a = poly[i], &b = poly[j];
        if ((a.y > p.y) != (b.y > p.y)) {
            const float x = a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y);
            if (p.x < x) inside = !inside;
        }
    }
    return inside;
}

} // namespace meowpuut::ui
