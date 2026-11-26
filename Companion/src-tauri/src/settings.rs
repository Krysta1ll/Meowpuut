//! The few input method settings the companion's pages show, behind one trait.
//!
//! - macOS ([`MacSettings`]): `settings.json` beside the ledger, as `docs/SETTINGS.md` describes. A write locks
//!   `settings.lock`, reads the file, changes only its own key (every other key, known or not, goes back as it was),
//!   writes a temporary file, renames it over, unlocks, and posts the `app.meowpuut.settings.changed` distributed
//!   notification (the input method also watches the folder, so a lost notification only delays it).
//! - Windows ([`WindowsSettings`]): `HKCU\Software\Meowpuut`, the DWORDs the text service reads (`Simplified`,
//!   `MixedJapanese`, `CandidateTextSize`; see `Windows/README.md`). The input mode is not a setting there: both
//!   keyboards (Pinyin and Zhuyin) are installed and Windows' own keyboard list picks one.
//!
//! Both also hold what the desk-pet process 喵撲貓窩 reads (FINAL.md §4.2, §11.1; `docs/SETTINGS.md`,
//! `Windows/README.md`): 桌面貓 (`deskPets` / `DeskPets`), the typing seat (`typingSeat` split|glass / `TypingSeat`
//! 0|1), and 時間花在哪裡's record switch, pause and hidden apps (`activityStats`, `activityPaused`, `activityHidden` /
//! `ActivityStats`, `ActivityPaused`, `ActivityHidden` as REG_MULTI_SZ). On Windows also `ActivityShareAppName` (DWORD,
//! default 0), which gates the desk's reading of app display names: the companion sets it to 1 exactly while sharing
//! and 「顯示 App 名稱」 are on (see `AppState::sync_app_name_gate`). The companion only writes them; the desk
//! picks them up at once (the distributed notification on macOS, `RegNotifyChangeKeyValue` on Windows).
//!
//! The candidate window's skin (`candidateSkin` on macOS: "glass" | "glyph"; `CandidateSkin` DWORD on Windows: 0 glass,
//! 1 glyph) follows the skin worn in the closet (`Companion.json` `skin`): [`CandidateSkin::worn`] maps it and
//! [`sync_candidate_skin`] writes it when the companion starts and whenever the worn skin changes. Only the companion
//! writes it; the input methods read it and switch live.
//!
//! Missing values read as the input method's own fallbacks (`Preferences` in `Sources/Engine.swift`, the getters in
//! `Windows/TextService/TextService.cpp` and `Windows/UI/CandidateWindow.cpp`).

use crate::files::{self, FileLock, LOCK_ATTEMPTS};
use crate::paths::Platform;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::fmt;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const PALETTES: [&str; 6] = ["rose", "ocean", "sage", "lavender", "amber", "graphite"];
pub const SHAPES: [&str; 3] = ["capsule", "wave", "cloud"];
const INPUT_MODES: [&str; 2] = ["zhuyin", "pinyin"];
/// `MP_ACTIVITY_MAX_RULES`: the desk takes at most this many hidden apps.
pub const MAX_HIDDEN: usize = 256;
/// Longest identifier the desk keeps (bytes).
pub const MAX_HIDDEN_BYTES: usize = 255;

/// 打字時貓在哪裡 (FINAL.md §4.2): 乙 split (default: Momo on the candidate glass, Mimimo walks along the top edge of the
/// window you type in) or 甲 glass (both on the glass; "walk with typing: off").
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TypingSeat {
    #[default]
    Split,
    Glass,
}

impl TypingSeat {
    fn key(self) -> &'static str {
        match self {
            Self::Split => "split",
            Self::Glass => "glass",
        }
    }

    /// The desk reads anything but `glass` as split.
    fn from_key(key: &str) -> Self {
        if key == "glass" {
            Self::Glass
        } else {
            Self::Split
        }
    }

    /// `TypingSeat` DWORD: 0 split, 1 glass (anything else reads as split, as `Windows/Desk/DeskSettings.cpp` does).
    fn dword(self) -> u32 {
        match self {
            Self::Split => 0,
            Self::Glass => 1,
        }
    }

    fn from_dword(value: u32) -> Self {
        if value == 1 {
            Self::Glass
        } else {
            Self::Split
        }
    }
}

/// Which candidate window the input method draws: 日常玻璃 (default) or 字景 (wardrobe No.04, `Companion.json` skin "glyph").
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CandidateSkin {
    #[default]
    Glass,
    Glyph,
}

impl CandidateSkin {
    /// The candidate window of a closet skin: 字景 for "glyph", the glass for every other skin (the other three skins
    /// change the pages and the home keyboard only).
    pub fn worn(closet_skin: &str) -> Self {
        if closet_skin == "glyph" {
            Self::Glyph
        } else {
            Self::Glass
        }
    }

    fn key(self) -> &'static str {
        match self {
            Self::Glass => "glass",
            Self::Glyph => "glyph",
        }
    }

    /// The input method reads anything but `glyph` as the glass.
    fn from_key(key: &str) -> Self {
        if key == "glyph" {
            Self::Glyph
        } else {
            Self::Glass
        }
    }

    /// `CandidateSkin` DWORD: 0 glass, 1 glyph (anything else reads as the glass).
    fn dword(self) -> u32 {
        match self {
            Self::Glass => 0,
            Self::Glyph => 1,
        }
    }

    fn from_dword(value: u32) -> Self {
        if value == 1 {
            Self::Glyph
        } else {
            Self::Glass
        }
    }
}

