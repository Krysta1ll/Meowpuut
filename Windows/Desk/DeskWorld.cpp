#include "DeskWorld.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace meowpuut::desk {
namespace {

using Segments = std::vector<std::pair<double, double>>;

Segments subtract(Segments segments, const Segments &cuts) {
    for (const auto &cut : cuts) {
        Segments out;
        for (const auto &s : segments) {
            if (cut.second <= s.first || cut.first >= s.second) {
                out.push_back(s);
                continue;
            }
            if (cut.first > s.first) out.emplace_back(s.first, cut.first);
            if (cut.second < s.second) out.emplace_back(cut.second, s.second);
        }
        segments = std::move(out);
    }
    return segments;
}

Segments intersect(const Segments &segments, const Segments &cuts) {
    Segments out;
    for (const auto &s : segments)
        for (const auto &c : cuts) {
            const double l = std::max(s.first, c.first), r = std::min(s.second, c.second);
            if (r > l) out.emplace_back(l, r);
        }
    return out;
}

// murmur3's finalizer: spreads a window handle over 32 bits.
std::uint32_t mix(std::uint32_t x) {
    x ^= x >> 16;
    x *= 0x85ebca6bu;
    x ^= x >> 13;
    x *= 0xc2b2ae35u;
    x ^= x >> 16;
    return x;
}

struct Box {
    double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
};

Box points(const RectPx &r, double k) {
    return {r.left / k, r.top / k, r.right / k, r.bottom / k};
}

struct Monitor {
    Box monitor, work;
    bool primary = false;
};

// Ground ids: the taskbar and floor pieces of monitor m are 1 + 16 m + piece
// (pieces 8–15 are the peek-only ones); window edges are at 0x10000 and above,
// from the window handle and the piece's index, so they stay put while the
// window does and a cat keeps its ground across measurements.
std::uint32_t baseId(std::size_t monitor, std::size_t piece) {
    return static_cast<std::uint32_t>(1 + 16 * monitor + piece);
}

class Ids {
public:
    std::uint32_t edge(std::uint32_t owner, std::uint32_t piece) {
        std::uint32_t id = mix(owner * 0x9E3779B1u + piece) % 0xFFF00000u + 0x10000u;
        while (std::find(used_.begin(), used_.end(), id) != used_.end()) id = id == 0xFFFFFFFFu ? 0x10000u : id + 1;
        used_.push_back(id);
        return id;
    }

private:
    std::vector<std::uint32_t> used_;
};

bool same(const MPDeskGround &a, const MPDeskGround &b) {
    return a.id == b.id && a.kind == b.kind && a.x0 == b.x0 && a.x1 == b.x1 && a.y == b.y && a.ox == b.ox &&
           a.owner == b.owner && a.flags == b.flags;
}

bool same(const MPDeskSide &a, const MPDeskSide &b) {
    return a.dir == b.dir && a.ground == b.ground && a.stand_x == b.stand_x;
}

} // namespace

bool keep(const WindowFacts &w) {
    return w.id != 0 && w.visible && !w.minimized && !w.cloaked && (!w.toolWindow || w.appWindow) && !w.clickThrough &&
           !w.ours && !w.shell && w.bounds.width() >= 8 && w.bounds.height() >= 8;
}

