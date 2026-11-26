#![forbid(unsafe_code)]
//! In-game "貓貓陪寫小書桌" anti-macro guard and locked ledger `Delta` writer.
//!
//! Enforces Rust monotonic rate limiting (`<= 4` graphemes/sec), cadence & character-variety entropy checks,
//! and a hidden local daily guard (`15,000` graphemes, matching `DAYS_PEAK_15000`). Zero text is ever persisted.
//!
//! ```
//! use meowpuut_companion_lib::pad::PadGuard;
//!
//! let mut guard = PadGuard::default();
//! let intervals = [180, 240, 310, 195, 270, 220, 350, 260];
//! let res = guard.check_and_accept(8, &intervals, 7, 10_000, 20_500).unwrap();
//! assert_eq!(res.accepted, 8);
//! // Robotic macro cadence (constant 50ms) is rejected:
//! assert!(guard.check_and_accept(16, &[50; 12], 8, 20_000, 20_500).is_err());
//! // Repetitive single-char mash is rejected:
//! assert!(guard.check_and_accept(20, &intervals, 1, 30_000, 20_500).is_err());
//! ```

use crate::files::{self, FileLock, LOCK_ATTEMPTS};
use crate::ledger::{Ledger, LedgerError};
use meowpuut_pets::book::{WHO_MIMIMO, WHO_MOMO};
use meowpuut_pets::codec::{Delta, PetState};
use meowpuut_pets::{Contacts, Pet};
use serde::Serialize;

/// Maximum sustained writing pad rate (4 graphemes/second, Rust monotonic clock).
pub const MAX_GRAPHEMES_PER_SEC: u64 = 4;
/// Short burst allowance so multi-character IME phrase commits pass naturally.
pub const BURST_ALLOWANCE: u64 = 24;
/// Maximum graphemes per single IPC commit call.
pub const MAX_BATCH_GRAPHEMES: u32 = 240;
/// Hidden local daily guard ceiling (aligns with `DAYS_PEAK_15000`; never shown as a task).
pub const DAILY_HIDDEN_CAP: u32 = 15_000;

/// Result of one validated writing-pad commit.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PadCommitOutcome {
    pub accepted: u32,
    pub daily_total: u32,
    pub total_chars: u64,
}

/// Stateful monotonic-clock guard for the in-game writing pad (stored in `AppState`, never serialized).
#[derive(Clone, Debug)]
pub struct PadGuard {
    tokens_milli: u64,
    last_mono_ms: Option<u64>,
    day: u32,
    today_accepted: u32,
}

impl Default for PadGuard {
    fn default() -> Self {
        Self {
            tokens_milli: BURST_ALLOWANCE * 1_000,
            last_mono_ms: None,
            day: 0,
            today_accepted: 0,
        }
    }
}

impl PadGuard {
    /// Validates `graphemes`, inter-keystroke `intervals_ms`, and `unique_chars` using Rust monotonic time.
    pub fn check_and_accept(
        &mut self,
        graphemes: u32,
        intervals_ms: &[u32],
        unique_chars: u32,
        now_mono_ms: u64,
        local_day: u32,
    ) -> Result<PadCommitOutcome, String> {
        if graphemes == 0 || graphemes > MAX_BATCH_GRAPHEMES {
            return Err(format!("invalid pad batch size: {graphemes}"));
        }
        if graphemes >= 12 && unique_chars <= 2 {
            return Err("low character variety filtered".to_owned());
        }
        if is_robotic_cadence(intervals_ms) {
            return Err("robotic keystroke cadence filtered".to_owned());
        }
        if local_day != self.day {
            self.day = local_day;
            self.today_accepted = 0;
        }
        if let Some(prev) = self.last_mono_ms {
            let elapsed_ms = now_mono_ms.saturating_sub(prev);
            let refill = elapsed_ms.saturating_mul(MAX_GRAPHEMES_PER_SEC);
            self.tokens_milli = self
                .tokens_milli
                .saturating_add(refill)
                .min(BURST_ALLOWANCE * 1_000);
        }
        self.last_mono_ms = Some(now_mono_ms);

        let avail_rate = (self.tokens_milli / 1_000) as u32;
        if avail_rate == 0 {
            return Err("rate limit exceeded (<= 4 graphemes/sec)".to_owned());
        }
        let avail_day = DAILY_HIDDEN_CAP.saturating_sub(self.today_accepted);
        let accepted = graphemes.min(avail_rate).min(avail_day);
        if accepted == 0 {
            return Ok(PadCommitOutcome { accepted: 0, daily_total: self.today_accepted, total_chars: 0 });
        }
        self.tokens_milli = self.tokens_milli.saturating_sub(u64::from(accepted) * 1_000);
        self.today_accepted = self.today_accepted.saturating_add(accepted);
        Ok(PadCommitOutcome { accepted, daily_total: self.today_accepted, total_chars: 0 })
    }
}

