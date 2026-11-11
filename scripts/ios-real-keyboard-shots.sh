#!/bin/bash
# Pictures of 喵撲 as a real system keyboard (not the lab shell) on a simulator,
# next to Apple's Pinyin and Zhuyin keyboards in the same host.
#   bash scripts/ios-real-keyboard-shots.sh <simulator-udid> <out-dir> [plan.json]
# Builds and installs the app with its keyboard extension (simulator signing,
# so the App Group applies), enables the keyboards in the simulator's own
# preferences, and runs the UI test in iOS/tests/RealKeyboard, which opens a
# text field in its host app and taps keys. The default plan is taken in light
# and dark appearance; a plan file is taken once in the current appearance
# (format: iOS/tests/RealKeyboard/UITests/RealKeyboardShots.swift).
# SKIP_BUILD=1 reuses the last build. The simulator's appearance is put back.
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
udid="$1"; out="$(mkdir -p "$2" && cd "$2" && pwd)"; plan="${3:-}"
build="${REALKB_BUILD:-$PWD/.build/iOS-real}"
ext=com.krysta1ll.meowpuut.ios.keyboard

xcrun simctl bootstatus "$udid" -b >/dev/null 2>&1 || true
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  xcodebuild -project iOS/Meowpuut.xcodeproj -scheme Meowpuut -sdk iphonesimulator \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$build/app" -jobs 6 build -quiet
  xcodegen generate --quiet --spec iOS/tests/RealKeyboard/project.yml --project "$build"
fi
xcrun simctl install "$udid" "$build/app/Build/Products/Debug-iphonesimulator/Meowpuut.app"

# English, Apple's Pinyin and Zhuyin, and 喵撲. AppleKeyboardsExpanded makes iOS use the list.
xcrun simctl spawn "$udid" defaults write -g AppleKeyboards -array "en_US@sw=QWERTY;hw=Automatic" \
  "zh_Hans-Pinyin@sw=Pinyin-Simplified;hw=Automatic" "zh_Hant-Zhuyin@sw=Zhuyin;hw=Automatic" "$ext"
xcrun simctl spawn "$udid" defaults write -g AppleKeyboardsExpanded -int 1
xcrun simctl spawn "$udid" defaults write com.apple.keyboard.preferences DidShowContinuousPathIntroduction -bool true
settings="$(xcrun simctl get_app_container "$udid" com.krysta1ll.meowpuut.ios group.com.krysta1ll.meowpuut.shared)/KeyboardSettings.json"

default_plan() {  # $1: suffix
  local s="$1" p='"inputMode":"pinyin","candidateTone":"system","catGlassSkin":true'
  cat <<EOF
[
 {"name":"meow-idle-$s","keyboard":"$ext","settings":{$p,"glassShape":"capsule"}},
 {"name":"meow-nihao-$s","keyboard":"$ext","taps":["n","i","h","a","o"],"settings":{$p,"glassShape":"capsule"}},
 {"name":"meow-nihao-darkpage-$s","keyboard":"$ext","page":"dark","taps":["n","i","h","a","o"],"settings":{$p,"glassShape":"capsule"}},
 {"name":"meow-nihao-colour-$s","keyboard":"$ext","page":"colour","taps":["n","i","h","a","o"],"settings":{$p,"glassShape":"capsule"}},
 {"name":"meow-wave-$s","keyboard":"$ext","taps":["n","i","h","a","o"],"settings":{$p,"glassShape":"wave"}},
 {"name":"meow-cloud-$s","keyboard":"$ext","taps":["n","i","h","a","o"],"settings":{$p,"glassShape":"cloud"}},
 {"name":"meow-expanded-$s","keyboard":"$ext","taps":["s","h","i","展開候選詞"],"settings":{$p,"glassShape":"capsule"}},
 {"name":"meow-zhuyin-$s","keyboard":"$ext","taps":["ㄋ","ㄧ"],"settings":{"inputMode":"zhuyin","candidateTone":"system","catGlassSkin":true,"glassShape":"capsule"}},
 {"name":"meow-landscape-$s","keyboard":"$ext","landscape":true,"taps":["n","i","h","a","o"],"settings":{$p,"glassShape":"capsule"}},
 {"name":"apple-pinyin-idle-$s","keyboard":"zh_Hans-Pinyin"},
 {"name":"apple-pinyin-nihao-$s","keyboard":"zh_Hans-Pinyin","taps":["n","i","h","a","o"]},
 {"name":"apple-pinyin-darkpage-$s","keyboard":"zh_Hans-Pinyin","page":"dark","taps":["n","i","h","a","o"]},
 {"name":"apple-zhuyin-$s","keyboard":"zh_Hant-Zhuyin"},
 {"name":"apple-landscape-$s","keyboard":"zh_Hans-Pinyin","landscape":true}
]
EOF
}

run_plan() {  # $1: plan file
  # TEST_RUNNER_ variables reach the UI test's environment without the prefix.
  TEST_RUNNER_REALKB_PLAN="$1" TEST_RUNNER_REALKB_OUT="$out" TEST_RUNNER_REALKB_SETTINGS="$settings" \
  xcodebuild test -project "$build/RealKeyboard.xcodeproj" -scheme RealKeyboard \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$build/shots" -quiet \
    > "$out/xcodebuild.log" 2>&1 || { echo "UI test failed, see $out/xcodebuild.log" >&2; return 1; }
  cat "$out/shots-log.txt" >> "$out/log.txt"; echo >> "$out/log.txt"
}

: > "$out/log.txt"
if [ -n "$plan" ]; then
  run_plan "$(cd "$(dirname "$plan")" && pwd)/$(basename "$plan")"
else
  was="$(xcrun simctl ui "$udid" appearance)"
  for look in light dark; do
    xcrun simctl ui "$udid" appearance "$look"
    default_plan "$look" > "$out/plan-$look.json"
    run_plan "$out/plan-$look.json" || { xcrun simctl ui "$udid" appearance "$was"; exit 1; }
  done
  xcrun simctl ui "$udid" appearance "$was"
fi
ls "$out"/*.png
