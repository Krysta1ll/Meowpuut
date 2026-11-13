# 核心、詞庫和客戶端

這份是拆分提案。現行 Rust 引擎在 [Meowpuut](https://github.com/Krysta1ll/Meowpuut) 的 `crates/`，獨立的 [Meowpuut-Core](https://github.com/Krysta1ll/Meowpuut-Core) 保存早期 C++ 版。

| 部分 | 建議 |
| --- | --- |
| Rust 核心、C 介面、詞庫建置、可再分發的詞庫和評測 | 一起在 Meowpuut-Core 開源。程式沿用 MIT，第三方資料附原授權和來源。 |
| Android、macOS、Windows | 放在同一個公開客戶端倉庫，便於共同修相容性問題。三個平台可以各自打包。 |
| iOS／iPadOS App、鍵盤擴充與發行設定 | 未來的客戶端放在私人倉庫，照樣使用同一份公開核心和詞庫。 |
| 小貓、標誌和皮膚 | 按素材的授權決定是否公開或允許再分發。社群作品納入前先和作者確認使用範圍。[美術素材授權](../ASSETS-LICENSE.md) |

客戶端固定使用一個已發行的核心版本與詞庫快照。輸入、候選、選字和分段上屏由核心提供，按鍵、視覺、系統整合和各平台發行由客戶端處理。改一個平台的鍵帽，不需要跟著發四個平台。

先讓現行 Rust 版在核心倉庫能獨立建置、跑測試並保留資料來源聲明。再讓四端切到同一個核心標籤，逐端比對打字結果。最後才安排 iOS 後續開發搬遷。現有公開下載和回報入口由 Meowpuut 繼續提供。已公開的 iOS 原始碼及其公開 fork 可能繼續流通。[GitHub 可見性說明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility)
