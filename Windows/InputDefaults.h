#pragma once

#include <cctype>
#include <string>
#include <vector>

// What a fresh install starts with, from the system region and language list.
// Pure C++17 with no Windows headers, so it is tested on any host. The region
// is only a local heuristic: callers read it, pass it here and drop it; only
// the resulting script is stored.
namespace meowpuut {

enum class StartMode { Zhuyin, Pinyin };
struct Start {
    StartMode mode = StartMode::Pinyin;
    bool simplified = true;
    bool operator==(const Start &o) const { return mode == o.mode && simplified == o.simplified; }
};

namespace detail {

enum class ChineseScript { None, Hans, Hant };
struct Tag { std::string language, script, region; };

inline std::string lower(std::string s) {
    for (auto &c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}
inline std::string upper(std::string s) {
    for (auto &c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    return s;
}

// "zh-Hant-TW", "zh_TW", "ZH-hans": language, optional four-letter script, optional two-letter region.
inline Tag parseTag(const std::string &text) {
    Tag tag;
    std::vector<std::string> parts;
    std::string current;
    for (const char c : text) {
        if (c == '-' || c == '_') { parts.push_back(current); current.clear(); }
        else current += c;
    }
    parts.push_back(current);
    tag.language = lower(parts[0]);
    for (std::size_t i = 1; i < parts.size(); ++i) {
        const auto &p = parts[i];
        if (p.size() == 4 && tag.script.empty() && tag.region.empty()) {
            tag.script = lower(p);
        } else if (p.size() == 2 && tag.region.empty()) {
            tag.region = upper(p);
        }
    }
    return tag;
}

inline bool isSimplifiedRegion(const std::string &r) { return r == "CN" || r == "SG" || r == "MY"; }
inline bool isTraditionalRegion(const std::string &r) { return r == "HK" || r == "MO" || r == "TW"; }

// The script a Chinese tag names; a bare region implies the usual one.
inline ChineseScript scriptOf(const Tag &tag) {
    if (tag.script == "hans") return ChineseScript::Hans;
    if (tag.script == "hant") return ChineseScript::Hant;
    if (isSimplifiedRegion(tag.region)) return ChineseScript::Hans;
    if (isTraditionalRegion(tag.region)) return ChineseScript::Hant;
    return ChineseScript::None;
}

} // namespace detail

// [region] is an ISO 3166 code in any case (may be empty); [languages] are BCP 47 tags, preferred first.
inline Start chooseStart(const std::string &region, const std::vector<std::string> &languages) {
    using namespace detail;
    const std::string code = upper(region);
    std::vector<ChineseScript> scripts;
    bool taiwanLanguage = false;
    for (const auto &text : languages) {
        const Tag tag = parseTag(text);
        if (tag.language != "zh") continue;
        const auto script = scriptOf(tag);
        scripts.push_back(script);
        if (tag.region == "TW" && script != ChineseScript::Hans) taiwanLanguage = true;
    }
    const auto hasHans = [&] { for (auto s : scripts) if (s == ChineseScript::Hans) return true; return false; };
    if (code == "TW" || taiwanLanguage) return {StartMode::Zhuyin, false};
    if (isSimplifiedRegion(code) || hasHans()) return {StartMode::Pinyin, true};
    if (code == "HK" || code == "MO") return {StartMode::Pinyin, false};
    for (auto s : scripts)
        if (s != ChineseScript::None) return {StartMode::Pinyin, s != ChineseScript::Hant};
    return {StartMode::Pinyin, true};
}

// What the first run of this version stores. Settings from an earlier version
// mean an existing person: mixed Japanese stays on and nothing else changes.
// A fresh install gets it off and, with no script stored yet, the start for its
// language and region.
struct FirstRun {
    bool mixedJapanese = false;
    bool storeScript = false;
    Start start;
};
inline FirstRun firstRun(bool existingSettings, bool hasScript, const std::string &region,
                         const std::vector<std::string> &languages) {
    FirstRun plan;
    if (existingSettings) { plan.mixedJapanese = true; return plan; }
    plan.mixedJapanese = false;
    if (!hasScript) { plan.storeScript = true; plan.start = chooseStart(region, languages); }
    return plan;
}

} // namespace meowpuut
