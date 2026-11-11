# 資料來源與授權

本專案詞庫系統精簡為繁體、簡體、日語各一份統一詞庫（`mimo-tc`、`mimo-sc`、`mimo-jp`），由離線清洗管線（`scripts/mimo_pipeline.py`、日語為 `scripts/mimo_jp_pipeline.py`）改造開源詞庫並編譯為本專案自訂格式的唯讀二進位檔 `mimo_base.bin`（`MIMO-BASE-1` / `MIMO-BIN-1`）與日語詞表 `mimo_jp.bin`（`MIMO-JP-1`）。

## 1. mimo-tc 繁體中文詞庫（MIT / Apache-2.0）

- 單一整合詞庫：`DataSources/mimo-tc/mimo-tc.json`
- 上游開源來源與授權：
  - **McBopomofoData**（`openvanilla/McBopomofoData`，固定修訂 `df084385e546287fc072dfce486d46f102ef9bd4`，**MIT** 授權）：提供注音／帶調拼音基準詞條與三級多音字優先權重。
  - **OpenCC**（`BYVoid/OpenCC`，**Apache-2.0** 授權）：提供 `STPhrases.txt` 繁簡一對多精準字形對齊與 `TWPhrases.txt`／`HKPhrases.txt` 港台地區詞彙。
  - **jieba**（`fxsjy/jieba` `dict.txt.big`，**MIT** 授權）：提供繁體詞頻統計校準。
- 隨附授權聲明：`Licenses/mimo-tc-MIT.txt`、`Licenses/OpenCC-Apache-2.0.txt`、`Licenses/jieba-MIT.txt` 與 `Licenses/mimo-tc-SOURCE.json`。

## 2. mimo-sc 簡體中文詞庫（GPL-3.0 / MIT）

- 單一整合詞庫：`DataSources/mimo-sc/mimo-sc.json`
- 上游開源來源與授權：
  - **霧凇拼音 `rime-ice`**（`iDvel/rime-ice`，**GPL-3.0** 授權）：清洗 `8105.dict.yaml` 與 `base.dict.yaml` 現代常用雙字、三字與四字詞條，剔除助詞碎片並映射為 `i16` 對數機率分數（Log-Probability）。
  - **pinyin-pro**（`zh-lx/pinyin-pro`，固定修訂 `0785234b4dc0fa3ea9f244e3d77703b2df6a9db4`，**MIT** 授權）：提供完整帶調拼音讀音對齊與基礎詞頻分佈。
- 隨附授權聲明：`Licenses/rime-ice-GPL-3.0.txt`、`Licenses/mimo-sc-MIT.txt` 與 `Licenses/mimo-sc-SOURCE.json`。

## 3. mimo-jp 日語詞表（BSD-3-Clause AND NAIST-2003 AND Apache-2.0 AND Unicode-3.0 AND MIT）

- 快照：`DataSources/mimo-jp/mimo-jp.tsv`（約 46,000 詞，由 `scripts/mimo_jp_pipeline.py build` 產生，不手改）、`kanji-pinyin.tsv`、`build-report.json`；來源與檢查碼記在 `DataSources/mimo-jp/SOURCE.json`。
- 建置讀取的上游來源與授權：
  - **Mozc** `dictionary_oss`（`google/mozc`，固定修訂 `a4541df29db443b49f81cf54665873b68fde9efa`，**BSD-3-Clause** 與 **NAIST-2003**）：讀音、詞性與成本，權重由成本換算。
  - **UniDic** `unidic-cwj` 2025.12（國立國語研究所 NINJAL，`https://clrd.ninjal.ac.jp/unidic_archive/2512/unidic-cwj-202512_full.zip`，三種授權中選用 **BSD-3-Clause**）：詞形、活用類別與外來語判斷。
  - **OpenCC** `data/dictionary/JPShinjitaiCharacters.txt`（`BYVoid/OpenCC`，固定修訂 `fae2a0a911c30764e669a7d8b0591cd8e852934a`，**Apache-2.0**）：日本新字體與舊字形對照。
  - **Unihan** 18.0.0（Unicode，`Unihan_Readings.txt`、`Unihan_Variants.txt`，**Unicode-3.0**）：少數漢字的普通話讀音與異體字。
  - 漢字拼音取自本專案的 `mimo-sc` `entries`（pinyin-pro，**MIT**）與 `mimo-tc`（McBopomofoData，**MIT**）讀音。
