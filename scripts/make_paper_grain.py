#!/usr/bin/env python3
"""Make the tileable paper grain, 128x128, with a fixed seed (same bytes every run).

    python3 scripts/make_paper_grain.py              write assets/brand/paper/grain.png and the Android copy
    python3 scripts/make_paper_grain.py --ios-root DIR  also write the iOS asset catalog (default: ../ios-art if present)
    python3 scripts/make_paper_grain.py --check      write nothing; exit 1 if a file differs

The PNG is white with the grain in its alpha channel (8-bit grey + alpha), so
every client tints it with the palette's `grain` colour and the colour's alpha
scales it. It wraps at the edges, so it tiles without a seam. Standard
library only.

Copies:
  assets/brand/paper/grain.png
  Android/app/src/main/res/drawable-nodpi/paper_grain.png
  <ios>/iOS/PaperArt.xcassets/paper_grain.imageset/paper_grain.png (+ Contents.json)
"""
import argparse
import os
import random
import struct
import sys
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SIZE = 128
SEED = 20261114


def wrapped_blur(src, radius):
    """Box blur on a torus, so the tile stays seamless."""
    n = SIZE
    tmp = [[0.0] * n for _ in range(n)]
    out = [[0.0] * n for _ in range(n)]
    w = 2 * radius + 1
    for y in range(n):
        for x in range(n):
            tmp[y][x] = sum(src[y][(x + d) % n] for d in range(-radius, radius + 1)) / w
    for y in range(n):
        for x in range(n):
            out[y][x] = sum(tmp[(y + d) % n][x] for d in range(-radius, radius + 1)) / w
    return out


def make_grain():
    rng = random.Random(SEED)
    fine = [[rng.random() for _ in range(SIZE)] for _ in range(SIZE)]
    # Fibres: short horizontal and vertical streaks, wrapped.
    fibre = [[0.0] * SIZE for _ in range(SIZE)]
    for _ in range(260):
        x, y = rng.randrange(SIZE), rng.randrange(SIZE)
        length = rng.randrange(4, 14)
        dx, dy = rng.choice(((1, 0), (0, 1), (1, 1), (1, -1)))
        for i in range(length):
            fibre[(y + dy * i) % SIZE][(x + dx * i) % SIZE] += 0.6
    soft = wrapped_blur(fine, 1)
    rows = []
    for y in range(SIZE):
        row = bytearray()
        for x in range(SIZE):
            v = 0.55 * fine[y][x] + 0.45 * soft[y][x] + fibre[y][x]
            # Stretch to the full range so the token alpha alone sets the strength.
            a = max(0, min(255, int((v - 0.35) / 0.9 * 255)))
            row += bytes((255, a))
        rows.append(bytes(row))
    return rows


def png(rows):
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + r for r in rows)
    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 4, 0, 0, 0)  # 8-bit grey + alpha
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


CONTENTS = """{
  "images" : [
    {
      "filename" : "paper_grain.png",
      "idiom" : "universal",
      "scale" : "1x"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  },
  "properties" : {
    "template-rendering-intent" : "template"
  }
}
"""

CATALOG = """{
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
"""


def write(path, data, check):
    mode = "wb" if isinstance(data, bytes) else "w"
    old = None
    if os.path.exists(path):
        with open(path, "rb" if mode == "wb" else "r") as f:
            old = f.read()
    if old == data:
        print("same   " + path)
        return False
    if check:
        print("DIFFERS " + path)
        return True
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(data)
    print("wrote  " + path)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=ROOT)
    ap.add_argument("--ios-root", default=None)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    data = png(make_grain())
    targets = [
        (os.path.join(args.root, "assets/brand/paper/grain.png"), data),
        (os.path.join(args.root, "Android/app/src/main/res/drawable-nodpi/paper_grain.png"), data),
    ]
    ios = args.ios_root or os.path.join(os.path.dirname(args.root), "ios-art")
    if os.path.isdir(os.path.join(ios, "iOS")):
        cat = os.path.join(ios, "iOS", "PaperArt.xcassets")
        targets += [
            (os.path.join(cat, "Contents.json"), CATALOG),
            (os.path.join(cat, "paper_grain.imageset", "Contents.json"), CONTENTS),
            (os.path.join(cat, "paper_grain.imageset", "paper_grain.png"), data),
        ]
    differs = [write(p, d, args.check) for p, d in targets]
    return 1 if any(differs) else 0


if __name__ == "__main__":
    sys.exit(main())
