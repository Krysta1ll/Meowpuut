#include "ForegroundWatcher.h"

#include <algorithm>
#include <cstring>
#include <cwchar>
#include <cwctype>

namespace meowpuut::desk {

namespace {

std::uint32_t seconds(std::uint64_t ms) { return static_cast<std::uint32_t>(std::min<std::uint64_t>(ms / 1000, UINT32_MAX)); }

bool sameCurrent(const MPActivityCurrent &a, const MPActivityCurrent &b) {
    return a.state == b.state && a.category == b.category && a.flags == b.flags && std::strcmp(a.app, b.app) == 0;
}

} // namespace

void ForegroundWatcher::configure(const ActivitySwitches &switches, const MPPetsClock &now, std::uint64_t idleMs) {
    if (!switches.on) {
        if (switches_.on) {
            // Credits the interval that ran while it was on, then forgets everything.
            switches_.on = false;
            stepNow(now, idleMs);
            ledger_.unload();
        }
        switches_ = switches;
        app_.clear();
        app_.shrink_to_fit();
        name_.clear();
        name_.shrink_to_fit();
        tickAt_ = 0;
        waitingForInput_ = false;
        lastState_ = MP_ACTIVITY_NOW_OFF;
        MPActivityCurrent off{};
        off.category = MP_ACTIVITY_NO_CATEGORY;
        publish(off);
        return;
    }
    const bool fresh = !switches_.on;
    switches_ = switches;
    if (!ledger_.loaded() && !ledger_.load()) return;
    ledger_.setHidden(switches_.hidden);
    if (fresh) app_.clear(); // the shell reports the app in front next
    if (!switches_.shareAppName) {
        name_.clear(); // switched off: forgotten, and this step republishes with ""
        name_.shrink_to_fit();
    }
    stepNow(now, idleMs);
}

void ForegroundWatcher::foreground(std::string app, const MPPetsClock &now, std::uint64_t idleMs, std::string name) {
    if (!switches_.on) return;
    if (!wantsAppName() || app.empty()) name.clear();
    if (app == app_ && name == name_) return;
    app_ = std::move(app);
    name_ = std::move(name);
    stepNow(now, idleMs);
}

void ForegroundWatcher::fullScreen(bool on, const MPPetsClock &now, std::uint64_t idleMs) {
    if (on == fullScreen_) return;
    fullScreen_ = on;
    if (switches_.on) stepNow(now, idleMs);
}

void ForegroundWatcher::sample(const MPPetsClock &now, std::uint64_t idleMs) {
    if (!switches_.on) return;
    if (tickAt_ && now.mono_ms >= tickAt_) {
        stepNow(now, idleMs);
        return;
    }
    // Waiting for you to come back: any input since the last step.
    if (waitingForInput_ && now.mono_ms >= idleMs && now.mono_ms - idleMs > lastStepMs_) stepNow(now, idleMs);
}

ForegroundWatcher::Schedule ForegroundWatcher::schedule() const {
    Schedule s;
    if (!switches_.on) return s;
    s.tickAt = tickAt_;
    s.probe = waitingForInput_;
    return s;
}

void ForegroundWatcher::flush() {
    if (ledger_.loaded()) ledger_.flush();
}

void ForegroundWatcher::stepNow(const MPPetsClock &now, std::uint64_t idleMs) {
    MPActivityInput in{};
    in.struct_size = sizeof in;
    in.clock = now;
    in.app_id = app_.empty() ? nullptr : app_.c_str();
    // The display name goes to the core only while the share-app-name switch is on.
    in.app_name = wantsAppName() && !name_.empty() ? name_.c_str() : nullptr;
    in.idle_seconds = seconds(idleMs);
    in.flags = (switches_.on ? static_cast<std::uint32_t>(MP_ACTIVITY_RECORDING) : 0u) | (switches_.paused ? static_cast<std::uint32_t>(MP_ACTIVITY_PAUSED) : 0u) |
               (fullScreen_ ? static_cast<std::uint32_t>(MP_ACTIVITY_FULLSCREEN) : 0u);
    MPActivityStepOut out{};
    if (!ledger_.step(in, out)) return;
    ++steps_;
    lastStepMs_ = now.mono_ms;
    tickAt_ = out.wake_after_ms ? now.mono_ms + out.wake_after_ms : 0;
    // Away (idle past the threshold) with an app in front: only input brings counting back.
    waitingForInput_ = switches_.on && out.wake_after_ms == 0 && out.state == MP_ACTIVITY_NOW_IDLE && !app_.empty();
    // You went away, paused or turned to a hidden app: write what was counted.
    if (lastState_ == MP_ACTIVITY_NOW_ACTIVE && out.state != MP_ACTIVITY_NOW_ACTIVE) ledger_.flush();
    lastState_ = out.state;
    publish(ledger_.current());
}

void ForegroundWatcher::publish(MPActivityCurrent next) {
    if (!wantsAppName()) std::memset(next.app, 0, sizeof next.app); // never a name without the share-app-name switch
    next.app[sizeof next.app - 1] = 0;
    next.reserved = 0;
    if (sameCurrent(next, current_)) return;
    current_ = next;
    ++changes_;
}

#ifdef _WIN32

namespace {

// The image path of a process (PROCESS_QUERY_LIMITED_INFORMATION works for
// elevated processes too); empty when it cannot be read.
std::wstring imagePath(DWORD pid) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!process) return {};
    std::wstring path(MAX_PATH, L'\0');
    for (;;) {
        DWORD size = static_cast<DWORD>(path.size());
        if (QueryFullProcessImageNameW(process, 0, path.data(), &size)) {
            path.resize(size);
            break;
        }
        if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || path.size() >= 32768) {
            path.clear();
            break;
        }
        path.resize(path.size() * 2);
    }
    CloseHandle(process);
    return path;
}

