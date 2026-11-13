# 喵撲的版本怎麼看

目前可下載的版本沿用原有名稱，版本與安裝檔見[下載頁](../README.md#下載)。下方是「窗邊」系列的首批構建號。

版本顯示為「**名詞 · 構建號**」，例如 `稜鏡 · 12`。每個大版本會為 iOS、Android、macOS、Windows 各選一個新名詞，同一輪的四個名字取自同一個主題。小更新沿用原名，只增加各平台自己的構建號。構建號按平台遞增，改名也接著往下數。

| 平台 | 第一輪「窗邊」 | 首批構建 |
| --- | --- | --- |
| iOS／iPadOS | 露珠 Dew | 露珠 · 15 |
| Android | 稜鏡 Prism | 稜鏡 · 12 |
| macOS | 窗臺 Sill | 窗臺 · 32 |
| Windows | 門廊 Porch | 門廊 · 2 |

米米莫和莫莫還是角色名。版本名取自牠們生活裡的物件或地方。下一輪若用「夜遊」，可以從燈籠、彗星、月亮、小巷挑出另一組。

## 發行與回報

- 發行標題和 App 版本資訊使用「名詞 · 構建號」。下載表把平台放在第一欄，發行說明標明測試版或正式版。
- 每個平台有自己的 GitHub 標籤，例如 `android-prism-12`。檔案名包含平台，方便下載後辨認，例如 `Meowpuut-Android-Prism-12.apk`。iOS 以 TestFlight 或 App Store 提供安裝。
- Apple 套件仍使用數字版本和構建欄位，名稱放在 App 內和發行說明。Android 的數字 `versionCode` 照常遞增，可以在 `versionName` 顯示命名版本。[Apple 版本欄位](https://developer.apple.com/help/glossary/version-number/) · [Android 版本欄位](https://developer.android.com/studio/publish/versioning)
- 回報範例為「Android／稜鏡 · 12／Android 15／GitHub APK」。比較舊版時照抄完整版本，例如 `0.8.0 Alpha 1`。每次發行記錄使用的核心版本、詞庫快照和來源提交。
