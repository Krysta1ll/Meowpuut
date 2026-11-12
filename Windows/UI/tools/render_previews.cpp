// Writes SVG snapshots of the Windows candidate window for review on macOS.
// Text is measured with Core Text in PingFang TC, the font rsvg-convert uses
// for these files, so widths in the snapshot match the rendered glyphs.
// Each state is written twice: the window alone (transparent, as Windows
// composes it) and the same window over a page of text.
//
//   scripts/build-windows-check.sh   (builds this tool and converts to PNG)
#include "CandidateLayout.h"
#include "Festival.h"
#include "Scene.h"
#include "SvgSnapshot.h"
#include "Theme.h"

#include <CoreText/CoreText.h>

#include <cstdio>
#include <fstream>
#include <iterator>
#include <map>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

using namespace meowpuut::ui;

namespace {

class CoreTextMeasurer : public TextMeasurer {
public:
    float measure(std::string_view text, float size, bool medium) override {
        const std::string key = std::string(medium ? "M" : "R") + std::to_string(size) + "|" + std::string(text);
        if (auto it = cache_.find(key); it != cache_.end()) return it->second;
        CFStringRef str = CFStringCreateWithBytes(nullptr, reinterpret_cast<const UInt8 *>(text.data()),
                                                  static_cast<CFIndex>(text.size()), kCFStringEncodingUTF8, false);
        CTFontRef font = CTFontCreateWithName(medium ? CFSTR("PingFangTC-Medium") : CFSTR("PingFangTC-Regular"), size, nullptr);
        const void *keys[] = {kCTFontAttributeName};
        const void *values[] = {font};
        CFDictionaryRef attrs = CFDictionaryCreate(nullptr, keys, values, 1, &kCFTypeDictionaryKeyCallBacks,
                                                   &kCFTypeDictionaryValueCallBacks);
        CFAttributedStringRef attributed = CFAttributedStringCreate(nullptr, str ? str : CFSTR(""), attrs);
        CTLineRef line = CTLineCreateWithAttributedString(attributed);
        const auto width = static_cast<float>(CTLineGetTypographicBounds(line, nullptr, nullptr, nullptr));
        CFRelease(line);
        CFRelease(attributed);
        CFRelease(attrs);
        CFRelease(font);
        if (str) CFRelease(str);
        cache_[key] = width;
        return width;
    }

private:
    std::map<std::string, float> cache_;
};

std::string base64(const std::string &data) {
    static const char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve((data.size() + 2) / 3 * 4);
    std::size_t i = 0;
    for (; i + 2 < data.size(); i += 3) {
        const unsigned v = (static_cast<unsigned char>(data[i]) << 16) | (static_cast<unsigned char>(data[i + 1]) << 8) |
                           static_cast<unsigned char>(data[i + 2]);
        out += table[(v >> 18) & 63];
        out += table[(v >> 12) & 63];
        out += table[(v >> 6) & 63];
        out += table[v & 63];
    }
    if (i < data.size()) {
        unsigned v = static_cast<unsigned char>(data[i]) << 16;
        if (i + 1 < data.size()) v |= static_cast<unsigned char>(data[i + 1]) << 8;
        out += table[(v >> 18) & 63];
        out += table[(v >> 12) & 63];
        out += i + 1 < data.size() ? table[(v >> 6) & 63] : '=';
        out += '=';
    }
    return out;
}

std::string dataUri(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        std::fprintf(stderr, "missing asset %s\n", path.c_str());
        return {};
    }
    const std::string bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    return "data:image/png;base64," + base64(bytes);
}

struct Scenario {
    std::string name;
    CandidateViewModel model;
    Appearance appearance = Appearance::Light;
    bool highContrast = false;
    bool transparency = true;
    Palette palette = Palette::Rose;
};

CandidateViewModel nihao() {
    CandidateViewModel m;
    m.candidates = {"你好", "妳好", "你好呀", "擬好", "泥好", "尼好", "你號"};
    m.japanese.assign(m.candidates.size(), false);
    return m;
}

CandidateViewModel sushi() {
    CandidateViewModel m;
    m.candidates = {"蘇軾", "素食", "速食", "宿舍", "訴說", "塑身", "素什", "寿司"};
    m.japanese = {false, false, false, false, false, false, false, true};
    return m;
}

