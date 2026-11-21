# 喵撲 Android

Android 版是一個輸入法（`InputMethodService`）加一個設定 App。中文解碼用與 macOS、iOS 相同的 Rust 核心（`Engine/crates/meowpuut-ffi`），詞庫是同一份 `mimo_base.bin`，日語詞表是 `mimo-jp` 編出的 `native-japanese.tsv`；介面依照 [docs/DESIGN.md](../docs/DESIGN.md)。

## 建置

需要：

- Android SDK（`ANDROID_HOME`，預設 `~/Library/Android/sdk`）：platform `android-37.0`、build-tools `37.0.0`、NDK `30.0.16248370`、CMake `3.31.6`
- Rust，加上 `aarch64-linux-android`、`x86_64-linux-android` 兩個 target（腳本會自動加）
- JDK 25 與 Gradle 9（JVM 測試用 JDK 25 的 Unicode 字素分段；`Android/gradlew` 會下載 Gradle；已經裝了 Gradle 9 就設 `MEOWPUUT_GRADLE=gradle`）

```sh
bash scripts/build-android.sh            # Android/app/build/outputs/apk/debug/app-debug.apk
bash scripts/build-android.sh --test     # 同上，再跑 JVM 單元測試
bash scripts/build-android.sh --release  # 輸出到 Android/app/build/outputs/apk/release/
```

其他 Gradle 參數放在 `MEOWPUUT_GRADLE_ARGS`，例如 `MEOWPUUT_GRADLE_ARGS="--offline --max-workers=4"`。檢查程式碼用 `cd Android && ./gradlew lintDebug`。

`scripts/build-android.sh --prepare` 只建置兩個 ABI 的 Rust 靜態庫（`.build/rust/android/<abi>/`），並把詞庫與授權檔放進 `app/src/main/assets/`（這兩個目錄不進版本控制）。Gradle 的 `preBuild` 會先執行它，所以直接跑 `./gradlew assembleDebug` 也可以。詞庫由固定版本的 `Engine` 子模組編譯至 `Engine/.build/lexicon`。

Gradle 不會自己下載 SDK 套件（`android.builder.sdkDownload=false`），缺了就直接報錯。手動解壓的套件要有 `package.xml`，Gradle 才認得。

`--release` 在這台 Mac 有發布金鑰時，會順便簽好 `app-release.apk`：金鑰檔是 `~/.android/meowpuut/meowpuut-release.p12`（別名 `meowpuut`，可用 `MEOWPUUT_ANDROID_KEYSTORE` 指定別的位置），密碼在登入鑰匙圈的「meowpuut-android-release」項目。金鑰和密碼都不進版本控制；之後每一版都要用同一把簽，弄丟了，已安裝的人就得先移除才能更新，所以要另外備份。2026-11-12 起用這把新金鑰；GitHub 上 0.1.0 那一版是另一把已經找不到的金鑰簽的，裝過 0.1.0 的人要先移除舊版。

沒有發布金鑰時，release APK 不簽。要裝到裝置上試，先用除錯金鑰簽：

```sh
"$ANDROID_HOME/build-tools/37.0.0/apksigner" sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out app-release-debugsigned.apk Android/app/build/outputs/apk/release/app-release-unsigned.apk
```

## 百爪典藏

鍵盤衣櫥的免費銷量破百紀念皮膚。淺色為奶油瓷白與香檳金，深色為午夜藍與暖金，維持原有鍵盤高度和觸控範圍。空白鍵有不與置中字樣重疊的小型 `100` 紀念章，窄鍵會自動省略。

米米莫換上象牙白禮服背心，莫莫穿午夜藍披肩；八張專屬全身圖包含迎賓、鞠躬、舉爪歡呼和抱章休息。輕摸小貓會鞠躬，選詞送出時雙貓短暫歡呼，連續打字會節制觸發。系統關閉動畫或選擇輕量效果時，服裝保留、旋轉和伸縮停止。紀念配色與膠囊造型固定，色調、玻璃透明度與小貓開關仍可調整。

## 字景

