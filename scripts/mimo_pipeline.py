"""Offline mimo lexicon pipeline: builds unified mimo-tc.json and mimo-sc.json from open-source corpora."""
import collections
import hashlib
import json
import math
import pathlib
import re
import subprocess
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parent.parent
MCBOPOMOFO_BASE = "https://raw.githubusercontent.com/openvanilla/McBopomofoData/df084385e546287fc072dfce486d46f102ef9bd4/Data"
OPENCC_BASE = "https://raw.githubusercontent.com/BYVoid/OpenCC/master"
JIEBA_BASE = "https://raw.githubusercontent.com/fxsjy/jieba/master"
RIME_ICE_BASE = "https://raw.githubusercontent.com/iDvel/rime-ice/main"
PINYIN_PRO_BASE = "https://raw.githubusercontent.com/zh-lx/pinyin-pro/0785234b4dc0fa3ea9f244e3d77703b2df6a9db4"

SMOOTHING_FLOOR_I16 = 2200
MAX_LOG_PROB_I16 = 10000
PARTICLE_PHRASE_MIN_COUNT = 1000
# Sentence-final particles: a phrase ending in one is a whole chat utterance (知道了, 在吗, 对啊).
_FINAL_PARTICLES = set("了吗呢吧啊呀哇哦喔呗")
_NON_LEXICAL_CHARS = set("的了着过吗呢吧啊呀哇哦喔呗们得地之这那哪一二三四五六七八九十百千万亿你我他她它很更最太都也就还又再才只")


def clamp_i16_weight(weight: int | float) -> int:
    """Clamp calibrated log-probability weight into the positive i16 engine range [1, 10000]."""
    return max(1, min(MAX_LOG_PROB_I16, round(weight)))


def log_prob_weight(raw_freq: float, scale: float = 360.0, smoothing: int = 1) -> int:
    """Map raw corpus count/probability onto the unified i16 Log-Probability scale."""
    if raw_freq <= 0:
        return max(1, smoothing)
    return clamp_i16_weight(scale * math.log1p(raw_freq))


def _is_cjk_phrase(text: str, min_len: int = 2, max_len: int = 4) -> bool:
    return min_len <= len(text) <= max_len and all(0x3400 <= ord(ch) <= 0x9FFF for ch in text)


def _download_text(url: str) -> str:
    return subprocess.run(
        ["curl", "-fsSL", "--max-time", "90", url],
        text=True,
        capture_output=True,
        check=True,
    ).stdout


def numbered_pinyin(syllable: str) -> str:
    """Convert tone-marked UTF-8 pinyin syllable into tone-numbered ASCII (1..5)."""
    result, tone = "", "5"
    for char in unicodedata.normalize("NFD", syllable.lower()):
        if char in {"\u0304", "\u0301", "\u030c", "\u0300"}:
            tone = {"\u0304": "1", "\u0301": "2", "\u030c": "3", "\u0300": "4"}[char]
        elif char == "\u0308" and result.endswith("u"):
            result = result[:-1] + "v"
        elif char == "\u0302" and result.endswith("e"):
            result += "h"
        else:
            result += char
    return result + tone


def _parse_rime_yaml(yaml_text: str) -> list[tuple[str, str, int]]:
    rows: list[tuple[str, str, int]] = []
    in_body = False
    for line in yaml_text.splitlines():
        if not in_body:
            if line.strip() == "...":
                in_body = True
            continue
        if not line or line.startswith("#") or "\t" not in line:
            continue
        parts = line.split("\t")
        if len(parts) < 3 or not parts[2].isdigit():
            continue
        rows.append((parts[0], parts[1].strip(), int(parts[2])))
    return rows


def load_mimo_tc_dataset() -> dict:
    """Load the consolidated DataSources/mimo-tc/mimo-tc.json dataset and verify its checksum."""
    tc_dir = ROOT / "DataSources/mimo-tc"
    manifest = json.loads((tc_dir / "SOURCE.json").read_text())
    for name, checksum in manifest["files"].items():
        if hashlib.sha256((tc_dir / name).read_bytes()).hexdigest() != checksum:
            raise ValueError(f"mimo-tc data changed: {name}; put edits in Resources/Lexicon instead")
    return json.loads((tc_dir / "mimo-tc.json").read_text())


def load_mimo_sc_dataset() -> dict:
    """Load the consolidated DataSources/mimo-sc/mimo-sc.json dataset and verify its checksum."""
    sc_dir = ROOT / "DataSources/mimo-sc"
    manifest = json.loads((sc_dir / "SOURCE.json").read_text())
    for name, checksum in manifest["files"].items():
        if hashlib.sha256((sc_dir / name).read_bytes()).hexdigest() != checksum:
            raise ValueError(f"mimo-sc source checksum mismatch: {name}")
    return json.loads((sc_dir / "mimo-sc.json").read_text())


