#include "CandidateLayout.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>

namespace meowpuut::ui {
namespace {

float pawWidth(const Metrics &m) { return std::round(m.font * m.pawRatio); }

std::string numberLabel(std::size_t index, bool shift) {
    return (shift ? std::string("\xE2\x87\xA7") : std::string()) + std::to_string(index + 1);
}

struct Measured {
    std::string number;
    float numberWidth = 0;
    std::string full;    // the candidate as given
    std::string text;    // what is drawn: `full`, or `full` cut in the middle
    float natural = 0;   // full text width
    float allowed = 0;   // after ellipsis budgeting
    bool japanese = false;
};

float overhead(const Metrics &m, const Measured &c) {
    float w = 2 * m.cellPad;
    if (c.numberWidth > 0) w += c.numberWidth + m.numberGap;
    if (c.japanese) w += m.pawGap + pawWidth(m);
    return w;
}

float cellWidth(const Metrics &m, const Measured &c) { return overhead(m, c) + c.allowed; }

std::vector<Measured> measureAll(const CandidateViewModel &model, TextMeasurer &measurer, const Metrics &m) {
    std::vector<Measured> cells;
    cells.reserve(model.candidates.size());
    for (std::size_t i = 0; i < model.candidates.size(); ++i) {
        Measured c;
        c.number = numberLabel(i, model.shiftNumbers);
        c.numberWidth = std::ceil(measurer.measure(c.number, m.numberFont, false));
        c.full = c.text = model.candidates[i];
        // Every cell is measured in the selected (medium) weight so moving the
        // highlight never shifts the row.
        c.natural = std::ceil(measurer.measure(c.text, m.font, true));
        c.allowed = c.natural;
        c.japanese = i < model.japanese.size() && model.japanese[i];
        cells.push_back(std::move(c));
    }
    return cells;
}

float rowWidth(const std::vector<Measured> &cells, const Metrics &m) {
    if (cells.empty()) return 0;
    float w = m.cellGap * static_cast<float>(cells.size() - 1);
    for (const auto &c : cells) w += cellWidth(m, c);
    return w;
}

// Lowers the widest texts to a common level until the row fits `budget`.
// No text goes below `floor`.
void waterFill(std::vector<Measured> &cells, const Metrics &m, float budget, float floor) {
    if (cells.empty()) return;
    float fixed = m.cellGap * static_cast<float>(cells.size() - 1);
    for (const auto &c : cells) fixed += overhead(m, c);
    const float textBudget = budget - fixed;
    float total = 0;
    for (const auto &c : cells) total += c.allowed;
    if (total <= textBudget) return;
    std::vector<float> widths;
    for (const auto &c : cells) widths.push_back(c.allowed);
    std::sort(widths.begin(), widths.end(), std::greater<float>());
    // Find level L with sum(min(w, L)) == textBudget.
    float level = floor;
    for (std::size_t k = 0; k < widths.size(); ++k) {
        const float below = std::accumulate(widths.begin() + static_cast<long>(k) + 1, widths.end(), 0.0f);
        const float candidate = (textBudget - below) / static_cast<float>(k + 1);
        const float next = k + 1 < widths.size() ? widths[k + 1] : 0.0f;
        if (candidate >= next) { level = candidate; break; }
    }
    level = std::max(level, floor);
    for (auto &c : cells) c.allowed = std::min(c.allowed, level);
}

void applyEllipsis(std::vector<Measured> &cells, TextMeasurer &measurer, const Metrics &m) {
    for (auto &c : cells) {
        if (c.allowed + 0.5f >= c.natural) { c.allowed = c.natural; continue; }
        c.text = ellipsizeMiddle(c.full, c.allowed, m.font, true, measurer);
        c.allowed = std::ceil(measurer.measure(c.text, m.font, true));
    }
}

// The ellipsis drops whole characters, so a squeezed row can end up short of
// `budget`. The first cut candidates take the spare width back.
void useSpareWidth(std::vector<Measured> &cells, TextMeasurer &measurer, const Metrics &m, float budget, float cap) {
    float spare = budget - rowWidth(cells, m);
    for (auto &c : cells) {
        if (spare < 1) return;
        if (c.allowed + 0.5f >= c.natural) continue;
        const float room = std::min(cap, c.allowed + spare);
        std::string wider = ellipsizeMiddle(c.full, room, m.font, true, measurer);
        const float width = std::ceil(measurer.measure(wider, m.font, true));
        if (width <= c.allowed || width > c.allowed + spare) continue;
        spare -= width - c.allowed;
        c.text = std::move(wider);
        c.allowed = width;
    }
}

CandidateCell placeCell(const Metrics &m, const Measured &c, std::size_t index, float x, float width,
                        float top, float height, float pillHeight) {
    CandidateCell cell;
    cell.index = index;
    cell.frame = {x, top, width, height};
    const float cy = top + height / 2;
    cell.centerY = cy;
    cell.pill = {x, cy - pillHeight / 2, width, pillHeight};
    cell.number = c.number;
    cell.numberWidth = c.numberWidth;
    cell.numberX = x + m.cellPad;
    cell.textX = cell.numberX + (c.numberWidth > 0 ? c.numberWidth + m.numberGap : 0);
    cell.text = c.text;
    cell.textWidth = c.allowed;
    cell.truncated = c.allowed + 0.5f < c.natural;
    cell.japanese = c.japanese;
    if (c.japanese) {
        const float pw = pawWidth(m);
        cell.paw = {cell.textX + c.allowed + m.pawGap, cy - pw / 2 - 0.5f, pw, pw};
    }
    return cell;
}

RectF glassBox(GlassShape shape, float left, float right, float bodyTop, float bodyHeight) {
    switch (shape) {
    case GlassShape::Wave: {
        const float h = bodyHeight / (1 - 2 * 0.065f);
        return {left, bodyTop - (h - bodyHeight) / 2, right - left, h};
    }
    case GlassShape::Cloud: {
        const float h = bodyHeight / (1 - 0.16f);
        return {left, bodyTop - (h - bodyHeight), right - left, h};
    }
    case GlassShape::Capsule: break;
    }
    return {left, bodyTop, right - left, bodyHeight};
}

RectF panelBox(GlassShape shape, float left, float right, float bodyTop, float bodyHeight, float barHeight) {
    switch (shape) {
    case GlassShape::Wave: {
        const float a = waveAmplitude(barHeight);
        return {left, bodyTop - a, right - left, bodyHeight + 2 * a};
    }
    case GlassShape::Cloud: {
        const float rise = cloudRise(barHeight);
        return {left, bodyTop - rise, right - left, bodyHeight + rise};
    }
    case GlassShape::Capsule: break;
    }
    return {left, bodyTop, right - left, bodyHeight};
}

// The two boxes of BarFrame for a glass that starts at `glassLeft`, with the
// right box at `rightX`. The window's edges and `content` are measured from them.
struct FrameBoxes {
    RectF left, right;
};

FrameBoxes frameBoxes(float bodyTop, float glassLeft, float rightX) {
    const float wl = BarFrame::leftWidth, hl = BarFrame::leftHeight;
    const float wr = BarFrame::rightWidth, hr = BarFrame::rightHeight;
    return {{glassLeft - BarFrame::glassStart * wl, bodyTop - 1 - hl * BarFrame::leftSeat, wl, hl},
            {rightX, bodyTop - 1 - hr * BarFrame::rightSeat, wr, hr}};
}

// The glass's top edge, read from the outline's samples.
class TopEdge {
public:
    explicit TopEdge(const GlassOutline &glass) : points_(outlineRun(glass, OutlineEdge::Top, 0, 0, 1)) {}

