#include "MeowpuutCore.h"

#include <algorithm>
#include <array>
#include <charconv>
#include <cmath>
#include <memory>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace {
constexpr std::string_view tones = " 6347";
constexpr std::string_view keys = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'";
constexpr std::array<std::string_view, 43> symbols = {
    "ㄅ","ㄆ","ㄇ","ㄈ","ㄉ","ㄊ","ㄋ","ㄌ","ㄍ","ㄎ","ㄏ","ㄐ","ㄑ","ㄒ","ㄓ","ㄔ","ㄕ","ㄖ","ㄗ","ㄘ","ㄙ",
    "ㄧ","ㄨ","ㄩ","ㄚ","ㄛ","ㄜ","ㄝ","ㄞ","ㄟ","ㄠ","ㄡ","ㄢ","ㄣ","ㄤ","ㄥ","ㄦ","ˉ","ˊ","ˇ","ˋ","˙"," "
};
static_assert(keys.size() == symbols.size());
constexpr size_t page_size = 7;
constexpr size_t max_input = 256;
constexpr size_t candidate_limit = 256;
constexpr size_t beam = 5;

bool is_tone(char key, bool pinyin = false) { return pinyin ? key >= '1' && key <= '5' : tones.find(key) != std::string_view::npos; }

struct Word {
    std::string_view row;
    int weight;

    std::string_view reading() const { return row.substr(0, row.find('\t')); }
    std::string_view text(bool hans) const {
        const auto start = row.find('\t') + 1;
        const auto separator = row.find('\t', start);
        return hans ? row.substr(separator + 1) : row.substr(start, separator - start);
    }
};

struct Lexicon {
    // The table is immutable after loading. Row and index views point into it.
    std::string storage;
    std::vector<Word> words;
    struct Bucket { std::string_view key; size_t start; };
    std::vector<Bucket> buckets;
    std::vector<std::pair<std::string_view, int>> context;
    size_t longest = 0;
    bool pinyin = false;
    std::array<std::vector<size_t>, 128> initials;

    bool load(std::string_view data) {
        storage.assign(data);
        data = storage;
        pinyin = data.substr(0, std::string_view("MEOWPUUT-PINYIN-1\n").size()) == "MEOWPUUT-PINYIN-1\n";
        const std::string_view header = pinyin ? "MEOWPUUT-PINYIN-1\n" : "MEOWPUUT-ZHUYIN-1\n";
        if (data.substr(0, header.size()) != header) return false;
        data.remove_prefix(header.size());
        words.reserve(std::count(data.begin(), data.end(), '\n'));
        bool reading_context = false;
        while (!data.empty()) {
            const auto newline = data.find('\n');
            auto line = data.substr(0, newline);
            if (newline == data.npos) data = {};
            else data.remove_prefix(newline + 1);
            if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
            if (line.empty()) continue;
            if (line == "# context" && !reading_context) {
                reading_context = true;
                continue;
            }
            const auto first_tab = line.find('\t');
            if (first_tab == line.npos || first_tab == 0) return false;
            const auto bare = line.substr(0, first_tab);
            const auto weight_tab = line.rfind('\t');
            const auto number = line.substr(weight_tab + 1);
            int weight = 0;
            const auto end = number.data() + number.size();
            const auto parsed = std::from_chars(number.data(), end, weight);
            if (parsed.ec != std::errc{} || parsed.ptr != end || weight < 0) return false;

            if (reading_context) {
                if (weight_tab != first_tab || (!context.empty() && context.back().first >= bare)) return false;
                context.emplace_back(bare, weight);
                continue;
            }

            if (weight_tab == first_tab) return false;
            const auto row = line.substr(first_tab + 1, weight_tab - first_tab - 1);
            const Word word{row, weight};
            const auto reading = word.reading();
            const auto first_separator = row.find('\t');
            const auto second_separator = row.find('\t', first_separator + 1);
            if (reading.empty() || first_separator == row.npos || second_separator == row.npos ||
                first_separator + 1 == second_separator || second_separator + 1 == row.size() ||
                !is_tone(reading.back(), pinyin)) return false;
            size_t position = 0;
            bool has_sound = false;
            for (char key : reading) {
                if (is_tone(key, pinyin)) {
                    if (!has_sound) return false;
                    has_sound = false;
                } else {
                    if (position == bare.size() || key != bare[position++] ||
                        (pinyin ? key < 'a' || key > 'z' : keys.find(key) == keys.npos) || key == '\'') return false;
                    has_sound = true;
                }
            }
            if (position != bare.size() || bare.size() > max_input) return false;
            if (buckets.empty() || buckets.back().key != bare) {
                if (!buckets.empty() && buckets.back().key > bare) return false;
                buckets.push_back({bare, words.size()});
                longest = std::max(longest, bare.size());
            } else if (words.back().weight < weight) return false;
            words.push_back(word);
        }
        {
            for (size_t i = 0; i < words.size(); ++i) {
                const auto reading = words[i].reading();
                if (words[i].text(false).size() <= 4 && static_cast<unsigned char>(reading.front()) < initials.size())
                    initials[static_cast<unsigned char>(reading.front())].push_back(i);
            }
            for (auto &items : initials) {
                std::stable_sort(items.begin(), items.end(), [&](size_t a, size_t b) { return words[a].weight > words[b].weight; });
                if (items.size() > 64) items.resize(64);
            }
        }
        return !words.empty();
    }

