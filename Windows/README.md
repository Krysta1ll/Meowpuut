# Windows 輸入法

從 0.8.0-alpha.1 起，Windows 又有可以安裝的喵撲。`TextService/` 是 TSF 文字服務 `MeowpuutTextService.dll`，在中文（繁體、简体）語言底下提供「喵撲拼音」和「喵撲注音」兩個鍵盤。它用這個資料夾的會話層（`Session`、`Keyboard`）、Rust 核心（`meowpuut-ffi`）和 `UI/` 的候選窗。`Desk/` 是桌面寵物「貓窩」`MeowpuutDesk.exe`（見「貓窩」）。發佈包在 macOS 上交叉編譯，分 x64（含 32 位元的 x86 DLL）和 Arm64 兩份，沒有數位簽章。

## 安裝與移除

1. 下載 `Meowpuut-0.9.0-alpha.1-windows-x64.zip`（Arm64 的電腦下載 `-windows-arm64.zip`），整個解壓。
2. 雙擊 `Install.cmd`。Windows 會要求系統管理員權限。檔案複製到 `C:\Program Files\Meowpuut\<所有 DLL 雜湊的前 12 碼>`（DLL 在 `x64\`、`x86\` 或 `arm64\` 子資料夾），用 `regsvr32` 註冊（64 位元的 DLL 用 `System32\regsvr32.exe`，x64 套件另外把 32 位元的 DLL 用 `SysWOW64\regsvr32.exe` 以同一個 CLSID 註冊），再把兩個鍵盤加進目前使用者的中文語言（優先用已經有的中文語言，沒有才新增一個）。新安裝依系統地區和語言清單決定預設鍵盤是注音還是拼音（規則在 `InputDefaults.h`，只用一次、不存地區）。
3. 按 Windows 鍵 + 空白鍵切到「喵撲拼音」或「喵撲注音」。已經開著的程式要重新開啟才會載入。

`Install.cmd` 也把貓窩（`x64\MeowpuutDesk.exe` 或 `arm64\MeowpuutDesk.exe`）設成目前使用者的登入項目並馬上開啟，見「貓窩」。

移除：雙擊同一個資料夾裡的 `Uninstall.cmd`。它先關掉貓窩、拿掉它的登入項目，再把兩個鍵盤從使用者的語言清單拿掉、取消註冊、刪除 `C:\Program Files\Meowpuut`、`HKCU\Software\Meowpuut`、打字紀錄和「時間花在哪裡」的紀錄（`%LOCALAPPDATA%\Meowpuut` 裡的 `PetJournal.bin`、`Activity.bin`、它們的 `.lock` 和暫存檔，資料夾空了就刪）。還有程式載著 DLL 時那個資料夾刪不掉，腳本會印出路徑；關掉那些程式或重新開機後再手動刪。

裝新版會放進新的資料夾，正在執行的程式繼續用舊的 DLL，舊資料夾在下次安裝時刪掉。0.1.x 的類別和拼音設定檔用同一組 GUID，裝 0.8.0 會直接取代，也會刪掉 0.1.x 登入時啟動玻璃預覽程式的捷徑。

套件的 DLL 必須和 Windows 相同架構，不同時 `Install.cmd` 會停下來，說該下載哪一份。Arm64 版是原生 Arm64 DLL，只會載入 Arm64 程式（記事本、Edge、檔案總管、Windows 終端機等內建程式都是）。用 x64 模擬執行的程式載入不了，在那些程式裡切到喵撲，按鍵會直接出英文字母。要兩種程式都能用得做 Arm64X DLL；Rust 核心目前沒有能交叉編譯的 Arm64EC 目標，所以還沒做。Arm64 套件沒有 x86 DLL（Arm64 的 Windows 用模擬執行 32 位元程式）。x64 套件有 32 位元的 `x86\MeowpuutTextService.dll`（同一個 CLSID），32 位元的程式也能打字；它只有交叉編譯過，還沒在真的 Windows 上跑過。

## 打字

按鍵和 macOS 相同，不同的地方是單按 Shift 和方向鍵：

| 按鍵 | 作用 |
| --- | --- |
| 數字（拼音 `1`–`8`；注音 Shift + `1`–`7`） | 選這一頁的候選 |
| 空白鍵 | 拼音：選反白的候選。注音：第一聲；已經打了聲調時選反白的候選，並送出整段 |
| Enter | 選反白的候選；沒有候選時送出讀音原文 |
| Shift + Enter | 送出打的字母原文；注音送出注音符號（ㄋㄧˇㄏㄠˇ） |
| ← →、Tab、Shift + Tab | 移動反白 |
| ↓ ／ ↑ | 展開／收起候選面板（展開時 ↓ 移動反白） |
| Page Up ／ Page Down | 翻頁 |
| Esc | 面板展開時收起；否則取消這段讀音 |
| 標點 | 拼音的 `, . ; / \ [ ]`、注音的 Shift + `, . / ; 1 9 0 [ ] \` 打出全形標點；組字中先送出反白的候選 |
| 其他字元 | 組字中先送出反白的候選，再打出這個字元 |
| Ctrl、Alt、Windows 組合鍵，Delete、Home、F5 等 | 組字中先送出反白的候選，再交給程式（例如聊天程式的 Ctrl + Enter 送出的是選好的字） |
| 單按 Shift | 中文、英文切換（macOS 的注音是 Shift + 空白鍵）。Shift 按住時按了別的鍵、點了滑鼠，或按超過一秒，都不算 |
| Caps Lock | 開著時不開始組字，直接打大寫 |

滑鼠：點候選選取，滾輪翻頁，右端箭頭展開成格狀面板，連點莫莫五下切換繁體、簡體。

工作列的輸入指示顯示「中」或「英」，點一下切換；右鍵選單可選繁體或簡體，候選字級有小、標準、大三檔。字級會保存在本機，其他程式下次顯示候選窗時套用。繁簡存在 `HKCU\Software\Meowpuut` 的 `Simplified`（右鍵選單裡還有「中日混打」開關，見「會話層」），每個程式開始新的一段讀音時讀一次，所以在一個程式切換，其他程式的下一段也會跟著換。候選窗的造型 `CandidateSkin`（DWORD，0＝玻璃、預設，1＝字景）也在 `HKCU\Software\Meowpuut`，由陪伴 App 寫入，見「字景」一節。玻璃的造型、色系和色調在 Windows 上還沒有設定的地方，固定是膠囊、玫瑰、跟隨系統的淺深色；節日裝扮照日曆自動換（規則同其他平台，見 `UI/Festival.h`）。

讀音是 TSF 組字，也就是文件裡有底線的文字，候選窗在它下方。焦點換到別的欄位時，讀音留在原來的欄位，回去再打字會接著用；程式自己結束組字（例如點到別處）時，讀音照原樣留在文件裡。切換鍵盤、切到英文、拼音注音互換時，先送出反白的候選。

上屏的文字先放在那個欄位自己的佇列裡，再由編輯工作階段寫入文件。程式拒絕寫入時，文字留在同一個欄位的佇列，下一次寫入時一起送出，不會跑到別的欄位。

## 在 Windows 上檢查過的

2026-11-12 在 Mac 上的 Windows 11 Arm64 虛擬機（繁體中文介面，`scripts/windows-vm.sh`，見下面）從乾淨的快照開始，用 Arm64 的 zip 跑過：

- `Install.cmd`：註冊、兩個鍵盤加進中文語言；Windows 鍵 + 空白鍵切到喵撲拼音、喵撲注音。
- 記事本（Arm64 原生）：`nihao` 有底線的讀音和候選窗（兩隻貓在玻璃上）；按 `1`、空白鍵、→ 加 Enter、滑鼠點第二個候選都送出對的字；↓ 展開面板、↑ 收起；Page Down 翻頁；`,` 打出「，」；Shift + Enter 送出字母原文；組字中按 Ctrl + End 先送出候選再移動游標。
- 單按 Shift 切到英文（工作列顯示「英」），再按一次回中文；按住 Shift 用滑鼠選字不會切換。
- 喵撲注音：`su3cl3` 顯示 ㄋㄧˇㄏㄠˇ，Shift + 1 送出「你好」；`ji3` 加空白鍵送出「我」。
- 連點莫莫五下：拼音 `lanse` 的候選從「藍色 藍 爛…」變成「蓝色 蓝 烂…」，再五下變回來。
- Edge 的網址列：拼音組字、空白鍵和數字選字都正常，候選窗在文字下方。「執行」對話框（檔案總管）裡也能打字。
- `Uninstall.cmd`：兩個鍵盤從清單拿掉，CLSID 和 TIP 的登錄機碼都刪掉；檔案總管還載著 DLL，所以資料夾留下，腳本印出路徑。
- x64 的 zip 在這台 Arm64 上安裝時停下來，說要下載 `windows-arm64`。

沒有在 Windows 上跑過的：x64 的 DLL（沒有 x64 的 Windows 可測）、Windows 10、高 DPI 和多螢幕、UWP 程式與開始功能表搜尋、Word 等其他程式、長時間使用；UI-less 模式、候選窗的擁有者與 IME 事件、無邊框全螢幕的一行（見「遊戲與全螢幕」）。

在 macOS 上：`scripts/build-windows-check.sh` 全部通過（排版測試、`session_test` 含 `mimo_base.bin` 共用詞庫、SVG 快照、MinGW GCC 交叉編譯 x64 DLL）；llvm-mingw 20261020 交叉編譯 x64 和 Arm64 DLL，沒有警告，只匯入 Windows 內建的 DLL。

## 會話層

每個輸入上下文應獨立持有一個 `meowpuut::Session`，並只在所屬執行緒使用。字典以 `std::filesystem::path` 讀取；Windows 呼叫端可以傳 UTF-16 路徑。文字服務用 `Session(SharedLexicon, bool pinyin)`：`mimo_base.bin` 和 `mimo_pairs.bin` 以檔案映射直接讀取，每個行程只載入一次，所有會話共用，各個程式也共用同一份檔案分頁。快照複製 UTF-8 組字、候選與上屏字串；`takeSnapshot()` 讀取後清除待提交文字，避免重複上屏。`commit()` 送出第一個候選，沒有候選時送出原本的讀音；文字服務用它把整段讀音送完。

`Keyboard.h` 提供鍵盤事件轉換：拼音數字選詞、Shift 加大千聲調鍵輸入拼音聲調，拼音組字時空格保留原文並加入空格；注音的數字留給聲調而 Shift 加數字選詞。翻頁與編輯鍵在組字時交給會話。呼叫端需傳入當次事件的虛擬鍵、鍵盤配置產生的 ASCII 字元及修飾鍵狀態，忽略 key-up 和死鍵，並自行處理單按 Shift 切換中英。文字服務在這層之前先處理空白鍵、Enter、方向鍵和標點（見上面的「打字」）。Ctrl、Alt、Windows 組合鍵保留給系統或應用程式。這個轉換層不會安裝鍵盤攔截器或註冊 TSF。

中日混打是一個開關，新安裝預設關閉；舊版已安裝的人升級後維持開啟（`Install.ps1` 發現已安裝的版本時，在新版第一次執行前寫入 `MixedJapanese=1`，並把沒存過的 `Simplified` 寫成 0，所以沒動過設定的人也一樣）。Windows 沒有設定 App：開關是 `HKCU\Software\Meowpuut` 的 `MixedJapanese`（DWORD，0 或 1），也在工作列輸入指示的右鍵選單裡（「中日混打」）。文字服務在開始新的一段讀音時讀它，所以改了之後下一段就生效，各程式都一樣；關閉時不向核心註冊日語詞，也就沒有帶貓爪的候選和中日混合的整句。第一次執行時，繁簡依系統語言和地區決定（`InputDefaults.h`，只在沒有存過 `Simplified` 的新安裝使用，只存結果、不存地區）；拼音和注音兩個鍵盤都會裝；`Install.ps1` 對新安裝依同一張表（地區 TW 或帶 TW 的繁體語言先注音，其他先拼音）把預設的那個排在語言的鍵盤清單最前面，之後使用者可以在 Windows 的鍵盤清單自己選。`Install.ps1` 的 `Get-Start` 和 `chooseStart` 要一起改。

拼音會話可在輸入前呼叫 `registerJapaneseWords(path)`，從生成的 mimo 詞庫目錄載入 `native-japanese.tsv`；它是審過的小型日語詞表（`Resources/Lexicon/japanese.tsv` 與 `japanese-reviewed.tsv`），格式為 `讀音|詞|權重`；macOS、iOS 改讀約 46,000 詞的 `mimo_jp.bin`，Windows 尚未改。未呼叫時保持純中文核心行為。混合中文與日語的完整句和前半段透過跨平台 C 核心產生，選取前半段後會保留未轉換的輸入。快照的 `japaneseCandidates` 與候選一一對應：每頁最多一個中日混合候選，排在該頁中文之後；額外候選放下一頁。第一頁可能有七個中文及一個額外的中日候選，拼音可用數字 1–8 選取。候選窗按快照的實際數量顯示，日語候選後面加貓爪記號。

生成詞庫後，可執行 `python3 scripts/export-windows-lexicon.py <生成詞庫目錄> <新的輸出目錄>` 匯出 Windows 使用的三份資料表與 `Licenses` 聲明目錄。發佈 Windows 二進位時須一併附上該目錄；`scripts/package-windows.sh` 從匯出結果取文字服務需要的三個檔案和 `Licenses`。

同一次組字中，選擇中文前段後，會話會暫存短期上下文以協助後段排序；整句完成、取消、刪除至空或清除時會移除。選中含日語的混合前段會作為語境邊界，清除之前的中文上下文，並保留尚未轉換的讀音。上下文只存在於各自的會話，不寫入個人詞庫。

單次組字達到核心容量時，下一個有效按鍵會先將原始讀音加入待提交文字，再開始新的組字；注音保留顯示的注音符號，拼音保留原文。待提交內容必須由呼叫端透過 `takeSnapshot()` 依序上屏；連續按鍵之間未取快照也不會覆蓋前一次結果。

TSF 的 [`OnTestKeyDown`](https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfkeyeventsink-ontestkeydown) 是按鍵意向查詢；`canHandleKey` 不改變組字、候選或待提交文字，可用於此查詢。實際處理另調用 `handleKey`。兩者之間應使用同一輸入上下文的會話與模式；模式必須與會話載入的詞庫一致。核心按鍵接受規則有變動時，要同步更新查詢並執行回歸：測試逐一比對拼音、注音在空輸入和組字狀態下所有 ASCII 可列印鍵的查詢結果與核心行為。`TextService/` 照這個規則接 TSF。

## 文字服務

`TextService/`：

- `TextService.cpp`：COM 伺服器（`DllGetClassObject`、`DllRegisterServer` 等）和 TSF 文字服務。TSF 為每個有鍵盤輸入的執行緒建立一個 `Service`；每個收到按鍵的 `ITfContext` 有自己的 `ContextState`（會話、組字、待上屏佇列、反白、面板展開）。`OnTestKeyDown` 和 `OnKeyDown` 用同一個沒有副作用的 `plan()` 決定要不要吃下按鍵，再由 `perform()` 執行。文字一律在 `TF_ES_ASYNCDONTCARE | TF_ES_READWRITE` 的編輯工作階段裡寫入，寫入時讀當下的狀態，所以排隊中的工作階段不會寫進舊內容。候選窗的位置來自 `ITfContextView::GetTextExt`，用 `LogicalToPhysicalPointForPerMonitorDPI` 換成實體像素；拿不到時（`TS_E_NOLAYOUT`）先用系統游標，等程式透過 `ITfTextLayoutSink` 通知版面變了再重讀一次。
- 註冊：`ITfInputProcessorProfileMgr::RegisterProfile` 在 zh-TW（0x0404）和 zh-CN（0x0804）兩個語言下各註冊兩個設定檔（兩個語言都註冊，使用者不必為了用喵撲新增不用的語言），`ITfCategoryMgr` 註冊鍵盤、顯示屬性、immersive、systray 和 UI element（`GUID_TFCAT_TIPCAP_UIELEMENTENABLED`）類別。少了 UI element 類別，以 UI-less 模式啟動 TSF 的遊戲根本不會載入喵撲；類別在 `DllRegisterServer` 裡寫入，所以舊版要重新執行一次 `Install.cmd` 才有。類別 GUID `{961C0ADA-4DC6-488F-9563-4FC144EFAE27}`、拼音設定檔 `{8FDE097D-3513-486A-9AB5-2D1B2C67C359}` 沿用 0.1.x；注音設定檔是 `{1F5D9D62-F4AF-4AAF-82A6-A482D7EC3AE2}`。
- 檔案位置：`lexicon\` 和 `Assets\` 先找 DLL 旁邊（建置資料夾），沒有再找上一層（安裝後 DLL 在 `<雜湊>\x64\` 這類子資料夾，`lexicon\`、`Assets\` 在 `<雜湊>\`）。
- 給貓窩的脈衝：每按一鍵（輸入法吃下的，和交給程式的都算，但不算 Shift、Ctrl 這些修飾鍵）、每次上屏，各寫一筆（`Desk/PulseChannel.h`，見「貓窩」）。只在打字紀錄也會記的地方送：不在 AppContainer、安全模式、密碼和唯讀欄位。組字起點在開始組字和上屏後的那次編輯工作階段裡量一次（組字範圍收合到起點的 `GetTextExt`），不逐鍵量。
- `TsfDeclarations.h`：MinGW 的 `msctf.h` 缺的介面（`ITfTextInputProcessorEx`、`ITfDisplayAttributeProvider`、`ITfLangBarItemButton`、`ITfMenu`）和幾個 GUID，還有 UI-less 模式用的 `ITfCandidateListUIElement`、`ITfCandidateListUIElementBehavior` 和 `TF_CLUIE_*`（順序與數值照 Windows SDK 的 msctf.h）。
- `Install.ps1`、`Install.cmd`、`Uninstall.cmd`：安裝與移除，見上面。
- `TextService.def`、`TextService.rc`：匯出表、版本資訊和圖示（建置時由 `assets/brand/AppIcon.png` 產生 `Meowpuut.ico`）。

0.1.1 的文字服務（commit 5e33638）沒有用 TSF 組字，讀音只顯示在另外啟動的 C# 玻璃預覽程式裡。這次留下的是：上屏佇列跟著欄位走、程式拒絕時保留不丟（0.1.1 的 `PendingDelivery`）、每個欄位各自保留讀音、密碼框和唯讀欄位不接手、`OnTestKeyDown` 不改變狀態。其餘改寫：組字、顯示屬性、`UI/` 的候選窗、注音、工作列的中／英指示、詞庫改用 `mimo_base.bin`。

## 打字紀錄（小貓的記憶）

`PetLedger.h/.cpp` 在每個載入 DLL 的程式裡各有一份帳本，和 iOS、Android 共用同一套規則（`meowpuut-pets`，`mp_pets_*`）。只存數量：確認送出的字數、打字的當地日期、每天的字數級距、哪幾隻貓在、一天裡的哪個時段；不存打了什麼、程式名稱或時間戳。檔案是 `%LOCALAPPDATA%\Meowpuut\PetJournal.bin`（固定 2884 位元組），旁邊有 `PetJournal.lock`。

- 字數在文字進入該欄位的送出佇列時算一次（`TextService.cpp` 的 `deliver`），用不依賴 ICU 的 `countGraphemes` 近似擴展字素簇；CJK、假名、拉丁字母和常見 emoji 是準的。
- 不逐鍵寫檔：每個程式最多每 60 秒寫一次（下一次送出時才檢查，沒有執行緒也沒有計時器），另外在 `Deactivate`、離開某個輸入欄位時寫。寫檔在檔案鎖下進行：讀檔、加上本程式的增量、合併、寫暫存檔、改名覆蓋。鎖等不到（約 0.25 秒）就放棄這一輪，之後再試。
- AppContainer 程式、安全模式（`TF_TMF_SECUREMODE`）和本輸入法不接管的欄位（密碼、唯讀）不計也不寫。
- 語言列選單「清除打字紀錄…」確認後清空並提高 epoch，別的程式之後的合併不會把舊數字帶回來。
- 貓窩也寫同一個檔：桌面上的摸摸（`MP_DESK_OUT_PETTED`，算 `MP_PETS_TAP`）、呼嚕（`PURR`，算 `MP_PETS_HOLD`）和掉落（`DROP`，算 `MP_PETS_DROP`）用同樣的規則分批寫（`DeskLoop.h` 的 `recordTouches`），另外在你離開（5 分鐘沒有輸入）、睡眠、登出和結束時寫。貓窩開啟時和每次寫檔合併之後，把帳本的狀態交給核心（`mp_desk_adopt`），今天的打招呼、躲貓貓次數、合唱次數和輸入法共用。

## 貓窩（桌面寵物）

`Desk/` 是三個程序裡的第二個（`design-preview/pc/FINAL.md` §7.1）：桌面寵物「喵撲貓窩」`MeowpuutDesk.exe`。輸入法只畫候選窗裡的貓；米米莫和莫莫出門到桌面上時，由貓窩畫。貓在哪裡、做什麼、什麼時候需要再看一次，全部由 Rust 核心決定（`Engine/crates/meowpuut-pets/src/desk.rs`，C ABI `mp_desk_*`、`mp_pulse_*`）；貓窩只量桌面、轉送輸入、畫圖。

- **登入項目，不經 Steam**：`HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 的 `MeowpuutDesk`。`MeowpuutDesk.exe --register-login` 寫入、`--unregister-login` 拿掉；`Install.ps1` 安裝時寫入並開啟，`-Uninstall`（含 `-Steam -Uninstall`）時關掉並拿掉。貓窩從不呼叫 SteamAPI，所以不會讓你整天顯示「正在玩」。只有 64 位元（x64、Arm64）；32 位元的程式裡，x86 的 DLL 照樣送脈衝。同一個工作階段只會有一個貓窩，第二個開啟時看到第一個就結束。
- **一隻貓一扇小視窗**：`WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_TOPMOST | WS_EX_NOACTIVATE`，預設再加 `WS_EX_TRANSPARENT`（點擊穿透）。圖是使用者的黏土貓 PNG（`Assets\`，和候選窗同一批 3 px/pt 的圖，加上 CLAY-POSES 第一、二批的桌面姿勢，尺寸和錨點照 `Assets\desk.json`），用 WIC 解碼成預乘 BGRA，只做放置、鏡像、壓扁（核心給的 ≤ 6 %，畫的時候也夾在 6 % 以內）、傾斜，以及躲在視窗邊後時照 `cats.json` 的 `cut`、`paws` 裁切（只露出邊緣以上和搭在邊上的爪子），再交給 `UpdateLayeredWindow`。不重畫、不改色。腳下的接觸陰影透明度在邊緣是 0，而且不算進命中範圍。
- **黏土貓會走路**：給核心的 `art` 是 `SLEEP|PEEK|SIDE|WALK`（116），所以沿著邊走（`walk_1`～`walk_4`，96 × 64 pt），不再跳。走路圖面向右，往左走時鏡像；走路時不傾斜、不彈跳。核心每走一步翻一次 `phase`（打字時一鍵一步），貓窩每看到一次翻轉就換下一格（`DeskArt.h` 的 `Gait`），所以一鍵一格，新的一段路從 `walk_1` 開始。走到了先用 `turn`（側身轉成正面）0.25 秒，再坐成 `idle`（減少動態時不轉）。看著你打字那一行用 `look_up`（圖往左上看，往右時鏡像）；拎起用 `lift`（64 × 80 pt），掛在後頸：錨點 (0.5, 0.06) 對準核心的後頸（腳上方身高 52 pt 的 0.82），懸空的腳留在視窗裡；放手往下掉用 `fall`；莫莫睡覺用她自己的 `momo_sleep`。米米莫還沒有起跳圖，起跳時仍是 `idle`。
- **停 250 ms 才能點**：游標停在貓**本身不透明的像素**上滿 250 ms，那隻貓的視窗才拿掉 `WS_EX_TRANSPARENT`（貓轉頭看你，`MP_DESK_EV_NOTICE`）；游標一離開那些像素就恢復穿透。按著滑鼠鍵（拖視窗、選字）、打字中（2 秒內有脈衝）、全螢幕時永遠穿透。能點時：點一下摸摸、按住呼嚕、拖曳拎起或丟出去、雙擊名牌、右鍵選單（摸摸、叫牠們回家／出門、安靜模式（一小時）、設定 ›「打字時陪走」「登入時開啟貓窩」「記錄時間花在哪裡」「暫停記錄」「關閉桌面貓」）。系統匣的喵撲圖示是貓門：點一下叫回家或出門，右鍵是同一份選單加「結束貓窩」。快捷鍵 `Ctrl+Alt+Shift+M` 也是叫回家／出門（`RegisterHotKey`，不是鉤子）。
- **量桌面，只讀矩形**：`EnumWindows`（前後順序）＋`IsWindowVisible`、`IsIconic`、`DWMWA_CLOAKED`（別的虛擬桌面）、`DWMWA_EXTENDED_FRAME_BOUNDS`；不算工具視窗（除非有 `WS_EX_APPWINDOW`）、點擊穿透的覆蓋層、自己的視窗和桌面背景。視窗上緣要有一隻貓高的空位、扣掉左邊 12 和右邊 146（標題列按鈕）、扣掉前面視窗擋住的部分，短於 56 不算（`DeskWorld.cpp`）。工作列上緣：主螢幕用 `SHAppBarMessage(ABM_GETTASKBARPOS／ABM_GETSTATE)`，其他螢幕看工作區比螢幕矮了多少；自動隱藏時用螢幕下緣。工作列在你正在用的視窗正下方的那段只能探頭。量桌面從不讀視窗標題、類別名稱、執行檔或程序名稱（「記錄時間花在哪裡」開著時，前景視窗那一個程式的執行檔名稱例外，見下）。座標：Per-Monitor V2 的實體像素 × 96 ÷ 主螢幕 DPI，所有螢幕在同一個平面上；貓照牠所在那個螢幕的 DPI 畫，大小不變。
- **計時器三層**（`DeskLoop.h`，FINAL §4.6 第 3 點）：核心說「下一次什麼時候要看」，貓窩只排那一個計時器。有貓醒著 10 Hz、都睡著 2 Hz 取樣游標（`GetCursorPos`，可合併的 `SetCoalescableTimer`）並重量桌面；有變才把世界交給核心。你不在（5 分鐘沒有輸入）時沒有任何計時器，只有一個可合併的 30 秒 `GetLastInputInfo` 檢查，而且沒有新輸入時連核心都不叫醒。全螢幕（`SHQueryUserNotificationState` 的 `QUNS_BUSY`、`QUNS_RUNNING_D3D_FULL_SCREEN`、`QUNS_PRESENTATION_MODE`，或前景視窗蓋滿它的螢幕）時貓收起、0 計時器；`EVENT_SYSTEM_FOREGROUND` 和前景程式的 `EVENT_OBJECT_LOCATIONCHANGE`（`SetWinEventHook`，`WINEVENT_OUTOFCONTEXT`，不注入）把牠們叫回來。拖曳、空中時每 16 ms 畫一次；沒有東西動時進 EcoQoS。
- **脈衝**（`PulseChannel.h`，r2-eng §8.4）：輸入法每按一鍵、每次上屏寫一筆固定 36 位元組的脈衝（`mp_pulse_encode`：累計的按鍵數、上屏次數、字數，組字中，組字起點量化到 8 pt，候選窗「展開」「在上方」兩個位元），不帶文字、哪個鍵、確切游標或候選窗大小。通道是私有命名空間（boundary descriptor 放使用者 SID 和 Medium 完整性標籤）裡的一小塊共享記憶體和一個自動重設事件，DACL 只給目前使用者，強制標籤 `S:(ML;;NRNWNX;;;ME)`，低完整性和 AppContainer 程式讀不到、也搶不到名字；**沒有** `ALL APPLICATION PACKAGES`。貓窩建立，輸入法在第一個按鍵時才打開，貓窩沒開時最多每 3 秒試一次。寫入用一次 compare-exchange 拿 seqlock，拿不到就丟掉這一筆（不等、不配置記憶體）；計數放在共享記憶體裡，換程式打字也一直往上加。AppContainer 程式、安全模式（`TF_TMF_SECUREMODE`）、密碼與唯讀欄位不送。組字起點只在開始組字和上屏時重量，逐鍵的游標不會離開輸入法。共享記憶體是版面第 2 版（512 位元組，物件名稱 `PulseSlot.v2`、`PulseSignal.v2`；舊的 128 位元組第 1 版不再讀寫）：除了脈衝，還有「哪幾隻貓在桌面上」（貓窩每一步寫，全螢幕時和結束時寫 0）和「現在」一欄（見「時間花在哪裡」）。欄位位置寫在 `PulseChannel.h` 開頭。
- **打字時的座位（乙，FINAL §4.2）**：候選窗每次更新時讀共享記憶體的「哪幾隻貓在桌面上」（`pulse::Client::glassCats`，一次讀取，不等待）：米米莫在桌面上（沿你正在打字的視窗上緣陪走）時，候選玻璃上只坐莫莫；其他時候（兩隻都在玻璃上、甲、叫回家了、全螢幕）兩隻都坐。莫莫一律坐在玻璃上，第一個鍵就在，不等貓窩。貓窩沒在跑時兩隻都坐：正常結束時它把欄位清成 0；它當掉沒清時，候選窗在米米莫那一位是 1 時最多每 3 秒看一次貓窩的單一執行個體互斥體還在不在（只打開再關掉，不等待；貓窩的執行緒一直持有它，所以輸入法看一眼不會讓剛啟動的貓窩誤以為已經有一個在跑）。玻璃的位置和大小不因米米莫離開而改變（`CandidateViewModel::seated`）；節日那天只坐穿節日衣服的莫莫。AppContainer 程式裡不開共享記憶體，兩隻都坐。
- **設定**：`HKCU\Software\Meowpuut` 的 `DeskPets`（DWORD，預設 1；0 時貓窩一開就結束）、`TypingSeat`（0＝乙：打字時莫莫在候選玻璃上、米米莫沿你正在打字的視窗上緣陪走，預設；1＝甲：兩隻都在候選玻璃上）、`DeskCatsOut`（哪幾隻在桌面上，叫回家會記住）、`ActivityStats`（DWORD，預設 0）、`ActivityPaused`（DWORD 0／1）、`ActivityHidden`（REG_MULTI_SZ，不記錄的執行檔名稱）、`ActivityShareAppName`（DWORD，預設 0；陪伴 App 只在「分享開著且『顯示 App 名稱』開著」時寫 1，其餘寫 0；1 時貓窩才讀前景 App 的顯示名稱〔版本資源的 FileDescription，沒有就用執行檔名去掉 .exe〕，只放記憶體並放進共享記憶體的「現在」一欄，不寫檔、不讀視窗標題）。改了馬上生效（`RegNotifyChangeKeyValue`）。減少動態跟隨系統的「顯示動畫」（`SPI_GETCLIENTAREAANIMATION`）。
- **摸摸、掉落、呼嚕記進打字紀錄**：見「打字紀錄（小貓的記憶）」。

### 時間花在哪裡（FINAL §11，預設關閉）

Steam 版的「記錄時間花在哪裡」由貓窩做（`ForegroundWatcher.h/.cpp`、`ActivityLedger.h/.cpp`，Rust 的 `mp_activity_*`）。輸入法完全不參與。

- **開關**：`ActivityStats` 是 1 才做任何事；預設 0。貓窩右鍵選單「設定 › 記錄時間花在哪裡」打開時先跳一個說明（只存類別和分鐘數、不記標題或網址、App 名稱只在記憶體裡分類），按確定才寫入；「暫停記錄」寫 `ActivityPaused`。`ActivityHidden` 列出的執行檔（不分大小寫）不記錄（`mp_activity_set_rules` 的 `MP_ACTIVITY_HIDDEN`）。關著時：前景鉤子不查任何程式、沒有計時器、不讀也不寫檔案。從開切到關時，先把開著那段已經算到的分鐘寫進檔案，然後從記憶體丟掉帳本和 App 名稱，之後什麼都不做。記錄在貓窩裡跑，所以「關閉桌面貓」（`DeskPets` 0，貓窩不啟動）也就不記錄。
- **前景 App**：用貓窩本來就有的 `SetWinEventHook(EVENT_SYSTEM_FOREGROUND, WINEVENT_OUTOFCONTEXT)`。前景視窗所屬程式用 `OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)`＋`QueryFullProcessImageNameW` 取得執行檔路徑，轉小寫，交給 Rust 分類時只用**檔名**（`code.exe`）；只有路徑會改變分類時（例如 `...\steamapps\common\...` 下的遊戲）才用完整路徑。它只在那個程式在前景的期間留在記憶體裡（每一步都要交給 `mp_activity_step`），從不寫進任何檔案或共享記憶體。`ApplicationFrameHost.exe` 外框裡的 UWP App：找外框底下屬於別的程式的子視窗，用那個程式的檔名；找不到就用外框本身，歸「其他」。讀不到路徑的程式也歸「其他」。不讀視窗標題、類別名稱、網址；不讀 App 的顯示名稱（那要 §11.1 的「顯示 App 名稱」開關，現在還沒有這個開關）。
- **閒置**：`GetLastInputInfo`，搭著貓窩原有的取樣（有貓醒著 10 Hz、都睡著 2 Hz），另外照 Rust 追蹤器自己的排程加兩個可合併的計時器：正在算分鐘時每 60 秒一次、你離開（超過閒置門檻 3 分鐘；影音或全螢幕 10 分鐘）後每 30 秒看一次有沒有輸入。全螢幕用貓窩自己的判斷（`QUNS_*` 或前景視窗蓋滿螢幕），全螢幕時也照算（只影響好友看到的內容）。
- **檔案**：`%LOCALAPPDATA%\Meowpuut\Activity.bin`（固定 12,824 位元組），旁邊 `Activity.lock`。寫法和 `PetJournal.bin` 一樣（`PetLedger.h` 的 `LedgerFile`）：鎖檔、讀檔、加上本程式的增量、合併、寫暫存檔（`Activity.bin.tmp<程序號碼>`）、改名覆蓋、再把結果合併回來。分批寫：開始算分鐘後最多每 5 分鐘一次，另外在你離開、暫停、切到不記錄的 App、關掉開關、睡眠、登出和貓窩結束時寫。檔案裡只有日期、類別、分鐘數和時段：沒有 App 名稱、路徑、標題或時間。移除時 `Install.ps1 -Uninstall` 一起刪。
- **給陪伴 App 的「現在」**：共享記憶體位移 128 起是一欄固定大小的「現在」：位移 128 的 `nowSeq`（貓窩寫入時是奇數），位移 136 起是原樣的 `MPActivityCurrent`（272 位元組：狀態、類別、全螢幕旗標、保留、`app[256]`）。唯一的文字 `app` 只在「顯示 App 名稱」開關允許時放 App 的顯示名稱；現在沒有這個開關，所以一律是空字串。關著時狀態是 `MP_ACTIVITY_NOW_OFF`、類別 255。讀法：讀 `nowSeq`（奇數就稍後再讀），複製 272 位元組，再讀一次 `nowSeq`，兩次相同才算數（`pulse::now`）。共享記憶體的 DACL 只給目前使用者，強制標籤 `NRNWNX;;;ME`：只有同一個使用者、Medium 以上完整性的程式打得開，低完整性和 AppContainer 程式讀不到。陪伴 App 的 Steam 分享模式還沒做。

檔案：`PulseChannel.h/.cpp`（輸入法和貓窩共用；名稱、版面、seqlock、座位、「現在」一欄可在主機測試，Win32 的部分在 `_WIN32` 裡）、`DeskWorld.h/.cpp`（世界）、`DeskArt.h/.cpp`（黏土圖、走路格、裁切、命中）、`DeskLoop.h/.cpp`（核心外的迴圈、停留判定、摸摸記帳）、`DeskSettings.h/.cpp`、`ForegroundWatcher.h/.cpp`（時間花在哪裡：開關、排程、前景 App）、`ActivityLedger.h/.cpp`（`Activity.bin`）、`DeskSmoke.h/.cpp`、`DeskApp.cpp`（Win32）、`Desk.rc`、`Desk.manifest`（Per-Monitor V2、asInvoker）、`tests/desk_test.cpp`。

`MeowpuutDesk.exe --smoke` 在 Windows 上不開視窗跑一次：假的桌面、模擬的時鐘照核心的排程走，用真的 PNG 畫貓；兩隻從系統匣出門後都站在地面上、游標停 250 ms 才能點、第一個按鍵莫莫就在候選玻璃上、米米莫在桌面上時候選窗只坐莫莫、米米莫沿視窗上緣走時走路圖一鍵換一格、停手後回到桌面、離開 10 分鐘裡核心 0 次喚醒、0 次取樣、只有 30 秒的輸入檢查；最後用另一組名稱真的建立一次私有命名空間（第二個貓窩在另一個執行緒上開，看到第一個就退出），從輸入法那一側送一筆脈衝再讀回來，讀候選窗的座位和「現在」一欄。結束代碼 0 是通過。它是視窗程式，命令列不會等它結束；在 PowerShell 用 `(Start-Process .\MeowpuutDesk.exe --smoke -NoNewWindow -Wait -PassThru).ExitCode`，結果印在同一個主控台。同一段（除了真正的命名空間）也在 macOS 上由 `scripts/build-windows-check.sh` 的 `desk_test` 跑；`desk_test` 另外測走路格一步一格與鏡像、壓扁上限、拎起掛在後頸、座位欄位的來回、「現在」一欄、摸摸／掉落／呼嚕寫進打字紀錄，以及時間紀錄：關著時什麼都不算也不寫檔、開著時一分鐘算一分鐘、離開、暫停、不記錄的 App 都不算、關掉後不再算，寫出的 `Activity.bin` 裡找不到執行檔名稱。

## Steam

Steam 把 depot 裝在自己的 `steamapps\common\...` 資料夾，而輸入法 DLL 會被每個程式載入，Steam 更新覆蓋不了使用中的檔案，所以輸入法**絕不從 Steam 的資料夾註冊**。`installscript.vdf`（在 `steam/`）讓 Steam 在玩家第一次啟動遊戲時以系統管理員身分執行 `Install.ps1 -Steam`：它不問任何事，沒有系統管理員權限時以代碼 5 離開，成功 0、失敗非 0（Steam 下次啟動會再跑）；把 depot 的檔案複製到 `Program Files\Meowpuut\<雜湊>` 並從那裡註冊（64 位元和 32 位元的 DLL 都註冊），再把鍵盤加進目前使用者的中文語言，並清掉不再使用的舊資料夾。重複執行不改變任何東西。解除安裝用 `Install.ps1 -Steam -Uninstall`。depot 的內容、installscript 各個鍵的來源與沒確認的部分、以及還要做的事（Authenticode 簽章、`HasRunKey` 的 build 號、Valve 的同意、真機測試）都在 `steam/README.md`。

## 建置

### 在 macOS 上交叉編譯

```sh
# 一個架構的 DLL，輸出到 .build/windows-ime/<x64|x86|arm64>/
MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/build-windows-ime.sh x64
MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/build-windows-ime.sh x86
MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/build-windows-ime.sh arm64

