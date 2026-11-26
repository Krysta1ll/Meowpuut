#include "DeskSmoke.h"

#include "DeskLoop.h"
#include "DeskSettings.h"
#include "PulseChannel.h"

#include <algorithm>
#include <cstdio>
#include <vector>

namespace meowpuut::desk {

const Image *OvalImages::scaled(const CatSprite &sprite, int width, int height) {
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return nullptr;
    auto [it, fresh] = cache_.try_emplace(std::make_tuple(&sprite, width, height));
    Image &img = it->second;
    if (fresh) {
        img.width = width;
        img.height = height;
        img.bgra.assign(static_cast<std::size_t>(width) * height * 4, 0);
        const double w = width, h = height;
        const double cx = (sprite.left + sprite.right) / 2 * w, cy = (sprite.top + sprite.bottom) / 2 * h;
        const double rx = (sprite.right - sprite.left) / 2 * w, ry = (sprite.bottom - sprite.top) / 2 * h;
        for (int y = 0; y < height; ++y)
            for (int x = 0; x < width; ++x) {
                const double u = (x + 0.5 - cx) / rx, v = (y + 0.5 - cy) / ry;
                if (u * u + v * v > 1) continue;
                std::uint8_t *p = &img.bgra[(static_cast<std::size_t>(y) * width + x) * 4];
                p[0] = 120, p[1] = 150, p[2] = 200, p[3] = 255;
            }
    }
    return &img;
}

namespace {

constexpr std::uint32_t kDpi = 120; // a 125 % screen
constexpr double kUnit = kDpi / 96.0;

RectPx rect(std::int32_t l, std::int32_t t, std::int32_t r, std::int32_t b) {
    RectPx x;
    x.left = l, x.top = t, x.right = r, x.bottom = b;
    return x;
}

// A 1920 × 1080 screen at 125 % (1536 × 864 desk points), the taskbar at the
// bottom, the memo you type in near the clock (within Mimimo's reach of the
// door) and a browser behind it, our tray icon by the clock.
Snapshot desktop() {
    Snapshot s;
    s.unitDpi = kDpi;
    MonitorFacts m;
    m.monitor = rect(0, 0, 1920, 1080);
    m.work = rect(0, 0, 1920, 1020);
    m.dpi = kDpi;
    m.primary = true;
    s.monitors.push_back(m);
    s.taskbar.known = true;
    s.taskbar.rect = rect(0, 1020, 1920, 1080);
    s.taskbar.edge = 3;
    WindowFacts memo;
    memo.id = 0x1001;
    memo.bounds = rect(1100, 650, 1880, 1000);
    WindowFacts browser;
    browser.id = 0x1002;
    browser.bounds = rect(100, 75, 1000, 725);
    s.windows = {memo, browser};
    s.foreground = memo;
    s.trayKnown = true;
    s.tray = rect(1700, 1030, 1740, 1070);
    return s;
}

const char *bodyName(std::uint8_t b) {
    const char *n = mp_desk_enum_name(MP_DESK_GROUP_BODY, b);
    return n ? n : "?";
}

struct Counts {
    unsigned wakes = 0, samples = 0, frames = 0, probes = 0, dwells = 0;
};

class Smoke {
public:
    explicit Smoke(ImageSource &art) : art_(art), loop_(coreSettings(DeskSettings{}), 20261123) {
        pulse::initialize(section_, kDpi);
    }

