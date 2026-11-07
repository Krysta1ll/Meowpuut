<p align="center">
  <img src="assets/brand/MeowpuutMark.png" width="160" alt="米米莫和莫莫">
</p>

<h1 align="center">喵撲 Meowpuut</h1>
<p align="center">mimimo。</p>
<p align="center">
  <a href="https://github.com/Krysta1ll/Meowpuut/releases/download/v0.4.2-alpha.1/Meowpuut-0.4.2-alpha.1-macos-arm64.dmg">下載 macOS 測試版</a> ·
  <a href="docs/INSTALL.md">安裝說明</a> ·
  <a href="https://github.com/Krysta1ll/Meowpuut/issues">回報問題</a> ·
  <a href="LICENSE">MIT</a>
</p>

喵撲是一款從日常中文輸入出發的開源輸入法。拼音和注音各有自己的按鍵規則，繁簡共用一份詞庫；偶爾想打日文單字，可以直接輸入羅馬音，不必為幾個字來回切換輸入法。

目前是公開測試版。它已能在 macOS 輸入中文，選詞和相容性仍在改進。遇到不順手的地方，歡迎提供按鍵順序和預期文字。

![喵撲的雙貓玻璃候選列](assets/screenshots/candidates.png)

## 米米莫、莫莫，和候選窗

左邊是米米莫，右邊是莫莫。貓耳構成玻璃的邊界，尾巴沿著候選列收起，兩張臉留在左右兩端。角色和視窗一起變長、收合；文字仍是中間最重要的部分。

「雙貓玻璃」之外，也有「簡潔玻璃」。兩種皮膚共用深色、淺色與跟隨系統的選擇，內建玫瑰奶茶、海鹽藍、鼠尾草綠、霧紫、琥珀蜜、石墨六組配色，玻璃強度亦可調整。兩者都依實際字寬安排候選，短詞可以並排，長句展開閱讀。縮放與展開有輕柔過渡，也會遵守系統的減少動態效果設定。

## 選字，先照顧中文

- **拼音與注音分開。** 拼音用數字直接選詞；注音保留數字聲調，用 Shift＋數字選詞，不讓兩套規則互相搶按鍵。
- **日語留在旁邊。** 拼音輸入時，完整羅馬音可產生假名或日語詞彙候選；有日語候選時，收合列保留一個，以傾斜的小貓爪標示。展開後可按候選排序繼續瀏覽。
- **英文留得住。** 拼音組字時，空格或 Enter 選取目前高亮的候選，數字可直接選詞；Shift＋Enter 輸出原始字母。單個字母仍可選英文，但中文優先。已識別的英文單字也可以成為首選。
- **越用越熟悉你的用詞。** 記住確認過的選詞、使用次數，以及短時間相鄰選詞的關係。反覆使用的短語可進入本機個人詞庫，無須逐個收藏。
- **表情跟著詞義走。** 「開心」「謝謝」「晚安」等詞可以帶出 emoji 和顏文字，作為文字之外的選擇。

日語目前是羅馬音轉假名加小型單字詞表，不是完整的日語整句漢字轉換器。能轉成假名，也不代表那串字一定是有意義的日語。

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

[**下載 0.4.2 Alpha 1 安裝盤**](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.4.2-alpha.1/Meowpuut-0.4.2-alpha.1-macos-arm64.dmg) · [安裝套件](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.4.2-alpha.1/Meowpuut-0.4.2-alpha.1-macos-arm64.pkg) · [SHA-256](https://github.com/Krysta1ll/Meowpuut/releases/download/v0.4.2-alpha.1/SHA256SUMS.txt)

| 平台 | 本次提供 |
| --- | --- |
| macOS 27+，Apple silicon | **0.4.2 Alpha 1** 安裝盤與安裝套件 |
| iOS／iPadOS 27+ | 宿主 App 與鍵盤擴充原始碼；尚未提供 TestFlight／IPA，本次候選修改尚未完成真機驗證 |
| Windows | C++ 核心可攜；尚無 TSF 接入與安裝包 |
| Intel Mac、較舊 macOS | 未驗證，不在本次測試包支援範圍 |

測試包尚未取得 Developer ID 簽署與 Apple 公證。下載前請讀[安裝與移除說明](docs/INSTALL.md)，Release 同時提供 SHA-256。不需要帳號，也不需要雲端服務。

## mimo 1.0：一份繁簡詞庫，兩套輸入規則

中文解碼、斷詞與排序由喵撲的 C++17 核心完成，沒有 Rime 執行時依賴。中文資料採用 McBopomofo 與 Pinyin Pro 的 MIT 詞表，再套用喵撲的讀音、字形與排序修訂；日語詞條也包含附帶 BSD 授權的 UniDic 精選資料。

合併詞庫名為 **mimo 1.0**。合併以**簡體詞面＋完整帶調讀音**為鍵：重複詞條採簡體來源詞頻，多音字分開保留；繁體專有詞也保留。兩份來源的頻率尺度以共同詞條校準，簡體來源的表達優先，本地修訂最後生效。每筆資料保有繁簡字形，切換輸出不需要載入另一份詞庫。

約 40 萬組詞條讀音保存在完整索引，常用部分供核心斷詞；這不是 40 萬個互不重複的詞，也不是即時聯網更新。固定版本、校驗值與授權見 [THIRD_PARTY.md](THIRD_PARTY.md)。合併策略與已知限制見[引擎說明](docs/ENGINE.md)。

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

C++ 核心可以單獨建置：

```sh
cmake -S Core -B .build/core
cmake --build .build/core
```

iOS 另需 XcodeGen，見 [iOS/README.md](iOS/README.md)。

## 一起把選詞做好

最有用的回報往往只有幾行：使用哪個模式、按了哪些鍵、想打什麼、目前出現什麼。請刪掉截圖中的私人內容，再附上系統與應用版本。

詞庫修訂放在 `Resources/Lexicon/`，不要直接修改上游快照。歡迎修讀音、調排序、補日常詞彙和改善跨應用相容性。提交方式見 [CONTRIBUTING.md](CONTRIBUTING.md)。

原創程式碼與隨附專案素材採 [MIT](LICENSE)；外部詞庫保留各自授權。喵撲仍在測試階段，長句斷詞、繁簡歧義字形、日語單字覆蓋和不同應用中的候選定位，都還有需要打磨的地方。
