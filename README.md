<p align="center">
  <img src="assets/brand/MeowpuutMark.png" width="160" alt="米米莫和莫莫">
</p>

<h1 align="center">喵撲 Meowpuut</h1>
<p align="center">mimimo。</p>
<p align="center">
  <a href="https://github.com/Krysta1ll/Meowpuut/releases/download/v0.6.0-alpha.1/Meowpuut-0.6.0-alpha.1-macos-arm64.dmg">下載 macOS 測試版</a> ·
  <a href="docs/INSTALL.md">安裝說明</a> ·
  <a href="https://github.com/Krysta1ll/Meowpuut/issues">回報問題</a> ·
  <a href="THIRD_PARTY.md">MIT / 資料來源</a>
</p>

喵撲是一款從日常中文輸入出發的開源輸入法。拼音和注音各有自己的按鍵規則，繁簡共用一份詞庫；偶爾想打日文單字，可以直接輸入羅馬音，不必為幾個字來回切換輸入法。

目前是公開測試版。macOS 和 iOS 可以日常輸入中文，Android 和 Windows 有較早的測試包；選詞和相容性仍在改進。遇到不順手的地方，歡迎提供按鍵順序和預期文字。

![macOS 候選列，淺色和深色：米米莫和莫莫趴在玻璃上緣；sushi 的候選裡有一個帶貓爪的寿司](assets/screenshots/candidates.png)

## 米米莫、莫莫，和候選列

候選列是一條玻璃。米米莫和莫莫趴在玻璃上緣，前爪搭在玻璃上，不蓋到候選字。牠們每天換一種位置：兩端各一隻探頭、擠在同一邊、一隻趴著一隻探頭等八種，設定裡也可以固定一種。候選多了玻璃跟著變長；文字仍是中間最重要的部分。摸米米莫他會縮一下搖頭，摸莫莫她會蹭一下，連摸五下切換繁簡。

玻璃有膠囊、波浪、雲朵三種造型，深色、淺色或跟隨系統，內建玫瑰奶茶、海鹽藍、鼠尾草綠、霧紫、琥珀蜜、石墨六組配色，玻璃強度可調。深色外觀下，玻璃的邊框和下緣那道光跟著配色走。不想要貓也可以關掉，只留玻璃。候選依實際字寬排成一行，長句在中間省略，展開後閱讀全部。縮放與展開有輕柔過渡，也會遵守系統的減少動態效果設定。

![iOS 鍵盤：淺色玫瑰奶茶與深色海鹽藍](assets/screenshots/ios-keyboard.png)

iOS 鍵盤的按鍵也是玻璃，同樣跟著配色。iOS 26 起用系統的液態玻璃；iOS 17 到 18 沒有液態玻璃，候選列是霧面玻璃，按鍵是帶配色的實色鍵面。四個平台的介面照同一份規格，見 [docs/DESIGN.md](docs/DESIGN.md)。

## 選字，先照顧中文

- **拼音與注音分開。** 拼音用數字直接選詞；注音保留數字聲調，用 Shift＋數字選詞，不讓兩套規則互相搶按鍵。
- **日語留在旁邊。** 拼音輸入時，完整羅馬音可產生假名或日語詞彙候選；有日語詞彙候選時（sushi 的寿司），收合列保留一個，以傾斜的小貓爪標示；只是假名拼寫的（haluo 的はぅお）排在所有中文候選之後。展開後可按候選排序繼續瀏覽。
- **英文留得住。** 拼音組字時，空格或 Enter 選取目前高亮的候選，數字可直接選詞；Shift＋Enter 輸出原始字母。單個字母仍可選英文，但中文優先。打的是英文單字時，首選就是原文，按空格直接輸入。拼音裡也可以夾英文：`wozaiyongiphone` 是「我在用iPhone」。
- **越用越熟悉你的用詞。** 記住確認過的選詞、使用次數，以及短時間相鄰選詞的關係。反覆使用的短語可進入本機個人詞庫，無須逐個收藏。
- **表情跟著詞義走。** 「開心」「謝謝」「晚安」等詞可以帶出 emoji 和顏文字，作為文字之外的選擇。

