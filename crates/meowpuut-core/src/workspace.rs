#![forbid(unsafe_code)]
//! Preallocated Structure-of-Arrays (`SoA`) scratch workspace for zero-allocation
//! hot-loop beam decoding and segment prediction.
//!
//! # Examples
//!
//! ```
//! use meowpuut_core::workspace::{DecoderScratchSoA, NasalVariantsStack};
//!
//! let mut scratch = DecoderScratchSoA::new();
//! let sig = scratch.capacity_signature();
//! scratch.clear_decode();
//! assert_eq!(scratch.capacity_signature(), sig);
//!
//! let variants = NasalVariantsStack::from_sounds("wang");
//! assert_eq!(variants.len(), 1);
//! assert_eq!(variants.get(0), "wan");
//! ```

use crate::reading::{BEAM, CANDIDATE_LIMIT, MAX_INPUT};

/// Stack-allocated nasal variants (`n` <-> `ng`) for a Pinyin sound string of length `4..=32`.
#[derive(Debug, Clone)]
pub struct NasalVariantsStack {
    buf: [[u8; 36]; 16],
    lens: [u8; 16],
    count: usize,
}

impl NasalVariantsStack {
    pub fn from_sounds(sounds: &str) -> Self {
        let mut out = Self { buf: [[0u8; 36]; 16], lens: [0u8; 16], count: 0 };
        let bytes = sounds.as_bytes();
        if bytes.len() < 4 || bytes.len() > 32 {
            return out;
        }
        for i in 1..bytes.len() {
            if bytes[i] != b'n' || !b"aeiouv".contains(&bytes[i - 1]) || out.count >= 16 {
                continue;
            }
            let slot = &mut out.buf[out.count];
            if i + 1 < bytes.len() && bytes[i + 1] == b'g' {
                slot[..=i].copy_from_slice(&bytes[..=i]);
                let rem = bytes.len() - (i + 2);
                slot[i + 1..i + 1 + rem].copy_from_slice(&bytes[i + 2..]);
                out.lens[out.count] = (bytes.len() - 1) as u8;
                out.count += 1;
            } else if i + 1 == bytes.len() || !b"aeiouv".contains(&bytes[i + 1]) {
                slot[..=i].copy_from_slice(&bytes[..=i]);
                slot[i + 1] = b'g';
                let rem = bytes.len() - (i + 1);
                slot[i + 2..i + 2 + rem].copy_from_slice(&bytes[i + 1..]);
                out.lens[out.count] = (bytes.len() + 1) as u8;
                out.count += 1;
            }
        }
        out
    }

    #[inline]
    pub fn len(&self) -> usize { self.count }
    #[inline]
    pub fn is_empty(&self) -> bool { self.count == 0 }
    #[inline]
    pub fn get(&self, idx: usize) -> &str {
        std::str::from_utf8(&self.buf[idx][..self.lens[idx] as usize]).unwrap_or("")
    }
}

/// Preallocated SoA suffix lattice for dynamic programming beam search.
#[derive(Debug, Clone)]
pub struct PathLatticeSoA {
    pub text_arena: String,
    pub text_offset: Vec<u32>,
    pub text_len: Vec<u16>,
    pub trad_offset: Vec<u32>,
    pub trad_len: Vec<u16>,
    pub score: Vec<f64>,
    pub spelled: Vec<bool>,
    pub verified: Vec<bool>,
    pub counts: [u8; MAX_INPUT + 1],
}

impl PathLatticeSoA {
    pub fn new() -> Self {
        let slots = (MAX_INPUT + 1) * BEAM;
        Self {
            text_arena: String::with_capacity(slots * 24),
            text_offset: vec![0; slots],
            text_len: vec![0; slots],
            trad_offset: vec![0; slots],
            trad_len: vec![0; slots],
            score: vec![0.0; slots],
            spelled: vec![false; slots],
            verified: vec![false; slots],
            counts: [0u8; MAX_INPUT + 1],
        }
    }

    #[inline]
    pub fn reset(&mut self, length: usize) {
        self.text_arena.clear();
        self.counts[..=length].fill(0);
    }

    #[inline]
    pub fn count_at(&self, pos: usize) -> usize { self.counts[pos] as usize }
    #[inline]
    pub fn slot_index(pos: usize, beam_idx: usize) -> usize { pos * BEAM + beam_idx }

