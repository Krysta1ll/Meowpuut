# 喵撲 iOS

宿主 App 和自訂鍵盤擴充。鍵盤和其他平台共用 Rust 核心（`crates/`）、Swift 候選排序和詞庫。介面依 `docs/DESIGN.md`，節日裝扮依 `docs/FESTIVALS.md`。需要含 iOS 27 SDK 的 Xcode、Rust、Python 3 和 XcodeGen。最低支援 iOS 17；iOS 26 之前沒有 Liquid Glass，用霧面玻璃和實色按鍵（實驗室 app 加 `-frosted YES` 可以在新系統上看這套樣式）。

```sh
brew install xcodegen
bash scripts/build-ios.sh          # 產生工程並編譯模擬器版本，輸出在 .build/iOS
bash scripts/test-ios.sh           # 單元測試和照片檢查，在 Mac 上跑，不需要模擬器
bash scripts/test-ios.sh --simulator <udid>   # 另外在模擬器上用真正的觸控面打字
```

工程由 `iOS/project.yml` 生成，不要手改 `Meowpuut.xcodeproj`。版本號和簽署團隊也在 `project.yml`。

## 目錄

| 路徑 | 內容 |
| --- | --- |
| `App/` | 宿主 App：首頁（生活照、試打、鍵盤預覽）、照片檢視、鍵盤設定 |
| `Keyboard/` | 鍵盤擴充 |
| `Lab/` | 開發用 App，把鍵盤的控制器放進一般 App，用啟動參數開到任何狀態。不隨產品出貨 |
| `Photos/` | 首頁的生活照和 `photos.json`。照片是私人的，不放進倉庫（`.gitignore`）；沒有這個資料夾時 `scripts/prepare-ios.sh` 會建一份空清單，首頁就不顯示相簿 |
| `Festivals/`、`Festivals.xcassets` | 節日衣櫥的清單和圖（docs/FESTIVALS.md），只放在鍵盤擴充裡，App 從內嵌的擴充讀 |
| `tests/` | 單元測試，每個檔案是一支獨立程式，由 `scripts/test-ios.sh` 編譯執行 |
| `Generated/` | `scripts/prepare-ios.sh` 產生的詞庫、授權文字和圖示 |

鍵盤的主要檔案：

- `KeyboardViewController.swift`：輸入流程、版面、和宿主文字欄位的往來。詞庫在背景載入，載入前按的鍵會排隊。
- `KeyboardKeysView.swift`：整個按鍵區是一個 UIKit 觸控面，按鍵是圖層。處理多指交疊、滑動、鍵帽氣泡、長按替代字、刪除連發和空白鍵游標。
- `KeyboardGeometry.swift`、`KeyboardKeys.swift`、`CandidateStripLayout.swift`、`KeyboardDrivers.swift`、`CapitalizationState.swift`：版面、按鍵表、候選排列和計時邏輯，不依賴 UIKit，所以能在 Mac 上測。
- `CandidateRegionView.swift`、`CatStage.swift`、`ExpandedCandidatesView.swift`：候選玻璃、兩隻貓和展開面板（SwiftUI）。玻璃本身在 `Sources/GlassShapes.swift`，和 macOS 共用。

## 鍵盤

