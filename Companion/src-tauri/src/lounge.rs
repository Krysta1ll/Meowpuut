#![forbid(unsafe_code)]
//! Cat lounge gameplay, monotonic local wallet (C4), furniture ownership shop (C5), quadratic Paragon stars (C6),
//! throttled petting & zero-exploit toys (C1), and unified view snapshot.
//!
//! ```
//! use meowpuut_companion_lib::lounge::{
//!     buy_furniture, claim_starter_furniture, equip_room_slot, interact_toy, paragon_of,
//!     settle_wallet, star_req, LoungeState,
//! };
//!
//! assert_eq!(star_req(0), Some(20_000));
//! assert_eq!(star_req(1), Some(41_000));
//! assert_eq!(star_req(10), Some(320_000));
//! let mut lounge = LoungeState::default();
//! assert!(equip_room_slot(&mut lounge, "left", Some("box_villa")).is_err());
//! claim_starter_furniture(&mut lounge, "kibble_rug").unwrap();
//! settle_wallet(&mut lounge, 1, 20_000);
//! assert_eq!(lounge.kibble_balance, 1_000);
//! buy_furniture(&mut lounge, "box_villa").unwrap();
//! equip_room_slot(&mut lounge, "left", Some("box_villa")).unwrap();
//! settle_wallet(&mut lounge, 1, 5_000); // cloud disabled: balance never goes negative
//! assert_eq!(lounge.kibble_balance, 400);
//! let r = interact_toy(&mut lounge, "cardboard_box", None, 5_000).unwrap();
//! assert_eq!(r.mood, "peek");
//! assert_eq!(paragon_of(145_000, 100, 100).paragon_level, 5);
//! ```

pub use crate::lounge_catalog::{
    achievements_catalog, bond_stories, star_stories, AchievementCatalogItem, StarStoryView,
    StoryView,
};
use crate::lounge_catalog::{
    find_curio, find_furniture, find_title, find_toy, reaction_line, CurioDef, FurnitureDef,
    PlaceDef, TitleDef, ToyDef, CURIOS, FURNITURE, PLACES, ROOM_SLOTS, STARTER_FURNITURE, TITLES,
    TOYS,
};
pub use crate::lounge_expedition::{
    advance_expeditions, complete_focus_at, dispatch_expedition, focus_start_at,
    grant_cozy_blanket, unlocked_slot_count, ExpeditionOutcome, ExpeditionSlot, ExpeditionTier,
    FocusOutcome, MAX_EXPEDITION_SLOTS, PITY_MAX,
};
pub use crate::lounge_paragon::{equip_title, paragon_of, star_req, unlocked_titles, ParagonInfo};
use meowpuut_pets::Pet;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Meow-chars needed to mint 1 kibble cookie (remainder always preserved).
pub const COOKIE_PER_MEOW_CHARS: u64 = 20;
/// Minimum interval between counted pats on the same cat (matches `desk.rs` 2s throttle).
pub const PET_COOLDOWN_MS: i64 = 2_000;
/// Pet kibble token bucket ceiling and refill period (60 max, +1 every 60s).
pub const PET_BUCKET_MAX: u32 = 60;
pub const PET_BUCKET_REFILL_MS: i64 = 60_000;
/// Expedition step credit per counted pat.
pub const PET_STEP_CHARS: u64 = 10;
/// Default deterministic PRNG seed (`"MEOWPUUT"` in ASCII).
pub const DEFAULT_SEED: u64 = 0x4D45_4F57_5055_5554;

/// Persistent local lounge state inside `Companion.json` (never uploaded to Steam Cloud).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LoungeState {
    pub wallet_epoch: u32,
    pub seen_meow_chars: u64,
    pub kibble_remainder: u64,
    pub kibble_balance: u64,
    pub owned_furniture: u32,
    pub starter_gift_claimed: bool,
    pub pet_form: String,
    pub curios: Vec<String>,
    pub curio_dupes: u32,
    pub room_slots: BTreeMap<String, String>,
    pub equipped_title: String,
    pub expedition_count: u32,
    pub expedition_pity: u8,
    pub onboarding_expedition_done: bool,
    pub rested_bonus_chars: u64,
    pub expedition_slots: [Option<ExpeditionSlot>; MAX_EXPEDITION_SLOTS],
    pub focus_completed: u32,
    #[serde(skip)]
    pub focus_started_mono_ms: Option<u64>,
    pub snack_vouchers: u32,
    pub toy_plays: BTreeMap<String, u32>,
    pub patted_mimimo_local: u32,
    pub patted_momo_local: u32,
    #[serde(skip)]
    pub last_pat_ms: [i64; 2],
    pub pet_bucket: u32,
    pub pet_bucket_ms: i64,
    pub seed: u64,
}

