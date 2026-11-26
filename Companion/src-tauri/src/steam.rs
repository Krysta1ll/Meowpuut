//! Steamworks (FINAL.md §6.2, §7, §11). The companion is the only Meowpuut process that talks to Steam.
//!
//! - **Achievements, settled once when the app opens**: [`settle`] evaluates `meowpuut_pets::achievements` against the
//!   merged ledger and the [`Progress`] counters this app keeps, unlocks the newly met ones that are `steam: true` and
//!   `enabled`, and calls `StoreStats` once. The two local-only ones (清晨的字, 深夜的字) and everything met while
//!   「成就」 is off stay on this computer (`Companion.json`) and are shown in the app only. No stats are ever set,
//!   no `IndicateAchievementProgress`.
//! - **The friends status line**: `SetRichPresence` / `ClearRichPresence`, driven by [`crate::share`].
//!
//! [`SteamApi`] is the one door, so the glue is tested with a fake ([`tests`]) and never needs a Steam client.
//! The real door ([`client::SteamClient`], cargo feature `steam`) wraps steamworks-rs; without the feature (the
//! default build) it is [`NotLinked`] and nothing links steam_api.

use crate::companion::Progress as Counters;
use meowpuut_pets::achievements::{evaluate, Progress, TABLE};
use meowpuut_pets::codec::PetState;
use serde::Serialize;
use std::collections::BTreeSet;
use std::path::Path;

/// Everything the companion asks of Steam. Nothing here can set a stat.
pub trait SteamApi: Send {
    fn connected(&self) -> bool;
    /// Whether Steam has this achievement unlocked; `None` when Steam does not know it (or is not connected).
    fn achievement(&self, api: &str) -> Option<bool>;
    /// `SetAchievement`.
    fn unlock(&mut self, api: &str) -> bool;
    /// `StoreStats`: sends the unlocked achievements (the only "stats" this app has).
    fn store(&mut self) -> bool;
    /// `SetRichPresence(key, value)`; an empty value deletes the key.
    fn set_presence(&mut self, key: &str, value: &str) -> bool;
    /// `ClearRichPresence`.
    fn clear_presence(&mut self);
    /// `SteamAPI_RunCallbacks`.
    fn pump(&mut self);

    // Steam Cloud (`ISteamRemoteStorage`), opt in. Only [`crate::cloud`] calls these, and it refuses everything but the
    // fixed-length ledger of this computer; the defaults are "no cloud".
    /// Steam Cloud is on for the account and for this app.
    fn cloud_enabled(&self) -> bool {
        false
    }
    /// Every file in the app's cloud: name and size.
    fn cloud_files(&self) -> Vec<(String, u64)> {
        Vec::new()
    }
    /// `FileRead` of a whole (small) file.
    fn cloud_read(&self, _name: &str) -> Option<Vec<u8>> {
        None
    }
    /// `FileWrite` (a write stream, closed): true when Steam took it.
    fn cloud_write(&mut self, _name: &str, _bytes: &[u8]) -> bool {
        false
    }
    /// `FileDelete`.
    fn cloud_delete(&mut self, _name: &str) -> bool {
        false
    }

    /// 截一段 (FINAL.md §6.4): `ISteamScreenshots::AddScreenshotToLibrary` for the picture file at `path` (a PNG,
    /// `width` × `height` pixels), then `SetLocation` to `location`, the name of the last place of the stretch (empty:
    /// none). Only [`crate::share_publish`] calls this, and only when the user pressed 「截一段」 and Steam is
    /// connected. True when Steam took the picture.
    fn screenshot(&mut self, _path: &Path, _width: u32, _height: u32, _location: &str) -> bool {
        false
    }
}

/// No Steam: the default build, or Steam not running.
pub struct NotLinked;

impl SteamApi for NotLinked {
    fn connected(&self) -> bool {
        false
    }
    fn achievement(&self, _api: &str) -> Option<bool> {
        None
    }
    fn unlock(&mut self, _api: &str) -> bool {
        false
    }
    fn store(&mut self) -> bool {
        false
    }
    fn set_presence(&mut self, _key: &str, _value: &str) -> bool {
        false
    }
    fn clear_presence(&mut self) {}
    fn pump(&mut self) {}
}

