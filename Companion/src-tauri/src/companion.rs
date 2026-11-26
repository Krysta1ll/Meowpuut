//! The companion's own few choices, in `Companion.json` beside the ledger (listed on the privacy page like every
//! other file). Nothing typed, nothing about other apps: which skin the pages wear, whether the first run is done, the
//! first-run answers, the achievement switch and what this computer already knows is unlocked, and the Steam friends
//! status switches (FINAL.md §11.1: sharing, the app name, the song, and how long sharing lasts).
//!
//! The record switch itself (「記錄時間花在哪裡」) is not here: the desk reads it, so it lives in settings.json /
//! `HKCU\Software\Meowpuut` ([`crate::settings`]). Sharing needs only the companion, so its switches live here.

use crate::files;
use serde::{Deserialize, Serialize};
use std::io;
use std::path::{Path, PathBuf};

pub const FILE_NAME: &str = "Companion.json";
pub const SKINS: [&str; 5] = ["daily", "classic", "pixel", "centenary", "glyph"];
/// The switches whose first turn-on shows the explanation page.
pub const EXPLAINED: [&str; 5] = ["record", "share", "app", "track", "cloud"];
/// 「1 小時」.
pub const HOUR_MS: i64 = 3_600_000;

/// How long sharing lasts once turned on (FINAL.md §11.1). The default is the shortest.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ShareLimit {
    #[default]
    Hour,
    Today,
    UntilOff,
}

/// 「讓 Steam 好友看到我在做什麼」 and its two sub-switches, all off by default.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Share {
    pub on: bool,
    pub show_app: bool,
    pub show_track: bool,
    pub limit: ShareLimit,
    /// With `Hour`: when sharing ends (wall-clock ms since 1970). Set each time sharing is turned on.
    pub ends_at_ms: i64,
    /// With `Today`: the local day index it was turned on.
    pub day: u32,
}

/// Counters the ledger cannot keep (`meowpuut_pets::achievements::Progress`: "Counters only grow; the client should
/// persist them next to the ledger"), and the achievements this computer knows are unlocked. All of it starts over
/// when the ledger's epoch changes (「清除貓咪紀錄」).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Progress {
    pub epoch: u32,
    /// The newest book day already counted into `lifetime_days`.
    pub last_day: u32,
    pub lifetime_days: u32,
    pub max_day_graphemes: u32,
    pub peak_days_1500: u32,
    pub snow_peaks_4000: u32,
    /// Monotonic 12-bit set of places reached across all days (`meowpuut_pets::achievements::place`).
    pub places: u16,
    /// Distinct festival IDs met on typed days (never sent to Steam; only the count unlocks `FEST_1`/`FEST_12`).
    pub festivals_met: Vec<String>,
    /// Monotonic festival count merged from synced mobile devices (Route A).
    pub mobile_festivals: u32,
    /// Times the user cut a scroll clip (截一段).
    pub scroll_shares: u32,
    /// Monotonic mirror of 喵撲貓窩 counters so 400-day or process restarts never lose progress.
    pub desk: crate::desk_counters::DeskCounters,
    /// API names (both the Steam ones and the two local-only ones).
    pub unlocked: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct CompanionState {
    pub first_run_done: bool,
    /// The skin the pages and the home keyboard wear (`daily` = 日常玻璃, the default).
    pub skin: String,
    /// Windows: the text service has both keyboards, so the home keyboard asks which one to draw. Unused on macOS,
    /// where `inputMode` in settings.json decides.
    pub hero_layout: String,
    /// Steam Cloud: off by default (FINAL.md §6.4). On: this computer's ledger is copied into the cloud as
    /// `ledger-<install id>.bin` and the other computers' are merged in on open ([`crate::cloud`]).
    pub cloud_opt_in: bool,
    /// The random id in this computer's cloud file name. Made the first time Steam Cloud is turned on, kept after it is
    /// turned off (to delete the file), never derived from the hardware or the account. Empty until then.
    pub install_id: String,
    /// Steam Cloud was turned off and this computer's file is not deleted yet (Steam was not there); done on the next
    /// open with Steam.
    pub cloud_delete_pending: bool,
    /// "讓米米莫和莫莫出門嗎？" (first run, step 7): yes by default. The desk process will read it.
    pub cats_out: bool,
    /// Achievements go to Steam (default). Off: they stay on this computer (FINAL.md §6.2).
    pub achievements_on: bool,
    pub progress: Progress,
    pub share: Share,
    /// Switches ([`EXPLAINED`]) whose explanation page was shown and accepted.
    pub explained: Vec<String>,
    /// Anonymous 128-bit cross-platform Pass code (`MEOW-...`, Route A) for mobile ↔ PC sync; empty until created/set.
    pub sync_code: String,
    /// Interactive cat lounge & endgame collection state (never uploaded to Steam Cloud).
    pub lounge: crate::lounge::LoungeState,
    /// Pure-game dual-track growth counters (active minutes & together days; local only).
    pub growth: crate::growth::CompanionGrowth,
}

