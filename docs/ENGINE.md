# 引擎與詞庫

## 三層

中文解碼、斷詞與排序由本專案以 Rust 寫成的核心完成（`crates/`），詞表以陣列結構體（SoA，Structure-of-Arrays）排列。除了 C ABI 那一層（`meowpuut-ffi`：整個 crate 禁止 `unsafe`，只在個別項目重新允許，每個 `unsafe` 區塊只有一個操作並附 `SAFETY` 說明），其他 crate 都是 `#![forbid(unsafe_code)]`。詞庫分三層：

1. **離線建置**：整合 `mimo-tc`（繁體注音／拼音，MIT）、`mimo-sc`（簡體帶調詞表，MIT）、OpenCC（繁簡一對多與港台詞彙，Apache-2.0）、jieba（繁體大詞表，MIT）與 `mimo-jp`（日語詞表，由 Mozc、UniDic、OpenCC、Unihan 經 `scripts/mimo_jp_pipeline.py` 產生，編譯為 `mimo_jp.bin`），分數統一校準為 `i16` 對數機率，編譯成本專案自訂格式的唯讀二進位檔 `mimo_base.bin`（`MIMO-BASE-1` 第 2 版：每種輸入法一份 `MIMO-BIN-1` 詞表，加一份 `MIMO-IDX-1` 查找索引）。另從本專案自寫的句子（約 20.6 萬句）和 Tatoeba、Common Voice、CrossWOZ、KdConv、MASSIVE 的句子計算哪個詞後面常接哪個詞，打包為詞對表 `mimo_pairs.bin`（見下方「詞對表」與 `scripts/mimo_pairs.py`）；含政治或貶低群體用語的句子不計入。詞庫另收 Wikidata（CC0）的作品、角色、地名等名稱，和本專案自寫的各行業用詞。
2. **映射的唯讀底座**：iOS 鍵盤擴充、macOS 與 Android 以 `mmap` 映射 `mimo_base.bin`，經 `mpn_lexicon_create_from_base_in_place` 交給核心。核心直接讀映射的檔案頁（詞表欄位與預先建好的雜湊索引），不複製到堆積，也不在啟動時建索引；詞庫在堆積上佔 0 位元組。詞對表同樣映射後以 `mpn_lexicon_attach_pairs` 交給核心，也不佔堆積。排序時每個詞的分數是詞庫權重，加上它與後一個詞的詞對分數，輸入開頭與結尾也各算一次；長句和口語句子因此比只看詞頻準。Windows 仍讀 TSV 並複製到堆積（`mpn_lexicon_create`），尚未使用詞對表。
3. **選詞記錄**：鍵盤以唯追加日誌 `mimo_user_wal.log` 記錄確認過的選詞，每 64 筆由鍵盤自己併入 `mimo_user.db`（`Sources/ChoiceMemory.swift`）。iOS 上這兩個檔案只放在鍵盤自己的沙盒，宿主 App 不讀也不寫。Android 與 Windows 還沒有選詞記錄。

固定版本、校驗值與授權見 [THIRD_PARTY.md](../THIRD_PARTY.md)。

## 執行時

crates/meowpuut-core（Rust）提供中文解碼器與日語詞表查詢，crates/meowpuut-ffi 提供 C API（include/meowpuut.h）。不可變詞表供會話共用，每個會話獨立保存組字、候選、頁碼與提交內容。拼音和注音各有讀音索引，共用詞條字形與頻率來源。

詞表的載入（crates/meowpuut-core/src/bin_format.rs）：iOS、macOS 與 Android 映射 mimo_base.bin，以 `mpn_lexicon_create_from_base_in_place` 交給核心，核心直接讀映射的檔案，不複製。檔案第 2 版在兩份 `MIMO-BIN-1` 詞表之後，每種輸入法附一份 `MIMO-IDX-1`：讀音的雜湊索引、語境詞組的雜湊索引（兩種輸入法共用一份）、繁簡單字對照表、每個首字母最多 64 個常用短詞，以及每列的詞編號（詞對表用）。這些原本在載入時建在堆積上（拼音約 6.5 MiB），現在由 scripts/mimo_binary.py 寫進檔案。索引段的檔頭記下雜湊索引最長的探測距離（查找不會再往後找；超過 255 的檔案不收）和詞表的抽樣指紋（64 段各 64 位元組的雜湊），換了詞表的索引段在載入時就會被拒絕。檔案原為 43,966,524 位元組；加上索引為 53,809,116 位元組，加上詞編號（3.4 MB）並合併 PL1–PL3 的詞條後為 66,413,088 位元組。改用已排序欄位的二分搜尋可以不加大檔案，但量過太慢（見 crates/meowpuut-core/src/context_index.rs）。核心仍接受第 1 版檔案，此時索引在載入時建在堆積上，沒有詞編號，也就不能附詞對表。`mpn_lexicon_create_from_base` 與 `mpn_lexicon_create` 照舊把詞表複製到堆積。iOS 鍵盤只讀 App 內唯讀的 mimo_base.bin，不讀 App Group 裡的副本；讀不到時才退回 native-*.bin 或 .tsv（iOS 鍵盤不再附 .tsv，其他平台的載入程式保留這條路）。