/// Opens Steam: `SteamAPI_InitFlat` with the app ID Steam launched us with (or `steam_appid.txt` in the working
/// folder, development only). `Err` says why not (no `steam` feature, Steam not running, …).
pub fn connect() -> Result<Box<dyn SteamApi>, String> {
    #[cfg(debug_assertions)]
    if std::env::var_os(LOG_ENV).is_some() {
        return Ok(Box::new(Logged));
    }
    #[cfg(feature = "steam")]
    return client::SteamClient::init().map(|c| Box::new(c) as Box<dyn SteamApi>);
    #[cfg(not(feature = "steam"))]
    Err("built without the steam feature".to_owned())
}

/// True when this build can talk to Steam (the `steam` feature, or the development stand-in below).
pub fn built() -> bool {
    cfg!(feature = "steam") || (cfg!(debug_assertions) && std::env::var_os(LOG_ENV).is_some())
}

/// Development builds only: `MEOWPUUT_STEAM_LOG=1` replaces Steam with [`Logged`], which prints every call to stderr
/// instead of sending it, so the sharing loop, the tray icon and the windowless mode can be tried without a Steam
/// client or account.
pub const LOG_ENV: &str = "MEOWPUUT_STEAM_LOG";

#[cfg(debug_assertions)]
pub struct Logged;

#[cfg(debug_assertions)]
impl SteamApi for Logged {
    fn connected(&self) -> bool {
        true
    }
    fn achievement(&self, _api: &str) -> Option<bool> {
        None
    }
    fn unlock(&mut self, api: &str) -> bool {
        eprintln!("steam(log): SetAchievement {api}");
        false
    }
    fn store(&mut self) -> bool {
        eprintln!("steam(log): StoreStats");
        true
    }
    fn set_presence(&mut self, key: &str, value: &str) -> bool {
        eprintln!("steam(log): SetRichPresence {key}={value:?}");
        true
    }
    fn clear_presence(&mut self) {
        eprintln!("steam(log): ClearRichPresence");
    }
    fn pump(&mut self) {}
    fn cloud_enabled(&self) -> bool {
        true
    }
    fn cloud_write(&mut self, name: &str, bytes: &[u8]) -> bool {
        eprintln!("steam(log): FileWrite {name} ({} bytes)", bytes.len());
        true
    }
    fn cloud_delete(&mut self, name: &str) -> bool {
        eprintln!("steam(log): FileDelete {name}");
        true
    }
    fn screenshot(&mut self, path: &Path, width: u32, height: u32, location: &str) -> bool {
        eprintln!("steam(log): AddScreenshotToLibrary {} {width}x{height}, SetLocation {location:?}", path.display());
        true
    }
}

// ---- achievements --------------------------------------------------------------------------------------------------

/// Brings the counters up to date with the ledger, desk counters, and pure-game companion growth, returning `Progress`.
/// Counters only grow; a new ledger epoch starts them over.
pub fn count(counters: &mut Counters, state: &PetState, desk: &crate::desk_counters::DeskCounters) -> Progress {
    count_with_growth(counters, state, desk, &crate::growth::CompanionGrowth::default())
}

