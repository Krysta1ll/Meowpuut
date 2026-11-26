//! 喵撲 Meowpuut companion app: the Rust side of a Tauri 2 thin view.
//!
//! This process exists only while its window is open: closing the window ends it (FINAL.md §7.1). The one exception is
//! the opt-in sharing mode (§11.2, [`sharing`]): while 「讓 Steam 好友看到我在做什麼」 lasts, it keeps running without a
//! window, with a small icon to stop it. It reads the word-count ledger the input methods write and the time ledger
//! the desk writes, shows and changes a few input method and desk settings, keeps its own small `Companion.json`, and
//! is the only Meowpuut process that talks to Steam ([`steam`]). It never counts keys or reads text: the home keyboard
//! lights up from the window's own key events, inside this window only. No global hooks.

pub mod activity;
pub mod clock;
pub mod cloud;
pub mod companion;
pub mod desk_counters;
pub mod desk_now;
#[cfg(all(target_os = "macos", feature = "devcapture"))]
mod devcapture;
pub mod festivals;
pub mod files;
pub mod glyph;
pub mod growth;
pub mod install;
pub mod ledger;
pub mod locale;
pub mod lounge;
pub mod lounge_catalog;
pub mod lounge_cmd;
pub mod lounge_expedition;
pub mod lounge_paragon;
pub mod lounge_stories;
pub mod mobile_sync;
pub mod now_playing;
pub mod pad;
pub mod paths;
pub mod photos;
pub mod settings;
pub mod share;
pub mod shot;
pub mod sharing;
pub mod steam;
pub mod sync_inspect;

use activity::{ActivityLedger, ActivityView};
use cloud::CloudState;
use companion::{Companion, CompanionState, Patch, Share};
use ledger::{FileStatus, Ledger, LedgerError, Reading, Summary};
use meowpuut_pets::codec::PetState;
use meowpuut_activity::presence::Language;
use paths::{DataDir, Platform};
use serde::{Deserialize, Serialize};
use settings::{Change, FileRegistry, MacSettings, Settings, SettingsStore, WindowsSettings};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};
use std::time::Instant;
use steam::{AchievementView, Settlement, SteamApi};
use tauri::{AppHandle, Emitter, Manager, RunEvent};

/// Sent to the page when the settlement on open is done.
pub const SETTLED_EVENT: &str = "steam-settled";
/// Sent to the page when a new photo manifest has been cached.
pub const PHOTOS_EVENT: &str = "photos-updated";
/// How often a missing Steam connection is tried again (when sharing is turned on, or the window opens).
const STEAM_RETRY_MS: u128 = 20_000;

/// The cloud step of this open: what the page is told, and the other computers' ledgers merged into the view.
#[derive(Default)]
struct CloudSnapshot {
    state: CloudState,
    others: Vec<PetState>,
}

pub struct AppState {
    data: DataDir,
    platform: Platform,
    ledger: Ledger,
    activity: ActivityLedger,
    settings: Box<dyn SettingsStore>,
    pub(crate) companion: Companion,
    steam: Mutex<Box<dyn SteamApi>>,
    steam_error: Mutex<Option<String>>,
    steam_tried: Mutex<Option<Instant>>,
    settlement: Mutex<Option<Settlement>>,
    cloud: Mutex<CloudSnapshot>,
    sharing: sharing::Controller,
    /// What this run last wrote to the desk's `ActivityShareAppName` (Windows); None: not yet.
    app_name_gate: Mutex<Option<bool>>,
    /// 字景's objects (the scroll, the home keyboard), see [`glyph`].
    glyph: glyph::Glyph,
    /// 生活照: the cache and its network threads (leaked once: it lives as long as the process).
    photos: &'static photos::Photos,
    /// Monotonic process start instant for anti-macro pad guard and focus sessions.
    started_at: Instant,
    /// Active focus session start timestamp in monotonic milliseconds (`None` when idle).
    focus_started_ms: Mutex<Option<u64>>,
    /// In-game writing pad rate/entropy/daily guard.
    pad_guard: Mutex<pad::PadGuard>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl AppState {
    pub fn new(data: DataDir, platform: Platform) -> Self {
        let settings = settings_store(&data, platform);
        let data_path = data.path.clone();
        Self {
            ledger: Ledger::new(&data.path),
            activity: ActivityLedger::new(&data.path),
            companion: Companion::new(&data.path),
            settings,
            data,
            platform,
            steam: Mutex::new(Box::new(steam::NotLinked)),
            steam_error: Mutex::new(None),
            steam_tried: Mutex::new(None),
            settlement: Mutex::new(None),
            cloud: Mutex::new(CloudSnapshot::default()),
            sharing: sharing::Controller::default(),
            app_name_gate: Mutex::new(None),
            glyph: glyph::Glyph::default(),
            photos: Box::leak(Box::new(photos::Photos::new(&data_path))),
            started_at: Instant::now(),
            focus_started_ms: Mutex::new(None),
            pad_guard: Mutex::new(pad::PadGuard::default()),
        }
    }

    pub(crate) fn mono_ms(&self) -> u64 {
        u64::try_from(self.started_at.elapsed().as_millis()).unwrap_or(u64::MAX)
    }

    pub(crate) fn focus_started_ms(&self) -> MutexGuard<'_, Option<u64>> {
        lock(&self.focus_started_ms)
    }

