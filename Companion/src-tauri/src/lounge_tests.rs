#![forbid(unsafe_code)]
//! Comprehensive C1-C6 regression tests, 100-seed expedition simulation, and catalog parity tests for [`crate::lounge`].

use super::*;
use crate::lounge_catalog::{CURIOS, FURNITURE, PLACES, STAR_STORIES, STORIES, TITLES, TOYS};
use crate::lounge_expedition::{
    advance_expeditions, complete_focus_at, dispatch_expedition, focus_start_at,
    grant_cozy_blanket, roll_one_curio, ExpeditionTier, PITY_MAX,
};
use meowpuut_pets::achievements::TABLE;

#[test]
fn c1_toys_never_touch_desk_and_pats_enforce_2s_cooldown() {
    let mut lounge = LoungeState::default();
    for i in 0..1_000 {
        let toy = TOYS[i % TOYS.len()].id;
        let r = interact_toy(&mut lounge, toy, None, (i as i64) * 50).unwrap();
        assert!(r.ledger_pet.is_none());
        assert_eq!(r.kibble_gained, 0);
    }
    assert_eq!(lounge.toy_plays.values().copied().sum::<u32>(), 1_000);
    assert_eq!(lounge.kibble_balance, 0);

    // Per-cat 2s cooldown for pats
    let p1 = interact_toy(&mut lounge, "pat", Some("mimimo"), 5_000).unwrap();
    assert!(!p1.throttled && p1.ledger_pet == Some(Pet::Mimimo) && p1.kibble_gained == 1);
    let p2 = interact_toy(&mut lounge, "pat", Some("mimimo"), 6_500).unwrap();
    assert!(p2.throttled && p2.ledger_pet.is_none() && p2.kibble_gained == 0);
    // Momo has independent cooldown
    let p3 = interact_toy(&mut lounge, "pat", Some("momo"), 6_500).unwrap();
    assert!(!p3.throttled && p3.ledger_pet == Some(Pet::Momo) && p3.kibble_gained == 1);
    // After 2,000ms Mimimo accepts again
    let p4 = interact_toy(&mut lounge, "pat", Some("mimimo"), 7_000).unwrap();
    assert!(!p4.throttled && p4.ledger_pet == Some(Pet::Mimimo));
    assert_eq!((lounge.patted_mimimo_local, lounge.patted_momo_local), (2, 1));
    assert!(interact_toy(&mut lounge, "pat", None, 9_000).is_err());
    assert!(interact_toy(&mut lounge, "bogus", None, 9_000).is_err());
}

#[test]
fn c2_focus_rejects_forged_unstarted_and_uses_rust_monotonic_clock() {
    let mut lounge = LoungeState::default();
    assert!(complete_focus_at(&mut lounge, 180 * 60_000).is_err());
    focus_start_at(&mut lounge, 10_000);
    let out = complete_focus_at(&mut lounge, 10_000 + 30 * 60_000).unwrap();
    assert_eq!((out.minutes, out.natural_meow_chars, out.snack_vouchers, out.focus_completed), (30, 180, 1, 1));
    assert_eq!(lounge.kibble_balance, 0, "focus never mints bonus currency");
    // Cannot double-complete without starting again
    assert!(complete_focus_at(&mut lounge, 10_000 + 60 * 60_000).is_err());
    // Early stop (<1 min) is zero-penalty and gives snack voucher
    focus_start_at(&mut lounge, 100_000);
    let early = complete_focus_at(&mut lounge, 120_000).unwrap();
    assert_eq!((early.minutes, early.snack_vouchers), (0, 2));
}

