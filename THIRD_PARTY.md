# 資料來源與授權

本專案詞庫系統精簡為繁體、簡體、日語各一份統一詞庫（`mimo-tc`、`mimo-sc`、`mimo-jp`），由離線清洗管線（`scripts/mimo_pipeline.py`）改造開源詞庫並編譯為原創唯讀二進位容器 `mimo_base.bin`（`MIMO-BASE-1` / `MIMO-BIN-1`）。

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

## 3. mimo-jp 日語常用詞庫（BSD-3-Clause）

- 單一整合詞庫：`DataSources/mimo-jp/mimo-jp.tsv`
- 上游開源來源與授權：
  - **UniDic**（衍生自 UniDic `2.1.2`，**BSD-3-Clause** 授權）與本專案獨立編修之羅馬字、漢字拼音別名與權重。
- 隨附授權聲明：`Licenses/mimo-jp-BSD.txt`、`Licenses/mimo-jp-derived-BSD.txt`、`Licenses/mimo-jp-COPYING.txt` 與 `Licenses/mimo-jp-SOURCE.json`。

## 原創引擎與整體授權說明

- 喵撲底層解碼核心（Rust `#![forbid(unsafe_code)]` SoA 架構）、唯讀零拷貝二進位格式 `mimo_base.bin`（`MIMO-BASE-1` / `MIMO-BIN-1`）與 App Group WAL 用戶動態記憶層（`mimo_user_wal.log` + `mimo_user.db`）皆由本專案 100% 獨立原創設計與實作，無任何第三方執行時引擎依賴。
- 因整合 `rime-ice`（GPL-3.0）衍生詞頻資料，本專案整體分發與合併詞庫採用 **GPL-3.0-or-later** 授權；上游各開源詞庫均完整保留其原始版權與授權聲明。
