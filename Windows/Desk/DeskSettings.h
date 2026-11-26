#pragma once
// The desk-pet process's settings and its login item (PETS.md §7).
//
// HKCU\Software\Meowpuut, shared with the text service and the companion app:
//   DeskPets     DWORD, default 1. 0: no desk pets; the process exits at once.
//   TypingSeat   DWORD, default 0. 0: 乙, Momo on the candidate glass and
//                Mimimo along your window's top edge ("walk with typing" on);
//                1: 甲, both on the glass.
//   DeskCatsOut  DWORD, default 3. Which cats are out on the desk (bit 0
//                Mimimo, bit 1 Momo): calling them home is kept across sign-ins.
//   ActivityStats   DWORD, default 0. 「記錄時間花在哪裡」 (FINAL.md §11,
//                ForegroundWatcher.h): 1 senses the foreground app and idle
//                and counts minutes per category into Activity.bin.
//   ActivityPaused  DWORD, default 0. 1: recording paused (nothing counted).
//   ActivityHidden  REG_MULTI_SZ, default empty. Exe names never recorded.
//   ActivityShareAppName  DWORD, default 0. 「顯示 App 名稱」: 1 lets the desk read the
//                foreground app's display name (FileDescription, else the exe name) and
//                publish it in the section's `now`, memory only. The companion app writes
//                1 exactly while sharing is on AND the switch is on, else 0.
// Reduce Motion follows the system (SPI_GETCLIENTAREAANIMATION).
//
// Login item: HKCU\Software\Microsoft\Windows\CurrentVersion\Run, value
// MeowpuutDesk = "<path of MeowpuutDesk.exe>". Never Steam: the pet process
// must not count as playing (FINAL.md §7.1).
#include "../../Engine/crates/meowpuut-ffi/include/meowpuut.h"
#include "ForegroundWatcher.h"

#include <cstdint>
#include <string>

namespace meowpuut::desk {

struct DeskSettings {
    bool deskPets = true;
    std::uint32_t typingSeat = MP_DESK_TYPING_SPLIT;
    std::uint32_t catsOut = 3;
    bool reduceMotion = false;
    bool quiet = false; // 安靜一小時: only what you start (not stored)
    double catScale = 1.0;
    ActivitySwitches activity;
};

// The core's settings: the clay cats as they ship (SLEEP|PEEK|SIDE|WALK = 116:
// they walk with your typing, one frame per key), the medium size.
MPDeskSettings coreSettings(const DeskSettings &s);
// The Run value for an executable path: the path in quotes.
std::wstring runCommand(const std::wstring &exe);

#ifdef _WIN32
inline constexpr const wchar_t *kSettingsKey = L"Software\\Meowpuut";
inline constexpr const wchar_t *kRunKey = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
inline constexpr const wchar_t *kRunValue = L"MeowpuutDesk";

DeskSettings readSettings();
bool systemReduceMotion();
void writeSetting(const wchar_t *name, std::uint32_t value);
bool registerLogin(const std::wstring &exe);
bool unregisterLogin();
bool loginRegistered();
std::wstring exePath();
#endif

} // namespace meowpuut::desk
