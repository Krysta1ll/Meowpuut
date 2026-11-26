# 喵撲陪伴 App（Companion）

Steam 在 macOS 與 Windows 上啟動的那個程式：首頁那把真鍵盤、行旅（含「時間花在哪裡」）、鍵盤衣櫥、爪印本、鍵盤設定（含桌面貓、時間花在哪裡與好友狀態），加上第一次開啟的七步。設計以 `design-preview/pc/FINAL.md` 為準（§2 每一頁、§5 第一次開啟、§6 Steam、§7 架構、§8 隱私、§10 決定、§11 時間花在哪裡與好友狀態）。

這是 **Tauri 2 薄視圖**（FINAL §7.1）：打開視窗才存在，關掉視窗程式就結束；不裝任何全域鉤子。它讀輸入法寫的字數帳本與貓窩寫的時間帳本、讀寫幾個輸入法與貓窩的設定，和自己的一個小檔案。它是**唯一**和 Steam 說話的喵撲程序（`steam` 功能，預設不編進來）：開窗時結算一次成就；使用者自己打開「讓 Steam 好友看到我在做什麼」時，分享期間可以沒有視窗、只留一個選單列／通知區域的小圖示繼續跑（§11.2），時間到或按「停止分享」就清掉那一行並結束。

字景的畫（行旅的長卷、引首、首頁那把鍵盤、第一次開啟每一步）由 Rust 字景引擎 `meowpuut-glyph` 畫，見「字景的畫」。

## 怎麼跑

需要 Node 20 以上、Rust（stable）。在這個資料夾裡：

```sh
npm install
npm run tauri dev
```

**試的時候不要碰真的帳本。** 用 `MEOWPUUT_DATA_DIR` 指到一個暫存資料夾，再用 Rust 的帳本 API 產生一份樣本：

```sh
cd src-tauri
cargo run --example sample_ledger -- /tmp/meowpuut-sample        # 約 230 天、各種級距、兩隻貓、五個時段
cd ..
MEOWPUUT_DATA_DIR=/tmp/meowpuut-sample npm run tauri dev
```

資料夾裡沒有 `Companion.json` 時會先走第一次開啟的七步；按右上角「之後再說」就略過。

### 環境變數（開發用）

| 變數 | 作用 |
| --- | --- |
| `MEOWPUUT_DATA_DIR` | 換掉資料夾（帳本、`settings.json`、`Companion.json`）。設了它，Mac 不發設定變更通知；Windows 不寫登錄檔，改寫同資料夾的 `registry-preview.json`。頁尾會標「試用資料夾」。 |
| `MEOWPUUT_PLATFORM=mac\|windows` | 照另一個平台的規則畫（鍵盤、快捷鍵、設定）。在 Mac 上預覽 Windows 版時，設定一律寫進資料夾裡的替身檔。 |
| `MEOWPUUT_THEME=light\|dark` | 不改系統外觀，直接看淺色或深色。 |
| `MEOWPUUT_SCRIPT=hant\|hans` | 不改系統語言，直接看繁體或簡體。 |
| `MEOWPUUT_START` | 從某一頁開始，例如 `book`、`journey?lens=month`、`settings?at=privacy`、`home?hold=1`（⌘ 地圖）、`home?post=xl`（路牌）、`setup?step=3`，以及（預覽與截圖用）`journey?lens=day&at=lead&pos=…&day=…`、`journey?pick=從-到&shoot=1`（框一段並按「存成長條」）、`journey?bench=1`（捲 8 秒，量幀時間與圖塊延遲，debug 建置印到 stderr）、`home?key=KeyM,KeyE`（這幾個鍵按著）、任何一頁加 `&dpr=1`（在 2× 螢幕上照 1× 畫，k = 1）。 |
| `MEOWPUUT_CAPTURE`（需 `devcapture` 功能） | 見「截圖」。 |
| `MEOWPUUT_WINDOW=3440x1440` | 開窗就是這個（邏輯）大小，看帶魚屏；螢幕放不下時會被系統縮小。 |
| `MEOWPUUT_SHARE_DIR`（只有 debug 建置） | 「截一段」的圖不問、直接存到這個資料夾（截分享圖用）。 |
| `MEOWPUUT_TILE_LOG=1` | 每一塊圖、每一幀鍵盤在 Rust 這一側花幾毫秒，印到 stderr。 |
| `MEOWPUUT_DESK_NOW` | 不問貓窩，固定回答「現在在做什麼」：`code:VS Code`（類別代碼加 App 名稱）、`fullscreen:web`、`idle`、`paused`、`hidden`、`off`。沒有簽章的開發版問不到真的貓窩（它只認 `app.meowpuut.companion` 的簽章），試分享模式時用。 |
| `MEOWPUUT_STEAM_LOG=1`（只有 debug 建置） | 不連 Steam，把每一個 Steam 呼叫印到 stderr（`steam(log): SetRichPresence kind="code"`）。不需要 Steam 帳號就能試分享模式、時限、系統匣圖示與無視窗模式。 |

## 建置與測試

```sh
npm run build            # 型別檢查（tsc）＋ vite 打包到 dist/
npm run tauri build      # 正式的 .app / .dmg（Mac）或 NSIS 安裝程式（Windows）
cd src-tauri
cargo build
cargo test               # 帳本與時間帳本的讀取與清除、設定 trait（兩個平台、桌面貓與時間花在哪裡的鍵）、
                         # 時間頁的彙總、成就結算（假的 Steam）、好友狀態的決定（開關、時限、全螢幕）、系統語言
cargo clippy --all-targets -- -D warnings
cargo build --features steam    # 連上 Steamworks 的版本（見「Steam」）
```

