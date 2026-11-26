//! 時間花在哪裡 (FINAL.md §11): the time ledger `Activity.bin` as the companion sees it, read-only except "clear",
//! and what the 「時間花在哪裡」 page under 行旅 is given.
//!
//! The file is the one 喵撲貓窩 writes while 「記錄時間花在哪裡」 is on (`Desk/Sources/ActivityLedger.swift`,
//! `Windows/Desk/ActivityLedger.cpp`): a fixed 12,824-byte `meowpuut_activity::ledger::ActivityState`, 400 local
//! days × 10 categories × active minutes plus day-part bits. No app names, identifiers, titles or times are in it.
//!
//! Reading needs no lock (the desk renames a complete file over the old one). Clearing is the desk's flush with
//! nothing new, exactly like [`crate::ledger`]'s: lock `Activity.lock`, read, clear a copy (the epoch rises above
//! anything the file holds, even a version this build cannot read), apply the copy's empty delta and merge it, write a
//! temporary file, rename, unlock. A desk still holding the old minutes flushes them with the old epoch, and both
//! `apply_delta` and `merge` drop those.

use crate::files::{self, FileLock, LOCK_ATTEMPTS};
use crate::ledger::{FileStatus, LedgerError};
use meowpuut_activity::ledger::{ActivityState, CodecError, Delta, STATE_BYTES};
use meowpuut_activity::{Category, CATEGORIES};
use serde::Serialize;
use std::path::PathBuf;

pub const FILE_NAME: &str = "Activity.bin";
pub const LOCK_NAME: &str = "Activity.lock";
const MAX_FILE: u64 = 64 * 1024;
const STATE_MAGIC: [u8; 2] = [0x9C, 0xC0];
/// Day parts in `DayPart` bit order: 清晨, 白天, 傍晚, 晚上, 深夜.
pub const PARTS: usize = 5;

pub struct ActivityLedger {
    dir: PathBuf,
}

pub struct ActivityReading {
    pub state: ActivityState,
    pub status: FileStatus,
    pub file_bytes: usize,
}

impl ActivityLedger {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }

    pub fn file(&self) -> PathBuf {
        self.dir.join(FILE_NAME)
    }

    pub fn exists(&self) -> bool {
        self.file().exists()
    }

    /// A missing, damaged or newer file reads as an empty ledger, as it does for the desk.
    pub fn read(&self) -> Result<ActivityReading, LedgerError> {
        let bytes = files::read_limited(&self.file(), MAX_FILE)?;
        let (state, status) = decode(&bytes);
        Ok(ActivityReading { state, status, file_bytes: bytes.len() })
    }

    /// 清除時間紀錄: every day, for good. Returns the state now on disk.
    pub fn clear(&self) -> Result<ActivityState, LedgerError> {
        self.clear_with(LOCK_ATTEMPTS)
    }

    pub fn clear_with(&self, lock_attempts: u32) -> Result<ActivityState, LedgerError> {
        let _lock = FileLock::acquire(&self.dir.join(LOCK_NAME), lock_attempts)?;
        let on_disk = files::read_limited(&self.file(), MAX_FILE).unwrap_or_default();
        let (mut disk, _) = decode(&on_disk);
        let mut live = disk.clone();
        // An unreadable (newer) file may still carry its epoch in the header: clear above it.
        while live.epoch() < header_epoch(&on_disk) {
            live.clear();
        }
        live.clear();
        disk.apply_delta(&Delta { epoch: live.epoch(), ..Delta::default() });
        disk.merge(&live);
        files::replace(&self.file(), &disk.to_bytes())?;
        Ok(disk)
    }
}

fn decode(bytes: &[u8]) -> (ActivityState, FileStatus) {
    if bytes.is_empty() {
        return (ActivityState::default(), FileStatus::Missing);
    }
    match ActivityState::from_bytes(bytes) {
        Ok(state) => (state, FileStatus::Ok),
        Err(CodecError::Version(version)) => (ActivityState::default(), FileStatus::UnknownVersion { version }),
        Err(CodecError::Corrupt) => (ActivityState::default(), FileStatus::Corrupt),
    }
}

fn header_epoch(bytes: &[u8]) -> u32 {
    if bytes.len() >= 8 && bytes[..2] == STATE_MAGIC {
        u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]])
    } else {
        0
    }
}

// ---- the page ----------------------------------------------------------------------------------------------------

/// One day of the week chart: minutes by category (category order), and their sum.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct DayView {
    pub day: u32,
    pub minutes: [u32; CATEGORIES],
    pub total: u32,
}

/// One of the five day parts over the month: on how many days it had any activity, and how many of those days each
/// category was there (category order; never sorted, so nothing is ranked).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PartView {
    pub days: u32,
    pub by_category: [u32; CATEGORIES],
}

