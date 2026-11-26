//! Cross-platform zero-PII mobile sync (Route A): anonymous 128-bit Pass code (`MEOW-...`) and
//! 3,072-byte CRDT [`SyncEnvelope`] snapshots (`MobileSync.bin` / `.mpsync`).
//!
//! Only numeric pet ledger state, monotonic achievement counters, and the 128-bit unlocked
//! achievement bitset ever leave this device. Word-choice memory (`mimo_user.db`), typed text,
//! custom phrases, app names, and activity history never enter a sync envelope.

#![forbid(unsafe_code)]

use crate::companion::Progress as Counters;
use crate::desk_counters::DeskCounters;
use crate::files;
use crate::steam;
use meowpuut_pets::achievements::{place, TABLE};
use meowpuut_pets::codec::PetState;
use meowpuut_pets::sync::{
    decode_any, decode_pass_code, encode_pass_code, group_prefix_of, SyncEnvelope, SyncEnvelopeV2,
    FLAG_PASS_ACTIVE, SYNC_BYTES, SYNC_V2_BYTES,
};
use serde::Serialize;
use std::io;
use std::path::Path;

/// Local mirror of the latest merged cross-platform envelope, stored beside `PetJournal.bin`.
pub const FILE_NAME: &str = "MobileSync.bin";

/// Summary returned to the Companion UI for the cross-platform sync section.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MobileSyncStatus {
    pub pass_code: Option<String>,
    pub has_mirror: bool,
    pub mirror_bytes: usize,
    pub envelope_bytes: usize,
    pub steam_unlocked: usize,
    pub steam_total: usize,
    pub local_unlocked: usize,
}

/// Result of importing a `.mpsync` file from iOS, Android, or another computer.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportOutcome {
    pub characters: u64,
    pub days: u32,
    pub unlocked_total: usize,
    pub newly_unlocked: usize,
}

/// Generates a fresh 128-bit anonymous Pass code (`MEOW-XXXX-...`) using OS randomness.
pub fn generate_pass_code() -> Option<String> {
    let mut key = [0u8; 16];
    getrandom::fill(&mut key).ok()?;
    Some(encode_pass_code(&key))
}

/// Validates and canonicalizes a user-entered Pass code (tolerates lowercase, spaces, `I`/`L`/`O`).
///
/// ```
/// use meowpuut_companion_lib::mobile_sync::normalize_pass_code;
/// let key = [0x42u8; 16];
/// let canonical = meowpuut_pets::sync::encode_pass_code(&key);
/// assert_eq!(normalize_pass_code(&canonical.to_lowercase()), Some(canonical));
/// assert_eq!(normalize_pass_code("MEOW-INVALID"), None);
/// ```
pub fn normalize_pass_code(input: &str) -> Option<String> {
    let key = decode_pass_code(input)?;
    Some(encode_pass_code(&key))
}

/// Computes the 8-byte anonymous sync-group prefix for a Pass code (all zeros when unset).
pub fn group_prefix(code: &str) -> [u8; 8] {
    decode_pass_code(code).map_or([0u8; 8], |k| group_prefix_of(&k))
}

/// Reads the locally cached merged mobile envelope (supports both 3,072B v1 and 4,096B v2), if present and valid.
pub fn read_mirror(dir: &Path) -> Option<SyncEnvelope> {
    let bytes = files::read_limited(&dir.join(FILE_NAME), (SYNC_V2_BYTES + 1) as u64).ok()?;
    let v2 = decode_any(&bytes).ok()?;
    let mut base = v2.base.clone();
    base.state.snapshot.committed_graphemes = v2.total_graphemes();
    Some(base)
}

/// Persists the merged cross-platform envelope atomically beside `PetJournal.bin`.
pub fn write_mirror(dir: &Path, env: &SyncEnvelope) -> io::Result<()> {
    std::fs::create_dir_all(dir)?;
    files::replace(&dir.join(FILE_NAME), &env.to_bytes())
}

/// Deletes `MobileSync.bin` if present (used on ledger clear or sync reset).
pub fn clear_mirror(dir: &Path) {
    let _ = std::fs::remove_file(dir.join(FILE_NAME));
}