# 貓窩（只有 64 位元），輸出到 .build/windows-desk/<x64|arm64>/（MeowpuutDesk.exe 和它畫的 Assets）
MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/build-windows-desk.sh x64
MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/build-windows-desk.sh arm64

# 發佈包：兩個 zip、兩個 Steam depot 資料夾（steam-depot-x64、steam-depot-arm64）和 SHA256SUMS.txt，輸出到 .build/windows-package/
MINGW_BIN=/path/to/llvm-mingw/bin MEOWPUUT_LEXICON_DIR=/path/to/generated/lexicon \
  bash scripts/package-windows.sh
```

需要 [llvm-mingw](https://github.com/mstorsjo/llvm-mingw)（0.8.0-alpha.1 用 20261020 的 UCRT macOS 版）、Rust 的 `x86_64-pc-windows-gnullvm`、`i686-pc-windows-gnullvm` 和 `aarch64-pc-windows-gnullvm` 目標（缺少時腳本執行 `rustup target add`），以及 Python 的 Pillow（做圖示）。x64 也可以用 Homebrew 的 mingw-w64 GCC，這時 Rust 目標是 `x86_64-pc-windows-gnu`。x86 只能用 llvm-mingw：Homebrew 的 i686 GCC 用 SJLJ 例外，Rust 的 i686 目標用 DWARF，連結不起來（`_Unwind_Resume` 找不到），腳本會直接說明後停下。DLL 靜態連結 C++ 執行庫和 Rust 核心，只依賴 Windows 內建的 DLL（UCRT、d2d1、DWrite、ole32 等）。

發佈包的內容：`x64/MeowpuutTextService.dll`、`x64/MeowpuutDesk.exe` 和 `x86/MeowpuutTextService.dll`（Arm64 包是 `arm64/MeowpuutTextService.dll` 和 `arm64/MeowpuutDesk.exe`）、`Assets/`（貓和節日的圖，含貓窩用的坐姿、開心、睡覺、走路、轉身、抬頭、拎起、掉落等圖和它們的 `desk.json`，保留所有權利）、`lexicon/`（`mimo_base.bin`、`mimo_pairs.bin`、`native-japanese.tsv`）、`Licenses/`（`export-windows-lexicon.py` 寫的聲明，含 `Meowpuut-MIT.txt` 和 `Meowpuut-Artwork.txt`，加上 LLVM、MinGW-w64 和 Rust 標準庫的授權）、`THIRD_PARTY.md`、安裝腳本、`installscript.vdf`（Steam 用，見下面），以及 `BUILD-INFO.md`（commit、編譯器版本、每個檔案的 SHA-256）。

### 在 Windows 上用 MSVC

需要 CMake 3.16 以上、Visual Studio 的 C++ 工具（MSVC）、Windows 10 SDK 和 Rust（`cargo` 在 PATH 中，MSVC 目標）。CMake 會先呼叫 `cargo build --release -p meowpuut-ffi`。這份 CMake 只支援 MSVC，32 位元的 DLL 用 `-A Win32` 設定（需要 `rustup target add i686-pc-windows-msvc`，Rust 函式庫會放在 `target/i686-pc-windows-msvc/release/`），用其他編譯器設定時會直接報錯；MinGW 請用下面的 `scripts/build-windows-check.sh`。

```powershell
cmake -S Windows -B build/windows -A x64 -DMEOWPUUT_LEXICON_DIR="C:/path/to/lexicons"
cmake --build build/windows --config Release
cd build/windows
ctest -C Release --output-on-failure
```

產生的目標：

| 目標 | 內容 |
| --- | --- |
| `MeowpuutWindowsSession` | `Session.cpp`、`Keyboard.cpp`，連結 Rust 核心 |
| `MeowpuutCandidateLayout` | `UI/` 中不含 Windows 標頭的部分 |
| `MeowpuutCandidateWindow` | `UI/CandidateWindow.cpp`，連結 d2d1、dwrite、windowscodecs |
| `MeowpuutCandidatePreview` | 預覽程式，連結 imm32 |
| `MeowpuutTextService` | 文字服務 DLL；建置前要在建置目錄放一個 `Meowpuut.ico`（還沒用 MSVC 建置過） |
| `MeowpuutPreviewAssets` | 每次建置都把 `UI/Assets` 重新複製到執行檔旁，貓圖或節日清單更新後不必重新連結 |
| `MeowpuutDeskCore` | `Desk/` 中不含 Win32 的部分（脈衝通道、世界、黏土圖、迴圈、設定、smoke） |
| `MeowpuutDesk` | 貓窩；建置前要在建置目錄放一個 `Meowpuut.ico`（還沒用 MSVC 建置過） |
| `MeowpuutDeskAssets` | 把貓窩畫的圖複製到 `MeowpuutDesk.exe` 旁的 `Assets` |
| `meowpuut_session_test`、`meowpuut_layout_test`、`meowpuut_desk_test` | 測試 |

`MEOWPUUT_LEXICON_DIR` 須包含 `native-pinyin.tsv`、`native-zhuyin.tsv` 和 `native-japanese.tsv`，由 `scripts/export-windows-lexicon.py` 匯出。未指定時不註冊 `session` 測試，`candidate_layout` 測試照常執行。這裡沒有 Windows 原生的詞庫編譯流程，請先在已支援的環境產生檔案。

## 候選窗

`UI/` 是 Windows 的候選窗，依 [docs/DESIGN.md](../docs/DESIGN.md) 繪製：雙貓玻璃、三種造型（膠囊、波浪、雲朵）、淺色與深色。

檔案分兩層：

- `Geometry.h`、`GlassShape`、`CandidateLayout`、`Theme`、`Scene`、`Blur`、`Festival`、`SvgSnapshot` 是不含 Windows 標頭的 C++17。`CandidateLayout` 算出每個候選的位置和點擊範圍，`Scene` 把排版和配色轉成一串繪圖指令（填色、描邊、裁切、圖片、文字）。Windows 視窗和 SVG 快照重播同一串指令，所以快照的形狀、位置和顏色與 Windows 相同。字型不同（快照用 PingFang TC，Windows 用微軟正黑體），字寬和省略位置會有出入。
- `CandidateWindow` 是 Win32 視窗，用 Direct2D 重播 `Scene`，文字用 DirectWrite，貓的 PNG 用 WIC 讀取。
- `GlyphStrip`（不含 Windows 標頭）是字景條的平台那一半：量字寬、長圖、計時、點擊範圍；`GlyphText` 是它在 Windows 上的 DirectWrite 文字（見下一節）。

### 字景（候選窗的另一種造型）

預設的玻璃一個數值都沒改。字景（FINAL §3，衣櫥 No.04）是另一種造型：72 pt 高、從游標下 6 pt 開始的一小條風景。選中的字是太陽在水上的倒影（朱紅階梯角方塊，字從裡面鏤空；夜裡是月），兩隻像素貓走在稜線上，停手 3 秒一隻鳥落在選中的字上；展開（↓）整條往下長。風景、貓、外框、太陽、倒影、箭頭都由 Rust 畫（`meowpuut-glyph` 的 `strip.rs`，C 介面 `mp_glyph_strip_*`，見 `meowpuut.h` 的 Glyph strip 一節）；平台只做候選字。

- **設定**：`HKCU\Software\Meowpuut` 的 `CandidateSkin`（DWORD）：0＝玻璃（預設，沒有這個值時也是）、1＝字景。陪伴 App 寫入；文字服務在每段新的讀音開始時讀一次（和 `CandidateTextSize`、`Simplified` 一樣），所以改了之後下一段就換，各程式都一樣，不必重開。
- **建置**：Rust 核心用 `--features glyph` 建（`meowpuut-ffi`），C++ 定義 `MEOWPUUT_GLYPH`；`scripts/build-windows-ime.sh` 的 x64、x86、arm64 都這樣。手機的函式庫不開。
- **候選字**（`UI/GlyphText.cpp`）：DirectWrite 加 GDI 互通的位圖繪製目標（`IDWriteGdiInterop::CreateBitmapRenderTarget`），鋸齒模式（`DWRITE_RENDERING_MODE_ALIASED`），只用 CPU，不建 GPU 裝置。字型是俐方體 11 號（家族 “Meowpuut Pixel A”，`Assets\fonts\pixel_a.ttf`）；它沒有的字用 Fusion Pixel Font（“Meowpuut Pixel B”，`pixel_b.otf`），再沒有的才交給系統退回字型。兩個檔案都是 SIL OFL 1.1（授權文字 `Assets\fonts\OFL-pixel-fonts.txt`，打包時也放進 `Licenses\`），由專用（isolated）的 DirectWrite 工廠的私有字型集合載入，**不安裝到系統**，別的程式看不到。字以 12 × k px 畫在 Rust 給的筆位和基線上，先畫光暈（同一行字在 `halo_color` 以每個 (a, b) × k 偏移，0 < a² + b² ≤ 10），再畫字。
- **k 與每螢幕縮放**：`k = argmin |12k − 14.5 × 縮放|`（`mp_glyph_strip_k_for`：100% → 1、125–200% → 2、250% → 3）。視窗已經在 per-monitor v2 下建立，`showNear` 每次讀游標所在螢幕的縮放；換到 k 不同的螢幕時整條重畫一次。
- **每鍵零重畫**（`UI/GlyphStrip.cpp`，不含 Windows 標頭，主機測試和預覽工具也用它）：預先畫一條「長圖」＝每個候選一格 LAND（太陽在那個字後面），選中的那格馬上畫，其餘每 16 ms 補一格。← → Tab 時只把那一格複製進 DIB，疊上 OVER（倒影、箭頭、外框）與 CATS（貓、鳥；Rust 畫，約 0.03–0.17 ms），字用快取的遮罩蓋上，然後 `UpdateLayeredWindow`；不重畫地面、不重新點陣化字。打字（候選變了）、地面走一格（按鍵後 0.6 倍上一鍵間隔，限制在 60–160 ms 後呼叫 `mp_glyph_strip_land`）、換主題或換 k 時長圖作廢重畫。貓和鳥靠 12 Hz 的計時器（`mp_glyph_strip_busy` 為真時），靜止時沒有計時器；視窗隱藏時長圖釋放（上限 40 MB）。
- **按鍵種類**：`CandidateWindow::update` 比對上一次的模型：讀音變長是字母、變短是刪除、只有反白或頁面變是其他（← → Tab、翻頁）；隱藏後的第一次是字母。
- **旅程**：旅程的距離＝帳本的已確認字數（`PetLedger::committedGraphemes()`，每段新讀音開始時讀一次，`CandidateWindow::setJourney`）× 8（`UNITS_PER_CHARACTER`，C 介面的 `mp_glyph_strip_set_distance` 吃這個單位）。所以候選窗裡的地方就是 App 此刻的地方。這個行程只看自己的帳本狀態（檔案在第一次使用時讀入，之後每次寫入時合併），別的行程剛打的字下次合併後才看得到。
- **遊戲與無障礙**：無邊框全螢幕在最前面 → `mp_glyph_strip_set_options(GAME)`，一行、不動，只剩行首兩雙眼睛；UI-less 模式照舊。對比佈景主題（高對比）→ 不用 Rust 的顏色，畫玻璃造型的單行系統色清單（沒有貓、不透明、`WM_SETTINGCHANGE` 時切換）。「透明效果」關閉或「動畫效果」關閉 → `safe=true`（風景只留天空那一條且不動，字在白紙上，選中是墨塊）；動畫關閉另外 `still=true`。缺少 `Assets\fonts` 時退回玻璃。
- **位置與視窗**：第一個候選的數字對齊游標左緣，條在游標下 6 pt（每 pt 是 k 個像素），下方不夠時翻到游標上方，限制在工作區內。同一個擁有者視窗、`EVENT_OBJECT_IME_SHOW/HIDE/CHANGE`、UI-less 的 UI element 兩種造型都一樣；字景的條整個都能點（候選、右端箭頭展開），沒有懸停底色。
- **看圖**：`scripts/build-windows-check.sh` 在 macOS 上用 Core Text 代替 DirectWrite、用同一個 `GlyphStripView` 畫出淺色、深色、安全、遊戲、展開、注音、日語（貓爪），k=1 和 k=2，到 `.build/windows-check/glyph-previews/`（`*-page.png` 是放在頁面上的樣子），並印出每鍵的主機耗時。Windows 上的 DirectWrite 路徑只能在真的 Windows 上驗。

視窗的行為：

- 分層視窗（`WS_EX_LAYERED`），畫在 32 位元 DIB 上，再用 `UpdateLayeredWindow` 以預乘 alpha 顯示。Direct2D 用軟體繪製（`ID2D1DCRenderTarget`）：輸入法會載入每個應用程式，不在每個行程裡建立 Direct3D 裝置。繪製目標遺失（`D2DERR_RECREATE_TARGET`）時丟掉點陣圖和筆刷，重建後重畫一次。
- 擁有者與焦點（照 Microsoft〈IME requirements〉的 Owned window）：候選窗是最上層的彈出視窗，擁有者是正在打字的視窗（`ITfContextView::GetWnd`，拿不到時用 `GetFocus`，再取它最上層的祖先）。擁有者必須在同一個執行緒；別的執行緒的視窗不當擁有者，因為跨執行緒的擁有關係會把兩個執行緒的輸入佇列綁在一起。擁有關係建立後不能轉移，所以換了擁有者就重建視窗（只重建 HWND，Direct2D 的繪製目標不受影響）；擁有者關閉時候選窗會一起被銷毀，下次顯示時再建。樣式是 `WS_EX_TOPMOST`、`WS_EX_TOOLWINDOW`（沒有工作列按鈕）、`WS_EX_NOACTIVATE`；`WM_MOUSEACTIVATE` 回傳 `MA_NOACTIVATE`，`WM_POINTERACTIVATE` 回傳 `PA_NOACTIVATE`，用滑鼠、觸控或筆點候選，輸入框都不會失去焦點。
- 出現、消失、移動或改變大小時，用 `NotifyWinEvent` 發出 `EVENT_OBJECT_IME_SHOW`、`EVENT_OBJECT_IME_HIDE`、`EVENT_OBJECT_IME_CHANGE`（〈IME requirements〉的 light dismiss；參數與 Microsoft 的 SampleIME 相同）。
- 每螢幕 DPI（v2）：視窗在 per-monitor v2 的執行緒 DPI 環境下建立，宿主行程不支援 DPI 時座標仍是實體像素。`showNear` 每次依游標所在螢幕讀取縮放和工作區；`WM_DPICHANGED`、`WM_DISPLAYCHANGE` 和工作列變動時重新擺放。
- 位置：第一個候選對齊游標左緣，貓耳在游標下方 6 pt；下方不夠時翻到上方。玻璃和貓留在螢幕工作區內，陰影可以超出。展開面板時玻璃和貓不動，面板標題的讀音對齊游標。
- 候選永遠單行，不換行。寬度由 DirectWrite 實測並快取（最多 768 筆），日語候選的貓爪記號算進寬度。單一候選超過 440 pt 或列寬七成時在中間省略，完整文字在展開面板（長句一行一個，面板最寬 560 pt）。整頁放不下時最長的候選先縮短，每個候選至少留第一個字；省略號不會切開旗幟、組合符號或異體字選擇符。文字 15 pt，候選前有 11 pt 的選詞編號（拼音 `1`–`8`，注音 `⇧1`…）。尺寸依 DESIGN.md §5 的桌面表，Windows 不同的地方列在同一節。
- 滑鼠：移到候選上有底色，按下時底色縮到 0.95，按下、放開在同一個候選才算選取；滾輪翻頁；右端箭頭展開成格狀面板，面板下方有上一頁、下一頁。陰影部分回傳 `HTTRANSPARENT`，點擊會落到同一執行緒下方的視窗。
- `update()` 只記下內容，下一輪訊息迴圈重畫一次；緊接著呼叫 `showNear()` 時只畫那一次。
- 米米莫在玻璃上緣的左端、莫莫在右端探頭，前爪搭在玻璃上（docs/DESIGN.md §1 的「兩端探頭」）。視窗只有一兩個候選、放不下兩隻時，只留莫莫。
- 分層視窗拿不到背後的畫面，不能即時模糊。所以玻璃本體接近不透明（淺色 98.5%，深色 98%），只有外緣 3 pt 是透明的，背後的文字不會和候選字混在一起。系統關閉透明效果時改為不透明底；高對比時使用系統顏色、隱藏貓、沒有陰影。
- 淺色／深色預設跟隨「應用程式模式」（`AppsUseLightTheme`），也可固定。系統設定變更（`WM_SETTINGCHANGE`）時自動重畫。
- 點米米莫他會縮一下搖頭，點莫莫她會歪一下頭（約 0.6 秒，系統關閉動畫時不動）。連點莫莫五下（兩下間隔不超過 1.6 秒）會呼叫 `toggleScript`，由宿主切換繁簡。高對比時沒有貓。計時器只在動作進行中存在，隱藏視窗時停止。
- 節日裝扮：讀取 `Assets\festivals\festivals.json`（見 [docs/FESTIVALS.md](../docs/FESTIVALS.md)）。當地日期在某套裝扮的期間內時，畫上 `front`、`back` 疊圖，替換列出的表情，並可改選中候選的顏色。清單為空或缺檔時照常顯示原本的貓；目前隨附的清單是空的。選項 `festivalOutfits` 可關閉，預設開啟。

用法：

```cpp
meowpuut::ui::CandidateWindowCallbacks callbacks;
callbacks.choose = [&](std::size_t index) { session.chooseOnPage(index); /* 更新組字與候選 */ };
callbacks.page = [&](int direction) { session.key(direction < 0 ? 0xff55 : 0xff56); };
callbacks.toggleExpanded = [&] { state.expanded = !state.expanded; };
callbacks.toggleScript = [&] { /* 切換繁簡：session.setSimplified(...)，再更新候選 */ };
meowpuut::ui::CandidateWindow window(instance, {}, callbacks);
if (!window.valid()) { /* Direct2D、DirectWrite 或 WIC 無法使用 */ }

