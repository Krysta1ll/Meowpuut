//! Steam Cloud, opt in (FINAL.md §6.4). Off by default: Steam's global Auto-Cloud switch cannot express that, so the
//! ledger normally lives outside any cloud folder and this module copies it in with `ISteamRemoteStorage` only after
//! the user turned 「雲端」 on.
//!
//! - **What goes up**: this computer's ledger, nothing else, as `ledger-<install id>.bin` (a fixed-length
//!   `meowpuut_pets::codec::PetState`). The install id is 16 random bytes, made once and kept in `Companion.json`;
//!   it is never derived from the hardware or the account. Each computer writes only its own file.
//! - **When it is read**: on open, the same moment achievements settle ([`crate::settle_on_open`]). The other
//!   computers' `ledger-*.bin` are merged into the view with `PetState::merge`; the local file is never changed by it.
//! - **What never goes up**: the word-choice memory (`mimo_user.db`, its `-wal` / `-shm`), the phrase dictionary,
//!   `Activity.bin`, `settings.json`, `Companion.json`, and anything else. The companion's own settings are variable
//!   length, so they stay home too (FINAL asks for fixed-length files only; if in doubt, ledgers only).
//!   [`check_upload`] is the one gate every write passes; a test pins what it refuses.
//! - **Off**: `FileDelete` of this computer's file (kept as "pending" in `Companion.json` until Steam answers).
//! - A trial data folder (`MEOWPUUT_DATA_DIR`) never syncs.
//!
//! The raw `cloud_*` methods of [`SteamApi`] are called from here only (a test scans the sources for it).

use crate::steam::SteamApi;
use meowpuut_pets::codec::{PetState, STATE_BYTES};
use meowpuut_pets::sync::{decode_any, SYNC_BYTES, SYNC_V2_BYTES};
use serde::Serialize;
use std::path::Path;

pub const PREFIX: &str = "ledger-";
pub const SUFFIX: &str = ".bin";
/// 16 random bytes, as lowercase hex.
pub const ID_CHARS: usize = 32;
/// More other computers than this are not read (one account does not have dozens of PCs).
pub const MAX_OTHERS: usize = 32;

/// A fresh random install id; `None` when the system has no random source (then nothing is synced).
pub fn new_install_id() -> Option<String> {
    let mut bytes = [0u8; ID_CHARS / 2];
    getrandom::fill(&mut bytes).ok()?;
    Some(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

pub fn valid_install_id(id: &str) -> bool {
    id.len() == ID_CHARS && id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

pub fn file_name(id: &str) -> String {
    format!("{PREFIX}{id}{SUFFIX}")
}

/// The install id inside a cloud file name, when it is a ledger's name.
pub fn id_of(name: &str) -> Option<&str> {
    name.strip_prefix(PREFIX)?.strip_suffix(SUFFIX).filter(|id| valid_install_id(id))
}

/// Why something may not go to the cloud.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refused {
    /// Not named `ledger-<32 hex>.bin` (a path, `mimo_user.db`, `-wal`, `-shm`, a phrase file, `Activity.bin`, …).
    Name,
    /// Not exactly the ledger's fixed length.
    Length,
    /// The right length but not a readable ledger (a database page, a text file, a newer version, …).
    NotALedger,
    /// A ledger's name, but not this computer's: each computer writes only its own file.
    NotOwn,
}

/// The only gate for a write: this computer's own ledger file name, one of the three fixed-length binary formats
/// (`STATE_BYTES` 2,884 B, `MPSY` v1 3,072 B, or `MPSY` v2 4,096 B), and bytes that decode with valid checksums.
pub fn check_upload(name: &str, own: &str, bytes: &[u8]) -> Result<(), Refused> {
    if id_of(name).is_none() {
        return Err(Refused::Name);
    }
    if name != own {
        return Err(Refused::NotOwn);
    }
    match bytes.len() {
        STATE_BYTES => PetState::from_bytes(bytes).map(drop).map_err(|_| Refused::NotALedger),
        SYNC_BYTES | SYNC_V2_BYTES => decode_any(bytes).map(drop).map_err(|_| Refused::NotALedger),
        _ => Err(Refused::Length),
    }
}

/// A local file may be the source of an upload only when it is the ledger itself, `PetJournal.bin`: not the word-choice
/// database, its WAL, the phrase dictionary, the time ledger or a settings file.
pub fn check_local_path(path: &Path) -> Result<(), Refused> {
    match path.file_name().and_then(|n| n.to_str()) {
        Some(crate::ledger::FILE_NAME) => Ok(()),
        _ => Err(Refused::Name),
    }
}

/// What the page is told about the cloud.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum CloudState {
    /// The switch is off (nothing is, or is about to be, in the cloud).
    #[default]
    Off,
    /// A trial data folder (`MEOWPUUT_DATA_DIR`): never syncs.
    Trial,
    /// This build has no Steam (no `steam` feature).
    NotBuilt,
    /// Steam is not running: the choice is kept, and it takes effect the next time Steam is there.
    NoSteam,
    /// Steam Cloud is switched off in Steam itself (for the account or for this app).
    SteamCloudOff,
    /// A write or read failed this time; tried again on the next open.
    Failed,
    /// On and in step. `others`: other computers' ledgers merged into the view; `skipped`: their files this build
    /// could not read (damaged, newer version, wrong length, too many).
    #[serde(rename_all = "camelCase")]
    Synced { others: u32, skipped: u32, wrote: bool },
}

/// The one door to Steam's remote storage: knows this computer's file name and refuses everything else.
pub struct Cloud<'a> {
    steam: &'a mut dyn SteamApi,
    own: String,
}

