#include "Session.h"
#include <algorithm>
#include <fstream>
#include <iterator>
#include <map>
#include <sstream>
#include <stdexcept>
#include <utility>

namespace meowpuut {
namespace { constexpr std::size_t pageSize = 7; }
Session::Session(const std::filesystem::path &path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("Unable to open Meowpuut lexicon");
    std::string data((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    if (file.bad()) throw std::runtime_error("Unable to read Meowpuut lexicon");
    pinyin_ = data.compare(0, 18, "MEOWPUUT-PINYIN-1\n") == 0;
    lexicon_.reset(mpn_lexicon_create(data.data(), data.size()), mpn_lexicon_destroy);
    if (!lexicon_) throw std::runtime_error("Invalid Meowpuut lexicon");
    session_.reset(mpn_session_create(lexicon_.get()));
    if (!session_) throw std::runtime_error("Unable to create Meowpuut session");
}
Session::Session(SharedLexicon lexicon, bool pinyin) : lexicon_(std::move(lexicon)), pinyin_(pinyin) {
    if (!lexicon_) throw std::runtime_error("Invalid Meowpuut lexicon");
    session_.reset(mpn_session_create(lexicon_.get()));
    if (!session_) throw std::runtime_error("Unable to create Meowpuut session");
}
std::size_t Session::registerJapaneseWords(const std::filesystem::path &path) {
    if (!pinyin_ || japaneseRegistered_) return 0;
    std::ifstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("Unable to open Japanese lexicon");
    std::map<std::string, std::pair<std::string, int>> best;
    std::string line;
    while (std::getline(file, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        const auto first = line.find('|'), second = line.find('|', first == line.npos ? first : first + 1);
        if (first == line.npos || second == line.npos || line.find('|', second + 1) != line.npos) continue;
        const auto text = line.substr(first + 1, second - first - 1);
        int weight = 0;
        try { std::size_t used = 0; weight = std::stoi(line.substr(second + 1), &used); if (used != line.size() - second - 1) continue; }
        catch (const std::exception &) { continue; }
        std::istringstream readings(line.substr(0, first));
        std::string reading;
        while (std::getline(readings, reading, ',')) {
            auto it = best.find(reading);
            if (it == best.end() || weight > it->second.second) best[reading] = {text, weight};
        }
    }
    if (file.bad()) throw std::runtime_error("Unable to read Japanese lexicon");
    std::size_t count = 0;
    for (const auto &word : best)
        count += mpn_add_japanese_word(session_.get(), word.first.c_str(), word.second.first.c_str(), word.second.second) != 0;
    japaneseRegistered_ = true;
    refreshCandidates();
    return count;
}
void Session::refreshCandidates() {
    chinese_.clear(); mixed_.clear();
    const auto count = mpn_candidate_count(session_.get());
    for (std::size_t i = 0; i < count; ++i) chinese_.push_back({mpn_candidate(session_.get(), i), i, 0, false});
    if (!pinyin_ || !japaneseRegistered_) return;
    const std::string raw = mpn_input(session_.get());
    const std::string whole = mpn_mixed_pinyin(session_.get(), raw.c_str(), simplified_ ? 1 : 0);
    std::size_t consumed = 0;
    const std::string prefix = mpn_mixed_pinyin_prefix(session_.get(), raw.c_str(), simplified_ ? 1 : 0, &consumed);
    auto add = [&](const std::string &text, std::size_t length) {
        if (text.empty() || length == 0 || length > raw.size()) return;
        const auto same = [&](const Candidate &candidate) { return candidate.text == text; };
        if (std::find_if(chinese_.begin(), chinese_.end(), same) == chinese_.end() &&
            std::find_if(mixed_.begin(), mixed_.end(), same) == mixed_.end())
            mixed_.push_back({text, 0, length, true});
    };
    add(whole, raw.size());
    add(prefix, consumed);
}
std::vector<Session::Candidate> Session::pageCandidates() const {
    std::vector<Candidate> result;
    const auto start = std::min(page_ * pageSize, chinese_.size());
    const auto end = std::min(start + pageSize, chinese_.size());
    result.insert(result.end(), chinese_.begin() + start, chinese_.begin() + end);
    // Preserve every Chinese slot. At most one Japanese-containing choice is
    // appended, and additional interpretations remain reachable on later pages.
    if (page_ < mixed_.size()) result.push_back(mixed_[page_]);
    return result;
}
bool Session::key(std::int32_t key) {
    if ((key == 0xff55 || key == 0xff56) && hasComposition()) {
        const auto pages = std::max((chinese_.size() + pageSize - 1) / pageSize, mixed_.size());
        if (key == 0xff55 && page_ > 0) --page_;
        if (key == 0xff56 && page_ + 1 < pages) ++page_;
        return true;
    }
    if (key == 0xff0d && hasComposition() && !pageCandidates().empty())
        return chooseOnPage(0);
    // The caller may process another key before reading a snapshot. Preserve
    // a previous partial commit even if the next key cancels the remainder.
    pending_ += mpn_committed(session_.get());
    mpn_clear_commit(session_.get());
    const bool handled = mpn_key(session_.get(), key) != 0;
    if (handled) { page_ = 0; refreshCandidates(); }
    return handled;
}
bool Session::hasComposition() const { return *mpn_input(session_.get()) != '\0'; }
std::string_view Session::input() const { return mpn_input(session_.get()); }
std::string Session::segmentContext() const { return mpn_segment_context(session_.get()); }
bool Session::canChooseOnPage(std::size_t index) const { return index < pageCandidates().size(); }
bool Session::chooseOnPage(std::size_t index) {
    const auto choices = pageCandidates();
    if (index >= choices.size()) return false;
    const auto &choice = choices[index];
    pending_ += mpn_committed(session_.get());
    mpn_clear_commit(session_.get());
    if (choice.japanese) {
        const std::string remaining = std::string(mpn_input(session_.get())).substr(choice.consumed);
        pending_ += choice.text;
        // A Japanese-containing selection is a context boundary: do not carry
        // earlier Chinese context across it when rebuilding the raw suffix.
        mpn_clear(session_.get());
        for (const auto c : remaining) if (!mpn_key(session_.get(), static_cast<unsigned char>(c)))
            throw std::runtime_error("Cannot restore unconverted reading");
    } else if (!mpn_choose(session_.get(), choice.index)) return false;
    page_ = 0; refreshCandidates();
    return true;
}
bool Session::commitLiteralWithSpace() {
    if (!hasComposition()) return false;
    pending_ += mpn_committed(session_.get());
    pending_ += mpn_input(session_.get());
    pending_ += ' ';
    mpn_clear(session_.get());
    page_ = 0; refreshCandidates();
    return true;
}
void Session::commit() {
    pending_ += mpn_committed(session_.get());
    mpn_clear_commit(session_.get());
    mpn_commit(session_.get());
    page_ = 0; refreshCandidates();
}
void Session::setSimplified(bool value) {
    simplified_ = value; mpn_set_simplified(session_.get(), value);
    page_ = 0; refreshCandidates();
}
void Session::clear() { mpn_clear(session_.get()); pending_.clear(); page_ = 0; refreshCandidates(); }
Snapshot Session::takeSnapshot() {
    Snapshot result;
    auto *s = session_.get();
    result.input = mpn_input(s); result.preedit = mpn_preedit(s);
    result.committed = std::move(pending_);
    pending_.clear();
    result.committed += mpn_committed(s);
    mpn_clear_commit(s);
    result.page = page_;
    const auto choices = pageCandidates();
    for (const auto &choice : choices) {
        result.candidates.push_back(choice.text);
        result.japaneseCandidates.push_back(choice.japanese);
    }
    const auto pages = std::max((chinese_.size() + pageSize - 1) / pageSize, mixed_.size());
    result.lastPage = page_ + 1 >= pages;
    return result;
}
}