    double join_bonus(std::string_view left, std::string_view right) const {
        if (context.empty() || right.empty()) return 0;
        // UTF-8 character boundaries for up to four characters on each side.
        std::array<size_t, 4> starts{}, ends{};
        size_t left_count = 0, right_count = 0;
        const auto is_start = [](char byte) { return (static_cast<unsigned char>(byte) & 0xC0) != 0x80; };
        for (size_t i = left.size(); i > 0 && left_count < starts.size();) {
            if (is_start(left[--i])) starts[left_count++] = i;
        }
        for (size_t i = 1; i <= right.size() && right_count < ends.size(); ++i) {
            if (i == right.size() || is_start(right[i])) ends[right_count++] = i;
        }
        if (left_count == 0 || right_count == 0) return 0;
        const size_t offset = starts[left_count - 1];
        std::string joined(left.substr(offset));
        joined.append(right.substr(0, ends[right_count - 1]));
        int frequency = 0;
        for (size_t i = 0; i < left_count; ++i) {
            const size_t begin = starts[i] - offset;
            for (size_t j = 0; j < right_count && i + j < 4; ++j) {
                const auto phrase = std::string_view(joined).substr(begin, left.size() - starts[i] + ends[j]);
                const auto entry = std::lower_bound(context.begin(), context.end(), phrase,
                    [](const auto &item, std::string_view value) { return item.first < value; });
                if (entry != context.end() && entry->first == phrase) frequency = std::max(frequency, entry->second);
            }
        }
        return std::min(3.0, 0.4 * std::log1p(frequency));
    }

    std::pair<size_t, size_t> range(std::string_view key) const {
        const auto bucket = std::lower_bound(buckets.begin(), buckets.end(), key,
                                             [](const Bucket &item, std::string_view reading) {
                                                 return item.key < reading;
                                             });
        if (bucket == buckets.end() || bucket->key != key) return {0, 0};
        const auto next = bucket + 1;
        return {bucket->start, next == buckets.end() ? words.size() : next->start};
    }
};

struct Reading {
    std::string sounds;
    std::array<char, max_input + 1> boundaries{};
    std::array<size_t, max_input + 1> boundary_count{};

    bool pinyin;
    explicit Reading(std::string_view input, bool latin) : pinyin(latin) {
        for (char key : input) {
            if (is_tone(key, pinyin) || key == '\'') boundaries[sounds.size()] = key;
            else sounds += key;
        }
        for (size_t i = 1; i <= sounds.size(); ++i) {
            boundary_count[i] = boundary_count[i - 1] + (boundaries[i] != 0);
        }
    }

    bool matches(const Word &word, size_t start, size_t end) const {
        const size_t required = boundary_count[end] - boundary_count[start];
        if (required == 0) return true;
        size_t matched = 0;
        for (char key : word.reading()) {
            if (!is_tone(key, pinyin)) { ++start; continue; }
            const char tone = boundaries[start];
            if (tone && tone != key && tone != '\'') return false;
            matched += tone != 0;
        }
        return matched == required;
    }
};