impl<'a> Cloud<'a> {
    pub fn new(steam: &'a mut dyn SteamApi, install_id: &str) -> Option<Self> {
        valid_install_id(install_id).then(|| Self { steam, own: file_name(install_id) })
    }

    pub fn own_name(&self) -> &str {
        &self.own
    }

    /// Copies this computer's ledger into its own cloud file. `Ok(true)`: written; `Ok(false)`: the cloud already has
    /// exactly these bytes.
    pub fn upload(&mut self, state: &PetState) -> Result<bool, Refused> {
        let bytes = state.to_bytes();
        check_upload(&self.own, &self.own, &bytes)?;
        if self.steam.cloud_read(&self.own).as_deref() == Some(&bytes[..]) {
            return Ok(false);
        }
        Ok(self.steam.cloud_write(&self.own, &bytes))
    }

    /// The other computers' ledgers (never this one's own file) and how many files were passed over.
    pub fn others(&mut self) -> (Vec<PetState>, u32) {
        let mut states = Vec::new();
        let mut skipped = 0u32;
        for (name, size) in self.steam.cloud_files() {
            if id_of(&name).is_none() || name == self.own {
                continue;
            }
            let valid_size = size == STATE_BYTES as u64 || size == SYNC_BYTES as u64 || size == SYNC_V2_BYTES as u64;
            if states.len() >= MAX_OTHERS || !valid_size {
                skipped += 1;
                continue;
            }
            let decoded = self.steam.cloud_read(&name).and_then(|b| match b.len() {
                STATE_BYTES => PetState::from_bytes(&b).ok(),
                SYNC_BYTES | SYNC_V2_BYTES => decode_any(&b).ok().map(|v2| {
                    let mut st = v2.base.state.clone();
                    st.snapshot.committed_graphemes = v2.total_graphemes();
                    st
                }),
                _ => None,
            });
            match decoded {
                Some(state) => states.push(state),
                None => skipped += 1,
            }
        }
        (states, skipped)
    }

    /// 「雲端」 turned off: deletes this computer's file only. True when it is gone.
    pub fn remove_own(&mut self) -> bool {
        self.steam.cloud_delete(&self.own);
        !self.steam.cloud_files().iter().any(|(name, _)| *name == self.own)
    }
}

/// What the switches and the build say, for [`run`].
pub struct Input<'a> {
    pub opt_in: bool,
    /// 「雲端」 was turned off and this computer's file has not been deleted yet.
    pub delete_pending: bool,
    pub install_id: &'a str,
    /// A trial data folder.
    pub trial: bool,
    /// This build can talk to Steam.
    pub built: bool,
}

pub struct Outcome {
    pub state: CloudState,
    /// The other computers' ledgers as of this open (merged into the view).
    pub others: Vec<PetState>,
    /// This computer's file was deleted (the pending flag can go).
    pub deleted: bool,
}

