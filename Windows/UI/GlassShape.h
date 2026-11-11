#pragma once
// The three glass silhouettes of docs/DESIGN.md §2 (capsule, wave, cloud).
// One sampled envelope feeds every consumer: the Direct2D path geometry, the
// SVG snapshot and hit testing, so a preview shows the outline the window draws.
#include "Geometry.h"
#include <string_view>

namespace meowpuut::ui {

enum class GlassShape { Capsule, Wave, Cloud };

GlassShape parseGlassShape(std::string_view name); // "capsule" | "wave" | "cloud"; anything else → Capsule
const char *glassShapeName(GlassShape shape);

struct GlassOutline {
    BezierPath path;              // closed, clockwise in screen coordinates
    std::vector<PointF> polygon;  // the samples behind `path` (hit testing, shadow, tests)
    RectF bounds;                 // the rectangle the outline was fitted to
    RectF body;                   // band the content is centred in (no wave swell, no cloud bumps)
    float radius = 0;             // corner radius of the body
};

// Collapsed candidate bar. `rect` is the full glass box: cloud bumps reach its
// top edge and the wave swells stay inside it. The cloud has no bump over the
// first `clearLeft` DIPs, where 米米莫's eyes look over the edge.
GlassOutline makeBarOutline(GlassShape shape, RectF rect, float clearLeft = 0);

// Expanded panel: a rounded rectangle whose top (and, for the wave, bottom)
// edge carries the same treatment as the bar. `barHeight` is the height of the
// collapsed bar's glass box; it scales the swell and bumps so the panel
// matches the bar it grew from.
GlassOutline makePanelOutline(GlassShape shape, RectF rect, float cornerRadius, float barHeight, float clearLeft = 0);

bool outlineContains(const GlassOutline &outline, PointF point);

// The outline moved `distance` DIPs towards the inside: the body within the
// clear rim, and the lip just inside the edge.
GlassOutline insetOutline(const GlassOutline &outline, float distance);

// A run of the top or bottom edge, left to right, between the given fractions
// of the width and moved `inset` DIPs inside the outline.
enum class OutlineEdge { Top, Bottom };
std::vector<PointF> outlineRun(const GlassOutline &outline, OutlineEdge edge, float inset, float from, float to);

// The specular streak along the upper-left rim: a run of the top edge.
std::vector<PointF> outlineHighlight(const GlassOutline &outline, float inset, float from, float to);

// Wave and cloud constants shared with the other clients (DESIGN.md §2).
float waveAmplitude(float barHeight);
float cloudRise(float barHeight); // how far the bumps rise above the body

} // namespace meowpuut::ui
