# 新貓上候選列：交接

分支 `worktree-agent-a81b683dec7beb499`，只在這個 worktree 工作，不 push。
這份筆記寫於 2026-11-12，第二位接手者被使用者叫停（改由 Opus 接手）時。只寫實際跑過、看過的事。

## 這一輪做了什麼

- `ae37c25` wip：把前一位留下的 macOS、Android、Windows 未提交程式存成一個檢查點（當時未建置）。
- 合併 `lexicon-without-rime-ice`（新圖示、App 內隱私政策頁），沒有衝突；`iOS/App/KeyboardSettingsView.swift` 兩邊的改動都在。
- 重新跑 `bash scripts/prepare-ios.sh`（成功，Xcode 專案已含 `PrivacyView.swift`）。
- 文案：設定頁和 Android 字串裡「連續輕摸右邊的莫莫五下」去掉「右邊的」（繁簡都改）。貓每天換位置，她不一定在右邊。

## 第三輪（Opus）進度

1. 已修：爪子在波浪、雲朵上被切缺口。前景爪子的遮罩改成整隻爪子（`CatSprite.pawOutlines`，iOS 鍵盤和 SwiftUI 共用），不再畫錨點列以下的整條（那條會在玻璃上留一條淡灰線，莫莫趴著時看得到）；所有身體先畫、所有爪子後畫，所以左邊作伴裡莫莫不再咬到米米莫的爪子。`place` 多一條：每隻爪子下面的邊緣高出錨點列不超過 1.5 pt。
2. 已修：左邊、右邊作伴的間距 −3 改 −6，米米莫的眼睛露出來。往內滑的上限 30 % 改 20 %。
3. 拿掉「米米莫扶框」（hold）：手機和 iPad 的玻璃外側沒有空間，他只能站在玻璃圓端前面，爪子抓不到東西，和素材包的樣子不同。`side_hold` 圖片和資料留著（素材包的一部分），沒有任何位置用它。
4. 傾斜和摸貓的旋轉：切口兩端都在爪子底下，爪子在前景，放大看不到縫，不改。
5. 接點 1 做好：`place(on:sprite:smallest:)` 和 `CatSchedule.arrangement(...smallest:)` 接受沒有切口的整隻貓（腳放在邊緣上，用不透明寬度），最小縮放由呼叫的人決定；`KeyboardGeometryTests.sittingCat` 用假圖測。
6. `bash scripts/test-ios.sh`：All 15 passed。截圖：`.build/cat06-shots/lab3/`（八種位置，膠囊淺深、波浪和雲朵淺深）。

各寬度放得下的位置（修完後）：手機膠囊八種全部；手機波浪 ends、rest、momoLounge（320 只有 ends、rest）；手機雲朵 ends、left、rest、momoLounge（375 多 right，320 沒有 momoLounge 以外的 right）；橫向三種造型 ends、left、right；iPad 膠囊八種，波浪、雲朵五到六種。

## 各平台狀態

### iOS
跑過：
- `bash scripts/test-ios.sh`（不帶模擬器）：All 15 passed（合併之後跑的）。
- Lab app 建置成功（`scripts/ios-keyboard-shots.sh`，iPhone 17）。

沒跑：
- `bash scripts/test-ios.sh --simulator <udid>` 的打字檢查。
- 主 app 加鍵盤擴充的建置（`Meowpuut` scheme）。
- `bash scripts/ios-real-keyboard-shots.sh`：真正的系統鍵盤截圖一張都還沒有，`log.txt` 的 393 / 406 pt 還沒驗證。改貓之前的基準在 `.build/cat06-shots/before-real/log.txt`（393 / 406）。
- iPad、iPad 浮動鍵盤、第二個色系的截圖。
- 前後對照圖（玻璃和候選列逐像素相同）。之前的圖在 `.build/cat06-shots/before-lab/`、`before-real/`。

看過的 lab 截圖（iPhone 17，402 pt，`.build/cat06-shots/lab/`）：
- 膠囊淺色九種位置（`ends.png` … `hold.png`，候選 nihao）、膠囊深色九種（`*-dark.png`，候選 kaixin，有表情和顏文字格）。原尺寸和放大都看了：貓坐在玻璃上緣，爪子搭過邊，清楚；深色下米米莫有一圈暖色光，看得清；沒有蓋到候選、選中膠囊、表情格、顏文字格或展開箭頭。
- 波浪、雲朵淺色的 ends / left / right / rest / momoLounge（`wave-*.png`、`cloud-*.png`）。