// The window laid over a page of body text, the way it sits in an editor.
std::string onPage(const std::string &window, SizeF size, bool dark, float scale) {
    const float pad = 22;
    const float w = size.width + 2 * pad, h = size.height + 2 * pad;
    std::ostringstream s;
    s << "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" << w * scale << "\" height=\"" << h * scale
      << "\" viewBox=\"0 0 " << w << " " << h << "\">\n<rect width=\"100%\" height=\"100%\" fill=\""
      << (dark ? "#1E1E1E" : "#FFFFFF") << "\"/>\n";
    const char *line = "今天天氣很好，我想去河邊散步，然後吃壽司。The quick brown fox jumps over the lazy dog. "
                       "今天天氣很好，我想去河邊散步，然後吃壽司。The quick brown fox jumps over the lazy dog.";
    for (float y = 15; y < h + 10; y += 24)
        s << "<text x=\"8\" y=\"" << y << "\" font-family=\"PingFang TC\" font-size=\"15\" fill=\""
          << (dark ? "#D4D4D4" : "#1F1F1F") << "\">" << line << "</text>\n";
    std::string nested = window;
    nested.insert(4, " x=\"" + std::to_string(pad) + "\" y=\"" + std::to_string(pad) + "\"");
    s << nested << "</svg>\n";
    return s.str();
}

} // namespace