World buildWorld(const Snapshot &s) {
    World w;
    const double k = static_cast<double>(std::clamp<std::uint32_t>(s.unitDpi, 96, 960)) / 96.0;
    const double scale = std::isfinite(s.catScale) ? std::clamp(s.catScale, 0.5, 2.0) : 1.0;
    const double band = 52.0 * scale + 6.0; // a cat's height of room above an edge
    std::vector<Monitor> monitors;
    for (const auto &m : s.monitors)
        if (!m.monitor.empty()) monitors.push_back({points(m.monitor, k), points(m.work.empty() ? m.monitor : m.work, k), m.primary});
    if (monitors.empty()) return w;

    for (const auto &f : s.windows)
        if (keep(f)) {
            const Box b = points(f.bounds, k);
            w.windows.push_back({f.id, b.x0, b.y0, b.x1, b.y1});
        }

    // Window top edges (PETS §2.1 rules 1–3): room above, under the top of the
    // work area; not the corners with the caption buttons; only the parts no
    // window in front covers within a cat's height.
    Ids ids;
    for (std::size_t i = 0; i < w.windows.size() && w.grounds.size() < MP_DESK_MAX_GROUNDS; ++i) {
        const Window &win = w.windows[i];
        const double mid = (win.x0 + win.x1) / 2;
        const Monitor *on = nullptr;
        for (const auto &m : monitors)
            if (mid >= m.work.x0 && mid < m.work.x1 && win.y0 >= m.work.y0 && win.y0 < m.work.y1) on = &m;
        if (!on || win.y0 - band < on->work.y0) continue;
        const double a = std::max(win.x0 + kCaptionLeft, on->work.x0), b = std::min(win.x1 - kCaptionRight, on->work.x1);
        if (b - a < kMinEdge) continue;
        Segments cuts;
        for (std::size_t j = 0; j < i; ++j) {
            const Window &o = w.windows[j];
            if (o.y0 < win.y0 + 4 && o.y1 > win.y0 - band) cuts.emplace_back(o.x0 - 2, o.x1 + 2);
        }
        std::uint32_t piece = 0;
        for (const auto &seg : subtract({{a, b}}, cuts)) {
            if (seg.second - seg.first < kMinEdge || w.grounds.size() >= MP_DESK_MAX_GROUNDS) continue;
            MPDeskGround g{};
            g.id = ids.edge(win.id, piece++);
            g.kind = MP_DESK_GROUND_EDGE;
            g.x0 = seg.first;
            g.x1 = seg.second;
            g.y = win.y0;
            g.ox = win.x0;
            g.owner = win.id;
            g.flags = MP_DESK_GROUND_BEHIND;
            w.grounds.push_back(g);
        }
    }

    // The base of each screen: the taskbar's top edge (cats hide behind it), or
    // the bottom of the screen when the taskbar hides itself or stands on
    // another side. Above the window you are using, a cat would cover your
    // content: that part of the taskbar is peek-only (rule 4).
    const std::size_t firstBase = w.grounds.size();
    Box using_{};
    bool haveUsing = false;
    if (keep(s.foreground)) {
        using_ = points(s.foreground.bounds, k);
        haveUsing = true;
    }
    for (std::size_t m = 0; m < monitors.size(); ++m) {
        const Monitor &mon = monitors[m];
        bool shelf = false;
        double y = mon.monitor.y1;
        if (mon.primary && s.taskbar.known) {
            const double top = s.taskbar.rect.top / k;
            if (!s.taskbar.autoHide && s.taskbar.edge == 3 && top > mon.monitor.y0 && top < mon.monitor.y1) {
                shelf = true;
                y = top;
            }
        } else if (mon.work.y1 < mon.monitor.y1 - 0.5 && mon.work.y1 > mon.monitor.y0) {
            shelf = true; // a bottom taskbar shortens the work area
            y = mon.work.y1;
        }
        const double inset = shelf ? 10 : 14;
        const Segments whole{{mon.monitor.x0 + inset, mon.monitor.x1 - inset}};
        Segments cover;
        if (haveUsing && using_.y1 > y - band && using_.y0 < y) cover.emplace_back(using_.x0, using_.x1);
        std::size_t piece = 0;
        const auto add = [&](const std::pair<double, double> &seg, bool peekOnly) {
            if (seg.second - seg.first < kMinBase || piece >= 16 || w.grounds.size() >= MP_DESK_MAX_GROUNDS) return;
            MPDeskGround g{};
            g.id = baseId(m, peekOnly ? 8 + piece % 8 : piece);
            ++piece;
            g.kind = shelf ? MP_DESK_GROUND_SHELF : MP_DESK_GROUND_FLOOR;
            g.x0 = seg.first;
            g.x1 = seg.second;
            g.y = y;
            g.ox = 0;
            g.owner = 0;
            g.flags = (shelf ? static_cast<std::uint32_t>(MP_DESK_GROUND_BEHIND) : 0u) | (peekOnly ? static_cast<std::uint32_t>(MP_DESK_GROUND_PEEK_ONLY) : 0u);
            w.grounds.push_back(g);
        };
        for (const auto &seg : subtract(whole, cover)) add(seg, false);
        if (shelf) {
            piece = 0;
            for (const auto &seg : intersect(whole, cover)) add(seg, true);
        }
    }

    // Window sides a clay cat can hold (side_hold), standing on a base ground:
    // Mimimo the left side from the left, Momo the right side from the right.
    for (const auto &win : w.windows) {
        for (std::size_t gi = firstBase; gi < w.grounds.size(); ++gi) {
            const MPDeskGround &g = w.grounds[gi];
            const double pawY = g.y - 28 * scale;
            if (!(win.y0 < pawY - 6 && win.y1 > pawY + 4)) continue;
            const double l = win.x0 - 22.5 * scale, r = win.x1 + 23.9 * scale;
            const auto blocked = [&](double from, double to) {
                for (const auto &o : w.windows)
                    if (o.id != win.id && o.x0 < to && o.x1 > from && o.y0 < g.y && o.y1 > pawY) return true;
                return false;
            };
            if (w.sides.size() < MP_DESK_MAX_SIDES && l > g.x0 + 10 && l < g.x1 - 10 && !blocked(win.x0 - 40, win.x0))
                w.sides.push_back({-1, g.id, l});
            if (w.sides.size() < MP_DESK_MAX_SIDES && r > g.x0 + 10 && r < g.x1 - 10 && !blocked(win.x1, win.x1 + 40))
                w.sides.push_back({1, g.id, r});
        }
    }

    // The cat door: our tray icon. Cats jump up out of the taskbar (PETS §5),
    // or drop from under a taskbar at the top of the screen.
    const Monitor *primaryAt = &monitors.front();
    for (const auto &m : monitors)
        if (m.primary) primaryAt = &m;
    const Monitor &primary = *primaryAt;
    double doorX = primary.monitor.x1 - 160;
    w.doors.out_y = primary.monitor.y1 - 8;
    w.doors.out_vy = -950;
    if (s.trayKnown && !s.tray.empty()) {
        const Box t = points(s.tray, k);
        doorX = (t.x0 + t.x1) / 2;
        if ((t.y0 + t.y1) / 2 < (primary.monitor.y0 + primary.monitor.y1) / 2) {
            w.doors.out_y = t.y1 + 8;
            w.doors.out_vy = 0;
        }
    } else if (s.taskbar.known && s.taskbar.edge == 3) {
        doorX = s.taskbar.rect.right / k - 160;
    }
    w.doors.out_x = doorX;
    w.doors.home_x = doorX;

    w.walls = {monitors.front().monitor.x0, monitors.front().monitor.x1, monitors.front().monitor.y0, monitors.front().monitor.y1};
    for (const auto &m : monitors) {
        w.walls.x0 = std::min(w.walls.x0, m.monitor.x0);
        w.walls.x1 = std::max(w.walls.x1, m.monitor.x1);
        w.walls.top = std::min(w.walls.top, m.monitor.y0);
        w.walls.bottom = std::max(w.walls.bottom, m.monitor.y1);
    }
    w.walls.bottom += 40;
    return w;
}

