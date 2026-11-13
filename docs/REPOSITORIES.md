# 核心、詞庫和客戶端

| 部分 | 維護位置 |
| --- | --- |
| Rust 核心、C 介面、詞庫與評測 | [Meowpuut-Core](https://github.com/Krysta1ll/Meowpuut-Core)，程式採 MIT 授權，第三方詞庫保留原授權和來源。 |
| Android、macOS、Windows | 此公開倉庫。各平台獨立建置與發行。 |
| iOS／iPadOS App、鍵盤擴充與發行設定 | 私人客戶端倉庫。 |
| 小貓、標誌和皮膚 | 按素材授權使用。社群作品納入前先和作者確認使用範圍。[美術素材授權](../ASSETS-LICENSE.md) |

客戶端透過 `Engine` Git 子模組固定同一份引擎和詞庫。更新引擎時一起更新子模組的提交，並把該提交記在發行資訊裡。

[下載](https://github.com/Krysta1ll/Meowpuut/releases)和[回報](https://github.com/Krysta1ll/Meowpuut/issues/new/choose)仍在這裡。舊版 iOS 原始碼曾經公開，原有 Git 歷史與 fork 可保有當時的版本。
