# 上架物料

iOS 正式版上 App Store 要準備的東西，以及目前的狀態（2026-11-12）。文案在 [listing.md](listing.md)，隱私政策在 [../PRIVACY.md](../PRIVACY.md)，技術面的核對事項在 [../APP_STORE.md](../APP_STORE.md)。

## 狀態

| 項目 | 狀態 |
| --- | --- |
| 名稱、副標題、推廣文字、描述、關鍵字（繁中、簡中、日、英） | 已寫，待你看過 |
| 隱私政策全文 | 已寫；需要一個公開網址（倉庫推上去後可用 GitHub 的頁面） |
| App 內看得到隱私政策 | 已加在鍵盤設定頁最下面（下一個建置才有） |
| App Store 的「App 隱私」問卷 | 答案：不收集資料 |
| 審核備註 | 已寫（見下） |
| 截圖（iPhone 6.9 吋、iPad 13 吋） | 企劃已寫（見下），等貓圖定案後製作 |
| App 預覽影片 | 等宣傳影片完成後剪 15–30 秒 |
| 支援網址、行銷網址 | 待定（可用 GitHub 倉庫與 issues） |
| 售價、上架地區 | 待你決定 |
| 出口合規 | 已在建置裡宣告不使用需申報的加密 |

## 截圖企劃

想法延續第一支宣傳片：**每張截圖上的大字，就是那張圖裡的鍵盤正在打的那句話。** 觀眾讀標題的同時看到它是怎麼打出來的，不另外寫宣傳句。

每張是一個暖紙色的畫面，上半是一張便條（輸入框裡是打出來的句子），下半是真的鍵盤截圖（模擬器，出貨的那份程式），裝在手機外框裡。字體用圓體，顏色照 `docs/DESIGN.md`。

| # | 便條上的字（鍵盤正在打的） | 鍵盤的狀態 | 要讓人看到的 |
| --- | --- | --- | --- |
| 1 | 輸入法是我做的，貓也是我養的 | 注音，候選列有詞，兩隻貓 | 兩隻貓和玻璃候選列 |
| 2 | 打 sushi，有貓爪的是日文 | 拼音打 sushi，候選有帶貓爪的「寿司」 | 中日混打 |
| 3 | 莫莫可愛い | 拼音打 momokawaii，選到這一句 | 一句裡中文接日文 |
| 4 | 我在用iPhone | 拼音打 wozaiyongiphone | 拼音裡夾英文 |
| 5 | 開心 😊 (\*´▽`\*) | 打「開心」，表情格和顏文字格；另一半畫面是 ^_^ 面板 | 表情符號和顏文字 |
| 6 | 膠囊、波浪、雲朵 | 三種玻璃造型並排，各一種顏色，一個深色 | 外觀可以換 |
| 7 | 你打的字，只有你知道 | 鍵盤設定頁：記住選詞的開關和清除；一行小字「不連網路，不需要完整取用」 | 隱私 |
| 8 | 牠們本人 | App 首頁的相簿 | 兩隻貓的照片（要不要放由你決定） |

iPad 用同一組，版面改橫向構圖。四個語系的便條文字各自翻譯；鍵盤畫面不變（介面本來就是中文）。

## 審核備註（給 App Review，英文）

```
Meowpuut is a custom keyboard for Chinese (Zhuyin and Pinyin) and Japanese, with a small host app.

How to try it
1. Open the app once. The first screen explains how to add the keyboard.
2. Settings > General > Keyboard > Keyboards > Add New Keyboard > 喵撲.
3. In any text field, hold the globe key and choose 喵撲.
4. Type "nihao" and press space: 你好. Type "sushi": the candidate with a small paw print (寿司) is Japanese.

Things you may want to know
- The keyboard does not request Full Access (RequestsOpenAccess is false) and has no network code. Everything works offline.
- It works without Full Access in all features. The globe key switches to the next keyboard.
- No account, no sign-in, no purchases, no ads, no analytics.
- The words a person picks are stored only in the keyboard's own container, to rank their usual choices first. This can be turned off or cleared in the app's settings ("記住選詞").
- The host app and the keyboard share an App Group only for the settings the person changes in the app.
- The app shows photos of the developer's two cats (the keyboard's mascots). "Save" uses add-only access to the photo library.
- The interface is in Traditional and Simplified Chinese.

Contact: https://github.com/Krysta1ll/Meowpuut/issues
```

## 還需要你決定或提供的

1. 售價和上架地區。
2. 支援用的聯絡方式：只留 GitHub 的 issues，還是另外給一個信箱。
3. 截圖第 8 張要不要放牠們的照片。
4. 隱私政策的網址放哪裡（倉庫公開的話，推上去就有）。
