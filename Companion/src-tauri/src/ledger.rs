//! The word-count ledger, `PetJournal.bin`, as the companion sees it: read-only, except "clear".
//!
//! The file is the one the input methods write (`Sources/PetLedger.swift` on macOS, `Windows/PetLedger.cpp` on
//! Windows): a fixed 2,884-byte `meowpuut_pets::codec::PetState`, format version 1, or 2 once the input methods have
//! recorded where the journey had got to on a day (`MarkView::place`). The companion never counts anything itself.
//!
//! Reading needs no lock: writers only ever rename a complete file over the old one.
//!
//! Clearing follows the writers' flush recipe, so it composes with input method processes that are flushing at the
//! same moment: lock `PetJournal.lock`, read the file, clear a copy (which raises the epoch), apply that copy's empty
//! delta and merge it into what was read, write a temporary file, rename it over the ledger, unlock. The raised epoch
//! is what makes it stick: a process still holding the old numbers flushes a delta and a state of the older epoch,
//! and `apply_delta` and `merge` both drop those, then adopt the cleared state.

use crate::files::{self, FileLock, LOCK_ATTEMPTS};
use meowpuut_pets::codec::{CodecError, Delta, PetState, STATE_BYTES};
use serde::Serialize;
use std::fmt;
use std::io;
use std::path::{Path, PathBuf};

pub const FILE_NAME: &str = "PetJournal.bin";
pub const LOCK_NAME: &str = "PetJournal.lock";
/// The state is 2,884 bytes; a bigger file is not a ledger (the text service uses the same limit).
const MAX_FILE: u64 = 64 * 1024;
const STATE_MAGIC: [u8; 2] = [0x9C, 0xA7];

/// What the file on disk turned out to be.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum FileStatus {
    /// No file yet: nothing has been typed with the ledger on (or it was removed).
    Missing,
    Ok,
    /// Wrong length, magic or checksum: the input methods start fresh on it, and so does this view.
    Corrupt,
    /// Written by a newer build this one cannot read.
    UnknownVersion { version: u8 },
}

#[derive(Debug)]
pub enum LedgerError {
    Io(io::Error),
    /// Another process held the lock for the whole wait.
    Busy,
}

impl fmt::Display for LedgerError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(f, "ledger file: {error}"),
            Self::Busy => write!(f, "the ledger is busy; try again in a moment"),
        }
    }
}

impl std::error::Error for LedgerError {}

impl From<io::Error> for LedgerError {
    fn from(error: io::Error) -> Self {
        if error.kind() == io::ErrorKind::WouldBlock {
            Self::Busy
        } else {
            Self::Io(error)
        }
    }
}

pub struct Ledger {
    dir: PathBuf,
}

/// The state read from disk and what the file was.
pub struct Reading {
    pub state: PetState,
    pub status: FileStatus,
    pub file_bytes: usize,
    pub header: Vec<u8>,
}

impl Ledger {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn file(&self) -> PathBuf {
        self.dir.join(FILE_NAME)
    }

    fn lock_file(&self) -> PathBuf {
        self.dir.join(LOCK_NAME)
    }

    /// Reads the ledger. A missing, damaged or newer file reads as a fresh state, as it does for the input methods.
    pub fn read(&self) -> Result<Reading, LedgerError> {
        let bytes = files::read_limited(&self.file(), MAX_FILE)?;
        let (state, status) = decode(&bytes);
        Ok(Reading { state, status, file_bytes: bytes.len(), header: bytes.iter().take(80).copied().collect() })
    }

    /// Forgets every record ("清除貓咪紀錄"); see the module documentation. Returns the state now on disk.
    pub fn clear(&self) -> Result<PetState, LedgerError> {
        self.clear_with(LOCK_ATTEMPTS)
    }

    pub fn clear_with(&self, lock_attempts: u32) -> Result<PetState, LedgerError> {
        let _lock = FileLock::acquire(&self.lock_file(), lock_attempts)?;
        let on_disk = files::read_limited(&self.file(), MAX_FILE).unwrap_or_default();
        let (mut disk, _) = decode(&on_disk);
        // This process's "live" copy is the one just read. A file this build cannot read may still carry an epoch
        // in its header: clear above it, so no copy of those records can come back.
        let mut live = disk.clone();
        live.epoch = live.epoch.max(header_epoch(&on_disk));
        live.clear();
        // The writers' flush, with nothing new to add: the empty delta of the cleared copy, then the copy itself.
        disk.apply_delta(&Delta { epoch: live.epoch, ..Delta::default() });
        disk.merge(&live);
        files::replace(&self.file(), &disk.to_bytes())?;
        Ok(disk)
    }
}