#[test]
fn c3_clockless_two_tier_expedition_cozy_blanket_split_and_100_seed_pity_bound() {
    // Onboarding stroll: 300 chars or 10 pats -> guaranteed curio_sea + 10 cookies
    let mut lounge = LoungeState::default();
    dispatch_expedition(&mut lounge, 0, "street", ExpeditionTier::Onboarding, 0, 0, 0, 0).unwrap();
    assert!(advance_expeditions(&mut lounge, 299, 0).is_empty());
    let done = advance_expeditions(&mut lounge, 1, 0);
    assert_eq!(done.len(), 1);
    assert_eq!(done[0].curios[0].id, "curio_sea");
    assert_eq!(done[0].reward_kibble, 10);
    assert!(lounge.onboarding_expedition_done);
    assert!(dispatch_expedition(&mut lounge, 0, "street", ExpeditionTier::Onboarding, 0, 0, 0, 0).is_err());

    // Multi-slot split & cozy blanket 2x
    assert_eq!(unlocked_slot_count(49, 49, 0), 1);
    assert_eq!(unlocked_slot_count(50, 10, 0), 2);
    assert_eq!(unlocked_slot_count(100, 100, 5), 3);
    grant_cozy_blanket(&mut lounge);
    dispatch_expedition(&mut lounge, 0, "street", ExpeditionTier::Stroll, 0x0FFF, 100, 100, 5).unwrap();
    dispatch_expedition(&mut lounge, 1, "fuji", ExpeditionTier::Adventure, 0x0FFF, 100, 100, 5).unwrap();
    // 2,000 chars boosted 2x -> 4,000 total -> split across 2 slots = 2,000 each -> Stroll completes, Adventure at 2,000/15,000
    let split_out = advance_expeditions(&mut lounge, 2_000, 0x0FFF);
    assert_eq!(split_out.len(), 1);
    assert_eq!(split_out[0].tier, ExpeditionTier::Stroll);
    assert_eq!(lounge.expedition_slots[1].as_ref().unwrap().walked_chars, 2_000);
    assert_eq!(lounge.rested_bonus_chars, 0);

    // 100-seed full 24-curio collection <= 264 rolls and bit-exact deterministic
    let max_possible_rolls = 24 * (u32::from(PITY_MAX) + 1);
    assert_eq!(max_possible_rolls, 264);
    for seed in 1u64..=100 {
        let mut a = LoungeState { seed, ..LoungeState::default() };
        let mut b = LoungeState { seed, ..LoungeState::default() };
        let mut rolls = 0u32;
        while a.curios.len() < CURIOS.len() {
            let missing = CURIOS.iter().find(|c| !a.curios.iter().any(|id| id == c.id)).unwrap();
            let place_bit = missing.place_bit.unwrap_or(2);
            let ra = roll_one_curio(&mut a, place_bit);
            let rb = roll_one_curio(&mut b, place_bit);
            assert_eq!(ra, rb, "bit-exact mismatch seed {seed} roll {rolls}");
            rolls += 1;
            assert!(rolls <= max_possible_rolls, "seed {seed} exceeded 264 rolls");
        }
        assert_eq!(a, b);
    }
}

#[test]
fn c4_monotonic_wallet_never_goes_into_debt_on_cloud_toggle_or_epoch_reset() {
    let mut lounge = LoungeState::default();
    assert_eq!(settle_wallet(&mut lounge, 1, 500_000), 500_000);
    assert_eq!(lounge.kibble_balance, 25_000);
    buy_furniture(&mut lounge, "box_villa").unwrap(); // -600
    buy_furniture(&mut lounge, "crt_tv").unwrap(); // -1400
    assert_eq!(lounge.kibble_balance, 23_000);
    // Cloud turned off: view drops from 500k -> 300k. Balance MUST stay 23,000, zero debt!
    assert_eq!(settle_wallet(&mut lounge, 1, 300_000), 0);
    assert_eq!(lounge.kibble_balance, 23_000);
    // Remainder preservation
    let mut rem = LoungeState { wallet_epoch: 1, ..LoungeState::default() };
    settle_wallet(&mut rem, 1, 15);
    assert_eq!((rem.kibble_balance, rem.kibble_remainder), (0, 15));
    settle_wallet(&mut rem, 1, 25);
    assert_eq!((rem.kibble_balance, rem.kibble_remainder), (1, 5));
    // Epoch increment resets wallet cleanly
    settle_wallet(&mut lounge, 2, 100);
    assert_eq!((lounge.wallet_epoch, lounge.kibble_balance, lounge.owned_furniture), (2, 5, 0));
}

#[test]
fn c5_furniture_ownership_starter_gift_and_p0_six_shop_items() {
    let mut lounge = LoungeState::default();
    assert_eq!(lounge.owned_furniture, 0);
    assert!(lounge.room_slots.is_empty());
    assert!(equip_room_slot(&mut lounge, "left", Some("box_villa")).is_err());

    // Claim starter gift once
    assert!(claim_starter_furniture(&mut lounge, "box_villa").is_err());
    claim_starter_furniture(&mut lounge, "kibble_rug").unwrap();
    assert!(claim_starter_furniture(&mut lounge, "corn_pot").is_err());
    assert!(lounge.curios.iter().any(|c| c == "cap_cobalt"));
    assert_eq!(lounge.room_slots.get("floor").map(String::as_str), Some("kibble_rug"));

    // Verify exactly 6 P0 shop items and cap_tower curio gate
    let p0_items: Vec<&str> = FURNITURE.iter().filter(|f| f.p0_shop).map(|f| f.id).collect();
    assert_eq!(p0_items, vec!["box_villa", "faucet_fountain", "corn_pot", "kibble_rug", "crt_tv", "cap_tower"]);
    settle_wallet(&mut lounge, 0, 200_000); // 10,000 cookies
    assert!(buy_furniture(&mut lounge, "cap_tower").is_err(), "needs 6 curios");
    for c in CURIOS.iter().take(6) {
        if !lounge.curios.iter().any(|id| id == c.id) {
            lounge.curios.push(c.id.to_owned());
        }
    }
    buy_furniture(&mut lounge, "cap_tower").unwrap();
    equip_room_slot(&mut lounge, "right", Some("cap_tower")).unwrap();
    assert!(buy_furniture(&mut lounge, "win_snow_peak").is_err(), "P1 item blocked in P0 shop");
}

