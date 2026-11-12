# 安裝喵撲 Meowpuut

目前的版本：iPhone、iPad 是 TestFlight 0.8.0（9）；Mac、Android、Windows 是 [0.8.0 Alpha 1](https://github.com/Krysta1ll/Meowpuut/releases/tag/v0.8.0-alpha.1)。都還是測試版。

- [iPhone、iPad](#iphoneipad)
- [Mac](#mac)
- [Android](#android)
- [Windows](#windows)
- [核對下載的檔案](#核對下載的檔案)

## iPhone、iPad

需要 iOS 或 iPadOS 17 以上。

1. 從 App Store 安裝 Apple 的 TestFlight。
2. 在 iPhone 或 iPad 上打開 <https://testflight.apple.com/join/kfMVXAC6>，在 TestFlight 裡按「接受」，再按「安裝」。
3. 到「設定 → 一般 → 鍵盤 → 鍵盤 → 新增鍵盤」，選「喵撲」。不用打開「允許完整取用」。
4. 在任何可以打字的地方，按住或點地球鍵切到喵撲。

注音、拼音或日語，繁體或簡體，還有玻璃的形狀和配色，都在喵撲 App 的「鍵盤設定」裡改。

移除：在「設定 → 一般 → 鍵盤 → 鍵盤」把喵撲刪掉，再刪掉 App。選詞記錄跟著鍵盤一起刪掉。

## Mac

需要 macOS 27 以上的 Apple 晶片 Mac。Intel Mac 和較舊的 macOS 沒有測過。

下載下面兩種其中一種，選一種裝就好：

- [Meowpuut-0.8.0-alpha.1-macos-arm64.dmg](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.8.0-alpha.1/Meowpuut-0.8.0-alpha.1-macos-arm64.dmg)：紙箱安裝盤，裡面也有一份「安裝或更新.pkg」。
- [Meowpuut-0.8.0-alpha.1-macos-arm64.pkg](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.8.0-alpha.1/Meowpuut-0.8.0-alpha.1-macos-arm64.pkg)：安裝套件。

### 用安裝盤

打開 `.dmg`，把紙箱裡的 **Meowpuut** 拖到右邊的「輸入法」。這個捷徑指向 `/Library/Input Methods`，不是「應用程式」。Finder 可能要你輸入管理員密碼。如果系統的輸入法資料夾還不存在，改用安裝套件，它會建立資料夾。

**更新舊版時請用「安裝或更新.pkg」**：Finder 不能覆蓋正在使用的輸入法。

### 用安裝套件

打開 `.pkg`，安裝到系統磁碟。需要管理員密碼。程式會放在 `/Library/Input Methods/Meowpuut.app`。

### 裝好以後

1. 儲存工作，登出再登入。更新時也要這樣做，舊的輸入法才不會留在記憶體裡。
2. 系統設定 → 鍵盤 → 文字輸入 → 編輯 → 加入 Meowpuut。名稱也可能顯示成「喵撲 Meowpuut」，在繁體中文那一類裡找。
3. 切到 Meowpuut，按 Control＋Option＋2 切到拼音，打 `nihao` 再按 1。預設是注音，按 Control＋Option＋1 切回來。

清單裡找不到輸入法時，先確認裝到了 `/Library/Input Methods`、也已經重新登入過；不要在使用者資料夾和系統資料夾各留一份。

### 沒有公證

這個測試版沒有用 Developer ID 簽署，也沒有經過 Apple 公證，App 只有本機的 ad-hoc 簽署。從瀏覽器下載後，macOS 可能不讓你打開。

請先用 `SHA256SUMS.txt` 核對檔案（見[下面](#核對下載的檔案)）。決定要試的話，照 [Apple 的說明](https://support.apple.com/en-hk/102445)在系統設定的「隱私權與安全性」允許這一次打開。不要關掉整台電腦的 Gatekeeper 或 SIP。系統還是拒絕的話，請把完整的提示回報給我們；也可以自己從原始碼建置。

紙箱只是 Finder 的安裝畫面，不會繞過 macOS 的檢查。不要直接從 `.dmg` 裡執行輸入法。

### 更新和移除

更新：直接裝新的安裝套件，選詞記錄不會被覆蓋。裝完登出再登入。

移除：先切到 ABC，從輸入來源清單移除 Meowpuut。在 Finder 用「前往檔案夾」打開 `/Library/Input Methods`，把 `Meowpuut.app` 丟到垃圾桶，然後登出。選詞記錄留在 `~/Library/Application Support/Meowpuut/`，確定不要了再自己刪。

## Android

需要 Android 8.0 以上的 64 位元手機或平板。

1. 下載 [Meowpuut-0.8.0-alpha.1-android.apk](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.8.0-alpha.1/Meowpuut-0.8.0-alpha.1-android.apk)。
2. **裝過 0.1.0 的話，先把舊的喵撲移除。** 0.8.0 換了簽署用的金鑰，Android 不會讓新的蓋過舊的。
3. 打開下載的 APK。系統可能會先要你允許瀏覽器或檔案 App 安裝未知來源的 App。
4. 打開喵撲 App，照首頁的兩個按鈕：到系統的鍵盤設定開啟「喵撲」（通常在「設定 → 系統 → 鍵盤 → 螢幕鍵盤」，各家手機的位置不太一樣），再用輸入法切換鈕選喵撲。首頁也可以直接試打。

這一版之後都用同一把金鑰簽，以後更新不用再移除。

## Windows

需要 Windows 10 或 11，x64。

下載 [Meowpuut-0.8.0-alpha.1-windows-x64.zip](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.8.0-alpha.1/Meowpuut-0.8.0-alpha.1-windows-x64.zip)，整個解壓縮後照裡面的說明安裝。這個測試版沒有簽署，Windows 可能會先跳出警告。

## 核對下載的檔案

每個版本都附 `SHA256SUMS.txt`。把下載的檔案和它放在同一個資料夾：

```sh
# Mac
shasum -a 256 -c SHA256SUMS.txt --ignore-missing
```

```powershell
# Windows：把算出來的值和 SHA256SUMS.txt 裡同名的那一行比對
certutil -hashfile Meowpuut-0.8.0-alpha.1-windows-x64.zip SHA256
```
