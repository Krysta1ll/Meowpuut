# 核心排序修正交接（0.8.0，Android / Windows）

## 問題
接上詞對表（mimo_pairs.bin）後，打完整音節（ma ta de ba ha la ne wo …）時，
第一個候選是縮寫詞（m+a 的 默哀、t+a 的 天啊），不是 嗎 他 的。
iOS / macOS 沒事，是因為 Swift 層（ChineseWithJapaneseBackend.swift 約 327–337 行）
用 WordEvidence 把「整個輸入就是完整讀音」的字詞移到前面；核心的呼叫方式一樣。
Android 和 Windows 直接用核心的順序。

## 原因
crates/meowpuut-core/src/decoder.rs（修正前 109–143 行，116 行的條件）：有詞對表時，
整段輸入的縮寫列（讀音不完整的列，權重是原詞的 1/8）放在清單最前面、不參與分數排序；
完整拼寫的字詞（merge::push_whole_words）放在後面依分數排序。所以縮寫列永遠排在 嗎 前面。

## 修正（e3c1a75）
- decoder.rs：只有在「沒有任何完整拼寫的字詞讀作整段輸入」時，縮寫列才照舊排在最前面
  （nh、xkl、zmb、mc 完全不變，英文 MC 的位置也不變）；否則縮寫列交給
  merge::push_whole_words 一起依分數排。
- merge.rs push_whole_words：多一個 abbreviations 參數；縮寫列的分數只用權重
  （weight/700 − 19），不加句首句尾項。試過加句首句尾項：縮寫列之間的順序會亂
  （sm 變 是嗎、nh 變 您好、xz 變 小張），所以不加。

## 量測（工具與輸出在 scratchpad/coreq）
- 核心單獨、接詞對表（Android/Windows 的用法）：短輸入清單拼音第一候選
  33/57 → 53/57（繁簡相同；剩下 4 個是我預期寫得嚴：o 喔、er 而、yu 魚、wbzd 修正前後都是 微不足道）；
  注音 19/19 不變。bench/dev/held/heldout-abbrev 的第一候選：只有 bench 的 wan（期待 我愛你）
  變成 玩，這兩列在 dropped.json 裡、不計分，Apple 給 萬。其餘 22,477 列第一候選完全相同。
- Swift 引擎（meoweval，用這個 worktree 的 Swift 原始碼重編，前後只差核心）：
  bench 830/924/920、dev 1121/944/955、held 469/388/399，前後完全相同；
  8,983 列的第一候選和前十個候選一列都沒變。
- 87 個同時有完整拼寫列和縮寫列的鍵：修正後第一候選全是完整拼寫的字詞或完整拼寫的路徑。

## 測試
- cargo test --workspace 全過（parity_100_seeds 不變；它和 check-chinese-golden.py 都沒接詞對表）
- 新測試 pairs_shipped.rs::shipped_pairs_first_candidates（舊核心在 ma 失敗：默哀）
- Windows session_test 照文字服務接上詞對表，檢查 ma de wo nh（舊核心失敗）；build-windows-check.sh 全過
- build-android.sh --test：APK 用新核心建好，JVM 單元測試 55 個過（不載入原生庫）
- test-ios.sh 14/14；check-chinese-golden.py 兩份都 PASS
- build.sh --test 全過：smoke「全部通過」，client golden 3974 項完全相同、不符 0 項，
  mimo-jp、日文詞表、混打核心檢查都過

全部做完；golden 和 parity 都沒有變，不需要重錄。

iOS/Generated 從主目錄 cp -c 複製，未重新產生；build-android.sh 自己編了一份
.build/android-lexicon，和 iOS/Generated 逐位元組相同。
