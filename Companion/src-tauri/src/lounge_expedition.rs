#![forbid(unsafe_code)]
//! Clockless 2-tier cat expeditions (C3) and monotonic-clock zero-pressure focus theater (C2).
//!
//! No instant kibble gacha exists here: expeditions consume only accumulated meow-chars (with cozy-blanket 2x
//! boost after breaks, even multi-slot split, weighted rarity `[45, 30, 18, 7]`, 30% landmark exclusive lock,
//! and hard `PITY_MAX = 10` proving all 24 curios complete within `<= 264` rolls).
//!
//! ```
//! use meowpuut_companion_lib::lounge::LoungeState;
//! use meowpuut_companion_lib::lounge_expedition::{
//!     advance_expeditions, complete_focus_at, dispatch_expedition, focus_start_at,
//!     ExpeditionTier, PITY_MAX,
//! };
//!
//! let mut lounge = LoungeState::default();
//! assert!(complete_focus_at(&mut lounge, 600_000).is_err());
//! focus_start_at(&mut lounge, 1_000);
//! let focus = complete_focus_at(&mut lounge, 1_000 + 25 * 60_000).unwrap();
//! assert_eq!((focus.minutes, focus.snack_vouchers), (25, 1));
//! assert_eq!(PITY_MAX, 10);
//! dispatch_expedition(&mut lounge, 0, "street", ExpeditionTier::Onboarding, 0, 0, 0, 0).unwrap();
//! let res = advance_expeditions(&mut lounge, 300, 0);
//! assert_eq!(res.len(), 1);
//! assert_eq!(res[0].curios[0].id, "curio_sea");
//! ```

use crate::lounge::{next_rand, LoungeState};
use crate::lounge_catalog::{find_place, CurioDef, CURIOS};
use serde::{Deserialize, Serialize};

pub const EXP_ONBOARD_CHARS: u64 = 300;
pub const EXP_ONBOARD_PATS: u32 = 10;
pub const STROLL_CHARS: u64 = 2_000;
pub const STROLL_ROLLS: u8 = 1;
pub const STROLL_KIBBLE: u64 = 10;
pub const ADVENTURE_CHARS: u64 = 15_000;
pub const ADVENTURE_ROLLS: u8 = 10;
pub const ADVENTURE_KIBBLE: u64 = 150;
pub const RESTED_BONUS_MAX: u64 = 2_000;
pub const MAX_EXPEDITION_SLOTS: usize = 3;

pub const CURIO_P_PERMILLE: u32 = 300;
pub const EXCL_P_PERMILLE: u32 = 300;
pub const PITY_MAX: u8 = 10;
pub const RARITY_W: [u32; 4] = [45, 30, 18, 7];

/// Expedition tier: onboarding stroll (once), regular stroll (2k/1 roll/10🍪), or grand adventure (15k/10 rolls/150🍪).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ExpeditionTier {
    Onboarding,
    Stroll,
    Adventure,
}

impl ExpeditionTier {
    #[must_use]
    pub const fn required_chars(self) -> u64 {
        match self {
            Self::Onboarding => EXP_ONBOARD_CHARS,
            Self::Stroll => STROLL_CHARS,
            Self::Adventure => ADVENTURE_CHARS,
        }
    }

    #[must_use]
    pub const fn rolls(self) -> u8 {
        match self {
            Self::Onboarding | Self::Stroll => STROLL_ROLLS,
            Self::Adventure => ADVENTURE_ROLLS,
        }
    }

    #[must_use]
    pub const fn reward_kibble(self) -> u64 {
        match self {
            Self::Onboarding | Self::Stroll => STROLL_KIBBLE,
            Self::Adventure => ADVENTURE_KIBBLE,
        }
    }
}

/// One active clockless paper-crane expedition slot.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExpeditionSlot {
    pub place_id: String,
    pub place_bit: u8,
    pub tier: ExpeditionTier,
    pub walked_chars: u64,
    pub need_chars: u64,
}