排序（crates/meowpuut-core/src/decoder.rs）：解碼從輸入結尾往前，為每個位置保留幾條到結尾的最佳路徑；一條路徑記它的第一個詞和後面那條路徑，不在解碼時複製文字。每個詞的分數是詞庫權重 / 700 − 19。附上詞對表 mimo_pairs.bin 後，再加上這個詞與後一個詞的詞對分數，輸入開頭與結尾也各算一次（句首與句尾當成兩個詞）；每個位置保留 16 條路徑，同一個開頭詞最多 4 條（輸入開頭不限，那裡的路徑就是候選），每個讀音區段取最重的 10 個不同字詞（取 20 個在保留集上只多對 8 句（共 6,003 句），按鍵的 p99 卻慢一半），拼音的首字母縮寫只用在沒有完整拼寫詞的區段或輸入結尾。分段（segments.rs）解碼的前段後面還有按鍵，所以最後一個詞不算句尾分數；預測分段時拿前 8 條路徑去比對後面的音節（沒有詞對表時 5 條），因為「我喜歡」的「我喜」在計數文字裡很少是兩個詞。誤拼與補全的候選（fallback.rs）也加上句首與句尾分數。沒有詞對表時照舊：每個位置 5 條路徑，每個區段 5 列，相鄰的字若組成語境表的詞組（context.tsv 與所有多字詞）就加分。聯想詞與分段上屏後的重排仍用語境表。

有詞對表時（crates/meowpuut-core/src/merge.rs）：整個輸入剛好是一個詞的候選、誤拼與補全的猜測，都換成與解碼路徑相同的分數（nat）一起排序，不再固定排在前面。整詞的分數是詞庫分數加句首與句尾分數，這兩項各限制在 ±1 nat：計數來自聊天，「煎蛋」「小事」常自成一句，限制大了會壓過「簡單」「小時」；完全不算又會讓「要是」「句子」勝過「鑰匙」「橘子」。猜測另扣 1.7 nat，只在明顯較好時勝過拼寫完整的讀音。

全拼與首字母混打（zenmb 怎麼辦、buzd 不知道、meiwt 沒問題）：一段字母不是任何鍵、又不能全拆成完整音節時，拆成完整音節與聲母的組合，至少各有一個，取讀音相符的詞，每段最多 8 列，比完整拼寫的權重少 3 nat。

詞對分數的快取：每個會話保留最近查過的詞對分數（16,384 格，約 192 KiB），下一個按鍵的解碼大多查同樣的詞對。分段選詞後，剩下的輸入以剛選的詞當前一個詞算句首的詞對分數，不再當成新的一句；客戶端把同一段文字設回語境時也沿用這個詞。

英文詞（crates/meowpuut-core/src/latin.rs）：DataSources/mimo-latin 的 8,300 個英文詞（從詞對表的同一批文字裡、緊鄰漢字的英文字數得來）由 compile-native-lexicon.py 寫進拼音詞表，鍵是大寫字母（IPHONE），拼音查找用小寫，碰不到它們。解碼時一段字母若不能拆成完整拼音，可以是英文詞（減 2 nat）；能拆成兩個以上音節時再減 1.5 nat，只在中文讀法很弱時勝出；一個音節（an、she）不當英文。整個輸入是英文詞時，它排第一（字母不是拼音且有母音，iphone、ok），否則排在前兩個候選之後（pdf、nice）。中文夾英文的整句（我OK、我在用iPhone）排在只吃掉一部分輸入的猜測之前。兩個英文詞不直接相連（haokini 不是「哈OKin」）；拼音假名混打的中文部分不用英文詞（sushiswap 不是「寿司swap」）。客戶端裡，整個輸入有審過的日語詞或拼音假名混打時（koohii コーヒー、woyaoauni 我要会う你），它排在夾英文片段的中文讀法（快哦耦合II）之前；單獨一個英文詞（game）照舊排第一。注音不查英文詞。

