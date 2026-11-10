#![forbid(unsafe_code)]
//! 100-seed deterministic simulation parity test & zero-heap-allocation verification
//! against the original C++ reference engine (`MeowpuutCore.cpp`).

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;

use meowpuut_core::{Lexicon, Session};

fn splitmix64(state: &mut u64) -> u64 {
    *state = state.wrapping_add(0x9e3779b97f4a7c15);
    let mut z = *state;
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d049bb133111eb);
    z ^ (z >> 31)
}

const PINYIN_POOL: [&str; 50] = [
    "nihao", "woxihuanni", "jintiantianqihenhao", "zhongguoren", "xiansheng",
    "pengyou", "shijieheping", "woxihuannidenekohenkeai", "nihaoshijie",
    "womenshihaopengyou", "zhuyinshurufa", "diannao", "chengxuyuan", "kaixin",
    "xiexieni", "mingtianjian", "bukeqi", "duibuqi", "meiguanxi", "zaoshanghao",
    "wanan", "woaini", "taibei", "dongjing", "watashixihuanneko", "sakurahenmei",
    "ni3hao3", "xian'an", "zhong1wen2", "ying1wen2", "ri4wen2", "fa3wen2",
    "nihaom", "woxihuanb", "womens", "jintianw", "pengyoum", "xianshengd",
    "nh", "wxhn", "jttqhh", "zgr", "xs", "py", "sjhp",
    "nihaonihao", "nihaoa", "shurufaceshi", "zheshiyigeceshi", "qingwen",
];

const ZHUYIN_POOL: [&str; 19] = [
    "su3cl3", "ji3vu3c0u3", "rupwu0wu0fu4cpcl3", "5j/eji6bp6", "v0g/ ",
    "q/7u.6", "g4r,4ck6qu/6", "su3cl3g4r,4", "ji3mp3g4cl3q/7u.6",
    "1j6e06", "vm,4vm,4su3", "au/6wu0ru04", "1j4d4f3", "ao6ej0uv7",
    "su3cl3a87", "5j/eji6", "t/6vu4m06", "d9vup ", "2u04sao3",
];

const CONTEXT_POOL: [&str; 10] = [
    "你", "我", "我們", "今天", "喜歡", "中國", "台灣", "世界", "朋友", "",
];

const GOLDEN_100_SEEDS: [u64; 100] = [
    0xb7a9d8fb4b990f39, 0xb3cbff46a49acf68, 0xd78b9f0b12943513, 0xdd9029d8550f6f97,
    0x403d2275364ff08b, 0x4f1a14fe675329cd, 0xdc7fdb58fa8551c7, 0xb05995a68434a18b,
    0x5ce1d7d47e5840c1, 0x8ca19ee140a1ea93, 0x59ce365fe440cda6, 0x14c1e045d37d9bd0,
    0xef144b4c30d25575, 0xae39be696d136f38, 0x2cce49ad6207a5bc, 0x8c2c94e0289afdf1,
    0x346279c4eefb89f6, 0xd32770519a6d42df, 0x06a797c7c92430ea, 0xfd4ce9ba1451e15f,
    0xef34b50ade062963, 0xb58136afe1d657cd, 0x80fe3746a4d169b9, 0xd5e795292ef9fc78,
    0x2d8c7a561c63590e, 0x062afbd44b8909f2, 0x715d9e5a6eff2324, 0xabcddbbf083e77b1,
    0xe4c6e01fb82cb8b7, 0xd989baff6375dfbc, 0x9c0544eea0a05fe0, 0x3a0e39532d4db62e,
    0x7520d92b8f703f45, 0x3c412161796ed34d, 0x571f686edf46600a, 0xc57912e812b6400d,
    0x8b034f6fc45df24c, 0xf7031b715e433e7b, 0x9eee0cec897b1238, 0x6664a0d7ec9633b5,
    0xf5af015131cc6950, 0xc452fad67911491a, 0x1f88acfc1ba59d14, 0xd479aecc9dbf0ac4,
    0xfda24e457550f4c0, 0xbbce88b2224704c5, 0x1bb95cca34e97b8d, 0x22ef1c4accc30847,
    0xd38a3d42ebe13304, 0x9eafc86b690d9b7e, 0x0f16cf1b28e5bd59, 0xacd63824ef0544c4,
    0xeba8bf3ff8f6eed2, 0x8a66c597b2fd1c79, 0x8cbaa7ec143522d8, 0x052f7817aeb2915a,
    0x6fbcafc62643ca08, 0x235fcc33614a0c41, 0x66fe9f816c98b6a5, 0x42a272081f0baf7f,
    0xee91f3614b55babf, 0xc4ed43029923bf26, 0x7f5c9e1203a04cdc, 0x92e849107b700fa6,
    0xa1e722c3da14df11, 0xbf12a02b1d16651a, 0x2b9d414dddf4fefe, 0x111e877745efd96a,
    0x6853c1c3ecb2eb21, 0x76247c6dda13bd7e, 0x2833f90b138ab56d, 0xf7adfd6e00b3d20f,
    0x0899f051071f9044, 0x921dd4d6448c2462, 0x12c897b660b3f582, 0x78c7772f2945270a,
    0x0186df2c570e9260, 0xdae737bcbc89e76e, 0xdace32a3a6be38a7, 0xa57b33366d6b633a,
    0xea6602b8a2999f0a, 0xe858e5db078a31ea, 0x433a415d8d5dca51, 0x6c6df79cd2cfa872,
    0xc553a921869071f1, 0xa6689e90dd36f9c3, 0xc2a2864440b4fdb8, 0xdb6593bcb2da1201,
    0x48db28e0a0a87870, 0x36d7b9f9f7bb81f0, 0x951f66009a3b19d8, 0xc3974507d77a4513,
    0x2eb1460b34ea4db9, 0x1373847a55d65633, 0xe781388bca85bb7e, 0xe2c5242bda1260fb,
    0x65719a4dedb5ff43, 0x26dbbe50bdc907c9, 0x7994a4a310dd9be5, 0x701c9303e6832865,
];

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_path_buf()
}

