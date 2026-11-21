#!/usr/bin/env python3
"""Builds the pixel type of the 字景 skin (Android/app/src/main/assets/fonts/).

  python3 scripts/subset_glyph_fonts.py              write the font files, their coverage lists and the licence text
  python3 scripts/subset_glyph_fonts.py --check      fail if the committed files are stale
  python3 scripts/subset_glyph_fonts.py --regen-chars  rewrite scripts/glyph-pixel-chars.txt from the lexicon sources
  python3 scripts/subset_glyph_fonts.py --report     print the sizes and how much of the character set each face draws

Two faces, both SIL OFL 1.1, both on a 12 pixel grid (one pixel is 100 font units at 12 px; the hand-drawn Latin, kana
and Han of both are 11 pixels tall):

  * Cubic 11 (俐方體 11 號, ACh-K/Cubic-11, v1.500): Traditional Chinese, kana, Latin, Zhuyin. Drawn first.
  * Fusion Pixel Font 12px proportional, zh_hant variant (TakWolf/fusion-pixel-font, release 2026.11.10): every other
    character of the set, Simplified and Japanese forms included (the variants differ in glyph shapes, not in coverage:
    zh_hans and ja draw the same 38,213 code points, so they add nothing to the chain).

Neither licence names a Reserved Font Name; the subsets are renamed all the same ("Meowpuut Pixel A" and "Meowpuut Pixel B")
because they are Modified Versions. The upstream files are not committed. Put them in --source (default
.build/font-sources) under these names; the script checks their sha256:

  Cubic_11.ttf, Cubic_11.OFL.txt                                (cubic-11 release v1.500, and its OFL.txt)
  fusion-pixel-font-12px-proportional-otf-v2026.11.10.zip       (fusion-pixel-font release 2026.11.10)

The character set (scripts/glyph-pixel-chars.txt) is every character of the lexicon sources that is seen at least ten
times (about 99.8 % of what the engine's own sentences contain), every character of the keyboard's and the app's own strings,
and the blocks of kana, Zhuyin, punctuation, arrows and Latin. A character outside it is drawn in the system font, as before.
The output is deterministic (fixed timestamps and options, sorted sets). Tools: pyftsubset and fonttools 4.x.
"""
import argparse
import glob
import hashlib
import lzma
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "Android" / "app" / "src" / "main" / "assets" / "fonts"
LICENCES = ROOT / "Android" / "app" / "src" / "main" / "assets" / "font-licenses"
CHARS_FILE = ROOT / "scripts" / "glyph-pixel-chars.txt"

CUBIC = ("Cubic_11.ttf", "0193f5f033612496df6b45ee92ac3b335bc6a5a24ff95da55ca87b33e57dcf62")
FUSION = ("fusion-pixel-font-12px-proportional-otf-v2026.11.10.zip", "287f40992540e25f11fea7498e7ffff1f4c1da745695be2a584a4575598c6c5b")
FUSION_FACE = "fusion-pixel-12px-proportional-zh_hant.otf"

MIN_COUNT = 10          # CJK characters seen fewer times in the lexicon sources are left to the system font
MIN_COUNT_OTHER = 3
FIXED_EPOCH = "1763078400"  # 2025-11-14
FIXED_HEAD_TIME = 3939408000

# Blocks that are cheap and that a keyboard needs whatever the lexicon says.
BLOCKS = [(0x20, 0x7E), (0xA0, 0x24F), (0x2B0, 0x2FF), (0x2010, 0x2027), (0x2030, 0x205E), (0x2190, 0x21FF), (0x25A0, 0x25FF),
          (0x3000, 0x303F), (0x3040, 0x30FF), (0x3100, 0x312F), (0x31A0, 0x31BF), (0x31C0, 0x31EF), (0xFF00, 0xFFEF)]


def is_cjk(ch):
    o = ord(ch)
    return 0x2E80 <= o <= 0x9FFF or 0x20000 <= o <= 0x2FA1F or 0xF900 <= o <= 0xFAFF


# --- The character set --------------------------------------------------------------------------------

LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def string_chars():
    """Every character in a string of the keyboard (ime/), the app pages and the string resources."""
    chars = set()
    kotlin = sorted((ROOT / "Android" / "app" / "src" / "main" / "java").rglob("*.kt"))
    for path in kotlin:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.lstrip().startswith(("//", "*", "/*")):
                continue
            for m in LITERAL.finditer(line):
                chars.update(m.group(1).encode("utf-8").decode("unicode_escape", "ignore") if "\\u" in m.group(1) else m.group(1))
    for path in sorted((ROOT / "Android" / "app" / "src" / "main" / "res").glob("values*/strings.xml")):
        for node in ET.parse(path).getroot().iter():
            if node.tag in ("string", "item") and node.text:
                chars.update(node.text)
    return {c for c in chars if c >= " " and c != "\u007f" and c not in "​�"}


