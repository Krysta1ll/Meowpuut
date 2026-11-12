# 節日衣櫥 handoff

Branch: worktree-agent-a490ea3ecee5b1e5f. Phase 1 done; Phase 2 under way. docs/FESTIVALS.md describes the current state.

## Phase 2 so far

- Merged the cat branch (32d3c9a), then `lexicon-without-rime-ice` with the finished cats (1ed8bc2; one conflict in Windows layout_test, kept the v2 festival tests).
- The owner allowed cutting and rearranging the art. The importer now cuts each trim's ornaments off the glass tube (`ornaments()` in scripts/import_festivals.py; per-theme `ADAPT`), and the bar shows only the right-end cluster, between the expand arrow and the screen edge (`FestivalEndsLayout`). The left end has no room for a readable cluster.
- Dressed cats sit behind the glass like the everyday peek, the edge across the chest (`bust` in the manifest, `FestivalTheme.catSprite`); on a festival day the pair always uses the `ends` arrangement, otherwise the day's everyday cats.
- iOS keyboard, the app's previews (only the theme worn today) and the macOS candidate window: cats, right ornament, capsule colour. Android and Windows: capsule colour only.
- `FestivalOutfit` and the Kotlin/C++ `Outfit` stubs are gone; render_cats.py no longer handles festivals.
- The shipped keyboard now shows the theme (no longer lab-only). Lab: `-festivalDay`, `-festivalTheme`. Real-keyboard shots of another day: `FESTIVAL_SHOTS=1 bash scripts/ios-real-keyboard-shots.sh` with `"festivalShotDay"` in a plan's settings.

## Run (after the size change, 0daab6f)

- `bash scripts/test-ios.sh --simulator <iPhone 16e>`: All 16 passed, 112 typing checks on the real touch surface (incl. the festival lab cases).
- `bash scripts/build.sh --test`: exit 0, 633 PASS lines, none failing.
- Android `testDebugUnitTest --rerun`: 55 tests, 0 failures.
- `bash scripts/build-windows-check.sh`: layout_test and session_test pass; MinGW cross build of the preview app succeeds. Never run on Windows.

## Pictures

- .build/festival-previews/ornaments/01…06-*.png (24 themes from the lab, by date from 2026-11-12; capsule light/dark, long candidate, wave light, cloud dark) and 給主人的說明.md.
- .build/festival-previews/real/: the four December themes on the real system keyboard (FESTIVAL_SHOTS build), capsule and wave; keyboard frame 390 × 386.

## Checked (December themes)

- Lab shots in .build/festival-previews/more/: landscape (cluster 28–35 pt, cats 0.53), 320 pt dark (cluster 34–41 pt, cats 0.66), wave dark, expanded panel, ^_^ row, Increase Contrast light and dark (palette pill, theme ornaments and cats stay). No case fell back to the plain look.
- Size: only the cats and cut ornaments ship. Keyboard Assets.car 6928 KB → 2968 KB; app 184560 KB → 180520 KB (simulator Debug).
- Height: real keyboard, same FESTIVAL_SHOTS build, 2026-12-24 vs an ordinary day: frame 390 × 386 both, typing and idle; the key area is pixel-identical (.build/festival-previews/height/).

## Open

- Android: dressed cats in the `ends` seat and the right-end cluster (1baaa1f; LogicTest checks all 24 themes fit on the three shapes at 393 dp). Debug builds only: `adb shell run-as com.krysta1ll.meowpuut.android sh -c 'echo christmas > files/festival'` (a theme id or a day; src/debug/FestivalOverride.kt, the release source set has an empty one). Seen on the emulator (1080×2400, 420 dpi): the four December themes, light and dark, .build/festival-previews/android/december.png.
- Windows: only the capsule colour. Cats and cluster need a festival field in CandidateViewModel (catArt is global), a third Sprite kind for the cluster in Scene/SvgSnapshot/CandidateWindow, and SVG snapshots to look at.
- Cut quality: halloween and songkran keep a short piece of tube, mid_autumn's moon loses an edge; raising `tau` for them cut the ornaments instead. new_year, white_day, midsummer lose ribbon ends. Narrow clusters (tanabata, midsummer, lantern_festival) come out about 30 pt tall.
- iPad seen only as geometry (no iPad simulator): at 820 and 1024 pt the cluster is 42–52 pt tall and the dressed cats seat at 0.83.