鍵盤衣櫥的免費皮膚，不需答題或兌換。整個鍵盤是一幅用注音、拼音和假名排出來的風景，一共十二個地方（山野、海、街、竹林、雪山、富士、香港、火山、沙漠、紅葉，另有兩個很少遇見的：月，以及更少見的月球，那裡能看見全彩的地球，兩隻貓穿著太空衣），米米莫和莫莫走在裡面；每個地方多了一種自己的顏色（朱紅仍是大家共用的）。貓有了背對著看風景、母雞蹲的姿勢，和愛心眼、暈、生氣、眨單眼、打呵欠等表情；米米莫換成更大一點的新樣子。淺色是紙與墨，深色是夜；外觀可選淺色、深色或跟著系統。配色固定，設定裡沒有配色選項。畫面不增加鍵盤高度、不改變任何觸控範圍；候選列、注音與符號鍵面用像素字型（Cubic 11、Fusion Pixel 12px，SIL OFL 1.1，見 `THIRD_PARTY.md` 第 10 節；子集化腳本 `scripts/subset_glyph_fonts.py`）。

呼出鍵盤時，鍵和候選立刻出現，畫面在七格（每秒十二格）內打字成形，貓最後出現；系統關閉動畫、開啟省電模式、選輕量效果、開啟高對比（改成一張紙加一條墨線）或使用浮動鍵盤時沒有這段，兩秒內重新呼出也不重演。畫面在最後一次按鍵約二十秒後停止重繪。打得越快世界越激動：風帶變密變快，貓改用跑的並揚起字塵，連續高速打字幾秒後一陣由世界自己的字組成的風暴橫掃天空；沒有在組字時送出一行，夜裡在地平線上放一朵由字組成的煙火，白天是一群鳥從地面驚起。候選列有候選字時，一兩隻鳥會停在候選字的上緣，候選一換就驚飛；落在太陽或月亮上的字和箭頭是從圓盤裡鏤空成紙色的；街的每顆鍵是一塊招牌，夜裡亮著、偶爾有一塊閃一下；候選列的表情符號縮到和字同一種像素格，保留彩色。這些在「標準」以上的效果檔、畫面會動時出現（輕量檔沒有）。畫面在背景執行緒繪製，不擋按鍵。字景只記住一個累計的「走過的距離」數字，不記打過的字，也不連網。

Debug 建置另有「客串小螃蟹」開關（預設關閉，release 建置沒有這個設定），以及固定停在某個地方、固定在某個成形瞬間的除錯設定。

## 自訂皮膚

開發者簽章並加密的 `.mpskin`（規格 `plans/custom-skins.md` v2，只做 Android）。設定 App 首頁「自訂皮膚 ›」：產生兌換 ID 傳給開發者、用檔案選擇器（或「以喵撲開啟」／分享）開啟收到的 `.mpskin`、套用、移除；`.mprevoke` 也從同一個選擇器匯入。驗章、解密、驗證器與安裝在 Rust 核心（`Engine` 的 `skin` 功能，經 `SkinNative` JNI）；請求金鑰與單調狀態存在 `filesDir/skinvault/`（Keystore AES-GCM 加密），已安裝的皮膚在 `filesDir/skins/<id>/<rev>/`。輸入法每次套用時在背景執行緒先驗章再讀圖與聲音（`CustomSkinLoader`），任何失敗都靜默退回內建皮膚；按鍵路徑不碰皮膚檔案（`SkinPathTest` 檢查原始碼）。

- Debug 建置用 `skin-test-keys`，信任公開的 `test-` 測試金鑰；Release 建置用 `skin` 並設 `MEOWPUUT_RELEASE_BUILD=1`，函式庫內沒有測試根金鑰（建置腳本會檢查），執行時也會因 `acceptsTestKeys` 而拒絕載入。
- Rust 函式庫依建置類型放在 `.build/rust/android/<debug|release>/<abi>/`。
- 版一的限制：圖框（`cats.trim`）與 `*_side_hold` 圖 Android 不畫；`cats.seating: edges` 只在 `base: classic` 生效。

## 安裝與啟用

```sh
adb install -r Android/app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable com.krysta1ll.meowpuut.android/.ime.MeowpuutIME
adb shell ime set com.krysta1ll.meowpuut.android/.ime.MeowpuutIME
```

