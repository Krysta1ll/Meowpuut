# App Store 準備記錄

狀態：開發中，尚未提交 App Store 或完成審核。iOS 真機開發簽署成功不等於已具備發行簽署或已通過審核。

## iOS

- 鍵盤擴充是系統支援的產品形式。Apple 的 [App Review Guidelines 4.4–4.4.1](https://developer.apple.com/app-store/review/guidelines/) 要求鍵盤在不授予完整取用及沒有網絡時仍可使用、可切換至下一個鍵盤，並限制鍵盤活動資料的用途。現有擴充設定 `RequestsOpenAccess=false`；應在真機上逐項驗證，而不只檢查設定值。
- 宿主與擴充使用同一 App Group 傳遞使用者明確修改的設定；個人選詞記錄留在鍵盤自己的沙盒。須驗證不開完整取用時的共享設定讀取、切換回鍵盤後的更新與一次性清除請求，並確認更新與重裝對使用者資料的影響。
- [App Store Review 5.1.1](https://developer.apple.com/app-store/review/guidelines/) 要求 App 內可找到隱私政策，App Store Connect 亦須提供可用的隱私政策 URL 與準確資料使用聲明。目前尚未在專案找到應用內政策連結。上架前應核對實際執行路徑、使用者可關閉與刪除的記憶、第三方元件及系統診斷，再撰寫並提供政策；不因資料僅保存在裝置上而省略政策。
- 擴充已有 `PrivacyInfo.xcprivacy`；目前檢查到的 iOS 開發包只有擴充內有該檔。需以 Release archive 與 Apple 的 [required-reason API 說明](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype) 逐項核對實際用途與檔案放置；不要因加了宣告就假設審核通過。應分別檢查宿主及擴充有無各自需要宣告的 API。
- 已生成的宿主 Info.plist 包含 `UILaunchScreen`；仍需在最終發行 archive 驗證，詳見 [TN3208](https://developer.apple.com/documentation/technotes/tn3208-preparing-your-apps-launch-screen-to-meet-app-store-requirements)。另須驗證 iPhone／iPad 不同尺寸、橫屏、VoiceOver、降低透明度、系統安全輸入欄及多種 App。
- 個人開發簽署可用於裝置開發，但 [TestFlight 與 App Store 發行](https://developer.apple.com/programs/)需要合適的 Apple Developer Program 發行帳戶與 App Store Connect 設定；目前不假設帳戶已具備這些條件。

目前的已簽署真機開發包可用以下命令檢查鍵盤擴充類型、完整取用設定、隱私清單的三組用途及宿主／擴充的 App Group 是否一致：

```sh
python3 scripts/check-ios-privacy.py .build/iOS-device/Build/Products/Debug-iphoneos/Meowpuut.app
```

檢查不會安裝 App 或讀取使用者輸入。加 `--release` 會拒絕仍啟用 `get-task-allow` 的除錯簽署；本機開發包因此會按預期失敗。這不是 App Store 自動驗收，也不能證明沒有其他需要宣告的 API、隱私政策已齊備、App Group 在真機可讀，或最終 archive 符合上架要求。`CA92.1` 對應鍵盤自身的 UserDefaults；`35F9.1` 對應雙擊 Shift 的時間間隔。`C617.1` 的檔案中繼資料使用仍須連同最終二進位及依賴一起核對。

## macOS：商店整合需先做隔離驗證

現有的獨立發行版透過 PKG 把輸入法安裝至 `/Library/Input Methods/`，並可能需要管理員權限；這條安裝流程**不能直接視為 Mac App Store 安裝流程**。Apple 的 [App Review 2.4.5](https://developer.apple.com/app-store/review/guidelines/) 要求 Mac App Store 應用沙盒化、使用自含的單一 App bundle，並限制向共享位置安裝與提權。[Apple 的沙盒說明](https://developer.apple.com/documentation/xcode/configuring-the-macos-app-sandbox)也將 App Sandbox 列為 Mac App Store 要求。現有 `scripts/build.sh` 使用本地 ad hoc 簽署，且未建立商店發行用的沙盒 target。現有 macOS 構建包沒有隱私清單，而程式使用 `UserDefaults`；商店目標應按實際用途加入並驗證 required-reason 宣告。

本機 macOS 27 SDK 的 `Carbon.framework/Frameworks/HIToolbox.framework/Headers/TextInputSources.h` 對 `TISRegisterInputSource` 的註釋指出，這個 API 註冊 input method bundle 的位置為 `/Library/Input Methods/` 或 `~/Library/Input Methods/`。現有 `Sources/SystemInput.swift` 也只接受這兩個目錄。因此，單純將現有 App 放到 `/Applications` 並沿用這條註冊路徑，沒有得到目前 SDK 註釋的支持。

這些事實**尚不能證明** InputMethodKit 類型的應用一律無法上架，也不能證明尚有可用的商店整合路徑。Apple 的 [InputMethodKit 文檔](https://developer.apple.com/documentation/inputmethodkit)說明文字服務框架，但未在此處提供本項目的 App Store 安裝方案。

建議先在獨立 macOS 測試帳戶或虛擬機，以新的測試 Bundle ID 建立沙盒簽署原型：檢查在合法位置啟動、註冊、列舉、登出再登入後是否仍可用，以及跨 App 組字、候選窗、更新和卸載行為。不得覆蓋現有使用中的輸入法、沿用其 Bundle ID，或把函式回傳成功當作完成啟用。對照結果向 Apple Developer Technical Support／App Review 詢問受支持的架構，再決定商店版如何封裝；獨立發行版可以繼續作為另一條產品路徑。