候選列的順序（crates/meowpuut-core/src/row.rs，有詞對表、且第一個候選讀完整個輸入時）：
1. 第一個候選，不動。
2. 整個輸入的其他詞庫詞，按分數。
3. 其他整句讀法最多兩個，且在最佳讀法 3 nat 之內；第一個候選 4 個字以內時最多一個，在 0.5 nat 之內。其餘移到最後，不刪。
4. 第一個讀法每個音節邊界的前段詞，長的在前、重的在前，第一個音節最多 24 個字，其他前段各 3 個。選了它，剩下的輸入繼續組字，和分段選詞一樣。
原本就有的部分候選，只留是第一個候選開頭的兩個。

Swift 的 ChineseWithJapaneseBackend 合併候選。英文判斷使用本機規則；日語使用 Romaji 轉假名與 mimo_jp.bin 詞表（見下方「日語詞庫」）。收合只顯示一個日語候選，放在右側。展開保留其餘日語，與較弱中文交錯。這不是通用語言辨識或完整日語句法解碼。

## mimo 1.0 統一詞庫

編譯器 scripts/compile-native-lexicon.py 的合併順序：
1. 校驗兩份上游快照的 SHA-256。
2. 讀取 `mimo-tc` 的字形、讀音、三級多音字優先表和詞頻，配對簡體字形。
3. 將 `mimo-sc` 帶調拼音正規化，按「簡體詞面＋帶調讀音」去重，並保護 `mimo-tc` 高頻多字詞不被地板值覆蓋；不同讀音不合併。
4. 用共同詞條的對數頻率差中位數校準繁體獨有詞的尺度，再減 350 作為簡體表達優先的排序偏好。這是可調先驗，不是兩份語料真實使用量的統計比較。
5. 套用本地修訂。修訂字形和權重必須勝出，即使人工權重比上游低。人工詞條衍生的單字也加入核心索引。
6. 注音索引以台灣用法為準：`mimo-tc` 有的多字詞，權重至少為 `mimo-tc` 權重加校準差（不減 350）；`mimo-tc` 有這個詞但沒有這個讀音時減 1400（多為大陸讀音）；單字不改，因為 `mimo-tc` 給同一字的各讀音同一權重。SQLite 的 lexicon 表也用這個權重。被合併掉的其他繁體字形（讚、準）只加回注音索引；單字排在保留字形之下，差距取 `mimo-tc` 的權重差。
7. 拼音索引取各來源的平均：多字詞為合併權重、`mimo-tc` 權重加校準差（兩邊都有的詞）、語料次數換算值三者平均；單字為合併權重與語料次數換算值的平均。語料次數是這個詞在計數文字裡的次數（DataSources/mimo-pairs 的 U 列；口語一次算 30 次書面），換算值為 700 ln(1+次數) 加上與合併權重之差的中位數，詞與單字各算一次；語料裡沒出現的詞當作 0 次。次數是「字面」的，不分讀音：一個詞最可能的讀音照上式調整，其他讀音移動同樣的量，先後不變。注音索引也往語料次數靠一半的差距。OpenCC 港台詞（regional_tw_hk）至少為 `mimo-tc` 權重加校準差再減 350。人工修訂詞和第 8 項的名稱不調整，但會降到調整前排在它上面、同讀音的詞之下，保留審閱時的先後。
8. 短語、名稱與行業用詞。短語（DataSources/mimo-phrases，scripts/mimo_phrases.py）：口語文字裡至少 5 次單獨成句、且單獨成句佔它口語出現次數兩成以上的詞組（辛苦了、知道了、真的嗎、別擔心），2–4 個字；只在句子中間常見的組合（我的、的時候）不收，詞對表已經涵蓋，收成詞反而擠掉同音的詞。讀音取各詞最重的讀音，權重用它自己的次數換算。名稱（DataSources/mimo-names，Wikidata）與行業用詞（Resources/Lexicon/terms.tsv）：逐字取最重的讀音；名稱的權重是 2400 + 450 ln(收錄它的維基百科語言版本數)，行業用詞固定 3200，都低於日常用詞，打完整才會出現。
9. 拼音首字母鍵：每個多字詞有一個每音節取首字母的鍵（知道了 zdl），音節以 zh、ch、sh 開頭時另有一個保留兩個字母的鍵（zhdl）。權重為拼音權重除以 8。不列只有部分音節寫全的鍵（什麼時候只有 smsh 和 shmshh）：在保留集上列出所有組合反而少對 8 筆，檔案也多 2 MB。
10. 輸出一份完整 SQLite 詞庫和兩個常用讀音索引（mimo_base.bin，含查找索引）。繁簡輸出使用同一筆記錄的 hant／hans 欄位，不是兩個各自累積的詞庫。

