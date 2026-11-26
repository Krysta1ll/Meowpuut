// Host test of the desk-pet process's portable parts (scripts/build-windows-check.sh):
// the pulse channel's names, layout and seqlock; the glass seat and the "now"
// field; the world filter and grounds from synthetic window rectangles; the clay
// painter's walk frames, mirroring, squash cap, lift, alpha hit test and edge cut;
// the dwell; the touches in the pet ledger; the activity tracker (FINAL.md §11)
// and its file; and the headless smoke run against the Rust core.
#include "../ActivityLedger.h"
#include "../DeskArt.h"
#include "../DeskLoop.h"
#include "../DeskSettings.h"
#include "../DeskSmoke.h"
#include "../DeskWorld.h"
#include "../ForegroundWatcher.h"
#include "../PulseChannel.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

namespace fs = std::filesystem;

using namespace meowpuut;
using namespace meowpuut::desk;

static int failures = 0;
#define CHECK(cond) \
    do { \
        if (!(cond)) { std::fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); ++failures; } \
    } while (0)

static RectPx rect(std::int32_t l, std::int32_t t, std::int32_t r, std::int32_t b) {
    RectPx x;
    x.left = l, x.top = t, x.right = r, x.bottom = b;
    return x;
}

static WindowFacts window(std::uint32_t id, RectPx r) {
    WindowFacts w;
    w.id = id;
    w.bounds = r;
    return w;
}

static const MPDeskGround *find(const World &w, std::uint32_t kind, std::uint32_t owner = 0, bool peek = false) {
    for (const auto &g : w.grounds)
        if (g.kind == kind && (owner == 0 || g.owner == owner) && ((g.flags & MP_DESK_GROUND_PEEK_ONLY) != 0) == peek) return &g;
    return nullptr;
}

static void pulseChannel() {
    CHECK(pulse::securityDescriptor(L"S-1-5-21-1-2-3-1001") == L"D:P(A;;GA;;;S-1-5-21-1-2-3-1001)S:(ML;;NRNWNX;;;ME)");
    CHECK(pulse::securityDescriptor(L"S-1-5-21)(A;;GA;;;WD").empty()); // nothing but a SID gets in
    CHECK(pulse::securityDescriptor(L"").empty());
    CHECK(std::wstring(pulse::kNames.slot).rfind(std::wstring(pulse::kNames.alias) + L"\\", 0) == 0);
    CHECK(std::wstring(pulse::kNames.signal).rfind(std::wstring(pulse::kNames.alias) + L"\\", 0) == 0);
    CHECK(std::wstring(pulse::kSmokeNames.alias) != pulse::kNames.alias);
    // No name ever offers the slot to AppContainers.
    CHECK(pulse::securityDescriptor(L"S-1-5-21-1").find(L"AC") == std::wstring::npos);

    alignas(64) unsigned char s[pulse::kSectionBytes] = {};
    pulse::Input in;
    in.keys = 1;
    CHECK(pulse::write(s, in) == pulse::Write::NotReady); // before the desk set it up
    std::uint8_t bytes[MP_PULSE_BYTES];
    pulse::Reader reader;
    CHECK(pulse::read(s, reader, 0, bytes) == pulse::Read::NotReady);
    pulse::initialize(s, 144);
    CHECK(pulse::ready(s));
    CHECK(pulse::unitDpi(s) == 144);
    CHECK(std::memcmp(s, "MPPS", 4) == 0);                   // magic, little endian
    CHECK(s[4] == 2 && s[5] == 0 && s[6] == 0 && s[7] == 2); // version 2, 512 bytes
    CHECK(pulse::read(s, reader, 0, bytes) == pulse::Read::Same); // nothing written yet (seq 0)

    // First write: counters from zero, the origin in desk points on the 8 pt grid.
    in.composing = true;
    in.haveOrigin = true;
    in.originX = 451; // 451 × 96 / 144 = 300.67 pt -> quantum 37
    in.originY = 477; // 318 pt -> 39
    in.lineHeight = 33; // 22 pt -> 3 quanta (rounded up)
    in.expanded = true;
    CHECK(pulse::write(s, in) == pulse::Write::Written);
    CHECK(pulse::read(s, reader, 0, bytes) == pulse::Read::Fresh);
    CHECK(pulse::read(s, reader, 0, bytes) == pulse::Read::Same);
    MPPulse p{};
    CHECK(mp_pulse_decode(bytes, sizeof bytes, &p) == MP_PULSE_OK);
    CHECK(p.seq == 1 && p.keys == 1 && p.commits == 0 && p.display == 0);
    CHECK((p.flags & (MP_PULSE_COMPOSING | MP_PULSE_ORIGIN | MP_PULSE_EXPANDED)) == (MP_PULSE_COMPOSING | MP_PULSE_ORIGIN | MP_PULSE_EXPANDED));
    CHECK((p.flags & MP_PULSE_ABOVE) == 0);
    CHECK(p.origin_x == 37 && p.origin_y == 39 && p.line == 3);

    // Another process adds to the same running counters.
    pulse::Input other;
    other.keys = 1;
    other.commits = 1;
    other.graphemes = 2;
    CHECK(pulse::write(s, other) == pulse::Write::Written);
    CHECK(pulse::read(s, reader, 0, bytes) == pulse::Read::Fresh);
    CHECK(mp_pulse_decode(bytes, sizeof bytes, &p) == MP_PULSE_OK);
    CHECK(p.seq == 2 && p.keys == 2 && p.commits == 1 && p.graphemes == 2);
    CHECK((p.flags & MP_PULSE_ORIGIN) == 0); // that one measured no origin

    // A writer inside the slot: others drop their pulse, the reader waits.
    s[pulse::kOffSeq] |= 1;
    CHECK(pulse::write(s, in) == pulse::Write::Busy);
    CHECK(pulse::read(s, reader, 5000, bytes) == pulse::Read::Busy);
    CHECK(pulse::read(s, reader, 5000 + pulse::kStuckMs - 1, bytes) == pulse::Read::Busy);
    // … and after kStuckMs the desk takes the slot back from a writer that died there.
    CHECK(pulse::read(s, reader, 5000 + pulse::kStuckMs, bytes) == pulse::Read::Busy);
    CHECK((s[pulse::kOffSeq] & 1) == 0);
    CHECK(pulse::write(s, in) == pulse::Write::Written);
    CHECK(pulse::read(s, reader, 7000, bytes) == pulse::Read::Fresh);
    CHECK(mp_pulse_decode(bytes, sizeof bytes, &p) == MP_PULSE_OK && p.keys == 3);

    // A torn pulse fails its checksum: the core counts it rejected, the next writer starts over.
    s[pulse::kOffPulse + 9] ^= 0x40;
    CHECK(pulse::write(s, other) == pulse::Write::Written);
    CHECK(pulse::read(s, reader, 7000, bytes) == pulse::Read::Fresh);
    CHECK(mp_pulse_decode(bytes, sizeof bytes, &p) == MP_PULSE_OK && p.seq == 1 && p.keys == 1);

    // A desk that starts again keeps the slot the input methods still hold.
    pulse::initialize(s, 96);
    CHECK(pulse::unitDpi(s) == 96);
    pulse::Reader fresh;
    CHECK(pulse::read(s, fresh, 0, bytes) == pulse::Read::Fresh);
    CHECK(mp_pulse_decode(bytes, sizeof bytes, &p) == MP_PULSE_OK && p.seq == 1);
    pulse::setUnitDpi(s, 50); // out of range: ignored
    CHECK(pulse::unitDpi(s) == 96);
    pulse::setDeskCats(s, 7);
    CHECK(pulse::deskCats(s) == 3);
    // Another layout version is not ours (version 1 was 128 bytes, under other names).
    s[pulse::kOffVersion] = 1;
    CHECK(!pulse::ready(s) && pulse::write(s, in) == pulse::Write::NotReady && pulse::unitDpi(s) == 0);

    // Without a valid unit there is no origin; the counters still go out.
    const MPPulse n = pulse::next(nullptr, in, 0);
    CHECK(n.seq == 1 && n.keys == 1 && (n.flags & MP_PULSE_ORIGIN) == 0);
    const MPPulse wrap = [] {
        MPPulse prev{};
        prev.seq = UINT32_MAX;
        prev.keys = UINT32_MAX;
        pulse::Input one;
        one.keys = 1;
        return pulse::next(&prev, one, 96);
    }();
    CHECK(wrap.seq == 0 && wrap.keys == 0);
}