/// Everything the 「時間花在哪裡」 page shows. All numbers; the page draws them.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityView {
    /// The seven days from `week_start`.
    pub week: Vec<DayView>,
    pub week_total: u32,
    /// Minutes by category over `month_first..=month_last`.
    pub month: [u32; CATEGORIES],
    pub month_total: u32,
    /// Days of that month with any minutes.
    pub month_days: u32,
    pub parts: Vec<PartView>,
    /// The oldest and newest day the ledger holds (0 when empty): the page's ‹ › stop there.
    pub first_day: u32,
    pub last_day: u32,
    pub days_kept: usize,
    /// 「只留近 N 天」 (0: all 400).
    pub keep_days: u16,
    pub status: FileStatus,
    pub file: String,
    pub file_bytes: usize,
    pub state_bytes: usize,
    pub epoch: u32,
}

/// Aggregates the ledger for one week (seven days from `week_start`) and one month (`month_first..=month_last`, local
/// day indexes the page computes from its calendar).
pub fn view(state: &ActivityState, week_start: u32, month_first: u32, month_last: u32) -> ActivityView {
    let minutes = |day: u32| -> [u32; CATEGORIES] {
        state.get(day).map(|d| d.minutes.map(u32::from)).unwrap_or([0; CATEGORIES])
    };
    let week: Vec<DayView> = (0..7)
        .map(|i| week_start.saturating_add(i))
        .map(|day| {
            let m = minutes(day);
            DayView { day, minutes: m, total: m.iter().sum() }
        })
        .collect();
    let mut month = [0u32; CATEGORIES];
    let mut month_days = 0;
    let mut parts: Vec<PartView> = (0..PARTS).map(|_| PartView { days: 0, by_category: [0; CATEGORIES] }).collect();
    for d in state.days().filter(|d| (month_first..=month_last).contains(&d.day)) {
        if d.total() > 0 {
            month_days += 1;
        }
        for c in Category::ALL {
            month[c.index()] += u32::from(d.minutes_of(c));
        }
        for (p, part) in parts.iter_mut().enumerate() {
            let bit = 1u8 << p;
            let mut any = false;
            for c in 0..CATEGORIES {
                if d.parts[c] & bit != 0 {
                    part.by_category[c] += 1;
                    any = true;
                }
            }
            part.days += u32::from(any);
        }
    }
    let mut days = state.days();
    let first_day = days.next().map_or(0, |d| d.day);
    let last_day = state.days().last().map_or(0, |d| d.day);
    ActivityView {
        week_total: week.iter().map(|d| d.total).sum(),
        week,
        month_total: month.iter().sum(),
        month,
        month_days,
        parts,
        first_day,
        last_day,
        days_kept: state.len(),
        keep_days: state.keep().days,
        status: FileStatus::Missing,
        file: String::new(),
        file_bytes: 0,
        state_bytes: STATE_BYTES,
        epoch: state.epoch(),
    }
}

