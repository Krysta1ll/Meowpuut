# 新貓上候選列：交接

分支 `worktree-agent-a81b683dec7beb499`，只在這個 worktree 工作，不 push。第三輪（Opus）寫於 2026-11-12。只寫實際跑過、看過的事。

## 現況

- **iOS**：完成。修了爪子被波浪、雲朵切缺口（前景改畫整隻爪子 `CatSprite.pawOutlines`，不再畫切口那一整列，那一列會在玻璃上留淡灰線）、身體全部先畫爪子後畫、作伴間距 −6、每隻爪下邊緣最多高 1.5 單位、成對往內滑上限 20 %（單隻 30 %）。拿掉「米米莫扶框」：玻璃外沒有讓他扶的空間。
  - `bash scripts/test-ios.sh`：All 15 passed；`--simulator 3176F860-…`：All 16 passed（打字檢查，911 次按鍵中位數 1.04 ms）。
  - 真正的系統鍵盤 `.build/cat06-shots/real/`：拼音 393 pt、注音 406 pt、橫向淺色 238 pt，和改之前相同。橫向深色兩次都沒轉成橫向（模擬器停在直向，量到 393），沒有那一張。
  - 前後對照 `.build/cat06-shots/before-after-nihao-light.png`：不同的像素只在舊貓、舊尾巴和新貓的位置，玻璃、候選、箭頭逐像素相同。
  - Lab 截圖：`.build/cat06-shots/lab3/`（八種位置、三種造型、淺深、320 pt、橫向、長候選、展開、顏文字、海鹽藍與琥珀蜜、注音、日語），`.build/cat06-shots/ipad/`（iPad 與 320 pt 浮動寬度）。
- **macOS**：`bash scripts/build.sh --test` PASS（0 failed checks）。候選窗截圖 `.build/cat06-shots/mac/`。窄的波浪窗（< 230 pt）和雲朵窗（< 200 pt）沒有貓；約 300 pt 的波浪窗莫莫會滑到中間偏右（在一段平的波谷上，不蓋東西）。
- **Android**：`bash scripts/build-android.sh --test` 成功，54 個單元測試 0 失敗（含新的 `FrozenBarTest`：舊程式量出的玻璃、候選列、展開鍵、字級，12 種寬度和造型）。模擬器截圖 `.build/cat06-shots/android/emulator-zhuyin-light.png` 看過；深色沒拍到（鍵盤沒跟著系統夜間模式換）。只有「兩端探頭」一種位置。
- **Windows**：`bash scripts/build-windows-check.sh` 全過：layout_test、session_test、SVG 快照、MinGW-w64 交叉編譯 `MeowpuutCandidatePreview.exe`。快照 `.build/windows-check/previews/`，挑過的在 `.build/cat06-shots/windows/`。舊版和新版的版面數值（60 種情況）完全相同。沒有在 Windows 上執行過。展開面板時，波浪和雲朵上的米米莫會往內移約 50 pt（面板的波浪和收合列不同，原位置不平）。

## 給節日 agent 的三個接點（請看這裡）

1. **貓圖的來源與整隻貓坐上緣（已做好，在 `e4ef811`）。**
   - 圖的描述：`Sources/CatSprites.swift:74` `CatArt.sprite(_:_:)`；圖片：iOS `iOS/Keyboard/CatStage.swift:14` `CatArt.image(_:)`，macOS `Sources/CatGlass.swift:23` `DesktopCatArt.picture(_:)`。
   - 座位：`Sources/CatSprites.swift:289` `CatArrangement.place(on:sprite:smallest:)`、`:420` `CatSchedule.arrangement(setting:day:on:sprite:smallest:)`。`cut` 為空（`0...0`）、`paws` 為空的 sprite 就是整隻貓：用不透明寬度找位置，錨點（腳）放在邊緣上，整隻畫在玻璃前（iOS `CatLayersView`、SwiftUI `CatFigures` 都已這樣畫）。`smallest` 由呼叫的人給，預設是平常貓的 0.45。
   - 測試：`iOS/tests/KeyboardGeometryTests.swift` 的 `sittingCat()`（64×80、錨點 (0.5, 0.92) 的假圖，320–440 直向、iPad、橫向三種造型都坐得上，最小縮放 0.3；375 pt 在 0.45 坐不上、0.4 坐得上）。
   - 坐姿在 393 pt 約 0.47、375 約 0.44、320 約 0.42、橫向約 0.34（照 `room / ((0.92 − 上緣) × 80)` 算）。
2. **列兩端的裝飾層：** iOS `iOS/Keyboard/CandidateRegionView.swift:475` `barTrim(_:)`（沒動過）；macOS 新加 `Sources/CatGlass.swift` `barTrim()`（在 `catsBar` 的背景裡，玻璃之上、候選和貓之下）。
3. **選中膠囊顏色：** iOS `CandidateRegionView.swift:334` `ui.outfit?.pillTint`（沒動過）；macOS `DeskPill(palette:festival:look:)`，傳入 `outfit?.pillColor`。
   - 圖片可以直接換：iOS `CatArt.pictures[sprite 名稱]`、macOS `DesktopCatArt.pictures`。有切口的 sprite 照邊緣姿勢坐（身體在玻璃後、爪子在前），沒有切口的整隻站在玻璃前。

## 還沒做

- 摸貓時的搖頭（6°）沒有截圖確認；切口兩端都在爪子底下，組字時 2° 傾斜放大看沒有縫。
- `assets/screenshots/candidates.png`（README 的圖）還是舊貓。
- iOS 實機、Windows 實機沒有跑過。

## 工具

- `.build/cat06-tools/sheet.py`：把多張截圖的候選列裁出來排成一張（`python3 sheet.py out.png 1000 700 a.png b.png`，可加 `--left --right --scale --cols`）。
- `.build/cat06-tools/pools.swift`：印各寬度、造型放得下的位置（和 `iOS/Keyboard/KeyboardGeometry.swift Sources/Appearance.swift Sources/CatSprites.swift Sources/CatSpriteData.swift` 一起用 `swiftc -parse-as-library` 編）。
- Lab 參數：`-catPlacement <id|daily>`、`-catDay <n>`、`-keyboardWidth 320`、`-landscape YES`。
