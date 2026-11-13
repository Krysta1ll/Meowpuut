# Mac App Store 建置筆記

## macOS：商店整合需先做隔離驗證

現有的獨立發行版透過 PKG 把輸入法安裝至 `/Library/Input Methods/`，並可能需要管理員權限；這條安裝流程**不能直接視為 Mac App Store 安裝流程**。Apple 的 [App Review 2.4.5](https://developer.apple.com/app-store/review/guidelines/) 要求 Mac App Store 應用沙盒化、使用自含的單一 App bundle，並限制向共享位置安裝與提權。[Apple 的沙盒說明](https://developer.apple.com/documentation/xcode/configuring-the-macos-app-sandbox)也將 App Sandbox 列為 Mac App Store 要求。現有 `scripts/build.sh` 使用本地 ad hoc 簽署，且未建立商店發行用的沙盒 target。現有 macOS 構建包沒有隱私清單，而程式使用 `UserDefaults`；商店目標應按實際用途加入並驗證 required-reason 宣告。

本機 macOS 27 SDK 的 `Carbon.framework/Frameworks/HIToolbox.framework/Headers/TextInputSources.h` 對 `TISRegisterInputSource` 的註釋指出，這個 API 註冊 input method bundle 的位置為 `/Library/Input Methods/` 或 `~/Library/Input Methods/`。現有 `Sources/SystemInput.swift` 也只接受這兩個目錄。因此，單純將現有 App 放到 `/Applications` 並沿用這條註冊路徑，沒有得到目前 SDK 註釋的支持。

這些事實**尚不能證明** InputMethodKit 類型的應用一律無法上架，也不能證明尚有可用的商店整合路徑。Apple 的 [InputMethodKit 文檔](https://developer.apple.com/documentation/inputmethodkit)說明文字服務框架，但未在此處提供本項目的 App Store 安裝方案。

建議先在獨立 macOS 測試帳戶或虛擬機，以新的測試 Bundle ID 建立沙盒簽署原型：檢查在合法位置啟動、註冊、列舉、登出再登入後是否仍可用，以及跨 App 組字、候選窗、更新和卸載行為。不得覆蓋現有使用中的輸入法、沿用其 Bundle ID，或把函式回傳成功當作完成啟用。對照結果向 Apple Developer Technical Support／App Review 詢問受支持的架構，再決定商店版如何封裝；獨立發行版可以繼續作為另一條產品路徑。
