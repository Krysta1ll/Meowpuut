#pragma once
// The desk-pet process's loop around the behaviour core (mp_desk_step,
// Engine desk.rs), without Win32, so the smoke test runs on any machine.
//
// The shell measures, forwards and draws; it crosses into the core once per
// wake with everything that happened since the last one: the world (only when
// it changed), the pulses, the input events. After each step it arms what
// timers() says and nothing else (FINAL.md §4.6 item 3):
//   frames     draw every display frame (a cat is held, airborne or fading);
//   wake       one one-shot timer at the core's next discrete change;
//   sampleMs   pointer and world sampling: 100 ms while a cat on the desk is
//              awake, 500 ms while every cat sleeps and you are here, none
//              otherwise;
//   idleProbe  you are away (5 minutes without input): no timer but one
//              coalescable 30 s GetLastInputInfo check, to notice you are back.
// Hidden (full screen, a game) means no timer at all: the shell's foreground
// hook brings it back.
#include "../PetLedger.h"
#include "DeskWorld.h"

#include <cstdint>
#include <vector>

namespace meowpuut::desk {

inline constexpr std::uint32_t kDwellMs = 250;        // desk.rs DWELL_MS
inline constexpr std::uint32_t kAwakeSampleMs = 100;  // desk.rs AWAKE_SAMPLE_MS
inline constexpr std::uint32_t kDrowsySampleMs = 500; // desk.rs DROWSY_SAMPLE_MS
inline constexpr std::uint32_t kIdleProbeMs = 30'000;
inline constexpr std::uint32_t kFrameMs = 16;

struct Timers {
    bool frames = false;
    bool wake = false;
    std::uint64_t wakeAt = 0;   // monotonic ms
    std::uint32_t sampleMs = 0;
    bool idleProbe = false;
};

class DeskLoop {
public:
    DeskLoop(const MPDeskSettings &settings, std::uint32_t seed);
    ~DeskLoop();
    DeskLoop(const DeskLoop &) = delete;
    DeskLoop &operator=(const DeskLoop &) = delete;
    bool valid() const { return desk_ != nullptr; }

    int setSettings(const MPPetsClock &clock, const MPDeskSettings &settings);
    // Today's shared limits (moments, duets, the greeting) from the pet ledger's
    // state (MP_PETS_STATE_BYTES): at start and after every flush that merged
    // the file. Kept, and handed again to a core made anew after a panic.
    int adopt(const std::vector<std::uint8_t> &petState);
    // Kept; handed to the core with the next step only when it differs from
    // what the core has.
    void setWorld(const World &world);
    const World &world() const { return world_; }
    // A pulse as the slot held it; caretOwner: the window holding its origin.
    void addPulse(const std::uint8_t *bytes, std::uint32_t caretOwner);
    void addEvent(std::uint32_t kind, std::uint32_t cat = 0, std::uint32_t arg = 0, double x = 0, double y = 0);
    // One step: MP_DESK_OK, or the core's error (the inputs are dropped; after
    // MP_DESK_E_PANIC the core is created anew and gets the world again).
    int step(const MPPetsClock &clock);

    const MPDeskOutput &output() const { return out_; }
    const std::vector<MPDeskEventOut> &taken() const { return taken_; }
    Timers timers() const;
    // Bit 0 Mimimo, bit 1 Momo: on the desktop (not at home, not on the glass).
    std::uint32_t deskCats() const;
    std::uint32_t worldsSent() const { return worldsSent_; }

private:
    MPDeskSettings settings_;
    std::uint32_t seed_;
    MPDesk *desk_ = nullptr;
    World world_, sent_;
    bool worldPending_ = true;
    std::uint32_t worldsSent_ = 0;
    std::vector<MPDeskPulse> pulses_;
    std::vector<MPDeskEvent> events_;
    std::vector<MPDeskEventOut> taken_;
    std::vector<std::uint8_t> adopted_;
    MPDeskOutput out_{};
};

// The touches the core took, into the pet ledger by its rules (meowpuut.h:
// "Feed PETTED/DROP/PURR ... to the ledger"): PETTED as MP_PETS_TAP, PURR as
// MP_PETS_HOLD, DROP as MP_PETS_DROP. The ledger batches its writes (at most
// one a minute while events come; the shell flushes when you go away and when
// it quits). Returns how many went in.
std::size_t recordTouches(const std::vector<MPDeskEventOut> &taken, PetLedger &ledger, const LedgerClock &clock);

// The dwell (PETS.md §2.5, FINAL.md §4.6 item 5): the pointer rests kDwellMs
// on a cat's opaque pixels before that cat's window takes the mouse; it goes
// back to click-through as soon as the pointer leaves those pixels. Clicks
// stay off while a button is down (you drag a window or select text), while
// you type, and with "click-through always".
struct DwellStep {
    std::uint32_t arm = 0;       // MP_PETS_MIMIMO / MP_PETS_MOMO: make it clickable, send MP_DESK_EV_NOTICE
    bool disarm = false;         // the clickable cat goes back to click-through
    std::uint64_t checkAt = 0;   // sample again then (0: no need)
};

class Dwell {
public:
    // over: the cat whose opaque pixels are under the pointer (0: none).
    DwellStep sample(std::uint64_t nowMs, std::uint32_t over, bool buttonDown, bool typing, bool clicksAllowed);
    // The clickable window holds the mouse (press to release): no changes meanwhile.
    void capture(bool on) { captured_ = on; }
    bool captured() const { return captured_; }
    std::uint32_t armed() const { return armed_; }
    DwellStep reset();

private:
    std::uint32_t candidate_ = 0, armed_ = 0;
    std::uint64_t since_ = 0;
    bool captured_ = false;
};

} // namespace meowpuut::desk
