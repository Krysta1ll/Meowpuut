#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
APP="$PWD/.build/Meowpuut.app"
TARGET="$(uname -m)-apple-macosx27.0"
# Recreate only generated resources so no retired dictionary survives an incremental build.
rm -rf "$APP/Contents/Resources"
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
cp DataSources/mimo-sc/RIME-ICE-LICENSE.txt "$APP/Contents/Resources/Licenses/rime-ice-GPL-3.0.txt"
cp DataSources/mimo-sc/SOURCE.json "$APP/Contents/Resources/Licenses/mimo-sc-SOURCE.json"
cp DataSources/mimo-jp/LICENSE.txt "$APP/Contents/Resources/Licenses/mimo-jp-derived-BSD.txt"
cp DataSources/mimo-jp/BSD.txt "$APP/Contents/Resources/Licenses/mimo-jp-BSD.txt"
cp DataSources/mimo-jp/COPYING.txt "$APP/Contents/Resources/Licenses/mimo-jp-COPYING.txt"
cp DataSources/mimo-jp/SOURCE.json "$APP/Contents/Resources/Licenses/mimo-jp-SOURCE.json"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp -R Resources/*.lproj Resources/Lexicon "$APP/Contents/Resources/"
cp Modules/*.json "$APP/Contents/Resources/Modules/"
swiftc -target "$TARGET" scripts/Icon.swift -o .build/icon
cp assets/brand/MeowpuutMark.png "$APP/Contents/Resources/"
.build/icon assets/brand/MeowpuutMark.png .build/Meowpuut.iconset
iconutil -c icns .build/Meowpuut.iconset -o "$APP/Contents/Resources/Meowpuut.icns"
cp .build/MeowpuutCatTemplate.tiff "$APP/Contents/Resources/"
swiftc -target "$TARGET" scripts/Register.swift -o .build/register-input-source
bash scripts/build-rust.sh macos
swiftc Sources/Engine.swift Sources/EngineKeyHandler.swift Sources/InputBackend.swift Sources/ChineseWithJapaneseBackend.swift Sources/JapaneseBackend.swift Sources/ChoiceMemory.swift Sources/Brand.swift Sources/CandidatePalette.swift Sources/CatGlass.swift Sources/Interface.swift Sources/CandidateWindow.swift Sources/SystemInput.swift Sources/Smoke.swift Sources/SmokePinyin.swift Sources/SmokeMixed.swift Sources/SmokeLayout.swift Sources/main.swift .build/rust/macosx/libmeowpuut_ffi.a \
  -target "$TARGET" -import-objc-header Sources/Bridge.h -lsqlite3 \
  -framework AppKit -framework SwiftUI -framework InputMethodKit \
  -o "$APP/Contents/MacOS/Meowpuut"
codesign --force --sign - "$APP"
echo "已構建：$APP"