Windows 版可以在 Mac 上先做型別檢查：`cargo check --target x86_64-pc-windows-gnu --lib --tests`（登錄檔、貓窩的共享記憶體、SMTC 那幾段只在 Windows 編譯）。測試都不需要 Steam 客戶端：Steam 只透過 `SteamApi` 這個 trait，測試用假的。

## 結構

```
Companion/
  index.html            一個視窗、一頁
  src/
    main.ts             換頁（#/home、#/book、#/journey、#/settings、#/closet、#/setup）、視窗內的按鍵與快捷鍵
    api.ts              後端指令的型別；在一般瀏覽器裡（npm run dev）用假資料代答，只為了改頁面
    store.ts            開窗時讀一次的資料、皮膚
    skins.ts            皮膚系統：代幣＋插槽（照手機 AppSkin.swift），五款皮膚、日常玻璃的六個色系
    glyph.ts            字景的圖：讀二進位回覆、k 的規則（k = argmin |12k − 14.5 × 螢幕倍率|）、畫到 canvas
    keyboard.ts         首頁 hero（字景皮膚是引擎畫的一張圖，鍵事件交給引擎；紙皮膚照舊）：照真的配置畫（Mac ⌘⌥⌃ fn／Windows Ctrl ⊞ Alt；全鍵拼音或注音大千）、按鍵亮、⌘ 地圖
    signpost.ts         ⌘K／Ctrl+K 路牌：拼音頭字母（xl → 行旅）、注音頭一個符號、或直接打中文
    i18n.ts dates.ts art.ts nav.ts
    categories.ts       時間花在哪裡的十類：名稱、固定顏色、分鐘的寫法
    pages/              home、book（爪印本）、journey（行旅）、time（時間花在哪裡）、settings（鍵盤設定）、closet（鍵盤衣櫥）、
                        setup（第一次開啟）
    art/                手機像素遊戲機的兩張 28 px 頭像（原樣複製，見 art/README.md）
    styles.css
  src-tauri/
    src/lib.rs          指令：app_info、ledger_summary、ledger_clear、reveal_ledger、open_input_settings、
                        settings_read、settings_write、companion_read、companion_update、steam_status、
                        activity_view、activity_clear、pick_app、share_status、share_stop、presence_preview；開窗時的成就結算
    src/glyph.rs        字景的畫：scroll_open、scroll_tile、scroll_roller、scroll_share、scroll_last_place、lead_info、lead_picture、
                        board_open、board_frame、glyph_here、guide_picture；一律回二進位（64 位元組標頭＋RGBA），不是 base64
    src/shot.rs         截一段的最後一步：檢查 PNG、交給 Steam 截圖庫（ISteamScreenshots）或存檔
    src/ledger.rs       帳本（讀、清除）
    src/activity.rs     時間帳本 Activity.bin（讀、清除）與「時間花在哪裡」頁的彙總
    src/settings.rs     設定 trait 與 Mac／Windows 兩個實作（含桌面貓、時間花在哪裡的鍵）
    src/companion.rs    陪伴 App 自己的 Companion.json（含成就計數、分享開關與時限）
    src/share.rs        好友狀態：貓窩回答的解讀、開關與時限的決定、只在那一行變了才呼叫 Steam（Sharer）
    src/sharing.rs      分享模式的執行期：每 15 秒一次的執行緒、系統匣／選單列圖示、沒有視窗時繼續跑
    src/desk_now.rs     問貓窩「現在在做什麼」：Mac XPC、Windows 共享記憶體、開發用的固定回答
    src/now_playing.rs  正在播放的歌：Mac AppleScript（音樂、Spotify）、Windows SMTC
    src/clock.rs        單調時鐘、牆上時鐘、本地日與分
    src/files.rs        檔案鎖與「寫暫存檔再改名」
    src/paths.rs        資料夾位置、平台
    src/locale.rs       繁簡（跟系統語言，規則同 Mac 輸入法的 InterfaceText）
    src/cloud.rs        Steam 雲端（預設關）：逐機帳本檔、合併、關掉時刪除、只准固定長度帳本的關卡
    src/steam.rs        SteamApi trait、成就結算、steamworks-rs 的實作（steam 功能）
    build.rs            steam 功能：把 Valve 的 steam_api 動態庫放到執行檔旁與 steam-redist/
    steam_appid.txt     480（Valve 的公開測試 App「Spacewar」），只給開發用
    Info.plist、Entitlements.plist   「顯示正在聽的歌」的 Automation 說明文字與 Hardened Runtime 權限
    tauri.steam-macos.conf.json、tauri.steam-windows.conf.json   steam 版打包時把動態庫放進安裝包
    src/devcapture.rs   開發用截圖（devcapture 功能）
    examples/sample_ledger.rs   產生樣本帳本（PetJournal.bin 與 Activity.bin）
  shots/                1× 截圖
```

頁面不用框架，純 TypeScript ＋ CSS。黏土貓直接引用本倉庫 `assets/brand/` 的原圖（不重畫、不改色、不降成像素），像素字型用 Android 已出貨的「Meowpuut Pixel A」（俐方體 11 號子集，`Android/app/src/main/assets/fonts/pixel_a.ttf`）。

## 每一頁