    pub(crate) fn pad_guard(&self) -> MutexGuard<'_, pad::PadGuard> {
        lock(&self.pad_guard)
    }

    pub fn steam(&self) -> MutexGuard<'_, Box<dyn SteamApi>> {
        lock(&self.steam)
    }

    /// Connects to Steam if this build can and it is not connected yet (at most every [`STEAM_RETRY_MS`]).
    fn ensure_steam(&self) {
        if !steam::built() || self.steam().connected() {
            return;
        }
        let mut tried = lock(&self.steam_tried);
        if tried.is_some_and(|t| t.elapsed().as_millis() < STEAM_RETRY_MS) {
            return;
        }
        *tried = Some(Instant::now());
        match steam::connect() {
            Ok(client) => {
                *self.steam() = client;
                *lock(&self.steam_error) = None;
            }
            Err(error) => *lock(&self.steam_error) = Some(error),
        }
    }

    /// Steam Cloud (FINAL.md §6.4), once per open and whenever the switch changes: with the switch on, this computer's
    /// ledger goes up as its own file and the other computers' are read for the view; with it off, this computer's
    /// file is deleted (now, or on the next open with Steam). A trial folder never syncs.
    pub fn cloud_sync(&self) {
        self.ensure_steam();
        let Ok(mut companion) = self.companion.read() else { return };
        if companion.cloud_opt_in && !cloud::valid_install_id(&companion.install_id) {
            if let Ok(made) = self.companion.modify(CompanionState::ensure_install_id) {
                companion = made;
            }
        }
        let (local, upload) = match self.ledger.read() {
            Ok(r) => (r.state, matches!(r.status, FileStatus::Ok | FileStatus::Missing)),
            Err(_) => (PetState::default(), false),
        };
        let input = cloud::Input {
            opt_in: companion.cloud_opt_in,
            delete_pending: companion.cloud_delete_pending,
            install_id: &companion.install_id,
            trial: self.data.overridden,
            built: steam::built(),
        };
        let outcome = cloud::run(&mut **self.steam(), &input, &local, upload);
        if outcome.deleted {
            let _ = self.companion.modify(|c| c.cloud_delete_pending = false);
        }
        *lock(&self.cloud) = CloudSnapshot { state: outcome.state, others: outcome.others };
    }

    /// The ledger as the pages see it: this computer's file with the other computers' (Steam Cloud, as of this open)
    /// and any synced mobile envelope (`MobileSync.bin`) merged in. The local ledger file itself is never changed.
    pub(crate) fn view_reading(&self) -> Result<Reading, LedgerError> {
        let mut reading = self.ledger.read()?;
        let cloud = lock(&self.cloud);
        if !cloud.others.is_empty() {
            reading.state = cloud::merged(&reading.state, &cloud.others);
        }
        if let Some(mobile) = mobile_sync::read_mirror(&self.data.path) {
            reading.state.merge(&mobile.state);
        }
        Ok(reading)
    }

    /// [`AppState::view_reading`] for the 字景 commands.
    fn reading_for_glyph(&self) -> Result<Reading, String> {
        self.view_reading().map_err(text)
    }

    fn recording(&self) -> bool {
        self.settings.read().map(|s| s.activity_stats).unwrap_or(false)
    }

    /// The share switches and whether recording is on, for the loop.
    pub fn share_inputs(&self) -> (Share, bool) {
        (self.companion.read().map(|c| c.share).unwrap_or_default(), self.recording())
    }

    /// Windows: writes `ActivityShareAppName` (only when it differs from what this run last wrote, so the desk is not
    /// woken for nothing). The first call of a run always writes, which also clears a 1 left by a crashed run.
    fn write_app_name_gate(&self, on: bool, force: bool) {
        if self.platform != Platform::Windows {
            return;
        }
        let mut last = lock(&self.app_name_gate);
        if !force && *last == Some(on) {
            return;
        }
        if self.settings.write(&Change::ActivityShareAppName(on)).is_ok() {
            *last = Some(on);
        }
    }

    /// The desk reads app names only while this is 1: exactly while sharing and 「顯示 App 名稱」 are on (not off, not
    /// past its limit, recording on). Call after every change of those, on open and on every sharing tick.
    pub fn sync_app_name_gate(&self) {
        let (share, recording) = self.share_inputs();
        self.write_app_name_gate(share::app_name_gate(&share, recording, clock::now()), false);
    }

    /// Quitting: the desk must stop reading names, whatever was written before.
    pub fn close_app_name_gate(&self) {
        self.write_app_name_gate(false, true);
    }

    /// The loop should run: the switches allow it, the limit holds and Steam is there. A limit that ran out while
    /// the app was closed turns the switch off here.
    pub fn share_wanted(&self) -> bool {
        let (share, recording) = self.share_inputs();
        let now = clock::now();
        if share::expired(&share, now) {
            let _ = self.companion.modify(|c| c.share.on = false);
            return false;
        }
        if share::wanted(&share, recording, now) {
            self.ensure_steam();
            return self.steam().connected();
        }
        false
    }
}

