#!/bin/bash
# Builds and stages the complete macOS Steam depot into .build/steam-mac/:
#   1. Universal Meowpuut.app (IMK input method)
#   2. Universal Meowpuut Desk.app (native desk-pet process)
#   3. Universal Meowpuut Companion.app (Tauri 2 thin view, --features steam, with libsteam_api.dylib)
#      embedding Meowpuut.app and Meowpuut Desk.app inside Contents/Resources/ so first-run setup
#      installs the input method into ~/Library/Input Methods/ with no admin password.
#
# Usage:
#   bash scripts/package-steam-macos.sh [--host-only] [--skip-ime] [--skip-desk]
#
# Then sign, notarize and staple with:
#   MEOWPUUT_SIGN_ID="..." MEOWPUUT_NOTARY_PROFILE="..." bash scripts/notarize-macos.sh --steam
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.cargo/bin:$PATH"

HOST_ONLY=0
SKIP_IME=0
SKIP_DESK=0
for arg in "$@"; do
  case "$arg" in
    --host-only) HOST_ONLY=1 ;;
    --skip-ime) SKIP_IME=1 ;;
    --skip-desk) SKIP_DESK=1 ;;
    *) echo "usage: $0 [--host-only] [--skip-ime] [--skip-desk]" >&2; exit 2 ;;
  esac
done

if [ "$SKIP_IME" = 0 ]; then
  echo "== [1/3] 建置 macOS 輸入法（Meowpuut.app）"
  bash scripts/build.sh
fi
if [ "$SKIP_DESK" = 0 ]; then
  echo "== [2/3] 建置 macOS 貓窩（Meowpuut Desk.app）"
  bash scripts/build-desk.sh
fi

IME_APP="$PWD/.build/Meowpuut.app"
DESK_APP="$PWD/.build/desk/Meowpuut Desk.app"
[ -d "$IME_APP" ] || { echo "error: $IME_APP 不存在" >&2; exit 1; }
[ -d "$DESK_APP" ] || { echo "error: $DESK_APP 不存在" >&2; exit 1; }

echo "== [3/3] 建置 macOS 陪伴 App（Meowpuut Companion.app, --features steam）"
if [ "$HOST_ONLY" = 1 ]; then
  TARGET="$(rustc -vV | sed -n 's/^host: //p')"
else
  TARGET="universal-apple-darwin"
fi
(
  cd Companion
  npm run tauri build -- --target "$TARGET" --bundles app --features steam --config src-tauri/tauri.steam-macos.conf.json
)

BUILT_APP="$PWD/Companion/src-tauri/target/$TARGET/release/bundle/macos/Meowpuut Companion.app"
[ -d "$BUILT_APP" ] || { echo "error: 找不到建置出的 $BUILT_APP" >&2; exit 1; }

STAGE="$PWD/.build/steam-mac"
DEST_APP="$STAGE/Meowpuut Companion.app"
rm -rf "$STAGE"
mkdir -p "$STAGE"
ditto --noextattr --norsrc "$BUILT_APP" "$DEST_APP"
mkdir -p "$DEST_APP/Contents/Resources"
ditto --noextattr --norsrc "$IME_APP" "$DEST_APP/Contents/Resources/Meowpuut.app"
ditto --noextattr --norsrc "$DESK_APP" "$DEST_APP/Contents/Resources/Meowpuut Desk.app"

# Sanity checks required before SteamPipe upload:
[ -f "$DEST_APP/Contents/MacOS/libsteam_api.dylib" ] || { echo "error: 缺少 libsteam_api.dylib" >&2; exit 1; }
if find "$STAGE" -name "steam_appid.txt" | grep -q .; then
  echo "error: steam_appid.txt 不得包含在 Steam depot 中" >&2
  exit 1
fi

echo "已完成 macOS Steam depot 暫存：$STAGE"
echo "下一步（需要 Developer ID 憑證與 notarytool 設定檔）："
echo "  MEOWPUUT_SIGN_ID=\"Developer ID Application: ...\" MEOWPUUT_NOTARY_PROFILE=\"...\" bash scripts/notarize-macos.sh --steam"
