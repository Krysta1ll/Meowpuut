#include "CandidateLayout.h"

#include <algorithm>
#include <cmath>
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

void placeCats(CandidateLayout &L, const Metrics &m, float bodyTop, float glassLeftX, float momoX) {
    const float hm = m.mimimoHeight, wm = hm * CatAnchors::mimimoAspect;
    const float hf = m.momoHeight, wf = hf * CatAnchors::momoAspect;
    // The bottom of his eyes is one DIP above the glass, clear of its edge line.
    L.mimimo = {glassLeftX - 0.24f * wm, bodyTop - 1 - hm * CatAnchors::mimimoEyeBottom, wm, hm};
    L.momo = {momoX, bodyTop - 1 - hf * CatAnchors::momoEyeLine, wf, hf};
    L.momoContactShadow = {L.momo.x + 0.12f * wf, L.momo.y + 0.80f * hf, 0.72f * wf, 0.20f * hf};
}

RectF contentBounds(const CandidateLayout &L) {
    RectF box = L.glass.bounds;
    if (L.decorations) {
        box = unite(box, L.mimimo);
        box = unite(box, L.momo);
        if (L.showTail) box = unite(box, L.tail);
    }
    return box;
}

} // namespace

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
    L.chevronUp = model.expanded;
    const bool deco = model.decorations;
    const float wm = m.mimimoHeight * CatAnchors::mimimoAspect;
    const float wf = m.momoHeight * CatAnchors::momoAspect;
    const float G = m.barHeight;
    const float bodyTop = deco ? std::ceil(m.padTop + m.mimimoHeight * CatAnchors::mimimoEyeBottom + 1) : m.padTop + 6;
    // The cat's left edge sits two DIPs inside the window; the glass starts a
    // quarter of the way across his body, so his chest is behind it.
    const float catLeft = m.padLeft + 2;
    const float glassLeft = deco ? catLeft + 0.24f * wm : m.padLeft;
    // His body shows blurred through the glass; the candidates start right of it.
    const float rowStart = deco ? catLeft + CatAnchors::mimimoBodyRight * wm + 2 : glassLeft + 10;
    // The cloud has no bump over his eyes.
    const float eyesClear = deco ? (CatAnchors::mimimoEyesRight - 0.24f) * wm : 0;
    auto cells = measureAll(model, measurer, m);

    if (!model.expanded) {
        const float rightZone = deco ? m.chevronWidth - m.chevronUnderMomo + wf + m.padRight : m.chevronWidth + 10 + m.padRight;
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
        const float momoX = L.chevron.right() - m.chevronUnderMomo;
        const float glassRight = deco ? momoX + wf - 2 : L.chevron.right() + 8;
        L.glass = makeBarOutline(model.shape, glassBox(model.shape, glassLeft, glassRight, bodyTop, G), eyesClear);
        float bottom = L.glass.bounds.bottom();
        if (deco) {
            placeCats(L, m, bodyTop, glassLeft, momoX);
            if (model.shape == GlassShape::Capsule) {
                const float th = m.tailHeight, tw = th * CatAnchors::tailAspect;
                L.showTail = true;
                L.tail = {catLeft + 7, bodyTop + G + 1 - th, tw, th};
                bottom = std::max(bottom, L.tail.bottom());
            }
            bottom = std::max(bottom, L.momo.bottom());
        }
        L.size = {glassRight + m.padRight, std::ceil(bottom + m.padBottom)};
        L.anchorX = L.cells.empty() ? rowStart : L.cells.front().frame.x;
        L.content = contentBounds(L);
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
    const float momoX = deco ? panelRight + 2 - wf : panelRight;

    // Header: the reading and the mode, clear of both cats.
    L.preedit = model.preedit;
    L.modeLabel = model.modeLabel;
    L.headerY = bodyTop + 4 + m.headerHeight / 2;
    L.preeditX = std::max(panelLeft + insetX, rowStart);
    L.modeRight = deco ? momoX - 6 : panelRight - insetX;
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
                               m.panelCorner, barBox, eyesClear);
    float bottom = L.glass.bounds.bottom();
    if (deco) {
        placeCats(L, m, bodyTop, glassLeft, momoX);
        bottom = std::max(bottom, L.momo.bottom());
    }
    L.size = {std::max(panelRight, deco ? L.momo.right() : panelRight) + m.padRight, std::ceil(bottom + m.padBottom)};
    L.anchorX = rowStart;
    L.content = contentBounds(L);
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
    if (layout.decorations) {
        // Only the faces, so a pet never lands on a candidate or the chevron.
        const RectF mimimoFace{layout.mimimo.x, layout.mimimo.y, layout.mimimo.width, layout.mimimo.height * 0.62f};
        if (mimimoFace.contains(p)) return {HitKind::Mimimo, 0};
        if (layout.momo.contains(p)) return {HitKind::Momo, 0};
    }
    return {};
}

bool coversPoint(const CandidateLayout &layout, PointF p) {
    if (outlineContains(layout.glass, p)) return true;
    if (!layout.decorations) return false;
    return layout.mimimo.contains(p) || layout.momo.contains(p) || (layout.showTail && layout.tail.contains(p));
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
