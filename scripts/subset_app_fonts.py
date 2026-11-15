#!/usr/bin/env python3
"""Builds the app-page fonts (紙日和, plans/art-direction.md section 5).

  python3 scripts/subset_app_fonts.py            write the font files
  python3 scripts/subset_app_fonts.py --check    fail if the committed files are stale
  python3 scripts/subset_app_fonts.py --chars    print the character set and the characters the font lacks

Two faces, both SIL OFL 1.1, used only by the app pages (never the keyboard, the
candidate window or the panels):

  * jf open 粉圓 v2.1, subset to every fixed UI string of this repository's app pages
    (the Traditional form; Simplified pages use the system font) plus ASCII
    and punctuation. The font's Reserved Font Names are 'open huninn' and 'huninn',
    so the subset is a renamed derivative: "Meowpuut Paper Round".
  * Fraunces (variable: opsz, wght, SOFT, WONK), instanced at wght 400 and 600 (keeping
    the wght axis would add about 45 KB per platform) and subset to Latin.

The upstream files are not committed. Put them in --source (default .build/font-sources)
under these names; the script checks their sha256:

  jf-openhuninn-2.1.ttf
      https://github.com/justfont/open-huninn-font/releases/download/v2.1/jf-openhuninn-2.1.ttf
  Fraunces[SOFT,WONK,opsz,wght].ttf
      https://raw.githubusercontent.com/google/fonts/main/ofl/fraunces/Fraunces%5BSOFT%2CWONK%2Copsz%2Cwght%5D.ttf

Tools: pyftsubset and fonttools (fontTools 4.x) on PATH, or --bin-dir. The output is
deterministic (fixed timestamps and options, sorted character sets).
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SOURCES = {
    "huninn": ("jf-openhuninn-2.1.ttf", "9d5bf4932d31fe94c18cd8cfddc98bc1b14ce10f4e354c682179db290a99c825"),
    "fraunces": ("Fraunces[SOFT,WONK,opsz,wght].ttf", "177ff6c0f14e5550a3c624247cd1189611d4eb65d000b14944c63d967958abbb"),
}

# opsz 36 suits a 23 pt wordmark and 20 pt stamp glyphs, SOFT 50 rounds the corners toward
# the clay look, WONK off keeps n/m/h plain.
FRAUNCES_INSTANCES = [("Regular", 400), ("SemiBold", 600)]
FRAUNCES_AXES = ["opsz=36", "SOFT=50", "WONK=0"]

PUNCTUATION = (
    "　、。，．；：？！「」『』（）［］【】《》〈〉〔〕…—～·・‧–‘’“”"
    "‹›«»°×÷±→←↑↓↗•©®™§¶†‡№℃％＆＋－＝０１２３４５６７８９"
)
ASCII = "".join(chr(c) for c in range(0x20, 0x7F))
LATIN_EXTRA = "‘’“”–—…·©®™°×"
# Not drawn in the paper face: the keyboard layout's Bopomofo and kana labels are not page titles,
# and each CJK glyph costs about 260 bytes. The system font draws them.
LEFT_TO_SYSTEM = [(0x3040, 0x30FF), (0x3100, 0x312F)]
LEFT_TO_SYSTEM_KEEP = "・ー"

FIXED_EPOCH = "1763078400"  # 2025-11-14
FIXED_HEAD_TIME = 3939408000  # 2024-11-14 in the font epoch (1904)


# --- Which files hold the app pages' fixed strings, per platform -----------------------------

def platform_files():
    """(code files, .strings files, strings.xml files, {platform: output dir}) of this repository."""
    if (ROOT / "iOS" / "App").is_dir() and not (ROOT / "Android").is_dir():
        code = sorted((ROOT / "iOS" / "App").glob("*.swift"))
        strings = sorted((ROOT / "iOS" / "App").glob("*.lproj/*.strings"))
        return code, strings, [], {"ios": ROOT / "iOS" / "App" / "Fonts"}
    # macOS: the Studio window. Android: the app package (settings, skin gallery, redeem) and
    # every strings.xml; the keyboard (ime/) is left out.
    code = [ROOT / "Sources" / "PaperStudio.swift", ROOT / "Sources" / "Interface.swift"]
    code += sorted((ROOT / "Android" / "app" / "src" / "main" / "java").rglob("app/*.kt"))
    strings = sorted((ROOT / "Resources").glob("*.lproj/*.strings"))
    res = ROOT / "Android" / "app" / "src" / "main" / "res"
    return code, strings, sorted(res.glob("values*/strings.xml")), {
        "macos": ROOT / "Resources" / "Fonts",
        "android": res / "font",
    }


# --- String extraction ------------------------------------------------------------------------

LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
ESCAPES = {"n": "\n", "t": "\t", '"': '"', "'": "'", "\\": "\\", "$": "$"}


def unescape(text):
    out, i = [], 0
    while i < len(text):
        ch = text[i]
        if ch == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", text[i + 2:i + 6]):
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(ESCAPES.get(nxt, ""))
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def strip_interpolation(text):
    """Drops Swift \\( ... ) interpolations so only the fixed text remains."""
    out, i = [], 0
    while i < len(text):
        if text.startswith("\\(", i):
            depth, i = 1, i + 2
            while i < len(text) and depth:
                depth += (text[i] == "(") - (text[i] == ")")
                i += 1
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


# t("繁體", "简体") and InterfaceText.system("繁體", "简体"): the Simplified forms and Simplified pages
# use the system font (粉圓 is a Traditional design), so only the first literal is subset.
PAIR_CALL = re.compile(r'(?:\bt|\bs|InterfaceText\.system)\(\s*("(?:[^"\\\n]|\\.)*")\s*,\s*"(?:[^"\\\n]|\\.)*"')


def literals(path):
    chars = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith(("//", "*", "/*")):
            continue
        line = PAIR_CALL.sub(lambda m: m.group(0)[:m.end(1) - m.start(0)] + ")", line)
        for match in LITERAL.finditer(line):
            chars.update(unescape(strip_interpolation(match.group(1))))
    return chars


def strings_file(path):
    chars = set()
    for match in re.finditer(r'"(?:[^"\\]|\\.)*"\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', path.read_text(encoding="utf-8")):
        chars.update(unescape(match.group(1)))
    return chars


def xml_strings(path):
    chars = set()
    for node in ET.parse(path).getroot().iter():
        if node.tag in ("string", "item") and node.text:
            chars.update(unescape(node.text))
    return chars


def collect():
    code, strings, xml, _ = platform_files()
    chars = set(ASCII) | set(PUNCTUATION)
    for p in code:
        chars |= literals(p)
    for p in strings:
        if "zh-Hans" not in str(p):
            chars |= strings_file(p)
    for p in xml:
        if "zh-rCN" not in str(p):
            chars |= xml_strings(p)
    def keep(c):
        if c < " " or c == "\u007f":
            return False
        if c in LEFT_TO_SYSTEM_KEEP:
            return True
        return not any(lo <= ord(c) <= hi for lo, hi in LEFT_TO_SYSTEM)
    return {c for c in chars if keep(c)}


# --- Font tools --------------------------------------------------------------------------------

def tool(name, bin_dir):
    candidates = [bin_dir] if bin_dir else [None, "/opt/anaconda3/bin"]
    for where in candidates:
        path = shutil.which(name, path=where)
        if path:
            return path
    sys.exit(f"{name} not found; install fonttools (pip install fonttools) or pass --bin-dir")


def run(cmd):
    env = {k: v for k, v in os.environ.items() if not k.startswith("PYTHON")}
    env["SOURCE_DATE_EPOCH"] = FIXED_EPOCH
    subprocess.run(cmd, check=True, env=env)


def rename(path, family, style, ps_name, note):
    """Gives a subset its own names (the OFL reserved names of 粉圓 must not survive) and fixed times."""
    from fontTools.ttLib import TTFont
    font = TTFont(path, recalcTimestamp=False)
    name = font["name"]
    full = family if style == "Regular" else f"{family} {style}"
    for nid in (1, 2, 3, 4, 6, 16, 17, 21, 22, 25):
        name.removeNames(nameID=nid)
    for plat, enc, lang in ((3, 1, 0x409), (1, 0, 0)):
        name.setName(family, 1, plat, enc, lang)
        name.setName(style, 2, plat, enc, lang)
        name.setName(f"{ps_name};{note}", 3, plat, enc, lang)
        name.setName(full, 4, plat, enc, lang)
        name.setName(ps_name, 6, plat, enc, lang)
    font["head"].modified = font["head"].created = FIXED_HEAD_TIME
    font.save(path)


def build(source, bin_dir, chars, tmp):
    """Returns {role: path} of the three finished fonts inside tmp."""
    paths = {}
    for key, (fname, digest) in SOURCES.items():
        paths[key] = source / fname
        if not paths[key].is_file():
            sys.exit(f"missing {paths[key]} (see the header of this script for the download URL)")
        if hashlib.sha256(paths[key].read_bytes()).hexdigest() != digest:
            sys.exit(f"{paths[key]} does not match the expected sha256 {digest}")
    subset, fonttools = tool("pyftsubset", bin_dir), tool("fonttools", bin_dir)
    common = ["--no-recalc-timestamp", "--notdef-outline", "--no-hinting", "--desubroutinize", "--drop-tables+=vhea,vmtx,gasp",
              "--name-IDs=0,1,2,3,4,5,6,7,8,9,13,14", "--name-languages=*"]

    (tmp / "chars.txt").write_text("".join(sorted(chars)), encoding="utf-8")
    round_font = tmp / "round.ttf"
    run([subset, str(paths["huninn"]), f"--text-file={tmp / 'chars.txt'}", f"--output-file={round_font}", *common,
         "--layout-features=ccmp"])
    rename(round_font, "Meowpuut Paper Round", "Regular", "MeowpuutPaperRound-Regular",
           "subset of the justfont round Han face 2.1, OFL-1.1")

    latin = set(ASCII) | set(LATIN_EXTRA)
    (tmp / "latin.txt").write_text("".join(sorted(latin)), encoding="utf-8")
    result = {"round": round_font}
    for style, weight in FRAUNCES_INSTANCES:
        inst = tmp / f"inst-{style}.ttf"
        run([fonttools, "varLib.instancer", str(paths["fraunces"]), f"wght={weight}", *FRAUNCES_AXES, "-o", str(inst)])
        out = tmp / f"serif-{style}.ttf"
        run([subset, str(inst), f"--text-file={tmp / 'latin.txt'}", f"--output-file={out}", *common,
             "--layout-features=kern,liga,onum,lnum"])
        rename(out, "Meowpuut Paper Serif", style, f"MeowpuutPaperSerif-{style}", f"static instance wght {weight}, Latin subset, OFL-1.1")
        result[f"serif-{style}"] = out
    return result


OUTPUT_NAMES = {
    "ios": {"round": "MeowpuutPaperRound.ttf", "serif-Regular": "MeowpuutPaperSerif-Regular.ttf",
            "serif-SemiBold": "MeowpuutPaperSerif-SemiBold.ttf"},
    "macos": {"round": "MeowpuutPaperRound.ttf", "serif-Regular": "MeowpuutPaperSerif-Regular.ttf",
              "serif-SemiBold": "MeowpuutPaperSerif-SemiBold.ttf"},
    "android": {"round": "paper_round.ttf", "serif-Regular": "paper_serif_regular.ttf",
                "serif-SemiBold": "paper_serif_semibold.ttf"},
}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", default=str(ROOT / ".build" / "font-sources"))
    ap.add_argument("--bin-dir")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--chars", action="store_true")
    args = ap.parse_args()
    source = Path(args.source)
    chars = collect()
    _, _, _, out_dirs = platform_files()

    if args.chars:
        cjk = "".join(sorted(c for c in chars if ord(c) > 0x2FFF))
        print(f"{len(chars)} characters, {len(cjk)} CJK:\n{cjk}")
        src = source / SOURCES["huninn"][0]
        if src.is_file():
            from fontTools.ttLib import TTFont
            cmap = TTFont(src).getBestCmap()
            print("not in the font (the system font draws them):",
                  repr("".join(sorted(c for c in chars if ord(c) not in cmap and c not in "\n\t"))))
        return

    with tempfile.TemporaryDirectory() as t:
        built = build(source, args.bin_dir, chars, Path(t))
        stale = []
        for platform, directory in out_dirs.items():
            size = 0
            for role, name in OUTPUT_NAMES[platform].items():
                dest, data = directory / name, built[role].read_bytes()
                size += len(data)
                if args.check:
                    if not dest.is_file() or dest.read_bytes() != data:
                        stale.append(str(dest.relative_to(ROOT)))
                else:
                    directory.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(data)
            print(f"{directory.relative_to(ROOT)}: {size / 1024:.1f} KB")
        if args.check:
            if stale:
                sys.exit("stale font files (run scripts/subset_app_fonts.py): " + ", ".join(stale))
            print("font files are up to date")


if __name__ == "__main__":
    main()