static void worldFilter() {
    WindowFacts w = window(1, rect(0, 100, 400, 400));
    CHECK(keep(w));
    WindowFacts x = w;
    x.cloaked = true;
    CHECK(!keep(x));
    x = w, x.minimized = true;
    CHECK(!keep(x));
    x = w, x.visible = false;
    CHECK(!keep(x));
    x = w, x.toolWindow = true;
    CHECK(!keep(x));
    x.appWindow = true; // a tool window with a taskbar button counts
    CHECK(keep(x));
    x = w, x.ours = true;
    CHECK(!keep(x));
    x = w, x.shell = true;
    CHECK(!keep(x));
    x = w, x.clickThrough = true;
    CHECK(!keep(x));
    x = w, x.bounds = rect(0, 0, 4, 400);
    CHECK(!keep(x));
    x = w, x.id = 0;
    CHECK(!keep(x));
}

static Snapshot screen() {
    Snapshot s;
    s.unitDpi = 96;
    MonitorFacts m;
    m.monitor = rect(0, 0, 1600, 900);
    m.work = rect(0, 0, 1600, 852);
    m.primary = true;
    s.monitors.push_back(m);
    s.taskbar.known = true;
    s.taskbar.rect = rect(0, 852, 1600, 900);
    s.trayKnown = true;
    s.tray = rect(1400, 860, 1432, 892);
    return s;
}

static void worldGrounds() {
    Snapshot s = screen();
    // Front to back: a small note over the editor's top edge, the editor, a maximized window, a window too high.
    s.windows = {window(10, rect(300, 150, 600, 400)), window(11, rect(100, 200, 900, 820)), window(12, rect(0, 0, 1600, 852)),
                 window(13, rect(1000, 40, 1400, 500))};
    WindowFacts hidden = window(14, rect(1100, 300, 1500, 600));
    hidden.cloaked = true; // another virtual desktop
    s.windows.push_back(hidden);
    s.foreground = s.windows[1];
    const World w = buildWorld(s);
    CHECK(w.windows.size() == 4);
    // The editor's edge: from 12 px in, to 146 px before its right side, minus the note in front (±2 px).
    std::vector<const MPDeskGround *> editor;
    for (const auto &g : w.grounds)
        if (g.owner == 11) editor.push_back(&g);
    CHECK(editor.size() == 2);
    if (editor.size() == 2) {
        CHECK(editor[0]->kind == MP_DESK_GROUND_EDGE && editor[0]->y == 200 && editor[0]->ox == 100);
        CHECK(editor[0]->x0 == 112 && editor[0]->x1 == 298);
        CHECK(editor[1]->x0 == 602 && editor[1]->x1 == 754);
        CHECK((editor[0]->flags & MP_DESK_GROUND_BEHIND) != 0);
        CHECK(editor[0]->id != editor[1]->id && editor[0]->id >= 0x10000);
    }
    CHECK(find(w, MP_DESK_GROUND_EDGE, 10) != nullptr); // the note's own edge (150 - 58 >= 0)
    CHECK(find(w, MP_DESK_GROUND_EDGE, 12) == nullptr); // maximized: no room above
    CHECK(find(w, MP_DESK_GROUND_EDGE, 13) == nullptr); // 40 px from the top: no room for a cat
    for (const auto &g : w.grounds) CHECK(g.owner != 14);
    // The taskbar: a shelf cats can hide behind; under the window you are using only peeking.
    const MPDeskGround *shelf = find(w, MP_DESK_GROUND_SHELF);
    const MPDeskGround *peek = find(w, MP_DESK_GROUND_SHELF, 0, true);
    CHECK(shelf && shelf->y == 852 && (shelf->flags & MP_DESK_GROUND_BEHIND));
    CHECK(peek && peek->x0 == 100 && peek->x1 == 900);
    CHECK(find(w, MP_DESK_GROUND_FLOOR) == nullptr);
    // The door is the tray icon; cats jump up out of the taskbar.
    CHECK(w.doors.out_x == 1416 && w.doors.home_x == 1416 && w.doors.out_y == 892 && w.doors.out_vy < 0);
    CHECK(w.walls.x0 == 0 && w.walls.x1 == 1600 && w.walls.top == 0 && w.walls.bottom == 940);
    CHECK(w.sides.empty()); // nothing reaches down to a cat's paws over the taskbar

    // Measured again: the same world; the editor moved 30 px right: its edges keep their ids and move.
    const World again = buildWorld(s);
    CHECK(sameWorld(w, again));
    Snapshot moved = s;
    moved.windows[1].bounds = rect(130, 200, 930, 820);
    moved.foreground = moved.windows[1];
    const World w2 = buildWorld(moved);
    CHECK(!sameWorld(w, w2));
    const MPDeskGround *e2 = find(w2, MP_DESK_GROUND_EDGE, 11);
    CHECK(e2 && !editor.empty() && e2->id == editor[0]->id && e2->ox == 130);

    // The caret's window: the front-most one holding the point.
    CHECK(ownerAt(w, 350, 300) == 10);
    CHECK(ownerAt(w, 150, 300) == 11);
    CHECK(ownerAt(w, 1550, 860) == 12 || ownerAt(w, 1550, 860) == 0);
    CHECK(ownerAt(w, 1590, 880) == 0);

    // A window standing on the taskbar's level: Mimimo may hold its left side, Momo its right.
    Snapshot tall = screen();
    tall.windows = {window(20, rect(400, 300, 900, 851))};
    const World w3 = buildWorld(tall);
    bool left = false, right = false;
    for (const auto &side : w3.sides) {
        left = left || (side.dir == -1 && side.stand_x == 400 - 22.5);
        right = right || (side.dir == 1 && side.stand_x == 900 + 23.9);
    }
    CHECK(left && right);
}

