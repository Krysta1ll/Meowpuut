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
cp DataSources/McBopomofo/LICENSE.txt "$APP/Contents/Resources/Licenses/McBopomofo-MIT.txt"
cp DataSources/McBopomofo/SOURCE.json "$APP/Contents/Resources/Licenses/McBopomofo-SOURCE.json"
cp DataSources/PinyinPro/LICENSE "$APP/Contents/Resources/Licenses/PinyinPro-MIT.txt"
cp DataSources/PinyinPro/SOURCE.json "$APP/Contents/Resources/Licenses/PinyinPro-SOURCE.json"
cp DataSources/UniDic/LICENSE.txt "$APP/Contents/Resources/Licenses/UniDic-derived-BSD.txt"
cp DataSources/UniDic/UNIDIC-BSD.txt "$APP/Contents/Resources/Licenses/UniDic-BSD.txt"
cp DataSources/UniDic/UNIDIC-COPYING.txt "$APP/Contents/Resources/Licenses/UniDic-COPYING.txt"
cp DataSources/UniDic/SOURCE.json "$APP/Contents/Resources/Licenses/UniDic-SOURCE.json"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp -R Resources/*.lproj Resources/Lexicon "$APP/Contents/Resources/"
cp Modules/*.json "$APP/Contents/Resources/Modules/"
swiftc -target "$TARGET" scripts/Icon.swift -o .build/icon
cp assets/brand/MeowpuutMark.png "$APP/Contents/Resources/"
.build/icon assets/brand/MeowpuutMark.png .build/Meowpuut.iconset
iconutil -c icns .build/Meowpuut.iconset -o "$APP/Contents/Resources/Meowpuut.icns"
cp .build/MeowpuutCatTemplate.tiff "$APP/Contents/Resources/"
swiftc -target "$TARGET" scripts/Register.swift -o .build/register-input-source
clang++ -target "$TARGET" -std=c++17 -O2 -c Core/MeowpuutCore.cpp -o .build/MeowpuutCore.o
swiftc Sources/Engine.swift Sources/InputBackend.swift Sources/JapaneseBackend.swift Sources/ChoiceMemory.swift Sources/Brand.swift Sources/CandidatePalette.swift Sources/CatGlass.swift Sources/Interface.swift Sources/SystemInput.swift Sources/Smoke.swift Sources/main.swift .build/MeowpuutCore.o \
  -target "$TARGET" -import-objc-header Sources/Bridge.h -lc++ -lsqlite3 \
  -framework AppKit -framework SwiftUI -framework InputMethodKit \
  -o "$APP/Contents/MacOS/Meowpuut"
codesign --force --sign - "$APP"
echo "已構建：$APP"
