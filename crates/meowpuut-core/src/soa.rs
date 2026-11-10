#![forbid(unsafe_code)]
//! Structure-of-Arrays (SoA) data layouts for words, buckets, context bigrams,
//! Japanese lexicon entries, and session candidates.
//!
//! # Examples
//!
//! ```
//! use meowpuut_core::soa::{WordTableSoA, FLAG_FULLY_SPELLED, FLAG_VERIFIED_SPELLING};
//!
//! let storage = "ni3hao3\t你好\t你好";
//! let mut table = WordTableSoA::with_capacity(4);
//! table.push(0, 7, 8, 6, 15, 6, 5200, FLAG_FULLY_SPELLED | FLAG_VERIFIED_SPELLING);
//! assert_eq!(table.len(), 1);
//! assert_eq!(table.reading(storage, 0), "ni3hao3");
//! assert_eq!(table.text(storage, 0, false), "你好");
//! assert_eq!(table.text(storage, 0, true), "你好");
//! assert!(table.is_fully_spelled(0));
//! assert!(table.is_verified_spelling(0));
//! ```

pub const FLAG_FULLY_SPELLED: u8 = 1 << 0;
pub const FLAG_SINGLE_HAN_HANT: u8 = 1 << 1;
pub const FLAG_SINGLE_HAN_HANS: u8 = 1 << 2;
pub const FLAG_VERIFIED_SPELLING: u8 = 1 << 3;

/// Cache-friendly Structure-of-Arrays (SoA) dictionary word table.
#[derive(Debug, Clone, Default)]
pub struct WordTableSoA {
    pub reading_offset: Vec<u32>,
    pub reading_len: Vec<u16>,
    pub hant_offset: Vec<u32>,
    pub hant_len: Vec<u16>,
    pub hans_offset: Vec<u32>,
    pub hans_len: Vec<u16>,
    pub weight: Vec<i32>,
    pub flags: Vec<u8>,
}

impl WordTableSoA {
    /// Heap bytes held by all columns (23 bytes per word when tight).
    pub fn heap_bytes(&self) -> usize {
        self.weight.capacity() * 23
    }

    /// Releases over-reserved column capacity after loading.
    pub fn shrink_to_fit(&mut self) {
        self.reading_offset.shrink_to_fit();
        self.reading_len.shrink_to_fit();
        self.hant_offset.shrink_to_fit();
        self.hant_len.shrink_to_fit();
        self.hans_offset.shrink_to_fit();
        self.hans_len.shrink_to_fit();
        self.weight.shrink_to_fit();
        self.flags.shrink_to_fit();
    }

    #[inline]
    pub fn with_capacity(cap: usize) -> Self {
        Self {
            reading_offset: Vec::with_capacity(cap),
            reading_len: Vec::with_capacity(cap),
            hant_offset: Vec::with_capacity(cap),
            hant_len: Vec::with_capacity(cap),
            hans_offset: Vec::with_capacity(cap),
            hans_len: Vec::with_capacity(cap),
            weight: Vec::with_capacity(cap),
            flags: Vec::with_capacity(cap),
        }
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.weight.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.weight.is_empty()
    }

    #[inline]
    #[allow(clippy::too_many_arguments)]
    pub fn push(
        &mut self,
        r_off: u32,
        r_len: u16,
        t_off: u32,
        t_len: u16,
        s_off: u32,
        s_len: u16,
        w: i32,
        f: u8,
    ) {
        self.reading_offset.push(r_off);
        self.reading_len.push(r_len);
        self.hant_offset.push(t_off);
        self.hant_len.push(t_len);
        self.hans_offset.push(s_off);
        self.hans_len.push(s_len);
        self.weight.push(w);
        self.flags.push(f);
    }

    #[inline]
    pub fn reading<'a>(&self, storage: &'a str, idx: usize) -> &'a str {
        let start = self.reading_offset[idx] as usize;
        let end = start + self.reading_len[idx] as usize;
        &storage[start..end]
    }

    #[inline]
    pub fn text<'a>(&self, storage: &'a str, idx: usize, hans: bool) -> &'a str {
        let (off, len) = if hans {
            (self.hans_offset[idx] as usize, self.hans_len[idx] as usize)
        } else {
            (self.hant_offset[idx] as usize, self.hant_len[idx] as usize)
        };
        &storage[off..off + len]
    }

    #[inline]
    pub fn is_fully_spelled(&self, idx: usize) -> bool {
        (self.flags[idx] & FLAG_FULLY_SPELLED) != 0
    }

    #[inline]
    pub fn is_single_han_hant(&self, idx: usize) -> bool {
        (self.flags[idx] & FLAG_SINGLE_HAN_HANT) != 0
    }

    #[inline]
    pub fn is_verified_spelling(&self, idx: usize) -> bool {
        (self.flags[idx] & FLAG_VERIFIED_SPELLING) != 0
    }
}

