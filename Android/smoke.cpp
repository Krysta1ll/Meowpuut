#include "MeowpuutCore.h"
#include "src/main/cpp/CandidateEvidence.h"

#include <cassert>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>

void check(const char *path, std::string_view keys, bool simplified, std::string_view expected) {
    std::ifstream file(path, std::ios::binary);
    const std::string data{std::istreambuf_iterator<char>{file}, {}};
    auto *lexicon = mpn_lexicon_create(data.data(), data.size());
    assert(lexicon);
    auto *session = mpn_session_create(lexicon);
    mpn_lexicon_destroy(lexicon);
    assert(session);

    mpn_set_simplified(session, simplified);
    for (char key : keys) assert(mpn_key(session, key));
    assert(std::string_view(mpn_preedit(session)).size() > 0);
    assert(mpn_key(session, 0xff08));
    assert(mpn_key(session, keys.back()));

    if (keys == "xi'an") {
        for (size_t i = 0; i < mpn_candidate_count(session); ++i)
            assert(std::string_view(mpn_candidate(session, i)) != "先");
    }

    size_t index = 0;
    while (index < mpn_candidate_count(session) && mpn_candidate(session, index) != expected) ++index;
    assert(index < mpn_candidate_count(session));
    assert(mpn_choose(session, index));
    assert(mpn_committed(session) == expected);
    assert(std::string_view(mpn_input(session)).empty());
    mpn_session_destroy(session);
}

void full_candidate() {
    constexpr std::string_view data = "MEOWPUUT-PINYIN-1\nhao\thao3\t好\t好\t100\nni\tni3\t你\t你\t100\n";
    auto *lexicon = mpn_lexicon_create(data.data(), data.size());
    assert(lexicon);
    auto *session = mpn_session_create(lexicon);
    mpn_lexicon_destroy(lexicon);
    assert(session);
    mpn_require_full_candidate(session);
    for (char key : std::string_view("nihaonihaonihao")) assert(mpn_key(session, key));
    assert(std::string_view(mpn_candidate(session, 0)) == "你好你好你好");
    mpn_commit(session);
    assert(std::string_view(mpn_input(session)).empty());
    for (char key : std::string_view("niqqqqqqqq")) assert(mpn_key(session, key));
    assert(std::string_view(mpn_exact_pinyin(session, "nihao")) == "你好");
    assert(std::string_view(mpn_input(session)) == "niqqqqqqqq");
    assert(std::string_view(mpn_candidate(session, 0)) == "你qqqqqqqq");
    assert(std::string_view(mpn_candidate(session, 1)) == "你");
    assert(mpn_sentence_count(session) == 1);
    assert(mpn_literal_tail(session) == 8);
    assert(mpn_candidate_consumed(session, 1) == 2);
    assert(std::string_view(mpn_candidate(session, 2)) == "niqqqqqqqq");
    mpn_commit(session);
    assert(std::string_view(mpn_committed(session)) == "你好你好你好你");
    assert(std::string_view(mpn_input(session)) == "qqqqqqqq");
    mpn_clear_commit(session);
    assert(std::string_view(mpn_committed(session)).empty());
    assert(std::string_view(mpn_input(session)) == "qqqqqqqq");
    mpn_clear(session);
    for (char key : std::string_view("nihaonihao")) assert(mpn_key(session, key));
    size_t prefix = 0;
    while (prefix < mpn_candidate_count(session) &&
           (std::string_view(mpn_candidate(session, prefix)) != "你好" || mpn_candidate_consumed(session, prefix) != 5)) ++prefix;
    assert(prefix < mpn_candidate_count(session));
    assert(mpn_choose(session, prefix));
    assert(std::string_view(mpn_committed(session)) == "你好");
    assert(std::string_view(mpn_input(session)) == "nihao");
    mpn_session_destroy(session);
}

void english_evidence() {
    std::ifstream file("Android/Generated/native-pinyin.tsv", std::ios::binary);
    const std::string data{std::istreambuf_iterator<char>{file}, {}};
    auto *lexicon = mpn_lexicon_create(data.data(), data.size());
    assert(lexicon);
    auto *session = mpn_session_create(lexicon);
    mpn_lexicon_destroy(lexicon);
    assert(session);
    mpn_require_full_candidate(session);
    for (int simplified : {0, 1}) {
        mpn_set_simplified(session, simplified);
        for (const auto *reading : {"sushi", "youhua", "jixu"}) {
            mpn_clear(session);
            for (const char key : std::string_view(reading)) assert(mpn_key(session, key));
            assert(pinyinHasExactChinese(session));
        }
        for (const auto *reading : {"emoji", "email", "emails"}) {
            mpn_clear(session);
            for (const char key : std::string_view(reading)) assert(mpn_key(session, key));
            assert(!pinyinHasExactChinese(session));
            size_t index = 0;
            while (index < mpn_candidate_count(session) && std::string_view(mpn_candidate(session, index)) != reading) ++index;
            assert(index < mpn_candidate_count(session));
            assert(mpn_choose(session, index));
            assert(std::string_view(mpn_committed(session)) == reading);
            assert(std::string_view(mpn_input(session)).empty());
        }
    }
    mpn_session_destroy(session);
}

int main() {
    full_candidate();
    english_evidence();
    check("Android/Generated/native-pinyin.tsv", "nihao", false, "你好");
    check("Android/Generated/native-pinyin.tsv", "youhua", false, "優化");
    check("Android/Generated/native-pinyin.tsv", "youhua", true, "优化");
    check("Android/Generated/native-pinyin.tsv", "xi'an", false, "西安");
    check("Android/Generated/native-pinyin.tsv", "xi'an", true, "西安");
    check("Android/Generated/native-zhuyin.tsv", "su3cl3", false, "你好");
}
