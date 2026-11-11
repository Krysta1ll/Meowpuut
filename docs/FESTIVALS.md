# 節日裝扮

台灣節日和國際節日（不含政治性節日）可以給米米莫和莫莫換裝。裝扮在節日前才製作，平時清單是空的，四個平台都照常顯示原本的貓。

## 清單

`assets/festivals/festivals.json`：

```json
{
  "version": 1,
  "outfits": [
    {
      "id": "lunar-new-year-2027",
      "title": { "hant": "春節", "hans": "春节" },
      "start": "2027-02-03",
      "end": "2027-02-12",
      "cats": {
        "mimimo": { "front": "mimimo_front.png" },
        "momo": { "front": "momo_front.png", "back": "momo_back.png",
                  "frames": { "happy": "momo_happy.png" } }
      },
      "pill": "#C8463C"
    }
  ]
}
```

- `start`、`end`：裝置當地日期，含頭含尾。農曆節日每年日期不同，逐年填寫。
- `front`：畫在貓前面（帽子、圍巾）；`back`：畫在貓後面（披風、燈籠）。
- 桌面版（macOS、Windows）的米米莫只畫玻璃外面的部分：玻璃上緣以上和左緣以左。他的裝扮畫帽子，不畫圍巾。
- `frames`：要換掉的表情格（`open`、`happy`、`blink`），沒列出的沿用原圖。
- `pill`：選填，節日期間選中候選的顏色。
- 期間重疊時，清單裡排前面的優先。

## 圖檔

每張圖都畫在該貓母版的同一張畫布上：尺寸與 `assets/brand/cats/mimimo_open.png`（或 `momo_open.png`）相同，透明背景，位置直接對齊母版。這樣各平台不需要額外的錨點。

把圖放進 `assets/festivals/<id>/`，再執行：

```sh
python3 scripts/render_cats.py
```

腳本會檢查尺寸，並輸出到 iOS（`iOS/Festivals`）、macOS（`Resources/Festivals`）、Android（`app/src/main/assets/festivals`）和 Windows（`UI/Assets/festivals`）。

## 用戶設定

各平台的外觀設定裡有「節日裝扮」開關，預設開啟。關閉後只顯示原本的貓。
