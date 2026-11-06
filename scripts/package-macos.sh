#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
APP="$PWD/.build/Meowpuut.app"
VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")
LABEL="${MEOWPUUT_RELEASE_LABEL:-$VERSION-alpha.1}"
ARCH=$(uname -m)
OUT="$PWD/.build/releases"
STAGE="$PWD/.build/package-stage"
codesign --verify --strict "$APP"
mkdir -p "$OUT" "$STAGE/Library/Input Methods"
ditto --noextattr --norsrc "$APP" "$STAGE/Library/Input Methods/Meowpuut.app"
pkgbuild --root "$STAGE" --component-plist scripts/components.plist \
  --identifier app.meowpuut.inputmethod.pkg --version "$VERSION" --install-location / \
  "$OUT/Meowpuut-component.pkg"
cat > "$OUT/Distribution.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
  <title>喵撲 Meowpuut $LABEL</title>
  <options customize="never" require-scripts="false" hostArchitectures="$ARCH"/>
  <allowed-os-versions><os-version min="27.0"/></allowed-os-versions>
  <domains enable_localSystem="true" enable_currentUserHome="false" enable_anywhere="false"/>
  <choices-outline><line choice="default"/></choices-outline>
  <choice id="default" visible="false"><pkg-ref id="app.meowpuut.inputmethod.pkg"/></choice>
  <pkg-ref id="app.meowpuut.inputmethod.pkg" version="$VERSION" onConclusion="RequireLogout">Meowpuut-component.pkg</pkg-ref>
</installer-gui-script>
XML
NAME="Meowpuut-$LABEL-macos-$ARCH.pkg"
productbuild --distribution "$OUT/Distribution.xml" --package-path "$OUT" "$OUT/$NAME"
(cd "$OUT" && shasum -a 256 "$NAME" > SHA256SUMS.txt)
echo "$OUT/$NAME"