static void worldScreens() {
    // An auto-hidden taskbar: the bottom of the screen is a floor.
    Snapshot s = screen();
    s.taskbar.autoHide = true;
    s.monitors[0].work = s.monitors[0].monitor;
    World w = buildWorld(s);
    const MPDeskGround *floor = find(w, MP_DESK_GROUND_FLOOR);
    CHECK(floor && floor->y == 900 && floor->x0 == 14 && (floor->flags & MP_DESK_GROUND_BEHIND) == 0);
    CHECK(find(w, MP_DESK_GROUND_SHELF) == nullptr);

    // Two screens at 150 % and 100 %: one plane in desk points (the primary's 144 DPI); the second
    // screen's taskbar comes from its shorter work area.
    Snapshot two;
    two.unitDpi = 144;
    MonitorFacts a;
    a.monitor = rect(0, 0, 2880, 1620);
    a.work = rect(0, 0, 2880, 1548);
    a.dpi = 144;
    a.primary = true;
    MonitorFacts b;
    b.monitor = rect(2880, 0, 4800, 1080);
    b.work = rect(2880, 0, 4800, 1032);
    b.dpi = 96;
    two.monitors = {a, b};
    two.taskbar.known = true;
    two.taskbar.rect = rect(0, 1548, 2880, 1620);
    two.windows = {window(30, rect(3000, 300, 3900, 900))};
    w = buildWorld(two);
    const MPDeskGround *edge = find(w, MP_DESK_GROUND_EDGE, 30);
    CHECK(edge && edge->y == 200 && edge->x0 == 2000 + 12);
    int shelves = 0;
    for (const auto &g : w.grounds)
        if (g.kind == MP_DESK_GROUND_SHELF) {
            ++shelves;
            CHECK(g.y == 1032 || g.y == 688);
        }
    CHECK(shelves == 2);
    CHECK(w.walls.x1 == 3200 && w.walls.bottom == 1080 + 40);
    // Without a tray icon the door is near the taskbar's right end.
    CHECK(w.doors.out_x == 1920 - 160);

    // No monitor known: nothing to stand on (the cats stay home).
    Snapshot none;
    CHECK(buildWorld(none).grounds.empty());
}

static void fullScreen() {
    Snapshot s = screen();
    s.foreground = window(40, rect(0, 0, 1600, 900));
    CHECK(coversMonitor(s));
    s.foreground.bounds = rect(-8, -8, 1608, 908); // a game's window slightly larger than the screen
    CHECK(coversMonitor(s));
    s.foreground.bounds = rect(0, 0, 1600, 852); // maximized: the taskbar shows
    CHECK(!coversMonitor(s));
    s.foreground = window(41, rect(0, 0, 1600, 900));
    s.foreground.shell = true; // the desktop itself
    CHECK(!coversMonitor(s));
    s.foreground.shell = false;
    s.foreground.ours = true;
    CHECK(!coversMonitor(s));
    s.foreground = WindowFacts{};
    CHECK(!coversMonitor(s));
    CHECK(fullScreenState(2) && fullScreenState(3) && fullScreenState(4));
    CHECK(!fullScreenState(1) && !fullScreenState(5) && !fullScreenState(0));
}

static MPDeskCat sitting(double x, double y) {
    MPDeskCat c{};
    c.body = MP_DESK_BODY_GROUND;
    c.pose = MP_DESK_POSE_SIT;
    c.seat = MP_DESK_SEAT_TOP;
    c.facing = 1;
    c.x = x;
    c.y = y;
    c.scale_x = c.scale_y = 1;
    c.flags = MP_DESK_CAT_ASLEEP; // no breathing: a still frame
    return c;
}

