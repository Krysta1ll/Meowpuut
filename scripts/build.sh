#!/bin/bash
# Builds the macOS input method into .build/Meowpuut.app. It does not install it.
#
#   bash scripts/build.sh            build
#   bash scripts/build.sh --test     build, then run every macOS check (the app's --smoke)
#   bash scripts/build.sh --sources  print the Swift files shared with scripts/preview-glass.sh
set -euo pipefail
cd "$(dirname "$0")/.."
# Engine, appearance and candidate window. Sources/InterfaceText.swift is the
# Traditional/Simplified text helper both Apple clients use.
SOURCES="Sources/Engine.swift Sources/EngineKeyHandler.swift Sources/InputBackend.swift Sources/ChineseWithJapaneseBackend.swift Sources/JapaneseBackend.swift Sources/ChoiceMemory.swift Sources/Symbols.swift Sources/Brand.swift Sources/Appearance.swift Sources/CandidatePalette.swift Sources/GlassShapes.swift Sources/CatSprites.swift Sources/CatSpriteData.swift Sources/Festivals.swift Sources/FestivalTrim.swift Sources/InterfaceText.swift Sources/CatFigures.swift Sources/CatGlass.swift Sources/Interface.swift Sources/CandidateWindow.swift Sources/SystemInput.swift"
if [ "${1:-}" = "--sources" ]; then
  echo "$SOURCES"
  exit 0
fi
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
APP="$PWD/.build/Meowpuut.app"
TARGET="$(uname -m)-apple-macosx27.0"
JOBS="${MEOWPUUT_JOBS:-4}"
# Recreate only generated resources so no retired dictionary survives an incremental build.
rm -rf "$APP/Contents/Resources"
# A failed compile must not leave an older binary behind.
rm -f "$APP/Contents/MacOS/Meowpuut"
mkdir -p .build "$APP/Contents/MacOS" "$APP/Contents/Resources/Modules"
bash Engine/scripts/build-lexicon.sh "$APP/Contents/Resources"
cp ASSETS-LICENSE.md "$APP/Contents/Resources/Licenses/Meowpuut-Artwork.txt"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp -R Resources/*.lproj Engine/Resources/Lexicon Resources/Festivals "$APP/Contents/Resources/"
# 米米莫 and 莫莫 (scripts/render_cats.py); NSImage pairs name.png with name@2x.png.
cp Resources/Cats/*.png "$APP/Contents/Resources/"
cp Modules/*.json "$APP/Contents/Resources/Modules/"
swiftc -target "$TARGET" scripts/Icon.swift -o .build/icon
cp assets/brand/MeowpuutMark.png "$APP/Contents/Resources/"
.build/icon assets/brand/MeowpuutMark.png .build/Meowpuut.iconset
iconutil -c icns .build/Meowpuut.iconset -o "$APP/Contents/Resources/Meowpuut.icns"
cp .build/MeowpuutCatTemplate.tiff "$APP/Contents/Resources/"
swiftc -target "$TARGET" scripts/Register.swift -o .build/register-input-source
bash scripts/build-rust.sh macos
# The checks ship in the app: Sources/Smoke*.swift and Tests/ run under --smoke.
swiftc -j "$JOBS" $SOURCES Sources/Smoke.swift Sources/SmokePinyin.swift Sources/SmokeMixed.swift Sources/SmokeLayout.swift Tests/CandidateLayoutTests.swift Sources/main.swift .build/rust/macosx/libmeowpuut_ffi.a \
  -target "$TARGET" -import-objc-header Sources/Bridge.h -lsqlite3 \
  -framework AppKit -framework SwiftUI -framework InputMethodKit \
  -o "$APP/Contents/MacOS/Meowpuut"
codesign --force --sign - "$APP"
echo "已構建：$APP"
if [ "${1:-}" = "--test" ]; then
  # Uses its own settings and a temporary folder; the installed input method is not touched.
  # MEOWPUUT_GOLDEN_CHECK=1 also compares the candidate lists with Tests/golden.
  MEOWPUUT_GOLDEN_CHECK=1 "$APP/Contents/MacOS/Meowpuut" --smoke
  # Lexicon checks on the bundled files: Japanese image and gate, Chinese goldens, old Japanese
  # outputs, mixed sentences in the core.
  python3 Engine/scripts/check-mimo-jp.py "$APP/Contents/Resources" --probe
  python3 Engine/scripts/check-chinese-golden.py --resources "$APP/Contents/Resources"
  python3 Engine/scripts/check-japanese-lexicon.py "$APP/Contents/Resources"
  python3 Engine/scripts/check-mixed-core.py "$APP/Contents/Resources"
fi
