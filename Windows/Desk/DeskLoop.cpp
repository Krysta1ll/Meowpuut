#include "DeskLoop.h"

#include <algorithm>
#include <cstring>

namespace meowpuut::desk {

DeskLoop::DeskLoop(const MPDeskSettings &settings, std::uint32_t seed)
    : settings_(settings), seed_(seed), desk_(mp_desk_create(&settings, seed)) {
    taken_.reserve(64);
}

DeskLoop::~DeskLoop() { mp_desk_free(desk_); }

int DeskLoop::setSettings(const MPPetsClock &clock, const MPDeskSettings &settings) {
    const int r = mp_desk_set_settings(desk_, &clock, &settings);
    if (r == MP_DESK_OK) settings_ = settings;
    return r;
}

int DeskLoop::adopt(const std::vector<std::uint8_t> &petState) {
    if (petState.size() != MP_PETS_STATE_BYTES) return MP_DESK_E_DATA;
    const int r = mp_desk_adopt(desk_, petState.data(), petState.size());
    if (r == MP_DESK_OK) adopted_ = petState;
    return r;
}

void DeskLoop::setWorld(const World &world) {
    world_ = world;
    worldPending_ = worldsSent_ == 0 || !sameWorld(world_, sent_);
}

void DeskLoop::addPulse(const std::uint8_t *bytes, std::uint32_t caretOwner) {
    if (!bytes) return;
    // Counters are running totals: when more pile up than a step takes, the newest count.
    if (pulses_.size() >= MP_DESK_MAX_PULSES) pulses_.erase(pulses_.begin());
    MPDeskPulse p{};
    std::memcpy(p.bytes, bytes, MP_PULSE_BYTES);
    p.caret_owner = caretOwner;
    pulses_.push_back(p);
}

void DeskLoop::addEvent(std::uint32_t kind, std::uint32_t cat, std::uint32_t arg, double x, double y) {
    if (events_.size() >= MP_DESK_MAX_EVENTS) events_.erase(events_.begin());
    MPDeskEvent e{};
    e.kind = kind;
    e.cat = cat;
    e.arg = arg;
    e.x = x;
    e.y = y;
    events_.push_back(e);
}

int DeskLoop::step(const MPPetsClock &clock) {
    if (!desk_) return MP_DESK_E_NULL;
    MPDeskInput in{};
    in.struct_size = sizeof in;
    const bool sendWorld = worldPending_;
    if (sendWorld) {
        in.flags = MP_DESK_WORLD_SET;
        in.grounds = world_.grounds.empty() ? nullptr : world_.grounds.data();
        in.ground_count = std::min<std::size_t>(world_.grounds.size(), MP_DESK_MAX_GROUNDS);
        in.sides = world_.sides.empty() ? nullptr : world_.sides.data();
        in.side_count = std::min<std::size_t>(world_.sides.size(), MP_DESK_MAX_SIDES);
        in.doors = world_.doors;
        in.walls = world_.walls;
    }
    in.pulses = pulses_.empty() ? nullptr : pulses_.data();
    in.pulse_count = pulses_.size();
    in.events = events_.empty() ? nullptr : events_.data();
    in.event_count = events_.size();
    taken_.assign(64, MPDeskEventOut{});
    in.events_out = taken_.data();
    in.events_out_capacity = taken_.size();
    MPDeskOutput out{};
    const int r = mp_desk_step(desk_, &clock, &in, &out, sizeof out);
    pulses_.clear();
    events_.clear();
    if (r != MP_DESK_OK) {
        taken_.clear();
        if (r == MP_DESK_E_PANIC) {
            // A contained failure: start over (the cats are at home again) and resend the world.
            mp_desk_free(desk_);
            desk_ = mp_desk_create(&settings_, ++seed_);
            if (!adopted_.empty()) mp_desk_adopt(desk_, adopted_.data(), adopted_.size());
            worldPending_ = true;
            worldsSent_ = 0;
        }
        return r;
    }
    out_ = out;
    taken_.resize(std::min<std::size_t>(out.event_count, taken_.size()));
    if (sendWorld) {
        sent_ = world_;
        worldPending_ = false;
        ++worldsSent_;
    }
    return r;
}

Timers DeskLoop::timers() const {
    Timers t;
    t.frames = (out_.flags & MP_DESK_OUT_FRAMES) != 0;
    t.wake = (out_.flags & MP_DESK_OUT_HAS_WAKE) != 0;
    t.wakeAt = out_.wake_at_ms;
    t.sampleMs = out_.tier == MP_DESK_TIER_AWAKE ? kAwakeSampleMs : out_.tier == MP_DESK_TIER_DROWSY ? kDrowsySampleMs : 0;
    t.idleProbe = (out_.flags & MP_DESK_OUT_IS_HIDDEN) == 0 && out_.tier == MP_DESK_TIER_OFF &&
                  out_.user_state == MP_DESK_USER_AWAY && deskCats() != 0;
    return t;
}

std::uint32_t DeskLoop::deskCats() const {
    std::uint32_t bits = 0;
    for (int i = 0; i < 2; ++i) {
        const std::uint8_t b = out_.cats[i].body;
        if (b == MP_DESK_BODY_GROUND || b == MP_DESK_BODY_AIR || b == MP_DESK_BODY_HELD) bits |= 1u << i;
    }
    return bits;
}

std::size_t recordTouches(const std::vector<MPDeskEventOut> &taken, PetLedger &ledger, const LedgerClock &clock) {
    std::size_t n = 0;
    for (const MPDeskEventOut &e : taken) {
        const std::uint32_t trigger = e.kind == MP_DESK_OUT_PETTED ? static_cast<std::uint32_t>(MP_PETS_TAP)
                                      : e.kind == MP_DESK_OUT_PURR ? static_cast<std::uint32_t>(MP_PETS_HOLD)
                                      : e.kind == MP_DESK_OUT_DROP ? static_cast<std::uint32_t>(MP_PETS_DROP)
                                                                   : 0u;
        if (!trigger || (e.cat != MP_PETS_MIMIMO && e.cat != MP_PETS_MOMO)) continue;
        ledger.touched(trigger, e.cat, clock);
        ++n;
    }
    return n;
}

DwellStep Dwell::sample(std::uint64_t now, std::uint32_t over, bool buttonDown, bool typing, bool clicksAllowed) {
    DwellStep step;
    if (captured_) return step;
    if (!clicksAllowed || typing || (buttonDown && armed_ == 0) || over == 0) {
        candidate_ = 0;
        step.disarm = armed_ != 0;
        armed_ = 0;
        return step;
    }
    if (armed_ == over) return step;
    if (armed_ != 0) {
        step.disarm = true;
        armed_ = 0;
    }
    if (over != candidate_) {
        candidate_ = over;
        since_ = now;
        step.checkAt = now + kDwellMs;
        return step;
    }
    if (now - since_ >= kDwellMs) {
        armed_ = over;
        step.arm = over;
        return step;
    }
    step.checkAt = since_ + kDwellMs;
    return step;
}

DwellStep Dwell::reset() {
    DwellStep step;
    step.disarm = armed_ != 0;
    armed_ = candidate_ = 0;
    captured_ = false;
    return step;
}

} // namespace meowpuut::desk
