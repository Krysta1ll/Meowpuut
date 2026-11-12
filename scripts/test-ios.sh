#!/bin/bash
# Runs the iOS tests.
#
#   bash scripts/test-ios.sh                      unit tests and the photo check (macOS, no simulator)
#   bash scripts/test-ios.sh --simulator <udid>   also types on the real keyboard in the lab app
#   bash scripts/test-ios.sh --only <name>        one unit test, e.g. --only KeyboardGeometry
#
# The unit tests are standalone programs in iOS/tests: each is compiled with
# the sources it covers and run on this Mac. The typing checks build the lab
# app (iOS/Lab), launch it on the simulator with a touch script for each case
# and compare what ends up in the text field.
set -euo pipefail
cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${MEOWPUUT_XCODE_DIR:-/Applications/Xcode.app/Contents/Developer}"

simulator=""
only=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --simulator) simulator="${2:?--simulator needs a device id}"; shift 2 ;;
    --only) only="${2:?--only needs a test name}"; shift 2 ;;
    *) echo "usage: bash scripts/test-ios.sh [--simulator <udid>] [--only <name>]" >&2; exit 2 ;;
  esac
done

out=.build/ios-tests
mkdir -p "$out"
failed=0
ran=0

# name: sources compiled with iOS/tests/<name>Tests.swift
tests=(
  "CapitalizationState: iOS/Keyboard/CapitalizationState.swift"
  "CatAffection: iOS/Keyboard/CatAffection.swift"
  "CompositionDelivery: iOS/Keyboard/CompositionDelivery.swift"
  "InterfaceText: iOS/InterfaceText.swift"
  "KeyboardSymbols: iOS/Keyboard/KeyboardSymbols.swift"
  "SharedKeyboardSettings: iOS/SharedKeyboardSettings.swift"
  "RepeatDelete: iOS/Keyboard/KeyboardDrivers.swift"
  "SpaceCursor: iOS/Keyboard/KeyboardDrivers.swift"
  "KeyboardGeometry: iOS/Keyboard/KeyboardGeometry.swift Sources/Appearance.swift Sources/CatSprites.swift Sources/CatSpriteData.swift iOS/tests/Support/BarFrame.swift"
  "KeyboardKeys: iOS/tests/Support/InputMode.swift iOS/InterfaceText.swift iOS/Keyboard/KeyboardGeometry.swift Sources/Appearance.swift Sources/CatSprites.swift Sources/CatSpriteData.swift iOS/Keyboard/KeyboardKeys.swift"
  "CandidateStripLayout: iOS/Keyboard/CandidateStripLayout.swift"
  "Festivals: Sources/Festivals.swift"
  "CatArt: iOS/Keyboard/KeyboardGeometry.swift Sources/Appearance.swift Sources/CatSprites.swift Sources/CatSpriteData.swift"
  "CatSchedule: iOS/Keyboard/KeyboardGeometry.swift Sources/Appearance.swift Sources/CatSprites.swift Sources/CatSpriteData.swift"
)

for entry in "${tests[@]}"; do
  name="${entry%%:*}"
  sources="${entry#*: }"
  if [ -n "$only" ] && [ "$only" != "$name" ]; then continue; fi
  ran=$((ran + 1))
  # shellcheck disable=SC2086
  if ! swiftc -O -parse-as-library $sources "iOS/tests/${name}Tests.swift" -o "$out/${name}Tests" 2> "$out/${name}Tests.log"; then
    echo "FAIL: ${name}Tests does not compile"
    sed -n '1,20p' "$out/${name}Tests.log"
    failed=$((failed + 1))
    continue
  fi
  if ! "$out/${name}Tests"; then
    echo "FAIL: ${name}Tests"
    failed=$((failed + 1))
  fi
done