- 拼音、注音、日語羅馬字三種模式，在喵撲 App 的鍵盤設定切換。`ABC` 鍵切到英文，再按一次回到原模式。英文時候選列有補完和拼字建議（`UITextChecker`），點了才換。
- 候選永遠單行，從米米莫身前的玻璃排到莫莫下巴下的箭頭。中文輸入時收合列最多一個日語詞，以貓爪標示；常用詞後面有表情符號（你好 👋），第三個詞後面有顏文字，點了才輸入；選詞之後，候選列先放剛送出的詞的表情符號和顏文字。符號頁的 `^_^` 鍵把候選列換成顏文字，箭頭展開成分組的面板。超過列寬 70% 的候選在中間省略。候選列可以左右滑，箭頭展開成面板（手機一排六格）。
- 組字在文字欄裡分音節（ni hao、ㄋㄧˇ ㄏㄠˇ）。拼音組字時 Return（確認）送出打的字母，注音送出選中的候選。
- 字元鍵在放開時送出；第二指按下時，第一指的鍵立刻送出。點在按鍵之間算最近的鍵。
- 刪除鍵按下先刪一個，0.42 秒後每秒約 12 次，約 2.5 秒後整詞刪除。空白鍵按住 0.4 秒變成游標控制，每 9 pt 移動一個字元；組字中不進游標模式。
- Shift 點一下大寫一個字母，0.3 秒內點兩下鎖定。拼音鎖定大寫時直接輸入大寫字母。
- 按鍵聲用 `UIDevice.playInputClick()`，跟隨系統設定。沒有按鍵震動：iOS 只讓開啟「完整取用」的鍵盤震動。
- 米米莫和莫莫趴在玻璃上緣，每天換一種位置（設定的「位置」可固定一種；規則見 docs/DESIGN.md §1）。兩隻貓會慢慢呼吸；摸米米莫他會縮一下、搖搖頭；摸莫莫她會蹭一下，連摸五下切換繁體和簡體，兩下之間停超過 1.6 秒就重新算。開啟「降低動態效果」時不呼吸，摸貓時只淡一下。
- 開啟「降低透明度」或「增加對比」時，玻璃改為不透明底色，按鍵加外框。
- 鍵盤上的文字和無障礙標籤跟隨目前輸入的繁簡；App 跟隨系統語言。

## 隱私和審核

- 鍵盤的 `RequestsOpenAccess` 是 `false`，不需要「完整取用」，也沒有網路請求。
- App 把使用者改過的設定寫進 App Group 的 `KeyboardSettings.json`，鍵盤只讀。選詞記錄留在鍵盤自己的沙盒，不進 App Group，也不跨裝置同步。清除記錄的請求由鍵盤在下次開啟時執行。
- `Keyboard/PrivacyInfo.xcprivacy` 宣告 UserDefaults、檔案時間戳記和開機時間三類 API。改動鍵盤用到的系統 API 時要一起更新。
- 建好的 App 可用 `python3 scripts/check-ios-privacy.py <Meowpuut.app> [--release]` 檢查。`--release` 會拒絕帶 `get-task-allow` 的開發簽署，請對匯出後的 App 使用。
- App 與鍵盤共用 App Group `group.com.krysta1ll.meowpuut.shared`。換開發團隊時，`AppGroup.entitlements`、`SharedKeyboardSettings.swift` 和 `project.yml` 要一起改。

## 生活照

```sh
python3 scripts/import-photos.py 照片.heic --cats mimimo,momo
```

照片會轉正、縮到長邊 2048 px、存成不含任何中繼資料的 JPEG，並加到 `Photos/photos.json` 最前面。`scripts/test-ios.sh` 會檢查每張照片都在清單裡、尺寸相符、沒有 EXIF 和位置資料。App 裡每張照片都能存到「照片」（只要求加入權限）或分享。

## 截圖

```sh
bash scripts/ios-keyboard-shots.sh <udid> <輸出目錄>
```

不帶其他參數時拍一組標準畫面：淺色深色、三種玻璃造型、中日混打、長候選、注音、日語、符號頁、展開面板、鍵帽氣泡、長按列和橫向。Lab 的啟動參數寫在 `Lab/KeyboardLabApp.swift` 開頭。折疊機有兩個螢幕，用 `SHOT_DISPLAY=1` 指定外螢幕。

宿主 App 的 Debug 版可用 `-openSettings YES` 直接開設定頁，`-openViewer <編號>` 直接開某張照片。

## 貓的圖

`Keyboard/Cats.xcassets` 由 `scripts/render_cats.py` 從素材包複製，不要手改。每隻貓有四張邊緣姿勢（探頭、搭邊、趴著、扶框）。`tests/CatArtTests.swift` 檢查圖和 `CatSpriteData.swift` 的尺寸、錨點、切口是否相符。

## 還沒驗證

模擬器上已編譯、截圖並跑過打字測試。真機上的記憶體用量、觸控手感、按鍵聲和不同 App 裡的表現還要測。
