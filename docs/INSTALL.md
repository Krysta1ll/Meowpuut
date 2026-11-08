## 紙箱安裝盤（0.4.1 起）

打開 `.dmg`，把紙箱裡的 **Meowpuut** 拖到右側「輸入法」。這個捷徑指向 `/Library/Input Methods`，不是「應用程式」。Finder 可能要求管理員驗證；**更新時請使用「安裝或更新.pkg」**，Finder 不能覆蓋正在使用的輸入法。

複製完成後儲存工作、登出再登入，再依下方步驟加入輸入來源。更新不會移除本機選詞記憶。安裝盤內也有「安裝或更新.pkg」，與單獨提供的 pkg 相同；兩種方式選一種即可。若系統輸入法資料夾尚不存在，請使用 pkg，它會建立資料夾。

安裝盤與安裝包尚未公證；紙箱只是 Finder 的安裝介面，不會繞過 macOS 的驗證。不要直接從 DMG 啟動輸入法，也不要停用 Gatekeeper。

# 安裝喵撲 Meowpuut

目前測試包：0.4.3 Alpha 1，支援 macOS 27 以上的 Apple silicon Mac。

## 安裝

1. 從 GitHub Release 下載 `Meowpuut-0.4.3-alpha.1-macos-arm64.pkg`。
2. 開啟安裝包，確認安裝到系統磁碟。需要管理員驗證；程式會放在 `/Library/Input Methods/Meowpuut.app`。
3. 儲存工作後登出，再登入。更新舊版時也請這樣做，避免舊的輸入服務仍留在記憶體。
4. 系統設定 → 鍵盤 → 文字輸入 → 編輯 → 加入 Meowpuut。名稱也可能顯示為「喵撲 Meowpuut」，可在繁體中文分類查找。
5. 切到 Meowpuut，按 Control＋Option＋2 使用拼音，輸入 `nihao` 後按 1。

輸入法沒有出現在清單時，先確認安裝位置與登入狀態；不要同時保留使用者目錄和系統目錄兩份輸入法。預設是注音，可按 Control＋Option＋1 切回。

## 簽署狀態

本測試包未使用 Developer ID Installer 簽署，App 只有本機 ad-hoc 簽署，尚未經 Apple 公證。因此從瀏覽器下載後，macOS 可能阻止開啟。

請先核對來源和 Release 中的 SHA-256。若決定試用，可依 [Apple 的開啟未驗證 App 說明](https://support.apple.com/en-hk/102445)，在系統設定的「隱私權與安全性」處理該次開啟。不要停用整台電腦的 Gatekeeper 或 SIP。若系統仍拒絕，請回報完整提示；也可自行從原始碼建置。

```sh
shasum -a 256 Meowpuut-0.4.3-alpha.1-macos-arm64.pkg
```

目前驗證的是本機安裝與輸入；尚未完成另一台乾淨 Mac 的下載、公證與首次安裝流程驗證。

## 更新與移除

更新可直接安裝新包，個人詞庫不會被覆蓋；完成後登出再登入。

移除時先切換到 ABC，從鍵盤輸入來源清單移除 Meowpuut，再透過 Finder 的「前往檔案夾」開啟 `/Library/Input Methods`，將 `Meowpuut.app` 移到垃圾桶並登出。個人資料會保留在 `~/Library/Application Support/Meowpuut/`；確定不再需要時才另行移除。
