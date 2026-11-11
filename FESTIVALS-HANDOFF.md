# 節日衣櫥 handoff (Phase 1, stopped early)

Branch: worktree-agent-a490ea3ecee5b1e5f. Stopped on request before Phase 1 was finished.

## Done and checked

- **Importer**: `scripts/import_festivals.py PACK [PACK ...]`. Run with the 24 collection followed by v1 and v2. Every PNG matched its pack's SHA256SUMS, and the collection's PNGs are byte-identical to the ones in v1 and v2. Sizes, RGBA, transparent edges and the foot anchor (±0.04) are checked. Outputs: `assets/festivals/` (@3x and the manifest), `iOS/Festivals/festivals.json`, `iOS/Festivals.xcassets` (@2x and @3x, no @1x), `Resources/Festivals` (@1x and @2x), Android and Windows (@3x). The PNGs keep the pack's tiny EXIF block (colour space and pixel size only) so they stay byte-identical with the checksums.
- **Manifest v2**, 24 themes: names (hant from the owner; hans, ja and en), the two sprites, the trim (caps, safeText, and the measured `rim`), colours (accent plus derived capsule colours for light, dark and Increase Contrast, contrast-checked), and days as `[first, main, last]` for 2026–2030.
- **Dates**, all 24 themes and all five years. Cross-checked against the system ICU calendars in the tests (Chinese, Korean time for 秋夕, Hebrew, Umm al-Qura, Persian) and against the web: HKO tables for the lunar days and the solstice, hebcal, aladhan (Umm al-Qura), Drik Panchang for Diwali and Holi, USNO for the solstice and equinox times, dallyeok.com for 秋夕. Easter comes from the computus, checked against published lists. Conventions are written in the comments of `DAYS` in the importer. **docs/FESTIVALS.md is not rewritten yet** and still describes the old system.
- **Selection** (`Sources/Festivals.swift`): a theme is worn from 7 days before its first day through its last day. Precedence: a festival on its main day, then one on its other own days (the one that began last wins), then one in its lead-up week (the nearest wins). Festivals sharing a main day take turns by day. Switches and the master toggle apply. "Already met" means the worn range began on or after the stored "since" date and on or before today (`Festivals.met`). The calendar for 2026-11-12 to 2027-12-31 is in `docs/festivals/calendar-2026-11-12-to-2027-12-31.txt`.
- **Trim renderer** (`Sources/FestivalTrim.swift`, geometry in Festivals.swift): the rim is laid on the glass outline with uniform scaling, and only the middle slice stretches. Wave and cloud get the ends only, with the rim fading out. A `clear` list of rects fades the trim under the expand arrow and under an uncapsuled candidate. It takes no touches. It is not inserted into the bar yet.
- **Settings**: `iOS/App/FestivalWardrobeView.swift` (master toggle, plus a switch for each theme already met; nothing about themes still to come). It is hooked in with one line in KeyboardSettingsView. MeowpuutApp records the "since" date once. `SharedKeyboardSettings.festivalsSkipped` is added. There is no "wear now".
- Art ships once on iOS, in the keyboard extension. The app reads the manifest and art from the embedded .appex. Measured in the simulator build's Assets.car: 5.7 MB of festival renditions (3.9 MB @3x, 1.9 MB @2x); the manifest is 32 KB.

## Run

- `bash scripts/test-ios.sh`: 13/13 pass. FestivalsTests has 500 checks.
- The iOS app scheme builds for the simulator.
- The lab builds, but only with an (empty, gitignored) `iOS/LabFestivals` folder present. Without it the build fails, and that failure was already there.
- Previews: `bash scripts/preview-festivals.sh` writes 24 sheets plus overview sheets to `.build/festival-previews/`. I looked at all of them.

## Not done (Phase 1)

- docs/FESTIVALS.md rewrite.
- Kotlin `Festivals.kt` and C++ `Festival.cpp/.h` for v2. Both still parse v1, so they read the new manifest as empty (plain cats). Android `LogicTest.festivalsManifest` and the Windows layout test will fail against the new fixture and manifest until they are ported. Not run.
- macOS `bash scripts/build.sh --test`: not run. Tests/CandidateLayoutTests.checkFestivals was rewritten for v2.
- The keyboard does not read `festivalsSkipped` yet (Preferences/Engine.swift is untouched).
- Lab hooks to force a date or theme.
- `scripts/render_cats.py export_festivals` still expects v1 and would stop on the v2 manifest. Remove it in Phase 2.
- `FestivalOutfit` stub at the end of Festivals.swift: it keeps the old cat code compiling. Delete it in Phase 2.

## Art notes from the previews

- On the current phone layout, every theme's right-hand ornaments sit under the expand arrow. The `clear` fade handles this, but it removes part of the right ornament.
- The rims of 白色情人節, 新年, 仲夏節, 秋夕 and 萬聖節 are off-centre vertically in their frames. The rim measurement copes with that.
- No halos seen in dark at phone size. The cats read well at 64 pt.

## Phase 2 needs

From the cat branch: the sprite source, the decoration layer over the bar's ends, and the accent override. Pass the glass rect, the shape and the clear rects (arrow, plus candidates outside `textArea`) to FestivalTrimView. Use `colors.light`, `colors.dark` and `colors.contrast` for the capsule. Show the theme in CandidateBarPreview only while it is worn.
