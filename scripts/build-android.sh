#!/bin/bash
# Builds the Android input method in Android/.
#
#   bash scripts/build-android.sh             -> Android/app/build/outputs/apk/debug/app-debug.apk
#   bash scripts/build-android.sh --release   -> Android/app/build/outputs/apk/release/app-release-unsigned.apk
#   bash scripts/build-android.sh --test      -> the debug APK and the JVM unit tests
#   bash scripts/build-android.sh --prepare   -> only the Rust engine and the lexicon assets
#                                                (Gradle's preBuild runs this)
#
# Needs the Android SDK (ANDROID_HOME, default ~/Library/Android/sdk) with
# platform 37.0, build-tools 37.0.0, NDK 30.0.16248370 and CMake 3.31.6; Rust
# with the aarch64-linux-android and x86_64-linux-android targets; Gradle 9
# and a JDK 17–25. Gradle never downloads SDK packages
# (android.builder.sdkDownload=false): a missing one stops the build.
#
#   MEOWPUUT_GRADLE=gradle        use an installed Gradle 9 instead of ./gradlew
#   MEOWPUUT_GRADLE_ARGS="..."    extra Gradle arguments, e.g. "--offline --max-workers=4"
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
export PATH="$HOME/.cargo/bin:$PATH"

mode="${1:-debug}"
case "$mode" in
  debug|--release|--test|--prepare) ;;
  *) echo "usage: bash scripts/build-android.sh [--release|--test|--prepare]" >&2; exit 2 ;;
esac
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/30.0.16248370}"
API=26

case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;   # the NDK uses this name on Apple silicon too
  Linux) HOST_TAG=linux-x86_64 ;;
  *) echo "unsupported host $(uname -s)" >&2; exit 2 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"

build_rust() {
  if [ ! -d "$TOOLCHAIN" ]; then
    echo "error: Android NDK not found at $NDK" >&2
    exit 1
  fi
  # Rust reads these; Xcode's values must not leak into the Android build.
  unset SDKROOT LIBRARY_PATH
  local installed
  installed="$(rustup target list --installed)"
  for abi in arm64-v8a x86_64; do
    case "$abi" in
      arm64-v8a) triple=aarch64-linux-android ;;
      x86_64) triple=x86_64-linux-android ;;
    esac
    grep -qx "$triple" <<<"$installed" || rustup target add "$triple"
    upper="$(echo "$triple" | tr '[:lower:]-' '[:upper:]_')"
    export "CARGO_TARGET_${upper}_LINKER=$TOOLCHAIN/${triple}${API}-clang"
    export "CC_${triple//-/_}=$TOOLCHAIN/${triple}${API}-clang"
    export "AR_${triple//-/_}=$TOOLCHAIN/llvm-ar"
    cargo rustc --release -p meowpuut-ffi --target "$triple" --crate-type staticlib
    out=".build/rust/android/$abi"
    mkdir -p "$out"
    # Replace only when changed so the native build is not redone needlessly.
    if ! cmp -s "target/$triple/release/libmeowpuut_ffi.a" "$out/libmeowpuut_ffi.a"; then
      cp "target/$triple/release/libmeowpuut_ffi.a" "$out/libmeowpuut_ffi.a"
    fi
  done
}

# What the keyboard reads: the Chinese lexicon, its word-pair table and the mimo-jp word list.
LEXICON_FILES="mimo_base.bin mimo_pairs.bin native-japanese.tsv"
# Everything scripts/compile-native-lexicon.py reads.
LEXICON_SOURCES="DataSources Resources/Lexicon scripts/compile-native-lexicon.py scripts/mimo_pipeline.py scripts/mimo_binary.py scripts/mimo_pairs.py scripts/mimo_jp_compile.py scripts/mimo_jp_binary.py scripts/japanese_readings.py scripts/ConvertScript.swift"

# True when a directory holds the lexicon files and no source is newer than them.
lexicon_current() {
  local file
  for file in $LEXICON_FILES; do
    [ -f "$1/$file" ] || return 1
    # shellcheck disable=SC2086
    [ -z "$(find $LEXICON_SOURCES -type f -newer "$1/$file" -print -quit)" ] || return 1
  done
}

