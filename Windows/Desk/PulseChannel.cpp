#include "PulseChannel.h"

#include <atomic>
#include <cstddef>
#include <cstring>

#ifdef _WIN32
#include <sddl.h>
#endif

namespace meowpuut::pulse {
namespace {

static_assert(sizeof(std::atomic<std::uint32_t>) == 4 && alignof(std::atomic<std::uint32_t>) == 4,
              "the section is read as 32-bit atomic words");
static_assert(std::atomic<std::uint32_t>::is_always_lock_free, "the section is shared between processes");
static_assert(MP_PULSE_BYTES == 36 && kOffPulse + MP_PULSE_BYTES <= kSectionBytes, "the pulse fits its slot");
static_assert(kOffPulse % 4 == 0 && kOffSeq % 64 == 0, "the slot starts a cache line");
static_assert(sizeof(MPActivityCurrent) == MP_ACTIVITY_CURRENT_BYTES && sizeof(MPActivityCurrent) % 4 == 0,
              "the now field is an MPActivityCurrent as is");
static_assert(kOffNowSeq >= kOffPulse + MP_PULSE_BYTES && kOffNow % 8 == 0 && kOffNow + MP_ACTIVITY_CURRENT_BYTES <= kSectionBytes,
              "the now field fits its place");

constexpr std::uint32_t kVersionWord = kVersion | (static_cast<std::uint32_t>(kSectionBytes) << 16);
constexpr std::size_t kPulseWords = MP_PULSE_BYTES / 4;

// The section is plain shared memory; every field is read and written as a
// 32-bit atomic, so a reader racing a writer reads old or new words, never
// undefined values, and the seqlock (plus the pulse checksum) tells them apart.
std::atomic<std::uint32_t> &word(void *section, std::size_t offset) {
    return *reinterpret_cast<std::atomic<std::uint32_t> *>(static_cast<unsigned char *>(section) + offset);
}
const std::atomic<std::uint32_t> &word(const void *section, std::size_t offset) {
    return *reinterpret_cast<const std::atomic<std::uint32_t> *>(static_cast<const unsigned char *>(section) + offset);
}

void loadPulse(const void *section, std::uint8_t *out) {
    for (std::size_t i = 0; i < kPulseWords; ++i) {
        const std::uint32_t v = word(section, kOffPulse + 4 * i).load(std::memory_order_relaxed);
        for (std::size_t b = 0; b < 4; ++b) out[4 * i + b] = static_cast<std::uint8_t>(v >> (8 * b));
    }
}

void storePulse(void *section, const std::uint8_t *bytes) {
    for (std::size_t i = 0; i < kPulseWords; ++i) {
        std::uint32_t v = 0;
        for (std::size_t b = 0; b < 4; ++b) v |= static_cast<std::uint32_t>(bytes[4 * i + b]) << (8 * b);
        word(section, kOffPulse + 4 * i).store(v, std::memory_order_relaxed);
    }
}

std::uint32_t validDpi(std::uint32_t dpi) { return dpi >= kMinDpi && dpi <= kMaxDpi ? dpi : 0; }

} // namespace

std::wstring securityDescriptor(const std::wstring &userSid) {
    if (userSid.size() < 5 || userSid.compare(0, 4, L"S-1-") != 0 || userSid.size() > 200) return {};
    for (wchar_t c : userSid)
        if (!(c == L'S' || c == L'-' || (c >= L'0' && c <= L'9'))) return {};
    return L"D:P(A;;GA;;;" + userSid + L")S:(ML;;NRNWNX;;;ME)";
}

MPPulse next(const MPPulse *previous, const Input &in, std::uint32_t dpi) {
    MPPulse p{};
    if (previous) {
        p.seq = previous->seq;
        p.keys = previous->keys;
        p.commits = previous->commits;
        p.graphemes = previous->graphemes;
    }
    // Unsigned arithmetic wraps; the desk diffs successive pulses with wrap-around.
    p.seq += 1u;
    p.keys += in.keys;
    p.commits += in.commits;
    p.graphemes += in.graphemes;
    p.display = 0;
    // The MP_PULSE_* flags are an unnamed enum: cast them so both arms of each conditional are unsigned
    // (GCC's -Wextra rejects mixing an enumerator with 0u under -Werror).
    p.flags = (in.composing ? static_cast<std::uint32_t>(MP_PULSE_COMPOSING) : 0u) |
              (in.expanded ? static_cast<std::uint32_t>(MP_PULSE_EXPANDED) : 0u) |
              (in.above ? static_cast<std::uint32_t>(MP_PULSE_ABOVE) : 0u);
    if (in.haveOrigin && validDpi(dpi)) {
        const double toPoints = 96.0 / static_cast<double>(dpi);
        MPPulseOrigin o{};
        if (mp_pulse_origin_measure(in.originX * toPoints, in.originY * toPoints, in.lineHeight * toPoints, &o)) {
            p.flags |= MP_PULSE_ORIGIN;
            p.origin_x = o.x;
            p.origin_y = o.y;
            p.line = o.line;
        }
    }
    return p;
}

bool ready(const void *section) {
    return section && word(section, kOffMagic).load(std::memory_order_acquire) == kMagic &&
           word(section, kOffVersion).load(std::memory_order_relaxed) == kVersionWord;
}

void initialize(void *section, std::uint32_t dpi) {
    if (!section) return;
    if (ready(section)) {
        // An earlier desk's section, still held by input methods: keep the slot and its counters.
        setUnitDpi(section, dpi);
        return;
    }
    for (std::size_t offset = 4; offset < kSectionBytes; offset += 4) word(section, offset).store(0, std::memory_order_relaxed);
    word(section, kOffVersion).store(kVersionWord, std::memory_order_relaxed);
    word(section, kOffUnitDpi).store(validDpi(dpi) ? dpi : 96u, std::memory_order_relaxed);
    word(section, kOffNow + offsetof(MPActivityCurrent, category)).store(MP_ACTIVITY_NO_CATEGORY, std::memory_order_relaxed);
    word(section, kOffMagic).store(kMagic, std::memory_order_release);
}

std::uint32_t unitDpi(const void *section) {
    return ready(section) ? validDpi(word(section, kOffUnitDpi).load(std::memory_order_relaxed)) : 0;
}

void setUnitDpi(void *section, std::uint32_t dpi) {
    if (section && validDpi(dpi)) word(section, kOffUnitDpi).store(dpi, std::memory_order_relaxed);
}

void setDeskCats(void *section, std::uint32_t cats) {
    if (section) word(section, kOffDeskCats).store(cats & 3u, std::memory_order_relaxed);
}

std::uint32_t deskCats(const void *section) {
    return ready(section) ? word(section, kOffDeskCats).load(std::memory_order_relaxed) & 3u : 0;
}

std::uint32_t glassCats(std::uint32_t desk) {
    return (desk & MP_PETS_MIMIMO) ? MP_PETS_MOMO : (MP_PETS_MIMIMO | MP_PETS_MOMO);
}

void setNow(void *section, const MPActivityCurrent &now) {
    if (!section) return;
    // One writer (the desk): odd while the words change, even again after.
    std::atomic<std::uint32_t> &seq = word(section, kOffNowSeq);
    const std::uint32_t s = seq.load(std::memory_order_relaxed) & ~1u;
    seq.store(s + 1u, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release);
    unsigned char bytes[sizeof now];
    std::memcpy(bytes, &now, sizeof now);
    for (std::size_t i = 0; i < sizeof now; i += 4) {
        std::uint32_t v = 0;
        for (std::size_t b = 0; b < 4; ++b) v |= static_cast<std::uint32_t>(bytes[i + b]) << (8 * b);
        word(section, kOffNow + i).store(v, std::memory_order_relaxed);
    }
    seq.store(s + 2u, std::memory_order_release);
}

bool now(const void *section, MPActivityCurrent &out) {
    if (!ready(section)) return false;
    const std::atomic<std::uint32_t> &seq = word(section, kOffNowSeq);
    const std::uint32_t s1 = seq.load(std::memory_order_acquire);
    if (s1 & 1u) return false;
    unsigned char bytes[sizeof out];
    for (std::size_t i = 0; i < sizeof out; i += 4) {
        const std::uint32_t v = word(section, kOffNow + i).load(std::memory_order_relaxed);
        for (std::size_t b = 0; b < 4; ++b) bytes[i + b] = static_cast<unsigned char>(v >> (8 * b));
    }
    std::atomic_thread_fence(std::memory_order_acquire);
    if (seq.load(std::memory_order_relaxed) != s1) return false;
    std::memcpy(&out, bytes, sizeof out);
    out.app[MP_ACTIVITY_TEXT_BYTES - 1] = '\0';
    return true;
}

void retire(void *section) {
    if (!ready(section)) return;
    setDeskCats(section, 0);
    MPActivityCurrent off{};
    off.category = MP_ACTIVITY_NO_CATEGORY;
    setNow(section, off);
}

Write write(void *section, const Input &in) noexcept {
    if (!ready(section)) return Write::NotReady;
    std::atomic<std::uint32_t> &seq = word(section, kOffSeq);
    std::uint32_t s = seq.load(std::memory_order_relaxed);
    if ((s & 1u) != 0) return Write::Busy;
    if (!seq.compare_exchange_strong(s, s + 1u, std::memory_order_acquire, std::memory_order_relaxed)) return Write::Busy;
    // The odd seq is ordered before the pulse words for any reader that sees them.
    std::atomic_thread_fence(std::memory_order_release);
    std::uint8_t bytes[MP_PULSE_BYTES];
    loadPulse(section, bytes);
    MPPulse previous{};
    const bool have = mp_pulse_decode(bytes, MP_PULSE_BYTES, &previous) == MP_PULSE_OK;
    const MPPulse pulse = next(have ? &previous : nullptr, in, unitDpi(section));
    if (mp_pulse_encode(&pulse, bytes, sizeof bytes) == MP_PULSE_BYTES) storePulse(section, bytes);
    seq.store(s + 2u, std::memory_order_release);
    return Write::Written;
}

Read read(void *section, Reader &reader, std::uint64_t nowMs, std::uint8_t *out) noexcept {
    if (!ready(section) || !out) return Read::NotReady;
    std::atomic<std::uint32_t> &seq = word(section, kOffSeq);
    const std::uint32_t s1 = seq.load(std::memory_order_acquire);
    if ((s1 & 1u) != 0) {
        // A writer holds the slot. One that died there would block every other
        // writer for good: after kStuckMs of the same odd seq, release it.
        if (reader.stuck != s1) {
            reader.stuck = s1;
            reader.stuckSince = nowMs;
        } else if (nowMs - reader.stuckSince >= kStuckMs) {
            std::uint32_t expected = s1;
            seq.compare_exchange_strong(expected, s1 + 1u, std::memory_order_acq_rel, std::memory_order_relaxed);
            reader.stuck = 0;
        }
        return Read::Busy;
    }
    reader.stuck = 0;
    if (s1 == reader.last) return Read::Same;
    std::uint8_t bytes[MP_PULSE_BYTES];
    loadPulse(section, bytes);
    std::atomic_thread_fence(std::memory_order_acquire);
    if (seq.load(std::memory_order_relaxed) != s1) return Read::Busy;
    std::memcpy(out, bytes, MP_PULSE_BYTES);
    reader.last = s1;
    return Read::Fresh;
}

#ifdef _WIN32

namespace {

// The user of this process: TOKEN_USER in a fixed buffer, no heap.
struct UserSid {
    alignas(8) BYTE buffer[sizeof(TOKEN_USER) + SECURITY_MAX_SID_SIZE];
    PSID sid = nullptr;
    bool read() {
        HANDLE token = nullptr;
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return false;
        DWORD length = 0;
        const bool ok = GetTokenInformation(token, TokenUser, buffer, sizeof buffer, &length);
        CloseHandle(token);
        if (!ok) return false;
        sid = reinterpret_cast<TOKEN_USER *>(buffer)->User.Sid;
        return IsValidSid(sid) != FALSE;
    }
};

// The boundary: this user's SID and the Medium integrity label. Only a process
// of this user at Medium or higher integrity matches it.
HANDLE makeBoundary(const wchar_t *name) {
    UserSid user;
    alignas(8) BYTE medium[SECURITY_MAX_SID_SIZE];
    DWORD size = sizeof medium;
    if (!user.read() || !CreateWellKnownSid(WinMediumLabelSid, nullptr, medium, &size)) return nullptr;
    HANDLE boundary = CreateBoundaryDescriptorW(name, 0);
    if (!boundary) return nullptr;
    if (!AddSIDToBoundaryDescriptor(&boundary, user.sid) || !AddIntegrityLabelToBoundaryDescriptor(&boundary, medium)) {
        DeleteBoundaryDescriptor(boundary);
        return nullptr;
    }
    return boundary;
}

// SECURITY_ATTRIBUTES for the namespace and its objects (securityDescriptor()).
class Security {
public:
    Security() {
        UserSid user;
        LPWSTR text = nullptr;
        if (!user.read() || !ConvertSidToStringSidW(user.sid, &text)) return;
        const std::wstring sddl = securityDescriptor(text);
        LocalFree(text);
        if (sddl.empty() ||
            !ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &descriptor_, nullptr))
            descriptor_ = nullptr;
        attributes_.nLength = sizeof attributes_;
        attributes_.lpSecurityDescriptor = descriptor_;
        attributes_.bInheritHandle = FALSE;
    }
    ~Security() {
        if (descriptor_) LocalFree(descriptor_);
    }
    Security(const Security &) = delete;
    Security &operator=(const Security &) = delete;
    SECURITY_ATTRIBUTES *get() { return descriptor_ ? &attributes_ : nullptr; }

private:
    PSECURITY_DESCRIPTOR descriptor_ = nullptr;
    SECURITY_ATTRIBUTES attributes_{};
};

void closeHandle(HANDLE &h) {
    if (h) CloseHandle(h);
    h = nullptr;
}

} // namespace