    #[inline]
    pub fn push_raw_at(&mut self, pos: usize, text: &str, trad: &str, score: f64, spelled: bool, verified: bool) {
        let b = self.counts[pos] as usize;
        if b >= BEAM { return; }
        let slot = Self::slot_index(pos, b);
        let t_off = self.text_arena.len() as u32;
        self.text_arena.push_str(text);
        let tr_off = self.text_arena.len() as u32;
        self.text_arena.push_str(trad);
        self.text_offset[slot] = t_off;
        self.text_len[slot] = text.len() as u16;
        self.trad_offset[slot] = tr_off;
        self.trad_len[slot] = trad.len() as u16;
        self.score[slot] = score;
        self.spelled[slot] = spelled;
        self.verified[slot] = verified;
        self.counts[pos] += 1;
    }

    #[inline]
    pub fn text(&self, slot: usize) -> &str {
        let s = self.text_offset[slot] as usize;
        &self.text_arena[s..s + self.text_len[slot] as usize]
    }

    #[inline]
    pub fn trad(&self, slot: usize) -> &str {
        let s = self.trad_offset[slot] as usize;
        &self.text_arena[s..s + self.trad_len[slot] as usize]
    }
}

impl Default for PathLatticeSoA { fn default() -> Self { Self::new() } }

/// Preallocated SoA step candidate transitions within a single DP position.
#[derive(Debug, Clone)]
pub struct StepPathsSoA {
    pub arena: String,
    pub text_offset: Vec<u32>,
    pub text_len: Vec<u16>,
    pub trad_offset: Vec<u32>,
    pub trad_len: Vec<u16>,
    pub score: Vec<f64>,
    pub spelled: Vec<bool>,
    pub verified: Vec<bool>,
    pub order: Vec<usize>,
}

impl StepPathsSoA {
    pub fn new() -> Self {
        let cap = 1024;
        Self {
            arena: String::with_capacity(cap * 24),
            text_offset: Vec::with_capacity(cap),
            text_len: Vec::with_capacity(cap),
            trad_offset: Vec::with_capacity(cap),
            trad_len: Vec::with_capacity(cap),
            score: Vec::with_capacity(cap),
            spelled: Vec::with_capacity(cap),
            verified: Vec::with_capacity(cap),
            order: Vec::with_capacity(cap),
        }
    }

    #[inline]
    pub fn clear(&mut self) {
        self.arena.clear();
        self.text_offset.clear();
        self.text_len.clear();
        self.trad_offset.clear();
        self.trad_len.clear();
        self.score.clear();
        self.spelled.clear();
        self.verified.clear();
        self.order.clear();
    }

    #[inline]
    #[allow(clippy::too_many_arguments)]
    pub fn push_joined(&mut self, w_text: &str, s_text: &str, w_trad: &str, s_trad: &str, keep_trad: bool, score: f64, spelled: bool, verified: bool) {
        let idx = self.score.len();
        let t_off = self.arena.len() as u32;
        self.arena.push_str(w_text);
        self.arena.push_str(s_text);
        let t_len = (w_text.len() + s_text.len()) as u16;
        let (tr_off, tr_len) = if keep_trad {
            let off = self.arena.len() as u32;
            self.arena.push_str(w_trad);
            self.arena.push_str(s_trad);
            (off, (w_trad.len() + s_trad.len()) as u16)
        } else { (0, 0) };
        self.text_offset.push(t_off);
        self.text_len.push(t_len);
        self.trad_offset.push(tr_off);
        self.trad_len.push(tr_len);
        self.score.push(score);
        self.spelled.push(spelled);
        self.verified.push(verified);
        self.order.push(idx);
    }

    #[inline]
    pub fn text(&self, idx: usize) -> &str {
        let s = self.text_offset[idx] as usize;
        &self.arena[s..s + self.text_len[idx] as usize]
    }

    #[inline]
    pub fn trad(&self, idx: usize) -> &str {
        let s = self.trad_offset[idx] as usize;
        &self.arena[s..s + self.trad_len[idx] as usize]
    }
}

impl Default for StepPathsSoA { fn default() -> Self { Self::new() } }

/// Preallocated SoA output buffer for `decode_into` and `Fallback` lists.
#[derive(Debug, Clone)]
pub struct DecodeOutputSoA {
    pub arena: String,
    pub text_offset: Vec<u32>,
    pub text_len: Vec<u16>,
    pub consumed: Vec<usize>,
    pub kind: Vec<u32>,
    pub score_kind: Vec<u32>,
    pub score: Vec<f64>,
}