struct Fallback { std::string text; size_t consumed; double score; };
std::vector<Fallback> fallback(const Lexicon &lexicon, std::string_view input, bool hans, bool allowSingle = true) {
    std::vector<Fallback> matches;
    const Reading reading(input, lexicon.pinyin);
    const auto &sounds = reading.sounds;
    auto collect = [&](std::string_view key, double penalty) {
        const auto range = lexicon.range(key);
        for (size_t i = range.first; i < std::min(range.second, range.first + 7); ++i) {
            const auto &word = lexicon.words[i];
            matches.push_back({std::string(word.text(hans)), input.size(), word.weight - penalty});
        }
    };
    if (sounds.size() == 1 && static_cast<unsigned char>(sounds[0]) < lexicon.initials.size()) {
        for (size_t i : lexicon.initials[static_cast<unsigned char>(sounds[0])]) {
            const auto &word = lexicon.words[i];
            matches.push_back({std::string(word.text(hans)), input.size(), double(word.weight)});
        }
    }
    if (lexicon.pinyin && sounds.size() >= 2 && sounds.size() <= 32 && input == sounds) {
        // Bounded prefix completion: binary search, then at most 256 dictionary buckets.
        auto first = std::lower_bound(lexicon.buckets.begin(), lexicon.buckets.end(), sounds,
            [](const Lexicon::Bucket &bucket, std::string_view key) { return bucket.key < key; });
        size_t scanned = 0;
        for (auto it = first; it != lexicon.buckets.end() && scanned++ < 256; ++it) {
            if (it->key.substr(0, sounds.size()) != sounds) break;
            if (it->key.size() - sounds.size() <= 6) collect(it->key, 1200.0 * (it->key.size() - sounds.size()));
        }
        // One local correction only; no full dictionary scan or arbitrary multi-edit guesses.
        if (sounds.size() >= 4) {
            for (size_t i = 0; i < sounds.size(); ++i) {
                if (i + 1 < sounds.size()) { auto key = sounds; std::swap(key[i], key[i + 1]); collect(key, 800); }
                if (i && sounds[i] == sounds[i - 1]) { auto key = sounds; key.erase(i, 1); collect(key, 800); }
                std::string_view neighbors;
                switch (sounds[i]) {
                    case 'p': neighbors = "o"; break; case 'o': neighbors = "ip"; break;
                    case 'i': neighbors = "uo"; break; case 'u': neighbors = "yi"; break;
                    case 'n': neighbors = "bm"; break; case 'm': neighbors = "n"; break;
                    case 'g': neighbors = "fh"; break; case 'h': neighbors = "gj"; break;
                    case 'a': neighbors = "s"; break; case 's': neighbors = "ad"; break;
                    case 'e': neighbors = "wr"; break; case 'r': neighbors = "et"; break;
                    default: break;
                }
                for (char ch : neighbors) { auto key = sounds; key[i] = ch; collect(key, 1200); }
            }
            if (sounds.back() == 'n') collect(sounds + "g", 900);
            if (sounds.size() > 2 && sounds.substr(sounds.size() - 2) == "ng") collect(sounds.substr(0, sounds.size() - 1), 900);
        }
    }
    if (matches.empty() && allowSingle) {
        // Offer the first character only, retaining every unconverted key after selection.
        for (size_t count = std::min(input.size(), size_t(7)); count > 0; --count) {
            const Reading prefix(input.substr(0, count), lexicon.pinyin);
            if (prefix.sounds.empty()) continue;
            const auto range = lexicon.range(prefix.sounds);
            for (size_t i = range.first; i < range.second && matches.size() < 28; ++i) {
                const auto &word = lexicon.words[i];
                const auto text = word.text(hans);
                const auto characters = std::count_if(text.begin(), text.end(), [](unsigned char ch) { return (ch & 0xc0) != 0x80; });
                if (characters == 1 && prefix.matches(word, 0, prefix.sounds.size()))
                    matches.push_back({std::string(text), count, static_cast<double>(word.weight)});
            }
            if (!matches.empty()) break;
        }
    }
    std::stable_sort(matches.begin(), matches.end(), [](const Fallback &a, const Fallback &b) { return a.score > b.score; });
    std::unordered_set<std::string> seen;
    std::vector<Fallback> result;
    for (auto &match : matches) {
        if (seen.insert(match.text).second) result.push_back(std::move(match));
        if (result.size() == 28) break;
    }
    return result;
}


