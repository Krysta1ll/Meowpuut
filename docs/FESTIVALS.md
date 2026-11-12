# 節日衣櫥

規則只有一條：每個節日從第一天的前七天起，到最後一天為止（裝置當地日期，含頭含尾），米米莫和莫莫穿上這個節日的衣服；其他日子穿平常的樣子，App 和鍵盤都沒有辦法提前穿上或預覽。

只看日曆，不看語言、地區或其他設定，所有人同一天看到同一套。不收政治性的節日。

這份文件是給開發者的。日期表和主題清單只放在這裡和清單檔裡，不出現在 App 裡。

## 一套主題有什麼

每套由美術交件，原樣放進專案，不重畫、不修改：

- 穿節日衣服的米米莫和莫莫，各一張坐姿圖，64×80 pt，腳的位置在 (0.5, 0.92)。
- 候選列的飾邊：360×80 pt，三段式，左右兩端固定，只拉長中段。飾邊畫在玻璃外圈，不是背景。
- 一個主色。選中候選的膠囊顏色由主色算出（淺色、深色、增加對比各一組），白字對比符合 docs/DESIGN.md。

怎麼放上候選列見下面「放上候選列的樣子」。

## 清單檔

`scripts/import_festivals.py` 產生，同一份放在 `assets/festivals/`、`iOS/Festivals/`、`Resources/Festivals/`、Android 的 `assets/festivals/` 和 Windows 的 `UI/Assets/festivals/`（測試會檢查五份相同）。不要手改，改了下次匯入會被蓋掉。

```json
{
  "version": 2,
  "leadDays": 7,
  "packs": ["Meowpuut_Festivals_24_Collection"],
  "themes": [
    {
      "id": "christmas",
      "names": {"hant": "聖誕節", "hans": "圣诞节", "ja": "クリスマス", "en": "Christmas"},
      "mimimo": {"image": "christmas_mimimo", "size": [64, 80], "anchor": [0.5, 0.92]},
      "momo": {"image": "christmas_momo", "size": [64, 80], "anchor": [0.5, 0.92]},
      "trim": {"image": "christmas_frame", "size": [360, 80],
               "caps": [0, 74.14, 0, 74.14], "safeText": [18, 90, 18, 90], "rim": [9.67, 9.67, 9.0, 9.67]},
      "colors": {"accent": "#AA5E58", "light": ["#B97B76", "#AE6660"], "dark": ["#BC807B", "#B16C66"], "contrast": "#9F5751"},
      "days": [["2026-12-25", "2026-12-25", "2026-12-25"], …]
    }
  ]
}
```

- `version`：2。其他版本整份當作沒有主題。
- `leadDays`：節日第一天之前幾天開始穿，現在是 7。
- `id`、`image`：只能用英文字母、數字、`_`、`-`，1 到 64 字，因為會變成檔名。不符合的主題整套略過。
- `names`：`hant` 是美術給的名字（含「・日本」這類標示），`hans`、`ja`、`en` 由匯入腳本補上，`en` 和 `ja` 留給商店文字。
- `size`、`anchor`：圖的邏輯尺寸（pt）和腳的位置（佔寬高的比例，從左上角算）。
- `ends`：匯入時剪下的裝飾，`left`、`right` 各一張（沒有就不放），`size` 是 pt。
- `bust`（在 `mimimo`、`momo` 裡）：貓坐在玻璃後面時，玻璃上緣切過的那一列（`row`）、那一列上不透明的左右範圍（`cut`）和整隻貓的不透明範圍（`bounds`），都是佔圖的比例。
- `caps`、`safeText`、`rim`：上、左、下、右四個數字（pt）。`caps` 是不拉長的兩端；`safeText` 是文字要避開的邊；`rim` 是匯入時量出的飾邊中線離圖邊的距離，用來把飾邊對到玻璃外框。
- `colors`：`light`、`dark` 是選中膠囊的上下兩色，`contrast` 是增加對比時的單色。
- `days`：每年一筆 `[第一天, 正日, 最後一天]`，yyyy-MM-dd，現在收 2026 到 2030 年。單日節日三個相同。

任何一欄讀不懂，那一套略過，其他照常。四個平台的讀法相同：`Sources/Festivals.swift`、Android 的 `ime/Festivals.kt`、Windows 的 `UI/Festival.cpp`。

## 日期的算法

日期寫在 `scripts/import_festivals.py` 的 `DAYS`，素材包裡沒有日期。農曆、希伯來曆、伊斯蘭曆和伊朗曆的日子由 `iOS/tests/FestivalsTests.swift` 對照系統日曆檢查。