impl DecodeOutputSoA {
    pub fn with_capacity(cap: usize) -> Self {
        Self {
            arena: String::with_capacity((cap * 64).max(16384)),
            text_offset: Vec::with_capacity(cap),
            text_len: Vec::with_capacity(cap),
            consumed: Vec::with_capacity(cap),
            kind: Vec::with_capacity(cap),
            score_kind: Vec::with_capacity(cap),
            score: Vec::with_capacity(cap),
        }
    }

    #[inline]
    pub fn clear(&mut self) {
        self.arena.clear();
        self.text_offset.clear();
        self.text_len.clear();
        self.consumed.clear();
        self.kind.clear();
        self.score_kind.clear();
        self.score.clear();
    }

    #[inline]
    pub fn len(&self) -> usize { self.score.len() }
    #[inline]
    pub fn is_empty(&self) -> bool { self.score.is_empty() }
    #[inline]
    pub fn text(&self, idx: usize) -> &str {
        let s = self.text_offset[idx] as usize;
        &self.arena[s..s + self.text_len[idx] as usize]
    }
    #[inline]
    pub fn contains_text(&self, candidate: &str) -> bool {
        (0..self.len()).any(|i| self.text(i) == candidate)
    }

    #[inline]
    pub fn push(&mut self, text: &str, consumed: usize, kind: u32, score_kind: u32, score: f64) {
        let off = self.arena.len() as u32;
        self.arena.push_str(text);
        self.text_offset.push(off);
        self.text_len.push(text.len() as u16);
        self.consumed.push(consumed);
        self.kind.push(kind);
        self.score_kind.push(score_kind);
        self.score.push(score);
    }

    #[inline]
    pub fn insert_at(&mut self, pos: usize, text: &str, consumed: usize, kind: u32, score_kind: u32, score: f64) {
        let off = self.arena.len() as u32;
        self.arena.push_str(text);
        self.text_offset.insert(pos, off);
        self.text_len.insert(pos, text.len() as u16);
        self.consumed.insert(pos, consumed);
        self.kind.insert(pos, kind);
        self.score_kind.insert(pos, score_kind);
        self.score.insert(pos, score);
    }

    #[inline]
    pub fn remove_at(&mut self, idx: usize) {
        self.text_offset.remove(idx);
        self.text_len.remove(idx);
        self.consumed.remove(idx);
        self.kind.remove(idx);
        self.score_kind.remove(idx);
        self.score.remove(idx);
    }

    #[inline]
    pub fn truncate(&mut self, max_len: usize) {
        if self.len() > max_len {
            self.text_offset.truncate(max_len);
            self.text_len.truncate(max_len);
            self.consumed.truncate(max_len);
            self.kind.truncate(max_len);
            self.score_kind.truncate(max_len);
            self.score.truncate(max_len);
        }
    }
}

/// Complete preallocated scratch workspace owned by a `Session`.
#[derive(Debug, Clone)]
pub struct DecoderScratchSoA {
    pub lattice: PathLatticeSoA,
    pub step_paths: StepPathsSoA,
    pub decoded: DecodeOutputSoA,
    pub aux_decoded: DecodeOutputSoA,
    pub fallback_raw: DecodeOutputSoA,
    pub fallback_out: DecodeOutputSoA,
    pub fallback_order: Vec<usize>,
    pub trad_scratch: String,
}

impl DecoderScratchSoA {
    pub fn new() -> Self {
        Self {
            lattice: PathLatticeSoA::new(),
            step_paths: StepPathsSoA::new(),
            decoded: DecodeOutputSoA::with_capacity(CANDIDATE_LIMIT + 32),
            aux_decoded: DecodeOutputSoA::with_capacity(CANDIDATE_LIMIT + 32),
            fallback_raw: DecodeOutputSoA::with_capacity(8192),
            fallback_out: DecodeOutputSoA::with_capacity(256),
            fallback_order: Vec::with_capacity(8192),
            trad_scratch: String::with_capacity(256),
        }
    }

    #[inline]
    pub fn clear_decode(&mut self) {
        self.decoded.clear();
        self.step_paths.clear();
    }

    pub fn capacity_signature(&self) -> usize {
        self.lattice.text_arena.capacity()
            + self.lattice.score.capacity()
            + self.step_paths.arena.capacity()
            + self.step_paths.score.capacity()
            + self.decoded.arena.capacity()
            + self.decoded.score.capacity()
            + self.aux_decoded.arena.capacity()
            + self.fallback_raw.arena.capacity()
            + self.fallback_out.arena.capacity()
            + self.fallback_order.capacity()
            + self.trad_scratch.capacity()
    }
}

impl Default for DecoderScratchSoA { fn default() -> Self { Self::new() } }
