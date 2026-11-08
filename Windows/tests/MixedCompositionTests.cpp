#include "../MixedComposition.h"
#include "../PendingDelivery.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <set>
#include <string>

static MPNLexicon *makeLexicon(const std::string &text) {
    auto *lexicon = mpn_lexicon_create(text.data(), text.size());
    assert(lexicon);
    return lexicon;
}

static void type(MPNSession *session, const std::string &text) {
    for (unsigned char ch : text) assert(mpn_key(session, ch));
}

static std::string numbered(const char *prefix, size_t number) {
    char buffer[40];
    std::snprintf(buffer, sizeof(buffer), "%s%03zu", prefix, number);
    return buffer;
}

static void verifyLongCandidateList() {
    std::string data = "MEOWPUUT-PINYIN-1\nni\tni3\t你\t你\t18000\n";
    for (size_t i = 0; i < 400; ++i) {
        const auto text = numbered("native", i);
        data += "ni\tni3\t" + text + "\t" + text + "\t" + std::to_string(16000 - i) + "\n";
    }
    auto *lexicon = makeLexicon(data);
    auto *session = mpn_session_create(lexicon);
    assert(session);
    type(session, "ni");

    ChineseEvidence evidence{{"你", 9000}};
    for (size_t i = 0; i < 320; ++i) evidence[numbered("extra", i)] = 1900 - static_cast<int>(i);
    const auto lookup = [&](const std::string &) { return evidence; };
    const std::vector<JapaneseWord> japanese{{"ni", "に", 6000}, {"ni", "你", 5000}};
    const auto choices = windowsCandidates(session, japanese, lookup, false);
    assert(choices.size() > 256);
    assert(choices.front().text == "你" && !choices.front().japanese);
    assert(choices.front().coreIndex && mpn_candidate_consumed(session, *choices.front().coreIndex) == 2);
    std::set<std::string> unique;
    for (const auto &choice : choices) {
        assert(unique.insert(choice.text).second);
        if (choice.coreIndex) {
            assert(*choice.coreIndex < mpn_candidate_count(session));
            assert(choice.text == mpn_candidate(session, *choice.coreIndex));
        }
    }
    size_t previous = 0;
    for (size_t i = 0; i < 320; ++i) {
        const auto text = numbered("extra", i);
        auto found = std::find_if(choices.begin(), choices.end(), [&](const auto &choice) { return choice.text == text; });
        assert(found != choices.end() && !found->coreIndex);
        const auto at = static_cast<size_t>(found - choices.begin());
        if (i < 7 && i > 0) assert(previous < at); // retain the leading supplements' weight order
        if (i < 7) previous = at;
        if (i == 319) {
            assert(at >= 256);
            const size_t page = at / 7, key = at % 7;
            assert(choices[page * 7 + key].text == text); // same mapping used by TextService
        }
    }
    const auto late = std::find_if(choices.begin(), choices.end(), [&](const auto &choice) {
        return choice.coreIndex && *choice.coreIndex >= 300;
    });
    assert(late != choices.end());
    const size_t absolute = static_cast<size_t>(late - choices.begin());
    assert(absolute >= 256);
    const size_t page = absolute / 7, key = absolute % 7;
    const auto &selected = choices[page * 7 + key];
    assert(selected.coreIndex && mpn_choose(session, *selected.coreIndex));
    assert(selected.text == mpn_committed(session));
    mpn_session_destroy(session);
    mpn_lexicon_destroy(lexicon);
}

