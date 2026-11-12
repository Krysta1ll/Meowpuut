#!/bin/bash
# Checks the Windows candidate window on a machine without Windows.
#
#   bash scripts/build-windows-check.sh
#
# 1. Layout tests (bounds-checked standard library, UndefinedBehaviorSanitizer;
#    AddressSanitizer with MEOWPUUT_ASAN=1).
# 2. session_test against the host Rust library and a generated lexicon
#    (MEOWPUUT_LEXICON_DIR, default iOS/Generated), when the lexicon exists.
# 3. SVG snapshots of every shape, tone and state, converted to PNG
#    (macOS: text is measured with Core Text; needs rsvg-convert).
# 4. MinGW-w64 cross build of the Rust engine, the candidate window, the
#    preview app and the text service DLL (scripts/build-windows-ime.sh), with
#    GCC MinGW-w64 or llvm-mingw (MINGW_BIN).
#
# Output: .build/windows-check/
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"
OUT=.build/windows-check
UI=Windows/UI
PORTABLE=("$UI/Blur.cpp" "$UI/CandidateLayout.cpp" "$UI/Festival.cpp" "$UI/GlassShape.cpp" "$UI/Scene.cpp" "$UI/SvgSnapshot.cpp" "$UI/Theme.cpp")
WARN=(-Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Werror)
CXX="${CXX:-c++}"
mkdir -p "$OUT"

echo "== candidate layout tests"
# Runs a test binary, stopping it after 120 s so a hung runtime is reported
# instead of blocking the script.
run_test() {
  local status=0
  perl -e 'alarm 120; exec @ARGV' "$@" || status=$?
  if [ "$status" = 142 ]; then echo "error: $1 did not finish in 120 s" >&2; return 1; fi
  return "$status"
}
# Bounds-checked standard library, then UndefinedBehaviorSanitizer.
"$CXX" -std=c++17 "${WARN[@]}" -O1 -g -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_DEBUG -D_GLIBCXX_ASSERTIONS \
  -I "$UI" "${PORTABLE[@]}" "$UI/tests/layout_test.cpp" -o "$OUT/layout_test"
run_test "$OUT/layout_test"
"$CXX" -std=c++17 "${WARN[@]}" -O1 -g -fsanitize=undefined -fno-sanitize-recover=undefined -I "$UI" "${PORTABLE[@]}" \
  "$UI/tests/layout_test.cpp" -o "$OUT/layout_test_ubsan"
run_test "$OUT/layout_test_ubsan"
# AddressSanitizer on request: on macOS 27 its runtime can hang before main
# even for an empty program.
if [ "${MEOWPUUT_ASAN:-0}" = 1 ]; then
  "$CXX" -std=c++17 "${WARN[@]}" -O1 -g -fsanitize=address -I "$UI" "${PORTABLE[@]}" "$UI/tests/layout_test.cpp" \
    -o "$OUT/layout_test_asan"
  run_test "$OUT/layout_test_asan"
fi

echo "== session test"
LEX="${MEOWPUUT_LEXICON_DIR:-iOS/Generated}"
if [ -f "$LEX/native-pinyin.tsv" ] && [ -f "$LEX/native-zhuyin.tsv" ] && [ -f "$LEX/native-japanese.tsv" ]; then
  cargo rustc --quiet --release -p meowpuut-ffi --crate-type staticlib
  "$CXX" -std=c++17 -O2 Windows/Session.cpp Windows/Keyboard.cpp Windows/tests/session_test.cpp \
    target/release/libmeowpuut_ffi.a -o "$OUT/session_test"
  BASE=()
  if [ -f "$LEX/mimo_base.bin" ]; then BASE=("$LEX/mimo_base.bin"); fi
  "$OUT/session_test" "$LEX/native-pinyin.tsv" "$LEX/native-zhuyin.tsv" "$LEX/native-japanese.tsv" ${BASE[@]+"${BASE[@]}"}
  echo "session_test passed"
else
  echo "skipped: no lexicon in $LEX (set MEOWPUUT_LEXICON_DIR)"