    SmokeResult run() {
        if (!loop_.valid()) {
            check(false, "the core starts");
            return {false, log_};
        }
        world_ = buildWorld(snapshot_);
        check(!world_.grounds.empty() && world_.grounds.size() <= MP_DESK_MAX_GROUNDS, "the synthetic desktop has grounds");
        loop_.setWorld(world_);
        loop_.addEvent(MP_DESK_EV_POINTER, 0, 0, px_, py_); // the first sample: you are here
        loop_.addEvent(MP_DESK_EV_IDLE, 0, 0);
        loop_.addEvent(MP_DESK_EV_OUT, MP_PETS_MIMIMO, 1);
        loop_.addEvent(MP_DESK_EV_OUT, MP_PETS_MOMO, 1);
        step();

        Counts c;
        drive(t0_ + 10'000, c);
        const auto &cats = loop_.output().cats;
        note("10 s: Mimimo %s, Momo %s; %u wakes, %u samples, %u frames", bodyName(cats[0].body), bodyName(cats[1].body), c.wakes,
             c.samples, c.frames);
        check(cats[0].body == MP_DESK_BODY_GROUND && cats[1].body == MP_DESK_BODY_GROUND,
              "both cats came out of the door and stand on a ground");
        check(loop_.worldsSent() == 1, "an unchanged world is handed to the core once");
        check(frames_[0].visible && frames_[1].visible, "both cats are drawn");

        dwell(c);

        drive(t0_ + 12'000, c);
        typing(c);

        drive(t0_ + 30'000, c);
        note("30 s: Mimimo %s, Momo %s", bodyName(loop_.output().cats[0].body), bodyName(loop_.output().cats[1].body));
        check(loop_.deskCats() == 3 && loop_.output().cats[1].body == MP_DESK_BODY_GROUND,
              "after the last key both cats are back on the desk");

        away();
        return {passed_, log_};
    }

private:
    void check(bool ok, const char *what) {
        log_ += ok ? "ok   " : "FAIL ";
        log_ += what;
        log_ += '\n';
        passed_ = passed_ && ok;
    }

    template <class... A>
    void note(const char *format, A... args) {
        char line[256];
        std::snprintf(line, sizeof line, format, args...);
        log_ += "     ";
        log_ += line;
        log_ += '\n';
    }

    MPPetsClock clock() const {
        const std::uint32_t minute = 900 + static_cast<std::uint32_t>((now_ - t0_) / 60'000);
        return MPPetsClock{now_, 20'416, minute % 1440};
    }

    void step() {
        const int r = loop_.step(clock());
        if (r != MP_DESK_OK) {
            note("mp_desk_step: %d", r);
            check(false, "every step succeeds");
        }
        for (const auto &e : loop_.taken()) taken_.push_back(e);
        for (int i = 0; i < 2; ++i) {
            gaits_[i].step(loop_.output().cats[i], now_);
            PaintContext ctx;
            ctx.unitDpi = kDpi;
            ctx.monitorDpi = kDpi;
            ctx.timeMs = static_cast<double>(now_);
            ctx.walkFrame = gaits_[i].walkFrame();
            ctx.walkLength = gaits_[i].length();
            ctx.turning = gaits_[i].turning(now_);
            paintCat(i, loop_.output().cats[i], ctx, art_, frames_[i]);
        }
        pulse::setDeskCats(section_, loop_.deskCats());
    }

    std::uint32_t catUnderPointer() const {
        const auto x = static_cast<std::int32_t>(px_ * kUnit), y = static_cast<std::int32_t>(py_ * kUnit);
        if (hitTest(frames_[1], x, y)) return MP_PETS_MOMO;
        if (hitTest(frames_[0], x, y)) return MP_PETS_MIMIMO;
        return 0;
    }

    // A pointer and world sample, as the shell takes them at the tier's rate.
    void sample() {
        loop_.addEvent(MP_DESK_EV_POINTER, 0, 0, px_, py_);
        reportIdle();
        loop_.setWorld(buildWorld(snapshot_));
        const bool typing = loop_.output().user_state == MP_DESK_USER_TYPING;
        const DwellStep d = dwell_.sample(now_, catUnderPointer(), false, typing, true);
        if (d.arm) loop_.addEvent(MP_DESK_EV_NOTICE, d.arm, 0, px_, py_);
        if (d.checkAt) dwellAt_ = d.checkAt;
    }

    void reportIdle() {
        loop_.addEvent(MP_DESK_EV_IDLE, 0, static_cast<std::uint32_t>(std::min<std::uint64_t>(now_ - userInput_, UINT32_MAX)));
        reportedInput_ = userInput_;
    }

    // Follows the timers the shell would arm until `until` (not stepping at it).
    void drive(std::uint64_t until, Counts &c) {
        for (int guard = 0; guard < 2'000'000; ++guard) {
            const Timers t = loop_.timers();
            if (t.sampleMs != sampleMs_) {
                sampleMs_ = t.sampleMs;
                nextSample_ = sampleMs_ ? now_ + sampleMs_ : 0;
            }
            if (!t.idleProbe) nextProbe_ = 0;
            else if (!nextProbe_) nextProbe_ = now_ + kIdleProbeMs;
            enum { None, Frame, Wake, Sample, Probe, DwellCheck } why = None;
            std::uint64_t next = until;
            const auto consider = [&](bool on, std::uint64_t at, decltype(why) reason) {
                if (on && at < next) next = at, why = reason;
            };
            consider(t.frames, now_ + kFrameMs, Frame);
            consider(t.wake, std::max(t.wakeAt, now_ + 1), Wake);
            consider(nextSample_ != 0, nextSample_, Sample);
            consider(nextProbe_ != 0, nextProbe_, Probe);
            consider(dwellAt_ != 0, dwellAt_, DwellCheck);
            if (why == None) {
                now_ = std::max(now_, until);
                return;
            }
            now_ = next;
            bool run = true;
            switch (why) {
            case Frame: ++c.frames; break;
            case Wake: ++c.wakes; break;
            case Sample:
                ++c.samples;
                nextSample_ = now_ + sampleMs_;
                sample();
                break;
            case Probe:
                // GetLastInputInfo: the core hears of it only when there was input since the last report;
                // otherwise nothing runs (a nap would end, and a cat stretch, with nobody there).
                ++c.probes;
                nextProbe_ = now_ + kIdleProbeMs;
                run = userInput_ != reportedInput_;
                if (run) reportIdle();
                break;
            case DwellCheck:
                ++c.dwells;
                dwellAt_ = 0;
                sample();
                break;
            case None: break;
            }
            if (run) step();
        }
        check(false, "the simulation ends (no spinning schedule)");
    }

    void dwell(Counts &c) {
        const Frame &momo = frames_[1];
        // The opaque pixel nearest the middle of her opaque pixels (any art: ovals or the clay PNGs).
        double sx = 0, sy = 0, n = 0;
        for (std::size_t i = 0; i < momo.mask.size(); ++i)
            if (momo.mask[i]) sx += static_cast<double>(i % momo.width), sy += static_cast<double>(i / momo.width), n += 1;
        check(n > 0, "Momo has opaque pixels");
        if (n == 0) return;
        double best = 1e18;
        std::size_t at = 0;
        for (std::size_t i = 0; i < momo.mask.size(); ++i) {
            if (!momo.mask[i]) continue;
            const double dx = static_cast<double>(i % momo.width) - sx / n, dy = static_cast<double>(i / momo.width) - sy / n;
            if (dx * dx + dy * dy < best) best = dx * dx + dy * dy, at = i;
        }
        px_ = (momo.left + static_cast<double>(at % momo.width) + 0.5) / kUnit;
        py_ = (momo.top + static_cast<double>(at / momo.width) + 0.5) / kUnit;
        userInput_ = now_;
        drive(now_ + 900, c);
        check(dwell_.armed() == MP_PETS_MOMO, "resting 250 ms on Momo's opaque pixels makes her clickable");
        px_ = py_ = 40;
        userInput_ = now_;
        drive(now_ + 600, c);
        check(dwell_.armed() == 0, "off her, click-through again");
        // A shadow-only pixel never catches the mouse.
        bool shadowHit = false;
        for (const Frame &f : frames_)
            for (std::size_t i = 0; i < f.mask.size(); ++i)
                if (f.mask[i] && f.bgra[i * 4 + 3] == 0) shadowHit = true;
        check(!shadowHit, "only drawn sprite pixels are in the hit mask");
    }

    void typing(Counts &c) {
        pulse::Input in;
        in.keys = 1;
        in.composing = true;
        in.haveOrigin = true;
        in.originX = 1150 * kUnit; // in the memo, physical pixels
        in.originY = 560 * kUnit;
        in.lineHeight = 22 * kUnit;
        bool glassAtOnce = false, seatsRight = true;
        unsigned walkSteps = 0, frameSkips = 0;
        for (int k = 0; k < 21; ++k) {
            drive(t0_ + 12'000 + static_cast<std::uint64_t>(k) * 150, c);
            if (k == 20) { // the commit
                in.commits = 1;
                in.graphemes = 2;
                in.composing = false;
            }
            if (pulse::write(section_, in) != pulse::Write::Written) check(false, "a pulse is written");
            std::uint8_t bytes[MP_PULSE_BYTES];
            if (pulse::read(section_, reader_, now_, bytes) != pulse::Read::Fresh) {
                check(false, "the desk reads the pulse");
                continue;
            }
            MPPulse p{};
            std::uint32_t owner = 0;
            if (mp_pulse_decode(bytes, sizeof bytes, &p) == MP_PULSE_OK && (p.flags & MP_PULSE_ORIGIN))
                owner = ownerAt(world_, p.origin_x * 8.0 + 4, p.origin_y * 8.0 + 4);
            if (k == 0) check(owner == 0x1001, "the caret's window is the memo");
            loop_.addPulse(bytes, owner);
            userInput_ = reportedInput_ = now_;
            const std::uint64_t stepsBefore = gaits_[0].steps();
            const int frameBefore = gaits_[0].walkFrame();
            const bool wasWalking = gaits_[0].walking();
            step();
            if (k == 0) glassAtOnce = loop_.output().cats[1].body == MP_DESK_BODY_GLASS;
            // What the candidate window reads from the section: only Momo while Mimimo is out with you.
            const std::uint8_t mimiBody = loop_.output().cats[0].body;
            const bool mimiOut = mimiBody == MP_DESK_BODY_GROUND || mimiBody == MP_DESK_BODY_AIR || mimiBody == MP_DESK_BODY_HELD;
            seatsRight = seatsRight && pulse::glassCats(pulse::deskCats(section_)) ==
                                           (mimiOut ? MP_PETS_MOMO : (MP_PETS_MIMIMO | MP_PETS_MOMO));
            // Mimimo walking with you: this key's step shows the next frame of the walk cycle.
            const MPDeskCat &mimi = loop_.output().cats[0];
            if ((mimi.pose == MP_DESK_POSE_WALK || mimi.pose == MP_DESK_POSE_TROT) && gaits_[0].steps() == stepsBefore + 1) {
                const int expected = wasWalking ? (frameBefore + gaits_[0].stepFrames()) % gaits_[0].length() : 0;
                if (gaits_[0].walkFrame() != expected) ++frameSkips;
                ++walkSteps;
            }
        }
        check(glassAtOnce, "Momo is on the candidate glass from the first key");
        check(seatsRight, "the candidate window seats only Momo while Mimimo is out on the desk");
        note("typing: Mimimo took %u walk steps with your keys", walkSteps);
        check(walkSteps >= 2 && frameSkips == 0, "the clay walk frames advance one per key");
        bool roof = false, glassMimi = false;
        for (const auto &e : taken_) {
            roof = roof || (e.kind == MP_DESK_OUT_ROOF && e.cat == MP_PETS_MIMIMO);
            glassMimi = glassMimi || (e.kind == MP_DESK_OUT_GLASS && e.cat == MP_PETS_MIMIMO);
        }
        note("typing: Mimimo %s", roof ? "hopped onto the memo's top edge" : glassMimi ? "joined the glass" : "stayed");
        check(roof || glassMimi, "Mimimo walks your window's top edge, or joins the glass");
        MPPulse last{};
        std::uint8_t bytes[MP_PULSE_BYTES];
        pulse::Reader peek;
        if (pulse::read(section_, peek, now_, bytes) == pulse::Read::Fresh) mp_pulse_decode(bytes, sizeof bytes, &last);
        check(last.seq == 21 && last.keys == 21 && last.commits == 1 && last.graphemes == 2,
              "the slot's running counters add up across writes");
    }

    void away() {
        Counts c;
        drive(t0_ + 330'000, c);
        const MPDeskOutput &o = loop_.output();
        note("330 s: user state %u, tier %u, flags %u", o.user_state, o.tier, o.flags);
        check(o.user_state == MP_DESK_USER_AWAY && o.tier == MP_DESK_TIER_OFF, "5 minutes without input: away, tier off");
        Counts gone;
        drive(t0_ + 930'000, gone);
        note("10 minutes away: %u wakes, %u samples, %u frames, %u input checks", gone.wakes, gone.samples, gone.frames, gone.probes);
        check(gone.wakes == 0 && gone.samples == 0 && gone.frames == 0, "zero wakes while away");
        check(gone.probes >= 19 && gone.probes <= 21, "only the 30 s input check runs");
        userInput_ = now_;
        drive(now_ + kIdleProbeMs + 1000, c);
        check(loop_.output().user_state != MP_DESK_USER_AWAY && loop_.timers().sampleMs != 0, "the input check notices you are back");
    }

    ImageSource &art_;
    DeskLoop loop_;
    Snapshot snapshot_ = desktop();
    World world_;
    Frame frames_[2];
    Gait gaits_[2] = {Gait(walkCycleLength(0)), Gait(walkCycleLength(1))};
    Dwell dwell_;
    alignas(64) unsigned char section_[pulse::kSectionBytes] = {};
    pulse::Reader reader_;
    std::vector<MPDeskEventOut> taken_;
    const std::uint64_t t0_ = 1'000'000;
    std::uint64_t now_ = t0_, userInput_ = t0_, reportedInput_ = t0_, nextSample_ = 0, nextProbe_ = 0, dwellAt_ = 0;
    std::uint32_t sampleMs_ = 0;
    double px_ = 40, py_ = 40;
    bool passed_ = true;
    std::string log_;
};

} // namespace

SmokeResult runSmoke(ImageSource *images) {
    OvalImages ovals;
    Smoke smoke(images ? *images : ovals);
    return smoke.run();
}

} // namespace meowpuut::desk
