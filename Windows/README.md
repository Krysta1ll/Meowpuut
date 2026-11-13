# Windows 輸入法

從 0.8.0-alpha.1 起，Windows 又有可以安裝的喵撲。`TextService/` 是 TSF 文字服務 `MeowpuutTextService.dll`，在「中文（台灣）」底下提供「喵撲拼音」和「喵撲注音」兩個鍵盤。它用這個資料夾的會話層（`Session`、`Keyboard`）、Rust 核心（`meowpuut-ffi`）和 `UI/` 的候選窗。發佈包在 macOS 上交叉編譯，分 x64 和 Arm64 兩份，沒有數位簽章。

## 安裝與移除

1. 下載 `Meowpuut-0.8.0-alpha.1-windows-x64.zip`（Arm64 的電腦下載 `-windows-arm64.zip`），整個解壓。
2. 雙擊 `Install.cmd`。Windows 會要求系統管理員權限。檔案複製到 `C:\Program Files\Meowpuut\<DLL 雜湊的前 12 碼>`，用 `regsvr32` 註冊，再把兩個鍵盤加進目前使用者的「中文（台灣）」。
3. 按 Windows 鍵 + 空白鍵切到「喵撲拼音」或「喵撲注音」。已經開著的程式要重新開啟才會載入。

移除：雙擊同一個資料夾裡的 `Uninstall.cmd`。它把兩個鍵盤從使用者的語言清單拿掉、取消註冊、刪除 `C:\Program Files\Meowpuut` 和 `HKCU\Software\Meowpuut`。還有程式載著 DLL 時那個資料夾刪不掉，腳本會印出路徑；關掉那些程式或重新開機後再手動刪。

裝新版會放進新的資料夾，正在執行的程式繼續用舊的 DLL，舊資料夾在下次安裝時刪掉。0.1.x 的類別和拼音設定檔用同一組 GUID，裝 0.8.0 會直接取代，也會刪掉 0.1.x 登入時啟動玻璃預覽程式的捷徑。

套件的 DLL 必須和 Windows 相同架構，不同時 `Install.cmd` 會停下來，說該下載哪一份。Arm64 版是原生 Arm64 DLL，只會載入 Arm64 程式（記事本、Edge、檔案總管、Windows 終端機等內建程式都是）。用 x64 模擬執行的程式載入不了，在那些程式裡切到喵撲，按鍵會直接出英文字母。要兩種程式都能用得做 Arm64X DLL；Rust 核心目前沒有能交叉編譯的 Arm64EC 目標，所以還沒做。x64 版沒有 32 位元 DLL，32 位元程式也用不了。

## 打字

按鍵和 macOS 相同，不同的地方是單按 Shift 和方向鍵：

| 按鍵 | 作用 |
| --- | --- |
| 數字（拼音 `1`–`8`；注音 Shift + `1`–`7`） | 選這一頁的候選 |
| 空白鍵 | 拼音：選反白的候選。注音：第一聲；已經打了聲調時選反白的候選，並送出整段 |
| Enter | 選反白的候選；沒有候選時送出讀音原文 |
| Shift + Enter | 送出打的字母原文 |
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

工作列的輸入指示顯示「中」或「英」，點一下切換；右鍵選單可選繁體或簡體。繁簡存在 `HKCU\Software\Meowpuut` 的 `Simplified`，每個程式開始新的一段讀音時讀一次，所以在一個程式切換，其他程式的下一段也會跟著換。造型、色系和色調在 Windows 上還沒有設定的地方，固定是膠囊、玫瑰、跟隨系統的淺深色；節日裝扮照日曆自動換（規則同其他平台，見 `UI/Festival.h`）。

讀音是 TSF 組字，也就是文件裡有底線的文字，候選窗在它下方。焦點換到別的欄位時，讀音留在原來的欄位，回去再打字會接著用；程式自己結束組字（例如點到別處）時，讀音照原樣留在文件裡。切換鍵盤、切到英文、拼音注音互換時，先送出反白的候選。

上屏的文字先放在那個欄位自己的佇列裡，再由編輯工作階段寫入文件。程式拒絕寫入時，文字留在同一個欄位的佇列，下一次寫入時一起送出，不會跑到別的欄位。