stage_assets() {
  # iOS/Generated (scripts/prepare-ios.sh) is reused while it is current;
  # otherwise the same compiler writes a copy for Android.
  local generated="$ROOT/iOS/Generated"
  if ! lexicon_current "$generated"; then
    generated="$ROOT/.build/android-lexicon"
    if ! lexicon_current "$generated"; then
      mkdir -p "$generated"
      swiftc scripts/ConvertScript.swift -o .build/convert-script
      python3 scripts/compile-native-lexicon.py "$generated" "$ROOT/.build/convert-script"
    fi
  fi
  local assets="$ROOT/Android/app/src/main/assets"
  rm -rf "$assets/licenses"
  mkdir -p "$assets/lexicon" "$assets/licenses"
  local file
  for file in $LEXICON_FILES; do
    cmp -s "$generated/$file" "$assets/lexicon/$file" || cp "$generated/$file" "$assets/lexicon/$file"
  done
  # The same notices as the macOS, iOS and Windows bundles.
  cp LICENSE "$assets/licenses/Meowpuut-MIT.txt"
  cp DataSources/mimo-tc/LICENSE.txt "$assets/licenses/mimo-tc-MIT.txt"
  cp DataSources/mimo-tc/OPENCC-LICENSE.txt "$assets/licenses/OpenCC-Apache-2.0.txt"
  cp DataSources/mimo-tc/JIEBA-LICENSE.txt "$assets/licenses/jieba-MIT.txt"
  cp DataSources/mimo-tc/SOURCE.json "$assets/licenses/mimo-tc-SOURCE.json"
  cp DataSources/mimo-sc/LICENSE "$assets/licenses/mimo-sc-MIT.txt"
  cp DataSources/mimo-sc/SOURCE.json "$assets/licenses/mimo-sc-SOURCE.json"
  cp DataSources/mimo-jp/LICENSE.txt "$assets/licenses/mimo-jp-derived-BSD.txt"
  cp DataSources/mimo-jp/BSD.txt "$assets/licenses/mimo-jp-BSD.txt"
  cp DataSources/mimo-jp/COPYING.txt "$assets/licenses/mimo-jp-COPYING.txt"
  cp DataSources/mimo-jp/SOURCE.json "$assets/licenses/mimo-jp-SOURCE.json"
  cp DataSources/mimo-jp/MOZC-LICENSE.txt "$assets/licenses/mimo-jp-Mozc-BSD.txt"
  cp DataSources/mimo-jp/UNICODE-LICENSE.txt "$assets/licenses/mimo-jp-Unicode-3.0.txt"
  cp DataSources/mimo-pairs/SOURCE.json "$assets/licenses/mimo-pairs-SOURCE.json"
  cp DataSources/mimo-pairs/Apache-2.0.txt "$assets/licenses/mimo-pairs-Apache-2.0.txt"
  cp DataSources/mimo-pairs/CC-BY-4.0.txt "$assets/licenses/mimo-pairs-CC-BY-4.0.txt"
  cp DataSources/mimo-names/SOURCE.json "$assets/licenses/mimo-names-SOURCE.json"
  cp DataSources/mimo-phrases/SOURCE.json "$assets/licenses/mimo-phrases-SOURCE.json"
  cp DataSources/mimo-pairs/CC-BY-2.0-FR.html "$assets/licenses/mimo-pairs-Tatoeba-CC-BY-2.0-FR.html"
  cp DataSources/mimo-pairs/CC0-1.0.txt "$assets/licenses/mimo-pairs-CommonVoice-CC0-1.0.txt"
  cp DataSources/mimo-latin/SOURCE.json "$assets/licenses/mimo-latin-SOURCE.json"
}

build_rust
stage_assets
[ "$mode" = "--prepare" ] && exit 0

cd Android
GRADLE="${MEOWPUUT_GRADLE:-./gradlew}"
export ANDROID_HOME="$SDK"
# AGP and its Kotlin compiler run on JDK 17–25.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  for version in 25 21 17; do
    if home="$(/usr/libexec/java_home -v "$version" 2>/dev/null)"; then export JAVA_HOME="$home"; break; fi
  done
fi
case "$mode" in
  --release) tasks="assembleRelease" ;;
  --test) tasks="assembleDebug testDebugUnitTest" ;;
  *) tasks="assembleDebug" ;;
esac
# shellcheck disable=SC2086
"$GRADLE" ${MEOWPUUT_GRADLE_ARGS:-} $tasks
