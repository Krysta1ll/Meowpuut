# 核心排序修正交接（0.8.0，Android / Windows）

## 問題
接上詞對表（mimo_pairs.bin）後，打完整音節（ma ta de ba ha la ne wo …）時，
第一個候選是縮寫詞（m+a 的 默哀、t+a 的 天啊），不是 嗎 他 的。
iOS / macOS 沒事，是因為 Swift 層（ChineseWithJapaneseBackend.swift 約 327–337 行）
用 WordEvidence 把「整個輸入就是完整讀音」的字詞移到前面；Android 和 Windows 直接用核心的順序。

## 原因
crates/meowpuut-core/src/decoder.rs（修正前 109–143 行）：有詞對表時，整段輸入的縮寫列
（讀音不完整的列，權重是原詞的 1/8）被放在清單最前面、不參與分數排序；完整拼寫的字詞
（merge::push_whole_words）則放在後面依分數排序。所以縮寫列永遠排在 嗎 前面。

## 修正
- decoder.rs：只有在「沒有任何完整拼寫的字詞讀作整段輸入」時，縮寫列才照舊排在最前面
  （nh、xkl、zmb、mc 不變）；否則縮寫列交給 merge::push_whole_words 一起依分數排。
- merge.rs push_whole_words：多一個 abbreviations 參數；縮寫列的分數只用權重
  （weight/700 − 19），不加句首句尾項，免得句首句尾項蓋過縮寫列之間很小的權重差。

## 量測（核心單獨、接詞對表，跟 Android/Windows 一樣；工具在 scratchpad/coreq）
- 短輸入清單：拼音第一候選對的 33/57 → 53/57（繁簡各一）；注音 19/19 不變。
- bench/dev/held/abbr 的第一候選：只有 bench 的 wan（期待 我愛你）變成 玩；
  這兩列在 dropped.json 裡、不計分，Apple 也給 萬。其餘第一候選完全相同。

## 進度
- [x] 修正、cargo test --workspace 全過（iOS/Generated 從主目錄 cp -c 複製，未重新產生）
- [x] Rust 回歸測試：crates/meowpuut-core/tests/pairs_shipped.rs 的 shipped_pairs_first_candidates
  （舊核心在 ma 就失敗：默哀）
- [ ] meoweval（Swift 引擎）bench/dev/held 前後
- [ ] build.sh --test、test-ios.sh、Tests/golden
- [ ] build-windows-check.sh、build-android.sh --test
