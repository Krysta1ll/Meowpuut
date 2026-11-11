#!/usr/bin/env python3
"""Add life photos of 米米莫 and 莫莫 to the iOS app's home page.

    python3 scripts/import-photos.py PHOTO [PHOTO ...] [--cats mimimo,momo] [--caption TEXT]
    python3 scripts/import-photos.py --list

Each photo is turned upright, converted to sRGB, resized to at most 2048 px
on its long side, saved as JPEG without any metadata (no location, no camera
data, no colour profile) and added to the front of iOS/Photos/photos.json. HEIC files are converted with macOS
`sips` first. Edit photos.json afterwards to change captions or order.
"""
import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageCms, ImageOps

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PHOTOS = os.path.join(ROOT, "iOS", "Photos")
MANIFEST = os.path.join(PHOTOS, "photos.json")
LONG_EDGE = 2048


def load():
    if not os.path.exists(MANIFEST):
        return {"version": 1, "photos": []}
    with open(MANIFEST, encoding="utf-8") as f:
        return json.load(f)


def save(manifest):
    os.makedirs(PHOTOS, exist_ok=True)
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")


def taken_month(img):
    """yyyy-MM from the camera's own date, if the photo still has one."""
    try:
        exif = img.getexif()
        value = exif.get_ifd(0x8769).get(36867) or exif.get(306)
        if value and len(value) >= 7:
            return value[:4] + "-" + value[5:7]
    except Exception:
        pass
    return None


def to_srgb(img):
    """Pixel values in sRGB. The saved file carries no colour profile and is
    read as sRGB, so a Display P3 photo (iPhone) is converted first; left as
    it is, its colours would come out duller."""
    icc = img.info.get("icc_profile")
    img = img.convert("RGB")
    if not icc:
        return img
    try:
        source = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        return ImageCms.profileToProfile(img, source, ImageCms.createProfile("sRGB"), outputMode="RGB")
    except Exception:
        # An unreadable or non-RGB profile: keep the pixels as they are.
        return img


def open_upright(path):
    temporary = None
    if path.lower().endswith((".heic", ".heif")):
        tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
        tmp.close()
        temporary = tmp.name
        subprocess.run(["sips", "-s", "format", "jpeg", path, "--out", temporary],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        path = temporary
    try:
        img = Image.open(path)
        img.load()
    finally:
        # The converted copy still holds the original's EXIF and location.
        if temporary:
            os.remove(temporary)
    month = taken_month(img)
    icc = img.info.get("icc_profile")
    img = ImageOps.exif_transpose(img)
    if icc and "icc_profile" not in img.info:
        img.info["icc_profile"] = icc
    return to_srgb(img), month


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("photos", nargs="*")
    parser.add_argument("--cats", default="", help="who is in the photo: mimimo, momo or mimimo,momo")
    parser.add_argument("--caption", default="", help="short caption shown on the photo")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()
    manifest = load()
    if args.list:
        for entry in manifest["photos"]:
            print(entry["file"], entry.get("month") or "", ",".join(entry.get("cats", [])), entry.get("caption", ""))
        return
    if not args.photos:
        parser.error("no photos given")
    cats = [c for c in args.cats.split(",") if c]
    for c in cats:
        if c not in ("mimimo", "momo"):
            sys.exit(f"unknown cat: {c}")
    added = []
    for path in args.photos:
        img, month = open_upright(path)
        img.thumbnail((LONG_EDGE, LONG_EDGE), Image.LANCZOS)
        digest = hashlib.sha256(img.tobytes()).hexdigest()[:12]
        name = f"photo-{digest}.jpg"
        if any(entry["file"] == name for entry in manifest["photos"]):
            print("already imported:", path)
            continue
        # A fresh image object carries no EXIF, ICC or XMP data.
        clean = Image.new("RGB", img.size)
        clean.paste(img)
        clean.save(os.path.join(PHOTOS, name), "JPEG", quality=84, optimize=True, progressive=True)
        entry = {"file": name, "width": clean.width, "height": clean.height, "cats": cats}
        if month:
            entry["month"] = month
        if args.caption:
            entry["caption"] = args.caption
        added.append(entry)
        print("added", name, f"{clean.width}x{clean.height}", month or "")
    manifest["photos"] = added + manifest["photos"]
    save(manifest)


if __name__ == "__main__":
    main()
