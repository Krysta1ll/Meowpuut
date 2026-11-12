# 節日衣櫥 handoff

Branch: worktree-agent-a490ea3ecee5b1e5f. Phase 1 is finished apart from what "Open" lists. Phase 2 waits for the cat branch.

## Phase 1, first agent (verified again after the merge)

- Importer `scripts/import_festivals.py PACK [PACK ...]` (24 collection, then v1 and v2), manifest v2 with 24 themes and 2026–2030 days, selection in `Sources/Festivals.swift`, trim renderer `Sources/FestivalTrim.swift` (not in the bar yet), settings page `iOS/App/FestivalWardrobeView.swift`, art only in the keyboard extension. Previews: `bash scripts/preview-festivals.sh` → `.build/festival-previews/`.

## Phase 1, second agent

- Merged `lexicon-without-rime-ice` (9c83ed1). One conflict, scripts/build.sh SOURCES: kept both Symbols.swift and FestivalTrim.swift. KeyboardSettingsView keeps the privacy row and the wardrobe link; project.yml keeps Symbols.swift, kaomoji.tsv, 0.7.0 (8) and the festival entries.
- Android `ime/Festivals.kt` and Windows `UI/Festival.cpp/.h` read manifest v2 and pick the theme and the met list by the same rules as Swift. The old overlay `Outfit` types stay only so the old cat code compiles; they find nothing.
- One cross-platform check: `Android/app/src/test/resources/festivals/worn-2026-2030.txt`, the theme worn on every day 2026–2030 (all on, and four switched off), written by the Swift test (`FESTIVALS_WRITE_WORN=1 bash scripts/test-ios.sh --only Festivals`) and compared day by day by LogicTest and layout_test. The fixture has six themes (two bad), with the same expected days in all three tests.
- id and image names must be plain names (letters, digits, `_`, `-`) on all three platforms.
- Keyboard: `Preferences.festivalsSkipped` (read from the shared settings) and `festivalWardrobe`; `iOS/Keyboard/KeyboardFestival.swift` `today()` is where the keyboard picks the theme. Lab only (`#if KEYBOARD_LAB`): `-festivalDay`, `-festivalTheme`; the lab result has a `festival` field; five simulator cases in scripts/test-ios.sh.
- `iOS/LabFestivals` and `-festivalTest` removed.
- docs/FESTIVALS.md rewritten.

## Run (second agent)

- `bash scripts/test-ios.sh`: 14/14 (13 unit suites + photos). FestivalsTests 504 checks.
- Android `./gradlew --offline testDebugUnitTest`: 48 tests, 0 failures (Kotlin main and test compile).
- `bash scripts/build-windows-check.sh`: layout_test (with -Werror -Wconversion), session_test, SVG snapshots pass; MinGW cross build skipped (no compiler here). CandidateWindow.cpp (Win32) not compiled.
- Simulator and macOS results: see the last commit message.

## Open

- `scripts/render_cats.py export_festivals` still expects v1; remove in Phase 2 (file owned by the cat agent).
- `FestivalOutfit` / `Outfit` stubs in Swift, Kotlin, C++: delete in Phase 2.

## Phase 2 needs

From the cat branch: the sprite source, the decoration layer over the bar's ends, and the accent override. Pass the glass rect, the shape and the clear rects (arrow, plus candidates outside `textArea`) to FestivalTrimView. Use `colors.light`, `colors.dark`, `colors.contrast` for the capsule. Show the theme in CandidateBarPreview only while it is worn (`KeyboardFestival.today()` in the keyboard, `Festivals.theme(wardrobe:)` in the app).

## Art notes from the previews

- On the phone layout every theme's right-hand ornaments sit under the expand arrow; the `clear` fade removes part of them. The selected first candidate also sits on the left cap's ornaments (it is outside `safeText.left`).
- The rims of 白色情人節, 新年, 仲夏節, 秋夕 and 萬聖節 are off-centre vertically in their frames; the rim measurement copes with that.
