# Meowpuut Windows x64 Alpha

## 直接下載安裝

從 [GitHub Releases](https://github.com/Krysta1ll/Meowpuut/releases/tag/windows-v0.1.1-alpha) 下載 Windows x64 ZIP，完整解壓後雙擊 Install.cmd。Windows 會要求管理員權限，以註冊系統輸入法。安裝後按 Win + 空白鍵，從繁體中文切換到 Meowpuut Pinyin。

此 Alpha 發佈包未簽署，Windows 應用程式控制政策可能阻止安裝或啟動玻璃渲染器。0.1.1 以拼音為主，注音尚未接入。安裝、焦點切換及上屏保護均未在 Windows 實測；應用關閉、宿主拒絕輸入或服務停止時，未提交的文字仍可能遺失。套件內的 BUILD-INFO.md 列出建置來源與驗證範圍。

## 雙貓玻璃候選窗預覽

在倉庫根目錄用 PowerShell 建置並執行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Windows\build-preview.ps1
& .\.build\windows\MeowpuutGlassPreview.exe
```

預覽使用 Windows 內建的 .NET Framework 與 WPF。雙貓輪廓以 macOS 深色候選窗為基準，並沿用 iOS 的雙貓造型。玻璃會即時取樣候選窗附近的桌面，在內部柔化背景，沿貓耳、弧角和尾部輪廓的法線偏移背景，折射由邊緣向內逐漸消退。輪廓反光和內側細光帶疊在暖色玻璃之上。取樣畫面僅在記憶體內處理。Windows 10 2004 起支援將預覽排除在螢幕擷取外，以避免重複取樣自身，因此部分截圖或錄影工具可能看不到預覽窗。無法取樣時回退為半透明玻璃，高對比模式下候選區使用系統色。

可點選候選，或按數字鍵 1–5、7 與左右方向鍵切換高亮。按 Esc 關閉，也可拖曳玻璃區移動視窗。預覽使用固定候選詞。

## Windows 系統輸入法（x64 Alpha）

在 Windows 10／11 x64 使用 Python 3 和 LLVM-MinGW 的 x64 編譯器建置。預設建置產物未簽署。安裝時 Windows 會要求以系統管理員權限將輸入服務安裝至 Program Files 並註冊 TSF。安裝程式會在目前登入工作階段啟用輸入法。

```powershell
.\Windows\build-ime.ps1 -Compiler C:\path\to\x86_64-w64-mingw32-clang++.exe
.\Windows\install-ime.ps1
```

在應用程式的文字區按 Win + 空白鍵，從繁體中文切換到 **Meowpuut Pinyin**。數字 1–7 直接選詞；方向鍵和 Tab 切換高亮，Enter 確認，空白鍵輸出原始拼音加空白。Page Up／Page Down 翻頁，Esc 取消，Backspace 修改。

可混打詞庫收錄的日語羅馬字，例如 `wosukini` 的候選包含「我好き你」。同頁有日語候選時，收合版保留其中一條，依原候選順序顯示。按 F4 展開查看同頁其他候選。低置信中文和其餘日語候選交替排列，貓爪只標記含日語的候選。選取帶貓爪的整句會直接上屏；選取局部中文詞會保留後續組字。
此版本使用喵撲的 C++ 中文引擎與實際詞庫，以 TSF 將文字送入應用程式。系統輸入法會啟動獨立的雙貓玻璃渲染器，顯示即時拼音與候選詞。支援 TSF 文字座標的應用程式會把候選窗貼近插入點；其餘應用程式沿用 Windows 提供的游標位置。渲染器會隨使用者登入啟動。若 Windows 應用程式控制政策阻止執行渲染器，輸入服務會使用原生候選窗。兩種候選窗都可用鍵盤選詞。已開啟的應用程式需重新啟動才能載入新版輸入服務。此建置適用於 x64 桌面應用程式。

### 安裝已簽署的版本

先建置輸入服務及玻璃渲染器，再以受信任的 Authenticode 發行者憑證簽署 `.build/windows/ime/MeowpuutTip.dll` 和 `.build/windows/MeowpuutGlassPreview.exe`。執行 `.\Windows\install-ime.ps1 -Signed`，安裝程式會驗證兩個簽章並直接安裝已簽署的檔案。

# 可攜式會話層

本節介紹跨平台 C++ 核心的會話介面；它本身不是可安裝或註冊的 Windows 輸入法。每個輸入上下文應獨立持有一個 `meowpuut::Session`，並只在所屬執行緒使用。字典以 `std::filesystem::path` 讀取；Windows 呼叫端可以傳 UTF-16 路徑。快照複製 UTF-8 組字、候選與上屏字串；`takeSnapshot()` 讀取後清除待提交文字，避免重複上屏。

`Keyboard.h` 提供鍵盤事件轉換：拼音數字選詞、Shift 加大千聲調鍵輸入拼音聲調，拼音組字時空格保留原文並加入空格；注音的數字留給聲調而 Shift 加數字選詞。翻頁與編輯鍵在組字時交給會話。呼叫端需傳入當次事件的虛擬鍵、鍵盤配置產生的 ASCII 字元及修飾鍵狀態，忽略 key-up 和死鍵，並自行處理單按 Shift 切換中英。Ctrl、Alt、Windows 組合鍵保留給系統或應用程式。這個轉換層不會安裝鍵盤攔截器或註冊 TSF。

拼音會話可在輸入前呼叫 `registerJapaneseWords(path)`，從生成的 mimo 詞庫目錄載入 `native-japanese.tsv`；它與 macOS、iOS 使用的日語讀音來自同一份合併資料。未呼叫時保持純中文核心行為。混合中文與日語的完整句和前半段透過跨平台 C 核心產生，選取前半段後會保留未轉換的輸入。快照的 `japaneseCandidates` 與候選一一對應：每頁最多一個中日混合候選，排在該頁中文之後；額外候選放下一頁。第一頁可能有七個中文及一個額外的中日候選，拼音可用數字 1–8 選取。後續 Windows UI 需按快照實際數量顯示並標示這些候選。

生成詞庫後，可執行 `python3 scripts/export-windows-lexicon.py <生成詞庫目錄> <新的輸出目錄>` 匯出 Windows 使用的三份資料表與 `Licenses` 聲明目錄。日後發佈 Windows 二進位時須一併附上該目錄；此會話層不會自行產生 Windows 安裝包。

同一次組字中，選擇中文前段後，會話會暫存短期上下文以協助後段排序；整句完成、取消、刪除至空或清除時會移除。選中含日語的混合前段會作為語境邊界，清除之前的中文上下文，並保留尚未轉換的讀音。上下文只存在於各自的會話，不寫入個人詞庫。

單次組字達到核心容量時，下一個有效按鍵會先將原始讀音加入待提交文字，再開始新的組字；注音保留顯示的注音符號，拼音保留原文。待提交內容必須由呼叫端透過 `takeSnapshot()` 依序上屏；連續按鍵之間未取快照也不會覆蓋前一次結果。

TSF 的 [`OnTestKeyDown`](https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfkeyeventsink-ontestkeydown) 是按鍵意向查詢；`canHandleKey` 不改變組字、候選或待提交文字，可用於此查詢。實際處理另調用 `handleKey`。兩者之間應使用同一輸入上下文的會話與模式；模式必須與會話載入的詞庫一致。核心按鍵接受規則有變動時，要同步更新查詢並執行回歸：測試逐一比對拼音、注音在空輸入和組字狀態下所有 ASCII 可列印鍵的查詢結果與核心行為。這組轉換層是 TSF 接入準備；它尚未連接上方的 TSF 原型。

在已安裝 CMake、Visual Studio C++ 開發工具和 Windows SDK 的機器上，使用已生成的 mimo 字典目錄：

```powershell
cmake -S Windows -B build/windows -A x64 -DMEOWPUUT_LEXICON_DIR="C:/path/to/lexicons"
cmake --build build/windows --config Release
ctest --test-dir build/windows -C Release --output-on-failure
```

目錄須包含 `native-pinyin.tsv`、`native-zhuyin.tsv` 和 `native-japanese.tsv`；它們由專案現有詞庫編譯流程產生。`MEOWPUUT_LEXICON_DIR` 未指定時不註冊整合測試。可用上方的 Windows 建置腳本產生詞庫，再指向其輸出目錄。

獨立 TSF 原型見上方；它與此會話層尚未整合。會話層尚未接入 macOS 的 Swift 排序、個人詞庫、完整日語候選、中文標點和全部快捷鍵邏輯。可參閱 Microsoft 的 [TSF 文檔](https://learn.microsoft.com/en-us/windows/win32/tsf/text-services-framework)、[composition](https://learn.microsoft.com/en-us/windows/win32/tsf/compositions) 和 [文字服務註冊](https://learn.microsoft.com/en-us/windows/win32/tsf/text-service-registration)。

會話層只在 macOS 的 Clang 上編譯並執行整合測試，未在 Windows、MSVC 或真實 TSF 客戶端驗證。

目前原始碼包含焦點切換與待上屏文字的保護實驗；僅通過跨平台佇列測試與 Windows x64 交叉編譯，尚未在 Windows 主機驗證 TSF 生命週期、非同步回呼或應用程式相容性。應用關閉、服務停用、宿主持續拒絕寫入時，未完成文字仍可能遺失。
