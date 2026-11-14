# 安裝喵撲 Meowpuut

iPhone、iPad 的正式版在 [App Store](https://apps.apple.com/app/id6832432299)。其他平台的正式版與更新說明見 [GitHub Releases](https://github.com/Krysta1ll/Meowpuut/releases)。

- [iPhone、iPad](#iphoneipad)
- [Mac](#mac)
- [Android](#android)
- [Windows](#windows)
- [核對下載的檔案](#核對下載的檔案)

## iPhone、iPad

需要 iOS 或 iPadOS 17 以上。

1. 從 [App Store](https://apps.apple.com/app/id6832432299) 安裝喵撲。
2. 到「設定 → 一般 → 鍵盤 → 鍵盤 → 新增鍵盤」，選「喵撲」。不用打開「允許完整取用」。
3. 在任何可以打字的地方，按住或點地球鍵切到喵撲。

注音、拼音或日語，繁體或簡體，還有玻璃的形狀和配色，都在喵撲 App 的「鍵盤設定」裡改。

移除：在「設定 → 一般 → 鍵盤 → 鍵盤」把喵撲刪掉，再刪掉 App。選詞記錄跟著鍵盤一起刪掉。

## Mac

需要 macOS 27 以上的 Apple 晶片 Mac。Intel Mac 和較舊的 macOS 沒有測過。

下面兩種選一種下載就好：

- [Meowpuut-Sill-26A032-arm64.dmg](https://github.com/Krysta1ll/Meowpuut/releases/download/macos-sill-26a032/Meowpuut-Sill-26A032-arm64.dmg)：紙箱安裝盤，適合首次安裝。
- [Meowpuut-Sill-26A032-arm64.pkg](https://github.com/Krysta1ll/Meowpuut/releases/download/macos-sill-26a032/Meowpuut-Sill-26A032-arm64.pkg)：安裝套件。

### 用安裝盤

打開 `.dmg`，把紙箱裡的 **Meowpuut** 拖到右邊的「輸入法」。這個捷徑指向 `/Library/Input Methods`，不是「應用程式」。Finder 可能要你輸入管理員密碼。如果系統的輸入法資料夾還不存在，改用安裝套件，它會建立資料夾。

**更新舊版時請下載上方的獨立 PKG**。Finder 無法覆蓋正在使用的輸入法。

### 用安裝套件

打開 `.pkg`，安裝到系統磁碟。需要管理員密碼。程式會放在 `/Library/Input Methods/Meowpuut.app`。

### 裝好以後

1. 儲存工作，登出再登入。更新時也要這樣做，舊的輸入法才不會留在記憶體裡。
2. 系統設定 → 鍵盤 → 文字輸入 → 編輯 → 加入 Meowpuut。名稱也可能顯示成「喵撲 Meowpuut」，在繁體中文那一類裡找。
3. 切到 Meowpuut，按 Control＋Option＋2 切到拼音，打 `nihao` 再按 1。預設是注音，按 Control＋Option＋1 切回來。

清單裡找不到輸入法時，先確認裝到了 `/Library/Input Methods`、也已經重新登入過；不要在使用者資料夾和系統資料夾各留一份。

### 沒有公證

這個安裝包沒有用 Developer ID 簽署，也沒有經過 Apple 公證，App 只有本機的 ad-hoc 簽署。從瀏覽器下載後，macOS 可能不讓你打開。

請先用 `SHA256SUMS.txt` 核對檔案（見[下面](#核對下載的檔案)）。決定要試的話，照 [Apple 的說明](https://support.apple.com/en-hk/102445)在系統設定的「隱私權與安全性」允許這一次打開。不要關掉整台電腦的 Gatekeeper 或 SIP。系統還是拒絕的話，請把完整的提示回報給我們；也可以自己從原始碼建置。

紙箱只是 Finder 的安裝畫面，不會繞過 macOS 的檢查。不要直接從 `.dmg` 裡執行輸入法。

### 更新和移除

更新：直接裝新的安裝套件，選詞記錄不會被覆蓋。裝完登出再登入。

移除：先切到 ABC，從輸入來源清單移除 Meowpuut。在 Finder 用「前往檔案夾」打開 `/Library/Input Methods`，把 `Meowpuut.app` 丟到垃圾桶，然後登出。選詞記錄留在 `~/Library/Application Support/Meowpuut/`，確定不要了再自己刪。

## Android

需要 Android 8.0 以上的 64 位元手機或平板。

1. 下載 [Meowpuut-Prism-26A013.apk](https://github.com/Krysta1ll/Meowpuut/releases/download/android-prism-26a013/Meowpuut-Prism-26A013.apk)。
2. **裝過 0.1.0 的話，先把舊的喵撲移除。** 0.8.0 換了簽署用的金鑰，Android 不會讓新的蓋過舊的。
3. 打開下載的 APK。系統可能會先要你允許瀏覽器或檔案 App 安裝未知來源的 App。
4. 打開喵撲 App，照首頁的兩個按鈕：到系統的鍵盤設定開啟「喵撲」（通常在「設定 → 系統 → 鍵盤 → 螢幕鍵盤」，各家手機的位置不太一樣），再用輸入法切換鈕選喵撲。首頁也可以直接試打。

從 0.8.0、0.9.0 或 0.9.1 發布版更新時，可直接安裝新 APK。

## Windows

需要 Windows 10 或 11。

- 一般的電腦（Intel、AMD）：下載 [Meowpuut-Porch-26A002-x64.zip](https://github.com/Krysta1ll/Meowpuut/releases/download/windows-porch-26a002/Meowpuut-Porch-26A002-x64.zip)。
- Arm 處理器的電腦（例如 Snapdragon 筆電）：下載 [Meowpuut-Porch-26A002-arm64.zip](https://github.com/Krysta1ll/Meowpuut/releases/download/windows-porch-26a002/Meowpuut-Porch-26A002-arm64.zip)。它只在原生 Arm64 的程式裡有效，像記事本、Edge、檔案總管、終端機；在模擬執行的 x64 程式裡打不出中文。

整個解壓縮後執行 `Install.cmd`，同意系統要的管理員權限。裝好後按 Windows 鍵＋空白鍵切到「喵撲拼音」或「喵撲注音」。移除用同一個資料夾裡的 `Uninstall.cmd`。這個安裝包沒有簽署，Windows 可能會先跳出警告。

裝錯版本時，安裝程式會先停下來告訴你該下載哪一個。

## 核對下載的檔案

每個版本都附 `SHA256SUMS.txt`。把下載的檔案和它放在同一個資料夾：

```sh
# Mac
shasum -a 256 -c SHA256SUMS.txt --ignore-missing
```

```powershell
# Windows：把算出來的值和 SHA256SUMS.txt 裡同名的那一行比對
certutil -hashfile Meowpuut-Porch-26A002-x64.zip SHA256
```