| 節日 | 第一天 / 正日 / 最後一天 | 依據 |
|---|---|---|
| 春節 | 除夕 / 初一 / 初七 | 農曆（中國時區）。到初七為止，元宵的前七天從初八開始，兩者不重疊 |
| 元宵 | 正月十五 | 農曆 |
| 端午 | 五月初五 | 農曆 |
| 七夕 | 七月初七 | 農曆 |
| 中秋 | 八月十五 | 農曆 |
| 重陽 | 九月初九 | 農曆 |
| 冬至 | 冬至當天 | 交節時刻換算成 UTC+8 的日期，和農曆的算法一樣 |
| 秋夕・韓國 | 八月十四 / 十五 / 十六 | 韓國的三天連假，農曆以韓國時區算 |
| 光明節 | 基斯流月 25 日起八天 | 第一天是點第一根蠟燭那晚之後的白天 |
| 開齋節 | 閃瓦魯月 1 日 / 1 日 / 3 日 | 烏姆蓋拉曆（Umm al-Qura）。實際日期看新月，各國可能差一天 |
| 排燈節 | 財神節（Dhanteras）/ 吉祥天女祭（Lakshmi Puja）/ 兄妹節（Bhai Dooj） | Drik Panchang，新德里。各地可能差一天 |
| 灑紅節 | 焚燒霍利卡（Holika Dahan）/ 灑色的那天 | Drik Panchang，新德里 |
| 諾魯孜節 | 法爾瓦爾丁月 1 日 | 春分在德黑蘭時間中午以前就是當天，否則是隔天 |
| 復活節 | 復活節主日 | 西方教會的算法 |
| 潑水節・泰國 | 4 月 13 日 / 13 日 / 15 日 | 泰國的三天國定假日 |
| 仲夏節・瑞典 | 仲夏夜 / 仲夏夜 / 仲夏日 | 仲夏夜是 6 月 19 到 25 日之間的星期五，仲夏日是隔天 |
| 新年、情人節、女兒節・日本、白色情人節、兒童節・日本、七夕・日本、萬聖節、聖誕節 | 1/1、2/14、3/3、3/14、5/5、7/7、10/31、12/25 | 固定日期 |

查核過的來源：香港天文台的農曆與節氣表、hebcal、aladhan（烏姆蓋拉曆）、Drik Panchang、美國海軍天文台的節氣時刻、dallyeok.com（秋夕）。復活節用 computus 算，再對照公開的日期表。

2031 年以後的日期還沒加。要加的時候在 `DAYS` 補上一年，查核後重新匯入，並重寫下面的逐日對照檔。

## 同一天有好幾個節日時

每天只穿一套，只由日期和開關決定：

1. 正日的節日優先。每個節日在自己的正日一定會穿到，包括落在較長節日期間的單日節日（例如 2027 年的萬聖節是排燈節的最後一天），隔天再換回較長的節日。
2. 其次是在自己節日期間（非正日）的節日。幾個同時在期間內時，最晚開始的那個優先（2030 年開齋節的三天落在春節期間，整段穿開齋節，之後換回春節）；同時開始的，先結束的優先。
3. 最後是在前七天裡的節日，日期最近的那個優先。
4. 正日相同、又在同一層的節日（中秋和秋夕每年都是）按日輪流，每天最多換一次。
5. 使用者關掉的節日跳過，改由下一個候選遞補。全部關掉（「跟著節日換裝」關閉）就一直穿平常的樣子。

2026-11-12 到 2027-12-31 每天穿哪套：`docs/festivals/calendar-2026-11-12-to-2027-12-31.txt`。2026 到 2030 年每一天的結果（全開，以及關掉四套）在 `Android/app/src/test/resources/festivals/worn-2026-2030.txt`。三個平台的測試都逐日比對這個檔，所以規則不會在某個平台走樣。故意改了規則或日期之後，用下面的指令重寫它，再看差異是不是預期的：

```sh
FESTIVALS_WRITE_WORN=1 bash scripts/test-ios.sh --only Festivals
```

## 設定

iOS App 的「節日衣櫥」頁：

- 「跟著節日換裝」：預設開啟。關閉後一直穿平常的樣子。
- 下面只列這台裝置已經遇過的節日，每個一個開關，關掉的從此跳過（正在穿的當天就換掉）。還沒遇過的節日不列出名稱也不給圖，頁尾只寫一句「還沒遇到的節日不會列出」。

「遇過」的算法：App 第一次啟動這個版本時記下當天日期（`festivalWardrobeSince`，只記一次）。某個節日只要有一次穿著期間（前七天到最後一天）在這天之後開始過、而且已經開始，就算遇過。鍵盤沒有完整取用權，不能寫入共用設定，所以這件事由 App 從日曆算，鍵盤不記錄任何東西。

開關存在共用設定的 `festivals` 和 `festivalsSkipped`，鍵盤只讀（`Preferences.festivalWardrobe`），用 `KeyboardFestival.today()` 決定當天穿哪套。

macOS、Android 和 Windows 目前只有「節日裝扮」總開關。

## 放上候選列的樣子

主人同意素材可以裁剪、移動、縮放，不必照原樣整張套在候選列上。現在的做法：

