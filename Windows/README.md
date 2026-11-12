# Windows 開發基礎

目前有 C++ 會話層、候選窗和一個預覽程式，**還不是可安裝或註冊的 Windows 輸入法**。每個輸入上下文應獨立持有一個 `meowpuut::Session`，並只在所屬執行緒使用。字典以 `std::filesystem::path` 讀取；Windows 呼叫端可以傳 UTF-16 路徑。快照複製 UTF-8 組字、候選與上屏字串；`takeSnapshot()` 讀取後清除待提交文字，避免重複上屏。

`Keyboard.h` 提供鍵盤事件轉換：拼音數字選詞、Shift 加大千聲調鍵輸入拼音聲調，拼音組字時空格保留原文並加入空格；注音的數字留給聲調而 Shift 加數字選詞。翻頁與編輯鍵在組字時交給會話。呼叫端需傳入當次事件的虛擬鍵、鍵盤配置產生的 ASCII 字元及修飾鍵狀態，忽略 key-up 和死鍵，並自行處理單按 Shift 切換中英。Ctrl、Alt、Windows 組合鍵保留給系統或應用程式。這個轉換層不會安裝鍵盤攔截器或註冊 TSF。

拼音會話可在輸入前呼叫 `registerJapaneseWords(path)`，從生成的 mimo 詞庫目錄載入 `native-japanese.tsv`；它是審過的小型日語詞表（`Resources/Lexicon/japanese.tsv` 與 `japanese-reviewed.tsv`），格式為 `讀音|詞|權重`；macOS、iOS 改讀約 46,000 詞的 `mimo_jp.bin`，Windows 尚未改。未呼叫時保持純中文核心行為。混合中文與日語的完整句和前半段透過跨平台 C 核心產生，選取前半段後會保留未轉換的輸入。快照的 `japaneseCandidates` 與候選一一對應：每頁最多一個中日混合候選，排在該頁中文之後；額外候選放下一頁。第一頁可能有七個中文及一個額外的中日候選，拼音可用數字 1–8 選取。候選窗按快照的實際數量顯示，日語候選後面加貓爪記號。

生成詞庫後，可執行 `python3 scripts/export-windows-lexicon.py <生成詞庫目錄> <新的輸出目錄>` 匯出 Windows 使用的三份資料表與 `Licenses` 聲明目錄。日後發佈 Windows 二進位時須一併附上該目錄；目前這裡仍是可攜式會話層，尚未提供 Windows 安裝包。

同一次組字中，選擇中文前段後，會話會暫存短期上下文以協助後段排序；整句完成、取消、刪除至空或清除時會移除。選中含日語的混合前段會作為語境邊界，清除之前的中文上下文，並保留尚未轉換的讀音。上下文只存在於各自的會話，不寫入個人詞庫。

單次組字達到核心容量時，下一個有效按鍵會先將原始讀音加入待提交文字，再開始新的組字；注音保留顯示的注音符號，拼音保留原文。待提交內容必須由呼叫端透過 `takeSnapshot()` 依序上屏；連續按鍵之間未取快照也不會覆蓋前一次結果。

TSF 的 [`OnTestKeyDown`](https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfkeyeventsink-ontestkeydown) 是按鍵意向查詢；`canHandleKey` 不改變組字、候選或待提交文字，可用於此查詢。實際處理另調用 `handleKey`。兩者之間應使用同一輸入上下文的會話與模式；模式必須與會話載入的詞庫一致。核心按鍵接受規則有變動時，要同步更新查詢並執行回歸：測試逐一比對拼音、注音在空輸入和組字狀態下所有 ASCII 可列印鍵的查詢結果與核心行為。這是 TSF 接入準備，並非已實作 TSF 文字服務。

## 在 Windows 上建置

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
2. `session_test`：用本機的 Rust 函式庫和 `iOS/Generated` 的詞庫（可用 `MEOWPUUT_LEXICON_DIR` 改）。沒有詞庫時跳過。
3. 快照：用 Core Text 量字，輸出各造型、色調和狀態的 SVG，再用 `rsvg-convert` 轉成 PNG，放在 `previews/`。檔名帶 `-on-page` 的是疊在一頁文字上的樣子。
4. 交叉編譯：Rust 核心、候選窗、預覽程式和排版測試，警告視為錯誤。結果在 `mingw/`。

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
3. 第一次開機時 Windows 依 `unattend.xml` 完成設定，建立本機帳號並登入，`firstlogon.ps1` 安裝 OpenSSH 伺服器、關掉睡眠和自動更新。等到 SSH 能連、桌面出現就完成。

日常使用：

