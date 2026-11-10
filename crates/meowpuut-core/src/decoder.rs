#![forbid(unsafe_code)]
//! Zero-allocation SoA beam decoder (`decode_into` and `decode_with_buffers`).
//!
//! # Examples
//!
//! ```
//! use meowpuut_core::{Lexicon, DecoderScratchSoA};
//! use meowpuut_core::decoder::{decode_into, has_exact_match};
//!
//! let tsv = concat!(
//!     "MEOWPUUT-PINYIN-1\n",
//!     "hao\thao3\t好\t好\t4800\n",
//!     "ni\tni3\t你\t你\t5000\n",
//!     "nihao\tni3hao3\t你好\t你好\t5400\n",
//!     "# context\n",
//!     "你好\t600\n",
//! );
//! let lex = Lexicon::load(tsv).unwrap();
//! assert!(has_exact_match(&lex, "nihao"));
//! let mut scratch = DecoderScratchSoA::new();
//! decode_into(&lex, "nihao", false, false, true, &mut scratch);
//! assert_eq!(scratch.decoded.len(), 1);
//! assert_eq!(scratch.decoded.text(0), "你好");
//! ```

use std::cmp::Ordering;

pub use crate::fallback::{fallback_into, has_exact_match, unfinished_phrase_into};
use crate::lexicon::Lexicon;
use crate::reading::{Reading, BEAM, CANDIDATE_LIMIT};
use crate::workspace::{
    DecodeOutputSoA, DecoderScratchSoA, NasalVariantsStack, PathLatticeSoA, StepPathsSoA,
};
use crate::{CandidateKind, CandidateScoreKind};

/// Core SoA beam decoder (`decode_into` on `DecoderScratchSoA::decoded`).
pub fn decode_into(
    lexicon: &Lexicon,
    input: &str,
    hans: bool,
    complete_only: bool,
    compute_metadata: bool,
    scratch: &mut DecoderScratchSoA,
) {
    decode_with_buffers(
        lexicon,
        input,
        hans,
        complete_only,
        compute_metadata,
        &mut scratch.lattice,
        &mut scratch.step_paths,
        &mut scratch.fallback_raw,
        &mut scratch.fallback_order,
        &mut scratch.fallback_out,
        &mut scratch.decoded,
    );
}