std::vector<std::string> decode(const Lexicon &lexicon, std::string_view input, bool hans) {
    const Reading reading(input, lexicon.pinyin);
    const size_t length = reading.sounds.size();
    if (length == 0) return {};

    std::vector<std::string> results;
    std::unordered_set<std::string> seen;
    auto append = [&](std::string_view text) {
        if (seen.emplace(text).second) results.emplace_back(text);
    };
    const auto whole = lexicon.range(reading.sounds);
    for (size_t index = whole.first; index < whole.second; ++index) {
        const auto &word = lexicon.words[index];
        if (reading.matches(word, 0, length)) append(word.text(hans));
        if (results.size() == candidate_limit) return results;
    }

    // Whole dictionary words stay first; known completions/corrections precede
    // speculative chains such as interpreting a mistyped ending as an abbreviation.
    if (results.empty() && lexicon.pinyin) {
        for (const auto &item : fallback(lexicon, input, hans, false)) append(item.text);
    }

    struct Path { std::string text, traditional; double score; };
    std::vector<std::vector<Path>> suffixes(length + 1);
    suffixes[length].push_back({"", "", 0});
    for (size_t start = length; start-- > 0;) {
        std::vector<Path> paths;
        for (size_t end = start + 1; end <= std::min(length, start + lexicon.longest); ++end) {
            if (suffixes[end].empty() || (start == 0 && end == length)) continue;
            const auto options = lexicon.range(std::string_view(reading.sounds).substr(start, end - start));
            size_t used = 0;
            for (size_t index = options.first; index < options.second; ++index) {
                const auto &word = lexicon.words[index];
                if (!reading.matches(word, start, end)) continue;
                // Compiler weights are already log-frequency priors. Logging them again
                // erases the distinction between common phrases and rare fragments.
                const double score = static_cast<double>(word.weight) / 700.0 - 19.0;
                for (const auto &suffix : suffixes[end]) {
                    const auto traditional = word.text(false);
                    const auto &right = hans ? suffix.traditional : suffix.text;
                    const double bonus = lexicon.join_bonus(traditional, right);
                    paths.push_back({
                        std::string(word.text(hans)) + suffix.text,
                        hans ? std::string(traditional) + right : std::string(),
                        score + suffix.score + bonus,
                    });
                }
                if (++used == beam) break;
            }
        }
        std::sort(paths.begin(), paths.end(), [](const Path &a, const Path &b) {
            return a.score == b.score ? a.text < b.text : a.score > b.score;
        });
        std::unordered_set<std::string> unique;
        for (auto &path : paths) {
            if (unique.insert(path.text).second) suffixes[start].push_back(std::move(path));
            if (suffixes[start].size() == beam) break;
        }
    }
    for (const auto &path : suffixes[0]) {
        append(path.text);
        if (results.size() == candidate_limit) break;
    }
    return results;
}
} // namespace

struct MPNLexicon { std::shared_ptr<const Lexicon> value; };
struct MPNSession {
    std::shared_ptr<const Lexicon> lexicon;
    std::string input, preedit, committed;
    std::vector<std::string> candidates;
    std::vector<size_t> consumed;
    size_t page = 0;
    bool hans = false;

    void reset() {
        input.clear();
        preedit.clear();
        candidates.clear();
        consumed.clear();
        page = 0;
    }

    void refresh() {
        std::string marked;
        if (lexicon->pinyin) marked = input;
        else for (char key : input) marked += symbols[keys.find(key)];
        auto ranked = decode(*lexicon, input, hans);
        consumed.assign(ranked.size(), input.size());
        if (ranked.empty()) {
            for (auto &item : fallback(*lexicon, input, hans)) {
                ranked.push_back(std::move(item.text));
                consumed.push_back(item.consumed);
            }
        }
        preedit.swap(marked);
        candidates.swap(ranked);
        page = 0;
    }
};