拍了還沒看（同一資料夾）：`wave-*-dark.png`、`cloud-*-dark.png`（長候選）、`land-*.png`（橫向）、`narrow-*.png`（320 pt）、`long-*.png`、`expanded-*.png`、`kaomoji-*.png`、`idle-rest.png`、`commit-right.png`、`zhuyin-mimimoLounge.png`、`japanese-momoLounge-dark.png`、`english-left.png`。

### macOS
程式已寫（`Sources/CandidateWindow.swift`、`CatGlass.swift`、`CatFigures.swift`、`Interface.swift`、`main.swift`、`Tests/CandidateLayoutTests.swift`、`scripts/build.sh`）。這一輪沒有建置、沒有跑 `bash scripts/build.sh --test`、沒有看過畫面。

### Android
輔助 agent 寫到一半（`ime/CatStage.kt`、`Cats.kt`、`CandidateBarView.kt`、`Geometry.kt`、`GlassOutline.kt`、`CatTest.kt`），沒有收尾報告。沒建置、沒跑測試。`app/src/test/.../OldBarProbe.kt` 是量舊數值用的探針，收尾時刪掉。

### Windows
輔助 agent 停在「點擊測試」（`Windows/UI/CandidateLayout.cpp/.h`、`Geometry.h`）。沒建置。`CatSpriteData.h` 的 `0f` 問題：產生器裡已有 `real()`，標頭裡搜不到 `0f`；`bash scripts/build-windows-check.sh` 還沒跑。

### 文件
還沒動。寫了貓在哪一邊、需要改的地方：`README.md` 第 18、22 行；`docs/DESIGN.md` 第 9、31、94、97、111、113、130 行；`iOS/README.md` 第 42 行。`docs/RELEASE_NOTES.md` 第 40 行是舊版紀錄，不改。`docs/BRAND.md` 第 9 行說的是標誌，不改。

## 放大看到的問題（還沒修）

1. **波浪和雲朵上，爪子被玻璃切出缺口。** 貓的錨點放在那一段邊緣的最低點，邊緣在爪子那裡較高時，玻璃蓋住爪子上半，前景只剩錨點列以下的一小片，看起來像缺一角或一條深色短線。看得到的圖：`wave-rest.png`（兩隻的爪子）、`wave-momoLounge.png`（莫莫右爪旁一條短線）、`cloud-rest.png`、`cloud-ends.png`（米米莫左爪）、`wave-ends.png`（米米莫右爪，較輕）。膠囊沒有這個問題。
   打算的修法（`Sources/CatSprites.swift` 的 `CatArrangement.place`）：
   - 每隻爪子寬度範圍內，邊緣高出錨點列不得超過約 0.8 pt（現在只有整段 4 pt、中間一半 1.5 pt 兩個條件）；不符合就照現有方式往內滑。
   - 波浪的坡很緩（最多約 6°）：讓貓繞錨點轉到兩爪連線貼著邊緣（上限約 7°），錨點列再往下移到整段切口都不高於邊緣，這樣不留縫。雲朵的坡太陡，只會落在平的段落。
   - Android、Windows 只做第一條（不轉）。
2. **「左邊作伴」裡莫莫蓋住米米莫右眼約 2 pt**（`left.png`、`left-dark.png`）。把那一席的 `overlap` 從 −3 改到約 −6，或改成米米莫畫在前面。
3. **波浪的「右邊作伴」滑到列的中間偏右**（`wave-right.png`，約在寬度 55–70 %），名不符實。把往內滑的上限從玻璃寬的 30 % 降到 20 %：手機波浪就不收這一種，雲朵的 ends / left 仍放得下（滑約 63–70 pt）。
4. **作伴的傾斜和摸貓的搖頭是繞錨點旋轉**，平切口的一端會離開玻璃邊（傾斜 4° 約 0.9 pt，搖頭 6° 約 1.4 pt）。沒在截圖裡確認看得到；想改成水平斜切（錨點列不動），站在玻璃前的整隻貓（扶框、節日坐姿）照舊用旋轉。若改，建議身體一層做呼吸、外面一層做傾斜和反應。

## 各寬度、各造型放得下的位置（現在的程式，`.build/cat06-tools/pools.swift` 印的）