impl Default for LoungeState {
    fn default() -> Self {
        Self {
            wallet_epoch: 0,
            seen_meow_chars: 0,
            kibble_remainder: 0,
            kibble_balance: 0,
            owned_furniture: 0,
            starter_gift_claimed: false,
            pet_form: "3d".to_owned(),
            curios: Vec::new(),
            curio_dupes: 0,
            room_slots: BTreeMap::new(),
            equipped_title: "kibble_taster".to_owned(),
            expedition_count: 0,
            expedition_pity: 0,
            onboarding_expedition_done: false,
            rested_bonus_chars: 0,
            expedition_slots: [None, None, None],
            focus_completed: 0,
            focus_started_mono_ms: None,
            snack_vouchers: 0,
            toy_plays: BTreeMap::new(),
            patted_mimimo_local: 0,
            patted_momo_local: 0,
            last_pat_ms: [-PET_COOLDOWN_MS; 2],
            pet_bucket: PET_BUCKET_MAX,
            pet_bucket_ms: 0,
            seed: DEFAULT_SEED,
        }
    }
}

impl LoungeState {
    /// Drops any unknown form, curio, unowned room slot, or invalid title read from disk.
    pub fn sanitize(&mut self) {
        if self.pet_form != "3d" && self.pet_form != "pixel" {
            self.pet_form = "3d".to_owned();
        }
        self.curios.retain(|id| find_curio(id).is_some());
        let owned = self.owned_furniture;
        self.room_slots.retain(|slot, item| {
            ROOM_SLOTS.contains(&slot.as_str())
                && find_furniture(item).is_some_and(|f| f.slot == slot && (owned & (1u32 << f.bit)) != 0)
        });
        if !self.equipped_title.is_empty() && find_title(&self.equipped_title).is_none() {
            self.equipped_title = "kibble_taster".to_owned();
        }
        self.kibble_remainder %= COOKIE_PER_MEOW_CHARS;
        self.pet_bucket = self.pet_bucket.min(PET_BUCKET_MAX);
        if self.seed == 0 {
            self.seed = DEFAULT_SEED;
        }
    }

    #[must_use]
    pub const fn owns_furniture(&self, bit: u8) -> bool {
        bit < 32 && (self.owned_furniture & (1u32 << bit)) != 0
    }
}

