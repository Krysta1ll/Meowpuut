# 參與喵撲

修一個常用字的排序，和寫一個新功能一樣有價值。

回報輸入問題時請包含：
- macOS／iOS 版本、喵撲版本與發生問題的應用。
- 注音或拼音模式、繁體或簡體輸出。
- 原始按鍵、預期文字、實際候選及選取後結果。
- 如有截圖，先遮掉私人內容。

詞庫新增與修訂請放在 Resources/Lexicon/chinese.tsv；日語在 japanese.tsv，表情符號在 emoji.tsv，顏文字在 kaomoji.tsv（改完執行 scripts/build-emoji-associations.py，它會產生 associations.json）。請提供讀音與使用例，勿複製沒有明確授權的商業詞庫。修改後執行 build.sh 和 --smoke，為排序問題留下能重現的測試。

Core 是 C++17 中文核心；Sources 是共享輸入邏輯與 macOS 介面；iOS 是平台鍵盤接入。避免把 AppKit 或 UIKit 依賴帶入 Core。

提交請說清楚「什麼輸入原先失敗，現在變成什麼」。一個 PR 解決一組相關問題即可。貢獻的原創內容依專案的 MIT 授權提供；第三方資料仍須附上來源，而且只收註明出處即可的授權（MIT、BSD、Apache-2.0、Unicode、CC0、CC BY）。GPL、相同方式分享（CC BY-SA）、非商業或禁止改作的資料不收。