字形轉換使用 Foundation。沒有明確對應的新詞可能遇到一簡對多繁的歧義，應以本地修訂校正。合併不會刪掉繁體專有表達或多音字；同義但不同寫法也不冒充重複詞。

lexicon-merge-report.json 記錄去重、來源新增、排除數量、頻率校準與索引規模。單字查找、前綴補全和誤拼修正使用有界搜尋；不會在每次按鍵時掃描整份詞庫。

## 詞對表

mimo_pairs.bin 記錄哪個詞後面常接哪個詞。詞對分數是 0.8 ×（log P(後詞|前詞) − log P(後詞)）：P(後詞|前詞) 用絕對折扣（D = 0.75），P(後詞) 是計數占比 0.8 加詞庫占比 0.2；以 1/64 nat 存放，表中沒有的詞對用前詞的退回分數。格式是每個前詞一段已排序的後詞（見 crates/meowpuut-core/src/pairs.rs），iOS、macOS 與 Android 映射檔案後以 `mpn_lexicon_attach_pairs` 交給核心，直接讀，不佔堆積。

詞編號是兩種輸入法所有詞的簡體寫法排序後的位置，0 和 1 是句首和句尾。scripts/mimo_binary.py 把每列的詞編號和這份詞表的指紋寫進 MIMO-IDX-1；詞對表帶著同一個指紋，不符的表會被拒絕，詞庫就照沒有詞對表的方式排序。

scripts/mimo_pairs.py 產生計數：
1. fetch：下載 Tatoeba、Common Voice sentence-collector、CrossWOZ、KdConv、MASSIVE 到 .build/pair-corpus，核對 DataSources/mimo-pairs/SOURCE.json 的 SHA-256。本專案自寫的句子（DataSources/mimo-everyday）在倉庫裡，不用下載。口語一組：自寫的聊天句、Tatoeba、Common Voice；書面一組：自寫的敘述句與中文夾英文句、三份對話資料。
2. count：用「只靠詞表、不含任何計數」編出的詞庫切詞（compile-native-lexicon.py 輸出目錄 轉換器 --no-phrases --no-counts），這樣切詞不受先前計數影響。每個子句（標點之間的漢字）用詞庫的字表轉成簡體，按詞庫詞的最大機率路徑切詞，計算單詞、詞對、句首與句尾。評測集與保留集的 Tatoeba 句子，以及含有其中任一 5 字以上文字的子句，不計入（--exclude）；含 Resources/Lexicon/blocked-terms.txt 詞語的句子整句不計入。scripts/mimo_phrases.py count 用同一個詞庫數短語，scripts/mimo_latin.py count 數英文詞。
3. snapshot：口語計數乘 10 合併（句首與句尾見下段），按 --keep 規則保留詞對，寫成 DataSources/mimo-pairs/counts.txt.xz。檔案以詞的文字為鍵，所以詞庫小改也能用。接著 scripts/mimo_phrases.py snapshot 寫出短語清單。
4. 建置時 compile-native-lexicon.py 依當次詞庫的詞編號把計數打包為 mimo_pairs.bin，詞庫沒有的詞連同它的詞對一起捨去。短語和名稱不在計數用的詞庫裡，所以沒有自己的詞對，用前詞的退回分數。context.tsv 的手寫詞組不進詞對表：把它們當成詞對加分，在保留集與評測集上沒有改變任何首選；它們仍在語境表裡，供沒有詞對表時的排序與聯想詞使用。

