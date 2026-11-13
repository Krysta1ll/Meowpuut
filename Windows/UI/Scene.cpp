#include "Scene.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace meowpuut::ui {
namespace {

Paint solid(Color color) {
    Paint p;
    p.color = color;
    return p;
}

Paint linear(PointF from, PointF to, std::vector<GradientStop> stops) {
    Paint p;
    p.kind = Paint::Kind::Linear;
    p.from = from;
    p.to = to;
    p.stops = std::move(stops);
    return p;
}

Paint radial(PointF centre, float rx, float ry, std::vector<GradientStop> stops) {
    Paint p;
    p.kind = Paint::Kind::Radial;
    p.from = centre;
    p.to = {rx, ry};
    p.stops = std::move(stops);
    return p;
}

class Builder {
public:
    Scene scene;

    std::size_t outline(GlassOutline o) {
        scene.outlines.push_back(std::move(o));
        return scene.outlines.size() - 1;
    }
    void shadow(std::size_t path, Color color, float sigma, float offsetY) {
        auto &c = add(DrawCommand::Kind::Shadow);
        c.path = path;
        c.paint = solid(color);
        c.blur = sigma;
        c.offsetY = offsetY;
    }
    void fill(std::size_t path, Paint paint) {
        auto &c = add(DrawCommand::Kind::FillPath);
        c.path = path;
        c.paint = std::move(paint);
    }
    void stroke(std::size_t path, float width, Paint paint) {
        auto &c = add(DrawCommand::Kind::StrokePath);
        c.path = path;
        c.width = width;
        c.paint = std::move(paint);
    }
    void pushClip(std::size_t path) { add(DrawCommand::Kind::PushClip).path = path; }
    void pushClipOut(std::size_t path) { add(DrawCommand::Kind::PushClipOut).path = path; }
    void pushClipOvals(std::vector<RectF> ovals, const Affine &transform) {
        auto &c = add(DrawCommand::Kind::PushClipOvals);
        c.ovals = std::move(ovals);
        c.transform = transform;
    }
    void popClip() { add(DrawCommand::Kind::PopClip); }
    void line(std::vector<PointF> points, float width, Paint paint) {
        if (points.size() < 2) return;
        auto &c = add(DrawCommand::Kind::StrokeLine);
        c.points = std::move(points);
        c.width = width;
        c.paint = std::move(paint);
    }
    void roundRect(const RectF &rect, float radius, Paint paint) {
        auto &c = add(DrawCommand::Kind::FillRoundRect);
        c.rect = rect;
        c.radius = radius;
        c.paint = std::move(paint);
    }
    void ellipse(const RectF &rect, Paint paint, float rotation = 0) {
        auto &c = add(DrawCommand::Kind::FillEllipse);
        c.rect = rect;
        c.rotation = rotation;
        c.paint = std::move(paint);
    }
    void image(Sprite sprite, const RectF &rect, const Affine &transform, Color tint = {0, 0, 0, 0}, float blur = 0) {
        auto &c = add(DrawCommand::Kind::Image);
        c.sprite = sprite;
        c.rect = rect;
        c.transform = transform;
        c.tint = tint;
        c.opacity = tint.a > 0 ? tint.a : 1;
        c.blur = blur;
    }
    void text(const std::string &s, float x, float baseline, float size, bool medium, Color color,
              TextAlign align = TextAlign::Leading) {
        if (s.empty()) return;
        auto &c = add(DrawCommand::Kind::Text);
        c.text = s;
        c.x = x;
        c.baseline = baseline;
        c.size = size;
        c.medium = medium;
        c.paint = solid(color);
        c.align = align;
    }

private:
    DrawCommand &add(DrawCommand::Kind kind) {
        scene.commands.emplace_back();
        scene.commands.back().kind = kind;
        return scene.commands.back();
    }
};

void glass(Builder &b, const CandidateLayout &L, const Theme &t) {
    const RectF box = L.glass.bounds;
    const std::size_t outer = b.outline(L.glass);
    if (t.shadow.a > 0) b.shadow(outer, t.shadow, t.shadowBlur / 2, t.shadowOffsetY);
    if (t.keyShadow.a > 0) b.shadow(outer, t.keyShadow, t.keyShadowBlur / 2, t.keyShadowOffsetY);

    Paint body = linear({0, box.y}, {0, box.bottom()}, {{0, t.glassTop}, {1, t.glassBottom}});
    if (t.opaque) {
        b.fill(outer, std::move(body));
    } else {
        // The rim is clear, so the page shows through it. The body inside is milky.
        const std::size_t inner = b.outline(insetOutline(L.glass, t.rimWidth));
        b.fill(outer, solid(t.rimTint));
        b.fill(inner, std::move(body));
        b.pushClip(inner);
        // Light that has passed through the glass: the caustic's colours on
        // the lower half of the body, strongest at the bottom edge. One soft
        // patch per colour, evenly spaced, each fading out where its
        // neighbours peak. Nothing under this body shows through it, so the
        // light is drawn on top.
        const float step = box.width / static_cast<float>(t.causticCount + 1);
        const float reach = L.metrics.barHeight / 2 - t.rimWidth, floorY = L.glass.body.bottom() - t.rimWidth;
        for (int i = 0; i < t.causticCount; ++i) {
            const float cx = box.x + step * static_cast<float>(i + 1);
            b.ellipse({cx - step, floorY - reach, 2 * step, 2 * reach},
                      radial({cx, floorY}, step, reach,
                             {{0, t.caustic[i].withAlpha(t.spillOpacity)}, {1, t.caustic[i].withAlpha(0)}}));
        }
        b.popClip();
        // A little of the palette over the whole glass.
        b.fill(outer, solid(t.tint.withAlpha(t.tintOpacity)));
        // The thick edge bends light: a soft bright band where the body meets
        // the rim. Three stacked strokes stand in for a 3.5 wide stroke
        // blurred by 1.8.
        for (float width : {6.0f, 4.0f, 2.0f})
            b.stroke(inner, width, solid(t.refraction.withAlpha(t.refraction.a * 0.22f)));
        // Band of colour in the lower rim. It fades out over the end caps,
        // where the rim turns upwards.
        const float fade = clampf(L.glass.radius / std::max(1.0f, box.width), 0.02f, 0.45f);
        std::vector<GradientStop> stops;
        stops.push_back({0, t.caustic[0].withAlpha(0)});
        for (int i = 0; i < t.causticCount; ++i) {
            const float u = static_cast<float>(i) / static_cast<float>(t.causticCount - 1);
            stops.push_back({fade + u * (1 - 2 * fade), t.caustic[i].withAlpha(t.causticOpacity)});
        }
        stops.push_back({1, t.caustic[t.causticCount - 1].withAlpha(0)});
        b.line(outlineRun(L.glass, OutlineEdge::Bottom, 2, 0, 1), 2.4f,
               linear({box.x, 0}, {box.right(), 0}, std::move(stops)));
        if (t.rimShade.a > 0) b.stroke(inner, 1, solid(t.rimShade));
    }
    if (t.lipStart.a > 0) {
        // The polished rim catches light at both ends: brightest at the upper
        // left, again at the lower right.
        const std::size_t lip = b.outline(insetOutline(L.glass, 0.75f));
        b.stroke(lip, 1.5f,
                 linear({box.x, box.y}, {box.right(), box.bottom()},
                        {{0, t.lipStart}, {0.55f, t.lipMid}, {1, t.lipEnd}}));
    }
    if (t.edge.a > 0) b.stroke(outer, 1, solid(t.edge));
    if (t.highlight.a > 0)
        b.line(outlineRun(L.glass, OutlineEdge::Top, t.opaque ? 2.0f : t.rimWidth / 2, 0.015f, 0.30f), 1.3f,
               linear({box.x, 0}, {box.x + 0.30f * box.width, 0},
                      {{0, t.highlight.withAlpha(0)}, {0.35f, t.highlight}, {1, t.highlight.withAlpha(0)}}));
}

// The reaction to a click, about the cat's paws: x' = anchor + shift + R·S·(x − anchor).
Affine reactionTransform(const CatPlacement &cat, const CatReaction &r) {
    const float a = r.tilt * 3.14159265f / 180.0f, cs = std::cos(a), sn = std::sin(a);
    Affine m;
    m.a = cs * r.scaleX;
    m.b = sn * r.scaleX;
    m.c = -sn * r.scaleY;
    m.d = cs * r.scaleY;
    m.e = cat.anchor.x + r.shift - (m.a * cat.anchor.x + m.c * cat.anchor.y);
    m.f = cat.anchor.y - (m.b * cat.anchor.x + m.d * cat.anchor.y);
    return m;
}

// Each whole paw, which is drawn again in front of the glass (a round paw is
// about 0.7 as tall as it is wide; CatSprite.pawOutlines on iOS).
std::vector<RectF> pawOvals(const CatPlacement &cat) {
    std::vector<RectF> ovals;
    const RectF &f = cat.frame;
    for (int i = 0; i < cat.sprite->pawCount; ++i) {
        const CatPaw &p = cat.sprite->paws[i];
        const float width = 2 * p.halfWidth * f.width + 0.8f, bottom = cat.anchor.y + p.depth * f.height + 0.4f;
        const float height = std::max(2 * p.depth * f.height, 0.7f * width) + 0.4f;
        ovals.push_back({f.x + p.x * f.width - width / 2, bottom - height, width, height});
    }
    return ovals;
}

// The cats sit behind the glass's top edge: each body shows only outside the
// glass, so its flat cut hides behind the edge, and its paws are drawn again
// over the glass. Every body goes on before any paws, so one cat never cuts
// into the other's paws. In dark appearance 米米莫 (black) gets a warm light
// round his outline.
void cats(Builder &b, const CandidateLayout &L, const CandidateViewModel &model, const Theme &t) {
    if (!L.decorations || L.cats.empty()) return;
    const std::size_t glassPath = b.outline(L.glass);
    const bool dark = t.appearance == Appearance::Dark;
    const auto sprite = [](const CatPlacement &c) { return c.cat == Cat::Mimimo ? Sprite::Mimimo : Sprite::Momo; };
    const auto turn = [&](const CatPlacement &c) { return reactionTransform(c, model.reactions[static_cast<int>(c.cat)]); };
    b.pushClipOut(glassPath);
    for (const auto &c : L.cats) {
        if (dark && c.cat == Cat::Mimimo) b.image(sprite(c), c.frame, turn(c), kGlow, kGlowBlur);
        b.image(sprite(c), c.frame, turn(c));
    }
    b.popClip();
    if (t.contactShadow.a > 0) {
        // Where a paw rests on the glass.
        b.pushClip(glassPath);
        for (const auto &c : L.cats)
            for (const RectF &paw : pawOvals(c)) {
                const RectF shade{paw.x - 0.1f * paw.width, paw.bottom() - 2.4f, 1.2f * paw.width, 4.4f};
                b.ellipse(shade, radial({shade.midX(), shade.midY()}, shade.width / 2, shade.height / 2,
                                        {{0, t.contactShadow}, {1, t.contactShadow.withAlpha(0)}}));
            }
        b.popClip();
    }
    for (const auto &c : L.cats) {
        if (c.sprite->pawCount == 0) continue;  // a dressed cat has no paws over the edge
        b.pushClipOvals(pawOvals(c), turn(c));
        b.image(sprite(c), c.frame, turn(c));
        b.popClip();
    }
}

// A paw print tilted 20° to the left, inside `box`.
void paw(Builder &b, const RectF &box, Color color) {
    const float s = box.width, cx = box.midX(), cy = box.midY();
    const float angle = -20.0f, a = angle * 3.14159265f / 180.0f, cs = std::cos(a), sn = std::sin(a);
    const auto turned = [&](float x, float y) {
        const float dx = x - cx, dy = y - cy;
        return PointF{cx + dx * cs - dy * sn, cy + dx * sn + dy * cs};
    };
    const PointF pad = turned(box.x + 0.50f * s, box.y + 0.68f * s);
    b.ellipse({pad.x - 0.27f * s, pad.y - 0.22f * s, 0.54f * s, 0.44f * s}, solid(color), angle);
    const float toes[4][2] = {{0.14f, 0.38f}, {0.36f, 0.17f}, {0.64f, 0.17f}, {0.86f, 0.38f}};
    for (const auto &toe : toes) {
        const PointF p = turned(box.x + toe[0] * s, box.y + toe[1] * s);
        b.ellipse({p.x - 0.115f * s, p.y - 0.115f * s, 0.23f * s, 0.23f * s}, solid(color));
    }
}

void candidates(Builder &b, const CandidateLayout &L, const CandidateViewModel &model, const Theme &t) {
    const auto &m = L.metrics;
    for (const auto &cell : L.cells) {
        const bool selected = cell.index == model.highlighted;
        const bool pressed = model.pressed && *model.pressed == cell.index;
        const bool hovered = !selected && model.hovered && *model.hovered == cell.index;
        // Keep the label and hit target still while the pill responds to a press.
        const RectF pill = pressed ? cell.pill.inset(cell.pill.width * 0.025f, cell.pill.height * 0.025f) : cell.pill;
        const float r = pill.height / 2;
        if (selected) {
            // A bead of tinted glass: a shadow in its own colour, light on
            // its upper edge, and the light that crossed it gathered inside
            // the lower edge.
            const bool bead = t.pillShadowOpacity > 0;
            const GlassOutline outline = makeBarOutline(GlassShape::Capsule, pill);
            if (bead) b.shadow(b.outline(outline), t.pillBottom.withAlpha(t.pillShadowOpacity), 3.5f, pressed ? 0.5f : 1.5f);
            b.roundRect(pill, r, linear({0, pill.y}, {0, pill.bottom()}, {{0, t.pillTop}, {1, t.pillBottom}}));
            if (bead) {
                b.line(outlineRun(outline, OutlineEdge::Top, 0.5f, 0, 1), 0.8f,
                       linear({0, pill.y}, {0, pill.midY()},
                              {{0, t.pillHighlight}, {1, t.pillHighlight.withAlpha(0)}}));
                // A wide faint line under a narrow one stands in for a 1.6
                // wide stroke blurred by 0.8.
                const auto glow = [&](float share) {
                    return linear({0, pill.midY()}, {0, pill.bottom()},
                                  {{0, t.pillGlow.withAlpha(0)}, {1, t.pillGlow.withAlpha(t.pillGlow.a * share)}});
                };
                b.line(outlineRun(outline, OutlineEdge::Bottom, 1.6f, 0, 1), 3.2f, glow(0.25f));
                b.line(outlineRun(outline, OutlineEdge::Bottom, 1.2f, 0, 1), 1.6f, glow(0.4f));
            }
        } else if (pressed) {
            b.roundRect(pill, r, solid(t.pressed));
        } else if (hovered) {
            b.roundRect(cell.pill, r, solid(t.hover));
        }
        // High contrast keeps system colours at full strength.
        const Color numberInk = !selected ? t.number : t.highContrast ? t.pillText : t.pillText.withAlpha(0.78f);
        const Color pawInk = !selected ? t.paw : t.highContrast ? t.pillText : t.pillText.withAlpha(0.85f);
        b.text(cell.number, cell.numberX, cell.centerY + kDigitCenterToBaseline * m.numberFont, m.numberFont, false,
               numberInk);
        b.text(cell.text, cell.textX, cell.centerY + kIdeographCenterToBaseline * m.font, m.font, selected,
               selected ? t.pillText : t.text);
        if (cell.japanese) paw(b, cell.paw, pawInk);
    }
}

void chevron(Builder &b, const RectF &r, bool up, Color color) {
    const float cx = r.midX(), cy = r.midY(), w = 4.6f, h = 2.4f, dir = up ? -1.0f : 1.0f;
    b.line({{cx - w, cy - dir * h}, {cx, cy + dir * h}, {cx + w, cy - dir * h}}, 1.7f, solid(color));
}

// Header (reading and mode) and footer (paging) of the expanded panel.
void panelChrome(Builder &b, const CandidateLayout &L, const Theme &t) {
    const auto &m = L.metrics;
    b.text(L.preedit, L.preeditX, L.headerY + kIdeographCenterToBaseline * m.headerFont, m.headerFont, false,
           t.secondaryText);
    b.text(L.modeLabel, L.modeRight, L.headerY + kIdeographCenterToBaseline * 11, 11, false, t.secondaryText,
           TextAlign::Trailing);
    const float rule = L.headerY + m.headerHeight / 2 + 1;
    b.line({{L.preeditX, rule}, {L.glass.bounds.right() - 14, rule}}, 0.6f, solid(t.separator));
    const float baseline = L.pageLabelCenter.y + kIdeographCenterToBaseline * 12;
    b.text("\xE2\x80\xB9 \xE4\xB8\x8A\xE4\xB8\x80\xE9\xA0\x81", L.prevPage.midX(), baseline, 12, true,   // ‹ 上一頁
           L.canPrev ? t.text : t.disabledText, TextAlign::Center);
    b.text(L.pageLabel, L.pageLabelCenter.x, L.pageLabelCenter.y + kDigitCenterToBaseline * 11, 11, true,
           t.secondaryText, TextAlign::Center);
    b.text("\xE4\xB8\x8B\xE4\xB8\x80\xE9\xA0\x81 \xE2\x80\xBA", L.nextPage.midX(), baseline, 12, true,   // 下一頁 ›
           L.canNext ? t.text : t.disabledText, TextAlign::Center);
}

} // namespace

Scene buildScene(const CandidateLayout &L, const CandidateViewModel &model, const Theme &t) {
    Builder b;
    b.scene.size = L.size;
    b.scene.dressed = L.dressed;

    glass(b, L, t);
    // A festival's ornaments at the right end, over the glass and under the cats.
    if (L.decorations && L.ornament) b.image(Sprite::Ornament, *L.ornament, Affine{});
    // The cats are in front of the glass and under the candidates; no paw reaches the candidates.
    cats(b, L, model, t);
    candidates(b, L, model, t);
    if (L.expanded) panelChrome(b, L, t);
    chevron(b, L.chevron, L.chevronUp, t.chevron);
    return std::move(b.scene);
}

} // namespace meowpuut::ui
