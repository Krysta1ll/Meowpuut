#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
APP="$PWD/.build/Meowpuut.app"
VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")
BUILD=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$APP/Contents/Info.plist")
BUILD_ID=$(printf '26A%03d' "$BUILD")
LABEL="${MEOWPUUT_RELEASE_LABEL:-Sill-$BUILD_ID}"
ARCH=universal
OUT="${MEOWPUUT_RELEASE_DIR:-$PWD/.build/releases}"
STAGE="$(mktemp -d "$PWD/.build/package-stage.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
codesign --verify --strict "$APP"
mkdir -p "$OUT" "$STAGE/Library/Input Methods" "$PWD/.build/installer-resources"
cp assets/brand/MeowpuutMark.png "$PWD/.build/installer-resources/Mark.png"
cat > "$PWD/.build/installer-resources/Welcome.html" <<'HTML'
<html><meta charset="utf-8"><body style="font-family:-apple-system;color:#302d2a;padding:24px"><img src="Mark.png" width="100"><h1>把小貓接回家。</h1><p>喵撲 Meowpuut · 拼音、注音與偶爾的日語。</p><p>這個安裝程式會把喵撲放入系統的「輸入法」資料夾。更新時會保留本機用詞記憶與設定。</p><p>需要 macOS 15 或更新版本，Apple 晶片與 Intel Mac 都可以。</p></body></html>
HTML
cat > "$PWD/.build/installer-resources/Conclusion.html" <<'HTML'
<html><meta charset="utf-8"><body style="font-family:-apple-system;color:#302d2a;padding:24px"><h1>小貓到家了。</h1><p>打開「系統設定 → 鍵盤 → 文字輸入 → 編輯」，確認 Meowpuut 已在清單裡，沒有的話按加入。</p><p>如果清單裡找不到，先儲存正在做的工作，登出再登入一次。</p><p>Control＋Option＋1：注音<br>Control＋Option＋2：拼音<br>拼音模式單按 Shift 切換中英。<br>Shift＋空格／6／3／4／7：拼音一／二／三／四／輕聲。</p><p>輸入法選單可切換雙貓／簡潔皮膚、深淺色調及六組配色。</p></body></html>
HTML
ditto --noextattr --norsrc "$APP" "$STAGE/Library/Input Methods/Meowpuut.app"
pkgbuild --root "$STAGE" --component-plist scripts/components.plist --scripts scripts/installer \
  --identifier app.meowpuut.inputmethod.pkg --version "$VERSION" --install-location / \
  "$OUT/Meowpuut-component.pkg"
cat > "$OUT/Distribution.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
  <title>喵撲 Meowpuut · 窗臺 · $BUILD_ID</title>
  <welcome file="Welcome.html"/>
  <conclusion file="Conclusion.html"/>
  <options customize="never" require-scripts="false"/>
  <allowed-os-versions><os-version min="15.0"/></allowed-os-versions>
  <domains enable_localSystem="true" enable_currentUserHome="false" enable_anywhere="false"/>
  <choices-outline><line choice="default"/></choices-outline>
  <choice id="default" visible="false"><pkg-ref id="app.meowpuut.inputmethod.pkg"/></choice>
  <pkg-ref id="app.meowpuut.inputmethod.pkg" version="$VERSION">Meowpuut-component.pkg</pkg-ref>
</installer-gui-script>
XML
NAME="Meowpuut-$LABEL-$ARCH.pkg"
# MEOWPUUT_INSTALLER_ID (a "Developer ID Installer" identity) signs the package; unset keeps the unsigned local build.
SIGN=()
[ -z "${MEOWPUUT_INSTALLER_ID:-}" ] || SIGN=(--sign "$MEOWPUUT_INSTALLER_ID" --timestamp)
productbuild --distribution "$OUT/Distribution.xml" --resources "$PWD/.build/installer-resources" --package-path "$OUT" ${SIGN[@]+"${SIGN[@]}"} "$OUT/$NAME"
(cd "$OUT" && shasum -a 256 "$NAME" > SHA256SUMS.txt)
echo "$OUT/$NAME"
