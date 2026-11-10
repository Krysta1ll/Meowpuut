#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build iOS/Generated
swiftc scripts/ConvertScript.swift -o .build/convert-script
python3 scripts/compile-native-lexicon.py iOS/Generated "$PWD/.build/convert-script"
rm -rf iOS/Generated/Licenses
mkdir -p iOS/Generated/Licenses
cp LICENSE iOS/Generated/Licenses/Meowpuut-MIT.txt
cp DataSources/mimo-tc/LICENSE.txt iOS/Generated/Licenses/mimo-tc-MIT.txt
cp DataSources/mimo-tc/OPENCC-LICENSE.txt iOS/Generated/Licenses/OpenCC-Apache-2.0.txt
cp DataSources/mimo-tc/JIEBA-LICENSE.txt iOS/Generated/Licenses/jieba-MIT.txt
cp DataSources/mimo-tc/SOURCE.json iOS/Generated/Licenses/mimo-tc-SOURCE.json
cp DataSources/mimo-sc/LICENSE iOS/Generated/Licenses/mimo-sc-MIT.txt
cp DataSources/mimo-sc/RIME-ICE-LICENSE.txt iOS/Generated/Licenses/rime-ice-GPL-3.0.txt
cp DataSources/mimo-sc/SOURCE.json iOS/Generated/Licenses/mimo-sc-SOURCE.json
cp DataSources/mimo-jp/LICENSE.txt iOS/Generated/Licenses/mimo-jp-derived-BSD.txt
cp DataSources/mimo-jp/BSD.txt iOS/Generated/Licenses/mimo-jp-BSD.txt
cp DataSources/mimo-jp/COPYING.txt iOS/Generated/Licenses/mimo-jp-COPYING.txt
cp DataSources/mimo-jp/SOURCE.json iOS/Generated/Licenses/mimo-jp-SOURCE.json
APP_ICON=iOS/Generated/Assets.xcassets/AppIcon.appiconset
swiftc -target "$(uname -m)-apple-macosx27.0" scripts/Icon.swift -o .build/icon
.build/icon assets/brand/MeowpuutMark.png "$APP_ICON" --ios
printf '%s\n' '{"images":[{"filename":"AppIcon.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"xcode","version":1}}' > "$APP_ICON/Contents.json"
xcodegen generate --spec iOS/project.yml