| 頁 | 快捷鍵 | 內容 |
| --- | --- | --- |
| 首頁 | ⌘0／Ctrl 0 | 字標、那把鍵盤（鍵距 44，1280 × 800 不捲動就看得到生活照的上緣）、試打線（真的文字框，換到喵撲就是你的候選窗）、四個入口、生活照、隱私句。按鍵只在視窗有焦點時亮（視窗自己的按鍵事件，沒有全域監聽）；按住 ⌘／Ctrl 鍵盤變成地圖，貓跳開。 |
| 鍵盤設定 | ⌘1 | 輸入方式（Mac 寫 `inputMode`；Windows 兩個鍵盤都裝了，這裡只選首頁畫哪一種）、繁簡、中日混打、候選字大小；**桌面貓**（開關；打字時「陪走」＝乙、「都在玻璃上」＝甲，FINAL §4.2）；**時間花在哪裡與好友狀態**（記錄、暫停、不記錄的 App、讓 Steam 好友看到、顯示 App 名稱、顯示正在聽的歌、分享多久、清除時間紀錄；四個開關第一次打開時先跳一頁白話說明，附上好友會看到的那一行，由 Rust 的 presence builder 產生）；Steam（成就開關可用；只在這台電腦的成就；雲端還沒接）；隱私與帳本：「喵撲知道的／不知道的」、帳本原文、打開檔案位置、清除貓咪紀錄（連時間紀錄一起清，要再確認一次）。 |
| 鍵盤衣櫥 | ⌘2 | 五款：No.01 日常玻璃（預設）、No.02 經典雲玻璃、No.03 像素遊戲機、No.100 百爪典藏、No.04 字景。每張卡預覽候選窗與鍵帽。穿上會換首頁鍵盤和頁面的紙；**候選窗目前只穿得了日常玻璃**，其他四張卡照實寫「候選窗之後才換得上」。Mac 的日常玻璃可以改六個色系與三種形狀（寫進 settings.json）；Windows 固定膠囊・玫瑰奶茶。 |
| 時間花在哪裡 | 行旅右上 | `Activity.bin` 的天 × 十類 × 分鐘：這一週每天一根堆疊長條（‹ › 換週，滑過看每一段）、這個月每一類的總數（固定順序，不排名次）、一天裡的五個時段（這個月有幾天在那個時段在電腦前，算天數）。沒有連續天數、沒有排名、不和上週比。記錄關著時是說明頁，連到開關。十類的顏色用 dataviz 的驗證器在日常玻璃的紙上淺深兩色都驗過（相鄰色盲 ΔE 淺 11.0／深 9.9，一般視覺 19.3／19.3；「其他」刻意是灰）。 |
| 行旅 | ⌘3 | 一整條長卷，左邊是帳本最早的那天，右邊是今天兩隻貓站的地方。年（每天一座山，高度＝那天的級距）、月（加日期與印，月與月隔水）、日（今天那段路）。最左是引首：累計字數、去過的地方、一天裡的時刻、山有多高、兩隻貓。滾輪前後、← → 一天、Home 引首、End 此刻、Esc 回首頁。 |
| 爪印本 | ⌘4 | 攤開的書：左頁這個月的月曆，打過字的日子一枚爪印（大小是級距；只有莫莫在的日子是棕色），沒打字的日子就是白紙，沒有連續天數；右頁這個月的照片與幾句話。‹ › 翻帳本還留著的月份。 |
| 路牌 | ⌘K | 用正在用的輸入法打：`xl`、`ㄒㄌ` 或「行旅」都找得到。「叫牠們回家」「讓牠們出門」兩塊牌子已經在，貓窩蓋好以前不能走。 |
| 第一次開啟 | — | FINAL §5 的七步，穿字景的代幣與像素字。第 3、4 步分 Mac／Windows。第 6 步是三扇門：門只開一條縫、Steam 雲端、時間花在哪裡（FINAL §11.1 的三個開關、兩個子開關與分享時限，預設全關，打開前先出和設定頁同一份說明與好友看到的那一行；按「開始」才寫入，「之後再說」全部維持關著）。最後「鍵盤就穿字景」可選，不選就是日常玻璃。 |

## 帳本

- 位置：macOS `~/Library/Application Support/Meowpuut/PetJournal.bin`，Windows `%LOCALAPPDATA%\Meowpuut\PetJournal.bin`（固定 2,884 位元組，`meowpuut-pets` 的 `PetState`）。
- 讀：不需要鎖（寫的一方都是整檔改名蓋過）。檔案不見、損壞或版本較新時當作空白，和輸入法一樣，頁面會寫出是哪一種。
- 清除：照 `Sources/PetLedger.swift`／`Windows/PetLedger.cpp` 的寫法，在 Rust 裡做一次：鎖 `PetJournal.lock`（Mac `flock`，Windows `LockFileEx`；忙的話最多等約 2 秒）→ 讀檔 → 複本 `clear()`（epoch 加一）→ 套上它的空 Delta、merge 進去 → 寫暫存檔 → 改名蓋過 → 解鎖。epoch 變大，所以還拿著舊數字的輸入法程序下次寫入時，舊的 Delta 和舊狀態都會被丟掉，然後跟著歸零（有測試照兩邊的 flush 步驟模擬）。讀不懂的新版本檔案，會從它檔頭的 epoch 再往上加。
- 陪伴 App 自己不記任何字數。

## 設定

一個 trait（`SettingsStore`），兩個實作：

