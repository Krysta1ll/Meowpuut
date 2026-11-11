#!/bin/bash
# Screenshots of the keyboard in the lab app (iOS/Lab) on a simulator.
#   bash scripts/ios-keyboard-shots.sh <simulator-udid> <out-dir> [name:args ...]
# Each name:args pair launches the lab with those arguments and saves <name>.png.
# With no pairs, the standard set is taken: light and dark, the three shapes,
# nihao, sushi, a long mixed candidate, Zhuyin, Japanese, both symbol pages,
# the expanded panel, a key balloon, a long-press row and landscape
# (`-landscape YES` starts the lab in landscape).
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
udid="$1"; out="$2"; shift 2
mkdir -p "$out"
xcrun simctl boot "$udid" 2>/dev/null || true
# A simulator can report a failed data migration and still run the lab.
xcrun simctl bootstatus "$udid" -b >/dev/null 2>&1 || true
xcrun simctl status_bar "$udid" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3 >/dev/null 2>&1 || true
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  xcodebuild -project iOS/Meowpuut.xcodeproj -scheme MeowpuutKeyboardLab -sdk iphonesimulator \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath .build/iOS-lab -jobs 4 CODE_SIGNING_ALLOWED=NO build -quiet
fi
xcrun simctl install "$udid" .build/iOS-lab/Build/Products/Debug-iphonesimulator/MeowpuutKeyboardLab.app
if [ "$#" -eq 0 ]; then
  long=woxiangshiberusushiheramen
  set -- \
    "nihao:-inputMode pinyin -type nihao" \
    "nihao-dark:-inputMode pinyin -type nihao -candidateTone dark" \
    "wave:-inputMode pinyin -type nihao -glassShape wave" \
    "wave-dark:-inputMode pinyin -type nihao -glassShape wave -candidateTone dark" \
    "cloud:-inputMode pinyin -type nihao -glassShape cloud" \
    "cloud-dark:-inputMode pinyin -type nihao -glassShape cloud -candidateTone dark" \
    "sushi:-inputMode pinyin -type sushi" \
    "sushi-dark:-inputMode pinyin -type sushi -candidateTone dark" \
    "long:-inputMode pinyin -type $long" \
    "long-dark:-inputMode pinyin -type $long -candidateTone dark" \
    "idle:-inputMode pinyin" \
    "zhuyin:-inputMode zhuyin -type su3cl3" \
    "zhuyin-dark:-inputMode zhuyin -type su3cl3 -candidateTone dark" \
    "japanese:-inputMode japanese -type sushi" \
    "english:-inputMode pinyin -english YES -document Hello|" \
    "symbols:-inputMode pinyin -page 1" \
    "symbols-more:-inputMode pinyin -page 2 -candidateTone dark" \
    "expanded:-inputMode pinyin -type shi -expand YES" \
    "expanded-dark:-inputMode pinyin -type sushi -expand YES -candidateTone dark" \
    "balloon:-inputMode pinyin -press q" \
    "balloon-edge:-inputMode pinyin -press p -candidateTone dark" \
    "alternates:-inputMode pinyin -english YES -press e -alternates YES" \
    "plain:-inputMode pinyin -type nihao -plain YES" \
    "landscape:-inputMode pinyin -type nihao -landscape YES" \
    "landscape-dark:-inputMode zhuyin -type su3cl3 -landscape YES -candidateTone dark"
fi
for spec in "$@"; do
  name="${spec%%:*}"; args="${spec#*:}"
  # shellcheck disable=SC2086
  xcrun simctl launch --terminate-running-process "$udid" com.krysta1ll.meowpuut.ios.lab $args >/dev/null
  sleep "${SHOT_DELAY:-3}"
  # A folding phone has two displays: SHOT_DISPLAY=1 is the cover, 3 the inner one.
  xcrun simctl io "$udid" screenshot ${SHOT_DISPLAY:+--display="$SHOT_DISPLAY"} "$out/$name.png" >/dev/null 2>&1 \
    || { echo "screenshot failed: $name" >&2; exit 1; }
  echo "$out/$name.png"
done
# A landscape shot leaves the simulator turned; one portrait launch puts it back.
xcrun simctl launch --terminate-running-process "$udid" com.krysta1ll.meowpuut.ios.lab >/dev/null 2>&1 || true
sleep 1
xcrun simctl terminate "$udid" com.krysta1ll.meowpuut.ios.lab >/dev/null 2>&1 || true