static void verifySameTextDifferentConsumption() {
    const std::string data = "MEOWPUUT-PINYIN-1\n"
        "hao\thao3\t好\t好\t15000\n"
        "ni\tni3\t你\t你\t15000\n"
        "nihao\tni3hao3\t你好\t你好\t15000\n"
        "nihaoni\tni3hao3ni3\t你好\t你好\t16000\n";
    auto *lexicon = makeLexicon(data);
    auto *session = mpn_session_create(lexicon);
    assert(session);
    type(session, "nihaoni");
    const auto choices = windowsCandidates(session, {}, [](const std::string &) { return ChineseEvidence{}; }, false);
    std::set<size_t> consumed;
    std::optional<size_t> partial;
    for (const auto &choice : choices) {
        if (choice.text == "你好" && choice.coreIndex) {
            const auto length = mpn_candidate_consumed(session, *choice.coreIndex);
            consumed.insert(length);
            if (length == 5) partial = *choice.coreIndex;
        }
    }
    assert(consumed == (std::set<size_t>{5, 7}));
    assert(partial && mpn_choose(session, *partial));
    assert(std::strcmp(mpn_committed(session), "你好") == 0);
    assert(std::strcmp(mpn_input(session), "ni") == 0);
    PendingDelivery delivery;
    delivery.append(L"你好");
    mpn_clear_commit(session); // same ownership transfer used by TSF
    assert(*delivery.begin() == L"你好");
    delivery.finish(false); // first segment is rejected by the host
    const auto tail = windowsCandidates(session, {}, [](const std::string &) { return ChineseEvidence{}; }, true);
    assert(!tail.empty() && tail.front().text == "你" && tail.front().coreIndex);
    assert(mpn_candidate_consumed(session, *tail.front().coreIndex) == 2);
    assert(mpn_choose(session, *tail.front().coreIndex));
    assert(std::strcmp(mpn_input(session), "") == 0);
    assert(std::strcmp(mpn_committed(session), "你") == 0);
    delivery.append(L"你");
    mpn_clear_commit(session);
    delivery.suspend();
    assert(delivery.begin() == nullptr);
    delivery.resume();
    assert(*delivery.begin() == L"你好"); delivery.finish(true);
    assert(*delivery.begin() == L"你"); delivery.finish(true);
    assert(delivery.empty()); // rejected prefix never disappears or overtakes its tail
    mpn_session_destroy(session);
    mpn_lexicon_destroy(lexicon);
}

static void verifyRealLexicon(const char *path) {
    std::ifstream file(path, std::ios::binary);
    assert(file);
    const std::string data{std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
    auto *lexicon = makeLexicon(data);
    auto *session = mpn_session_create(lexicon);
    assert(session);
    type(session, "ji");
    const auto choices = windowsCandidates(session, {}, [](const std::string &) { return ChineseEvidence{}; }, false);
    const auto found = std::find_if(choices.begin(), choices.end(), [](const auto &choice) { return choice.text == "蹟"; });
    assert(found != choices.end() && found->coreIndex);
    const auto position = static_cast<size_t>(found - choices.begin());
    const size_t page = position / 7, key = position % 7;
    const auto &selected = choices[page * 7 + key];
    assert(selected.coreIndex && selected.text == mpn_candidate(session, *selected.coreIndex));
    assert(mpn_choose(session, *selected.coreIndex));
    assert(std::strcmp(mpn_committed(session), "蹟") == 0 && std::strcmp(mpn_input(session), "") == 0);
    mpn_clear(session);
    type(session, "lue");
    const auto aliasChoices = windowsCandidates(session, {}, [](const std::string &reading) {
        ChineseEvidence evidence;
        for (const auto &alias : pinyinEvidenceReadings(reading)) {
            if (alias == "lue") evidence.emplace("蓼莪", 1388);
            if (alias == "lve") evidence.emplace("略", 5000);
        }
        return evidence;
    }, false);
    assert(!aliasChoices.empty() && aliasChoices.front().text == "略");
    mpn_session_destroy(session);
    mpn_lexicon_destroy(lexicon);
}

int main(int argc, char **argv) {
    assert((pinyinEvidenceReadings("lue") == std::vector<std::string>{"lue", "lve"}));
    assert((pinyinEvidenceReadings("nue") == std::vector<std::string>{"nue", "nve"}));
    assert((pinyinEvidenceReadings("shenglue") == std::vector<std::string>{"shenglue", "shenglve"}));
    assert((pinyinEvidenceReadings("lu'e") == std::vector<std::string>{"lue"}));
    assert((pinyinEvidenceReadings("nu'e") == std::vector<std::string>{"nue"}));
    verifyLongCandidateList();
    verifySameTextDifferentConsumption();
    if (argc > 1) verifyRealLexicon(argv[1]);
    std::puts("PASS: Windows candidate pagination, indices and distinct consumption");
}
