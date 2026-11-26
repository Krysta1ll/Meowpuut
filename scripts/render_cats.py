#!/usr/bin/env python3
"""Import 米米莫 and 莫莫 from the owner's asset pack and copy them to every client.

    python3 scripts/render_cats.py --pack DIR   read the pack into assets/brand/cats, then write every client
    python3 scripts/render_cats.py              write every client again from assets/brand/cats
    python3 scripts/render_cats.py --check      write nothing; stop if a client's files differ from the import
    python3 scripts/render_cats.py --desk DIR   add the desktop-pet poses: DIR holds the normalised 512-px masters
                                                (design-preview/pc/clay-tools/normalize_poses.py output)
    python3 scripts/render_cats.py --desk-3d DIR  switch the desktop poses the owner chose to the 3D renders: DIR is
                                                clay-3d-stage1/round-9 (desk-3d.json, sprites/, sprites/walk/)
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

To the clients:
  Android/app/src/main/res/drawable-nodpi/cat_*.png   same poses at 3 px per point
  Resources/Cats                     macOS edge poses (and desktop poses), @1x/@2x PNGs
  Windows/UI/Assets/*.png            edge poses (and desktop poses) at 3 px per point
  Sources/CatSpriteData.swift        geometry of all poses
  Android .../ime/CatSpriteData.kt, Windows/UI/CatSpriteData.h   edge geometry

Desktop-pet poses (walk, turn, look_up, lift, fall, momo_sleep; CLAY-POSES.md P0) are not part of the pack:
--desk-3d DIR takes the poses the owner switched to the 3D renders (both cats' walk as a full 12-frame cycle, turn, fall,
momo_lift with the rig's own scruff anchor), copies their masters, and records the set in pack/desk-set.json (cycles in
frame order, fps, playback, anchors); every other pose stays what --desk imported (GPT). --desk DIR copies the masters to assets/brand/cats/masters/, resamples sprites/<name>@{1,2,3}x.png from them
(Lanczos, nothing else touched), and writes the "deskSprites" entries of pack/manifest.json. Every run then
validates them and writes the "desk" section of cats.json (size, anchor, bounds, facing, cycle, walk foot
contacts), copies them to Resources/Cats (@1x/@2x) and Windows/UI/Assets (3x) and writes
Resources/Cats/desk.json and Windows/UI/Assets/desk.json. They are not in the generated CatSpriteData files.

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

# Desktop-pet poses (CLAY-POSES.md P0). name -> (canvas size in px of the master, logical size in pt, facing, kind)
# kind: stand = feet on the anchor row; air = nominal anchor, nothing touches the ground; hang = held at the scruff.
DESK_KINDS = {
    "walk_1": ((768, 512), (96, 64), "right", "stand"), "walk_2": ((768, 512), (96, 64), "right", "stand"),
    "walk_3": ((768, 512), (96, 64), "right", "stand"), "walk_4": ((768, 512), (96, 64), "right", "stand"),
    "turn": ((512, 512), (64, 64), "front", "stand"), "look_up": ((512, 512), (64, 64), "front", "stand"),
    "lift": ((512, 640), (64, 80), "front", "hang"), "fall": ((512, 512), (64, 64), "front", "air"),
    "sleep": ((512, 512), (64, 64), "front", "stand"),
}
DESK_BASE = [f"{cat}_{pose}" for cat in CATS for pose in DESK_KINDS if not (pose == "sleep" and cat == "mimimo")]
# The poses that come from the 3D renders (--desk-3d); the walk is the cycles of desk-3d.json.
DESK_3D_POSES = ("turn", "fall", "lift")
DESK_3D_SKIP = {"mimimo_lift"}   # the owner keeps the GPT lift for Mimimo
DESK_SET = os.path.join("pack", "desk-set.json")
DESK_STAND_ANCHOR = (0.5, 0.9)
DESK_HANG_ANCHOR = (0.5, 0.06)   # the scruff is not reliably visible in the art; the normaliser puts the cat's top here
EYE_TOLERANCE = 0.03             # walk frames: eye spacing may differ by this much
DESK_ARTWORK = ("Desktop-pet poses (2026-11-23): the 3D renders of clay-3d-stage1 round-9 (both walks as 12-frame cycles, "
                "turn, fall, momo_lift) and, kept as they were, the GPT image-generation illustrations from the 06 pack "
                "references (mimimo_look_up, momo_look_up, mimimo_lift, momo_sleep), normalised by "
                "design-preview/pc/clay-tools/normalize_poses.py; no 3D rig or mesh included.")
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
    # The desktop poses are not in the pack: keep their files across the replacement.
    kept = {}
    desk_names = desk_sprites()
    for name in desk_names:
        for relative in [f"masters/{name}.png"] + [f"sprites/{name}@{scale}x.png" for scale in SCALES]:
            path = os.path.join(target, relative)
            if os.path.exists(path):
                with open(path, "rb") as f:
                    kept[relative] = f.read()
    if os.path.exists(os.path.join(target, DESK_SET)):
        with open(os.path.join(target, DESK_SET), "rb") as f:
            kept[DESK_SET] = f.read()
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
    for relative, data in kept.items():
        with open(os.path.join(target, relative), "wb") as f:
            f.write(data)
    if kept:
        write_desk_manifest()
    print(f"read {len(plan)} files from {pack}" + (f"; kept {len(kept)} desktop pose files" if kept else ""))


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



# --------------------------------------------------------------------------
# Desktop-pet poses
# --------------------------------------------------------------------------

def desk_set():
    """pack/desk-set.json (written by --desk-3d): cycles, fps, playback and the 3D poses; empty before the first import."""
    path = os.path.join(cats_dir(), DESK_SET)
    if not os.path.exists(path):
        return {"cycles": {}, "poses": {}}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def desk_sprites():
    """Every desktop sprite, in manifest order: a cat's walk is its whole cycle (key frames walk_1…4 and the
    in-betweens), then the other poses."""
    cycles = desk_set()["cycles"]
    names = []
    for name in DESK_BASE:
        cat, pose = name.split("_", 1)
        cycle = cycles.get(f"{cat}_walk")
        if cycle and pose.startswith("walk_"):
            if pose == "walk_1":
                names += cycle
            continue
        names.append(name)
    return names


def desk_kind(name):
    """The (canvas, logical size, facing, kind) of a pose; the walk's in-betweens are built like walk_1."""
    pose = desk_parts(name)[1]
    if re.fullmatch(r"walk_f\d\d", pose):
        pose = "walk_1"
    return DESK_KINDS[pose]