- **Mac**：`settings.json`，照 `docs/SETTINGS.md`：鎖 `settings.lock`、讀檔、只改自己那一個鍵（其他鍵原樣寫回，包括不認得的）、寫暫存檔、改名、解鎖，再發 `app.meowpuut.settings.changed` 分散式通知。讀不到的值用輸入法自己的預設值。
- **Windows**：`HKCU\Software\Meowpuut` 的 DWORD：`Simplified`、`MixedJapanese`、`CandidateTextSize`（0 小、1 標準、2 大）。輸入方式不是設定（兩個鍵盤都裝了，由 Windows 的鍵盤清單選），色系與形狀目前沒有，寫入時回傳「不支援」。

桌面貓與時間花在哪裡（貓窩讀，陪伴 App 只寫）：

| 設定 | Mac `settings.json` | Windows `HKCU\Software\Meowpuut` |
| --- | --- | --- |
| 桌面貓 | `deskPets`（布林，預設開） | `DeskPets`（DWORD，預設 1） |
| 打字時 | `typingSeat`：`split`（陪走，預設）／`glass` | `TypingSeat`：0／1 |
| 記錄時間花在哪裡 | `activityStats`（預設關） | `ActivityStats`（預設 0） |
| 暫停記錄 | `activityPaused` | `ActivityPaused` |
| 不記錄的 App | `activityHidden`（bundle id 字串陣列） | `ActivityHidden`（REG_MULTI_SZ，執行檔名稱） |
| 顯示 App 名稱（給貓窩的閘門） | 不寫（XPC 問） | `ActivityShareAppName`（預設 0；分享且該開關開著時才寫 1） |

不記錄的 App 照貓窩的規則整理（去空白、不分大小寫去重、每個最多 255 位元組、最多 256 個）。因為沒有任何地方記得你用過哪些 App，這裡沒有「最近用過」：使用者自己打上 bundle id／執行檔名稱，或按「挑一個 App…」用系統的開檔視窗挑（Mac 讀那個 `.app` 的 bundle id，Windows 取 `.exe` 的檔名）。Mac 的 `catGlassSkin`（米米莫與莫莫）關著時，頁面會說桌面上也不會有貓。

陪伴 App 自己的選擇（穿哪件、第一次開啟走完了沒、Windows 首頁畫哪個鍵盤、雲端與「出門」兩個答案、成就開關與這台電腦知道已解鎖的成就和計數、三個分享開關與時限、哪幾個開關的說明頁已經看過）（含雲端的隨機安裝 ID 與待刪旗標）存在同資料夾的 `Companion.json`，隱私頁也列出它。分享只關陪伴 App 的事，所以那幾個開關不在 `settings.json`；記錄開關貓窩要讀，所以在 `settings.json`／登錄檔。關掉記錄時，分享也跟著關。

## 時間帳本

- 位置：同資料夾的 `Activity.bin`（固定 12,824 位元組，`meowpuut-activity` 的 `ActivityState`），鎖檔 `Activity.lock`。貓窩在「記錄」開著時寫，陪伴 App 只讀，除了清除。
- 讀：不需要鎖。檔案不見、損壞或版本較新時當作空白。
- 清除（「清除時間紀錄」，以及「清除貓咪紀錄」時檔案存在就一起清）：和字數帳本同一套：鎖 `Activity.lock` → 讀 → 複本 `clear()`（epoch 加一，讀不懂的新版本檔案從檔頭的 epoch 再往上）→ 套上空 Delta、merge → 寫暫存檔 → 改名 → 解鎖。還拿著舊分鐘數的貓窩下次 flush 時，舊 Delta 與舊狀態都被丟掉（有測試照貓窩的 flush 步驟模擬）。
- 頁面的彙總在 Rust（`activity::view`）：一週七天各類分鐘、一個月各類總數與有分鐘的天數、五個時段各有幾天（以及那幾天各類出現幾次）。日期換算在頁面（`dates.ts`），傳進來的是本地日的索引。

## Steam

**steamworks-rs**：`steamworks` 0.13.1（`steamworks-sys` 0.13.0，Steamworks SDK 1.64），授權 MIT／Apache-2.0；但 `steamworks-sys/lib/steam/` 裡 Valve 的檔案（標頭與 `redistributable_bin` 的 `libsteam_api.dylib`、`steam_api64.dll`）不在這個授權裡，屬於 Steamworks SDK 的授權（Steamworks SDK Access Agreement，可以隨遊戲散布 redistributable）。放在 cargo 功能 `steam` 後面，**預設關**：一般建置不連 steam_api、不需要 Steam。

- **動態庫怎麼跟著走**：steamworks-sys 只在建置時連結 `libsteam_api.dylib`／`steam_api64.dll`，不會把它放到執行檔旁（dylib 的 install name 是 `@loader_path/libsteam_api.dylib`）。`build.rs` 在開了 `steam` 時把對應平台的檔案複製到 `target/<profile>/`（`cargo run`、`tauri dev` 就能啟動）與 `steam-redist/`（git 不收）。來源是 `STEAM_SDK_LOCATION`（從 partner.steamgames.com 下載的 SDK 的 `sdk/` 資料夾，正式版用它），沒設就用 cargo registry 裡 steamworks-sys 附的同一份 1.64。打包：
  - Mac：`npm run tauri build -- --features steam --config src-tauri/tauri.steam-macos.conf.json`（放進 `Contents/MacOS/`，和執行檔同一份簽章，公證前要一起簽）
  - Windows：`npm run tauri build -- --features steam --config src-tauri/tauri.steam-windows.conf.json`（`steam_api64.dll` 放在安裝資料夾、執行檔旁）
