#![forbid(unsafe_code)]
//! SoA Lexicon loader, binary-searched bucket/context tables, and zero-allocation
//! bigram join bonus calculation.
//!
//! # Examples
//!
//! ```
//! use meowpuut_core::Lexicon;
//!
//! let data = concat!(
//!     "MEOWPUUT-PINYIN-1\n",
//!     "hao\thao3\t好\t好\t4800\n",
//!     "ni\tni3\t你\t你\t5000\n",
//!     "nihao\tni3hao3\t你好\t你好\t5400\n",
//!     "# context\n",
//!     "你好\t600\n",
//! );
//! let lex = Lexicon::load(data).expect("valid lexicon");
//! assert!(lex.pinyin);
//! assert_eq!(lex.words.len(), 3);
//! assert_eq!(lex.range("nihao"), (2, 3));
//! assert!(lex.join_bonus("你", "好", false) > 2.0);
//! assert_eq!(lex.traditionalize("你好", false), "你好");
//! ```

use std::collections::HashMap;

use crate::context_index::PhraseIndex;
use crate::reading::{
    han_characters_stack, is_fully_spelled_reading, is_tone, strict_han_count, KEYS, MAX_INPUT,
};
use crate::soa::{
    BucketTableSoA, ContextTableSoA, WordTableSoA, FLAG_FULLY_SPELLED, FLAG_SINGLE_HAN_HANS,
    FLAG_SINGLE_HAN_HANT, FLAG_VERIFIED_SPELLING,
};

/// Immutable dictionary stored in Structure-of-Arrays (SoA) layout.
#[derive(Debug, Clone)]
pub struct Lexicon {
    pub storage: String,
    pub words: WordTableSoA,
    pub buckets: BucketTableSoA,
    pub context: ContextTableSoA,
    context_index: PhraseIndex,
    bucket_index: PhraseIndex,
    traditional_by_str: HashMap<String, (u32, u16, i32)>,
    simplified_by_trad: HashMap<String, (u32, u16, i32)>,
    pub longest: usize,
    pub pinyin: bool,
    pub initials: Vec<Vec<u32>>,
}

fn parse_non_negative_i32(s: &str) -> Option<i32> {
    if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    s.parse::<i32>().ok().filter(|&v| v >= 0)
}

