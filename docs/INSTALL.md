# 安裝喵撲 Meowpuut

首個公開測試包：0.4.0 Alpha 1，支援 macOS 27 以上的 Apple silicon Mac。

## 安裝

1. 從 GitHub Release 下載 `Meowpuut-0.4.0-alpha.1-macos-arm64.pkg`。
2. 開啟安裝包，確認安裝到系統磁碟。需要管理員驗證；程式會放在 `/Library/Input Methods/Meowpuut.app`。
3. 儲存工作後登出，再登入。更新舊版時也請這樣做，避免舊的輸入服務仍留在記憶體。
4. 系統設定 → 鍵盤 → 文字輸入 → 編輯 → 加入 Meowpuut。名稱也可能顯示為「喵撲 Meowpuut」，可在繁體中文分類查找。
5. 切到 Meowpuut，按 Control＋Option＋2 使用拼音，輸入 `nihao` 後按 1。

輸入法沒有出現在清單時，先確認安裝位置與登入狀態；不要同時保留使用者目錄和系統目錄兩份輸入法。預設是注音，可按 Control＋Option＋1 切回。

## 簽署狀態

本測試包未使用 Developer ID Installer 簽署，App 只有本機 ad-hoc 簽署，尚未經 Apple 公證。因此從瀏覽器下載後，macOS 可能阻止開啟。

請先核對來源和 Release 中的 SHA-256。若決定試用，可依 [Apple 的開啟未驗證 App 說明](https://support.apple.com/en-hk/102445)，在系統設定的「隱私權與安全性」處理該次開啟。不要停用整台電腦的 Gatekeeper 或 SIP。若系統仍拒絕，請回報完整提示；也可自行從原始碼建置。

```sh
shasum -a 256 Meowpuut-0.4.0-alpha.1-macos-arm64.pkg
```

目前驗證的是本機安裝與輸入；尚未完成另一台乾淨 Mac 的下載、公證與首次安裝流程驗證。

## 更新與移除

更新可直接安裝新包，個人詞庫不會被覆蓋；完成後登出再登入。

移除時先切換到 ABC，從鍵盤輸入來源清單移除 Meowpuut，再透過 Finder 的「前往檔案夾」開啟 `/Library/Input Methods`，將 `Meowpuut.app` 移到垃圾桶並登出。個人資料會保留在 `~/Library/Application Support/Meowpuut/`；確定不再需要時才另行移除。
