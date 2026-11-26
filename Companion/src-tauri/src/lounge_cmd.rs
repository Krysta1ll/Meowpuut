#![forbid(unsafe_code)]
//! Tauri command handlers for the pure-game cat lounge, writing pad, active minutes, sync inspector, and trophy wall.
//!
//! ```
//! use meowpuut_companion_lib::lounge_cmd::places_mask_of;
//! use meowpuut_pets::codec::PetState;
//! assert_eq!(places_mask_of(&PetState::default(), 0b101, 0), 0b101);
//! ```

use crate::clock;
use crate::companion::CompanionState;
use crate::desk_counters;
use crate::growth::{trail_places_mask, view_meow_chars};
use crate::lounge::{
    self, AchievementCatalogItem, ExpeditionOutcome, ExpeditionTier, FocusOutcome, LoungeView,
    ToyReaction,
};
use crate::mobile_sync;
use crate::pad::{self, PadCommitOutcome};
use crate::sync_inspect::{self, SyncInspection};
use crate::{settle_on_open, AppState};
use meowpuut_pets::achievements::place;
use meowpuut_pets::codec::PetState;
use meowpuut_pets::moments::DayPart;
use meowpuut_pets::sync::SyncEnvelopeV2;
use meowpuut_pets::Pet;
use serde::Serialize;
use tauri::AppHandle;

/// Combines persisted `Progress::places`, live ledger marks, and pure-game companion trail landmarks.
#[must_use]
pub fn places_mask_of(state: &PetState, persisted: u16, active_minutes: u64) -> u16 {
    let mut mask = (persisted | trail_places_mask(active_minutes)) & place::ALL;
    for mark in state.book.marks() {
        if let Some(p) = mark.place_index() {
            if p < place::COUNT {
                mask |= 1u16 << p;
            }
        }
    }
    mask
}

fn current_day_part_bit() -> u8 {
    use chrono::Timelike;
    let h = chrono::Local::now().hour();
    let part = match h {
        5..=8 => DayPart::Dawn,
        9..=16 => DayPart::Day,
        17..=21 => DayPart::Evening,
        _ => DayPart::LateNight,
    };
    part.bit()
}

fn total_active_minutes(state: &AppState, c: &CompanionState, epoch: u32) -> u64 {
    let desk = desk_counters::read(&state.data.path);
    let desk_mins = if desk.epoch == 0 || desk.epoch == epoch { desk.active_minutes } else { 0 };
    let local_mins = if c.growth.epoch == epoch { c.growth.active_minutes } else { 0 };
    c.progress.desk.active_minutes.max(desk_mins).max(local_mins)
}