/// Core SoA beam decoder writing into an explicit `DecodeOutputSoA` target.
#[allow(clippy::too_many_arguments)]
pub fn decode_with_buffers(
    lexicon: &Lexicon,
    input: &str,
    hans: bool,
    complete_only: bool,
    compute_metadata: bool,
    lattice: &mut PathLatticeSoA,
    step_paths: &mut StepPathsSoA,
    fb_raw: &mut DecodeOutputSoA,
    fb_order: &mut Vec<usize>,
    fb_out: &mut DecodeOutputSoA,
    out: &mut DecodeOutputSoA,
) {
    out.clear();
    let reading = Reading::new(input, lexicon.pinyin);
    let length = reading.len();
    if length == 0 {
        return;
    }

    let verified_word = |idx: usize| -> bool {
        if !compute_metadata || !lexicon.pinyin {
            true
        } else {
            lexicon.words.is_verified_spelling(idx)
        }
    };

    let (whole_lo, whole_hi) = lexicon.range(reading.sounds());
    for index in whole_lo..whole_hi {
        let wr = lexicon.words.reading(&lexicon.storage, index).as_bytes();
        let spelled = lexicon.words.is_fully_spelled(index);
        if (!complete_only || spelled) && reading.matches(wr, 0, length) {
            let kind = if !reading.unambiguous {
                CandidateKind::Unknown as u32
            } else if lexicon.pinyin && !spelled {
                CandidateKind::Correction as u32
            } else if verified_word(index) {
                CandidateKind::Exact as u32
            } else {
                CandidateKind::Unknown as u32
            };
            let text = lexicon.words.text(&lexicon.storage, index, hans);
            if !out.contains_text(text) {
                out.push(
                    text,
                    input.len(),
                    kind,
                    CandidateScoreKind::Lexicon as u32,
                    f64::from(lexicon.words.weight[index]),
                );
            }
        }
        if out.len() == CANDIDATE_LIMIT {
            return;
        }
    }

    if !complete_only && lexicon.pinyin && !out.is_empty() && input == reading.sounds() {
        let mut insertion = 3.min(out.len());
        let mut added = 0usize;
        let variants = NasalVariantsStack::from_sounds(reading.sounds());
        for v_idx in 0..variants.len() {
            let (lo, hi) = lexicon.range(variants.get(v_idx));
            for index in lo..hi {
                if added >= 2 {
                    break;
                }
                let weight = lexicon.words.weight[index];
                if weight < 4000 {
                    break;
                }
                let text = lexicon.words.text(&lexicon.storage, index, hans);
                if !out.contains_text(text) {
                    out.insert_at(
                        insertion,
                        text,
                        input.len(),
                        CandidateKind::Correction as u32,
                        CandidateScoreKind::Lexicon as u32,
                        f64::from(weight),
                    );
                    insertion += 1;
                    added += 1;
                }
            }
            if added == 2 {
                break;
            }
        }
        out.truncate(CANDIDATE_LIMIT);
    }

    if out.is_empty() && lexicon.pinyin && !complete_only {
        fallback_into(lexicon, input, hans, false, fb_raw, fb_order, fb_out);
        for i in 0..fb_out.len() {
            let text = fb_out.text(i);
            if !out.contains_text(text) {
                out.push(
                    text,
                    input.len(),
                    CandidateKind::Correction as u32,
                    CandidateScoreKind::Fallback as u32,
                    fb_out.score[i],
                );
            }
        }
    }

    lattice.reset(length);
    lattice.push_raw_at(length, "", "", 0.0, true, true);
    let sounds_str = reading.sounds();

    for start in (0..length).rev() {
        step_paths.clear();
        let max_end = length.min(start + lexicon.longest);
        for end in (start + 1)..=max_end {
            let suffix_count = lattice.count_at(end);
            if suffix_count == 0 || (start == 0 && end == length) {
                continue;
            }
            let (lo, hi) = lexicon.range(&sounds_str[start..end]);
            let mut used = 0usize;
            for index in lo..hi {
                let word_spelled = lexicon.words.is_fully_spelled(index);
                if complete_only && !word_spelled {
                    continue;
                }
                let wr = lexicon.words.reading(&lexicon.storage, index).as_bytes();
                if !reading.matches(wr, start, end) {
                    continue;
                }
                let score = f64::from(lexicon.words.weight[index]) / 700.0 - 19.0;
                let verified = verified_word(index);
                let traditional = lexicon.words.text(&lexicon.storage, index, false);
                let word_text = lexicon.words.text(&lexicon.storage, index, hans);

                for b_idx in 0..suffix_count {
                    let s_slot = PathLatticeSoA::slot_index(end, b_idx);
                    let s_text = lattice.text(s_slot);
                    let s_trad = lattice.trad(s_slot);
                    let right = if hans { s_trad } else { s_text };
                    let bonus = lexicon.join_bonus(traditional, right, false);
                    step_paths.push_joined(
                        word_text,
                        s_text,
                        traditional,
                        right,
                        hans,
                        score + lattice.score[s_slot] + bonus,
                        lattice.spelled[s_slot] && (!lexicon.pinyin || word_spelled),
                        lattice.verified[s_slot] && verified,
                    );
                }
                used += 1;
                if used == BEAM {
                    break;
                }
            }
        }

        let sp_arena = &step_paths.arena;
        let sp_off = &step_paths.text_offset;
        let sp_len = &step_paths.text_len;
        let sp_scores = &step_paths.score;
        step_paths.order.sort_by(|&a, &b| {
            let sa = sp_scores[a];
            let sb = sp_scores[b];
            if sa == sb {
                let ta = &sp_arena[sp_off[a] as usize..sp_off[a] as usize + sp_len[a] as usize];
                let tb = &sp_arena[sp_off[b] as usize..sp_off[b] as usize + sp_len[b] as usize];
                ta.cmp(tb)
            } else if sa > sb {
                Ordering::Less
            } else {
                Ordering::Greater
            }
        });

        for &idx in &step_paths.order {
            let cand_text = step_paths.text(idx);
            let mut duplicate = false;
            let current_count = lattice.count_at(start);
            for b_idx in 0..current_count {
                let existing_slot = PathLatticeSoA::slot_index(start, b_idx);
                if lattice.text(existing_slot) == cand_text {
                    duplicate = true;
                    break;
                }
            }
            if !duplicate {
                lattice.push_raw_at(
                    start,
                    cand_text,
                    step_paths.trad(idx),
                    step_paths.score[idx],
                    step_paths.spelled[idx],
                    step_paths.verified[idx],
                );
                if lattice.count_at(start) == BEAM {
                    break;
                }
            }
        }
    }

    let top_count = lattice.count_at(0);
    for b_idx in 0..top_count {
        let slot = PathLatticeSoA::slot_index(0, b_idx);
        let kind = if !reading.unambiguous {
            CandidateKind::Unknown as u32
        } else if !lattice.spelled[slot] {
            CandidateKind::Correction as u32
        } else if lattice.verified[slot] {
            CandidateKind::Path as u32
        } else {
            CandidateKind::Unknown as u32
        };
        let text = lattice.text(slot);
        if !out.contains_text(text) {
            out.push(
                text,
                input.len(),
                kind,
                CandidateScoreKind::Path as u32,
                lattice.score[slot],
            );
        }
        if out.len() == CANDIDATE_LIMIT {
            break;
        }
    }
}
