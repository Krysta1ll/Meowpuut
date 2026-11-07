"""Compile the bundled word data into the portable engine's UTF-8 table."""
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



def imported_words(converter):
    root = ROOT / "DataSources/McBopomofo"
    manifest = json.loads((root / "SOURCE.json").read_text())
    for name, checksum in manifest["files"].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != checksum:
            raise ValueError(f"Upstream data changed: {name}; put edits in Resources/Lexicon instead")
    frequency = {}
    for line in (root / "phrase.occ").read_text().splitlines():
        fields = line.split()
        if len(fields) == 2: frequency[fields[0]] = int(fields[1])
    mapping = {}
    entries = {}
    skipped = 0
    # Establish reading aliases using the upstream explicit per-character pinyin.
    for line in (root / "BPMFBase.txt").read_text().splitlines():
        fields = line.split()
        if len(fields) < 4: continue
        word, bpmf, pinyin = fields[:3]
        pinyin = pinyin.lower().replace("ü", "v")
        if not pinyin[-1:].isdigit(): pinyin += "1"
        if bpmf.startswith("ㄝ"): pinyin = "eh" + pinyin[-1]
        if encode(pinyin) is None:
            skipped += 1
            continue
        mapping.setdefault(bpmf, pinyin)
        if any(ord(char) < 0x3400 for char in word): continue
        entries[(word, pinyin)] = max(1, round(700 * math.log1p(frequency.get(word, 0))))
    for line in (root / "BPMFMappings.txt").read_text().splitlines():
        fields = line.split()
        if len(fields) < 2: continue
        word, syllables = fields[0], fields[1:]
        if len(syllables) > 12 or any(part not in mapping for part in syllables):
            skipped += 1
            continue
        code = " ".join(mapping[part] for part in syllables)
        if len(word) != len(syllables):
            skipped += 1
            continue
        entries[(word, code)] = max(1, round(700 * math.log1p(frequency.get(word, 0))))
    # A common reading gets the full character prior; other readings are discounted.
    main_reading = {}
    for line in (root / "heterophony1.list").read_text().splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[1] in mapping: main_reading[fields[0]] = mapping[fields[1]]
    for key in entries:
        word, code = key
        if len(word) == 1 and word in main_reading and main_reading[word] != code:
            entries[key] = max(1, entries[key] // 8)
    text = sorted({word for word, _ in entries})
    converted = subprocess.run([converter], input="\n".join(text) + "\n", text=True, capture_output=True, check=True).stdout.splitlines()
    if len(converted) != len(text): raise ValueError("Script conversion count mismatch")
    hans = dict(zip(text, converted))
    print(f"MIT source: {len(entries):,} accepted readings; {skipped} unsupported/mismatched rows excluded")
    return {(word, hans[word], code): weight for (word, code), weight in entries.items()}

def numbered_pinyin(syllable):
    result, tone = "", "5"
    for char in unicodedata.normalize("NFD", syllable.lower()):
        if char in {"\u0304", "\u0301", "\u030c", "\u0300"}:
            tone = {"\u0304":"1", "\u0301":"2", "\u030c":"3", "\u0300":"4"}[char]
        elif char == "\u0308" and result.endswith("u"): result = result[:-1] + "v"
        elif char == "\u0302" and result.endswith("e"): result += "h"
        else: result += char
    return result + tone

def merge_simplified(words, converter):
    root = ROOT / "DataSources/PinyinPro"
    manifest = json.loads((root / "SOURCE.json").read_text())
    for name, expected in manifest["files"].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Simplified source checksum mismatch: {name}")
    source = json.loads((root / "complete.json").read_text())
    canonical = {}
    for (hant, hans, reading), weight in words.items():
        if (hans, reading) not in canonical or canonical[(hans, reading)][1] < weight:
            canonical[(hans, reading)] = (hant, weight)
    merged = {(hant, hans, reading): weight for (hans, reading), (hant, weight) in canonical.items()}
    native_keys = set(merged)
    valid, skipped = [], 0
    for hans, values in source.items():
        if not 1 <= len(hans) <= 12 or not all(0x3400 <= ord(c) <= 0x9fff for c in hans): skipped += 1; continue
        reading = " ".join(numbered_pinyin(part) for part in values[0].split())
        if len(reading.split()) != len(hans) or not all(encode(x) for x in reading.split()): skipped += 1; continue
        valid.append((hans, reading, float(values[1])))
    missing = sorted({hans for hans, reading, _ in valid if (hans, reading) not in canonical})
    converted = subprocess.run([converter, "--traditional"], input="\n".join(missing) + "\n", text=True, capture_output=True, check=True).stdout.splitlines()
    if len(converted) != len(missing): raise ValueError("Traditional conversion mismatch")
    traditional = dict(zip(missing, converted))
    # Align the two corpus scales using shared readings, then modestly prefer
    # simplified-source usage. These are ranking priors, not comparable raw counts.
    def source_weight(frequency):
        return max(1, round(700 * math.log1p(max(0, frequency) * 1e12)))
    differences = [source_weight(frequency) - canonical[(hans, reading)][1]
        for hans, reading, frequency in valid
        if (hans, reading) in canonical and frequency > 0 and canonical[(hans, reading)][1] > 0]
    offset = round(statistics.median(differences)) if differences else 0
    merged = {key: max(1, weight + offset - 350) for key, weight in merged.items()}
    duplicates = added = 0
    for hans, reading, frequency in valid:
        existing = canonical.get((hans, reading))
        hant = existing[0] if existing else traditional[hans]
        key = (hant, hans, reading)
        # Simplified-source frequency wins a duplicate; our reviewed overlay applies last.
        merged[key] = source_weight(frequency)
        duplicates += existing is not None
        added += existing is None
        if existing or frequency >= 1e-11: native_keys.add(key)
    report = {"name": "mimo", "version": "1.0", "simplified_rows": len(valid), "duplicate_readings_merged": duplicates,
              "new_readings": added, "excluded_invalid_rows": skipped,
              "merged_readings": len(merged), "native_hot_readings": len(native_keys),
              "traditional_only_scale_offset": offset, "traditional_only_preference_discount": 350,
              "note": "All valid new readings remain in indexed SQLite; frequent words also enter native segmentation."}
    print("Simplified merge:", json.dumps(report, ensure_ascii=False))
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
    for number, fields in authored("chinese.tsv"):
        if len(fields) != 4: raise ValueError(f"chinese.tsv:{number}: expected 4 fields")
        traditional, simplified, code, priority = fields
        sounds = code.split()
        if not all(encode(s) for s in sounds): raise ValueError(f"chinese.tsv:{number}: invalid pinyin")
        if not traditional or not simplified or not priority.isdigit(): raise ValueError(f"chinese.tsv:{number}: invalid word")
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
    (output / "lexicon-merge-report.json").write_text(json.dumps(merge_report, indent=2))
    (output / "mimo-manifest.json").write_text(json.dumps({
        "name": "mimo", "version": "1.0", "readings": len(words),
        "key": "simplified spelling + tone-qualified reading",
        "frequency_preference": "simplified source; reviewed overlays last",
        "sources": ["McBopomofo (MIT)", "Pinyin Pro complete (MIT)"],
        "japanese_sources": ["UniDic-CWJ 2025.12 reviewed subset (BSD-3-Clause)", "Meowpuut authored"]
    }, ensure_ascii=False, indent=2))
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
    # Context-only observations steer adjacent word segments without creating
    # artificial dictionary entries or changing the standalone word order.
    for number, fields in authored("context.tsv"):
        if len(fields) != 2: raise ValueError(f"context.tsv:{number}: expected 2 fields")
        phrase, priority = fields
        if not 2 <= len(phrase) <= 5 or not all('\u3400' <= c <= '\u9fff' for c in phrase) or not priority.isdigit() or not 1 <= int(priority) <= 10000:
            raise ValueError(f"context.tsv:{number}: invalid phrase or priority")
        context[phrase] = max(context.get(phrase, 0), int(priority))
    for mode, entries in rows.items():
        with (output / f"native-{mode}.tsv").open("w") as dest:
            dest.write(f"MEOWPUUT-{mode.upper()}-1\n")
            for (bare, reading, hant, hans), weight in sorted(entries.items(), key=lambda row: (row[0][0], -row[1], row[0][1:])):
                dest.write(f"{bare}\t{reading}\t{hant}\t{hans}\t{weight}\n")
            dest.write("# context\n")
            for word, weight in sorted(context.items()): dest.write(f"{word}\t{weight}\n")
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
    unidic = ROOT / "DataSources/UniDic"
    manifest = json.loads((unidic / "SOURCE.json").read_text())
    daily = (unidic / "daily.tsv").read_bytes()
    if hashlib.sha256(daily).hexdigest() != manifest["curated_sha256"]:
        raise ValueError("UniDic curated data changed; update the pinned source record")
    for number, line in enumerate(daily.decode("utf-8").splitlines(), 1):
        if not line or line.startswith("#"): continue
        fields = line.split("|")
        if len(fields) != 5: raise ValueError(f"UniDic daily.tsv:{number}: expected 5 fields")
        kana, word, romaji, chinese, priority = fields
        if not re.fullmatch(r"[ぁ-ゖー]+", kana) or not priority.isdigit():
            raise ValueError(f"UniDic daily.tsv:{number}: invalid reading or priority")
        weight = int(priority)
        if not 4000 < weight <= 10000:
            raise ValueError(f"UniDic daily.tsv:{number}: priority must leave room for a lower-weight alias")
        add_japanese(romaji, word, weight)
        alias = pinyin_kana(word, chinese.split())
        if alias != romaji:
            # Pure-kanji pinyin can be ordinary Chinese; the portable core treats
            # this 3500 weight as ambiguous rather than Japanese-only evidence.
            contains_kana = any("\u3040" <= char <= "\u30ff" for char in word)
            alias_weight = max(4000, weight - 3000) if contains_kana else 3500
            add_japanese(alias, word, alias_weight)
    japanese = {reading: [{"text": word, "weight": weight} for word, weight in entries.items()]
                for reading, entries in japanese.items()}
    for values in japanese.values(): values.sort(key=lambda value: (-value["weight"], value["text"]))
    (output / "native-japanese.json").write_text(json.dumps(japanese, ensure_ascii=False, sort_keys=True))
    # Both platform adapters consume the same merged and deduplicated entries.
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