std::string utf8(const std::wstring &w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(static_cast<std::size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

struct FrameChild {
    DWORD host = 0, found = 0;
};

BOOL CALLBACK frameChild(HWND child, LPARAM lp) {
    auto *f = reinterpret_cast<FrameChild *>(lp);
    DWORD pid = 0;
    GetWindowThreadProcessId(child, &pid);
    if (pid && pid != f->host) {
        f->found = pid;
        return FALSE;
    }
    return TRUE;
}

std::wstring lower(std::wstring s) {
    if (!s.empty()) CharLowerBuffW(s.data(), static_cast<DWORD>(s.size()));
    return s;
}

std::wstring trimmed(const wchar_t *text, std::size_t n) {
    std::wstring s(text, n);
    while (!s.empty() && (s.back() == L'\0' || iswspace(s.back()))) s.pop_back();
    std::size_t at = 0;
    while (at < s.size() && iswspace(s[at])) ++at;
    return s.substr(at);
}

// The first language's FileDescription of the exe's version resource; empty when it has none.
std::wstring fileDescription(const std::wstring &path) {
    DWORD handle = 0;
    const DWORD size = GetFileVersionInfoSizeW(path.c_str(), &handle);
    if (!size || size > 4 * 1024 * 1024) return {};
    std::vector<char> data(size);
    if (!GetFileVersionInfoW(path.c_str(), 0, size, data.data())) return {};
    struct Translation {
        WORD language, codePage;
    } *translation = nullptr;
    UINT length = 0;
    if (!VerQueryValueW(data.data(), L"\\VarFileInfo\\Translation", reinterpret_cast<LPVOID *>(&translation), &length) ||
        length < sizeof(Translation) || !translation)
        return {};
    wchar_t query[80];
    std::swprintf(query, 80, L"\\StringFileInfo\\%04x%04x\\FileDescription", static_cast<unsigned>(translation[0].language),
                  static_cast<unsigned>(translation[0].codePage));
    wchar_t *text = nullptr;
    length = 0;
    if (!VerQueryValueW(data.data(), query, reinterpret_cast<LPVOID *>(&text), &length) || !text || !length) return {};
    return trimmed(text, wcsnlen(text, length));
}

// The app's display name: FileDescription, else the exe's file name without ".exe". Never a path.
std::string displayNameOf(const std::wstring &path) {
    const auto isPathLike = [](const std::wstring &s) { return s.find_first_of(L"\\/") != std::wstring::npos; };
    std::wstring name = fileDescription(path);
    if (name.empty() || isPathLike(name)) {
        const auto slash = path.find_last_of(L"\\/");
        name = slash == std::wstring::npos ? path : path.substr(slash + 1);
        if (name.size() > 4 && lower(name.substr(name.size() - 4)) == L".exe") name.resize(name.size() - 4);
    }
    return utf8(name);
}

constexpr const char *kFrameHost = "applicationframehost.exe"; // not in the table: "other"
constexpr const char *kUnknown = "(unknown)";                   // a process whose image cannot be read: "other"

} // namespace

std::string appIdentifier(HWND window, std::string *displayName) {
    if (displayName) displayName->clear();
    DWORD pid = 0;
    if (!window || !GetWindowThreadProcessId(window, &pid) || !pid || pid == GetCurrentProcessId()) return {};
    std::wstring path = imagePath(pid);
    if (path.empty()) return kUnknown;
    std::wstring rawPath = path; // original case, for the version resource and the file name
    CharLowerBuffW(path.data(), static_cast<DWORD>(path.size()));
    const auto baseOf = [](const std::wstring &p) {
        const auto slash = p.find_last_of(L"\\/");
        return slash == std::wstring::npos ? p : p.substr(slash + 1);
    };
    if (baseOf(path) == L"applicationframehost.exe") {
        // A UWP app's frame: the app is the process of a child window of another process. One walk
        // of the frame's children; when it is not there (suspended, closing), the frame counts as other.
        FrameChild f;
        f.host = pid;
        EnumChildWindows(window, &frameChild, reinterpret_cast<LPARAM>(&f));
        path = f.found ? imagePath(f.found) : std::wstring();
        if (path.empty()) return kFrameHost; // a frame without an app: no name either
        rawPath = path;
        CharLowerBuffW(path.data(), static_cast<DWORD>(path.size()));
    }
    if (displayName) *displayName = displayNameOf(rawPath);
    const std::string full = utf8(path), base = utf8(baseOf(path));
    if (base.empty()) return kUnknown;
    // The path only when a folder decides the category (...\steamapps\common\...).
    return mp_activity_classify(full.c_str(), nullptr, 0) == mp_activity_classify(base.c_str(), nullptr, 0) ? base : full;
}

#endif

} // namespace meowpuut::desk
