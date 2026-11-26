//! Native input-method registration, desk-pet launch, and macOS uninstall cleanup (FINAL.md §5, §7.3).
//!
//! - **macOS** (Steam's `installscript` cannot run processes on macOS): copies the bundled `Meowpuut.app` into
//!   `~/Library/Input Methods/Meowpuut.app` (no administrator password), runs `lsregister -f`, then calls
//!   `Meowpuut --register` (`TISRegisterInputSource` + `TISEnableInputSource`). Also registers/launches
//!   `Meowpuut Desk.app` when letting the cats out, and cleans up both on uninstall.
//! - **Windows**: Steam's elevated `Install.ps1 -Steam` registers the machine-wide DLLs (possibly under another admin
//!   account), so the companion calls `InstallLayoutOrTip` (`input.dll`) as the signed-in user to add 喵撲拼音 and
//!   喵撲注音 to the user's keyboard list, and launches `MeowpuutDesk.exe` non-elevated.

use crate::paths::Platform;
use crate::settings::{Change, SettingsStore};
use serde::Serialize;
use std::path::{Path, PathBuf};

/// Windows TSF CLSID and profile GUIDs (must stay in sync with `Windows/TextService/Install.ps1` and `TextService.cpp`).
pub const WIN_CLSID: &str = "{961C0ADA-4DC6-488F-9563-4FC144EFAE27}";
pub const WIN_PINYIN_PROFILE: &str = "{8FDE097D-3513-486A-9AB5-2D1B2C67C359}";
pub const WIN_ZHUYIN_PROFILE: &str = "{1F5D9D62-F4AF-4AAF-82A6-A482D7EC3AE2}";

/// Result of installing or checking the input method.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum InstallOutcome {
    /// Registered and enabled; ready to switch to.
    Enabled { detail: String },
    /// Registered, but the user still needs to add/enable it in System Settings.
    Registered { detail: String },
    /// Installed into Input Methods, but macOS needs one logout/login before listing it.
    NeedsLogout { detail: String },
    /// Bundled input method (or Windows machine registration) was not found.
    BundleMissing { detail: String },
    /// Installation failed.
    Failed { detail: String },
}

/// Builds the Windows `InstallLayoutOrTip` profile strings for a Chinese language id (`"0404"` or `"0804"`).
///
/// ```
/// use meowpuut_companion_lib::install::windows_tip_strings;
/// let tips = windows_tip_strings("0404", true);
/// assert_eq!(tips.len(), 2);
/// assert!(tips[0].starts_with("0404:{961C0ADA-4DC6-488F-9563-4FC144EFAE27}"));
/// ```
#[must_use]
pub fn windows_tip_strings(lang_id: &str, zhuyin_first: bool) -> [String; 2] {
    let pinyin = format!("{lang_id}:{WIN_CLSID}{WIN_PINYIN_PROFILE}");
    let zhuyin = format!("{lang_id}:{WIN_CLSID}{WIN_ZHUYIN_PROFILE}");
    if zhuyin_first { [zhuyin, pinyin] } else { [pinyin, zhuyin] }
}

/// Candidate locations for a bundled `.app` relative to the running executable.
#[must_use]
pub fn bundle_candidates(exe: Option<&Path>, cwd: Option<&Path>, app_name: &str, dev_rel: &str) -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Some(exe) = exe {
        if let Some(macos_dir) = exe.parent() {
            // Inside `Meowpuut Companion.app/Contents/Resources/<app_name>`
            if let Some(contents) = macos_dir.parent() {
                out.push(contents.join("Resources").join(app_name));
                // Sibling of `Meowpuut Companion.app` in the Steam depot root
                if let Some(app_bundle) = contents.parent() {
                    if let Some(depot_root) = app_bundle.parent() {
                        out.push(depot_root.join(app_name));
                    }
                }
            }
            out.push(macos_dir.join(app_name));
        }
    }
    if let Some(cwd) = cwd {
        out.push(cwd.join(dev_rel));
        if let Some(parent) = cwd.parent() {
            out.push(parent.join(dev_rel));
            if let Some(grand) = parent.parent() {
                out.push(grand.join(dev_rel));
            }
        }
    }
    out
}

/// Finds the first existing directory in `candidates`.
#[must_use]
pub fn first_existing_dir(candidates: &[PathBuf]) -> Option<PathBuf> {
    candidates.iter().find(|p| p.is_dir()).cloned()
}

/// Installs or registers the platform input source (step 3 of first-run setup).
pub fn install_input_source(platform: Platform, trial: bool) -> InstallOutcome {
    if trial || platform != Platform::native() {
        return InstallOutcome::Enabled { detail: "試用模式：不改動系統輸入法。".to_owned() };
    }
    #[cfg(target_os = "macos")]
    {
        macos::install_ime()
    }
    #[cfg(windows)]
    {
        windows_impl::install_tips()
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        InstallOutcome::BundleMissing { detail: "unsupported platform".to_owned() }
    }
}