- **App ID**：開發用 480（Valve 的公開測試 App「Spacewar」），寫在 `src-tauri/steam_appid.txt`，從 `src-tauri` 啟動時 Steam 讀它；**不要放進安裝包**（從 Steam 啟動時 Steam 自己給 App ID）。480 沒有喵撲的成就，結算時它們會列為「Steam 後台還沒有」（`refused`），下次再補。
- **開窗時結算成就**（FINAL §6.2，`steam::settle`）：讀帳本 → 更新 `Companion.json` 的計數（`lifetimeDays`：書裡比上次更新的日子才加；`maxDayGraphemes`：今天的精確字數取最大；epoch 變了就全部重來）→ 用 `meowpuut_pets::achievements::evaluate` 算出達成的 → 這台電腦的清單加上新的（App 裡顯示「只在這台電腦上的成就」：清晨的字、深夜的字，以及成就關著時達成的）→ 成就開著、連上 Steam、不是試用資料夾時，Steam 還沒解鎖的 `steam: true` 成就 `SetAchievement`，有新的才 `StoreStats` 一次。沒有任何 Stat、沒有 `IndicateAchievementProgress`。補上時首頁說一句「牠們出門時做到的事，現在補上了。」`Progress` 現在填得了的只有 `lifetime_days`、`max_day_graphemes`；地方、節日、貓窩的計數、截一段都還沒有來源，是 0。
- **好友狀態**（FINAL §11，`share.rs`、`sharing.rs`）：分享開著、記錄開著、連上 Steam、時限沒到時，一條執行緒每 15 秒：問貓窩現在的類別（「顯示 App 名稱」開著才要名稱）→「顯示正在聽的歌」開著才讀歌名 → 交給 `meowpuut_activity::presence::build` → 那一行變了才 `SetRichPresence` 四個 key（空值刪掉舊 key），或 `ClearRichPresence`。全螢幕與遊戲一律「專心中」；暫停、離開、不記錄的 App 是「陪著發呆」。分享關掉、記錄關掉、時限到（1 小時／今天／直到我關掉）就清掉並把開關關回去；結束程式一律清掉。分享中會有系統匣／選單列小圖示（像素貓頭，Mac 用 template 圖）：「打開喵撲」「停止分享」。關掉最後一個視窗時若正在分享，程式留著（Mac 也收起 Dock 圖示），分享結束就自己結束；⌘Q 照常結束。
  - Mac 問貓窩：XPC Mach 服務 `app.meowpuut.desk.activity`，要求對方簽章識別碼 `app.meowpuut.desk`（加本身的 Team ID）。貓窩只認簽章為 `app.meowpuut.companion` 的陪伴 App，所以沒簽章的開發版問不到（分享時就什麼都不送）；用 `MEOWPUUT_DESK_NOW` 試。
  - Windows 問貓窩：私有命名空間（使用者 SID＋Medium 標籤）裡的共享記憶體 `Meowpuut\PulseSlot.v2`，版面 2，位移 128 的 seqlock 與 136 的 `MPActivityCurrent`（`Windows/Desk/PulseChannel.h`）。Windows 的「顯示 App 名稱」靠登錄值 `HKCU\Software\Meowpuut\ActivityShareAppName`（DWORD，預設 0）：陪伴 App 只在「分享開著、記錄開著、時限沒到、『顯示 App 名稱』開著」時寫 1，其餘一律寫 0（關分享、時限到、關記錄、結束程式；每次開啟與每個分享週期都會重新對一次，所以上次當掉留下的 1 會被改回）。貓窩只在它是 1 時才讀前景 App 的顯示名稱（版本資源的 `FileDescription`，沒有就用執行檔名去掉 `.exe`，不讀視窗標題，只放記憶體），放進「現在」一欄的 `app`；這一側只在 `want_app` 時才取用。Mac 不寫這個值（XPC 的 `{"app": bool}` 已經是閘門）。陪伴 App 被強制結束時來不及寫 0，貓窩會繼續把名稱放在共享記憶體裡（只有同一使用者的程序讀得到），直到下次開啟陪伴 App。
  - 正在播放：Mac 只問「音樂」與 Spotify（`osascript`，只在那個播放器開著時問，不會把它打開；第一次 macOS 問 Automation，`Info.plist` 有說明、`Entitlements.plist` 有 `com.apple.security.automation.apple-events`）；不用 MediaRemote、不用 perl 的繞法；網易雲、QQ 音樂標「暫不支援」。Windows 用 `GlobalSystemMediaTransportControlsSessionManager` 目前的工作階段，正在播放時的標題。
