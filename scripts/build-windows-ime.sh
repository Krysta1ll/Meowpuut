#!/bin/bash
# Cross-builds the Meowpuut text service, MeowpuutTextService.dll, on a
# machine without Windows.
#
#   bash scripts/build-windows-ime.sh x64|x86|arm64 [output directory]
#
# Compiler: llvm-mingw (x86_64-/i686-/aarch64-w64-mingw32-clang++), or
# Homebrew's mingw-w64 GCC for x64 only (its i686 GCC is SJLJ, which Rust's
# i686 target cannot link). MINGW_BIN may name the
# toolchain's bin directory. The Rust engine is built for the matching
# *-pc-windows-gnullvm (clang) or x86_64-pc-windows-gnu (GCC) target.
# x86 is the 32-bit DLL that 32-bit programs on x64 Windows load (same CLSID
# as x64; Install.ps1 registers it with SysWOW64\regsvr32.exe).
# Default output: .build/windows-ime/<arch>/
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
ARCH="${1:?usage: build-windows-ime.sh x64|x86|arm64 [output directory]}"
OUT="${2:-.build/windows-ime/$ARCH}"
VERSION="門廊 · 26A003"
VERSION_NUMBERS=0,9,0,3
if [ -n "${MINGW_BIN:-}" ]; then export PATH="$MINGW_BIN:$PATH"; fi

case "$ARCH" in
  x64) TRIPLE=x86_64-w64-mingw32 ;;
  x86) TRIPLE=i686-w64-mingw32 ;;
  arm64) TRIPLE=aarch64-w64-mingw32 ;;
  *) echo "unknown architecture: $ARCH" >&2; exit 1 ;;
esac
if command -v "$TRIPLE-clang++" >/dev/null; then
  CXX="$TRIPLE-clang++"
  RUST_TARGET="${TRIPLE%%-*}-pc-windows-gnullvm"
elif [ "$ARCH" = x64 ] && command -v "$TRIPLE-g++" >/dev/null; then
  CXX="$TRIPLE-g++"
  RUST_TARGET=x86_64-pc-windows-gnu
elif [ "$ARCH" = x86 ] && command -v "$TRIPLE-g++" >/dev/null; then
  # Homebrew's i686 GCC unwinds with SJLJ, Rust's i686-pc-windows-gnu with
  # DWARF (_Unwind_RaiseException, _Unwind_Resume): the link fails, and a
  # mismatch would break catch_unwind in the FFI layer anyway.
  echo "x86 needs llvm-mingw (i686-w64-mingw32-clang++, DWARF unwinding); Homebrew's i686 GCC cannot link Rust's i686 target. Set MINGW_BIN to llvm-mingw's bin directory." >&2
  exit 1
else
  echo "no $TRIPLE C++ compiler: install llvm-mingw and set MINGW_BIN to its bin directory" >&2
  exit 1
fi
# 32-bit stdcall names are decorated (_DllRegisterServer@0); TextService.def
# lists the plain names, which the linker matches with this option.
LDEXTRA=()
if [ "$ARCH" = x86 ]; then LDEXTRA=(-Wl,--enable-stdcall-fixup); fi
WINDRES="$TRIPLE-windres"
rustup target list --installed | grep -qx "$RUST_TARGET" || rustup target add "$RUST_TARGET"

echo "== $ARCH: $("$CXX" --version | head -1), Rust $RUST_TARGET"
cargo rustc --locked --manifest-path Engine/Cargo.toml --target-dir target --quiet --release -p meowpuut-ffi --features glyph --target "$RUST_TARGET" --crate-type staticlib
RUST_LIB="target/$RUST_TARGET/release/libmeowpuut_ffi.a"
RUST_NATIVE=$(cargo rustc --locked --manifest-path Engine/Cargo.toml --target-dir target --quiet --release -p meowpuut-ffi --features glyph --target "$RUST_TARGET" --crate-type staticlib -- --print native-static-libs 2>&1 \
  | sed -n 's/.*native-static-libs: //p' | tail -1)

rm -rf "$OUT"
mkdir -p "$OUT/obj"
UI=Windows/UI
WARN=(-Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Werror)
# MEOWPUUT_GLYPH: the 字景 strip's C ABI (mp_glyph_strip_*), the `glyph` feature of meowpuut-ffi built above.
DEFS=(-DUNICODE -D_UNICODE -DNOMINMAX -D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00 -DMEOWPUUT_GLYPH=1)
objects=()
for src in "$UI/Blur.cpp" "$UI/CandidateLayout.cpp" "$UI/Festival.cpp" "$UI/GlassShape.cpp" "$UI/Scene.cpp" \
           "$UI/GlyphStrip.cpp" "$UI/GlyphText.cpp" "$UI/SvgSnapshot.cpp" "$UI/Theme.cpp" "$UI/CandidateWindow.cpp" Windows/Session.cpp Windows/Keyboard.cpp Windows/PetLedger.cpp \
           Windows/Desk/PulseChannel.cpp Windows/TextService/TextService.cpp; do
  obj="$OUT/obj/$(basename "${src%.cpp}").o"
  "$CXX" -std=c++17 "${WARN[@]}" -O2 "${DEFS[@]}" -I "$UI" -c "$src" -o "$obj" &
  objects+=("$obj")
done
wait

# The icon is the app icon at the sizes Windows asks for.
python3 - "$OUT/obj/Meowpuut.ico" <<'EOF'
import sys
from PIL import Image
Image.open('assets/brand/AppIcon.png').convert('RGBA').save(
    sys.argv[1], sizes=[(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (256, 256)])
EOF
"$WINDRES" --codepage=65001 -I "$OUT/obj" -DMEOWPUUT_VERSION="\\\"$VERSION\\\"" -DMEOWPUUT_VERSION_NUMBERS="$VERSION_NUMBERS" \
  Windows/TextService/TextService.rc -O coff -o "$OUT/obj/TextService.res.o"

# shellcheck disable=SC2086 # RUST_NATIVE is a list of -l flags
"$CXX" -shared -static -s -O2 ${LDEXTRA[@]+"${LDEXTRA[@]}"} -o "$OUT/MeowpuutTextService.dll" "${objects[@]}" "$OUT/obj/TextService.res.o" \
  Windows/TextService/TextService.def "$RUST_LIB" \
  -ld2d1 -ldwrite -lwindowscodecs -lole32 -loleaut32 -luuid -luser32 -lgdi32 -ladvapi32 -lshell32 $RUST_NATIVE
echo "Built $OUT/MeowpuutTextService.dll ($(du -h "$OUT/MeowpuutTextService.dll" | cut -f1))"
