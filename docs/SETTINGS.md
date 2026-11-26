# 設定檔（settings.json）

macOS 版喵撲的輸入法設定存在一個 JSON 檔，供另一個配套 app 讀取與修改。

- 路徑：`~/Library/Application Support/Meowpuut/settings.json`（引擎試打版為 `Meowpuut Engine Preview`）
- 鎖檔：同資料夾的 `settings.lock`
- 只有設定，沒有任何輸入過的文字。唯一例外是 `candidatePins`：使用者自己釘選的字詞（讀音對應字詞），原本就存在設定裡。

## 格式

```json
{
  "schema": 1,
  "settings": { "inputMode": "zhuyin", "simplified": false }
}
```

`settings` 只放使用者動過的鍵。缺少的鍵用預設值，未知的鍵會被保留、不處理。值的型別錯誤或不認得的列舉值，輸入法當作預設值。

| 鍵 | 型別 | 值 | 預設 |
| --- | --- | --- | --- |
| `inputMode` | 字串 | `zhuyin`、`pinyin`、`japanese` 等輸入方式 | 首次執行依系統語言與地區決定（`zhuyin` 或 `pinyin`） |
| `simplified` | 布林 | 簡體輸出 | 首次執行依系統語言與地區決定 |
| `keyboardBackend` | 字串 | 輸入引擎 | `meowpuut` |
| `modulesEnabled` | 布林 | 片語模組 | `true` |
| `rememberChoices` | 布林 | 記住選字 | `true` |
| `mixJapanese` | 布林 | 中日混打 | 新安裝 `false`；升級者 `true` |
| `catGlassSkin` | 布林 | 米米莫與莫莫 | `true` |
| `catPlacement` | 字串 | `daily` 或某個排列的 id | `daily` |
| `deskPets` | 布林 | 桌面貓（喵撲貓窩）：貓在桌面上 | `true` |
| `typingSeat` | 字串 | 打字時貓在哪裡：`split`（莫莫在候選玻璃上、米米莫沿你正在寫的視窗上緣陪走）或 `glass`（兩隻都在候選玻璃上） | `split` |
| `activityStats` | 布林 | 記錄時間花在哪裡（Steam 版）：喵撲貓窩在本機累計每天各類 App 用了幾分鐘 | `false` |
| `activityPaused` | 布林 | 暫停記錄時間花在哪裡 | `false` |
| `activityHidden` | 字串陣列 | 不記錄的 App（bundle id，不分大小寫，最多 256 個） | `[]` |
| `candidateTone` | 字串 | 色調 | `system` |
| `candidatePalette` | 字串 | 色系 | `rose` |
| `candidateTextSize` | 字串 | 候選字級 | `standard` |
| `candidateWindowSize` | 字串 | 候選窗大小 | `standard` |
| `candidateSkin` | 字串 | 候選窗的皮膚：`glass`（日常玻璃）或 `glyph`（字景，衣櫥 No.04）。其他值當作 `glass` | `glass` |
| `glassStrength` | 字串 | 玻璃強度 | `balanced` |
| `glassShape` | 字串 | 造型 | `capsule` |
| `festivalOutfits` | 布林 | 節日裝扮總開關 | `true` |
| `festivalsSkipped` | 字串陣列 | 關閉的節日主題 id | `[]` |
| `keySound` | 字串 | 按鍵聲 | `off` |
| `keySoundVolume` | 整數 | 0 到 100 | `100` |
| `candidatePins` | 字串到字串的物件 | 使用者釘選的字詞 | `{}` |

列舉值的完整清單以輸入法選單為準（`Sources/Appearance.swift`、`Sources/KeySounds.swift`、`Sources/InputBackend.swift`）。`effectsLevel` 是舊版留下的鍵，現在不使用。

`deskPets`、`typingSeat` 與三個 `activity…` 鍵由桌面寵物程序「喵撲貓窩」（`Desk/`）讀取，它只讀這個檔、從不建立或寫入；輸入法不使用 `activity…` 鍵。`catGlassSkin` 關閉時桌面上也沒有貓（桌面貓＝`catGlassSkin` 且 `deskPets`）。兩者任一關閉時，輸入法也不送打字脈衝給貓窩。桌面貓關閉只是讓貓回家、程序不畫任何東西；要讓程序完全不啟動，配套 app 另外取消登入項目（`SMAppService`）。

