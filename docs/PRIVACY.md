# 喵撲的隱私政策

最後更新：2026 年 11 月 23 日

喵撲（Meowpuut）是一個鍵盤。這份說明適用於 iOS、iPadOS、macOS、Android 和 Windows 的喵撲。

## 一句話

打字在裝置上處理。iPhone 和 iPad 可以選擇用自己的 iCloud 同步中文選詞記錄。

## 鍵盤看得到什麼，拿它做什麼

- 按鍵只在裝置上用來算出候選字。啟用選詞記憶時，鍵盤會在本機保留已確認的選詞資料。
- 英文候選（iOS、Android）：鍵盤讀游標前的文字，只把正在打的字和它前面最多兩個字（含它們之間的句號、問號）交給本機的喵撲引擎，算出補完、拼字更正和下一個字；數字、中文和更早的文字不交出去。只在記憶體裡算，不寫檔、不記錄、不上傳。密碼、電子郵件和網址欄位不給候選。字只有在你點了候選才會換，空白鍵和換行照常輸入，不自動改字。
- iOS 的「允許完整取用」可選。自訂按鍵聲、鍵盤震動和 iCloud 選詞同步需要開啟。基本打字和本機選詞記憶可以離線使用。
- iOS 和 Android 的小貓好感度與行旅統計、macOS 和 Windows 的打字紀錄，只在本機記輸入量、日子和互動次數，這些紀錄沒有輸入內容。
- 沒有帳號，沒有廣告，沒有分析或追蹤工具。除了 Steam 版內含 Valve 的 Steamworks SDK（只用在成就、好友狀態和選用的 Steam 雲端，見下方），沒有第三方的 SDK。

## 存在你裝置上的東西