/// Same as [`count`], also folding in pure-game `CompanionGrowth` (`meow_chars`, `together_days`, companion trail).
pub fn count_with_growth(
    counters: &mut Counters,
    state: &PetState,
    desk: &crate::desk_counters::DeskCounters,
    growth: &crate::growth::CompanionGrowth,
) -> Progress {
    if counters.epoch != state.epoch {
        *counters = Counters { epoch: state.epoch, ..Counters::default() };
    }
    for mark in state.book.marks() {
        if mark.day > counters.last_day {
            counters.lifetime_days = counters.lifetime_days.saturating_add(1);
            if mark.size >= meowpuut_pets::book::size_band(1_500) {
                counters.peak_days_1500 = counters.peak_days_1500.saturating_add(1);
            }
            if mark.size >= meowpuut_pets::book::size_band(4_000) {
                counters.snow_peaks_4000 = counters.snow_peaks_4000.saturating_add(1);
            }
            counters.last_day = mark.day;
        }
        if let Some(place) = mark.place_index() {
            if place < meowpuut_pets::achievements::place::COUNT {
                counters.places |= 1u16 << place;
            }
        }
    }
    counters.max_day_graphemes = counters.max_day_graphemes.max(state.daily.graphemes);
    let active_days = state
        .book
        .marks()
        .map(|m| m.day)
        .chain(growth.recent_together_days.iter().copied());
    let festivals = crate::festivals::collect_met(active_days, &mut counters.festivals_met)
        .max(counters.mobile_festivals);
    if desk.epoch == 0 || desk.epoch == state.epoch {
        counters.desk = counters.desk.max_with(desk);
        counters.desk.epoch = state.epoch;
    }
    let total_active_minutes = counters.desk.active_minutes.max(if growth.epoch == state.epoch { growth.active_minutes } else { 0 });
    let total_together_days = counters
        .lifetime_days
        .max(counters.desk.together_days)
        .max(if growth.epoch == state.epoch { growth.together_days } else { 0 });
    let trail_places = crate::growth::trail_places_mask(total_active_minutes);
    counters.places = (counters.places | trail_places) & meowpuut_pets::achievements::place::ALL;
    let meow_chars = crate::growth::view_meow_chars(state.snapshot.committed_graphemes, total_active_minutes);
    Progress {
        places: counters.places & meowpuut_pets::achievements::place::ALL,
        festivals,
        lifetime_days: total_together_days,
        max_day_graphemes: counters.max_day_graphemes,
        peak_days_1500: counters.peak_days_1500,
        snow_peaks_4000: counters.snow_peaks_4000,
        meow_chars,
        outings: counters.desk.outings,
        purrs: counters.desk.purrs,
        rides: counters.desk.rides,
        caught: counters.desk.caught,
        found: counters.desk.found,
        roof: counters.desk.roof,
        steps: counters.desk.steps,
        nose: counters.desk.nose,
        moved: counters.desk.moved,
        scroll_shares: counters.scroll_shares,
        ..Progress::default()
    }
}

/// What one settlement did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Settlement {
    pub connected: bool,
    /// Newly met on this computer (any kind), added to `Companion.json`.
    pub local_new: Vec<&'static str>,
    /// Unlocked on Steam this time.
    pub sent: Vec<&'static str>,
    /// Met, meant for Steam, but Steam does not know the name (not set up in Steamworks, e.g. the test App ID 480)
    /// or refused it. Tried again next time.
    pub refused: Vec<&'static str>,
    /// Met, meant for Steam, but not sent: 「成就」 is off, Steam is not connected, or this is a trial data folder.
    pub held: Vec<&'static str>,
    pub stored: bool,
}

/// The settlement on open. `unlocked` is `Companion.json`'s list (updated here). `send`: 「成就」 is on and the data
/// folder is the real one (a trial folder never unlocks anything on Steam).
pub fn settle(steam: &mut dyn SteamApi, state: &PetState, progress: &Progress, unlocked: &mut Vec<String>, send: bool) -> Settlement {
    let mut out = Settlement { connected: steam.connected(), ..Settlement::default() };
    let known: BTreeSet<&str> = unlocked.iter().map(String::as_str).collect();
    out.local_new = evaluate(state, progress, &known).into_iter().map(|u| u.api).collect();
    unlocked.extend(out.local_new.iter().map(|a| (*a).to_owned()));

    let eval_apis: BTreeSet<&str> = evaluate(state, progress, &BTreeSet::new()).into_iter().map(|u| u.api).collect();
    let known_now: BTreeSet<&str> = unlocked.iter().map(String::as_str).collect();
    let meant: Vec<&'static str> = TABLE
        .iter()
        .filter(|d| d.steam && d.enabled && (eval_apis.contains(d.api) || known_now.contains(d.api)))
        .map(|d| d.api)
        .collect();
    if !send || !out.connected {
        // Only what Steam may not have yet is "held"; without a connection that is everything met.
        out.held = meant;
        return out;
    }
    for api in meant {
        match steam.achievement(api) {
            Some(true) => {}
            Some(false) if steam.unlock(api) => out.sent.push(api),
            _ => out.refused.push(api),
        }
    }
    if !out.sent.is_empty() {
        out.stored = steam.store();
        steam.pump();
    }
    out
}

