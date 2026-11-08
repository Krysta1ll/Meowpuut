#pragma once

#include "../Core/MeowpuutCore.h"
#include <algorithm>
#include <cstdlib>
#include <istream>
#include <iterator>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

struct JapaneseWord {
    std::string reading, text;
    int weight;
};

inline std::vector<JapaneseWord> readJapaneseWords(std::istream &file) {
    std::vector<JapaneseWord> words;
    for (std::string line; std::getline(file, line);) {
        if (line.empty() || line.front() == '#') continue;
        const auto first = line.find('|');
        if (first == line.npos) continue;
        const auto second = line.find('|', first + 1);
        if (second == line.npos) continue;
        const auto text = line.substr(first + 1, second - first - 1);
        const int weight = std::atoi(line.c_str() + second + 1);
        for (size_t begin = 0; begin < first;) {
            const auto comma = std::min(line.find(',', begin), first);
            words.push_back({line.substr(begin, comma - begin), text, weight});
            begin = comma + 1;
        }
    }
    std::stable_sort(words.begin(), words.end(), [](const auto &a, const auto &b) {
        return a.weight > b.weight;
    });
    return words;
}

using ChineseEvidence = std::map<std::string, int>;

// Query both valid spellings without treating an explicit lu'e as lüe.
inline std::vector<std::string> pinyinEvidenceReadings(const std::string &input) {
    auto canonical = input;
    for (size_t i = 0; i + 2 < canonical.size(); ++i)
        if ((canonical[i] == 'l' || canonical[i] == 'n') && canonical[i + 1] == 'u' && canonical[i + 2] == 'e')
            canonical[i + 1] = 'v';
    auto withoutSeparators = [](std::string reading) {
        reading.erase(std::remove_if(reading.begin(), reading.end(), [](char c) {
            return c == '\'' || (c >= '1' && c <= '5');
        }), reading.end());
        return reading;
    };
    auto original = withoutSeparators(input);
    canonical = withoutSeparators(std::move(canonical));
    if (canonical == original) return {std::move(original)};
    return {std::move(original), std::move(canonical)};
}

// Pinyin evidence is indexed without delimiters; verify explicit boundaries
// and tones against the original reading before treating it as exact.
inline bool pinyinEvidenceMatches(const std::string &input, const std::string &toned) {
    if (input.find_first_of("'12345") == std::string::npos) return true;
    std::map<size_t, char> required;
    size_t position = 0;
    for (const char c : input) {
        if (c >= 'a' && c <= 'z') ++position;
        else if (c == '\'' || (c >= '1' && c <= '5')) {
            if (!position || !required.emplace(position, c).second) return false;
        } else return false;
    }
    if (required.empty()) return true;
    position = 0;
    for (const char c : toned) {
        if (c >= 'a' && c <= 'z') ++position;
        else if (c >= '1' && c <= '5') {
            const auto item = required.find(position);
            if (item != required.end()) {
                if (item->second != '\'' && item->second != c) return false;
                required.erase(item);
            }
        } else return false;
    }
    return required.empty();
}

struct WindowsCandidate {
    std::string text;
    std::optional<size_t> coreIndex;
    bool japanese = false;
    bool strong = false;
};

// Mirrors the macOS mixed-reading gate: one exact Japanese word surrounded by
// complete, high-confidence Chinese dictionary readings.
template<class Lookup>
std::string mixedReading(const std::string &input, const std::vector<JapaneseWord> &dictionary, Lookup lookup) {
    if (input.size() < 5 || input.size() > 24 ||
        !std::all_of(input.begin(), input.end(), [](char c) { return c >= 'a' && c <= 'z'; }))
        return {};
    std::unordered_map<std::string, const JapaneseWord *> japanese;
    for (const auto &word : dictionary) japanese.emplace(word.reading, &word);
    if (japanese.count(input)) return {};

    using Evidence = std::optional<std::pair<std::string, int>>;
    std::unordered_map<std::string, Evidence> cache;
    auto bestChinese = [&](const std::string &reading) -> Evidence {
        if (reading.empty()) return std::make_pair(std::string(), 0);
        const auto found = cache.find(reading);
        if (found != cache.end()) return found->second;
        Evidence best;
        for (const auto &[text, weight] : lookup(reading)) {
            if (weight >= 2000 && (!best || weight > best->second))
                best = std::make_pair(text, weight);
        }
        cache.emplace(reading, best);
        return best;
    };

    int bestScore = -1;
    std::string mixed;
    for (size_t start = 0; start + 3 <= input.size(); ++start) {
        for (size_t end = start + 3; end <= std::min(input.size(), start + 10); ++end) {
            const auto match = japanese.find(input.substr(start, end - start));
            if (match == japanese.end() || match->second->weight < 4000) continue;
            const auto before = bestChinese(input.substr(0, start));
            const auto after = bestChinese(input.substr(end));
            if (!before || !after) continue;
            const int score = before->second + match->second->weight + after->second;
            if (score > bestScore) {
                bestScore = score;
                mixed = before->first + match->second->text + after->first;
            }
        }
    }
    return mixed;
}

