# 喵撲 Android 測試版

Android 7.0（API 24）以上的系統鍵盤。拼音、注音、候選排序和繁簡輸出共用喵撲的 C++17 核心及 mimo 詞庫。也可用羅馬音輸入日語假名和詞表中的日文單字。鍵盤離線運作，沒有網路權限。

介面沿用 iOS 的鍵位、試打頁、候選展開、雙貓／簡潔皮膚、深淺色與十組色系。玻璃外觀由 Android 的漸層、描邊和透明度繪製。選詞記憶和完整的 iOS 混合語言排序仍待移植。

## 安裝

[直接下載已簽署的 APK](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.4.3-alpha.1/Meowpuut-0.4.3-alpha.1-android.apk)（[發佈資訊與校驗檔](https://github.com/Krysta1ll/Meowpuut/releases/tag/v0.4.3-alpha.1)），安裝後開啟「喵撲」。在「鍵盤設定」啟用並選取喵撲，回到試打頁即可輸入。

0.4.3-alpha.1 使用新的發行簽章。若已裝過 Android 0.1.0，請先記下自己的鍵盤設定，再卸載舊版、安裝新版；卸載會清除喵撲的本機設定，不能直接覆蓋更新。第一次安裝不受影響。

## 0.4.3-alpha.1

這版更新 mimo 詞庫與共用輸入核心，補回單字讀音，修正長句分段後剩餘讀音的處理。拼音輸入到下一個音節時，可以保留前面的分段候選，不必從第一個字重選。也修正換欄位、移動游標和長按時的待送文字處理。

這是公開測試版，尚未完成 Android 真機驗收。

## 建置

準備 JDK 17、Gradle 8.9、Android SDK Platform 35、Build Tools 35.0.0、NDK 27.2.12479018、CMake 3.22.1 和 Python 3。將 `ANDROID_HOME` 指向 Android SDK。詞庫使用隨原始碼附帶的固定轉換資料，不需要安裝 `uconv`。

在儲存庫根目錄執行：

```sh
python3 scripts/compile-native-lexicon.py Android/Generated
gradle -p Android assembleDebug
```

APK 位於 `Android/build/outputs/apk/debug/`。安裝後打開「喵撲」，從「鍵盤設定」進入系統設定，啟用並選取喵撲鍵盤。

## 製作簽署安裝包

使用 JDK 17，設定 `ANDROID_HOME` 與 `GRADLE`（Gradle 執行檔路徑），再提供以下本機環境變數：

- `MEOWPUUT_ANDROID_KEYSTORE`：原有發行金鑰檔案。
- `MEOWPUUT_ANDROID_KEY_ALIAS`：金鑰別名。
- `MEOWPUUT_ANDROID_STORE_PASS_FILE`：只含 keystore 密碼的本機檔案。
- `MEOWPUUT_ANDROID_KEY_PASS_FILE`：金鑰密碼檔案；相同時可省略。

密碼檔與金鑰應保存在儲存庫外，限制為帳號本人可讀。執行 `Android/build-release.sh` 後，簽署 APK 會放在 `.build/releases/<版本>/`。腳本會重建詞庫、編譯 release、檢查簽章與 16 KB ZIP 對齊，最後列出 SHA-256。更新既有安裝必須使用相同簽署金鑰。

## Windows 模擬器

在 Android SDK 中安裝 Android Emulator、Platform Tools 和 `system-images;android-35;default;x86_64`，再從儲存庫根目錄執行：

```powershell
pwsh -File Android/start-test.ps1
```

腳本建立或啟動 `Meowpuut_API_35`，安裝最新建置的 APK，並在該模擬器啟用喵撲和開啟試打頁。若未設定 `ANDROID_HOME`，腳本使用本機的 `.build/android-tools/sdk`。

## 試打

「鍵盤設定 → 按鍵高度」可選標準、加高或特高。鍵盤依目前視窗可用高度收緊，空間足夠時保留所選高度；橫向與分割視窗不再被固定的 312 dp 最低高度撐大。導航列與螢幕缺口留在安全區外，展開候選時維持鍵盤原高度。按鍵列與候選條的縱向點擊範圍至少 44 dp；極矮視窗仍可能需要系統全螢幕編輯區。這些尺寸經幾何測試，尚待 Android 真機確認。

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


## 上屏失敗重試

目前原始碼會保留已選取、但應用程式暫時拒絕接收的文字，顯示「文字尚未送出，點此重試」。點提示或再次按鍵會先重試該次選詞；成功前不寫入剩餘讀音，也不執行舊候選的點擊。若重試前游標已移動，會暫停自動重試；只有點選「游標已移動，點此插入待送文字」才插入目前位置。文字只屬於目前編輯欄位；離開欄位後不會送到另一個應用程式。此保護尚未在 Android 真機驗證；應用關閉或持續拒絕寫入仍可能讓未送出的文字遺失。

可用 JDK 獨立檢查重試、順序與欄位隔離：

```sh
javac -d /tmp/meowpuut-delivery Android/src/main/java/app/meowpuut/android/PendingCommit.java Android/tests/PendingCommitTest.java
java -ea -cp /tmp/meowpuut-delivery app.meowpuut.android.PendingCommitTest
```


版面幾何可在桌面 JDK 檢查，涵蓋 192 組視窗高度、安全區、拼音／注音列數與高度偏好：

```sh
javac -d /tmp/meowpuut-layout Android/src/main/java/app/meowpuut/android/KeyboardLayoutMetrics.java Android/tests/KeyboardLayoutMetricsTest.java
java -ea -cp /tmp/meowpuut-layout app.meowpuut.android.KeyboardLayoutMetricsTest
```


## 編輯位置與長按

在應用程式中清空、貼上或移動游標時，鍵盤會結束舊組字，避免下次按鍵帶回已刪除的讀音。上屏造成的延遲游標回報會與已接受的寫入逐一比對。長按刪除與空格滑動在隱藏、換欄位、重新佈局或視圖移除時取消；空格上的第二根手指也會取消該次手勢。

```sh
javac -d /tmp/meowpuut-selection Android/src/main/java/app/meowpuut/android/EditorSelection.java Android/tests/EditorSelectionTest.java
java -ea -cp /tmp/meowpuut-selection app.meowpuut.android.EditorSelectionTest
```

游標協議已通過桌面模型測試，手勢生命週期已編譯檢查；仍需在 Android 應用中驗證真實事件順序。