impl Lexicon {
    /// Parses a UTF-8 native lexicon table into SoA arrays.
    ///
    /// The input is not retained: only the strings the engine reads (bucket keys,
    /// readings, both scripts, context phrases) are copied into a compact arena,
    /// and identical traditional/simplified texts share one copy. This keeps the
    /// steady-state footprint well below the raw file size for iOS extensions.
    pub fn load(input_data: &str) -> Option<Self> {
        let data = input_data;
        let pinyin = data.starts_with("MEOWPUUT-PINYIN-1\n");
        let header = if pinyin { "MEOWPUUT-PINYIN-1\n" } else { "MEOWPUUT-ZHUYIN-1\n" };
        if !data.starts_with(header) {
            return None;
        }
        let line_estimate = data.bytes().filter(|&b| b == b'\n').count();
        let mut arena = String::with_capacity(data.len() / 2);
        let mut words = WordTableSoA::with_capacity(line_estimate);
        let mut buckets = BucketTableSoA::default();
        let mut context = ContextTableSoA::default();
        let mut longest = 0usize;
        let mut reading_context = false;

        let mut offset_cursor = header.len();
        while offset_cursor < data.len() {
            let rem = &data[offset_cursor..];
            let (raw_line, next_offset) = match rem.find('\n') {
                Some(pos) => (&rem[..pos], offset_cursor + pos + 1),
                None => (rem, data.len()),
            };
            offset_cursor = next_offset;
            let line = raw_line.strip_suffix('\r').unwrap_or(raw_line);
            if line.is_empty() { continue; }
            if line == "# context" && !reading_context {
                reading_context = true;
                continue;
            }
            let first_tab = line.find('\t')?;
            if first_tab == 0 { return None; }
            let bare = &line[..first_tab];
            let weight_tab = line.rfind('\t')?;
            let weight = parse_non_negative_i32(&line[weight_tab + 1..])?;

            if reading_context {
                if weight_tab != first_tab { return None; }
                if !context.is_empty() && context.phrase(&arena, context.len() - 1) >= bare {
                    return None;
                }
                context.push(arena.len() as u32, first_tab as u16, weight);
                arena.push_str(bare);
                continue;
            }
            if weight_tab == first_tab { return None; }
            let row = &line[first_tab + 1..weight_tab];
            let first_sep = row.find('\t')?;
            let second_sep = row[first_sep + 1..].find('\t').map(|i| first_sep + 1 + i)?;
            let reading = &row[..first_sep];
            if reading.is_empty()
                || first_sep + 1 == second_sep
                || second_sep + 1 == row.len()
                || !is_tone(*reading.as_bytes().last()?, pinyin)
            {
                return None;
            }
            let mut position = 0usize;
            let mut has_sound = false;
            let bare_bytes = bare.as_bytes();
            for &key in reading.as_bytes() {
                if is_tone(key, pinyin) {
                    if !has_sound { return None; }
                    has_sound = false;
                } else {
                    let valid_key = if pinyin { (b'a'..=b'z').contains(&key) } else { KEYS.contains(&key) };
                    if position == bare_bytes.len() || key != bare_bytes[position] || !valid_key || key == b'\'' {
                        return None;
                    }
                    position += 1;
                    has_sound = true;
                }
            }
            if position != bare_bytes.len() || bare_bytes.len() > MAX_INPUT { return None; }

            let word_idx = words.len() as u32;
            if buckets.is_empty() || buckets.key(&arena, buckets.len() - 1) != bare {
                if !buckets.is_empty() && buckets.key(&arena, buckets.len() - 1) > bare {
                    return None;
                }
                buckets.push(arena.len() as u32, first_tab as u16, word_idx, word_idx + 1);
                arena.push_str(bare);
                longest = longest.max(bare.len());
            } else {
                if *words.weight.last()? < weight { return None; }
                if let Some(end) = buckets.word_end.last_mut() { *end = word_idx + 1; }
            }

            let hant = &row[first_sep + 1..second_sep];
            let hans = &row[second_sep + 1..];
            let r_off = arena.len() as u32;
            arena.push_str(reading);
            let t_off = arena.len() as u32;
            arena.push_str(hant);
            let s_off = if hans == hant {
                t_off
            } else {
                let off = arena.len() as u32;
                arena.push_str(hans);
                off
            };

            let mut flags = 0u8;
            if is_fully_spelled_reading(reading) { flags |= FLAG_FULLY_SPELLED; }
            if hant.len() <= 4 && strict_han_count(hant) == 1 { flags |= FLAG_SINGLE_HAN_HANT; }
            if hans.len() <= 4 && strict_han_count(hans) == 1 { flags |= FLAG_SINGLE_HAN_HANS; }
            words.push(r_off, first_sep as u16, t_off, hant.len() as u16, s_off, hans.len() as u16, weight, flags);
        }
        arena.shrink_to_fit();
        words.shrink_to_fit();
        let storage = arena;
        Self::from_soa_parts(storage, words, buckets, context, longest, pinyin, false)
    }