/// Makes the input method's candidate skin the one the closet wears. Writes (and so wakes the input method) only when
/// it differs; returns whether it wrote.
pub fn sync_candidate_skin(store: &dyn SettingsStore, closet_skin: &str) -> Result<bool, SettingsError> {
    let want = CandidateSkin::worn(closet_skin);
    if store.read()?.candidate_skin == want {
        return Ok(false);
    }
    store.write(&Change::CandidateSkin(want))?;
    Ok(true)
}

/// The hidden-apps list as the desk keeps it: trimmed, no blanks, at most 255 bytes each, no repeats (ignoring case),
/// in the order given, at most [`MAX_HIDDEN`].
pub fn clean_hidden(ids: &[String]) -> Vec<String> {
    let mut seen = std::collections::BTreeSet::new();
    ids.iter()
        .map(|id| id.trim())
        .filter(|id| !id.is_empty() && id.len() <= MAX_HIDDEN_BYTES && !id.chars().any(char::is_control))
        .filter(|id| seen.insert(id.to_lowercase()))
        .take(MAX_HIDDEN)
        .map(str::to_owned)
        .collect()
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TextSize {
    Small,
    #[default]
    Standard,
    Large,
}

impl TextSize {
    fn key(self) -> &'static str {
        match self {
            Self::Small => "small",
            Self::Standard => "standard",
            Self::Large => "large",
        }
    }

    fn from_key(key: &str) -> Option<Self> {
        match key {
            "small" => Some(Self::Small),
            "standard" => Some(Self::Standard),
            "large" => Some(Self::Large),
            _ => None,
        }
    }

    /// `enum class CandidateTextSize { Small, Standard, Large }` in `Windows/UI/CandidateLayout.h`.
    fn dword(self) -> u32 {
        match self {
            Self::Small => 0,
            Self::Standard => 1,
            Self::Large => 2,
        }
    }

    fn from_dword(value: u32) -> Self {
        match value {
            0 => Self::Small,
            2 => Self::Large,
            _ => Self::Standard,
        }
    }
}

/// What the pages show. `None` means this platform's input method has no such setting (yet).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    /// `zhuyin`, `pinyin` (or another mode the input method knows, shown as is).
    pub input_mode: Option<String>,
    pub simplified: bool,
    pub mix_japanese: bool,
    pub candidate_text_size: TextSize,
    /// Which candidate window is drawn (`candidateSkin` / `CandidateSkin`).
    pub candidate_skin: CandidateSkin,
    /// 日常玻璃's colour family (`candidatePalette`).
    pub palette: Option<String>,
    /// 日常玻璃's shape (`glassShape`).
    pub glass_shape: Option<String>,
    /// 桌面貓: the cats on the desktop (喵撲貓窩). Default on.
    pub desk_pets: bool,
    /// macOS `catGlassSkin` (米米莫與莫莫, the input method's menu): off means no cats on the desktop either. Windows
    /// has no such switch (always true).
    pub cats_on: bool,
    pub typing_seat: TypingSeat,
    /// 記錄時間花在哪裡 (Steam version). Default off.
    pub activity_stats: bool,
    /// 暫停記錄.
    pub activity_paused: bool,
    /// Apps never recorded nor named to friends: macOS bundle ids, Windows executable names.
    pub activity_hidden: Vec<String>,
}

/// One change the pages can make.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(tag = "key", content = "value", rename_all = "camelCase")]
pub enum Change {
    InputMode(String),
    Simplified(bool),
    MixJapanese(bool),
    CandidateTextSize(TextSize),
    Palette(String),
    GlassShape(String),
    DeskPets(bool),
    TypingSeat(TypingSeat),
    ActivityStats(bool),
    ActivityPaused(bool),
    /// The whole list (the page adds and removes); cleaned with [`clean_hidden`] before it is written.
    ActivityHidden(Vec<String>),
    /// Windows only (`ActivityShareAppName`): lets the desk read the foreground app's display name. Written by the
    /// companion itself, never by a page: 1 exactly while sharing and 「顯示 App 名稱」 are on, else 0. On macOS the
    /// desk is asked over XPC instead, so this is [`SettingsError::Unsupported`] there.
    ActivityShareAppName(bool),
    /// The candidate window's skin; written by the companion itself when the closet's worn skin changes.
    CandidateSkin(CandidateSkin),
}

#[derive(Debug)]
pub enum SettingsError {
    Io(io::Error),
    Busy,
    /// This platform's input method has no such setting.
    Unsupported(&'static str),
    /// Not one of the values the input method knows.
    Invalid(String),
}

impl fmt::Display for SettingsError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(f, "settings: {error}"),
            Self::Busy => write!(f, "settings are being written by the input method; try again"),
            Self::Unsupported(what) => write!(f, "{what} is not a setting on this platform"),
            Self::Invalid(value) => write!(f, "not a value the input method knows: {value}"),
        }
    }
}

impl std::error::Error for SettingsError {}

impl From<io::Error> for SettingsError {
    fn from(error: io::Error) -> Self {
        if error.kind() == io::ErrorKind::WouldBlock {
            Self::Busy
        } else {
            Self::Io(error)
        }
    }
}

/// The settings of one platform's input method.
pub trait SettingsStore: Send + Sync {
    fn platform(&self) -> Platform;
    /// Where the values live, for the privacy page ("打開檔案位置").
    fn location(&self) -> String;
    fn read(&self) -> Result<Settings, SettingsError>;
    /// Applies one change and returns the settings as they now are.
    fn write(&self, change: &Change) -> Result<Settings, SettingsError>;
}

