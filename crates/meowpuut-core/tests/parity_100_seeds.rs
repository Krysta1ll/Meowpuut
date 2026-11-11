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
    0xcbe6605169367c5c, 0x887275dd90ac4068, 0x20797df249e6ac8b, 0xd547eedb594cec1e,
    0x581caf0dc7fa58ea, 0x3ee16dcdd19c66a8, 0x181614389fc66227, 0xe85401222b296676,
    0x8231690d09345b6e, 0x1a18b00b530e709b, 0xa9bca21e3b5d029e, 0x544017f374505a3f,
    0x743a85bab06ff49b, 0xda7319c174affcdb, 0x878eec7ddf579fd5, 0x1e468554884d7404,
    0x0b6f0872603bb39d, 0xedaadbf434934337, 0x4766e9619dbfd937, 0xb26c960419733843,
    0xd1bfb3ab5cedd8c1, 0x8f6986c853947055, 0xda448b67a800fa9c, 0xb06f83de64dc3f3c,
    0x970d396607875f69, 0x0b9779e06b53d7b8, 0xa5abf1ebc862d483, 0xafbe855ddac764c9,
    0x9d95a3a19e0ffa0f, 0xa5ce35e48441f829, 0x640c02fad8ec3127, 0xc54036445e40b229,
    0x54c3dc3e2172ab0b, 0x14251378b8b98f53, 0x970a7bbf831433d5, 0x6307cc2bbc2b594b,
    0x5e52b6c24c40b18f, 0x8e4a942d83e35522, 0x6606a3494fd083e3, 0x8c03d33e515ce078,
    0x4b39db6286d76b62, 0x0704a85d79df38c0, 0x598f2ff6b4f89030, 0x7fac119d1df981e7,
    0xd26cabcf170b812d, 0x4bbcbfebb6205b26, 0xbf7697894d9d37c5, 0x03c67096ea0ee52c,
    0x554f32f5d0a17d21, 0x811ef5028d5aec00, 0x29fcfb70ef87b133, 0xe96bab46a4b33f24,
    0xb3cd2028c11796c6, 0xa802bb45f65c60f4, 0xa597e6df8dea80d3, 0x7a90c8277ea9d72a,
    0xccb093bd7fbb3ca2, 0xa4598eab229b8bef, 0x4fb6d12e6f47572f, 0xcf0f4480565b43db,
    0x7c5f7f159019ce02, 0x4997cb55c58879a6, 0x00b2e411b23c38c6, 0x870182d0fa116c37,
    0x5434717dccf27488, 0xb0f69a9799f5ef5b, 0x035314066ab75b95, 0x6158cba65690c624,
    0x15e0ea50659a6cf1, 0x6be59e526cd309e8, 0x43660b095397a2d2, 0xe1a0893cbe1c90d0,
    0x2de0290e5660bc82, 0xfea5525570d7f793, 0x81c8a34e5ef5f7ed, 0x1f00bae43f69fd1b,
    0xc7de21659f1f8f66, 0x9996a9ee99d6432e, 0x23043fa2fab6a801, 0x99dcd53244619f89,
    0x34777ceb108aa3fd, 0xe3e7af497b211aa7, 0x454a52c386da796a, 0x7e2cf59c13ad66d7,
    0x0788b157bccac022, 0xb02f9a52c73a970a, 0x892b3ce132d454fb, 0x86c407dd309a682c,
    0x507218a24435997b, 0x2eab57e33d3cbf69, 0xe2eabe5614200986, 0xb4a84dfb314d1581,
    0x5d8c77ab17f6376c, 0x43701dc676bc87fa, 0xc5da052243fa19ad, 0x0c11c73eb960f044,
    0x190d73341f22e4df, 0x8f158071914199fd, 0x5a60f7752cce0f5b, 0x69c6df161afdf48e,
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