- **雲端**（FINAL §6.4，`cloud.rs`，預設關）：Auto-Cloud 做不到預設關，所以用 `ISteamRemoteStorage` 明確地讀寫（steamworks-rs 的 `remote_storage()`，在 `steam` 功能底下）。
  - 打開「雲端」：把這台電腦的 `PetJournal.bin`（2,884 位元組的 `PetState`）複製進雲端，檔名 `ledger-<隨機安裝 ID>.bin`。安裝 ID 是第一次打開時產生的 16 個隨機位元組（32 個十六進位字），存在 `Companion.json` 的 `installId`，不從硬體或帳號推出來。每台電腦只寫自己的檔，內容沒變就不重寫。
  - 開窗時（和結算成就同一刻，在結算之前）：讀其他電腦的 `ledger-*.bin`（長度不對、壞掉、較新版本的略過，最多讀 32 個），用 `meowpuut-pets` 的 `merge` 併進「畫面上的帳本」。畫面（首頁、爪印本、行旅、隱私頁）與成就結算看的是合併後的數字；本機的 `PetJournal.bin` 不會被改寫。別台清除過紀錄（epoch 較新）時，`merge` 本來就以較新的為準。
  - 關掉「雲端」：`FileDelete` 這台電腦的那個檔，別台的不動。Steam 沒開著時記成待刪（`Companion.json` 的 `cloudDeletePending`），下次開著時刪。
  - 只有固定長度的帳本會上去：`cloud::check_upload` 是每一次寫入的唯一關卡（檔名必須是自己的 `ledger-<32 位十六進位>.bin`、長度剛好 `STATE_BYTES`、內容讀得成帳本），`cloud::Cloud` 是通往 `cloud_*` 方法的唯一門，測試掃原始碼確認別處不能直接呼叫。選詞記憶（`mimo_user.db`、`-wal`、`-shm`）、短語詞庫、`Activity.bin`、`settings.json`、`Companion.json` 都不會上去。陪伴 App 自己的設定不是固定長度，所以也不同步（FINAL：有疑問就只放帳本）。
  - 試用資料夾（`MEOWPUUT_DATA_DIR`）從不同步，連 `MEOWPUUT_STEAM_LOG=1` 也一樣（開關還能切、選擇會記，但頁面寫「試用資料夾：不會上傳」）。沒有 Steam 客戶端時開關旁寫「Steam 沒有開著」，選擇先記著。Steam 自己的雲端在 Steam 裡關著時不寫，寫「Steam 裡的雲端關著」。
  - Steamworks 後台要設的：這個 App 的 Steam Cloud 要開（位元組配額與檔案數夠放幾個 2.8 KB 的檔），**不要**設 Auto-Cloud 路徑（設了，帳本就會預設上雲）。

## 截圖

沒有螢幕錄製權限也能截：開發用的 `devcapture` 功能（只有 macOS）讓視窗請 WKWebView 拍自己的頁面，存檔後結束。

```sh
cd src-tauri
cargo build --features devcapture
# vite 要在跑（npm run dev 或 npm run tauri dev）
MEOWPUUT_DATA_DIR=/tmp/meowpuut-sample MEOWPUUT_THEME=dark MEOWPUUT_START=book \
MEOWPUUT_CAPTURE=/tmp/book.png target/debug/meowpuut-companion
```

拍到的是頁面本身（Retina 為 2×），不含系統的標題列與紅綠燈。`shots/` 裡是縮成 1× 的版本：

| 檔案 | 內容 |
| --- | --- |
| `home-light@1x.png`、`home-dark@1x.png` | 首頁（日常玻璃，全鍵拼音） |
| `home-map-light@1x.png`、`home-signpost-dark@1x.png` | 按住 ⌘ 的地圖、⌘K 路牌（打 `jp`） |
| `home-zhuyin-light@1x.png`、`home-windows-light@1x.png` | 注音大千、Windows 的鍵盤 |
| `home-pixel-light@1x.png`、`home-centenary-dark@1x.png`、`home-glyph-light@1x.png` | 其他三種鍵帽材質（字景為替身） |
| `book-light@1x.png`、`book-dark@1x.png` | 爪印本 |
| `journey-year-light@1x.png`、`journey-year-dark@1x.png`、`journey-month-light@1x.png` | 行旅：年、月 |
| `settings-light@1x.png`、`settings-dark@1x.png`、`settings-privacy-light@1x.png` | 鍵盤設定、隱私與帳本 |
| `closet-light@1x.png`、`closet-hans-light@1x.png` | 鍵盤衣櫥（繁體、簡體） |
| `setup-1/3/6/7-light@1x.png`、`setup-3-windows-light@1x.png`、`setup-6-dark@1x.png` | 第一次開啟（第 6 步：三扇門） |
| `settings-cloud-light@1x.png`、`settings-cloud-dark@1x.png`、`explain-cloud-light@1x.png` | Steam 雲端開關（試用資料夾，開著）、第一次打開前的說明頁 |
| `settings-desk-activity-light@1x.png`、`settings-desk-activity-dark@1x.png` | 桌面貓、時間花在哪裡與好友狀態 |
| `settings-sharing-light@1x.png` | 分享中（`MEOWPUUT_STEAM_LOG`，「分享中・到 08:04」） |
| `settings-activity-windows-light@1x.png` | Windows 的規則：執行檔名稱、通知區域（截圖拍於「顯示 App 名稱」還標「之後才能用」的版本，之後再重拍） |
| `settings-steam-dark@1x.png` | Steam：成就開關、只在這台電腦上的成就 |
| `explain-record-light@1x.png`、`explain-share-light@1x.png`、`explain-track-dark@1x.png` | 第一次打開開關時的說明頁（好友看到的那一行由 presence builder 產生） |
| `time-light@1x.png`、`time-dark@1x.png`、`time-empty-light@1x.png` | 時間花在哪裡：這一週（深色是上一週）、這個月、五個時段；記錄關著時 |
| `journey-time-entry-light@1x.png` | 行旅右上的「時間花在哪裡」 |

## 字景的畫

引擎是 `Engine/crates/meowpuut-glyph`（只讀，不在這裡改）。頁面只負責排版與搬像素：