fn check(value: &str, allowed: &[&str]) -> Result<(), SettingsError> {
    if allowed.contains(&value) {
        Ok(())
    } else {
        Err(SettingsError::Invalid(value.to_owned()))
    }
}

// ---- macOS: settings.json ------------------------------------------------------------------------------------------

pub struct MacSettings {
    dir: PathBuf,
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    notify: bool,
}

impl MacSettings {
    pub const FILE: &'static str = "settings.json";
    pub const LOCK: &'static str = "settings.lock";
    pub const SCHEMA: u64 = 1;
    pub const CHANGED: &'static str = "app.meowpuut.settings.changed";
    const MAX_FILE: u64 = 1_048_576;

    /// `notify`: post the distributed notification after a write (off in tests and when previewing).
    pub fn new(dir: impl Into<PathBuf>, notify: bool) -> Self {
        Self { dir: dir.into(), notify }
    }

    fn file(&self) -> PathBuf {
        self.dir.join(Self::FILE)
    }

    /// The `settings` object; empty when the file is missing. A damaged file reads as empty too (the input method
    /// keeps its values in memory then; the next write rebuilds the file, as its own writes do).
    fn values(path: &Path) -> io::Result<Map<String, Value>> {
        let bytes = files::read_limited(path, Self::MAX_FILE)?;
        Ok(serde_json::from_slice::<Value>(&bytes)
            .ok()
            .and_then(|root| root.get("settings").and_then(Value::as_object).cloned())
            .unwrap_or_default())
    }

    fn parse(values: &Map<String, Value>) -> Settings {
        let string = |key: &str| values.get(key).and_then(Value::as_str);
        let flag = |key: &str| values.get(key).and_then(Value::as_bool);
        let pick = |key: &str, allowed: &[&str], fallback: &str| {
            string(key).filter(|v| allowed.contains(v)).unwrap_or(fallback).to_owned()
        };
        Settings {
            input_mode: Some(string("inputMode").filter(|v| !v.is_empty()).unwrap_or("zhuyin").to_owned()),
            simplified: flag("simplified").unwrap_or(false),
            mix_japanese: flag("mixJapanese").unwrap_or(false),
            candidate_text_size: string("candidateTextSize").and_then(TextSize::from_key).unwrap_or_default(),
            candidate_skin: string("candidateSkin").map(CandidateSkin::from_key).unwrap_or_default(),
            palette: Some(pick("candidatePalette", &PALETTES, "rose")),
            glass_shape: Some(pick("glassShape", &SHAPES, "capsule")),
            desk_pets: flag("deskPets").unwrap_or(true),
            cats_on: flag("catGlassSkin").unwrap_or(true),
            typing_seat: string("typingSeat").map(TypingSeat::from_key).unwrap_or_default(),
            activity_stats: flag("activityStats").unwrap_or(false),
            activity_paused: flag("activityPaused").unwrap_or(false),
            activity_hidden: clean_hidden(
                &values
                    .get("activityHidden")
                    .and_then(Value::as_array)
                    .map(|items| items.iter().filter_map(Value::as_str).map(str::to_owned).collect::<Vec<_>>())
                    .unwrap_or_default(),
            ),
        }
    }

    fn entry(change: &Change) -> Result<(&'static str, Value), SettingsError> {
        Ok(match change {
            Change::InputMode(mode) => {
                check(mode, &INPUT_MODES)?;
                ("inputMode", Value::from(mode.as_str()))
            }
            Change::Simplified(on) => ("simplified", Value::from(*on)),
            Change::MixJapanese(on) => ("mixJapanese", Value::from(*on)),
            Change::CandidateTextSize(size) => ("candidateTextSize", Value::from(size.key())),
            Change::Palette(palette) => {
                check(palette, &PALETTES)?;
                ("candidatePalette", Value::from(palette.as_str()))
            }
            Change::GlassShape(shape) => {
                check(shape, &SHAPES)?;
                ("glassShape", Value::from(shape.as_str()))
            }
            Change::DeskPets(on) => ("deskPets", Value::from(*on)),
            Change::TypingSeat(seat) => ("typingSeat", Value::from(seat.key())),
            Change::ActivityStats(on) => ("activityStats", Value::from(*on)),
            Change::ActivityPaused(on) => ("activityPaused", Value::from(*on)),
            Change::ActivityHidden(ids) => ("activityHidden", Value::from(clean_hidden(ids))),
            Change::ActivityShareAppName(_) => return Err(SettingsError::Unsupported("the desk's app-name gate (XPC asks instead)")),
            Change::CandidateSkin(skin) => ("candidateSkin", Value::from(skin.key())),
        })
    }

    fn post_changed(&self) {
        #[cfg(target_os = "macos")]
        if self.notify {
            notify::post(Self::CHANGED);
        }
    }
}

impl SettingsStore for MacSettings {
    fn platform(&self) -> Platform {
        Platform::Mac
    }

    fn location(&self) -> String {
        self.file().display().to_string()
    }

    fn read(&self) -> Result<Settings, SettingsError> {
        Ok(Self::parse(&Self::values(&self.file())?))
    }

    fn write(&self, change: &Change) -> Result<Settings, SettingsError> {
        let (key, value) = Self::entry(change)?;
        let settings = {
            let _lock = FileLock::acquire(&self.dir.join(Self::LOCK), LOCK_ATTEMPTS)?;
            let mut values = Self::values(&self.file())?;
            values.insert(key.to_owned(), value);
            let document = serde_json::json!({ "schema": Self::SCHEMA, "settings": Value::Object(values.clone()) });
            let bytes = serde_json::to_vec_pretty(&document).map_err(io::Error::other)?;
            files::replace(&self.file(), &bytes)?;
            Self::parse(&values)
        };
        self.post_changed();
        Ok(settings)
    }
}