fn decode(bytes: &[u8]) -> (PetState, FileStatus) {
    if bytes.is_empty() {
        return (PetState::default(), FileStatus::Missing);
    }
    match PetState::from_bytes(bytes) {
        Ok(state) => (state, FileStatus::Ok),
        Err(CodecError::Version(version)) => (PetState::default(), FileStatus::UnknownVersion { version }),
        Err(CodecError::Corrupt) => (PetState::default(), FileStatus::Corrupt),
    }
}

/// The epoch field of any state-shaped header (magic `9C A7`, epoch u32 at 4), whatever its version; 0 otherwise.
fn header_epoch(bytes: &[u8]) -> u32 {
    if bytes.len() >= 8 && bytes[..2] == STATE_MAGIC {
        u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]])
    } else {
        0
    }
}

// ---- what the pages are given ------------------------------------------------------------------------------------

/// One day of the paw book: the local day index, the size band 0–4, who was there, which day parts had typing, and
/// where the journey had got to.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct MarkView {
    pub day: u32,
    pub size: u8,
    pub who: u8,
    pub part: u8,
    /// The place the cats had walked to that day: an index of the journey's places (0–11 today, the order of
    /// `meowpuut_glyph::scenes::ALL`). `None` (JSON null) for a day recorded before the ledger kept places, or by a
    /// phone: the journey page draws those as 「更早的路已經捲起來了」. Two computers that disagree about a day
    /// keep the place further along the road (`meowpuut_pets::book::further`).
    pub place: Option<u8>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct CatCounts {
    pub pets: u64,
    pub falls: u64,
    pub plays: u64,
}

/// Everything the pages show, all numbers. `marks` is oldest first; the book keeps the last 400 local days.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Summary {
    pub characters: u64,
    pub days: usize,
    pub epoch: u32,
    /// Local day index of the daily block, and the characters of that day (exact, but only for that one day).
    pub daily_day: u32,
    pub daily_characters: u32,
    pub mimimo: CatCounts,
    pub momo: CatCounts,
    pub marks: Vec<MarkView>,
    pub status: FileStatus,
    pub file: String,
    pub file_bytes: usize,
    pub state_bytes: usize,
    /// The first 80 bytes of the file, as hex (the header; the marks follow).
    pub header_hex: String,
}

