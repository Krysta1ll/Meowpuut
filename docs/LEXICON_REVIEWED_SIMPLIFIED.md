# 簡體顯示的逐項修訂

`Resources/Lexicon/reviewed-simplified-exclusions.tsv` 記錄經審核的例外，每行依序為「繁體字形、完整帶調拼音、要排除的簡體顯示、已存在的正確簡體顯示」。四欄必須同時匹配；這不是全域繁簡轉換規則。除被確認的那一筆顯示外，繁體候選、其他讀音、其他詞組及個人詞庫均保留。

## 審核依據

第一批 11 筆從完整詞庫的同繁體、同讀音、不同簡體顯示分組挑出；原始候選、編輯權重、來源證據和固定轉換結果可用下列指令重新產生。報告僅供人工審核，不會修改詞庫：

```sh
python3 scripts/audit-mimo-corpus.py --resources .build/Meowpuut.app/Contents/Resources --output /tmp/mimo-review.json
```

第一批每筆都有相同繁體、讀音及建議簡體字形的 McBopomofo 來源記錄，也與專案固定 ICU 轉換結果一致。除「為 wei2」和「們 men2」外，Pinyin Pro 亦直接含建議簡體字形的同調條目；這兩項不能宣稱已由 Pinyin Pro 獨立證實同一聲調。Pinyin Pro 的頻率和專案排序權重是不同尺度，不能直接比較為真實使用次數。

| 繁體 | 讀音 | 排除的簡體顯示 | 保留的簡體顯示 |
| --- | --- | --- | --- |
| 為 | wei2 | 為 | 为 |
| 們 | men2 | 們 | 们 |
| 裡 | li3 | 裡 | 里 |
| 係 | xi4 | 係 | 系 |
| 對於 | dui4yu2 | 对於 | 对于 |
| 治療 | zhi4liao2 | 治療 | 治疗 |
| 至於 | zhi4yu2 | 至於 | 至于 |
| 有利於 | you3li4yu2 | 有利於 | 有利于 |
| 遊 | you2 | 遊 | 游 |
| 複 | fu4 | 複 | 复 |
| 麵 | mian4 | 麵 | 面 |

此外，簡體顯示針對 `妳 ni3` 和 `妳好 ni3hao3` 採用「你／你好」，繁體仍保留「妳／妳好」。這是面向簡體使用場景的詞條顯示決策，不對其他讀音全局替換；[臺灣教育部辭典](https://dict.revised.moe.edu.tw/dictView.jsp?ID=3027&la=1&powerMode=0)收錄了其女性第二人稱用法。目標字形由專案自訂詞條明確提供，再按完整讀音核對後移除舊顯示。

對於其他詞組，只在繁簡字形、字數和帶調讀音逐字對齊，且對應位置明確為 `妳 ni3` 時，把簡體顯示的該字改為「你」。繁體顯示保留原文；`妳 nai3` 和無法對齊的詞條不做替換。若正確簡體對應已存在，保留該詞原有權重，不用舊字形的權重覆蓋。每次建置均在資源的 `simplified-pronoun-audit.json` 記錄實際處理的詞條與權重；自訂詞條若與規則衝突會停止建置以便審核。

可參考 [Unicode 17 Unihan 字形資料](https://www.unicode.org/Public/17.0.0/ucd/Unihan.zip) 及中國教育部對 [《通用規範漢字表》的說明](https://www.moe.gov.cn/jyb_xwfb/xw_fbh/moe_2069/s7135/s7562/s7569/201308/t20130827_156343.html)。字形映射本身不能決定詞義；台灣教育部辭典對 [「記錄」亦作「紀錄」](https://dict.revised.moe.edu.tw/dictView.jsp?ID=88669&la=0&powerMode=0) 的記錄，就是需要保留變體的例子。[「對於」](https://dict.revised.moe.edu.tw/dictView.jsp?ID=48242&la=0&powerMode=0) 和 [「治療」](https://dict.revised.moe.edu.tw/dictView.jsp?ID=114294&la=0&powerMode=0) 亦可在其辭典查核。

「著／着」、「乾／干」、乾隆、紀錄／记录、俱樂部／俱乐部涉及語義或詞組層面的判斷，未依單字轉換直接列入本批排除。其他未核定的組別仍留在審核報告，不能推論為正確或錯誤。

## 回歸

重新建置詞庫後執行：

```sh
python3 Core/tests/check_reviewed_simplified.py .build/Meowpuut.app/Contents/Resources
```

測試核對每個精確項在 SQLite 和拼音、注音核心中的輸出與選字，並保護 `著名`、`乾隆`、兩種「紀錄」簡體詞形及 `俱乐部` 等有效詞項；不要求任何已知錯誤字形在未來詞庫中保留。修改清單時應先核對字形、讀音及正確候選確實存在，再補相應案例。
