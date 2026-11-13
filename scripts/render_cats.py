#!/usr/bin/env python3
"""Import 米米莫 and 莫莫 from the owner's asset pack and copy them to every client.

    python3 scripts/render_cats.py --pack DIR   read the pack into assets/brand/cats, then write every client
    python3 scripts/render_cats.py              write every client again from assets/brand/cats
    python3 scripts/render_cats.py --check      write nothing; stop if a client's files differ from the import
    python3 scripts/render_cats.py --self-test  run the importer on a temporary copy and on broken copies
    python3 scripts/render_cats.py --root DIR   use DIR as the repo root

Nothing is drawn here. The pictures are the pack's own PNGs; this script
copies them, measures where each cat touches the bar, and stops when a size
or an anchor is not what the clients expect.

From the pack (--pack):
  software/png/*@{1,2,3}x.png        -> assets/brand/cats/sprites/
  software/master-512, edge-masters  -> assets/brand/cats/masters/
  manifest.json, edge-anchors.json,
  README.txt                         -> assets/brand/cats/pack/
  preview/candidate-poses.png,
  preview/contact-sheet.png,
  sources/hero.png                   -> assets/brand/cats/reference/
Every file taken is checked against the pack's SHA256SUMS.txt.

Measured and written:
  assets/brand/cats/cats.json        size, anchor, opaque bounds, the flat
                                     cut and the paws of every sprite

To the clients (the edge poses, which are the ones on the candidate bar):
  Resources/Cats                     macOS @1x/@2x PNGs
  Android/app/src/main/res/drawable-nodpi/cat_*.png   3 px per point
  Windows/UI/Assets/*.png            3 px per point
  Sources/CatSpriteData.swift, Android .../ime/CatSpriteData.kt,
  Windows/UI/CatSpriteData.h         the numbers in cats.json as code

Festival art has its own importer, scripts/import_festivals.py.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

CATS = ("mimimo", "momo")
# The poses made for the candidate bar. `top` poses put their paws on the
# bar's upper edge; `side` holds the bar's end.
EDGE_POSES = {"edge_rest": "top", "edge_peek": "top", "edge_lounge": "top", "side_hold": "side"}
EDGE_SPRITES = [f"{cat}_{pose}" for cat in CATS for pose in EDGE_POSES]
SCALES = (1, 2, 3)
OPAQUE = 128        # alpha that counts as the cat
VISIBLE = 24        # alpha that counts for the outer bounds


def cats_dir():
    return os.path.join(ROOT, "assets", "brand", "cats")



def stop(message):
    raise SystemExit("render_cats: " + message)


# --------------------------------------------------------------------------
# Reading the pack
# --------------------------------------------------------------------------

def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def import_pack(pack):
    """Copy what the product needs out of the pack. The pack is only read."""
    pack = os.path.abspath(pack)
    sums = {}
    with open(os.path.join(pack, "SHA256SUMS.txt"), encoding="utf-8") as f:
        for line in f:
            if line.strip():
                digest, name = line.split(None, 1)
                sums[name.strip()] = digest
    with open(os.path.join(pack, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    names = [entry["id"] for entry in manifest["sprites"] + manifest["edgeSprites"]]
    basic = [entry["id"] for entry in manifest["sprites"]]

    target = cats_dir()
    # design_reference.png (the bar's four drafts) stays; only the pack's folders are replaced.
    for folder in ("sprites", "masters", "pack", "reference"):
        shutil.rmtree(os.path.join(target, folder), ignore_errors=True)
    plan = [("manifest.json", "pack/manifest.json"), ("software/edge-anchors.json", "pack/edge-anchors.json"),
            ("README.txt", "pack/README.txt"), ("preview/candidate-poses.png", "reference/candidate-poses.png"),
            ("preview/contact-sheet.png", "reference/contact-sheet.png"), ("sources/hero.png", "reference/hero.png")]
    for name in names:
        for scale in SCALES:
            plan.append((f"software/png/{name}@{scale}x.png", f"sprites/{name}@{scale}x.png"))
        folder = "master-512" if name in basic else "edge-masters"
        plan.append((f"software/{folder}/{name}.png", f"masters/{name}.png"))
    for source, destination in plan:
        path = os.path.join(pack, source)
        if source not in sums:
            stop(f"{source} is not listed in the pack's SHA256SUMS.txt")
        if sha256(path) != sums[source]:
            stop(f"{source} does not match the pack's SHA256SUMS.txt")
        out = os.path.join(target, destination)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        shutil.copyfile(path, out)
    print(f"read {len(plan)} files from {pack}")


# --------------------------------------------------------------------------
# Measuring
# --------------------------------------------------------------------------

def runs(alpha, row, threshold=OPAQUE):
    """Opaque column ranges [first, last] in one row."""
    width = alpha.size[0]
    found, start = [], None
    for x in range(width):
        solid = alpha.getpixel((x, row)) > threshold
        if solid and start is None:
            start = x
        if not solid and start is not None:
            found.append((start, x - 1))
            start = None
    if start is not None:
        found.append((start, width - 1))
    return found


def measure(image, size, anchor, kind, label):
    """What clients need to seat one sprite, as fractions of its canvas.

    `image` is the 3x picture. Stops when the picture is not built the way
    its pose says: a top pose is a cat whose body ends in a flat cut on the
    anchor row with only its paws below; a side pose has its gripping paw
    at the anchor; a sitting pose stands on its anchor row.
    """
    width, height = image.size
    alpha = image.getchannel("A")
    if alpha.getextrema()[0] != 0:
        stop(f"{label}: no transparent background")
    box = alpha.point(lambda v: 255 if v > VISIBLE else 0).getbbox()
    if box is None or box[0] == 0 or box[1] == 0 or box[2] == width or box[3] == height:
        stop(f"{label}: the cat touches the canvas edge; ears and tail need a transparent margin")
    result = {
        "size": [size[0], size[1]],
        "anchor": [round(anchor[0], 4), round(anchor[1], 4)],
        "bounds": [round(box[0] / width, 4), round(box[1] / height, 4), round(box[2] / width, 4), round(box[3] / height, 4)],
    }
    row = round(anchor[1] * height)
    if kind == "top":
        above = runs(alpha, row - 3)
        body = max(above, key=lambda r: r[1] - r[0], default=None)
        if body is None or (body[1] - body[0]) < 0.4 * (box[2] - box[0]):
            stop(f"{label}: no body above the anchor row")
        if not body[0] <= anchor[0] * width <= body[1]:
            stop(f"{label}: the anchor is not over the body")
        # Below the anchor row only the paws are left: most of the body's span is clear.
        under = runs(alpha, row + 2)
        covered = sum(b - a + 1 for a, b in under)
        if covered > 0.75 * (body[1] - body[0] + 1):
            stop(f"{label}: the body is not cut on the anchor row ({anchor[1]:.3f})")
        still_body = runs(alpha, row - 6)
        if not still_body or max(b - a for a, b in still_body) < 0.9 * (body[1] - body[0]):
            stop(f"{label}: the body ends above the anchor row")
        paws = []
        for first, last in runs(alpha, row):
            depth = 0
            middle = (first + last) // 2
            while row + depth < height and alpha.getpixel((middle, row + depth)) > OPAQUE:
                depth += 1
            if depth >= 2 and last - first >= 5:
                paws.append([round((first + last + 1) / 2 / width, 4), round((last - first + 1) / 2 / width, 4),
                             round(depth / height, 4)])
        if not paws:
            stop(f"{label}: no paw reaches below the anchor row")
        if box[3] - row > 0.2 * height:
            stop(f"{label}: something hangs far below the anchor row")
        # The cut runs from the first to the last thing that ends on the bar (a tail lies beside the body).
        ends = [r for r in above if r[1] - r[0] >= 5]
        result["cut"] = [round(ends[0][0] / width, 4), round((ends[-1][1] + 1) / width, 4)]
        result["paws"] = paws
    elif kind == "side":
        # The paw's tip touches the bar's end at the anchor, from outside.
        x = round(anchor[0] * width)
        reach = round(0.07 * width)
        near = [alpha.getpixel((min(width - 1, max(0, x + dx)), min(height - 1, max(0, row + dy))))
                for dx in range(-reach, reach + 1) for dy in range(-reach, reach + 1)]
        if max(near) <= OPAQUE:
            stop(f"{label}: no paw at the anchor")
    else:
        if abs(box[3] - row) > 0.012 * height:
            stop(f"{label}: the cat does not stand on its anchor row")
    return result


def load_sprite(directory, name, size):
    """The three scales of one sprite, sizes checked; returns the 3x image."""
    images = {}
    for scale in SCALES:
        path = os.path.join(directory, f"{name}@{scale}x.png")
        if not os.path.exists(path):
            stop(f"{name}@{scale}x.png is missing")
        image = Image.open(path)
        if image.mode != "RGBA":
            stop(f"{name}@{scale}x.png is {image.mode}, expected RGBA")
        if image.size != (size[0] * scale, size[1] * scale):
            stop(f"{name}@{scale}x.png is {image.size}, expected {(size[0] * scale, size[1] * scale)}")
        images[scale] = image
    return images[3]


def build_metrics():
    """cats.json: the pack's sizes and anchors plus what is measured on the pixels."""
    pack = os.path.join(cats_dir(), "pack")
    with open(os.path.join(pack, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    with open(os.path.join(pack, "edge-anchors.json"), encoding="utf-8") as f:
        edge = {entry["id"]: entry for entry in json.load(f)}
    sprites = {}
    for entry in manifest["sprites"]:
        size = (entry["logicalSize"], entry["logicalSize"])
        image = load_sprite(os.path.join(cats_dir(), "sprites"), entry["id"], size)
        sprites[entry["id"]] = measure(image, size, entry["anchorNormalized"], "sit", entry["id"])
    for entry in manifest["edgeSprites"]:
        if edge.get(entry["id"], {}).get("anchorNormalized") != entry["anchorNormalized"]:
            stop(f"{entry['id']}: manifest.json and edge-anchors.json disagree")
        pose = entry["id"].split("_", 1)[1]
        if pose not in EDGE_POSES:
            stop(f"{entry['id']}: unknown pose")
        image = load_sprite(os.path.join(cats_dir(), "sprites"), entry["id"], tuple(entry["logicalSize"]))
        sprites[entry["id"]] = measure(image, entry["logicalSize"], entry["anchorNormalized"], EDGE_POSES[pose], entry["id"])
    missing = [name for name in EDGE_SPRITES if name not in sprites]
    if missing:
        stop("the pack lacks " + ", ".join(missing))
    return {"pack": {"name": manifest["name"], "version": manifest["version"]}, "sprites": sprites}


# --------------------------------------------------------------------------
# Writing: every output is collected first, so --check can compare.
# --------------------------------------------------------------------------

class Outputs:
    def __init__(self):
        self.files = {}      # path relative to ROOT -> bytes
        self.folders = []    # folders this import owns entirely (stale files are removed)

    def add(self, path, data):
        self.files[path] = data if isinstance(data, bytes) else data.encode("utf-8")

    def copy(self, path, source):
        with open(source, "rb") as f:
            self.files[path] = f.read()

    def own(self, folder, keep=lambda name: False):
        self.folders.append((folder, keep))

    def stale(self):
        found = []
        for folder, keep in self.folders:
            base = os.path.join(ROOT, folder)
            for directory, _, names in os.walk(base):
                for name in names:
                    relative = os.path.relpath(os.path.join(directory, name), ROOT)
                    if relative not in self.files and not keep(os.path.relpath(os.path.join(directory, name), base)) \
                            and name != ".DS_Store":
                        found.append(relative)
        return sorted(found)

    def differences(self):
        changed = []
        for path, data in sorted(self.files.items()):
            full = os.path.join(ROOT, path)
            if not os.path.exists(full):
                changed.append(path + " (missing)")
            else:
                with open(full, "rb") as f:
                    if f.read() != data:
                        changed.append(path)
        return changed + [path + " (not part of the import)" for path in self.stale()]

    def write(self):
        for path in self.stale():
            os.remove(os.path.join(ROOT, path))
        for folder, _ in self.folders:
            base = os.path.join(ROOT, folder)
            for directory, _, _ in sorted(os.walk(base), reverse=True):
                if directory != base and not os.listdir(directory):
                    os.rmdir(directory)
        for path, data in self.files.items():
            full = os.path.join(ROOT, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            if os.path.exists(full):
                with open(full, "rb") as f:
                    if f.read() == data:
                        continue
            with open(full, "wb") as f:
                f.write(data)


def number(value):
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return text if text else "0"


def real(value):
    """A C++ float literal: it needs a decimal point (0f is not one)."""
    text = number(value)
    return (text if "." in text else text + ".0") + "f"


def swift_data(metrics):
    lines = ["// Generated by scripts/render_cats.py from assets/brand/cats/cats.json. Do not edit.",
             "import CoreGraphics", "",
             "extension CatSprite {",
             "    /// The sprites on the candidate bar, by asset name.",
             "    static let all: [String: CatSprite] = ["]
    for name in EDGE_SPRITES:
        s = metrics["sprites"][name]
        b = s["bounds"]
        cut = s.get("cut", [0, 0])
        paws = ", ".join(f"CatSprite.Paw(x: {number(p[0])}, halfWidth: {number(p[1])}, depth: {number(p[2])})" for p in s.get("paws", []))
        lines.append(f'        "{name}": CatSprite(name: "{name}", size: CGSize(width: {s["size"][0]}, height: {s["size"][1]}), '
                     f'anchor: CGPoint(x: {number(s["anchor"][0])}, y: {number(s["anchor"][1])}),')
        lines.append(f'            bounds: CGRect(x: {number(b[0])}, y: {number(b[1])}, width: {number(b[2] - b[0])}, height: {number(b[3] - b[1])}), '
                     f'cut: {number(cut[0])}...{number(cut[1])}, paws: [{paws}]),')
    lines += ["    ]", "}", ""]
    return "\n".join(lines)


def kotlin_data(metrics):
    lines = ["// Generated by scripts/render_cats.py from assets/brand/cats/cats.json. Do not edit.",
             "package com.krysta1ll.meowpuut.android.ime", "",
             "/** One cat picture: size in dp, and where it touches the bar, as fractions of its canvas. */",
             "data class CatSprite(",
             "    val name: String, val width: Float, val height: Float, val anchorX: Float, val anchorY: Float,",
             "    val left: Float, val top: Float, val right: Float, val bottom: Float,",
             "    val cutLeft: Float, val cutRight: Float, val paws: List<FloatArray>,",
             ")", "",
             "object CatSpriteData {",
             "    val all: Map<String, CatSprite> = mapOf("]
    for name in EDGE_SPRITES:
        s = metrics["sprites"][name]
        b = s["bounds"]
        cut = s.get("cut", [0, 0])
        paws = ", ".join("floatArrayOf(" + ", ".join(number(v) + "f" for v in p) + ")" for p in s.get("paws", []))
        lines.append(f'        "{name}" to CatSprite("{name}", {s["size"][0]}f, {s["size"][1]}f, {number(s["anchor"][0])}f, {number(s["anchor"][1])}f,')
        lines.append(f'            {number(b[0])}f, {number(b[1])}f, {number(b[2])}f, {number(b[3])}f, {number(cut[0])}f, {number(cut[1])}f, listOf({paws})),')
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def cpp_data(metrics):
    lines = ["// Generated by scripts/render_cats.py from assets/brand/cats/cats.json. Do not edit.",
             "#pragma once", "", "namespace meowpuut {", "",
             "struct CatPaw { float x, halfWidth, depth; };", "",
             "// One cat picture: size in points, and where it touches the bar, as fractions of its canvas.",
             "struct CatSprite {",
             "    const char* name;",
             "    float width, height, anchorX, anchorY;",
             "    float left, top, right, bottom;",
             "    float cutLeft, cutRight;",
             "    int pawCount;",
             "    CatPaw paws[4];",
             "};", "",
             f"inline constexpr CatSprite kCatSprites[{len(EDGE_SPRITES)}] = {{"]
    for name in EDGE_SPRITES:
        s = metrics["sprites"][name]
        b = s["bounds"]
        cut = s.get("cut", [0, 0])
        paws = s.get("paws", [])
        if len(paws) > 4:
            stop(f"{name}: more than four paws")
        paw_text = ", ".join("{" + ", ".join(real(v) for v in p) + "}" for p in paws) or "{0.0f, 0.0f, 0.0f}"
        values = [s["size"][0], s["size"][1], s["anchor"][0], s["anchor"][1], b[0], b[1], b[2], b[3], cut[0], cut[1]]
        lines.append(f'    {{"{name}", ' + ", ".join(real(v) for v in values) + f', {len(paws)}, {{{paw_text}}}}},')
    lines += ["};", "", "}  // namespace meowpuut", ""]
    return "\n".join(lines)


def collect_platforms(out, metrics):
    sprites = os.path.join(cats_dir(), "sprites")
    out.add("assets/brand/cats/cats.json", json.dumps(metrics, indent=2, ensure_ascii=False) + "\n")

    out.own("Resources/Cats")
    # Android and Windows keep other files in these folders: only the cats are replaced.
    out.own("Android/app/src/main/res/drawable-nodpi", keep=lambda name: not name.startswith("cat_"))
    out.own("Windows/UI/Assets", keep=lambda name: not re.fullmatch(r"(mimimo|momo)_[a-z_]+\.png", name))
    for name in EDGE_SPRITES:
        out.copy(f"Resources/Cats/{name}.png", os.path.join(sprites, f"{name}@1x.png"))
        out.copy(f"Resources/Cats/{name}@2x.png", os.path.join(sprites, f"{name}@2x.png"))
        out.copy(f"Android/app/src/main/res/drawable-nodpi/cat_{name}.png", os.path.join(sprites, f"{name}@3x.png"))
        out.copy(f"Windows/UI/Assets/{name}.png", os.path.join(sprites, f"{name}@3x.png"))
    out.add("Sources/CatSpriteData.swift", swift_data(metrics))
    out.add("Android/app/src/main/java/com/krysta1ll/meowpuut/android/ime/CatSpriteData.kt", kotlin_data(metrics))
    out.add("Windows/UI/CatSpriteData.h", cpp_data(metrics))


# --------------------------------------------------------------------------

def collect():
    metrics = build_metrics()
    out = Outputs()
    collect_platforms(out, metrics)
    return out, metrics


def self_test():
    """Runs the import on a temporary copy of the art, then on broken copies."""
    global ROOT
    real = ROOT
    with tempfile.TemporaryDirectory() as scratch:
        for folder in ("assets/brand/cats",):
            shutil.copytree(os.path.join(real, folder), os.path.join(scratch, folder))
        ROOT = scratch
        sprites = os.path.join(scratch, "assets", "brand", "cats", "sprites")

        def fails(label, needle):
            try:
                collect()
            except SystemExit as error:
                if needle not in str(error):
                    stop(f"self-test: {label} stopped for another reason: {error}")
                return
            stop(f"self-test: {label} was accepted")

        out, metrics = collect()
        out.write()
        if out.differences():
            stop("self-test: a second run finds differences")
        for name in EDGE_SPRITES:
            sprite = metrics["sprites"][name]
            for path, scale in ((f"Resources/Cats/{name}.png", 1),
                                (f"Android/app/src/main/res/drawable-nodpi/cat_{name}.png", 3), (f"Windows/UI/Assets/{name}.png", 3)):
                if Image.open(os.path.join(scratch, path)).size != (sprite["size"][0] * scale, sprite["size"][1] * scale):
                    stop(f"self-test: {path} has the wrong size")
        # A client file that was edited, and one the import does not know.
        with open(os.path.join(scratch, "Resources/Cats/momo_edge_peek.png"), "ab") as f:
            f.write(b"x")
        open(os.path.join(scratch, "Resources/Cats/mimimo_open.png"), "wb").close()
        if out.differences() != ["Resources/Cats/momo_edge_peek.png", "Resources/Cats/mimimo_open.png (not part of the import)"]:
            stop(f"self-test: --check missed a changed or stale file: {out.differences()}")

        peek = os.path.join(sprites, "mimimo_edge_peek@3x.png")
        original = Image.open(peek).copy()
        moved = Image.new("RGBA", original.size, (0, 0, 0, 0))
        moved.paste(original, (0, -14))
        moved.save(peek)
        fails("a cat moved off its anchor", "mimimo_edge_peek")
        original.resize((190, 190)).save(peek)
        fails("a sprite of the wrong size", "expected (192, 192)")
        original.convert("RGB").save(peek)
        fails("a sprite without transparency", "expected RGBA")
        original.save(peek)
        os.remove(os.path.join(sprites, "momo_side_hold@2x.png"))
        fails("a missing scale", "momo_side_hold@2x.png is missing")
        ROOT = real
    print("PASS: the import is repeatable and sized for every client; a changed or stale client file is reported; "
          "a moved anchor, a wrong size, a missing alpha channel and a missing scale are refused")


def main():
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--pack", metavar="DIR", help="the owner's asset pack (read only)")
    parser.add_argument("--check", action="store_true", help="write nothing; fail if anything differs")
    parser.add_argument("--self-test", action="store_true", help="check the importer itself")
    parser.add_argument("--root", metavar="DIR", help="repo root (default: this checkout)")
    args = parser.parse_args()
    if args.root:
        ROOT = os.path.abspath(args.root)
    if args.self_test:
        self_test()
        return
    if args.pack:
        if args.check:
            stop("--check reads the repository, not the pack")
        import_pack(args.pack)
    out, metrics = collect()
    if args.check:
        changed = out.differences()
        if changed:
            print("FAIL: these differ from what the import writes:")
            for path in changed:
                print("  " + path)
            sys.exit(1)
        print(f"PASS: {len(metrics['sprites'])} sprites measured; {len(out.files)} client files match the import")
        return
    out.write()
    print(f"cats written for macOS, Android and Windows ({len(out.files)} files)")


if __name__ == "__main__":
    main()