# Every test file is in the table above.
for file in iOS/tests/*Tests.swift; do
  name="$(basename "$file" Tests.swift)"
  listed=0
  for entry in "${tests[@]}"; do [ "${entry%%:*}" = "$name" ] && listed=1; done
  if [ "$listed" = 0 ]; then
    echo "FAIL: $file is not listed in scripts/test-ios.sh"
    failed=$((failed + 1))
  fi
done

if [ -z "$only" ] && [ -f iOS/Photos/photos.json ]; then
  ran=$((ran + 1))
  # Bundled photos: listed in photos.json, the stated size, and no metadata
  # (no EXIF, GPS, XMP, IPTC, colour profile or comment).
  if ! python3 - <<'PY'
import json, os, struct, sys
root = "iOS/Photos"
manifest = json.load(open(os.path.join(root, "photos.json"), encoding="utf-8"))
listed = [entry["file"] for entry in manifest["photos"]]
problems = []
if manifest.get("version") != 1: problems.append("photos.json: version is not 1")
if len(set(listed)) != len(listed): problems.append("photos.json lists a photo twice")
on_disk = sorted(name for name in os.listdir(root) if name.lower().endswith((".jpg", ".jpeg", ".png", ".heic")))
for name in sorted(set(on_disk) - set(listed)): problems.append(f"{name} is not in photos.json")
for name in sorted(set(listed) - set(on_disk)): problems.append(f"{name} is listed but missing")
allowed = {0xE0: "JFIF"}  # APP0 only
for entry in manifest["photos"]:
    name = entry["file"]
    path = os.path.join(root, name)
    if not os.path.exists(path): continue
    data = open(path, "rb").read()
    if data[:2] != b"\xff\xd8":
        problems.append(f"{name} is not a JPEG"); continue
    size = None
    i = 2
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            problems.append(f"{name}: unexpected data at {i}"); break
        marker = data[i + 1]
        if marker == 0xDA: break  # image data follows
        length = struct.unpack(">H", data[i + 2:i + 4])[0]
        body = data[i + 4:i + 2 + length]
        if 0xE0 <= marker <= 0xEF and marker not in allowed:
            kind = body[:12].split(b"\0")[0].decode("latin1", "replace")
            problems.append(f"{name}: metadata segment APP{marker - 0xE0} ({kind})")
        if marker == 0xFE: problems.append(f"{name}: comment segment")
        if marker in (0xC0, 0xC1, 0xC2):
            size = struct.unpack(">HH", body[1:5])[::-1]
        i += 2 + length
    for needle in (b"Exif\0", b"http://ns.adobe.com/xap", b"GPS", b"ICC_PROFILE", b"Photoshop 3.0"):
        if needle in data[:i]: problems.append(f"{name}: contains {needle.decode('latin1').strip(chr(0))}")
    if size is None: problems.append(f"{name}: no image size found")
    elif size != (entry["width"], entry["height"]): problems.append(f"{name}: is {size}, photos.json says {(entry['width'], entry['height'])}")
    if max(entry["width"], entry["height"]) > 2048: problems.append(f"{name}: longer than 2048 px")
    for cat in entry.get("cats", []):
        if cat not in ("mimimo", "momo"): problems.append(f"{name}: unknown cat {cat}")
    month = entry.get("month")
    if month is not None and not (len(month) == 7 and month[4] == "-" and month[:4].isdigit() and month[5:].isdigit()):
        problems.append(f"{name}: month should be yyyy-MM")
if problems:
    print("FAIL: bundled photos"); [print("  " + p) for p in problems]; sys.exit(1)
print(f"PASS: {len(listed)} bundled photos are listed, sized as stated and carry no metadata")
PY
  then failed=$((failed + 1)); fi
fi

if [ -n "$simulator" ]; then
  ran=$((ran + 1))
  echo "Building the lab app for the typing checks…"
  xcrun simctl boot "$simulator" 2>/dev/null || true
  xcrun simctl bootstatus "$simulator" -b >/dev/null 2>&1 || true
  xcodebuild -project iOS/Meowpuut.xcodeproj -scheme MeowpuutKeyboardLab -sdk iphonesimulator \
    -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath .build/iOS-lab -jobs 4 CODE_SIGNING_ALLOWED=NO build -quiet
  xcrun simctl install "$simulator" .build/iOS-lab/Build/Products/Debug-iphonesimulator/MeowpuutKeyboardLab.app
  if ! python3 - "$simulator" <<'PY'
import json, os, random, subprocess, sys, time

udid = sys.argv[1]
bundle = "com.krysta1ll.meowpuut.ios.lab"
container = subprocess.check_output(["xcrun", "simctl", "get_app_container", udid, bundle, "data"], text=True).strip()

def run(script, args):
    name = f"typing-{os.getpid()}-{random.randrange(10**9)}.json"
    path = os.path.join(container, "tmp", name)
    subprocess.check_output(["xcrun", "simctl", "launch", "--terminate-running-process", udid, bundle,
                             "-touchScript", script, "-resultFile", name] + args)
    for _ in range(600):
        if os.path.exists(path):
            time.sleep(0.05)
            with open(path, encoding="utf-8") as f: data = json.load(f)
            os.remove(path)
            return data
        time.sleep(0.05)
    return {"errors": ["the lab app wrote no result"]}

EN = ["-inputMode", "pinyin", "-english", "YES", "-autocapitalization", "none"]
ENS = ["-inputMode", "pinyin", "-english", "YES"]
PY = ["-inputMode", "pinyin"]
ZY = ["-inputMode", "zhuyin"]
JA = ["-inputMode", "japanese"]
def doc(text): return ["-document", text]

# name, lab arguments, touch script (iOS/Lab/LabScript.swift), expected state
cases = [
 ("taps type letters", EN, "keys:hello", dict(text="hello")),
 ("a second finger commits the first key", EN, "d1:a;d2:b;u1;u2", dict(text="ab")),
 ("three fingers keep their order", EN, "d1:a;d2:b;d3:c;u3;u2;u1", dict(text="abc")),
 ("sliding types the key under the finger at release", EN, "d1:q;m1:w;m1:e;u1", dict(text="e")),
 ("a cancelled touch types nothing", EN, "d1:q;c1", dict(text="", balloons=0, touches=0)),
 ("a held key shows one balloon", EN, "d1:q;w100", dict(text="", balloons=1, touches=1)),
 ("Shift capitalises one letter", EN, "tap:shift;keys:ab", dict(text="Ab", shift="off")),
 ("double tap locks capitals", EN, "tap:shift;tap:shift;keys:ab", dict(text="AB", shift="locked")),
 ("a tap unlocks", EN, "tap:shift;tap:shift;keys:ab;tap:shift;keys:c", dict(text="ABc", shift="off")),
 ("slow Shift taps do not lock", EN, "tap:shift;w500;tap:shift;keys:a", dict(text="a", shift="off")),
 ("held Shift capitalises until released", EN, "d1:shift;tap:a;tap:b;u1;tap:c", dict(text="ABc", shift="off")),
 ("sliding from Shift types one capital", EN, "d1:shift;m1:a;u1;tap:b", dict(text="Ab", shift="off")),
 ("a sentence starts with a capital", ENS, "keys:hi", dict(text="Hi")),
 ("double tap at a sentence start locks", ENS, "tap:shift;tap:shift;keys:ab", dict(text="AB", shift="locked")),
 ("a tap declines the sentence capital", ENS, "tap:shift;w500;keys:ab", dict(text="ab")),
 ("Delete removes one character", EN, "keys:abc;tap:delete", dict(text="ab")),
 # The wait runs long when the Mac is busy, so the lower bound leaves room for two more repeats.
 ("held Delete repeats", EN + doc("abcdefghijklmnop|"), "d1:delete;w760;u1", dict(_length=(8, 12))),
 ("space types a space", EN, "keys:a;tap:space;keys:b", dict(text="a b")),
 ("double space types a period", EN, "keys:a;tap:space;tap:space", dict(text="a. ")),
 ("held space moves the cursor 9 pt a character", EN + doc("hello|"), "d1:space;w520;m1:-27,0;u1", dict(before="he", after="llo")),
 ("the cursor follows the finger both ways", EN + doc("hello|"), "d1:space;w520;m1:-45,0;m1:+18,0;u1", dict(before="he", after="llo")),
 ("the cursor steps over an emoji as one character", EN + doc("a😀b|"), "d1:space;w520;m1:-18,0;u1", dict(before="a", after="😀b")),
 ("a short hold on space still types it", EN, "keys:a;d1:space;w200;u1", dict(text="a ")),
 ("the symbol page stays open", EN, "tap:symbols;tap:1;tap:2", dict(text="12", page=1)),
 ("space returns to letters", EN, "tap:symbols;tap:1;tap:space", dict(text="1 ", page=0)),
 ("sliding from 123 types one symbol", EN, "d1:symbols;m1:1;u1;keys:a", dict(text="1a", page=0)),
 ("the second symbol page", EN, "tap:symbols;tap:symbols;tap:[", dict(text="[", page=2)),
 ("a long press released without sliding types the key", EN, "d1:e;w620;u1", dict(text="e")),
 ("sliding picks an accent", EN, "d1:e;w620;m1:+40,0;u1", dict(text="è")),
 ("on the right half the key stays under the finger", EN, "d1:o;w620;u1", dict(text="o")),
 ("and its accents run to the left", EN, "d1:o;w620;m1:-40,0;u1", dict(text="ô")),
 ("Shift applies to a long press released on the key", EN, "tap:shift;d1:e;w620;u1;keys:a", dict(text="Ea", shift="off")),
 ("a held symbol types itself", EN, "tap:symbols;d1:!;w620;u1", dict(text="!")),
 ("sliding well below the key cancels a long press", EN, "d1:e;w620;m1:+0,120;u1", dict(text="", touches=0)),
 ("3.14 stays on the symbol page", EN, "tap:symbols;tap:3;tap:.;tap:1;tap:4", dict(text="3.14", page=1)),
 ("an apostrophe returns to letters", EN, "tap:symbols;tap:';keys:s", dict(text="'s", page=0)),
 ("a Chinese full stop returns to letters", PY, "tap:symbols;tap:。", dict(text="。", page=0)),
 ("but not after a digit", PY, "tap:symbols;tap:1;tap:、;tap:2", dict(text="1、2", page=1)),
 ("the Traditional symbol page has corner brackets", ZY, "tap:symbols;tap:「;tap:」", dict(text="「」", page=1)),
 ("Return is typed before the next finger's key", EN, "keys:a;d1:return;d2:b;u2;u1", dict(text="a\nb")),
 ("the English toggle applies before the next finger's key", PY + ["-autocapitalization", "none"], "d1:toggle;d2:a;u2;u1", dict(text="a", marked="", english=True)),
 ("a held Return is kept when another finger changes the page", PY, "tap:symbols;d1:return;d2:，;u2;u1", dict(text="\n，", page=0)),
 ("a second finger during a slide from 123", EN, "d1:symbols;m1:1;d2:2;u1;u2", dict(text="12", page=0)),
 ("and when the second finger lifts first", EN, "d1:symbols;m1:1;d2:2;u2;u1", dict(text="12", page=0)),
 ("123 held while another finger types returns to letters", EN, "d1:symbols;tap:5;u1;keys:a", dict(text="5a", page=0)),
 ("a slide from Shift is typed before the next finger's key", EN, "d1:shift;m1:h;d2:i;u2;u1", dict(text="HI", shift="off")),
 ("other fingers type nothing while space moves the cursor", EN + doc("hello|"), "d1:space;w520;d2:a;m1:-18,0;u1", dict(before="hel", after="lo", touches=0)),
 ("English suggests completions of the word", EN, "keys:hel;w300", dict(text="hel", _suggests="hello", _first=None)),
 ("and a correction, which only a tap takes", EN, "keys:becuase;w300;suggest:0", dict(text="because ")),
 ("space never takes a suggestion", EN, "keys:becuase;w300;tap:space", dict(text="becuase ")),
 ("Return never takes a suggestion", EN, "keys:becuase;w300;tap:return", dict(text="becuase\n")),
 ("Return starts a new line", EN, "keys:a;tap:return;keys:b", dict(text="a\nb")),
 ("a disabled Return does nothing", EN + ["-returnAutomatic", "YES"], "tap:return", dict(text="")),
 ("pinyin composes", PY, "keys:nihao", dict(marked="ni\u2006hao", _first="你好")),
 ("space picks the first candidate", PY, "keys:nihao;tap:space", dict(text="你好", marked="")),
 ("an emoji follows its word but is never first", PY, "keys:nihao", dict(_first="你好", _second="👋")),
 ("a tap picks a candidate", PY, "keys:nihao;pick:2", dict(text="妳好")),
 ("a tap on the emoji types it", PY, "keys:nihao;pick:1", dict(text="👋", marked="")),
 ("space commits the word, not the emoji", PY, "keys:nihao;tap:space", dict(text="你好")),
 ("a kaomoji follows the third word", PY, "keys:kaixin", dict(_first="開心", _second="😊", _kaomoji_after=3)),
 ("space and Return never type a kaomoji", ZY, "keys:ㄎㄞ;tap:space;keys:ㄒㄧㄣ;tap:space;tap:space;keys:ㄎㄞ;tap:space;keys:ㄒㄧㄣ;tap:space;tap:return", dict(text="開心開心")),
 ("after a commit one emoji of the word comes first", PY, "keys:shengrikuaile;tap:space", dict(text="生日快樂", _symbols_first={"🎂", "🎉"})),
 ("a tapped emoji is typed and the row stays", PY, "keys:shengrikuaile;tap:space;suggest:0;suggest:0", dict(_typed_first_twice="生日快樂", _symbols_first={"🎂", "🎉"})),
 ("Japanese words have emoji too", JA, "keys:arigatou", dict(_first="ありがとう", _second="🙏")),
 ("the ^_^ key lists kaomoji", PY, "tap:symbols;tap:kaomoji", dict(text="", page=1, _kaomoji_open=True)),
 ("a kaomoji is typed and the list stays open", PY, "tap:symbols;tap:kaomoji;kaomoji:0;kaomoji:0", dict(text="(*´▽`*)(*´▽`*)", _kaomoji_open=True)),
 ("the ^_^ key closes the list", PY, "tap:symbols;tap:kaomoji;tap:kaomoji", dict(_kaomoji_open=False)),
 ("a letter closes the list and is typed", PY, "tap:symbols;tap:kaomoji;tap:letters;keys:ni", dict(marked="ni", _kaomoji_open=False)),
 ("the ^_^ key commits what is being typed", PY, "keys:nihao;tap:symbols;tap:kaomoji;kaomoji:0", dict(text="你好(*´▽`*)", marked="")),
 ("the kaomoji panel stays open while typing from it", PY, "tap:symbols;tap:kaomoji;expand;w300;kaomoji:0", dict(expanded=True, text="(*´▽`*)")),
 ("space types an English word as it was typed", PY + ["-autocapitalization", "none"], "keys:hello;tap:space", dict(text="hello", marked="")),
 ("Zhuyin: space and Return commit the word, not the emoji", ZY, "keys:ㄋㄧˇㄏㄠˇ;tap:space;keys:ㄋㄧˇㄏㄠˇ;tap:return", dict(text="你好你好")),
 ("pinyin with overlapping fingers", PY, "d1:n;d2:i;u1;d1:h;u2;d2:a;u1;d1:o;u2;u1;tap:space", dict(text="你好")),
 ("Delete shortens the reading", PY, "keys:nih;tap:delete", dict(marked="ni")),
 ("Return commits the pinyin letters", PY, "keys:nihao;tap:return", dict(text="nihao", marked="")),
 ("and keeps their case", PY, "keys:i;tap:shift;keys:phone;tap:return", dict(text="iPhone")),
 ("a symbol commits the composition first", PY, "keys:nihao;tap:symbols;tap:1", dict(text="你好1")),
 ("sushi offers 寿司 as Japanese", PY, "keys:sushi", dict(_has="寿司", _japanese="寿司")),
 ("haluo keeps its kana spelling after the Chinese", PY, "keys:haluo", dict(_after_chinese="はぅお", compactJapanese="")),
 ("a sentence in one go", PY, "keys:woxianghekafei;tap:space", dict(text="我想喝咖啡")),
 ("one Shift keeps the capital in the reading", PY, "keys:i;tap:shift;keys:phone", dict(marked="iPhone", _first="iPhone")),
 ("Caps Lock in pinyin types capitals", PY, "tap:shift;tap:shift;keys:nba", dict(text="NBA", marked="")),
 ("switching to English commits", PY + ["-autocapitalization", "none"], "keys:ni;tap:toggle;keys:ok", dict(text="你ok", english=True)),
 ("a held space while composing still picks", PY, "keys:ni;d1:space;w520;u1", dict(text="你", marked="")),
 ("a held 一聲 adds the tone and keeps composing", ZY, "keys:ㄇㄠ;d1:space;w520;u1", dict(_has="貓")),
 ("a pause while composing keeps the reading", PY, "keys:ni;w500;keys:hao", dict(marked="ni\u2006hao")),
 ("a caret moved by touch drops the reading", PY + doc("今天|"), "keys:nih;w400;caret:-6;w100;keys:a", dict(before="", marked="a", after="今天ni\u2006h")),
 ("a field emptied by the host drops the reading", PY, "keys:nih;w400;clear;w100;keys:a", dict(text="a", marked="a")),
 ("keys pressed while the lexicon loads are kept", PY + ["-touchScriptEarly", "YES"], "keys:nihao;tap:space", dict(text="你好")),
 ("the expanded panel opens", PY, "keys:nihao;expand;w300", dict(expanded=True, marked="ni\u2006hao")),
 ("and closes again", PY, "keys:nihao;expand;w300;collapse;w300;tap:space", dict(expanded=False, text="你好")),
 ("Zhuyin composes", ZY, "keys:ㄋㄧˇㄏㄠˇ", dict(_first="你好")),
 ("Zhuyin syllables are split in the marked text", ZY, "keys:ㄋㄧˇㄏㄠˇ", dict(marked="ㄋㄧˇ\u2004ㄏㄠˇ")),
 ("Zhuyin space commits", ZY, "keys:ㄋㄧˇㄏㄠˇ;tap:space", dict(text="你好")),
 ("Zhuyin Return commits the highlighted candidate", ZY, "keys:ㄋㄧˇㄏㄠˇ;tap:return", dict(text="你好")),
 ("Zhuyin first tone", ZY, "keys:ㄇㄠ;tap:space", dict(_has="貓")),
 ("Zhuyin initials find words, not key codes", ZY, "keys:ㄋㄏ", dict(_first="你好", _lacks="sc")),
 ("Zhuyin symbol page", ZY, "tap:symbols;tap:1", dict(text="1", page=1)),
 ("Zhuyin with no candidates types the bopomofo, not key codes", ZY, "keys:ㄅㄆㄇㄈ;tap:return", dict(text="ㄅㄆㄇㄈ")),
 ("Zhuyin without tones is not taken for English", ZY, "keys:ㄖㄣㄏㄜㄖㄣ", dict(_first="任何人", marked="ㄖㄣ\u2004ㄏㄜ\u2004ㄖㄣ")),
 ("a number field opens on the digits", ZY + ["-keyboardType", "number"], "tap:1;tap:2", dict(text="12", page=1)),
 ("Japanese converts", JA, "keys:sushi;tap:space", dict(text="寿司")),
 ("Japanese ー joins the reading and Return keeps the kana", JA, "keys:ge;tap:symbols;tap:ー;keys:mu;tap:return", dict(text="げーむ", page=0)),
 ("Japanese shows every candidate", JA, "keys:neko", dict(_has="猫")),
 ("capitals put katakana first", JA, "tap:shift;tap:shift;keys:sushi", dict(_first="スシ")),
]

failed = 0
times = []
for name, args, script, want in cases:
    got = run(script, args)
    problems = []
    for key, value in want.items():
        if key == "_length":
            n = len(got.get("text", ""))
            if not value[0] <= n <= value[1]: problems.append(f"{n} characters left, expected {value[0]} to {value[1]}")
        elif key == "_first":
            if (got.get("candidates") or [None])[0] != value: problems.append(f"first candidate is not {value}: {got.get('candidates')}")
        elif key == "_has":
            if value not in (got.get("candidates") or []): problems.append(f"{value} is not offered: {got.get('candidates')}")
        elif key == "_suggests":
            if value not in (got.get("suggestions") or []): problems.append(f"{value} is not suggested: {got.get('suggestions')}")
        elif key == "_second":
            if (got.get("candidates") or [None, None])[1:2] != [value]: problems.append(f"second candidate is not {value}: {got.get('candidates')}")
        elif key == "_lacks":
            if value in (got.get("candidates") or []): problems.append(f"{value} is offered: {got.get('candidates')}")
        elif key == "_after_chinese":
            shown = got.get("candidates") or []
            han = [i for i, t in enumerate(shown) if any(0x3400 <= ord(c) <= 0x9fff for c in t)]
            # The row now lists the first-syllable words (哈 蝦 蛤 ...), so the kana can
            # be on a later page; it must not come before a Chinese candidate.
            if value in shown and han and shown.index(value) < han[-1]:
                problems.append(f"{value} is not after the Chinese candidates: {shown}")
        elif key == "_kaomoji_after":
            # A kaomoji in the row, with at least `value` words before it.
            shown = got.get("candidates") or []
            at = [i for i, text in enumerate(shown) if text.startswith("(") and i > 0]
            words = [text for text in shown[:at[0]] if any(0x3400 <= ord(c) <= 0x9fff for c in text)] if at else []
            if len(words) < value: problems.append(f"no kaomoji after {value} words: {shown}")
        elif key == "_symbols_first":
            # The lab remembers picks between runs, so the two may come in either order.
            if (got.get("predictions") or [""])[0] not in value: problems.append(f"the row does not start with one of {value}: {got.get('predictions')}")
        elif key == "_typed_first_twice":
            first = (got.get("predictions") or [""])[0]
            if got.get("text") != value + first * 2: problems.append(f"text is {got.get('text')!r}, expected {value + first * 2!r}")
        elif key == "_kaomoji_open":
            if bool(got.get("kaomoji")) != value: problems.append(f"the kaomoji list is {'open' if got.get('kaomoji') else 'closed'}")
        elif key == "_japanese":
            if value not in (got.get("japanese") or []): problems.append(f"{value} is not marked Japanese")
        elif got.get(key) != value:
            problems.append(f"{key} is {got.get(key)!r}, expected {value!r}")
    problems += got.get("errors") or []
    times += [a + b for a, b in zip(got.get("strokeMs", []), got.get("layoutMs", []))]
    if problems:
        failed += 1
        print(f"FAIL: {name}: " + "; ".join(problems))
times.sort()
if times:
    print("  key handling and layout per stroke: median %.2f ms, 95th percentile %.2f ms (%d strokes)"
          % (times[len(times) // 2], times[int(len(times) * 0.95)], len(times)))
if failed:
    print(f"FAIL: {failed} of {len(cases)} typing checks")
    sys.exit(1)
print(f"PASS: {len(cases)} typing checks on the real touch surface")
PY
  then failed=$((failed + 1)); fi
  xcrun simctl terminate "$simulator" com.krysta1ll.meowpuut.ios.lab >/dev/null 2>&1 || true
fi

if [ "$failed" -gt 0 ]; then
  echo "$failed of $ran failed"
  exit 1
fi
echo "All $ran passed"