fi

echo "== SVG snapshots"
if [ "$(uname)" = Darwin ] && command -v rsvg-convert >/dev/null; then
  "$CXX" -std=c++17 -O1 -I "$UI" "${PORTABLE[@]}" "$UI/tools/render_previews.cpp" \
    -framework CoreText -framework CoreFoundation -o "$OUT/render_previews"
  rm -rf "$OUT/previews"
  mkdir -p "$OUT/previews"
  "$OUT/render_previews" "$UI/Assets" "$OUT/previews"
  for svg in "$OUT"/previews/*.svg; do rsvg-convert "$svg" -o "${svg%.svg}.png"; done
  echo "PNG snapshots in $OUT/previews"
else
  echo "skipped: needs macOS and rsvg-convert"
fi

echo "== MinGW-w64 cross build"
# GCC (brew install mingw-w64) or clang (llvm-mingw); MINGW_BIN may name the
# toolchain's bin directory when it is not on PATH.
if [ -n "${MINGW_BIN:-}" ]; then export PATH="$MINGW_BIN:$PATH"; fi
if command -v x86_64-w64-mingw32-g++ >/dev/null; then
  MCXX=x86_64-w64-mingw32-g++
elif command -v x86_64-w64-mingw32-clang++ >/dev/null; then
  MCXX=x86_64-w64-mingw32-clang++
else
  echo "skipped: no MinGW-w64 C++ compiler (brew install mingw-w64, or set MINGW_BIN to an llvm-mingw bin directory)"
  exit 0
fi
echo "compiler: $("$MCXX" --version | head -1)"
WINDRES=x86_64-w64-mingw32-windres
TARGET="${RUST_WINDOWS_TARGET:-x86_64-pc-windows-gnu}"
rustup target list --installed | grep -qx "$TARGET" || rustup target add "$TARGET"
cargo rustc --quiet --release -p meowpuut-ffi --target "$TARGET" --crate-type staticlib
RUST_LIB="target/$TARGET/release/libmeowpuut_ffi.a"
# Libraries the Rust standard library needs on this target.
RUST_NATIVE=$(cargo rustc --quiet --release -p meowpuut-ffi --target "$TARGET" --crate-type staticlib -- --print native-static-libs 2>&1 \
  | sed -n 's/.*native-static-libs: //p' | tail -1)
MW="$OUT/mingw"
rm -rf "$MW"
mkdir -p "$MW/obj"
DEFS=(-DUNICODE -D_UNICODE -DNOMINMAX -D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00)
objects=()
for src in "${PORTABLE[@]}" "$UI/CandidateWindow.cpp" Windows/Session.cpp Windows/Keyboard.cpp Windows/Preview/PreviewApp.cpp; do
  obj="$MW/obj/$(basename "${src%.cpp}").o"
  "$MCXX" -std=c++17 "${WARN[@]}" -O2 "${DEFS[@]}" -I "$UI" -c "$src" -o "$obj"
  objects+=("$obj")
done
(cd Windows/Preview && "$WINDRES" Preview.rc -O coff -o "../../$MW/obj/preview_res.o")
"$MCXX" -municode -mwindows -static -O2 -o "$MW/MeowpuutCandidatePreview.exe" "${objects[@]}" "$MW/obj/preview_res.o" \
  "$RUST_LIB" -ld2d1 -ldwrite -lwindowscodecs -lole32 -luuid -luser32 -lgdi32 -ladvapi32 -lshell32 -limm32 $RUST_NATIVE
"$MCXX" -std=c++17 "${WARN[@]}" -O2 -static -I "$UI" "${PORTABLE[@]}" "$UI/tests/layout_test.cpp" -o "$MW/meowpuut_layout_test.exe"
cp -R "$UI/Assets" "$MW/Assets"
echo "Built $MW/MeowpuutCandidatePreview.exe (copy a lexicon folder next to it before running on Windows)"
bash scripts/build-windows-ime.sh x64 "$OUT/mingw-ime"