Server::~Server() { close(); }

void Server::close() {
    // Input methods keep the section: tell them no cat is on the desk any more.
    if (view_) retire(view_), UnmapViewOfFile(view_);
    view_ = nullptr;
    closeHandle(signal_);
    closeHandle(section_);
    if (instance_) ReleaseMutex(instance_);
    closeHandle(instance_);
    // Without PRIVATE_NAMESPACE_FLAG_DESTROY: input methods that hold the
    // namespace keep it, and the next desk opens the same slot.
    if (namespace_) ClosePrivateNamespace(namespace_, 0);
    namespace_ = nullptr;
    if (boundary_) DeleteBoundaryDescriptor(boundary_);
    boundary_ = nullptr;
    reader_ = {};
}

Server::Open Server::open(std::uint32_t dpi) {
    close();
    Security security;
    SECURITY_ATTRIBUTES *sa = security.get();
    boundary_ = makeBoundary(names_.boundary);
    if (!sa || !boundary_) {
        close();
        return Open::Failed;
    }
    namespace_ = CreatePrivateNamespaceW(sa, boundary_, names_.alias);
    if (!namespace_ && GetLastError() == ERROR_ALREADY_EXISTS) namespace_ = OpenPrivateNamespaceW(boundary_, names_.alias);
    if (!namespace_) {
        close();
        return Open::Failed;
    }
    // Owned by this thread for the desk's life. An existing mutex nobody owns was only being looked at
    // by an input method (or left by a desk that died): this desk takes it.
    instance_ = CreateMutexW(sa, TRUE, names_.instance);
    if (!instance_) {
        close();
        return Open::Failed;
    }
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        const DWORD waited = WaitForSingleObject(instance_, 0);
        if (waited != WAIT_OBJECT_0 && waited != WAIT_ABANDONED) {
            closeHandle(instance_); // not ours to release
            close();
            return Open::AlreadyRunning;
        }
    }
    section_ = CreateFileMappingW(INVALID_HANDLE_VALUE, sa, PAGE_READWRITE, 0, static_cast<DWORD>(kSectionBytes), names_.slot);
    if (section_) view_ = MapViewOfFile(section_, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, kSectionBytes);
    signal_ = CreateEventW(sa, FALSE, FALSE, names_.signal);
    if (!view_ || !signal_) {
        close();
        return Open::Failed;
    }
    initialize(view_, dpi);
    return Open::Ok;
}

