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
SOURCES="Sources/Engine.swift Sources/FirstRunDefaults.swift Sources/EngineKeyHandler.swift Sources/InputBackend.swift Sources/ChineseWithJapaneseBackend.swift Sources/JapaneseBackend.swift Sources/ChoiceMemory.swift Sources/NeverVolunteer.swift Sources/Symbols.swift Sources/Brand.swift Sources/Appearance.swift Sources/CandidatePalette.swift Sources/GlassShapes.swift Sources/CatSprites.swift Sources/CatSpriteData.swift Sources/Festivals.swift Sources/FestivalTrim.swift Sources/InterfaceText.swift Sources/CatFigures.swift Sources/CatGlass.swift Sources/PaperTokens.swift Sources/PaperStudio.swift Sources/Interface.swift Sources/CandidateWindow.swift Sources/SystemInput.swift Sources/KeySounds.swift"
if [ "${1:-}" = "--sources" ]; then
  echo "$SOURCES"
  exit 0
fi
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
APP="$PWD/.build/Meowpuut.app"
TARGET="$(uname -m)-apple-macosx27.0"
JOBS="${MEOWPUUT_JOBS:-4}"
LEXICON="${MEOWPUUT_LEXICON_DIR:-$PWD/.build/macos-lexicon}"
# Recreate only generated resources so no retired dictionary survives an incremental build.
rm -rf "$APP/Contents/Resources"
# A failed compile must not leave an older binary behind.
rm -f "$APP/Contents/MacOS/Meowpuut"
mkdir -p .build "$APP/Contents/MacOS" "$APP/Contents/Resources/Modules"
if [ -z "${MEOWPUUT_LEXICON_DIR:-}" ]; then
  bash Engine/scripts/build-lexicon.sh "$LEXICON"
fi
for file in mimo_base.bin mimo_jp.bin mimo_pairs.bin mimo_trigrams.bin mimo_assoc.bin associations.json language-evidence.sqlite; do
  cp "$LEXICON/$file" "$APP/Contents/Resources/"
done
# The list of words the keyboard never offers by itself (Sources/NeverVolunteer.swift), as hashes: the lexicon
# build writes it (Engine/scripts/build-lexicon.sh); an older lexicon folder without it gets it written here.
if [ -f "$LEXICON/never-volunteer.txt" ]; then
  cp "$LEXICON/never-volunteer.txt" "$APP/Contents/Resources/never-volunteer.txt"
else
  python3 Engine/scripts/blocked_terms.py --client "$APP/Contents/Resources/never-volunteer.txt"
fi
python3 Engine/scripts/copy-lexicon-notices.py "$APP/Contents/Resources/Licenses"
cp ASSETS-LICENSE.md "$APP/Contents/Resources/Licenses/Meowpuut-Artwork.txt"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp -R Resources/*.lproj Resources/Festivals "$APP/Contents/Resources/"
# 米米莫 and 莫莫 (scripts/render_cats.py); NSImage pairs name.png with name@2x.png.
cp Resources/Cats/*.png "$APP/Contents/Resources/"
cp Modules/*.json "$APP/Contents/Resources/Modules/"
# Typing sounds (docs/SOUNDS.md): the same WAV files as iOS and Android.
for set in assets/key-sounds/*/; do
  mkdir -p "$APP/Contents/Resources/key-sounds/$(basename "$set")"
  cp "$set"*.wav "$APP/Contents/Resources/key-sounds/$(basename "$set")/"
done
swiftc -target "$TARGET" scripts/Icon.swift -o .build/icon
cp assets/brand/MeowpuutMark.png "$APP/Contents/Resources/"
cp assets/brand/paper/grain.png "$APP/Contents/Resources/paper_grain.png"
# App-page fonts and their licences (scripts/subset_app_fonts.py); PaperFont registers them in the Studio only.
mkdir -p "$APP/Contents/Resources/Fonts"
cp Resources/Fonts/* "$APP/Contents/Resources/Fonts/"
.build/icon assets/brand/MeowpuutMark.png .build/Meowpuut.iconset
iconutil -c icns .build/Meowpuut.iconset -o "$APP/Contents/Resources/Meowpuut.icns"
cp .build/MeowpuutCatTemplate.tiff "$APP/Contents/Resources/"
swiftc -target "$TARGET" scripts/Register.swift -o .build/register-input-source
bash scripts/build-rust.sh macos
# The checks ship in the app: Sources/Smoke*.swift and Tests/ run under --smoke.
SWIFT_OPT="${MEOWPUUT_SWIFT_OPT:--O -whole-module-optimization}"
swiftc $SWIFT_OPT -j "$JOBS" $SOURCES Sources/Smoke.swift Sources/SmokePinyin.swift Sources/SmokeMixed.swift Sources/SmokeLayout.swift Tests/CandidateLayoutTests.swift Sources/main.swift .build/rust/macosx/libmeowpuut_ffi.a \
  -target "$TARGET" -import-objc-header Sources/Bridge.h -lsqlite3 \
  -framework AppKit -framework SwiftUI -framework InputMethodKit \
  -o "$APP/Contents/MacOS/Meowpuut"
codesign --force --sign - "$APP"
echo "已構建：$APP"
if [ "${1:-}" = "--test" ]; then
  # Uses its own settings and a temporary folder; the installed input method is not touched.
  # MEOWPUUT_GOLDEN_CHECK=1 also compares the candidate lists with Tests/golden.
  MEOWPUUT_LEXICON_DIR="$LEXICON" MEOWPUUT_GOLDEN_CHECK=1 "$APP/Contents/MacOS/Meowpuut" --smoke
  # Lexicon checks on the bundled files: Japanese image and gate, Chinese goldens, old Japanese
  # outputs, mixed sentences in the core.
  python3 Engine/scripts/check-mimo-jp.py "$LEXICON" --probe
  python3 Engine/scripts/check-chinese-golden.py --resources "$LEXICON"
  python3 Engine/scripts/check-japanese-lexicon.py "$LEXICON" "$APP/Contents/Resources/Licenses"
  python3 Engine/scripts/check-mixed-core.py "$LEXICON"
fi
