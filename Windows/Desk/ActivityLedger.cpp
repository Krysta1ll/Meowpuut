#include "ActivityLedger.h"

#include "../PetLedger.h"

#include <algorithm>
#include <array>

namespace meowpuut::desk {

ActivityLedger::ActivityLedger(std::filesystem::path directory) : directory_(std::move(directory)) {}

ActivityLedger::~ActivityLedger() { unload(); }

bool ActivityLedger::load() {
    if (live_) return true;
    if (directory_.empty()) return false;
    const auto bytes = LedgerFile::readFile(directory_ / kFileName);
    int status = 0;
    live_ = mp_activity_from_bytes(bytes.data(), bytes.size(), &status);
    dirty_ = haveBase_ = false;
    applyHidden();
    return live_ != nullptr;
}

void ActivityLedger::setHidden(const std::vector<std::string> &apps) {
    hidden_ = apps;
    applyHidden();
}

void ActivityLedger::applyHidden() {
    if (!live_) return;
    std::vector<MPActivityRule> rules;
    for (const std::string &app : hidden_) {
        if (app.empty() || rules.size() >= MP_ACTIVITY_MAX_RULES) continue;
        MPActivityRule r{};
        r.id = app.c_str();
        r.target = MP_ACTIVITY_HIDDEN;
        rules.push_back(r);
    }
    // A name that is not UTF-8 makes the whole list invalid: keep the ones that are.
    if (mp_activity_set_rules(live_, rules.data(), rules.size()) == MP_ACTIVITY_OK) return;
    std::vector<MPActivityRule> good;
    for (const MPActivityRule &r : rules)
        if (mp_activity_classify(r.id, &r, 1) == MP_ACTIVITY_HIDDEN) good.push_back(r);
    mp_activity_set_rules(live_, good.data(), good.size());
}

bool ActivityLedger::step(const MPActivityInput &in, MPActivityStepOut &out) {
    if (!live_) return false;
    MPActivityStepOut o{};
    if (mp_activity_step(live_, &in, &o, sizeof o) != MP_ACTIVITY_OK) return false;
    out = o;
    const std::uint64_t now = in.clock.mono_ms;
    if (o.minutes > 0) {
        dirty_ = true;
        if (!haveBase_) {
            // The first interval starts at the first minute counted.
            haveBase_ = true;
            lastFlushMs_ = now;
        }
    }
    if (dirty_ && now >= lastFlushMs_ && now - lastFlushMs_ >= kFlushIntervalMs) {
        lastFlushMs_ = now; // a failed write is tried again after the next interval
        flush();
    }
    return true;
}

// The pet ledger's recipe (meowpuut.h, Activity): under the file lock read the
// file, add this process's delta, merge in the live state, write a temporary
// file, rename it over the file, then merge the result back.
bool ActivityLedger::flush() {
    if (!live_ || !dirty_) return true;
    LedgerFile store(directory_, kFileName, kLockName);
    if (!store.lock()) return false;
    const auto onDisk = store.read();
    int status = 0;
    MPActivity *disk = mp_activity_from_bytes(onDisk.data(), onDisk.size(), &status);
    std::array<std::uint8_t, MP_ACTIVITY_DELTA_BYTES> delta{};
    std::vector<std::uint8_t> state(MP_ACTIVITY_STATE_BYTES), merged(MP_ACTIVITY_STATE_BYTES);
    if (!disk || mp_activity_take_delta(live_, delta.data(), delta.size()) != delta.size() ||
        mp_activity_to_bytes(live_, state.data(), state.size()) != state.size()) {
        mp_activity_free(disk);
        return false;
    }
    dirty_ = false;
    mp_activity_apply_delta(disk, delta.data(), delta.size());
    mp_activity_merge(disk, state.data(), state.size());
    const bool written = mp_activity_to_bytes(disk, merged.data(), merged.size()) == merged.size() &&
                         store.replace(merged.data(), merged.size());
    mp_activity_free(disk);
    if (!written) {
        dirty_ = true; // nothing new to add, but the live state still has to reach the file
        return false;
    }
    mp_activity_merge(live_, merged.data(), merged.size());
    return true;
}

void ActivityLedger::unload() {
    if (!live_) return;
    flush();
    mp_activity_free(live_);
    live_ = nullptr;
    dirty_ = haveBase_ = false;
}

MPActivityCurrent ActivityLedger::current() const {
    MPActivityCurrent c{};
    c.state = MP_ACTIVITY_NOW_OFF;
    c.category = MP_ACTIVITY_NO_CATEGORY;
    if (live_ && mp_activity_current(live_, &c, sizeof c) != MP_ACTIVITY_OK) {
        c = MPActivityCurrent{};
        c.category = MP_ACTIVITY_NO_CATEGORY;
    }
    return c;
}

std::vector<MPActivityDay> ActivityLedger::days() const {
    if (!live_) return {};
    std::vector<MPActivityDay> out(MP_ACTIVITY_DAYS);
    const std::size_t n = mp_activity_days(live_, out.data(), out.size());
    out.resize(std::min(n, out.size()));
    return out;
}

} // namespace meowpuut::desk
