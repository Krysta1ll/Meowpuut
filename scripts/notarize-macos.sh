#!/bin/bash
# Signs, notarizes and staples the macOS release (Developer ID, hardened runtime). Prepared for the
# Steam/direct release; it needs the owner's Apple Developer account and is NOT run by any other script.
#
#   MEOWPUUT_SIGN_ID="Developer ID Application: Name (TEAMID)"
#   MEOWPUUT_INSTALLER_ID="Developer ID Installer: Name (TEAMID)"   (not needed for --steam)
#   MEOWPUUT_NOTARY_PROFILE=name   (made once with: xcrun notarytool store-credentials name ...)
#   bash scripts/build.sh && bash scripts/notarize-macos.sh [--dmg]
#   bash scripts/package-steam-macos.sh && bash scripts/notarize-macos.sh --steam
#
# --dmg also builds the .dmg (needs .build/dmg-tools, see scripts/package-dmg.py), signs, notarizes
# and staples it. Without it only the .pkg is produced.
# --steam signs, notarizes and staples .build/steam-mac/Meowpuut Companion.app together with its
# embedded Meowpuut.app (IME), Meowpuut Desk.app and libsteam_api.dylib using the same Developer ID
# (so XPC team-ID signature checks pass). `--check` only validates the arguments.
#
# Entitlements: none. An input method is an ordinary app to the hardened runtime: it loads no
# unsigned plug-ins, uses no JIT and no DYLD variables, and talks to the system through InputMethodKit's
# Mach service, which needs no entitlement. The Rust engine is linked statically. If a future change
# loads third-party code, add scripts/Meowpuut.entitlements and pass --entitlements to codesign here.
set -euo pipefail
cd "$(dirname "$0")/.."
WANT_DMG=0; WANT_STEAM=0; CHECK_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --dmg) WANT_DMG=1 ;;
    --steam) WANT_STEAM=1 ;;
    --check) CHECK_ONLY=1 ;;
    *) echo "usage: $0 [--dmg] [--steam] [--check]" >&2; exit 2 ;;
  esac
done
missing=()
if [ "$WANT_STEAM" = 1 ]; then
  REQUIRED_VARS=(MEOWPUUT_SIGN_ID MEOWPUUT_NOTARY_PROFILE)
else
  REQUIRED_VARS=(MEOWPUUT_SIGN_ID MEOWPUUT_INSTALLER_ID MEOWPUUT_NOTARY_PROFILE)
fi
for v in "${REQUIRED_VARS[@]}"; do
  [ -n "${!v:-}" ] || missing+=("$v")
done
if [ "${#missing[@]}" -gt 0 ]; then
  echo "error: 缺少環境變數 / missing environment variables: ${missing[*]}" >&2
  echo "需要 Developer ID 憑證與 notarytool 設定檔；見本檔開頭的說明。" >&2
  exit 1