fn mix_bytes(hash: &mut u64, data: &[u8]) {
    for &b in data {
        *hash ^= u64::from(b);
        *hash = hash.wrapping_mul(1099511628211);
    }
}

fn mix_str(hash: &mut u64, s: &str) {
    mix_bytes(hash, s.as_bytes());
    mix_bytes(hash, &[0xff]);
}

fn mix_u64(hash: &mut u64, v: u64) {
    mix_bytes(hash, &v.to_le_bytes());
}

fn run_seed(session: &mut Session, seed: usize, is_pinyin: bool) -> u64 {
    let mut rng = seed as u64;
    let mut hash = 14695981039346656037u64;

    for _step in 0..50 {
        let r = splitmix64(&mut rng);
        let op = r % 100;
        let mut handled = false;

        if op < 55 {
            let phrase = if is_pinyin {
                PINYIN_POOL[((r >> 8) as usize) % PINYIN_POOL.len()]
            } else {
                ZHUYIN_POOL[((r >> 8) as usize) % ZHUYIN_POOL.len()]
            };
            let take = 1 + (((r >> 16) as usize) % phrase.len());
            for &b in &phrase.as_bytes()[..take] {
                handled = session.key(i32::from(b));
            }
        } else if op < 65 {
            handled = session.key(0xff08);
        } else if op < 75 {
            let cnt = session.candidate_count();
            if cnt > 0 {
                let idx = ((r >> 8) as usize) % cnt;
                handled = session.choose(idx);
            }
        } else if op < 82 {
            handled = session.key(if (r & 1) != 0 { 0xff56 } else { 0xff55 });
        } else if op < 88 {
            session.set_simplified(((r >> 8) & 1) != 0);
            handled = true;
        } else if op < 94 {
            let ctx = CONTEXT_POOL[((r >> 8) as usize) % CONTEXT_POOL.len()];
            session.set_segment_context(ctx);
            handled = true;
        } else if op < 97 {
            handled = session.key(0xff0d);
        } else {
            handled = session.key(0xff1b);
        }

        mix_u64(&mut hash, u64::from(handled));
        mix_str(&mut hash, session.input());
        mix_str(&mut hash, session.preedit());
        mix_str(&mut hash, session.committed());
        mix_str(&mut hash, session.segment_context());
        mix_u64(&mut hash, session.page() as u64);

        let cnt = session.candidate_count();
        mix_u64(&mut hash, cnt as u64);
        for i in 0..cnt {
            mix_str(&mut hash, session.candidate(i));
            mix_u64(&mut hash, session.candidate_consumed(i) as u64);
            let info_opt = session.candidate_info(i);
            mix_u64(&mut hash, u64::from(info_opt.is_some()));
            let info = info_opt.unwrap();
            mix_u64(&mut hash, u64::from(info.kind));
            mix_u64(&mut hash, u64::from(info.score_kind));
            mix_u64(&mut hash, info.consumed_bytes);
            mix_u64(&mut hash, info.remaining_offset_bytes);
            let score_str = format!("{:.9}", info.score);
            mix_str(&mut hash, &score_str);
        }

        if is_pinyin {
            let q = PINYIN_POOL[((r >> 24) as usize) % PINYIN_POOL.len()];
            let simp = ((r >> 32) & 1) != 0;
            let mp = session.mixed_pinyin(q, simp);
            mix_str(&mut hash, mp);
            let (pref, pref_cons) = session.mixed_pinyin_prefix(q, simp);
            mix_str(&mut hash, pref);
            mix_u64(&mut hash, pref_cons as u64);
        }
        session.clear_commit();
    }
    hash
}

