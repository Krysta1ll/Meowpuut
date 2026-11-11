#pragma once
// The candidate window as a flat list of drawing commands. Layout and theme go
// in; the SVG snapshot and the Direct2D window both replay this one list, so
// a preview shows the same shapes, positions and colours. Text widths differ
// with the font.
#include "CandidateLayout.h"
#include "Theme.h"

#include <cstddef>
#include <string>
#include <vector>

namespace meowpuut::ui {

struct GradientStop {
    float offset = 0;
    Color color;
};

// Neighbouring stops that differ in alpha share one RGB, so renderers that
// blend straight and renderers that blend premultiplied agree.
struct Paint {
    enum class Kind { Solid, Linear, Radial };
    Kind kind = Kind::Solid;
    Color color;                      // Solid
    PointF from, to;                  // Linear: end points. Radial: centre in `from`, radii in `to`.
    std::vector<GradientStop> stops;  // Linear and Radial
};

enum class Sprite { MimimoBack, Mimimo, MimimoFront, MomoBack, Momo, MomoFront, Tail };
enum class TextAlign { Leading, Center, Trailing };

struct DrawCommand {
    enum class Kind {
        Shadow,         // outline `path` in paint.color, blurred by `blur`, moved down `offsetY`
        FillPath,       // outline `path`
        StrokePath,     // outline `path`, `width`
        PushClip,       // clip to outline `path` until the matching PopClip
        PopClip,
        StrokeLine,     // open polyline `points`, `width`, round caps and joins
        FillRoundRect,  // `rect` with corner `radius`
        FillEllipse,    // inscribed in `rect`, turned `rotation` degrees about its centre
        Image,          // `sprite` stretched to `rect` at `opacity`, blurred by `blur`
        Text            // one line of `text`; `x` is its leading edge, centre or trailing edge
    };
    Kind kind = Kind::FillPath;
    std::size_t path = 0;             // index into Scene::outlines
    std::vector<PointF> points;
    RectF rect;
    float radius = 0, rotation = 0, width = 0;
    Paint paint;                      // Text uses paint.color
    float blur = 0, offsetY = 0;      // Gaussian sigma, in DIPs
    Sprite sprite = Sprite::Mimimo;
    float opacity = 1;
    std::string text;
    float x = 0, baseline = 0, size = 0;
    bool medium = false;              // font weight 500 instead of 400
    TextAlign align = TextAlign::Leading;
};

struct Scene {
    SizeF size;                          // window size in DIPs
    std::vector<GlassOutline> outlines;
    std::vector<DrawCommand> commands;   // back to front
    CatFace mimimoFace = CatFace::Open, momoFace = CatFace::Open;
};

Scene buildScene(const CandidateLayout &layout, const CandidateViewModel &model, const Theme &theme);

// Baseline below a text line's visual centre, as a fraction of the font size.
constexpr float kIdeographCenterToBaseline = 0.37f;
constexpr float kDigitCenterToBaseline = 0.36f;

// Gaussian sigma, in DIPs, of the copy of 米米莫 that shows on the glass body.
constexpr float kFrostBlur = 3.0f;

} // namespace meowpuut::ui
