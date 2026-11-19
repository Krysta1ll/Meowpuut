#!/usr/bin/env python3
"""Curate, auto-crop screenshots, strip metadata, deduplicate, and stage cat photos
for HK Google Cloud Storage upload alongside the bundled offline fallback gallery."""

import argparse
import hashlib
import json
import os
import shutil
import struct
from PIL import Image, ImageOps
import numpy as np

DEFAULT_RAW_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../photos"))
DEFAULT_BUNDLED_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../Meowpuut-iOS/iOS/Photos"))
MAX_SIDE = 2048
JPEG_QUALITY = 84

EXCLUDED_FILES = {
    "IMG_7457.PNG",
    "B1AA2B93E03B8D6331551ABB03150C42 2.PNG",
}

SCREENSHOT_FILES = {"IMG_7456.PNG", "IMG_7459.PNG"}

# Curated cat tags for the new raw photo imports.
NEW_PHOTO_CATS = {
    "05B3B025BFE4A92EFAF298F62B67210B.PNG": ["mimimo"],
    "17FC8A507E58A84FC8FA0F38EBDE52CE.PNG": ["momo"],
    "2F95FD731D9DEC9DCC574107804B1E94.PNG": ["momo"],
    "42013D92555E9113AB674AA138DF433F.PNG": ["mimimo"],
    "4742DE9CA0FC42944F290F4C3DCBA853.PNG": ["momo"],
    "5E9BFFCF4057B502CB2F53D71DBB2B7D.PNG": ["momo"],
    "63DD51D40456E97E079B408B31B8D48B.PNG": ["momo"],
    "7631B23EEA6293CECC797F70B3234965.PNG": ["mimimo", "momo"],
    "76E05BB0C41EF1C896A9E5A6EEAE2B92.PNG": ["mimimo"],
    "7ABDDAF8D4FD39AF8DAF7071E4D6B3DF.PNG": ["momo"],
    "8285552CC26237136A5473146169F3EA.PNG": ["momo"],
    "8831EF38A1BE1359ACFA0DE8A0BC97F0.PNG": ["momo"],
    "8EBD257908469A4D999B3B3BFCBC9142.PNG": ["momo"],
    "9AB64556834E0EC1DE3173CB13AB7D36.PNG": ["momo"],
    "A67A918A9946AA8AB9638D972D1CE85E.PNG": ["momo"],
    "A9B01353E6255C7BB19B4B112A14D8A0.PNG": ["momo"],
    "B1AA2B93E03B8D6331551ABB03150C42.PNG": ["momo"],
    "C17DB8AB16757C0DA4F32B6651E3E000.PNG": ["mimimo"],
    "D234B919D46DFF29909CFA4F812F86FE.PNG": ["mimimo"],
    "D7AA2C9DC3891B4A0544C295D2E32322.PNG": ["momo"],
    "IMG_7456.PNG": ["mimimo"],
    "IMG_7459.PNG": ["mimimo"],
}


def dhash(img: Image.Image, size: int = 8) -> int:
    g = img.convert("L").resize((size + 1, size), Image.Resampling.LANCZOS)
    arr = np.array(g)
    diff = arr[:, 1:] > arr[:, :-1]
    bits = 0
    for b in diff.flatten():
        bits = (bits << 1) | int(b)
    return bits


def autocrop_screenshot(img: Image.Image) -> Image.Image:
    rgb = np.array(img.convert("RGB"))
    row_max = rgb.max(axis=(1, 2))
    row_mean = rgb.mean(axis=(1, 2))
    nonblack = np.where((row_max > 20) & (row_mean > 8))[0]
    segs = []
    if len(nonblack) > 0:
        s = int(nonblack[0])
        p = s
        for y in nonblack[1:]:
            yi = int(y)
            if yi > p + 8:
                segs.append((s, p + 1))
                s = yi
            p = yi
        segs.append((s, p + 1))
    top, bot = max(segs, key=lambda ab: ab[1] - ab[0])
    sub = rgb[top:bot]
    col_mean = sub.mean(axis=(0, 2))
    nonblack_x = np.where(col_mean > 8)[0]
    left, right = int(nonblack_x[0]), int(nonblack_x[-1]) + 1
    return img.crop((left, top, right, bot))


