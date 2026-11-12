# README 0.8.0 交接

任務：重寫 README、0.8.0 發行說明、.build/release-0.8.0-github.md、新 iOS 截圖、更新 docs/INSTALL.md。只改 README.md、assets/screenshots/、docs/INSTALL.md、docs/RELEASE_NOTES.md、docs/ENGINE.md。不推送、不建 release。

## 已知事實

- TestFlight 公開連結（主人給的）：https://testflight.apple.com/join/kfMVXAC6 。README 下載段、平台表、INSTALL.md、GitHub release 都用它：裝 Apple 的 TestFlight → 開連結 → 安裝喵撲 → 到設定加入鍵盤。
- 今天 2026-11-12 沒有節日裝扮（已查 festivals.json）。
- Android minSdk 26 = Android 8.0（任務說 7.0，照程式寫 8.0，回報）。只有 arm64-v8a、x86_64。
- Android、Windows 的日文只有 native-japanese.tsv（388 列）；iOS、macOS 是 mimo_jp.bin 約 4.6 萬詞。Android、Windows 沒有選詞記憶、沒有表情。
- 跑過的例子（iOS 系統鍵盤實拍，在 scratchpad/shots）：sushi 第四格寿司帶爪、xkl 辛苦了、wozaiyongiphone、momokawaii 莫莫可愛い、shiberu 食べる、kaixin 開心 😊 開 凱 (*´▽`*)、zenmb 怎麼辦、ㄋㄧˇㄏㄠˇ 與 ㄋㄏ 你好。
- 評測數字（830／922／918 等）是 da60ad6 之前量的，da60ad6 重錄了 golden，所以 README 不放數字。
- forbid(unsafe_code) 不是全工作區：meowpuut-ffi 允許逐項 unsafe。ENGINE.md 已寫正確。
- scripts/ios-real-keyboard-shots.sh 在 macOS bash 3.2 下 `extra[@]` 空陣列會 unbound；先自己 xcodebuild 再 SKIP_BUILD=1。
- 版號還沒改：Resources/Info.plist 0.6.0、Android versionName 0.6.0、iOS project.yml 0.7.0 (8)。
- Windows TSF 在 worktree-agent-a790d96f777b2dfc8 做，還沒合進 lexicon-without-rime-ice。

## 進度

- [x] 合併 lexicon-without-rime-ice（91ecf5d）
- [x] 截圖 assets/screenshots/ios-keyboard.png、expanded.png（954a470）
- [x] ENGINE.md 接收三層架構（7bc8760）
- [x] README 初稿、INSTALL.md（dad09aa）；Windows 段落還要照合併後的 Windows/README 補
- [x] RELEASE_NOTES.md 0.8.0（Windows「又可以安裝」那句是照協調者說的，分支還沒合併）
- [x] .build/release-0.8.0-github.md（.build 不進 git；Windows 段落同上）
- [x] 已知問題實拍：huaxue 仍是滑雪、nihao 仍有逆號；wuli 已是物理、ie 是家（日文）
- [x] Mac 的候選窗不能摸貓（只有 iPhone、Android、Windows）
- [x] 第二次合併 lexicon（00b145a）：Windows 也有節日的貓、Android 節日在模擬器看過
- [ ] Windows TSF 分支合併後：照 Windows/README.md 改 INSTALL.md 的 Windows 段、README、notes、GitHub body；ENGINE.md「Windows 仍讀 TSV」可能要改（那個分支讓 Session 讀 mimo_base.bin）
- [ ] 刪掉這個檔案
