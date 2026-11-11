#include "Festival.h"

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

bool asciiLetterOrDigit(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
}

// Names Windows opens as a device whatever the extension.
bool deviceName(const std::string &stem) {
    std::string upper;
    for (char c : stem) upper += (c >= 'a' && c <= 'z') ? static_cast<char>(c - 'a' + 'A') : c;
    if (upper == "CON" || upper == "PRN" || upper == "AUX" || upper == "NUL") return true;
    return upper.size() == 4 && (upper.compare(0, 3, "COM") == 0 || upper.compare(0, 3, "LPT") == 0) &&
           upper[3] >= '1' && upper[3] <= '9';
}

// A file name inside the outfit's own directory: letters, digits, '_', '-'
// and '.', ending in ".png". No separators, no "..", no device names.
bool safeFileName(const std::string &name) {
    if (name.size() <= 4 || name.size() > 128) return false;
    for (char c : name)
        if (!(asciiLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) return false;
    if (name.front() == '.' || name.find("..") != std::string::npos) return false;
    if (deviceName(name.substr(0, name.find('.')))) return false;
    return name.compare(name.size() - 4, 4, ".png") == 0;
}

bool safeId(const std::string &id) {
    if (id.empty() || id.size() > 64) return false;
    for (char c : id)
        if (!(asciiLetterOrDigit(c) || c == '-' || c == '_')) return false;
    return !deviceName(id);
}

bool readCat(const Json *node, CatOutfit &out) {
    if (!node) return true;
    if (node->type != Json::Type::Object) return false;
    const auto take = [](const std::string &v, std::string &slot) {
        if (v.empty()) return true;
        if (!safeFileName(v)) return false;
        slot = v;
        return true;
    };
    if (!take(node->str("front"), out.front) || !take(node->str("back"), out.back)) return false;
    if (const auto *frames = node->get("frames")) {
        if (frames->type != Json::Type::Object) return false;
        const char *names[3] = {"open", "happy", "blink"};
        for (int i = 0; i < 3; ++i)
            if (!take(frames->str(names[i]), out.frames[i])) return false;
    }
    return true;
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

FestivalManifest parseFestivalManifest(std::string_view json, std::string *error) {
    FestivalManifest manifest;
    Json root;
    Parser parser(json);
    if (!parser.parse(root) || root.type != Json::Type::Object) {
        setError(error, "festivals.json is not a JSON object");
        return manifest;
    }
    if (const auto *version = root.get("version")) {
        if (version->type != Json::Type::Number || version->number != 1) {
            setError(error, "unsupported festivals.json version");
            return manifest;
        }
    }
    const auto *outfits = root.get("outfits");
    if (!outfits) return manifest;
    if (outfits->type != Json::Type::Array) {
        setError(error, "outfits is not an array");
        return manifest;
    }
    for (const auto &item : outfits->array) {
        if (item.type != Json::Type::Object) { setError(error, "outfit is not an object"); continue; }
        Outfit outfit;
        outfit.id = item.str("id");
        if (!safeId(outfit.id)) { setError(error, "outfit id must use letters, digits, - or _"); continue; }
        if (!parseLocalDate(item.str("start"), outfit.start) || !parseLocalDate(item.str("end"), outfit.end) ||
            outfit.end.key() < outfit.start.key()) {
            setError(error, "outfit " + outfit.id + " has an invalid date range");
            continue;
        }
        if (const auto *title = item.get("title")) {
            outfit.titleHant = title->str("hant");
            outfit.titleHans = title->str("hans");
        }
        const auto *cats = item.get("cats");
        if (cats && cats->type != Json::Type::Object) { setError(error, "outfit " + outfit.id + ": cats is not an object"); continue; }
        if (cats && (!readCat(cats->get("mimimo"), outfit.mimimo) || !readCat(cats->get("momo"), outfit.momo))) {
            setError(error, "outfit " + outfit.id + " names an invalid image file");
            continue;
        }
        const std::string pill = item.str("pill");
        if (!pill.empty()) {
            outfit.pill = parseHexColor(pill);
            if (!outfit.pill) { setError(error, "outfit " + outfit.id + " has an invalid pill colour"); continue; }
        }
        manifest.outfits.push_back(std::move(outfit));
    }
    return manifest;
}

const Outfit *activeOutfit(const FestivalManifest &manifest, LocalDate today) {
    for (const auto &outfit : manifest.outfits)
        if (today.key() >= outfit.start.key() && today.key() <= outfit.end.key()) return &outfit;
    return nullptr;
}

void applyOutfitPill(Theme &theme, Color pill) {
    if (theme.highContrast) return;
    const auto mix = [](Color c, Color toward, float t) {
        return Color{c.r + (toward.r - c.r) * t, c.g + (toward.g - c.g) * t, c.b + (toward.b - c.b) * t, 1};
    };
    if (theme.appearance == Appearance::Dark) {
        theme.pillTop = mix(pill, Color{0, 0, 0, 1}, 0.12f);
        theme.pillBottom = mix(pill, Color{0, 0, 0, 1}, 0.28f);
    } else {
        theme.pillTop = mix(pill, Color{1, 1, 1, 1}, 0.14f);
        theme.pillBottom = pill;
    }
}

} // namespace meowpuut::ui