bool sameWorld(const World &a, const World &b) {
    if (a.grounds.size() != b.grounds.size() || a.sides.size() != b.sides.size()) return false;
    for (std::size_t i = 0; i < a.grounds.size(); ++i)
        if (!same(a.grounds[i], b.grounds[i])) return false;
    for (std::size_t i = 0; i < a.sides.size(); ++i)
        if (!same(a.sides[i], b.sides[i])) return false;
    return a.doors.out_x == b.doors.out_x && a.doors.out_y == b.doors.out_y && a.doors.out_vy == b.doors.out_vy &&
           a.doors.home_x == b.doors.home_x && a.walls.x0 == b.walls.x0 && a.walls.x1 == b.walls.x1 &&
           a.walls.top == b.walls.top && a.walls.bottom == b.walls.bottom;
}

std::uint32_t ownerAt(const World &w, double x, double y) {
    for (const auto &win : w.windows)
        if (x >= win.x0 && x < win.x1 && y >= win.y0 && y < win.y1) return win.id;
    return 0;
}

bool coversMonitor(const Snapshot &s) {
    const WindowFacts &f = s.foreground;
    if (f.id == 0 || f.ours || f.shell || !f.visible || f.minimized || f.cloaked || f.bounds.empty()) return false;
    for (const auto &m : s.monitors) {
        const RectPx &r = m.monitor;
        if (r.empty()) continue;
        const bool overlaps = f.bounds.left < r.right && f.bounds.right > r.left && f.bounds.top < r.bottom && f.bounds.bottom > r.top;
        if (overlaps && f.bounds.left <= r.left && f.bounds.top <= r.top && f.bounds.right >= r.right && f.bounds.bottom >= r.bottom)
            return true;
    }
    return false;
}

bool fullScreenState(int quns) { return quns == 2 || quns == 3 || quns == 4; }

} // namespace meowpuut::desk