impl Outcome {
    fn only(state: CloudState) -> Self {
        Self { state, others: Vec::new(), deleted: false }
    }
}

/// One cloud step, on open and whenever the switch changes. `upload_local`: the local ledger is a readable one (or
/// still empty), so its copy may go up; a damaged or newer file is never copied over a good cloud copy.
pub fn run(steam: &mut dyn SteamApi, input: &Input, local: &PetState, upload_local: bool) -> Outcome {
    if input.trial {
        return Outcome::only(CloudState::Trial);
    }
    if !input.opt_in && !input.delete_pending {
        return Outcome::only(CloudState::Off);
    }
    if !input.built {
        return Outcome::only(if input.opt_in { CloudState::NotBuilt } else { CloudState::Off });
    }
    if !steam.connected() {
        return Outcome::only(if input.opt_in { CloudState::NoSteam } else { CloudState::Off });
    }
    let Some(mut cloud) = Cloud::new(steam, input.install_id) else {
        return Outcome::only(if input.opt_in { CloudState::Failed } else { CloudState::Off });
    };
    if !input.opt_in {
        let deleted = cloud.remove_own();
        return Outcome { state: CloudState::Off, others: Vec::new(), deleted };
    }
    if !cloud.steam.cloud_enabled() {
        return Outcome::only(CloudState::SteamCloudOff);
    }
    let wrote = if upload_local {
        match cloud.upload(local) {
            Ok(wrote) => wrote,
            Err(_) => return Outcome::only(CloudState::Failed),
        }
    } else {
        false
    };
    let (others, skipped) = cloud.others();
    Outcome { state: CloudState::Synced { others: others.len() as u32, skipped, wrote }, others, deleted: false }
}

