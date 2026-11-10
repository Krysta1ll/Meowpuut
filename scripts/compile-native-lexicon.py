"""Compile the bundled mimo-tc, mimo-sc, and mimo-jp lexicons into portable UTF-8 tables."""
import collections
import functools
import pathlib
import re
import sys
import json
import sqlite3
import hashlib
import math
import unicodedata
import subprocess
import statistics
from japanese_readings import pinyin_kana
from mimo_binary import write_mimo_binaries
from mimo_pipeline import clamp_i16_weight, load_mimo_sc_dataset, load_mimo_tc_dataset, numbered_pinyin

ROOT = pathlib.Path(__file__).resolve().parent.parent
KEYS = dict(zip("bpmfdtnlgkhjqxZCSrzcsiuvaoeEAIOUMNKGR12345",
                "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347"))
FINALS = {
    "a": "a", "o": "o", "e": "e", "eh": "E", "ai": "A", "ei": "I",
    "ao": "O", "ou": "U", "an": "M", "en": "N", "ang": "K", "eng": "G",
    "er": "R", "i": "i", "ia": "ia", "io": "io", "ie": "iE",
    "iai": "iA", "iao": "iO", "iu": "iU", "iou": "iU",
    "ian": "iM", "in": "iN", "iang": "iK", "ing": "iG",
    "u": "u", "ua": "ua", "uo": "uo", "uai": "uA", "ui": "uI", "uei": "uI",
    "uan": "uM", "un": "uN", "uen": "uN", "uang": "uK", "ueng": "uG", "ong": "uG",
    "v": "v", "ve": "vE", "van": "vM", "vn": "vN", "iong": "vG",
}

@functools.lru_cache(maxsize=None)
def encode(syllable):
    match = re.fullmatch(r"([a-züê]+)([1-5])", syllable)
    if not match:
        return None
    sound, tone = match.groups()
    sound = sound.replace("ü", "v").replace("ê", "eh")
    if sound.startswith("y"):
        tail = sound[1:]
        sound = tail if tail.startswith("i") else "v" + tail[1:] if tail.startswith("u") else "i" + tail
    elif sound.startswith("w"):
        tail = sound[1:]
        sound = tail if tail.startswith("u") else "u" + tail
    if sound == "r":
        sound = "er"
    if sound == "m":
        sound = "mu"
    initial, final = "", sound
    match = re.match(r"^(zh|ch|sh|[bpmfdtnlgkhjqxrzcs])(.+)$", sound)
    if match and sound != "er":
        initial, final = match.groups()
    if initial in {"j", "q", "x"} and final.startswith("u"):
        final = "v" + final[1:]
    if final == "i" and initial in {"zh", "ch", "sh", "r", "z", "c", "s"}:
        final = ""
    phonetic = FINALS.get(final, "" if not final else None)
    if phonetic is None:
        return None
    initial = {"zh": "Z", "ch": "C", "sh": "S"}.get(initial, initial)
    return "".join(KEYS[symbol] for symbol in initial + phonetic + tone)


def authored(name):
    for number, line in enumerate((ROOT / "Resources/Lexicon" / name).read_text().splitlines(), 1):
        if not line or line.startswith("#"): continue
        fields = line.split("|")
        yield number, fields


def imported_words(converter=None):
    tc_data = load_mimo_tc_dataset()
    entries = {(hant, hans, code): int(weight) for hant, hans, code, weight in tc_data["entries"]}
    skipped = tc_data.get("skipped_rows", 0)
    print(f"mimo-tc source: {len(entries):,} accepted readings; {skipped} unsupported/mismatched rows excluded")
    return entries, tc_data.get("st_map", {})


