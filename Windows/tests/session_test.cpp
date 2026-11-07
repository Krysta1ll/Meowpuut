#include "../Session.h"
#include "../Keyboard.h"
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <stdexcept>

static void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}
static void type(meowpuut::Session &session, const char *keys) {
    for (; *keys; ++keys) require(session.key(static_cast<unsigned char>(*keys)), "key was not accepted");
}
static std::size_t indexOf(const meowpuut::Snapshot &s, const std::string &text) {
    auto it = std::find(s.candidates.begin(), s.candidates.end(), text);
    require(it != s.candidates.end(), "expected candidate absent on first page");
    return static_cast<std::size_t>(it - s.candidates.begin());
}

static void verifyInputLimit(meowpuut::Session &py, meowpuut::Session &zy) {
    const auto limit = mpn_max_input_bytes();
    require(limit >= 2 && limit < 4096, "bounded input limit");
    const std::string longPinyin(limit, 'a');
    const std::string longZhuyin(limit, 'j');
    const meowpuut::KeyEvent pinyinA{'A', 'a'}, pinyinB{'B', 'b'}, pinyinC{'C', 'c'};
    const meowpuut::KeyEvent zhuyinJ{'J', 'j'}, zhuyinQ{'Q', 'q'};
    const auto feed = [](meowpuut::Session &session, meowpuut::InputMode mode,
                         const meowpuut::KeyEvent &event, std::size_t count) {
        for (std::size_t i = 0; i < count; ++i) {
            require(meowpuut::canHandleKey(session, mode, event), "query accepts long input key");
            require(meowpuut::handleKey(session, mode, event), "long input key handled");
        }
    };
    for (bool simplified : {false, true}) {
        py.clear(); py.setSimplified(simplified);
        feed(py, meowpuut::InputMode::Pinyin, pinyinA, limit);
        require(py.input() == longPinyin, "pinyin reaches the input limit");
        // Consecutive rollover without reading a snapshot retains both blocks
        // in order and starts a new composition with the next key.
        feed(py, meowpuut::InputMode::Pinyin, pinyinB, limit);
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, pinyinC), "pinyin second rollover");
        auto state = py.takeSnapshot();
        require(state.committed == longPinyin + std::string(limit, 'b') && state.input == "c",
                "pinyin rollover preserves both readings and the next key");
        require(py.takeSnapshot().committed.empty(), "pinyin rollover delivered once");

        py.clear(); feed(py, meowpuut::InputMode::Pinyin, pinyinA, limit);
        const meowpuut::KeyEvent unsupported{0, '@'};
        require(!meowpuut::canHandleKey(py, meowpuut::InputMode::Pinyin, unsupported), "unsupported key passes at pinyin limit");
        require(!meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, unsupported), "unsupported key does not roll over pinyin");
        require(py.input() == longPinyin && py.takeSnapshot().committed.empty(), "pinyin limit remains intact after an unsupported key");
        const meowpuut::KeyEvent tone{'3', '#', true};
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, tone), "pinyin tone at the limit");
        state = py.takeSnapshot();
        require(state.committed == longPinyin + "3" && state.input.empty(), "tone remains with the old reading");

        py.clear(); feed(py, meowpuut::InputMode::Pinyin, pinyinA, limit);
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, {0x20, ' '}), "pinyin space at the limit");
        state = py.takeSnapshot();
        require(state.committed == longPinyin + " " && state.input.empty(), "Windows literal space retains the reading");

        zy.clear(); zy.setSimplified(simplified);
        feed(zy, meowpuut::InputMode::Zhuyin, zhuyinJ, limit);
        const auto visibleReading = zy.takeSnapshot().preedit;
        require(!visibleReading.empty(), "zhuyin visible reading exists");
        require(!meowpuut::canHandleKey(zy, meowpuut::InputMode::Zhuyin, unsupported), "unsupported key passes at zhuyin limit");
        require(!meowpuut::handleKey(zy, meowpuut::InputMode::Zhuyin, unsupported), "unsupported key does not roll over zhuyin");
        require(zy.input() == longZhuyin && zy.takeSnapshot().committed.empty(), "zhuyin limit remains intact after an unsupported key");
        require(meowpuut::handleKey(zy, meowpuut::InputMode::Zhuyin, zhuyinQ), "zhuyin rollover");
        require(meowpuut::handleKey(zy, meowpuut::InputMode::Zhuyin, {0x1b, 0}), "cancel new zhuyin composition");
        state = zy.takeSnapshot();
        require(state.committed == visibleReading && state.input.empty(), "cancel retains undelivered zhuyin reading");
        require(zy.takeSnapshot().committed.empty(), "zhuyin rollover delivered once");

        zy.clear(); feed(zy, meowpuut::InputMode::Zhuyin, zhuyinJ, limit);
        const auto beforeTone = zy.takeSnapshot().preedit;
        require(meowpuut::handleKey(zy, meowpuut::InputMode::Zhuyin, {'3', '3'}), "zhuyin tone at the limit");
        state = zy.takeSnapshot();
        require(state.committed == beforeTone + u8"ˇ" && state.input.empty(), "zhuyin tone retains the old visible reading");

        zy.clear(); feed(zy, meowpuut::InputMode::Zhuyin, zhuyinJ, limit);
        const auto beforeSpace = zy.takeSnapshot().preedit;
        require(meowpuut::handleKey(zy, meowpuut::InputMode::Zhuyin, {0x20, ' '}), "zhuyin first tone at the limit");
        state = zy.takeSnapshot();
        require(state.committed == beforeSpace + u8"ˉ" && state.input.empty(), "first tone remains with the old reading");
    }

    py.clear(); py.setSimplified(false); type(py, "woxihuann");
    auto state = py.takeSnapshot();
    require(py.chooseOnPage(indexOf(state, u8"我喜歡")), "prefix before rollover");
    // Do not read the selected prefix until after the rollover and next key.
    feed(py, meowpuut::InputMode::Pinyin, pinyinA, limit - 1);
    require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, pinyinB), "rollover after selected prefix");
    state = py.takeSnapshot();
    require(state.committed == u8"我喜歡" + std::string("n") + std::string(limit - 1, 'a') && state.input == "b",
            "selected prefix and raw suffix commit in order without loss");
    require(py.segmentContext().empty(), "rollover ends the previous segment context");
    require(py.takeSnapshot().committed.empty(), "selected prefix and rollover delivered once");

    py.clear(); type(py, "wosukinihao");
    require(py.key(0xff56), "navigate to mixed prefix before rollover");
    state = py.takeSnapshot();
    require(py.chooseOnPage(indexOf(state, u8"我好き")), "mixed prefix before rollover");
    feed(py, meowpuut::InputMode::Pinyin, pinyinA, limit - 5);
    require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, pinyinB), "rollover after mixed prefix");
    state = py.takeSnapshot();
    require(state.committed == u8"我好き" + std::string("nihao") + std::string(limit - 5, 'a') && state.input == "b",
            "mixed prefix and preserved suffix commit in order");
    require(py.takeSnapshot().committed.empty(), "mixed prefix and rollover delivered once");
}
// TSF may ask repeatedly before the actual keydown. Verify that the query
// leaves state untouched and agrees with the independently executed core.
static void verifyPrintableAcceptance(meowpuut::Session &session, meowpuut::InputMode mode,
                                      const char *prefix) {
    for (int ch = 32; ch <= 126; ++ch) {
        session.clear();
        type(session, prefix);
        const auto before = session.takeSnapshot();
        const meowpuut::KeyEvent event{0, static_cast<char>(ch)};
        const auto allowed = meowpuut::canHandleKey(session, mode, event);
        require(allowed == meowpuut::canHandleKey(session, mode, event), "repeated TSF key query");
        const auto after = session.takeSnapshot();
        require(before.input == after.input && before.preedit == after.preedit &&
                before.candidates == after.candidates && before.page == after.page &&
                after.committed.empty(), "TSF query must not mutate composition");
        require(allowed == session.key(ch), "query and core key handling disagree");
    }
}
#ifdef _WIN32
int wmain(int argc, wchar_t **argv) {
#else
int main(int argc, char **argv) {
#endif
    if (argc != 4) { std::cerr << "usage: session_test native-pinyin.tsv native-zhuyin.tsv japanese.tsv\n"; return 2; }
    try {
        bool invalid = false;
        try { meowpuut::Session missing("no-such-meowpuut-lexicon.tsv"); } catch (const std::runtime_error &) { invalid = true; }
        require(invalid, "missing lexicon must fail");
        meowpuut::Session py(argv[1]), zy(argv[2]);
        type(py, "nihao");
        auto saved = py.takeSnapshot();
        require(saved.input == "nihao" && !saved.preedit.empty(), "pinyin composition");
        require(py.chooseOnPage(indexOf(saved, u8"你好")), "pinyin choice");
        auto committed = py.takeSnapshot();
        require(committed.committed == u8"你好" && py.takeSnapshot().committed.empty(), "commit exactly once");
        require(saved.input == "nihao", "snapshot retains its own strings");
        type(zy, "su3cl3");
        auto zhuyin = zy.takeSnapshot();
        require(zy.chooseOnPage(indexOf(zhuyin, u8"你好")), "zhuyin choice");
        require(zy.takeSnapshot().committed == u8"你好", "zhuyin commit");
        py.clear(); py.setSimplified(true); type(py, "hanyu");
        auto simplified = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(simplified, u8"汉语")), "simplified output");
        require(py.takeSnapshot().committed == u8"汉语", "simplified commit");
        require(!py.chooseOnPage(7) && !py.chooseOnPage(SIZE_MAX), "invalid indexes rejected");
        py.clear(); py.setSimplified(false); type(py, "hanyu");
        auto traditional = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(traditional, u8"漢語")), "traditional output");
        require(py.takeSnapshot().committed == u8"漢語", "traditional commit");
        py.clear();
        for (char ch : std::string("nihao"))
            require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin,
                                        {static_cast<std::uint32_t>(ch - 'a' + 'A'), ch}), "pinyin key mapping");
        require(!meowpuut::canHandleKey(py, meowpuut::InputMode::Pinyin, {'C', 'c', false, true}), "query passes control shortcut");
        require(!meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, {'C', 'c', false, true}), "pass through control shortcut");
        require(!meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, {0x0d, 0, true}), "pass through shift+enter");
        require(py.takeSnapshot().input == "nihao", "shortcuts leave composition untouched");
        require(meowpuut::canHandleKey(py, meowpuut::InputMode::Pinyin, {'1', '1'}), "query first candidate selection");
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, {'1', '1'}), "pinyin number selects");
        require(py.takeSnapshot().committed == u8"你好", "pinyin number commits first candidate");
        py.clear();
        for (char ch : std::string("hao")) py.key(ch);
        require(meowpuut::canHandleKey(py, meowpuut::InputMode::Pinyin, {'3', '#', true}), "query pinyin tone key");
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, {'3', '#', true}), "pinyin tone key");
        require(py.takeSnapshot().input == "hao3", "pinyin shift+3 is third tone");
        py.clear();
        require(!meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, {0x20, ' ', true}), "shift-space passes without composition");
        zy.clear();
        for (char ch : std::string("su3cl3")) {
            const auto vk = ch >= 'a' && ch <= 'z' ? ch - 'a' + 'A' : ch;
            require(meowpuut::handleKey(zy, meowpuut::InputMode::Zhuyin,
                                        {static_cast<std::uint32_t>(vk), ch}), "zhuyin key mapping");
        }
        require(zy.takeSnapshot().input == "su3cl3", "zhuyin digits remain tone keys");
        require(meowpuut::handleKey(zy, meowpuut::InputMode::Zhuyin, {'1', '!', true}), "zhuyin shift+number selects");
        require(zy.takeSnapshot().committed == u8"你好", "zhuyin selection commits");
        require(!meowpuut::handleKey(zy, meowpuut::InputMode::Zhuyin, {0x08, 0}), "backspace passes without composition");
        py.clear(); type(py, "nihao");
        const meowpuut::KeyEvent space{0x20, ' '};
        require(meowpuut::canHandleKey(py, meowpuut::InputMode::Pinyin, space), "pinyin literal space query");
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, space), "pinyin literal space");
        require(py.takeSnapshot().committed == "nihao " && py.takeSnapshot().committed.empty(), "pinyin space commits literal once");
        py.clear(); type(py, "wox");
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, space), "literal space without exact candidate");
        require(py.takeSnapshot().committed == "wox ", "pinyin typo is retained");
        py.clear(); type(py, "nihao");
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, {'1', '1'}), "first pending commit");
        type(py, "emoji");
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, space), "literal after pending commit");
        require(py.takeSnapshot().committed == u8"你好emoji ", "pending commits retain order");
        py.clear(); type(py, "nihao");
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, space), "literal before clear");
        py.clear();
        require(py.takeSnapshot().committed.empty(), "clear discards pending literal");
        py.clear(); type(py, "nihao");
        const auto chineseBefore = py.takeSnapshot();
        require(py.registerJapaneseWords(argv[3]) > 0, "Japanese words registered");
        require(py.registerJapaneseWords(argv[3]) == 0, "registration must not duplicate words");
        auto chineseAfter = py.takeSnapshot();
        require(chineseBefore.candidates == chineseAfter.candidates, "pure Chinese ordering unchanged");
        require(zy.registerJapaneseWords(argv[3]) == 0, "Japanese mixed candidates belong to pinyin mode");
        py.clear(); type(py, "wosukinihao");
        auto page = py.takeSnapshot();
        require(!page.japaneseCandidates.empty() && page.japaneseCandidates.back(), "mixed choice appears after Chinese");
        require(std::count(page.japaneseCandidates.begin(), page.japaneseCandidates.end(), true) == 1, "one mixed choice per page");
        require(std::find(page.candidates.begin(), page.candidates.end(), u8"我好き你好") != page.candidates.end(), "whole mixed sentence appears");
        if (page.candidates.size() == 8) {
            const meowpuut::KeyEvent eighth{'8', '8'};
            require(meowpuut::canHandleKey(py, meowpuut::InputMode::Pinyin, eighth), "eighth shortcut reaches extra mixed candidate");
        }
        require(py.key(0xff56), "next candidate page");
        page = py.takeSnapshot();
        require(page.page == 1 && page.japaneseCandidates.back(), "mixed prefix appears last on next page");
        require(py.chooseOnPage(indexOf(page, u8"我好き")), "select mixed prefix");
        auto partial = py.takeSnapshot();
        require(partial.committed == u8"我好き" && partial.input == "nihao", "mixed prefix leaves suffix intact");
        require(py.takeSnapshot().committed.empty(), "partial commit delivered once");
        require(py.chooseOnPage(indexOf(partial, u8"你好")), "choose Chinese suffix");
        require(py.takeSnapshot().committed == u8"你好", "suffix commits once");
        py.clear(); type(py, "nihaosuki");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"你好好き")), "choose full mixed sentence");
        require(py.takeSnapshot().committed == u8"你好好き" && !py.hasComposition(), "full mixed consumes all input");
        py.clear(); type(py, "wohaokini");
        page = py.takeSnapshot();
        require(page.candidates.back() == u8"我好き你" && page.japaneseCandidates.back(),
                "pinyin-kana alias is available after Chinese candidates");
        require(py.chooseOnPage(indexOf(page, u8"我好き你")), "select pinyin-kana mixed choice");
        require(py.takeSnapshot().committed == u8"我好き你", "pinyin-kana mixed commit");
        py.clear(); type(py, "nimenhaokinihao");
        require(py.key(0xff56), "page to the mixed prefix");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"你們好き")), "select pinyin-kana mixed prefix");
        page = py.takeSnapshot();
        require(page.committed == u8"你們好き" && page.input == "nihao", "pinyin-kana prefix retains suffix");
        require(py.chooseOnPage(indexOf(page, u8"你好")), "choose suffix after pinyin-kana prefix");
        require(py.takeSnapshot().committed == u8"你好", "suffix remains unmodified and committed once");
        py.clear(); type(py, "woausuki");
        page = py.takeSnapshot();
        require(!page.japaneseCandidates.empty() && page.japaneseCandidates.back(), "reviewed short au is registered");
        require(page.candidates.back() == u8"我会う好き", "reviewed two-character reading participates in mixed input");
        py.clear(); type(py, "audio");
        page = py.takeSnapshot();
        require(std::none_of(page.japaneseCandidates.begin(), page.japaneseCandidates.end(), [](bool value) { return value; }),
                "short au must not create a mixed candidate from English audio");
        py.clear(); type(py, "wosuki");
        page = py.takeSnapshot();
        const auto mixedIndex = indexOf(page, u8"我好き");
        require(mixedIndex < 7 && mixedIndex + 1 == page.candidates.size(), "short page mixed choice has contiguous last index");
        const auto number = static_cast<char>('1' + mixedIndex);
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin,
                                    {static_cast<std::uint32_t>(number), number}), "number selects short-page mixed candidate");
        require(py.takeSnapshot().committed == u8"我好き", "short-page shortcut selects displayed candidate");
        py.clear(); type(py, "haokini");
        page = py.takeSnapshot();
        require(page.candidates.size() == 8 && page.candidates.back() == u8"好き你", "eighth displayed candidate is mixed");
        require(meowpuut::handleKey(py, meowpuut::InputMode::Pinyin, {'8', '8'}), "eight selects the eighth displayed candidate");
        require(py.takeSnapshot().committed == u8"好き你", "eighth shortcut commits mixed choice");
        py.clear(); type(py, "wosukinihao");
        require(py.key(0xff56), "navigate to next page before return");
        page = py.takeSnapshot();
        require(!page.candidates.empty(), "next page has candidates");
        const auto pageFirst = page.candidates.front();
        require(py.key(0xff0d), "return selects current page");
        require(py.takeSnapshot().committed == pageFirst, "return commits current page first candidate");
        py.clear(); type(py, "wosukinihao");
        require(py.key(0xff56), "page to prefix without snapshot");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"我好き")), "partial select without intervening snapshot");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"你好")), "select remaining phrase");
        require(py.takeSnapshot().committed == u8"你好", "second selection committed");
        zy.clear(); type(zy, "su3cl3v");
        auto zhPartial = zy.takeSnapshot();
        require(zy.chooseOnPage(indexOf(zhPartial, u8"你好")), "select zhuyin prefix");
        zhPartial = zy.takeSnapshot();
        require(zhPartial.committed == u8"你好" && zhPartial.input == "v", "zhuyin partial choice preserves unfinished syllable");
        py.clear(); py.setSimplified(false); type(py, "woxihuann");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"我喜歡")), "pinyin incomplete sentence prefix");
        page = py.takeSnapshot();
        require(page.committed == u8"我喜歡" && page.input == "n" && py.segmentContext() == u8"我喜歡",
                "partial selection retains short-lived context");
        meowpuut::Session separate(argv[1]);
        require(separate.segmentContext().empty(), "context remains isolated to its session");
        type(py, "i"); page = py.takeSnapshot();
        require(!page.candidates.empty() && page.candidates.front() == u8"你", "context helps rank continued reading");
        require(py.chooseOnPage(indexOf(page, u8"你")), "finish sentence");
        require(py.takeSnapshot().committed == u8"你" && py.segmentContext().empty(), "completed sentence clears context");
        py.clear(); py.setSimplified(true); type(py, "woxihuann");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"我喜欢")), "simplified partial selection");
        require(py.segmentContext() == u8"我喜歡", "simplified context normalized for ranking");
        require(py.key(0xff1b), "cancel remaining composition");
        require(py.segmentContext().empty(), "cancel clears composition context");
        require(py.takeSnapshot().committed == u8"我喜欢", "cancel preserves an undelivered selected prefix");
        py.clear(); py.setSimplified(false); type(py, "woxihuann");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"我喜歡")), "select prefix before deleting remainder");
        require(py.key(0xff08), "delete last unconverted key");
        require(py.segmentContext().empty(), "deleting remaining input clears context");
        require(py.takeSnapshot().committed == u8"我喜歡", "deletion preserves pending prefix");
        py.clear(); py.setSimplified(false); type(py, "woxihuann");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"我喜歡")), "select prefix before mixed continuation");
        require(py.segmentContext() == u8"我喜歡", "prefix context set");
        type(py, "ihaosukinihao");
        require(py.key(0xff56), "page to mixed prefix");
        page = py.takeSnapshot();
        require(py.chooseOnPage(indexOf(page, u8"你好好き")), "select mixed prefix after Chinese prefix");
        page = py.takeSnapshot();
        require(page.input == "nihao" && py.segmentContext().empty(), "mixed prefix retains raw suffix but clears old Chinese context");
        py.clear();
        require(py.segmentContext().empty(), "explicit clear removes context");
        verifyInputLimit(py, zy);
        for (auto prefix : {"", "hao", "hao3"}) verifyPrintableAcceptance(py, meowpuut::InputMode::Pinyin, prefix);
        for (auto prefix : {"", "su", "su3"}) verifyPrintableAcceptance(zy, meowpuut::InputMode::Zhuyin, prefix);
        std::cout << "PASS: modes, scripts, commit ownership, literal space and non-mutating TSF key queries\n";
        return 0;
    } catch (const std::exception &e) { std::cerr << "FAIL: " << e.what() << '\n'; return 1; }
}