## 在 Windows 上檢查過的

2026-11-12 在 Mac 上的 Windows 11 Arm64 虛擬機（zh-TW，`scripts/windows-vm.sh`，見下面）從乾淨的快照開始，用 Arm64 的 zip 跑過：

- `Install.cmd`：註冊、兩個鍵盤加進「中文（台灣）」；Windows 鍵 + 空白鍵切到喵撲拼音、喵撲注音。
- 記事本（Arm64 原生）：`nihao` 有底線的讀音和候選窗（兩隻貓在玻璃上）；按 `1`、空白鍵、→ 加 Enter、滑鼠點第二個候選都送出對的字；↓ 展開面板、↑ 收起；Page Down 翻頁；`,` 打出「，」；Shift + Enter 送出字母原文；組字中按 Ctrl + End 先送出候選再移動游標。
- 單按 Shift 切到英文（工作列顯示「英」），再按一次回中文；按住 Shift 用滑鼠選字不會切換。
- 喵撲注音：`su3cl3` 顯示 ㄋㄧˇㄏㄠˇ，Shift + 1 送出「你好」；`ji3` 加空白鍵送出「我」。
- 連點莫莫五下：拼音 `lanse` 的候選從「藍色 藍 爛…」變成「蓝色 蓝 烂…」，再五下變回來。
- Edge 的網址列：拼音組字、空白鍵和數字選字都正常，候選窗在文字下方。「執行」對話框（檔案總管）裡也能打字。
- `Uninstall.cmd`：兩個鍵盤從清單拿掉，CLSID 和 TIP 的登錄機碼都刪掉；檔案總管還載著 DLL，所以資料夾留下，腳本印出路徑。
- x64 的 zip 在這台 Arm64 上安裝時停下來，說要下載 `windows-arm64`。

沒有在 Windows 上跑過的：x64 的 DLL（沒有 x64 的 Windows 可測）、Windows 10、高 DPI 和多螢幕、UWP 程式與開始功能表搜尋、Word 等其他程式、長時間使用。

在 macOS 上：`scripts/build-windows-check.sh` 全部通過（排版測試、`session_test` 含 `mimo_base.bin` 共用詞庫、SVG 快照、MinGW GCC 交叉編譯 x64 DLL）；llvm-mingw 20261020 交叉編譯 x64 和 Arm64 DLL，沒有警告，只匯入 Windows 內建的 DLL。

## 會話層

每個輸入上下文應獨立持有一個 `meowpuut::Session`，並只在所屬執行緒使用。字典以 `std::filesystem::path` 讀取；Windows 呼叫端可以傳 UTF-16 路徑。文字服務用 `Session(SharedLexicon, bool pinyin)`：`mimo_base.bin` 和 `mimo_pairs.bin` 以檔案映射直接讀取，每個行程只載入一次，所有會話共用，各個程式也共用同一份檔案分頁。快照複製 UTF-8 組字、候選與上屏字串；`takeSnapshot()` 讀取後清除待提交文字，避免重複上屏。`commit()` 送出第一個候選，沒有候選時送出原本的讀音；文字服務用它把整段讀音送完。

`Keyboard.h` 提供鍵盤事件轉換：拼音數字選詞、Shift 加大千聲調鍵輸入拼音聲調，拼音組字時空格保留原文並加入空格；注音的數字留給聲調而 Shift 加數字選詞。翻頁與編輯鍵在組字時交給會話。呼叫端需傳入當次事件的虛擬鍵、鍵盤配置產生的 ASCII 字元及修飾鍵狀態，忽略 key-up 和死鍵，並自行處理單按 Shift 切換中英。文字服務在這層之前先處理空白鍵、Enter、方向鍵和標點（見上面的「打字」）。Ctrl、Alt、Windows 組合鍵保留給系統或應用程式。這個轉換層不會安裝鍵盤攔截器或註冊 TSF。

