#!/bin/bash
# Builds the macOS input method into .build/Meowpuut.app. It does not install it.
#
#   bash scripts/build.sh            build
#   bash scripts/build.sh --test     build, then run every macOS check (the app's --smoke)
#   bash scripts/build.sh --sources  print the Swift files shared with scripts/preview-glass.sh
set -euo pipefail
cd "$(dirname "$0")/.."
# Engine, appearance and candidate window. iOS/InterfaceText.swift is the
# Traditional/Simplified text helper both Apple clients use.
SOURCES="Sources/Engine.swift Sources/EngineKeyHandler.swift Sources/InputBackend.swift Sources/ChineseWithJapaneseBackend.swift Sources/JapaneseBackend.swift Sources/ChoiceMemory.swift Sources/Symbols.swift Sources/Brand.swift Sources/Appearance.swift Sources/CandidatePalette.swift Sources/GlassShapes.swift Sources/Festivals.swift iOS/InterfaceText.swift Sources/CatGlass.swift Sources/Interface.swift Sources/CandidateWindow.swift Sources/SystemInput.swift"
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
swiftc scripts/ConvertScript.swift -o .build/convert-script
python3 scripts/compile-native-lexicon.py "$APP/Contents/Resources" "$PWD/.build/convert-script"
mkdir -p "$APP/Contents/Resources/Licenses"
cp LICENSE "$APP/Contents/Resources/Licenses/Meowpuut-MIT.txt"
cp DataSources/mimo-tc/LICENSE.txt "$APP/Contents/Resources/Licenses/mimo-tc-MIT.txt"
cp DataSources/mimo-tc/OPENCC-LICENSE.txt "$APP/Contents/Resources/Licenses/OpenCC-Apache-2.0.txt"
cp DataSources/mimo-tc/JIEBA-LICENSE.txt "$APP/Contents/Resources/Licenses/jieba-MIT.txt"
cp DataSources/mimo-tc/SOURCE.json "$APP/Contents/Resources/Licenses/mimo-tc-SOURCE.json"
cp DataSources/mimo-sc/LICENSE "$APP/Contents/Resources/Licenses/mimo-sc-MIT.txt"
cp DataSources/mimo-sc/SOURCE.json "$APP/Contents/Resources/Licenses/mimo-sc-SOURCE.json"
cp DataSources/mimo-jp/LICENSE.txt "$APP/Contents/Resources/Licenses/mimo-jp-derived-BSD.txt"
cp DataSources/mimo-jp/BSD.txt "$APP/Contents/Resources/Licenses/mimo-jp-BSD.txt"
cp DataSources/mimo-jp/COPYING.txt "$APP/Contents/Resources/Licenses/mimo-jp-COPYING.txt"
cp DataSources/mimo-jp/SOURCE.json "$APP/Contents/Resources/Licenses/mimo-jp-SOURCE.json"
cp DataSources/cldr-emoji/LICENSE.txt "$APP/Contents/Resources/Licenses/CLDR-Unicode-3.0.txt"
cp DataSources/cldr-emoji/SOURCE.json "$APP/Contents/Resources/Licenses/cldr-emoji-SOURCE.json"
cp DataSources/mimo-jp/MOZC-LICENSE.txt "$APP/Contents/Resources/Licenses/mimo-jp-Mozc-BSD.txt"
cp DataSources/mimo-jp/UNICODE-LICENSE.txt "$APP/Contents/Resources/Licenses/mimo-jp-Unicode-3.0.txt"
cp DataSources/mimo-pairs/SOURCE.json "$APP/Contents/Resources/Licenses"/mimo-pairs-SOURCE.json
cp DataSources/mimo-pairs/Apache-2.0.txt "$APP/Contents/Resources/Licenses"/mimo-pairs-Apache-2.0.txt
cp DataSources/mimo-pairs/CC-BY-4.0.txt "$APP/Contents/Resources/Licenses"/mimo-pairs-CC-BY-4.0.txt
cp DataSources/mimo-names/SOURCE.json "$APP/Contents/Resources/Licenses"/mimo-names-SOURCE.json
cp DataSources/mimo-phrases/SOURCE.json "$APP/Contents/Resources/Licenses"/mimo-phrases-SOURCE.json
cp DataSources/mimo-pairs/CC-BY-2.0-FR.html "$APP/Contents/Resources/Licenses"/mimo-pairs-Tatoeba-CC-BY-2.0-FR.html
cp DataSources/mimo-pairs/CC0-1.0.txt "$APP/Contents/Resources/Licenses"/mimo-pairs-CommonVoice-CC0-1.0.txt
cp DataSources/mimo-latin/SOURCE.json "$APP/Contents/Resources/Licenses"/mimo-latin-SOURCE.json
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp -R Resources/*.lproj Resources/Lexicon Resources/Festivals "$APP/Contents/Resources/"
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
  python3 scripts/check-mimo-jp.py "$APP/Contents/Resources" --probe
  python3 scripts/check-chinese-golden.py --resources "$APP/Contents/Resources"
  python3 scripts/check-japanese-lexicon.py "$APP/Contents/Resources"
  python3 scripts/check-mixed-core.py
fi