/// Checks whether the input source is already registered/enabled without copying files.
pub fn check_input_source(platform: Platform, trial: bool) -> InstallOutcome {
    if trial || platform != Platform::native() {
        return InstallOutcome::Enabled { detail: "試用模式".to_owned() };
    }
    #[cfg(target_os = "macos")]
    {
        macos::check_ime()
    }
    #[cfg(windows)]
    {
        windows_impl::check_ime()
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        InstallOutcome::BundleMissing { detail: "unsupported platform".to_owned() }
    }
}

/// Updates `deskPets` in shared settings and, when turning on, launches/registers the desk-pet process.
pub fn set_desk_out(platform: Platform, settings: &dyn SettingsStore, out: bool, trial: bool) -> Result<bool, String> {
    settings.write(&Change::DeskPets(out)).map_err(|e| e.to_string())?;
    if !out || trial || platform != Platform::native() {
        return Ok(out);
    }
    #[cfg(target_os = "macos")]
    macos::ensure_desk_running();
    #[cfg(windows)]
    windows_impl::ensure_desk_running();
    Ok(true)
}

/// macOS uninstall cleanup: unregisters Desk login item, stops Desk & IME, removes `~/Library/Input Methods/Meowpuut.app`.
pub fn uninstall_system_components(platform: Platform, trial: bool) -> Result<String, String> {
    if trial || platform != Platform::native() {
        return Ok("試用模式：沒有改動系統元件。".to_owned());
    }
    #[cfg(target_os = "macos")]
    {
        macos::uninstall_components()
    }
    #[cfg(windows)]
    {
        Err("Windows 請由 Steam 解除安裝或執行 Uninstall.cmd。".to_owned())
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        Ok(String::new())
    }
}

#[cfg(target_os = "macos")]
mod macos {
    use super::{bundle_candidates, first_existing_dir, InstallOutcome};
    use std::path::{Path, PathBuf};
    use std::process::Command;

    const LSREGISTER: &str = "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister";

    fn user_ime_path() -> Option<PathBuf> {
        std::env::var_os("HOME").map(|h| PathBuf::from(h).join("Library/Input Methods/Meowpuut.app"))
    }

    fn system_ime_path() -> PathBuf {
        PathBuf::from("/Library/Input Methods/Meowpuut.app")
    }

    fn find_bundled_ime() -> Option<PathBuf> {
        let exe = std::env::current_exe().ok();
        let cwd = std::env::current_dir().ok();
        let mut cands = bundle_candidates(exe.as_deref(), cwd.as_deref(), "Meowpuut.app", ".build/Meowpuut.app");
        if let Some(u) = user_ime_path() {
            cands.push(u);
        }
        cands.push(system_ime_path());
        first_existing_dir(&cands)
    }

    fn find_desk_app() -> Option<PathBuf> {
        let exe = std::env::current_exe().ok();
        let cwd = std::env::current_dir().ok();
        let cands = bundle_candidates(exe.as_deref(), cwd.as_deref(), "Meowpuut Desk.app", ".build/desk/Meowpuut Desk.app");
        first_existing_dir(&cands)
    }

    fn run_register(bundle: &Path) -> InstallOutcome {
        let bin = bundle.join("Contents/MacOS/Meowpuut");
        if !bin.is_file() {
            return InstallOutcome::BundleMissing { detail: "找不到輸入法執行檔".to_owned() };
        }
        let Ok(out) = Command::new(&bin).arg("--register").arg(bundle).output() else {
            return InstallOutcome::Failed { detail: "無法啟動輸入法註冊工具".to_owned() };
        };
        let stdout = String::from_utf8_lossy(&out.stdout).trim().to_owned();
        let stderr = String::from_utf8_lossy(&out.stderr).trim().to_owned();
        match out.status.code() {
            Some(0) => {
                if stdout.contains("系統設定") {
                    InstallOutcome::Registered { detail: stdout }
                } else {
                    InstallOutcome::Enabled { detail: if stdout.is_empty() { "已加入輸入方式".to_owned() } else { stdout } }
                }
            }
            Some(1) => InstallOutcome::NeedsLogout { detail: if stderr.is_empty() { "請登出再登入一次".to_owned() } else { stderr } },
            _ => InstallOutcome::Failed { detail: if stderr.is_empty() { stdout } else { stderr } },
        }
    }