impl Default for CompanionState {
    fn default() -> Self {
        Self {
            first_run_done: false,
            skin: "daily".to_owned(),
            hero_layout: "pinyin".to_owned(),
            cloud_opt_in: false,
            install_id: String::new(),
            cloud_delete_pending: false,
            cats_out: true,
            achievements_on: true,
            progress: Progress::default(),
            share: Share::default(),
            explained: Vec::new(),
            sync_code: String::new(),
            lounge: crate::lounge::LoungeState::default(),
            growth: crate::growth::CompanionGrowth::default(),
        }
    }
}

/// A partial update from the page.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Patch {
    pub first_run_done: Option<bool>,
    pub skin: Option<String>,
    pub hero_layout: Option<String>,
    pub cloud_opt_in: Option<bool>,
    pub cats_out: Option<bool>,
    pub achievements_on: Option<bool>,
    pub share: Option<bool>,
    pub share_app: Option<bool>,
    pub share_track: Option<bool>,
    pub share_limit: Option<ShareLimit>,
    /// Marks one switch's explanation as shown.
    pub explained: Option<String>,
}

/// The time a patch is applied at (sharing's end is fixed when it is turned on).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WallNow {
    pub ms: i64,
    pub day: u32,
}

impl CompanionState {
    /// Makes the install id if there is none (or it is damaged). It is only ever random.
    pub fn ensure_install_id(&mut self) {
        if !crate::cloud::valid_install_id(&self.install_id) {
            self.install_id = crate::cloud::new_install_id().unwrap_or_default();
        }
    }

    /// Applies a patch. Turning sharing on (or changing its limit while on) starts the limit afresh from `now`.
    pub fn apply(&mut self, patch: &Patch, now: WallNow) {
        if let Some(v) = patch.first_run_done {
            self.first_run_done = v;
        }
        if let Some(v) = patch.skin.as_deref().filter(|v| SKINS.contains(v)) {
            self.skin = v.to_owned();
        }
        if let Some(v) = patch.hero_layout.as_deref().filter(|v| ["pinyin", "zhuyin"].contains(v)) {
            self.hero_layout = v.to_owned();
        }
        if let Some(v) = patch.cloud_opt_in {
            if v && !self.cloud_opt_in {
                // Turning it on: the id is made once; a file still waiting to be deleted stays (it is the same file).
                self.ensure_install_id();
                self.cloud_delete_pending = false;
            } else if !v && self.cloud_opt_in && !self.install_id.is_empty() {
                self.cloud_delete_pending = true;
            }
            self.cloud_opt_in = v;
        }
        if let Some(v) = patch.cats_out {
            self.cats_out = v;
        }
        if let Some(v) = patch.achievements_on {
            self.achievements_on = v;
        }
        let was = (self.share.on, self.share.limit);
        if let Some(v) = patch.share_limit {
            self.share.limit = v;
        }
        if let Some(v) = patch.share {
            self.share.on = v;
        }
        if let Some(v) = patch.share_app {
            self.share.show_app = v;
        }
        if let Some(v) = patch.share_track {
            self.share.show_track = v;
        }
        if self.share.on && (!was.0 || was.1 != self.share.limit) {
            self.share.ends_at_ms = now.ms.saturating_add(HOUR_MS);
            self.share.day = now.day;
        }
        if let Some(name) = patch.explained.as_deref().filter(|n| EXPLAINED.contains(n)) {
            if !self.explained.iter().any(|e| e == name) {
                self.explained.push(name.to_owned());
            }
        }
    }
}

pub struct Companion {
    file: PathBuf,
}

impl Companion {
    pub fn new(dir: &Path) -> Self {
        Self { file: dir.join(FILE_NAME) }
    }

    pub fn read(&self) -> io::Result<CompanionState> {
        let bytes = files::read_limited(&self.file, 65_536)?;
        let mut state: CompanionState = serde_json::from_slice(&bytes).unwrap_or_default();
        if !SKINS.contains(&state.skin.as_str()) {
            state.skin = CompanionState::default().skin;
        }
        if !["pinyin", "zhuyin"].contains(&state.hero_layout.as_str()) {
            state.hero_layout = CompanionState::default().hero_layout;
        }
        state.explained.retain(|e| EXPLAINED.contains(&e.as_str()));
        if !state.install_id.is_empty() && !crate::cloud::valid_install_id(&state.install_id) {
            state.install_id.clear();
        }
        state.lounge.sanitize();
        Ok(state)
    }

