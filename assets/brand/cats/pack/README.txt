喵撲 · 06 號角色素材包 v1
米米莫 & 莫莫

先看哪裡
• preview/contact-sheet.png：8 個基本表情與 64 px 深淺底檢查。
• preview/candidate-preview.html：直接用瀏覽器開啟，看候選列與摸摸回饋。
• preview/meowpuut-duo-idle.mp4：4 秒、1080p、30 fps 預覽影片。

軟件素材
software/png：16 個獨立角色姿勢，各有 @1x / @2x / @3x，共 48 張透明 PNG。
其中 8 個基本表情為下列 64 pt 規格；另 8 個搭邊姿勢使用獨立尺寸，見下節。
標準顯示尺寸 64 × 64 pt；像素分別是 64、128、192。可顯示為 40–64 pt。
software/ios/MeowpuutCats.xcassets：直接加入 Xcode，使用 mimimo_idle、momo_happy 等名稱。
software/android/drawable-nodpi：192 px 透明圖；顯示大小請用 dp 明確設定，勿按原始 px 放大。
software/web：192 px 透明圖，建議 CSS width: 64px 或以下。
software/master-512：512 px 統一畫布的工作輸出。這是輸出尺寸，不代表每個表情都有 512 px 原生細節；原生裁切尺寸記錄在 manifest.json。
基本表情素材都是 sRGB、RGBA 透明 PNG。各姿勢底部對齊，基準錨點為畫布頂部計算的 (0.5, 0.9)。耳朵與尾巴留有透明邊界。

狀態名稱
米米莫：idle 待機、press 伸爪、happy 被摸、sleep 睡覺。
莫莫：idle 待機、wave 舉爪、happy 被摸、jump 開心跳起。
每個狀態是獨立圖。狀態間沒有聲稱是骨架插值或連續動作捕捉。

候選框搭邊姿勢
• edge_rest：雙爪搭邊，72 × 80 pt。
• edge_lounge：側躺趴著，112 × 72 pt。
• edge_peek：探頭，64 × 64 pt。
• side_hold：側邊扶框，64 × 88 pt；米米莫在左、莫莫在右。
兩隻貓各有以上 4 個姿勢，皆已加入 iOS、Android、Web 輸出。
software/edge-anchors.json 記錄實際尺寸與爪子接觸點；座標原點在圖片左上。
將接觸點對到候選框邊緣：圖片左上位置 = 接觸目標位置 − 圖片顯示尺寸 × anchorNormalized。
上緣搭邊用候選框頂部作目標；側邊扶框用左右邊緣中點。不要把 8 張不同長寬比的圖片強行拉成正方形。
preview/candidate-poses.png 是四種搭法的總覽；preview/edge-preview.html 可離線切換姿勢、深淺色及寬度。
software/edge-masters 是較大畫布的工作輸出，原生細節仍以 nativeTrimmedPixels 為準。
這 8 個新增姿勢為靜態素材，沒有另製逐幀動畫。

動畫
software/animation 下有 4 段可播放動畫：
• mimimo_idle / momo_idle：48 幀，24 fps，2 秒循環，輕微呼吸與擺動。
• mimimo_pet / momo_pet：18 幀，24 fps，0.75 秒，開心表情的輕彈回饋，播放一次。
每幀是 192 × 192 透明 PNG，frame_000.png 起順序播放。
atlas.png 以左上角起、由左至右逐列排列；欄數與幀數見 manifest.json。
這些動畫由同一張角色圖作 2D 位移、縮放與旋轉生成，沒有眨眼口型動畫，也不包含 3D 模型、骨架、Lottie 或 Rive 檔案。
GIF 只用作預覽，正式程式請用 PNG 或原生 transform。低記憶體鍵盤優先用單張 PNG 配合原生動畫；進入背景、不可見或開啟「減少動態效果」時停止。
software/examples/CatMascot.swift 提供 UIKit 單張圖的輕量摸摸回饋；不預載整段動畫。

影片素材
video/meowpuut-duo-idle-alpha.mov：1920 × 1080、30 fps、4 秒、Apple ProRes 4444，保留 Alpha，無聲音。可直接放入支援 Alpha 的剪輯軟件。
preview/meowpuut-duo-idle.mp4：同一動畫加米白背景的 H.264 預覽，沒有 Alpha。
video/masters：兩隻貓獨立的 1024 px 透明工作圖。
video/keyframes：影片中每秒抽出的 4 張透明／背景預覽關鍵幀。
沒有把插畫放大後標成原生 4K；影片以 1080p 交付。

分鏡與圖片
sources/hero.png：雙貓與玻璃候選列主視覺。
sources/storyboard.png：六格完整分鏡。
storyboard/shot-01.png 至 shot-06.png：獨立分鏡畫面。
storyboard/timing.csv：建議的鏡頭時長、動作與銜接。
分鏡是後續製作短片的參考，不是已完成的六鏡頭動畫；目前完成影片是雙貓待機循環。

生成與重建
sources 保留未覆蓋的生成原圖。角色插畫由內建圖像工具依本機貓咪照片與已選定的 06 設計生成；尺寸、透明導出、動畫與影片用 CoreGraphics / AVFoundation 製作。
tools/build-assets.swift、tools/export-edge.swift、tools/verify.swift 為本次匯出及驗證腳本。
validation-video.json 記錄解碼後的幀率、尺寸、時長與 Alpha；validation-images.json 記錄 PNG／圖集檢查。
