#pragma once
// The pulse channel: how the input method tells the desk-pet process (貓窩,
// MeowpuutDesk.exe) that you typed (FINAL.md §4.6 items 1–2, r2-eng §8.4).
//
// One small shared-memory section and one auto-reset event, both inside a
// private namespace whose boundary descriptor holds the user's SID and the
// Medium integrity label. Every object carries the DACL "this user only" and
// the mandatory label S:(ML;;NRNWNX;;;ME), so low-integrity and AppContainer
// processes can neither read the slot nor create the names first. There is no
// ALL APPLICATION PACKAGES entry, ever; the input method does not even try to
// send from an AppContainer process or in secure mode.
//
// The desk creates the namespace, the section and the event (Server); each
// process the text service is loaded into opens them lazily on its first key
// (Client) and retries at most every kRetryMs while the desk is not running.
//
// Section layout, version 2, kSectionBytes long, little endian:
//
//   offset  size  field
//   0       4     magic "MPPS" (written last by the desk: the section is ready)
//   4       2     layout version (2)
//   6       2     section size (512)
//   8       4     unitDpi: the desk's point is a physical pixel × 96 / unitDpi (the primary monitor's DPI)
//   12      4     deskCats: bit 0 Mimimo, bit 1 Momo are out on the desktop (for the candidate window's seats);
//                 0 while the desk is hidden (full screen) and after it quits
//   16      48    reserved, 0
//   64      4     seq: seqlock word, odd while a writer holds the slot
//   68      36    the pulse, as mp_pulse_encode writes it (Engine pulse.rs)
//   104     24    reserved, 0
//   128     4     nowSeq: seqlock word of the field below, odd while the desk writes it
//   132     4     reserved, 0
//   136     272   now: an MPActivityCurrent as is (FINAL.md §11, for the companion app's Steam
//                 presence): state, category, flags (full screen), reserved, app[256]. Only the
//                 desk writes it, only while 「記錄時間花在哪裡」 is on (otherwise state OFF and
//                 everything else 0). app, the one text, is the display name only while the
//                 share-app-name switch allows it (the registry value ActivityShareAppName, which the companion
//                 sets only while sharing and 「顯示 App 名稱」 are on), and only while the state is
//                 ACTIVE; otherwise "".
//                 Never an executable name, a path or a window title.
//   408     104   reserved, 0
//
// Who can read it: the section's DACL and label (below) let only this user's
// processes at Medium integrity or higher open it.
//
// The pulse's running counters live in the slot, not in each process: a
// writer decodes the pulse it finds, adds its own keys, commits and graphemes
// and writes the result with seq + 1, so the counters and seq keep growing
// whichever program you type in. Writers take the slot with one compare-
// exchange and drop the pulse when another writer holds it (never wait); a
// torn read fails the pulse checksum anyway. The desk resets a seq left odd
// for kStuckMs by a writer that died inside the slot.
//
// The portable part (names, layout, the seqlock, the pulse arithmetic) builds
// on any platform for the host tests; Server and Client are Windows only.
#include "../../Engine/crates/meowpuut-ffi/include/meowpuut.h"

#include <cstddef>
#include <cstdint>
#include <string>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace meowpuut::pulse {

// Names. The namespace alias prefixes every object name ("Meowpuut\PulseSlot.v2").
// A layout change gets new object names, so an old DLL that lives on in a long-running
// program writes into an old slot nobody reads instead of corrupting the new one.
struct Names {
    const wchar_t *boundary; // boundary descriptor name
    const wchar_t *alias;    // private namespace alias
    const wchar_t *slot;     // section
    const wchar_t *signal;   // auto-reset event
    const wchar_t *instance; // mutex: one desk per session
};
inline constexpr Names kNames{L"Meowpuut.Desk", L"Meowpuut", L"Meowpuut\\PulseSlot.v2", L"Meowpuut\\PulseSignal.v2",
                              L"Meowpuut\\DeskInstance"};
// The desk's --smoke uses its own names, so it runs beside a real desk.
inline constexpr Names kSmokeNames{L"Meowpuut.Desk.Smoke", L"MeowpuutSmoke", L"MeowpuutSmoke\\PulseSlot.v2",
                                   L"MeowpuutSmoke\\PulseSignal.v2", L"MeowpuutSmoke\\DeskInstance"};

// SDDL for the namespace and its objects: generic all for the user, nothing for
// anyone else, and the Medium mandatory label with no-read-up, no-write-up and
// no-execute-up. `userSid` is the string form ("S-1-5-21-…"); empty when it is
// not a SID string.
std::wstring securityDescriptor(const std::wstring &userSid);

inline constexpr std::uint32_t kMagic = 0x5350504Du; // "MPPS"
inline constexpr std::uint16_t kVersion = 2;
inline constexpr std::size_t kSectionBytes = 512;
inline constexpr std::size_t kOffMagic = 0, kOffVersion = 4, kOffUnitDpi = 8, kOffDeskCats = 12, kOffSeq = 64,
                             kOffPulse = 68, kOffNowSeq = 128, kOffNow = 136;
inline constexpr std::uint32_t kMinDpi = 96, kMaxDpi = 960;
inline constexpr std::uint64_t kRetryMs = 3000; // the IME tries to open the channel at most this often
inline constexpr std::uint64_t kStuckMs = 1000; // a writer holding the slot longer than this is gone