#[cfg(target_os = "macos")]
mod notify {
    use core_foundation::base::TCFType;
    use core_foundation::string::CFString;
    use core_foundation_sys::notification_center::{
        CFNotificationCenterGetDistributedCenter, CFNotificationCenterPostNotification,
    };

    /// Posts a distributed notification with no object and no user info (docs/SETTINGS.md step 4).
    pub fn post(name: &str) {
        let name = CFString::new(name);
        // SAFETY: the distributed center is a process-wide singleton; `name` outlives the call; object and userInfo
        // may be NULL; deliverImmediately is a Boolean.
        unsafe {
            CFNotificationCenterPostNotification(
                CFNotificationCenterGetDistributedCenter(),
                name.as_concrete_TypeRef(),
                std::ptr::null(),
                std::ptr::null(),
                1,
            );
        }
    }
}

// ---- Windows: HKCU\Software\Meowpuut ---------------------------------------------------------------------------------

/// The values under one key: DWORDs and one REG_MULTI_SZ. The real key is `HKCU\Software\Meowpuut`; tests and
/// previews use a stand-in.
pub trait Registry: Send + Sync {
    fn get_dword(&self, name: &str) -> io::Result<Option<u32>>;
    fn set_dword(&self, name: &str, value: u32) -> io::Result<()>;
    /// A REG_MULTI_SZ; `None` when missing or of another type.
    fn get_strings(&self, name: &str) -> io::Result<Option<Vec<String>>>;
    fn set_strings(&self, name: &str, value: &[String]) -> io::Result<()>;
    fn location(&self) -> String;
}

pub struct WindowsSettings<R: Registry> {
    registry: R,
    /// Shown when `Simplified` was never stored (the text service stores its first-run choice, so this is rare).
    simplified_fallback: bool,
}

impl<R: Registry> WindowsSettings<R> {
    pub const KEY: &'static str = r"HKCU\Software\Meowpuut";

    pub fn new(registry: R, simplified_fallback: bool) -> Self {
        Self { registry, simplified_fallback }
    }
}

impl<R: Registry> SettingsStore for WindowsSettings<R> {
    fn platform(&self) -> Platform {
        Platform::Windows
    }

    fn location(&self) -> String {
        self.registry.location()
    }

    fn read(&self) -> Result<Settings, SettingsError> {
        let r = &self.registry;
        Ok(Settings {
            input_mode: None,
            simplified: r.get_dword("Simplified")?.map_or(self.simplified_fallback, |v| v != 0),
            mix_japanese: r.get_dword("MixedJapanese")?.is_some_and(|v| v != 0),
            candidate_text_size: r.get_dword("CandidateTextSize")?.map_or(TextSize::Standard, TextSize::from_dword),
            candidate_skin: r.get_dword("CandidateSkin")?.map_or(CandidateSkin::Glass, CandidateSkin::from_dword),
            palette: None,
            glass_shape: None,
            desk_pets: r.get_dword("DeskPets")?.is_none_or(|v| v != 0),
            cats_on: true,
            typing_seat: r.get_dword("TypingSeat")?.map_or(TypingSeat::Split, TypingSeat::from_dword),
            activity_stats: r.get_dword("ActivityStats")?.is_some_and(|v| v != 0),
            activity_paused: r.get_dword("ActivityPaused")?.is_some_and(|v| v != 0),
            activity_hidden: clean_hidden(&r.get_strings("ActivityHidden")?.unwrap_or_default()),
        })
    }

    fn write(&self, change: &Change) -> Result<Settings, SettingsError> {
        let r = &self.registry;
        match change {
            Change::Simplified(on) => r.set_dword("Simplified", u32::from(*on))?,
            Change::MixJapanese(on) => r.set_dword("MixedJapanese", u32::from(*on))?,
            Change::CandidateTextSize(size) => r.set_dword("CandidateTextSize", size.dword())?,
            Change::DeskPets(on) => r.set_dword("DeskPets", u32::from(*on))?,
            Change::TypingSeat(seat) => r.set_dword("TypingSeat", seat.dword())?,
            Change::ActivityStats(on) => r.set_dword("ActivityStats", u32::from(*on))?,
            Change::ActivityPaused(on) => r.set_dword("ActivityPaused", u32::from(*on))?,
            Change::ActivityHidden(ids) => r.set_strings("ActivityHidden", &clean_hidden(ids))?,
            Change::ActivityShareAppName(on) => r.set_dword("ActivityShareAppName", u32::from(*on))?,
            Change::CandidateSkin(skin) => r.set_dword("CandidateSkin", skin.dword())?,
            Change::InputMode(_) => return Err(SettingsError::Unsupported("the input mode")),
            Change::Palette(_) => return Err(SettingsError::Unsupported("the colour family")),
            Change::GlassShape(_) => return Err(SettingsError::Unsupported("the glass shape")),
        }
        self.read()
    }
}

/// One stored value: what the stand-ins keep.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum RegValue {
    Dword(u32),
    Strings(Vec<String>),
}

/// In memory: tests.
#[derive(Default)]
pub struct MemoryRegistry {
    values: Mutex<BTreeMap<String, RegValue>>,
}