auto snapshot = session.takeSnapshot();
window.update(meowpuut::ui::makeViewModel(snapshot, state, meowpuut::ui::GlassShape::Capsule));
window.showNear(caretRectInScreenPixels);   // 沒有候選時會自動隱藏
```

視窗要在有訊息迴圈的執行緒建立、使用和銷毀（文字服務的 UI 執行緒）。`instance` 是擁有視窗類別的模組；最後一個視窗銷毀時類別會取消註冊，DLL 卸載前要先銷毀所有視窗。貓的圖檔預設從模組旁的 `Assets` 資料夾讀取，可用 `assetDirectory` 指定；圖檔由 `scripts/render_cats.py` 產生，不要手改。

## 遊戲與全螢幕

1. 遊戲自己畫候選（TSF UI-less 模式）：候選同時是一個 TSF UI element（`ITfCandidateListUIElementBehavior`）。出現候選時，文字服務先呼叫 `ITfUIElementMgr::BeginUIElement`；程式回答 `*pbShow = FALSE` 就由它自己畫，喵撲的候選窗不出現，之後候選、反白或頁面一改變就呼叫 `UpdateUIElement`，讀音結束、換欄位或失去焦點時呼叫 `EndUIElement`。程式以 `TF_TMAE_UIELEMENTENABLEDONLY` 啟動 TSF 時（`GetActiveFlags` 有 `TF_TMF_UIELEMENTENABLEDONLY`），喵撲的候選窗一律不出現。清單只有目前這一頁（引擎自己分頁，不提供其他頁的字），所以永遠是一頁、從 0 開始，翻頁時換掉字串；`SetPageIndex` 不支援。程式可用 `SetSelection` 移動反白（同 ← →）、`Finalize` 選反白的候選（同 Enter）、`Abort` 取消這段讀音（同收合時的 Esc）。候選字串只交給載入喵撲的那個程式（TSF 本來就是這樣），不會送到別處。沒有讀音的 UI element（`ITfReadingInformationUIElement`）：喵撲的讀音就是文件裡的組字，沒有另外的讀音視窗。
2. 無邊框全螢幕：前景視窗沒有標題列、而且蓋滿它所在的螢幕時，候選窗改成「一行」：沒有貓、沒有焦散、不透明、不動，按 ↓ 照樣能展開面板。最大化的一般視窗有標題列，不算。
3. 獨佔全螢幕：遊戲以獨佔模式占用顯示器時，桌面合成不顯示其他任何視窗，喵撲的候選窗也一樣。字照樣打得出來，但看不到候選，除非遊戲用 UI-less 模式自己畫。請在遊戲設定裡改用「無邊框視窗」。

### 預覽程式

`Preview/PreviewApp.cpp` 是一個編輯框，經由 `Session` 和 `Keyboard.h` 打字，在組字下方顯示候選窗。組字以普通文字暫放在編輯框中，這不是文字服務。選單可切換拼音／注音、繁簡、造型、色調、候選字級和節日裝扮；單按 Shift 切換中英；方向鍵或 Tab 移動選中候選，Enter 送出選中的候選；連點莫莫五下切換繁簡，組字保留。

編輯框不接系統輸入法（`ImmAssociateContext`），所以系統鍵盤是微軟注音或拼音時也能直接打字。組字中按標點、大寫字母等不屬於讀音的字元，會先送出選中的候選，再輸入那個字元；Delete、Home、Ctrl+Z、Ctrl+V 等編輯鍵在組字中沒有作用；按滑鼠左鍵或右鍵會先結束組字。

執行前把 `export-windows-lexicon.py` 匯出的目錄放在程式旁的 `lexicon` 資料夾，或用 `--lexicon <目錄>` 指定。

### 在 macOS 上檢查

```sh
bash scripts/build-windows-check.sh
```

分四段，輸出在 `.build/windows-check/`：

1. 排版測試：標準庫邊界檢查版和 UndefinedBehaviorSanitizer 版。設 `MEOWPUUT_ASAN=1` 另跑 AddressSanitizer。
2. `session_test`：用本機的 Rust 函式庫和 `Engine/.build/lexicon` 的詞庫（可用 `MEOWPUUT_LEXICON_DIR` 改）。沒有詞庫時跳過。
3. 快照：用 Core Text 量字，輸出各造型、色調和狀態的 SVG，再用 `rsvg-convert` 轉成 PNG，放在 `previews/`。檔名帶 `-on-page` 的是疊在一頁文字上的樣子。
4. 交叉編譯：Rust 核心、候選窗、預覽程式、排版測試和 x64 的文字服務 DLL，警告視為錯誤。結果在 `mingw/` 和 `mingw-ime/`。

第 2 段在詞庫目錄有 `mimo_base.bin` 時，也檢查文字服務的讀法：原地讀取、兩個會話共用一個詞庫、`commit()` 把讀音送完。

第 4 段需要 MinGW-w64。兩種做法：

```sh
# GCC：Homebrew 的 mingw-w64，Rust 目標用預設的 x86_64-pc-windows-gnu
brew install mingw-w64
bash scripts/build-windows-check.sh

