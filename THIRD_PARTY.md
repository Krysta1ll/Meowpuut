# 資料來源與授權

## McBopomofo 詞庫資料（MIT）

- 上游：https://github.com/openvanilla/McBopomofo
- 固定修訂：`df084385e546287fc072dfce486d46f102ef9bd4`
- 只導入 `BPMFBase.txt`、`BPMFMappings.txt`、`phrase.occ` 及三份 `heterophony` 表；目前編譯器使用第一優先讀音表。
- 原始檔、MIT 授權及逐檔 SHA-256 位於 `DataSources/McBopomofo/`。macOS/iOS App 都附帶 `Licenses/McBopomofo-MIT.txt` 與來源版本資訊。
- Meowpuut 自行解析資料、建立索引、解碼、排序與輸出；未導入 McBopomofo 引擎或介面。修改放在 `Resources/Lexicon`，不修改上游原始檔。

## Pinyin Pro 完整簡體詞表（MIT）

- 上游：https://github.com/zh-lx/pinyin-pro/tree/main/packages/data
- 只採 `packages/data/json/complete.json`，其 README 標示來源為 jieba 詞表；不採 modern.json 或任何第三方執行引擎。
- 固定版本、資料提交日期、SHA-256 與 MIT 原文位於 `DataSources/PinyinPro/`。
- `SOURCE.json` 區分倉庫版本與資料檔提交；不將倉庫更新日期宣稱為每個詞條的新鮮度。
- App 附帶 `Licenses/PinyinPro-MIT.txt` 與來源資訊。繁體新詞的字形轉換使用系統 Foundation，已有對應詞時保留原詞庫字形；有歧義的字形仍需後續人工校訂。

## 其他

不打包或連結 Rime，也不使用舊 Terra Pinyin、essay、Mozc、JMdict 或 OpenCC 詞表。舊資料及其分發建置產物已移除。

## UniDic 日語常用詞精選（BSD-3-Clause）

- 以國立國語研究所 UniDic-CWJ 2025.12 為上游，從 UniDic-to-Mozc v1.1.0 衍生表人工挑選 50 個常用詞；版本、來源、雜湊與授權保留於 `DataSources/UniDic/`。
- 已選擇 UniDic 多重授權中的修訂 BSD 條款；衍生表及原始 UniDic 2025.12 的 BSD 聲明均隨 macOS/iOS 安裝包附上。人工補充的漢字拼音、羅馬字和排序分數不代表 UniDic 詞頻。Windows 詞庫分發須一併附上這些聲明，見 `Windows/README.md`。
- 其他日語詞條與羅馬字組字器由本專案編寫。執行時使用 Apple 系統框架、系統 SQLite 和 C++ 標準庫；建置時用 Foundation 做基礎繁簡字形轉換，修訂檔可明確覆蓋詞面。圖示使用自有雙貓素材及系統 SF Symbols。

本文件適用於本次發布的原始碼與安裝包。專案原創程式碼與隨附素材見根目錄 LICENSE；上游詞庫分別依各自的 MIT 或 BSD 授權聲明分發。