/// Rejects fixed-interval macro streams (`>= 8` intervals with tight spread `<= 4ms` and fast pace `< 120ms`).
#[must_use]
pub fn is_robotic_cadence(intervals_ms: &[u32]) -> bool {
    if intervals_ms.len() < 8 {
        return false;
    }
    let (mut min_v, mut max_v, mut sum) = (u32::MAX, 0u32, 0u64);
    for &ms in intervals_ms {
        min_v = min_v.min(ms);
        max_v = max_v.max(ms);
        sum = sum.saturating_add(u64::from(ms));
    }
    let avg = sum / (intervals_ms.len() as u64);
    max_v.saturating_sub(min_v) <= 4 && avg < 120
}

/// Applies a validated writing-pad grapheme batch to `PetJournal.bin` using the standard `FileLock` + `Delta` protocol.
pub fn commit_graphemes_to_ledger(
    ledger: &Ledger,
    graphemes: u32,
    day: u32,
    day_part_bit: u8,
) -> Result<PetState, LedgerError> {
    let lock_path = ledger.dir().join(crate::ledger::LOCK_NAME);
    let _lock = FileLock::acquire(&lock_path, LOCK_ATTEMPTS)?;
    let mut state = ledger.read()?.state;
    let day_graphemes = if state.daily.day == day {
        state.daily.graphemes.saturating_add(graphemes)
    } else {
        graphemes
    };
    state.apply_delta(&Delta {
        epoch: state.epoch,
        graphemes: u64::from(graphemes),
        day,
        day_graphemes,
        who: WHO_MIMIMO | WHO_MOMO,
        part: day_part_bit,
        ..Delta::default()
    });
    if let Some(parent) = ledger.file().parent() {
        std::fs::create_dir_all(parent)?;
    }
    files::replace(&ledger.file(), &state.to_bytes())?;
    Ok(state)
}

/// Safely records one throttled lounge pet (`Event::Petted`) into `PetJournal.bin` under `FileLock`.
pub fn record_pet_to_ledger(
    ledger: &Ledger,
    pet: Pet,
    day: u32,
    day_part_bit: u8,
) -> Result<PetState, LedgerError> {
    let lock_path = ledger.dir().join(crate::ledger::LOCK_NAME);
    let _lock = FileLock::acquire(&lock_path, LOCK_ATTEMPTS)?;
    let mut state = ledger.read()?.state;
    let (contacts, who) = match pet {
        Pet::Mimimo => ([Contacts { pets: 1, ..Contacts::default() }, Contacts::default()], WHO_MIMIMO),
        Pet::Momo => ([Contacts::default(), Contacts { pets: 1, ..Contacts::default() }], WHO_MOMO),
    };
    state.apply_delta(&Delta {
        epoch: state.epoch,
        contacts,
        day,
        day_graphemes: if state.daily.day == day { state.daily.graphemes } else { 0 },
        who,
        part: day_part_bit,
        ..Delta::default()
    });
    if let Some(parent) = ledger.file().parent() {
        std::fs::create_dir_all(parent)?;
    }
    files::replace(&ledger.file(), &state.to_bytes())?;
    Ok(state)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rate_limit_entropy_and_hidden_daily_cap_work() {
        let mut g = PadGuard::default();
        let human = [170, 210, 295, 160, 240, 310, 190, 225];
        // Accepts burst up to 24 immediately
        assert_eq!(g.check_and_accept(24, &human, 18, 1_000, 20_500).unwrap().accepted, 24);
        // Immediate next call at same ms is rate-limited
        assert!(g.check_and_accept(4, &human, 4, 1_000, 20_500).is_err());
        // After 1 second, 4 graphemes refill
        assert_eq!(g.check_and_accept(10, &human, 8, 2_000, 20_500).unwrap().accepted, 4);
        // Rejects robotic cadence and repetitive single char
        assert!(g.check_and_accept(10, &[40; 10], 8, 10_000, 20_500).is_err());
        assert!(g.check_and_accept(16, &human, 2, 10_000, 20_500).is_err());
        // Daily cap at 15,000
        g.today_accepted = DAILY_HIDDEN_CAP - 3;
        let capped = g.check_and_accept(10, &human, 8, 20_000, 20_500).unwrap();
        assert_eq!((capped.accepted, capped.daily_total), (3, DAILY_HIDDEN_CAP));
        let zero = g.check_and_accept(5, &human, 5, 30_000, 20_500).unwrap();
        assert_eq!(zero.accepted, 0);
        // Next day resets cap
        let next_day = g.check_and_accept(6, &human, 6, 40_000, 20_501).unwrap();
        assert_eq!((next_day.accepted, next_day.daily_total), (6, 6));
    }

    #[test]
    fn ledger_commit_and_pet_write_safely() {
        let tmp = tempfile::tempdir().unwrap();
        let ledger = Ledger::new(tmp.path());
        let s1 = commit_graphemes_to_ledger(&ledger, 120, 20_500, 2).unwrap();
        assert_eq!((s1.snapshot.committed_graphemes, s1.daily.graphemes), (120, 120));
        let s2 = record_pet_to_ledger(&ledger, Pet::Mimimo, 20_500, 2).unwrap();
        assert_eq!((s2.snapshot.mimimo.pets, s2.snapshot.committed_graphemes), (1, 120));
    }
}
