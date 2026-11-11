#!/usr/bin/env python3
"""Bring festival wardrobe packs into the repository (docs/FESTIVALS.md).

    python3 scripts/import_festivals.py PACK [PACK ...]

For example the 24-theme collection, optionally followed by the v1 and v2
packs it was made from: a theme found in two packs must be byte-identical in
both. Themes keep the order of the packs and of each pack's manifest.

This is the only way festival art gets into the repository. It checks every
file it copies against its pack's SHA256SUMS.txt, checks sizes, alpha and the
cats' foot anchor, measures where each trim's rim runs, derives the capsule
colours from the accent, and writes the manifest and images for each client:

    assets/festivals/festivals.json and <id>/   the @3x images (the record)
    iOS/Festivals/festivals.json                 manifest for the keyboard and app
    iOS/Festivals.xcassets                       @2x and @3x image sets
    Resources/Festivals                          macOS: manifest, @1x and @2x
    Android/app/src/main/assets/festivals        manifest and @3x (nodpi) images
    Windows/UI/Assets/festivals                  manifest and @3x images

The festival dates live in DAYS below, not in the packs: the packs have none.
"""

import colorsys
import datetime
import hashlib
import json
import os
import shutil
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The festivals' own days per year: first day, main day, last day. The worn
# range starts LEAD_DAYS before the first day and ends on the last. Sources
# and conventions are in docs/FESTIVALS.md; the lunar, Hebrew, Islamic and
# Persian days are checked against the system calendars by
# iOS/tests/FestivalsTests.swift.
LEAD_DAYS = 7
YEARS = range(2026, 2031)


def one(*days):
    return [(d, d, d) for d in days]


def fixed(month_day):
    return one(*(f"{y}-{month_day}" for y in YEARS))


DAYS = {
    # 除夕, 初一, 初七.
    "spring_festival": [("2026-02-16", "2026-02-17", "2026-02-23"), ("2027-02-05", "2027-02-06", "2027-02-12"),
                        ("2028-01-25", "2028-01-26", "2028-02-01"), ("2029-02-12", "2029-02-13", "2029-02-19"),
                        ("2030-02-02", "2030-02-03", "2030-02-09")],
    "lantern_festival": one("2026-03-03", "2027-02-20", "2028-02-09", "2029-02-27", "2030-02-17"),  # 正月十五
    "dragon_boat": one("2026-06-19", "2027-06-09", "2028-05-28", "2029-06-16", "2030-06-05"),  # 五月初五
    "mid_autumn": one("2026-09-25", "2027-09-15", "2028-10-03", "2029-09-22", "2030-09-12"),  # 八月十五
    "tanabata": fixed("07-07"),
    # Thailand's three public holidays.
    "songkran": [(f"{y}-04-13", f"{y}-04-13", f"{y}-04-15") for y in YEARS],
    # Dhanteras, Lakshmi Puja, Bhai Dooj; Drik Panchang for New Delhi.
    "diwali": [("2026-11-06", "2026-11-08", "2026-11-11"), ("2027-10-27", "2027-10-29", "2027-10-31"),
               ("2028-10-15", "2028-10-17", "2028-10-19"), ("2029-11-04", "2029-11-05", "2029-11-07"),
               ("2030-10-24", "2030-10-26", "2030-10-28")],
    # 1 to 3 Shawwal, Umm al-Qura calendar.
    "eid": [("2026-03-20", "2026-03-20", "2026-03-22"), ("2027-03-09", "2027-03-09", "2027-03-11"),
            ("2028-02-26", "2028-02-26", "2028-02-28"), ("2029-02-14", "2029-02-14", "2029-02-16"),
            ("2030-02-04", "2030-02-04", "2030-02-06")],
    # The eight days from 25 Kislev; the first candle is lit the evening before.
    "hanukkah": [("2026-12-05", "2026-12-05", "2026-12-12"), ("2027-12-25", "2027-12-25", "2028-01-01"),
                 ("2028-12-13", "2028-12-13", "2028-12-20"), ("2029-12-02", "2029-12-02", "2029-12-09"),
                 ("2030-12-21", "2030-12-21", "2030-12-28")],
    "easter": one("2026-04-05", "2027-03-28", "2028-04-16", "2029-04-01", "2030-04-21"),  # Western reckoning
    "halloween": fixed("10-31"),
    "christmas": fixed("12-25"),
    "new_year": fixed("01-01"),
    "valentine": fixed("02-14"),
    "white_day": fixed("03-14"),
    "hinamatsuri": fixed("03-03"),
    "childrens_day": fixed("05-05"),
    "qixi": one("2026-08-19", "2027-08-08", "2028-08-26", "2029-08-16", "2030-08-05"),  # 七月初七
    "double_ninth": one("2026-10-18", "2027-10-08", "2028-10-26", "2029-10-16", "2030-10-05"),  # 九月初九
    # The solstice's date in UTC+8, as the Chinese calendar counts it.
    "winter_solstice": one("2026-12-22", "2027-12-22", "2028-12-21", "2029-12-21", "2030-12-22"),
    # Holika Dahan, then Rangwali Holi; Drik Panchang for New Delhi.
    "holi": [("2026-03-03", "2026-03-04", "2026-03-04"), ("2027-03-21", "2027-03-22", "2027-03-22"),
             ("2028-03-10", "2028-03-11", "2028-03-11"), ("2029-02-28", "2029-03-01", "2029-03-01"),
             ("2030-03-19", "2030-03-20", "2030-03-20")],
    # 1 Farvardin: the equinox's day in Tehran time if it falls before noon, else the next day.
    "nowruz": one("2026-03-21", "2027-03-21", "2028-03-20", "2029-03-20", "2030-03-21"),
    # Korea's three days, 八月十四 to 十六.
    "chuseok": [("2026-09-24", "2026-09-25", "2026-09-26"), ("2027-09-14", "2027-09-15", "2027-09-16"),
                ("2028-10-02", "2028-10-03", "2028-10-04"), ("2029-09-21", "2029-09-22", "2029-09-23"),
                ("2030-09-11", "2030-09-12", "2030-09-13")],
    # Midsummer Eve, the Friday from 19 to 25 June, and Midsummer Day after it.
    "midsummer": [("2026-06-19", "2026-06-19", "2026-06-20"), ("2027-06-25", "2027-06-25", "2027-06-26"),
                  ("2028-06-23", "2028-06-23", "2028-06-24"), ("2029-06-22", "2029-06-22", "2029-06-23"),
                  ("2030-06-21", "2030-06-21", "2030-06-22")],
}