impl ActivityView {
    pub fn of(ledger: &ActivityLedger, reading: &ActivityReading, week_start: u32, month_first: u32, month_last: u32) -> Self {
        Self {
            status: reading.status,
            file: ledger.file().display().to_string(),
            file_bytes: reading.file_bytes,
            ..view(&reading.state, week_start, month_first, month_last)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use meowpuut_activity::DayPart;

    fn ledger_with(cells: &[(u32, Category, DayPart, u16)]) -> ActivityState {
        let mut s = ActivityState::default();
        for &(day, c, p, m) in cells {
            s.add(day, c, p, m);
        }
        s
    }

    #[test]
    fn the_week_has_seven_days_with_minutes_by_category() {
        let s = ledger_with(&[
            (20_400, Category::Code, DayPart::Day, 95),
            (20_400, Category::Web, DayPart::Night, 30),
            (20_402, Category::Write, DayPart::Dawn, 12),
            (20_407, Category::Game, DayPart::Night, 60), // the next week
        ]);
        let v = view(&s, 20_400, 20_390, 20_419);
        assert_eq!(v.week.len(), 7);
        assert_eq!(v.week.iter().map(|d| d.day).collect::<Vec<_>>(), (20_400..20_407).collect::<Vec<_>>());
        assert_eq!(v.week[0].minutes[Category::Code.index()], 95);
        assert_eq!(v.week[0].minutes[Category::Web.index()], 30);
        assert_eq!(v.week[0].total, 125);
        assert_eq!(v.week[1].total, 0, "a day without minutes is an empty bar, not a gap in the list");
        assert_eq!(v.week[2].minutes[Category::Write.index()], 12);
        assert_eq!(v.week_total, 137);
    }

    #[test]
    fn the_month_totals_and_day_parts_stay_inside_the_month() {
        let s = ledger_with(&[
            (20_389, Category::Code, DayPart::Day, 500), // the day before the month
            (20_390, Category::Code, DayPart::Day, 60),
            (20_390, Category::Code, DayPart::Night, 30),
            (20_390, Category::Chat, DayPart::Night, 10),
            (20_400, Category::Media, DayPart::LateNight, 45),
            (20_419, Category::Learn, DayPart::Dawn, 20),
            (20_420, Category::Learn, DayPart::Dawn, 999), // the day after
        ]);
        let v = view(&s, 20_400, 20_390, 20_419);
        assert_eq!(v.month[Category::Code.index()], 90);
        assert_eq!(v.month[Category::Chat.index()], 10);
        assert_eq!(v.month[Category::Media.index()], 45);
        assert_eq!(v.month[Category::Learn.index()], 20);
        assert_eq!(v.month_total, 165);
        assert_eq!(v.month_days, 3);
        // 晚上 (Night) had two categories on one day: one day, counted once; each category counted on it.
        let night = &v.parts[DayPart::Night as usize];
        assert_eq!(night.days, 1);
        assert_eq!(night.by_category[Category::Code.index()], 1);
        assert_eq!(night.by_category[Category::Chat.index()], 1);
        assert_eq!(v.parts[DayPart::Day as usize].days, 1);
        assert_eq!(v.parts[DayPart::LateNight as usize].days, 1);
        assert_eq!(v.parts[DayPart::Dawn as usize].days, 1, "the day after the month is not in it");
        assert_eq!(v.parts[DayPart::Evening as usize], PartView { days: 0, by_category: [0; CATEGORIES] });
        assert_eq!((v.first_day, v.last_day, v.days_kept), (20_389, 20_420, 5));
    }

    #[test]
    fn an_empty_ledger_is_all_zeros() {
        let v = view(&ActivityState::default(), 20_000, 20_000, 20_030);
        assert_eq!((v.week_total, v.month_total, v.month_days, v.first_day, v.last_day), (0, 0, 0, 0, 0));
        assert!(v.parts.iter().all(|p| p.days == 0));
        assert_eq!(v.state_bytes, 12_824);
    }

    #[test]
    fn read_and_clear_follow_the_desks_recipe() {
        let tmp = tempfile::tempdir().unwrap();
        let ledger = ActivityLedger::new(tmp.path());
        let r = ledger.read().unwrap();
        assert_eq!((r.status, r.file_bytes), (FileStatus::Missing, 0));
        let mut s = ledger_with(&[(20_400, Category::Code, DayPart::Day, 95)]);
        s.clear(); // epoch 1, as after an earlier clear
        s.add(20_401, Category::Write, DayPart::Day, 40);
        std::fs::write(ledger.file(), s.to_bytes()).unwrap();
        let r = ledger.read().unwrap();
        assert_eq!((r.status, r.file_bytes, r.state.len()), (FileStatus::Ok, STATE_BYTES, 1));

        // A desk that read the file before the clear flushes its minutes afterwards: they do not come back.
        let mut desk_live = r.state.clone();
        let mut pending = Delta { epoch: desk_live.epoch(), ..Delta::default() };
        pending.add(20_402, Category::Code, DayPart::Night, 5);
        desk_live.apply_delta(&pending);

        let cleared = ledger.clear().unwrap();
        assert_eq!(cleared.epoch(), 2);
        assert!(cleared.is_empty());
        let mut disk = ledger.read().unwrap().state;
        disk.apply_delta(&pending);
        disk.merge(&desk_live);
        assert!(disk.is_empty(), "older minutes are dropped");
        desk_live.merge(&disk);
        assert_eq!(desk_live, cleared, "the desk adopts the cleared ledger");
        assert!(!tmp.path().join(format!("{FILE_NAME}.tmp{}", std::process::id())).exists());
    }

    #[test]
    fn damaged_newer_and_locked_files() {
        let tmp = tempfile::tempdir().unwrap();
        let ledger = ActivityLedger::new(tmp.path());
        let mut bytes = ledger_with(&[(20_000, Category::Web, DayPart::Day, 3)]).to_bytes();
        bytes[40] ^= 0xFF;
        std::fs::write(ledger.file(), bytes).unwrap();
        assert_eq!(ledger.read().unwrap().status, FileStatus::Corrupt);
        let mut newer = ActivityState::default();
        for _ in 0..7 {
            newer.clear();
        }
        let mut bytes = newer.to_bytes();
        bytes[2] = 9;
        std::fs::write(ledger.file(), bytes).unwrap();
        assert_eq!(ledger.read().unwrap().status, FileStatus::UnknownVersion { version: 9 });
        assert_eq!(ledger.clear().unwrap().epoch(), 8, "cleared above the epoch it could not otherwise read");
        let held = FileLock::acquire(&tmp.path().join(LOCK_NAME), 1).unwrap();
        assert!(matches!(ledger.clear_with(2), Err(LedgerError::Busy)));
        drop(held);
    }
}