```sh
MEOWPUUT_LEXICON_DIR=iOS/Generated bash scripts/build-windows-check.sh
bash scripts/windows-vm.sh start                 # 開機，等到桌面出現
bash scripts/windows-vm.sh deploy                # 複製 mingw/ 的 exe、Assets 和詞庫到 C:\meow\preview
bash scripts/windows-vm.sh run 'C:\meow\preview\meowpuut_layout_test.exe'
bash scripts/windows-vm.sh shots                 # 三種造型 × 淺深色 × 收合／展開
bash scripts/windows-vm.sh scale 200             # 換顯示比例，Windows 會重新開機
bash scripts/windows-vm.sh shots
bash scripts/windows-vm.sh stop
```

其他指令：`copy <資料夾> [<Windows 路徑>]`、`show <名稱> <預覽程式參數…>`、`screen <檔名.png>`、`status`、`ssh`。`run` 在 cmd.exe 執行，結束碼原樣傳回。截圖在 `.build/windows-vm-shots/`：`<比例>-<色調>-<造型>.png` 是游標附近裁下的範圍，`screens/` 是整個畫面。

`show` 的做法：用排程工作在已登入的桌面啟動預覽程式（從 SSH 啟動的程式不能顯示視窗），帶 `--at` 等參數把候選窗固定在一個位置和狀態，再用 QEMU 的 `screendump` 取整個畫面。預覽程式的截圖參數寫在 `Preview/PreviewApp.cpp` 開頭，例如：

```sh
bash scripts/windows-vm.sh show nihao --shape cloud --tone dark --type nihao
bash scripts/windows-vm.sh show list --candidates '喵,貓,ja:ねこ' --preedit mao --expanded
MOUSE=420,330 bash scripts/windows-vm.sh show hover --candidates '喵,貓,毛'   # 先把滑鼠移到該處
```

這台虛擬機能證明的：Windows 的 Direct2D、DirectWrite（微軟正黑體）、WIC 讀 PNG、分層視窗的透明與陰影、DPI 縮放後的排版，在真的 Windows 上畫出來的樣子；排版測試在 Windows 上通過。不能證明的：畫面是 QEMU 的 ramfb，Windows 用基本顯示卡驅動，沒有 GPU（候選窗本來就用軟體繪製，影響不大）；x64 程式跑在模擬下，速度不代表實機；只有一個螢幕，沒有測不同 DPI 的兩個螢幕之間移動；也沒有測輸入法本身，因為還沒有 TSF 文字服務。

## 尚未完成

這裡還沒有 TSF 文字服務，也沒有安裝包。接 TSF 還需要：

- COM 伺服器 DLL：`DllGetClassObject`、`DllCanUnloadNow`、`DllRegisterServer`／`DllUnregisterServer`，以及類別工廠。
- 註冊：用 `ITfInputProcessorProfileMgr` 註冊語言設定檔（zh-TW、zh-CN），用 `ITfCategoryMgr` 註冊鍵盤類別；要在 UWP 和開始功能表中使用，還要註冊 immersive 和 systray 支援的類別。
- `ITfTextInputProcessorEx`：`ActivateEx`／`Deactivate`，每個執行緒一個實例，在這裡建立和銷毀候選窗。
- `ITfKeyEventSink`：`OnTestKeyDown` 用 `canHandleKey`，`OnKeyDown` 用 `handleKey`。
- 組字：在 `ITfEditSession` 內用 `ITfContextComposition::StartComposition` 開始，寫入 preedit，上屏時結束；實作 `ITfCompositionSink` 處理應用程式中止組字。UTF-8 與 UTF-16 的轉換在這一層做。
- 顯示屬性：`ITfDisplayAttributeProvider`，讓組字有底線。
- 候選窗位置：`ITfContextView::GetTextExt` 取得組字範圍的螢幕矩形，傳給 `showNear`。
- `ITfCandidateListUIElement` 和 `ITfUIElementMgr`：讓螢幕閱讀程式取得候選，並支援遊戲等不顯示輸入法介面的程式。
- 輸入模式（中／英）、拼音／注音、繁簡、造型和色調的切換與儲存：compartment、語言列或系統匣圖示，以及設定的存放位置。
- 每個輸入上下文一個 `Session`；焦點切換（`ITfThreadMgrEventSink`）時結束組字並隱藏候選窗。

會話層還沒有接入 macOS 的 Swift 排序、個人詞庫、完整日語候選、中文標點和全部快捷鍵邏輯。可參閱 Microsoft 的 [TSF 文件](https://learn.microsoft.com/en-us/windows/win32/tsf/text-services-framework)、[composition](https://learn.microsoft.com/en-us/windows/win32/tsf/compositions) 和 [文字服務註冊](https://learn.microsoft.com/en-us/windows/win32/tsf/text-service-registration)。

驗證狀況：會話層和候選窗排版在 macOS 的 Clang 上編譯並測試；候選窗和預覽程式以 llvm-mingw 交叉編譯通過，沒有警告。候選窗和預覽程式還沒有在 Windows 上執行過，也沒有用 MSVC 編譯過。