# Clang：解開 llvm-mingw（UCRT 版），Rust 目標改用 gnullvm
MINGW_BIN=/path/to/llvm-mingw/bin RUST_WINDOWS_TARGET=x86_64-pc-windows-gnullvm \
  bash scripts/build-windows-check.sh
```

[llvm-mingw](https://github.com/mstorsjo/llvm-mingw) 的 Clang 要搭配 `x86_64-pc-windows-gnullvm`；腳本會在缺少時執行 `rustup target add`。找不到編譯器時第 4 段跳過，前三段照常執行。交叉編譯出來的 `MeowpuutCandidatePreview.exe` 只依賴系統 DLL，把 `mingw/` 整個資料夾和詞庫複製到 Windows 就能執行。

### 在 Mac 上的 Windows 虛擬機

`scripts/windows-vm.sh` 在 Apple 晶片的 Mac 上跑一台 Windows 11 Arm64 虛擬機，用來實際執行交叉編譯出來的預覽程式和測試。虛擬機是 Homebrew 的 QEMU 加上 macOS 的 Hypervisor（hvf），沒有視窗，全部經由 SSH 和 QEMU 的 QMP 控制。x64 的 exe 在 Windows 內建的 x64 模擬下執行。

檔案在主 checkout 的 `.build/windows-vm/`（不進 git，可用 `WINDOWS_VM_DIR` 改）：`disk.qcow2` 是系統碟，`vars.fd` 是 UEFI 變數，`media/` 是下載的安裝檔和做好的 `install.iso`、`data.dmg`（建好之後可以刪掉 `media/`，約 12 GB），`ssh/` 是登入用的金鑰。Windows 的帳號是 `meow`，密碼 `meow`，開機自動登入；SSH 只收這把金鑰，埠是本機的 127.0.0.1:2222。Windows 沒有啟用，也不需要產品金鑰。

從零建立：

```sh
brew install qemu wimlib xorriso
bash scripts/windows-vm.sh create
```

`create` 依序做這些事，中斷後重跑會從缺的那一步接著做：

1. 從 Microsoft 的 Media Creation Tool 目錄（`products.cab`）找出繁體中文 Arm64 的 ESD，從 `dl.delivery.mp.microsoft.com` 下載並核對 SHA-1（約 4.7 GB）。另外下載 virtio 網路卡驅動（Fedora 的 `virtio-win.iso`，Windows 沒有 QEMU 網路卡的驅動）和 Microsoft 的 OpenSSH（GitHub `PowerShell/Win32-OpenSSH`）。
2. 用 wimlib 從 ESD 取出開機檔和 Windows PE，做成 `install.iso`；ESD 本身超過 4 GB，放不進 ISO 9660，所以和網路卡驅動、OpenSSH、`scripts/windows-vm/` 的檔案一起放進 exFAT 的 `data.dmg`。不跑 Windows 安裝程式（因此沒有產品金鑰和版本選擇的畫面）：Windows PE 執行 `deploy.cmd`，分割磁碟、套用專業版映像、加入網路卡驅動、寫入 `unattend.xml`，然後關機。
3. 第一次開機時 Windows 依 `unattend.xml` 完成設定，`SetupComplete.cmd` 用 pnputil 裝網路卡驅動（Windows PE 裡的 `dism /Add-Driver` 會失敗，0x80004002），建立本機帳號並登入，`firstlogon.ps1` 安裝 OpenSSH 伺服器、關掉睡眠和自動更新。等到 SSH 能連、桌面出現就完成。

在這台 M4 上，ESD 下載約 10 分鐘，Windows PE 套用映像約 60 分鐘（資料碟走模擬的 USB，很慢），第一次開機到桌面約 10 分鐘。改了 `scripts/windows-vm/` 的檔案後，刪掉 `media/data.dmg` 和 `install.iso` 再 `create`，否則用的是舊的檔案；要整台重裝，另外刪掉 `installed` 和 `disk.qcow2`。之後每次開機到桌面約 30 秒，關機幾秒。螢幕固定是 1024 × 768（QEMU 的 ramfb）。

日常使用：

```sh
MEOWPUUT_LEXICON_DIR=Engine/.build/lexicon bash scripts/build-windows-check.sh
bash scripts/windows-vm.sh start                 # 開機，等到桌面出現
bash scripts/windows-vm.sh deploy                # 複製 mingw/ 的 exe、Assets 和詞庫到 C:\meow\preview
bash scripts/windows-vm.sh run 'C:\meow\preview\meowpuut_layout_test.exe'
bash scripts/windows-vm.sh shots                 # 三種造型 × 淺深色 × 收合／展開
bash scripts/windows-vm.sh scale 150             # 換顯示比例，Windows 會重新開機；1024 × 768 的螢幕到 200 % 時候選列放不下
bash scripts/windows-vm.sh shots
bash scripts/windows-vm.sh stop
```

其他指令：

| 指令 | 作用 |
| --- | --- |
| `copy <資料夾> [<Windows 路徑>]` | 複製到 Windows，預設 `C:\meow\<資料夾名>` |
| `run <命令>` | 經 SSH 在 cmd.exe 執行，輸出和結束碼原樣傳回。SSH 登入的 `meow` 是系統管理員，命令本來就是提高權限的 |
| `desktop '<命令列>'` | 在已登入的桌面啟動程式（以提高權限的排程工作執行），程式啟動後就返回。SSH 啟動的程式在 session 0，看不到視窗 |
| `type '<文字>'` | 用 QMP 送出鍵盤輸入，只限 ASCII，美式配置 |
| `keys ret ctrl-a alt-f4` | 送出按鍵，名稱是 QEMU 的 qcode，`-` 表示同時按 |
| `click <x> <y> [left\|right]` | 滑鼠點擊，座標是截圖的像素 |
| `shot <檔名.png>` | 整個畫面的截圖（QMP `screendump`），也可寫 `screen` |
| `snapshot save\|restore\|list [<名稱>]` | qcow2 內部快照加一份 UEFI 變數，預設名稱 `clean`；要先 `stop` |
| `show <名稱> <預覽程式參數…>` | 見下 |
| `status`、`ssh` | 狀態、互動式 shell |

這台是測試機：`firstlogon.ps1` 把系統管理員的 UAC 提示關掉（`ConsentPromptBehaviorAdmin=0`），安裝程式可以無人值守地提高權限；UAC 本身沒關，市集應用程式需要它。裝好後先 `stop`，再 `snapshot save clean`，之後每次測試前 `snapshot restore clean` 就回到乾淨的系統。截圖在 `.build/windows-vm-shots/`：`<比例>-<色調>-<造型>.png` 是游標附近裁下的範圍，`screens/` 是整個畫面。

`show` 的做法：用排程工作在已登入的桌面啟動預覽程式（從 SSH 啟動的程式不能顯示視窗），帶 `--at` 等參數把候選窗固定在一個位置和狀態，再用 QEMU 的 `screendump` 取整個畫面。預覽程式的截圖參數寫在 `Preview/PreviewApp.cpp` 開頭，例如：

```sh
bash scripts/windows-vm.sh show nihao --shape cloud --tone dark --type nihao
bash scripts/windows-vm.sh show list --candidates '喵,貓,ja:ねこ' --preedit mao --expanded
MOUSE=420,330 bash scripts/windows-vm.sh show hover --candidates '喵,貓,毛'   # 先把滑鼠移到該處
```

這台虛擬機能證明的：Windows 的 Direct2D、DirectWrite（微軟正黑體）、WIC 讀 PNG、分層視窗的透明與陰影、DPI 縮放後的排版、滑鼠點擊範圍，在真的 Windows 上的樣子；排版測試在 Windows 上通過。不能證明的：畫面是 QEMU 的 ramfb，Windows 用基本顯示卡驅動，沒有 GPU（候選窗本來就用軟體繪製，影響不大）；x64 程式跑在 Windows 的 x64 模擬下，速度不代表實機；只有一個 1024 × 768 的螢幕，沒有測不同 DPI 的兩個螢幕之間移動，200 % 也放不下；顯示比例是用 `LogPixels` 設的整體比例，不是每個螢幕各自的設定。

## 尚未完成

- 沒有數位簽章。SmartScreen 會先擋，公司管理的電腦可能不允許安裝。
- Arm64 版只支援 Arm64 程式，沒有 x86 DLL；x64 版的 32 位元 DLL 還沒在真的 Windows 上測過（見「安裝與移除」）。
- Ctrl + 空白鍵或程式呼叫 `ImmSetOpenStatus` 關不掉輸入法；中英切換只有單按 Shift 和工作列的「中／英」。
- 切換繁簡後，其他還留著讀音的欄位，候選窗顯示的還是舊的字，回到那個欄位直接送出時，送出的可能和畫面上的不同。
- 詞庫在一個程式裡第一次載入失敗後，那個程式就不再嘗試，喵撲在那裡打不出中文，要重開程式。
- 螢幕閱讀程式讀不到候選。Microsoft〈IME requirements〉的 IME accessibility 要求候選清單有 UI Automation 提供者：`AutomationId` 是 `IME_Candidate_Window`；出現、消失時發 `UIA_MenuOpenedEventId`、`UIA_MenuClosedEventId`；反白改變時在該項目發 `UIA_SelectionItem_ElementSelectedEventId`（`IsSelected` 為 TRUE）；每個項目的 `Name` 是候選字。這些都還沒做，候選窗不回應 `WM_GETOBJECT`。UI-less 的 UI element 是給程式自己畫候選用的，朗讀程式不讀它。
- 用 IMM32 自己畫候選的遊戲（`WM_IME_NOTIFY` 加 `ImmGetCandidateList`）能不能拿到喵撲的候選，還沒驗證。
- 開始功能表搜尋、設定等 UWP 程式裡，候選窗是普通的最上層視窗，可能被蓋住。
- 造型、色系、色調沒有設定的地方。
- 日語混打只用小型的 `native-japanese.tsv`，還沒改讀 macOS、iOS 用的 `mimo_jp.bin`；也沒有個人詞庫、macOS 的 Swift 排序和 `/` 片語模組。
- `CMakeLists.txt` 的 MSVC 建置（含 `MeowpuutTextService`）沒有實際跑過。
- 虛擬機上看到：切到喵撲注音後，工作列的「中／英」指示有時不見，只剩鍵盤圖示；拼音時正常。
- 點到候選窗玻璃和貓以外的地方（透明的陰影區）時，點擊落到下面的文件，程式會結束組字，讀音照原樣（字母或注音符號）留在文件裡。
- 有程式還載著 DLL 時，移除後 `C:\Program Files\Meowpuut` 的資料夾要等那些程式關掉或重新開機才能刪。
- 安裝和移除時 `Set-WinUserLanguageList` 會印一行英文警告（顯示語言改變要重新登入），可以忽略。
- 貓窩：只交叉編譯過、在 macOS 上跑過可攜的部分和 smoke，**還沒在真的 Windows 上執行過**（要測的清單見下）。沒有：躲貓貓時點邊緣找到貓（`MP_DESK_EV_SEAT_TAP`，要點到別的程式的視窗，不裝滑鼠鉤子做不到）、窗臺、虛擬桌面切換時把貓帶過去（`IVirtualDesktopManager`）、開始功能表或搜尋開著時淡出、分享螢幕時隱藏（`WDA_EXCLUDEFROMCAPTURE`）、大中小三檔、增加對比的外框、節日坐姿、第一次能點時的提示、米米莫的起跳圖（起跳時仍是 `idle`）。合唱和找到貓（`DUET`、`FOUND`）還沒記進打字紀錄。貓窩只在開啟時和自己寫檔之後 `mp_desk_adopt`，輸入法同一天稍後才打的招呼，要等貓窩下次寫檔才知道。候選窗只在更新時讀座位：停手 8 秒後莫莫落回桌面，組字還開著時候選窗裡的莫莫要到下一個鍵才不見（中間會有兩隻莫莫）。多個使用者同時登入時，系統管理員安裝新版會關掉其他使用者從舊資料夾執行的貓窩，他們要重新登入。`Install.ps1` 結束貓窩用的是強制結束，系統匣圖示要等游標移過去才消失，最後幾分鐘的摸摸和時間紀錄也來不及寫。
- 時間花在哪裡：陪伴 App 的「時間花在哪裡」頁、Steam 分享模式（讀共享記憶體的「現在」一欄）、「顯示 App 名稱」和「顯示正在聽的歌」兩個開關、「只留近 90 天」（`mp_activity_set_keep_days`）、「清除貓咪紀錄」時一併清空 `Activity.bin` 都還沒做；現在只能關掉開關，或用 `Install.ps1 -Uninstall`、手動刪檔。第一次開啟時的引導也還沒問這一題（預設關）。
- 貓窩要在真的 Windows 上測：`--smoke`（x64、Arm64）；私有命名空間與強制標籤在一般、系統管理員（高完整性）、低完整性程式和 AppContainer（Edge 分頁、商店 App）裡的行為；x86 程式送的脈衝；100–250 % 與混合 DPI、多螢幕、工作列在上／左／右／自動隱藏、副螢幕的工作列；`WS_EX_TRANSPARENT` 切換後是否立刻能點、陰影是否真的點不到；拖曳、丟出去、雙擊、右鍵選單、系統匣（`Shell_NotifyIconGetRect`）、快捷鍵；無邊框全螢幕遊戲、獨佔全螢幕、影片全螢幕、簡報模式、Alt+Enter；工作列被點後貓是否還在最上層；置頂互搶（錄影、聊天覆蓋層）；虛擬桌面；登入時自動開啟、`Install.ps1` 的安裝、更新、`-Steam`、`-Uninstall`（連 `Activity.bin` 一起刪）；睡眠與喚醒；記憶體（≤ 35 MB）與閒置 CPU（你不在時 0）。黏土走路：一鍵一格、往左走時鏡像、走到後轉身、拎起時掛在後頸而且腳不被裁掉（不同 DPI 的副螢幕也一樣）、掉落圖、莫莫睡覺圖。候選窗：米米莫沿視窗上緣陪走時玻璃上只坐莫莫、甲或叫回家時兩隻、全螢幕時兩隻、結束貓窩和用工作管理員強制結束貓窩後兩隻都回來（後者最多 3 秒）；貓窩剛啟動時輸入法正在打字，貓窩不會誤以為已經有一個在跑。摸摸、呼嚕、掉落有沒有寫進 `PetJournal.bin`（陪伴 App 或 `mp_pets_progress`）。「記錄時間花在哪裡」：選單的說明與開關、暫停、`ActivityHidden`、關著時前景切換不查程式（Process Monitor 看不到 `OpenProcess`）、記事本／VS Code／瀏覽器的分類、UWP（計算機、設定、商店）、系統管理員身分的程式、`steamapps\common` 下的遊戲、讀不到的受保護程式、鎖定畫面、睡眠喚醒後不重複計時、全螢幕遊戲中每 60 秒的計時器沒有影響、`Activity.bin` 裡找不到 App 名稱、離開後 30 秒的檢查、`ActivityShareAppName` 為 1 時「現在」一欄有 App 顯示名稱（一般程式、UWP、沒有版本資源的程式）而 0 時沒有、隱藏的 App 沒有。`--smoke` 在 Windows 上：第二個貓窩（另一個執行緒）被擋下、座位與「現在」一欄的來回。

可參閱 Microsoft 的 [TSF 文件](https://learn.microsoft.com/en-us/windows/win32/tsf/text-services-framework)、[composition](https://learn.microsoft.com/en-us/windows/win32/tsf/compositions) 和 [文字服務註冊](https://learn.microsoft.com/en-us/windows/win32/tsf/text-service-registration)。
