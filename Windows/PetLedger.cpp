#include "PetLedger.h"

#include "../Engine/crates/meowpuut-ffi/include/meowpuut.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <string>
#include <system_error>
#include <vector>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <knownfolders.h>
#include <shlobj.h>
#else
#include <fcntl.h>
#include <sys/file.h>
#include <time.h>
#include <unistd.h>
#include <fstream>
#endif

namespace meowpuut {
namespace {

// ---- graphemes ---------------------------------------------------------------

bool inRange(char32_t c, char32_t lo, char32_t hi) { return c >= lo && c <= hi; }
bool isRegionalIndicator(char32_t c) { return inRange(c, 0x1F1E6, 0x1F1FF); }
bool extendsCluster(char32_t c) {
    return inRange(c, 0x0300, 0x036F) || inRange(c, 0x1AB0, 0x1AFF) || inRange(c, 0x1DC0, 0x1DFF) ||
           inRange(c, 0x20D0, 0x20FF) || inRange(c, 0xFE20, 0xFE2F) || inRange(c, 0x3099, 0x309A) ||
           inRange(c, 0xFE00, 0xFE0F) || inRange(c, 0xE0100, 0xE01EF) || c == 0x200D ||
           inRange(c, 0x1F3FB, 0x1F3FF) || inRange(c, 0xE0020, 0xE007F);
}

// Next code point; an invalid or truncated sequence is one U+FFFD per byte.
char32_t decode(std::string_view s, std::size_t &i) {
    const auto b = [&](std::size_t k) { return static_cast<unsigned char>(s[k]); };
    const unsigned char lead = b(i);
    std::size_t need = 0;
    char32_t c = 0, min = 0;
    if (lead < 0x80) { ++i; return lead; }
    if (lead >= 0xC2 && lead <= 0xDF) { need = 1; c = lead & 0x1Fu; min = 0x80; }
    else if (lead >= 0xE0 && lead <= 0xEF) { need = 2; c = lead & 0x0Fu; min = 0x800; }
    else if (lead >= 0xF0 && lead <= 0xF4) { need = 3; c = lead & 0x07u; min = 0x10000; }
    else { ++i; return 0xFFFD; }
    if (i + need >= s.size()) { ++i; return 0xFFFD; }
    for (std::size_t k = 1; k <= need; ++k) {
        if ((b(i + k) & 0xC0) != 0x80) { ++i; return 0xFFFD; }
        c = (c << 6) | (b(i + k) & 0x3Fu);
    }
    if (c < min || c > 0x10FFFF || inRange(c, 0xD800, 0xDFFF)) { ++i; return 0xFFFD; }
    i += need + 1;
    return c;
}

} // namespace

std::size_t countGraphemes(std::string_view utf8) {
    std::size_t count = 0;
    bool have = false, afterZwj = false, riOpen = false;
    char32_t previous = 0;
    for (std::size_t i = 0; i < utf8.size();) {
        const char32_t c = decode(utf8, i);
        const bool joins = have && (afterZwj || extendsCluster(c) || (isRegionalIndicator(c) && riOpen) ||
                                    (c == 0x0A && previous == 0x0D));
        if (joins) {
            if (isRegionalIndicator(c)) riOpen = false;
        } else {
            ++count;
            have = true;
            riOpen = isRegionalIndicator(c);
        }
        afterZwj = c == 0x200D;
        previous = c;
    }
    return count;
}

LocalClock localClockFromUtc(std::int64_t utcSeconds, std::int32_t offsetSeconds) {
    const std::int64_t local = utcSeconds + offsetSeconds;
    std::int64_t days = local / 86'400;
    std::int64_t rest = local % 86'400;
    if (rest < 0) { rest += 86'400; --days; }
    LocalClock clock;
    clock.day = days >= 1 && days < static_cast<std::int64_t>(UINT32_MAX) ? static_cast<std::uint32_t>(days) : 0;
    clock.minute = static_cast<std::uint32_t>(std::min<std::int64_t>(1439, rest / 60));
    return clock;
}

namespace {

#ifdef _WIN32
std::int64_t fileTimeSeconds(const FILETIME &ft) {
    ULARGE_INTEGER u;
    u.LowPart = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    return static_cast<std::int64_t>(u.QuadPart / 10'000'000ULL) - 11'644'473'600LL;
}
#endif

} // namespace

LedgerClock systemLedgerClock() {
    LedgerClock clock;
#ifdef _WIN32
    clock.monoMs = GetTickCount64();
    FILETIME utc{}, local{};
    SYSTEMTIME localTime{};
    GetSystemTimeAsFileTime(&utc);
    GetLocalTime(&localTime);
    if (SystemTimeToFileTime(&localTime, &local)) {
        // Local minus UTC, rounded to a minute so the two reads' jitter drops out.
        const std::int64_t utcSeconds = fileTimeSeconds(utc);
        const std::int64_t diff = fileTimeSeconds(local) - utcSeconds;
        const std::int64_t offset = (diff >= 0 ? diff + 30 : diff - 30) / 60 * 60;
        clock.local = localClockFromUtc(utcSeconds, static_cast<std::int32_t>(offset));
    }
#else
    clock.monoMs = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
    const time_t now = time(nullptr);
    struct tm parts{};
    if (localtime_r(&now, &parts)) clock.local = localClockFromUtc(static_cast<std::int64_t>(now), static_cast<std::int32_t>(parts.tm_gmtoff));
#endif
    return clock;
}

// ---- file layer ----------------------------------------------------------------

namespace {
constexpr std::size_t kMaxFile = 64 * 1024; // the states are 2884 and 12,824 bytes; anything bigger is not ours
} // namespace

LedgerFile::LedgerFile(std::filesystem::path directory, const char *fileName, const char *lockName)
    : directory_(std::move(directory)), file_(directory_ / fileName), lockName_(lockName) {}

LedgerFile::~LedgerFile() { release(); }

std::vector<std::uint8_t> LedgerFile::read() const { return readFile(file_); }

#ifdef _WIN32

bool LedgerFile::lock() {
    std::error_code ec;
    std::filesystem::create_directories(directory_, ec);
    HANDLE h = CreateFileW((directory_ / lockName_).c_str(), GENERIC_READ | GENERIC_WRITE,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    lock_ = h;
    for (int attempt = 0; attempt < 25; ++attempt) {
        OVERLAPPED overlapped{};
        if (LockFileEx(lock_, LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &overlapped)) return true;
        Sleep(10);
    }
    release();
    return false;
}

void LedgerFile::release() {
    if (!lock_) return;
    OVERLAPPED overlapped{};
    UnlockFileEx(lock_, 0, 1, 0, &overlapped);
    CloseHandle(lock_);
    lock_ = nullptr;
}

std::vector<std::uint8_t> LedgerFile::readFile(const std::filesystem::path &file) {
    std::vector<std::uint8_t> bytes;
    HANDLE h = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return bytes;
    LARGE_INTEGER size{};
    if (GetFileSizeEx(h, &size) && size.QuadPart > 0 && size.QuadPart <= static_cast<LONGLONG>(kMaxFile)) {
        bytes.resize(static_cast<std::size_t>(size.QuadPart));
        DWORD got = 0;
        if (!ReadFile(h, bytes.data(), static_cast<DWORD>(bytes.size()), &got, nullptr)) got = 0;
        bytes.resize(got);
    }
    CloseHandle(h);
    return bytes;
}

bool LedgerFile::replace(const std::uint8_t *bytes, std::size_t size) const {
    std::filesystem::path temporary = file_;
    temporary += L".tmp" + std::to_wstring(GetCurrentProcessId());
    HANDLE h = CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD put = 0;
    const bool written = WriteFile(h, bytes, static_cast<DWORD>(size), &put, nullptr) && put == size && FlushFileBuffers(h);
    CloseHandle(h);
    if (written && MoveFileExW(temporary.c_str(), file_.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) return true;
    DeleteFileW(temporary.c_str());
    return false;
}

#else

bool LedgerFile::lock() {
    std::error_code ec;
    std::filesystem::create_directories(directory_, ec);
    lock_ = ::open((directory_ / lockName_).c_str(), O_CREAT | O_RDWR, 0600);
    if (lock_ < 0) return false;
    for (int attempt = 0; attempt < 25; ++attempt) {
        if (::flock(lock_, LOCK_EX | LOCK_NB) == 0) return true;
        usleep(10'000);
    }
    release();
    return false;
}

void LedgerFile::release() {
    if (lock_ < 0) return;
    ::flock(lock_, LOCK_UN);
    ::close(lock_);
    lock_ = -1;
}

std::vector<std::uint8_t> LedgerFile::readFile(const std::filesystem::path &file) {
    std::ifstream in(file, std::ios::binary);
    std::vector<std::uint8_t> bytes;
    if (!in) return bytes;
    bytes.resize(kMaxFile + 1);
    in.read(reinterpret_cast<char *>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    const auto got = static_cast<std::size_t>(in.gcount());
    if (got > kMaxFile) return {};
    bytes.resize(got);
    return bytes;
}

bool LedgerFile::replace(const std::uint8_t *bytes, std::size_t size) const {
    std::filesystem::path temporary = file_;
    temporary += ".tmp" + std::to_string(getpid());
    {
        std::ofstream out(temporary, std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char *>(bytes), static_cast<std::streamsize>(size));
        out.flush();
        if (!out) { std::error_code ec; std::filesystem::remove(temporary, ec); return false; }
    }
    if (::rename(temporary.c_str(), file_.c_str()) == 0) return true;
    std::error_code ec;
    std::filesystem::remove(temporary, ec);
    return false;
}

#endif

#ifdef _WIN32
bool appContainerProcess() {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return true; // cannot tell: do not record
    DWORD isContainer = 1, length = 0;
    const bool ok = GetTokenInformation(token, TokenIsAppContainer, &isContainer, sizeof isContainer, &length);
    CloseHandle(token);
    return !ok || isContainer != 0;
}
#endif

// ---- ledger ----------------------------------------------------------------------

void PetLedger::Free::operator()(MPPets *p) const { mp_pets_free(p); }

PetLedger::PetLedger(std::filesystem::path directory, bool enabled)
    : directory_(std::move(directory)), enabled_(enabled && !directory_.empty()) {}

PetLedger::~PetLedger() = default;

std::filesystem::path PetLedger::defaultDirectory() {
#ifdef _WIN32
    PWSTR path = nullptr;
    std::filesystem::path result;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_DEFAULT, nullptr, &path)) && path)
        result = std::filesystem::path(path) / L"Meowpuut";
    if (path) CoTaskMemFree(path);
    return result;
#else
    return {};
#endif
}

PetLedger &PetLedger::shared() {
    // Never destroyed: no work at DLL unload.
    static PetLedger *ledger = [] {
#ifdef _WIN32
        const bool allowed = !appContainerProcess();
        return new PetLedger(allowed ? defaultDirectory() : std::filesystem::path(), allowed);
#else
        return new PetLedger(defaultDirectory(), false);
#endif
    }();
    return *ledger;
}

bool PetLedger::ensureLoaded() {
    if (live_) return true;
    const auto bytes = LedgerFile::readFile(directory_ / kFileName);
    int status = 0;
    live_.reset(mp_pets_from_bytes(bytes.data(), bytes.size(), &status));
    return live_ != nullptr;
}

void PetLedger::committed(std::size_t graphemes) { committed(graphemes, systemLedgerClock()); }

void PetLedger::committed(std::size_t graphemes, const LedgerClock &clock) {
    if (graphemes == 0) return;
    record(MP_PETS_COMMIT, static_cast<std::uint32_t>(std::min<std::size_t>(graphemes, UINT32_MAX)), clock);
}

void PetLedger::touched(std::uint32_t trigger, std::uint32_t pet, const LedgerClock &clock) {
    if ((trigger != MP_PETS_TAP && trigger != MP_PETS_HOLD && trigger != MP_PETS_DROP) ||
        (pet != MP_PETS_MIMIMO && pet != MP_PETS_MOMO))
        return;
    record(trigger, pet, clock);
}

// Mode off: the event is counted and the day marked, but no moment is planned (the
// desk core and the candidate window plan their own).
void PetLedger::record(std::uint32_t trigger, std::uint32_t arg, const LedgerClock &clock) {
    if (!enabled_) return;
    std::lock_guard<std::mutex> guard(mutex_);
    if (!ensureLoaded()) return;
    const MPPetsAmbience ambience{0, UINT32_MAX, MP_PETS_MODE_OFF, MP_PETS_MIMIMO | MP_PETS_MOMO};
    const MPPetsClock when{clock.monoMs, clock.local.day, clock.local.minute};
    MPPetsPlan plan{};
    mp_pets_decide(live_.get(), trigger, arg, &ambience, &when, 0, &plan);
    dirty_ = true;
    if (trigger == MP_PETS_COMMIT) {
        typed_ = true;
        lastCommit_ = clock;
    }
    if (!haveBase_) {
        haveBase_ = true;
        lastFlushMs_ = clock.monoMs;
    }
    if (clock.monoMs >= lastFlushMs_ && clock.monoMs - lastFlushMs_ >= kFlushIntervalMs) {
        lastFlushMs_ = clock.monoMs; // a failed write is retried after the next interval, not at every commit
        flushLocked();
    }
}

std::vector<std::uint8_t> PetLedger::state() {
    if (!enabled_) return {};
    std::lock_guard<std::mutex> guard(mutex_);
    if (!ensureLoaded()) return {};
    std::vector<std::uint8_t> bytes(MP_PETS_STATE_BYTES);
    if (mp_pets_to_bytes(live_.get(), bytes.data(), bytes.size()) != bytes.size()) return {};
    return bytes;
}

std::uint64_t PetLedger::merges() {
    std::lock_guard<std::mutex> guard(mutex_);
    return merges_;
}

bool PetLedger::flush() {
    if (!enabled_) return true;
    std::lock_guard<std::mutex> guard(mutex_);
    return flushLocked();
}

// The day's place, once per flush after commits (never per key): where the
// journey has got to for the characters this process's state holds, on the
// local day of the last commit. The ledger keeps the furthest place of a day.
void PetLedger::reachPlaceLocked() {
    if (!typed_) return;
    typed_ = false;
#ifdef MEOWPUUT_GLYPH
    const std::uint64_t total = committedGraphemesLocked();
    const std::int64_t characters = total > static_cast<std::uint64_t>(INT64_MAX) ? INT64_MAX : static_cast<std::int64_t>(total);
    const MPPetsClock when{lastCommit_.monoMs, lastCommit_.local.day, lastCommit_.local.minute};
    mp_pets_reach_place(live_.get(), &when, mp_glyph_place_of_characters(characters));
#endif
}

// Under the file lock: read the file, add this process's increments, merge in
// the live state, write a temporary file, rename it over the file, then merge
// the result back. The increments are taken only once the lock is held, so a
// busy lock loses nothing; a failed write after that does (as on iOS).
bool PetLedger::flushLocked() {
    if (!dirty_) return true;
    if (!live_) return true;
    LedgerFile store(directory_, kFileName, kLockName);
    if (!store.lock()) return false;
    const auto onDisk = store.read();
    int status = 0;
    std::unique_ptr<MPPets, Free> disk(mp_pets_from_bytes(onDisk.data(), onDisk.size(), &status));
    std::array<std::uint8_t, MP_PETS_DELTA_BYTES> delta{};
    std::array<std::uint8_t, MP_PETS_STATE_BYTES> state{}, merged{};
    if (disk) reachPlaceLocked();
    if (!disk || mp_pets_take_delta(live_.get(), delta.data(), delta.size()) != delta.size() ||
        mp_pets_to_bytes(live_.get(), state.data(), state.size()) != state.size())
        return false;
    dirty_ = false;
    mp_pets_apply_delta(disk.get(), delta.data(), delta.size());
    mp_pets_merge(disk.get(), state.data(), state.size());
    if (mp_pets_to_bytes(disk.get(), merged.data(), merged.size()) != merged.size() ||
        !store.replace(merged.data(), merged.size())) {
        dirty_ = true; // nothing new to add, but the live state still has to reach the file
        return false;
    }
    mp_pets_merge(live_.get(), merged.data(), merged.size());
    ++merges_;
    return true;
}

void PetLedger::clear() {
    if (!enabled_) return;
    std::lock_guard<std::mutex> guard(mutex_);
    if (!ensureLoaded()) return;
    mp_pets_clear(live_.get());
    dirty_ = true;
    typed_ = false; // what was typed before is forgotten: no place for it either
    flushLocked();
}

std::uint64_t PetLedger::committedGraphemes() {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!ensureLoaded()) return 0;
    return committedGraphemesLocked();
}

std::uint64_t PetLedger::committedGraphemesLocked() {
    // The C ABI has no total, so read the header (codec.rs; the same in versions 1 and 2):
    // magic 9C A7, version, reserved, epoch u32 at 4, graphemes u64 at 8, little endian.
    std::array<std::uint8_t, MP_PETS_STATE_BYTES> bytes{};
    if (mp_pets_to_bytes(live_.get(), bytes.data(), bytes.size()) != bytes.size()) return 0;
    std::uint64_t total = 0;
    for (int i = 7; i >= 0; --i) total = total << 8 | bytes[static_cast<std::size_t>(8 + i)];
    return total;
}

std::size_t PetLedger::dayCount() {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!ensureLoaded()) return 0;
    std::array<MPPetsMark, 400> marks{};
    return mp_pets_marks(live_.get(), marks.data(), marks.size());
}

std::vector<std::uint8_t> PetLedger::dayPlaces() {
    std::lock_guard<std::mutex> guard(mutex_);
    if (!ensureLoaded()) return {};
    std::vector<std::uint8_t> places(400);
    places.resize(std::min(places.size(), mp_pets_mark_places(live_.get(), places.data(), places.size())));
    return places;
}

} // namespace meowpuut
