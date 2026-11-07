# Windows 雙貓玻璃候選窗預覽

在倉庫根目錄用 PowerShell 建置並執行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Windows\build-preview.ps1
& .\.build\windows\MeowpuutGlassPreview.exe
```

預覽使用 Windows 內建的 .NET Framework 與 WPF。雙貓輪廓以 macOS 深色候選窗為基準，並沿用 iOS 的雙貓造型。玻璃會即時取樣候選窗附近的桌面，在內部柔化背景，沿貓耳、弧角和尾部輪廓的法線偏移背景，折射由邊緣向內逐漸消退。輪廓反光和內側細光帶疊在暖色玻璃之上。取樣畫面僅在記憶體內處理。Windows 10 2004 起支援將預覽排除在螢幕擷取外，以避免重複取樣自身，因此部分截圖或錄影工具可能看不到預覽窗。無法取樣時回退為半透明玻璃，高對比模式下候選區使用系統色。

可點選候選，或按數字鍵 1–5、7 與左右方向鍵切換高亮。按 Esc 關閉，也可拖曳玻璃區移動視窗。預覽使用固定候選詞。

## Windows 系統輸入法（x64 Alpha）

在 Windows 10／11 x64 使用 Python 3 和 LLVM-MinGW 的 x64 編譯器建置。此分支提供原始碼，預設產物未簽署。安裝時 Windows 會要求以系統管理員權限將輸入服務安裝至 Program Files 並註冊 TSF。安裝程式會在目前登入工作階段啟用輸入法。

```powershell
.\Windows\build-ime.ps1 -Compiler C:\path\to\x86_64-w64-mingw32-clang++.exe
.\Windows\install-ime.ps1
```

在應用程式的文字區按 Win + 空白鍵，從繁體中文切換到 **Meowpuut Pinyin**。數字 1–7 直接選詞；方向鍵和 Tab 切換高亮，Enter 確認，空白鍵輸出原始拼音加空白。Page Up／Page Down 翻頁，Esc 取消，Backspace 修改。

可混打詞庫收錄的日語羅馬字，例如 `wosukini` 的候選包含「我好き你」。同頁有日語候選時，收合版保留其中一條，依原候選順序顯示。按 F4 展開查看同頁其他候選。低置信中文和其餘日語候選交替排列，貓爪只標記含日語的候選。選取帶貓爪的整句會直接上屏；選取局部中文詞會保留後續組字。
此版本使用喵撲的 C++ 中文引擎與實際詞庫，以 TSF 將文字送入應用程式。系統輸入法會啟動獨立的雙貓玻璃渲染器，顯示即時拼音與候選詞。支援 TSF 文字座標的應用程式會把候選窗貼近插入點；其餘應用程式沿用 Windows 提供的游標位置。渲染器會隨使用者登入啟動。若 Windows 應用程式控制政策阻止執行渲染器，輸入服務會使用原生候選窗。兩種候選窗都可用鍵盤選詞。已開啟的應用程式需重新啟動才能載入新版輸入服務。此建置適用於 x64 桌面應用程式。

### 安裝已簽署的版本

先建置輸入服務及玻璃渲染器，再以受信任的 Authenticode 發行者憑證簽署 `.build/windows/ime/MeowpuutTip.dll` 和 `.build/windows/MeowpuutGlassPreview.exe`。執行 `.\Windows\install-ime.ps1 -Signed`，安裝程式會驗證兩個簽章並直接安裝已簽署的檔案。