Read Server::take(std::uint64_t nowMs, std::uint8_t *out) noexcept {
    return view_ ? read(view_, reader_, nowMs, out) : Read::NotReady;
}

void Server::setUnitDpi(std::uint32_t dpi) { pulse::setUnitDpi(view_, dpi); }
void Server::setDeskCats(std::uint32_t cats) { pulse::setDeskCats(view_, cats); }
void Server::setNow(const MPActivityCurrent &current) { pulse::setNow(view_, current); }

Client &Client::shared() {
    // Never destroyed: nothing runs at DLL unload.
    static Client *client = new Client();
    return *client;
}

Client::~Client() {
    if (view_) UnmapViewOfFile(view_);
    closeHandle(signal_);
    closeHandle(section_);
    if (namespace_) ClosePrivateNamespace(namespace_, 0);
    if (boundary_) DeleteBoundaryDescriptor(boundary_);
}

void Client::prepare(bool allowed) noexcept {
    AcquireSRWLockExclusive(&lock_);
    if (!prepared_) {
        prepared_ = true;
        boundary_ = allowed ? makeBoundary(names_.boundary) : nullptr;
        allowed_ = boundary_ != nullptr;
    }
    ReleaseSRWLockExclusive(&lock_);
}

// Under lock_. Kernel calls only: no heap, no wait.
bool Client::tryOpen() noexcept {
    const ULONGLONG now = GetTickCount64();
    if (static_cast<LONG64>(now) < nextTry_) return false;
    nextTry_ = static_cast<LONG64>(now + kRetryMs);
    if (!namespace_) namespace_ = OpenPrivateNamespaceW(boundary_, names_.alias);
    if (!namespace_) return false;
    if (!section_) section_ = OpenFileMappingW(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, names_.slot);
    if (!signal_) signal_ = OpenEventW(EVENT_MODIFY_STATE, FALSE, names_.signal);
    if (!section_ || !signal_) return false;
    view_ = MapViewOfFile(section_, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, kSectionBytes);
    if (!view_) return false;
    InterlockedExchangePointer(&ready_, view_);
    return true;
}