def lexicon_counts():
    sources = ROOT / "Engine" / "DataSources"
    counts = Counter()

    def feed(text):
        for ch in text:
            if ord(ch) >= 0x80:
                counts[ch] += 1
    for p in sorted(glob.glob(str(sources / "*" / "*.xz"))):
        with lzma.open(p, "rt", encoding="utf-8", errors="ignore") as f:
            feed(f.read())
    for pattern in ("*.tsv", "*.json", "*.txt"):
        for p in sorted(glob.glob(str(sources / "*" / pattern))):
            if os.path.getsize(p) < 50_000_000:
                feed(Path(p).read_text(encoding="utf-8", errors="ignore"))
    return counts


def regen_chars():
    counts = lexicon_counts()
    if not counts:
        sys.exit("no lexicon sources under Engine/DataSources (is the Engine submodule checked out?)")
    chars = {c for c, n in counts.items() if n >= (MIN_COUNT if is_cjk(c) else MIN_COUNT_OTHER)}
    chars |= string_chars()
    for lo, hi in BLOCKS:
        chars |= {chr(o) for o in range(lo, hi + 1)}
    chars = {c for c in chars if c >= " " and c != "\u007f" and not (0xD800 <= ord(c) <= 0xDFFF) and not 0x1F000 <= ord(c) <= 0x1FAFF}
    CHARS_FILE.write_text("".join(sorted(chars)) + "\n", encoding="utf-8")
    cjk = sum(1 for c in chars if is_cjk(c))
    total = sum(counts.values())
    seen = sum(n for c, n in counts.items() if c in chars)
    print(f"{len(chars)} characters ({cjk} CJK); they are {100 * seen / total:.3f} % of the {total} non-ASCII characters in the sources")


def read_chars():
    return set(CHARS_FILE.read_text(encoding="utf-8").strip("\n"))


# --- Font tools ----------------------------------------------------------------------------------------

def tool(name):
    for where in (None, "/opt/anaconda3/bin"):
        path = shutil.which(name, path=where)
        if path:
            return path
    sys.exit(f"{name} not found; install fonttools (pip install fonttools)")


def run(cmd):
    env = {k: v for k, v in os.environ.items() if not k.startswith("PYTHON")}
    env["SOURCE_DATE_EPOCH"] = FIXED_EPOCH
    subprocess.run(cmd, check=True, env=env)


def rename(path, family, ps_name, note):
    from fontTools.ttLib import TTFont
    font = TTFont(path, recalcTimestamp=False)
    name = font["name"]
    for nid in (1, 2, 3, 4, 6, 16, 17, 21, 22, 25):
        name.removeNames(nameID=nid)
    for plat, enc, lang in ((3, 1, 0x409), (1, 0, 0)):
        name.setName(family, 1, plat, enc, lang)
        name.setName("Regular", 2, plat, enc, lang)
        name.setName(f"{ps_name};{note}", 3, plat, enc, lang)
        name.setName(family, 4, plat, enc, lang)
        name.setName(ps_name, 6, plat, enc, lang)
    font["head"].modified = font["head"].created = FIXED_HEAD_TIME
    font.save(path)


def ranges(codes):
    """Sorted code points as 'LLLL-HHHH' hex ranges, one per line: the coverage list the keyboard reads."""
    out, codes = [], sorted(codes)
    i = 0
    while i < len(codes):
        j = i
        while j + 1 < len(codes) and codes[j + 1] == codes[j] + 1:
            j += 1
        out.append(f"{codes[i]:X}" if i == j else f"{codes[i]:X}-{codes[j]:X}")
        i = j + 1
    return "\n".join(out) + "\n"


