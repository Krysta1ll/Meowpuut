//! Where the companion finds the input method's files, and which platform's rules it follows.
//!
//! - macOS: `~/Library/Application Support/Meowpuut/` (the input method's `Preferences.directory`).
//! - Windows: `%LOCALAPPDATA%\Meowpuut\` (the text service's `PetLedger::defaultDirectory`).
//!
//! `MEOWPUUT_DATA_DIR` replaces that folder (development and tests: never touch a real ledger while trying things).
//! `MEOWPUUT_PLATFORM=mac|windows` draws the other platform's keyboard and uses its settings rules; with it set to the
//! platform the app is not running on, settings go to a file in the data folder, never to the real store.

use serde::Serialize;
use std::env;
use std::path::PathBuf;

pub const DATA_DIR_ENV: &str = "MEOWPUUT_DATA_DIR";
pub const PLATFORM_ENV: &str = "MEOWPUUT_PLATFORM";

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    Mac,
    Windows,
}

impl Platform {
    /// The platform the binary was built for.
    pub const fn native() -> Self {
        if cfg!(windows) {
            Self::Windows
        } else {
            Self::Mac
        }
    }

    /// The platform whose rules the app follows (the native one unless `MEOWPUUT_PLATFORM` says otherwise).
    pub fn current() -> Self {
        match env::var(PLATFORM_ENV).ok().as_deref().map(str::to_ascii_lowercase).as_deref() {
            Some("windows" | "win") => Self::Windows,
            Some("mac" | "macos") => Self::Mac,
            _ => Self::native(),
        }
    }
}

/// The folder holding `PetJournal.bin` (and on macOS `settings.json`).
#[derive(Clone, Debug)]
pub struct DataDir {
    pub path: PathBuf,
    /// True when `MEOWPUUT_DATA_DIR` chose it.
    pub overridden: bool,
}

pub fn data_dir() -> Option<DataDir> {
    if let Some(value) = env::var_os(DATA_DIR_ENV).filter(|v| !v.is_empty()) {
        return Some(DataDir { path: PathBuf::from(value), overridden: true });
    }
    default_data_dir().map(|path| DataDir { path, overridden: false })
}

#[cfg(target_os = "macos")]
fn default_data_dir() -> Option<PathBuf> {
    env::var_os("HOME").map(|home| PathBuf::from(home).join("Library/Application Support/Meowpuut"))
}

/// `%LOCALAPPDATA%` is FOLDERID_LocalAppData, which the text service asks the shell for.
#[cfg(windows)]
fn default_data_dir() -> Option<PathBuf> {
    env::var_os("LOCALAPPDATA").map(|dir| PathBuf::from(dir).join("Meowpuut"))
}

#[cfg(not(any(target_os = "macos", windows)))]
fn default_data_dir() -> Option<PathBuf> {
    env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .or_else(|| env::var_os("HOME").map(|home| PathBuf::from(home).join(".local/share")))
        .map(|dir| dir.join("Meowpuut"))
}
