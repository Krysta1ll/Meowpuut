# 喵撲 iOS

包含宿主 App 與自訂鍵盤擴充，共用 C++ 中文核心、Swift 候選排序與詞庫。需要含 iOS 27 SDK 的 Xcode、Python 3 與 XcodeGen。

```sh
brew install xcodegen
bash scripts/build-ios.sh
```

工程由 project.yml 生成，輸出在 .build/iOS。真機安裝需在 Xcode 選擇自己的開發團隊與可用的 Bundle ID；目前沒有公開 TestFlight 或可直接安裝的 IPA。

鍵盤 RequestsOpenAccess=false，輸入不需要完整取用權限。個人詞庫保存在擴充自己的沙盒，不與 macOS 同步。已通過模擬器目標編譯；真機記憶體、觸控、無障礙與跨 App 輸入仍需測試。
