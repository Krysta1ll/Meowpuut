# 參與喵撲

一組能重現的按鍵，或一張候選框的草圖，都可以拿來討論。

回報問題時，請附上裝置型號、系統版本、喵撲的完整版本和安裝來源。若問題只在某個 App 發生，也請寫下 App 名稱。輸入問題可附模式、繁簡、原始按鍵、預期和實際候選。聲音或震動問題可附相關開關，以及最後一個正常版本。截圖請先遮掉私人內容。[回報問題](https://github.com/Krysta1ll/Meowpuut/issues/new/choose)

詞庫修訂放在 `Resources/Lexicon/`。提交時附上讀音和使用例，改動候選排序時也請附一個可以重現的例子。[引擎與詞庫](docs/ENGINE.md)

現行 Rust 引擎在 `crates/meowpuut-core`，C 介面在 `crates/meowpuut-ffi`，詞庫建置工具在 `crates/meowpuut-lexicon` 和 `scripts/`。按鍵事件、候選窗和系統整合由各客戶端處理。改共用引擎可執行 `cargo test --workspace`，改客戶端則參照各平台的建置說明。

一個 PR 處理一組相關問題。程式及詞庫修訂依專案 MIT 授權提供，第三方內容請附來源與授權。皮膚或角色素材若要納入正式版，先和作者確認署名與使用範圍。[程式授權](LICENSE) · [第三方來源](THIRD_PARTY.md) · [美術素材](ASSETS-LICENSE.md)