- **行旅**（`pages/journey.ts`）：`scroll_open` 依視窗寬高（單位是「點」，一點 = `k / devicePixelRatio` 個 CSS 像素，所以畫面一格對一個裝置像素，世界只以整點移動）排好長卷，回傳 `geometry()` 與每一天的位置。紙皮膚把畫放在圓角畫板（`painting`／`top`／`height`），字景皮膚整頁都是畫。**圖塊**一塊 512 點，只畫視窗前後各一個視窗寬，離視窗中心近的先要，同時最多 3 塊；每塊是一個 async 指令（`scroll_tile`），回 RGBA 二進位（`tauri::ipc::Response`），`putImageData` 上 canvas；換遠近、大小、明暗、皮膚就整個重開、清掉快取。兩側的軸是 `scroll_roller`（只有 17 種粗細，只在粗細變了才再要）。滾輪、← →（一天）、Home、End、Esc 照舊，滾動是 0.3 的緩動、落在整點。
- **引首**：`lead_info`（字數、`journey::places` 的各地次數、兩隻貓的好感）＋ `lead_picture`（`place`、`parts`、`bands`、`cats`）。貓的好感沿用手機的規則 `Snapshot::progress`：每 200 個字或每 3 次接觸一級，上限 100；摸的次數是 `snapshot.<貓>.pets`。引首比視窗高時可以在引首上滾輪上下捲。
- **截一段**：天頭的按鈕，拖過一段日子，卡片寫日期、幾座山、幾種地方；「存成長條」存檔（對話框是既有的 dialog plugin），有 Steam 連上時多一顆「放進 Steam 截圖」（`ISteamScreenshots::AddScreenshotToLibrary`，等 `ScreenshotReady` 後 `SetLocation` 成那一段最後的地方）。只有按下才呼叫。紙皮膚在頁面上把黏土貓與字標疊上去（位置是引擎給的 `cats`／`title`，貓比上緣高時圖往上長高）。沒有打過的字、數字預設不寫。
- **首頁鍵盤**（字景皮膚）：`board_open` ＋ `board_frame`。`KeyboardEvent.code` 對應 `key_down`／`key_up`，⌘（Windows 是 Ctrl）是 `set_hold`（地圖），引擎說 `busy()` 時每秒 12 次 `step()` 再畫，沒事就不問。鍵距 44。
- **第一次開啟**：第 2–7 步各一張 `guide_picture`（572 × 300 點），引擎畫的字與按鈕上方蓋著真的 `<button>`；第 6 步的三扇門內容還在畫面下方。
- **鍵盤衣櫥的字景卡**：`strip_layout` ＋ `strip_picture`（引擎的 `strip`，目前的地方、太陽在選中的字後面、兩隻像素貓）。字是頁面的：用「Meowpuut Pixel A」量 12 px 的寬度交給引擎，引擎回每個字的位置與顏色，頁面以 12 × k 像素、帶引擎的描邊畫在圖上。
- **字景的紙色**：`glyph_tokens` 回引擎的 `Tokens::glyph`（淺 #ECE8DC、深 #151413……），開窗時蓋掉 `skins.ts` 裡字景的代幣，所以頁面與圖是同一張紙。
- **生活照**（`photos.rs`、`photos.ts`、`pages/photos.ts`）：照手機。同一個香港 Google Cloud Storage 公開唯讀桶（`photos.json` 版本 1、`photos/<file>.jpg`）、同一個每日順序（iOS 與 Android 的 `DailyPhotoOrder`，黃金向量是測試）、同樣的檔名與大小規則（安全的 .jpg、JPEG、10 MB 內）。網路只做一般的 GET：沒有 cookie、沒有識別碼，User-Agent 是通用的 `photo-client/1`，清單多一個 `Accept`。快取在資料夾的 `Photos/`（`manifest.json` 與照片，先寫暫存檔再改名），新清單到時，清單上沒有的檔案會刪掉。頁面不等網路：`photos_today` 只讀快取；`photos_refresh` 開一條執行緒抓清單（每次開窗一次，失敗五分鐘後再試），抓到新的就發 `photos-updated`；`photo_image` 是 async 指令，照片不在快取才下載（同一檔同時只下載一次）。沒網路就用快取，沒快取就說「照片要連上網路才會出現」。HTTP 用戶端是 `ureq`（rustls，內建根憑證）：Cargo.lock 裡本來沒有能連 https 的原生用戶端（Tauri 帶的 reqwest 只給 wasm），ureq 小、阻塞式、不拉 async 執行環境。紙皮膚：照片本身加 A 的相紙白邊、微微偏轉；首頁一列三張，點一下開整頁，整頁點一張放大。字景：先是引擎的 `page::photo_type`（頁面把照片縮到 256 px 內解成 RGBA 交給引擎，回字的圖），點一下才是照片；放大一律是照片。
- 首頁右上的地名與第幾站來自帳本的地方（`glyph_here`）。字景的首頁鍵盤可以用滑鼠點鍵（`board_keys` 的矩形），跟打字一樣亮。

試用資料夾的 `sample_ledger` 現在每個有打字的日子都記地方（照累計字數走 `journey::places`），前 70 天沒有（所以軸上有「更早的路已經捲起來了」）。

## 還是替身的