    pub fn install_ime() -> InstallOutcome {
        let Some(src) = find_bundled_ime() else {
            return InstallOutcome::BundleMissing { detail: "找不到隨附的 Meowpuut.app 輸入法套件".to_owned() };
        };
        let Some(dst) = user_ime_path() else {
            return InstallOutcome::Failed { detail: "找不到家目錄".to_owned() };
        };
        // If only system-wide /Library/Input Methods/Meowpuut.app exists, register it directly.
        if src == system_ime_path() && !dst.is_dir() {
            let _ = Command::new(LSREGISTER).arg("-f").arg(&src).status();
            return run_register(&src);
        }
        // Copy bundled IME into ~/Library/Input Methods/Meowpuut.app unless src is already dst.
        if src != dst {
            let Some(parent) = dst.parent() else {
                return InstallOutcome::Failed { detail: "路徑無效".to_owned() };
            };
            if let Err(e) = std::fs::create_dir_all(parent) {
                return InstallOutcome::Failed { detail: e.to_string() };
            }
            let partial = parent.join("Meowpuut.app.partial");
            let _ = std::fs::remove_dir_all(&partial);
            let copied = Command::new("/usr/bin/ditto")
                .args(["--noextattr", "--norsrc"])
                .arg(&src)
                .arg(&partial)
                .status();
            if !copied.is_ok_and(|s| s.success()) {
                let _ = std::fs::remove_dir_all(&partial);
                return InstallOutcome::Failed { detail: "複製輸入法套件失敗".to_owned() };
            }
            // Stop any running IME instance before swapping the bundle.
            let _ = Command::new("/usr/bin/pkill").args(["-x", "Meowpuut"]).status();
            let _ = std::fs::remove_dir_all(&dst);
            if let Err(e) = std::fs::rename(&partial, &dst) {
                let _ = std::fs::remove_dir_all(&partial);
                return InstallOutcome::Failed { detail: format!("無法更新輸入法套件：{e}") };
            }
        }
        let _ = Command::new(LSREGISTER).arg("-f").arg(&dst).status();
        run_register(&dst)
    }

    pub fn check_ime() -> InstallOutcome {
        if let Some(dst) = user_ime_path().filter(|p| p.is_dir()) {
            return run_register(&dst);
        }
        let sys = system_ime_path();
        if sys.is_dir() {
            return run_register(&sys);
        }
        InstallOutcome::BundleMissing { detail: "尚未複製到「輸入法」資料夾".to_owned() }
    }

    pub fn ensure_desk_running() {
        let Some(desk) = find_desk_app() else { return };
        let bin = desk.join("Contents/MacOS/Meowpuut Desk");
        if bin.is_file() {
            let _ = Command::new(&bin).arg("--register").status();
        }
        let _ = Command::new("/usr/bin/open").arg(&desk).spawn();
    }

    pub fn uninstall_components() -> Result<String, String> {
        let mut actions = Vec::new();
        if let Some(desk) = find_desk_app() {
            let bin = desk.join("Contents/MacOS/Meowpuut Desk");
            if bin.is_file() {
                let _ = Command::new(&bin).arg("--unregister").status();
                actions.push("已移除貓窩登入項目");
            }
        }
        let _ = Command::new("/usr/bin/pkill").args(["-x", "Meowpuut Desk"]).status();
        let _ = Command::new("/usr/bin/pkill").args(["-x", "Meowpuut"]).status();
        if let Some(dst) = user_ime_path().filter(|p| p.is_dir()) {
            std::fs::remove_dir_all(&dst).map_err(|e| format!("移除輸入法套件失敗：{e}"))?;
            actions.push("已移除 ~/Library/Input Methods/Meowpuut.app");
        }
        if actions.is_empty() {
            Ok("系統裡沒有需要清理的輸入法或貓窩項目。".to_owned())
        } else {
            Ok(actions.join("；"))
        }
    }
}

#[cfg(windows)]
mod windows_impl {
    use super::{windows_tip_strings, InstallOutcome, WIN_CLSID};
    use crate::locale::{system_script, Script};
    use std::path::PathBuf;
    use std::process::Command;
    use winreg::enums::HKEY_LOCAL_MACHINE;
    use winreg::RegKey;

    fn registered_dll() -> Option<PathBuf> {
        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        let key = hklm.open_subkey(format!("SOFTWARE\\Classes\\CLSID\\{WIN_CLSID}\\InprocServer32")).ok()?;
        let path: String = key.get_value("").ok()?;
        let p = PathBuf::from(path);
        p.is_file().then_some(p)
    }

