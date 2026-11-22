#!/usr/bin/env python3
"""Write Meowpuut.ico from assets/brand/AppIcon.png.

The sizes match scripts/build-windows-ime.sh, so the MSVC CMake build and the
macOS cross-compile produce the same icon.
"""
import sys
from pathlib import Path

from PIL import Image

SIZES = [(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (256, 256)]


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: generate-windows-ico.py AppIcon.png Meowpuut.ico")
    source, dest = Path(sys.argv[1]), Path(sys.argv[2])
    dest.parent.mkdir(parents=True, exist_ok=True)
    Image.open(source).convert("RGBA").save(dest, sizes=SIZES)


if __name__ == "__main__":
    main()