/// One achievement as the app shows it (the local-only ones, and anything kept on this computer).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AchievementView {
    pub api: &'static str,
    pub name: [&'static str; 2],
    pub desc: [&'static str; 2],
    pub steam: bool,
}

pub fn views(unlocked: &[String]) -> Vec<AchievementView> {
    TABLE
        .iter()
        .filter(|d| unlocked.iter().any(|u| u == d.api))
        .map(|d| AchievementView { api: d.api, name: [d.zh_hant, d.zh_hans], desc: [d.desc_hant, d.desc_hans], steam: d.steam })
        .collect()
}

// ---- the real door -------------------------------------------------------------------------------------------------

#[cfg(feature = "steam")]
pub mod client {
    use super::SteamApi;

    /// steamworks-rs `Client` (Send + Sync; one per process).
    pub struct SteamClient {
        client: steamworks::Client,
    }

    impl SteamClient {
        pub fn init() -> Result<Self, String> {
            steamworks::Client::init().map(|client| Self { client }).map_err(|e| e.to_string())
        }
    }

    impl SteamApi for SteamClient {
        fn connected(&self) -> bool {
            true
        }
        fn achievement(&self, api: &str) -> Option<bool> {
            self.client.user_stats().achievement(api).get().ok()
        }
        fn unlock(&mut self, api: &str) -> bool {
            self.client.user_stats().achievement(api).set().is_ok()
        }
        fn store(&mut self) -> bool {
            self.client.user_stats().store_stats().is_ok()
        }
        fn set_presence(&mut self, key: &str, value: &str) -> bool {
            // steamworks-rs turns both into C strings and panics on an interior NUL; the presence builder already
            // removes control characters, this is the last guard.
            if key.contains('\0') || value.contains('\0') {
                return false;
            }
            self.client.friends().set_rich_presence(key, (!value.is_empty()).then_some(value))
        }
        fn clear_presence(&mut self) {
            self.client.friends().clear_rich_presence();
        }
        fn pump(&mut self) {
            self.client.run_callbacks();
        }
        fn cloud_enabled(&self) -> bool {
            let storage = self.client.remote_storage();
            storage.is_cloud_enabled_for_account() && storage.is_cloud_enabled_for_app()
        }
        fn cloud_files(&self) -> Vec<(String, u64)> {
            self.client.remote_storage().files().into_iter().map(|f| (f.name, f.size)).collect()
        }
        fn cloud_read(&self, name: &str) -> Option<Vec<u8>> {
            use std::io::Read;
            if name.contains('\0') {
                return None;
            }
            // A ledger is 2,884 bytes; never read more than a small cap, whatever the cloud says.
            let mut bytes = Vec::new();
            self.client.remote_storage().file(name).read().take(65_536).read_to_end(&mut bytes).ok()?;
            Some(bytes)
        }
        fn cloud_write(&mut self, name: &str, bytes: &[u8]) -> bool {
            use std::io::Write;
            if name.contains('\0') {
                return false;
            }
            // The write stream is committed when the writer is dropped.
            let mut writer = self.client.remote_storage().file(name).write();
            writer.write_all(bytes).is_ok()
        }
        fn cloud_delete(&mut self, name: &str) -> bool {
            !name.contains('\0') && self.client.remote_storage().file(name).delete()
        }
        fn screenshot(&mut self, path: &std::path::Path, width: u32, height: u32, location: &str) -> bool {
            use std::sync::{Arc, Mutex};
            let (Ok(w), Ok(h)) = (i32::try_from(width), i32::try_from(height)) else { return false };
            // Tagging waits for ScreenshotReady (Valve: "can now be tagged"): pump callbacks for up to a second.
            let ready = Arc::new(Mutex::new(false));
            let seen = Arc::clone(&ready);
            let _handle = self.client.register_callback(move |_: steamworks::screenshots::ScreenshotReady| {
                if let Ok(mut flag) = seen.lock() {
                    *flag = true;
                }
            });
            let Ok(shot) = self.client.screenshots().add_screenshot_to_library(path, None, w, h) else { return false };
            for _ in 0..40 {
                self.client.run_callbacks();
                if ready.lock().is_ok_and(|flag| *flag) {
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(25));
            }
            if let Ok(place) = std::ffi::CString::new(location).map_err(|_| ()).and_then(|c| (!location.is_empty()).then_some(c).ok_or(())) {
                // SAFETY: the interface pointer is Steam's own, valid while the client lives; `place` is a NUL-terminated
                // string that outlives the call; `shot` is the handle Steam just returned.
                unsafe {
                    steamworks_sys::SteamAPI_ISteamScreenshots_SetLocation(steamworks_sys::SteamAPI_SteamScreenshots_v003(), shot, place.as_ptr());
                }
            }
            true
        }
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use meowpuut_pets::codec::Delta;
    use std::collections::BTreeMap;

    /// A Steam that records every call. `known`: the achievements Steamworks has, and whether they are unlocked.
    #[derive(Default)]
    pub struct FakeSteam {
        pub online: bool,
        pub known: BTreeMap<String, bool>,
        pub calls: Vec<String>,
        pub presence: BTreeMap<String, String>,
        /// The app's Steam Cloud folder.
        pub cloud: BTreeMap<String, Vec<u8>>,
        /// Steam Cloud is on in Steam itself (the fake's `Default` says off; [`FakeSteam::online`] turns it on).
        pub cloud_allowed: bool,
        /// Every cloud file name that was read.
        pub reads: std::sync::Mutex<Vec<String>>,
    }

    impl FakeSteam {
        pub fn online() -> Self {
            let known = TABLE.iter().filter(|d| d.steam).map(|d| (d.api.to_owned(), false)).collect();
            Self { online: true, known, cloud_allowed: true, ..Self::default() }
        }
    }

    impl SteamApi for FakeSteam {
        fn screenshot(&mut self, path: &Path, width: u32, height: u32, location: &str) -> bool {
            self.calls.push(format!("screenshot {} {width}x{height} at {location}", path.file_name().map_or(String::new(), |n| n.to_string_lossy().into_owned())));
            self.online
        }
        fn connected(&self) -> bool {
            self.online
        }
        fn achievement(&self, api: &str) -> Option<bool> {
            self.known.get(api).copied().filter(|_| self.online)
        }
        fn unlock(&mut self, api: &str) -> bool {
            self.calls.push(format!("unlock {api}"));
            match self.known.get_mut(api) {
                Some(v) => {
                    *v = true;
                    true
                }
                None => false,
            }
        }
        fn store(&mut self) -> bool {
            self.calls.push("store".to_owned());
            true
        }
        fn set_presence(&mut self, key: &str, value: &str) -> bool {
            self.calls.push(format!("set {key}={value}"));
            if value.is_empty() {
                self.presence.remove(key);
            } else {
                self.presence.insert(key.to_owned(), value.to_owned());
            }
            true
        }
        fn clear_presence(&mut self) {
            self.calls.push("clear".to_owned());
            self.presence.clear();
        }
        fn pump(&mut self) {}
        fn cloud_enabled(&self) -> bool {
            self.online && self.cloud_allowed
        }
        fn cloud_files(&self) -> Vec<(String, u64)> {
            self.cloud.iter().map(|(n, b)| (n.clone(), b.len() as u64)).collect()
        }
        fn cloud_read(&self, name: &str) -> Option<Vec<u8>> {
            self.reads.lock().unwrap().push(name.to_owned());
            self.cloud.get(name).cloned()
        }
        fn cloud_write(&mut self, name: &str, bytes: &[u8]) -> bool {
            self.calls.push(format!("cloud write {name}"));
            self.cloud.insert(name.to_owned(), bytes.to_vec());
            true
        }
        fn cloud_delete(&mut self, name: &str) -> bool {
            self.calls.push(format!("cloud delete {name}"));
            self.cloud.remove(name).is_some()
        }
    }

    /// Typed days as the input methods record them: (day, graphemes, day-part bits).
    fn typed(epoch: u32, days: &[(u32, u32, u8)]) -> PetState {
        let mut state = PetState { epoch, ..PetState::default() };
        for &(day, graphemes, part) in days {
            state.apply_delta(&Delta { epoch, graphemes: u64::from(graphemes), day, day_graphemes: graphemes, who: 1, part, ..Delta::default() });
        }
        state
    }

    const DAWN: u8 = 1 << 0;
    const DAY: u8 = 1 << 1;

    #[test]
    fn settles_once_sends_only_steam_achievements_and_stores_once() {
        let state = typed(1, &[(20_000, 1_200, DAY), (20_001, 1_600, DAWN)]);
        let mut steam = FakeSteam::online();
        let mut unlocked = Vec::new();
        let mut counters = Counters::default();
        let empty = crate::desk_counters::DeskCounters::default();
        let progress = count(&mut counters, &state, &empty);
        let s = settle(&mut steam, &state, &progress, &mut unlocked, true);
        assert_eq!(s.sent, ["CHARS_1", "CHARS_100", "CHARS_1K", "DAYS_1", "DAYS_PEAK_1500"]);
        assert!(s.local_new.contains(&"LOCAL_DAWN"), "local-only ones are kept on this computer");
        assert!(!s.sent.contains(&"LOCAL_DAWN"), "and never sent");
        assert!(s.stored);
        // Only achievements and one StoreStats: no stats, no presence.
        assert_eq!(steam.calls.iter().filter(|c| *c == "store").count(), 1);
        assert!(steam.calls.iter().all(|c| c.starts_with("unlock ") || c == "store"), "{:?}", steam.calls);
        // Opening again: nothing new, no calls.
        steam.calls.clear();
        let again = settle(&mut steam, &state, &count(&mut counters, &state, &empty), &mut unlocked, true);
        assert!(again.sent.is_empty() && again.local_new.is_empty() && !again.stored);
        assert!(steam.calls.is_empty());
    }

    #[test]
    fn achievements_off_offline_or_a_trial_folder_send_nothing() {
        let state = typed(1, &[(20_000, 5, DAY)]);
        for (online, send) in [(true, false), (false, true)] {
            let mut steam = FakeSteam { online, ..FakeSteam::online() };
            let mut unlocked = Vec::new();
            let s = settle(&mut steam, &state, &Progress::default(), &mut unlocked, send);
            assert!(steam.calls.is_empty(), "online {online} send {send}: {:?}", steam.calls);
            assert_eq!(s.held, ["CHARS_1", "DAYS_1"]);
            assert_eq!(unlocked, ["CHARS_1", "DAYS_1"], "still shown in the app");
        }
    }

    #[test]
    fn names_steam_does_not_know_are_refused_and_tried_again_later() {
        let state = typed(1, &[(20_000, 5, DAY)]);
        // App ID 480 (Spacewar) has none of our achievements.
        let mut steam = FakeSteam { online: true, ..FakeSteam::default() };
        let mut unlocked = Vec::new();
        let s = settle(&mut steam, &state, &Progress::default(), &mut unlocked, true);
        assert_eq!(s.refused, ["CHARS_1", "DAYS_1"]);
        assert!(s.sent.is_empty() && !s.stored);
        assert!(steam.calls.is_empty(), "no SetAchievement for unknown names, no StoreStats");
        // Once Steamworks has them, the next open sends them.
        let mut steam = FakeSteam::online();
        let s = settle(&mut steam, &state, &Progress::default(), &mut unlocked, true);
        assert_eq!(s.sent, ["CHARS_1", "DAYS_1"]);
        assert!(s.local_new.is_empty());
        // Already unlocked on Steam: not sent again.
        let s = settle(&mut steam, &state, &Progress::default(), &mut unlocked, true);
        assert!(s.sent.is_empty());
    }

    #[test]
    fn counters_grow_and_start_over_with_a_new_epoch() {
        let mut counters = Counters::default();
        let empty = crate::desk_counters::DeskCounters::default();
        let p = count(&mut counters, &typed(1, &[(20_000, 100, DAY), (20_003, 4_200, DAY)]), &empty);
        assert_eq!((p.lifetime_days, p.max_day_graphemes), (2, 4_200));
        // The same days again do not count twice; a new day does.
        let p = count(&mut counters, &typed(1, &[(20_000, 100, DAY), (20_003, 4_200, DAY), (20_004, 7, DAY)]), &empty);
        assert_eq!((p.lifetime_days, p.max_day_graphemes), (3, 4_200), "the peak stays");
        counters.unlocked.push("CHARS_1".into());
        // Cleared: epoch 2.
        let p = count(&mut counters, &typed(2, &[(20_010, 3, DAY)]), &empty);
        assert_eq!((p.lifetime_days, p.max_day_graphemes, counters.epoch), (1, 3, 2));
        assert!(counters.unlocked.is_empty());
    }

    #[test]
    fn a_day_that_left_the_ring_still_counts_for_100_days() {
        // The ring holds 400 days; the counters remember older ones.
        let mut counters = Counters { epoch: 1, last_day: 19_000, lifetime_days: 99, ..Counters::default() };
        let state = typed(1, &[(20_000, 5, DAY)]);
        let p = count(&mut counters, &state, &crate::desk_counters::DeskCounters::default());
        let mut steam = FakeSteam::online();
        let s = settle(&mut steam, &state, &p, &mut Vec::new(), true);
        assert!(s.sent.contains(&"DAYS_100"));
    }

    #[test]
    fn places_festivals_scroll_share_and_desk_counters_unlock_achievements() {
        let mut state = PetState { epoch: 1, ..PetState::default() };
        let fest_day = crate::festivals::parse_day("2026-02-17").unwrap(); // Spring Festival
        state.apply_delta(&Delta {
            epoch: 1,
            graphemes: 50,
            day: fest_day,
            day_graphemes: 50,
            who: 1,
            part: DAY,
            place: 2, // place_index = 1 (SEA)
            ..Delta::default()
        });
        let mut counters = Counters { epoch: 1, scroll_shares: 1, ..Counters::default() };
        let desk = crate::desk_counters::DeskCounters {
            epoch: 1,
            outings: 1,
            purrs: 1,
            rides: 1,
            caught: 1,
            found: 1,
            roof: 1,
            steps: 1_000,
            nose: 1,
            moved: 1,
            active_minutes: 0,
            together_days: 0,
        };
        let p = count(&mut counters, &state, &desk);
        let mut steam = FakeSteam::online();
        let s = settle(&mut steam, &state, &p, &mut Vec::new(), true);
        for expected in [
            "PLACE_SEA",
            "FEST_1",
            "SCROLL_SHARE",
            "CAT_OUT",
            "CAT_PURR",
            "CAT_RIDE",
            "CAT_CAUGHT",
            "CAT_FOUND",
            "CAT_ROOF",
            "CAT_STEPS_1K",
            "CAT_NOSE",
            "CAT_MOVED",
        ] {
            assert!(s.sent.contains(&expected), "missing {expected} in {:?}", s.sent);
        }
    }

    #[test]
    fn the_app_shows_local_names_from_the_table() {
        let v = views(&["LOCAL_LATE_NIGHT".into(), "CHARS_1".into(), "BOGUS".into()]);
        assert_eq!(v.iter().map(|a| a.api).collect::<Vec<_>>(), ["CHARS_1", "LOCAL_LATE_NIGHT"]);
        assert_eq!(v[1].name[0], "深夜的字・貓頭鷹咪");
        assert!(!v[1].steam);
    }
}