日語是羅馬音轉假名加約 46,000 詞的日語詞表（`mimo-jp` 2.0，`mimo_jp.bin`），一個讀音給出那個讀音的詞，不是日語整句漢字轉換器，也不做前綴補全。拼音模式只在不打擾中文時給日語詞：詞表裡一般的詞排在所有中文候選之後；不知道日語讀音時，可用漢字的拼音加假名的羅馬字打（`shiberu` 食べる、`dushu` 読書）。能轉成假名，也不代表那串字一定是有意義的日語。

## 先試幾個字

| 模式 | 輸入 | 可以選到 |
| --- | --- | --- |
| 注音 | `su3cl3` | 你好 |
| 注音 | `aul `（末尾空格） | 喵 |
| 拼音 | `nihao` | 你好 |
| 拼音 | `miao` | 喵 |
| 拼音 | `youhua` | 優化／优化 |
| 拼音 | `d` | 的、到、都等中文，亦保留原字母 |
| 拼音 | `sushi` | 中文同音詞，以及日語備選 |
| 拼音 | `emoji` | 英文原文 |
| 拼音 | `wozaiyongiphone` | 我在用iPhone |

| 操作 | 快捷鍵 |
| --- | --- |
| 注音／拼音／日語模式 | Control＋Option＋1／2／3 |
| 拼音選詞 | 1–7 |
| 注音選詞 | Shift＋1–7 |
| 確認候選 | Enter；拼音也可按空格 |
| 保留原始字母 | Shift＋Enter |
| 展開／收合或取消 | ↓／Esc |
| 翻頁 | Option＋←／→，或 Page Up／Page Down |
| 拼音聲調（可選） | Shift＋空格／6／3／4／7 → 一／二／三／四／輕聲 |
| 中英切換 | 拼音單按 Shift；注音保留 Shift＋Space |
| 標點選單 | 反引號 |
| 中文模式輸入英文斜線 `/` | Option＋斜線 |
| 本地短語模組 | Option＋Space |

拼音直接按標點鍵輸入中文標點；Shift＋逗號、句號、斜線可輸入英文標點。注音的聲韻鍵保留原功能。繁簡與皮膚可在輸入法選單切換。

## 下載與平台

[**下載 macOS 0.6.0 Alpha 1 安裝盤**](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.6.0-alpha.1/Meowpuut-0.6.0-alpha.1-macos-arm64.dmg) · [安裝套件](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.6.0-alpha.1/Meowpuut-0.6.0-alpha.1-macos-arm64.pkg) · [SHA-256](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.6.0-alpha.1/SHA256SUMS.txt)

