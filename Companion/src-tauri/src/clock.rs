//! The one place the companion reads clocks: a monotonic clock for the presence builder, the wall clock for the share
//! limit, and the local day index and minute (days since 1970-01-01 in this computer's time zone, as the ledgers and
//! the pages count them).

use crate::companion::WallNow;
use meowpuut_activity::Clock;
use std::sync::OnceLock;
use std::time::Instant;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Now {
    pub mono_ms: u64,
    pub wall_ms: i64,
    pub day: u32,
    pub minute: u16,
}

impl Now {
    pub const fn clock(self) -> Clock {
        Clock { mono_ms: self.mono_ms, day: self.day, minute: self.minute }
    }

    pub const fn wall(self) -> WallNow {
        WallNow { ms: self.wall_ms, day: self.day }
    }
}

fn start() -> Instant {
    static START: OnceLock<Instant> = OnceLock::new();
    *START.get_or_init(Instant::now)
}

pub fn now() -> Now {
    use chrono::{Local, Timelike};
    let mono_ms = u64::try_from(start().elapsed().as_millis()).unwrap_or(u64::MAX).saturating_add(1);
    let local = Local::now();
    let naive = local.naive_local();
    let day = naive.and_utc().timestamp().div_euclid(86_400);
    let minute = (naive.hour() * 60 + naive.minute()) as u16;
    Now { mono_ms, wall_ms: local.timestamp_millis(), day: u32::try_from(day).unwrap_or(0), minute }
}