inline std::vector<WindowsCandidate> interleaveCandidates(std::vector<WindowsCandidate> choices) {
    if (std::none_of(choices.begin(), choices.end(), [](const auto &choice) { return choice.japanese; }))
        return choices;
    std::vector<WindowsCandidate> strong, weak, japanese;
    for (auto &choice : choices)
        (choice.japanese ? japanese : choice.strong ? strong : weak).push_back(std::move(choice));
    const size_t lead = strong.empty() ? std::min(size_t(2), weak.size()) : std::min(size_t(6), strong.size());
    strong.insert(strong.end(), std::make_move_iterator(weak.begin()), std::make_move_iterator(weak.end()));
    std::vector<WindowsCandidate> result;
    result.insert(result.end(), std::make_move_iterator(strong.begin()), std::make_move_iterator(strong.begin() + lead));
    for (size_t i = 0; i < japanese.size() || i + lead < strong.size(); ++i) {
        if (i < japanese.size()) result.push_back(std::move(japanese[i]));
        if (i + lead < strong.size()) result.push_back(std::move(strong[i + lead]));
    }
    return result;
}

template<class Lookup>
std::vector<WindowsCandidate> windowsCandidates(MPNSession *session, const std::vector<JapaneseWord> &dictionary,
                                                 Lookup lookup, bool chineseLocked) {
    const std::string input = mpn_input(session);
    if (input.empty()) return {};
    std::vector<WindowsCandidate> choices;
    std::set<std::pair<std::string, size_t>> seen;
    const auto evidence = chineseLocked ? ChineseEvidence{} : lookup(input);
    auto append = [&](std::string text, std::optional<size_t> index, bool japanese) {
        const size_t consumed = index ? mpn_candidate_consumed(session, *index) : input.size();
        if (text.empty() || !seen.emplace(text, consumed).second)
            return;
        const auto found = evidence.find(text);
        const bool strong = found != evidence.end() && found->second >= 2000;
        choices.push_back({std::move(text), index, japanese && found == evidence.end(), strong});
    };

    std::vector<size_t> chinese;
    std::set<std::pair<std::string, size_t>> nativeSelections;
    for (size_t i = 0; i < mpn_candidate_count(session); ++i) {
        chinese.push_back(i);
        nativeSelections.emplace(mpn_candidate(session, i), mpn_candidate_consumed(session, i));
    }
    std::stable_partition(chinese.begin(), chinese.end(), [&](size_t i) {
        return evidence.count(mpn_candidate(session, i)) != 0;
    });
    if (chineseLocked) {
        for (auto i : chinese) append(mpn_candidate(session, i), i, false);
        return choices;
    }

    std::vector<std::pair<std::string, int>> additional;
    for (const auto &entry : evidence) {
        if (!nativeSelections.count({entry.first, input.size()})) additional.push_back(entry);
    }
    std::stable_sort(additional.begin(), additional.end(), [](const auto &a, const auto &b) {
        return a.second > b.second;
    });
    const bool supplementFirst = chinese.empty() || !evidence.count(mpn_candidate(session, chinese.front()));
    auto supplement = [&] {
        for (size_t i = 0; i < std::min(size_t(7), additional.size()); ++i) append(additional[i].first, {}, false);
    };
    if (supplementFirst) supplement();
    for (size_t i = 0; i < std::min(size_t(2), chinese.size()); ++i)
        append(mpn_candidate(session, chinese[i]), chinese[i], false);
    append(mixedReading(input, dictionary, lookup), {}, true);
    const auto firstJapanese = std::find_if(dictionary.begin(), dictionary.end(), [&](const auto &word) {
        return word.reading == input;
    });
    if (firstJapanese != dictionary.end()) append(firstJapanese->text, {}, true);
    if (input.size() <= 3) append(input, {}, false);
    if (chinese.size() > 2) append(mpn_candidate(session, chinese[2]), chinese[2], false);
    if (!supplementFirst) supplement();
    for (size_t i = 3; i < chinese.size(); ++i) append(mpn_candidate(session, chinese[i]), chinese[i], false);
    for (size_t i = 7; i < additional.size(); ++i) append(additional[i].first, {}, false);
    for (const auto &word : dictionary)
        if (word.reading == input) append(word.text, {}, true);
    if (choices.empty()) append(input, {}, false);
    return interleaveCandidates(std::move(choices));
}