`activityStats` 關閉（預設）時，喵撲貓窩不偵測前景 App、不計時，也不碰 `Activity.bin`（桌面貓本身仍會看系統閒置時間判斷你在不在，不記錄）。開啟後它在同一個資料夾的 `Activity.bin`（鎖檔 `Activity.lock`，固定 12,824 位元組）累計「哪一天 × 哪一類 × 幾分鐘」與時段，不存 App 名稱、bundle id、視窗標題、網址或時間點；寫檔分批（約每 5 分鐘，以及關掉開關、睡眠、結束時），規則同 `PetJournal.bin`（鎖、讀、加上增量、合併、寫暫存檔、改名）。`activityPaused` 開啟或 App 在 `activityHidden` 裡時不計時。清除：喵撲貓窩選單的「清除時間紀錄」（提高紀錄的 epoch，舊副本不會把分鐘數帶回來）。

`candidateSkin` 由配套 app 寫：使用者在鍵盤衣櫥穿上的皮膚（`Companion.json` 的 `skin`）是 `glyph`（字景）時寫 `glyph`，穿其他皮膚時寫 `glass`；配套 app 啟動時也對一次（值相同就不寫、不發通知）。輸入法只讀、從不寫這個鍵，收到設定變更通知後下一次顯示候選窗就換上（正在顯示的窗也會換）。Windows 對應 `HKCU\Software\Meowpuut` 的 DWORD `CandidateSkin`（0＝玻璃，預設；1＝字景），由同一個配套 app 寫、文字服務讀。字景的候選窗畫法見 `design-preview/pc/FINAL.md` §3；它畫的地方是 App 此刻的地方（旅程距離＝`PetJournal.bin` 的累計字數），深淺由 `candidateTone`（色調）決定（`system` 跟著系統外觀），並跟著系統的「增加對比」「降低透明度」（安全畫法）與「減少動態」（靜止）。

## 配套 app 怎麼寫

1. 取 `settings.lock` 的 `flock(LOCK_EX)`。
2. 讀 `settings.json`，只改自己要動的鍵，其他鍵原樣寫回。
3. 寫到同資料夾的暫存檔，再 `rename` 蓋過 `settings.json`（原子寫入；不要就地改檔）。
4. 解鎖，然後在 `DistributedNotificationCenter` 發出名為 `app.meowpuut.settings.changed` 的通知，不帶任何內容（沒有 object、沒有 userInfo）。

輸入法會監看資料夾，收到通知、或輸入法啟用時，比對檔案的修改時間與大小，有變才重讀；效果與在輸入法選單改設定相同。檔案損壞或不見時，輸入法沿用記憶中的設定，下一次寫入會重建檔案。

輸入法自己的寫入會延遲約 0.4 秒合併為一次，只更動它改過的鍵，不會蓋掉配套 app 寫的其他鍵。

## 配套 app 讀「現在在做什麼」（好友狀態用）

App 名稱只在記憶體裡，不寫檔，所以這一項不放在檔案裡，而是向喵撲貓窩問：

- XPC Mach 服務 `app.meowpuut.desk.activity`（貓窩 LaunchAgent 的第二個 `MachServices` 名稱，由 launchd 保留給貓窩）。用 libxpc：`xpc_connection_create_mach_service`，並以 `xpc_connection_set_peer_code_signing_requirement` 要求對方簽章識別碼為 `app.meowpuut.desk`（Developer ID 版再加同一個 Team ID）。
- 貓窩只接受簽章識別碼為 `app.meowpuut.companion`（Developer ID 版再加同一個 Team ID）的請求，其他程序的請求在送達前就被系統丟掉。
- 請求：字典 `{"v": 1（uint64）, "app": 布林}`，`app` 只在配套 app 的「顯示 App 名稱」開著時設為 true。
- 回覆：字典 `{"v": 1, "current": 272 位元組}`，內容是 `MPActivityCurrent`（`Engine/crates/meowpuut-ffi/include/meowpuut.h`）：狀態、類別、全螢幕旗標，以及 App 顯示名稱（只有 `app` 為 true、你正在用一個有記錄的 App 時才有，否則全是 0）。`activityStats` 關閉時狀態是 OFF、類別 255。不能問歷史、分鐘數或 bundle id。

## 從舊版升級

第一次啟動有此檔的版本時，若 `settings.json` 不存在，輸入法把舊 UserDefaults（`app.meowpuut.inputmethod.Meowpuut`）裡的每個已知鍵複製過來一次，舊資料原樣保留，可回退。之後只讀這個檔，不再回頭看舊資料。