static void art() {
    CHECK(findSprite("mimimo_idle") && findSprite("momo_edge_peek") && findSprite("momo_side_hold"));
    for (const CatSprite &p : kDeskPoses) CHECK(findSprite(p.name) == &p); // CLAY-POSES batches 1 and 2
    MPDeskCat c = sitting(400, 300);
    CHECK(std::string(clayArt(0, c).sprite->name) == "mimimo_idle");
    CHECK(std::string(clayArt(1, c, 0, true).sprite->name) == "momo_turn"); // arrived after a walk
    c.pose = MP_DESK_POSE_SLEEP;
    CHECK(std::string(clayArt(1, c).sprite->name) == "momo_sleep");
    CHECK(std::string(clayArt(0, c).sprite->name) == "mimimo_sleep");
    c.pose = MP_DESK_POSE_WALK;
    for (int frame = 0; frame < 4; ++frame) {
        const Art a = clayArt(0, c, frame);
        CHECK(a.fx == Fx::Walk && std::string(a.sprite->name) == "mimimo_walk_" + std::to_string(frame + 1));
    }
    c.pose = MP_DESK_POSE_TROT;
    CHECK(std::string(clayArt(1, c, 6).sprite->name) == "momo_walk_3"); // the 4-frame fallback
    // The 12-frame cycle: the table's frames in order, the key poses on every third.
    CHECK(walkCycleLength(0) == 12 && walkCycleLength(1) == 12);
    for (int cat = 0; cat < 2; ++cat) {
        const std::string base = cat == 0 ? "mimimo_walk_" : "momo_walk_";
        for (int frame = 0; frame < 12; ++frame) {
            const std::string name = clayArt(cat, c, frame, false, 12).sprite->name;
            char tail[8];
            std::snprintf(tail, sizeof tail, "f%02d", frame + 1);
            CHECK(name == base + (frame % 3 == 0 ? std::to_string(frame / 3 + 1) : std::string(tail)));
        }
        CHECK(std::string(clayArt(cat, c, -1, false, 12).sprite->name) == base + "f12"); // wraps
    }
    CHECK(std::string(clayArt(0, c, 5, false, 8).sprite->name) == "mimimo_walk_2"); // no cycle of that length: walk_1-4
    c.pose = MP_DESK_POSE_LOOK;
    CHECK(std::string(clayArt(0, c).sprite->name) == "mimimo_look_up" && clayArt(0, c).native == -1);
    c.pose = MP_DESK_POSE_AIR;
    CHECK(std::string(clayArt(1, c).sprite->name) == "momo_fall");
    c.pose = MP_DESK_POSE_HELD;
    CHECK(std::string(clayArt(0, c).sprite->name) == "mimimo_lift" && clayArt(0, c).fx == Fx::Held);
    c.pose = MP_DESK_POSE_JUMP;
    CHECK(std::string(clayArt(0, c).sprite->name) == "mimimo_idle"); // no hop figure yet
    c.pose = MP_DESK_POSE_JUMP;
    CHECK(std::string(clayArt(1, c).sprite->name) == "momo_jump" && clayArt(1, c).fx == Fx::Air);
    c.pose = MP_DESK_POSE_REST;
    c.seat = MP_DESK_SEAT_BEHIND;
    CHECK(std::string(clayArt(0, c).sprite->name) == "mimimo_edge_rest" && clayArt(0, c).behind);
    c.seat = MP_DESK_SEAT_SIDE;
    c.pose = MP_DESK_POSE_SIDE;
    CHECK(std::string(clayArt(1, c).sprite->name) == "momo_side_hold" && clayArt(1, c).side);

    OvalImages ovals;
    PaintContext ctx;
    ctx.unitDpi = 144;
    ctx.monitorDpi = 144;
    Frame f;
    c = sitting(400, 300);
    CHECK(paintCat(1, c, ctx, ovals, f));
    CHECK(f.visible && f.width == 240 && f.height == 204);
    CHECK(f.left == 600 - 120 && f.top == 450 - 174);
    // The body's middle catches the mouse; the corner of the window and the shadow do not.
    CHECK(hitTest(f, 600, 450 - 40));
    CHECK(!hitTest(f, f.left + 1, f.top + 1));
    CHECK(!hitTest(f, 600 + 20, 450 + 2)); // the contact shadow under the paws
    bool shadowDrawn = false;
    for (std::size_t i = 0; i < f.mask.size(); ++i) {
        if (f.mask[i] && f.bgra[i * 4 + 3] < kHitAlpha) CHECK(false);
        if (!f.mask[i] && f.bgra[i * 4 + 3] > 0) shadowDrawn = true;
    }
    CHECK(shadowDrawn);
    // Nothing moved: no redraw.
    CHECK(!paintCat(1, c, ctx, ovals, f));
    c.x += 1;
    CHECK(paintCat(1, c, ctx, ovals, f));

    // Behind an edge: nothing opaque below the edge line except the paws.
    c = sitting(400, 300);
    c.seat = MP_DESK_SEAT_BEHIND;
    c.pose = MP_DESK_POSE_PEEK;
    CHECK(paintCat(0, c, ctx, ovals, f));
    const int edgeRow = 450 - f.top;
    int below = 0, above = 0;
    for (int y = 0; y < f.height; ++y)
        for (int x = 0; x < f.width; ++x)
            if (f.mask[static_cast<std::size_t>(y) * f.width + x]) (y >= edgeRow ? below : above)++;
    CHECK(above > 0 && below > 0 && below < above / 5); // only the paws hang over
    c.sink = 1;                                          // sunk: not even the paws
    CHECK(paintCat(0, c, ctx, ovals, f));
    below = 0;
    for (int y = edgeRow; y < f.height; ++y)
        for (int x = 0; x < f.width; ++x) below += f.mask[static_cast<std::size_t>(y) * f.width + x];
    CHECK(below == 0);

    // A cat at home or on the glass is not on the desk.
    c = sitting(400, 300);
    c.body = MP_DESK_BODY_GLASS;
    c.pose = MP_DESK_POSE_NONE;
    CHECK(paintCat(0, c, ctx, ovals, f)); // hidden now
    CHECK(!f.visible && !hitTest(f, 600, 410));
    CHECK(!paintCat(0, c, ctx, ovals, f));

    // On a 100 % monitor beside the 150 % primary the cat keeps its size in that monitor's pixels.
    ctx.monitorDpi = 96;
    c = sitting(400, 300);
    CHECK(paintCat(0, c, ctx, ovals, f));
    CHECK(f.width == 160 && f.height == 136 && f.left == 600 - 80);
}

static void dwell() {
    Dwell d;
    DwellStep s = d.sample(1000, MP_PETS_MOMO, false, false, true);
    CHECK(!s.arm && s.checkAt == 1250);
    s = d.sample(1100, MP_PETS_MOMO, false, false, true);
    CHECK(!s.arm && s.checkAt == 1250);
    s = d.sample(1250, MP_PETS_MOMO, false, false, true);
    CHECK(s.arm == MP_PETS_MOMO && d.armed() == MP_PETS_MOMO);
    s = d.sample(1300, MP_PETS_MOMO, false, false, true);
    CHECK(!s.arm && !s.disarm);
    d.capture(true); // pressed: nothing changes until the release
    s = d.sample(1400, 0, true, false, true);
    CHECK(!s.disarm && d.armed() == MP_PETS_MOMO);
    d.capture(false);
    s = d.sample(1500, 0, false, false, true);
    CHECK(s.disarm && d.armed() == 0);
    // Moving across with a button down (dragging a window) never arms.
    d.sample(2000, MP_PETS_MIMIMO, true, false, true);
    s = d.sample(2400, MP_PETS_MIMIMO, true, false, true);
    CHECK(!s.arm && d.armed() == 0);
    // Typing, or "click-through always": never.
    d.sample(3000, MP_PETS_MIMIMO, false, true, true);
    s = d.sample(3400, MP_PETS_MIMIMO, false, true, true);
    CHECK(!s.arm);
    d.sample(4000, MP_PETS_MIMIMO, false, false, false);
    s = d.sample(4400, MP_PETS_MIMIMO, false, false, false);
    CHECK(!s.arm);
    // From one cat straight to the other: the first lets go, the second waits its own 250 ms.
    d.sample(5000, MP_PETS_MIMIMO, false, false, true);
    d.sample(5300, MP_PETS_MIMIMO, false, false, true);
    CHECK(d.armed() == MP_PETS_MIMIMO);
    s = d.sample(5400, MP_PETS_MOMO, false, false, true);
    CHECK(s.disarm && !s.arm && s.checkAt == 5650);
}