    pub(crate) fn from_soa_parts(
        storage: String,
        words: WordTableSoA,
        buckets: BucketTableSoA,
        context: ContextTableSoA,
        longest: usize,
        pinyin: bool,
        verified_ready: bool,
    ) -> Option<Self> {
        if words.is_empty() { return None; }

        let mut traditional_by_str: HashMap<String, (u32, u16, i32)> = HashMap::new();
        let mut simplified_by_trad: HashMap<String, (u32, u16, i32)> = HashMap::new();
        let mut initials: Vec<Vec<u32>> = vec![Vec::new(); 128];
        for i in 0..words.len() {
            let flags = words.flags[i];
            let weight = words.weight[i];
            if (flags & FLAG_SINGLE_HAN_HANT) != 0 && (flags & FLAG_SINGLE_HAN_HANS) != 0 {
                let simplified = words.text(&storage, i, true);
                if traditional_by_str.get(simplified).map_or(true, |&(_, _, old_w)| old_w < weight) {
                    traditional_by_str.insert(String::from(simplified), (words.hant_offset[i], words.hant_len[i], weight));
                }
                let traditional = words.text(&storage, i, false);
                if traditional != simplified
                    && simplified_by_trad.get(traditional).map_or(true, |&(_, _, old_w)| old_w < weight)
                {
                    simplified_by_trad.insert(String::from(traditional), (words.hans_offset[i], words.hans_len[i], weight));
                }
            }
            if words.hant_len[i] <= 4 {
                let first_byte = storage.as_bytes()[words.reading_offset[i] as usize] as usize;
                if first_byte < initials.len() { initials[first_byte].push(i as u32); }
            }
        }
        for items in &mut initials {
            items.sort_by(|&a, &b| words.weight[b as usize].cmp(&words.weight[a as usize]));
            if items.len() > 64 { items.truncate(64); }
        }

        let context_index = PhraseIndex::build(context.len(), |i| context.phrase(&storage, i).as_bytes());
        let bucket_index = PhraseIndex::build(buckets.len(), |i| buckets.key(&storage, i).as_bytes());
        let mut lex = Self {
            storage, words, buckets, context, context_index, bucket_index, traditional_by_str, simplified_by_trad, longest, pinyin, initials,
        };
        if !verified_ready {
            lex.precompute_verified_spellings();
        }
        Some(lex)
    }

    fn precompute_verified_spellings(&mut self) {
        if !self.pinyin {
            for f in &mut self.words.flags { *f |= FLAG_VERIFIED_SPELLING; }
            return;
        }
        let mut single_str_cache: HashMap<String, bool> = HashMap::new();
        for idx in 0..self.words.len() {
            let r_off = self.words.reading_offset[idx] as usize;
            let r_len = self.words.reading_len[idx] as usize;
            let spelling = &self.storage[r_off..r_off + r_len];
            let bytes = spelling.as_bytes();
            let (mut start, mut ok) = (0usize, true);
            for end in 0..bytes.len() {
                if !is_tone(bytes[end], true) { continue; }
                let syl = &spelling[start..=end];
                let found = if let Some(&cached) = single_str_cache.get(syl) {
                    cached
                } else {
                    let (lo, hi) = self.range(&syl[..syl.len() - 1]);
                    let has_single = (lo..hi).any(|e| {
                        self.words.reading(&self.storage, e) == syl && self.words.is_single_han_hant(e)
                    });
                    single_str_cache.insert(String::from(syl), has_single);
                    has_single
                };
                if !found { ok = false; break; }
                start = end + 1;
            }
            if ok && start == bytes.len() {
                self.words.flags[idx] |= FLAG_VERIFIED_SPELLING;
            }
        }
    }

    #[inline]
    pub fn lower_bound_bucket(&self, key: &str) -> usize {
        let (mut size, mut left) = (self.buckets.len(), 0usize);
        while size > 0 {
            let half = size / 2;
            let mid = left + half;
            if self.buckets.key(&self.storage, mid) < key {
                left = mid + 1;
                size -= half + 1;
            } else {
                size = half;
            }
        }
        left
    }

    #[inline]
    pub fn range(&self, key: &str) -> (usize, usize) {
        let (storage, buckets) = (&self.storage, &self.buckets);
        self.bucket_index
            .get(key.as_bytes(), |i| buckets.key(storage, i).as_bytes())
            .map_or((0, 0), |idx| buckets.range(idx))
    }

    /// Heap bytes of the lexicon tables (storage, SoA columns, hash indexes).
    pub fn heap_bytes(&self) -> usize {
        self.storage.capacity()
            + self.words.heap_bytes()
            + self.buckets.key_offset.capacity() * 12
            + self.context.frequency.capacity() * 10
            + self.context_index.heap_bytes()
            + self.bucket_index.heap_bytes()
            + self.initials.iter().map(|v| v.capacity() * 4).sum::<usize>()
    }

