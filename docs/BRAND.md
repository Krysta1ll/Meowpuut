# Meowpuut 視覺設計簡報

2026-11-06，使用者確定：美學主導、有藝術感；米米莫和莫莫是雙主角；不要模板化宣傳文案。Logo、應用圖示、字標、候選窗和行動鍵盤需要屬於同一套視覺語言。

角色資料由使用者補充：米米莫為黑色公貓，調皮機靈、聰明；莫莫為白色重點色母貓，乖巧溫柔、偶爾呆萌，也很聰明。兩位都是使用者的貓。現在的造型是使用者照著牠們的照片自己做的黏土風格設計（06 號素材包，匯入在 `assets/brand/cats/`），程式不另外畫貓。

## 已選定的標誌

2026-11-12 起用新設計的貓：

- 應用圖示（`assets/brand/AppIcon.png`，使用者選定的「大頭特寫」）：兩隻貓的頭，米米莫在左後方，莫莫在右前方，暖米色底。iOS 用整張方圖，macOS 由 `scripts/Icon.swift` 套成圓角方塊，Android 的前景和單色圖層跟著同一張圖。
- 標誌（`assets/brand/MeowpuutMark.png`，透明底）：兩隻並排坐著的全身像，用在 README、App 首頁和安裝程式。

介面操作色沿用 01 的朱紅。

## 跨平台材質

雙貓、暖紙色與朱紅是兩端共用的識別。iOS 試打頁以雙貓、粗字標與注音字樣構成頁首，鍵盤設定入口沿用原生導航列。鍵盤的繁簡、短語與設定共用一組原生玻璃操作列。macOS 的試打區使用同一紙色，候選窗在翻頁操作使用玻璃。鍵帽、編輯器與候選字面保有實色或標準內容表面。

玻璃採原生 regular 材質，由系統適配外觀與輔助使用設定。參考 [Apple · Materials](https://developer.apple.com/design/human-interface-guidelines/materials)。

## 概念探索

1. 字形：以注音字形與實驗排版建立識別，檢查雙主角如何進入標誌輪廓和負空間。
2. 紙墨：從紙張、印刷與版畫借鑑色彩和邊緣關係，產品輸入區域保持清晰。
3. 器物：從按鍵、觸感與簡潔幾何體建立圖示和控制項的一致比例。

三套概念保存在 `assets/brand/concepts`。

## 參考研究

- [Bauhaus-Archiv · bauhaus.typography](https://bauhauskooperation.de/en/knowledge/publications/publikationen-zum-bauhaus/bauhaustypography)：字形與構成關係。
- [Letterform Archive · Bauhaus magazine](https://exhibitions.letterformarchive.org/bauhaus/artworks/bauhaus-magazine-for-design-bauhaus-zeitschrift-fu-r-gestaltung-vol-3-no-1)：印刷尺度、網格和對比。
- [Pentagram · The Joslyn](https://www.pentagram.com/work/the-joslyn)：從客製字體建立整體識別，而非只做孤立標誌。
- [Pentagram · MoMA](https://www.pentagram.com/work/moma)：跨媒介的版式一致性與藝術內容的呈現關係。
- [Apple · Materials](https://developer.apple.com/design/human-interface-guidelines/materials)：品牌設計仍需要在原生系統層級、材質和閱讀清晰度中成立。

參考用於研究方法與構成，不複製已有標誌或把其他品牌名稱放進產品。
