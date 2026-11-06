#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
APP="$PWD/.build/Meowpuut.app"
VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")
LABEL="${MEOWPUUT_RELEASE_LABEL:-$VERSION-alpha.1}"
ARCH=$(uname -m)
OUT="$PWD/.build/releases"
mkdir -p "$PWD/.build"
# Each package gets a fresh stage so removed resources cannot leak into updates.
STAGE="$(mktemp -d "$PWD/.build/package-stage.XXXXXX")"
codesign --verify --strict "$APP"
mkdir -p "$OUT" "$STAGE/Library/Input Methods" "$PWD/.build/installer-resources"
cp assets/brand/MeowpuutMark.png "$PWD/.build/installer-resources/Mark.png"
cat > "$PWD/.build/installer-resources/Welcome.html" <<'HTML'
<html><meta charset="utf-8"><body style="font-family:-apple-system;color:#302d2a;padding:24px"><img src="Mark.png" width="100"><h1>把小貓接回家。</h1><p>喵撲 Meowpuut · 拼音、注音與偶爾的日語。</p><p>這個安裝程式會把喵撲放入系統的「輸入法」資料夾。更新時會保留本機用詞記憶與設定。</p><p>需要 macOS 27 或更新版本，以及 Apple silicon。</p></body></html>
HTML
cat > "$PWD/.build/installer-resources/Conclusion.html" <<'HTML'
<html><meta charset="utf-8"><body style="font-family:-apple-system;color:#302d2a;padding:24px"><h1>小貓到家了。</h1><p>先儲存正在做的工作，登出再登入。</p><p>打開「系統設定 → 鍵盤 → 文字輸入 → 編輯」，加入 Meowpuut。</p><p>Control＋Option＋1：注音<br>Control＋Option＋2：拼音<br>拼音模式單按 Shift 切換中英。<br>Shift＋空格／6／3／4／7：拼音一／二／三／四／輕聲。</p><p>輸入法選單可切換雙貓／簡潔皮膚、深淺色調及六組配色。</p></body></html>
HTML
ditto --noextattr --norsrc "$APP" "$STAGE/Library/Input Methods/Meowpuut.app"
pkgbuild --root "$STAGE" --component-plist scripts/components.plist --scripts scripts/installer \
  --identifier app.meowpuut.inputmethod.pkg --version "$VERSION" --install-location / \
  "$OUT/Meowpuut-component.pkg"
cat > "$OUT/Distribution.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
  <title>喵撲 Meowpuut $LABEL</title>
  <welcome file="Welcome.html"/>
  <conclusion file="Conclusion.html"/>
  <options customize="never" require-scripts="false" hostArchitectures="$ARCH"/>
  <allowed-os-versions><os-version min="27.0"/></allowed-os-versions>
  <domains enable_localSystem="true" enable_currentUserHome="false" enable_anywhere="false"/>
  <choices-outline><line choice="default"/></choices-outline>
  <choice id="default" visible="false"><pkg-ref id="app.meowpuut.inputmethod.pkg"/></choice>
  <pkg-ref id="app.meowpuut.inputmethod.pkg" version="$VERSION" onConclusion="RequireLogout">Meowpuut-component.pkg</pkg-ref>
</installer-gui-script>
XML
NAME="Meowpuut-$LABEL-macos-$ARCH.pkg"
productbuild --distribution "$OUT/Distribution.xml" --resources "$PWD/.build/installer-resources" --package-path "$OUT" "$OUT/$NAME"
(cd "$OUT" && shasum -a 256 "$NAME" > SHA256SUMS.txt)
echo "$OUT/$NAME"
