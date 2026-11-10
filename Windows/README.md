# Windows 開發基礎

目前提供的是 C++ 核心的會話介面，**尚不是可安裝或註冊的 Windows 輸入法**。每個輸入上下文應獨立持有一個 `meowpuut::Session`，並只在所屬執行緒使用。字典以 `std::filesystem::path` 讀取；Windows 呼叫端可以傳 UTF-16 路徑。快照複製 UTF-8 組字、候選與上屏字串；`takeSnapshot()` 讀取後清除待提交文字，避免重複上屏。

`Keyboard.h` 提供鍵盤事件轉換：拼音數字選詞、Shift 加大千聲調鍵輸入拼音聲調，拼音組字時空格保留原文並加入空格；注音的數字留給聲調而 Shift 加數字選詞。翻頁與編輯鍵在組字時交給會話。呼叫端需傳入當次事件的虛擬鍵、鍵盤配置產生的 ASCII 字元及修飾鍵狀態，忽略 key-up 和死鍵，並自行處理單按 Shift 切換中英。Ctrl、Alt、Windows 組合鍵保留給系統或應用程式。這個轉換層不會安裝鍵盤攔截器或註冊 TSF。

拼音會話可在輸入前呼叫 `registerJapaneseWords(path)`，從生成的 mimo 詞庫目錄載入 `native-japanese.tsv`；它與 macOS、iOS 使用的日語讀音來自同一份合併資料。未呼叫時保持純中文核心行為。混合中文與日語的完整句和前半段透過跨平台 C 核心產生，選取前半段後會保留未轉換的輸入。快照的 `japaneseCandidates` 與候選一一對應：每頁最多一個中日混合候選，排在該頁中文之後；額外候選放下一頁。第一頁可能有七個中文及一個額外的中日候選，拼音可用數字 1–8 選取。後續 Windows UI 需按快照實際數量顯示並標示這些候選。

生成詞庫後，可執行 `python3 scripts/export-windows-lexicon.py <生成詞庫目錄> <新的輸出目錄>` 匯出 Windows 使用的三份資料表與 `Licenses` 聲明目錄。日後發佈 Windows 二進位時須一併附上該目錄；目前這裡仍是可攜式會話層，尚未提供 Windows 安裝包。

同一次組字中，選擇中文前段後，會話會暫存短期上下文以協助後段排序；整句完成、取消、刪除至空或清除時會移除。選中含日語的混合前段會作為語境邊界，清除之前的中文上下文，並保留尚未轉換的讀音。上下文只存在於各自的會話，不寫入個人詞庫。

單次組字達到核心容量時，下一個有效按鍵會先將原始讀音加入待提交文字，再開始新的組字；注音保留顯示的注音符號，拼音保留原文。待提交內容必須由呼叫端透過 `takeSnapshot()` 依序上屏；連續按鍵之間未取快照也不會覆蓋前一次結果。

TSF 的 [`OnTestKeyDown`](https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfkeyeventsink-ontestkeydown) 是按鍵意向查詢；`canHandleKey` 不改變組字、候選或待提交文字，可用於此查詢。實際處理另調用 `handleKey`。兩者之間應使用同一輸入上下文的會話與模式；模式必須與會話載入的詞庫一致。核心按鍵接受規則有變動時，要同步更新查詢並執行回歸：測試逐一比對拼音、注音在空輸入和組字狀態下所有 ASCII 可列印鍵的查詢結果與核心行為。這是 TSF 接入準備，並非已實作 TSF 文字服務。

在已安裝 CMake、Visual Studio C++ 開發工具和 Windows SDK 的機器上，使用已生成的 mimo 字典目錄：

```powershell
cmake -S Windows -B build/windows -A x64 -DMEOWPUUT_LEXICON_DIR="C:/path/to/lexicons"
cmake --build build/windows --config Release
ctest --test-dir build/windows -C Release --output-on-failure
```

目錄須包含 `native-pinyin.tsv`、`native-zhuyin.tsv` 和 `native-japanese.tsv`；它們由專案現有詞庫編譯流程產生。`MEOWPUUT_LEXICON_DIR` 未指定時不註冊整合測試。這裡尚未提供 Windows 原生字典編譯流程，請先在已支援的環境產生檔案。

下一步接 Windows TSF：COM 文字服務生命週期、文字輸入處理、composition edit session、候選清單、輸入模式與字形切換、正確的 UTF-8／UTF-16 轉換、註冊和卸載，均尚未完成。會話層尚未接入 macOS 的 Swift 排序、個人詞庫、完整日語候選、中文標點和全部快捷鍵邏輯。可參閱 Microsoft 的 [TSF 文檔](https://learn.microsoft.com/en-us/windows/win32/tsf/text-services-framework)、[composition](https://learn.microsoft.com/en-us/windows/win32/tsf/compositions) 和 [文字服務註冊](https://learn.microsoft.com/en-us/windows/win32/tsf/text-service-registration)。

本次只在 macOS 的 Clang 上編譯並執行整合測試，未在 Windows、MSVC 或真實 TSF 客戶端驗證；也沒有安裝包。
