# Steam 上的 Windows 輸入法安裝

這個資料夾放 Steam 發行用的檔案。輸入法本身（`MeowpuutTextService.dll`）的說明在 `../README.md`。

## 為什麼不能直接註冊 Steam 資料夾裡的 DLL

輸入法 DLL 會被每一個有鍵盤輸入的程式載入。如果從 `steamapps\common\...` 註冊，只要有一個程式載著它，Steam 更新就覆蓋不了這個檔案。所以 `Install.ps1 -Steam` 先把檔案複製到 `C:\Program Files\Meowpuut\<DLL 雜湊的前 12 碼>\`，從那裡註冊；更新時複製到新的雜湊資料夾，舊的在沒有程式使用後刪除。Steam 的資料夾裡的檔案只是安裝用的來源。

## depot 的內容

depot 的根目錄就是 `package-windows.sh` 輸出的 `.build/windows-package/steam-depot-<架構>/`：

| 檔案 | x64 depot | Arm64 depot |
| --- | --- | --- |
| `x64\MeowpuutTextService.dll` | 有 | 無 |
| `x86\MeowpuutTextService.dll`（32 位元程式用） | 有 | 無（Arm64 的 Windows 用模擬執行 32 位元程式，不做 x86 DLL） |
| `arm64\MeowpuutTextService.dll` | 無 | 有 |
| `x64\MeowpuutDesk.exe`（貓窩，只有 64 位元） | 有 | 無 |
| `arm64\MeowpuutDesk.exe` | 無 | 有 |
| `Install.ps1`、`installscript.vdf` | 有 | 有 |
| `Assets\`（含貓窩的桌面姿勢和 `desk.json`）、`lexicon\`、`Licenses\`、`THIRD_PARTY.md`、`BUILD-INFO.md` | 有 | 有 |

`MeowpuutDesk.exe` 和 DLL 一起複製到 `Program Files\Meowpuut\<雜湊>\<架構>\`（雜湊也算進它），`-Steam` 只把那一份設成目前使用者的登入項目（`HKCU\...\Run\MeowpuutDesk`），不從 Steam 的系統管理員程序開啟它，所以貓窩從不由 Steam 啟動、也不會讓 Steam 顯示「遊戲中」。`Install.cmd` 和 `Uninstall.cmd` 是給 zip 用的，depot 不需要，不影響。`Install.ps1` 只複製自己這台 Windows 用得到的架構（x64 的 Windows 複製 x64 和 x86，Arm64 的 Windows 複製 arm64），所以兩個 depot 裝在同一個資料夾也不會互相干擾；也可以把三個資料夾放進同一個 depot。注意 Steam 的 depot 設定能不能依 Arm64 篩選要在 Steamworks 後台確認（未驗證）。

## installscript 怎麼跑

1. 應用程式建置設定（`app_build.vdf`）的 depot 用 `"InstallScript" "installscript.vdf"` 指向這份檔案；上傳時 Steam 簽署它（寫 HKLM 需要簽署過的 script）。
2. 玩家第一次啟動遊戲時，Steam 檢查 `HasRunKey`，沒有就以系統管理員身分執行 `powershell.exe -File Install.ps1 -Steam`。結束代碼 0 才算成功並記下；非 0 則下次啟動再跑一次。
3. `Install.ps1 -Steam` 不問任何事。它沒有系統管理員權限時以代碼 5 離開，不會自己跳 UAC；套件沒有這台 Windows 用的 DLL 時代碼 4；其他失敗代碼 1。重複執行不會改變任何東西（同一個版本的資料夾已經在就不再複製，註冊是冪等的）。
4. 它做的事：複製到 `Program Files\Meowpuut\<雜湊>`（先複製到 `.partial` 再改名，不會留下複製一半的資料夾）→ 用 `SysWOW64\regsvr32.exe` 註冊 32 位元 DLL，再用 `System32\regsvr32.exe` 註冊 64 位元 DLL（同一個 CLSID）→ 把喵撲拼音、喵撲注音加進目前使用者的中文鍵盤 → 刪掉不再使用的舊資料夾（還被程式載著的會留下，下次安裝再刪）。
5. Steam 的程序是 32 位元時，腳本會用 `Sysnative` 的 64 位元 PowerShell 重跑自己，避免 `System32` 被導到 `SysWOW64`。
6. 解除安裝時 `Run Process On Uninstall` 執行 `Install.ps1 -Steam -Uninstall`：關掉貓窩、拿掉它的登入項目、取消兩個 DLL 的註冊、從使用者的鍵盤清單拿掉、刪除 `Program Files\Meowpuut`、打字紀錄（`PetJournal.bin`）、桌面貓計數（`DeskCounters.json`）和「時間花在哪裡」的紀錄（`Activity.bin`），連同鎖檔和暫存檔。還有程式載著 DLL 時會以 `MoveFileExW(..., MOVEFILE_DELAY_UNTIL_REBOOT)` 排程在下次重開機自動刪除剩餘檔案與資料夾。

「目前使用者」是執行 Steam 的那個帳號。如果 Steam 的提權用的是另一個系統管理員帳號，鍵盤會加到那個帳號，不是玩家；陪伴 App 的引導第 3 步已實作以目前使用者身分呼叫 `InstallLayoutOrTip` 補齊這個洞。

## 還必須由你做的事

- **Authenticode 簽章**：兩個（或三個）DLL 和 `Install.ps1` 都要簽。Windows 11 的 Smart App Control 會擋沒簽章的 DLL；腳本沒簽章時執行原則和 SmartScreen 也可能擋。簽章要在 `package-windows.sh` 之後、上傳 depot 之前做，而且簽完雜湊就變了（資料夾名稱跟著變，是預期的）。
- **`HasRunKey` 的 build 號**：每次發行新 build，把 `installscript.vdf` 裡 `InstalledBuild_26A003` 改成新的 build 號（`package-windows.sh` 的 `BUILD_ID`），否則已經跑過的玩家不會再跑安裝，新版 DLL 不會註冊。
- **確認 Valve 接受這種系統修改**（`design-preview/pc/research.md` §3.1 列為未證實），並確認 `installscript.vdf` 裡標「UNCERTAIN」的幾項。
- **在真的 Windows 上測**：乾淨的 x64 和 Arm64 機器，Steam 實際安裝、更新（舊版 DLL 還被記事本載著）、驗證檔案、解除安裝；32 位元程式（找一個 32 位元的遊戲或工具）能不能打字。本機沒有 PowerShell，`Install.ps1` 只讀過、沒有執行過。
- **x86 DLL 要用 llvm-mingw 建**：Homebrew 的 i686 GCC 用 SJLJ 例外，連不上 Rust 的 i686 目標（見 `../README.md`）。
