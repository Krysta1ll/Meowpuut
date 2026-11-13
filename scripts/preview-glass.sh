#!/bin/bash
# Developer preview of the macOS candidate window (scripts/GlassPreview.swift).
#
#   bash scripts/preview-glass.sh                  build and open the preview window
#   NO_OPEN=1 bash scripts/preview-glass.sh        build only
#   bash scripts/preview-glass.sh --snapshot DIR   build, write PNG sheets into DIR and exit; no window opens
#   bash scripts/preview-glass.sh --snapshot DIR --festivals F   the same, with the outfits in folder F
#   bash scripts/preview-glass.sh --snapshot DIR --only NAME     only the sheets whose file name starts with NAME
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
APP="$PWD/.build/GlassPreview.app"
BIN="$APP/Contents/MacOS/GlassPreview"
# A failed compile must not leave an older binary to be run by mistake.
rm -f "$BIN"
rm -rf "$APP/Contents/Resources"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp Resources/Cats/*.png assets/brand/MeowpuutMark.png "$APP/Contents/Resources/"
cp -R Resources/Festivals "$APP/Contents/Resources/"
# The preview types through the real engine, so it needs the lexicon that
# scripts/build.sh compiles into the app.
LEXICON="$PWD/.build/Meowpuut.app/Contents/Resources"
[ -f "$LEXICON/mimo_base.bin" ] || bash scripts/build.sh
for file in mimo_base.bin mimo_jp.bin mimo_pairs.bin associations.json language-evidence.sqlite; do
  if [ -e "$LEXICON/$file" ]; then cp -c "$LEXICON/$file" "$APP/Contents/Resources/" 2>/dev/null || cp "$LEXICON/$file" "$APP/Contents/Resources/"; fi
done
bash scripts/build-rust.sh macos
swiftc -j "${MEOWPUUT_JOBS:-4}" $(bash scripts/build.sh --sources) scripts/GlassPreview.swift .build/rust/macosx/libmeowpuut_ffi.a \
  -target "$(uname -m)-apple-macosx27.0" -import-objc-header Sources/Bridge.h -lsqlite3 \
  -framework AppKit -framework SwiftUI -framework Carbon -framework Metal -framework QuartzCore \
  -o "$BIN"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>app.meowpuut.GlassPreview</string><key>CFBundleExecutable</key><string>GlassPreview</string><key>CFBundleName</key><string>GlassPreview</string><key>CFBundlePackageType</key><string>APPL</string><key>LSUIElement</key><false/></dict></plist>
PLIST
/usr/libexec/PlistBuddy -c "Add :CFBundleVersion string $(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' Resources/Info.plist)" "$APP/Contents/Info.plist"
if [ "${1:-}" = "--snapshot" ]; then
  [ -n "${2:-}" ] || { echo "usage: $0 --snapshot DIR [--festivals DIR] [--only NAME]" >&2; exit 2; }
  # Exits on its own: 0 when every sheet was written.
  exec "$BIN" "$@"
fi
[ "${NO_OPEN:-0}" = 1 ] || open "$APP"
