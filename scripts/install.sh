#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
test -x .build/Meowpuut.app/Contents/MacOS/Meowpuut || bash scripts/build.sh
DEST="$HOME/Library/Input Methods/Meowpuut.app"
if [ -e "$DEST" ]; then
  if [ "${1:-}" != "--update" ]; then
    echo "已存在安装。更新请退出 Meowpuut 后运行 bash scripts/install.sh --update。"
    exit 1
  fi
  if pgrep -f "$DEST/Contents/MacOS/Meowpuut" > /dev/null; then
    echo "请先退出 Meowpuut 再更新。"
    exit 1
  fi
  ID=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$DEST/Contents/Info.plist")
  case "$ID" in
    app.meowpuut.inputmethod|app.meowpuut.inputmethod.Meowpuut) ;;
    *) echo "安装目录被其他应用占用。"; exit 1 ;;
  esac
  mv "$DEST" "$PWD/.build/Previous-Meowpuut-$(date +%Y%m%d-%H%M%S).app"
fi
mkdir -p "$HOME/Library/Input Methods"
cp -R .build/Meowpuut.app "$DEST"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$DEST"
.build/register-input-source "$DEST"
echo "已安装。请在系统设置 → 键盘 → 输入法中添加 Meowpuut；未显示时注销后重试。"
