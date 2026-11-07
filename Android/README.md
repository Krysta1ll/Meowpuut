# 喵撲 Android 測試版

Android 7.0（API 24）以上的系統鍵盤。拼音、注音、候選排序和繁簡輸出共用喵撲的 C++17 核心及 mimo 詞庫。也可用羅馬音輸入日語假名和詞表中的日文單字。鍵盤離線運作，沒有網路權限。

介面沿用 iOS 的鍵位、試打頁、候選展開、雙貓／簡潔皮膚、深淺色與十組色系。玻璃外觀由 Android 的漸層、描邊和透明度繪製。選詞記憶和完整的 iOS 混合語言排序仍待移植。

## 安裝

[直接下載已簽署的 APK](https://github.com/Krysta1ll/Meowpuut/releases/download/android-v0.1.0-alpha/Meowpuut-0.1.0-alpha-android.apk)（[發佈資訊與校驗檔](https://github.com/Krysta1ll/Meowpuut/releases/tag/android-v0.1.0-alpha)），安裝後開啟「喵撲」。在「鍵盤設定」啟用並選取喵撲，回到試打頁即可輸入。

## 建置

準備 JDK 17、Gradle 8.9、Android SDK Platform 35、Build Tools 35.0.0、NDK 27.2.12479018、CMake 3.22.1，以及 Python 3 和 ICU 的 `uconv`。將 `ANDROID_HOME` 指向 Android SDK。Windows 可在 WSL 裝 `icu-devtools` 產生詞庫，接著用 Windows 的 Android SDK 建置。

在儲存庫根目錄執行：

```sh
python3 scripts/compile-native-lexicon.py Android/Generated --uconv
gradle -p Android assembleDebug
```

APK 位於 `Android/build/outputs/apk/debug/`。安裝後打開「喵撲」，從「鍵盤設定」進入系統設定，啟用並選取喵撲鍵盤。

## Windows 模擬器

在 Android SDK 中安裝 Android Emulator、Platform Tools 和 `system-images;android-35;default;x86_64`，再從儲存庫根目錄執行：

```powershell
pwsh -File Android/start-test.ps1
```

腳本建立或啟動 `Meowpuut_API_35`，安裝最新建置的 APK，並在該模擬器啟用喵撲和開啟試打頁。若未設定 `ANDROID_HOME`，腳本使用本機的 `.build/android-tools/sdk`。

## 試打

「鍵盤設定 → 按鍵高度」可選標準、加高或特高，橫向螢幕使用標準高度以保留編輯空間。

- 新安裝預設使用簡體拼音。切換注音後，依序點 `ㄋ ㄧ ˇ ㄏ ㄠ ˇ`，選「你好」。注音的空格鍵在組字時可標一聲或選詞。
- 長按底列的 ABC／語言鍵，直接選擇注音、日語或拼音。拼音輸入 `nihao` 可按空格提交首選「你好」。前綴候選旁會顯示對應拼音，選取後可繼續轉換剩餘讀音。
- 長按 ABC／語言鍵並從選單切換繁簡，用拼音輸入 `youhua`，選「優化」或「优化」。
- 日語輸入 `sushi` 可選「寿司」、平假名或片假名。拼音模式識別詞庫中的日語片段後，中文可完整解碼時優先顯示中文候選，手動指定日語片段後則以指定寫法為首選。含日語的候選會加上貓爪標記。收合時最多顯示一項，其他日語候選會和低置信中文候選交錯出現在展開清單。長按該候選可選其他寫法，只替換日語片段，整句會留在組字區。
- 點候選條右側箭頭展開，展開後可翻頁。長按空格滑動可移動游標，長按退格可連續刪除。
- 密碼、網址、電郵及數字欄位使用直接輸入。

## 核心冒煙測試

生成詞庫後，使用桌面端的 C++17 編譯器執行：

```sh
c++ -std=c++17 -ICore Core/MeowpuutCore.cpp Android/smoke.cpp -o Android/Generated/smoke
Android/Generated/smoke
```