# Simplified Chinese, Japanese and English. The Traditional name is the
# owner's, from the pack's manifest.
NAMES = {
    "spring_festival": ("春节", "春節", "Lunar New Year"),
    "lantern_festival": ("元宵", "元宵節", "Lantern Festival"),
    "dragon_boat": ("端午", "端午節", "Dragon Boat Festival"),
    "mid_autumn": ("中秋", "中秋節", "Mid-Autumn Festival"),
    "tanabata": ("七夕・日本", "七夕", "Tanabata"),
    "songkran": ("泼水节・泰国", "ソンクラーン", "Songkran"),
    "diwali": ("排灯节", "ディワリ", "Diwali"),
    "eid": ("开斋节", "イード・アル＝フィトル", "Eid al-Fitr"),
    "hanukkah": ("光明节", "ハヌカ", "Hanukkah"),
    "easter": ("复活节", "イースター", "Easter"),
    "halloween": ("万圣节", "ハロウィン", "Halloween"),
    "christmas": ("圣诞节", "クリスマス", "Christmas"),
    "new_year": ("新年", "お正月", "New Year"),
    "valentine": ("情人节", "バレンタインデー", "Valentine's Day"),
    "white_day": ("白色情人节", "ホワイトデー", "White Day"),
    "hinamatsuri": ("女儿节・日本", "ひな祭り", "Hinamatsuri"),
    "childrens_day": ("儿童节・日本", "こどもの日", "Children's Day (Japan)"),
    "qixi": ("七夕", "七夕（旧暦）", "Qixi Festival"),
    "double_ninth": ("重阳", "重陽の節句", "Double Ninth Festival"),
    "winter_solstice": ("冬至", "冬至", "Winter Solstice"),
    "holi": ("洒红节", "ホーリー", "Holi"),
    "nowruz": ("诺鲁孜节", "ノウルーズ", "Nowruz"),
    "chuseok": ("秋夕・韩国", "チュソク", "Chuseok"),
    "midsummer": ("仲夏节・瑞典", "夏至祭（スウェーデン）", "Midsummer (Sweden)"),
}

PARTS = ("mimimo", "momo", "frame")

# Capsule colours under white text (docs/DESIGN.md §4): light top 3.2:1 and
# bottom 4:1, dark at least 3:1, Increase Contrast 5:1. Each target has a
# little room above the rule.
TARGETS = {"light": (3.4, 4.3), "dark": (3.2, 4.0), "contrast": 5.3}


def stop(message):
    raise SystemExit(f"import_festivals: {message}")


def luminance(rgb):
    def channel(c):
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_with_white(rgb):
    return 1.05 / (luminance(rgb) + 0.05)


def hex_rgb(text):
    return tuple(int(text[i:i + 2], 16) / 255 for i in (1, 3, 5))


def rgb_hex(rgb):
    return "#" + "".join(f"{round(min(1, max(0, c)) * 255):02X}" for c in rgb)