def desk_is3d(name):
    return name in desk_set()["poses"]


def desk_anchor(name):
    """A measured anchor from the set (momo_lift: the rig's scruff), else the nominal one of its kind."""
    measured = desk_set()["poses"].get(name, {}).get("anchor")
    if measured:
        return tuple(measured)
    return DESK_HANG_ANCHOR if desk_kind(name)[3] == "hang" else DESK_STAND_ANCHOR


def desk_parts(name):
    cat, pose = name.split("_", 1)
    return cat, pose


def desk_cycle(name):
    """('mimimo_walk', 'key', 2) for mimimo_walk_2 (the second of the four key poses), ('mimimo_walk', 'frame', 5) for
    mimimo_walk_f05 (the fifth frame of the cycle), else None."""
    head, _, tail = name.rpartition("_")
    if tail.isdigit():
        return head, "key", int(tail)
    if re.fullmatch(r"f\d\d", tail):
        return head, "frame", int(tail[1:])
    return None


def cycle_order(cycle_id, names):
    """The names of one cycle in frame order. Key poses _1.._4 sit at every N/4th frame (N is the cycle's length), the
    in-betweens _fNN at frame NN; the frames must be exactly 1…N, with no gap and no duplicate."""
    n = len(names)
    if n % 4:
        stop(f"{cycle_id}: {n} frames; a cycle must be a multiple of four so the four key poses sit on it")
    at = {}
    for name in names:
        _, kind, number = desk_cycle(name)
        position = (number - 1) * (n // 4) + 1 if kind == "key" else number
        if kind == "key" and not 1 <= number <= 4:
            stop(f"{name}: a key pose is walk_1 to walk_4")
        if position in at:
            stop(f"{cycle_id}: frame {position} is both {at[position]} and {name}")
        at[position] = name
    if sorted(at) != list(range(1, n + 1)):
        stop(f"{cycle_id}: needs frames 1 to {n} (key poses at {', '.join(str(i * (n // 4) + 1) for i in range(4))}), "
             f"has {sorted(at)}")
    return [at[i] for i in range(1, n + 1)]


def _yellow(r, g, b):
    return r > 190 and g > 160 and b < 140 and r - b > 80


def _blue(r, g, b):
    return b > 190 and g > 170 and r < 190 and b - r > 30


def eye_spacing(image, cat):
    """Distance between the two eye centres in px of `image` (same colour rules as normalize_poses.py), or None."""
    test = _yellow if cat == "mimimo" else _blue
    px = image.load()
    pts = [(x, y) for y in range(image.height) for x in range(image.width)
           if px[x, y][3] > 200 and test(*px[x, y][:3])]
    if len(pts) < 12:
        return None
    xs = sorted(p[0] for p in pts)
    mid = (xs[0] + xs[-1]) / 2
    left = [p for p in pts if p[0] < mid]
    right = [p for p in pts if p[0] >= mid]
    if len(left) < 4 or len(right) < 4:
        return None
    lx, ly = sum(p[0] for p in left) / len(left), sum(p[1] for p in left) / len(left)
    rx, ry = sum(p[0] for p in right) / len(right), sum(p[1] for p in right) / len(right)
    d = ((rx - lx) ** 2 + (ry - ly) ** 2) ** 0.5
    return d if d > 3 else None


def foot_contacts(alpha, floor):
    """The lowest opaque point of each paw touching the floor: [x, y] as fractions of the canvas.
    Paws are the opaque columns within a thin band above the floor row; columns less than 3 px apart are one paw."""
    width, height = alpha.size
    band = max(2, round(0.02 * height))
    columns = []
    for x in range(width):
        low = None
        for y in range(min(height - 1, floor), floor - band - 1, -1):
            if alpha.getpixel((x, y)) > OPAQUE:
                low = y
                break
        if low is not None:
            columns.append((x, low))
    groups = []
    for x, y in columns:
        if groups and x - groups[-1][-1][0] <= 3:
            groups[-1].append((x, y))
        else:
            groups.append([(x, y)])
    found = []
    for g in groups:
        if len(g) < 4:
            continue
        first, last = g[0][0], g[-1][0]
        found.append([round((first + last + 1) / 2 / width, 4), round((max(y for _, y in g) + 1) / height, 4)])
    return found


def lowest_row(alpha):
    box = alpha.point(lambda v: 255 if v > OPAQUE else 0).getbbox()
    return box[3] - 1


def build_desk_metrics():
    """cats.json "desk": measured on the 3x sprites. Stops when a pose is not built the way its entry says."""
    sprites_dir = os.path.join(cats_dir(), "sprites")
    group = desk_set()
    poses, images, cycles = {}, {}, {}
    for name in desk_sprites():
        canvas, logical, facing, kind = desk_kind(name)
        image = load_sprite(sprites_dir, name, logical)
        images[name] = image
        anchor = desk_anchor(name)
        measured = "anchor" in group["poses"].get(name, {})
        width, height = image.size
        alpha = image.getchannel("A")
        if alpha.getextrema()[0] != 0:
            stop(f"{name}: no transparent background")
        box = alpha.point(lambda v: 255 if v > VISIBLE else 0).getbbox()
        if box is None or box[0] == 0 or box[1] == 0 or box[2] == width or box[3] == height:
            stop(f"{name}: the cat touches the canvas edge; ears and tail need a transparent margin")
        if not (0 <= anchor[0] <= 1 and 0 <= anchor[1] <= 1):
            stop(f"{name}: anchor outside the canvas")
        left, top, right, bottom = (round(box[0] / width, 4), round(box[1] / height, 4),
                                    round(box[2] / width, 4), round(box[3] / height, 4))
        if kind in ("stand", "hang") and not left <= anchor[0] <= right:
            stop(f"{name}: the anchor is not over the cat")
        if kind == "stand":
            row = round(anchor[1] * height)
            if abs(lowest_row(alpha) + 1 - row) > 0.07 * height:
                stop(f"{name}: the cat does not stand near its anchor row")
        if kind == "hang":
            # Nominal: the top of the cat. Measured (the rig's scruff): on the cat, in its upper part, near its middle.
            if not measured and not abs(top - anchor[1]) <= 0.03:
                stop(f"{name}: the cat does not hang from the top of the canvas")
            if measured and not (top <= anchor[1] <= top + 0.2 * (bottom - top) + 0.02 and abs(anchor[0] - 0.5) <= 0.1):
                stop(f"{name}: the measured scruff is not in the upper middle of the cat")
        entry = {"size": [logical[0], logical[1]], "anchor": [anchor[0], anchor[1]], "facing": facing,
                 "bounds": [left, top, right, bottom], "source": "3d" if desk_is3d(name) else "gpt"}
        if kind == "hang":
            entry["anchorKind"] = (group["poses"][name].get("anchorKind", "scruff") + " (measured on the rig)" if measured
                                   else "scruff (nominal: top centre of the canvas; not measured)")
        cyc = desk_cycle(name)
        if cyc:
            cycles.setdefault(cyc[0], []).append(name)
        poses[name] = entry
    ordered = {}
    for cycle_id, names in sorted(cycles.items()):
        names = cycle_order(cycle_id, names)
        if group["cycles"].get(cycle_id, names) != names:
            stop(f"{cycle_id}: pack/desk-set.json lists the frames in another order than their names")
        ordered[cycle_id] = names
        n = len(names)
        for index, name in enumerate(names):
            poses[name]["cycle"] = {"id": cycle_id, "frame": index + 1, "of": n}
        cat = desk_parts(names[0])[0]
        spacing = [eye_spacing(images[nm], cat) for nm in names]
        if None in spacing:
            stop(f"{cycle_id}: eyes not found in frame {spacing.index(None) + 1}")
        mean = sum(spacing) / len(spacing)
        for nm, d in zip(names, spacing):
            if abs(d - mean) > EYE_TOLERANCE * mean:
                stop(f"{nm}: eye spacing {d:.1f} px differs from the cycle's {mean:.1f} px by more than {EYE_TOLERANCE:.0%}; "
                     f"the walk frames do not share one scale")
        floor = max(lowest_row(images[nm].getchannel("A")) for nm in names)
        for nm in names:
            contacts = foot_contacts(images[nm].getchannel("A"), floor)
            if not contacts:
                stop(f"{nm}: no paw reaches the cycle's floor")
            poses[nm]["contacts"] = contacts
    result = {"poses": poses, "cycles": ordered}
    if ordered:
        fps = group.get("fps", 12)
        playback = group.get("playback", {})
        if not isinstance(fps, (int, float)) or fps <= 0:
            stop("pack/desk-set.json: fps must be a positive number")
        if sorted(playback) != sorted(ordered) or set(playback.values()) - {"loop", "once"}:
            stop("pack/desk-set.json: playback must name every cycle as loop or once")
        result["fps"] = fps
        result["playback"] = {key: playback[key] for key in sorted(playback)}
    result["note"] = ("anchor and bounds are fractions of the sprite canvas; contacts are [x, y] of each paw on the "
                      "cycle's floor; right-facing poses are mirrored by the shell for left; a cycle's frames are in "
                      "order and its key poses walk_1 to walk_4 sit on every (frames / 4)th one: a step driven by a key "
                      "advances that many frames and lands on a key pose; playback at fps shows every frame")
    return result


def resample(master, size, scale):
    return master.resize((size[0] * scale, size[1] * scale), Image.LANCZOS)


def import_desk(folder):
    """Copy normalised masters in and resample the sprites. The folder is only read."""
    folder = os.path.abspath(folder)
    names = [name for name in desk_sprites() if not desk_is3d(name)]
    for name in names:
        canvas, logical, _, _ = desk_kind(name)
        path = os.path.join(folder, name + ".png")
        if not os.path.exists(path):
            stop(f"{path} is missing")
        master = Image.open(path)
        if master.mode != "RGBA" or master.size != canvas:
            stop(f"{name}.png is {master.mode} {master.size}, expected RGBA {canvas}")
        shutil.copyfile(path, os.path.join(cats_dir(), "masters", name + ".png"))
        for scale in SCALES:
            resample(master, logical, scale).save(os.path.join(cats_dir(), "sprites", f"{name}@{scale}x.png"), optimize=True)
    write_desk_manifest()
    print(f"read {len(names)} desktop poses from {folder}")


def import_desk3d(folder):
    """The poses the owner switched to the 3D renders: both cats' walk cycles (all 12 frames), turn, fall and momo_lift
    (with the rig's scruff anchor). Copies the masters, resamples the sprites, writes pack/desk-set.json. The folder is
    only read; every other desktop pose is left as it is."""
    folder = os.path.abspath(folder)
    with open(os.path.join(folder, "desk-3d.json"), encoding="utf-8") as f:
        source = json.load(f)
    poses, cycles = source["poses"], {}
    chosen = []
    for cat in CATS:
        names = source["cycles"].get(f"{cat}_walk")
        if not names:
            stop(f"desk-3d.json has no {cat}_walk cycle")
        cycles[f"{cat}_walk"] = cycle_order(f"{cat}_walk", names)
        if cycles[f"{cat}_walk"] != names:
            stop(f"{cat}_walk: desk-3d.json lists the frames out of order")
        chosen += names
        chosen += [f"{cat}_{pose}" for pose in DESK_3D_POSES if f"{cat}_{pose}" not in DESK_3D_SKIP]
    group = {"artwork": "3D renders, clay-3d-stage1 round-9 (desk-3d.json)", "fps": source["fps"],
             "playback": {key: source["playback"][key] for key in sorted(cycles)}, "cycles": cycles, "poses": {}}
    for name in chosen:
        pose = poses.get(name)
        if pose is None:
            stop(f"desk-3d.json has no pose {name}")
        canvas, logical, facing, _ = desk_kind(name)
        if pose["pixelSize"] != list(canvas) or pose["size"] != list(logical) or pose["facing"] != facing:
            stop(f"{name}: desk-3d.json says {pose['pixelSize']} px, {pose['size']} pt, {pose['facing']}; "
                 f"the desk wants {list(canvas)} px, {list(logical)} pt, {facing}")
        path = os.path.join(folder, pose["file"])
        if not os.path.exists(path):
            stop(f"{path} is missing")
        if pose.get("sequenceFile") and open(path, "rb").read() != open(os.path.join(folder, pose["sequenceFile"]), "rb").read():
            stop(f"{name}: {pose['file']} is not the same picture as {pose['sequenceFile']}")
        master = Image.open(path)
        if master.mode != "RGBA" or master.size != canvas:
            stop(f"{name}: {pose['file']} is {master.mode} {master.size}, expected RGBA {canvas}")
        entry = {"source": "3d"}
        if pose.get("anchorKind"):
            entry["anchor"] = [round(pose["anchor"][0], 4), round(pose["anchor"][1], 4)]
            entry["anchorKind"] = pose["anchorKind"]
        elif list(pose["anchor"]) != list(DESK_STAND_ANCHOR):
            stop(f"{name}: anchor {pose['anchor']}, expected {list(DESK_STAND_ANCHOR)}")
        group["poses"][name] = entry
        shutil.copyfile(path, os.path.join(cats_dir(), "masters", name + ".png"))
        for scale in SCALES:
            resample(master, logical, scale).save(os.path.join(cats_dir(), "sprites", f"{name}@{scale}x.png"), optimize=True)
    with open(os.path.join(cats_dir(), DESK_SET), "w", encoding="utf-8") as f:
        f.write(json.dumps(group, indent=2, ensure_ascii=False) + "\n")
    write_desk_manifest()
    print(f"read {len(chosen)} desktop poses ({len(cycles)} walk cycles of {len(next(iter(cycles.values())))} frames) from {folder}")


DESK_BLOCK = re.compile(r'  "deskArtwork" : .*?(?=  "edgeSprites")', re.S)


def desk_manifest_block():
    lines = [f'  "deskArtwork" : {json.dumps(DESK_ARTWORK, ensure_ascii=False)},', '  "deskSprites" : [']
    names = desk_sprites()
    for i, name in enumerate(names):
        _, logical, facing, kind = desk_kind(name)
        anchor = desk_anchor(name)
        lines += ["    {", f'      "anchorNormalized" : [{anchor[0]}, {anchor[1]}],', f'      "facing" : "{facing}",',
                  f'      "id" : "{name}",', f'      "logicalSize" : [{logical[0]}, {logical[1]}],',
                  f'      "source" : "design-preview/pc/CLAY-POSES.md P0 ({"3D round-9" if desk_is3d(name) else "GPT"})"', "    }" + ("," if i < len(names) - 1 else "")]
    lines += ["  ],", ""]
    return "\n".join(lines)


def write_desk_manifest():
    path = os.path.join(cats_dir(), "pack", "manifest.json")
    with open(path, encoding="utf-8") as f:
        text = f.read()
    text = DESK_BLOCK.sub("", text)
    marker = '  "edgeSprites"'
    if marker not in text:
        stop("pack/manifest.json has no edgeSprites key")
    text = text.replace(marker, desk_manifest_block() + marker, 1)
    json.loads(text)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def check_desk_manifest():
    with open(os.path.join(cats_dir(), "pack", "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    entries = {e["id"]: e for e in manifest.get("deskSprites", [])}
    if sorted(entries) != sorted(desk_sprites()):
        stop("pack/manifest.json deskSprites does not list the desktop poses: run --desk DIR or --desk-3d DIR")
    for name, e in entries.items():
        _, logical, facing, kind = desk_kind(name)
        anchor = desk_anchor(name)
        if e["logicalSize"] != list(logical) or e["anchorNormalized"] != list(anchor) or e["facing"] != facing:
            stop(f"{name}: pack/manifest.json disagrees with the script")
    if "GPT" not in manifest.get("deskArtwork", "") or (desk_set()["poses"] and "3D" not in manifest["deskArtwork"]):
        stop("pack/manifest.json deskArtwork lacks the provenance")

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
    check_desk_manifest()
    return {"pack": {"name": manifest["name"], "version": manifest["version"]}, "sprites": sprites,
            "desk": build_desk_metrics()}


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
             "    /// Geometry of the keyboard artwork, by asset name.",
             "    static let all: [String: CatSprite] = ["]
    for name in EDGE_SPRITES + [name for name in metrics["sprites"] if name not in EDGE_SPRITES]:
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
    desk_json = json.dumps(metrics["desk"], indent=2, ensure_ascii=False) + "\n"
    for name in desk_sprites():
        out.copy(f"Resources/Cats/{name}.png", os.path.join(sprites, f"{name}@1x.png"))
        out.copy(f"Resources/Cats/{name}@2x.png", os.path.join(sprites, f"{name}@2x.png"))
        out.copy(f"Windows/UI/Assets/{name}.png", os.path.join(sprites, f"{name}@3x.png"))
    out.add("Resources/Cats/desk.json", desk_json)
    out.add("Windows/UI/Assets/desk.json", desk_json)
    # Android and Windows keep other files in these folders: only the cats are replaced.
    out.own("Android/app/src/main/res/drawable-nodpi",
            keep=lambda name: not name.startswith("cat_") or name.startswith(("cat_classic_", "cat_pixel_")))
    out.own("Windows/UI/Assets", keep=lambda name: not re.fullmatch(r"(mimimo|momo)_[a-z_0-9]+\.png", name) and name != "desk.json")
    for name in metrics["sprites"]:
        out.copy(f"Android/app/src/main/res/drawable-nodpi/cat_{name}.png", os.path.join(sprites, f"{name}@3x.png"))
    for name in EDGE_SPRITES:
        out.copy(f"Resources/Cats/{name}.png", os.path.join(sprites, f"{name}@1x.png"))
        out.copy(f"Resources/Cats/{name}@2x.png", os.path.join(sprites, f"{name}@2x.png"))
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
        for name, sprite in metrics["sprites"].items():
            paths = [(f"Android/app/src/main/res/drawable-nodpi/cat_{name}.png", 3)]
            if name in EDGE_SPRITES:
                paths += [(f"Resources/Cats/{name}.png", 1), (f"Windows/UI/Assets/{name}.png", 3)]
            for path, scale in paths:
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
    # A cycle is every frame 1…N once, the key poses on every N/4th.
    full = desk_set()["cycles"]["momo_walk"]
    if cycle_order("momo_walk", full) != full or cycle_order("momo_walk", full[::-1]) != full:
        stop("self-test: a 12-frame cycle is not put in order")
    for label, names, needle in (("a missing frame", full[:-1], "multiple of four"),
                                 ("a gap", full[:5] + full[6:] + ["momo_walk_f13"], "needs frames 1 to 12"),
                                 ("a key pose and an in-between on one frame", full[:11] + ["momo_walk_f04"], "frame 4 is both")):
        try:
            cycle_order("momo_walk", names)
        except SystemExit as error:
            if needle not in str(error):
                stop(f"self-test: {label} stopped for another reason: {error}")
            continue
        stop(f"self-test: {label} was accepted")
    print("PASS: the import is repeatable and sized for every client; a changed or stale client file is reported; "
          "a moved anchor, a wrong size, a missing alpha channel, a missing scale and a broken walk cycle are refused")


def main():
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--pack", metavar="DIR", help="the owner's asset pack (read only)")
    parser.add_argument("--desk", metavar="DIR", help="normalised desktop-pet masters (read only)")
    parser.add_argument("--desk-3d", metavar="DIR", dest="desk3d", help="3D renders of the desktop poses (round-9; read only)")
    parser.add_argument("--check", action="store_true", help="write nothing; fail if anything differs")
    parser.add_argument("--self-test", action="store_true", help="check the importer itself")
    parser.add_argument("--root", metavar="DIR", help="repo root (default: this checkout)")
    args = parser.parse_args()
    if args.root:
        ROOT = os.path.abspath(args.root)
    if args.self_test:
        self_test()
        return
    if args.desk:
        if args.check:
            stop("--check reads the repository, not the masters")
        import_desk(args.desk)
    if args.desk3d:
        if args.check:
            stop("--check reads the repository, not the renders")
        import_desk3d(args.desk3d)
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
        print(f"PASS: {len(metrics['sprites'])} sprites and {len(metrics['desk']['poses'])} desktop poses measured; {len(out.files)} client files match the import")
        return
    out.write()
    print(f"cats written for macOS, Android and Windows ({len(out.files)} files)")


if __name__ == "__main__":
    main()