    /// Frequency of an exact context phrase, via the O(1) hashed index.
    #[inline]
    pub fn context_frequency(&self, phrase: &[u8]) -> Option<i32> {
        let (storage, context) = (&self.storage, &self.context);
        self.context_index
            .get(phrase, |i| context.phrase(storage, i).as_bytes())
            .map(|row| context.frequency[row])
    }

    /// Computes context join bonus between `left` and `right` using a stack-allocated 64-byte buffer.
    pub fn join_bonus(&self, left: &str, right: &str, distinguish_strong: bool) -> f64 {
        if self.context.is_empty() || right.is_empty() { return 0.0; }
        let (mut starts, mut ends) = ([0usize; 4], [0usize; 4]);
        let (mut left_count, mut right_count) = (0usize, 0usize);
        let is_start = |byte: u8| (byte & 0xC0) != 0x80;

        let left_bytes = left.as_bytes();
        let mut i = left_bytes.len();
        while i > 0 && left_count < starts.len() {
            i -= 1;
            if is_start(left_bytes[i]) {
                starts[left_count] = i;
                left_count += 1;
            }
        }
        let right_bytes = right.as_bytes();
        for idx in 1..=right_bytes.len() {
            if right_count >= ends.len() { break; }
            if idx == right_bytes.len() || is_start(right_bytes[idx]) {
                ends[right_count] = idx;
                right_count += 1;
            }
        }
        if left_count == 0 || right_count == 0 { return 0.0; }
        let offset = starts[left_count - 1];
        let left_tail = &left_bytes[offset..];
        let right_head = &right_bytes[..ends[right_count - 1]];
        let total_len = left_tail.len() + right_head.len();
        if total_len > 64 { return 0.0; }
        let mut joined_buf = [0u8; 64];
        joined_buf[..left_tail.len()].copy_from_slice(left_tail);
        joined_buf[left_tail.len()..total_len].copy_from_slice(right_head);
        let joined = &joined_buf[..total_len];

        let mut frequency = 0i32;
        for li in 0..left_count {
            let begin = starts[li] - offset;
            for rj in 0..right_count {
                if li + rj >= 4 { break; }
                let len = left_bytes.len() - starts[li] + ends[rj];
                if let Some(f) = self.context_frequency(&joined[begin..begin + len]) {
                    frequency = frequency.max(f);
                }
            }
        }
        if distinguish_strong && right_count > 1 {
            (3.0 * (f64::from(frequency).ln_1p() / 10000.0_f64.ln_1p())).min(3.0)
        } else {
            (0.4 * f64::from(frequency).ln_1p()).min(3.0)
        }
    }

    #[inline]
    pub fn traditional_char<'a>(&'a self, ch: &'a str) -> &'a str {
        if let Some(&(t_off, t_len, _)) = self.traditional_by_str.get(ch) {
            &self.storage[t_off as usize..t_off as usize + t_len as usize]
        } else {
            ch
        }
    }

    /// Most frequent simplified form of one traditional character (identity if unknown).
    #[inline]
    pub fn simplified_char<'a>(&'a self, ch: &'a str) -> &'a str {
        if let Some(&(s_off, s_len, _)) = self.simplified_by_trad.get(ch) {
            &self.storage[s_off as usize..s_off as usize + s_len as usize]
        } else {
            ch
        }
    }

    pub fn traditionalize_into(&self, value: &str, tail: bool, out: &mut String) {
        out.clear();
        let Some(chars) = han_characters_stack(value) else { return; };
        let start = if tail && chars.len() > 4 { chars.len() - 4 } else { 0 };
        let end = chars.len().min(start + 4);
        for i in start..end {
            out.push_str(self.traditional_char(chars.slice(value, i)));
        }
    }

    pub fn traditionalize(&self, value: &str, tail: bool) -> String {
        let mut out = String::with_capacity(16);
        self.traditionalize_into(value, tail, &mut out);
        out
    }
}
