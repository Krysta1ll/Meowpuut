#include "../ScriptMode.h"
#include <cassert>
#include <string>
#include <string_view>

static void type(MPNSession *session, std::string_view input) {
    for (char key : input) assert(mpn_key(session, key));
}

int main() {
    assert(pinyinScriptShortcut('Q', true, true, true, false, false));
    assert(!pinyinScriptShortcut('Q', true, true, true, true, false)); // AltGr
    assert(!pinyinScriptShortcut('Q', true, true, true, false, true)); // Win
    assert(!pinyinScriptShortcut('Q', true, true, false, false, false));
    assert(!pinyinScriptShortcut('Q', true, false, true, false, false));
    assert(!pinyinScriptShortcut('J', true, true, true, false, false)); // IDE select all
    assert(!pinyinScriptShortcut('F', true, true, true, false, false));
    constexpr std::string_view data =
        "MEOWPUUT-PINYIN-2\n"
        "han\than4\t漢\t汉\t9000\t9000\n"
        "yu\tyu3\t語\t语\t9000\t9000\n";
    auto *lexicon = mpn_lexicon_create(data.data(), data.size());
    assert(lexicon);
    auto *first = mpn_session_create(lexicon);
    auto *second = mpn_session_create(lexicon);
    assert(first && second);

    // The service starts in simplified mode, and each context has its own
    // composition and pending text. Switching one cannot drain the other.
    assert(applyPinyinScript(first, false, true));
    assert(applyPinyinScript(second, false, true));
    assert(!applyPinyinScript(first, true, true));
    type(first, "han");
    assert(std::string(mpn_candidate(first, 0)) == "汉");
    assert(mpn_choose(first, 0));
    type(first, "yu");
    type(second, "han");
    assert(std::string(mpn_candidate(second, 0)) == "汉");
    assert(applyPinyinScript(first, true, false));
    assert(std::string(mpn_candidate(first, 0)) == "語");
    assert(std::string(mpn_input(first)) == "yu");
    assert(std::string(mpn_committed(first)) == "汉");
    assert(std::string(mpn_candidate(second, 0)) == "汉");
    assert(std::string(mpn_input(second)) == "han");
    assert(std::string(mpn_committed(second)).empty());

    // A suspended context is updated when it becomes active, preserving its
    // own input while rebuilding candidate identities for the current mode.
    assert(applyPinyinScript(second, true, false));
    assert(std::string(mpn_input(second)) == "han");
    assert(std::string(mpn_candidate(second, 0)) == "漢");
    assert(mpn_choose(second, 0));
    assert(std::string(mpn_committed(second)) == "漢");
    assert(std::string(mpn_committed(first)) == "汉");
    assert(mpn_choose(first, 0));
    assert(std::string(mpn_committed(first)) == "汉語");

    mpn_session_destroy(second);
    mpn_session_destroy(first);
    mpn_lexicon_destroy(lexicon);
}
