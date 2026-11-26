#!/bin/bash
# Cross-builds the desk-pet process, MeowpuutDesk.exe (喵撲貓窩), on a
# machine without Windows.
#
#   bash scripts/build-windows-desk.sh x64|arm64 [output directory]
#
# 64-bit only: one pet process runs per session, natively on x64 or Arm64
# Windows. 32-bit programs need only the x86 text service, which sends pulses.
# Compiler and Rust target as scripts/build-windows-ime.sh: llvm-mingw
# (x86_64-/aarch64-w64-mingw32-clang++; MINGW_BIN may name its bin directory),
# or Homebrew's mingw-w64 GCC for x64. The Rust engine (meowpuut-ffi, with
# mp_desk_* and mp_pulse_*) is linked statically, as in the DLL.
# Output: MeowpuutDesk.exe and Assets\ (every clay figure it draws, the same
# @3x PNGs the candidate window uses, with the desk poses walk_1–4, turn,
# look_up, lift, fall and momo_sleep and their desk.json), default
# .build/windows-desk/<arch>/.
# Run MeowpuutDesk.exe --smoke there on Windows for the headless check.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
ARCH="${1:?usage: build-windows-desk.sh x64|arm64 [output directory]}"
OUT="${2:-.build/windows-desk/$ARCH}"
VERSION="門廊 · 26A003"
VERSION_NUMBERS=0,9,0,3
if [ -n "${MINGW_BIN:-}" ]; then export PATH="$MINGW_BIN:$PATH"; fi

case "$ARCH" in
  x64) TRIPLE=x86_64-w64-mingw32 ;;
  arm64) TRIPLE=aarch64-w64-mingw32 ;;
  *) echo "unknown architecture: $ARCH (the desk is 64-bit only: x64 or arm64)" >&2; exit 1 ;;
esac
if command -v "$TRIPLE-clang++" >/dev/null; then
  CXX="$TRIPLE-clang++"
  RUST_TARGET="${TRIPLE%%-*}-pc-windows-gnullvm"
elif [ "$ARCH" = x64 ] && command -v "$TRIPLE-g++" >/dev/null; then
  CXX="$TRIPLE-g++"
  RUST_TARGET=x86_64-pc-windows-gnu
else
  echo "no $TRIPLE C++ compiler: install llvm-mingw and set MINGW_BIN to its bin directory" >&2
  exit 1
fi
WINDRES="$TRIPLE-windres"
rustup target list --installed | grep -qx "$RUST_TARGET" || rustup target add "$RUST_TARGET"

echo "== desk $ARCH: $("$CXX" --version | head -1), Rust $RUST_TARGET"
cargo rustc --locked --manifest-path Engine/Cargo.toml --target-dir target --quiet --release -p meowpuut-ffi --target "$RUST_TARGET" --crate-type staticlib
RUST_LIB="target/$RUST_TARGET/release/libmeowpuut_ffi.a"
RUST_NATIVE=$(cargo rustc --locked --manifest-path Engine/Cargo.toml --target-dir target --quiet --release -p meowpuut-ffi --target "$RUST_TARGET" --crate-type staticlib -- --print native-static-libs 2>&1 \
  | sed -n 's/.*native-static-libs: //p' | tail -1)

rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/Assets"
DESK=Windows/Desk
WARN=(-Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Werror)
DEFS=(-DUNICODE -D_UNICODE -DNOMINMAX -D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00)
objects=()
pids=()
for src in "$DESK/PulseChannel.cpp" "$DESK/DeskWorld.cpp" "$DESK/DeskArt.cpp" "$DESK/DeskLoop.cpp" "$DESK/DeskSettings.cpp" \
           "$DESK/DeskSmoke.cpp" "$DESK/ActivityLedger.cpp" "$DESK/ForegroundWatcher.cpp" "$DESK/DeskApp.cpp" Windows/PetLedger.cpp; do
  obj="$OUT/obj/$(basename "${src%.cpp}").o"
  "$CXX" -std=c++17 "${WARN[@]}" -O2 "${DEFS[@]}" -c "$src" -o "$obj" &
  pids+=($!)
  objects+=("$obj")
done
for pid in "${pids[@]}"; do wait "$pid"; done

# The icon is the app icon at the sizes Windows asks for (the tray uses 16–32).
python3 - "$OUT/obj/Meowpuut.ico" <<'EOF'
import sys
from PIL import Image
Image.open('assets/brand/AppIcon.png').convert('RGBA').save(
    sys.argv[1], sizes=[(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (256, 256)])
EOF
"$WINDRES" --codepage=65001 -I "$OUT/obj" -I "$DESK" -DMEOWPUUT_VERSION="\\\"$VERSION\\\"" -DMEOWPUUT_VERSION_NUMBERS="$VERSION_NUMBERS" \
  "$DESK/Desk.rc" -O coff -o "$OUT/obj/Desk.res.o"

# shellcheck disable=SC2086 # RUST_NATIVE is a list of -l flags
"$CXX" -municode -mwindows -static -s -O2 -o "$OUT/MeowpuutDesk.exe" "${objects[@]}" "$OUT/obj/Desk.res.o" "$RUST_LIB" \
  -ldwmapi -lshcore -lwindowscodecs -lole32 -loleaut32 -luuid -luser32 -lgdi32 -ladvapi32 -lshell32 -lversion $RUST_NATIVE

# The figures it draws: the edge and side poses the candidate window ships, the
# desk poses (walk, turn, look_up, lift, fall, momo_sleep, with desk.json), and
# the sitting, reacting and sleeping ones (DeskArt.cpp), all at 3 px per point.
cp Windows/UI/Assets/*.png Windows/UI/Assets/desk.json "$OUT/Assets/"
for name in mimimo_idle mimimo_press mimimo_happy mimimo_sleep momo_idle momo_wave momo_happy momo_jump; do
  cp "assets/brand/cats/sprites/${name}@3x.png" "$OUT/Assets/$name.png"
done
echo "Built $OUT/MeowpuutDesk.exe ($(du -h "$OUT/MeowpuutDesk.exe" | cut -f1))"