def with_contrast(accent, ratio):
    """The accent's hue and saturation at the lightness that gives `ratio` under white."""
    h, l, s = colorsys.rgb_to_hls(*accent)
    low, high = 0.0, 1.0
    for _ in range(40):
        mid = (low + high) / 2
        if contrast_with_white(colorsys.hls_to_rgb(h, mid, s)) >= ratio:
            low = mid
        else:
            high = mid
    # Round to 8 bits, then step darker until the rounded colour still passes.
    rgb = hex_rgb(rgb_hex(colorsys.hls_to_rgb(h, low, s)))
    while contrast_with_white(rgb) < ratio:
        low -= 0.002
        rgb = hex_rgb(rgb_hex(colorsys.hls_to_rgb(h, low, s)))
    return rgb_hex(rgb)


def colours(accent_hex):
    accent = hex_rgb(accent_hex)
    return {
        "accent": accent_hex.upper(),
        "light": [with_contrast(accent, r) for r in TARGETS["light"]],
        "dark": [with_contrast(accent, r) for r in TARGETS["dark"]],
        "contrast": with_contrast(accent, TARGETS["contrast"]),
    }


def runs(values, threshold=60):
    out, start = [], None
    for i, v in enumerate(list(values) + [0]):
        if v > threshold and start is None:
            start = i
        elif v <= threshold and start is not None:
            out.append((start, i - 1))
            start = None
    return out