| 平台 | 目前提供 |
| --- | --- |
| macOS 27+，Apple silicon | **0.6.0 Alpha 1** 安裝盤與安裝套件 |
| iOS／iPadOS 17+ | 0.7.0（8）TestFlight 測試版（宿主 App 與鍵盤擴充），原始碼見 `iOS/`。iOS 17、18 上的樣式還沒有在實機上跑過 |
| Android 7.0+ | [0.1.0 Alpha APK](https://github.com/Krysta1ll/Meowpuut/releases/tag/android-v0.1.0-alpha) |
| Windows 10／11 x64 | [0.1.1 Alpha](https://github.com/Krysta1ll/Meowpuut/releases/tag/windows-v0.1.1-alpha)，拼音；尚未在 Windows 上實際驗證 |
| Intel Mac、較舊 macOS | 未驗證，不在本次測試包支援範圍 |

macOS、Android、Windows 的安裝檔比原始碼舊：2026 年 11 月 12 日調整過的候選列玻璃目前只在原始碼和 iOS 測試版裡。

macOS 測試包尚未取得 Developer ID 簽署與 Apple 公證。下載前請讀[安裝與移除說明](docs/INSTALL.md)，Release 同時提供 SHA-256。不需要帳號，也不需要雲端服務。

## mimo 1.0：三層分離架構與一份繁簡詞庫

中文解碼、斷詞與排序由本專案以 Rust 寫成的 SoA（Structure-of-Arrays）核心完成，全工作區強制啟用 `#![forbid(unsafe_code)]`。詞庫系統採用三層分離設計：

1. **第一層（離線構建管線）**：整合 `mimo-tc`（繁體注音／拼音，MIT）、`mimo-sc`（簡體帶調詞表，MIT）、`OpenCC`（繁簡一對多消歧與港台詞彙，Apache-2.0）、`jieba`（繁體大詞表，MIT）與 `mimo-jp`（日語詞表，由 Mozc、UniDic、OpenCC、Unihan 經 `scripts/mimo_jp_pipeline.py` 產生，編譯為 `mimo_jp.bin`），統一校準為 `i16` 對數機率分數（Log-Probability），編譯為本專案自訂格式的唯讀二進位檔 **`mimo_base.bin`**（`MIMO-BASE-1` 第 2 版：每種輸入法一份 `MIMO-BIN-1` 詞表，加一份 `MIMO-IDX-1` 查找索引）。另從本專案自寫的句子（約 20.6 萬句）和 Tatoeba、Common Voice、CrossWOZ、KdConv、MASSIVE 的句子計算哪個詞後面常接哪個詞，打包為詞對表 **`mimo_pairs.bin`**（見 `scripts/mimo_pairs.py`）；含政治或貶低群體用語的句子不計入。詞庫另收 Wikidata（CC0）的作品、角色、地名等名稱，和本專案自寫的各行業用詞。
2. **第二層（映射的唯讀底座）**：iOS 鍵盤擴充、macOS 與 Android 客戶端以 `mmap` 映射 `mimo_base.bin`，經 `mpn_lexicon_create_from_base_in_place` 交給 Rust 核心。核心直接讀取映射的檔案頁（詞表欄位與預先建好的雜湊索引），不複製到堆積，也不在啟動時建索引；詞庫在堆積上佔 0 位元組。詞對表 `mimo_pairs.bin` 同樣映射後以 `mpn_lexicon_attach_pairs` 交給核心，也不佔堆積。排序時每個詞的分數是詞庫權重，加上它與後一個詞的詞對分數，輸入開頭與結尾也各算一次；長句和口語句子因此比只看詞頻準。Windows 仍讀 TSV 並複製到堆積（`mpn_lexicon_create`），尚未使用詞對表。
3. **第三層（選詞記錄）**：鍵盤以唯追加日誌 `mimo_user_wal.log` 記錄確認過的選詞，每 64 筆由鍵盤自己併入 `mimo_user.db`。iOS 上這兩個檔案只放在鍵盤自己的沙盒，宿主 App 不讀也不寫。

固定版本、校驗值與開源致謝見 [THIRD_PARTY.md](THIRD_PARTY.md)。合併策略與引擎架構見[引擎說明](docs/ENGINE.md)。

## 資料留在本機

輸入、排序和個人詞庫不需要網路。學習的是你確認過的候選，不讀取其他應用的聊天記錄或整份文件。記憶可在設定中停用或清除。

macOS 資料位於 `~/Library/Application Support/Meowpuut/`。iOS 鍵盤使用自己的沙盒；目前沒有跨裝置同步。

## 從原始碼建置

macOS 需要 macOS 27 SDK 的完整 Xcode、Python 3 和命令列工具。建置腳本預設使用 `/Applications/Xcode.app`；其他位置可設定 `MEOWPUUT_XCODE_DIR`。

```sh
git clone https://github.com/Krysta1ll/Meowpuut.git
cd Meowpuut
bash scripts/build.sh
.build/Meowpuut.app/Contents/MacOS/Meowpuut --smoke
bash scripts/package-macos.sh
```

詞庫快照隨原始碼保存，正常建置不用下載外部引擎或詞庫。測試使用獨立偏好設定，不修改日常選詞記憶。安裝包輸出至 `.build/releases/`。

iOS 另需 XcodeGen，見 [iOS/README.md](iOS/README.md)。

## 一起把選詞做好

最有用的回報往往只有幾行：使用哪個模式、按了哪些鍵、想打什麼、目前出現什麼。請刪掉截圖中的私人內容，再附上系統與應用版本。

詞庫修訂放在 `Resources/Lexicon/`，不要直接修改上游快照。歡迎修讀音、調排序、補日常詞彙和改善跨應用相容性。提交方式見 [CONTRIBUTING.md](CONTRIBUTING.md)。

喵撲以 [MIT](LICENSE) 授權發佈。詞庫的語料是我們自己收集和撰寫的，用到的公開詞表與句子都保留來源與授權聲明，見 [THIRD_PARTY.md](THIRD_PARTY.md)。喵撲仍在測試階段，長句斷詞、繁簡歧義字形、日語詞表的審讀（不收的詞目前由自動審讀判定，尚未經母語者審讀）和不同應用中的候選定位，都還有需要打磨的地方。