/// Builds a 3,072-byte [`SyncEnvelope`] from the current merged view, counters, and desk events.
///
/// ```
/// use meowpuut_companion_lib::{companion::Progress, desk_counters::DeskCounters, mobile_sync};
/// use meowpuut_pets::codec::PetState;
/// let env = mobile_sync::build_envelope(&PetState::default(), &Progress::default(), &DeskCounters::default(), "");
/// assert_eq!(env.to_bytes().len(), meowpuut_pets::sync::SYNC_BYTES);
/// ```
pub fn build_envelope(
    state: &PetState,
    counters: &Counters,
    desk: &DeskCounters,
    sync_code: &str,
) -> SyncEnvelope {
    let mut snap = counters.clone();
    let progress = steam::count(&mut snap, state, desk);
    let prefix = group_prefix(sync_code);
    let flags = if prefix != [0u8; 8] { FLAG_PASS_ACTIVE } else { 0 };
    let mut env = SyncEnvelope {
        flags,
        group_prefix: prefix,
        state: state.clone(),
        progress,
        unlocked_bits: [0u8; 16],
    };
    for api in &snap.unlocked {
        env.mark_unlocked(api);
    }
    env.refresh_unlocks();
    env
}

/// Merges a [`SyncEnvelope`]'s monotonic counters and unlocked bits back into `Companion.json` counters.
pub fn apply_to_counters(env: &SyncEnvelope, counters: &mut Counters) -> usize {
    if env.state.epoch > counters.epoch {
        *counters = Counters { epoch: env.state.epoch, ..Counters::default() };
    }
    if env.state.epoch < counters.epoch {
        return 0;
    }
    let p = &env.progress;
    counters.lifetime_days = counters.lifetime_days.max(p.lifetime_days);
    counters.max_day_graphemes = counters.max_day_graphemes.max(p.max_day_graphemes);
    counters.peak_days_1500 = counters.peak_days_1500.max(p.peak_days_1500);
    counters.snow_peaks_4000 = counters.snow_peaks_4000.max(p.snow_peaks_4000);
    counters.places = (counters.places | p.places) & place::ALL;
    counters.mobile_festivals = counters.mobile_festivals.max(p.festivals);
    counters.scroll_shares = counters.scroll_shares.max(p.scroll_shares);
    let incoming_desk = DeskCounters {
        epoch: env.state.epoch,
        outings: p.outings,
        purrs: p.purrs,
        rides: p.rides,
        caught: p.caught,
        found: p.found,
        roof: p.roof,
        steps: p.steps,
        nose: p.nose,
        moved: p.moved,
        active_minutes: 0,
        together_days: 0,
    };
    counters.desk = counters.desk.max_with(&incoming_desk);
    counters.desk.epoch = env.state.epoch;

    let before = counters.unlocked.len();
    for api in env.unlocked_apis() {
        if !counters.unlocked.iter().any(|u| u == api) {
            counters.unlocked.push(api.to_owned());
        }
    }
    counters.unlocked.len().saturating_sub(before)
}

/// Imports a 3,072-byte (v1) or 4,096-byte (v2) `.mpsync` payload from mobile/PC, CRDT-merges it,
/// persists `MobileSync.bin` (v1 wire format in P0), and updates `counters`.
pub fn import_bytes(
    dir: &Path,
    local_view: &PetState,
    counters: &mut Counters,
    desk: &DeskCounters,
    sync_code: &str,
    incoming_bytes: &[u8],
) -> Result<ImportOutcome, String> {
    let incoming = decode_any(incoming_bytes).map_err(|_| {
        format!("這不是有效的喵撲同步檔（需要 {SYNC_BYTES} 或 {SYNC_V2_BYTES} 位元組且通過校驗的 .mpsync 檔）。")
    })?;
    let own_prefix = group_prefix(sync_code);
    if own_prefix != [0u8; 8]
        && incoming.base.group_prefix != [0u8; 8]
        && own_prefix != incoming.base.group_prefix
    {
        return Err("同步碼群組不符：請確認手機與電腦使用同一組 MEOW 通行碼（或先清除本機通行碼再匯入）。".to_owned());
    }
    let current = SyncEnvelopeV2::from_v1(build_envelope(local_view, counters, desk, sync_code));
    let merged_v2 = current.merge(&incoming);
    let mut merged = merged_v2.base.clone();
    merged.state.snapshot.committed_graphemes = merged_v2.total_graphemes();
    merged.refresh_unlocks();
    write_mirror(dir, &merged).map_err(|e| e.to_string())?;
    let newly_unlocked = apply_to_counters(&merged, counters);
    Ok(ImportOutcome {
        characters: merged.state.snapshot.committed_graphemes,
        days: merged.progress.lifetime_days.max(merged.state.book.marks().count() as u32),
        unlocked_total: merged.unlocked_apis().len(),
        newly_unlocked,
    })
}

