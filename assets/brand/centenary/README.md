# 百爪典藏 · Hundred Paws

銷量破百紀念皮膚。2026-11-18。

原生內建皮膚 ID 為 `centenary`，在鍵盤衣櫥自行選用，無需答題或兌換。iOS 與 Android 使用同一份色票與雙貓美術，原有鍵盤與使用者已選皮膚不會被自動替換。

## 視覺規格

淺色是奶油瓷白與香檳金，深色是午夜藍與暖金。候選膠囊使用深銅金底白字，鍵帽採清楚的實色與細金邊。保留字體、觸控區域和鍵盤高度。

米米莫改為睜大金眼、穿象牙白禮服背心／午夜藍領帶／金爪胸針；莫莫穿午夜藍短斗篷／香檳金領結，表情與演出獨立設計。每隻貓都有迎賓、鞠躬、歡呼、抱章休息四張全新造型。所有休息、反應及拖曳路徑都使用新素材，不混回日常版舊姿勢。

摸摸觸發 560 ms 致謝鞠躬；選詞上屏觸發 640 ms 雙貓歡呼，間隔至少 1400 ms。沒有持續慶祝動畫，減少動態效果時停用旋轉與縮放。

空白鍵左側空餘區域放置原生繪製的 `100` 紀念章，只在鍵寬至少 128 pt/dp 且字樣不會重疊時顯示。徽章沒有獨立點擊區域，空白鍵文字維持置中。淺色徽章數字使用深銅金以維持對比。

| Token | Light | Dark |
| --- | --- | --- |
| Deck top | `#F5EDDE` | `#1C2635` |
| Deck bottom | `#E9DCC6` | `#101824` |
| Character key | `#FFFCF5` | `#344255` |
| Utility key | `#E4D4B8` | `#202C3B` |
| Main text | `#302A22` | `#F8EFDC` |
| Secondary text | `#6D5D48` | `#D4BE96` |
| Gold ornament | `#A67C3D` | `#D7B46F` |
| Selected top | `#805E30` | `#805E30` |
| Selected bottom | `#684920` | `#684920` |
| Selected text | `#FFFFFF` | `#FFFFFF` |
| Action key | `#76542A` | `#76542A` |

設計色票文字對比：淺色主字至少 9.74:1、次字至少 4.69:1；深色主字至少 8.93:1、次字至少 8.42:1；白字對選中膠囊至少 5.90:1。實際渲染仍須核對深淺色、窄螢幕、橫向、九鍵、高對比與降低透明度。

## 美術來源

- `hundred-paws-duo.png`：1536 × 1024、RGBA、透明背景。衣櫥專用紀念美術，不作鍵盤背景，不替換按鍵或文字。
- `sources/mimimo-poses.png`、`sources/momo-poses.png`：內建圖像工具生成的兩張 2×2 原始圖集，每張 1254 × 1254。由左至右、由上至下為 idle、bow、cheer、doze。
- `sprites/`：8 個姿態 × 1x／2x／3x，共 24 張透明 PNG。標準畫布 64 × 64 pt，足部錨點 (0.5, 0.90)。`sprites.json` 記錄實測透明邊界。
- `export-sprites.swift`：CoreGraphics 切分圖格、統一畫布與匯出尺寸，不繪製或修改角色內容。兩端使用同一份素材與幾何。
- 參考：現有 `assets/brand/cats/reference/hero.png` 提供貓種、毛色和雕塑材質。服裝、動作及表情是本次全新設計。
- 生成工具：內建 `image_gen`，未使用 API／CLI 回退。
- 原始輸出保留在 Codex `generated_images`，此處為專案正式副本。

最終造型與主視覺的生成提示見 [prompts.md](prompts.md)。以下保留初稿提示作來源紀錄；初稿未作為正式雙貓造型。

### Initial concept prompt

```text
Use case: identity-preserve / stylized-concept. Project asset for Meowpuut keyboard's first 100-sales commemorative skin, named Hundred Paws. Create a premium collector-edition transparent cutout illustration, landscape 1536x1024. Use the reference image ONLY to preserve the identity and exact sculpted character style of the two Meowpuut cats: left charcoal-black cat with sleepy golden yellow eyes, right cream Siamese with chocolate ears/mask/paws and large pale blue eyes. Preserve their facial shapes, ear shapes, matte fine-grain vinyl texture, minimal cute mouth, proportions and personalities. Remove all keyboard/candidate bar elements. New composition: both full-body cats seated closely and affectionately, black cat slightly taller on the left, Siamese slightly smaller on the right, full tails/paws entirely in frame. A small exquisite freestanding champagne-gold collector's medallion rests between their front paws, with the numeral '100' precisely embossed in the center. The medallion has a thin double rim and one tiny paw-shaped engraving above the number, no other wording. Add a small midnight-blue silk bow with fine gold trim at the Siamese's neck and an understated warm ivory bow on the black cat. Gentle happy celebratory expressions while keeping the black cat's characteristic sleepy eyes. Restrained luxury collectible toy photography: finely brushed champagne gold, subtle warm edge highlights, immaculate soft studio lighting, gentle contact shading under paws, no harsh shine or glitter. Palette ivory, champagne gold, midnight blue, natural existing fur colors. Center entire artwork with generous transparent margin all around; crisp alpha edges with no opaque background, no cut-off ears/tails, no white outline. No balloons, confetti, banners, cakes, UI mockup, Chinese text, watermark, clutter or extra cats. Render polished final production artwork, not a sketch.
```
