// Portable layout tests: run on any C++17 compiler (no Windows headers).
//   c++ -std=c++17 -I Windows/UI Windows/UI/*.cpp Windows/UI/tests/layout_test.cpp -o layout_test && ./layout_test
#include "Blur.h"
#include "CandidateLayout.h"
#include "Festival.h"
#include "Scene.h"
#include "SvgSnapshot.h"
#include "Theme.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <sstream>
#include <string>

using namespace meowpuut::ui;

namespace {
int failures = 0;
void check(bool ok, const char *what, int line) {
    if (!ok) { ++failures; std::fprintf(stderr, "FAIL line %d: %s\n", line, what); }
}
#define CHECK(x) check((x), #x, __LINE__)

// Deterministic widths: CJK and kana one em, ASCII ~0.55 em.
class FixedMeasurer : public TextMeasurer {
public:
    int calls = 0;
    float measure(std::string_view text, float size, bool medium) override {
        ++calls;
        float w = 0;
        for (auto cp : utf8CodePoints(text)) {
            const auto c = static_cast<unsigned char>(cp[0]);
            if (c < 0x80) w += (c >= '0' && c <= '9') ? 0.60f : 0.55f;
            else if (cp == "\xE2\x87\xA7") w += 0.80f;
            else w += 1.0f;
        }
        return w * size * (medium ? 1.02f : 1.0f);
    }
};

bool validUtf8(const std::string &s) {
    std::size_t i = 0;
    while (i < s.size()) {
        const auto c = static_cast<unsigned char>(s[i]);
        std::size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (len == 0 || i + len > s.size()) return false;
        for (std::size_t k = 1; k < len; ++k)
            if ((static_cast<unsigned char>(s[i + k]) >> 6) != 0x2) return false;
        i += len;
    }
    return true;
}

CandidateViewModel sample() {
    CandidateViewModel m;
    m.candidates = {"你好", "妳好", "你好呀", "擬好", "泥好", "尼好", "你號", "すし"};
    m.japanese = {false, false, false, false, false, false, false, true};
    return m;
}

void testShapes() {
    const RectF box{10, 20, 400, 40};
    for (auto shape : {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud}) {
        const auto o = makeBarOutline(shape, box);
        CHECK(!o.path.empty());
        CHECK(o.polygon.size() > 40);
        CHECK(outlineContains(o, {box.midX(), o.body.midY()}));
        CHECK(!outlineContains(o, {box.x + 0.5f, box.y + 0.5f}));          // corner is outside
        CHECK(!outlineContains(o, {box.right() + 2, box.midY()}));
        for (const auto &p : o.polygon) {
            CHECK(p.x >= box.x - 0.01f && p.x <= box.right() + 0.01f);
            CHECK(p.y >= box.y - 0.01f && p.y <= box.bottom() + 0.01f);
        }
        const float bodyHeight = o.body.height;
        CHECK(bodyHeight > 0 && bodyHeight <= box.height);
    }
    // Capsule: the ends are semicircles of radius h/2.
    const auto cap = makeBarOutline(GlassShape::Capsule, box);
    CHECK(outlineContains(cap, {box.x + 20, box.midY()}));
    CHECK(!outlineContains(cap, {box.x + 3, box.y + 3}));
    // Wave: both edges swell inside the box and the body keeps a constant band.
    const auto wave = makeBarOutline(GlassShape::Wave, box);
    CHECK(std::fabs(wave.body.height - box.height * (1 - 2 * 0.065f)) < 0.01f);
    // Cloud: bumps rise above the body; the gap between two bumps does not.
    const auto cloud = makeBarOutline(GlassShape::Cloud, box);
    const float rise = cloudRise(box.height);
    CHECK(outlineContains(cloud, {box.x + 0.15f * box.width, box.y + rise * 0.4f}));
    CHECK(outlineContains(cloud, {box.x + 0.47f * box.width, box.y + rise * 0.6f}));
    CHECK(!outlineContains(cloud, {box.x + 0.31f * box.width, box.y + rise * 0.2f}));
    // The bottom stays flat: nothing below the box.
    for (const auto &p : cloud.polygon) CHECK(p.y <= box.bottom() + 0.01f);
    // Highlight streak lies inside the glass.
    CHECK(!outlineHighlight(cap, 2, 0.02f, 0.3f).empty());
    for (const auto &p : outlineHighlight(cap, 2, 0.02f, 0.3f)) CHECK(outlineContains(cap, p));
    // The inset outline (the body inside the rim) and the run along the
    // bottom edge stay inside every shape.
    for (auto shape : {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud}) {
        for (const auto &o : {makeBarOutline(shape, box), makePanelOutline(shape, {0, 0, 360, 200}, 18, 40)}) {
            const auto inner = insetOutline(o, 3);
            CHECK(inner.polygon.size() > 40 && !inner.path.empty());
            for (const auto &p : inner.polygon) CHECK(outlineContains(o, p));
            CHECK(outlineContains(inner, {o.bounds.midX(), o.body.midY()}));
            CHECK(!outlineContains(inner, {o.bounds.midX(), o.body.bottom() - 1}));   // the rim is outside it
            const auto bottom = outlineRun(o, OutlineEdge::Bottom, 1.5f, 0, 1);
            CHECK(bottom.size() > 20);
            for (std::size_t i = 0; i < bottom.size(); ++i) {
                CHECK(outlineContains(o, bottom[i]));
                CHECK(bottom[i].y > o.body.midY());
                if (i > 0) CHECK(bottom[i].x >= bottom[i - 1].x - 0.01f);
            }
        }
    }
    // Panel outline for every shape.
    for (auto shape : {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud}) {
        const auto panel = makePanelOutline(shape, {0, 0, 360, 200}, 18, 40);
        CHECK(outlineContains(panel, {180, 100}));
        CHECK(!outlineContains(panel, {1, 1}));
        CHECK(outlineContains(panel, {4, 100}));   // straight left side
    }
    CHECK(parseGlassShape("wave") == GlassShape::Wave && parseGlassShape("cloud") == GlassShape::Cloud);
    CHECK(parseGlassShape("nope") == GlassShape::Capsule);
}

void testCollapsed() {
    FixedMeasurer measurer;
    auto model = sample();
    for (auto shape : {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud}) {
        model.shape = shape;
        const auto L = layoutCandidates(model, measurer);
        const auto &m = L.metrics;
        CHECK(L.cells.size() == model.candidates.size());
        // One line per cell, ordered, non-overlapping, within the glass body.
        for (std::size_t i = 0; i < L.cells.size(); ++i) {
            const auto &c = L.cells[i];
            CHECK(std::fabs(c.frame.height - m.barHeight) < 0.01f);
            CHECK(c.textX >= c.frame.x + m.cellPad - 0.01f);
            CHECK(c.textX + c.textWidth <= c.frame.right() - m.cellPad + 0.01f);
            CHECK(!c.truncated);
            CHECK(c.pill.height < c.frame.height);
            if (i > 0) CHECK(c.frame.x >= L.cells[i - 1].frame.right() + m.cellGap - 0.01f);
            CHECK(c.number == std::to_string(i + 1));
        }
        CHECK(L.chevron.x >= L.cells.back().frame.right());
        // The body band is the same height for every shape.
        CHECK(std::fabs(L.glass.body.height - m.barHeight) < 0.05f);
        CHECK(L.glass.bounds.bottom() <= L.size.height);
        // Both cats, 米米莫 at the left end and 莫莫 at the right.
        CHECK(L.cats.size() == 2 && L.cats[0].cat == Cat::Mimimo && L.cats[1].cat == Cat::Momo);
        CHECK(L.cats[0].anchor.x < L.glass.bounds.midX() && L.cats[1].anchor.x > L.glass.bounds.midX());
    }
}

// The edge's height at x, from the drawn outline.
float edgeAt(const CandidateLayout &L, float x) {
    const auto run = outlineRun(L.glass, OutlineEdge::Top, 0, 0, 1);
    for (std::size_t i = 1; i < run.size(); ++i)
        if (run[i].x >= x) {
            const PointF a = run[i - 1], b = run[i];
            return a.y + (b.y - a.y) * clampf((x - a.x) / std::max(0.001f, b.x - a.x), 0, 1);
        }
    return run.back().y;
}

void testCatsSeat() {
    // On a short bar or a long one, collapsed or expanded, every cat shown sits
    // on the glass's top edge inside the window, and no paw reaches a
    // candidate or the expand arrow.
    FixedMeasurer measurer;
    const auto all = sample().candidates;
    for (auto shape : {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud}) {
        for (std::size_t n : {std::size_t(1), std::size_t(2), std::size_t(3), std::size_t(8)}) {
            for (bool expanded : {false, true}) {
                CandidateViewModel model;
                model.candidates.assign(all.begin(), all.begin() + static_cast<long>(n));
                model.japanese.assign(n, false);
                model.shape = shape;
                model.expanded = expanded;
                const auto L = layoutCandidates(model, measurer);
                // 莫莫 is always there: five clicks on her switch the script.
                CHECK(!L.cats.empty() && L.cats.back().cat == Cat::Momo);
                if (n >= 3) CHECK(L.cats.size() == 2);
                for (const auto &cat : L.cats) {
                    const RectF body = cat.body();
                    CHECK(body.x >= 0 && body.y >= 0 && body.right() <= L.size.width && body.bottom() <= L.size.height);
                    CHECK(std::fabs(cat.frame.width / cat.frame.height - cat.sprite->width / cat.sprite->height) < 1e-4f);
                    // The anchor is on the lowest point of the edge under the cut: never above the edge.
                    CHECK(cat.anchor.y >= edgeAt(L, cat.anchor.x) - 0.3f);
                    CHECK(cat.anchor.y - edgeAt(L, cat.anchor.x) <= 4.5f);
                    for (int i = 0; i < cat.sprite->pawCount; ++i) {
                        for (const auto &c : L.cells) CHECK(!cat.paw(i).intersects(c.pill));
                        CHECK(!cat.paw(i).intersects(L.chevron.inset(5, 8)));
                    }
                }
                if (L.cats.size() == 2) CHECK(L.cats[0].body().right() < L.cats[1].body().x);
                if (shape == GlassShape::Cloud) {
                    // The bumps are still there, right of his eyes.
                    float top = L.glass.body.y;
                    for (const auto &p : L.glass.polygon) top = std::min(top, p.y);
                    CHECK(top < L.glass.body.y - 4);
                }
            }
        }
    }
}

void testJapaneseNeverWraps() {
    // Regression: a two-character Japanese candidate was squeezed into a
    // column one character wide. Its cell must hold the full natural width.
    FixedMeasurer measurer;
    CandidateViewModel model;
    model.candidates = {"蘇軾", "すし", "寿司"};
    model.japanese = {false, true, true};
    const auto L = layoutCandidates(model, measurer);
    const auto &m = L.metrics;
    const float natural = measurer.measure("すし", m.font, true);
    CHECK(std::fabs(L.cells[1].textWidth - std::ceil(natural)) < 0.01f);
    CHECK(!L.cells[1].truncated);
    CHECK(L.cells[1].japanese && !L.cells[1].paw.empty());
    CHECK(L.cells[1].paw.x >= L.cells[1].textX + L.cells[1].textWidth);
    CHECK(L.cells[1].paw.right() <= L.cells[1].frame.right() - m.cellPad + 0.01f);
    // Same text without the marker is exactly the marker narrower.
    const float plain = L.cells[0].frame.width;
    CHECK(std::fabs(L.cells[2].frame.width - plain - (m.pawGap + std::round(m.font * m.pawRatio))) < 0.01f);
    CHECK(std::fabs(L.cells[1].paw.width - std::round(m.font * 0.45f)) < 0.01f);   // DESIGN.md §4
}

void testEllipsis() {
    FixedMeasurer measurer;
    std::string longText;
    for (int i = 0; i < 60; ++i) longText += "長";
    longText += "すし";
    const auto e = ellipsizeMiddle(longText, 200, 15, true, measurer);
    CHECK(e.find("\xE2\x80\xA6") != std::string::npos);
    CHECK(validUtf8(e));
    CHECK(measurer.measure(e, 15, true) <= 200);
    CHECK(e.rfind("\xE3\x81\x99\xE3\x81\x97") == e.size() - 6); // keeps the tail "すし"
    CHECK(ellipsizeMiddle("短", 200, 15, true, measurer) == "短");

    CandidateViewModel model;
    model.candidates = {longText, "你好", longText, "我"};
    model.japanese = {true, false, false, false};
    LayoutLimits limits;
    limits.maxWidth = 700;
    const auto L = layoutCandidates(model, measurer, limits);
    CHECK(L.cells[0].truncated && L.cells[2].truncated);
    CHECK(!L.cells[1].truncated && !L.cells[3].truncated);
    CHECK(L.size.width <= limits.maxWidth + 0.5f);
    for (const auto &c : L.cells) CHECK(validUtf8(c.text));
    // One candidate takes at most longCandidateMax, however wide the screen.
    limits.maxWidth = 1920;
    const auto wide = layoutCandidates(model, measurer, limits);
    CHECK(wide.cells[0].truncated && wide.cells[0].textWidth <= wide.metrics.longCandidateMax + 0.5f);
    CHECK(wide.cells[0].textWidth > wide.metrics.longCandidateMax - 20);
    CHECK(wide.size.width < 1200);

    // A flag, a kana with a combining voicing mark and a variation selector
    // are never cut apart.
    const std::string flag = "\xF0\x9F\x87\xB9\xF0\x9F\x87\xBC";       // regional indicators T, W
    for (float width : {40.0f, 62.0f, 78.0f, 92.0f, 110.0f}) {
        const auto cut = ellipsizeMiddle("長長" + flag + "長長長長", width, 15, true, measurer);
        CHECK(validUtf8(cut));
        CHECK(cut.find("\xF0\x9F\x87\xB9") == std::string::npos || cut.find(flag) != std::string::npos);
        CHECK(cut.find("\xF0\x9F\x87\xBC") == std::string::npos || cut.find(flag) != std::string::npos);
        const auto kana = ellipsizeMiddle("長長長長長か\xE3\x82\x99長\xEF\xB8\x8F", width, 15, true, measurer);
        const auto dots = kana.find("\xE2\x80\xA6");
        CHECK(dots != std::string::npos);
        // Neither mark directly follows the ellipsis.
        CHECK(kana.compare(dots + 3, 3, "\xE3\x82\x99") != 0 && kana.compare(dots + 3, 3, "\xEF\xB8\x8F") != 0);
    }
}

void testNarrowScreen() {
    // 640 DIPs (a 1280-pixel screen at 200%) cannot hold eight four-character
    // candidates whole. Every cell keeps its first character and the window
    // still fits.
    FixedMeasurer measurer;
    CandidateViewModel model;
    for (const char *lead : {"你", "我", "他", "她", "它", "牠", "祂", "您"}) model.candidates.push_back(std::string(lead) + "好嗎啊");
    model.japanese.assign(8, false);
    LayoutLimits limits;
    limits.maxWidth = 640;
    const auto L = layoutCandidates(model, measurer, limits);
    CHECK(L.cells.size() == 8);
    for (std::size_t i = 0; i < L.cells.size(); ++i) {
        const auto &c = L.cells[i];
        CHECK(c.truncated);
        CHECK(c.text == model.candidates[i].substr(0, 3) + "\xE2\x80\xA6");   // first character and the ellipsis
    }
    CHECK(L.size.width <= limits.maxWidth + 0.5f);
    // With more room the spare width goes to the first candidates: two come
    // back whole, the third gets its last character back.
    limits.maxWidth = 720;
    const auto roomier = layoutCandidates(model, measurer, limits);
    CHECK(!roomier.cells[0].truncated && !roomier.cells[1].truncated);
    CHECK(roomier.cells[2].text == "他\xE2\x80\xA6啊");
    CHECK(roomier.cells[7].text == "您\xE2\x80\xA6");
    CHECK(roomier.size.width <= limits.maxWidth + 0.5f);
    // At 1000 nothing is cut.
    limits.maxWidth = 1000;
    for (const auto &c : layoutCandidates(model, measurer, limits).cells) CHECK(!c.truncated);
    // Narrower than the row at its floor: no cell shrinks to a bare ellipsis.
    // The row keeps its width and the window is wider than the work area;
    // placeNearCaret keeps the left end on screen, so the right end hangs out.
    limits.maxWidth = 520;
    const auto tight = layoutCandidates(model, measurer, limits);
    for (std::size_t i = 0; i < tight.cells.size(); ++i)
        CHECK(tight.cells[i].text == model.candidates[i].substr(0, 3) + "\xE2\x80\xA6");
    CHECK(tight.size.width > limits.maxWidth);
    const auto at = placeNearCaret(tight, 2.0f, {300, 100, 302, 140}, {0, 0, 1040, 800});
    CHECK(at.x + std::lround(tight.content.x * 2.0f) == 0);
}

void testExpandedAndPaging() {
    FixedMeasurer measurer;
    auto model = sample();
    model.expanded = true;
    model.page = 1;
    model.lastPage = false;
    model.preedit = "nihao";
    model.modeLabel = "拼 · 繁";
    const auto L = layoutCandidates(model, measurer);
    CHECK(L.expanded && L.chevronUp);
    CHECK(L.canPrev && L.canNext);
    CHECK(L.pageLabel == "2");
    CHECK(L.cells.size() == model.candidates.size());
    for (std::size_t i = 0; i < L.cells.size(); ++i) {
        CHECK(outlineContains(L.glass, {L.cells[i].frame.midX(), L.cells[i].frame.midY()}));
        CHECK(L.cells[i].frame.bottom() <= L.prevPage.y);
        for (std::size_t j = i + 1; j < L.cells.size(); ++j) {
            const auto &a = L.cells[i].frame, &b = L.cells[j].frame;
            const bool overlap = a.x < b.right() - 0.01f && b.x < a.right() - 0.01f && a.y < b.bottom() - 0.01f && b.y < a.bottom() - 0.01f;
            CHECK(!overlap);
        }
    }
    CHECK(hitTest(L, {L.nextPage.midX(), L.nextPage.midY()}).kind == HitKind::NextPage);
    CHECK(hitTest(L, {L.prevPage.midX(), L.prevPage.midY()}).kind == HitKind::PrevPage);
    model.page = 0;
    model.lastPage = true;
    const auto first = layoutCandidates(model, measurer);
    CHECK(!first.canPrev && !first.canNext);
    CHECK(hitTest(first, {first.nextPage.midX(), first.nextPage.midY()}).kind == HitKind::None);
    CHECK(hitTest(first, {first.prevPage.midX(), first.prevPage.midY()}).kind == HitKind::None);

    for (auto shape : {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud}) {
        // Opening the panel does not move the glass or the cats: same anchor,
        // same left edge, same top edge (the swell and bumps keep their size).
        model.shape = shape;
        model.expanded = false;
        const auto bar = layoutCandidates(model, measurer);
        model.expanded = true;
        const auto panel = layoutCandidates(model, measurer);
        CHECK(std::fabs(panel.anchorX - bar.anchorX) < 0.01f);
        CHECK(std::fabs(panel.glass.bounds.x - bar.glass.bounds.x) < 0.01f);
        CHECK(std::fabs(panel.glass.bounds.y - bar.glass.bounds.y) < 0.01f);
        CHECK(std::fabs(panel.glass.body.y - bar.glass.body.y) < 0.01f);
        if (shape == GlassShape::Capsule)
            CHECK(std::fabs(panel.cats[0].frame.x - bar.cats[0].frame.x) < 0.01f &&
                  std::fabs(panel.cats[0].frame.y - bar.cats[0].frame.y) < 0.3f);
    }

    // A sentence the collapsed bar shows whole is whole in the panel too.
    CandidateViewModel longModel;
    longModel.candidates = {"今天天氣很好我想去河邊散步然後吃壽司", "今天", "金天", "今"};
    longModel.japanese.assign(4, false);
    const auto shown = layoutCandidates(longModel, measurer);
    CHECK(!shown.cells[0].truncated);
    longModel.expanded = true;
    const auto opened = layoutCandidates(longModel, measurer);
    CHECK(!opened.cells[0].truncated && opened.cells[0].text == longModel.candidates[0]);
    CHECK(opened.glass.bounds.width > opened.metrics.panelMinWidth);
    CHECK(opened.glass.bounds.width <= opened.metrics.panelMaxWidth + 0.01f);
    // Past the panel's maximum it is cut, and the panel stops growing.
    longModel.candidates[0] += longModel.candidates[0] + longModel.candidates[0];
    const auto capped = layoutCandidates(longModel, measurer);
    CHECK(capped.cells[0].truncated);
    CHECK(std::fabs(capped.glass.bounds.width - capped.metrics.panelMaxWidth) < 0.01f);
}

void testHitTesting() {
    FixedMeasurer measurer;
    const auto model = sample();
    const auto L = layoutCandidates(model, measurer);
    for (const auto &c : L.cells) {
        const auto hit = hitTest(L, {c.frame.midX(), c.frame.midY()});
        CHECK(hit.kind == HitKind::Candidate && hit.index == c.index);
    }
    CHECK(hitTest(L, {L.chevron.midX(), L.chevron.midY()}).kind == HitKind::Expand);
    CHECK(hitTest(L, {L.cats[0].touch().midX(), L.cats[0].touch().midY()}).kind == HitKind::Mimimo);
    CHECK(hitTest(L, {L.cats[1].touch().midX(), L.cats[1].touch().midY()}).kind == HitKind::Momo);
    // A paw over the glass is not a cat to click.
    CHECK(hitTest(L, {L.cats[0].paw(0).midX(), L.cats[0].anchor.y + 0.5f}).kind != HitKind::Mimimo);
    CHECK(hitTest(L, {1, 1}).kind == HitKind::None);
}

void testZhuyinNumbersAndContrast() {
    FixedMeasurer measurer;
    auto model = sample();
    model.shiftNumbers = true;
    model.decorations = false;
    const auto L = layoutCandidates(model, measurer);
    CHECK(L.cells[0].number == "\xE2\x87\xA7" "1");
    CHECK(L.cats.empty());
    const auto first = hitTest(L, {L.cells[0].frame.midX(), L.cells[0].frame.midY()});
    CHECK(first.kind == HitKind::Candidate && first.index == 0);
    const auto third = hitTest(L, {L.cells[2].frame.midX(), L.cells[2].frame.midY()});
    CHECK(third.kind == HitKind::Candidate && third.index == 2);
    // Selection colours follow Sources/GlassShapes.swift.
    const auto graphite = makeTheme(Appearance::Dark, Palette::Graphite, true, false);
    CHECK(std::fabs(graphite.pillTop.r - Color::rgb(0x7E848D).r) < 1e-4f && std::fabs(graphite.pillBottom.b - Color::rgb(0x666C75).b) < 1e-4f);
    // Light pills: white text at least 3.2:1 at the top and 4:1 at the bottom.
    const struct { Palette palette; std::uint32_t top, bottom; } lightPills[] = {
        {Palette::Rose, 0xAB8689, 0x946D71},     {Palette::Ocean, 0x7892AD, 0x5C7A9B},
        {Palette::Sage, 0x7F9583, 0x647D69},     {Palette::Lavender, 0x9A88B3, 0x816EA0},
        {Palette::Amber, 0xA78B60, 0x927242},    {Palette::Graphite, 0x8B9099, 0x717781}};
    for (const auto &expected : lightPills) {
        const auto light = makeTheme(Appearance::Light, expected.palette, true, false);
        const auto top = Color::rgb(expected.top), bottom = Color::rgb(expected.bottom);
        CHECK(light.pillTop.r == top.r && light.pillTop.g == top.g && light.pillTop.b == top.b);
        CHECK(light.pillBottom.r == bottom.r && light.pillBottom.g == bottom.g && light.pillBottom.b == bottom.b);
    }
    // The dark rim follows the palette: blue for ocean, warm for amber.
    const auto ocean = makeTheme(Appearance::Dark, Palette::Ocean, true, false);
    const auto amber = makeTheme(Appearance::Dark, Palette::Amber, true, false);
    CHECK(ocean.lipStart.b > ocean.lipStart.r && amber.lipStart.r > amber.lipStart.b);
    CHECK(ocean.caustic[0].b > ocean.caustic[0].r && ocean.causticCount == 4);
    const auto hc = makeTheme(Appearance::Light, Palette::Rose, true, true, Color::rgb(0x000000),
                              Color::rgb(0xFFFFFF), Color::rgb(0x1AEBFF), Color::rgb(0x000000));
    CHECK(hc.opaque && hc.causticCount == 0 && hc.shadow.a == 0);
    const auto plain = makeTheme(Appearance::Dark, Palette::Sage, false, false);
    CHECK(plain.opaque && plain.causticCount == 0 && plain.glassTop.a == 1);
    // Without live blur the body must hide the text behind it.
    for (auto appearance : {Appearance::Light, Appearance::Dark}) {
        const auto glassy = makeTheme(appearance, Palette::Rose, true, false);
        CHECK(glassy.glassTop.a >= 0.95f && glassy.glassBottom.a >= 0.95f);
        CHECK(glassy.rimTint.a < glassy.glassTop.a);
    }
}

std::size_t count(const Scene &scene, DrawCommand::Kind kind) {
    std::size_t n = 0;
    for (const auto &c : scene.commands) n += c.kind == kind;
    return n;
}

const DrawCommand *findText(const Scene &scene, const std::string &text) {
    for (const auto &c : scene.commands)
        if (c.kind == DrawCommand::Kind::Text && c.text == text) return &c;
    return nullptr;
}

void testScene() {
    FixedMeasurer measurer;
    auto model = sample();
    model.highlighted = 2;
    model.hovered = 4;
    for (auto shape : {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud}) {
        for (bool expanded : {false, true}) {
            for (auto appearance : {Appearance::Light, Appearance::Dark}) {
                model.shape = shape;
                model.expanded = expanded;
                const auto L = layoutCandidates(model, measurer);
                const auto scene = buildScene(L, model, makeTheme(appearance, Palette::Rose, true, false));
                CHECK(!scene.commands.empty() && scene.commands.front().kind == DrawCommand::Kind::Shadow);
                int depth = 0;
                bool glassSeen = false, textSeen = false;
                std::size_t cats = 0;
                for (const auto &c : scene.commands) {
                    using Kind = DrawCommand::Kind;
                    if (c.kind == Kind::PushClip || c.kind == Kind::PushClipOut || c.kind == Kind::PushClipOvals) ++depth;
                    if (c.kind == Kind::PopClip) --depth;
                    CHECK(depth >= 0 && depth <= 1);
                    if (c.kind == Kind::Shadow || c.kind == Kind::FillPath || c.kind == Kind::StrokePath ||
                        c.kind == Kind::PushClip || c.kind == Kind::PushClipOut)
                        CHECK(c.path < scene.outlines.size());
                    if (c.kind == Kind::FillPath) glassSeen = true;
                    if (c.kind == Kind::Text) textSeen = true;
                    // The cats are in front of the glass and under the candidates, always inside a clip.
                    if (c.kind == Kind::Image) {
                        CHECK(glassSeen && !textSeen && depth == 1);
                        cats += c.tint.a == 0;
                        // Only 米米莫, only in dark, gets the warm light.
                        if (c.tint.a > 0) CHECK(c.sprite == Sprite::Mimimo && appearance == Appearance::Dark);
                    }
                    if (c.kind == Kind::PushClipOvals) CHECK(!c.ovals.empty());
                    if (c.kind == Kind::StrokeLine) CHECK(c.points.size() >= 2);
                    // Stops that differ in alpha keep one RGB (see Paint).
                    for (std::size_t i = 1; i < c.paint.stops.size(); ++i) {
                        const auto &a = c.paint.stops[i - 1].color, &b = c.paint.stops[i].color;
                        CHECK(c.paint.stops[i].offset >= c.paint.stops[i - 1].offset);
                        if (a.a != b.a) CHECK(a.r == b.r && a.g == b.g && a.b == b.b);
                    }
                }
                CHECK(depth == 0);
                // The body is laid inside the rim; the cat shows on it blurred.
                // Outlines: glass, body, lip and the selected pill.
                // Each cat's body once outside the glass and its paws once over it.
                CHECK(cats == 4);
                // The glass, its inner body, its lip, the selected pill and the glass again for the cats.
                CHECK(scene.outlines.size() == 5);
                // The inner body, and the paw shadows on the glass.
                CHECK(count(scene, DrawCommand::Kind::PushClip) == 2);
                CHECK(count(scene, DrawCommand::Kind::PushClipOut) == 1 && count(scene, DrawCommand::Kind::PushClipOvals) == 2);
                // Two under the glass, one under the selected pill.
                CHECK(count(scene, DrawCommand::Kind::Shadow) == 3);
                // One selected pill and one hover wash.
                CHECK(count(scene, DrawCommand::Kind::FillRoundRect) == 2);
                for (std::size_t i = 0; i < model.candidates.size(); ++i) {
                    const auto *text = findText(scene, model.candidates[i]);
                    CHECK(text != nullptr);
                    if (text) CHECK(text->medium == (i == model.highlighted) && text->size == L.metrics.font);
                }
                CHECK((findText(scene, "1") != nullptr) && (findText(scene, "8") != nullptr));
            }
        }
    }
    model.expanded = false;
    model.shape = GlassShape::Capsule;
    // Transparency off: one flat fill, no rim, nothing blurred but the shadow; the only clip is the paw shadows'.
    const auto L = layoutCandidates(model, measurer);
    const auto flat = buildScene(L, model, makeTheme(Appearance::Light, Palette::Rose, false, false));
    CHECK(count(flat, DrawCommand::Kind::PushClip) == 1);
    for (const auto &c : flat.commands) CHECK(c.kind != DrawCommand::Kind::Image || c.blur == 0);
    // High contrast: no shadow, no cats.
    model.decorations = false;
    const auto hcLayout = layoutCandidates(model, measurer);
    const auto hc = buildScene(hcLayout, model,
                               makeTheme(Appearance::Dark, Palette::Rose, true, true, Color::rgb(0x000000), Color::rgb(0xFFFFFF),
                                         Color::rgb(0x1AEBFF), Color::rgb(0x000000), Color::rgb(0x808080)));
    CHECK(count(hc, DrawCommand::Kind::Shadow) == 0 && count(hc, DrawCommand::Kind::Image) == 0);
    CHECK(count(hc, DrawCommand::Kind::StrokePath) == 1);   // the border
    // A pressed candidate gets its own wash.
    model.decorations = true;
    model.hovered.reset();
    model.pressed = 5;
    const auto pressed = buildScene(L, model, makeTheme(Appearance::Light, Palette::Rose, true, false));
    CHECK(count(pressed, DrawCommand::Kind::FillRoundRect) == 2);
    // It is the pill at 0.95, about the same centre.
    bool shrunk = false;
    for (const auto &c : pressed.commands) {
        if (c.kind != DrawCommand::Kind::FillRoundRect) continue;
        const auto &pill = L.cells[5].pill;
        if (std::fabs(c.rect.width - pill.width * 0.95f) < 0.01f && std::fabs(c.rect.midX() - pill.midX()) < 0.01f &&
            std::fabs(c.rect.height - pill.height * 0.95f) < 0.01f)
            shrunk = true;
    }
    CHECK(shrunk);
}

void testSvg() {
    FixedMeasurer measurer;
    auto model = sample();
    model.highlighted = 2;
    const auto L = layoutCandidates(model, measurer);
    const auto scene = buildScene(L, model, makeTheme(Appearance::Light, Palette::Rose, true, false));
    const auto svg = renderSvg(scene, SvgAssets{});
    CHECK(svg.find("<svg") == 0);
    CHECK(svg.find("linearGradient") != std::string::npos);
    CHECK(svg.find("\xE4\xBD\xA0\xE5\xA5\xBD\xE5\x91\x80") != std::string::npos); // 你好呀
    CHECK(svg.find("</svg>") != std::string::npos);
    // Every <g> opened for a clip or a filter is closed.
    std::size_t open = 0, close = 0;
    for (std::size_t at = svg.find("<g "); at != std::string::npos; at = svg.find("<g ", at + 1)) ++open;
    for (std::size_t at = svg.find("</g>"); at != std::string::npos; at = svg.find("</g>", at + 1)) ++close;
    CHECK(open == close && open > 0);
}

void testPlacement() {
    FixedMeasurer measurer;
    const auto L = layoutCandidates(sample(), measurer);
    const PixelRect work{0, 0, 1920, 1040};
    for (float scale : {1.0f, 1.5f, 2.0f}) {
        const auto px = [scale](float v) { return static_cast<long>(std::lround(v * scale)); };
        // Room below: the cats start just under the caret, the first candidate at its left edge.
        const PixelRect caret{600, 300, 602, 330};
        auto at = placeNearCaret(L, scale, caret, work);
        CHECK(at.x + px(L.anchorX) == caret.left);
        CHECK(at.y + px(L.content.y) == caret.bottom + px(6));
        // Near the bottom: above the caret, glass just over the line.
        const PixelRect low{600, 1000, 602, 1030};
        at = placeNearCaret(L, scale, low, work);
        CHECK(at.y + px(L.content.bottom()) == low.top - px(6));
        // Right edge: the glass and 莫莫 stay inside the work area.
        const PixelRect right{1900, 300, 1902, 330};
        at = placeNearCaret(L, scale, right, work);
        CHECK(at.x + px(L.content.right()) <= work.right);
        CHECK(at.x + px(L.content.right()) >= work.right - 1);
        // Left edge: 米米莫 stays inside; only the shadow margin may hang out.
        const PixelRect left{2, 300, 4, 330};
        at = placeNearCaret(L, scale, left, work);
        CHECK(at.x + px(L.content.x) >= work.left);
        CHECK(at.x <= work.left);
        // A work area on a monitor to the left of the primary one.
        const PixelRect second{-2560, -200, 0, 1200};
        at = placeNearCaret(L, scale, {-2500, 100, -2498, 130}, second);
        CHECK(at.x + px(L.content.x) >= second.left && at.x + px(L.content.right()) <= second.right);
        CHECK(at.y + px(L.content.y) >= 130);
        // A screen too short for either side: the content still fits inside it.
        const PixelRect tiny{0, 0, 1920, px(L.content.height) + 30};
        at = placeNearCaret(L, scale, {600, 20, 602, 50}, tiny);
        CHECK(at.y + px(L.content.y) >= tiny.top && at.y + px(L.content.bottom()) <= tiny.bottom);
    }
    // The shadow margins are not part of the content; the cats are.
    CHECK(L.content.x > 0 && L.content.right() < L.size.width && L.content.bottom() < L.size.height);
    for (const auto &cat : L.cats) CHECK(L.content.y <= cat.body().y);
    // Clicks land on the glass and the cats, not on the shadow around them.
    CHECK(coversPoint(L, {L.glass.bounds.midX(), L.glass.body.midY()}));
    CHECK(coversPoint(L, {L.cats[1].touch().midX(), L.cats[1].touch().y + 3}));
    CHECK(!coversPoint(L, {L.glass.bounds.midX(), L.size.height - 2}));
    CHECK(!coversPoint(L, {L.glass.bounds.midX(), 1}));
}

void testShadowMask() {
    const std::vector<PointF> square{{20, 20}, {80, 20}, {80, 60}, {20, 60}};
    for (float scale : {1.0f, 0.5f}) {
        const auto mask = shadowMask(square, {100, 80}, scale, 4);
        CHECK(mask.width == static_cast<int>(100 * scale) && mask.height == static_cast<int>(80 * scale));
        CHECK(mask.alpha.size() == static_cast<std::size_t>(mask.width * mask.height));
        const auto at = [&](float x, float y) {
            return mask.alpha[static_cast<std::size_t>(static_cast<int>(y * scale) * mask.width + static_cast<int>(x * scale))];
        };
        CHECK(at(50, 40) > 245);              // well inside
        CHECK(at(2, 2) == 0 && at(97, 77) == 0);
        CHECK(at(20, 40) > 90 && at(20, 40) < 165);   // on the edge: about half
        CHECK(at(14, 40) > 0 && at(14, 40) < at(20, 40));
    }
    CHECK(shadowMask({}, {100, 80}, 1, 4).alpha.empty());
    // An unblurred mask is the plain coverage.
    const auto hard = shadowMask(square, {100, 80}, 1, 0);
    CHECK(hard.alpha[40 * 100 + 50] == 255 && hard.alpha[40 * 100 + 10] == 0 && hard.alpha[10 * 100 + 50] == 0);
    // Blurring premultiplied pixels keeps a flat area flat and softens an edge.
    std::vector<std::uint8_t> px(40 * 40 * 4, 0);
    for (int y = 0; y < 40; ++y)
        for (int x = 20; x < 40; ++x)
            for (int c = 0; c < 4; ++c) px[static_cast<std::size_t>((y * 40 + x) * 4 + c)] = 200;
    blurPremultiplied(px, 40, 40, 2);
    CHECK(px[(20 * 40 + 34) * 4] > 195 && px[(20 * 40 + 4) * 4] == 0);
    CHECK(px[(20 * 40 + 20) * 4] > 60 && px[(20 * 40 + 20) * 4] < 140);
}
std::string readFile(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

// The repository root, from this file's path.
std::string repoRoot() {
    const std::string file = __FILE__;
    const auto at = file.rfind("Windows/UI/tests/");
    return at == std::string::npos ? std::string() : file.substr(0, at); // "" when compiled from the root
}

LocalDate date(const std::string &text) {
    LocalDate d;
    parseLocalDate(text, d);
    return d;
}

// The same cases as iOS/tests/FestivalsTests.swift and Android's LogicTest.
void testFestivals() {
    const std::string root = repoRoot();
    std::string error;
    const auto fixture = parseFestivalManifest(readFile(root + "Android/app/src/test/resources/festivals/fixture.json"), &error);
    CHECK(fixture.themes.size() == 4 && fixture.leadDays == 7 && !error.empty());
    CHECK(fixture.themes.size() == 4 && fixture.themes[0].id == "alpha" && fixture.themes[1].id == "beta" &&
          fixture.themes[2].id == "gamma" && fixture.themes[3].id == "delta");
    if (fixture.themes.size() != 4) return;
    const auto &alpha = fixture.themes[0];
    CHECK(alpha.trim.caps.left == 70 && alpha.trim.caps.right == 70 && alpha.trim.safeText.left == 90);
    CHECK(alpha.momo.image == "alpha_momo" && alpha.ja == "\xE3\x82\xA2\xE3\x83\xAB\xE3\x83\x95\xE3\x82\xA1");
    CHECK(alpha.days.size() == 1 && alpha.days[0].first.key() == 20270205 && alpha.days[0].main.key() == 20270206 &&
          alpha.days[0].last.key() == 20270212);
    CHECK(alpha.colors.dark.size() == 2 && alpha.colors.dark[1] == "#990000");

    const auto worn = [&](const char *day, const FestivalWardrobe &wardrobe) {
        const auto *theme = wornTheme(fixture, date(day), wardrobe);
        return theme ? theme->id : std::string("-");
    };
    const FestivalWardrobe all;
    const char *days[] = {"2027-01-28", "2027-01-29", "2027-02-02", "2027-02-06", "2027-02-07", "2027-02-09",
                          "2027-02-10", "2027-02-12", "2027-02-13", "2027-02-14", "2027-02-15"};
    const char *want[] = {"-", "alpha", "alpha", "alpha", "alpha", "delta", "alpha", "alpha", "beta", "gamma", "-"};
    for (int i = 0; i < 11; ++i) CHECK(worn(days[i], all) == want[i]);
    CHECK(worn("2027-02-13", {true, {"beta"}}) == "gamma" && worn("2027-02-14", {true, {"beta"}}) == "gamma");
    CHECK(worn("2027-02-06", {true, {"alpha"}}) == "delta" && worn("2027-02-06", {false, {}}) == "-");
    const auto met = [&](const char *since, const char *today) {
        std::string ids;
        for (const auto *theme : metThemes(fixture, date(since), date(today))) ids += theme->id + " ";
        return ids;
    };
    CHECK(met("2027-02-01", "2027-02-01") == "alpha " && met("2027-02-01", "2027-02-07") == "alpha beta gamma delta ");
    CHECK(met("2027-02-13", "2027-02-13") == "beta gamma " && met("2027-03-01", "2028-02-07") == "beta ");

    // Every day of 2026-2030 against the file the Swift tests write.
    const auto shipped = parseFestivalManifest(readFile(root + "Windows/UI/Assets/festivals/festivals.json"));
    CHECK(shipped.themes.size() == 24);
    std::ifstream lines(root + "Android/app/src/test/resources/festivals/worn-2026-2030.txt");
    std::string line;
    std::getline(lines, line);
    FestivalWardrobe some;
    {
        std::istringstream ids(line.substr(line.find(": ") + 2));
        for (std::string id; ids >> id;) some.skipped.insert(id);
    }
    int compared = 0, wrong = 0;
    while (std::getline(lines, line)) {
        std::istringstream fields(line);
        std::string day, a, b;
        fields >> day >> a >> b;
        const auto *x = wornTheme(shipped, date(day), all), *y = wornTheme(shipped, date(day), some);
        if ((x ? x->id : "-") != a || (y ? y->id : "-") != b) {
            if (++wrong <= 5) std::fprintf(stderr, "worn on %s differs\n", day.c_str());
        }
        ++compared;
    }
    CHECK(compared == 1826 && wrong == 0 && some.skipped.size() == 4);

    // A festival day: the dressed pair at the ends, the cluster past the arrow, on every shape.
    for (const auto &theme : shipped.themes) {
        const auto art = festivalArt(theme);
        CHECK(art != nullptr && art->rightWidth > 0);
        if (!art) continue;
        for (auto shape : {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud}) {
            FixedMeasurer measurer;
            auto model = sample();
            model.shape = shape;
            model.festival = art;
            const auto L = layoutCandidates(model, measurer);
            CHECK(L.dressed && L.cats.size() == 2 && L.ornament && L.ornament->x > L.chevron.midX() && L.ornament->right() <= L.size.width);
            model.decorations = false;  // high contrast: no cats, no ornaments
            const auto plain = layoutCandidates(model, measurer);
            CHECK(!plain.dressed && !plain.ornament);
        }
    }

    CHECK(parseFestivalManifest(R"({"version":1,"outfits":[]})").themes.empty());
    CHECK(parseFestivalManifest("").themes.empty());
    CHECK(parseFestivalManifest("{not json").themes.empty());
    // Nesting deeper than the reader allows is refused, not followed.
    const std::string deep = std::string(200, '[') + std::string(200, ']');
    CHECK(parseFestivalManifest("{\"version\":2,\"leadDays\":7,\"x\":" + deep + ",\"themes\":[]}", &error).themes.empty());
    LocalDate d;
    CHECK(parseLocalDate("2028-02-29", d) && !parseLocalDate("2027-02-29", d) && !parseLocalDate("2027-13-01", d));
    CHECK(dayNumber(date("1970-01-01")) == 0 && dayNumber(date("2028-03-01")) - dayNumber(date("2028-02-28")) == 2);
    CHECK(!parseHexColor("C8463C") && parseHexColor("#c8463c"));

    // The capsule takes the theme's light or dark pair.
    auto theme = makeTheme(Appearance::Light, Palette::Rose, true, false);
    applyFestivalPill(theme, alpha);
    CHECK(std::fabs(theme.pillTop.r - 0xBB / 255.0f) < 1e-3f && std::fabs(theme.pillBottom.r - 0xAA / 255.0f) < 1e-3f);
    auto dark = makeTheme(Appearance::Dark, Palette::Rose, true, false);
    applyFestivalPill(dark, alpha);
    CHECK(std::fabs(dark.pillBottom.r - 0x99 / 255.0f) < 1e-3f);
    auto contrast = makeTheme(Appearance::Light, Palette::Rose, true, true);
    const auto before = contrast.pillBottom.r;
    applyFestivalPill(contrast, alpha);
    CHECK(contrast.pillBottom.r == before);

    // The cats' pictures reach the snapshot by name.
    FixedMeasurer measurer;
    const auto model = sample();
    const auto L = layoutCandidates(model, measurer);
    SvgAssets assets;
    assets.mimimo = "mimimo.png";
    assets.momo = "momo.png";
    const auto svg = renderSvg(buildScene(L, model, theme), assets);
    CHECK(svg.find("mimimo.png") != std::string::npos && svg.find("momo.png") != std::string::npos);
}
} // namespace

int main() {
    testShapes();
    testCollapsed();
    testCatsSeat();
    testJapaneseNeverWraps();
    testEllipsis();
    testNarrowScreen();
    testExpandedAndPaging();
    testHitTesting();
    testZhuyinNumbersAndContrast();
    testScene();
    testSvg();
    testPlacement();
    testShadowMask();
    testFestivals();
    if (failures) { std::fprintf(stderr, "%d failure(s)\n", failures); return 1; }
    std::puts("layout_test: all checks passed");
    return 0;
}