void *Client::openView() noexcept {
    void *view = InterlockedCompareExchangePointer(&ready_, nullptr, nullptr);
    if (view) return view;
    // Another thread of this program is opening it: not this time, rather than wait.
    if (!TryAcquireSRWLockExclusive(&lock_)) return nullptr;
    const bool open = allowed_ && (view_ || tryOpen());
    view = open ? view_ : nullptr;
    ReleaseSRWLockExclusive(&lock_);
    return view;
}

bool Client::send(const Input &in) noexcept {
    void *view = openView();
    if (!view) return false;
    const Write result = write(view, in);
    // Busy also wakes the desk: it notices a slot left held by a writer that died.
    if (result != Write::NotReady) SetEvent(signal_);
    return result == Write::Written;
}

// The desk holds its instance mutex for its whole life; it is gone with the desk. Only opened and
// closed again here (never waited on), so a desk starting meanwhile still takes it (Server::open).
bool Client::deskRunning() noexcept {
    const LONG64 now = static_cast<LONG64>(GetTickCount64());
    if (now < InterlockedCompareExchange64(&nextAliveCheck_, 0, 0)) return InterlockedCompareExchange(&running_, 0, 0) != 0;
    InterlockedExchange64(&nextAliveCheck_, now + static_cast<LONG64>(kRetryMs));
    HANDLE instance = OpenMutexW(SYNCHRONIZE, FALSE, names_.instance);
    const bool running = instance != nullptr || GetLastError() == ERROR_ACCESS_DENIED;
    if (instance) CloseHandle(instance);
    InterlockedExchange(&running_, running ? 1 : 0);
    return running;
}

std::uint32_t Client::glassCats() noexcept {
    void *view = openView();
    const std::uint32_t desk = view ? deskCats(view) : 0u;
    if ((desk & MP_PETS_MIMIMO) && !deskRunning()) return pulse::glassCats(0);
    return pulse::glassCats(desk);
}

#endif

} // namespace meowpuut::pulse
