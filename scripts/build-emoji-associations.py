#!/usr/bin/env python3
"""Builds Resources/Lexicon/associations.json, the emoji and kaomoji offered for
Chinese words: the hand-written entries in Resources/Lexicon/associations-hand.json
first, then emoji from the Unicode CLDR annotations (DataSources/cldr-emoji).

  python3 scripts/build-emoji-associations.py
      rebuild associations.json from the two files above
  python3 scripts/build-emoji-associations.py --cldr zh.xml zh_Hant.xml
      first rebuild DataSources/cldr-emoji/cldr-emoji.tsv from CLDR's
      common/annotations/zh.xml and zh_Hant.xml (put their sha256 in SOURCE.json)
"""
import collections, hashlib, json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HAND = ROOT / "Resources/Lexicon/associations-hand.json"
TABLE = ROOT / "DataSources/cldr-emoji/cldr-emoji.tsv"
OUT = ROOT / "Resources/Lexicon/associations.json"
# A keyword shared by more emoji than this says nothing (手, 臉, 動物).
MAX_EMOJI_PER_WORD = 6
PER_WORD = 2


def is_word(text):
    return 1 <= len(text) <= 4 and all("㐀" <= ch <= "鿿" for ch in text)


def emoji(cp):
    """The emoji as typed, or None for symbols that are not emoji (→, ©, ≠)."""
    points = [ord(ch) for ch in cp]
    if all(p >= 0x1F000 or p in (0x200D, 0xFE0F) for p in points):
        return cp
    if len(points) == 1 and (0x2600 <= points[0] <= 0x27BF or 0x231A <= points[0] <= 0x23FA or points[0] in (0x2B50, 0x2B55)):
        return cp + "\uFE0F"  # emoji presentation, not the text glyph
    return None


def closeness(word, name):
    """0: the emoji's name is the word; 1: one contains the other; 2: they share
    a character (牛奶 and 一杯奶 🥛 before 黄油 🧈); 3: only a keyword."""
    if word == name:
        return 0
    if word in name or name in word:
        return 1
    return 2 if set(word) & set(name) else 3


def read_cldr(paths):
    # word -> [(file, closeness, order, emoji)]: zh before zh_Hant (你好 is 👋 in
    # zh, 🖖 in zh_Hant), then the emoji whose name is closest to the word.
    words = collections.defaultdict(list)
    order = 0
    for file, path in enumerate(paths):
        text = Path(path).read_text(encoding="utf-8")
        names = dict(re.findall(r'<annotation cp="([^"]+)" type="tts">([^<]+)</annotation>', text))
        for cp, keywords in re.findall(r'<annotation cp="([^"]+)">([^<]+)</annotation>', text):
            shown = emoji(cp)
            if not shown:
                continue
            order += 1
            name = names.get(cp, "").strip()
            for word in {k.strip() for k in keywords.split("|")} | {name}:
                # A single character only names an emoji (猫 🐈); as a keyword it is too loose (好 🫡).
                if is_word(word) and (len(word) > 1 or word == name):
                    words[word].append((file, closeness(word, name), order, shown))
    table = {}
    for word, found in words.items():
        unique = {}
        for item in sorted(found):
            unique.setdefault(item[3], item)
        if len(unique) <= MAX_EMOJI_PER_WORD:
            table[word] = [item[3] for item in sorted(unique.values())][:PER_WORD]
    return table


def main():
    args = sys.argv[1:]
    if args[:1] == ["--cldr"]:
        table = read_cldr(args[1:])
        TABLE.write_text("".join(f"{w}\t{' '.join(e)}\n" for w, e in sorted(table.items())), encoding="utf-8")
        for path in args[1:]:
            print(Path(path).name, hashlib.sha256(Path(path).read_bytes()).hexdigest())
    hand = json.loads(HAND.read_text(encoding="utf-8"))
    result = dict(hand)
    for line in TABLE.read_text(encoding="utf-8").splitlines():
        word, symbols = line.split("\t")
        if word not in result:
            result[word] = symbols.split(" ")
    OUT.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=0) + "\n", encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {len(hand)} hand-written, {len(result) - len(hand)} from CLDR")


if __name__ == "__main__":
    main()
