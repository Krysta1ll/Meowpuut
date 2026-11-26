#include "DeskArt.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace meowpuut::desk {

// From assets/brand/cats/cats.json (size in points; anchor and bounds as
// fractions of the image). Same row layout as CatSpriteData.h, so
// scripts/build-windows-check.sh reads both against cats.json.
const CatSprite kDeskSprites[8] = {
    {"mimimo_idle", 64.0f, 64.0f, 0.5f, 0.9f, 0.2344f, 0.0833f, 0.7656f, 0.8958f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_press", 64.0f, 64.0f, 0.5f, 0.9f, 0.2031f, 0.0833f, 0.7969f, 0.8958f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_happy", 64.0f, 64.0f, 0.5f, 0.9f, 0.2188f, 0.0833f, 0.7812f, 0.8958f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_sleep", 64.0f, 64.0f, 0.5f, 0.9f, 0.2031f, 0.2969f, 0.7969f, 0.8958f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_idle", 64.0f, 64.0f, 0.5f, 0.9f, 0.2292f, 0.0833f, 0.7708f, 0.8958f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_wave", 64.0f, 64.0f, 0.5f, 0.9f, 0.2083f, 0.0885f, 0.7969f, 0.8906f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_happy", 64.0f, 64.0f, 0.5f, 0.9f, 0.2031f, 0.0833f, 0.7969f, 0.8958f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_jump", 64.0f, 64.0f, 0.5f, 0.9f, 0.1979f, 0.125f, 0.8073f, 0.8958f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
};

// From assets/brand/cats/cats.json `desk` (= Assets/desk.json); checked by
// scripts/build-windows-check.sh like the table above. A cat's walk is its whole
// cycle in frame order (12 frames: the key poses walk_1-4 on every third), so
// the cycle length is the number of its walk rows.
const CatSprite kDeskPoses[33] = {
    {"mimimo_walk_1", 96.0f, 64.0f, 0.5f, 0.9f, 0.2222f, 0.1302f, 0.7847f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_f02", 96.0f, 64.0f, 0.5f, 0.9f, 0.2257f, 0.1354f, 0.7882f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_f03", 96.0f, 64.0f, 0.5f, 0.9f, 0.2292f, 0.1667f, 0.7882f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_2", 96.0f, 64.0f, 0.5f, 0.9f, 0.2257f, 0.1875f, 0.7882f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_f05", 96.0f, 64.0f, 0.5f, 0.9f, 0.2292f, 0.1667f, 0.7882f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_f06", 96.0f, 64.0f, 0.5f, 0.9f, 0.2326f, 0.1354f, 0.7847f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_3", 96.0f, 64.0f, 0.5f, 0.9f, 0.2326f, 0.125f, 0.7847f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_f08", 96.0f, 64.0f, 0.5f, 0.9f, 0.2257f, 0.1042f, 0.7847f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_f09", 96.0f, 64.0f, 0.5f, 0.9f, 0.2188f, 0.099f, 0.7847f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_4", 96.0f, 64.0f, 0.5f, 0.9f, 0.2118f, 0.099f, 0.7847f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_f11", 96.0f, 64.0f, 0.5f, 0.9f, 0.1979f, 0.099f, 0.7847f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_walk_f12", 96.0f, 64.0f, 0.5f, 0.9f, 0.2083f, 0.1146f, 0.7847f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_turn", 64.0f, 64.0f, 0.5f, 0.9f, 0.1927f, 0.1302f, 0.7969f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_look_up", 64.0f, 64.0f, 0.5f, 0.9f, 0.1823f, 0.026f, 0.8177f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_lift", 64.0f, 80.0f, 0.5f, 0.06f, 0.2396f, 0.0583f, 0.7604f, 0.775f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"mimimo_fall", 64.0f, 64.0f, 0.5f, 0.9f, 0.2135f, 0.1771f, 0.7917f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_1", 96.0f, 64.0f, 0.5f, 0.9f, 0.1979f, 0.1042f, 0.809f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_f02", 96.0f, 64.0f, 0.5f, 0.9f, 0.2014f, 0.1146f, 0.809f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_f03", 96.0f, 64.0f, 0.5f, 0.9f, 0.2049f, 0.125f, 0.809f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_2", 96.0f, 64.0f, 0.5f, 0.9f, 0.2049f, 0.1302f, 0.809f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_f05", 96.0f, 64.0f, 0.5f, 0.9f, 0.1979f, 0.1198f, 0.809f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_f06", 96.0f, 64.0f, 0.5f, 0.9f, 0.1875f, 0.1094f, 0.809f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_3", 96.0f, 64.0f, 0.5f, 0.9f, 0.184f, 0.099f, 0.809f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_f08", 96.0f, 64.0f, 0.5f, 0.9f, 0.184f, 0.0938f, 0.8056f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_f09", 96.0f, 64.0f, 0.5f, 0.9f, 0.1875f, 0.099f, 0.8056f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_4", 96.0f, 64.0f, 0.5f, 0.9f, 0.191f, 0.099f, 0.8056f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_f11", 96.0f, 64.0f, 0.5f, 0.9f, 0.1944f, 0.1042f, 0.8056f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_walk_f12", 96.0f, 64.0f, 0.5f, 0.9f, 0.1979f, 0.1042f, 0.8056f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_turn", 64.0f, 64.0f, 0.5f, 0.9f, 0.1927f, 0.1198f, 0.8021f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_look_up", 64.0f, 64.0f, 0.5f, 0.9f, 0.1771f, 0.0104f, 0.8229f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_lift", 64.0f, 80.0f, 0.4986f, 0.1278f, 0.2188f, 0.0417f, 0.7708f, 0.7667f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_fall", 64.0f, 64.0f, 0.5f, 0.9f, 0.1719f, 0.0885f, 0.8229f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
    {"momo_sleep", 64.0f, 64.0f, 0.5f, 0.9f, 0.2031f, 0.3125f, 0.7969f, 0.901f, 0.0f, 0.0f, 0, {{0.0f, 0.0f, 0.0f}}},
};

const CatSprite *findSprite(std::string_view name) {
    for (const CatSprite &s : kDeskSprites)
        if (name == s.name) return &s;
    for (const CatSprite &s : kDeskPoses)
        if (name == s.name) return &s;
    for (const CatSprite &s : kCatSprites)
        if (name == s.name) return &s;
    return nullptr;
}

int walkCycleLength(int cat) {
    const char *prefix = cat == 0 ? "mimimo_walk_" : "momo_walk_";
    int count = 0;
    for (const CatSprite &s : kDeskPoses)
        if (std::strncmp(s.name, prefix, std::strlen(prefix)) == 0) ++count;
    return count >= 4 && count % 4 == 0 ? count : 4;
}

const CatSprite *walkSprite(int cat, int frame, int length) {
    const char *prefix = cat == 0 ? "mimimo_walk_" : "momo_walk_";
    const int n = std::max(length, 1);
    const int index = ((frame % n) + n) % n;
    if (n >= 4 && n == walkCycleLength(cat) && n > 4) {
        int seen = 0;
        for (const CatSprite &s : kDeskPoses)
            if (std::strncmp(s.name, prefix, std::strlen(prefix)) == 0 && seen++ == index) return &s;
    }
    char name[32];
    std::snprintf(name, sizeof name, "%s%d", prefix, index % 4 + 1);
    return findSprite(name);
}

Art clayArt(int cat, const MPDeskCat &c, int walkFrame, bool turning, int walkLength) {
    const bool mimi = cat == 0;
    const auto sprite = [&](const char *pose) {
        char name[32];
        std::snprintf(name, sizeof name, "%s_%s", mimi ? "mimimo" : "momo", pose);
        return findSprite(name);
    };
    Art art;
    const auto behind = [&](const char *pose) {
        art.sprite = sprite(pose);
        art.behind = true;
        return art;
    };
    if (c.seat == MP_DESK_SEAT_BEHIND)
        return behind(c.pose == MP_DESK_POSE_REST ? "edge_rest" : c.pose == MP_DESK_POSE_LOUNGE ? "edge_lounge" : "edge_peek");
    if (c.seat == MP_DESK_SEAT_SIDE || c.pose == MP_DESK_POSE_SIDE) {
        art.sprite = sprite("side_hold");
        art.side = true;
        return art;
    }
    switch (c.pose) {
    case MP_DESK_POSE_PEEK: return behind("edge_peek");
    case MP_DESK_POSE_REST: return behind("edge_rest");
    case MP_DESK_POSE_LOUNGE: return behind("edge_lounge");
    case MP_DESK_POSE_WALK:
    case MP_DESK_POSE_TROT: {
        art.sprite = walkSprite(cat, walkFrame, walkLength);
        art.fx = Fx::Walk;
        break;
    }
    case MP_DESK_POSE_SIT:
        if (turning) art.sprite = sprite("turn");
        break;
    case MP_DESK_POSE_LOOK:
        art.sprite = sprite("look_up");
        art.native = -1; // she looks up to the left; mirrored, to the right
        break;
    case MP_DESK_POSE_PREP: art.fx = Fx::Prep; break;
    case MP_DESK_POSE_JUMP:
        art.fx = Fx::Air;
        if (!mimi) art.sprite = sprite("jump");
        break;
    case MP_DESK_POSE_AIR:
        art.fx = Fx::Air;
        art.sprite = sprite("fall");
        break;
    case MP_DESK_POSE_HELD:
        art.fx = Fx::Held;
        art.sprite = sprite("lift");
        break;
    case MP_DESK_POSE_SLEEP: art.sprite = sprite("sleep"); break;
    case MP_DESK_POSE_HAPPY: art.sprite = sprite("happy"); break;
    case MP_DESK_POSE_PRESS:
    case MP_DESK_POSE_WAVE: art.sprite = sprite(mimi ? "press" : "wave"); break;
    case MP_DESK_POSE_STRETCH: art.fx = Fx::Stretch; break;
    default: break; // land, and the poses clay cannot draw (loaf, groom, back; the core never asks for them)
    }
    if (!art.sprite) art.sprite = sprite("idle");
    return art;
}

void Gait::step(const MPDeskCat &c, std::uint64_t nowMs) {
    const bool onGround = c.body == MP_DESK_BODY_GROUND && c.seat == MP_DESK_SEAT_TOP;
    if (onGround && (c.pose == MP_DESK_POSE_WALK || c.pose == MP_DESK_POSE_TROT)) {
        // The first step shows walk_1; every step after it (a flipped phase) the next frame.
        if (!walking_) frame_ = 0;
        else if (c.phase != phase_) frame_ = (frame_ + stepFrames()) % length_;
        if (!walking_ || c.phase != phase_) ++steps_;
        walking_ = true;
        phase_ = c.phase;
        turnUntil_ = 0;
        return;
    }
    // Arrived: from the side to the front, through the turn.
    if (walking_ && onGround && c.pose == MP_DESK_POSE_SIT && c.react == MP_DESK_REACT_NONE) turnUntil_ = nowMs + kTurnMs;
    else if (c.pose != MP_DESK_POSE_SIT || !onGround) turnUntil_ = 0;
    walking_ = false;
}

namespace {

// Bilinear sample of a premultiplied image at continuous pixel coordinates
// (pixel centres at +0.5); outside is transparent.
void sample(const Image &img, double x, double y, double out[4]) {
    out[0] = out[1] = out[2] = out[3] = 0;
    const double fx = x - 0.5, fy = y - 0.5;
    const double bx = std::floor(fx), by = std::floor(fy);
    const double tx = fx - bx, ty = fy - by;
    const int x0 = static_cast<int>(bx), y0 = static_cast<int>(by);
    for (int j = 0; j < 2; ++j) {
        const int yy = y0 + j;
        if (yy < 0 || yy >= img.height) continue;
        const double wy = j ? ty : 1 - ty;
        for (int i = 0; i < 2; ++i) {
            const int xx = x0 + i;
            if (xx < 0 || xx >= img.width) continue;
            const double w = wy * (i ? tx : 1 - tx);
            const std::uint8_t *p = &img.bgra[(static_cast<std::size_t>(yy) * img.width + xx) * 4];
            for (int k = 0; k < 4; ++k) out[k] += w * p[k];
        }
    }
}

std::uint8_t byte(double v) { return static_cast<std::uint8_t>(std::clamp(std::lround(v), 0L, 255L)); }

std::int64_t quantize(double v, double q) { return std::isfinite(v) ? std::llround(v * q) : 0; }

std::uint64_t fnv(const std::int64_t *values, std::size_t n) {
    std::uint64_t h = 1469598103934665603ull;
    for (std::size_t i = 0; i < n; ++i) {
        std::uint64_t v = static_cast<std::uint64_t>(values[i]);
        for (int b = 0; b < 8; ++b) {
            h ^= (v >> (8 * b)) & 0xffu;
            h *= 1099511628211ull;
        }
    }
    return h;
}

bool hide(Frame &f) {
    const bool changed = f.visible;
    f.visible = false;
    f.signature = 0;
    return changed;
}

double finiteOr(double v, double fallback) { return std::isfinite(v) ? v : fallback; }

} // namespace

bool paintCat(int cat, const MPDeskCat &c, const PaintContext &ctx, ImageSource &images, Frame &f) {
    const bool onDesk = (c.body == MP_DESK_BODY_GROUND || c.body == MP_DESK_BODY_AIR || c.body == MP_DESK_BODY_HELD) &&
                        c.pose != MP_DESK_POSE_NONE && std::isfinite(c.x) && std::isfinite(c.y);
    if (!onDesk) return hide(f);
    const Art art = clayArt(cat, c, ctx.walkFrame, ctx.turning && !ctx.reduceMotion, ctx.walkLength);
    if (!art.sprite) return hide(f);
    const CatSprite &sp = *art.sprite;

    const double unit = std::clamp<std::uint32_t>(ctx.unitDpi, 96, 960) / 96.0;
    const double scale = std::clamp(finiteOr(ctx.catScale, 1.0), 0.5, 2.0);
    const double mon = std::clamp<std::uint32_t>(ctx.monitorDpi, 96, 960) / 96.0 * scale; // device pixels per point for this cat
    const double m = mon * (art.behind ? 0.86 : 1.0);
    const int w = std::max(1, static_cast<int>(std::lround(sp.width * m)));
    const int h = std::max(1, static_cast<int>(std::lround(sp.height * m)));
    // side_hold's anchor in cats.json is the paw on the window's side; the core's point is between the feet.
    // lift's anchor is the scruff.
    const double wd = w, hd = h;
    const double ax = (art.side ? 0.5 : sp.anchorX) * wd, ay = (art.side ? sp.bottom : sp.anchorY) * hd;

    const bool held = art.fx == Fx::Held;
    const int cw = static_cast<int>(std::lround(kCanvasW * mon)), ch = static_cast<int>(std::lround(kCanvasH * mon));
    const double px = std::clamp(c.x * unit, -1e8, 1e8), py = std::clamp(c.y * unit, -1e8, 1e8);
    // A lifted cat hangs from the scruff the pointer holds, kScruffRise of its height above its paws (desk points).
    const double lift = held ? kScruffRise * kBodyHeight * scale * unit : 0;
    const std::int32_t left = static_cast<std::int32_t>(std::lround(px)) - cw / 2;
    const std::int32_t top = held ? static_cast<std::int32_t>(std::lround(py - lift) - std::lround(kScruffY * mon))
                                  : static_cast<std::int32_t>(std::lround(py) - std::lround(kPawsY * mon));
    const double lx = px - left, ly = py - top; // the paws in the canvas
    const double tx = lx, ty = ly - lift;        // where the sprite's anchor goes

    const bool lounge = std::strstr(sp.name, "lounge") != nullptr;
    const int native = lounge ? (cat == 0 ? -1 : 1) : art.native;
    const bool flip = ((art.behind && !lounge) || art.side) ? false : (c.facing < 0 ? -1 : 1) != native;
    double sx = finiteOr(c.scale_x, 1.0), sy = finiteOr(c.scale_y, 1.0);
    const double rot = finiteOr(c.tilt, 0.0);
    if (!ctx.reduceMotion && !(c.flags & MP_DESK_CAT_ASLEEP) && c.body == MP_DESK_BODY_GROUND && c.seat == MP_DESK_SEAT_TOP &&
        art.fx == Fx::None && !art.side && !art.behind && c.react == MP_DESK_REACT_NONE) {
        // Breathing, stepped at the core's 12 Hz tick (stop-motion, DESIGN §7).
        const double tick = std::floor(finiteOr(ctx.timeMs, 0) / 83.0) * 83.0;
        sy *= 1 + 0.018 * std::sin(tick / 5200.0 * 2 * 3.14159265358979 + (cat ? 2.0 : 0.0));
    }
    sx = std::clamp(sx, 1 - kSquashCap, 1 + kSquashCap);
    sy = std::clamp(sy, 1 - kSquashCap, 1 + kSquashCap);
    const double pivotX = ax, pivotY = ay;
    const double sink = std::clamp(finiteOr(c.sink, 0), 0.0, 1.0);
    const bool clip = art.behind || sink > 0;
    const double sinkPx = clip ? sink * (ay - sp.top * hd + 2 * mon) : 0;
    const bool shadow = c.body == MP_DESK_BODY_GROUND && c.seat == MP_DESK_SEAT_TOP && !art.side && !clip;
    const std::uint8_t opacity = byte(std::clamp(finiteOr(ctx.opacity, 1), 0.0, 1.0) * 255);

    const std::int64_t key[] = {reinterpret_cast<std::intptr_t>(&sp), w, h, left, top, cw, ch, quantize(sx, 1000),
                                quantize(sy, 1000), quantize(rot, 100), quantize(sinkPx, 10), flip, clip,
                                shadow, opacity, quantize(lx, 8), quantize(ly, 8), quantize(ty, 8)};
    const std::uint64_t signature = fnv(key, sizeof key / sizeof key[0]);
    if (f.visible && f.signature == signature) return false;
    const Image *img = images.scaled(sp, w, h);
    if (!img || img->width != w || img->height != h || img->bgra.size() != static_cast<std::size_t>(w) * h * 4) return hide(f);

    f.visible = true;
    f.left = left;
    f.top = top;
    f.width = cw;
    f.height = ch;
    f.opacity = opacity;
    f.signature = signature;
    f.bgra.assign(static_cast<std::size_t>(cw) * ch * 4, 0);
    f.mask.assign(static_cast<std::size_t>(cw) * ch, 0);

    if (shadow) {
        // A soft contact shadow under the paws. Its alpha falls to exactly 0 at
        // the rim, and it never enters the mask.
        // As wide as the body: a walking cat is seen from the side.
        const double body = art.fx == Fx::Walk ? (sp.right - sp.left) * sp.width : (cat ? 35.0 : 34.0);
        const double rx = 0.625 * body * mon, ry = 4 * mon, cx = lx, cy = ly - mon;
        const int y0 = std::max(0, static_cast<int>(std::floor(cy - ry))), y1 = std::min(ch, static_cast<int>(std::ceil(cy + ry)));
        const int x0 = std::max(0, static_cast<int>(std::floor(cx - rx))), x1 = std::min(cw, static_cast<int>(std::ceil(cx + rx)));
        for (int y = y0; y < y1; ++y)
            for (int x = x0; x < x1; ++x) {
                const double u = (x + 0.5 - cx) / rx, v = (y + 0.5 - cy) / ry, r2 = u * u + v * v;
                if (r2 >= 1) continue;
                f.bgra[(static_cast<std::size_t>(y) * cw + x) * 4 + 3] = byte(0.16 * 255 * (1 - r2) * (1 - r2));
            }
    }

    // canvas p = O + R(rot) · S · (q − pivot), q in image pixels, O where the
    // pivot lands: the anchor on the paws, lifted or sunk.
    const double fsx = sx * (flip ? -1 : 1);
    if (std::fabs(fsx) < 1e-3 || std::fabs(sy) < 1e-3) return true;
    const double rad = rot * 3.14159265358979 / 180, cr = std::cos(rad), sr = std::sin(rad);
    const double ox = tx + (pivotX - ax), oy = ty + (pivotY - ay) + sinkPx;
    double bx0 = 1e18, by0 = 1e18, bx1 = -1e18, by1 = -1e18;
    for (const double qx : {0.0, wd})
        for (const double qy : {0.0, hd}) {
            const double ux = (qx - pivotX) * fsx, uy = (qy - pivotY) * sy;
            const double X = ox + cr * ux - sr * uy, Y = oy + sr * ux + cr * uy;
            bx0 = std::min(bx0, X), bx1 = std::max(bx1, X), by0 = std::min(by0, Y), by1 = std::max(by1, Y);
        }
    const int xs = std::max(0, static_cast<int>(std::floor(bx0)) - 1), xe = std::min(cw, static_cast<int>(std::ceil(bx1)) + 1);
    const int ys = std::max(0, static_cast<int>(std::floor(by0)) - 1), ye = std::min(ch, static_cast<int>(std::ceil(by1)) + 1);

    // Behind an edge: everything above the edge line, plus the paws hanging
    // over it while the cat has not sunk (cats.json cut & paws).
    struct Box {
        double x0, y0, x1, y1;
    } paws[4];
    int pawCount = 0;
    if (art.behind && sink == 0) {
        const double boxLeft = lx - ax;
        for (int i = 0; i < sp.pawCount && i < 4; ++i) {
            const CatPaw &p = sp.paws[i];
            const double cx = (flip ? 1 - p.x : p.x) * wd, a = p.halfWidth * wd + mon;
            paws[pawCount++] = {boxLeft + cx - a, ly - mon, boxLeft + cx + a, ly + p.depth * hd + 1.5 * mon};
        }
    }
    for (int y = ys; y < ye; ++y) {
        const double cy = y + 0.5;
        for (int x = xs; x < xe; ++x) {
            const double cx = x + 0.5;
            if (clip && cy >= ly) {
                bool inPaw = false;
                for (int i = 0; i < pawCount && !inPaw; ++i)
                    inPaw = cx >= paws[i].x0 && cx < paws[i].x1 && cy >= paws[i].y0 && cy < paws[i].y1;
                if (!inPaw) continue;
            }
            const double vx = cx - ox, vy = cy - oy;
            const double qx = (cr * vx + sr * vy) / fsx + pivotX, qy = (-sr * vx + cr * vy) / sy + pivotY;
            double s[4];
            sample(*img, qx, qy, s);
            if (s[3] < 0.5) continue;
            const std::size_t at = static_cast<std::size_t>(y) * cw + x;
            std::uint8_t *d = &f.bgra[at * 4];
            const double keep = 1 - s[3] / 255.0;
            for (int k = 0; k < 4; ++k) d[k] = byte(s[k] + d[k] * keep);
            if (s[3] >= kHitAlpha) f.mask[at] = 1;
        }
    }
    return true;
}

bool hitTest(const Frame &f, std::int32_t x, std::int32_t y) {
    if (!f.visible) return false;
    const std::int64_t lx = static_cast<std::int64_t>(x) - f.left, ly = static_cast<std::int64_t>(y) - f.top;
    if (lx < 0 || ly < 0 || lx >= f.width || ly >= f.height) return false;
    const std::size_t at = static_cast<std::size_t>(ly) * f.width + static_cast<std::size_t>(lx);
    return at < f.mask.size() && f.mask[at] != 0;
}

} // namespace meowpuut::desk