impl Registry for MemoryRegistry {
    fn get_dword(&self, name: &str) -> io::Result<Option<u32>> {
        Ok(match self.values.lock().unwrap_or_else(|e| e.into_inner()).get(name) {
            Some(RegValue::Dword(v)) => Some(*v),
            _ => None,
        })
    }
    fn set_dword(&self, name: &str, value: u32) -> io::Result<()> {
        self.values.lock().unwrap_or_else(|e| e.into_inner()).insert(name.to_owned(), RegValue::Dword(value));
        Ok(())
    }
    fn get_strings(&self, name: &str) -> io::Result<Option<Vec<String>>> {
        Ok(match self.values.lock().unwrap_or_else(|e| e.into_inner()).get(name) {
            Some(RegValue::Strings(v)) => Some(v.clone()),
            _ => None,
        })
    }
    fn set_strings(&self, name: &str, value: &[String]) -> io::Result<()> {
        self.values.lock().unwrap_or_else(|e| e.into_inner()).insert(name.to_owned(), RegValue::Strings(value.to_vec()));
        Ok(())
    }
    fn location(&self) -> String {
        "(memory)".to_owned()
    }
}

/// A JSON file standing in for the key: `MEOWPUUT_DATA_DIR` runs on Windows and Windows previews on a Mac, so trying
/// things never touches the real registry.
pub struct FileRegistry {
    file: PathBuf,
}

impl FileRegistry {
    pub const FILE: &'static str = "registry-preview.json";

    pub fn new(dir: &Path) -> Self {
        Self { file: dir.join(Self::FILE) }
    }

    fn load(&self) -> io::Result<BTreeMap<String, RegValue>> {
        let bytes = files::read_limited(&self.file, 262_144)?;
        Ok(serde_json::from_slice(&bytes).unwrap_or_default())
    }

    fn store(&self, name: &str, value: RegValue) -> io::Result<()> {
        let mut values = self.load()?;
        values.insert(name.to_owned(), value);
        if let Some(dir) = self.file.parent() {
            std::fs::create_dir_all(dir)?;
        }
        files::replace(&self.file, &serde_json::to_vec_pretty(&values).map_err(io::Error::other)?)
    }
}

impl Registry for FileRegistry {
    fn get_dword(&self, name: &str) -> io::Result<Option<u32>> {
        Ok(match self.load()?.remove(name) {
            Some(RegValue::Dword(v)) => Some(v),
            _ => None,
        })
    }
    fn set_dword(&self, name: &str, value: u32) -> io::Result<()> {
        self.store(name, RegValue::Dword(value))
    }
    fn get_strings(&self, name: &str) -> io::Result<Option<Vec<String>>> {
        Ok(match self.load()?.remove(name) {
            Some(RegValue::Strings(v)) => Some(v),
            _ => None,
        })
    }
    fn set_strings(&self, name: &str, value: &[String]) -> io::Result<()> {
        self.store(name, RegValue::Strings(value.to_vec()))
    }
    fn location(&self) -> String {
        format!("{} (stands in for {})", self.file.display(), WindowsSettings::<MemoryRegistry>::KEY)
    }
}

/// The real key.
#[cfg(windows)]
pub struct HkcuRegistry;