    float at(float x) const {
        std::size_t i = 1;
        while (i + 1 < points_.size() && points_[i].x < x) ++i;
        const PointF a = points_[i - 1], b = points_[i];
        return a.y + (b.y - a.y) * clampf((x - a.x) / (b.x - a.x), 0, 1);
    }

    // Between two x positions: the edge's lowest point, and how far the edge
    // rises above it anywhere on the stretch and over its middle half. A cat
    // is seated on the lowest point, so this much of it is behind the glass.
    struct Rise {
        float low, most, middle;
    };
    Rise rise(float from, float to) const {
        const float quarter = (to - from) / 4;
        float low = at(from), high = low, middle = std::numeric_limits<float>::max();
        for (float x = from;; x += 1) {
            const float here = std::min(x, to), y = at(here);
            low = std::max(low, y);
            high = std::min(high, y);
            if (here >= from + quarter && here <= to - quarter) middle = std::min(middle, y);
            if (x >= to) break;
        }
        return {low, low - high, low - std::min(low, middle)};
    }

private:
    std::vector<PointF> points_;
};

// The bar as the cats see it.
struct CatStage {
    const TopEdge &edge;
    RectF glass;       // the glass's rect
    float unit;        // gaps and tolerances are in these: 1 on a 46 pt bar
    float endRadius;   // of the glass's round ends
    float room;        // how far a cat rises above its seat
    float top;         // nothing of a cat is above this line
    RectF row, arrow;  // the candidates' band and the expand arrow: no paw covers either
};

// A cat at one end of the top edge. Its cut starts just inside the round end
// and slides inward until the edge under it is level enough and its paws are
// clear of what is drawn on the glass. `at`, when given, is tried first: where
// the cut started on the collapsed bar.
std::optional<CatPlacement> seatCat(Cat cat, const CatSprite &art, bool left, const CatStage &s, std::optional<float> at = std::nullopt) {
    const float scale = std::min(1.0f, s.room / ((art.anchorY - art.top) * art.height));
    const float width = art.width * scale, height = art.height * scale;
    const float span = (art.cutRight - art.cutLeft) * width;
    // How far into the round end the cut may start, and how much of the cat
    // the edge may hide: at its sides, and under its face.
    const float cornerReach = 4 * s.unit, hiddenSide = 4 * s.unit, hiddenFace = 1.5f * s.unit;
    for (float slide = at ? -1 : 0; slide <= 0.3f * s.glass.width; slide += slide < 0 ? 1 : 2 * s.unit) {
        const float from = slide < 0 ? *at
                           : left    ? s.glass.x + s.endRadius - cornerReach + slide
                                     : s.glass.right() - s.endRadius + cornerReach - slide - span;
        const TopEdge::Rise rise = s.edge.rise(from, from + span);
        if (rise.most > hiddenSide || rise.middle > hiddenFace) continue;
        CatPlacement place;
        place.cat = cat;
        place.sprite = &art;
        const float x = from - art.cutLeft * width;
        // A paw sinks into the glass by as much as the edge rises under it.
        bool level = true;
        for (int i = 0; i < art.pawCount; ++i) {
            const float centre = x + art.paws[i].x * width, half = art.paws[i].halfWidth * width;
            const TopEdge::Rise under = s.edge.rise(centre - half, centre + half);
            level = level && rise.low - (under.low - under.most) <= hiddenFace;
        }
        if (!level) continue;
        place.anchor = {x + art.anchorX * width, rise.low};
        place.frame = {x, rise.low - art.anchorY * height, width, height};
        if (place.body().y < s.top) continue;
        bool covers = false;
        for (int i = 0; i < art.pawCount; ++i)
            covers = covers || place.paw(i).intersects(s.row) || place.paw(i).intersects(s.arrow);
        if (!covers) return place;
    }
    return std::nullopt;
}

// 米米莫 at the left end of the top edge, 莫莫 at the right end: the
// arrangement `ends` of Sources/CatSprites.swift. `row` is the band the
// candidates take and `stageTop` the top of the room kept for the cats.
// `bar` is the collapsed bar when this is the panel: 米米莫 keeps his seat
// from there where the panel's edge allows, so he does not jump when it opens.
void seatCats(CandidateLayout &L, float bodyTop, float stageTop, const RectF &row, const CandidateLayout *bar = nullptr) {
    const TopEdge edge(L.glass);
    // The arrow as Scene.cpp draws it: 9.2 × 4.8 with a 1.7 stroke.
    const RectF arrow{L.chevron.midX() - 5.5f, L.chevron.midY() - 3.3f, 11, 6.6f};
    // Units come from the collapsed bar's glass, so the panel seats the cats
    // the same way. The three shapes share the body's top line here, not the
    // top of their rects (DESIGN.md §5): the room is measured from that line
    // and the cats are one size on every shape.
    const float unit = glassBox(L.shape, 0, 1, 0, L.metrics.barHeight).height / 46;
    const CatStage stage{edge, L.glass.bounds, unit, L.metrics.barHeight / 2, bodyTop - stageTop - 1.5f, stageTop, row, arrow};
    std::optional<float> at;
    if (bar && !bar->cats.empty() && bar->cats.front().cat == Cat::Mimimo) {
        const CatPlacement &was = bar->cats.front();
        at = was.frame.x + was.sprite->cutLeft * was.frame.width;
    }
    // On a festival day the dressed pair, when both fit; otherwise the everyday cats.
    if (L.festival) {
        const auto mimimo = seatCat(Cat::Mimimo, L.festival->mimimo, true, stage, at);
        const auto momo = seatCat(Cat::Momo, L.festival->momo, false, stage);
        if (mimimo && momo && !mimimo->body().inset(-4 * unit, 0).intersects(momo->body())) {
            L.cats = {*mimimo, *momo};
            L.dressed = true;
            return;
        }
    }
    const auto mimimo = seatCat(Cat::Mimimo, *catArt(Cat::Mimimo).sprite, true, stage, at);
    const auto momo = seatCat(Cat::Momo, *catArt(Cat::Momo).sprite, false, stage);
    // Should the two ever meet, 莫莫 stays: five clicks on her switch the script.
    const bool apart = !mimimo || !momo || !mimimo->body().inset(-4 * unit, 0).intersects(momo->body());
    if (mimimo && apart) L.cats.push_back(*mimimo);
    if (momo) L.cats.push_back(*momo);
}

} // namespace

// The festival's cluster at the bar's right end (FestivalEndsLayout in
// Sources/Festivals.swift): between the arrow and the window's edge, on the
// glass's lower edge, no taller than the glass's body; none when it would come
// out under 60 % of that.
static std::optional<RectF> festivalOrnament(const CandidateLayout &L, float windowRight) {
    if (!L.festival || L.festival->rightWidth <= 0) return std::nullopt;
    const RectF &body = L.glass.body;
    const float w = L.festival->rightWidth, h = L.festival->rightHeight;
    const float end = std::min(windowRight - 1, L.glass.bounds.right() + 4);
    const float arrowRight = L.chevron.midX() + 5.5f;
    const float k = std::min(body.height / h, (end - arrowRight - 2) / w);
    if (h * k < 0.6f * body.height) return std::nullopt;
    return RectF{end - w * k, body.bottom() + 1 - h * k, w * k, h * k};
}

CatArt catArt(Cat cat) {
    const std::string_view name = cat == Cat::Mimimo ? "mimimo_edge_peek" : "momo_edge_peek";
    for (const CatSprite &sprite : kCatSprites)
        if (name == sprite.name) return {&sprite, std::string(name) + ".png"};
    return {};
}

RectF CatPlacement::body() const {
    return {frame.x + sprite->left * frame.width, frame.y + sprite->top * frame.height,
            (sprite->right - sprite->left) * frame.width, (sprite->bottom - sprite->top) * frame.height};
}

RectF CatPlacement::touch() const {
    const RectF box = body();
    return {box.x, box.y, box.width, anchor.y - box.y};
}

RectF CatPlacement::paw(int index) const {
    const CatPaw &p = sprite->paws[index];
    return {frame.x + (p.x - p.halfWidth) * frame.width - 0.5f, anchor.y, 2 * p.halfWidth * frame.width + 1,
            p.depth * frame.height + 0.5f};
}

CatReaction catReaction(Cat cat, float t) {
    CatReaction r;
    if (t <= 0 || t >= 1) return r;
    const float pi = 3.14159265f, settle = 1 - t;
    if (cat == Cat::Mimimo) {
        // Ducks once and shakes his head on the way back up.
        const float duck = std::sin(pi * t);
        r.scaleX = 1 + 0.04f * duck;
        r.scaleY = 1 - 0.07f * duck;
        r.shift = 1.2f * std::sin(4 * pi * t) * settle;
    } else {
        // Leans to one side, then a little to the other.
        r.tilt = 4 * std::sin(2 * pi * t) * settle;
    }
    return r;
}

std::vector<std::string_view> utf8CodePoints(std::string_view text) {
    std::vector<std::string_view> out;
    std::size_t i = 0;
    while (i < text.size()) {
        const auto c = static_cast<unsigned char>(text[i]);
        std::size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        len = std::min(len, text.size() - i);
        out.push_back(text.substr(i, len));
        i += len;
    }
    return out;
}

namespace {
char32_t decode(std::string_view cp) {
    const auto b = [&](std::size_t i) { return static_cast<char32_t>(static_cast<unsigned char>(cp[i])); };
    switch (cp.size()) {
    case 2: return ((b(0) & 0x1F) << 6) | (b(1) & 0x3F);
    case 3: return ((b(0) & 0x0F) << 12) | ((b(1) & 0x3F) << 6) | (b(2) & 0x3F);
    case 4: return ((b(0) & 0x07) << 18) | ((b(1) & 0x3F) << 12) | ((b(2) & 0x3F) << 6) | (b(3) & 0x3F);
    default: return cp.empty() ? 0 : b(0);
    }
}

// Combining marks, kana voicing marks, variation selectors, emoji skin tones
// and the zero-width joiner stay with the character before them.
bool joinsPrevious(char32_t c) {
    return (c >= 0x0300 && c <= 0x036F) || c == 0x3099 || c == 0x309A || (c >= 0xFE00 && c <= 0xFE0F) ||
           (c >= 0xE0100 && c <= 0xE01EF) || (c >= 0x1F3FB && c <= 0x1F3FF) || c == 0x200D;
}

bool regionalIndicator(char32_t c) { return c >= 0x1F1E6 && c <= 0x1F1FF; }

// The pieces an ellipsis may fall between.
std::vector<std::string_view> ellipsisUnits(std::string_view text) {
    std::vector<std::string_view> units;
    bool afterJoiner = false, halfFlag = false;
    for (const auto cp : utf8CodePoints(text)) {
        const char32_t c = decode(cp);
        const bool closesFlag = halfFlag && regionalIndicator(c);
        if (!units.empty() && (afterJoiner || closesFlag || joinsPrevious(c))) {
            units.back() = std::string_view(units.back().data(), units.back().size() + cp.size());
        } else {
            units.push_back(cp);
        }
        halfFlag = regionalIndicator(c) && !closesFlag;
        afterJoiner = c == 0x200D;
    }
    return units;
}
} // namespace

std::string ellipsizeMiddle(std::string_view text, float maxWidth, float size, bool medium, TextMeasurer &measurer) {
    if (measurer.measure(text, size, medium) <= maxWidth) return std::string(text);
    const auto cps = ellipsisUnits(text);
    const std::string ellipsis = "\xE2\x80\xA6";
    const auto build = [&](std::size_t keep) {
        const std::size_t head = (keep + 1) / 2, tail = keep / 2;
        std::string s;
        for (std::size_t i = 0; i < head; ++i) s += cps[i];
        s += ellipsis;
        for (std::size_t i = cps.size() - tail; i < cps.size(); ++i) s += cps[i];
        return s;
    };
    std::size_t lo = 0, hi = cps.empty() ? 0 : cps.size() - 1;
    std::string best = ellipsis;
    while (lo < hi) {
        const std::size_t mid = (lo + hi + 1) / 2;
        auto candidate = build(mid);
        if (measurer.measure(candidate, size, medium) <= maxWidth) { lo = mid; best = std::move(candidate); }
        else hi = mid - 1;
    }
    return best;
}

CandidateLayout layoutCandidates(const CandidateViewModel &model, TextMeasurer &measurer,
                                 const LayoutLimits &limits, const Metrics &m) {
    CandidateLayout L;
    L.metrics = m;
    L.shape = model.shape;
    L.expanded = model.expanded;
    L.decorations = model.decorations;
    L.festival = model.decorations ? model.festival : nullptr;
    L.chevronUp = model.expanded;
    const bool deco = model.decorations;
    // Every position below that names BarFrame is frozen (see CandidateLayout.h).
    const float wl = BarFrame::leftWidth, wr = BarFrame::rightWidth;
    const float G = m.barHeight;
    const float bodyTop = deco ? std::ceil(m.padTop + BarFrame::leftHeight * BarFrame::leftSeat + 1) : m.padTop + 6;
    // The left box sits two DIPs inside the window; the glass starts a
    // quarter of the way across it.
    const float boxLeft = m.padLeft + 2;
    const float glassLeft = deco ? boxLeft + BarFrame::glassStart * wl : m.padLeft;
    const float rowStart = deco ? boxLeft + BarFrame::rowStart * wl + 2 : glassLeft + 10;
    // The cloud keeps its bumps off the glass's left end.
    const float cloudClear = deco ? (BarFrame::cloudClear - BarFrame::glassStart) * wl : 0;
    auto cells = measureAll(model, measurer, m);

    if (!model.expanded) {
        const float rightZone = deco ? m.chevronWidth - BarFrame::arrowInset + wr + m.padRight : m.chevronWidth + 10 + m.padRight;
        const float maxRow = std::max(m.minRow, limits.maxWidth - rowStart - rightZone);
        // A squeezed candidate keeps at least one character and the ellipsis.
        const float floor = std::ceil(measurer.measure("\xE6\xB0\xB8\xE2\x80\xA6", m.font, true));
        const float cap = std::max(floor, std::min(m.longCandidateMax, m.ellipsisRatio * maxRow));
        for (auto &c : cells) c.allowed = std::min(c.allowed, cap);
        waterFill(cells, m, maxRow, floor);
        applyEllipsis(cells, measurer, m);
        useSpareWidth(cells, measurer, m, maxRow, cap);

        const float pillHeight = std::round(G * m.pillRatio);
        float x = rowStart;
        for (std::size_t i = 0; i < cells.size(); ++i) {
            const float w = cellWidth(m, cells[i]);
            L.cells.push_back(placeCell(m, cells[i], i, x, w, bodyTop, G, pillHeight));
            L.cells.back().truncated = cells[i].allowed + 0.5f < cells[i].natural;
            x += w + m.cellGap;
        }
        float rowEnd = cells.empty() ? rowStart : x - m.cellGap;
        rowEnd = std::max(rowEnd, rowStart + m.minRow);
        L.chevron = {rowEnd + 2, bodyTop, m.chevronWidth, G};
        const float rightBoxX = L.chevron.right() - BarFrame::arrowInset;
        const float glassRight = deco ? rightBoxX + wr - 2 : L.chevron.right() + 8;
        L.glass = makeBarOutline(model.shape, glassBox(model.shape, glassLeft, glassRight, bodyTop, G), cloudClear);
        L.content = L.glass.bounds;
        float bottom = L.glass.bounds.bottom();
        if (deco) {
            const FrameBoxes frame = frameBoxes(bodyTop, glassLeft, rightBoxX);
            L.content = unite(unite(L.content, frame.left), frame.right);
            if (model.shape == GlassShape::Capsule) {
                const float h = BarFrame::stripHeight;
                const RectF strip{boxLeft + 7, bodyTop + G + 1 - h, h * BarFrame::stripAspect, h};
                L.content = unite(L.content, strip);
                bottom = std::max(bottom, strip.bottom());
            }
            bottom = std::max(bottom, frame.right.bottom());
            seatCats(L, bodyTop, frame.left.y, {rowStart, bodyTop + (G - pillHeight) / 2, rowEnd - rowStart, pillHeight});
            L.ornament = festivalOrnament(L, glassRight + m.padRight);
        }
        L.size = {glassRight + m.padRight, std::ceil(bottom + m.padBottom)};
        L.anchorX = L.cells.empty() ? rowStart : L.cells.front().frame.x;
        return L;
    }

    // Expanded grid --------------------------------------------------------
    const float insetX = 14;
    const float panelLeft = glassLeft;
    const float chromeRight = m.padRight;
    float maxNatural = 0;
    for (const auto &c : cells) maxNatural = std::max(maxNatural, overhead(m, c) + c.natural);
    std::size_t cols = maxNatural <= 150 ? 3 : maxNatural <= 240 ? 2 : 1;
    cols = std::max<std::size_t>(1, std::min(cols, std::max<std::size_t>(1, cells.size())));
    const float maxInner = std::max(m.panelMinWidth, std::min(m.panelMaxWidth, limits.maxWidth - panelLeft - chromeRight)) - 2 * insetX;
    const float minInner = m.panelMinWidth - 2 * insetX;
    // One column takes the longest candidate whole, up to the panel's maximum.
    const float widest = cols == 1 ? maxNatural : std::min(maxNatural, 220.0f);
    const float wanted = static_cast<float>(cols) * std::max(m.gridMinCell, widest) +
                         static_cast<float>(cols - 1) * m.gridColumnGap;
    const float inner = std::clamp(wanted, minInner, std::max(minInner, maxInner));
    const float colWidth = (inner - static_cast<float>(cols - 1) * m.gridColumnGap) / static_cast<float>(cols);
    const float panelRight = panelLeft + inner + 2 * insetX;
    const float rightBoxX = deco ? panelRight + 2 - wr : panelRight;

    // Header: the reading and the mode.
    L.preedit = model.preedit;
    L.modeLabel = model.modeLabel;
    L.headerY = bodyTop + 4 + m.headerHeight / 2;
    L.preeditX = std::max(panelLeft + insetX, rowStart);
    L.modeRight = deco ? rightBoxX - 6 : panelRight - insetX;
    if (!L.preedit.empty()) {
        const float room = std::max(20.0f, L.modeRight - 50 - L.preeditX);
        L.preedit = ellipsizeMiddle(L.preedit, room, m.headerFont, false, measurer);
    }

    const float gridTop = bodyTop + 4 + m.headerHeight + 4;
    const float pillHeight = m.gridCellHeight - 4;
    std::size_t used = 0;
    float rowY = gridTop;
    for (std::size_t i = 0; i < cells.size(); ++i) {
        auto c = cells[i];
        const float need = overhead(m, c) + c.natural;
        std::size_t span = 1;
        while (span < cols && static_cast<float>(span) * colWidth + static_cast<float>(span - 1) * m.gridColumnGap < need) ++span;
        if (used > 0 && used + span > cols) { used = 0; rowY += m.gridCellHeight + m.gridRowGap; }
        const float width = static_cast<float>(span) * colWidth + static_cast<float>(span - 1) * m.gridColumnGap;
        const float textRoom = width - overhead(m, c);
        if (c.natural > textRoom) {
            c.allowed = std::max(0.0f, textRoom);
            c.text = ellipsizeMiddle(c.full, c.allowed, m.font, true, measurer);
            c.allowed = std::ceil(measurer.measure(c.text, m.font, true));
        }
        const float x = panelLeft + insetX + static_cast<float>(used) * (colWidth + m.gridColumnGap);
        auto cell = placeCell(m, c, i, x, width, rowY, m.gridCellHeight, pillHeight);
        cell.truncated = c.allowed + 0.5f < c.natural;
        L.cells.push_back(cell);
        used += span;
        if (used >= cols) { used = 0; rowY += m.gridCellHeight + m.gridRowGap; }
    }
    const float gridBottom = cells.empty() ? gridTop + m.gridCellHeight : (used == 0 ? rowY - m.gridRowGap : rowY + m.gridCellHeight);
    const float footerTop = gridBottom + 6;
    L.canPrev = model.page > 0;
    L.canNext = !model.lastPage;
    const float footerMid = footerTop + m.footerHeight / 2;
    const float centerX = panelLeft + (panelRight - panelLeft) / 2;
    L.pageLabel = std::to_string(model.page + 1);
    L.pageLabelCenter = {centerX, footerMid};
    const float buttonWidth = 78;
    L.prevPage = {centerX - 18 - buttonWidth, footerTop, buttonWidth, m.footerHeight};
    L.nextPage = {centerX + 18, footerTop, buttonWidth, m.footerHeight};
    L.chevron = {panelRight - insetX - m.chevronWidth + 6, footerTop, m.chevronWidth, m.footerHeight};
    const float panelBottom = footerTop + m.footerHeight + 6;
    // The swell and the bumps keep the size they have on the collapsed bar.
    const float barBox = glassBox(model.shape, 0, 1, 0, G).height;
    L.glass = makePanelOutline(model.shape,
                               panelBox(model.shape, panelLeft, panelRight, bodyTop, panelBottom - bodyTop, barBox),
                               m.panelCorner, barBox, cloudClear);
    L.content = L.glass.bounds;
    float right = panelRight, bottom = L.glass.bounds.bottom();
    if (deco) {
        const FrameBoxes frame = frameBoxes(bodyTop, glassLeft, rightBoxX);
        L.content = unite(unite(L.content, frame.left), frame.right);
        right = std::max(right, frame.right.right());
        bottom = std::max(bottom, frame.right.bottom());
        // On the panel the paws stay off the header's text and the grid.
        const float textTop = L.headerY - 0.6f * m.headerFont;
        CandidateViewModel collapsed = model;
        collapsed.expanded = false;
        const CandidateLayout bar = layoutCandidates(collapsed, measurer, limits, m);
        seatCats(L, bodyTop, frame.left.y, {panelLeft + insetX, textTop, inner, gridBottom - textTop}, &bar);
    }
    L.size = {right + m.padRight, std::ceil(bottom + m.padBottom)};
    L.anchorX = rowStart;
    return L;
}

Hit hitTest(const CandidateLayout &layout, PointF p) {
    for (const auto &cell : layout.cells)
        if (cell.frame.contains(p)) return {HitKind::Candidate, cell.index};
    if (layout.chevron.contains(p)) return {HitKind::Expand, 0};
    if (layout.expanded) {
        if (layout.canPrev && layout.prevPage.contains(p)) return {HitKind::PrevPage, 0};
        if (layout.canNext && layout.nextPage.contains(p)) return {HitKind::NextPage, 0};
    }
    // A cat takes clicks on what shows above the glass, never on the paws over it.
    for (const auto &cat : layout.cats)
        if (cat.touch().contains(p)) return {cat.cat == Cat::Mimimo ? HitKind::Mimimo : HitKind::Momo, 0};
    return {};
}

bool coversPoint(const CandidateLayout &layout, PointF p) {
    if (outlineContains(layout.glass, p)) return true;
    for (const auto &cat : layout.cats)
        if (cat.touch().contains(p)) return true;
    return false;
}

PixelPoint placeNearCaret(const CandidateLayout &layout, float scale, PixelRect caret, PixelRect work) {
    const auto px = [scale](float dips) { return static_cast<long>(std::lround(dips * scale)); };
    const long gap = px(6);
    const long left = px(layout.content.x), top = px(layout.content.y);
    const long right = px(layout.content.right()), bottom = px(layout.content.bottom());
    long x = caret.left - px(layout.anchorX);
    long y = caret.bottom + gap - top;
    if (y + bottom > work.bottom) {
        const long above = caret.top - gap - bottom;
        if (above + top >= work.top) {
            y = above;
        } else {
            // No room on either side: take the roomier one and cover the caret line.
            y = work.bottom - caret.bottom >= caret.top - work.top ? work.bottom - bottom : work.top - top;
        }
    }
    x = std::max(work.left - left, std::min(x, work.right - right));
    y = std::max(work.top - top, std::min(y, work.bottom - bottom));
    return {x, y};
}

} // namespace meowpuut::ui