def merge_simplified(tc_bundle, converter):
    words, st_map = tc_bundle
    sc_data = load_mimo_sc_dataset()
    canonical = {}
    for (hant, hans, reading), weight in words.items():
        if (hans, reading) not in canonical or canonical[(hans, reading)][1] < weight:
            canonical[(hans, reading)] = (hant, weight)
    merged = {(hant, hans, reading): weight for (hans, reading), (hant, weight) in canonical.items()}
    native_keys = set(merged)
    valid = [(hans, reading, float(freq)) for hans, reading, freq in sc_data["entries"]]
    skipped = int(sc_data.get("excluded_invalid_rows", 0))
    rime_additions = [(hans, reading, int(w)) for hans, reading, w in sc_data.get("rime_ice_additions", [])]
    missing = sorted({hans for hans, reading, _ in valid if (hans, reading) not in canonical} | {hans for hans, _, _ in rime_additions})
    converted = subprocess.run([converter, "--traditional"], input="\n".join(missing) + "\n", text=True, capture_output=True, check=True).stdout.splitlines()
    if len(converted) != len(missing): raise ValueError("Traditional conversion mismatch")
    traditional = {h: st_map.get(h, t) for h, t in zip(missing, converted)}
    def source_weight(frequency):
        return clamp_i16_weight(700 * math.log1p(max(0, frequency) * 1e12))
    differences = [source_weight(frequency) - canonical[(hans, reading)][1]
        for hans, reading, frequency in valid
        if (hans, reading) in canonical and frequency > 0 and canonical[(hans, reading)][1] > 0]
    offset = round(statistics.median(differences)) if differences else 0
    merged = {key: clamp_i16_weight(weight + offset - 350) for key, weight in merged.items()}
    duplicates = added = 0
    for hans, reading, frequency in valid:
        existing = canonical.get((hans, reading))
        hant = existing[0] if existing else traditional[hans]
        key = (hant, hans, reading)
        merged[key] = source_weight(frequency)
        if existing or frequency >= 1e-11: native_keys.add(key)
        duplicates += existing is not None
        added += existing is None
    for hans, reading, rime_w in rime_additions:
        key = (traditional[hans], hans, reading)
        merged[key] = rime_w
        native_keys.add(key)
    report = {"name": "mimo", "version": "1.0", "license": "GPL-3.0-or-later",
              "lexicons": ["mimo-tc", "mimo-sc", "mimo-jp"],
              "simplified_rows": len(valid), "duplicate_readings_merged": duplicates,
              "new_readings": added, "excluded_invalid_rows": skipped,
              "rime_ice_phrases_loaded": int(sc_data.get("rime_ice_phrases_loaded", 0)),
              "rime_ice_added_readings": len(rime_additions), "opencc_st_phrases_loaded": len(st_map),
              "merged_readings": len(merged), "native_hot_readings": len(native_keys),
              "traditional_only_scale_offset": offset, "traditional_only_preference_discount": 350,
              "note": "All valid new readings remain in indexed SQLite; frequent words also enter native segmentation."}
    print("mimo-sc merge:", json.dumps(report, ensure_ascii=False))
    return merged, native_keys, report


