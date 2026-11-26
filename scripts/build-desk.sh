#!/bin/bash
# Builds 喵撲貓窩 (Meowpuut Desk), the macOS desk-pet process (universal: arm64 + x86_64, macOS 15 and later), into
# ".build/desk/Meowpuut Desk.app". It does not install it and does not register the login item.
#
#   bash scripts/build-desk.sh            build
#   bash scripts/build-desk.sh --test     build, then run the headless checks (the app's --smoke)
#
# Run it by hand:   ".build/desk/Meowpuut Desk.app/Contents/MacOS/Meowpuut Desk" [--settings-dir DIR] [--run-seconds N --shots DIR]
# Login item:       "…/Meowpuut Desk" --register   (SMAppService; the input method's pulses need it: launchd owns the Mach service)
set -euo pipefail
cd "$(dirname "$0")/.."
# The Desk's own sources, and the input method's files it shares: the sprite table (render_cats.py), the interface
# text helper, the settings file reader and the pulse channel.
SOURCES="$(ls Desk/Sources/*.swift | tr '\n' ' ') Sources/CatSpriteData.swift Sources/InterfaceText.swift Sources/SettingsStore.swift Sources/DeskPulse.swift"
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
NAME="Meowpuut Desk"
APP="$PWD/.build/desk/$NAME.app"
MIN_MACOS="15.0"
TARGETS="arm64-apple-macosx$MIN_MACOS x86_64-apple-macosx$MIN_MACOS"
TARGET="$(uname -m)-apple-macosx$MIN_MACOS"
JOBS="${MEOWPUUT_JOBS:-4}"
rm -rf "$APP/Contents/Resources" "$APP/Contents/Library"
rm -f "$APP/Contents/MacOS/$NAME"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/Licenses" "$APP/Contents/Library/LaunchAgents"
cp Desk/Info.plist "$APP/Contents/Info.plist"
# Same version as the input method: they ship and are signed together.
for key in CFBundleShortVersionString CFBundleVersion; do
  /usr/libexec/PlistBuddy -c "Set :$key $(/usr/libexec/PlistBuddy -c "Print :$key" Resources/Info.plist)" "$APP/Contents/Info.plist"
done
cp Desk/LaunchAgents/app.meowpuut.desk.plist "$APP/Contents/Library/LaunchAgents/"
# 米米莫 and 莫莫 as the owner's pack has them (scripts/render_cats.py): the edge poses the input method ships, and the
# whole cats from the same import. NSImage and DeskArt pair name.png with name@2x.png.
cp Resources/Cats/*.png "$APP/Contents/Resources/"
# The desk poses' geometry (walk_1…4, turn, look_up, lift, fall, momo_sleep: size, anchor, bounds; render_cats.py --desk).
cp Resources/Cats/desk.json "$APP/Contents/Resources/desk.json"
for name in mimimo_idle mimimo_press mimimo_happy mimimo_sleep momo_idle momo_wave momo_happy momo_jump; do
  cp "assets/brand/cats/sprites/${name}@1x.png" "$APP/Contents/Resources/$name.png"
  cp "assets/brand/cats/sprites/${name}@2x.png" "$APP/Contents/Resources/$name@2x.png"
done
cp ASSETS-LICENSE.md "$APP/Contents/Resources/Licenses/Meowpuut-Artwork.txt"
# The app icon and the menu bar cat door (the input menu's cat), drawn by the same script as the input method's.
mkdir -p .build/desk
swiftc -target "$TARGET" scripts/Icon.swift -o .build/desk/icon
.build/desk/icon assets/brand/MeowpuutMark.png .build/desk/MeowpuutDesk.iconset
iconutil -c icns .build/desk/MeowpuutDesk.iconset -o "$APP/Contents/Resources/MeowpuutDesk.icns"
cp .build/desk/MeowpuutCatTemplate.tiff "$APP/Contents/Resources/"
bash scripts/build-rust.sh macos
mkdir -p .build/desk/slices
SLICES=()
for T in $TARGETS; do
  slice=".build/desk/slices/desk-${T%%-*}"
  swiftc -j "$JOBS" $SOURCES .build/rust/macosx/libmeowpuut_ffi.a \
    -target "$T" -import-objc-header Sources/Bridge.h \
    -framework AppKit -framework QuartzCore -framework ServiceManagement -framework Security -framework Carbon \
    -o "$slice"
  SLICES+=("$slice")
done
lipo -create "${SLICES[@]}" -output "$APP/Contents/MacOS/$NAME"
# Ad-hoc signature for local use (code identifier app.meowpuut.desk). scripts/notarize-macos.sh re-signs the input
# method with Developer ID; the Desk must be signed by the same team (Sources/DeskPulse.swift requires it).
codesign --force --sign - --identifier app.meowpuut.desk "$APP"
echo "已構建：$APP"
if [ "${1:-}" = "--test" ]; then
  "$APP/Contents/MacOS/$NAME" --smoke
fi
