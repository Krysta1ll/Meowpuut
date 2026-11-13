#!/bin/bash
# Builds the Android input method in Android/.
#
#   bash scripts/build-android.sh             -> Android/app/build/outputs/apk/debug/app-debug.apk
#   bash scripts/build-android.sh --release   -> Android/app/build/outputs/apk/release/
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
    cargo rustc --locked --manifest-path Engine/Cargo.toml --target-dir target --release -p meowpuut-ffi --target "$triple" --crate-type staticlib
    out=".build/rust/android/$abi"
    mkdir -p "$out"
    # Replace only when changed so the native build is not redone needlessly.
    if ! cmp -s "target/$triple/release/libmeowpuut_ffi.a" "$out/libmeowpuut_ffi.a"; then
      cp "target/$triple/release/libmeowpuut_ffi.a" "$out/libmeowpuut_ffi.a"
    fi
  done
}

# What the keyboard reads: the Chinese lexicon, its word-pair table and the mimo-jp word list and emoji associations.
LEXICON_FILES="mimo_base.bin mimo_pairs.bin native-japanese.tsv associations.json"
# Everything scripts/compile-native-lexicon.py reads.
LEXICON_SOURCES="Engine/DataSources Engine/Resources/Lexicon Engine/scripts"
lexicon_current() {
  local file
  for file in $LEXICON_FILES; do
    [ -f "$1/$file" ] || return 1
    # shellcheck disable=SC2086
    [ -z "$(find $LEXICON_SOURCES -type f -newer "$1/$file" -print -quit)" ] || return 1
  done
}

stage_assets() {
  local generated="$ROOT/Engine/.build/lexicon"
  if ! lexicon_current "$generated" || [ ! -f "$generated/Licenses/Meowpuut-MIT.txt" ]; then
    bash Engine/scripts/build-lexicon.sh
  fi
  local assets="$ROOT/Android/app/src/main/assets"
  rm -rf "$assets/licenses"
  mkdir -p "$assets/lexicon" "$assets/licenses"
  local file
  for file in $LEXICON_FILES; do
    cmp -s "$generated/$file" "$assets/lexicon/$file" || cp "$generated/$file" "$assets/lexicon/$file"
  done
  cp "$generated"/Licenses/* "$assets/licenses/"
  cp ASSETS-LICENSE.md "$assets/licenses/Meowpuut-Artwork.txt"

}

build_rust
stage_assets
[ "$mode" = "--prepare" ] && exit 0

cd Android
GRADLE="${MEOWPUUT_GRADLE:-./gradlew}"
export ANDROID_HOME="$SDK"
# AGP and its Kotlin compiler run on JDK 17–25.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  java_versions=(25 21 17)
  [ "$mode" != "--test" ] || java_versions=(25)
  for version in "${java_versions[@]}"; do
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

# The release APK is signed with the project's release key when this Mac has it:
# MEOWPUUT_ANDROID_KEYSTORE (default ~/.android/meowpuut/meowpuut-release.p12, alias
# meowpuut), password from the login Keychain item "meowpuut-android-release".
# Without the key the unsigned APK is left as it is.
if [ "$mode" = "--release" ]; then
  KEYSTORE="${MEOWPUUT_ANDROID_KEYSTORE:-$HOME/.android/meowpuut/meowpuut-release.p12}"
  OUTDIR=app/build/outputs/apk/release
  if [ -f "$KEYSTORE" ]; then
    APKSIGNER="$SDK/build-tools/37.0.0/apksigner"
    MEOWPUUT_KS_PASS="$(security find-generic-password -s meowpuut-android-release -a meowpuut -w)" \
      "$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias meowpuut --ks-pass env:MEOWPUUT_KS_PASS \
      --out "$OUTDIR/app-release.apk" "$OUTDIR/app-release-unsigned.apk"
    "$APKSIGNER" verify "$OUTDIR/app-release.apk"
    build=$("$SDK/build-tools/37.0.0/aapt" dump badging "$OUTDIR/app-release.apk" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p")
    [ -n "$build" ] || { echo "error: no versionCode in signed APK" >&2; exit 1; }
    release="$OUTDIR/Meowpuut-Android-Prism-$build.apk"
    cp "$OUTDIR/app-release.apk" "$release"
    echo "signed: Android/$release"
  else
    echo "no release key at $KEYSTORE: Android/$OUTDIR/app-release-unsigned.apk is unsigned"
  fi
fi