口語證據與用字慣例：口語來源是 Tatoeba、Common Voice 與本專案的聊天句子（DataSources/mimo-everyday 的 everyday 與 chat-2，由語言模型為本專案寫成，台灣與大陸用語都有，scripts/mimo_everyday.py 去掉重複、空行與超過 40 字的行）。句首那一列只用口語計數；每個詞接句尾的比例取它在口語裡的比例（向全體平均拉近 2 個子句），沒在口語出現的詞取平均，因為百科文章開頭與結尾的詞和聊天不同。打包時句首與句尾分數限制在 ±1.5 nat，其他詞對分數與退回分數限制在 ±4 nat。Resources/Lexicon/pair-defaults.tsv 列出用字慣例（她、它 → 他，妳 → 你，嘛 → 吗）：換字後仍是詞的變體，詞對分數不高於預設寫法，不論它在前或在後，口語次數也不會把它抬到預設寫法之上，所以只有詞庫權重或使用者選詞能讓「她」排到「他」前面。單詞權重也用口語證據：counts.txt.xz 的 U 列最後一欄是口語次數，compile-native-lexicon.py 的 spoken_weights 把口語出現 2 次以上的多字詞，往 700 ln(1 + 次數) + 中位差 的口語權重拉高 1.5 倍差距（只升不降，同一詞的各讀音加同樣的量，首字母縮寫跟著完整權重走），注音與拼音都套用。這些值在 dev 集（1,401 句 × 3）上選定。

## 更新與個人詞庫

中文快照由 scripts/mimo_pipeline.py 重建，日語快照由 scripts/mimo_jp_pipeline.py 重建。更新後重新建置、執行 --smoke 與資料檢查，不在使用者輸入時下載資料。

ChoiceMemory 只學習已確認選詞，以及短時間相鄰選詞形成的短語。資料保存在本機，與捆綁詞庫分開，可停用或清除。沒有聊天記錄讀取或雲端同步。

## 日語詞庫

- 快照：`python3 scripts/mimo_jp_pipeline.py fetch` 取得並核對固定版本的輸入，`build` 寫到 `.build/mimo-jp-snapshot`，`verify` 重建並與 `DataSources/mimo-jp` 比對。管線不會寫進 `DataSources/`；更新時手動複製並更新 `SOURCE.json`。
- 本專案自訂的部分在 `Resources/Lexicon`：`japanese.tsv`、`japanese-reviewed.tsv`（審過的詞，位置照舊）、`japanese-deny.tsv`（不收或不在拼音模式出現的詞）、`japanese-kanji-pinyin.tsv`（多音字與國字的讀音）。
- 編譯：`compile-native-lexicon.py` 呼叫 `scripts/mimo_jp_compile.py`，在 `mimo_base.bin` 旁寫出 `mimo_jp.bin`（`MIMO-JP-1`，格式見 `scripts/mimo_jp_binary.py` 與 `crates/meowpuut-core/src/japanese.rs`）。給人審讀的清單寫到 `.build/mimo-jp-review`。
- 拼音模式的門檻在編譯時算好，每個詞條一個位元。常數在 `mimo_jp_compile.py` 開頭：至少 3 個字母、不是單一拼音音節；看起來像拼音（含打到一半）的讀音要 4 個字母且權重 3200 以上；與中文讀音相同時權重要 4500 以上，且不是同一個詞；每個讀音最多 3 個詞。
- 客戶端（macOS、iOS）：審過的詞位置照舊；詞表的其他詞排在所有中文候選之後、假名之前；與中文候選同字的詞不出現；純漢字的拼音別名在中文也有這個讀音時排在最後，不進收合列。Android、Windows 仍讀 `native-japanese.tsv`。
- 檢查：`python3 scripts/check-mimo-jp.py <編譯目錄> --probe`（映像、門檻、不收清單、拼音模式打擾比例），`check-chinese-golden.py`（中文候選不變），`check-japanese-lexicon.py`（舊輸出不變）；`bash scripts/build.sh --test` 會一起執行。

## 測試邊界

--smoke 涵蓋繁簡、拼音／注音模式隔離、誤拼、單字母、長句、候選翻頁、日語保留、個人記憶與候選尺寸。它不是輸入準確率評測；毫秒測量也只是測試批次內的引擎耗時，不等於端到端輸入延遲。

Windows 尚未接入 TSF。iOS 尚需真機記憶體、跨 App、安全輸入欄位與無障礙驗證。