/// Completed expedition report returned to the UI.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExpeditionOutcome {
    pub slot: usize,
    pub place_id: &'static str,
    pub place_name: [&'static str; 2],
    pub tier: ExpeditionTier,
    pub curios: Vec<CurioDef>,
    pub new_count: u32,
    pub pity_triggers: u32,
    pub pity_now: u8,
    pub all_collected: bool,
    pub reward_kibble: u64,
    pub kibble_balance: u64,
    pub dialogue: [&'static str; 2],
}

/// How many expedition slots are currently unlocked (`1` base, `2` at any cat Lv.50, `3` at Paragon `★5`).
#[must_use]
pub const fn unlocked_slot_count(bond_mimimo: u8, bond_momo: u8, paragon_level: u32) -> usize {
    if paragon_level >= 5 {
        3
    } else if bond_mimimo >= 50 || bond_momo >= 50 {
        2
    } else {
        1
    }
}

/// Dispatches a clockless paper-crane expedition into `slot` (`0..unlocked_slot_count`). Cats stay home and on desktop.
#[allow(clippy::too_many_arguments)]
pub fn dispatch_expedition(
    lounge: &mut LoungeState,
    slot: usize,
    place_id: &str,
    tier: ExpeditionTier,
    places_mask: u16,
    bond_mimimo: u8,
    bond_momo: u8,
    paragon_level: u32,
) -> Result<(), String> {
    let cap = unlocked_slot_count(bond_mimimo, bond_momo, paragon_level);
    if slot >= cap || slot >= MAX_EXPEDITION_SLOTS {
        return Err(format!("expedition slot {slot} is locked (unlocked: {cap})"));
    }
    if lounge.expedition_slots[slot].is_some() {
        return Err(format!("expedition slot {slot} is already active"));
    }
    if tier == ExpeditionTier::Onboarding && lounge.onboarding_expedition_done {
        return Err("onboarding expedition already completed".to_owned());
    }
    let Some(place) = find_place(place_id) else {
        return Err(format!("unknown place: {place_id}"));
    };
    let open = tier == ExpeditionTier::Onboarding || place.always_open || (places_mask & (1u16 << place.bit)) != 0;
    if !open {
        return Err(format!("place not reached yet: {place_id}"));
    }
    lounge.expedition_slots[slot] = Some(ExpeditionSlot {
        place_id: place.id.to_owned(),
        place_bit: place.bit,
        tier,
        walked_chars: 0,
        need_chars: tier.required_chars(),
    });
    Ok(())
}

/// Grants cozy-blanket rested boost (next `2,000` meow-chars count 2x toward active expeditions).
pub fn grant_cozy_blanket(lounge: &mut LoungeState) {
    lounge.rested_bonus_chars = RESTED_BONUS_MAX;
}

/// Advances all active expedition slots using `delta_meow_chars` (split evenly across active slots; 2x while cozy blanket holds).
pub fn advance_expeditions(lounge: &mut LoungeState, delta_meow_chars: u64, _places_mask: u16) -> Vec<ExpeditionOutcome> {
    let active_count = lounge.expedition_slots.iter().filter(|s| s.is_some()).count() as u64;
    let total_pats = lounge.patted_mimimo_local.saturating_add(lounge.patted_momo_local);
    if active_count == 0 {
        return Vec::new();
    }
    let boosted = if delta_meow_chars > 0 && lounge.rested_bonus_chars > 0 {
        let bonus = delta_meow_chars.min(lounge.rested_bonus_chars);
        lounge.rested_bonus_chars = lounge.rested_bonus_chars.saturating_sub(bonus);
        delta_meow_chars.saturating_add(bonus)
    } else {
        delta_meow_chars
    };
    let step = boosted / active_count;
    let mut outcomes = Vec::new();
    for slot_idx in 0..MAX_EXPEDITION_SLOTS {
        let ready = if let Some(active) = lounge.expedition_slots[slot_idx].as_mut() {
            active.walked_chars = active.walked_chars.saturating_add(step);
            let pat_done = active.tier == ExpeditionTier::Onboarding && total_pats >= EXP_ONBOARD_PATS;
            active.walked_chars >= active.need_chars || pat_done
        } else {
            false
        };
        if ready {
            if let Some(done) = lounge.expedition_slots[slot_idx].take() {
                outcomes.push(resolve_slot(lounge, slot_idx, &done));
            }
        }
    }
    outcomes
}

const fn rarity_weight(rarity: &str) -> u32 {
    match rarity.as_bytes() {
        b"common" => RARITY_W[0],
        b"uncommon" => RARITY_W[1],
        b"rare" => RARITY_W[2],
        _ => RARITY_W[3],
    }
}

/// Draws one curio roll with zero heap allocation using stack arrays, weighted `[45, 30, 18, 7]`,
/// 30% unowned landmark-exclusive bias, and hard `PITY_MAX = 10` guarantee.
pub fn roll_one_curio(lounge: &mut LoungeState, place_bit: u8) -> (Option<CurioDef>, bool, bool) {
    let (mut pool, mut pool_w, mut pool_len) = ([0usize; 24], [0u32; 24], 0usize);
    let (mut unowned, mut unowned_w, mut unowned_len) = ([0usize; 24], [0u32; 24], 0usize);
    let mut exclusive_unowned: Option<usize> = None;

    for (idx, c) in CURIOS.iter().enumerate() {
        if !(c.place_bit.is_none() || c.place_bit == Some(place_bit)) {
            continue;
        }
        let w = rarity_weight(c.rarity);
        pool[pool_len] = idx;
        pool_w[pool_len] = w;
        pool_len += 1;
        if !lounge.curios.iter().any(|id| id == c.id) {
            unowned[unowned_len] = idx;
            unowned_w[unowned_len] = w;
            unowned_len += 1;
            if c.place_bit == Some(place_bit) {
                exclusive_unowned = Some(idx);
            }
        }
    }

    let r1 = next_rand(&mut lounge.seed);
    let r2 = next_rand(&mut lounge.seed);

    if lounge.expedition_pity >= PITY_MAX && unowned_len > 0 {
        let idx = exclusive_unowned.unwrap_or_else(|| pick_weighted(&unowned[..unowned_len], &unowned_w[..unowned_len], r2));
        lounge.expedition_pity = 0;
        let curio = CURIOS[idx];
        lounge.curios.push(curio.id.to_owned());
        return (Some(curio), true, true);
    }

    let hit_curio = (r1 % 1_000) < u64::from(CURIO_P_PERMILLE);
    if !hit_curio {
        if unowned_len > 0 {
            lounge.expedition_pity = lounge.expedition_pity.saturating_add(1);
        }
        return (None, false, false);
    }

    let chosen_idx = if let Some(ex) = exclusive_unowned.filter(|_| ((r1 / 1_000) % 1_000) < u64::from(EXCL_P_PERMILLE)) {
        ex
    } else {
        pick_weighted(&pool[..pool_len], &pool_w[..pool_len], r2)
    };
    let curio = CURIOS[chosen_idx];
    let is_new = !lounge.curios.iter().any(|id| id == curio.id);
    if is_new {
        lounge.curios.push(curio.id.to_owned());
        lounge.expedition_pity = 0;
    } else if unowned_len > 0 {
        lounge.expedition_pity = lounge.expedition_pity.saturating_add(1);
        lounge.curio_dupes = lounge.curio_dupes.saturating_add(1);
    }
    (Some(curio), is_new, false)
}

fn pick_weighted(items: &[usize], weights: &[u32], roll: u64) -> usize {
    let total: u64 = weights.iter().map(|&w| u64::from(w)).sum();
    if total == 0 {
        return items[0];
    }
    let mut target = roll % total;
    for (&item, &w) in items.iter().zip(weights.iter()) {
        let wu = u64::from(w);
        if target < wu {
            return item;
        }
        target -= wu;
    }
    items[items.len() - 1]
}

fn resolve_slot(lounge: &mut LoungeState, slot: usize, done: &ExpeditionSlot) -> ExpeditionOutcome {
    let place = find_place(&done.place_id).unwrap_or(&crate::lounge_catalog::PLACES[2]);
    let mut curios = Vec::new();
    let (mut new_count, mut pity_triggers) = (0u32, 0u32);

    if done.tier == ExpeditionTier::Onboarding {
        lounge.onboarding_expedition_done = true;
        let shell = CURIOS.iter().copied().find(|c| c.id == "curio_sea").unwrap_or(CURIOS[13]);
        if !lounge.curios.iter().any(|id| id == shell.id) {
            lounge.curios.push(shell.id.to_owned());
            new_count = 1;
        }
        curios.push(shell);
    } else {
        for _ in 0..done.tier.rolls() {
            let (dropped, is_new, pity) = roll_one_curio(lounge, place.bit);
            if let Some(c) = dropped {
                curios.push(c);
            }
            if is_new {
                new_count += 1;
            }
            if pity {
                pity_triggers += 1;
            }
        }
    }

    let reward_kibble = done.tier.reward_kibble();
    lounge.kibble_balance = lounge.kibble_balance.saturating_add(reward_kibble);
    lounge.expedition_count = lounge.expedition_count.saturating_add(1);
    let all_collected = lounge.curios.len() >= CURIOS.len();

    let dialogue = if done.tier == ExpeditionTier::Onboarding {
        ["紙鶴小信差帶回「絕無魚味白貝殼」！米米莫聞了兩秒皺眉 wer，轉頭見甜玉米立刻爆珍奶圓瞳！", "纸鹤小信差带回「绝无鱼味白贝壳」！米米莫闻了两秒皱眉 wer，转头见甜玉米立刻爆珍奶圆瞳！"]
    } else if pity_triggers > 0 {
        ["星光保底觸發！莫莫的紙鶴小信差精準寄回還沒見過的新奇珍，超級咪到！", "星光保底触发！莫莫的纸鹤小信差精准寄回还没见过的新奇珍，超级咪到！"]
    } else if new_count > 0 {
        ["紙鶴信差寄來門口小籃！帶回全新奇珍與脆貓餅乾，兩隻貓都在窗台等你開箱！", "纸鹤信差寄来门口小篮！带回全新奇珍与脆猫饼干，两只猫都在窗台等你开箱！"]
    } else if all_collected {
        ["二十四件奇珍已全部收藏完畢！小信差照樣帶回滿滿貓餅乾與沿途微風。", "二十四件奇珍已全部收藏完毕！小信差照样带回满满猫饼干与沿途微风。"]
    } else {
        ["紙鶴小信差帶著貓餅乾回來啦，也把下一次必出新奇珍的幸運值再推近了一步！", "纸鹤小信差带着猫饼干回来啦，也把下一次必出新奇珍的幸运值再推近了一步！"]
    };

    ExpeditionOutcome {
        slot,
        place_id: place.id,
        place_name: place.name,
        tier: done.tier,
        curios,
        new_count,
        pity_triggers,
        pity_now: lounge.expedition_pity,
        all_collected,
        reward_kibble,
        kibble_balance: lounge.kibble_balance,
        dialogue,
    }
}

/// Result of completing a zero-pressure minimal focus session (C2: Rust monotonic clock, zero extra currency).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FocusOutcome {
    pub minutes: u32,
    pub natural_meow_chars: u64,
    pub snack_vouchers: u32,
    pub focus_completed: u32,
    pub dialogue: [&'static str; 2],
}

/// Starts a zero-pressure focus session using Rust's monotonic clock timestamp (`mono_ms`).
pub fn focus_start_at(lounge: &mut LoungeState, mono_ms: u64) {
    lounge.focus_started_mono_ms = Some(mono_ms);
}

/// Completes a focus session solely from Rust's monotonic elapsed time (rejects calls without `focus_start`,
/// never touches `progress.desk.*`, never mints bonus kibble; awards natural meow-char fuel + 1 zero-currency snack voucher).
pub fn complete_focus_at(lounge: &mut LoungeState, now_mono_ms: u64) -> Result<FocusOutcome, String> {
    let Some(started) = lounge.focus_started_mono_ms.take() else {
        return Err("focus session was not started on the Rust monotonic clock".to_owned());
    };
    let elapsed_ms = now_mono_ms.saturating_sub(started);
    let minutes = u32::try_from((elapsed_ms / 60_000).min(180)).unwrap_or(180);
    let natural_meow_chars = u64::from(minutes).saturating_mul(6);
    if minutes >= 1 {
        lounge.focus_completed = lounge.focus_completed.saturating_add(1);
    }
    lounge.snack_vouchers = lounge.snack_vouchers.saturating_add(1);
    let dialogue = if minutes >= 15 {
        ["這段安靜陪讀超米到！收工蓋上紙本陪讀小票，附贈 1 張零壓力零食反應券。", "这段安静陪读超米到！收工盖上纸本陪读小票，附赠 1 张零压力零食反应券。"]
    } else {
        ["隨時停下來歇會兒也沒關係奧！莫莫遞來 1 張零食反應券，不催你。", "随时停下来歇会儿也没关系奥！莫莫递来 1 张零食反应券，不催你。"]
    };
    Ok(FocusOutcome {
        minutes,
        natural_meow_chars,
        snack_vouchers: lounge.snack_vouchers,
        focus_completed: lounge.focus_completed,
        dialogue,
    })
}