static void settings() {
    DeskSettings s;
    MPDeskSettings c = coreSettings(s);
    CHECK(c.mode == MP_DESK_MODE_FULL && c.typing == MP_DESK_TYPING_SPLIT && c.flags == 0);
    CHECK(c.art[0] == 116 && c.art[1] == 116); // the clay cats as they ship: they walk
    CHECK(c.size[0].w == 34 && c.size[0].h == 52 && c.size[1].w == 35);
    s.typingSeat = MP_DESK_TYPING_GLASS;
    s.reduceMotion = true;
    s.quiet = true;
    c = coreSettings(s);
    CHECK(c.mode == MP_DESK_MODE_QUIET && c.typing == MP_DESK_TYPING_GLASS && c.flags == MP_DESK_REDUCE_MOTION);
    CHECK(runCommand(L"C:\\Program Files\\Meowpuut\\a\\x64\\MeowpuutDesk.exe") ==
          L"\"C:\\Program Files\\Meowpuut\\a\\x64\\MeowpuutDesk.exe\"");
    MPDesk *desk = mp_desk_create(&c, 1);
    CHECK(desk != nullptr); // the core takes every setting we make
    mp_desk_free(desk);
}

static void loopWorld() {
    DeskLoop loop(coreSettings(DeskSettings{}), 7);
    CHECK(loop.valid());
    Snapshot s = screen();
    s.windows = {window(11, rect(100, 200, 900, 700))};
    const World w = buildWorld(s);
    loop.setWorld(w);
    MPPetsClock clock{1000, 20000, 600};
    CHECK(loop.step(clock) == MP_DESK_OK && loop.worldsSent() == 1);
    loop.setWorld(buildWorld(s));
    clock.mono_ms += 100;
    CHECK(loop.step(clock) == MP_DESK_OK && loop.worldsSent() == 1); // unchanged: not sent again
    s.windows[0].bounds.left += 40;
    loop.setWorld(buildWorld(s));
    clock.mono_ms += 100;
    CHECK(loop.step(clock) == MP_DESK_OK && loop.worldsSent() == 2);
    // At home both, nobody here: no timer at all.
    const Timers t = loop.timers();
    CHECK(loop.deskCats() == 0 && t.sampleMs == 0 && !t.idleProbe && !t.frames);
}


static std::string readAll(const fs::path &p) {
    std::ifstream in(p, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(in), {});
}

// A figure whose left half (left of its anchor) is opaque: tells a mirrored frame from one that is not.
class LeftHalfImages : public ImageSource {
public:
    const Image *scaled(const CatSprite &sprite, int width, int height) override {
        auto [it, fresh] = cache_.try_emplace(std::make_tuple(&sprite, width, height));
        Image &img = it->second;
        if (fresh) {
            img.width = width;
            img.height = height;
            img.bgra.assign(static_cast<std::size_t>(width) * height * 4, 0);
            const int top = static_cast<int>(sprite.top * static_cast<float>(height));
            const int bottom = static_cast<int>(sprite.bottom * static_cast<float>(height));
            const int left = static_cast<int>(sprite.left * static_cast<float>(width));
            const int middle = static_cast<int>(sprite.anchorX * static_cast<float>(width));
            for (int y = top; y < bottom; ++y)
                for (int x = left; x < middle; ++x) img.bgra[(static_cast<std::size_t>(y) * width + x) * 4 + 3] = 255;
        }
        return &img;
    }

private:
    std::map<std::tuple<const CatSprite *, int, int>, Image> cache_;
};

static int maskRows(const Frame &f) {
    int rows = 0;
    for (int y = 0; y < f.height; ++y)
        for (int x = 0; x < f.width; ++x)
            if (f.mask[static_cast<std::size_t>(y) * f.width + x]) {
                ++rows;
                break;
            }
    return rows;
}