在手機上：設定 → 系統 → 鍵盤 → 螢幕鍵盤，開啟「喵撲」，再用輸入法切換鈕選它。喵撲 App 首頁有這兩個步驟的按鈕，也能直接試打。

## 結構

| 位置 | 內容 |
| --- | --- |
| `app/src/main/cpp/meowpuut_jni.c` | JNI 橋接，直接呼叫 `meowpuut.h` 的 C ABI；字串以 UTF-16 傳遞，擴充 B 區漢字不會損壞 |
| `engine/` | `Composer`（拼音、注音、日語羅馬字組字）、候選排序、詞庫載入 |
| `ime/Geometry.kt`、`GlassOutline.kt` | 與 iOS `KeyboardGeometry.swift`、`GlassShapes.swift` 相同的版面與玻璃造型計算 |
| `ime/KeyTouches.kt`、`KeyStates.kt` | 每根手指按在哪個鍵、放開時打出什麼：無死角的觸控區、多指連打、刪除鍵加速、長按空白鍵移游標、從 Shift 或符號鍵滑到字元、Shift 連按大寫鎖定、長按替代字。不依賴 Android，由單元測試直接驅動 |
| `ime/KeysView.kt` | 按鍵繪製，把觸控交給 `KeyTouches` |
| `ime/CandidateBarView.kt` | 玻璃候選列、米米莫與莫莫、單行候選、聯想詞與快速標點 |
| `ime/ExpandedPanelView.kt` | 展開後的候選面板 |
| `app/SettingsActivity.kt` | 啟用步驟、試打、輸入與外觀設定 |

`app/src/test` 的測試不需要裝置：版面與觸控區（`GeometryTest`）、候選排序與 Shift、刪除、節日清單的規則（`LogicTest`）、照腳本按鍵的手指（`TouchTest`）。

## 與 iOS、macOS 的差異

- Android 沒有讓輸入法讀取背後畫面的即時模糊，玻璃是模擬的：半透明底、內光、虹彩焦散與邊框光。米米莫和莫莫在玻璃上緣兩端探頭（docs/DESIGN.md §1 的「兩端探頭」），身體畫在玻璃外，爪子畫在玻璃前。
- App 的「輸入 → 自訂詞庫」可新增、編輯和刪除詞條。完整讀音相符時，自訂詞條會在候選前方，照儲存的原文輸入。支援全鍵拼音、九鍵拼音、注音和日語羅馬字。詞庫留在這台裝置，與選詞記憶分開儲存。
- 選詞記憶會把反覆選過的中文候選往前排，選取次數相同時優先最近選過的詞。可在 App 的「輸入」清除記錄。
- 英文（English 鍵）：候選列有補完、拼字更正和下一個字，來自核心的 `mimo_en.bin`（放在 assets/lexicon，不壓縮，直接映射）。從九鍵拼音切到英文時會使用 26 鍵 QWERTY。
- 系統的對比設定為中或高時（Android 14 起），玻璃改成不透明，對應 iOS 的「增加對比」。
- 震動預設關閉。在 App 的「按鍵回饋」開啟後可選輕柔、標準或強，並遵循 Android 的系統觸感設定。按鍵聲也預設關閉，可在同一處開啟。
- 貓的動作時間跟著系統的「動畫時間比例」（開發人員選項）伸縮，設為關閉時貓不跳、不晃。
- 長按字母的替代字列在鍵盤右半邊會從右排到左，第一個替代字永遠在手指正上方。

## 驗證狀況

在模擬器上（Android 37、arm64、軟體繪圖 swiftshader，1080×2400、420 dpi）實際點按鍵確認過：

- 中日混打是設定 App「輸入」裡的一個開關（「中日混打」），新安裝預設關閉；舊版就有設定的人升級後維持開啟。開關改了，下一次打字就生效，不用重啟鍵盤。第一次執行時，輸入模式和繁簡依系統語言和地區決定（`InputDefaults`，只在沒有存過模式和繁簡時用，只存結果、不存地區）。
- 開啟後，拼音打 `nihao`、`sushi`：寿司 帶貓掌記號排在第三個，不用展開就看得到；長的中日混合候選從中間省略，始終一行。
- 注音、日語、英文切換、Shift 與大寫鎖定、兩頁符號、展開面板、長按刪除連刪、長按空白鍵移游標、長按字母的替代字。
- 摸莫莫五下切換簡繁，摸米米莫的跳動與小圖示。
- 膠囊、波浪、雲朵三種造型的淺色與深色；手機橫向；360、432 dp 寬的手機；800 dp 寬的平板；手勢導覽與三按鈕導覽的底部留白。
- release 版（R8 壓縮）裝上後拼音、注音、日語都能打，JNI 沒有被移除。
- 整段操作 logcat 沒有當機或例外。