#[test]
fn c6_quadratic_paragon_pure_from_meow_chars_and_five_star_stories() {
    assert_eq!(star_req(0), Some(20_000));
    assert_eq!(star_req(1), Some(41_000));
    assert_eq!(star_req(5), Some(145_000));
    assert_eq!(star_req(10), Some(320_000));
    assert_eq!(star_req(25), Some(1_145_000));
    assert_eq!(star_req(50), Some(3_520_000));
    assert_eq!(star_req(100), Some(12_020_000));
    assert_eq!(star_req(u64::MAX), None);

    assert_eq!(paragon_of(40_999, 100, 100).paragon_level, 0);
    assert_eq!(paragon_of(41_000, 100, 100).paragon_level, 1);
    assert_eq!(paragon_of(145_000, 100, 100).paragon_level, 5);
    assert_eq!(paragon_of(1_145_000, 100, 100).paragon_level, 25);
    assert_eq!(paragon_of(3_520_000, 100, 100).paragon_level, 50);
    assert!(paragon_of(u64::MAX, 100, 100).paragon_level > 100_000);

    let ss = star_stories(10);
    assert_eq!(ss.len(), 5);
    assert_eq!(ss.iter().filter(|s| s.unlocked).count(), 3);
    for s in STAR_STORIES {
        for txt in [s.title[0], s.title[1], s.body[0], s.body[1]] {
            assert!(!txt.contains('字'), "star story {} must never mention word counts: {txt}", s.id);
        }
    }
}

#[test]
fn catalog_landmarks_match_achievements_and_pass_compliance() {
    assert_eq!(PLACES[6].name, ["富士", "富士"]);
    assert_eq!(PLACES[7].name, ["香港", "香港"]);
    let place_apis = [
        "PLACE_FIELD", "PLACE_SEA", "PLACE_STREET", "PLACE_BAMBOO",
        "PLACE_SNOW", "PLACE_MOON", "PLACE_FUJI", "PLACE_HONG_KONG",
        "PLACE_VOLCANO", "PLACE_DESERT", "PLACE_MAPLE", "PLACE_LUNAR",
    ];
    for (idx, api) in place_apis.iter().enumerate() {
        let ach = TABLE.iter().find(|d| d.api == *api).unwrap();
        let pname = PLACES[idx].name[0];
        assert!(ach.zh_hant.contains(pname), "PLACES[{idx}] ({pname}) must match {api} ({})", ach.zh_hant);
    }
    const FORBIDDEN: &[&str] = &[
        "擺擺布", "摆摆布", "手買", "手买", "肚買", "肚买", "手咪", "肚咪",
        "連續", "连续", "每天", "限時", "限时", "打卡", "零作弊", "買買木", "买买木", "坑坑",
    ];
    let all_texts = PLACES.iter().flat_map(|p| [p.name[0], p.name[1], p.vibe[0], p.vibe[1]])
        .chain(CURIOS.iter().flat_map(|c| [c.name[0], c.name[1], c.flavor[0], c.flavor[1]]))
        .chain(FURNITURE.iter().flat_map(|f| [f.name[0], f.name[1], f.desc[0], f.desc[1]]))
        .chain(TITLES.iter().flat_map(|t| [t.name[0], t.name[1], t.hint[0], t.hint[1]]))
        .chain(TOYS.iter().flat_map(|t| [t.name[0], t.name[1], t.desc[0], t.desc[1]]))
        .chain(STORIES.iter().flat_map(|s| [s.title[0], s.title[1], s.body[0], s.body[1]]))
        .chain(STAR_STORIES.iter().flat_map(|s| [s.title[0], s.title[1], s.body[0], s.body[1]]));
    for text in all_texts {
        for bad in FORBIDDEN {
            assert!(!text.contains(bad), "catalog contains forbidden word {bad}: {text}");
        }
    }

    // Titles calibrated: word_sea_god needs 1M meow_chars; ultimate_damidao needs ★25 or 70 achievements
    let lounge = LoungeState::default();
    let forty_ach: Vec<String> = (0..40).map(|i| format!("A_{i}")).collect();
    assert!(!unlocked_titles(&lounge, 100_000, 0, &forty_ach, 5).contains(&"word_sea_god"));
    assert!(unlocked_titles(&lounge, 1_000_000, 0, &[], 5).contains(&"word_sea_god"));
    assert!(!unlocked_titles(&lounge, 200_000, 0, &forty_ach, 5).contains(&"ultimate_damidao"));
    assert!(unlocked_titles(&lounge, 200_000, 0, &forty_ach, 25).contains(&"ultimate_damidao"));

    let cat = achievements_catalog(&["CHARS_1".into(), "CHARS_2M".into(), "CHARS_3M".into(), "CHARS_4M".into()]);
    assert_eq!(cat.iter().find(|a| a.api == "CHARS_2M").unwrap().tier, "hard");
    assert_eq!(cat.iter().find(|a| a.api == "CHARS_3M").unwrap().tier, "very_hard");
    assert_eq!(cat.iter().find(|a| a.api == "CHARS_4M").unwrap().tier, "legend");
    assert_eq!(cat.iter().find(|a| a.api == "BOND_BOTH_MAX").unwrap().tier, "normal");
}
