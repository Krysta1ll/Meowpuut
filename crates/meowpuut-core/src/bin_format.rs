#![forbid(unsafe_code)]
//! Zero-parse binary lexicon format (`MIMO-BIN-1` and `MIMO-BASE-1` container
//! for `mimo_base.bin`) storing pre-validated SoA columns and `i16` Log-Probability
//! weights for near-instant (`< 2 ms`) `mmap` startup on iOS and macOS.
//!
//! # Examples
//!
//! ```
//! use meowpuut_core::Lexicon;
//!
//! let tsv = concat!(
//!     "MEOWPUUT-PINYIN-1\n",
//!     "hao\thao3\t好\t好\t4800\n",
//!     "ni\tni3\t你\t你\t5000\n",
//!     "nihao\tni3hao3\t你好\t你好\t5400\n",
//!     "# context\n",
//!     "你好\t600\n",
//! );
//! let lex = Lexicon::load(tsv).expect("valid tsv");
//! let bin = lex.to_binary();
//! let loaded = Lexicon::load_bytes(&bin).expect("valid MIMO-BIN-1");
//! assert_eq!(loaded.words.len(), 3);
//! assert_eq!(loaded.range("nihao"), (2, 3));
//! assert_eq!(loaded.context_frequency("你好".as_bytes()), Some(600));
//!
//! let zhuyin_tsv = "MEOWPUUT-ZHUYIN-1\nsu\tsu3\t你\t你\t5000\n";
//! let zhuyin_bin = Lexicon::load(zhuyin_tsv).unwrap().to_binary();
//! let base = Lexicon::pack_base(&bin, &zhuyin_bin);
//! let py_from_base = Lexicon::load_from_base(&base, true).unwrap();
//! let zy_from_base = Lexicon::load_from_base(&base, false).unwrap();
//! assert!(py_from_base.pinyin);
//! assert!(!zy_from_base.pinyin);
//! ```

use crate::lexicon::Lexicon;
use crate::soa::{BucketTableSoA, ContextTableSoA, WordTableSoA};

/// Magic header for a single-mode `MIMO-BIN-1` SoA lexicon image (12 bytes).
pub const MIMO_BIN_MAGIC: &[u8; 12] = b"MIMO-BIN-1\0\0";

/// Magic header for a dual-mode `MIMO-BASE-1` (`mimo_base.bin`) container (12 bytes).
pub const MIMO_BASE_MAGIC: &[u8; 12] = b"MIMO-BASE-1\0";

const HEADER_LEN: usize = 32;
const WORD_ROW_BYTES: usize = 21;
const BUCKET_ROW_BYTES: usize = 14;
const CONTEXT_ROW_BYTES: usize = 8;

fn read_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    let slice = bytes.get(offset..offset + 4)?;
    let mut arr = [0u8; 4];
    arr.copy_from_slice(slice);
    Some(u32::from_le_bytes(arr))
}

fn read_u32_vec(bytes: &mut &[u8], count: usize) -> Option<Vec<u32>> {
    let byte_len = count.checked_mul(4)?;
    let section = bytes.get(..byte_len)?;
    *bytes = &bytes[byte_len..];
    let mut out = Vec::with_capacity(count);
    for chunk in section.chunks_exact(4) {
        let mut arr = [0u8; 4];
        arr.copy_from_slice(chunk);
        out.push(u32::from_le_bytes(arr));
    }
    Some(out)
}

fn read_u16_vec(bytes: &mut &[u8], count: usize) -> Option<Vec<u16>> {
    let byte_len = count.checked_mul(2)?;
    let section = bytes.get(..byte_len)?;
    *bytes = &bytes[byte_len..];
    let mut out = Vec::with_capacity(count);
    for chunk in section.chunks_exact(2) {
        let mut arr = [0u8; 2];
        arr.copy_from_slice(chunk);
        out.push(u16::from_le_bytes(arr));
    }
    Some(out)
}

fn read_i16_as_i32_vec(bytes: &mut &[u8], count: usize) -> Option<Vec<i32>> {
    let byte_len = count.checked_mul(2)?;
    let section = bytes.get(..byte_len)?;
    *bytes = &bytes[byte_len..];
    let mut out = Vec::with_capacity(count);
    for chunk in section.chunks_exact(2) {
        let mut arr = [0u8; 2];
        arr.copy_from_slice(chunk);
        out.push(i32::from(i16::from_le_bytes(arr)));
    }
    Some(out)
}

fn push_u32_slice(out: &mut Vec<u8>, values: &[u32]) {
    for &v in values {
        out.extend_from_slice(&v.to_le_bytes());
    }
}