static void walking() {
    // One step per key (the core flips `phase` with every step), walk_1 first; a 12-frame cycle advances 3 frames per
    // step, so every step lands on a key pose and no in-between ever shows on the typing path.
    Gait g(12);
    CHECK(g.length() == 12 && g.stepFrames() == 3 && Gait(7).length() == 4 && Gait().stepFrames() == 1);
    MPDeskCat c = sitting(400, 300);
    c.flags = 0;
    g.step(c, 1000);
    CHECK(!g.walking() && !g.turning(1000));
    c.pose = MP_DESK_POSE_WALK;
    const int expect[] = {0, 3, 6, 9, 0, 3};
    for (int k = 0; k < 6; ++k) {
        c.phase ^= 1;
        g.step(c, 1000 + 150 * static_cast<std::uint64_t>(k));
        CHECK(g.walkFrame() == expect[k] && g.keyPose() == k % 4);
        CHECK(clayArt(0, c, g.walkFrame(), false, g.length()).sprite->name == "mimimo_walk_" + std::to_string(k % 4 + 1));
        g.step(c, 1016 + 150 * static_cast<std::uint64_t>(k)); // a step of the core without a new stride
        CHECK(g.walkFrame() == expect[k]);
    }
    CHECK(g.steps() == 6);
    // A missing cycle: the 4-frame gait, one frame per step.
    Gait four(4);
    MPDeskCat w = sitting(400, 300);
    w.flags = 0;
    w.pose = MP_DESK_POSE_WALK;
    for (int k = 0; k < 5; ++k) {
        w.phase ^= 1;
        four.step(w, 100 * static_cast<std::uint64_t>(k));
        CHECK(four.walkFrame() == k % 4);
    }
    // Arrived: the turn from the side to the front, for kTurnMs.
    c.pose = MP_DESK_POSE_SIT;
    g.step(c, 2000);
    CHECK(g.turning(2000) && g.turning(2000 + kTurnMs - 1) && !g.turning(2000 + kTurnMs));
    g.step(c, 2100);
    CHECK(g.turning(2100) && g.turnUntil() == 2000 + kTurnMs);
    c.pose = MP_DESK_POSE_HAPPY;
    g.step(c, 2150);
    CHECK(!g.turning(2150)); // something else: no turn
    c.pose = MP_DESK_POSE_WALK;
    c.phase ^= 1;
    g.step(c, 2200);
    CHECK(g.walkFrame() == 0); // a new walk starts at walk_1

    // The walk frames face right; walking left mirrors them.
    LeftHalfImages half;
    PaintContext ctx;
    Frame f;
    c = sitting(400, 300);
    c.flags = 0;
    c.pose = MP_DESK_POSE_WALK;
    const auto sides = [&](int facing, int frame) {
        c.facing = static_cast<std::int8_t>(facing);
        ctx.walkFrame = frame;
        paintCat(0, c, ctx, half, f);
        long left = 0, right = 0;
        for (int y = 0; y < f.height; ++y)
            for (int x = 0; x < f.width; ++x)
                if (f.mask[static_cast<std::size_t>(y) * f.width + x]) {
                    if (x + f.left < 399) ++left;
                    if (x + f.left > 401) ++right;
                }
        return std::make_pair(left, right);
    };
    for (int frame = 0; frame < 4; ++frame) {
        const auto toRight = sides(1, frame), toLeft = sides(-1, frame);
        CHECK(toRight.first > 0 && toRight.second == 0);
        CHECK(toLeft.second > 0 && toLeft.first == 0);
    }
    // A walk frame is drawn as is: no tilt, no hop (the stand-in's wobble is gone).
    c.facing = 1;
    c.phase = 0;
    ctx.walkFrame = 1;
    paintCat(0, c, ctx, half, f);
    const std::vector<std::uint8_t> one = f.mask;
    c.phase = 1;
    c.flags = MP_DESK_CAT_FRESH_STEP;
    paintCat(0, c, ctx, half, f);
    CHECK(f.mask == one);

    // The squash cap: a hint below 0.94 is drawn at 0.94.
    OvalImages ovals;
    c = sitting(400, 300);
    paintCat(1, c, ctx, ovals, f);
    const int tall = maskRows(f);
    c.scale_y = 0.94;
    paintCat(1, c, ctx, ovals, f);
    const int capped = maskRows(f);
    c.scale_y = 0.5;
    paintCat(1, c, ctx, ovals, f);
    CHECK(maskRows(f) == capped && capped > tall * 0.9 && capped < tall); // not half as tall

    c.scale_y = 1.4;
    paintCat(1, c, ctx, ovals, f);
    CHECK(maskRows(f) <= tall * 1.07);

    // Lifted: the lift figure hangs from the scruff, 0.82 of the body above the paws, legs inside the window.
    c = sitting(400, 300);
    c.flags = 0;
    c.body = MP_DESK_BODY_HELD;
    c.pose = MP_DESK_POSE_HELD;
    CHECK(paintCat(0, c, ctx, ovals, f));
    const double scruff = 300 - kScruffRise * kBodyHeight;
    CHECK(hitTest(f, 400, static_cast<std::int32_t>(scruff + 28)) && !hitTest(f, 400, 320));
    int lowest = 0;
    for (int y = 0; y < f.height; ++y)
        for (int x = 0; x < f.width; ++x)
            if (f.mask[static_cast<std::size_t>(y) * f.width + x]) lowest = y;
    CHECK(lowest < f.height - 2);
}

static void glassSeat() {
    alignas(64) unsigned char g[pulse::kSectionBytes] = {};
    const std::uint32_t both = MP_PETS_MIMIMO | MP_PETS_MOMO;
    const auto seats = [&] { return pulse::glassCats(pulse::deskCats(g)); };
    CHECK(seats() == both); // no desk: both on the glass
    pulse::initialize(g, 96);
    CHECK(seats() == both);
    pulse::setDeskCats(g, MP_PETS_MIMIMO); // 乙 while you type: Mimimo on your window's top edge
    CHECK(pulse::deskCats(g) == MP_PETS_MIMIMO && seats() == MP_PETS_MOMO);
    pulse::setDeskCats(g, both); // the first key, before the desk moved Momo: she is on the glass anyway
    CHECK(seats() == MP_PETS_MOMO);
    pulse::setDeskCats(g, MP_PETS_MOMO); // Mimimo could not get there and joined the glass
    CHECK(seats() == both);
    pulse::setDeskCats(g, 0); // 甲, called home, or full screen
    CHECK(seats() == both);
    pulse::setDeskCats(g, MP_PETS_MIMIMO);
    pulse::retire(g); // the desk quits
    CHECK(pulse::deskCats(g) == 0 && seats() == both);

    // The "now" field for the companion's Steam presence: an MPActivityCurrent as is, behind its own seqlock.
    MPActivityCurrent n{};
    CHECK(pulse::now(g, n) && n.state == MP_ACTIVITY_NOW_OFF && n.category == MP_ACTIVITY_NO_CATEGORY && n.app[0] == 0);
    MPActivityCurrent c{};
    c.state = MP_ACTIVITY_NOW_ACTIVE;
    c.category = MP_ACTIVITY_CAT_CODE;
    c.flags = MP_ACTIVITY_FULLSCREEN;
    pulse::setNow(g, c);
    CHECK(pulse::now(g, n) && n.state == MP_ACTIVITY_NOW_ACTIVE && n.category == MP_ACTIVITY_CAT_CODE &&
          n.flags == MP_ACTIVITY_FULLSCREEN && n.app[0] == 0);
    CHECK(std::memcmp(g + pulse::kOffNow, &c, sizeof c) == 0);
    g[pulse::kOffNowSeq] |= 1; // the desk is writing it
    CHECK(!pulse::now(g, n));
    pulse::setNow(g, c);
    CHECK(pulse::now(g, n) && (g[pulse::kOffNowSeq] & 1) == 0);
    pulse::retire(g);
    CHECK(pulse::now(g, n) && n.state == MP_ACTIVITY_NOW_OFF && n.category == MP_ACTIVITY_NO_CATEGORY);
    // The pulse slot is untouched by all this.
    CHECK(pulse::ready(g) && pulse::unitDpi(g) == 96);
}

