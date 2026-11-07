#pragma once

#include "../Core/MeowpuutCore.h"
#include <cstdint>
#include <filesystem>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace meowpuut {
// This adapter does not implement TSF. A session belongs to one input context
// and must be used from the thread which owns that context.
struct Snapshot {
    std::string input, preedit, committed; // UTF-8; independent of core lifetime
    std::vector<std::string> candidates;
    // Parallel to candidates. Mixed Japanese candidates are always last on a page.
    std::vector<bool> japaneseCandidates;
    std::size_t page = 0;
    bool lastPage = true;
};

class Session {
public:
    explicit Session(const std::filesystem::path &lexicon);
    // Optional independently authored source TSV: reading aliases|text|weight.
    // Register once before typing; returns the number of accepted readings.
    std::size_t registerJapaneseWords(const std::filesystem::path &dictionary);
    Session(const Session &) = delete;
    Session &operator=(const Session &) = delete;
    bool key(std::int32_t key);
    bool chooseOnPage(std::size_t index);
    bool canChooseOnPage(std::size_t index) const;
    bool hasComposition() const;
    std::string_view input() const;
    // A copy of the current composition's short-lived Chinese context.
    std::string segmentContext() const;
    bool commitLiteralWithSpace();
    void setSimplified(bool simplified);
    void clear();
    // Taking a snapshot consumes the pending commit once; candidate indexes
    // refer to the current page and remain valid only until the next mutation.
    Snapshot takeSnapshot();
private:
    struct Candidate { std::string text; std::size_t index = 0; std::size_t consumed = 0; bool japanese = false; };
    void refreshCandidates();
    std::vector<Candidate> pageCandidates() const;
    struct LexiconDeleter { void operator()(MPNLexicon *p) const { mpn_lexicon_destroy(p); } };
    struct SessionDeleter { void operator()(MPNSession *p) const { mpn_session_destroy(p); } };
    std::unique_ptr<MPNLexicon, LexiconDeleter> lexicon_;
    std::unique_ptr<MPNSession, SessionDeleter> session_;
    std::string pending_;
    std::vector<Candidate> chinese_, mixed_;
    std::size_t page_ = 0;
    bool pinyin_ = false, simplified_ = false, japaneseRegistered_ = false;
};
}
