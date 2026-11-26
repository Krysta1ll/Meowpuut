#pragma once

// The word-count ledger of the Windows text service: the same device-local
// record the iOS and Android keyboards keep (crates/meowpuut-pets, mp_pets_*).
// It stores numbers only: committed character counts, the local days typed on,
// the size band of each day, which cats were around, the part of the day and
// where the 字景 journey had got to that day (a 4-bit place, worked out from the
// character count on the flush). Never typed text, an app or executable name,
// or a timestamp.
//
// File: %LOCALAPPDATA%\Meowpuut\PetJournal.bin (2884 bytes, fixed length) with
// PetJournal.lock beside it, shared with every host process the DLL is loaded
// into. A process holds one ledger; it writes at most once a minute while
// typing, on Deactivate, and when a context it typed in goes away. No threads,
// no timers; nothing here runs in DllMain.
//
// The desk-pet process (MeowpuutDesk.exe) keeps one too: the touches on the
// desk cats (petted, purr, dropped) go into the same file by the same rules.
//
// The day's place needs the 字景 journey (mp_glyph_place_of_characters): it is
// recorded when this file is built with MEOWPUUT_GLYPH against meowpuut-ffi
// with the `glyph` feature (the text service). The desk-pet process builds it
// without, and types nothing, so it records no place.
//
// Portable C++17: the Windows file layer (CreateFileW/LockFileEx/MoveFileExW)
// and a POSIX one (flock/rename) sit behind the same class so host tests run.
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string_view>
#include <vector>

struct MPPets;

namespace meowpuut {

// Grapheme clusters of UTF-8 text without ICU. Approximates the extended
// grapheme clusters iOS and Android count: a code point is not counted when
// it extends the previous cluster (combining marks, variation selectors, ZWJ
// and the code point after it, emoji skin tones, tag characters, the second
// of a regional-indicator pair, LF after CR). Exact for CJK, kana, Latin and
// common emoji; Hangul jamo sequences and rarer scripts' spacing marks count
// per code point. Invalid bytes count as one each.
std::size_t countGraphemes(std::string_view utf8);

// The clock the rules want: local day index (days since 1970-01-01 in local
// time, 0 when out of range) and the local minute of the day.
struct LocalClock {
    std::uint32_t day = 0;
    std::uint32_t minute = 0;
};
// utcSeconds: Unix time; offsetSeconds: local time minus UTC (east positive).
LocalClock localClockFromUtc(std::int64_t utcSeconds, std::int32_t offsetSeconds);

struct LedgerClock {
    std::uint64_t monoMs = 0; // a monotonic clock (GetTickCount64)
    LocalClock local;
};
LedgerClock systemLedgerClock();

// A fixed-length state file several processes share (PetJournal.bin, the desk's
// Activity.bin): a lock file beside it, and every write a temporary file
// (<file>.tmp<pid>) renamed over it. The flush recipe of meowpuut.h: lock(),
// read(), add this process's delta and merge, replace(), merge the result back.
class LedgerFile {
public:
    LedgerFile(std::filesystem::path directory, const char *fileName, const char *lockName);
    ~LedgerFile();
    LedgerFile(const LedgerFile &) = delete;
    LedgerFile &operator=(const LedgerFile &) = delete;

    // Takes the lock (creating the folder), retrying for about 0.25 s: a host
    // program must not hang behind another process that holds it.
    bool lock();
    std::vector<std::uint8_t> read() const;
    bool replace(const std::uint8_t *bytes, std::size_t size) const;
    // At most 64 KiB; empty when missing, unreadable or larger.
    static std::vector<std::uint8_t> readFile(const std::filesystem::path &file);

private:
    void release();
    std::filesystem::path directory_, file_;
    const char *lockName_;
#ifdef _WIN32
    void *lock_ = nullptr; // the lock file's HANDLE while held
#else
    int lock_ = -1;
#endif
};

#ifdef _WIN32
// True in an AppContainer process, or when that cannot be told: nothing is
// recorded there, and the text service sends no pulse to the desk-pet process.
bool appContainerProcess();
#endif

class PetLedger {
public:
    static constexpr std::uint64_t kFlushIntervalMs = 60'000;

    // enabled=false makes every call a no-op (AppContainer processes).
    explicit PetLedger(std::filesystem::path directory, bool enabled = true);
    ~PetLedger();
    PetLedger(const PetLedger &) = delete;
    PetLedger &operator=(const PetLedger &) = delete;

    // The process's ledger in %LOCALAPPDATA%\Meowpuut, created on first use and
    // never destroyed. Disabled in an AppContainer process (checked once).
    static PetLedger &shared();
    static std::filesystem::path defaultDirectory();
    static constexpr const char *kFileName = "PetJournal.bin";
    static constexpr const char *kLockName = "PetJournal.lock";

    // Counts one commit of `graphemes` characters (nothing for 0). Flushes only
    // when dirty and kFlushIntervalMs have passed since this process's last flush
    // (the first interval starts at its first commit). The flush after commits
    // also records the day's place (MEOWPUUT_GLYPH builds): where the journey has
    // got to for the characters committed, on the local day of the last commit.
    void committed(std::size_t graphemes);
    void committed(std::size_t graphemes, const LedgerClock &clock);
    // A touch on a cat on the desk: MP_PETS_TAP (petted), MP_PETS_HOLD (a purr)
    // or MP_PETS_DROP; pet MP_PETS_MIMIMO or MP_PETS_MOMO. Counted and flushed
    // like a commit (anything else is ignored).
    void touched(std::uint32_t trigger, std::uint32_t pet, const LedgerClock &clock);
    // The pet state as this process has it (the file at the first use, merged
    // with every flush since): MP_PETS_STATE_BYTES, for mp_desk_adopt. Empty
    // when disabled.
    std::vector<std::uint8_t> state();
    // How many flushes merged the file back so far (the shared state may have changed).
    std::uint64_t merges();
    // Writes the unflushed increments now when there are any. True when nothing is left to write.
    bool flush();
    // Forgets the records, raises the epoch and writes that to the file.
    void clear();

    // Totals as this process's state has them (tests, never the key path).
    std::uint64_t committedGraphemes();
    std::size_t dayCount();
    // Each kept day's place, oldest day first: a place index, or MP_PETS_PLACE_UNKNOWN (tests).
    std::vector<std::uint8_t> dayPlaces();

private:
    struct Free {
        void operator()(MPPets *) const;
    };
    bool ensureLoaded();
    bool flushLocked();
    void record(std::uint32_t trigger, std::uint32_t arg, const LedgerClock &clock);
    void reachPlaceLocked();
    std::uint64_t committedGraphemesLocked();

    std::filesystem::path directory_;
    bool enabled_;
    std::mutex mutex_;
    std::unique_ptr<MPPets, Free> live_;
    bool dirty_ = false;
    // Characters were committed since the last flush: the flush records the place, on lastCommit_'s day.
    bool typed_ = false;
    LedgerClock lastCommit_;
    bool haveBase_ = false;
    std::uint64_t lastFlushMs_ = 0;
    std::uint64_t merges_ = 0;
};

} // namespace meowpuut