/// macOS: settings.json in the data folder (the notification only when it is the real folder). Windows: the real
/// HKCU key only when running natively against the real folder; otherwise a stand-in file in the data folder.
fn settings_store(data: &DataDir, platform: Platform) -> Box<dyn SettingsStore> {
    let fallback = locale::system_script() == locale::Script::Hans;
    match platform {
        Platform::Mac => Box::new(MacSettings::new(&data.path, !data.overridden && Platform::native() == Platform::Mac)),
        Platform::Windows => {
            #[cfg(windows)]
            if !data.overridden {
                return Box::new(WindowsSettings::new(settings::HkcuRegistry, fallback));
            }
            Box::new(WindowsSettings::new(FileRegistry::new(&data.path), fallback))
        }
    }
}

fn text<E: std::fmt::Display>(error: E) -> String {
    error.to_string()
}

/// Settles achievements once (FINAL.md §6.2), off the main thread: connect to Steam if possible, bring the counters
/// up to date, unlock what is newly met, `StoreStats` once. Then the page is told, and sharing may start.
pub fn settle_on_open(app: AppHandle) {
    std::thread::spawn(move || {
        let state = app.state::<AppState>();
        state.ensure_steam();
        // Steam Cloud first, so the settlement below sees the merged totals.
        state.cloud_sync();
        if let Ok(reading) = state.view_reading() {
            let send_allowed = !state.data.overridden;
            let desk = desk_counters::read(&state.data.path);
            let mut result = None;
            let _ = state.companion.modify(|c| {
                let progress = steam::count_with_growth(&mut c.progress, &reading.state, &desk, &c.growth);
                let mut steam = state.steam();
                result = Some(steam::settle(&mut **steam, &reading.state, &progress, &mut c.progress.unlocked, c.achievements_on && send_allowed));
            });
            *lock(&state.settlement) = result;
        }
        let _ = app.emit(SETTLED_EVENT, ());
        state.sharing.sync(&app);
    });
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AppInfo {
    platform: Platform,
    native_platform: Platform,
    script: locale::Script,
    version: &'static str,
    data_dir: String,
    data_dir_overridden: bool,
    ledger_file: String,
    activity_file: String,
    settings_location: String,
    companion_file: String,
    /// `MEOWPUUT_START`: open on this page (previews and screenshots).
    start: Option<String>,
}

#[tauri::command]
fn app_info(state: tauri::State<'_, AppState>) -> AppInfo {
    AppInfo {
        platform: state.platform,
        native_platform: Platform::native(),
        script: locale::system_script(),
        version: env!("CARGO_PKG_VERSION"),
        data_dir: state.data.path.display().to_string(),
        data_dir_overridden: state.data.overridden,
        ledger_file: state.ledger.file().display().to_string(),
        activity_file: state.activity.file().display().to_string(),
        settings_location: state.settings.location(),
        companion_file: state.data.path.join(companion::FILE_NAME).display().to_string(),
        start: std::env::var("MEOWPUUT_START").ok().filter(|s| !s.is_empty()),
    }
}

#[tauri::command]
fn ledger_summary(state: tauri::State<'_, AppState>) -> Result<Summary, String> {
    let reading = state.view_reading().map_err(text)?;
    Ok(Summary::of(&state.ledger, &reading))
}

/// "清除貓咪紀錄": the page asks for confirmation first. The time ledger goes with it (FINAL.md §11.3), when there is one.
#[tauri::command]
fn ledger_clear(app: AppHandle, state: tauri::State<'_, AppState>) -> Result<Summary, String> {
    state.ledger.clear().map_err(text)?;
    if state.activity.exists() {
        state.activity.clear().map_err(text)?;
    }
    desk_counters::clear(&state.data.path);
    mobile_sync::clear_mirror(&state.data.path);
    // The counters and the list of unlocked achievements start over with the new epoch.
    settle_on_open(app);
    ledger_summary(state)
}

/// "打開檔案位置": shows the ledger in Finder / File Explorer (the folder when there is no file yet).
#[tauri::command]
fn reveal_ledger(state: tauri::State<'_, AppState>) -> Result<(), String> {
    reveal(&state.ledger.file(), state.ledger.dir()).map_err(text)
}

fn reveal(file: &Path, dir: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let exists = file.exists();
    #[cfg(target_os = "macos")]
    {
        let mut open = std::process::Command::new("/usr/bin/open");
        if exists {
            open.arg("-R").arg(file);
        } else {
            open.arg(dir);
        }
        open.spawn().map(drop)
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let mut explorer = std::process::Command::new("explorer.exe");
        if exists {
            explorer.raw_arg(format!("/select,\"{}\"", file.display()));
        } else {
            explorer.arg(dir);
        }
        explorer.spawn().map(drop)
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        let _ = exists;
        std::process::Command::new("xdg-open").arg(dir).spawn().map(drop)
    }
}

/// First run, step 3: the system's own keyboard settings, where 喵撲 is added by hand when adding it automatically
/// did not work (macOS: Keyboard › Input Sources; Windows: Language & region).
#[tauri::command]
fn open_input_settings() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    let result = std::process::Command::new("/usr/bin/open")
        .arg("x-apple.systempreferences:com.apple.Keyboard-Settings.extension")
        .spawn()
        .map(drop);
    #[cfg(windows)]
    let result = std::process::Command::new("explorer.exe").arg("ms-settings:regionlanguage").spawn().map(drop);
    #[cfg(not(any(target_os = "macos", windows)))]
    let result: std::io::Result<()> = Ok(());
    result.map_err(text)
}