fn snapshot_view(state: &AppState, c: &CompanionState) -> Result<LoungeView, String> {
    let reading = state.view_reading().map_err(|e| e.to_string())?;
    let active_mins = total_active_minutes(state, c, reading.state.epoch);
    let meow_chars = view_meow_chars(reading.state.snapshot.committed_graphemes, active_mins);
    let places = places_mask_of(&reading.state, c.progress.places, active_mins);
    let bond_mimimo = reading.state.snapshot.progress(Pet::Mimimo).level;
    let bond_momo = reading.state.snapshot.progress(Pet::Momo).level;
    Ok(lounge::build_view(
        &c.lounge,
        meow_chars,
        places,
        bond_mimimo,
        bond_momo,
        &c.progress.unlocked,
    ))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InteractResponse {
    pub reaction: ToyReaction,
    pub completed: Vec<ExpeditionOutcome>,
    pub view: LoungeView,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettleLoungeResponse {
    pub completed: Vec<ExpeditionOutcome>,
    pub view: LoungeView,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FocusResponse {
    pub outcome: FocusOutcome,
    pub view: LoungeView,
}

#[tauri::command]
pub fn lounge_view(state: tauri::State<'_, AppState>) -> Result<LoungeView, String> {
    let reading = state.view_reading().map_err(|e| e.to_string())?;
    let updated = state
        .companion
        .modify(|c| {
            let active_mins = total_active_minutes(&state, c, reading.state.epoch);
            let meow_chars = view_meow_chars(reading.state.snapshot.committed_graphemes, active_mins);
            let delta = lounge::settle_wallet(&mut c.lounge, reading.state.epoch, meow_chars);
            let places = places_mask_of(&reading.state, c.progress.places, active_mins);
            let _ = lounge::advance_expeditions(&mut c.lounge, delta, places);
        })
        .map_err(|e| e.to_string())?;
    snapshot_view(&state, &updated)
}

#[tauri::command]
pub fn lounge_set_form(state: tauri::State<'_, AppState>, form: String) -> Result<LoungeView, String> {
    let mut err = None;
    let updated = state
        .companion
        .modify(|c| {
            if let Err(e) = lounge::set_pet_form(&mut c.lounge, &form) {
                err = Some(e);
            }
        })
        .map_err(|e| e.to_string())?;
    if let Some(e) = err {
        return Err(e);
    }
    snapshot_view(&state, &updated)
}

#[tauri::command]
pub fn lounge_interact(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    toy_id: String,
    cat: Option<String>,
) -> Result<InteractResponse, String> {
    let reading = state.view_reading().map_err(|e| e.to_string())?;
    let now_ms = clock::now().wall_ms;
    let mut outcome: Result<ToyReaction, String> = Err("not run".to_owned());
    let mut completed = Vec::new();
    let updated = state
        .companion
        .modify(|c| {
            let active_mins = total_active_minutes(&state, c, reading.state.epoch);
            let places = places_mask_of(&reading.state, c.progress.places, active_mins);
            outcome = lounge::interact_toy(&mut c.lounge, &toy_id, cat.as_deref(), now_ms);
            if let Ok(ref react) = outcome {
                if react.pat_step_chars > 0 {
                    completed = lounge::advance_expeditions(&mut c.lounge, react.pat_step_chars, places);
                }
            }
        })
        .map_err(|e| e.to_string())?;
    let reaction = outcome?;
    if let Some(pet) = reaction.ledger_pet {
        let _ = pad::record_pet_to_ledger(&state.ledger, pet, clock::now().day, current_day_part_bit());
        settle_on_open(app);
    }
    Ok(InteractResponse { reaction, completed, view: snapshot_view(&state, &updated)? })
}

#[tauri::command]
pub fn lounge_claim_starter(state: tauri::State<'_, AppState>, item_id: String) -> Result<LoungeView, String> {
    let mut err = None;
    let updated = state
        .companion
        .modify(|c| {
            if let Err(e) = lounge::claim_starter_furniture(&mut c.lounge, &item_id) {
                err = Some(e);
            }
        })
        .map_err(|e| e.to_string())?;
    if let Some(e) = err {
        return Err(e);
    }
    snapshot_view(&state, &updated)
}

#[tauri::command]
pub fn lounge_buy_furniture(state: tauri::State<'_, AppState>, item_id: String) -> Result<LoungeView, String> {
    let mut err = None;
    let updated = state
        .companion
        .modify(|c| {
            if let Err(e) = lounge::buy_furniture(&mut c.lounge, &item_id) {
                err = Some(e);
            }
        })
        .map_err(|e| e.to_string())?;
    if let Some(e) = err {
        return Err(e);
    }
    snapshot_view(&state, &updated)
}

#[tauri::command]
pub fn lounge_dispatch_expedition(
    state: tauri::State<'_, AppState>,
    slot: usize,
    place_id: String,
    tier: ExpeditionTier,
) -> Result<SettleLoungeResponse, String> {
    let reading = state.view_reading().map_err(|e| e.to_string())?;
    let bond_m = reading.state.snapshot.progress(Pet::Mimimo).level;
    let bond_o = reading.state.snapshot.progress(Pet::Momo).level;
    let mut err = None;
    let mut completed = Vec::new();
    let updated = state
        .companion
        .modify(|c| {
            let active_mins = total_active_minutes(&state, c, reading.state.epoch);
            let meow_chars = view_meow_chars(reading.state.snapshot.committed_graphemes, active_mins);
            let places = places_mask_of(&reading.state, c.progress.places, active_mins);
            let paragon = lounge::paragon_of(meow_chars, bond_m, bond_o).paragon_level;
            if let Err(e) = lounge::dispatch_expedition(&mut c.lounge, slot, &place_id, tier, places, bond_m, bond_o, paragon) {
                err = Some(e);
            } else {
                completed = lounge::advance_expeditions(&mut c.lounge, 0, places);
            }
        })
        .map_err(|e| e.to_string())?;
    if let Some(e) = err {
        return Err(e);
    }
    Ok(SettleLoungeResponse { completed, view: snapshot_view(&state, &updated)? })
}

#[tauri::command]
pub fn lounge_equip_room(
    state: tauri::State<'_, AppState>,
    slot: String,
    item_id: Option<String>,
) -> Result<LoungeView, String> {
    let mut err = None;
    let updated = state
        .companion
        .modify(|c| {
            if let Err(e) = lounge::equip_room_slot(&mut c.lounge, &slot, item_id.as_deref()) {
                err = Some(e);
            }
        })
        .map_err(|e| e.to_string())?;
    if let Some(e) = err {
        return Err(e);
    }
    snapshot_view(&state, &updated)
}

#[tauri::command]
pub fn lounge_equip_title(state: tauri::State<'_, AppState>, title_id: String) -> Result<LoungeView, String> {
    let reading = state.view_reading().map_err(|e| e.to_string())?;
    let bond_m = reading.state.snapshot.progress(Pet::Mimimo).level;
    let bond_o = reading.state.snapshot.progress(Pet::Momo).level;
    let mut err = None;
    let updated = state
        .companion
        .modify(|c| {
            let active_mins = total_active_minutes(&state, c, reading.state.epoch);
            let meow_chars = view_meow_chars(reading.state.snapshot.committed_graphemes, active_mins);
            let places = places_mask_of(&reading.state, c.progress.places, active_mins);
            let paragon = lounge::paragon_of(meow_chars, bond_m, bond_o);
            let unlocked = lounge::unlocked_titles(&c.lounge, meow_chars, places, &c.progress.unlocked, paragon.paragon_level);
            if let Err(e) = lounge::equip_title(&mut c.lounge, &title_id, &unlocked) {
                err = Some(e);
            }
        })
        .map_err(|e| e.to_string())?;
    if let Some(e) = err {
        return Err(e);
    }
    snapshot_view(&state, &updated)
}

#[tauri::command]
pub fn lounge_focus_start(state: tauri::State<'_, AppState>) -> Result<u64, String> {
    let mono_ms = state.mono_ms();
    *state.focus_started_ms() = Some(mono_ms);
    Ok(mono_ms)
}

#[tauri::command]
pub fn lounge_complete_focus(app: AppHandle, state: tauri::State<'_, AppState>) -> Result<FocusResponse, String> {
    let started = state.focus_started_ms().take();
    let now_mono_ms = state.mono_ms();
    let mut outcome: Result<FocusOutcome, String> = Err("not run".to_owned());
    let updated = state
        .companion
        .modify(|c| {
            c.lounge.focus_started_mono_ms = started;
            outcome = lounge::complete_focus_at(&mut c.lounge, now_mono_ms);
        })
        .map_err(|e| e.to_string())?;
    let outcome = outcome?;
    settle_on_open(app);
    Ok(FocusResponse { outcome, view: snapshot_view(&state, &updated)? })
}

#[tauri::command]
pub fn pad_commit(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    graphemes: u32,
    intervals_ms: Vec<u32>,
    unique_chars: u32,
) -> Result<PadCommitOutcome, String> {
    let now_mono = state.mono_ms();
    let day = clock::now().day;
    let mut out = state
        .pad_guard()
        .check_and_accept(graphemes, &intervals_ms, unique_chars, now_mono, day)?;
    if out.accepted > 0 {
        let updated_ledger = pad::commit_graphemes_to_ledger(&state.ledger, out.accepted, day, current_day_part_bit())
            .map_err(|e| e.to_string())?;
        out.total_chars = updated_ledger.snapshot.committed_graphemes;
        let _ = state.companion.modify(|c| {
            let active_mins = total_active_minutes(&state, c, updated_ledger.epoch);
            let meow_chars = view_meow_chars(updated_ledger.snapshot.committed_graphemes, active_mins);
            let delta = lounge::settle_wallet(&mut c.lounge, updated_ledger.epoch, meow_chars);
            let places = places_mask_of(&updated_ledger, c.progress.places, active_mins);
            let _ = lounge::advance_expeditions(&mut c.lounge, delta, places);
        });
        settle_on_open(app);
    }
    Ok(out)
}

#[tauri::command]
pub fn record_active_minute(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    idle_seconds: u32,
) -> Result<LoungeView, String> {
    let reading = state.view_reading().map_err(|e| e.to_string())?;
    let day = clock::now().day;
    let mut credited = false;
    let updated = state
        .companion
        .modify(|c| {
            credited = c.growth.record_active_minute(reading.state.epoch, day, idle_seconds);
            if credited {
                let active_mins = total_active_minutes(&state, c, reading.state.epoch);
                let meow_chars = view_meow_chars(reading.state.snapshot.committed_graphemes, active_mins);
                let delta = lounge::settle_wallet(&mut c.lounge, reading.state.epoch, meow_chars);
                let places = places_mask_of(&reading.state, c.progress.places, active_mins);
                let _ = lounge::advance_expeditions(&mut c.lounge, delta, places);
            }
        })
        .map_err(|e| e.to_string())?;
    if credited {
        settle_on_open(app);
    }
    snapshot_view(&state, &updated)
}

#[tauri::command]
pub fn inspect_sync_envelope(state: tauri::State<'_, AppState>) -> Result<SyncInspection, String> {
    let reading = state.view_reading().map_err(|e| e.to_string())?;
    let companion = state.companion.read().map_err(|e| e.to_string())?;
    let desk = desk_counters::read(&state.data.path);
    let base = mobile_sync::build_envelope(&reading.state, &companion.progress, &desk, &companion.sync_code);
    Ok(sync_inspect::inspect_envelope(&SyncEnvelopeV2::from_v1(base)))
}

#[tauri::command]
pub fn achievements_catalog(state: tauri::State<'_, AppState>) -> Vec<AchievementCatalogItem> {
    let unlocked = state.companion.read().map(|c| c.progress.unlocked).unwrap_or_default();
    lounge::achievements_catalog(&unlocked)
}
