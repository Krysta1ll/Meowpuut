#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
APP="$PWD/.build/Meowpuut Engine Preview.app"
if pgrep -xf "$APP/Contents/MacOS/Meowpuut" >/dev/null; then
  echo "請先退出引擎試打版再更新。"
  exit 1
fi
bash scripts/build.sh
ditto "$PWD/.build/Meowpuut.app" "$APP"
INFO="$APP/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier app.meowpuut.EnginePreview" \
  -c "Set :CFBundleName Meowpuut Engine Preview" -c "Set :LSUIElement false" "$INFO"
for key in InputMethodConnectionName InputMethodServerControllerClass InputMethodServerDelegateClass \
           TISInputSourceID TISIntendedLanguage tsInputMethodIconFileKey ComponentInputModeDict; do
  /usr/libexec/PlistBuddy -c "Delete :$key" "$INFO"
done
codesign --force --sign - "$APP"
codesign --verify --strict "$APP"
LS=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
"$LS" -u "$PWD/.build/Meowpuut.app" 2>/dev/null || true
printf '%s\n' "$APP"