def fetch_and_build_mimo_datasets(converter: str, encode_fn) -> dict:
    """Download McBopomofo, OpenCC, jieba, rime-ice, and pinyin-pro to rebuild mimo-tc.json & mimo-sc.json."""
    tc_dir = ROOT / "DataSources/mimo-tc"
    sc_dir = ROOT / "DataSources/mimo-sc"

    bpmf_base = _download_text(f"{MCBOPOMOFO_BASE}/BPMFBase.txt")
    bpmf_mappings = _download_text(f"{MCBOPOMOFO_BASE}/BPMFMappings.txt")
    phrase_occ = _download_text(f"{MCBOPOMOFO_BASE}/phrase.occ")
    het1 = _download_text(f"{MCBOPOMOFO_BASE}/heterophony1.list")
    het2 = _download_text(f"{MCBOPOMOFO_BASE}/heterophony2.list")
    het3 = _download_text(f"{MCBOPOMOFO_BASE}/heterophony3.list")
    st_text = _download_text(f"{OPENCC_BASE}/data/dictionary/STPhrases.txt")
    tw_text = _download_text(f"{OPENCC_BASE}/data/dictionary/TWPhrases.txt")
    hk_text = _download_text(f"{OPENCC_BASE}/data/dictionary/HKPhrases.txt")
    opencc_license = _download_text(f"{OPENCC_BASE}/LICENSE")
    jieba_license = _download_text(f"{JIEBA_BASE}/LICENSE")
    jieba_big = _download_text(f"{JIEBA_BASE}/extra_dict/dict.txt.big")
    rime_license = _download_text(f"{RIME_ICE_BASE}/LICENSE")
    rime_8105 = _download_text(f"{RIME_ICE_BASE}/cn_dicts/8105.dict.yaml")
    rime_base = _download_text(f"{RIME_ICE_BASE}/cn_dicts/base.dict.yaml")
    complete_raw = json.loads(_download_text(f"{PINYIN_PRO_BASE}/packages/data/json/complete.json"))

    (tc_dir / "OPENCC-LICENSE.txt").write_text(opencc_license)
    (tc_dir / "JIEBA-LICENSE.txt").write_text(jieba_license)
    (sc_dir / "RIME-ICE-LICENSE.txt").write_text(rime_license)

    freq = {f[0]: int(f[1]) for ln in phrase_occ.splitlines() if len(f := ln.split()) == 2}
    mapping, entries, skipped_tc = {}, {}, 0
    for line in bpmf_base.splitlines():
        fields = line.split()
        if len(fields) < 4:
            continue
        word, bpmf, py = fields[:3]
        py = py.lower().replace("ü", "v")
        if not py[-1:].isdigit():
            py += "1"
        if bpmf.startswith("ㄝ"):
            py = "eh" + py[-1]
        if encode_fn(py) is None:
            skipped_tc += 1
            continue
        mapping.setdefault(bpmf, py)
        if all(ord(c) >= 0x3400 for c in word):
            entries[(word, py)] = max(1, round(700 * math.log1p(freq.get(word, 0))))
    for line in bpmf_mappings.splitlines():
        fields = line.split()
        if len(fields) < 2:
            continue
        word, syls = fields[0], fields[1:]
        if len(syls) > 12 or len(word) != len(syls) or any(p not in mapping for p in syls):
            skipped_tc += 1
            continue
        entries[(word, " ".join(mapping[p] for p in syls))] = max(1, round(700 * math.log1p(freq.get(word, 0))))
    tiers = [collections.defaultdict(set) for _ in range(3)]
    for tier, text_src in zip(tiers, (het1, het2, het3)):
        for line in text_src.splitlines():
            if len(f := line.split()) == 2 and f[1] in mapping:
                tier[f[0]].add(mapping[f[1]])
    for (word, code) in list(entries):
        if len(word) == 1 and word in tiers[0] and code not in tiers[0][word]:
            w = entries[(word, code)]
            entries[(word, code)] = max(1, round(w * 0.72) if code in tiers[1][word] else round(w * 0.45) if code in tiers[2][word] else w // 8)
    words_sorted = sorted({w for w, _ in entries})
    conv = subprocess.run([converter], input="\n".join(words_sorted) + "\n", text=True, capture_output=True, check=True).stdout.splitlines()
    hans_map = dict(zip(words_sorted, conv))
    tc_entries = [[w, hans_map[w], c, wt] for (w, c), wt in sorted(entries.items(), key=lambda x: (x[0][0], hans_map[x[0][0]], x[0][1]))]

    jieba_freq = {p[0]: int(p[1]) for ln in jieba_big.splitlines() if len(p := ln.split()) >= 2 and _is_cjk_phrase(p[0], 2, 8) and p[1].isdigit()}
    st_map = {h: t.split()[0] for ln in st_text.splitlines() if "\t" in ln and not ln.startswith("#") for h, t in [ln.split("\t", 1)] if _is_cjk_phrase(h, 2, 8) and len(h) == len(t.split()[0]) and h != t.split()[0]}
    regional_tw_hk = {}
    for src_t in (tw_text, hk_text):
        for ln in src_t.splitlines():
            if "\t" in ln and not ln.startswith("#"):
                for hant in ln.split("\t", 1)[1].split():
                    if _is_cjk_phrase(hant, 2, 6):
                        regional_tw_hk[hant] = max(regional_tw_hk.get(hant, 0), log_prob_weight(jieba_freq.get(hant, 0), 320.0, SMOOTHING_FLOOR_I16))

    tc_payload = {"name": "mimo-tc", "version": "1.0", "license": "MIT AND Apache-2.0", "skipped_rows": skipped_tc, "entry_count": len(tc_entries), "st_phrase_count": len(st_map), "regional_phrase_count": len(regional_tw_hk), "entries": tc_entries, "st_map": st_map, "regional_tw_hk": regional_tw_hk}
    (tc_dir / "mimo-tc.json").write_text(json.dumps(tc_payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n")

    canonical = {(hans, code): (hant, wt) for hant, hans, code, wt in tc_entries}
    valid_sc, skipped_sc = [], 0
    for hans, values in complete_raw.items():
        if not 1 <= len(hans) <= 12 or not all(0x3400 <= ord(c) <= 0x9FFF for c in hans):
            skipped_sc += 1; continue
        toned = [numbered_pinyin(p) for p in values[0].split()]
        if len(toned) != len(hans) or not all(encode_fn(x) for x in toned):
            skipped_sc += 1; continue
        r_str, f_val = " ".join(toned), float(values[1])
        valid_sc.append([hans, r_str, f_val])

    rime = rime_ice_tables(rime_8105, rime_base, valid_sc, canonical, encode_fn)
    sc_payload = {"name": "mimo-sc", "version": "1.0", "license": "GPL-3.0-or-later AND MIT", "excluded_invalid_rows": skipped_sc, "entry_count": len(valid_sc), "entries": valid_sc, **rime}
    (sc_dir / "mimo-sc.json").write_text(json.dumps(sc_payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n")
    return {"mimo_tc_entries": len(tc_entries), "mimo_sc_entries": len(valid_sc), "rime_ice_added": rime["rime_ice_added_count"]}


def rime_ice_tables(rime_8105: str, rime_base: str, valid_sc: list, canonical: dict, encode_fn) -> dict:
    """The rime-ice part of mimo-sc.json: phrases added to the lexicon and the counts of every
    mimo-sc reading rime-ice lists, so the compiler can use rime-ice evidence for all words."""
    char_best, phrase_toned = {}, {}
    for hans, r_str, f_val in valid_sc:
        toned = r_str.split()
        bare_parts = [re.sub(r"[1-5]$", "", p) for p in toned]
        if len(hans) == 1 and ((hans, bare_parts[0]) not in char_best or f_val > char_best[(hans, bare_parts[0])][1]):
            char_best[(hans, bare_parts[0])] = (toned[0], f_val)
        elif len(hans) <= 6:
            phrase_toned[(hans, " ".join(bare_parts))] = r_str
    seen_sc = {(h, r) for h, r, _ in valid_sc}
    rime_entries: dict[str, tuple[str, int]] = {}
    for word, bare_py, r_w in _parse_rime_yaml(rime_8105) + _parse_rime_yaml(rime_base):
        if not _is_cjk_phrase(word, 2, 4) or r_w < 800 or (len(word) >= 3 and len(set(word)) == 1) or len(bare_py.split()) != len(word):
            continue
        t_str = phrase_toned.get((word, bare_py)) or (" ".join(char_best[(c, s)][0] for c, s in zip(word, bare_py.split())) if all((c, s) in char_best for c, s in zip(word, bare_py.split())) else None)
        if t_str:
            cal_w = min(3100 if len(word) == 2 else 2350, log_prob_weight(r_w, 260.0, 1800))
            if word not in rime_entries or cal_w > rime_entries[word][1]:
                rime_entries[word] = (t_str, cal_w)
    rime_additions = [[h, t, min(2100, w)] for h, (t, w) in rime_entries.items() if w >= 2300 and (h, t) not in canonical and (h, t) not in seen_sc and not any(c in _NON_LEXICAL_CHARS for c in h) and all(encode_fn(s) for s in t.split())]
    # Everyday phrases that end in a sentence-final particle (在嗎, 知道了, 對啊), which the
    # rule above leaves out: 2-4 characters, rime-ice count >= PARTICLE_PHRASE_MIN_COUNT, tones
    # from each character's most frequent reading. The compiler weights them by their count.
    # Phrases mimo-sc lists (often below its index threshold) are kept: the compiler indexes them.
    bare_sc = {(h, " ".join(re.sub(r"[1-5]$", "", p) for p in c.split())) for (h, c) in canonical}
    added = {h for h, _, _ in rime_additions}
    particle_phrases = {}
    for word, bare_py, count in _parse_rime_yaml(rime_base):
        bare_py = bare_py.replace("ü", "v")
        syllables = bare_py.split()
        if not _is_cjk_phrase(word, 2, 4) or count < PARTICLE_PHRASE_MIN_COUNT or len(syllables) != len(word) \
                or (len(word) >= 3 and len(set(word)) == 1) or not any(c in _NON_LEXICAL_CHARS for c in word) \
                or (len(word) == 2 and word[-1] not in _FINAL_PARTICLES) \
                or (word, bare_py) in bare_sc or word in added or not all((c, y) in char_best for c, y in zip(word, syllables)):
            continue
        toned = " ".join(char_best[(c, y)][0] for c, y in zip(word, syllables))
        if all(encode_fn(y) for y in toned.split()) and count > particle_phrases.get((word, toned), 0):
            particle_phrases[(word, toned)] = count
    # Counts keyed by word and toneless syllables (rime-ice has no tones).
    wanted = {(h, " ".join(re.sub(r"[1-5]$", "", p) for p in r.split())) for h, r, _ in valid_sc}
    wanted |= {(h, " ".join(re.sub(r"[1-5]$", "", p) for p in t.split())) for h, t, _ in rime_additions}
    wanted |= {(h, " ".join(re.sub(r"[1-5]$", "", p) for p in t.split())) for h, t in particle_phrases}
    counts: dict[tuple[str, str], int] = {}
    for word, bare_py, count in _parse_rime_yaml(rime_8105) + _parse_rime_yaml(rime_base):
        key = (word, bare_py.replace("ü", "v"))
        if key in wanted and count > counts.get(key, 0):
            counts[key] = count
    return {"rime_ice_phrases_loaded": len(rime_entries), "rime_ice_added_count": len(rime_additions),
            "rime_ice_additions": rime_additions,
            "rime_ice_particle_phrases": [[h, t, c] for (h, t), c in sorted(particle_phrases.items())],
            "rime_ice_counts": [[w, py, c] for (w, py), c in sorted(counts.items())]}


def update_rime_ice_from_files(directory: str) -> dict:
    """Recompute the rime-ice fields of DataSources/mimo-sc/mimo-sc.json from local copies of
    cn_dicts/8105.dict.yaml and cn_dicts/base.dict.yaml (no download), and update SOURCE.json."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("compile_native_lexicon", ROOT / "scripts/compile-native-lexicon.py")
    compiler = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compiler)
    folder = pathlib.Path(directory)
    sc_dir = ROOT / "DataSources/mimo-sc"
    sc = load_mimo_sc_dataset()
    canonical = {(hans, code): (hant, wt) for hant, hans, code, wt in load_mimo_tc_dataset()["entries"]}
    rime = rime_ice_tables((folder / "8105.dict.yaml").read_text(), (folder / "base.dict.yaml").read_text(),
                           sc["entries"], canonical, compiler.encode)
    sc.update(rime)
    (sc_dir / "mimo-sc.json").write_text(json.dumps(sc, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n")
    manifest = json.loads((sc_dir / "SOURCE.json").read_text())
    manifest["files"]["mimo-sc.json"] = hashlib.sha256((sc_dir / "mimo-sc.json").read_bytes()).hexdigest()
    (sc_dir / "SOURCE.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    return {key: len(value) if isinstance(value, list) else value for key, value in rime.items()}


if __name__ == "__main__":
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == "--rime-ice-from":
        print(update_rime_ice_from_files(sys.argv[2]))
    else:
        sys.exit("usage: python3 scripts/mimo_pipeline.py --rime-ice-from DIR  (DIR holds 8105.dict.yaml and base.dict.yaml)")
