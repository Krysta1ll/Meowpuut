# Windows 輸入法交接

分支 worktree-agent-a790d96f777b2dfc8，已合併 lexicon-without-rime-ice（fec01cc）。

## 進度

- [x] Session 可共用詞庫（mimo_base.bin 原地讀取）、`commit()`；session_test 加了這條路
- [x] Windows/TextService：TSF 文字服務 DLL（組字、底線、候選窗、拼音／注音兩個設定檔、工作列中／英）
- [x] 交叉編譯：`scripts/build-windows-ime.sh x64|arm64`（llvm-mingw 20261020 在 `/Users/scarletkrimilia/Documents/Projects/Meowpuut/.build/toolchains/`）；`build-windows-check.sh` 第 4 段也編 x64 DLL
- [x] 安裝、移除腳本與打包：`scripts/package-windows.sh` → `.build/windows-package/Meowpuut-0.8.0-alpha.1-windows-{x64,arm64}.zip`
- [x] Windows/README.md 改寫
- [x] `ITfTextLayoutSink`（Chromium 晚給位置時重擺候選窗）、同一版重裝不覆蓋使用中的 DLL
- [x] 審查修正：Shift 加點按／加其他鍵不切英文（比對執行緒鍵盤狀態的翻轉位元，加一秒上限，焦點離開時取消）；Ctrl／Alt／Win 組合鍵與 Delete、Home 等先結束讀音再交給程式（在 OnTestKeyDown 同步寫入）；Shift+Enter 送出原文；沒有候選時方向鍵不動；SetText 中組字被終止時不取空指標；FFI 的 mixed_pinyin、mixed_pinyin_prefix、predict 包 catch_unwind（cargo test --workspace、build.sh --test 都通過）
- [ ] 在 VM 上實測（13:29 時 VM 代理還在 WinPE 裡裝 Windows，沒有 `installed`）

最後打包：commit ae94bb9，`.build/windows-package/`（這個 worktree 底下）
- `Meowpuut-0.8.0-alpha.1-windows-x64.zip` 39,492,480 bytes，SHA-256 e3e9cfa063bbebf76e1a8112ef99c421e56d503ff1984ad4369218a415affc91
- `Meowpuut-0.8.0-alpha.1-windows-arm64.zip` 39,426,992 bytes，SHA-256 31d551b5eb290be1ebb2228cb195f84619012b3ab1fa097425c81a8779a02ac3
- 之後若再提交，重跑 `MINGW_BIN=… MEOWPUUT_LEXICON_DIR=/Users/scarletkrimilia/Documents/Projects/Meowpuut/iOS/Generated bash scripts/package-windows.sh`，BUILD-INFO 的 commit 才對得上。

VM 用的驅動：`/private/tmp/claude-501/-Users-scarletkrimilia-Documents-Projects-Meowpuut/5b918450-7542-4720-b593-bb806111566a/scratchpad/vm.py`（QMP 按鍵、滑鼠、截圖），還沒對 VM 用過。

## 在 Windows 上跑過的

還沒有。

## VM 上要跑的測試（Arm64 VM，用 arm64 的 zip）

0. 另外要看：Shift+點滑鼠選字不會切英文；Ctrl+Enter 送出的是選好的字；Shift+Enter 送出字母原文。
1. `windows-vm.sh copy .build/windows-package/Meowpuut-0.8.0-alpha.1-windows-arm64`，在桌面工作階段執行 `Install.cmd`（UAC 要按「是」）；截圖。
2. 檢查：`HKLM\SOFTWARE\Classes\CLSID\{961C0ADA-…}\InprocServer32` 指到 `C:\Program Files\Meowpuut\<hash>\MeowpuutTextService.dll`；`Get-WinUserLanguageList` 的 zh-Hant-TW 有兩個 TIP。
3. 記事本：Win+空白鍵切到喵撲拼音；打 `nihao`（截圖：有底線的讀音、候選窗、貓）；按 `1`；打 `woxihuanni` 按空白鍵；打 `ni` 按 → 再 Enter；打 `hao` 點第二個候選（滑鼠）；打 `shi` 按 ↓ 展開（截圖）、↑ 收起；Page Down 翻頁；打 `,`、`.` 看全形標點；單按 Shift 切英文打 `abc`，再按 Shift 切回（截圖工作列的中／英）。
4. 連點莫莫五下 → 候選變簡體（截圖），再五下回繁體。
5. 切到喵撲注音：打 `su3cl3`（ㄋㄧˇㄏㄠˇ），Shift+1 選；打 `ji3` 後按空白鍵選並送出。
6. 第二個程式：Edge 的網址列或一個 textarea，重複 3 的前半；另外試 Windows 終端機。
7. 焦點：組字中點到別的視窗、再回來；組字中 Alt+Tab。
8. 執行 `Uninstall.cmd`；確認鍵盤消失、`C:\Program Files\Meowpuut` 刪掉（或印出還在用的資料夾）。

## 需要 VM 代理的

- 能在互動桌面工作階段（不是 SSH 的 session 0）執行程式並允許 UAC：要嘛 `run` 有一個「在桌面執行」的選項（例如 `schtasks /create /it … /run`），要嘛我用 QMP 按鍵（Win+R）自己開。
- QMP 的 `input-send-event`（usb-tablet 絕對座標）點滑鼠；QMP `send-key` 打字。我會直接用 `scripts/windows-vm/qmp.py`，不改它。
