#pragma once
// 「記錄時間花在哪裡」 (FINAL.md §11): the desk-pet process senses the
// foreground app and whether you are there, and feeds the time ledger
// (ActivityLedger, mp_activity_*). Steam version only; off by default.
//
// What it senses, and only while the switch is on:
//   - the foreground app, on the EVENT_SYSTEM_FOREGROUND hook the desk already
//     has: the lower-case image *base name* of the window's process
//     ("code.exe"), or its full image path when the path decides the category
//     (a game under ...\steamapps\common\...). It stays in memory for the
//     tracker's steps while that app is in front and is never written anywhere.
//     A UWP app framed by ApplicationFrameHost.exe is the process of the
//     frame's child window from another process; when there is none, the frame
//     itself, which counts as "other". Never a window title, a class name or
//     an address.
//   - the app's display name, only while the share-app-name switch
//     (ActivityShareAppName, §11.1 「顯示 App 名稱」) is on: the exe's version
//     resource FileDescription, else its file name without ".exe". Kept in
//     memory, handed to the core, never written to the ledger; never a path.
//   - idle: GetLastInputInfo, at the rates the desk already samples (10 Hz /
//     2 Hz with a cat awake / asleep), plus a coalescable 60 s tick while
//     minutes are being counted and a coalescable 30 s input check while you
//     are away (the tracker's own schedule, schedule()).
//   - full screen: the desk's own check (DeskWorld.h coversMonitor and the
//     shell's notification state).
// Turned off, it does nothing at all: the hook skips it, no process is
// queried, no timer runs, the ledger is flushed and dropped from memory.
//
// Settings, HKCU\Software\Meowpuut (DeskSettings.h): ActivityStats (DWORD,
// default 0), ActivityPaused (DWORD 0/1), ActivityHidden (REG_MULTI_SZ, exe
// names the user chose not to record), ActivityShareAppName (DWORD 0/1, the
// companion's share-app-name gate: 1 exactly while sharing AND 「顯示 App 名稱」 are on).
//
// The policy is portable (host tests); appIdentifier() is Win32.
#include "ActivityLedger.h"

#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace meowpuut::desk {

struct ActivitySwitches {
    bool on = false;                 // ActivityStats
    bool paused = false;             // ActivityPaused
    std::vector<std::string> hidden; // ActivityHidden: exe names, UTF-8
    bool shareAppName = false;       // ActivityShareAppName: the display name may be read and published
    bool operator==(const ActivitySwitches &o) const {
        return on == o.on && paused == o.paused && hidden == o.hidden && shareAppName == o.shareAppName;
    }
    bool operator!=(const ActivitySwitches &o) const { return !(*this == o); }
};

class ForegroundWatcher {
public:
    // How often the shell checks for input while you are away (DeskLoop.h kIdleProbeMs).
    static constexpr std::uint32_t kProbeMs = 30'000;

    explicit ForegroundWatcher(std::filesystem::path directory) : ledger_(std::move(directory)) {}

    // The switch is on: the shell senses the foreground app for it.
    bool enabled() const { return switches_.on; }
    // The switches changed (and once at start). Turned on: the ledger is read;
    // the shell then reports the app in front (foreground). Turned off: one
    // last step credits the minutes counted while it was on, the ledger is
    // written and dropped, the app forgotten; nothing runs after that.
    void configure(const ActivitySwitches &switches, const MPPetsClock &now, std::uint64_t idleMs);
    // The shell must read the app's display name (appIdentifier's out-parameter) and pass it
    // to foreground(): recording and the share-app-name switch are both on.
    bool wantsAppName() const { return switches_.on && switches_.shareAppName; }
    // The app in front changed: its identifier (appIdentifier), "" for none, and its display
    // name (ignored and forgotten unless wantsAppName()). Called again with the same app and a
    // name when the switch was just turned on, which fills the name at once.
    void foreground(std::string app, const MPPetsClock &now, std::uint64_t idleMs, std::string name = {});
    void fullScreen(bool on, const MPPetsClock &now, std::uint64_t idleMs);
    // An idle reading (the shell's pointer sampling, or a timer below). Steps
    // when the tracker's tick is due, or when it waited for input and there was some.
    void sample(const MPPetsClock &now, std::uint64_t idleMs);

    struct Schedule {
        std::uint64_t tickAt = 0; // monotonic ms of the next tick, 0: none
        bool probe = false;       // away: check for input every kProbeMs
    };
    Schedule schedule() const;
    // Writes what was counted (you went away, the desk quits, the machine sleeps).
    void flush();

    // What the companion's Steam presence may know: state, category, full
    // screen, and app: the display name only while wantsAppName() and the state is ACTIVE
    // (the core leaves it "" otherwise, also for a hidden app), else "". OFF while off.
    const MPActivityCurrent &current() const { return current_; }
    // Changes whenever current() changed (the shell copies it to the section).
    std::uint64_t changes() const { return changes_; }
    const ActivityLedger &ledger() const { return ledger_; }
    std::uint64_t steps() const { return steps_; } // tests

private:
    void stepNow(const MPPetsClock &now, std::uint64_t idleMs);
    void publish(MPActivityCurrent next);

    ActivityLedger ledger_;
    ActivitySwitches switches_;
    std::string app_;  // the app in front, in memory only
    std::string name_; // its display name, in memory only, "" unless wantsAppName()
    bool fullScreen_ = false, waitingForInput_ = false;
    std::uint32_t lastState_ = MP_ACTIVITY_NOW_OFF;
    std::uint64_t lastStepMs_ = 0, tickAt_ = 0, changes_ = 0, steps_ = 0;
    MPActivityCurrent current_ = [] {
        MPActivityCurrent c{};
        c.category = MP_ACTIVITY_NO_CATEGORY;
        return c;
    }();
};

#ifdef _WIN32
// The identifier of the app a window belongs to, as described above; "" for
// none (no window, or one of this process). With displayName: also the app's
// display name (FileDescription, else the exe file name without ".exe"; "" for a
// frame without an app or an unreadable process). Only pass it while wantsAppName().
std::string appIdentifier(HWND window, std::string *displayName = nullptr);
#endif

} // namespace meowpuut::desk
