#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK.}"
: "${MEOWPUUT_ANDROID_KEYSTORE:?Set the existing release keystore path.}"
: "${MEOWPUUT_ANDROID_KEY_ALIAS:?Set the release key alias.}"
: "${MEOWPUUT_ANDROID_STORE_PASS_FILE:?Set a local file containing the keystore password.}"
key_pass_file="${MEOWPUUT_ANDROID_KEY_PASS_FILE:-$MEOWPUUT_ANDROID_STORE_PASS_FILE}"
version=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" Android/build.gradle)
[[ -n "$version" && -f "$MEOWPUUT_ANDROID_KEYSTORE" && -f "$MEOWPUUT_ANDROID_STORE_PASS_FILE" && -f "$key_pass_file" ]]
output="$PWD/.build/releases/$version"
mkdir -p "$output"
python3 scripts/compile-native-lexicon.py Android/Generated
"${GRADLE:-gradle}" -p Android assembleRelease --no-daemon --console=plain
build_tools="$ANDROID_HOME/build-tools/35.0.0"
apk="$output/Meowpuut-$version-android.apk"
unsigned=(Android/build/outputs/apk/release/*-release-unsigned.apk)
[[ ${#unsigned[@]} == 1 && -f "${unsigned[0]}" ]]
"$build_tools/zipalign" -P 16 -f 4 "${unsigned[0]}" "$output/android-aligned.apk"
signing=(sign --ks "$MEOWPUUT_ANDROID_KEYSTORE" --ks-key-alias "$MEOWPUUT_ANDROID_KEY_ALIAS"
    --ks-pass "file:$MEOWPUUT_ANDROID_STORE_PASS_FILE")
if [[ "$key_pass_file" != "$MEOWPUUT_ANDROID_STORE_PASS_FILE" ]]; then
    signing+=(--key-pass "file:$key_pass_file")
fi
"$build_tools/apksigner" "${signing[@]}" --out "$apk" "$output/android-aligned.apk"
rm "$output/android-aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$apk"
"$build_tools/zipalign" -c -P 16 4 "$apk"
shasum -a 256 "$apk"
