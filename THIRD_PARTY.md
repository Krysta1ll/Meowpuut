# 資料來源與授權

## McBopomofo 詞庫資料（MIT）

- 上游：https://github.com/openvanilla/McBopomofo
- 固定修訂：`745e8641a95d958532f0820c3ee679c2a585d8e3`
- 只導入 `BPMFBase.txt`、`BPMFMappings.txt`、`phrase.occ` 及三份 `heterophony` 表；目前編譯器使用第一優先讀音表。
- 原始檔、MIT 授權及逐檔 SHA-256 位於 `DataSources/McBopomofo/`。macOS/iOS App 都附帶 `Licenses/McBopomofo-MIT.txt` 與來源版本資訊。
- `Resources/Lexicon/mcbopomofo-retained.tsv` 保留 23 條在上述修訂中移除的常用詞，並記錄原始修訂、讀音與詞頻；同樣遵循上游 MIT 授權。
- Meowpuut 自行解析資料、建立索引、解碼、排序與輸出；未導入 McBopomofo 引擎或介面。修改放在 `Resources/Lexicon`，不修改上游原始檔。

## Pinyin Pro 完整簡體詞表（MIT）

- 上游：https://github.com/zh-lx/pinyin-pro/tree/main/packages/data
- 只採 `packages/data/json/complete.json`，其 README 標示來源為 jieba 詞表；不採 modern.json 或任何第三方執行引擎。
- 固定版本、資料提交日期、SHA-256 與 MIT 原文位於 `DataSources/PinyinPro/`。
- `SOURCE.json` 區分倉庫版本與資料檔提交；不將倉庫更新日期宣稱為每個詞條的新鮮度。
- App 附帶 `Licenses/PinyinPro-MIT.txt` 與來源資訊。繁體新詞使用固定版本的繁簡轉換快照，已有對應詞時保留原詞庫字形；有歧義的字形仍需後續人工校訂。

## 固定繁簡轉換資料（Unicode License V3）

- 編譯詞庫時使用 ICU 78.2 的 `Traditional-Simplified` 轉換及其反向結果，隨專案提供已產生的固定查表；所有平台使用同一份資料，不依賴系統的 ICU 或字形轉換版本。
- ICU 的 `Hans_Hant.txt` 由 CLDR 產生，原始檔保留 Apple Inc.、Unicode Inc. 及其他貢獻者的版權聲明。原規則、Unicode License V3 完整原文、版本與 SHA-256 存於 `DataSources/MimoNormalization/`，並隨各平台套件提供。
- `reviewed-overrides.tsv` 是喵撲人工審核的 11 筆本地修訂，用以保持既有字形。來源與更新方法見 [固定轉換資料說明](DataSources/MimoNormalization/README.md)。

## 其他

不打包或連結 Rime，也不使用舊 Terra Pinyin、essay、Mozc、JMdict 或 OpenCC 詞表。舊資料及其分發建置產物已移除。

## UniDic 日語常用詞精選（BSD-3-Clause）

- 以國立國語研究所 UniDic-CWJ 2025.12 為上游，從 UniDic-to-Mozc v1.1.0 衍生表人工挑選 50 個常用詞；版本、來源、雜湊與授權保留於 `DataSources/UniDic/`。
- 已選擇 UniDic 多重授權中的修訂 BSD 條款；衍生表及原始 UniDic 2025.12 的 BSD 聲明均隨 macOS/iOS 安裝包附上。人工補充的漢字拼音、羅馬字和排序分數不代表 UniDic 詞頻。Windows 詞庫分發須一併附上這些聲明，見 `Windows/README.md`。
- 其他日語詞條與羅馬字組字器由本專案編寫。Apple 平台執行時使用系統框架、系統 SQLite 和 C++ 標準庫；建置時使用上述固定繁簡轉換快照，修訂檔可明確覆蓋詞面。圖示使用自有雙貓素材及系統 SF Symbols。

本文件適用於本次發布的原始碼與安裝包。專案原創程式碼與隨附素材見根目錄 LICENSE；上游詞庫與轉換資料分別依隨附的 MIT、BSD 或 Unicode 授權聲明分發。