// One pulse's worth of what the IME saw since its last pulse. Positions are in
// physical screen pixels; the writer turns them into desk points with the
// section's unitDpi and quantizes them (mp_pulse_origin_measure), so neither
// the exact caret nor the candidate window's size leaves the IME.
struct Input {
    std::uint32_t keys = 0;      // keys pressed (never which)
    std::uint32_t commits = 0;   // commits
    std::uint32_t graphemes = 0; // committed graphemes (the ledger's count)
    bool composing = false;
    bool haveOrigin = false;     // the composition origin is known
    double originX = 0, originY = 0, lineHeight = 0;
    bool expanded = false;       // the candidate window is expanded
    bool above = false;          // it opened above the line
};

// The pulse that follows `previous` (nullptr: none or unreadable) after `in`.
// Counters add with wrap-around, seq + 1; display is 0 (unknown: the desk finds
// the screen from the origin). The origin is used only with a valid unitDpi.
MPPulse next(const MPPulse *previous, const Input &in, std::uint32_t unitDpi);

// The section, as raw memory of at least kSectionBytes, aligned to 8 bytes.
// The desk: makes it ready (keeps a valid slot) and keeps its fields current.
void initialize(void *section, std::uint32_t unitDpi);
bool ready(const void *section);
std::uint32_t unitDpi(const void *section); // 0 when not ready or out of range
void setUnitDpi(void *section, std::uint32_t dpi);
void setDeskCats(void *section, std::uint32_t cats);
std::uint32_t deskCats(const void *section);
// The cats the candidate window seats on its glass (FINAL.md §4.2, 乙): Momo
// always (she is on the glass from the first key, drawn by the IME without
// waiting for the desk); Mimimo unless he is out on the desk. deskCats: the
// section's field, 0 when the desk is not running (both on the glass).
std::uint32_t glassCats(std::uint32_t deskCats);
// The desk quits: no cat is on the desk any more and nothing is being recorded.
void retire(void *section);

// The "now" field (the desk writes, the companion app reads; seqlock).
void setNow(void *section, const MPActivityCurrent &now);
// False while the desk is writing it (try again) or when the section is not ready.
bool now(const void *section, MPActivityCurrent &out);

enum class Write { Written, Busy, NotReady };
// The IME's write: never waits, never allocates.
Write write(void *section, const Input &in) noexcept;

enum class Read { Fresh, Same, Busy, NotReady };
struct Reader {
    std::uint32_t last = 0;        // seq of the last pulse taken
    std::uint32_t stuck = 0;       // an odd seq seen …
    std::uint64_t stuckSince = 0;  // … since then (the desk's monotonic ms)
};
// The desk's read: Fresh copies a new pulse (MP_PULSE_BYTES) into `out`.
Read read(void *section, Reader &reader, std::uint64_t nowMs, std::uint8_t *out) noexcept;

#ifdef _WIN32

// The desk's side. Opens the namespace (or the one IMEs still hold from an
// earlier desk), the single-instance mutex, the section and the event. The
// desk's thread owns the mutex for its life: an input method that opens it for
// a moment (Client::glassCats) never makes a starting desk think another one
// runs, and a desk that died leaves it abandoned, which the next one takes.
class Server {
public:
    enum class Open { Ok, AlreadyRunning, Failed };
    explicit Server(const Names &names = kNames) : names_(names) {}
    ~Server();
    Server(const Server &) = delete;
    Server &operator=(const Server &) = delete;

    Open open(std::uint32_t unitDpi);
    HANDLE signal() const { return signal_; } // wait on it; auto-reset
    Read take(std::uint64_t nowMs, std::uint8_t *out) noexcept;
    void setUnitDpi(std::uint32_t dpi);
    void setDeskCats(std::uint32_t cats);
    void setNow(const MPActivityCurrent &now);
    void *section() const { return view_; }

private:
    void close();
    Names names_;
    HANDLE boundary_ = nullptr, namespace_ = nullptr, instance_ = nullptr, section_ = nullptr, signal_ = nullptr;
    void *view_ = nullptr;
    Reader reader_;
};

// The input method's side: one per process.
class Client {
public:
    static Client &shared();
    explicit Client(const Names &names = kNames) : names_(names) {}
    ~Client();
    Client(const Client &) = delete;
    Client &operator=(const Client &) = delete;

    // Once per process, off the key path (it allocates the boundary descriptor).
    // allowed = false (an AppContainer process) makes send() a no-op for good.
    void prepare(bool allowed) noexcept;
    // Writes one pulse and wakes the desk. Never blocks and never allocates:
    // while the channel is not open it tries to open it at most every kRetryMs,
    // and a slot another writer holds drops this pulse.
    bool send(const Input &in) noexcept;
    // The cats the candidate window seats (glassCats()): both until the channel
    // is open, and both when the desk that set deskCats is gone (it crashed
    // before clearing it). Never blocks: while Mimimo is out, whether the desk
    // still runs is checked at most every kRetryMs (the instance mutex exists).
    std::uint32_t glassCats() noexcept;

private:
    bool tryOpen() noexcept;
    void *openView() noexcept; // the mapped section, opening it when allowed and due; nullptr otherwise
    bool deskRunning() noexcept;
    Names names_;
    SRWLOCK lock_ = SRWLOCK_INIT;
    bool prepared_ = false, allowed_ = false;
    HANDLE boundary_ = nullptr, namespace_ = nullptr, section_ = nullptr, signal_ = nullptr;
    void *view_ = nullptr;          // set under lock_, read through ready_
    void *volatile ready_ = nullptr; // published with a release store (InterlockedExchangePointer)
    volatile LONG64 nextTry_ = 0;
    volatile LONG64 nextAliveCheck_ = 0;
    volatile LONG running_ = 1;
};

#endif

} // namespace meowpuut::pulse