/// The local ledger with the other computers' merged in: what the pages show and the achievements are settled on.
pub fn merged(local: &PetState, others: &[PetState]) -> PetState {
    let mut view = local.clone();
    for other in others {
        view.merge(other);
    }
    view
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::steam::tests::FakeSteam;
    use meowpuut_pets::codec::Delta;

    const ID_A: &str = "0123456789abcdef0123456789abcdef";
    const ID_B: &str = "fedcba9876543210fedcba9876543210";

    fn typed(epoch: u32, days: &[(u32, u32)]) -> PetState {
        let mut state = PetState { epoch, ..PetState::default() };
        for &(day, graphemes) in days {
            state.apply_delta(&Delta { epoch, graphemes: u64::from(graphemes), day, day_graphemes: graphemes, who: 1, part: 2, ..Delta::default() });
        }
        state
    }

    fn input<'a>(id: &'a str, opt_in: bool) -> Input<'a> {
        Input { opt_in, delete_pending: false, install_id: id, trial: false, built: true }
    }

    #[test]
    fn install_ids_are_random_and_well_formed() {
        let a = new_install_id().unwrap();
        let b = new_install_id().unwrap();
        assert!(valid_install_id(&a) && valid_install_id(&b));
        assert_ne!(a, b);
        assert!(!valid_install_id("") && !valid_install_id(&a.to_uppercase()) && !valid_install_id(&a[1..]));
        assert_eq!(id_of(&file_name(&a)), Some(a.as_str()));
    }

    #[test]
    fn opting_in_writes_exactly_one_file_and_it_is_this_computers_ledger() {
        let local = typed(1, &[(20_000, 1_200), (20_001, 40)]);
        let mut steam = FakeSteam::online();
        let out = run(&mut steam, &input(ID_A, true), &local, true);
        assert_eq!(out.state, CloudState::Synced { others: 0, skipped: 0, wrote: true });
        assert_eq!(steam.calls.iter().filter(|c| c.starts_with("cloud write")).count(), 1, "{:?}", steam.calls);
        assert_eq!(steam.cloud.keys().collect::<Vec<_>>(), [&file_name(ID_A)]);
        let bytes = &steam.cloud[&file_name(ID_A)];
        assert_eq!(bytes.len(), STATE_BYTES);
        assert_eq!(PetState::from_bytes(bytes).unwrap(), local);
        // Opening again with nothing changed writes nothing.
        steam.calls.clear();
        let again = run(&mut steam, &input(ID_A, true), &local, true);
        assert_eq!(again.state, CloudState::Synced { others: 0, skipped: 0, wrote: false });
        assert!(steam.calls.iter().all(|c| !c.starts_with("cloud write")), "{:?}", steam.calls);
        // New typing changes this computer's own file, and only that one.
        let more = typed(1, &[(20_000, 1_200), (20_001, 90)]);
        run(&mut steam, &input(ID_A, true), &more, true);
        assert_eq!(steam.cloud.len(), 1);
        assert_eq!(PetState::from_bytes(&steam.cloud[&file_name(ID_A)]).unwrap(), more);
    }

    #[test]
    fn two_computers_merge_and_never_touch_each_others_file() {
        let a = typed(1, &[(20_000, 1_000), (20_002, 300)]);
        let b = typed(1, &[(20_001, 500), (20_002, 700)]);
        let mut steam = FakeSteam::online();
        // Computer B opened first and uploaded; then A opens.
        run(&mut steam, &input(ID_B, true), &b, true);
        let b_file = steam.cloud[&file_name(ID_B)].clone();
        let out = run(&mut steam, &input(ID_A, true), &a, true);
        assert_eq!(out.state, CloudState::Synced { others: 1, skipped: 0, wrote: true });
        assert_eq!(steam.cloud[&file_name(ID_B)], b_file, "A never writes B's file");
        let view = merged(&a, &out.others);
        assert_eq!(view.book.marks().map(|m| m.day).collect::<Vec<_>>(), [20_000, 20_001, 20_002]);
        assert_eq!(view.snapshot.committed_graphemes, a.snapshot.committed_graphemes.max(b.snapshot.committed_graphemes));
        // The local ledger itself is untouched by merging.
        assert_eq!(a.book.marks().count(), 2);
        // Merging is the same either way round.
        let from_b = run(&mut steam, &input(ID_B, true), &b, true);
        assert_eq!(merged(&b, &from_b.others).to_bytes(), view.to_bytes());
        // A file of the newer epoch (the other computer cleared its records) wins, as `merge` says.
        let cleared = typed(2, &[(20_005, 9)]);
        steam.cloud.insert(file_name(ID_B), cleared.to_bytes().to_vec());
        let out = run(&mut steam, &input(ID_A, true), &a, true);
        assert_eq!(merged(&a, &out.others).epoch, 2);
    }

    #[test]
    fn unreadable_or_odd_files_in_the_cloud_are_passed_over() {
        let local = typed(1, &[(20_000, 5)]);
        let mut steam = FakeSteam::online();
        steam.cloud.insert(file_name(ID_B), vec![1; STATE_BYTES]); // right length, not a ledger
        steam.cloud.insert(format!("{PREFIX}{ID_A}x{SUFFIX}"), vec![0; 3]); // not ours to read at all
        steam.cloud.insert("mimo_user.db".to_owned(), vec![0; 100]);
        let out = run(&mut steam, &input(ID_A, true), &local, true);
        assert_eq!(out.state, CloudState::Synced { others: 0, skipped: 1, wrote: true });
        assert!(steam.reads.lock().unwrap().iter().all(|n| n != "mimo_user.db"));
    }

    #[test]
    fn opting_out_deletes_this_computers_file_only() {
        let local = typed(1, &[(20_000, 5)]);
        let other = typed(1, &[(20_001, 7)]);
        let mut steam = FakeSteam::online();
        steam.cloud.insert(file_name(ID_B), other.to_bytes().to_vec());
        run(&mut steam, &input(ID_A, true), &local, true);
        assert_eq!(steam.cloud.len(), 2);
        steam.calls.clear();
        let off = Input { opt_in: false, delete_pending: true, ..input(ID_A, false) };
        let out = run(&mut steam, &off, &local, true);
        assert!(out.deleted && out.state == CloudState::Off);
        assert_eq!(steam.cloud.keys().collect::<Vec<_>>(), [&file_name(ID_B)], "the other computer's file stays");
        assert_eq!(steam.calls.iter().filter(|c| c.starts_with("cloud delete")).collect::<Vec<_>>(), [&format!("cloud delete {}", file_name(ID_A))]);
        // Off and nothing pending: Steam is not even asked.
        steam.calls.clear();
        let idle = run(&mut steam, &input(ID_A, false), &local, true);
        assert_eq!(idle.state, CloudState::Off);
        assert!(steam.calls.is_empty());
    }

    #[test]
    fn a_delete_waits_for_steam_and_the_choice_is_kept_without_it() {
        let local = typed(1, &[(20_000, 5)]);
        let mut steam = FakeSteam { online: false, ..FakeSteam::online() };
        steam.cloud.insert(file_name(ID_A), local.to_bytes().to_vec());
        // Turned on, no Steam client: "Steam 沒有開著", nothing sent, nothing breaks.
        let out = run(&mut steam, &input(ID_A, true), &local, true);
        assert_eq!(out.state, CloudState::NoSteam);
        let off = Input { opt_in: false, delete_pending: true, ..input(ID_A, false) };
        let out = run(&mut steam, &off, &local, true);
        assert!(!out.deleted, "still pending");
        assert!(steam.calls.is_empty() && steam.cloud.len() == 1);
        // Steam comes back: now it goes.
        steam.online = true;
        assert!(run(&mut steam, &off, &local, true).deleted);
        assert!(steam.cloud.is_empty());
    }

    #[test]
    fn a_trial_folder_a_build_without_steam_and_steams_own_switch_never_sync() {
        let local = typed(1, &[(20_000, 5)]);
        let mut steam = FakeSteam::online();
        let trial = Input { trial: true, delete_pending: true, ..input(ID_A, true) };
        assert_eq!(run(&mut steam, &trial, &local, true).state, CloudState::Trial);
        assert!(steam.calls.is_empty() && steam.cloud.is_empty());
        let unbuilt = Input { built: false, ..input(ID_A, true) };
        assert_eq!(run(&mut steam, &unbuilt, &local, true).state, CloudState::NotBuilt);
        steam.cloud_allowed = false;
        assert_eq!(run(&mut steam, &input(ID_A, true), &local, true).state, CloudState::SteamCloudOff);
        assert!(steam.calls.is_empty() && steam.cloud.is_empty());
        // A damaged local ledger is not copied over the cloud copy.
        steam.cloud_allowed = true;
        let out = run(&mut steam, &input(ID_A, true), &PetState::default(), false);
        assert_eq!(out.state, CloudState::Synced { others: 0, skipped: 0, wrote: false });
        assert!(steam.cloud.is_empty());
        // A broken install id syncs nothing.
        assert_eq!(run(&mut steam, &input("nope", true), &local, true).state, CloudState::Failed);
    }

    /// The forbidden files: only a fixed-length ledger under this computer's own `ledger-<id>.bin` passes.
    #[test]
    fn nothing_but_a_fixed_length_ledger_may_be_uploaded() {
        let own = file_name(ID_A);
        let ledger = typed(1, &[(20_000, 5)]).to_bytes();
        assert_eq!(check_upload(&own, &own, &ledger), Ok(()));
        // Names: the word-choice memory and its siblings, the phrase dictionary, the time ledger, settings, paths.
        for name in [
            "mimo_user.db",
            "mimo_user.db-wal",
            "mimo_user.db-shm",
            "mimo_user.db-journal",
            "phrases.txt",
            "phrases.json",
            "Phrases.sqlite",
            "Activity.bin",
            "PetJournal.bin",
            "settings.json",
            "Companion.json",
            "ledger.bin",
            "ledger-.bin",
            "ledger-0123.bin",
            "ledger-0123456789ABCDEF0123456789ABCDEF.bin",
            "ledger-0123456789abcdef0123456789abcdef.bin.wal",
            "ledger-0123456789abcdef0123456789abcdef.bin-wal",
            "../ledger-0123456789abcdef0123456789abcdef.bin",
            "sub/ledger-0123456789abcdef0123456789abcdef.bin",
            "",
        ] {
            assert_eq!(check_upload(name, &own, &ledger), Err(Refused::Name), "{name:?}");
        }
        // Another computer's file is not ours to write.
        assert_eq!(check_upload(&file_name(ID_B), &own, &ledger), Err(Refused::NotOwn));
        // Content: wrong length (the word-choice database, the time ledger, a text file), or the right length but
        // not a ledger (a SQLite page, zeros, a newer version).
        let sqlite = {
            let mut page = vec![0u8; 4096];
            page[..16].copy_from_slice(b"SQLite format 3\0");
            page
        };
        for (what, bytes) in [
            ("wal-sized", vec![0u8; 32 + 24 + 4096]),
            ("time ledger length", vec![0u8; 12_824]),
            ("empty", vec![]),
            ("one byte short", ledger[..STATE_BYTES - 1].to_vec()),
            ("one byte long", [&ledger[..], &[0u8][..]].concat()),
            ("text", b"{\"skin\":\"daily\"}".to_vec()),
        ] {
            assert_eq!(check_upload(&own, &own, &bytes), Err(Refused::Length), "{what}");
        }
        // 4,096 B SQLite page matches MPSY v2 size, so it is refused by magic/checksum verification:
        assert_eq!(check_upload(&own, &own, &sqlite), Err(Refused::NotALedger));
        // Valid MPSY v1 (3,072 B) and v2 (4,096 B) envelopes pass whitelist:
        let v1_env = meowpuut_pets::sync::SyncEnvelope::default().to_bytes();
        let v2_env = meowpuut_pets::sync::SyncEnvelopeV2::default().to_bytes_v2();
        assert_eq!(check_upload(&own, &own, &v1_env), Ok(()));
        assert_eq!(check_upload(&own, &own, &v2_env), Ok(()));
        assert_eq!(check_upload(&own, &own, &vec![0u8; STATE_BYTES]), Err(Refused::NotALedger));
        assert_eq!(check_upload(&own, &own, &sqlite[..STATE_BYTES]), Err(Refused::NotALedger));
        let mut newer = ledger;
        newer[2] = 2; // a version this build does not know
        assert_eq!(check_upload(&own, &own, &newer), Err(Refused::NotALedger));
        let mut flipped = ledger;
        flipped[100] ^= 1; // checksum
        assert_eq!(check_upload(&own, &own, &flipped), Err(Refused::NotALedger));
        // Local paths: only PetJournal.bin is a source.
        assert_eq!(check_local_path(Path::new("/data/Meowpuut/PetJournal.bin")), Ok(()));
        for path in ["/data/mimo_user.db", "/data/mimo_user.db-wal", "/data/mimo_user.db-shm", "/data/Phrases.db", "/data/Activity.bin", "/data/settings.json", "/data/", ""] {
            assert_eq!(check_local_path(Path::new(path)), Err(Refused::Name), "{path:?}");
        }
    }

    /// The door itself, not just the checker: writes of any other name or content never reach Steam.
    #[test]
    fn the_door_has_no_way_to_write_another_name() {
        let mut steam = FakeSteam::online();
        steam.cloud.insert("mimo_user.db".to_owned(), vec![9; 10]);
        {
            let mut cloud = Cloud::new(&mut steam, ID_A).unwrap();
            assert_eq!(cloud.own_name(), file_name(ID_A));
            assert!(cloud.upload(&typed(1, &[(20_000, 1)])).unwrap());
            // The database is never listed as a ledger, never read, never deleted by "off".
            let (others, skipped) = cloud.others();
            assert!((others.len(), skipped) == (0, 0));
            assert!(cloud.remove_own());
        }
        assert!(steam.cloud.contains_key("mimo_user.db"), "removing ours leaves everything else");
        assert!(steam.calls.iter().all(|c| !c.contains("mimo_user.db")), "{:?}", steam.calls);
        assert!(steam.reads.lock().unwrap().iter().all(|n| n != "mimo_user.db"));
        assert!(Cloud::new(&mut steam, "not-an-id").is_none());
    }

    /// Only this module (and the Steam door itself) may call the raw remote-storage methods.
    #[test]
    fn only_this_module_calls_the_raw_cloud_methods() {
        let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        for entry in std::fs::read_dir(&src).unwrap() {
            let path = entry.unwrap().path();
            let name = path.file_name().unwrap().to_string_lossy().into_owned();
            if !name.ends_with(".rs") || name == "cloud.rs" || name == "steam.rs" {
                continue;
            }
            let text = std::fs::read_to_string(&path).unwrap();
            for raw in ["cloud_write(", "cloud_delete(", "cloud_read(", "cloud_files("] {
                assert!(!text.contains(raw), "{name} calls {raw} directly; go through cloud::Cloud");
            }
        }
    }
}