- 本專案自行編寫（依本倉庫授權）：`Resources/Lexicon/japanese-reviewed.tsv`（60 個審過的詞，原 mimo-jp 1.x）、`japanese-deny.tsv`（不收的詞）、`japanese-kanji-pinyin.tsv`（多音字與國字的讀音）。
- `japanese-deny.tsv` 的 2,098 列待審詞已逐列判定，但判定是自動審讀，尚未經日語母語者審讀。
- 只在開發時用來排定審讀順序、建置不讀也不隨附任何內容的資料：**JMdict**（EDRDG，**CC BY-SA 4.0**，`http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz`，2026-11-10 的檔案，sha256 `8e511b59c1efb57d7dc90f58f386058035ca662cb4a23d5693b5e185190c5ede`）與 **SudachiDict** 20261015（**Apache-2.0**，`https://sudachi.s3.ap-northeast-1.amazonaws.com/sudachidict-raw/v1/20261015/`，`small_lex.zip` `981b78cdc532ce058b34c76cd9acd3c9a6c771cc0ce528d7de92424b21172125`、`core_lex.zip` `e9ac06222f0ce401a0fef9b129e61e93d59e3ff395a1b037f498962ee5b8ad8b`、`notcore_lex.zip` `16072d75abe6a048947aacbcecfe1ab65a200965a9f1ddb7ceab366348fa21f2`）。詞類偏移量曾對照 TUBELEX-JA（修訂 `7cb5fb36add76b83a266d1967536e1a1d3faa513`，BSD-3-Clause）量測一次，只留下五個常數。
- 授權：快照與 `SOURCE.json` 為 `BSD-3-Clause AND NAIST-2003 AND Apache-2.0 AND Unicode-3.0 AND MIT`。編譯出的 `mimo_jp.bin` 每個詞條帶一個由合併中文詞庫（含 rime-ice）算出的拼音模式標記，因此與 `mimo_base.bin` 一樣依 **GPL-3.0-or-later** 分發。
- 隨附授權聲明：`Licenses/mimo-jp-Mozc-BSD.txt`（Mozc `LICENSE`，含 NAIST 聲明）、`Licenses/mimo-jp-Unicode-3.0.txt`、`Licenses/mimo-jp-BSD.txt`、`Licenses/mimo-jp-derived-BSD.txt`、`Licenses/mimo-jp-COPYING.txt`、`Licenses/mimo-jp-SOURCE.json`；OpenCC 與 pinyin-pro、McBopomofoData 的聲明即上文的 `OpenCC-Apache-2.0.txt`、`mimo-sc-MIT.txt`、`mimo-tc-MIT.txt`。

## 4. cldr-emoji 中文詞對應的表情符號（Unicode-3.0）

- 快照：`DataSources/cldr-emoji/cldr-emoji.tsv`，由 `scripts/build-emoji-associations.py --cldr` 產生；來源與檢查碼記在 `DataSources/cldr-emoji/SOURCE.json`。鍵盤隨附的 `associations.json` 由 `Resources/Lexicon/associations-hand.json`（本專案自行編寫）與這份表建成。
- 上游來源與授權：**CLDR** 49（`unicode-org/cldr`，標籤 `release-49`，`common/annotations/zh.xml` 與 `zh_Hant.xml`，**Unicode-3.0**）：表情符號的中文名稱與關鍵字。
- 隨附授權聲明：`Licenses/CLDR-Unicode-3.0.txt` 與 `Licenses/cldr-emoji-SOURCE.json`。

## 4. mimo-pairs 詞對計數（CC BY-SA 4.0）

- 計數快照：`DataSources/mimo-pairs/counts.txt.xz`，由 `scripts/mimo_pairs.py` 從下列文字切詞計數；建置時打包為 `mimo_pairs.bin`（`MIMO-PAIR-1`）。來源版本與 SHA-256 見 `DataSources/mimo-pairs/SOURCE.json`。
- 來源與授權：
  - **中文維基百科、維基導遊、維基教科書**（2026-11-01 備份，**CC BY-SA 4.0**）：書面文字。計數由此衍生，故 `mimo_pairs.bin` 與計數快照依 CC BY-SA 4.0 分發（可單向相容 GPL-3.0）；署名為各站的貢獻者。
  - **Tatoeba** 中文句子（2026-11-11 匯出，**CC BY 2.0 FR**）：口語句子，署名為 Tatoeba 與其貢獻者。
  - **Mozilla Common Voice sentence-collector** zh-TW、zh-CN（commit `bc5d2c4`，**CC0 1.0**）：口語句子。
  - **mimo-everyday** 日常句子（`DataSources/mimo-everyday`，**GPL-3.0-or-later**，本專案自有）：2026-11-11 由語言模型為本專案寫成的聊天短句，台灣用語（繁體）與大陸用語（簡體），主題與行數見該目錄的 `SOURCE.json`。當作口語計入詞對與單詞權重，不含第三方文字。
- 未使用：中文維基新聞（條款未核對）、libpinyin 與 sunpinyin open-gram 模型（訓練文字與條款不明）。
- 測試與保留句不計入：評測集的 Tatoeba 句子編號、保留集句子，以及含有其中任一 5 字以上文字的子句。
- 隨附授權聲明：`Licenses/mimo-pairs-SOURCE.json`、`Licenses/mimo-pairs-CC-BY-SA-4.0.txt`、`Licenses/mimo-pairs-Tatoeba-CC-BY-2.0-FR.html`、`Licenses/mimo-pairs-CommonVoice-CC0-1.0.txt`。

## 4a. mimo-latin 中文裡的英文詞（CC BY-SA 4.0）

- 詞表：`DataSources/mimo-latin/latin.tsv`，由 `scripts/mimo_latin.py` 從 mimo-pairs 的同一批文字（及本專案自寫的日常句子）數出緊鄰漢字的英文詞與最常見的大小寫；建置時加入 `mimo_base.bin` 的拼音詞表。來源與授權同第 4 節，見 `DataSources/mimo-latin/SOURCE.json`。
- 隨附授權聲明：`Licenses/mimo-latin-SOURCE.json`（授權全文同 mimo-pairs）。

## 引擎與整體授權說明

- 解碼核心（Rust，`#![forbid(unsafe_code)]`）、唯讀二進位格式 `mimo_base.bin`（`MIMO-BASE-1` / `MIMO-BIN-1`）與選詞記錄（`mimo_user_wal.log` + `mimo_user.db`）由本專案寫成，執行時不依賴第三方引擎。
- 因整合 `rime-ice`（GPL-3.0）衍生詞頻資料，本專案整體分發與合併詞庫採用 **GPL-3.0-or-later** 授權；上游各開源詞庫均完整保留其原始版權與授權聲明。
