#!/usr/bin/env python3
"""WCAG 2.x contrast check for assets/brand/paper/paper-tokens.json.

    python3 scripts/check_palette_contrast.py            print every pair, exit 1 if any fails
    python3 scripts/check_palette_contrast.py --root DIR use DIR as the repo root

Text needs 4.5:1. Large text and UI components (the pill against the paper)
need 3:1. Translucent tape is composited over `paper` before measuring.
"""
import argparse
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TEXT, UI = 4.5, 3.0


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def over(fg, bg, alpha):
    return tuple(fg[i] * alpha + bg[i] * (1 - alpha) for i in range(3))


def lum(c):
    def f(x):
        x /= 255
        return x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2])


def ratio(a, b):
    la, lb = lum(a), lum(b)
    if la < lb:
        la, lb = lb, la
    return (la + 0.05) / (lb + 0.05)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=ROOT)
    args = ap.parse_args()
    with open(os.path.join(args.root, "assets", "brand", "paper", "paper-tokens.json"), encoding="utf-8") as f:
        data = json.load(f)
    tape_alpha = data["tapeAlpha"]
    failures = total = 0
    for mode, themes in data["modes"].items():
        for theme, t in themes.items():
            c = {k: rgb(v) for k, v in t.items()}
            tape_a = over(c["tapeA"], c["paper"], tape_alpha)
            tape_b = over(c["tapeB"], c["paper"], tape_alpha)
            pairs = [
                ("ink on paper", c["ink"], c["paper"], TEXT),
                ("ink on paper2", c["ink"], c["paper2"], TEXT),
                ("inkSecondary on paper", c["inkSecondary"], c["paper"], TEXT),
                ("inkSecondary on paper2", c["inkSecondary"], c["paper2"], TEXT),
                ("pillText on pill", c["pillText"], c["pill"], TEXT),
                ("stamp (text) on paper", c["stamp"], c["paper"], TEXT),
                ("stamp (graphic) on paper2", c["stamp"], c["paper2"], UI),
                ("pill (UI) on paper", c["pill"], c["paper"], UI),
                ("ink on tapeA", c["ink"], tape_a, TEXT),
                ("ink on tapeB", c["ink"], tape_b, TEXT),
            ]
            for name, fg, bg, need in pairs:
                r = ratio(fg, bg)
                ok = r >= need
                total += 1
                failures += not ok
                print(f"{'ok  ' if ok else 'FAIL'} {mode:5} {theme:9} {name:26} {r:6.2f} (need {need})")
    print(f"{total - failures}/{total} pairs pass")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
