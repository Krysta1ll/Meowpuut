#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
BREW_PREFIX="$(brew --prefix)"
APP="$PWD/.build/Meowpuut.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/Rime/opencc"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp -R Resources/*.lproj "$APP/Contents/Resources/"
for source in Vendor/*/*.yaml; do cp "$source" "$APP/Contents/Resources/Rime/"; done
cp Vendor/essay/essay.txt "$APP/Contents/Resources/Rime/"
cp Resources/default.custom.yaml "$APP/Contents/Resources/Rime/"
cp Resources/bopomofo.custom.yaml "$APP/Contents/Resources/Rime/"
cp -R "$BREW_PREFIX/share/opencc/"* "$APP/Contents/Resources/Rime/opencc/"
mkdir -p "$APP/Contents/Resources/Modules"
cp Modules/*.json "$APP/Contents/Resources/Modules/"
mkdir -p "$APP/Contents/Resources/Licenses"
for source in Vendor/*; do
  module="$(basename "$source")"
  cp "$source/LICENSE" "$APP/Contents/Resources/Licenses/$module-LICENSE"
done
cp "$BREW_PREFIX/opt/librime/LICENSE" "$APP/Contents/Resources/Licenses/librime-LICENSE"
cp "$BREW_PREFIX/opt/opencc/LICENSE" "$APP/Contents/Resources/Licenses/opencc-LICENSE"
swiftc -target "$(uname -m)-apple-macosx27.0" scripts/Icon.swift -o .build/icon
.build/icon .build/Meowpuut.iconset
iconutil -c icns .build/Meowpuut.iconset -o "$APP/Contents/Resources/Meowpuut.icns"
swiftc -target "$(uname -m)-apple-macosx27.0" scripts/Register.swift -o .build/register-input-source
clang++ -mmacosx-version-min=27.0 -std=c++17 -c Sources/Bridge.mm -I "$BREW_PREFIX/include" -o .build/Bridge.o -fobjc-arc
swiftc Sources/Engine.swift Sources/Interface.swift Sources/SystemInput.swift Sources/main.swift .build/Bridge.o \
  -target "$(uname -m)-apple-macosx27.0" \
  -import-objc-header Sources/Bridge.h -L "$BREW_PREFIX/lib" -lrime -lc++ \
  -framework AppKit -framework SwiftUI -framework InputMethodKit \
  -o "$APP/Contents/MacOS/Meowpuut"
codesign --force --sign - "$APP"
echo "已构建：$APP"