- **字景的畫**：字景的畫已全數接上（行旅、引首、首頁鍵盤、第一次開啟、衣櫥的候選窗卡、生活照）。
- **地名與去過的地方**：已接（帳本的地方），並接入 13 個地點成就結算；改版前的日子與超過保存期的日子是「更早的路已經捲起來了」。
- **生活照**：首頁與生活照頁已接上；爪印本右頁「這個月的照片」仍是空相紙（要不要用當天順序的照片，等決定）。
- **原生按鍵擷取**：FINAL §2.1 提到可在原生端擷取（Mac `NSEvent` 本地監聽、Windows 前景 Raw Input）。這一版用視窗自己的 DOM 按鍵事件（組字時 `code` 仍然有值），一樣只在視窗有焦點時亮、不裝全域鉤子。
- **安裝步驟**：已實作（`install.rs` + `setup.ts`）：macOS 自動複製 `Meowpuut.app` 至 `~/Library/Input Methods`、執行 `lsregister -f` 與 `Meowpuut --register` 並於「我加好了」檢核；Windows 以目前使用者呼叫 `InstallLayoutOrTip` 加入中文字盤。
- **Steam**：成就（已接通字數、日子、地點、節日、截一段與貓窩 `DeskCounters.json` 計數，文案已更新為咪咪貓貓話）、好友狀態、雲端與截一段已接（`steam` 功能），待在真的 Steam 客戶端上實測（見下）。
- **貓窩**：路牌「出門」「叫回家」、引導第 7 步與設定頁皆已接通貓窩設定與啟動；窗臺列 P1。
- **節日**：爪印本已接上朱紅節日表（`festivals.rs`）並累計 `FEST_1`／`FEST_12` 成就。
- **字型**：紙世界已內建共用圓體 `MeowpuutPaperRound.ttf`（jf open 粉圓 2.1 子集，SIL OFL 1.1），未覆蓋字元平滑回退系統字型。

## 給擁有者（TODO）

- 第一次開啟、隱私頁、衣櫥與各頁的說明文字都是草稿（照 FINAL 與原型寫的），請過一次（`src/pages/setup.ts` 開頭有標）。
- 「只有莫莫在的日子是棕色爪印」是這一版的讀法，請確認要不要這樣分。
- 改了使用者看得到的功能，正式出貨時記得同步小貓知識庫（全倉規則）；這一版還沒出貨，所以沒有推上去（行旅的字景長卷、截一段、首頁字景鍵盤、第一次開啟的畫都是新的使用者可見功能）。
- 「時間花在哪裡」頁、說明頁、各開關旁的文字都是草稿，請過一次。
- 要在真的 Steam 上試的：截一段（`AddScreenshotToLibrary` 收不收 PNG、`ScreenshotReady` 等多久、`SetLocation` 在 Steam 截圖庫裡顯示成什麼、F12 的原生截圖不被影響）、雲端（`FileWrite` 的寫入串流收尾、`FileRead`、`FileDelete`、`GetFileCount` 的列表；兩台電腦的合併；Steam 裡關掉雲端時的樣子；Steamworks 後台的配額與不設 Auto-Cloud）、用 480 連上、成就結算（480 沒有喵撲的成就，會全部列為 refused；要在自己的 App ID 設好 API 名稱再試）、`SetRichPresence` 在好友清單上的樣子（要先把 `Engine/crates/meowpuut-activity/steam/rich_presence.vdf` 上傳到 Steamworks 後台）、更新頻率限制、分享模式的系統匣與無視窗。
- 要在真的 Windows 上試的：共享記憶體「現在」一欄（要貓窩在跑）、SMTC 的歌名（中文播放器）、`ActivityHidden` 的 REG_MULTI_SZ 寫入後貓窩馬上生效、挑執行檔、系統匣圖示、關窗後繼續分享、`ActivityShareAppName` 的來回（開關打開後貓窩馬上給名稱、關掉馬上不給；UWP 與有／沒有版本資源的程式的名稱）。
- 要在簽過章的 Mac 版上試的：XPC 問貓窩（雙方簽章要求）、Automation 第一次詢問、無視窗時 Dock 圖示收起。

## 相依套件版本

| 套件 | 版本 |
| --- | --- |
| `tauri`（crate） | 2.12.7（tauri-build 2.7.6、wry 0.57.3、tao 0.37.3） |
| `@tauri-apps/cli` | 2.12.6 |
| `@tauri-apps/api` | 2.12.5 |
| `vite` | 8.3.8 |
| `typescript` | 7.0.2 |
| Rust crates | serde 1.0.229、serde_json 1.0.154、sys-locale 0.3.2、core-foundation 0.10.1（Mac）、winreg 0.56.0（Windows）、tempfile 3.27.0（測試）；`devcapture` 另用 objc2 0.6.5、objc2-web-kit 0.3.2、block2 0.6.2 |
| `meowpuut-pets` | 本倉庫 `Engine/crates/meowpuut-pets`（path 相依，含 `achievements`） |
| `meowpuut-activity` | 本倉庫 `Engine/crates/meowpuut-activity`（path 相依：Activity.bin 的格式、presence builder） |
| `steamworks`（`steam` 功能） | 0.13.1（steamworks-sys 0.13.0，Steamworks SDK 1.64）；MIT／Apache-2.0，SDK 檔案屬 Valve 的授權 |
| `tauri-plugin-dialog` | 2.8.4（只從 Rust 叫開檔視窗；頁面沒有它的權限） |
| 其他 | chrono 0.4.45（本地日）、tauri 的 `tray-icon` 功能；Mac：objc2-app-kit `NSRunningApplication`（播放器是否開著）、libxpc 與 Security.framework（直接呼叫）；Windows：windows 0.62.2（共享記憶體、SMTC） |

macOS 最低版本設為 15.0（FINAL §10 第 6 題）。
