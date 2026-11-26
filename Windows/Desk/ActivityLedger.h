#pragma once
// The time ledger of FINAL.md §11 (「記錄時間花在哪裡」, Steam version, off by
// default): which local day, which of ten categories, how many active minutes,
// and the parts of the day (crates/meowpuut-activity, mp_activity_*). The
// format has no room for an app name, an identifier, a window title, an
// address or a time of day; the tracker classifies the identifier it is handed
// and drops it.
//
// File: %LOCALAPPDATA%\Meowpuut\Activity.bin (12,824 bytes, fixed length) with
// Activity.lock beside it, written exactly like PetJournal.bin (LedgerFile:
// lock, read, apply this process's delta, merge, temporary file, rename, merge
// the result back). Only the desk-pet process writes it: at most every
// kFlushIntervalMs while minutes are being added, and when you go away, when
// recording is turned off and when the desk quits. Nothing is read or written
// while recording is off.
//
// Portable C++17 (the host tests run it against the Rust library).
#include "../../Engine/crates/meowpuut-ffi/include/meowpuut.h"

#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace meowpuut::desk {

class ActivityLedger {
public:
    static constexpr const char *kFileName = "Activity.bin";
    static constexpr const char *kLockName = "Activity.lock";
    static constexpr std::uint64_t kFlushIntervalMs = 5 * 60'000;

    explicit ActivityLedger(std::filesystem::path directory);
    ~ActivityLedger();
    ActivityLedger(const ActivityLedger &) = delete;
    ActivityLedger &operator=(const ActivityLedger &) = delete;

    // Reads the file into a tracker (fresh when missing or damaged). Recording
    // starts off. False when there is no folder or no handle.
    bool load();
    bool loaded() const { return live_ != nullptr; }
    // The apps the user chose not to record (「不記錄」): exe names, any case.
    void setHidden(const std::vector<std::string> &apps);
    // One tracker step (mp_activity_step); writes the file when due. False,
    // and `out` untouched, when not loaded or on an error.
    bool step(const MPActivityInput &in, MPActivityStepOut &out);
    // Writes the minutes not written yet. True when nothing is left to write.
    bool flush();
    // Flushes and forgets the tracker (recording turned off).
    void unload();
    // What the status line may show now; OFF when not loaded.
    MPActivityCurrent current() const;
    // The days as this process has them, oldest first (tests, the companion's page later).
    std::vector<MPActivityDay> days() const;

private:
    void applyHidden();
    std::filesystem::path directory_;
    MPActivity *live_ = nullptr;
    std::vector<std::string> hidden_;
    bool dirty_ = false, haveBase_ = false;
    std::uint64_t lastFlushMs_ = 0;
};

} // namespace meowpuut::desk
