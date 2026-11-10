#![forbid(unsafe_code)]
//! Open-addressing exact-match hash index over byte-string rows.
//!
//! Used for the context (n-gram) table probed by `Lexicon::join_bonus` and for
//! exact reading-bucket lookups in `Lexicon::range`, both of which sit in the
//! innermost decoder loop. A sorted binary search costs ~18 string compares per
//! probe; this index answers with one hash, one 16-bit tag compare, and (only on
//! a tag hit) one byte compare. Lookups are exact, so results are identical to
//! the binary search they replace. Ordered prefix scans keep using the sorted
//! tables.
//!
//! # Examples
//!
//! ```
//! use meowpuut_core::context_index::PhraseIndex;
//!
//! let rows = ["你好", "世界", "喜歡"];
//! let key = |i: usize| rows[i].as_bytes();
//! let index = PhraseIndex::build(rows.len(), key);
//! assert_eq!(index.get("世界".as_bytes(), key), Some(1));
//! assert_eq!(index.get("喜歡".as_bytes(), key), Some(2));
//! assert_eq!(index.get("再見".as_bytes(), key), None);
//! assert_eq!(PhraseIndex::default().get(b"x", key), None);
//! assert!(index.heap_bytes() >= 16 * 6);
//! ```

const K: u64 = 0x9E37_79B9_7F4A_7C15;

/// Fast 64-bit hash for short UTF-8 phrases (8-byte chunks, multiply-rotate mix).
#[inline]
pub fn phrase_hash(bytes: &[u8]) -> u64 {
    let mut h = (bytes.len() as u64).wrapping_mul(K);
    let mut chunks = bytes.chunks_exact(8);
    for chunk in &mut chunks {
        let mut word = [0u8; 8];
        word.copy_from_slice(chunk);
        h = (h ^ u64::from_le_bytes(word)).wrapping_mul(K).rotate_left(29);
    }
    let rest = chunks.remainder();
    if !rest.is_empty() {
        let mut word = [0u8; 8];
        word[..rest.len()].copy_from_slice(rest);
        h = (h ^ u64::from_le_bytes(word)).wrapping_mul(K).rotate_left(29);
    }
    h ^= h >> 32;
    h = h.wrapping_mul(0xD6E8_FEB8_6659_FD93);
    h ^ (h >> 32)
}

/// Hash index from row bytes to row number.
///
/// SoA layout: `slots` holds `row + 1` (0 = empty) and `tags` holds the high
/// 16 bits of the hash, so a failed probe never touches row storage.
#[derive(Debug, Clone, Default)]
pub struct PhraseIndex {
    mask: usize,
    slots: Vec<u32>,
    tags: Vec<u16>,
}

impl PhraseIndex {
    /// Builds an index with load factor <= 0.5 over rows `0..len`.
    /// Rows are expected to be unique (the lexicon loader enforces this).
    pub fn build<'a>(len: usize, key: impl Fn(usize) -> &'a [u8]) -> Self {
        if len == 0 {
            return Self::default();
        }
        let capacity = (len * 2).next_power_of_two().max(16);
        let mask = capacity - 1;
        let mut slots = vec![0u32; capacity];
        let mut tags = vec![0u16; capacity];
        for row in 0..len {
            let h = phrase_hash(key(row));
            let mut pos = (h as usize) & mask;
            while slots[pos] != 0 {
                pos = (pos + 1) & mask;
            }
            slots[pos] = row as u32 + 1;
            tags[pos] = (h >> 48) as u16;
        }
        Self { mask, slots, tags }
    }

    /// Returns the row whose bytes equal `phrase`, if any.
    #[inline]
    pub fn get<'a>(&self, phrase: &[u8], key: impl Fn(usize) -> &'a [u8]) -> Option<usize> {
        if self.slots.is_empty() {
            return None;
        }
        let h = phrase_hash(phrase);
        let tag = (h >> 48) as u16;
        let mut pos = (h as usize) & self.mask;
        loop {
            let slot = self.slots[pos];
            if slot == 0 {
                return None;
            }
            if self.tags[pos] == tag {
                let row = slot as usize - 1;
                if key(row) == phrase {
                    return Some(row);
                }
            }
            pos = (pos + 1) & self.mask;
        }
    }

    /// Heap bytes held by the index (for memory budgeting on iOS).
    pub fn heap_bytes(&self) -> usize {
        self.slots.capacity() * 4 + self.tags.capacity() * 2
    }
}
