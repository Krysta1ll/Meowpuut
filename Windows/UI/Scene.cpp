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
    void image(Sprite sprite, const RectF &rect, float opacity = 1, float blur = 0) {
        auto &c = add(DrawCommand::Kind::Image);
        c.sprite = sprite;
        c.rect = rect;
        c.opacity = opacity;
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

// Outfit layer behind, the cat, outfit layer in front: all on one canvas.
void mimimo(Builder &b, const RectF &box, float opacity = 1, float blur = 0) {
    b.image(Sprite::MimimoBack, box, opacity, blur);
    b.image(Sprite::Mimimo, box, opacity, blur);
    b.image(Sprite::MimimoFront, box, opacity, blur);
}

void glass(Builder &b, const CandidateLayout &L, const Theme &t) {
    const RectF box = L.glass.bounds;
    const std::size_t outer = b.outline(L.glass);
    if (t.shadow.a > 0) b.shadow(outer, t.shadow, t.shadowBlur / 2, t.shadowOffsetY);
    if (t.keyShadow.a > 0) b.shadow(outer, t.keyShadow, t.keyShadowBlur / 2, t.keyShadowOffsetY);
    // 米米莫 is behind the glass.
    if (L.decorations) mimimo(b, L.mimimo);

    Paint body = linear({0, box.y}, {0, box.bottom()}, {{0, t.glassTop}, {1, t.glassBottom}});
    if (t.opaque) {
        b.fill(outer, std::move(body));
    } else {
        // The rim is clear, so the page and the cat show through it. The body
        // inside is milky; only a soft copy of the cat shows on it.
        const std::size_t inner = b.outline(insetOutline(L.glass, t.rimWidth));
        b.fill(outer, solid(t.rimTint));
        b.fill(inner, std::move(body));
        if (L.decorations && t.frostOpacity > 0) {
            b.pushClip(inner);
            mimimo(b, L.mimimo, t.frostOpacity, kFrostBlur);
            b.popClip();
        }
        if (t.causticCount > 0) {
            // Iridescent band in the lower rim. It fades out over the end caps,
            // where the rim turns upwards.
            const float fade = clampf(L.glass.radius / std::max(1.0f, box.width), 0.02f, 0.45f);
            std::vector<GradientStop> stops;
            stops.push_back({0, t.caustic[0].withAlpha(0)});
            for (int i = 0; i < t.causticCount; ++i) {
                const float u = t.causticCount == 1 ? 0.5f : static_cast<float>(i) / static_cast<float>(t.causticCount - 1);
                stops.push_back({fade + u * (1 - 2 * fade), t.caustic[i].withAlpha(t.causticOpacity)});
            }
            stops.push_back({1, t.caustic[t.causticCount - 1].withAlpha(0)});
            b.line(outlineRun(L.glass, OutlineEdge::Bottom, t.rimWidth / 2, 0, 1), t.rimWidth,
                   linear({box.x, 0}, {box.right(), 0}, std::move(stops)));
        }
        if (t.rimShade.a > 0) b.stroke(inner, 1, solid(t.rimShade));
    }
    if (t.lipStart.a > 0 || t.lipEnd.a > 0) {
        const std::size_t lip = b.outline(insetOutline(L.glass, 0.75f));
        b.stroke(lip, 1.5f,
                 linear({box.x, box.y}, {box.x + std::min(box.width, box.height * 4), box.bottom()},
                        {{0, t.lipStart}, {1, t.lipEnd}}));
    }
    if (t.edge.a > 0) b.stroke(outer, 1, solid(t.edge));
    if (t.highlight.a > 0)
        b.line(outlineRun(L.glass, OutlineEdge::Top, t.opaque ? 2.0f : t.rimWidth / 2, 0.015f, 0.30f), 1.3f,
               linear({box.x, 0}, {box.x + 0.30f * box.width, 0},
                      {{0, t.highlight.withAlpha(0)}, {0.35f, t.highlight}, {1, t.highlight.withAlpha(0)}}));
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
        const bool pressed = !selected && model.pressed && *model.pressed == cell.index;
        const bool hovered = !selected && model.hovered && *model.hovered == cell.index;
        const float r = cell.pill.height / 2;
        if (selected) {
            b.roundRect(cell.pill, r,
                        linear({0, cell.pill.y}, {0, cell.pill.bottom()}, {{0, t.pillTop}, {1, t.pillBottom}}));
            if (t.pillHighlight.a > 0)
                b.line({{cell.pill.x + r, cell.pill.y + 0.75f}, {cell.pill.right() - r, cell.pill.y + 0.75f}}, 0.8f,
                       solid(t.pillHighlight));
        } else if (pressed) {
            // Pressed: the wash shrinks to 0.95 (DESIGN.md §4); the text stays put.
            const RectF pill = cell.pill.inset(cell.pill.width * 0.025f, cell.pill.height * 0.025f);
            b.roundRect(pill, pill.height / 2, solid(t.pressed));
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
    b.scene.mimimoFace = model.mimimoFace;
    b.scene.momoFace = model.momoFace;

    glass(b, L, t);
    // The tail lies in front of the glass (capsule only).
    if (L.decorations && L.showTail) b.image(Sprite::Tail, L.tail);
    candidates(b, L, model, t);
    if (L.expanded) panelChrome(b, L, t);
    chevron(b, L.chevron, L.chevronUp, t.chevron);
    // 莫莫 rests her chin on the glass, in front of it.
    if (L.decorations) {
        if (t.contactShadow.a > 0) {
            const RectF &cs = L.momoContactShadow;
            b.ellipse(cs, radial({cs.midX(), cs.midY()}, cs.width / 2, cs.height / 2,
                                 {{0, t.contactShadow}, {1, t.contactShadow.withAlpha(0)}}));
        }
        b.image(Sprite::MomoBack, L.momo);
        b.image(Sprite::Momo, L.momo);
        b.image(Sprite::MomoFront, L.momo);
    }
    return std::move(b.scene);
}

} // namespace meowpuut::ui