def verify_clean_jpeg(path: str) -> tuple[int, int]:
    data = open(path, "rb").read()
    assert data[:2] == b"\xff\xd8", f"{path}: not JPEG"
    i = 2
    size = None
    while i + 4 <= len(data):
        assert data[i] == 0xFF, f"{path}: invalid marker at {i}"
        marker = data[i + 1]
        if marker == 0xDA:
            break
        length = struct.unpack(">H", data[i + 2 : i + 4])[0]
        body = data[i + 4 : i + 2 + length]
        if 0xE0 <= marker <= 0xEF and marker != 0xE0:
            raise AssertionError(f"{path}: unexpected metadata segment APP{marker - 0xE0}")
        if marker == 0xFE:
            raise AssertionError(f"{path}: unexpected comment segment")
        if marker in (0xC0, 0xC1, 0xC2):
            size = struct.unpack(">HH", body[1:5])[::-1]
        i += 2 + length
    for needle in (b"Exif\0", b"http://ns.adobe.com/xap", b"GPS", b"ICC_PROFILE", b"Photoshop 3.0"):
        assert needle not in data[:i], f"{path}: metadata needle found"
    assert size is not None
    return size


def save_clean_jpeg(img: Image.Image, out_dir: str) -> tuple[str, int, int]:
    im = ImageOps.exif_transpose(img).convert("RGB")
    w, h = im.size
    if max(w, h) > MAX_SIDE:
        scale = MAX_SIDE / max(w, h)
        im = im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.Resampling.LANCZOS)
    clean = Image.frombytes("RGB", im.size, im.tobytes())
    tmp = os.path.join(out_dir, ".tmp-photo.jpg")
    clean.save(tmp, format="JPEG", quality=JPEG_QUALITY, optimize=True, progressive=True)
    digest = hashlib.sha256(open(tmp, "rb").read()).hexdigest()[:12]
    filename = f"photo-{digest}.jpg"
    dest = os.path.join(out_dir, filename)
    os.replace(tmp, dest)
    vw, vh = verify_clean_jpeg(dest)
    assert (vw, vh) == clean.size
    return filename, vw, vh


def build_staging(raw_dir: str, bundled_dir: str, staging_dir: str) -> None:
    photos_out = os.path.join(staging_dir, "photos")
    os.makedirs(photos_out, exist_ok=True)

    bundled_manifest = json.load(open(os.path.join(bundled_dir, "photos.json"), encoding="utf-8"))
    entries = []
    seen_files = set()
    seen_hashes = []

    # Preserve existing bundled photos under their existing content-hashed filenames
    for b in bundled_manifest["photos"]:
        src = os.path.join(bundled_dir, b["file"])
        dst = os.path.join(photos_out, b["file"])
        shutil.copy2(src, dst)
        vw, vh = verify_clean_jpeg(dst)
        assert (vw, vh) == (b["width"], b["height"])
        seen_files.add(b["file"])
        seen_hashes.append(dhash(Image.open(dst)))
        entries.append(dict(b))

    # Add cleaned new raw photos
    raw_names = sorted(
        f for f in os.listdir(raw_dir)
        if f.lower().endswith((".png", ".jpg", ".jpeg", ".heic")) and f not in EXCLUDED_FILES
    )
    seen_sha = set()
    for name in raw_names:
        path = os.path.join(raw_dir, name)
        raw_sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
        if raw_sha in seen_sha:
            continue
        seen_sha.add(raw_sha)

        im = Image.open(path)
        if name in SCREENSHOT_FILES:
            im = autocrop_screenshot(im)
        dh = dhash(im)
        if any((dh ^ prev).bit_count() <= 6 for prev in seen_hashes):
            print(f"SKIP near-duplicate: {name}")
            continue
        seen_hashes.append(dh)

        out_file, w, h = save_clean_jpeg(im, photos_out)
        if out_file in seen_files:
            continue
        seen_files.add(out_file)
        cats = NEW_PHOTO_CATS.get(name, ["mimimo", "momo"])
        entries.append({
            "file": out_file,
            "width": w,
            "height": h,
            "cats": cats,
        })

    manifest = {"version": 1, "photos": entries}
    manifest_path = os.path.join(staging_dir, "photos.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"Staged {len(entries)} metadata-free photos -> {staging_dir}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", default=DEFAULT_RAW_DIR)
    parser.add_argument("--bundled", default=DEFAULT_BUNDLED_DIR)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    build_staging(args.raw, args.bundled, args.out)