def rim(frame, scale):
    """Where the trim's rim runs, as insets in points from the frame's edges
    to the middle of the rim's stroke: top, left, bottom, right.

    Top and bottom are measured down the middle column, which the packs keep
    free of ornaments. The ends are under the ornaments, so the rim is taken
    to sit as far in from the sides as from the top, as drawn.
    """
    alpha = frame.getchannel("A")
    w, h = frame.size
    found = runs(alpha.getpixel((w // 2, y)) for y in range(h))
    if len(found) < 2:
        stop(f"expected two rim strokes down the middle of the trim, found {found}")
    (a, b), (c, d) = found[0], found[-1]
    top = round((a + b + 1) / 2 / scale, 2)
    bottom = round((h - (c + d + 1) / 2) / scale, 2)
    return [top, top, bottom, top]


def feet(sprite):
    """The lowest row with paint, as a fraction of the height."""
    alpha = sprite.getchannel("A").point(lambda v: 255 if v > 60 else 0)
    return alpha.getbbox()[3] / sprite.size[1]


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def read_packs(paths):
    """[(pack, theme)] in order, and every pack's checksums by (pack, name)."""
    sources, sums, seen = [], {}, {}
    for pack in paths:
        with open(os.path.join(pack, "SHA256SUMS.txt"), encoding="utf-8") as f:
            for line in f:
                digest, name = line.strip().split(None, 1)
                sums[(pack, name)] = digest
        with open(os.path.join(pack, "manifest.json"), encoding="utf-8") as f:
            themes = json.load(f)["themes"]
        for t in themes:
            if t["id"] not in seen:
                seen[t["id"]] = pack
                sources.append((pack, t))
                continue
            other = seen[t["id"]]
            for (p, name), digest in sums.items():
                if p == pack and name.startswith(f"png/{t['id']}/") and sums.get((other, name)) != digest:
                    stop(f"{name} differs between {os.path.basename(other)} and {os.path.basename(pack)}")
    return sources, sums


def main():
    if len(sys.argv) < 2:
        stop("usage: import_festivals.py PACK [PACK ...]")
    sources, sums = read_packs([os.path.abspath(p) for p in sys.argv[1:]])

    def checked(pack, relative):
        path = os.path.join(pack, relative)
        if sums.get((pack, relative)) != sha256(path):
            stop(f"{os.path.basename(pack)}/{relative} does not match SHA256SUMS.txt")
        return path

    ids = [t["id"] for _, t in sources]
    if sorted(ids) != sorted(DAYS) or sorted(ids) != sorted(NAMES):
        stop(f"the packs' themes {ids} are not the ones DAYS and NAMES describe")

    themes, images = [], {}
    for pack, t in sources:
        ident = t["id"]
        cat_w, cat_h = t["catLogicalSize"]
        frame_w, frame_h = t["frameLogicalSize"]
        sizes = {"mimimo": (cat_w, cat_h), "momo": (cat_w, cat_h), "frame": (frame_w, frame_h)}
        for part in PARTS:
            name = f"{ident}_{part}"
            for scale in (1, 2, 3):
                path = checked(pack, f"png/{ident}/{name}@{scale}x.png")
                img = Image.open(path)
                want = (round(sizes[part][0] * scale), round(sizes[part][1] * scale))
                if img.size != want or img.mode != "RGBA":
                    stop(f"{name}@{scale}x: expected RGBA {want}, got {img.mode} {img.size}")
                alpha = img.getchannel("A")
                w, h = img.size
                edge = [alpha.getpixel((x, y)) for x in range(w) for y in (0, h - 1)] + \
                       [alpha.getpixel((x, y)) for y in range(h) for x in (0, w - 1)]
                if max(edge) > 8:
                    stop(f"{name}@{scale}x: paint touches the edge of the image")
                images[(name, scale)] = path
            if part != "frame":
                at, anchor_y = feet(Image.open(images[(name, 3)])), t["catAnchorNormalized"][1]
                if abs(at - anchor_y) > 0.04:
                    stop(f"{name}: feet end at {at:.3f} of the height, the anchor says {anchor_y}")
        for first, main_day, last in DAYS[ident]:
            datetime.date.fromisoformat(first), datetime.date.fromisoformat(last)
            if not first <= main_day <= last:
                stop(f"{ident}: {first}, {main_day}, {last} are not in order")
        hans, ja, en = NAMES[ident]

        def sprite(part):
            return {"image": f"{ident}_{part}", "size": [cat_w, cat_h], "anchor": t["catAnchorNormalized"]}

        themes.append({
            "id": ident,
            "names": {"hant": t["name"], "hans": hans, "ja": ja, "en": en},
            "mimimo": sprite("mimimo"),
            "momo": sprite("momo"),
            "trim": {
                "image": f"{ident}_frame",
                "size": [frame_w, frame_h],
                "caps": [round(v, 2) for v in t["frameCapInsets"]],
                "safeText": t["safeTextInsets"],
                "rim": rim(Image.open(images[(f"{ident}_frame", 3)]), 3),
            },
            "colors": colours(t["accent"]),
            "days": [list(d) for d in DAYS[ident]],
        })

    packs = list(dict.fromkeys(os.path.basename(p) for p, _ in sources))
    manifest = {"version": 2, "leadDays": LEAD_DAYS, "packs": packs, "themes": themes}
    text = json.dumps(manifest, ensure_ascii=False, indent=1) + "\n"

    def fresh(directory):
        if os.path.isdir(directory):
            shutil.rmtree(directory)
        os.makedirs(directory)
        with open(os.path.join(directory, "festivals.json"), "w", encoding="utf-8") as f:
            f.write(text)

    record = os.path.join(ROOT, "assets", "festivals")
    fresh(record)
    for t in themes:
        os.makedirs(os.path.join(record, t["id"]))
        for part in PARTS:
            name = f"{t['id']}_{part}"
            shutil.copyfile(images[(name, 3)], os.path.join(record, t["id"], name + ".png"))

    # iOS: the manifest in the Festivals folder, the art in an asset catalog
    # so the App Store hands each device only its own scale.
    fresh(os.path.join(ROOT, "iOS", "Festivals"))
    catalog = os.path.join(ROOT, "iOS", "Festivals.xcassets")
    if os.path.isdir(catalog):
        shutil.rmtree(catalog)
    os.makedirs(catalog)
    info = {"info": {"author": "xcode", "version": 1}}
    with open(os.path.join(catalog, "Contents.json"), "w") as f:
        json.dump(info, f, indent=2)
        f.write("\n")
    for t in themes:
        for part in PARTS:
            name = f"{t['id']}_{part}"
            folder = os.path.join(catalog, name + ".imageset")
            os.makedirs(folder)
            entries = []
            for scale in (2, 3):
                shutil.copyfile(images[(name, scale)], os.path.join(folder, f"{name}@{scale}x.png"))
                entries.append({"filename": f"{name}@{scale}x.png", "idiom": "universal", "scale": f"{scale}x"})
            with open(os.path.join(folder, "Contents.json"), "w") as f:
                json.dump({"images": entries, **info}, f, indent=2)
                f.write("\n")

    # macOS: @1x and @2x next to the manifest.
    mac = os.path.join(ROOT, "Resources", "Festivals")
    fresh(mac)
    for (name, scale), path in images.items():
        if scale in (1, 2):
            shutil.copyfile(path, os.path.join(mac, name + ("@2x" if scale == 2 else "") + ".png"))

    # Android and Windows: one @3x image each, sized in code from the manifest.
    for directory in (os.path.join(ROOT, "Android", "app", "src", "main", "assets", "festivals"),
                      os.path.join(ROOT, "Windows", "UI", "Assets", "festivals")):
        fresh(directory)
        for (name, scale), path in images.items():
            if scale == 3:
                shutil.copyfile(path, os.path.join(directory, name + ".png"))

    for t in themes:
        c = t["colors"]
        print(f"{t['id']:17} rim {t['trim']['rim']}  light {c['light']}  dark {c['dark']}  contrast {c['contrast']}")
    print(f"{len(themes)} themes imported from {', '.join(packs)}")


if __name__ == "__main__":
    main()