fn push_u16_slice(out: &mut Vec<u8>, values: &[u16]) {
    for &v in values {
        out.extend_from_slice(&v.to_le_bytes());
    }
}

fn push_i32_as_i16_slice(out: &mut Vec<u8>, values: &[i32]) {
    for &v in values {
        let clamped = v.clamp(i32::from(i16::MIN), i32::from(i16::MAX)) as i16;
        out.extend_from_slice(&clamped.to_le_bytes());
    }
}

impl Lexicon {
    /// Auto-detects `MIMO-BIN-1` binary format or `MEOWPUUT-*-1` UTF-8 TSV text.
    pub fn load_bytes(bytes: &[u8]) -> Option<Self> {
        if bytes.starts_with(MIMO_BIN_MAGIC) {
            Self::load_binary(bytes)
        } else {
            let text = std::str::from_utf8(bytes).ok()?;
            Self::load(text)
        }
    }

    /// Serializes this `Lexicon` into a compact `MIMO-BIN-1` binary image with
    /// `i16` Log-Probability weights and precomputed `FLAG_VERIFIED_SPELLING` bits.
    pub fn to_binary(&self) -> Vec<u8> {
        let storage_len = self.storage.len();
        let words_len = self.words.len();
        let buckets_len = self.buckets.len();
        let context_len = self.context.len();
        let total = HEADER_LEN
            + storage_len
            + words_len * WORD_ROW_BYTES
            + buckets_len * BUCKET_ROW_BYTES
            + context_len * CONTEXT_ROW_BYTES;
        let mut out = Vec::with_capacity(total);
        out.extend_from_slice(MIMO_BIN_MAGIC);
        let meta = u32::from(self.pinyin) | ((self.longest.min(255) as u32) << 8);
        out.extend_from_slice(&meta.to_le_bytes());
        out.extend_from_slice(&(storage_len as u32).to_le_bytes());
        out.extend_from_slice(&(words_len as u32).to_le_bytes());
        out.extend_from_slice(&(buckets_len as u32).to_le_bytes());
        out.extend_from_slice(&(context_len as u32).to_le_bytes());

        out.extend_from_slice(self.storage.as_bytes());

        push_u32_slice(&mut out, &self.words.reading_offset);
        push_u16_slice(&mut out, &self.words.reading_len);
        push_u32_slice(&mut out, &self.words.hant_offset);
        push_u16_slice(&mut out, &self.words.hant_len);
        push_u32_slice(&mut out, &self.words.hans_offset);
        push_u16_slice(&mut out, &self.words.hans_len);
        push_i32_as_i16_slice(&mut out, &self.words.weight);
        out.extend_from_slice(&self.words.flags);

        push_u32_slice(&mut out, &self.buckets.key_offset);
        push_u16_slice(&mut out, &self.buckets.key_len);
        push_u32_slice(&mut out, &self.buckets.word_start);
        push_u32_slice(&mut out, &self.buckets.word_end);

        push_u32_slice(&mut out, &self.context.phrase_offset);
        push_u16_slice(&mut out, &self.context.phrase_len);
        push_i32_as_i16_slice(&mut out, &self.context.frequency);
        out
    }