int main(int argc, char **argv) {
    if (argc != 3 && argc != 5) {
        std::fprintf(stderr, "usage: render_previews <Windows/UI/Assets> <output dir> [<festivals dir> <YYYY-MM-DD>]\n");
        return 2;
    }
    const std::string assets = argv[1], out = argv[2];
    SvgAssets svgAssets;
    svgAssets.mimimo = dataUri(assets + "/" + catArt(Cat::Mimimo).image);
    svgAssets.momo = dataUri(assets + "/" + catArt(Cat::Momo).image);
    std::string festivalName;

    std::vector<Scenario> scenarios;
    const GlassShape shapes[] = {GlassShape::Capsule, GlassShape::Wave, GlassShape::Cloud};
    for (auto appearance : {Appearance::Light, Appearance::Dark}) {
        for (auto shape : shapes) {
            const std::string tone = appearance == Appearance::Light ? "light" : "dark";
            Scenario collapsed{tone + "-" + glassShapeName(shape) + "-collapsed", sushi(), appearance};
            collapsed.model.shape = shape;
            collapsed.model.hovered = 2;
            scenarios.push_back(collapsed);

            Scenario expanded{tone + "-" + glassShapeName(shape) + "-expanded", sushi(), appearance};
            expanded.model.shape = shape;
            expanded.model.expanded = true;
            expanded.model.highlighted = 7;
            expanded.model.page = 1;
            expanded.model.lastPage = false;
            expanded.model.preedit = "sushi";
            expanded.model.modeLabel = "拼音 · 繁";
            scenarios.push_back(expanded);
        }
    }
    // The palettes: the dark rim and caustic take the palette's colour.
    for (auto appearance : {Appearance::Light, Appearance::Dark}) {
        for (const char *name : {"ocean", "sage", "lavender", "amber", "graphite"}) {
            Scenario tinted{std::string(appearance == Appearance::Light ? "light" : "dark") + "-capsule-" + name, sushi(),
                            appearance};
            tinted.palette = parsePalette(name);
            scenarios.push_back(tinted);
        }
    }
    Scenario oceanPanel{"dark-capsule-ocean-expanded", sushi(), Appearance::Dark};
    oceanPanel.palette = Palette::Ocean;
    oceanPanel.model.expanded = true;
    oceanPanel.model.preedit = "sushi";
    oceanPanel.model.modeLabel = "拼音 · 繁";
    scenarios.push_back(oceanPanel);
    Scenario hello{"light-capsule-nihao", nihao(), Appearance::Light};
    // Both cats a third of the way through their answer to a click.
    hello.model.reactions[0] = catReaction(Cat::Mimimo, 0.33f);
    hello.model.reactions[1] = catReaction(Cat::Momo, 0.33f);
    hello.model.pressed = 3;
    scenarios.push_back(hello);
    Scenario zhuyin{"dark-cloud-zhuyin", nihao(), Appearance::Dark};
    zhuyin.model.shape = GlassShape::Cloud;
    zhuyin.model.shiftNumbers = true;
    zhuyin.model.highlighted = 1;
    scenarios.push_back(zhuyin);
    Scenario longMixed{"light-wave-long", {}, Appearance::Light};
    longMixed.model.candidates = {"我今天想吃寿司然後去河邊散步看夕陽再回家睡覺明天早上還要早起去上班所以不能太晚睡", "我", "握", "窩", "臥"};
    longMixed.model.japanese = {true, false, false, false, false};
    longMixed.model.shape = GlassShape::Wave;
    scenarios.push_back(longMixed);
    // Kana and a mixed sentence: each stays on one line with its paw mark.
    Scenario kana{"light-capsule-japanese", {}, Appearance::Light};
    kana.model.candidates = {"今天", "金天", "今", "金", "斤", "今天は楽しい", "すし"};
    kana.model.japanese = {false, false, false, false, false, true, true};
    kana.model.highlighted = 5;
    scenarios.push_back(kana);
    Scenario single{"dark-capsule-single", {}, Appearance::Dark};
    single.model.candidates = {"喵"};
    single.model.japanese = {false};
    scenarios.push_back(single);
    // A short bar on the cloud.
    for (auto appearance : {Appearance::Light, Appearance::Dark}) {
        Scenario shortCloud{std::string(appearance == Appearance::Light ? "light" : "dark") + "-cloud-short", {}, appearance};
        shortCloud.model.candidates = {"喵", "貓"};
        shortCloud.model.japanese = {false, false};
        shortCloud.model.shape = GlassShape::Cloud;
        scenarios.push_back(shortCloud);
    }
    // A whole sentence: the panel is one column, as wide as the sentence.
    Scenario sentence{"light-capsule-sentence-expanded", {}, Appearance::Light};
    sentence.model.candidates = {"今天天氣很好我想去河邊散步然後吃壽司", "今天", "金天", "今"};
    sentence.model.japanese = {true, false, false, false};
    sentence.model.expanded = true;
    sentence.model.preedit = "jintiantianqihenhao";
    sentence.model.modeLabel = "拼音 · 繁";
    scenarios.push_back(sentence);
    for (auto appearance : {Appearance::Light, Appearance::Dark}) {
        Scenario flat{std::string(appearance == Appearance::Light ? "light" : "dark") + "-capsule-no-transparency", sushi(),
                      appearance};
        flat.transparency = false;
        scenarios.push_back(flat);
    }
    Scenario contrast{"high-contrast", sushi(), Appearance::Dark, true};
    contrast.model.decorations = false;
    scenarios.push_back(contrast);
    Scenario contrastPanel{"high-contrast-expanded", sushi(), Appearance::Dark, true};
    contrastPanel.model.decorations = false;
    contrastPanel.model.expanded = true;
    contrastPanel.model.preedit = "sushi";
    contrastPanel.model.modeLabel = "拼音 · 繁";
    scenarios.push_back(contrastPanel);

    // Optional festival check: the theme worn on the given date, with its
    // capsule colours. Its cats and ornaments are not drawn on Windows yet.
    std::optional<FestivalTheme> festivalTheme;
    if (argc == 5) {
        const std::string dir = argv[3];
        std::ifstream in(dir + "/festivals.json", std::ios::binary);
        const std::string json((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        std::string error;
        const auto manifest = parseFestivalManifest(json, &error);
        LocalDate day;
        if (!parseLocalDate(argv[4], day)) { std::fprintf(stderr, "bad date %s\n", argv[4]); return 2; }
        const FestivalTheme *worn = wornTheme(manifest, day, FestivalWardrobe{});
        if (!worn) { std::fprintf(stderr, "no festival on %s (%s)\n", argv[4], error.c_str()); return 1; }
        Scenario festival{"festival-" + worn->id, sushi(), Appearance::Light};
        scenarios.push_back(festival);
        festivalTheme = *worn;
        festivalName = festival.name;
    }

    CoreTextMeasurer measurer;
    LayoutLimits limits;
    limits.maxWidth = 900;
    const std::string font = "PingFang TC";
    for (const auto &s : scenarios) {
        const auto layout = layoutCandidates(s.model, measurer, limits);
        // High contrast uses the colours of Windows' "Night sky" theme.
        Theme theme = s.highContrast
                          ? makeTheme(s.appearance, Palette::Rose, true, true, Color::rgb(0x000000), Color::rgb(0xFFFFFF),
                                      Color::rgb(0xD6B4FD), Color::rgb(0x2B2B2B), Color::rgb(0xA6A6A6))
                          : makeTheme(s.appearance, s.palette, s.transparency, false);
        const bool festive = s.name == festivalName;
        if (festive && festivalTheme) applyFestivalPill(theme, *festivalTheme);
        const Scene scene = buildScene(layout, s.model, theme);
        std::ofstream(out + "/" + s.name + ".svg", std::ios::binary) << renderSvg(scene, svgAssets, font, 2.0f);
        std::ofstream(out + "/" + s.name + "-on-page.svg", std::ios::binary)
            << onPage(renderSvg(scene, svgAssets, font, 1.0f), layout.size, s.appearance == Appearance::Dark, 2.0f);
        std::printf("%s %.0fx%.0f\n", s.name.c_str(), static_cast<double>(layout.size.width),
                    static_cast<double>(layout.size.height));
    }
    return 0;
}