def build(source, chars, tmp):
    paths = {}
    for key, (fname, digest) in (("cubic", CUBIC), ("fusion", FUSION)):
        p = source / fname
        if not p.is_file():
            sys.exit(f"missing {p} (see the header of this script)")
        if hashlib.sha256(p.read_bytes()).hexdigest() != digest:
            sys.exit(f"{p} does not match the expected sha256 {digest}")
        paths[key] = p
    with zipfile.ZipFile(paths["fusion"]) as z:
        (tmp / FUSION_FACE).write_bytes(z.read(FUSION_FACE))
        fusion_licences = {n: z.read(n).decode("utf-8") for n in sorted(z.namelist()) if n == "OFL.txt" or n.startswith("LICENSES/")}
    from fontTools.ttLib import TTFont
    cubic_cmap = set(TTFont(paths["cubic"]).getBestCmap())
    fusion_cmap = set(TTFont(tmp / FUSION_FACE).getBestCmap())
    wanted = {ord(c) for c in chars}
    a_codes = sorted(wanted & cubic_cmap)
    b_codes = sorted((wanted - cubic_cmap) & fusion_cmap)
    subset = tool("pyftsubset")
    common = ["--no-recalc-timestamp", "--notdef-outline", "--no-hinting", "--desubroutinize", "--drop-tables+=vhea,vmtx,gasp,DSIG,GDEF,GSUB,GPOS,kern",
              "--name-IDs=0,1,2,3,4,5,6,7,8,9,13,14", "--name-languages=*", "--layout-features="]
    results = {}
    for key, src, codes, fam, ps, ext, note in (
            ("a", paths["cubic"], a_codes, "Meowpuut Pixel A", "MeowpuutPixelA-Regular", "ttf", "subset of Cubic 11 v1.500, OFL-1.1"),
            ("b", tmp / FUSION_FACE, b_codes, "Meowpuut Pixel B", "MeowpuutPixelB-Regular", "otf", "subset of Fusion Pixel Font 12px zh_hant 2026.11.10, OFL-1.1")):
        (tmp / f"{key}.txt").write_text("".join(chr(c) for c in codes), encoding="utf-8")
        out = tmp / f"pixel_{key}.{ext}"
        run([subset, str(src), f"--text-file={tmp / f'{key}.txt'}", f"--output-file={out}", *common])
        rename(out, fam, ps, note)
        results[f"pixel_{key}.{ext}"] = out.read_bytes()
        results[f"pixel_{key}.cov"] = ranges(codes).encode("ascii")
    results["__licence__"] = fusion_licences
    return results, {"a": len(a_codes), "b": len(b_codes), "wanted": len(wanted), "outside": len(wanted - cubic_cmap - fusion_cmap)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", default=str(ROOT / ".build" / "font-sources"))
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--regen-chars", action="store_true")
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()
    if args.regen_chars:
        regen_chars()
        return
    chars = read_chars()
    with tempfile.TemporaryDirectory() as t:
        results, counts = build(Path(args.source), chars, Path(t))
        fusion_licences = results.pop("__licence__")
        licence = assemble_licence(Path(args.source), fusion_licences)
        results["OFL-pixel-fonts.txt"] = licence.encode("utf-8")
        if args.report:
            print(counts)
        stale = []
        for name, data in results.items():
            dest = (LICENCES if name.endswith(".txt") else OUT) / name
            if args.check:
                if not dest.is_file() or dest.read_bytes() != data:
                    stale.append(str(dest.relative_to(ROOT)))
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(data)
            print(f"{dest.relative_to(ROOT)}: {len(data) / 1024:.1f} KB")
        if args.check:
            if stale:
                sys.exit("stale: " + ", ".join(stale) + " (run scripts/subset_glyph_fonts.py)")
            print("pixel fonts are up to date")


CUBIC_NOTICE_FILE = "Cubic_11.OFL.txt"


def assemble_licence(source, fusion_licences):
    """Cubic 11's own OFL.txt (kept beside the font as Cubic_11.OFL.txt) and the four texts of the Fusion Pixel release."""
    sep = "\n\n" + "=" * 78 + "\n\n"
    blocks = ["Pixel type of the 字景 skin: Cubic 11 and Fusion Pixel Font (subsets, renamed \"Meowpuut Pixel A\" and \"Meowpuut Pixel B\").\n"
              "Both are distributed under the SIL Open Font License 1.1; the licence texts and notices that came with them follow.\n"
              "Fusion Pixel Font is built from Ark Pixel, Cubic 11 and Galmuri, whose licences follow it."]
    cubic = source / CUBIC_NOTICE_FILE
    if not cubic.is_file():
        sys.exit(f"missing {cubic} (the OFL.txt that came with Cubic_11.ttf)")
    blocks.append("[ Cubic 11, OFL.txt ]\n\n" + cubic.read_text(encoding="utf-8").rstrip())
    for name in ("OFL.txt", "LICENSES/cubic-11/OFL.txt", "LICENSES/ark-pixel/OFL.txt", "LICENSES/galmuri/LICENSE.txt"):
        label = "Fusion Pixel Font, OFL.txt" if name == "OFL.txt" else name
        blocks.append(f"[ {label} ]\n\n" + fusion_licences[name].rstrip())
    return sep.join(blocks) + "\n"


if __name__ == "__main__":
    main()
