#include "Festival.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <utility>

namespace meowpuut::ui {
namespace {

// A small JSON reader: objects, arrays, strings, numbers, true/false/null.
struct Json {
    enum class Type { Null, Bool, Number, String, Array, Object } type = Type::Null;
    bool boolean = false;
    double number = 0;
    std::string string;
    std::vector<Json> array;
    std::vector<std::pair<std::string, Json>> object;

    const Json *get(std::string_view key) const {
        if (type != Type::Object) return nullptr;
        for (const auto &entry : object)
            if (entry.first == key) return &entry.second;
        return nullptr;
    }
    std::string str(std::string_view key) const {
        const auto *v = get(key);
        return v && v->type == Type::String ? v->string : std::string();
    }
};

class Parser {
public:
    explicit Parser(std::string_view text) : s_(text) {}
    bool parse(Json &out) {
        skip();
        if (!value(out, 0)) return false;
        skip();
        return pos_ == s_.size();
    }
    std::size_t position() const { return pos_; }

private:
    std::string_view s_;
    std::size_t pos_ = 0;

    void skip() {
        while (pos_ < s_.size() && (s_[pos_] == ' ' || s_[pos_] == '\n' || s_[pos_] == '\r' || s_[pos_] == '\t')) ++pos_;
    }
    bool literal(std::string_view word) {
        if (s_.substr(pos_, word.size()) != word) return false;
        pos_ += word.size();
        return true;
    }
    bool value(Json &out, int depth) {
        if (depth > 32 || pos_ >= s_.size()) return false;
        switch (s_[pos_]) {
        case '{': return object(out, depth);
        case '[': return array(out, depth);
        case '"': out.type = Json::Type::String; return string(out.string);
        case 't': out.type = Json::Type::Bool; out.boolean = true; return literal("true");
        case 'f': out.type = Json::Type::Bool; out.boolean = false; return literal("false");
        case 'n': out.type = Json::Type::Null; return literal("null");
        default: return numberValue(out);
        }
    }
    bool object(Json &out, int depth) {
        out.type = Json::Type::Object;
        ++pos_;
        skip();
        if (pos_ < s_.size() && s_[pos_] == '}') { ++pos_; return true; }
        while (true) {
            skip();
            std::string key;
            if (pos_ >= s_.size() || s_[pos_] != '"' || !string(key)) return false;
            skip();
            if (pos_ >= s_.size() || s_[pos_] != ':') return false;
            ++pos_;
            skip();
            Json item;
            if (!value(item, depth + 1)) return false;
            out.object.emplace_back(std::move(key), std::move(item));
            skip();
            if (pos_ < s_.size() && s_[pos_] == ',') { ++pos_; continue; }
            if (pos_ < s_.size() && s_[pos_] == '}') { ++pos_; return true; }
            return false;
        }
    }
    bool array(Json &out, int depth) {
        out.type = Json::Type::Array;
        ++pos_;
        skip();
        if (pos_ < s_.size() && s_[pos_] == ']') { ++pos_; return true; }
        while (true) {
            skip();
            Json item;
            if (!value(item, depth + 1)) return false;
            out.array.push_back(std::move(item));
            skip();
            if (pos_ < s_.size() && s_[pos_] == ',') { ++pos_; continue; }
            if (pos_ < s_.size() && s_[pos_] == ']') { ++pos_; return true; }
            return false;
        }
    }
    static void appendUtf8(std::string &out, unsigned cp) {
        if (cp < 0x80) out += static_cast<char>(cp);
        else if (cp < 0x800) { out += static_cast<char>(0xC0 | (cp >> 6)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    bool hex4(unsigned &out) {
        if (pos_ + 4 > s_.size()) return false;
        out = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = s_[pos_++];
            out <<= 4;
            if (c >= '0' && c <= '9') out |= static_cast<unsigned>(c - '0');
            else if (c >= 'a' && c <= 'f') out |= static_cast<unsigned>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') out |= static_cast<unsigned>(c - 'A' + 10);
            else return false;
        }
        return true;
    }
    bool string(std::string &out) {
        ++pos_; // opening quote
        while (pos_ < s_.size()) {
            const char c = s_[pos_++];
            if (c == '"') return true;
            if (static_cast<unsigned char>(c) < 0x20) return false;
            if (c != '\\') { out += c; continue; }
            if (pos_ >= s_.size()) return false;
            const char e = s_[pos_++];
            switch (e) {
            case '"': out += '"'; break;
            case '\\': out += '\\'; break;
            case '/': out += '/'; break;
            case 'b': out += '\b'; break;
            case 'f': out += '\f'; break;
            case 'n': out += '\n'; break;
            case 'r': out += '\r'; break;
            case 't': out += '\t'; break;
            case 'u': {
                unsigned cp = 0;
                if (!hex4(cp)) return false;
                if (cp >= 0xD800 && cp <= 0xDBFF) {
                    unsigned low = 0;
                    if (pos_ + 2 > s_.size() || s_[pos_] != '\\' || s_[pos_ + 1] != 'u') return false;
                    pos_ += 2;
                    if (!hex4(low) || low < 0xDC00 || low > 0xDFFF) return false;
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                    return false;
                }
                appendUtf8(out, cp);
                break;
            }
            default: return false;
            }
        }
        return false;
    }
    bool numberValue(Json &out) {
        const std::size_t start = pos_;
        if (pos_ < s_.size() && (s_[pos_] == '-' || s_[pos_] == '+')) ++pos_;
        while (pos_ < s_.size() && (std::isdigit(static_cast<unsigned char>(s_[pos_])) || s_[pos_] == '.' ||
                                    s_[pos_] == 'e' || s_[pos_] == 'E' || s_[pos_] == '-' || s_[pos_] == '+'))
            ++pos_;
        if (pos_ == start) return false;
        const std::string digits(s_.substr(start, pos_ - start));
        char *end = nullptr;
        out.type = Json::Type::Number;
        out.number = std::strtod(digits.c_str(), &end);
        return end && *end == '\0';
    }
};

// 1 to 64 ASCII letters, digits, '_' or '-': safe as a file name.
bool isName(const std::string &text) {
    if (text.empty() || text.size() > 64) return false;
    for (char c : text)
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) return false;
    return true;
}

// The readers below return false when a field is missing or of the wrong kind.
bool readString(const Json &node, std::string_view key, std::string &out) {
    const auto *v = node.get(key);
    if (!v || v->type != Json::Type::String) return false;
    out = v->string;
    return true;
}

bool readNumbers(const Json &node, std::string_view key, std::size_t count, float *out) {
    const auto *v = node.get(key);
    if (!v || v->type != Json::Type::Array || v->array.size() != count) return false;
    for (std::size_t i = 0; i < count; ++i) {
        if (v->array[i].type != Json::Type::Number) return false;
        out[i] = static_cast<float>(v->array[i].number);
    }
    return true;
}

bool readInsets(const Json &node, std::string_view key, FestivalInsets &out) {
    float v[4];
    if (!readNumbers(node, key, 4, v)) return false;
    out = {v[0], v[1], v[2], v[3]};
    return true;
}

bool readStrings(const Json &node, std::string_view key, std::vector<std::string> &out) {
    const auto *v = node.get(key);
    if (!v || v->type != Json::Type::Array) return false;
    for (const auto &item : v->array) {
        if (item.type != Json::Type::String) return false;
        out.push_back(item.string);
    }
    return true;
}

bool readSprite(const Json *node, FestivalSprite &out) {
    float size[2], anchor[2];
    if (!node || !readString(*node, "image", out.image) || !readNumbers(*node, "size", 2, size) ||
        !readNumbers(*node, "anchor", 2, anchor))
        return false;
    out.width = size[0], out.height = size[1], out.anchorX = anchor[0], out.anchorY = anchor[1];
    return true;
}

bool readDays(const Json &node, FestivalDays &out) {
    if (node.type != Json::Type::Array || node.array.size() != 3) return false;
    LocalDate d[3];
    for (int i = 0; i < 3; ++i)
        if (node.array[i].type != Json::Type::String || !parseLocalDate(node.array[i].string, d[i])) return false;
    if (d[1].key() < d[0].key() || d[2].key() < d[1].key()) return false;
    out = {d[0], d[1], d[2]};
    return true;
}

bool readTheme(const Json &node, FestivalTheme &t) {
    const auto *names = node.get("names"), *trim = node.get("trim"), *colors = node.get("colors"), *days = node.get("days");
    if (node.type != Json::Type::Object || !names || !trim || !colors || !days || days->type != Json::Type::Array) return false;
    float size[2];
    if (!readString(node, "id", t.id) || !readString(*names, "hant", t.hant) || !readString(*names, "hans", t.hans) ||
        !readString(*names, "ja", t.ja) || !readString(*names, "en", t.en) || !readSprite(node.get("mimimo"), t.mimimo) ||
        !readSprite(node.get("momo"), t.momo) || !readString(*trim, "image", t.trim.image) ||
        !readNumbers(*trim, "size", 2, size) || !readInsets(*trim, "caps", t.trim.caps) ||
        !readInsets(*trim, "safeText", t.trim.safeText) || !readInsets(*trim, "rim", t.trim.rim) ||
        !readString(*colors, "accent", t.colors.accent) || !readStrings(*colors, "light", t.colors.light) ||
        !readStrings(*colors, "dark", t.colors.dark) || !readString(*colors, "contrast", t.colors.contrast))
        return false;
    t.trim.width = size[0], t.trim.height = size[1];
    for (const auto &item : days->array) {
        FestivalDays d;
        if (!readDays(item, d)) return false;
        t.days.push_back(d);
    }
    return isName(t.id) && isName(t.mimimo.image) && isName(t.momo.image) && isName(t.trim.image);
}

void setError(std::string *error, std::string message) {
    if (error && error->empty()) *error = std::move(message);
}

} // namespace

bool parseLocalDate(std::string_view text, LocalDate &out) {
    if (text.size() != 10 || text[4] != '-' || text[7] != '-') return false;
    for (std::size_t i : {0u, 1u, 2u, 3u, 5u, 6u, 8u, 9u})
        if (!std::isdigit(static_cast<unsigned char>(text[i]))) return false;
    const auto at = [&](std::size_t i, std::size_t n) {
        int v = 0;
        for (std::size_t k = 0; k < n; ++k) v = v * 10 + (text[i + k] - '0');
        return v;
    };
    LocalDate d{at(0, 4), at(5, 2), at(8, 2)};
    if (d.month < 1 || d.month > 12 || d.day < 1) return false;
    static const int days[] = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    int limit = days[d.month - 1];
    if (d.month == 2) {
        const bool leap = (d.year % 4 == 0 && d.year % 100 != 0) || d.year % 400 == 0;
        limit = leap ? 29 : 28;
    }
    if (d.day > limit) return false;
    out = d;
    return true;
}

std::optional<Color> parseHexColor(std::string_view text) {
    if (text.size() != 7 || text[0] != '#') return std::nullopt;
    std::uint32_t value = 0;
    for (std::size_t i = 1; i < 7; ++i) {
        const char c = text[i];
        value <<= 4;
        if (c >= '0' && c <= '9') value |= static_cast<std::uint32_t>(c - '0');
        else if (c >= 'a' && c <= 'f') value |= static_cast<std::uint32_t>(c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') value |= static_cast<std::uint32_t>(c - 'A' + 10);
        else return std::nullopt;
    }
    return Color::rgb(value);
}

int dayNumber(LocalDate date) {
    const int y = date.month <= 2 ? date.year - 1 : date.year, m = date.month, d = date.day;
    const int era = (y >= 0 ? y : y - 399) / 400;
    const int yoe = y - era * 400;
    const int doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
    const int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

FestivalManifest parseFestivalManifest(std::string_view json, std::string *error) {
    FestivalManifest manifest;
    Json root;
    Parser parser(json);
    if (!parser.parse(root) || root.type != Json::Type::Object) {
        setError(error, "festivals.json is not a JSON object");
        return manifest;
    }
    const auto *version = root.get("version"), *lead = root.get("leadDays"), *themes = root.get("themes");
    if (!version || version->type != Json::Type::Number || version->number != 2 || !lead ||
        lead->type != Json::Type::Number || !themes || themes->type != Json::Type::Array) {
        setError(error, "festivals.json is not version 2 with leadDays and themes");
        return manifest;
    }
    manifest.leadDays = static_cast<int>(lead->number);
    for (const auto &item : themes->array) {
        FestivalTheme theme;
        if (readTheme(item, theme)) manifest.themes.push_back(std::move(theme));
        else setError(error, "a theme does not read: " + item.str("id"));
    }
    return manifest;
}

const FestivalTheme *wornTheme(const FestivalManifest &manifest, LocalDate today, const FestivalWardrobe &wardrobe) {
    if (!wardrobe.on) return nullptr;
    struct Standing { int step; const FestivalTheme *theme; const FestivalDays *days; };
    const int now = dayNumber(today);
    std::vector<Standing> pool;
    for (const auto &theme : manifest.themes) {
        if (wardrobe.skipped.count(theme.id)) continue;
        for (const auto &days : theme.days) {
            if (now < dayNumber(days.first) - manifest.leadDays || today.key() > days.last.key()) continue;
            const int step = today.key() == days.main.key() ? 1 : today.key() >= days.first.key() ? 2 : 3;
            pool.push_back({step, &theme, &days});
        }
    }
    if (pool.empty()) return nullptr;
    int step = 3;
    for (const auto &s : pool) step = std::min(step, s.step);
    std::vector<Standing> standing;
    for (const auto &s : pool)
        if (s.step == step) standing.push_back(s);
    // The first of the best, as Swift's min(by:) picks it.
    const Standing *leader = &standing[0];
    for (const auto &s : standing) {
        const auto &a = *s.days, &b = *leader->days;
        const bool before = step == 2 ? (a.first.key() > b.first.key() || (a.first.key() == b.first.key() && a.last.key() < b.last.key()))
                          : step == 3 ? a.first.key() < b.first.key() : false;
        if (before) leader = &s;
    }
    std::vector<const FestivalTheme *> turns;
    for (const auto &s : standing)
        if (s.days->main.key() == leader->days->main.key()) turns.push_back(s.theme);
    const int n = static_cast<int>(turns.size());
    return turns[static_cast<std::size_t>(((now % n) + n) % n)];
}

std::vector<const FestivalTheme *> metThemes(const FestivalManifest &manifest, LocalDate since, LocalDate today) {
    std::vector<const FestivalTheme *> out;
    for (const auto &theme : manifest.themes)
        for (const auto &days : theme.days)
            if (dayNumber(days.first) - manifest.leadDays <= dayNumber(today) && days.last.key() >= since.key()) {
                out.push_back(&theme);
                break;
            }
    return out;
}

void applyFestivalPill(Theme &theme, const FestivalTheme &festival) {
    if (theme.highContrast) return;
    const auto &pair = theme.appearance == Appearance::Dark ? festival.colors.dark : festival.colors.light;
    if (pair.size() != 2) return;
    const auto top = parseHexColor(pair[0]), bottom = parseHexColor(pair[1]);
    if (!top || !bottom) return;
    theme.pillTop = *top;
    theme.pillBottom = *bottom;
}

} // namespace meowpuut::ui