- **選詞記錄**：依平台記住你確認的選詞、選用次數、常接在一起的詞和點過的表情符號，用來把常用候選排在前面。在設定裡可以關掉「記住選詞」，也可以一次清除。
- **iCloud 選詞同步**：預設關閉。開啟後，中文選詞、讀音、次數和最近選用時間加密存入使用者 Apple 帳號的私人 CloudKit 資料庫。上下文、表情符號和顏文字留在本機。可在喵撲設定關閉同步或清除 iCloud 選詞記錄。
- **Android 選詞記憶**：記住你確認上屏的中文候選及對應讀音和次數。密碼欄和要求停用個人化的欄位不使用這些記錄，也不會新增記錄。
- **自訂詞庫（iOS、Android）**：手動新增的詞和讀音存於本機設定，並依完整讀音顯示候選。可逐筆編輯或刪除，清除選詞記錄後仍會保留。iCloud 選詞同步僅涵蓋前述中文選詞記錄。
- **行旅統計（iOS、Android）**：喵撲 App 的統計頁把貓咪紀錄累加成一份只增不減的帳本，存在這台裝置上：打字的字數總計、有打字的日子（只記是哪一天，用本地的日序號）、當天字量的級距（五級）、當天是哪隻貓在、一天裡的哪個時段、兩隻貓的互動次數與熟度；旅程走了多遠由字數算出，不另外存。不記你打的字、不記是在哪個 App 打的、不記每次打字的時間點。這份帳本不連網、不拿去分析；在設定裡清除貓咪紀錄時一併清空，旅程也回到起點。iOS 沒開「允許完整取用」時，鍵盤不會把這些交給 App。
- **打字紀錄（macOS、Windows）**：輸入法把送出的字數累加到同一種只增不減的帳本，存在這台電腦你自己的使用者資料夾（macOS 是 `~/Library/Application Support/Meowpuut/`，Windows 是 `%LOCALAPPDATA%\Meowpuut\`）：字數總計、有打字的日子（只記是哪一天，用本地的日序號）、當天字量的級距（五級）、一天裡的哪個時段，以及候選窗上有沒有貓。不記你打的字、不記是在哪個程式打的、不記每次打字的時間點；檔案分批寫入（大約每分鐘一次，以及切換輸入法或離開輸入欄位時），不會每按一鍵就寫。Windows 在商店 App（AppContainer）、安全桌面、密碼欄和唯讀欄位裡不記。這份帳本不連網、不拿去分析。macOS 在喵撲設定的「選詞記憶」頁、Windows 在工作列輸入指示的右鍵選單可以清除；Windows 移除喵撲時一併刪除。
- **設定**：外觀、輸入方式、繁體或簡體等你自己調過的選項。
- **桌面貓（macOS、Windows 的 Steam 版）**：輸入法每次按鍵後，只告訴同一台電腦上的桌面貓程序「喵撲貓窩」幾個累計數字（按了幾下、送出幾次、送出幾個字）、是否正在組字、組字起點的粗略位置（對齊 8 點的格子）、在哪個螢幕，以及候選窗是否展開、在上方還是下方。不含你打的字、不含按了哪個鍵、不含游標的確切位置或時間。這些只在這台電腦上的兩個喵撲程式之間傳遞（macOS 會檢查雙方的程式簽章），貓窩只在記憶體裡拿來讓貓陪你走，不寫檔、不上傳；貓窩回給輸入法的只有「哪幾隻貓在桌面上」。在密碼欄裡不傳。
- **時間花在哪裡（Steam 版，預設關閉）**：你打開後，喵撲貓窩在本機累計每天各類 App（寫作、程式、設計、瀏覽、通訊、影音、遊戲、學習、辦公、其他）用了幾分鐘，以及大約在一天的哪個時段。它只看前景是哪個 App、你多久沒動鍵盤或滑鼠；App 的識別碼只在記憶體裡拿來分類，查完就丟。不讀視窗標題、網址或文件內容，不需要螢幕錄製、輔助使用或輸入監控權限。檔案（macOS 是 `~/Library/Application Support/Meowpuut/Activity.bin`）只有類別、分鐘數和時段，不存 App 名稱、識別碼、標題、網址或時間點，不連網、不上傳。你可以讓個別 App 不被記錄、隨時暫停；關掉「記錄時間花在哪裡」就不再偵測也不再記錄；在喵撲貓窩的選單（選單列的貓，或在貓身上按右鍵）選「清除時間紀錄」可刪除全部天數。
- **Steam 成就與好友狀態（Steam 版）**：只有陪伴 App「喵撲」會連 Steam，輸入法和喵撲貓窩都不連。開啟 App 時，它照本機的打字紀錄算出新達成的成就，只告訴 Steam 是哪幾個成就，不送字數或其他數字；成就開關關掉時不送。「讓 Steam 好友看到我在做什麼」預設關閉，要先開「記錄時間花在哪裡」才能開。開啟後，喵撲會把一行狀態交給 Steam，顯示在你的 Steam 好友清單裡，例如「喵撲・在寫程式」。狀態只有目前的 App 類別；你另外打開「顯示 App 名稱」或「顯示正在聽的歌」時，才加上 App 的顯示名稱或歌名。全螢幕和玩遊戲時一律只顯示「專心中」。歌名在 macOS 只問「音樂」和 Spotify（第一次會由系統問你是否允許），在 Windows 讀系統的媒體控制；只即時送出，不寫檔。分享有時限（1 小時、今天、直到你關掉），時間到、關掉開關或結束 App 時，喵撲會清除這行狀態。交給 Steam 的內容依 Steam 自己的隱私政策處理；Steam 也會照常顯示你正在玩「喵撲」。
- **Steam 雲端（Steam 版，預設關閉）**：你在陪伴 App「喵撲」打開後，這台電腦的打字紀錄帳本（上面「打字紀錄」那一份：固定長度、只有數字）會以 `ledger-<隨機碼>.bin` 複製一份到你自己的 Steam 雲端。你在另一台電腦打開喵撲時，會把兩邊的數字合起來顯示。每台電腦只寫自己的那一份，不改別台的。選詞記憶、短語、你打的字、「時間花在哪裡」的紀錄、App 名稱和各項設定永遠不會上傳。隨機碼是第一次打開時隨機產生的，和你的電腦或帳號都對不上。關掉時，這台電腦的那一份會從雲端刪除。只有陪伴 App 會連 Steam。
- Apple 裝置上的本機檔案可能隨系統備份（例如 iCloud 備份），取決於你的裝置設定。Android 版的應用程式備份已停用。

## 照片

iOS 與 Android 的喵撲宿主 App，以及 macOS 與 Windows 的陪伴 App「喵撲」，會展示米米莫和莫莫的生活照。照片透過香港 Google Cloud Storage 的公開唯讀端點載入，快取在本機，每天換一個順序。請求只是對公開清單檔與圖片檔的一般 GET：不帶 cookie，不帶任何帳號、個人資料或裝置追蹤識別碼，User-Agent 是通用字串；和連上任何網站一樣，對方的伺服器看得到你的 IP 位址。陪伴 App 把清單與看過的照片存在自己資料夾的 `Photos/`（macOS 是 `~/Library/Application Support/Meowpuut/Photos/`，Windows 是 `%LOCALAPPDATA%\Meowpuut\Photos\`）；沒有網路時用存著的，清單更新時，清單上沒有的檔案會刪掉。所有平台的鍵盤與輸入法本身，以及喵撲貓窩，全程離線，不發出任何網路連線；陪伴 App 連網只有這件事，以及上面寫的 Steam 成就、好友狀態與雲端。按「存到照片」（iOS）時，系統會問你是否允許把那張照片加進你的相簿；喵撲不讀取你相簿或電腦裡的任何照片。

## 兒童

喵撲不收集任何人的資料，包括兒童。

## 這份說明如果更改

會更新這一頁和上面的日期。

## 聯絡

有問題請到 <https://github.com/Krysta1ll/Meowpuut/issues> 留言。

---

# Privacy Policy (English)

Last updated: 23 November 2026

Meowpuut (喵撲) is a keyboard. This policy covers Meowpuut on iOS, iPadOS, macOS, Android and Windows.

**In one sentence:** Typing is processed on your device. On iPhone and iPad, you can choose to sync Chinese word choices through your own iCloud account.

**What the keyboard sees.** Key presses are used on the device to calculate candidates. When word-choice memory is enabled, the keyboard saves confirmed choices on the device. On iOS, "Allow Full Access" is optional and enables custom typing sounds, keyboard haptics and iCloud word-choice sync. Basic typing and local word-choice memory work offline. On iOS and Android, cat affinity and the journey statistics, and on macOS and Windows the typing records, use on-device counts of committed characters, days and pet interactions. There are no Meowpuut accounts, ads, analytics or trackers, and no third-party SDKs except Valve's Steamworks SDK in the Steam version, used only for achievements, friend status and the optional Steam Cloud (below).

**What is stored on your device.** Depending on the platform, the keyboard may store confirmed word choices, their frequency, context and selected symbols to rank common candidates. You can turn memory off or clear it in settings. On Android, password fields and fields that disable personalized learning do not use or update memory. On Apple devices, local files may be included in system backups. Android app backup is disabled.

**Journey statistics on iOS and Android.** The statistics page of the Meowpuut app adds the cats' records up into a ledger that only grows and is stored on this device: the total number of characters typed, the days on which you typed (only which day, as a local day number), that day's amount in five steps, which cat was there, the part of the day, and the cats' interaction counts and familiarity. How far the journey has gone is worked out from the character count and is not stored separately. It does not record what you type, which app you typed in, or when each keystroke happened. The ledger is never sent anywhere or analysed, and clearing the cat records in settings clears it too and sends the journey back to the start. On iOS, without "Allow Full Access" the keyboard does not hand these records to the app.

**Typing records on macOS and Windows.** The input method adds the characters you commit to the same kind of ledger, kept in your own user folder on this computer (`~/Library/Application Support/Meowpuut/` on macOS, `%LOCALAPPDATA%\Meowpuut\` on Windows): the total number of characters, the days on which you typed (only which day, as a local day number), that day's amount in five steps, the part of the day, and whether the cats were on the candidate window. It does not record what you type, which program you typed in, or when each keystroke happened, and the file is written in batches (about once a minute, and when you switch input methods or leave a text field), never on every key. On Windows nothing is recorded in Store apps (AppContainer), on the secure desktop, or in password and read-only fields. The ledger is never sent anywhere or analysed. You can clear it on the "選詞記憶" page of Meowpuut's settings on macOS, and from the right-click menu of the input indicator on the taskbar on Windows; uninstalling Meowpuut on Windows deletes it.

**Desk cats on macOS and Windows (Steam version).** After each key, the input method tells the desk-cat program on the same computer, 喵撲貓窩 (Meowpuut Desk), only a few running counts (keys pressed, commits, characters committed), whether a word is being composed, a coarse position where the composition starts (on an 8-point grid), which screen, and whether the candidate window is expanded and above or below the line. It never includes what you type, which key you pressed, the exact caret position, or a time. This passes only between the two Meowpuut programs on this computer (on macOS each checks the other's code signature); Meowpuut Desk uses it in memory to let the cats walk with you, and neither writes it to a file nor sends it anywhere. All it tells the input method back is which cats are out on the desktop. Nothing is sent from password fields.

**Where your time goes (Steam version, off by default).** If you turn it on, Meowpuut Desk adds up, on this computer, how many minutes each day went to each kind of app (writing, coding, design, web, chat, media, games, learning, office, other) and roughly which part of the day. It only looks at which app is in front and how long since you last used the keyboard or mouse; an app's identifier is used in memory to find its category and then dropped. It never reads window titles, web addresses or documents, and needs no Screen Recording, Accessibility or Input Monitoring permission. The file (`~/Library/Application Support/Meowpuut/Activity.bin` on macOS) holds only categories, minutes and parts of the day: no app names, identifiers, titles, addresses or times, and it is never sent anywhere. You can exclude individual apps and pause at any time; turning "record where my time goes" off stops both the detection and the recording, and "清除時間紀錄" (Clear time records) in Meowpuut Desk's menu (the cat in the menu bar, or right-click a cat) deletes every day.

**Steam achievements and friend status (Steam version).** Only the companion app, Meowpuut, connects to Steam; the input method and Meowpuut Desk never do. When it opens, it works out from the typing records on this computer which achievements are newly reached and tells Steam only which achievements they are, never character counts or other numbers; nothing is sent while achievements are turned off. "Let Steam friends see what I'm doing" is off by default and needs "record where my time goes" turned on first. When it is on, Meowpuut hands Steam one line of status, shown in your Steam friends list, such as "Meowpuut · writing code". The line holds only the current kind of app; an app's display name or a song title is added only if you also turn on "show app name" or "show what I'm listening to". In full screen and in games it always says only "focused". On macOS song titles come only from Music and Spotify (the system asks you once whether to allow this); on Windows they come from the system's media controls. They are sent live and never written to a file. Sharing has a time limit (1 hour, today, or until you turn it off), and Meowpuut clears the line when time is up, when you turn sharing off, or when the app quits. What Steam receives is handled under Steam's own privacy policy, and Steam also shows, as usual, that you are playing Meowpuut.

**Steam Cloud (Steam version, off by default).** If you turn it on in the companion app, Meowpuut, a copy of this computer's typing-records ledger (the one described above: fixed-size, numbers only) is stored in your own Steam Cloud as `ledger-<random id>.bin`, and another computer of yours adds those numbers together with its own when Meowpuut opens there. Each computer writes only its own file and never changes another's. Word-choice memory, phrases, what you type, the "where your time goes" records, app names and settings are never uploaded. The random id is generated once, when you first turn it on, and cannot be traced to your computer or account. Turning it off deletes this computer's file from the cloud. Only the companion app connects to Steam.

**Custom dictionary on iOS and Android.** Manually added phrases and readings are saved in local settings and appear as candidates when the full reading matches. You can edit or delete individual entries. Clearing word-choice memory keeps the custom dictionary.

**Optional iCloud sync.** When enabled, Chinese word choices, their readings, selection counts and last-used times are encrypted and stored in the private CloudKit database of your Apple Account. Context history, emoji and kaomoji stay on the device. You can turn off sync or clear iCloud word-choice records in Meowpuut settings.

**Photos.** The iOS and Android host apps, and the companion app Meowpuut on macOS and Windows, show photos of the two cats. They are loaded from a public, read-only Hong Kong Google Cloud Storage bucket (`storage.googleapis.com`), cached on the device, and shown in an order that changes daily. Requests are plain GETs for the public list and image files: no cookies, no accounts, personal data or tracking identifiers, and a generic User-Agent; as with any website, the server can see your IP address. The companion app keeps the list and the photos it has shown in `Photos/` inside its own folder (`~/Library/Application Support/Meowpuut/Photos/` on macOS, `%LOCALAPPDATA%\Meowpuut\Photos\` on Windows); offline it uses what it has, and when the list changes, files no longer on it are deleted. The keyboards and input methods on every platform, and Meowpuut Desk, stay fully offline and make no network connections; besides this, the companion app connects only for the Steam achievements, friend status and cloud described above. When you tap Save on iOS, the system asks whether the app may add that photo to your library. Meowpuut does not read the photos in your library or on your computer.

**Children.** Meowpuut collects no data from anyone, including children.

**Changes.** This page and its date will be updated.

**Contact.** <https://github.com/Krysta1ll/Meowpuut/issues>
