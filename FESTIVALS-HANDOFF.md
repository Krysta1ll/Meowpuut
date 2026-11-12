# 節日衣櫥 handoff

Branch: worktree-agent-a490ea3ecee5b1e5f. Phase 1 done; Phase 2 under way. docs/FESTIVALS.md describes the current state.

## Phase 2 so far

- Merged the cat branch (32d3c9a), then `lexicon-without-rime-ice` with the finished cats (1ed8bc2; one conflict in Windows layout_test, kept the v2 festival tests).
- The owner allowed cutting and rearranging the art. The importer now cuts each trim's ornaments off the glass tube (`ornaments()` in scripts/import_festivals.py; per-theme `ADAPT`), and the bar shows only the right-end cluster, between the expand arrow and the screen edge (`FestivalEndsLayout`). The left end has no room for a readable cluster.
- Dressed cats sit behind the glass like the everyday peek, the edge across the chest (`bust` in the manifest, `FestivalTheme.catSprite`); on a festival day the pair always uses the `ends` arrangement, otherwise the day's everyday cats.
- iOS keyboard, the app's previews (only the theme worn today) and the macOS candidate window: cats, right ornament, capsule colour. Android and Windows: capsule colour only.
- `FestivalOutfit` and the Kotlin/C++ `Outfit` stubs are gone; render_cats.py no longer handles festivals.
- The shipped keyboard now shows the theme (no longer lab-only). Lab: `-festivalDay`, `-festivalTheme`. Real-keyboard shots of another day: `FESTIVAL_SHOTS=1 bash scripts/ios-real-keyboard-shots.sh` with `"festivalShotDay"` in a plan's settings.

## Run

- `bash scripts/test-ios.sh`: 15/15 (FestivalsTests 577 checks, incl. the end-cluster placement for 24 themes × 3 shapes).
- `bash scripts/build.sh --test`: exit 0. Android `testDebugUnitTest`: 55/0. `bash scripts/build-windows-check.sh`: layout and session tests pass, MinGW cross build of the preview app succeeds. Windows never run on Windows.
- iOS app and lab build for the simulator.

## Open

- Android and Windows: dressed cats and the right-end ornament.
- Owner sheets: .build/festival-previews/ornaments/ (in progress).
- Cut quality: a few clusters keep a short arc of the tube (halloween, songkran) or lose a ribbon end (new_year, white_day, midsummer); tune with `ADAPT` after looking.
- iOS catalog still ships the unused `_frame` images (~2 MB); drop them once Android/Windows decide what they draw.
