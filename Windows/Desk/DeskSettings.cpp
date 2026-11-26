#include "DeskSettings.h"

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace meowpuut::desk {

MPDeskSettings coreSettings(const DeskSettings &s) {
    MPDeskSettings c{};
    c.mode = s.quiet ? MP_DESK_MODE_QUIET : MP_DESK_MODE_FULL;
    c.flags = s.reduceMotion ? static_cast<std::uint32_t>(MP_DESK_REDUCE_MOTION) : 0u;
    c.typing = s.typingSeat == MP_DESK_TYPING_GLASS ? MP_DESK_TYPING_GLASS : MP_DESK_TYPING_SPLIT;
    // The clay cats with CLAY-POSES batches 1 and 2: they walk (walk_1–4), so no hopping along an edge.
    const std::uint8_t clay = MP_DESK_ART_SLEEP | MP_DESK_ART_PEEK | MP_DESK_ART_SIDE | MP_DESK_ART_WALK;
    c.art[0] = c.art[1] = clay;
    const double k = s.catScale > 0.4 && s.catScale < 2.1 ? s.catScale : 1.0;
    c.size[0].w = 34 * k;
    c.size[0].h = 52 * k;
    c.size[1].w = 35 * k;
    c.size[1].h = 52 * k;
    return c;
}

std::wstring runCommand(const std::wstring &exe) { return L"\"" + exe + L"\""; }

#ifdef _WIN32

namespace {

bool readDword(const wchar_t *name, DWORD &value) {
    DWORD size = sizeof value;
    return RegGetValueW(HKEY_CURRENT_USER, kSettingsKey, name, RRF_RT_REG_DWORD, nullptr, &value, &size) == ERROR_SUCCESS;
}

// A REG_MULTI_SZ as UTF-8 strings (empty ones and ones that are not valid UTF-16 left out).
std::vector<std::string> readStrings(const wchar_t *name) {
    std::vector<std::string> out;
    DWORD size = 0;
    if (RegGetValueW(HKEY_CURRENT_USER, kSettingsKey, name, RRF_RT_REG_MULTI_SZ, nullptr, nullptr, &size) != ERROR_SUCCESS ||
        size == 0 || size > 64 * 1024)
        return out;
    std::wstring text(size / sizeof(wchar_t) + 1, L'\0');
    if (RegGetValueW(HKEY_CURRENT_USER, kSettingsKey, name, RRF_RT_REG_MULTI_SZ, nullptr, text.data(), &size) != ERROR_SUCCESS)
        return out;
    text.resize(size / sizeof(wchar_t));
    for (std::size_t at = 0; at < text.size();) {
        const std::size_t end = text.find(L'\0', at);
        const std::size_t n = (end == std::wstring::npos ? text.size() : end) - at;
        if (n > 0 && n < 1024) {
            const int bytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data() + at, static_cast<int>(n), nullptr, 0,
                                                  nullptr, nullptr);
            if (bytes > 0) {
                std::string s(static_cast<std::size_t>(bytes), '\0');
                WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data() + at, static_cast<int>(n), s.data(), bytes, nullptr,
                                    nullptr);
                out.push_back(std::move(s));
            }
        }
        if (end == std::wstring::npos) break;
        at = end + 1;
    }
    return out;
}

} // namespace

bool systemReduceMotion() {
    BOOL animate = TRUE;
    return SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &animate, 0) && !animate;
}

DeskSettings readSettings() {
    DeskSettings s;
    DWORD v = 0;
    if (readDword(L"DeskPets", v)) s.deskPets = v != 0;
    if (readDword(L"TypingSeat", v)) s.typingSeat = v == 1 ? MP_DESK_TYPING_GLASS : MP_DESK_TYPING_SPLIT;
    if (readDword(L"DeskCatsOut", v)) s.catsOut = v & 3u;
    if (readDword(L"ActivityStats", v)) s.activity.on = v != 0;
    if (readDword(L"ActivityPaused", v)) s.activity.paused = v != 0;
    if (readDword(L"ActivityShareAppName", v)) s.activity.shareAppName = v != 0;
    s.activity.hidden = readStrings(L"ActivityHidden");
    s.reduceMotion = systemReduceMotion();
    return s;
}

void writeSetting(const wchar_t *name, std::uint32_t value) {
    const DWORD v = value;
    RegSetKeyValueW(HKEY_CURRENT_USER, kSettingsKey, name, REG_DWORD, &v, sizeof v);
}

bool registerLogin(const std::wstring &exe) {
    if (exe.empty()) return false;
    const std::wstring command = runCommand(exe);
    return RegSetKeyValueW(HKEY_CURRENT_USER, kRunKey, kRunValue, REG_SZ, command.c_str(),
                           static_cast<DWORD>((command.size() + 1) * sizeof(wchar_t))) == ERROR_SUCCESS;
}

bool unregisterLogin() {
    const LSTATUS r = RegDeleteKeyValueW(HKEY_CURRENT_USER, kRunKey, kRunValue);
    return r == ERROR_SUCCESS || r == ERROR_FILE_NOT_FOUND;
}

bool loginRegistered() {
    DWORD size = 0;
    return RegGetValueW(HKEY_CURRENT_USER, kRunKey, kRunValue, RRF_RT_REG_SZ, nullptr, nullptr, &size) == ERROR_SUCCESS;
}

std::wstring exePath() {
    std::wstring path(MAX_PATH, L'\0');
    for (;;) {
        const DWORD n = GetModuleFileNameW(nullptr, path.data(), static_cast<DWORD>(path.size()));
        if (n == 0) return {};
        if (n < path.size()) {
            path.resize(n);
            return path;
        }
        if (path.size() >= 32768) return {};
        path.resize(path.size() * 2);
    }
}

#endif

} // namespace meowpuut::desk