fi
if [ "$WANT_STEAM" = 1 ]; then
  STEAM_APP="$PWD/.build/steam-mac/Meowpuut Companion.app"
  STEAM_IME="$STEAM_APP/Contents/Resources/Meowpuut.app"
  STEAM_DESK="$STEAM_APP/Contents/Resources/Meowpuut Desk.app"
  STEAM_DYLIB="$STEAM_APP/Contents/MacOS/libsteam_api.dylib"
  STEAM_ENT="$PWD/Companion/src-tauri/Entitlements.steam.plist"
  for p in "$STEAM_APP" "$STEAM_IME" "$STEAM_DESK"; do
    [ -d "$p" ] || { echo "error: $p 不存在，請先執行 bash scripts/package-steam-macos.sh" >&2; exit 1; }
  done
  [ -f "$STEAM_DYLIB" ] || { echo "error: 缺少 $STEAM_DYLIB" >&2; exit 1; }
  [ -f "$STEAM_ENT" ] || { echo "error: 缺少 $STEAM_ENT" >&2; exit 1; }
  [ "$CHECK_ONLY" = 0 ] || { echo "Steam 參數與目錄檢查通過。"; exit 0; }
  export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
  echo "== [Steam] 由內而外簽署三套 bundle 與 libsteam_api.dylib（同一 Team ID + Hardened Runtime）"
  codesign --force --options runtime --timestamp --sign "$MEOWPUUT_SIGN_ID" "$STEAM_IME"
  codesign --force --options runtime --timestamp --sign "$MEOWPUUT_SIGN_ID" --identifier app.meowpuut.desk "$STEAM_DESK"
  codesign --force --options runtime --timestamp --sign "$MEOWPUUT_SIGN_ID" "$STEAM_DYLIB"
  codesign --force --options runtime --timestamp --entitlements "$STEAM_ENT" --sign "$MEOWPUUT_SIGN_ID" --identifier app.meowpuut.companion "$STEAM_APP"
  codesign --verify --strict --deep --verbose=2 "$STEAM_APP"
  ZIP="$PWD/.build/steam-mac-notarize.zip"
  rm -f "$ZIP"
  echo "== [Steam] 打包送交公證：$ZIP"
  ditto -c -k --keepParent "$STEAM_APP" "$ZIP"
  xcrun notarytool submit "$ZIP" --keychain-profile "$MEOWPUUT_NOTARY_PROFILE" --wait
  rm -f "$ZIP"
  echo "== [Steam] Staple 與驗證"
  xcrun stapler staple "$STEAM_IME"
  xcrun stapler staple "$STEAM_DESK"
  xcrun stapler staple "$STEAM_APP"
  xcrun stapler validate "$STEAM_APP"
  spctl -a -vv -t exec "$STEAM_APP"
  echo "完成（Steam macOS）：$STEAM_APP"
  exit 0
fi
APP="$PWD/.build/Meowpuut.app"
[ -d "$APP" ] || { echo "error: $APP 不存在，請先執行 bash scripts/build.sh" >&2; exit 1; }
[ "$CHECK_ONLY" = 0 ] || { echo "參數檢查通過。"; exit 0; }
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"

# Both slices must be present: Developer ID releases are universal.
archs="$(lipo -archs "$APP/Contents/MacOS/Meowpuut")"
case "$archs" in *arm64*x86_64*|*x86_64*arm64*) ;; *) echo "error: 不是 universal 二進位（$archs）" >&2; exit 1 ;; esac

echo "== 簽署 App（hardened runtime）"
codesign --force --deep --options runtime --timestamp --sign "$MEOWPUUT_SIGN_ID" "$APP"
codesign --verify --strict --deep --verbose=2 "$APP"
codesign -dv "$APP" 2>&1 | grep -q "flags=.*runtime" || { echo "error: hardened runtime 沒有啟用" >&2; exit 1; }

echo "== 建立並簽署安裝套件"
PKG="$(MEOWPUUT_INSTALLER_ID="$MEOWPUUT_INSTALLER_ID" bash scripts/package-macos.sh | tail -n 1)"
[ -f "$PKG" ] || { echo "error: 找不到安裝套件" >&2; exit 1; }
ARTIFACTS=("$PKG")
if [ "$WANT_DMG" = 1 ]; then
  echo "== 建立並簽署安裝盤"
  .build/dmg-tools/bin/python scripts/package-dmg.py
  DMG="${PKG%.pkg}.dmg"
  [ -f "$DMG" ] || { echo "error: 找不到安裝盤" >&2; exit 1; }
  codesign --force --timestamp --sign "$MEOWPUUT_SIGN_ID" "$DMG"
  ARTIFACTS+=("$DMG")
fi

for artifact in "${ARTIFACTS[@]}"; do
  echo "== 送交公證：$artifact"
  xcrun notarytool submit "$artifact" --keychain-profile "$MEOWPUUT_NOTARY_PROFILE" --wait
  xcrun stapler staple "$artifact"
  xcrun stapler validate "$artifact"
  case "$artifact" in
    *.pkg) spctl -a -vv -t install "$artifact" ;;
    *.dmg) spctl -a -vv -t open --context context:primary-signature "$artifact" ;;
  esac
done
( cd "$(dirname "$PKG")" && shasum -a 256 "${ARTIFACTS[@]##*/}" > SHA256SUMS.txt )
echo "完成：${ARTIFACTS[*]}"
