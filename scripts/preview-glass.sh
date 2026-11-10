#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
APP="$PWD/.build/GlassPreview.app"
mkdir -p "$APP/Contents/MacOS"
bash scripts/build-rust.sh macos
swiftc Sources/Engine.swift Sources/EngineKeyHandler.swift Sources/InputBackend.swift Sources/ChineseWithJapaneseBackend.swift Sources/JapaneseBackend.swift Sources/ChoiceMemory.swift Sources/Brand.swift Sources/CandidatePalette.swift Sources/CatGlass.swift Sources/Interface.swift Sources/SystemInput.swift scripts/GlassPreview.swift .build/rust/macosx/libmeowpuut_ffi.a -target "$(uname -m)-apple-macosx27.0" -import-objc-header Sources/Bridge.h -lsqlite3 -framework AppKit -framework SwiftUI -framework Carbon -o "$APP/Contents/MacOS/GlassPreview"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>app.meowpuut.GlassPreview</string><key>CFBundleExecutable</key><string>GlassPreview</string><key>CFBundleName</key><string>GlassPreview</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>
PLIST
open "$APP"
