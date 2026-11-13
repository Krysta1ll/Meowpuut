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
- 沒有選詞記憶與個人詞庫，也沒有 macOS 的英文辨識資料庫；拼音輸入英文單字時，原始字母排在第二個候選。
- 系統的對比設定為中或高時（Android 14 起），玻璃改成不透明，對應 iOS 的「增加對比」。
- 震動使用系統的鍵盤觸感，系統關掉觸感時就不震；App 裡另有開關。iOS 因為需要「完整取用」才能震動，拿掉了這個開關，Android 沒有這個限制。按鍵聲預設關閉，可在 App 裡開啟。
- 貓的動作時間跟著系統的「動畫時間比例」（開發人員選項）伸縮，設為關閉時貓不跳、不晃。
- 長按字母的替代字列在鍵盤右半邊會從右排到左，第一個替代字永遠在手指正上方。

## 驗證狀況

在模擬器上（Android 37、arm64、軟體繪圖 swiftshader，1080×2400、420 dpi）實際點按鍵確認過：

- 拼音打 `nihao`、`sushi`：寿司 帶貓掌記號排在第三個，不用展開就看得到；長的中日混合候選從中間省略，始終一行。
- 注音、日語、英文切換、Shift 與大寫鎖定、兩頁符號、展開面板、長按刪除連刪、長按空白鍵移游標、長按字母的替代字。
- 摸莫莫五下切換簡繁，摸米米莫的跳動與小圖示。
- 膠囊、波浪、雲朵三種造型的淺色與深色；手機橫向；360、432 dp 寬的手機；800 dp 寬的平板；手勢導覽與三按鈕導覽的底部留白。
- release 版（R8 壓縮）裝上後拼音、注音、日語都能打，JNI 沒有被移除。
- 整段操作 logcat 沒有當機或例外。

還沒驗證：

- 實機。模擬器用軟體繪圖，每格畫面的 GPU 時間約 28 ms，所以 `dumpsys gfxinfo` 的卡頓比例在這裡沒有參考價值；主執行緒錄製一格約 0.3 ms。
- 記憶體：輸入法程序的 PSS 約 160 MB，其中原生堆積約 106 MB，因為核心把詞庫從 APK 複製到記憶體。要降低得在 Rust 核心加上直接映射詞庫的介面。
- TalkBack 實際朗讀、實體鍵盤、分割畫面與摺疊機。
