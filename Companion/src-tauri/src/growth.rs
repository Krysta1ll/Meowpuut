#![forbid(unsafe_code)]
//! Pure-game dual-track growth engine: unified meow-chars (`max(real_chars, active_minutes * 6)`),
//! `together_days` (typed OR `>= 30` active minutes), and companion trail landmark unlocks.
//!
//! Zero daily ceilings, zero FOMO streaks, zero keyboard content—only OS idle seconds `< 60s`.
//!
//! ```
//! use meowpuut_companion_lib::growth::{trail_places_mask, view_meow_chars, CompanionGrowth};
//!
//! assert_eq!(view_meow_chars(100, 50), 300);
//! assert_eq!(view_meow_chars(500, 50), 500);
//! assert_eq!(trail_places_mask(1_000), 0x0FFF);
//! let mut g = CompanionGrowth::default();
//! for _ in 0..30 {
//!     assert!(g.record_active_minute(1, 20_500, 12));
//! }
//! assert_eq!((g.active_minutes, g.together_days), (30, 1));
//! assert!(!g.record_active_minute(1, 20_500, 60)); // idle >= 60s ignored
//! ```

use meowpuut_pets::achievements::place;
use serde::{Deserialize, Serialize};

/// Unified growth rate for active companion minutes (6 meow-chars/min; never stacks with real typing).
pub const MEOW_CHARS_PER_MINUTE: u64 = 6;
/// Active minutes in a single local day needed to count as a `together_day` without typing.
pub const TOGETHER_DAY_MIN_MINUTES: u32 = 30;
/// Maximum OS idle seconds still considered actively present.
pub const MAX_ACTIVE_IDLE_SECS: u32 = 59;
/// Rolling cap on remembered local `together_days` indices (matches the 400-day Paw Book ring).
const MAX_REMEMBERED_DAYS: usize = 400;

/// Unified meow-chars fuel: `max(real_chars, active_minutes * 6)` so typing + presence never double-dip.
#[must_use]
pub const fn view_meow_chars(real_chars: u64, active_minutes: u64) -> u64 {
    let from_minutes = active_minutes.saturating_mul(MEOW_CHARS_PER_MINUTE);
    if real_chars >= from_minutes { real_chars } else { from_minutes }
}

/// Companion trail landmark thresholds (active minutes -> `place` bit), allowing pure-game players to visit all 12.
const TRAIL_MILESTONES: [(u64, u8); 12] = [
    (5, place::SEA),
    (30, place::STREET),
    (55, place::FIELD),
    (110, place::BAMBOO),
    (170, place::SNOW),
    (240, place::FUJI),
    (320, place::HONG_KONG),
    (410, place::VOLCANO),
    (510, place::DESERT),
    (620, place::MAPLE),
    (760, place::MOON),
    (920, place::LUNAR),
];

/// Returns the 12-bit landmark mask unlocked by the companion trail for `active_minutes`.
#[must_use]
pub const fn trail_places_mask(active_minutes: u64) -> u16 {
    let mut mask = 0u16;
    let mut i = 0;
    while i < TRAIL_MILESTONES.len() {
        let (need, bit) = TRAIL_MILESTONES[i];
        if active_minutes >= need {
            mask |= 1u16 << bit;
        }
        i += 1;
    }
    mask & place::ALL
}

/// Persisted local pure-game growth counters inside `Companion.json` (reset when `epoch` bumps).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct CompanionGrowth {
    pub epoch: u32,
    pub active_minutes: u64,
    pub today_day: u32,
    pub today_active_minutes: u32,
    pub together_days: u32,
    pub recent_together_days: Vec<u32>,
}

impl CompanionGrowth {
    /// Aligns growth counters to the active ledger `epoch`.
    pub fn align_epoch(&mut self, epoch: u32) {
        if self.epoch != epoch {
            *self = Self { epoch, ..Self::default() };
        }
    }

    /// Records one active companion minute if `idle_seconds <= 59`. Returns `true` when credited.
    pub fn record_active_minute(&mut self, epoch: u32, local_day: u32, idle_seconds: u32) -> bool {
        self.align_epoch(epoch);
        if idle_seconds > MAX_ACTIVE_IDLE_SECS {
            return false;
        }
        self.active_minutes = self.active_minutes.saturating_add(1);
        if local_day != self.today_day {
            self.today_day = local_day;
            self.today_active_minutes = 0;
        }
        self.today_active_minutes = self.today_active_minutes.saturating_add(1);
        if self.today_active_minutes >= TOGETHER_DAY_MIN_MINUTES
            && !self.recent_together_days.contains(&local_day)
        {
            self.together_days = self.together_days.saturating_add(1);
            self.recent_together_days.push(local_day);
            if self.recent_together_days.len() > MAX_REMEMBERED_DAYS {
                let excess = self.recent_together_days.len() - MAX_REMEMBERED_DAYS;
                self.recent_together_days.drain(0..excess);
            }
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dual_track_fuel_takes_max_and_never_double_counts() {
        assert_eq!(view_meow_chars(0, 0), 0);
        assert_eq!(view_meow_chars(0, 240), 1_440);
        assert_eq!(view_meow_chars(3_000, 240), 3_000);
        assert_eq!(view_meow_chars(u64::MAX, 100), u64::MAX);
    }

    #[test]
    fn companion_trail_reaches_all_12_places_monotonically() {
        let mut prev = 0u16;
        for m in [0, 5, 30, 55, 110, 170, 240, 320, 410, 510, 620, 760, 920] {
            let cur = trail_places_mask(m);
            assert_eq!(prev & cur, prev, "monotonic at {m}");
            prev = cur;
        }
        assert_eq!(trail_places_mask(920), place::ALL);
    }

    #[test]
    fn active_minutes_and_together_days_respect_idle_and_epoch() {
        let mut g = CompanionGrowth::default();
        assert!(!g.record_active_minute(1, 20_500, 60));
        for _ in 0..29 {
            assert!(g.record_active_minute(1, 20_500, 5));
        }
        assert_eq!((g.active_minutes, g.together_days), (29, 0));
        assert!(g.record_active_minute(1, 20_500, 5));
        assert_eq!((g.active_minutes, g.together_days), (30, 1));
        // Extra minutes on same day do not double-count together_days
        assert!(g.record_active_minute(1, 20_500, 5));
        assert_eq!(g.together_days, 1);
        // Next day counts a second together_day
        for _ in 0..30 {
            assert!(g.record_active_minute(1, 20_501, 0));
        }
        assert_eq!((g.active_minutes, g.together_days), (61, 2));
        // Epoch bump resets cleanly
        g.align_epoch(2);
        assert_eq!((g.epoch, g.active_minutes, g.together_days), (2, 0, 0));
    }
}