    pub fn update(&self, patch: &Patch, now: WallNow) -> io::Result<CompanionState> {
        self.modify(|state| state.apply(patch, now))
    }

    /// Read, change, write (one process writes this file: no lock).
    pub fn modify(&self, change: impl FnOnce(&mut CompanionState)) -> io::Result<CompanionState> {
        let mut state = self.read()?;
        change(&mut state);
        if let Some(dir) = self.file.parent() {
            std::fs::create_dir_all(dir)?;
        }
        files::replace(&self.file, &serde_json::to_vec_pretty(&state).map_err(io::Error::other)?)?;
        Ok(state)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: WallNow = WallNow { ms: 1_800_000_000_000, day: 20_833 };

    #[test]
    fn everything_new_is_off_and_old_files_still_read() {
        let tmp = tempfile::tempdir().unwrap();
        let c = Companion::new(tmp.path());
        let fresh = c.read().unwrap();
        assert!(!fresh.share.on && !fresh.share.show_app && !fresh.share.show_track);
        assert_eq!(fresh.share.limit, ShareLimit::Hour);
        assert!(fresh.achievements_on, "achievements are on by default (FINAL §6.2)");
        // A file from the version before these fields.
        std::fs::write(tmp.path().join(FILE_NAME), r#"{"firstRunDone":true,"skin":"glyph","heroLayout":"zhuyin","cloudOptIn":false,"catsOut":true}"#).unwrap();
        let old = c.read().unwrap();
        assert!(old.first_run_done && old.achievements_on && !old.share.on);
        assert_eq!(old.skin, "glyph");
    }

    #[test]
    fn the_install_id_is_random_made_once_and_kept_for_the_delete() {
        let mut s = CompanionState::default();
        assert!(!s.cloud_opt_in && s.install_id.is_empty(), "off by default, no id yet");
        s.apply(&Patch { cloud_opt_in: Some(true), ..Patch::default() }, NOW);
        let id = s.install_id.clone();
        assert!(crate::cloud::valid_install_id(&id) && s.cloud_opt_in && !s.cloud_delete_pending);
        // Turned off: the id stays, the file is to be deleted.
        s.apply(&Patch { cloud_opt_in: Some(false), ..Patch::default() }, NOW);
        assert_eq!((s.install_id.as_str(), s.cloud_delete_pending, s.cloud_opt_in), (id.as_str(), true, false));
        // Turned on again: the same id, nothing left to delete.
        s.apply(&Patch { cloud_opt_in: Some(true), ..Patch::default() }, NOW);
        assert_eq!((s.install_id.as_str(), s.cloud_delete_pending), (id.as_str(), false));
        // Off twice, or off when it never was on: no pending delete.
        let mut fresh = CompanionState::default();
        fresh.apply(&Patch { cloud_opt_in: Some(false), ..Patch::default() }, NOW);
        assert!(!fresh.cloud_delete_pending && fresh.install_id.is_empty());
        // A damaged id in the file is dropped on read.
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join(FILE_NAME), r#"{"installId":"hello"}"#).unwrap();
        assert!(Companion::new(tmp.path()).read().unwrap().install_id.is_empty());
    }

    #[test]
    fn turning_sharing_on_starts_its_limit() {
        let mut s = CompanionState::default();
        s.apply(&Patch { share: Some(true), ..Patch::default() }, NOW);
        assert!(s.share.on);
        assert_eq!((s.share.ends_at_ms, s.share.day), (NOW.ms + HOUR_MS, NOW.day));
        // Later changes of the sub-switches do not extend it.
        let later = WallNow { ms: NOW.ms + 600_000, day: NOW.day };
        s.apply(&Patch { share_app: Some(true), ..Patch::default() }, later);
        assert_eq!(s.share.ends_at_ms, NOW.ms + HOUR_MS);
        // A new limit while on starts it again.
        s.apply(&Patch { share_limit: Some(ShareLimit::Today), ..Patch::default() }, later);
        assert_eq!((s.share.limit, s.share.ends_at_ms), (ShareLimit::Today, later.ms + HOUR_MS));
        s.apply(&Patch { share: Some(false), ..Patch::default() }, later);
        assert!(!s.share.on && s.share.show_app, "the sub-switches are remembered");
    }

    #[test]
    fn explained_marks_only_known_switches_once() {
        let tmp = tempfile::tempdir().unwrap();
        let c = Companion::new(tmp.path());
        for name in ["share", "share", "nonsense", "record"] {
            c.update(&Patch { explained: Some(name.into()), ..Patch::default() }, NOW).unwrap();
        }
        assert_eq!(c.read().unwrap().explained, ["share", "record"]);
    }
}
