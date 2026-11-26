#![forbid(unsafe_code)]
//! Quadratic Paragon star tier (`★0 → ★∞`, pure from `view_meow_chars`, C6) and title unlock/equip logic.
//!
//! ```
//! use meowpuut_companion_lib::lounge_paragon::{paragon_of, star_req};
//! assert_eq!(star_req(0), Some(20_000));
//! assert_eq!(star_req(1), Some(41_000));
//! assert_eq!(star_req(25), Some(1_145_000));
//! let p = paragon_of(145_000, 100, 100);
//! assert_eq!((p.paragon_level, p.stardust_pips), (5, 0));
//! ```

use crate::lounge::LoungeState;
use crate::lounge_catalog::{find_title, TITLES};
use serde::Serialize;

/// Cumulative meow-chars required for Paragon star `n`: `20_000 + 1_000 * n * (n + 20)` (checked against overflow).
#[must_use]
pub fn star_req(n: u64) -> Option<u64> {
    n.checked_add(20)?
        .checked_mul(n)?
        .checked_mul(1_000)?
        .checked_add(20_000)
}

/// Bond levels (`0..=100`) plus quadratic Paragon star tier (`★0 → ★∞`) with 10 stardust pips per star (C6).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ParagonInfo {
    pub bond_mimimo: u8,
    pub bond_momo: u8,
    pub max_bond_reached: bool,
    pub paragon_level: u32,
    pub stardust_pips: u8,
    pub next_star_pct: u8,
    pub next_star_need: u64,
}

/// Pure O(log n) quadratic Paragon derivation from `view_meow_chars` only (zero achievement inflation, zero alloc).
#[must_use]
pub fn paragon_of(view_meow_chars: u64, bond_mimimo: u8, bond_momo: u8) -> ParagonInfo {
    let (m, o) = (bond_mimimo.min(100), bond_momo.min(100));
    if m < 100 || o < 100 {
        let pct = ((u32::from(m) + u32::from(o)) * 100 / 200) as u8;
        return ParagonInfo {
            bond_mimimo: m,
            bond_momo: o,
            max_bond_reached: false,
            paragon_level: 0,
            stardust_pips: (pct / 10).min(9),
            next_star_pct: pct,
            next_star_need: 20_000,
        };
    }
    let (mut lo, mut hi) = (0u64, 135_000_000u64);
    while lo < hi {
        let mid = lo + (hi - lo).div_ceil(2);
        if star_req(mid).is_some_and(|req| req <= view_meow_chars) {
            lo = mid;
        } else {
            hi = mid - 1;
        }
    }
    let cur_req = star_req(lo).unwrap_or(u64::MAX);
    let next_req = star_req(lo.saturating_add(1));
    let (step, rem) = match next_req {
        Some(nr) => (nr.saturating_sub(cur_req).max(1), view_meow_chars.saturating_sub(cur_req)),
        None => (1, 0),
    };
    let pct = ((rem.saturating_mul(100)) / step).min(99) as u8;
    let pips = ((rem.saturating_mul(10)) / step).min(9) as u8;
    ParagonInfo {
        bond_mimimo: m,
        bond_momo: o,
        max_bond_reached: true,
        paragon_level: u32::try_from(lo).unwrap_or(u32::MAX),
        stardust_pips: pips,
        next_star_pct: pct,
        next_star_need: step,
    }
}

/// Computes which of the 10 titles are currently unlocked.
#[must_use]
pub fn unlocked_titles(
    lounge: &LoungeState,
    meow_chars: u64,
    places_mask: u16,
    unlocked_achievements: &[String],
    paragon_level: u32,
) -> Vec<&'static str> {
    let has_ach = |api: &str| unlocked_achievements.iter().any(|u| u == api);
    TITLES
        .iter()
        .filter(|t| match t.id {
            "kibble_taster" => true,
            "balance_master" => (lounge.patted_mimimo_local >= 5 && lounge.patted_momo_local >= 5) || has_ach("PET_BOTH_100"),
            "cap_tycoon" => lounge.curios.len() >= 6,
            "cardboard_lord" => lounge.toy_plays.get("cardboard_box").copied().unwrap_or(0) >= 3 || has_ach("CAT_FOUND"),
            "corn_alchemist" => lounge.expedition_count >= 5 || meow_chars >= 50_000,
            "focus_sage" => lounge.focus_completed >= 1,
            "lunar_gagagu" => (places_mask & (1u16 << 11)) != 0 || lounge.curios.iter().any(|c| c == "curio_lunar"),
            "market_collector" => lounge.curios.len() >= 18,
            "word_sea_god" => meow_chars >= 1_000_000,
            "ultimate_damidao" => paragon_level >= 25 || unlocked_achievements.len() >= 70 || has_ach("MASTER_MEOWPUUT"),
            _ => false,
        })
        .map(|t| t.id)
        .collect()
}

/// Equips an unlocked title (or empty string to clear).
pub fn equip_title(lounge: &mut LoungeState, title_id: &str, unlocked: &[&str]) -> Result<(), String> {
    if title_id.is_empty() {
        lounge.equipped_title.clear();
        return Ok(());
    }
    if find_title(title_id).is_none() {
        return Err(format!("unknown title: {title_id}"));
    }
    if !unlocked.contains(&title_id) {
        return Err(format!("title not unlocked yet: {title_id}"));
    }
    lounge.equipped_title = title_id.to_owned();
    Ok(())
}