def main(destination, converter):
    output = pathlib.Path(destination)
    output.mkdir(parents=True, exist_ok=True)
    associations = json.loads((ROOT / "Resources/Lexicon/associations.json").read_text())
    (output / "associations.json").write_text(json.dumps(associations, ensure_ascii=False))
    words, native_keys, merge_report = merge_simplified(imported_words(converter), converter)
    (output / "lexicon-merge-report.json").write_text(json.dumps(merge_report, indent=2))
    authored_count = 0
    reviewed_groups = {}
    derived_keys = set()
    for overlay_file in ("chinese.tsv", "community.tsv"):
        for number, fields in authored(overlay_file):
            if len(fields) != 4: raise ValueError(f"{overlay_file}:{number}: expected 4 fields")
            traditional, simplified, code, priority = fields
            sounds = code.split()
            if not all(encode(s) for s in sounds): raise ValueError(f"{overlay_file}:{number}: invalid pinyin")
            if not traditional or not simplified or not priority.isdigit(): raise ValueError(f"{overlay_file}:{number}: invalid word")
            weight = int(priority)
            reviewed_groups[(simplified, code)] = ((traditional, simplified, code), weight)
            words[(traditional, simplified, code)] = weight
            native_keys.add((traditional, simplified, code))
            authored_count += 1
            # Character readings are derived only from our own explicitly supplied words.
            if len(traditional) == len(simplified) == len(sounds):
                for hant, hans, sound in zip(traditional, simplified, sounds):
                    key = (hant, hans, sound)
                    words[key] = max(words.get(key, 0), min(1000, weight // 10))
                    native_keys.add(key)
                    derived_keys.add(key)
    canonical = {}
    native_groups = {(key[1], key[2]) for key in native_keys}
    for key, weight in words.items():
        group = (key[1], key[2])
        if group not in canonical or canonical[group][1] < weight: canonical[group] = (key, weight)
    # Reviewed spellings/weights win even when lower than an upstream variant.
    canonical.update(reviewed_groups)
    merge_report["reviewed_readings"] = len(reviewed_groups)
    merge_report["derived_character_readings"] = len(derived_keys)
    merge_report["final_duplicate_variants_removed"] = len(words) - len(canonical)
    words = {key: weight for key, weight in canonical.values()}
    native_keys = {key for key in words if (key[1], key[2]) in native_groups}
    merge_report["final_readings"] = len(words)
    merge_report["final_native_readings"] = len(native_keys)
    rows = {"zhuyin": {}, "pinyin": {}}
    def add(mode, bare, reading, hant, hans, weight):
        key = (bare, reading, hant, hans)
        rows[mode][key] = max(rows[mode].get(key, 0), weight)
    context = {}
    for (hant, hans, code), weight in words.items():
        if (hant, hans, code) not in native_keys: continue
        sounds = code.split()
        phonetic = "".join(encode(sound) for sound in sounds)
        add("zhuyin", re.sub("[ 6347]", "", phonetic), phonetic, hant, hans, weight)
        aliases = {"".join(sounds)}
        aliases |= {re.sub(r"([nl])ve", r"\1ue", p) for p in list(aliases)}
        # Full pinyin is primary. Initial abbreviations remain available with a lower prior.
        if len(sounds) > 1:
            short = "".join(s[0] + s[-1] for s in sounds)
            add("pinyin", re.sub("[1-5]", "", short), short, hant, hans, max(1, weight // 8))
        for reading in aliases: add("pinyin", re.sub("[1-5]", "", reading), reading, hant, hans, weight)
        if len(hant) > 1: context[hant] = max(context.get(hant, 0), weight)
    # Context-only observations steer adjacent word segments and next-word predictions.
    for number, fields in authored("context.tsv"):
        if len(fields) != 2: raise ValueError(f"context.tsv:{number}: expected 2 fields")
        phrase, priority = fields
        if not 2 <= len(phrase) <= 5 or not all('\u3400' <= c <= '\u9fff' for c in phrase) or not priority.isdigit() or not 1 <= int(priority) <= 10000:
            raise ValueError(f"context.tsv:{number}: invalid phrase or priority")
        context[phrase] = max(context.get(phrase, 0), int(priority))
    sorted_context = sorted(context.items())
    sorted_rows = {}
    for mode, entries in rows.items():
        ordered = sorted(entries.items(), key=lambda row: (row[0][0], -row[1], row[0][1:]))
        sorted_rows[mode] = ordered
        with (output / f"native-{mode}.tsv").open("w") as dest:
            dest.write(f"MEOWPUUT-{mode.upper()}-1\n")
            for (bare, reading, hant, hans), weight in ordered:
                dest.write(f"{bare}\t{reading}\t{hant}\t{hans}\t{weight}\n")
            dest.write("# context\n")
            for word, weight in sorted_context: dest.write(f"{word}\t{weight}\n")
    bin_stats = write_mimo_binaries(output, sorted_rows["pinyin"], sorted_rows["zhuyin"], sorted_context)
    merge_report.update(bin_stats)
    (output / "lexicon-merge-report.json").write_text(json.dumps(merge_report, indent=2))
    (output / "mimo-manifest.json").write_text(json.dumps({
        "name": "mimo", "version": "1.0", "license": "GPL-3.0-or-later",
        "readings": len(words), "lexicons": ["mimo-tc", "mimo-sc", "mimo-jp"],
        "binary_format": "MIMO-BASE-1 / MIMO-BIN-1 (i16 Log-Probability SoA)",
        "binary_files": ["mimo_base.bin"],
        "key": "simplified spelling + tone-qualified reading",
        "frequency_preference": "mimo-sc + mimo-tc + rime-ice calibrated fusion + OpenCC STPhrases; reviewed overlays last",
        "sources": ["mimo-tc (MIT)", "mimo-sc (MIT)", "rime-ice (GPL-3.0)", "OpenCC (Apache-2.0)", "jieba (MIT)"],
        "japanese_sources": ["mimo-jp (BSD-3-Clause)", "Meowpuut authored"]
    }, ensure_ascii=False, indent=2))
    japanese = {}
    def add_japanese(reading, word, weight):
        if not re.fullmatch("[a-z'-]+", reading) or not word or not 0 < weight <= 10000:
            raise ValueError(f"invalid Japanese entry: {reading!r}, {word!r}, {weight}")
        entries = japanese.setdefault(reading, {})
        entries[word] = max(entries.get(word, 0), weight)
    for number, fields in authored("japanese.tsv"):
        if len(fields) != 3: raise ValueError(f"japanese.tsv:{number}: expected 3 fields")
        readings, word, weight = fields
        for reading in readings.split(","):
            add_japanese(reading, word, int(weight))
    mimo_jp = ROOT / "DataSources/mimo-jp"
    manifest = json.loads((mimo_jp / "SOURCE.json").read_text())
    daily = (mimo_jp / "mimo-jp.tsv").read_bytes()
    if hashlib.sha256(daily).hexdigest() != manifest["curated_sha256"]:
        raise ValueError("mimo-jp curated data changed; update the pinned source record")
    for number, line in enumerate(daily.decode("utf-8").splitlines(), 1):
        if not line or line.startswith("#"): continue
        fields = line.split("|")
        if len(fields) != 5: raise ValueError(f"mimo-jp.tsv:{number}: expected 5 fields")
        kana, word, romaji, chinese, priority = fields
        if not re.fullmatch(r"[ぁ-ゖー]+", kana) or not priority.isdigit():
            raise ValueError(f"mimo-jp.tsv:{number}: invalid reading or priority")
        weight = int(priority)
        if not 4000 < weight <= 10000:
            raise ValueError(f"mimo-jp.tsv:{number}: priority must leave room for a lower-weight alias")
        add_japanese(romaji, word, weight)
        alias = pinyin_kana(word, chinese.split())
        if alias != romaji:
            contains_kana = any("\u3040" <= char <= "\u30ff" for char in word)
            alias_weight = max(4000, weight - 3000) if contains_kana else 3500
            add_japanese(alias, word, alias_weight)
    japanese = {reading: [{"text": word, "weight": weight} for word, weight in entries.items()]
                for reading, entries in japanese.items()}
    for values in japanese.values(): values.sort(key=lambda value: (-value["weight"], value["text"]))
    (output / "native-japanese.json").write_text(json.dumps(japanese, ensure_ascii=False, sort_keys=True))
    with (output / "native-japanese.tsv").open("w") as dest:
        for reading in sorted(japanese):
            for entry in japanese[reading]:
                dest.write(f"{reading}|{entry['text']}|{entry['weight']}\n")
    database = output / "language-evidence.sqlite"
    if database.exists(): database.unlink()
    db = sqlite3.connect(database)
    db.execute("CREATE TABLE words(language TEXT, reading TEXT, word TEXT, weight INTEGER, PRIMARY KEY(language,reading,word)) WITHOUT ROWID")
    db.execute("CREATE TABLE lexicon(reading TEXT, phonetic TEXT, bare TEXT, hant TEXT, hans TEXT, weight INTEGER, toned TEXT, PRIMARY KEY(reading,phonetic,hant,hans)) WITHOUT ROWID")
    def record(language, reading, word, weight):
        db.execute("INSERT INTO words VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET weight=max(weight,excluded.weight)", (language, reading, word, weight))
    for (hant, hans, code), weight in words.items():
        reading = re.sub("[1-5 ]", "", code)
        phonetic = "".join(encode(sound) for sound in code.split())
        db.execute("INSERT OR REPLACE INTO lexicon VALUES(?,?,?,?,?,?,?)", (reading, phonetic, re.sub("[ 6347]", "", phonetic), hant, hans, weight, code.replace(" ", "")))
    for reading, items in japanese.items():
        for item in items: record("ja", reading.replace("'", ""), item["text"], item["weight"])
    db.execute("CREATE INDEX lexicon_phonetic ON lexicon(phonetic)")
    db.execute("CREATE INDEX lexicon_bare ON lexicon(bare)")
    db.commit(); db.close()
    print(f"Meowpuut overlay: {authored_count} Chinese rows; {len(japanese)} Japanese readings; total {len(words):,} Chinese readings")


if __name__ == "__main__":
    main(*sys.argv[1:])