impl Summary {
    pub fn of(ledger: &Ledger, reading: &Reading) -> Self {
        let state = &reading.state;
        let marks: Vec<MarkView> =
            state.book.marks().map(|m| MarkView { day: m.day, size: m.size, who: m.who, part: m.part, place: m.place_index() }).collect();
        let counts = |c: meowpuut_pets::Contacts| CatCounts { pets: c.pets, falls: c.falls, plays: c.plays };
        Self {
            characters: state.snapshot.committed_graphemes,
            days: marks.len(),
            epoch: state.epoch,
            daily_day: state.daily.day,
            daily_characters: state.daily.graphemes,
            mimimo: counts(state.snapshot.mimimo),
            momo: counts(state.snapshot.momo),
            marks,
            status: reading.status,
            file: ledger.file().display().to_string(),
            file_bytes: reading.file_bytes,
            state_bytes: STATE_BYTES,
            header_hex: reading.header.iter().map(|b| format!("{b:02x}")).collect::<Vec<_>>().join(" "),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use meowpuut_pets::book::{WHO_MIMIMO, WHO_MOMO};
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;

    /// A state the way the input methods build one: a delta per typed day.
    fn typed(epoch: u32, days: &[(u32, u32, u8, u8)]) -> PetState {
        let mut state = PetState { epoch, ..PetState::default() };
        for &(day, graphemes, who, part) in days {
            state.apply_delta(&Delta {
                epoch,
                graphemes: u64::from(graphemes),
                day,
                day_graphemes: graphemes,
                who,
                part,
                ..Delta::default()
            });
        }
        state
    }

    fn write(dir: &Path, state: &PetState) {
        std::fs::create_dir_all(dir).unwrap();
        std::fs::write(dir.join(FILE_NAME), state.to_bytes()).unwrap();
    }

    #[test]
    fn a_missing_file_reads_as_a_fresh_ledger() {
        let tmp = tempfile::tempdir().unwrap();
        let ledger = Ledger::new(tmp.path().join("Meowpuut"));
        let reading = ledger.read().unwrap();
        assert_eq!(reading.status, FileStatus::Missing);
        assert_eq!(reading.state, PetState::default());
        let summary = Summary::of(&ledger, &reading);
        assert_eq!((summary.characters, summary.days, summary.file_bytes), (0, 0, 0));
    }

    #[test]
    fn totals_and_marks_come_from_the_file() {
        let tmp = tempfile::tempdir().unwrap();
        let state = typed(2, &[(20_400, 80, WHO_MIMIMO, 0b00010), (20_401, 1_700, WHO_MOMO, 0b00011), (20_405, 4_100, 3, 0b10000)]);
        write(tmp.path(), &state);
        let ledger = Ledger::new(tmp.path());
        let reading = ledger.read().unwrap();
        assert_eq!(reading.status, FileStatus::Ok);
        let summary = Summary::of(&ledger, &reading);
        assert_eq!(summary.characters, 80 + 1_700 + 4_100);
        assert_eq!(summary.days, 3);
        assert_eq!(summary.epoch, 2);
        assert_eq!(
            summary.marks,
            vec![
                MarkView { day: 20_400, size: 0, who: WHO_MIMIMO, part: 0b00010, place: None },
                MarkView { day: 20_401, size: 3, who: WHO_MOMO, part: 0b00011, place: None },
                MarkView { day: 20_405, size: 4, who: 3, part: 0b10000, place: None },
            ]
        );
        assert_eq!((summary.daily_day, summary.daily_characters), (20_405, 4_100));
        assert_eq!(summary.file_bytes, STATE_BYTES);
        assert!(summary.header_hex.starts_with("9c a7 01 00 02 00 00 00"));
    }

    /// The day's place (format version 2), as the input methods record it on their flush: each day's own place, and
    /// none for the days from before (a version-1 file, the phones' format, reads with every place unknown).
    #[test]
    fn marks_carry_the_place_the_journey_had_got_to() {
        let tmp = tempfile::tempdir().unwrap();
        let mut state = typed(0, &[(20_400, 80, WHO_MIMIMO, 0b00010), (20_401, 1_700, WHO_MOMO, 0b00011)]);
        assert_eq!(state.to_bytes()[2], 1, "no place yet: version 1");
        // The PC input method's flush on day 20,402: its delta carries the place code (index 4 + 1).
        state.apply_delta(&Delta { graphemes: 900, day: 20_402, day_graphemes: 900, who: 3, part: 0b00100, place: 5, ..Delta::default() });
        write(tmp.path(), &state);
        let ledger = Ledger::new(tmp.path());
        let reading = ledger.read().unwrap();
        assert_eq!(reading.status, FileStatus::Ok);
        let summary = Summary::of(&ledger, &reading);
        assert_eq!(summary.marks.iter().map(|m| m.place).collect::<Vec<_>>(), vec![None, None, Some(4)]);
        assert!(summary.header_hex.starts_with("9c a7 02 00"));
        let json = serde_json::to_value(&summary).unwrap();
        assert_eq!(json["marks"][0]["place"], serde_json::Value::Null);
        assert_eq!(json["marks"][2]["place"], 4);
        // Clearing forgets the places too.
        assert!(ledger.clear().unwrap().book.is_empty());
        assert!(Summary::of(&ledger, &ledger.read().unwrap()).marks.is_empty());
    }

    #[test]
    fn damaged_and_newer_files_read_as_fresh_and_say_so() {
        let tmp = tempfile::tempdir().unwrap();
        let ledger = Ledger::new(tmp.path());
        let mut bytes = typed(1, &[(20_000, 500, 1, 1)]).to_bytes();
        bytes[100] ^= 0xFF;
        std::fs::write(ledger.file(), bytes).unwrap();
        let reading = ledger.read().unwrap();
        assert_eq!((reading.status, reading.state.clone()), (FileStatus::Corrupt, PetState::default()));
        bytes[2] = 9;
        std::fs::write(ledger.file(), bytes).unwrap();
        assert_eq!(ledger.read().unwrap().status, FileStatus::UnknownVersion { version: 9 });
        std::fs::write(ledger.file(), vec![0u8; 70_000]).unwrap();
        assert!(matches!(ledger.read(), Err(LedgerError::Io(_))));
    }

    #[test]
    fn clear_empties_the_file_and_raises_the_epoch() {
        let tmp = tempfile::tempdir().unwrap();
        let before = typed(3, &[(20_100, 900, 1, 2), (20_101, 50, 2, 4)]);
        write(tmp.path(), &before);
        let ledger = Ledger::new(tmp.path());
        let cleared = ledger.clear().unwrap();
        assert_eq!(cleared, PetState { epoch: 4, ..PetState::default() });
        let reading = ledger.read().unwrap();
        assert_eq!((reading.status, reading.state), (FileStatus::Ok, cleared));
        assert!(!tmp.path().join(format!("{FILE_NAME}.tmp{}", std::process::id())).exists());
        // Clearing an empty folder still writes a cleared ledger, so older copies elsewhere cannot come back.
        let fresh = Ledger::new(tmp.path().join("new"));
        assert_eq!(fresh.clear().unwrap().epoch, 1);
    }

    /// An input method process that read the ledger before the clear flushes afterwards, exactly as
    /// PetLedger.swift / PetLedger.cpp do: lock, read, apply its delta, merge its live state, write, merge back.
    #[test]
    fn a_process_flushing_old_numbers_after_a_clear_cannot_bring_them_back() {
        let tmp = tempfile::tempdir().unwrap();
        let ledger = Ledger::new(tmp.path());
        let mut live = typed(5, &[(20_200, 3_000, 1, 2)]);
        write(tmp.path(), &live);
        // It keeps typing before it flushes: these increments are its pending delta.
        let pending = Delta { epoch: 5, graphemes: 40, day: 20_201, day_graphemes: 40, who: 2, part: 8, ..Delta::default() };
        live.apply_delta(&pending);

        ledger.clear().unwrap();

        let mut disk = ledger.read().unwrap().state;
        disk.apply_delta(&pending);
        disk.merge(&live);
        std::fs::write(ledger.file(), disk.to_bytes()).unwrap();
        live.merge(&disk);

        let after = ledger.read().unwrap().state;
        assert_eq!(after, PetState { epoch: 6, ..PetState::default() });
        assert_eq!(live, after, "the process adopts the cleared ledger");
        // What it types next is counted from zero.
        let mut next = live.clone();
        next.apply_delta(&Delta { epoch: 6, graphemes: 7, day: 20_202, day_graphemes: 7, who: 1, part: 2, ..Delta::default() });
        assert_eq!(next.snapshot.committed_graphemes, 7);
    }

    #[test]
    fn clear_rises_above_an_epoch_it_cannot_otherwise_read() {
        let tmp = tempfile::tempdir().unwrap();
        let ledger = Ledger::new(tmp.path());
        let mut bytes = typed(41, &[(20_000, 10, 1, 1)]).to_bytes().to_vec();
        bytes[2] = 3; // a future version (2 is the day's place): unreadable here, but the epoch still sits at offset 4
        std::fs::write(ledger.file(), &bytes).unwrap();
        assert_eq!(ledger.clear().unwrap().epoch, 42);
    }

    #[test]
    fn clear_waits_for_the_lock_and_gives_up_when_it_stays_taken() {
        let tmp = tempfile::tempdir().unwrap();
        let ledger = Ledger::new(tmp.path());
        write(tmp.path(), &typed(1, &[(20_000, 10, 1, 1)]));

        // Held for the whole (short) wait: Busy, and the file is untouched.
        let held = FileLock::acquire(&tmp.path().join(LOCK_NAME), 1).unwrap();
        assert!(matches!(ledger.clear_with(3), Err(LedgerError::Busy)));
        assert_eq!(ledger.read().unwrap().state.epoch, 1);
        drop(held);

        // Held briefly by another thread: clear waits and then succeeds.
        let (taken, wait) = mpsc::channel();
        let path = tmp.path().join(LOCK_NAME);
        let holder = thread::spawn(move || {
            let lock = FileLock::acquire(&path, 1).unwrap();
            taken.send(()).unwrap();
            thread::sleep(Duration::from_millis(120));
            drop(lock);
        });
        wait.recv().unwrap();
        assert_eq!(ledger.clear().unwrap().epoch, 2);
        holder.join().unwrap();
    }
}