#[cfg(windows)]
impl Registry for HkcuRegistry {
    fn get_dword(&self, name: &str) -> io::Result<Option<u32>> {
        use winreg::enums::HKEY_CURRENT_USER;
        let key = match winreg::RegKey::predef(HKEY_CURRENT_USER).open_subkey(r"Software\Meowpuut") {
            Ok(key) => key,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error),
        };
        match key.get_value::<u32, _>(name) {
            Ok(value) => Ok(Some(value)),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
            // Present but not a DWORD: what the text service's RegGetValueW(RRF_RT_REG_DWORD) would ignore.
            Err(_) => Ok(None),
        }
    }
    fn set_dword(&self, name: &str, value: u32) -> io::Result<()> {
        use winreg::enums::HKEY_CURRENT_USER;
        let (key, _) = winreg::RegKey::predef(HKEY_CURRENT_USER).create_subkey(r"Software\Meowpuut")?;
        key.set_value(name, &value)
    }
    fn get_strings(&self, name: &str) -> io::Result<Option<Vec<String>>> {
        use winreg::enums::{RegType, HKEY_CURRENT_USER};
        let key = match winreg::RegKey::predef(HKEY_CURRENT_USER).open_subkey(r"Software\Meowpuut") {
            Ok(key) => key,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error),
        };
        match key.get_raw_value(name) {
            // The desk reads it with RegGetValueW(RRF_RT_REG_MULTI_SZ): any other type is ignored there too.
            Ok(raw) if raw.vtype == RegType::REG_MULTI_SZ => Ok(key.get_value::<Vec<String>, _>(name).ok()),
            Ok(_) => Ok(None),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(error),
        }
    }
    fn set_strings(&self, name: &str, value: &[String]) -> io::Result<()> {
        use winreg::enums::HKEY_CURRENT_USER;
        let (key, _) = winreg::RegKey::predef(HKEY_CURRENT_USER).create_subkey(r"Software\Meowpuut")?;
        // winreg writes a Vec<String> as REG_MULTI_SZ; an empty list removes the value (an empty REG_MULTI_SZ is
        // legal but some tools show it oddly).
        if value.is_empty() {
            return match key.delete_value(name) {
                Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
                _ => Ok(()),
            };
        }
        key.set_value(name, &value.to_vec())
    }
    fn location(&self) -> String {
        WindowsSettings::<MemoryRegistry>::KEY.to_owned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stores(dir: &Path) -> Vec<Box<dyn SettingsStore>> {
        vec![
            Box::new(MacSettings::new(dir, false)),
            Box::new(WindowsSettings::new(MemoryRegistry::default(), false)),
            Box::new(WindowsSettings::new(FileRegistry::new(&dir.join("win")), false)),
        ]
    }

    #[test]
    fn every_store_reads_defaults_and_keeps_what_it_writes() {
        let tmp = tempfile::tempdir().unwrap();
        for store in stores(tmp.path()) {
            let before = store.read().unwrap();
            assert!(!before.simplified && !before.mix_japanese, "{:?}", store.platform());
            assert_eq!(before.candidate_text_size, TextSize::Standard);
            store.write(&Change::Simplified(true)).unwrap();
            store.write(&Change::MixJapanese(true)).unwrap();
            let after = store.write(&Change::CandidateTextSize(TextSize::Large)).unwrap();
            assert_eq!(after, store.read().unwrap());
            assert!(after.simplified && after.mix_japanese);
            assert_eq!(after.candidate_text_size, TextSize::Large);
            store.write(&Change::Simplified(false)).unwrap();
            assert!(!store.read().unwrap().simplified);
        }
    }

    #[test]
    fn mac_writes_one_key_and_keeps_every_other_key() {
        let tmp = tempfile::tempdir().unwrap();
        let file = tmp.path().join(MacSettings::FILE);
        std::fs::write(
            &file,
            r#"{"schema":1,"settings":{"inputMode":"pinyin","candidatePins":{"ni":"妳"},"keySound":"typewriter","futureKey":[1,2]}}"#,
        )
        .unwrap();
        let store = MacSettings::new(tmp.path(), false);
        assert_eq!(store.read().unwrap().input_mode.as_deref(), Some("pinyin"));
        let after = store.write(&Change::InputMode("zhuyin".into())).unwrap();
        assert_eq!(after.input_mode.as_deref(), Some("zhuyin"));
        let root: Value = serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
        assert_eq!(root["schema"], 1);
        let settings = &root["settings"];
        assert_eq!(settings["inputMode"], "zhuyin");
        assert_eq!(settings["candidatePins"]["ni"], "妳");
        assert_eq!(settings["keySound"], "typewriter");
        assert_eq!(settings["futureKey"], serde_json::json!([1, 2]));
        assert!(!tmp.path().join(format!("{}.tmp{}", MacSettings::FILE, std::process::id())).exists());
    }

    #[test]
    fn mac_reads_the_input_methods_fallbacks_and_survives_a_damaged_file() {
        let tmp = tempfile::tempdir().unwrap();
        let store = MacSettings::new(tmp.path(), false);
        let fresh = store.read().unwrap();
        assert_eq!(fresh.input_mode.as_deref(), Some("zhuyin"));
        assert_eq!((fresh.palette.as_deref(), fresh.glass_shape.as_deref()), (Some("rose"), Some("capsule")));
        let file = tmp.path().join(MacSettings::FILE);
        std::fs::write(&file, r#"{"schema":1,"settings":{"candidatePalette":"neon","glassShape":7,"simplified":"yes"}}"#).unwrap();
        let odd = store.read().unwrap();
        assert_eq!((odd.palette.as_deref(), odd.glass_shape.as_deref(), odd.simplified), (Some("rose"), Some("capsule"), false));
        std::fs::write(&file, "not json").unwrap();
        assert_eq!(store.read().unwrap(), fresh);
        // As the input method does, the next write rebuilds the file.
        store.write(&Change::Palette("ocean".into())).unwrap();
        assert_eq!(store.read().unwrap().palette.as_deref(), Some("ocean"));
    }

    #[test]
    fn values_outside_the_lists_are_refused_and_nothing_is_written() {
        let tmp = tempfile::tempdir().unwrap();
        let store = MacSettings::new(tmp.path(), false);
        for change in [Change::InputMode("nine-key".into()), Change::Palette("neon".into()), Change::GlassShape("star".into())] {
            assert!(matches!(store.write(&change), Err(SettingsError::Invalid(_))));
        }
        assert!(!tmp.path().join(MacSettings::FILE).exists());
    }

    #[test]
    fn windows_has_no_input_mode_palette_or_shape_and_uses_the_text_services_dwords() {
        let registry = MemoryRegistry::default();
        registry.set_dword("CandidateTextSize", 0).unwrap();
        registry.set_dword("MixedJapanese", 1).unwrap();
        let store = WindowsSettings::new(registry, true);
        let read = store.read().unwrap();
        assert_eq!((read.input_mode, read.palette, read.glass_shape), (None, None, None));
        assert!(read.simplified, "an unset Simplified shows the system-language fallback");
        assert!(read.mix_japanese);
        assert_eq!(read.candidate_text_size, TextSize::Small);
        for change in [Change::InputMode("pinyin".into()), Change::Palette("rose".into()), Change::GlassShape("wave".into())] {
            assert!(matches!(store.write(&change), Err(SettingsError::Unsupported(_))));
        }
        store.write(&Change::CandidateTextSize(TextSize::Large)).unwrap();
        store.write(&Change::Simplified(false)).unwrap();
        assert_eq!(store.registry.get_dword("CandidateTextSize").unwrap(), Some(2));
        assert_eq!(store.registry.get_dword("Simplified").unwrap(), Some(0));
        // An unknown DWORD reads as the text service's default.
        store.registry.set_dword("CandidateTextSize", 9).unwrap();
        assert_eq!(store.read().unwrap().candidate_text_size, TextSize::Standard);
    }

    #[test]
    fn changes_arrive_from_the_page_as_tagged_json() {
        let parse = |s: &str| serde_json::from_str::<Change>(s).unwrap();
        assert_eq!(parse(r#"{"key":"inputMode","value":"pinyin"}"#), Change::InputMode("pinyin".into()));
        assert_eq!(parse(r#"{"key":"candidateTextSize","value":"large"}"#), Change::CandidateTextSize(TextSize::Large));
        assert_eq!(parse(r#"{"key":"mixJapanese","value":true}"#), Change::MixJapanese(true));
    }

    #[test]
    fn a_mac_write_waits_for_the_input_methods_lock() {
        let tmp = tempfile::tempdir().unwrap();
        let store = MacSettings::new(tmp.path(), false);
        let held = FileLock::acquire(&tmp.path().join(MacSettings::LOCK), 1).unwrap();
        let path = tmp.path().to_owned();
        let writer = std::thread::spawn(move || MacSettings::new(path, false).write(&Change::Simplified(true)).map(|s| s.simplified));
        std::thread::sleep(std::time::Duration::from_millis(100));
        assert!(!store.read().unwrap().simplified, "nothing written while the lock is held");
        drop(held);
        assert!(writer.join().unwrap().unwrap());
    }
    #[test]
    fn desk_and_activity_defaults_match_the_desk() {
        let tmp = tempfile::tempdir().unwrap();
        for store in stores(tmp.path()) {
            let s = store.read().unwrap();
            assert!(s.desk_pets && s.cats_on, "{:?}: desk pets on by default", store.platform());
            assert_eq!(s.typing_seat, TypingSeat::Split);
            assert!(!s.activity_stats && !s.activity_paused, "時間花在哪裡 is off by default");
            assert!(s.activity_hidden.is_empty());
        }
    }

    #[test]
    fn every_store_keeps_the_desk_and_activity_keys() {
        let tmp = tempfile::tempdir().unwrap();
        for store in stores(tmp.path()) {
            store.write(&Change::DeskPets(false)).unwrap();
            store.write(&Change::TypingSeat(TypingSeat::Glass)).unwrap();
            store.write(&Change::ActivityStats(true)).unwrap();
            store.write(&Change::ActivityPaused(true)).unwrap();
            let hidden = vec![" com.apple.Safari ".to_owned(), "COM.APPLE.SAFARI".to_owned(), "".to_owned(), "Code.exe".to_owned()];
            let s = store.write(&Change::ActivityHidden(hidden)).unwrap();
            assert_eq!(s, store.read().unwrap());
            assert!(!s.desk_pets && s.activity_stats && s.activity_paused, "{:?}", store.platform());
            assert_eq!(s.typing_seat, TypingSeat::Glass);
            assert_eq!(s.activity_hidden, ["com.apple.Safari", "Code.exe"], "trimmed, no blanks, no repeats ignoring case");
            // The other settings are untouched.
            assert!(!s.simplified);
            let s = store.write(&Change::ActivityHidden(Vec::new())).unwrap();
            assert!(s.activity_hidden.is_empty());
            store.write(&Change::TypingSeat(TypingSeat::Split)).unwrap();
            assert_eq!(store.read().unwrap().typing_seat, TypingSeat::Split);
        }
    }

    #[test]
    fn mac_writes_the_keys_docs_settings_names() {
        let tmp = tempfile::tempdir().unwrap();
        let store = MacSettings::new(tmp.path(), false);
        store.write(&Change::DeskPets(false)).unwrap();
        store.write(&Change::TypingSeat(TypingSeat::Glass)).unwrap();
        store.write(&Change::ActivityStats(true)).unwrap();
        store.write(&Change::ActivityPaused(false)).unwrap();
        store.write(&Change::ActivityHidden(vec!["com.apple.Music".into()])).unwrap();
        let root: Value = serde_json::from_slice(&std::fs::read(tmp.path().join(MacSettings::FILE)).unwrap()).unwrap();
        let settings = &root["settings"];
        assert_eq!(settings["deskPets"], false);
        assert_eq!(settings["typingSeat"], "glass");
        assert_eq!(settings["activityStats"], true);
        assert_eq!(settings["activityPaused"], false);
        assert_eq!(settings["activityHidden"], serde_json::json!(["com.apple.Music"]));
        // What the desk does with odd values: a wrong type is the default, an unknown seat is split.
        std::fs::write(
            tmp.path().join(MacSettings::FILE),
            r#"{"schema":1,"settings":{"deskPets":"no","typingSeat":"roof","activityStats":1,"activityHidden":["a.b",7,"A.B"],"catGlassSkin":false}}"#,
        )
        .unwrap();
        let s = store.read().unwrap();
        assert!(s.desk_pets && !s.activity_stats && !s.cats_on);
        assert_eq!(s.typing_seat, TypingSeat::Split);
        assert_eq!(s.activity_hidden, ["a.b"]);
    }

    #[test]
    fn mac_has_no_app_name_gate_xpc_asks_instead() {
        let tmp = tempfile::tempdir().unwrap();
        let store = MacSettings::new(tmp.path(), false);
        assert!(matches!(store.write(&Change::ActivityShareAppName(true)), Err(SettingsError::Unsupported(_))));
        assert!(!tmp.path().join(MacSettings::FILE).exists());
        let parse: Change = serde_json::from_str(r#"{"key":"activityShareAppName","value":true}"#).unwrap();
        assert_eq!(parse, Change::ActivityShareAppName(true));
    }

    #[test]
    fn windows_uses_the_desks_registry_values() {
        let store = WindowsSettings::new(MemoryRegistry::default(), false);
        store.write(&Change::DeskPets(false)).unwrap();
        store.write(&Change::TypingSeat(TypingSeat::Glass)).unwrap();
        store.write(&Change::ActivityStats(true)).unwrap();
        store.write(&Change::ActivityHidden(vec!["chrome.exe".into(), "Chrome.EXE".into()])).unwrap();
        let r = &store.registry;
        assert_eq!(r.get_dword("DeskPets").unwrap(), Some(0));
        assert_eq!(r.get_dword("TypingSeat").unwrap(), Some(1));
        assert_eq!(r.get_dword("ActivityStats").unwrap(), Some(1));
        assert_eq!(r.get_strings("ActivityHidden").unwrap(), Some(vec!["chrome.exe".to_owned()]));
        // 「顯示 App 名稱」's gate for the desk: absent is off; the companion writes 1 and 0.
        assert_eq!(r.get_dword("ActivityShareAppName").unwrap(), None);
        store.write(&Change::ActivityShareAppName(true)).unwrap();
        assert_eq!(r.get_dword("ActivityShareAppName").unwrap(), Some(1));
        store.write(&Change::ActivityShareAppName(false)).unwrap();
        assert_eq!(r.get_dword("ActivityShareAppName").unwrap(), Some(0));
        // A TypingSeat the desk does not know reads as split; a DWORD where a list should be is no list.
        r.set_dword("TypingSeat", 7).unwrap();
        r.set_dword("ActivityHidden", 1).unwrap();
        let s = store.read().unwrap();
        assert_eq!(s.typing_seat, TypingSeat::Split);
        assert!(s.activity_hidden.is_empty());
    }

    #[test]
    fn the_hidden_list_is_capped_like_the_desks() {
        let many: Vec<String> = (0..300).map(|i| format!("app.number{i}")).collect();
        assert_eq!(clean_hidden(&many).len(), MAX_HIDDEN);
        let long = "a".repeat(MAX_HIDDEN_BYTES + 1);
        assert!(clean_hidden(&[long, "tab\there".into()]).is_empty());
        let parse = |s: &str| serde_json::from_str::<Change>(s).unwrap();
        assert_eq!(parse(r#"{"key":"typingSeat","value":"glass"}"#), Change::TypingSeat(TypingSeat::Glass));
        assert_eq!(parse(r#"{"key":"activityHidden","value":["x.y"]}"#), Change::ActivityHidden(vec!["x.y".into()]));
        assert_eq!(parse(r#"{"key":"deskPets","value":false}"#), Change::DeskPets(false));
    }

    #[test]
    fn the_candidate_skin_follows_the_closet_on_both_platforms() {
        assert_eq!(CandidateSkin::worn("glyph"), CandidateSkin::Glyph);
        for other in ["daily", "classic", "pixel", "centenary", "", "nonsense"] {
            assert_eq!(CandidateSkin::worn(other), CandidateSkin::Glass, "{other}");
        }
        let tmp = tempfile::tempdir().unwrap();
        for store in stores(tmp.path()) {
            assert_eq!(store.read().unwrap().candidate_skin, CandidateSkin::Glass, "{:?}: the glass is the default", store.platform());
            // Wearing 字景 writes once; the same skin again writes nothing.
            assert!(sync_candidate_skin(store.as_ref(), "glyph").unwrap());
            assert_eq!(store.read().unwrap().candidate_skin, CandidateSkin::Glyph);
            assert!(!sync_candidate_skin(store.as_ref(), "glyph").unwrap());
            // Any other skin puts the glass back.
            assert!(sync_candidate_skin(store.as_ref(), "pixel").unwrap());
            assert_eq!(store.read().unwrap().candidate_skin, CandidateSkin::Glass);
            assert!(!sync_candidate_skin(store.as_ref(), "daily").unwrap());
            // The other settings are untouched.
            assert!(!store.read().unwrap().simplified);
        }
    }

    #[test]
    fn the_candidate_skin_is_written_where_the_input_methods_read_it() {
        let tmp = tempfile::tempdir().unwrap();
        let file = tmp.path().join(MacSettings::FILE);
        std::fs::write(&file, r#"{"schema":1,"settings":{"inputMode":"pinyin","keySound":"water"}}"#).unwrap();
        let mac = MacSettings::new(tmp.path(), false);
        mac.write(&Change::CandidateSkin(CandidateSkin::Glyph)).unwrap();
        let root: Value = serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
        assert_eq!(root["settings"]["candidateSkin"], "glyph");
        assert_eq!((root["settings"]["inputMode"].as_str(), root["settings"]["keySound"].as_str()), (Some("pinyin"), Some("water")));
        // A missing or unknown value is the glass.
        for odd in [r#"{"schema":1,"settings":{}}"#, r#"{"schema":1,"settings":{"candidateSkin":"pixel"}}"#, r#"{"schema":1,"settings":{"candidateSkin":3}}"#] {
            std::fs::write(&file, odd).unwrap();
            assert_eq!(mac.read().unwrap().candidate_skin, CandidateSkin::Glass, "{odd}");
        }
        let win = WindowsSettings::new(MemoryRegistry::default(), false);
        win.write(&Change::CandidateSkin(CandidateSkin::Glyph)).unwrap();
        assert_eq!(win.registry.get_dword("CandidateSkin").unwrap(), Some(1));
        win.write(&Change::CandidateSkin(CandidateSkin::Glass)).unwrap();
        assert_eq!(win.registry.get_dword("CandidateSkin").unwrap(), Some(0));
        // Anything but 1 reads as the glass, as the text service does.
        win.registry.set_dword("CandidateSkin", 5).unwrap();
        assert_eq!(win.read().unwrap().candidate_skin, CandidateSkin::Glass);
        let parse: Change = serde_json::from_str(r#"{"key":"candidateSkin","value":"glyph"}"#).unwrap();
        assert_eq!(parse, Change::CandidateSkin(CandidateSkin::Glyph));
    }
}