MPNLexicon *mpn_lexicon_create(const char *data, size_t size) {
    if (!data || !size) return nullptr;
    try {
        auto lexicon = std::make_shared<Lexicon>();
        if (!lexicon->load({data, size})) return nullptr;
        return new MPNLexicon{std::move(lexicon)};
    } catch (...) { return nullptr; }
}
void mpn_lexicon_destroy(MPNLexicon *lexicon) { delete lexicon; }

MPNSession *mpn_session_create(const MPNLexicon *lexicon) {
    if (!lexicon) return nullptr;
    try {
        auto session = std::make_unique<MPNSession>();
        session->lexicon = lexicon->value;
        return session.release();
    } catch (...) { return nullptr; }
}
void mpn_session_destroy(MPNSession *session) { delete session; }

void mpn_set_simplified(MPNSession *session, int simplified) {
    if (session->hans == (simplified != 0)) return;
    session->hans = simplified != 0;
    try { session->refresh(); } catch (...) { session->reset(); }
}
void mpn_clear(MPNSession *session) {
    session->reset();
    session->committed.clear();
}
void mpn_commit(MPNSession *session) {
    if (!session->candidates.empty()) { mpn_choose(session, session->page * page_size); return; }
    session->committed.swap(session->input);
    session->reset();
}
int mpn_choose(MPNSession *session, size_t index) {
    if (index >= session->candidates.size()) return 0;
    auto text = session->candidates[index];
    auto remaining = session->input.substr(session->consumed[index]);
    session->reset();
    session->input = std::move(remaining);
    session->refresh();
    session->committed = std::move(text);
    return 1;
}

int mpn_key(MPNSession *session, int32_t key) {
    try {
        auto &input = session->input;
        if (key == 0xff55 || key == 0xff56) {
            if (input.empty()) return 0;
            if (key == 0xff55 && session->page > 0) --session->page;
            if (key == 0xff56 && (session->page + 1) * page_size < session->candidates.size()) ++session->page;
            return 1;
        }
        if (key == 0xff1b && !input.empty()) { mpn_clear(session); return 1; }
        if (key == 0xff08 && !input.empty()) {
            input.pop_back();
            session->refresh();
            return 1;
        }
        if (key == 0xff0d && !input.empty()) { mpn_commit(session); return 1; }
        if (key < 32 || key > 126) return 0;
        const auto ch = static_cast<char>(key);
        if (session->lexicon->pinyin) {
            if (ch == ' ' && !input.empty()) { mpn_commit(session); return 1; }
            const bool boundary = is_tone(ch, true) || ch == '\'';
            if (!(ch >= 'a' && ch <= 'z') && !boundary) return 0;
            if (boundary && (input.empty() || is_tone(input.back(), true) || input.back() == '\'')) return 0;
            if (input.size() == max_input) return 1;
            input += ch; session->refresh(); return 1;
        }
        const bool boundary = is_tone(ch) || ch == '\'';
        if (keys.find(ch) == keys.npos || (boundary && input.empty())) return 0;
        if (boundary && (is_tone(input.back()) || input.back() == '\'')) {
            if (ch == ' ') mpn_commit(session);
            return 1;
        }
        if (input.size() == max_input) return 1;
        input += ch;
        session->refresh();
        return 1;
    } catch (...) {
        session->reset();
        return 0;
    }
}

const char *mpn_input(const MPNSession *session) { return session->input.c_str(); }
const char *mpn_preedit(const MPNSession *session) { return session->preedit.c_str(); }
const char *mpn_committed(const MPNSession *session) { return session->committed.c_str(); }
void mpn_clear_commit(MPNSession *session) { session->committed.clear(); }
size_t mpn_candidate_count(const MPNSession *session) { return session->candidates.size(); }
const char *mpn_candidate(const MPNSession *session, size_t index) {
    return index < session->candidates.size() ? session->candidates[index].c_str() : "";
}
size_t mpn_page(const MPNSession *session) { return session->page; }