    /// Loads a `MIMO-BIN-1` binary image directly into SoA tables without text parsing.
    pub fn load_binary(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < HEADER_LEN || &bytes[..12] != MIMO_BIN_MAGIC {
            return None;
        }
        let meta = read_u32(bytes, 12)?;
        let pinyin = (meta & 1) != 0;
        let longest = ((meta >> 8) & 0xFF) as usize;
        let storage_len = read_u32(bytes, 16)? as usize;
        let words_len = read_u32(bytes, 20)? as usize;
        let buckets_len = read_u32(bytes, 24)? as usize;
        let context_len = read_u32(bytes, 28)? as usize;
        if words_len == 0 || buckets_len == 0 {
            return None;
        }
        let expected_len = HEADER_LEN
            .checked_add(storage_len)?
            .checked_add(words_len.checked_mul(WORD_ROW_BYTES)?)?
            .checked_add(buckets_len.checked_mul(BUCKET_ROW_BYTES)?)?
            .checked_add(context_len.checked_mul(CONTEXT_ROW_BYTES)?)?;
        if bytes.len() != expected_len {
            return None;
        }

        let mut cursor = &bytes[HEADER_LEN..];
        let storage_str = std::str::from_utf8(&cursor[..storage_len]).ok()?;
        let storage = String::from(storage_str);
        cursor = &cursor[storage_len..];

        let reading_offset = read_u32_vec(&mut cursor, words_len)?;
        let reading_len = read_u16_vec(&mut cursor, words_len)?;
        let hant_offset = read_u32_vec(&mut cursor, words_len)?;
        let hant_len = read_u16_vec(&mut cursor, words_len)?;
        let hans_offset = read_u32_vec(&mut cursor, words_len)?;
        let hans_len = read_u16_vec(&mut cursor, words_len)?;
        let weight = read_i16_as_i32_vec(&mut cursor, words_len)?;
        let flags = cursor.get(..words_len)?.to_vec();
        cursor = &cursor[words_len..];

        let key_offset = read_u32_vec(&mut cursor, buckets_len)?;
        let key_len = read_u16_vec(&mut cursor, buckets_len)?;
        let word_start = read_u32_vec(&mut cursor, buckets_len)?;
        let word_end = read_u32_vec(&mut cursor, buckets_len)?;

        let phrase_offset = read_u32_vec(&mut cursor, context_len)?;
        let phrase_len = read_u16_vec(&mut cursor, context_len)?;
        let frequency = read_i16_as_i32_vec(&mut cursor, context_len)?;

        for i in 0..words_len {
            let r_s = reading_offset[i] as usize;
            let r_e = r_s.checked_add(reading_len[i] as usize)?;
            let t_s = hant_offset[i] as usize;
            let t_e = t_s.checked_add(hant_len[i] as usize)?;
            let s_s = hans_offset[i] as usize;
            let s_e = s_s.checked_add(hans_len[i] as usize)?;
            if r_s == r_e
                || storage.get(r_s..r_e).is_none()
                || storage.get(t_s..t_e).is_none()
                || storage.get(s_s..s_e).is_none()
            {
                return None;
            }
        }
        for i in 0..buckets_len {
            let k_s = key_offset[i] as usize;
            let k_e = k_s.checked_add(key_len[i] as usize)?;
            let ws = word_start[i] as usize;
            let we = word_end[i] as usize;
            if k_s == k_e || storage.get(k_s..k_e).is_none() || ws >= we || we > words_len {
                return None;
            }
        }
        for i in 0..context_len {
            let p_s = phrase_offset[i] as usize;
            let p_e = p_s.checked_add(phrase_len[i] as usize)?;
            if p_s == p_e || storage.get(p_s..p_e).is_none() {
                return None;
            }
        }

        let words = WordTableSoA {
            reading_offset,
            reading_len,
            hant_offset,
            hant_len,
            hans_offset,
            hans_len,
            weight,
            flags,
        };
        let buckets = BucketTableSoA { key_offset, key_len, word_start, word_end };
        let context = ContextTableSoA { phrase_offset, phrase_len, frequency };
        Self::from_soa_parts(storage, words, buckets, context, longest, pinyin, true)
    }

    /// Packs Pinyin and Zhuyin `MIMO-BIN-1` blobs into a 64-byte aligned `MIMO-BASE-1`
    /// container (`mimo_base.bin`).
    pub fn pack_base(pinyin_bin: &[u8], zhuyin_bin: &[u8]) -> Vec<u8> {
        let py_offset = 64usize;
        let py_len = pinyin_bin.len();
        let after_py = py_offset + py_len;
        let zy_offset = (after_py + 63) & !63;
        let zy_len = zhuyin_bin.len();
        let mut out = Vec::with_capacity(zy_offset + zy_len);
        out.extend_from_slice(MIMO_BASE_MAGIC);
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&(py_offset as u32).to_le_bytes());
        out.extend_from_slice(&(py_len as u32).to_le_bytes());
        out.extend_from_slice(&(zy_offset as u32).to_le_bytes());
        out.extend_from_slice(&(zy_len as u32).to_le_bytes());
        out.resize(py_offset, 0);
        out.extend_from_slice(pinyin_bin);
        out.resize(zy_offset, 0);
        out.extend_from_slice(zhuyin_bin);
        out
    }

    /// Extracts and loads either the Pinyin or Zhuyin `Lexicon` from a mapped
    /// `MIMO-BASE-1` (`mimo_base.bin`) container.
    pub fn load_from_base(base_bytes: &[u8], pinyin: bool) -> Option<Self> {
        if base_bytes.len() < HEADER_LEN || &base_bytes[..12] != MIMO_BASE_MAGIC {
            return None;
        }
        let version = read_u32(base_bytes, 12)?;
        if version != 1 {
            return None;
        }
        let (offset, len) = if pinyin {
            (read_u32(base_bytes, 16)? as usize, read_u32(base_bytes, 20)? as usize)
        } else {
            (read_u32(base_bytes, 24)? as usize, read_u32(base_bytes, 28)? as usize)
        };
        let end = offset.checked_add(len)?;
        let slice = base_bytes.get(offset..end)?;
        Self::load_binary(slice)
    }
}