還沒驗證：

- 實機。模擬器用軟體繪圖，每格畫面的 GPU 時間約 28 ms，所以 `dumpsys gfxinfo` 的卡頓比例在這裡沒有參考價值；主執行緒錄製一格約 0.3 ms。
- 記憶體：輸入法程序的 PSS 約 160 MB，其中原生堆積約 106 MB，因為核心把詞庫從 APK 複製到記憶體。要降低得在 Rust 核心加上直接映射詞庫的介面。
- TalkBack 實際朗讀、實體鍵盤、分割畫面與摺疊機。

## 第一行字（新手引導）

每台裝置第一次打開 App 時自動出現一次的引導（新安裝與升級上來的人都一樣，可跳過）：六幕，沒有「下一步」，做了該做的事才會前進（啟用鍵盤 → 太陽升起；在輸入線上打字、選字 → 兩隻貓出現；選一條路 → 那就是輸入方式）。規格與原型在 `design-preview/onboarding/`（工作區根目錄，不在這個倉庫）。

- 進入點：`app/OnboardingActivity`。`SettingsActivity.onCreate` 只有一行掛接（`Onboarding.due(...)` 為真就先開它）；設定頁最下方有「再看一次「第一行字」」。
- 自動出現只看這台裝置有沒有決定過：沒有記錄就出現並記成 `due`，走完、按「跳過」或返回鍵記成 `done`，之後不再自動出現。新來的人與已經在用的人只決定走法（設定裡的 `onboardingKind`：沒更新過、也沒有 `cat_affinity`／`choice-memory` 檔是 `new`，其餘是 `using`；「再看一次」一律走 `using`）。已經在用的人：輸入法已啟用就自己日出、喵撲已是目前輸入法就直接能打；第四幕現在用的那條路有記號，走那條不寫任何設定，走別條才改輸入方式；不點「鍵盤就穿字景」就不動皮膚。
- 引導被系統結束（去系統設定時行程被回收等）後接著走：進度（第幾幕、走了哪條路、時間）存在 `onboardingProgress`，30 分鐘內再打開就從那一幕「剛回來」接著走（設定頁在 `due` 或有新鮮進度時開引導）；走完、跳過、返回時清掉，「再看一次」從頭。
- 引導在畫面上時鍵盤暫時穿字景（設定裡的 `onboardingSkinUntil`，有期限；離開畫面、`Application.onCreate`（新行程不可能有引導在畫面上）都會清掉）；使用者選的皮膚不動，除非最後點了「鍵盤就穿字景」。
- 留下來的只有：`onboarding`、`onboardingKind`、進行中的 `onboardingProgress`、選的輸入方式（`mode`，已在用的那條不寫）、點了才有的 `skin`。輸入線上打的字不讀內容（只看長度與是否在組字），離開那一幕就清空。
- 程式在 `app/onboarding/`：`OnboardingState`（不碰畫面的狀態機）、`OnboardingScape`（整頁地景）、`OnboardingPainter`（路牌、牌子、木門、貓、點陣字旁白）、`OnboardingView`（背景執行緒每秒 12 格）、`Onboarding`（何時出現、暫穿皮膚）。只用到字景繪製最底層的東西（雜訊、字池、畫布、貓的像素、點陣字），沒有改 `ime/glyph/`。
- 測試：`OnboardingTest`（何時出現、暫穿皮膚的期限、不讀打字內容、六幕走得完、繁簡文案齊全）。`ONBOARDING_PREVIEW_DIR=/某個資料夾 ./gradlew :app:testDebugUnitTest --tests "*OnboardingPreviewTool*"` 會在桌機上把每一幕畫成 PNG。