/// Structure-of-Arrays (SoA) bare reading bucket index.
///
/// # Examples
///
/// ```
/// use meowpuut_core::soa::BucketTableSoA;
/// let storage = "nihao";
/// let mut buckets = BucketTableSoA::default();
/// buckets.push(0, 5, 0, 3);
/// assert_eq!(buckets.len(), 1);
/// assert_eq!(buckets.key(storage, 0), "nihao");
/// assert_eq!(buckets.range(0), (0, 3));
/// ```
#[derive(Debug, Clone, Default)]
pub struct BucketTableSoA {
    pub key_offset: Vec<u32>,
    pub key_len: Vec<u16>,
    pub word_start: Vec<u32>,
    pub word_end: Vec<u32>,
}

impl BucketTableSoA {
    #[inline]
    pub fn len(&self) -> usize {
        self.word_start.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.word_start.is_empty()
    }

    #[inline]
    pub fn push(&mut self, k_off: u32, k_len: u16, start: u32, end: u32) {
        self.key_offset.push(k_off);
        self.key_len.push(k_len);
        self.word_start.push(start);
        self.word_end.push(end);
    }

    #[inline]
    pub fn key<'a>(&self, storage: &'a str, idx: usize) -> &'a str {
        let start = self.key_offset[idx] as usize;
        let end = start + self.key_len[idx] as usize;
        &storage[start..end]
    }

    #[inline]
    pub fn range(&self, idx: usize) -> (usize, usize) {
        (self.word_start[idx] as usize, self.word_end[idx] as usize)
    }
}

/// Structure-of-Arrays (SoA) context phrase table for bigram/n-gram join scoring.
#[derive(Debug, Clone, Default)]
pub struct ContextTableSoA {
    pub phrase_offset: Vec<u32>,
    pub phrase_len: Vec<u16>,
    pub frequency: Vec<i32>,
}

impl ContextTableSoA {
    #[inline]
    pub fn len(&self) -> usize {
        self.frequency.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.frequency.is_empty()
    }

    #[inline]
    pub fn push(&mut self, off: u32, len: u16, freq: i32) {
        self.phrase_offset.push(off);
        self.phrase_len.push(len);
        self.frequency.push(freq);
    }

    #[inline]
    pub fn phrase<'a>(&self, storage: &'a str, idx: usize) -> &'a str {
        let start = self.phrase_offset[idx] as usize;
        let end = start + self.phrase_len[idx] as usize;
        &storage[start..end]
    }
}

/// Structure-of-Arrays (SoA) session-local Japanese word table.
#[derive(Debug, Clone, Default)]
pub struct JapaneseTableSoA {
    pub arena: String,
    pub reading_offset: Vec<u32>,
    pub reading_len: Vec<u16>,
    pub text_offset: Vec<u32>,
    pub text_len: Vec<u16>,
    pub weight: Vec<i32>,
    pub single_han: Vec<bool>,
}

impl JapaneseTableSoA {
    #[inline]
    pub fn with_capacity(words_cap: usize, bytes_cap: usize) -> Self {
        Self {
            arena: String::with_capacity(bytes_cap),
            reading_offset: Vec::with_capacity(words_cap),
            reading_len: Vec::with_capacity(words_cap),
            text_offset: Vec::with_capacity(words_cap),
            text_len: Vec::with_capacity(words_cap),
            weight: Vec::with_capacity(words_cap),
            single_han: Vec::with_capacity(words_cap),
        }
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.weight.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.weight.is_empty()
    }

    #[inline]
    pub fn push(&mut self, reading: &str, text: &str, weight: i32, is_single_han: bool) {
        let r_off = self.arena.len() as u32;
        self.arena.push_str(reading);
        let t_off = self.arena.len() as u32;
        self.arena.push_str(text);
        self.reading_offset.push(r_off);
        self.reading_len.push(reading.len() as u16);
        self.text_offset.push(t_off);
        self.text_len.push(text.len() as u16);
        self.weight.push(weight);
        self.single_han.push(is_single_han);
    }

    #[inline]
    pub fn reading(&self, idx: usize) -> &str {
        let start = self.reading_offset[idx] as usize;
        let end = start + self.reading_len[idx] as usize;
        &self.arena[start..end]
    }

    #[inline]
    pub fn text(&self, idx: usize) -> &str {
        let start = self.text_offset[idx] as usize;
        let end = start + self.text_len[idx] as usize;
        &self.arena[start..end]
    }
}

/// Preallocated Structure-of-Arrays (SoA) candidate buffer for a session.
#[derive(Debug, Clone, Default)]
pub struct CandidatePoolSoA {
    pub arena: String,
    pub text_offset: Vec<u32>,
    pub text_len: Vec<u16>,
    pub consumed: Vec<usize>,
    pub kind: Vec<u32>,
    pub score_kind: Vec<u32>,
    pub score: Vec<f64>,
}

impl CandidatePoolSoA {
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
    pub fn len(&self) -> usize {
        self.consumed.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.consumed.is_empty()
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
    pub fn text(&self, idx: usize) -> &str {
        let start = self.text_offset[idx] as usize;
        let end = start + self.text_len[idx] as usize;
        &self.arena[start..end]
    }
}
