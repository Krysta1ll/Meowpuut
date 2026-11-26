#![forbid(unsafe_code)]
//! Monotonic desk-pet counters read by the companion for achievement settlement and pure-game growth.
//!
//! Written in batched atomic saves to `DeskCounters.json` beside `PetJournal.bin`. Contains only unsigned counters
//! and the ledger epoch—no text, no timestamps, no app names. Reset whenever `PetJournal.bin` is cleared.

use crate::files;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const FILE_NAME: &str = "DeskCounters.json";

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DeskCounters {
    pub epoch: u32,
    pub outings: u32,
    pub purrs: u32,
    pub rides: u32,
    pub caught: u32,
    pub found: u32,
    pub roof: u32,
    pub steps: u64,
    pub nose: u32,
    pub moved: u32,
    /// Monotonic active companion minutes (OS idle < 60s; zero keyboard content).
    pub active_minutes: u64,
    /// Monotonic days together (typed OR >=30 active minutes).
    pub together_days: u32,
}

impl DeskCounters {
    /// Returns the componentwise maximum of two counter snapshots (monotonic merge).
    ///
    /// ```
    /// use meowpuut_companion_lib::desk_counters::DeskCounters;
    /// let a = DeskCounters { epoch: 1, outings: 2, steps: 500, active_minutes: 40, ..Default::default() };
    /// let b = DeskCounters { epoch: 1, outings: 1, roof: 3, steps: 800, together_days: 2, ..Default::default() };
    /// let merged = a.max_with(&b);
    /// assert_eq!((merged.outings, merged.roof, merged.steps, merged.active_minutes, merged.together_days), (2, 3, 800, 40, 2));
    /// ```
    #[must_use]
    pub fn max_with(&self, other: &Self) -> Self {
        Self {
            epoch: self.epoch.max(other.epoch),
            outings: self.outings.max(other.outings),
            purrs: self.purrs.max(other.purrs),
            rides: self.rides.max(other.rides),
            caught: self.caught.max(other.caught),
            found: self.found.max(other.found),
            roof: self.roof.max(other.roof),
            steps: self.steps.max(other.steps),
            nose: self.nose.max(other.nose),
            moved: self.moved.max(other.moved),
            active_minutes: self.active_minutes.max(other.active_minutes),
            together_days: self.together_days.max(other.together_days),
        }
    }
}

#[must_use]
pub fn file_path(dir: &Path) -> PathBuf {
    dir.join(FILE_NAME)
}

#[must_use]
pub fn read(dir: &Path) -> DeskCounters {
    let Ok(bytes) = files::read_limited(&file_path(dir), 16_384) else {
        return DeskCounters::default();
    };
    serde_json::from_slice(&bytes).unwrap_or_default()
}

pub fn clear(dir: &Path) {
    let _ = std::fs::remove_file(file_path(dir));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_missing_valid_and_corrupt_files_safely() {
        let tmp = tempfile::tempdir().unwrap();
        assert_eq!(read(tmp.path()), DeskCounters::default());
        std::fs::write(
            file_path(tmp.path()),
            r#"{"epoch":2,"outings":4,"purrs":1,"rides":2,"roof":5,"steps":1200,"activeMinutes":90,"togetherDays":3}"#,
        )
        .unwrap();
        let got = read(tmp.path());
        assert_eq!((got.epoch, got.outings, got.steps, got.active_minutes, got.together_days), (2, 4, 1200, 90, 3));
        clear(tmp.path());
        assert_eq!(read(tmp.path()), DeskCounters::default());
        std::fs::write(file_path(tmp.path()), b"{bad json").unwrap();
        assert_eq!(read(tmp.path()), DeskCounters::default());
    }
}