static void ledgerTouches(const fs::path &root) {
    const fs::path dir = root / "pets";
    PetLedger ledger(dir);
    const std::vector<MPDeskEventOut> taken = {
        {MP_DESK_OUT_PETTED, MP_PETS_MIMIMO, 0, 0}, {MP_DESK_OUT_PETTED, MP_PETS_MIMIMO, 0, 0}, {MP_DESK_OUT_DROP, MP_PETS_MOMO, 1, 0},
        {MP_DESK_OUT_PURR, MP_PETS_MIMIMO, 0, 0},   {MP_DESK_OUT_STEP, 0, 0, 0},                {MP_DESK_OUT_HELLO, 0, 0, 0},
        {MP_DESK_OUT_PETTED, 0, 0, 0}};
    LedgerClock clock;
    clock.monoMs = 5000;
    clock.local = {20000, 600};
    CHECK(recordTouches(taken, ledger, clock) == 4);
    CHECK(ledger.merges() == 0 && !fs::exists(dir / PetLedger::kFileName)); // batched: nothing written yet
    CHECK(ledger.flush() && ledger.merges() == 1);
    const auto bytes = LedgerFile::readFile(dir / PetLedger::kFileName);
    int status = -1;
    MPPets *p = mp_pets_from_bytes(bytes.data(), bytes.size(), &status);
    MPPetsProgress mimi{}, momo{};
    CHECK(status == MP_PETS_OK && mp_pets_progress(p, MP_PETS_MIMIMO, &mimi, sizeof mimi) &&
          mp_pets_progress(p, MP_PETS_MOMO, &momo, sizeof momo));
    CHECK(mimi.pets == 2 && mimi.falls == 0 && momo.pets == 0 && momo.falls == 1);
    MPPetsMark marks[4] = {};
    CHECK(mp_pets_marks(p, marks, 4) == 1 && marks[0].day == 20000 && marks[0].who == 3);
    mp_pets_free(p);
    // A minute later the next touch writes by itself, as the IME's commits do.
    clock.monoMs += PetLedger::kFlushIntervalMs;
    CHECK(recordTouches({{MP_DESK_OUT_PETTED, MP_PETS_MOMO, 0, 0}}, ledger, clock) == 1 && ledger.merges() == 2);
    // The core adopts today's shared limits from that state.
    DeskLoop loop(coreSettings(DeskSettings{}), 3);
    CHECK(loop.adopt(ledger.state()) == MP_DESK_OK);
    CHECK(loop.adopt({}) == MP_DESK_E_DATA);
    CHECK(loop.adopt(std::vector<std::uint8_t>(MP_PETS_STATE_BYTES, 0xA5)) == MP_DESK_E_DATA);
}