#[tauri::command]
async fn install_input_source(state: tauri::State<'_, AppState>) -> Result<install::InstallOutcome, String> {
    let (platform, trial) = (state.platform, state.data.overridden);
    tauri::async_runtime::spawn_blocking(move || install::install_input_source(platform, trial)).await.map_err(text)
}

#[tauri::command]
async fn check_input_source(state: tauri::State<'_, AppState>) -> Result<install::InstallOutcome, String> {
    let (platform, trial) = (state.platform, state.data.overridden);
    tauri::async_runtime::spawn_blocking(move || install::check_input_source(platform, trial)).await.map_err(text)
}

#[tauri::command]
async fn desk_set_out(state: tauri::State<'_, AppState>, out: bool) -> Result<bool, String> {
    let res = install::set_desk_out(state.platform, state.settings.as_ref(), out, state.data.overridden)?;
    let _ = state.companion.modify(|c| c.cats_out = res);
    Ok(res)
}

#[tauri::command]
async fn uninstall_system_components(state: tauri::State<'_, AppState>) -> Result<String, String> {
    let (platform, trial) = (state.platform, state.data.overridden);
    tauri::async_runtime::spawn_blocking(move || install::uninstall_system_components(platform, trial))
        .await
        .map_err(text)?
}

#[tauri::command]
fn book_festivals(first_day: u32, last_day: u32) -> Vec<festivals::FestivalDay> {
    festivals::in_range(first_day, last_day)
}

/// Cross-platform mobile sync (Route A): status, anonymous Pass code, and `.mpsync` export/import.
#[tauri::command]
fn mobile_sync_status(state: tauri::State<'_, AppState>) -> mobile_sync::MobileSyncStatus {
    let companion = state.companion.read().unwrap_or_default();
    mobile_sync::status_of(&state.data.path, &companion.sync_code, &companion.progress.unlocked)
}

#[tauri::command]
fn mobile_sync_create_pass(state: tauri::State<'_, AppState>) -> Result<mobile_sync::MobileSyncStatus, String> {
    let code = mobile_sync::generate_pass_code().ok_or_else(|| "無法取得系統隨機數".to_owned())?;
    let updated = state.companion.modify(|c| c.sync_code = code).map_err(text)?;
    Ok(mobile_sync::status_of(&state.data.path, &updated.sync_code, &updated.progress.unlocked))
}

#[tauri::command]
fn mobile_sync_set_pass(state: tauri::State<'_, AppState>, code: String) -> Result<mobile_sync::MobileSyncStatus, String> {
    let trimmed = code.trim();
    let clean = if trimmed.is_empty() {
        String::new()
    } else {
        mobile_sync::normalize_pass_code(trimmed)
            .ok_or_else(|| "通行碼格式或校驗碼不正確（應為 MEOW-XXXX-...）".to_owned())?
    };
    let updated = state.companion.modify(|c| c.sync_code = clean).map_err(text)?;
    Ok(mobile_sync::status_of(&state.data.path, &updated.sync_code, &updated.progress.unlocked))
}

#[tauri::command]
async fn mobile_sync_export(app: AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;
    let reading = state.view_reading().map_err(text)?;
    let companion = state.companion.read().map_err(text)?;
    let desk = desk_counters::read(&state.data.path);
    let env = mobile_sync::build_envelope(&reading.state, &companion.progress, &desk, &companion.sync_code);
    let bytes = env.to_bytes();
    let Some(chosen) = app
        .dialog()
        .file()
        .add_filter("Meowpuut Sync", &["mpsync"])
        .set_file_name("meowpuut-sync.mpsync")
        .blocking_save_file()
    else {
        return Ok(None);
    };
    let path = chosen.into_path().map_err(text)?;
    std::fs::write(&path, bytes).map_err(text)?;
    Ok(Some(path.display().to_string()))
}