/// Deterministic splitmix64 step (zero allocation, cross-platform identical).
#[inline]
pub fn next_rand(seed: &mut u64) -> u64 {
    if *seed == 0 {
        *seed = DEFAULT_SEED;
    }
    *seed = seed.wrapping_add(0x9E37_79B9_7F4A_7C15);
    let mut z = *seed;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

/// Monotonic local wallet settlement (C4): resets on epoch bump; never deducts already-credited cookies
/// if `view_meow_chars` drops (e.g. Steam Cloud toggled off). Returns newly credited meow-char delta.
pub fn settle_wallet(lounge: &mut LoungeState, epoch: u32, view_meow_chars: u64) -> u64 {
    if lounge.wallet_epoch != epoch {
        let keep_form = lounge.pet_form.clone();
        *lounge = LoungeState { wallet_epoch: epoch, pet_form: keep_form, ..LoungeState::default() };
    }
    if view_meow_chars <= lounge.seen_meow_chars {
        return 0;
    }
    let delta = view_meow_chars - lounge.seen_meow_chars;
    lounge.seen_meow_chars = view_meow_chars;
    let total = lounge.kibble_remainder.saturating_add(delta);
    let cookies = total / COOKIE_PER_MEOW_CHARS;
    lounge.kibble_remainder = total % COOKIE_PER_MEOW_CHARS;
    lounge.kibble_balance = lounge.kibble_balance.saturating_add(cookies);
    delta
}

/// Grants the starter free floor furniture (pick 1 of 3) and starter `cap_cobalt` curio once (C5).
pub fn claim_starter_furniture(lounge: &mut LoungeState, item_id: &str) -> Result<(), String> {
    if lounge.starter_gift_claimed {
        return Err("starter furniture gift already claimed".to_owned());
    }
    if !STARTER_FURNITURE.contains(&item_id) {
        return Err(format!("not a starter furniture option: {item_id}"));
    }
    let item = find_furniture(item_id).ok_or_else(|| format!("unknown furniture: {item_id}"))?;
    lounge.owned_furniture |= 1u32 << item.bit;
    lounge.starter_gift_claimed = true;
    lounge.room_slots.insert(item.slot.to_owned(), item.id.to_owned());
    if !lounge.curios.iter().any(|c| c == "cap_cobalt") {
        lounge.curios.push("cap_cobalt".to_owned());
    }
    Ok(())
}

/// Buys one of the 6 P0 shop furniture pieces with local kibble cookies (C5).
pub fn buy_furniture(lounge: &mut LoungeState, item_id: &str) -> Result<u64, String> {
    let item = find_furniture(item_id).ok_or_else(|| format!("unknown furniture: {item_id}"))?;
    if !item.p0_shop {
        return Err(format!("furniture {item_id} is not in the P0 shop"));
    }
    if lounge.owns_furniture(item.bit) {
        return Err(format!("furniture already owned: {item_id}"));
    }
    if lounge.curios.len() < usize::from(item.unlock_curios) {
        return Err(format!("requires {} curios to unlock {item_id}", item.unlock_curios));
    }
    let price = u64::from(item.price);
    if lounge.kibble_balance < price {
        return Err(format!("not enough kibble: have {}, need {price}", lounge.kibble_balance));
    }
    lounge.kibble_balance -= price;
    lounge.owned_furniture |= 1u32 << item.bit;
    Ok(lounge.kibble_balance)
}

/// Switches between 3D clay (`"3d"`) and pixel (`"pixel"`) lounge display forms.
pub fn set_pet_form(lounge: &mut LoungeState, form: &str) -> Result<(), String> {
    if form != "3d" && form != "pixel" {
        return Err(format!("unknown pet form: {form}"));
    }
    lounge.pet_form = form.to_owned();
    Ok(())
}

/// Cat reaction returned after petting or playing with a lounge toy (C1: never mutates `progress.desk.*`).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToyReaction {
    pub toy_id: &'static str,
    pub cat: &'static str,
    pub mood: &'static str,
    pub drama: &'static str,
    pub dialogue: [&'static str; 2],
    pub throttled: bool,
    pub kibble_gained: u64,
    #[serde(skip)]
    pub ledger_pet: Option<Pet>,
    #[serde(skip)]
    pub pat_step_chars: u64,
}

fn refill_pet_bucket(lounge: &mut LoungeState, now_ms: i64) {
    if lounge.pet_bucket_ms <= 0 || now_ms < lounge.pet_bucket_ms {
        lounge.pet_bucket_ms = now_ms;
        return;
    }
    let elapsed = now_ms - lounge.pet_bucket_ms;
    let gained = (elapsed / PET_BUCKET_REFILL_MS) as u32;
    if gained > 0 {
        lounge.pet_bucket = lounge.pet_bucket.saturating_add(gained).min(PET_BUCKET_MAX);
        lounge.pet_bucket_ms += i64::from(gained) * PET_BUCKET_REFILL_MS;
    }
}

/// Plays with one of the 6 toys or pets Mimimo/Momo (C1: zero writes to `progress.desk.*`; 2s per-cat pat cooldown).
pub fn interact_toy(
    lounge: &mut LoungeState,
    toy_id: &str,
    cat: Option<&str>,
    now_ms: i64,
) -> Result<ToyReaction, String> {
    let pick = (next_rand(&mut lounge.seed) as usize) & 1;
    if toy_id == "pat" {
        let (idx, pet, name, mood, line_key) = match cat {
            Some("mimimo") => (0usize, Pet::Mimimo, "mimimo", "purr", "pat_mimimo"),
            Some("momo") => (1usize, Pet::Momo, "momo", "happy", "pat_momo"),
            _ => return Err("pat requires cat \"mimimo\" or \"momo\"".to_owned()),
        };
        let cooled = now_ms.saturating_sub(lounge.last_pat_ms[idx]) >= PET_COOLDOWN_MS;
        let (mut kibble_gained, mut step_chars, mut ledger_pet) = (0u64, 0u64, None);
        if cooled {
            lounge.last_pat_ms[idx] = now_ms;
            if idx == 0 {
                lounge.patted_mimimo_local = lounge.patted_mimimo_local.saturating_add(1);
            } else {
                lounge.patted_momo_local = lounge.patted_momo_local.saturating_add(1);
            }
            ledger_pet = Some(pet);
            refill_pet_bucket(lounge, now_ms);
            if lounge.pet_bucket > 0 {
                lounge.pet_bucket -= 1;
                lounge.kibble_balance = lounge.kibble_balance.saturating_add(1);
                kibble_gained = 1;
                step_chars = PET_STEP_CHARS;
            }
        }
        return Ok(ToyReaction {
            toy_id: "pat",
            cat: name,
            mood,
            drama: if idx == 0 { "DRAMA_CHIN_BOBA" } else { "DRAMA_MOMO_NUZZLE" },
            dialogue: reaction_line(line_key, pick),
            throttled: !cooled,
            kibble_gained,
            ledger_pet,
            pat_step_chars: step_chars,
        });
    }
    let Some(toy) = find_toy(toy_id) else {
        return Err(format!("unknown toy: {toy_id}"));
    };
    *lounge.toy_plays.entry(toy.id.to_owned()).or_insert(0) += 1;
    let (who, mood, drama) = match toy.id {
        "kibble" => ("both", "happy", "DRAMA_KIBBLE_CRUNCH"),
        "sweet_corn" => ("mimimo", "sparkle", "DRAMA_FISH_VS_CORN"),
        "bottle_cap" => ("both", "dilated", "DRAMA_CAP_STASH"),
        "cardboard_box" => ("mimimo", "peek", "DRAMA_CAP_STASH"),
        "laser_dot" => ("both", "dilated", "DRAMA_LASER_CHASE"),
        _ => ("momo", "happy", "DRAMA_WINDOW_HITCHHIKE"),
    };
    Ok(ToyReaction {
        toy_id: toy.id,
        cat: who,
        mood,
        drama,
        dialogue: reaction_line(toy.id, pick),
        throttled: false,
        kibble_gained: 0,
        ledger_pet: None,
        pat_step_chars: 0,
    })
}

/// Equips or clears an OWNED furniture item in one of the 5 room slots (C5).
pub fn equip_room_slot(lounge: &mut LoungeState, slot: &str, item_id: Option<&str>) -> Result<(), String> {
    if !ROOM_SLOTS.contains(&slot) {
        return Err(format!("unknown room slot: {slot}"));
    }
    match item_id.map(str::trim).filter(|s| !s.is_empty()) {
        None => {
            lounge.room_slots.remove(slot);
            Ok(())
        }
        Some(id) => {
            let item = find_furniture(id).ok_or_else(|| format!("unknown furniture: {id}"))?;
            if item.slot != slot {
                return Err(format!("furniture {id} belongs to slot {}, not {slot}", item.slot));
            }
            if !lounge.owns_furniture(item.bit) {
                return Err(format!("furniture not owned yet: {id}"));
            }
            lounge.room_slots.insert(slot.to_owned(), item.id.to_owned());
            Ok(())
        }
    }
}

/// Complete view returned to the frontend lounge page.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoungeView {
    pub state: LoungeState, pub available_kibble: u64, pub total_chars: u64, pub places_mask: u16,
    pub unlocked_slots: usize, pub paragon: ParagonInfo, pub places: &'static [PlaceDef],
    pub curios: &'static [CurioDef], pub furniture: &'static [FurnitureDef], pub titles: &'static [TitleDef],
    pub toys: &'static [ToyDef], pub unlocked_titles: Vec<&'static str>, pub stories: Vec<StoryView>,
    pub star_stories: Vec<StarStoryView>,
}

/// Builds the full [`LoungeView`] snapshot for Tauri commands.
#[must_use]
pub fn build_view(
    lounge: &LoungeState, view_meow_chars: u64, places_mask: u16,
    bond_mimimo: u8, bond_momo: u8, unlocked_achievements: &[String],
) -> LoungeView {
    let paragon = paragon_of(view_meow_chars, bond_mimimo, bond_momo);
    let titles_unlocked = unlocked_titles(lounge, view_meow_chars, places_mask, unlocked_achievements, paragon.paragon_level);
    LoungeView {
        state: lounge.clone(), available_kibble: lounge.kibble_balance, total_chars: view_meow_chars,
        places_mask, unlocked_slots: unlocked_slot_count(bond_mimimo, bond_momo, paragon.paragon_level),
        paragon, places: &PLACES, curios: &CURIOS, furniture: &FURNITURE, titles: &TITLES, toys: &TOYS,
        unlocked_titles: titles_unlocked, stories: bond_stories(bond_mimimo, bond_momo),
        star_stories: star_stories(paragon.paragon_level),
    }
}

#[cfg(test)]
#[path = "lounge_tests.rs"]
mod tests;
