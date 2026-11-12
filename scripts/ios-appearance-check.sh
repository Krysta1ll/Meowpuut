#!/bin/bash
# Checks that the real keyboard follows the system's light and dark while it
# is up and when it comes back after the system changed while it was away.
#   bash scripts/ios-appearance-check.sh <simulator-udid> <out-dir>
# Needs the app and the RealKeyboard host installed by
# scripts/ios-real-keyboard-shots.sh. Opens the keyboard in dark, turns the
# system light, then leaves for Settings, turns dark and light again and
# comes back; each time it measures the q key and says which way it looks.
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"
u="$1"; out="$(mkdir -p "$2" && cd "$2" && pwd)"
host=local.meowpuut.realkeyboard.host
settings="$(xcrun simctl get_app_container "$u" com.krysta1ll.meowpuut.ios group.com.krysta1ll.meowpuut.shared)/KeyboardSettings.json"
printf '%s' '{"inputMode":"pinyin","candidateTone":"system","catGlassSkin":true,"glassShape":"cloud","candidatePalette":"ocean"}' > "$settings"
export SIMCTL_CHILD_REALKB_KEYBOARD=com.krysta1ll.meowpuut.ios.keyboard SIMCTL_CHILD_REALKB_PAGE=light
was="$(xcrun simctl ui "$u" appearance)"
shot() { sleep 2.5; xcrun simctl io "$u" screenshot "$out/$1.png" >/dev/null 2>&1; }
xcrun simctl ui "$u" appearance dark
xcrun simctl launch --terminate-running-process "$u" "$host" >/dev/null; shot 1-dark
xcrun simctl ui "$u" appearance light; shot 2-light
xcrun simctl launch "$u" com.apple.Preferences >/dev/null; sleep 1.5
xcrun simctl ui "$u" appearance dark
xcrun simctl launch "$u" "$host" >/dev/null; shot 3-dark
xcrun simctl launch "$u" com.apple.Preferences >/dev/null; sleep 1.5
xcrun simctl ui "$u" appearance light
xcrun simctl launch "$u" "$host" >/dev/null; shot 4-light
xcrun simctl terminate "$u" "$host" >/dev/null 2>&1 || true
xcrun simctl ui "$u" appearance "$was"
python3 - "$out" <<'EOF'
import sys, glob, os
from PIL import Image
bad = 0
for path in sorted(glob.glob(os.path.join(sys.argv[1], "[0-9]-*.png"))):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    # Inside the q key on a portrait iPhone. A dark keyboard left over a
    # light system measures about 180, a light one about 230.
    r, g, b = im.getpixel((int(w * 0.035), int(h * 0.715)))
    looks = "light" if (r + g + b) / 3 > 200 else "dark"
    want = path.rsplit("-", 1)[1][:-4]
    bad += looks != want
    print(f"{os.path.basename(path)}: q key {(r, g, b)} looks {looks}" + ("" if looks == want else f", should be {want}"))
sys.exit(1 if bad else 0)
EOF