#[tauri::command]
async fn mobile_sync_import(app: AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<mobile_sync::ImportOutcome>, String> {
    use tauri_plugin_dialog::DialogExt;
    let Some(picked) = app
        .dialog()
        .file()
        .add_filter("Meowpuut Sync", &["mpsync"])
        .blocking_pick_file()
    else {
        return Ok(None);
    };
    let path = picked.into_path().map_err(text)?;
    let bytes = files::read_limited(&path, (meowpuut_pets::sync::SYNC_V2_BYTES + 1) as u64).map_err(text)?;
    let reading = state.view_reading().map_err(text)?;
    let desk = desk_counters::read(&state.data.path);
    let mut result: Result<mobile_sync::ImportOutcome, String> = Err("未執行匯入".to_owned());
    state
        .companion
        .modify(|c| {
            result = mobile_sync::import_bytes(&state.data.path, &reading.state, &mut c.progress, &desk, &c.sync_code, &bytes);
        })
        .map_err(text)?;
    let outcome = result?;
    settle_on_open(app);
    Ok(Some(outcome))
}

#[tauri::command]
fn mobile_sync_clear(state: tauri::State<'_, AppState>) -> Result<mobile_sync::MobileSyncStatus, String> {
    mobile_sync::clear_mirror(&state.data.path);
    let updated = state.companion.modify(|c| c.sync_code.clear()).map_err(text)?;
    Ok(mobile_sync::status_of(&state.data.path, &updated.sync_code, &updated.progress.unlocked))
}

/// Explicit in-app quit (Valve Game-type build review requires a clear way to exit).
#[tauri::command]
fn quit_app(app: AppHandle) {
    app.exit(0);
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SettingsView {
    settings: Settings,
    location: String,
    /// Which skins the candidate window itself can wear on this platform today (the pages can wear all five).
    candidate_skins: Vec<&'static str>,
}

fn settings_view(state: &AppState, settings: Settings) -> SettingsView {
    SettingsView { settings, location: state.settings.location(), candidate_skins: vec!["daily", "glyph"] }
}

#[tauri::command]
fn settings_read(state: tauri::State<'_, AppState>) -> Result<SettingsView, String> {
    let settings = state.settings.read().map_err(text)?;
    Ok(settings_view(&state, settings))
}

/// Async (off the main thread): turning recording off may wait for the sharing loop to finish its tick.
#[tauri::command]
async fn settings_write(app: AppHandle, state: tauri::State<'_, AppState>, change: Change) -> Result<SettingsView, String> {
    let settings = state.settings.write(&change).map_err(text)?;
    // Sharing needs recording: turning recording off turns sharing off too.
    if change == Change::ActivityStats(false) {
        let _ = state.companion.modify(|c| c.share.on = false);
    }
    state.sharing.sync(&app);
    Ok(settings_view(&state, settings))
}

#[tauri::command]
fn companion_read(state: tauri::State<'_, AppState>) -> Result<CompanionState, String> {
    state.companion.read().map_err(text)
}

#[tauri::command]
async fn companion_update(app: AppHandle, state: tauri::State<'_, AppState>, patch: Patch) -> Result<CompanionState, String> {
    let updated = state.companion.update(&patch, clock::now().wall()).map_err(text)?;
    // The worn skin decides the candidate window too: 字景 for "glyph", the glass for every other skin.
    if patch.skin.is_some() {
        let _ = settings::sync_candidate_skin(state.settings.as_ref(), &updated.skin);
    }
    if patch.share.is_some() || patch.share_limit.is_some() || patch.share_app.is_some() || patch.share_track.is_some() {
        state.sharing.sync(&app);
    }
    if patch.achievements_on == Some(true) || patch.cloud_opt_in.is_some() {
        // Cloud on: copy up and merge; off: delete. Both run in the settlement thread, which also tells the page.
        settle_on_open(app);
    }
    Ok(state.companion.read().unwrap_or(updated))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SteamView {
    /// This build links Steamworks (the `steam` feature).
    built: bool,
    connected: bool,
    /// Why not connected (Steam not running, not launched through Steam, …).
    error: Option<String>,
    achievements_on: bool,
    cloud_on: bool,
    /// What the cloud step of this open did (or why it did nothing).
    cloud: CloudState,
    /// This computer's cloud file name while the switch is on.
    cloud_file: Option<String>,
    /// A trial data folder: nothing is ever unlocked on Steam from it.
    trial: bool,
    /// This open's settlement (None until it has run).
    settlement: Option<Settlement>,
    /// What this computer knows is unlocked.
    unlocked: Vec<AchievementView>,
}

#[tauri::command]
fn steam_status(state: tauri::State<'_, AppState>) -> SteamView {
    let companion = state.companion.read().unwrap_or_default();
    SteamView {
        built: steam::built(),
        connected: state.steam().connected(),
        error: lock(&state.steam_error).clone(),
        achievements_on: companion.achievements_on,
        cloud_on: companion.cloud_opt_in,
        cloud: if state.data.overridden { CloudState::Trial } else { lock(&state.cloud).state },
        cloud_file: (companion.cloud_opt_in && cloud::valid_install_id(&companion.install_id)).then(|| cloud::file_name(&companion.install_id)),
        trial: state.data.overridden,
        settlement: lock(&state.settlement).clone(),
        unlocked: steam::views(&companion.progress.unlocked),
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ActivityPage {
    view: ActivityView,
    recording: bool,
    paused: bool,
    categories: [&'static str; meowpuut_activity::CATEGORIES],
}

/// 「時間花在哪裡」: one week (seven days from `week_start`) and one month (`month_first..=month_last`), local day indexes.
#[tauri::command]
fn activity_view(state: tauri::State<'_, AppState>, week_start: u32, month_first: u32, month_last: u32) -> Result<ActivityPage, String> {
    let reading = state.activity.read().map_err(text)?;
    let settings = state.settings.read().ok();
    Ok(ActivityPage {
        view: ActivityView::of(&state.activity, &reading, week_start, month_first, month_last),
        recording: settings.as_ref().is_some_and(|s| s.activity_stats),
        paused: settings.as_ref().is_some_and(|s| s.activity_paused),
        categories: share::category_codes(),
    })
}

/// 清除時間紀錄 (the page asks first).
#[tauri::command]
fn activity_clear(state: tauri::State<'_, AppState>) -> Result<(), String> {
    state.activity.clear().map(drop).map_err(text)
}

/// The hidden-apps editor's 「挑一個 App」: a native open panel; the answer is the bundle id (macOS) or the executable's
/// file name (Windows), never stored anywhere but the hidden list.
#[tauri::command]
async fn pick_app(app: AppHandle, state: tauri::State<'_, AppState>) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;
    let platform = state.platform;
    let dialog = match platform {
        Platform::Mac => app.dialog().file().add_filter("App", &["app"]).set_directory("/Applications"),
        Platform::Windows => app.dialog().file().add_filter("exe", &["exe"]),
    };
    let Some(picked) = dialog.blocking_pick_file() else { return Ok(None) };
    let path = picked.into_path().map_err(text)?;
    app_identifier(&path, platform).map(Some).ok_or_else(|| "no app identifier".to_owned())
}

/// What the desk matches for an app: its bundle id (macOS) or its executable's file name (Windows).
fn app_identifier(path: &Path, platform: Platform) -> Option<String> {
    match platform {
        Platform::Windows => path.file_name().map(|n| n.to_string_lossy().into_owned()),
        Platform::Mac => bundle_identifier(path),
    }
}

#[cfg(target_os = "macos")]
fn bundle_identifier(path: &Path) -> Option<String> {
    use core_foundation::base::TCFType;
    use core_foundation::bundle::CFBundle;
    use core_foundation::string::CFString;
    use core_foundation::url::CFURL;
    let bundle = CFBundle::new(CFURL::from_path(path, true)?)?;
    // SAFETY: a valid bundle; the identifier is owned by it (get rule) and copied before the bundle goes.
    let id = unsafe { core_foundation_sys::bundle::CFBundleGetIdentifier(bundle.as_concrete_TypeRef()) };
    (!id.is_null()).then(|| unsafe { CFString::wrap_under_get_rule(id) }.to_string())
}

#[cfg(not(target_os = "macos"))]
fn bundle_identifier(_path: &Path) -> Option<String> {
    None
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ShareStatus {
    /// The loop is running (the line is being sent).
    active: bool,
    connected: bool,
    built: bool,
    /// This platform's desk can give app names (macOS over XPC, Windows through `ActivityShareAppName`).
    app_names: bool,
}

#[tauri::command]
fn share_status(state: tauri::State<'_, AppState>) -> ShareStatus {
    ShareStatus {
        active: state.sharing.active(),
        connected: state.steam().connected(),
        built: steam::built(),
        app_names: desk_now::app_names_available(state.platform),
    }
}

#[tauri::command]
async fn share_stop(app: AppHandle) -> Result<(), String> {
    sharing::stop(&app);
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PreviewAsk {
    share: bool,
    show_app: bool,
    show_track: bool,
}

/// The explanation page's preview: exactly the lines the presence builder makes for these switches.
#[tauri::command]
fn presence_preview(state: tauri::State<'_, AppState>, ask: PreviewAsk) -> Vec<share::PreviewLine> {
    let language = if locale::system_script() == locale::Script::Hans { Language::SChinese } else { Language::TChinese };
    let share = Share { on: ask.share, show_app: ask.show_app, show_track: ask.show_track, ..Share::default() };
    share::preview(&share, true, desk_now::app_names_available(state.platform), language)
}

/// 生活照 as the cache has them today (never waits for the network).
#[tauri::command]
fn photos_today(state: tauri::State<'_, AppState>, day: u32) -> photos::PhotosView {
    state.photos.today(day)
}

/// Starts the manifest fetch (a thread of its own; at most once per open); `photos-updated` tells the page when a new
/// manifest is cached. Answers at once.
#[tauri::command]
fn photos_refresh(app: AppHandle, state: tauri::State<'_, AppState>) -> bool {
    state.photos.refresh(move |changed| {
        if changed {
            let _ = app.emit(PHOTOS_EVENT, ());
        }
    })
}

/// One photo's JPEG bytes: from the cache, else downloaded (and cached) off the async runtime's threads.
#[tauri::command]
async fn photo_image(state: tauri::State<'_, AppState>, file: String) -> Result<tauri::ipc::Response, String> {
    let photos = state.photos;
    tauri::async_runtime::spawn_blocking(move || photos.image(&file)).await.map_err(text)?.map(tauri::ipc::Response::new)
}

/// 截一段: the page's picture (a PNG, as the raw body) goes to Steam's screenshot library with the stretch's last place
/// as its location (`location` header, percent-encoded), or, without Steam, to a file the user picks. Headers: `name`
/// (the file name, percent-encoded). Called only when the user presses the button.
#[tauri::command]
async fn share_publish(app: AppHandle, state: tauri::State<'_, AppState>, request: tauri::ipc::Request<'_>) -> Result<shot::Published, String> {
    use tauri_plugin_dialog::DialogExt;
    let tauri::ipc::InvokeBody::Raw(png) = request.body() else { return Err("expected the picture's bytes".to_owned()) };
    let header = |name: &str| {
        let raw = request.headers().get(name).and_then(|v| v.to_str().ok()).unwrap_or_default();
        percent_decode(raw)
    };
    let (name, location) = (header("name"), header("location"));
    shot::check_png(png)?;
    // `target: steam` (the page's 「放進 Steam 截圖」) goes to Steam; anything else is a file the user picks.
    if header("target") == "steam" {
        state.ensure_steam();
        match shot::to_steam(&mut **state.steam(), &shot::staging_dir(), &name, png, &location) {
            Ok(true) => {
                let _ = state.companion.modify(|c| c.progress.scroll_shares = c.progress.scroll_shares.saturating_add(1));
                settle_on_open(app);
                return Ok(shot::Published::Steam { location });
            }
            Ok(false) => {}
            Err(why) => return Ok(shot::Published::Failed { why }),
        }
    }
    // Development only: MEOWPUUT_SHARE_DIR saves the picture there without asking (screenshots of the share image).
    if let (true, Some(dir)) = (cfg!(debug_assertions), std::env::var_os("MEOWPUUT_SHARE_DIR")) {
        let path = PathBuf::from(dir).join(shot::clean_name(&name));
        return Ok(match std::fs::write(&path, png) {
            Ok(()) => {
                let _ = state.companion.modify(|c| c.progress.scroll_shares = c.progress.scroll_shares.saturating_add(1));
                shot::Published::Saved { path: path.display().to_string() }
            }
            Err(e) => shot::Published::Failed { why: e.to_string() },
        });
    }
    // No Steam: the dialog plugin's save panel.
    let Some(chosen) = app.dialog().file().add_filter("PNG", &["png"]).set_file_name(shot::clean_name(&name)).blocking_save_file() else {
        return Ok(shot::Published::Cancelled);
    };
    let path = chosen.into_path().map_err(text)?;
    Ok(match std::fs::write(&path, png) {
        Ok(()) => {
            let _ = state.companion.modify(|c| c.progress.scroll_shares = c.progress.scroll_shares.saturating_add(1));
            settle_on_open(app);
            shot::Published::Saved { path: path.display().to_string() }
        }
        Err(e) => shot::Published::Failed { why: e.to_string() },
    })
}

/// `%XX` escapes back to UTF-8 (header values are ASCII).
fn percent_decode(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match (bytes[i], bytes.get(i + 1..i + 3).and_then(|h| std::str::from_utf8(h).ok()).and_then(|h| u8::from_str_radix(h, 16).ok())) {
            (b'%', Some(b)) => {
                out.push(b);
                i += 3;
            }
            (b, _) => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Development only: the page's measurements (frame times, tile latency) to stderr, for `MEOWPUUT_START=journey?bench=1`.
#[tauri::command]
fn dev_report(text: String) {
    if cfg!(debug_assertions) {
        eprintln!("bench: {text}");
    }
}

pub fn run() {
    let data = paths::data_dir().unwrap_or_else(|| DataDir { path: PathBuf::from("Meowpuut"), overridden: true });
    let state = AppState::new(data, Platform::current());
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(state)
        .setup(|app| {
            // MEOWPUUT_THEME=light|dark: previews of either appearance without changing the system's.
            if let Some(window) = app.get_webview_window("main") {
                let theme = match std::env::var("MEOWPUUT_THEME").ok().as_deref() {
                    Some("dark") => Some(tauri::Theme::Dark),
                    Some("light") => Some(tauri::Theme::Light),
                    _ => None,
                };
                if theme.is_some() {
                    let _ = window.set_theme(theme);
                }
                // MEOWPUUT_WINDOW=3440x1440: a window of this (logical) size, for the ultrawide look.
                if let Some((w, h)) = std::env::var("MEOWPUUT_WINDOW").ok().and_then(|v| v.split_once('x').and_then(|(w, h)| Some((w.parse::<f64>().ok()?, h.parse::<f64>().ok()?)))) {
                    let _ = window.set_min_size(Some(tauri::LogicalSize::new(w.min(900.0), h.min(640.0))));
                    let _ = window.set_size(tauri::LogicalSize::new(w, h));
                }
            }
            // Once at start: the input method wears what the closet says even if the file changed behind our back.
            {
                let state = app.state::<AppState>();
                if let Ok(worn) = state.companion.read() {
                    let _ = settings::sync_candidate_skin(state.settings.as_ref(), &worn.skin);
                }
            }
            settle_on_open(app.handle().clone());
            #[cfg(all(target_os = "macos", feature = "devcapture"))]
            devcapture::schedule(app.handle());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            app_info,
            ledger_summary,
            ledger_clear,
            reveal_ledger,
            open_input_settings,
            install_input_source,
            check_input_source,
            desk_set_out,
            uninstall_system_components,
            book_festivals,
            mobile_sync_status,
            mobile_sync_create_pass,
            mobile_sync_set_pass,
            mobile_sync_export,
            mobile_sync_import,
            mobile_sync_clear,
            lounge_cmd::lounge_view,
            lounge_cmd::lounge_set_form,
            lounge_cmd::lounge_interact,
            lounge_cmd::lounge_claim_starter,
            lounge_cmd::lounge_buy_furniture,
            lounge_cmd::lounge_dispatch_expedition,
            lounge_cmd::lounge_equip_room,
            lounge_cmd::lounge_equip_title,
            lounge_cmd::lounge_focus_start,
            lounge_cmd::lounge_complete_focus,
            lounge_cmd::pad_commit,
            lounge_cmd::record_active_minute,
            lounge_cmd::inspect_sync_envelope,
            lounge_cmd::achievements_catalog,
            quit_app,
            settings_read,
            settings_write,
            companion_read,
            companion_update,
            steam_status,
            activity_view,
            activity_clear,
            pick_app,
            share_status,
            share_stop,
            presence_preview,
            glyph::scroll_open,
            glyph::scroll_tile,
            glyph::scroll_roller,
            glyph::scroll_share,
            glyph::scroll_last_place,
            glyph::lead_info,
            glyph::lead_picture,
            glyph::board_open,
            glyph::board_frame,
            glyph::board_keys,
            glyph::glyph_here,
            glyph::guide_picture,
            glyph::glyph_tokens,
            glyph::photo_type,
            glyph::strip_layout,
            glyph::strip_picture,
            photos_today,
            photos_refresh,
            photo_image,
            share_publish,
            dev_report
        ])
        .build(tauri::generate_context!())
        .expect("error while building the companion app");
    app.run(|app, event| match event {
        // The last window closed (code None, no window left) while sharing: stay, without a window, until sharing
        // ends. A quit with the window still open (⌘Q, the app menu) quits, and the line is cleared.
        RunEvent::ExitRequested { code: None, api, .. } if app.webview_windows().is_empty() && app.state::<AppState>().sharing.active() => {
            api.prevent_exit();
            sharing::go_windowless(app);
        }
        // Quitting, however it happens: the status line is always cleared.
        RunEvent::Exit => app.state::<AppState>().sharing.shutdown(app),
        _ => {}
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use settings::Registry;

    fn gate(dir: &Path) -> Option<u32> {
        FileRegistry::new(dir).get_dword("ActivityShareAppName").unwrap()
    }

    #[test]
    fn windows_desk_may_read_app_names_exactly_while_sharing_with_the_name_switch() {
        let tmp = tempfile::tempdir().unwrap();
        let state = AppState::new(DataDir { path: tmp.path().to_owned(), overridden: true }, Platform::Windows);
        let share = |on: bool, show_app: bool| {
            state.companion.modify(|c| c.share = Share { on, show_app, limit: companion::ShareLimit::UntilOff, ..c.share.clone() }).unwrap();
        };
        // Nothing on: 0 is written once (clearing anything stale), not on every call.
        state.sync_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(0));
        // Sharing needs recording.
        share(true, true);
        state.sync_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(0), "recording off");
        state.settings.write(&Change::ActivityStats(true)).unwrap();
        state.sync_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(1));
        share(true, false);
        state.sync_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(0), "name switch off");
        share(true, true);
        state.sync_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(1));
        share(false, true);
        state.sync_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(0), "sharing off");
        // A limit that ran out.
        state.companion.modify(|c| c.share = Share { on: true, show_app: true, limit: companion::ShareLimit::Hour, ends_at_ms: 1, ..c.share.clone() }).unwrap();
        state.sync_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(0), "limit over");
        // Quitting closes it even when it was open.
        share(true, true);
        state.sync_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(1));
        state.close_app_name_gate();
        assert_eq!(gate(tmp.path()), Some(0));
    }

    fn typed(day: u32, graphemes: u32) -> PetState {
        let mut state = PetState { epoch: 1, ..PetState::default() };
        state.apply_delta(&meowpuut_pets::codec::Delta { epoch: 1, graphemes: u64::from(graphemes), day, day_graphemes: graphemes, who: 1, part: 2, ..Default::default() });
        state
    }

    #[test]
    fn the_view_merges_the_other_computers_and_the_file_stays_as_it_was() {
        let tmp = tempfile::tempdir().unwrap();
        let state = AppState::new(DataDir { path: tmp.path().to_owned(), overridden: true }, Platform::Mac);
        let mine = typed(20_000, 900);
        std::fs::write(state.ledger.file(), mine.to_bytes()).unwrap();
        lock(&state.cloud).others = vec![typed(20_001, 1_500)];
        let view = state.view_reading().unwrap();
        assert_eq!(view.state.book.marks().map(|m| m.day).collect::<Vec<_>>(), [20_000, 20_001]);
        assert_eq!(view.state.snapshot.committed_graphemes, 1_500);
        assert_eq!(std::fs::read(state.ledger.file()).unwrap(), mine.to_bytes(), "the local ledger is not rewritten by the merge");
        // Cloud off (no other computers): the plain local view.
        lock(&state.cloud).others.clear();
        assert_eq!(state.view_reading().unwrap().state, mine);
    }

    #[test]
    fn a_trial_folder_never_syncs_even_with_the_switch_on() {
        let tmp = tempfile::tempdir().unwrap();
        let state = AppState::new(DataDir { path: tmp.path().to_owned(), overridden: true }, Platform::Mac);
        *state.steam() = Box::new(steam::tests::FakeSteam::online());
        state.companion.update(&Patch { cloud_opt_in: Some(true), ..Patch::default() }, clock::now().wall()).unwrap();
        state.cloud_sync();
        assert_eq!(lock(&state.cloud).state, CloudState::Trial);
        assert!(lock(&state.cloud).others.is_empty());
        // The page is told the same, whatever Steam says.
        assert!(state.companion.read().unwrap().cloud_opt_in, "the choice itself is kept");
        // A real folder in a build without Steam: the choice is kept, nothing is sent.
        let real = AppState::new(DataDir { path: tmp.path().to_owned(), overridden: false }, Platform::Mac);
        real.cloud_sync();
        assert_eq!(lock(&real.cloud).state, if steam::built() { CloudState::NoSteam } else { CloudState::NotBuilt });
    }

    #[test]
    fn the_mac_desk_is_asked_over_xpc_so_nothing_is_written() {
        let tmp = tempfile::tempdir().unwrap();
        let state = AppState::new(DataDir { path: tmp.path().to_owned(), overridden: true }, Platform::Mac);
        state.companion.modify(|c| c.share = Share { on: true, show_app: true, limit: companion::ShareLimit::UntilOff, ..c.share.clone() }).unwrap();
        state.sync_app_name_gate();
        state.close_app_name_gate();
        assert_eq!(gate(tmp.path()), None);
    }
}