    fn call_install_layout_or_tip(profile: &str) -> bool {
        use windows::core::{s, w};
        use windows::Win32::Foundation::FreeLibrary;
        use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};
        type FnInstall = unsafe extern "system" fn(*const u16, u32) -> i32;
        let wide: Vec<u16> = profile.encode_utf16().chain(std::iter::once(0)).collect();
        // SAFETY: loads system `input.dll`, resolves `InstallLayoutOrTip`, calls it with a NUL-terminated UTF-16 string,
        // and frees the module handle before returning.
        unsafe {
            let Ok(lib) = LoadLibraryW(w!("input.dll")) else { return false };
            let ok = GetProcAddress(lib, s!("InstallLayoutOrTip")).is_some_and(|proc| {
                let func: FnInstall = std::mem::transmute(proc);
                func(wide.as_ptr(), 0) != 0
            });
            let _ = FreeLibrary(lib);
            ok
        }
    }

    pub fn install_tips() -> InstallOutcome {
        if registered_dll().is_none() {
            return InstallOutcome::BundleMissing {
                detail: "系統尚未完成喵撲輸入法註冊，請確認 Steam 已完成初次啟動設定。".to_owned(),
            };
        }
        let lang = if system_script() == Script::Hans { "0804" } else { "0404" };
        let tips = windows_tip_strings(lang, lang == "0404");
        let mut ok_count = 0usize;
        for tip in &tips {
            if call_install_layout_or_tip(tip) {
                ok_count += 1;
            }
        }
        if ok_count == tips.len() {
            InstallOutcome::Enabled { detail: "已把喵撲拼音與喵撲注音加入中文鍵盤清單。".to_owned() }
        } else if ok_count > 0 {
            InstallOutcome::Registered { detail: "已加入部分鍵盤，請在語言設定確認。".to_owned() }
        } else {
            InstallOutcome::Registered { detail: "請在語言設定確認喵撲已加入，或登出再登入。".to_owned() }
        }
    }

    pub fn check_ime() -> InstallOutcome {
        if registered_dll().is_some() {
            InstallOutcome::Enabled { detail: "喵撲輸入法已註冊。".to_owned() }
        } else {
            InstallOutcome::BundleMissing { detail: "尚未偵測到已註冊的喵撲輸入法 DLL。".to_owned() }
        }
    }

    pub fn ensure_desk_running() {
        let desk = registered_dll()
            .and_then(|dll| dll.parent().map(|dir| dir.join("MeowpuutDesk.exe")))
            .filter(|p| p.is_file())
            .or_else(|| {
                let exe = std::env::current_exe().ok()?;
                let dir = exe.parent()?;
                ["x64", "arm64"].iter().map(|a| dir.join(a).join("MeowpuutDesk.exe")).find(|p| p.is_file())
            });
        let Some(desk) = desk else { return };
        let _ = Command::new(&desk).arg("--register-login").status();
        let _ = Command::new(&desk).spawn();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings::{FileRegistry, MacSettings, WindowsSettings};

    #[test]
    fn windows_tips_order_matches_script() {
        let tw = windows_tip_strings("0404", true);
        assert!(tw[0].ends_with(WIN_ZHUYIN_PROFILE));
        assert!(tw[1].ends_with(WIN_PINYIN_PROFILE));
        let cn = windows_tip_strings("0804", false);
        assert!(cn[0].ends_with(WIN_PINYIN_PROFILE));
        assert!(cn[1].ends_with(WIN_ZHUYIN_PROFILE));
    }

    #[test]
    fn bundle_candidates_checks_resources_depot_and_dev() {
        let exe = Path::new("/steam/Meowpuut Companion.app/Contents/MacOS/meowpuut-companion");
        let cwd = Path::new("/repo/Companion");
        let cands = bundle_candidates(Some(exe), Some(cwd), "Meowpuut.app", ".build/Meowpuut.app");
        assert!(cands.contains(&PathBuf::from("/steam/Meowpuut Companion.app/Contents/Resources/Meowpuut.app")));
        assert!(cands.contains(&PathBuf::from("/steam/Meowpuut.app")));
        assert!(cands.contains(&PathBuf::from("/repo/.build/Meowpuut.app")));
    }

    #[test]
    fn trial_mode_never_mutates_host_and_writes_desk_setting() {
        let tmp = tempfile::tempdir().unwrap();
        let mac = MacSettings::new(tmp.path(), false);
        assert!(matches!(install_input_source(Platform::Mac, true), InstallOutcome::Enabled { .. }));
        assert!(matches!(check_input_source(Platform::Mac, true), InstallOutcome::Enabled { .. }));
        assert_eq!(set_desk_out(Platform::Mac, &mac, false, true), Ok(false));
        assert!(!mac.read().unwrap().desk_pets);
        assert_eq!(set_desk_out(Platform::Mac, &mac, true, true), Ok(true));
        assert!(mac.read().unwrap().desk_pets);
        let win = WindowsSettings::new(FileRegistry::new(tmp.path()), false);
        assert_eq!(set_desk_out(Platform::Windows, &win, false, true), Ok(false));
        assert!(!win.read().unwrap().desk_pets);
        assert!(uninstall_system_components(Platform::Mac, true).is_ok());
    }
}
