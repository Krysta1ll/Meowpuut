//! Writes a made-up ledger for trying the companion without touching a real one:
//!
//!   cargo run --example sample_ledger -- /tmp/meowpuut-sample            (days up to today, UTC)
//!   cargo run --example sample_ledger -- /tmp/meowpuut-sample 20780      (days up to this local day index)
//!
//! It builds the state the way the input methods do, one `Delta` per typed day, so the file is exactly what they
//! would write: about 230 typed days over the last 400, every size band, both cats, all five day parts, and today.
//!
//! Every typed day carries the place the journey had got to that day (the way the input methods record it: the place
//! of the characters typed so far, `meowpuut_glyph::journey`), except the first 70 days of the 400, which stay
//! "recorded before the ledger kept places" so the scroll's rolled-up earlier road shows.
//!
//! It also writes `Activity.bin` (時間花在哪裡) the way the desk does, minutes added cell by cell through
//! `meowpuut-activity`: about 150 days, weekdays busier than weekends, every category and day part, and today.

use meowpuut_activity::ledger::ActivityState;
use meowpuut_activity::{Category, DayPart};
use meowpuut_glyph::journey::{self, Journey};
use meowpuut_pets::codec::{Delta, PetState};
use meowpuut_pets::Contacts;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

fn main() {
    let mut args = std::env::args().skip(1);
    let dir = PathBuf::from(args.next().expect("usage: sample_ledger <folder> [today's day index]"));
    let today: u32 = args.next().map(|d| d.parse().expect("a day index")).unwrap_or_else(|| {
        (SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs() / 86_400) as u32
    });

    let mut seed = 0x5EED_CAFE_u64;
    let mut next = move || {
        seed = seed.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = seed;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    };

    let mut state = PetState { epoch: 1, ..PetState::default() };
    // The road starts with the characters typed before the 400 days the book keeps.
    let mut typed_so_far: i64 = 61_000;
    let mut road = Journey::new();
    for day in today - 399..=today {
        let typed = day == today || next() % 100 < 58;
        if !typed {
            continue;
        }
        // Mostly a few hundred characters, sometimes a long day.
        let graphemes = match next() % 20 {
            0..=4 => 20 + next() % 80,
            5..=11 => 100 + next() % 400,
            12..=16 => 500 + next() % 1_000,
            17..=18 => 1_500 + next() % 2_500,
            _ => 4_000 + next() % 3_000,
        } as u32;
        let who = [1u8, 2, 3, 3][(next() % 4) as usize];
        let mut part = 0u8;
        for bit in 0..5 {
            if next() % 3 == 0 {
                part |= 1 << bit;
            }
        }
        if part == 0 {
            part = 1 << 1;
        }
        typed_so_far += i64::from(graphemes);
        // A place code is the place's index + 1; 0 is "no place" (the first 70 days).
        let place = if day < today - 329 { 0 } else { journey::places(journey::distance_of(typed_so_far), &mut road).here as u8 + 1 };
        state.apply_delta(&Delta { epoch: 1, graphemes: u64::from(graphemes), day, day_graphemes: graphemes, who, part, place, ..Delta::default() });
    }
    // Earlier typing, before the 400 days the book keeps, and the cats' counters.
    state.snapshot.committed_graphemes += 61_000;
    state.snapshot.mimimo = Contacts { pets: 141, falls: 6, plays: 12 };
    state.snapshot.momo = Contacts { pets: 203, falls: 4, plays: 9 };

    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("PetJournal.bin"), state.to_bytes()).unwrap();
    println!(
        "{}: {} characters, {} days, today = {today}",
        dir.join("PetJournal.bin").display(),
        state.snapshot.committed_graphemes,
        state.book.len()
    );

    // 時間花在哪裡: a working rhythm with quieter weekends (day 0 = 1970-01-01, a Thursday).
    let mut activity = ActivityState::default();
    let parts = [DayPart::Dawn, DayPart::Day, DayPart::Evening, DayPart::Night, DayPart::LateNight];
    for day in today - 150..=today {
        let weekend = matches!((day + 4) % 7, 0 | 6);
        if day != today && next() % 100 < if weekend { 35 } else { 8 } {
            continue;
        }
        // (category, typical minutes on a weekday, on a weekend, the parts it lives in)
        let plan: [(Category, u64, u64, &[usize]); 10] = [
            (Category::Write, 50, 30, &[1, 3]),
            (Category::Code, 170, 20, &[1, 3, 4]),
            (Category::Design, 35, 10, &[1]),
            (Category::Web, 60, 70, &[1, 2, 3]),
            (Category::Chat, 40, 30, &[1, 2]),
            (Category::Media, 30, 90, &[2, 3, 4]),
            (Category::Game, 10, 80, &[3, 4]),
            (Category::Learn, 25, 30, &[0, 3]),
            (Category::Office, 70, 0, &[1]),
            (Category::Other, 15, 15, &[1, 2]),
        ];
        for (category, weekday_minutes, weekend_minutes, lives) in plan {
            let typical = if weekend { weekend_minutes } else { weekday_minutes };
            if typical == 0 || next() % 100 < 30 {
                continue;
            }
            let total = typical / 2 + next() % typical;
            // Spread over one or two of its parts.
            let first = lives[(next() % lives.len() as u64) as usize];
            let second = lives[(next() % lives.len() as u64) as usize];
            let half = total / 2;
            activity.add(day, category, parts[first], (total - half) as u16);
            activity.add(day, category, parts[second], half.max(1) as u16);
        }
    }
    std::fs::write(dir.join("Activity.bin"), activity.to_bytes()).unwrap();
    println!("{}: {} days", dir.join("Activity.bin").display(), activity.len());
}