#[test]
fn parity_100_random_seeds_zero_diff_and_zero_hot_allocs() {
    let root = repo_root();
    let py_tsv = fs::read_to_string(root.join("iOS/Generated/native-pinyin.tsv"))
        .expect("read native-pinyin.tsv");
    let zy_tsv = fs::read_to_string(root.join("iOS/Generated/native-zhuyin.tsv"))
        .expect("read native-zhuyin.tsv");

    let py_lex = Arc::new(Lexicon::load(&py_tsv).expect("load pinyin lexicon"));
    let zy_lex = Arc::new(Lexicon::load(&zy_tsv).expect("load zhuyin lexicon"));
    let base_bytes = fs::read(root.join("iOS/Generated/mimo_base.bin")).expect("read mimo_base.bin");
    let py_base_lex = Arc::new(Lexicon::load_from_base(&base_bytes, true).expect("load pinyin from mimo_base.bin"));
    let zy_base_lex = Arc::new(Lexicon::load_from_base(&base_bytes, false).expect("load zhuyin from mimo_base.bin"));
    assert_eq!(py_base_lex.words.len(), py_lex.words.len());
    assert_eq!(py_base_lex.words.flags, py_lex.words.flags);
    assert_eq!(zy_base_lex.words.len(), zy_lex.words.len());
    assert_eq!(zy_base_lex.words.flags, zy_lex.words.flags);

    let mut py_reused = Session::new(Arc::clone(&py_lex));
    let mut py_bin_session = Session::new(Arc::clone(&py_base_lex));
    for sess in [&mut py_reused, &mut py_bin_session] {
        sess.add_japanese_word("watashi", "私", 5500);
        sess.add_japanese_word("neko", "猫", 5200);
        sess.add_japanese_word("kawaii", "可愛い", 5300);
        sess.add_japanese_word("arigatou", "ありがとう", 5600);
        sess.add_japanese_word("konnichiwa", "こんにちは", 5400);
        sess.add_japanese_word("sakura", "桜", 4800);
        sess.add_japanese_word("tokyo", "東京", 5100);
        sess.add_japanese_word("sushi", "寿司", 4900);
        sess.add_japanese_word("au", "会う", 4200);
        sess.add_japanese_word("ai", "愛", 3500);
    }

    let mut zy_reused = Session::new(Arc::clone(&zy_lex));
    let mut zy_bin_session = Session::new(Arc::clone(&zy_base_lex));

    for (seed, &expected_hash) in GOLDEN_100_SEEDS.iter().enumerate() {
        let is_pinyin = seed % 2 == 0;
        let (actual_hash, bin_hash) = if is_pinyin {
            py_reused.clear();
            py_reused.set_simplified(false);
            py_bin_session.clear();
            py_bin_session.set_simplified(false);
            (run_seed(&mut py_reused, seed, true), run_seed(&mut py_bin_session, seed, true))
        } else {
            zy_reused.clear();
            zy_reused.set_simplified(false);
            zy_bin_session.clear();
            zy_bin_session.set_simplified(false);
            (run_seed(&mut zy_reused, seed, false), run_seed(&mut zy_bin_session, seed, false))
        };

        assert_eq!(
            actual_hash, bin_hash,
            "Binary vs TSV mismatch at seed {seed}: tsv={actual_hash:016x}, bin={bin_hash:016x}"
        );
        assert_eq!(
            actual_hash, expected_hash,
            "Mismatch at seed {seed}: actual={actual_hash:016x}, expected={expected_hash:016x}"
        );
    }

    // Verify zero heap allocations across hot simulation loops once warmed up:
    let py_cap_before = py_reused.capacity_signature();
    let zy_cap_before = zy_reused.capacity_signature();
    for seed in 0..20 {
        py_reused.clear();
        py_reused.set_simplified(false);
        let _ = run_seed(&mut py_reused, seed * 2, true);
        zy_reused.clear();
        zy_reused.set_simplified(false);
        let _ = run_seed(&mut zy_reused, seed * 2 + 1, false);
    }
    assert_eq!(
        py_reused.capacity_signature(),
        py_cap_before,
        "Pinyin hot simulation loop grew heap capacity"
    );
    assert_eq!(
        zy_reused.capacity_signature(),
        zy_cap_before,
        "Zhuyin hot simulation loop grew heap capacity"
    );
}