static MPPetsClock at(std::uint64_t ms) {
    return MPPetsClock{ms, 20000, static_cast<std::uint32_t>(600 + ms / 60'000) % 1440};
}

static std::uint32_t minutes(const ActivityLedger &ledger, int category = -1) {
    std::uint32_t total = 0;
    for (const MPActivityDay &d : ledger.days())
        for (int i = 0; i < MP_ACTIVITY_CATEGORIES; ++i)
            if (category < 0 || category == i) total += d.minutes[i];
    return total;
}

// The shell's pointer sampling, once a second for `ms`: you are here (idle 0), or away since `now`.
static void sampleFor(ForegroundWatcher &w, std::uint64_t &now, std::uint64_t ms, bool here = true) {
    const std::uint64_t end = now + ms;
    std::uint64_t input = now;
    for (; now < end; now += 1000) {
        if (here) input = now;
        w.sample(at(now), now - input);
    }
}

static void activity(const fs::path &root) {
    const fs::path dir = root / "activity";
    const fs::path file = dir / ActivityLedger::kFileName;
    const std::string secret = "meowsecretapp.exe";
    const int other = mp_activity_classify(secret.c_str(), nullptr, 0);
    CHECK(other == MP_ACTIVITY_CAT_OTHER && mp_activity_classify("code.exe", nullptr, 0) == MP_ACTIVITY_CAT_CODE);
    ForegroundWatcher w(dir);
    std::uint64_t now = 1'000'000;

    // Off, the default: nothing is sensed, counted, scheduled or written.
    CHECK(!w.enabled() && !DeskSettings{}.activity.on);
    w.foreground(secret, at(now), 0);
    w.fullScreen(true, at(now), 0);
    w.fullScreen(false, at(now), 0);
    sampleFor(w, now, 10 * 60'000);
    CHECK(w.steps() == 0 && !w.ledger().loaded() && !fs::exists(dir));
    CHECK(w.schedule().tickAt == 0 && !w.schedule().probe && w.current().state == MP_ACTIVITY_NOW_OFF);

    // On: ten minutes in one app are ten minutes of its category.
    ActivitySwitches on;
    on.on = true;
    w.configure(on, at(now), 0);
    w.foreground(secret, at(now), 0);
    CHECK(w.current().state == MP_ACTIVITY_NOW_ACTIVE && w.current().category == static_cast<std::uint32_t>(other));
    CHECK(w.current().app[0] == 0 && !w.wantsAppName()); // no display name without the share-app-name switch
    w.foreground(secret, at(now), 0, "Secret App"); // even when a shell hands one over
    CHECK(w.current().app[0] == 0);
    CHECK(w.schedule().tickAt == now + 60'000 && !w.schedule().probe);
    sampleFor(w, now, 10 * 60'000);
    CHECK(minutes(w.ledger(), other) >= 9 && minutes(w.ledger(), other) <= 10);
    CHECK(fs::exists(file)); // batched: the first write five minutes after the first minute
    w.foreground("code.exe", at(now), 0);
    sampleFor(w, now, 3 * 60'000);
    CHECK(minutes(w.ledger(), MP_ACTIVITY_CAT_CODE) >= 2);
    w.flush();
    // The file holds days, categories and minutes: never an app's name, in any encoding.
    std::string bytes = readAll(file);
    CHECK(bytes.size() == MP_ACTIVITY_STATE_BYTES);
    CHECK(bytes.find("meowsecret") == std::string::npos && bytes.find("code") == std::string::npos &&
          bytes.find(".exe") == std::string::npos && bytes.find(std::string("m\0e\0o\0w", 7)) == std::string::npos);

    // Away: nothing counts past the last input, no tick, only the input check; input brings it back.
    std::uint32_t before = minutes(w.ledger());
    sampleFor(w, now, 15 * 60'000, false);
    CHECK(minutes(w.ledger()) <= before + 1);
    CHECK(w.current().state == MP_ACTIVITY_NOW_IDLE && w.schedule().tickAt == 0 && w.schedule().probe);
    const std::uint64_t steps = w.steps();
    w.sample(at(now), 20 * 60'000); // the 30 s check: still nobody
    CHECK(w.steps() == steps);
    w.sample(at(now), 0);
    CHECK(w.current().state == MP_ACTIVITY_NOW_ACTIVE && w.schedule().tickAt != 0 && !w.schedule().probe);

    // Paused, or an app the user hid: nothing counts.
    before = minutes(w.ledger());
    ActivitySwitches paused = on;
    paused.paused = true;
    w.configure(paused, at(now), 0);
    sampleFor(w, now, 5 * 60'000);
    CHECK(minutes(w.ledger()) == before && w.current().state == MP_ACTIVITY_NOW_PAUSED && w.schedule().tickAt == 0);
    ActivitySwitches hidden = on;
    hidden.hidden = {"CODE.EXE"};
    w.configure(hidden, at(now), 0);
    sampleFor(w, now, 5 * 60'000);
    CHECK(minutes(w.ledger()) == before && w.current().state == MP_ACTIVITY_NOW_HIDDEN);
    w.foreground(secret, at(now), 0);
    sampleFor(w, now, 3 * 60'000);
    CHECK(minutes(w.ledger()) >= before + 2);
    // 「顯示 App 名稱」 (ActivityShareAppName): the name is published only while it is on and the state is ACTIVE.
    {
        ActivitySwitches share = on;
        share.shareAppName = true;
        w.configure(share, at(now), 0);
        CHECK(w.wantsAppName() && w.current().app[0] == 0); // on, but the shell has not reported a name yet
        w.foreground(secret, at(now), 0, "Secret App"); // the same app in front: the name fills at once
        CHECK(w.current().state == MP_ACTIVITY_NOW_ACTIVE && std::string(w.current().app) == "Secret App");
        // Hidden app: state HIDDEN, no name.
        ActivitySwitches shareHidden = share;
        shareHidden.hidden = {"CODE.EXE"};
        w.configure(shareHidden, at(now), 0);
        w.foreground("code.exe", at(now), 0, "Visual Studio Code");
        CHECK(w.current().state == MP_ACTIVITY_NOW_HIDDEN && w.current().app[0] == 0);
        w.foreground(secret, at(now), 0, "Secret App");
        CHECK(std::string(w.current().app) == "Secret App");
        // Paused: no name.
        ActivitySwitches sharePaused = share;
        sharePaused.paused = true;
        w.configure(sharePaused, at(now), 0);
        CHECK(w.current().state == MP_ACTIVITY_NOW_PAUSED && w.current().app[0] == 0);
        w.configure(share, at(now), 0);
        w.foreground(secret, at(now), 0, "Secret App");
        CHECK(std::string(w.current().app) == "Secret App");
        // Switch turned off: gone at once, and a later name is ignored.
        const std::uint64_t changes = w.changes();
        w.configure(on, at(now), 0);
        CHECK(w.current().app[0] == 0 && w.changes() > changes && !w.wantsAppName());
        w.foreground(secret, at(now), 0, "Secret App");
        CHECK(w.current().app[0] == 0);
        // Turned on again with the app still in front: the name is back when the shell passes it.
        w.configure(share, at(now), 0);
        w.foreground(secret, at(now), 0, "Secret App");
        CHECK(std::string(w.current().app) == "Secret App");
        w.configure(on, at(now), 0); // back to plain recording for what follows
        CHECK(w.current().app[0] == 0);
    }
    // Full screen counts (it only changes what friends see).
    w.fullScreen(true, at(now), 0);
    CHECK((w.current().flags & MP_ACTIVITY_FULLSCREEN) != 0);
    sampleFor(w, now, 2 * 60'000);
    w.fullScreen(false, at(now), 0);

    // Off again: what was counted is written, then nothing runs and nothing more is recorded.
    const std::uint32_t total = minutes(w.ledger());
    w.configure(ActivitySwitches{}, at(now), 0);
    CHECK(!w.enabled() && !w.ledger().loaded() && w.current().state == MP_ACTIVITY_NOW_OFF);
    bytes = readAll(file);
    const std::uint64_t stepsOff = w.steps();
    w.foreground("code.exe", at(now), 0);
    sampleFor(w, now, 10 * 60'000);
    CHECK(readAll(file) == bytes && w.steps() == stepsOff && w.schedule().tickAt == 0 && !w.schedule().probe);
    ActivityLedger stored(dir);
    CHECK(stored.load() && minutes(stored) >= total && minutes(stored) <= total + 1);

    // A second writer's minutes and these add up in the file (delta, merge, rename).
    {
        ActivityLedger second(dir);
        CHECK(second.load());
        MPActivityInput in{};
        in.struct_size = sizeof in;
        in.app_id = "code.exe";
        in.flags = MP_ACTIVITY_RECORDING;
        MPActivityStepOut out{};
        in.clock = at(50'000'000);
        CHECK(second.step(in, out));
        in.clock = at(50'000'000 + 2 * 60'000);
        CHECK(second.step(in, out) && out.minutes == 2);
        CHECK(second.flush());
    }
    ActivityLedger reread(dir);
    CHECK(reread.load() && minutes(reread) >= minutes(stored) + 2);
}

int main() {
    const fs::path root = fs::temp_directory_path() /
                          ("meowpuut-desk-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    pulseChannel();
    glassSeat();
    worldFilter();
    worldGrounds();
    worldScreens();
    fullScreen();
    art();
    walking();
    dwell();
    settings();
    loopWorld();
    ledgerTouches(root);
    activity(root);
    std::error_code ec;
    fs::remove_all(root, ec);
    const SmokeResult smoke = runSmoke();
    std::fputs(smoke.log.c_str(), stdout);
    CHECK(smoke.passed);
    if (failures) {
        std::fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    std::puts("desk_test passed");
    return 0;
}