- 不鋪整張 360×80 的飾邊。候選列只有兩端有空位：左端從螢幕邊到第一個候選只有十幾 pt，右端在展開箭頭和螢幕邊之間約 40 pt。匯入時把飾邊兩端的裝飾從玻璃管上剪下（`ornaments()`，依玻璃管本身的顏色分開），右端那一團存成 `<id>_right`，放在右端：坐在玻璃下緣、不超過玻璃高度、不碰箭頭（`FestivalEndsLayout`）。左端那一團放上去會小到看不清，所以不放；縮出來不到玻璃高度 60 % 的一律不畫。
- 穿節日衣服的貓（64×80 坐姿）整隻放在玻璃上緣只剩不到一半大，所以改成跟平常的探頭一樣坐在玻璃後面：玻璃上緣從胸口切過（`bust`，從畫面頂端往下 56 %），頭、帽子和領口露在上面。節日當天兩隻貓固定坐在兩端（`ends`），放不下時用當天平常的貓。
- 選中候選的膠囊用主題顏色；增加對比時用原本的顏色。
- 每套可調的參數在 `scripts/import_festivals.py` 的 `ADAPT`：用哪一側的裝飾、玻璃管的寬度和顏色容許值、去掉小碎片的門檻、切胸口的位置。改了重新匯入即可，不手改 PNG。

各平台：iOS 鍵盤與 App 的預覽、macOS 候選窗都有貓、右端裝飾和膠囊顏色。Android 和 Windows 目前只有膠囊顏色，貓和裝飾還沒畫。

## 匯入新的素材包

素材包是唯讀的，匯入腳本是素材進專案的唯一途徑：

```sh
python3 scripts/import_festivals.py ~/…/Meowpuut_Festivals_24_Collection ~/…/Meowpuut_Festivals_v1 ~/…/Meowpuut_Festivals_v2
```

需要 Python 的 Pillow、numpy 和 scipy。可以一次給好幾個包，主題照包的順序和各包清單的順序排。同一套出現在兩個包裡時，兩邊的檔案必須逐位元相同，否則停下來。

加一套新主題：

1. 在 `DAYS` 寫五年的日期（附上算法和來源的註解），在 `NAMES` 寫簡體、日文、英文名稱。腳本會檢查包裡的主題和這兩張表完全對得上。
2. 執行匯入。腳本對照 `SHA256SUMS.txt`、檢查尺寸、透明邊、腳的位置，量出 `rim`，從主色算出膠囊顏色，寫出各平台的清單和圖。
3. 執行 `bash scripts/test-ios.sh --only Festivals`，用上面的指令重寫逐日對照檔，看新節日落在哪些日子、有沒有擠掉別的節日的正日。
4. 用 Lab 看每一套：`bash scripts/ios-keyboard-shots.sh <模擬器> <資料夾> "名稱:-inputMode pinyin -type nihao -festivalTheme <id>"`，淺色和深色、三種造型都看過再提交。

各平台只拿到要畫的圖（兩隻貓和剪下的裝飾），整張飾邊只留在 `assets/festivals/` 當紀錄。iOS 是 @2x 和 @3x 的 asset catalog，只放在鍵盤擴充裡，App 從內嵌的擴充讀（不放 @1x，App Store 只給裝置需要的那一組）；macOS 是 @1x 和 @2x；Android 和 Windows 是 @3x 一張，大小由程式按清單算。

## 美術交件的內容

一個素材包資料夾，內含：

- `manifest.json`，每套一筆：`id`、`name`（繁體名稱）、`catLogicalSize`（[64, 80]）、`catAnchorNormalized`（[0.5, 0.92]）、`frameLogicalSize`（[360, 80]）、`frameCapInsets`、`safeTextInsets`（上、左、下、右）、`accent`（#RRGGBB）。
- `png/<id>/<id>_mimimo@1x.png`、`@2x`、`@3x`，`_momo` 和 `_frame` 同樣三種倍率。RGBA，尺寸剛好是邏輯尺寸乘倍率，四邊留透明（邊上的 alpha 不超過 8），貓的腳底和 `catAnchorNormalized` 的高度相差不超過 0.04。
- `SHA256SUMS.txt`，列出每個 PNG。
- 每個節日的簡短文化說明（例如 `文化參考.txt`），寫給開發者核對日期和用詞。

飾邊是照膠囊形畫的：中線順著玻璃外框，兩端的裝飾不要伸進 `safeTextInsets` 以內。波浪和雲朵造型的玻璃只畫兩端，中段的直線不畫。

## 開發時看別天的主題

出貨的 App 和鍵盤沒有這種功能，也沒有隱藏手勢。只有下面這些地方可以：

- Lab App（`MeowpuutKeyboardLab`，不出貨）：`-festivalDay 2026-12-25` 看那天穿哪套，`-festivalTheme halloween` 直接穿某一套，`-festivalOutfits NO`、`-festivalsSkipped '(christmas)'` 模擬設定。這段程式碼只在 `KEYBOARD_LAB` 編譯條件下存在。
- 測試：`Festivals.theme(onDay:in:wardrobe:)` 可以指定任何日子。
- 截圖：Lab 加 `-festivalTheme`（`scripts/ios-keyboard-shots.sh`）；真正的系統鍵盤用 `FESTIVAL_SHOTS=1 bash scripts/ios-real-keyboard-shots.sh`，計畫的 settings 裡寫 `"festivalShotDay"`。
