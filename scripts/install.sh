#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -e "/Library/Input Methods/Meowpuut.app" ]; then
  echo "已安裝系統版 Meowpuut。請透過系統安裝包更新。"
  exit 1
fi
test -x .build/Meowpuut.app/Contents/MacOS/Meowpuut || bash scripts/build.sh
DEST="$HOME/Library/Input Methods/Meowpuut.app"
if [ -e "$DEST" ]; then
  if [ "${1:-}" != "--update" ]; then
    echo "已存在安裝。更新請先退出 Meowpuut 後執行 bash scripts/install.sh --update。"
    exit 1
  fi
  if pgrep -f "$DEST/Contents/MacOS/Meowpuut" > /dev/null; then
    echo "請先退出 Meowpuut 再更新。"
    exit 1
  fi
  ID=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$DEST/Contents/Info.plist")
  case "$ID" in
    app.meowpuut.inputmethod|app.meowpuut.inputmethod.Meowpuut) ;;
    *) echo "安裝目錄被其他應用程式佔用。"; exit 1 ;;
  esac
  mv "$DEST" "$PWD/.build/Previous-Meowpuut-$(date +%Y%m%d-%H%M%S).app"
fi
mkdir -p "$HOME/Library/Input Methods"
cp -R .build/Meowpuut.app "$DEST"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
# Running the build can register its bundle ID with Launch Services.
"$LSREGISTER" -u "$PWD/.build/Meowpuut.app" 2>/dev/null || true
"$LSREGISTER" -f "$DEST"
.build/register-input-source "$DEST"
echo "已安裝。請在系統設定 → 鍵盤 → 輸入法中加入 Meowpuut；未顯示時請登出後重試。"