拼音會話可在輸入前呼叫 `registerJapaneseWords(path)`，從生成的 mimo 詞庫目錄載入 `native-japanese.tsv`；它是審過的小型日語詞表（`Resources/Lexicon/japanese.tsv` 與 `japanese-reviewed.tsv`），格式為 `讀音|詞|權重`；macOS、iOS 改讀約 46,000 詞的 `mimo_jp.bin`，Windows 尚未改。未呼叫時保持純中文核心行為。混合中文與日語的完整句和前半段透過跨平台 C 核心產生，選取前半段後會保留未轉換的輸入。快照的 `japaneseCandidates` 與候選一一對應：每頁最多一個中日混合候選，排在該頁中文之後；額外候選放下一頁。第一頁可能有七個中文及一個額外的中日候選，拼音可用數字 1–8 選取。候選窗按快照的實際數量顯示，日語候選後面加貓爪記號。

生成詞庫後，可執行 `python3 scripts/export-windows-lexicon.py <生成詞庫目錄> <新的輸出目錄>` 匯出 Windows 使用的三份資料表與 `Licenses` 聲明目錄。發佈 Windows 二進位時須一併附上該目錄；`scripts/package-windows.sh` 從匯出結果取文字服務需要的三個檔案和 `Licenses`。

同一次組字中，選擇中文前段後，會話會暫存短期上下文以協助後段排序；整句完成、取消、刪除至空或清除時會移除。選中含日語的混合前段會作為語境邊界，清除之前的中文上下文，並保留尚未轉換的讀音。上下文只存在於各自的會話，不寫入個人詞庫。

單次組字達到核心容量時，下一個有效按鍵會先將原始讀音加入待提交文字，再開始新的組字；注音保留顯示的注音符號，拼音保留原文。待提交內容必須由呼叫端透過 `takeSnapshot()` 依序上屏；連續按鍵之間未取快照也不會覆蓋前一次結果。

TSF 的 [`OnTestKeyDown`](https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfkeyeventsink-ontestkeydown) 是按鍵意向查詢；`canHandleKey` 不改變組字、候選或待提交文字，可用於此查詢。實際處理另調用 `handleKey`。兩者之間應使用同一輸入上下文的會話與模式；模式必須與會話載入的詞庫一致。核心按鍵接受規則有變動時，要同步更新查詢並執行回歸：測試逐一比對拼音、注音在空輸入和組字狀態下所有 ASCII 可列印鍵的查詢結果與核心行為。`TextService/` 照這個規則接 TSF。

## 文字服務

`TextService/`：

- `TextService.cpp`：COM 伺服器（`DllGetClassObject`、`DllRegisterServer` 等）和 TSF 文字服務。TSF 為每個有鍵盤輸入的執行緒建立一個 `Service`；每個收到按鍵的 `ITfContext` 有自己的 `ContextState`（會話、組字、待上屏佇列、反白、面板展開）。`OnTestKeyDown` 和 `OnKeyDown` 用同一個沒有副作用的 `plan()` 決定要不要吃下按鍵，再由 `perform()` 執行。文字一律在 `TF_ES_ASYNCDONTCARE | TF_ES_READWRITE` 的編輯工作階段裡寫入，寫入時讀當下的狀態，所以排隊中的工作階段不會寫進舊內容。候選窗的位置來自 `ITfContextView::GetTextExt`，用 `LogicalToPhysicalPointForPerMonitorDPI` 換成實體像素；拿不到時（`TS_E_NOLAYOUT`）先用系統游標，等程式透過 `ITfTextLayoutSink` 通知版面變了再重讀一次。
- 註冊：`ITfInputProcessorProfileMgr::RegisterProfile` 在 zh-TW（0x0404）下註冊兩個設定檔，`ITfCategoryMgr` 註冊鍵盤、顯示屬性、immersive 和 systray 類別。類別 GUID `{961C0ADA-4DC6-488F-9563-4FC144EFAE27}`、拼音設定檔 `{8FDE097D-3513-486A-9AB5-2D1B2C67C359}` 沿用 0.1.x；注音設定檔是 `{1F5D9D62-F4AF-4AAF-82A6-A482D7EC3AE2}`。
- `TsfDeclarations.h`：MinGW 的 `msctf.h` 缺的介面（`ITfTextInputProcessorEx`、`ITfDisplayAttributeProvider`、`ITfLangBarItemButton`、`ITfMenu`）和幾個 GUID。
- `Install.ps1`、`Install.cmd`、`Uninstall.cmd`：安裝與移除，見上面。
- `TextService.def`、`TextService.rc`：匯出表、版本資訊和圖示（建置時由 `assets/brand/AppIcon.png` 產生 `Meowpuut.ico`）。