- 手機直向 320–440，膠囊：九種全部（ends、left、right、lounge、rest、mimimoLounge、momoLounge、company、hold）。
- 手機直向，波浪：ends、left、right（320 沒有 right）、rest、momoLounge。
- 手機直向，雲朵：ends、left、right、rest、momoLounge。
- 手機橫向（三種造型）：ends、left、right，貓縮到 0.47–0.49。
- iPad 膠囊：九種；波浪：ends、left、right（744 沒有）、rest、momoLounge，1180 以上加 company；雲朵：前五種加 company。
- 手機 393 上貓的縮放：探頭 0.68、搭邊 0.54、趴著 0.60、扶框 0.82（玻璃上方只有 32.5 pt）。

## 給節日 agent 的三個接點（第三輪更新，請看這裡）

1. **貓圖的來源與整隻貓坐上緣（已做好，在 `e4ef811`）。**
   - 圖的描述：`Sources/CatSprites.swift:74` `CatArt.sprite(_:_:)`；圖片：iOS `iOS/Keyboard/CatStage.swift:14` `CatArt.image(_:)`，macOS `Sources/CatGlass.swift:23` `DesktopCatArt.picture(_:)`。
   - 座位：`Sources/CatSprites.swift:289` `CatArrangement.place(on:sprite:smallest:)`、`:420` `CatSchedule.arrangement(setting:day:on:sprite:smallest:)`。`cut` 為空（`0...0`）、`paws` 為空的 sprite 就是整隻貓：用不透明寬度找位置，錨點（腳）放在邊緣上，整隻畫在玻璃前（iOS `CatLayersView`、SwiftUI `CatFigures` 都已這樣畫）。`smallest` 由呼叫的人給，預設是平常貓的 0.45。
   - 測試：`iOS/tests/KeyboardGeometryTests.swift` 的 `sittingCat()`（64×80、錨點 (0.5, 0.92) 的假圖，320–440 直向、iPad、橫向三種造型都坐得上，最小縮放 0.3；375 pt 在 0.45 坐不上、0.4 坐得上）。
   - 坐姿在 393 pt 約 0.47、375 約 0.44、320 約 0.42、橫向約 0.34（照 `room / ((0.92 − 上緣) × 80)` 算）。
2. **列兩端的裝飾層：** iOS `iOS/Keyboard/CandidateRegionView.swift:475` `barTrim(_:)`（沒動過）；macOS 新加 `Sources/CatGlass.swift` `barTrim()`（在 `catsBar` 的背景裡，玻璃之上、候選和貓之下）。
3. **選中膠囊顏色：** iOS `CandidateRegionView.swift:334` `ui.outfit?.pillTint`（沒動過）；macOS `DeskPill(palette:festival:look:)`，傳入 `outfit?.pillColor`。

## 下一步（照順序）

1. iOS：修上面 1–3（4 視情況），重跑 `bash scripts/test-ios.sh`，更新 `KeyboardGeometryTests` 裡各造型的位置清單（橫向現在斷言只有 ends / left / right）。
2. iOS：看完「拍了還沒看」的圖，補 iPad（模擬器 1FA9C4BB-…）、浮動鍵盤、第二個色系。
3. iOS：`bash scripts/test-ios.sh --simulator 3176F860-BAA8-4A80-B407-D3A669A8B9A3`，再 `bash scripts/ios-real-keyboard-shots.sh 3176F860-… .build/cat06-shots/real`，確認 `log.txt` 是 393 / 406，逐張看。
4. 前後對照：同一狀態（例如 nihao 膠囊淺色）和 `before-real/meow-nihao-light.png` 比，貓以外的像素要相同。
5. 整隻貓坐上緣（接點 1）加測試。
6. macOS：`bash scripts/build.sh --test`，看候選窗（`scripts/preview-glass.sh`）。
7. Android：`bash scripts/build-android.sh --test`，刪 `OldBarProbe.kt`。Windows：`bash scripts/build-windows-check.sh`，看 `.build/windows-check/` 的圖。
8. 文件和 README（上面列的行）。

## 工具

- `.build/cat06-tools/sheet.py`：把多張截圖的候選列裁出來排成一張（`python3 sheet.py out.png 1000 700 a.png b.png`，可加 `--left --right --scale --cols`）。
- `.build/cat06-tools/pools.swift`：印各寬度、造型放得下的位置（和 `iOS/Keyboard/KeyboardGeometry.swift Sources/Appearance.swift Sources/CatSprites.swift Sources/CatSpriteData.swift` 一起用 `swiftc -parse-as-library` 編）。
- Lab 參數：`-catPlacement <id|daily>`、`-catDay <n>`、`-keyboardWidth 320`、`-landscape YES`。