/// Counts enabled Steam achievements in [`TABLE`] and how many of them are currently unlocked.
pub fn status_of(dir: &Path, sync_code: &str, unlocked: &[String]) -> MobileSyncStatus {
    let mirror_path = dir.join(FILE_NAME);
    let has_mirror = read_mirror(dir).is_some();
    let mirror_bytes = std::fs::metadata(&mirror_path).map(|m| m.len() as usize).unwrap_or(0);
    let steam_total = TABLE.iter().filter(|d| d.steam && d.enabled).count();
    let steam_unlocked = TABLE
        .iter()
        .filter(|d| d.steam && d.enabled && unlocked.iter().any(|u| u == d.api))
        .count();
    let local_unlocked = TABLE
        .iter()
        .filter(|d| !d.steam && d.enabled && unlocked.iter().any(|u| u == d.api))
        .count();
    MobileSyncStatus {
        pass_code: normalize_pass_code(sync_code),
        has_mirror,
        mirror_bytes,
        envelope_bytes: SYNC_BYTES,
        steam_unlocked,
        steam_total,
        local_unlocked,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use meowpuut_pets::codec::Delta;

    #[test]
    fn pass_code_generation_and_normalization_work() {
        let code = generate_pass_code().unwrap();
        assert!(code.starts_with("MEOW-"));
        assert_eq!(normalize_pass_code(&code.to_lowercase()), Some(code.clone()));
        assert_eq!(normalize_pass_code("not-a-code"), None);
        assert_ne!(group_prefix(&code), [0u8; 8]);
        assert_eq!(group_prefix(""), [0u8; 8]);
    }

    #[test]
    fn mobile_import_merges_crdt_state_counters_and_achievements() {
        let tmp = tempfile::tempdir().unwrap();
        let code = generate_pass_code().unwrap();
        let prefix = group_prefix(&code);

        // PC side: 400k chars + 6 places.
        let mut pc_state = PetState { epoch: 1, ..PetState::default() };
        pc_state.apply_delta(&Delta {
            epoch: 1,
            graphemes: 400_000,
            day: 20_500,
            day_graphemes: 2_000,
            who: 1,
            part: 2,
            ..Delta::default()
        });
        let mut pc_counters = Counters { epoch: 1, places: 0b0000_0011_1111, ..Counters::default() };

        // Mobile side: 650k chars + other 6 places + 12 festivals + hard achievement unlocked.
        let mut mobile = SyncEnvelope {
            flags: FLAG_PASS_ACTIVE,
            group_prefix: prefix,
            state: PetState { epoch: 1, ..PetState::default() },
            progress: meowpuut_pets::achievements::Progress {
                places: 0b1111_1100_0000,
                festivals: 12,
                lifetime_days: 365,
                max_day_graphemes: 8_500,
                ..Default::default()
            },
            unlocked_bits: [0u8; 16],
        };
        mobile.state.apply_delta(&Delta {
            epoch: 1,
            graphemes: 650_000,
            day: 20_501,
            day_graphemes: 8_500,
            who: 3,
            part: 4,
            ..Delta::default()
        });
        mobile.refresh_unlocks();
        let bytes = mobile.to_bytes();

        let out = import_bytes(
            tmp.path(),
            &pc_state,
            &mut pc_counters,
            &DeskCounters::default(),
            &code,
            &bytes,
        )
        .unwrap();
        assert_eq!(out.characters, 650_000);
        assert!(out.days >= 365);
        assert_eq!(pc_counters.places, place::ALL);
        assert_eq!(pc_counters.mobile_festivals, 12);
        assert!(pc_counters.unlocked.iter().any(|a| a == "PLACES_12"));
        assert!(pc_counters.unlocked.iter().any(|a| a == "DAYS_365"));
        assert!(pc_counters.unlocked.iter().any(|a| a == "DAYS_PEAK_8000"));
        assert!(pc_counters.unlocked.iter().any(|a| a == "FEST_12"));
        assert!(read_mirror(tmp.path()).is_some());

        // Different pass code group is rejected.
        let other_code = generate_pass_code().unwrap();
        assert!(import_bytes(
            tmp.path(),
            &pc_state,
            &mut pc_counters,
            &DeskCounters::default(),
            &other_code,
            &bytes,
        )
        .is_err());

        // Clearing mirror removes MobileSync.bin.
        clear_mirror(tmp.path());
        assert!(read_mirror(tmp.path()).is_none());
    }

    #[test]
    fn status_counts_all_enabled_steam_achievements() {
        let tmp = tempfile::tempdir().unwrap();
        let st = status_of(tmp.path(), "", &["CHARS_1".into(), "LOCAL_DAWN".into()]);
        assert_eq!(st.steam_total, 79); // 80 Steam defined, 1 (`CAT_CUDDLE`) gated for P1 duo_cuddle
        assert_eq!(st.steam_unlocked, 1);
        assert_eq!(st.local_unlocked, 1);
        assert_eq!(st.envelope_bytes, SYNC_BYTES);
    }
}
