//! Festival calendar for the Paw Book (vermilion festival days) and achievement counting (`FEST_1`, `FEST_12`).
//!
//! Reads the bundled festival tables (the 24 core festivals from `Resources/Festivals/festivals.json`, prioritized,
//! plus the world festival calendar from `Android/app/src/main/assets/festivals-world/festivals-world.days.json`).
//! Never leaks named dates to Steam: only distinct festival IDs met on days the user typed are counted locally.

use chrono::NaiveDate;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;

const CORE_JSON: &str = include_str!("../../../Resources/Festivals/festivals.json");
const WORLD_JSON: &str = include_str!("../../../Android/app/src/main/assets/festivals-world/festivals-world.days.json");
const UNIX_EPOCH: Option<NaiveDate> = NaiveDate::from_ymd_opt(1970, 1, 1);

#[derive(Deserialize)]
struct CoreFile {
    themes: Vec<Entry>,
}

#[derive(Deserialize)]
struct WorldFile {
    festivals: Vec<Entry>,
}

#[derive(Deserialize)]
struct Entry {
    id: String,
    names: Names,
    #[serde(default)]
    days: Vec<[String; 3]>,
}

#[derive(Deserialize)]
struct Names {
    hant: String,
    hans: String,
}

/// One festival falling on a calendar day, returned to the Paw Book page.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FestivalDay {
    pub day: u32,
    pub id: String,
    pub name: [String; 2],
    pub main: bool,
}

struct Calendar {
    by_day: BTreeMap<u32, Vec<FestivalDay>>,
}

/// Converts `"yyyy-MM-dd"` to a local day index (days since 1970-01-01, matching `Companion/src/dates.ts`).
///
/// ```
/// use meowpuut_companion_lib::festivals::parse_day;
/// assert_eq!(parse_day("1970-01-01"), Some(0));
/// assert_eq!(parse_day("2026-02-17"), Some(20501));
/// assert_eq!(parse_day("invalid"), None);
/// ```
#[must_use]
pub fn parse_day(iso: &str) -> Option<u32> {
    let date = NaiveDate::parse_from_str(iso, "%Y-%m-%d").ok()?;
    let epoch = UNIX_EPOCH?;
    u32::try_from(date.signed_duration_since(epoch).num_days()).ok()
}

fn add_entries(by_day: &mut BTreeMap<u32, Vec<FestivalDay>>, entries: Vec<Entry>) {
    for entry in entries {
        for [first, main, last] in entry.days {
            let (Some(start), Some(peak), Some(end)) = (parse_day(&first), parse_day(&main), parse_day(&last)) else {
                continue;
            };
            // Cap abnormally long seasonal spans (e.g. 81-day Shujiu) to their main day so the calendar stays vermilion
            // only on true festival days.
            let (lo, hi) = if end.saturating_sub(start) > 10 { (peak, peak) } else { (start, end) };
            for day in lo..=hi {
                let list = by_day.entry(day).or_default();
                if list.iter().any(|existing| existing.id == entry.id) {
                    continue;
                }
                list.push(FestivalDay {
                    day,
                    id: entry.id.clone(),
                    name: [entry.names.hant.clone(), entry.names.hans.clone()],
                    main: day == peak,
                });
            }
        }
    }
}

fn calendar() -> &'static Calendar {
    static CAL: OnceLock<Calendar> = OnceLock::new();
    CAL.get_or_init(|| {
        let mut by_day = BTreeMap::new();
        if let Ok(core) = serde_json::from_str::<CoreFile>(CORE_JSON) {
            add_entries(&mut by_day, core.themes);
        }
        if let Ok(world) = serde_json::from_str::<WorldFile>(WORLD_JSON) {
            add_entries(&mut by_day, world.festivals);
        }
        Calendar { by_day }
    })
}

/// Primary festival (if any) on `day`. Core festivals take priority over world ones.
#[must_use]
pub fn on_day(day: u32) -> Option<&'static FestivalDay> {
    let list = calendar().by_day.get(&day)?;
    list.iter().find(|f| f.main).or_else(|| list.first())
}

/// All festival days inside `[first_day, last_day]` inclusive (one primary entry per day, for the Paw Book month view).
#[must_use]
pub fn in_range(first_day: u32, last_day: u32) -> Vec<FestivalDay> {
    if first_day > last_day {
        return Vec::new();
    }
    calendar()
        .by_day
        .range(first_day..=last_day)
        .filter_map(|(_, list)| list.iter().find(|f| f.main).or_else(|| list.first()).cloned())
        .collect()
}

/// Records any festivals falling on `typed_days` into `met` (deduplicated, sorted) and returns the count.
pub fn collect_met(typed_days: impl IntoIterator<Item = u32>, met: &mut Vec<String>) -> u32 {
    let mut seen: BTreeSet<String> = met.iter().cloned().collect();
    let cal = calendar();
    for day in typed_days {
        if let Some(list) = cal.by_day.get(&day) {
            for f in list {
                seen.insert(f.id.clone());
            }
        }
    }
    *met = seen.into_iter().collect();
    u32::try_from(met.len()).unwrap_or(u32::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spring_festival_2026_is_found_and_prioritized() {
        let day = parse_day("2026-02-17").unwrap();
        let hit = on_day(day).expect("Spring Festival 2026");
        assert_eq!(hit.id, "spring_festival");
        assert_eq!(hit.name[0], "春節");
        assert!(hit.main);
    }

    #[test]
    fn range_and_met_deduplicate_across_years() {
        let mut met = Vec::new();
        let d1 = parse_day("2026-02-17").unwrap();
        let d2 = parse_day("2027-02-06").unwrap();
        let count_same = collect_met([d1, d2], &mut met);
        assert!(met.iter().any(|id| id == "spring_festival"));
        assert!(count_same >= 1);
        let feb = in_range(parse_day("2026-02-01").unwrap(), parse_day("2026-02-28").unwrap());
        assert!(feb.iter().any(|f| f.id == "spring_festival"));
    }
}
