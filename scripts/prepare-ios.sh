#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build iOS/Generated
python3 scripts/compile-native-lexicon.py iOS/Generated
mkdir -p iOS/Generated/Licenses
cp LICENSE iOS/Generated/Licenses/Meowpuut-MIT.txt
cp DataSources/McBopomofo/LICENSE.txt iOS/Generated/Licenses/McBopomofo-MIT.txt
cp DataSources/McBopomofo/SOURCE.json iOS/Generated/Licenses/McBopomofo-SOURCE.json
cp DataSources/PinyinPro/LICENSE iOS/Generated/Licenses/PinyinPro-MIT.txt
cp DataSources/PinyinPro/SOURCE.json iOS/Generated/Licenses/PinyinPro-SOURCE.json
cp DataSources/UniDic/LICENSE.txt iOS/Generated/Licenses/UniDic-derived-BSD.txt
cp DataSources/UniDic/UNIDIC-BSD.txt iOS/Generated/Licenses/UniDic-BSD.txt
cp DataSources/UniDic/UNIDIC-COPYING.txt iOS/Generated/Licenses/UniDic-COPYING.txt
cp DataSources/UniDic/SOURCE.json iOS/Generated/Licenses/UniDic-SOURCE.json
cp DataSources/MimoNormalization/ICU-LICENSE.txt iOS/Generated/Licenses/ICU-LICENSE.txt
cp DataSources/MimoNormalization/ICU-Hans_Hant.txt iOS/Generated/Licenses/ICU-Hans_Hant.txt
cp DataSources/MimoNormalization/SOURCE.json iOS/Generated/Licenses/MimoNormalization-SOURCE.json
APP_ICON=iOS/Generated/Assets.xcassets/AppIcon.appiconset
swiftc -target "$(uname -m)-apple-macosx27.0" scripts/Icon.swift -o .build/icon
.build/icon assets/brand/MeowpuutMark.png "$APP_ICON" --ios
printf '%s\n' '{"images":[{"filename":"AppIcon.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"xcode","version":1}}' > "$APP_ICON/Contents.json"
xcodegen generate --spec iOS/project.yml