0.1.1 的文字服務（commit 5e33638）沒有用 TSF 組字，讀音只顯示在另外啟動的 C# 玻璃預覽程式裡。這次留下的是：上屏佇列跟著欄位走、程式拒絕時保留不丟（0.1.1 的 `PendingDelivery`）、每個欄位各自保留讀音、密碼框和唯讀欄位不接手、`OnTestKeyDown` 不改變狀態。其餘改寫：組字、顯示屬性、`UI/` 的候選窗、注音、工作列的中／英指示、詞庫改用 `mimo_base.bin`。

## 建置

### 在 macOS 上交叉編譯

```sh
# 一個架構的 DLL，輸出到 .build/windows-ime/<x64|arm64>/
MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/build-windows-ime.sh x64
MINGW_BIN=/path/to/llvm-mingw/bin bash scripts/build-windows-ime.sh arm64

# 發佈包：兩個架構的 zip 和 SHA256SUMS.txt，輸出到 .build/windows-package/
MINGW_BIN=/path/to/llvm-mingw/bin MEOWPUUT_LEXICON_DIR=/path/to/generated/lexicon \
  bash scripts/package-windows.sh
```

需要 [llvm-mingw](https://github.com/mstorsjo/llvm-mingw)（0.8.0-alpha.1 用 20261020 的 UCRT macOS 版）、Rust 的 `x86_64-pc-windows-gnullvm` 和 `aarch64-pc-windows-gnullvm` 目標（缺少時腳本執行 `rustup target add`），以及 Python 的 Pillow（做圖示）。x64 也可以用 Homebrew 的 mingw-w64 GCC，這時 Rust 目標是 `x86_64-pc-windows-gnu`。DLL 靜態連結 C++ 執行庫和 Rust 核心，只依賴 Windows 內建的 DLL（UCRT、d2d1、DWrite、ole32 等）。

發佈包的內容：`MeowpuutTextService.dll`、`Assets/`（貓和節日的圖，保留所有權利）、`lexicon/`（`mimo_base.bin`、`mimo_pairs.bin`、`native-japanese.tsv`）、`Licenses/`（`export-windows-lexicon.py` 寫的聲明，含 `Meowpuut-MIT.txt` 和 `Meowpuut-Artwork.txt`，加上 LLVM、MinGW-w64 和 Rust 標準庫的授權）、`THIRD_PARTY.md`、安裝腳本，以及 `BUILD-INFO.md`（commit、編譯器版本、每個檔案的 SHA-256）。

### 在 Windows 上用 MSVC

需要 CMake 3.16 以上、Visual Studio 的 C++ 工具（MSVC）、Windows 10 SDK 和 Rust（`cargo` 在 PATH 中，MSVC 目標）。CMake 會先呼叫 `cargo build --release -p meowpuut-ffi`。這份 CMake 只支援 MSVC，用其他編譯器設定時會直接報錯；MinGW 請用下面的 `scripts/build-windows-check.sh`。

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
| `meowpuut_session_test`、`meowpuut_layout_test` | 測試 |

`MEOWPUUT_LEXICON_DIR` 須包含 `native-pinyin.tsv`、`native-zhuyin.tsv` 和 `native-japanese.tsv`，由 `scripts/export-windows-lexicon.py` 匯出。未指定時不註冊 `session` 測試，`candidate_layout` 測試照常執行。這裡沒有 Windows 原生的詞庫編譯流程，請先在已支援的環境產生檔案。

## 候選窗

`UI/` 是 Windows 的候選窗，依 [docs/DESIGN.md](../docs/DESIGN.md) 繪製：雙貓玻璃、三種造型（膠囊、波浪、雲朵）、淺色與深色。

檔案分兩層：

- `Geometry.h`、`GlassShape`、`CandidateLayout`、`Theme`、`Scene`、`Blur`、`Festival`、`SvgSnapshot` 是不含 Windows 標頭的 C++17。`CandidateLayout` 算出每個候選的位置和點擊範圍，`Scene` 把排版和配色轉成一串繪圖指令（填色、描邊、裁切、圖片、文字）。Windows 視窗和 SVG 快照重播同一串指令，所以快照的形狀、位置和顏色與 Windows 相同。字型不同（快照用 PingFang TC，Windows 用微軟正黑體），字寬和省略位置會有出入。
- `CandidateWindow` 是 Win32 視窗，用 Direct2D 重播 `Scene`，文字用 DirectWrite，貓的 PNG 用 WIC 讀取。

視窗的行為：

- 分層視窗（`WS_EX_LAYERED`），畫在 32 位元 DIB 上，再用 `UpdateLayeredWindow` 以預乘 alpha 顯示。Direct2D 用軟體繪製（`ID2D1DCRenderTarget`）：輸入法會載入每個應用程式，不在每個行程裡建立 Direct3D 裝置。繪製目標遺失（`D2DERR_RECREATE_TARGET`）時丟掉點陣圖和筆刷，重建後重畫一次。
- 不搶焦點：`WS_EX_NOACTIVATE`，`WM_MOUSEACTIVATE` 回傳 `MA_NOACTIVATE`。點候選不會讓輸入框失去焦點。
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

### 預覽程式

`Preview/PreviewApp.cpp` 是一個編輯框，經由 `Session` 和 `Keyboard.h` 打字，在組字下方顯示候選窗。組字以普通文字暫放在編輯框中，這不是文字服務。選單可切換拼音／注音、繁簡、造型、色調和節日裝扮；單按 Shift 切換中英；方向鍵或 Tab 移動選中候選，Enter 送出選中的候選；連點莫莫五下切換繁簡，組字保留。

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
- Arm64 版只支援 Arm64 程式，x64 版沒有 32 位元 DLL（見「安裝與移除」）。
- Ctrl + 空白鍵或程式呼叫 `ImmSetOpenStatus` 關不掉輸入法；中英切換只有單按 Shift 和工作列的「中／英」。
- 切換繁簡後，其他還留著讀音的欄位，候選窗顯示的還是舊的字，回到那個欄位直接送出時，送出的可能和畫面上的不同。
- 詞庫在一個程式裡第一次載入失敗後，那個程式就不再嘗試，喵撲在那裡打不出中文，要重開程式。- 沒有 `ITfCandidateListUIElement` 和 `ITfUIElementMgr`：螢幕閱讀程式讀不到候選，全螢幕遊戲等不顯示輸入法介面的程式看不到候選窗。
- 開始功能表搜尋、設定等 UWP 程式裡，候選窗是普通的最上層視窗，可能被蓋住。
- 造型、色系、色調沒有設定的地方。
- 日語混打只用小型的 `native-japanese.tsv`，還沒改讀 macOS、iOS 用的 `mimo_jp.bin`；也沒有個人詞庫、macOS 的 Swift 排序和 `/` 片語模組。
- `CMakeLists.txt` 的 MSVC 建置（含 `MeowpuutTextService`）沒有實際跑過。
- 虛擬機上看到：切到喵撲注音後，工作列的「中／英」指示有時不見，只剩鍵盤圖示；拼音時正常。
- 點到候選窗玻璃和貓以外的地方（透明的陰影區）時，點擊落到下面的文件，程式會結束組字，讀音照原樣（字母或注音符號）留在文件裡。
- 有程式還載著 DLL 時，移除後 `C:\Program Files\Meowpuut` 的資料夾要等那些程式關掉或重新開機才能刪。
- 安裝和移除時 `Set-WinUserLanguageList` 會印一行英文警告（顯示語言改變要重新登入），可以忽略。

可參閱 Microsoft 的 [TSF 文件](https://learn.microsoft.com/en-us/windows/win32/tsf/text-services-framework)、[composition](https://learn.microsoft.com/en-us/windows/win32/tsf/compositions) 和 [文字服務註冊](https://learn.microsoft.com/en-us/windows/win32/tsf/text-service-registration)。
