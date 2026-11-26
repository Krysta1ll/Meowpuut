// Renders the 字景 candidate window to PNG on macOS, text included, with the same GlyphStripView the Windows window
// uses (the atlas, the slice, OVER and CATS, the halo). Core Text stands in for DirectWrite: the same fonts (Cubic 11,
// Fusion Pixel from Assets/fonts), aliased, at 12 x k px, thresholded to the one-bit masks the window gets.
//
//   render_glyph_previews <Assets/fonts> <output directory>          the PNGs
//   render_glyph_previews <Assets/fonts> <output directory> bench    the per-key cost (the platform half of it)
//
// scripts/build-windows-check.sh builds and runs it (needs the host Rust library built with --features glyph).
#include "GlyphStrip.h"

#include <CoreGraphics/CoreGraphics.h>
#include <CoreText/CoreText.h>
#include <ImageIO/ImageIO.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <string>
#include <vector>

using namespace meowpuut::ui;

namespace {

CFStringRef cf(const std::string &s) {
    return CFStringCreateWithBytes(nullptr, reinterpret_cast<const UInt8 *>(s.data()), static_cast<CFIndex>(s.size()),
                                   kCFStringEncodingUTF8, false);
}

class CoreTextGlyphText : public GlyphTextEngine {
public:
    explicit CoreTextGlyphText(const std::string &dir) {
        for (const char *file : {"pixel_a.ttf", "pixel_b.otf"}) {
            const std::string path = dir + "/" + file;
            CFStringRef p = cf(path);
            CFURLRef url = CFURLCreateWithFileSystemPath(nullptr, p, kCFURLPOSIXPathStyle, false);
            CFErrorRef err = nullptr;
            if (!CTFontManagerRegisterFontsForURL(url, kCTFontManagerScopeProcess, &err) && err) {
                // Already registered in this process is fine.
                CFRelease(err);
            }
            CFRelease(url);
            CFRelease(p);
        }
    }

    double measure(std::string_view s) override {
        if (auto it = widths_.find(std::string(s)); it != widths_.end()) return it->second;
        CTLineRef line = makeLine(s, 12);
        const double w = line ? CTLineGetTypographicBounds(line, nullptr, nullptr, nullptr) : 0;
        if (line) CFRelease(line);
        widths_[std::string(s)] = w;
        return w;
    }

    bool raster(std::string_view s, unsigned k, GlyphMask &out) override {
        out = GlyphMask{};
        const int px = 12 * static_cast<int>(k);
        CTLineRef line = makeLine(s, px);
        if (!line) return false;
        const int pad = px, rowsAbove = px * 2, h = px * 4;
        const int w = static_cast<int>(std::ceil(CTLineGetTypographicBounds(line, nullptr, nullptr, nullptr))) + 2 * pad;
        std::vector<std::uint8_t> bits(static_cast<std::size_t>(w) * static_cast<std::size_t>(h), 0);
        CGColorSpaceRef gray = CGColorSpaceCreateDeviceGray();
        CGContextRef ctx = CGBitmapContextCreate(bits.data(), static_cast<size_t>(w), static_cast<size_t>(h), 8, static_cast<size_t>(w), gray, kCGImageAlphaNone);
        CGColorSpaceRelease(gray);
        CGContextSetShouldAntialias(ctx, false);
        CGContextSetShouldSmoothFonts(ctx, false);
        CGContextSetAllowsFontSmoothing(ctx, false);
        CGContextSetGrayFillColor(ctx, 1, 1);
        CGContextSetTextPosition(ctx, pad, h - rowsAbove); // bottom-up: the baseline is rowsAbove from the top
        CTLineDraw(line, ctx);
        CGContextRelease(ctx);
        CFRelease(line);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
                if (bits[static_cast<std::size_t>(y * w + x)] >= 128) {
                    x0 = std::min(x0, x); x1 = std::max(x1, x);
                    y0 = std::min(y0, y); y1 = std::max(y1, y);
                }
        if (x1 < 0) return true;
        out.left = x0 - pad;
        out.top = y0 - rowsAbove;
        out.width = x1 - x0 + 1;
        out.height = y1 - y0 + 1;
        out.on.assign(static_cast<std::size_t>(out.width * out.height), 0);
        for (int y = 0; y < out.height; ++y)
            for (int x = 0; x < out.width; ++x)
                out.on[static_cast<std::size_t>(y * out.width + x)] = bits[static_cast<std::size_t>((y0 + y) * w + x0 + x)] >= 128 ? 1 : 0;
        return true;
    }

private:
    // One line, each character in the first of Cubic 11 and Fusion Pixel that has it (else the system's cascade).
    CTLineRef makeLine(std::string_view text, int px) {
        CFStringRef str = cf(std::string(text));
        if (!str) return nullptr;
        CTFontRef a = CTFontCreateWithName(CFSTR("Meowpuut Pixel A"), static_cast<CGFloat>(px), nullptr);
        CTFontRef b = CTFontCreateWithName(CFSTR("Meowpuut Pixel B"), static_cast<CGFloat>(px), nullptr);
        CFMutableAttributedStringRef as = CFAttributedStringCreateMutable(nullptr, 0);
        CFAttributedStringReplaceString(as, CFRangeMake(0, 0), str);
        const CFIndex n = CFStringGetLength(str);
        CFAttributedStringSetAttribute(as, CFRangeMake(0, n), kCTFontAttributeName, a);
        CFAttributedStringSetAttribute(as, CFRangeMake(0, n), kCTForegroundColorFromContextAttributeName, kCFBooleanTrue);
        for (CFIndex i = 0; i < n;) {
            UniChar u[2] = {CFStringGetCharacterAtIndex(str, i), 0};
            CFIndex len = 1;
            if (CFStringIsSurrogateHighCharacter(u[0]) && i + 1 < n) { u[1] = CFStringGetCharacterAtIndex(str, i + 1); len = 2; }
            CGGlyph g[2] = {0, 0};
            if (!CTFontGetGlyphsForCharacters(a, u, g, len) && CTFontGetGlyphsForCharacters(b, u, g, len))
                CFAttributedStringSetAttribute(as, CFRangeMake(i, len), kCTFontAttributeName, b);
            i += len;
        }
        CTLineRef line = CTLineCreateWithAttributedString(as);
        CFRelease(as);
        CFRelease(a);
        CFRelease(b);
        CFRelease(str);
        return line;
    }
    std::map<std::string, double> widths_;
};

struct Shot {
    std::vector<std::uint8_t> px; // BGRA premultiplied
    unsigned w = 0, h = 0;
};

Shot shoot(GlyphStripView &v) {
    Shot s;
    const GlyphSize z = v.size();
    s.w = z.pixelWidth;
    s.h = z.pixelHeight;
    s.px.assign(static_cast<std::size_t>(s.w) * s.h * 4, 0);
    v.present(s.px.data(), static_cast<std::size_t>(s.w) * 4, s.w, s.h);
    return s;
}

bool writePng(const std::string &path, const std::vector<std::uint8_t> &bgra, unsigned w, unsigned h) {
    CGColorSpaceRef rgb = CGColorSpaceCreateDeviceRGB();
    CFDataRef data = CFDataCreate(nullptr, bgra.data(), static_cast<CFIndex>(bgra.size()));
    CGDataProviderRef provider = CGDataProviderCreateWithCFData(data);
    CGImageRef image = CGImageCreate(w, h, 8, 32, static_cast<size_t>(w) * 4, rgb,
                                     kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little, provider, nullptr, false,
                                     kCGRenderingIntentDefault);
    CFStringRef p = cf(path);
    CFURLRef url = CFURLCreateWithFileSystemPath(nullptr, p, kCFURLPOSIXPathStyle, false);
    CGImageDestinationRef dest = CGImageDestinationCreateWithURL(url, CFSTR("public.png"), 1, nullptr);
    CGImageDestinationAddImage(dest, image, nullptr);
    const bool ok = CGImageDestinationFinalize(dest);
    CFRelease(dest);
    CFRelease(url);
    CFRelease(p);
    CGImageRelease(image);
    CGDataProviderRelease(provider);
    CFRelease(data);
    CGColorSpaceRelease(rgb);
    return ok;
}

struct Scenario {
    std::string name;
    double scale;  // 1 -> k 1, 1.5 -> k 2
    bool dark = false, safe = false, game = false, expanded = false, zhuyin = false;
    std::vector<std::string> words;
    std::vector<bool> japanese;
    std::size_t selected = 0;
    std::int64_t characters = 0; // the ledger: which place (0: the first place, the sea, as the prototype's first window)
};

// The strip on a page: a line of text with a caret, the strip 6 pt below it, as the window sits.
Shot onPage(const Shot &strip, unsigned k, bool dark) {
    const unsigned margin = 24 * k, caretH = 18 * k, gap = 6 * k;
    Shot page;
    page.w = strip.w + 2 * margin;
    page.h = margin + caretH + gap + strip.h + margin;
    const std::uint32_t bg = dark ? 0xFF1F2229 : 0xFFFAF9F6;
    page.px.resize(static_cast<std::size_t>(page.w) * page.h * 4);
    for (std::size_t i = 0; i < page.px.size(); i += 4) {
        page.px[i] = static_cast<std::uint8_t>(bg);
        page.px[i + 1] = static_cast<std::uint8_t>(bg >> 8);
        page.px[i + 2] = static_cast<std::uint8_t>(bg >> 16);
        page.px[i + 3] = 255;
    }
    const std::uint8_t ink = dark ? 0xDD : 0x22;
    for (unsigned y = 0; y < caretH; ++y)
        for (unsigned x = 0; x < k; ++x) {
            const std::size_t at = (static_cast<std::size_t>(margin + y) * page.w + margin + 14 * k - k + x) * 4;
            page.px[at] = page.px[at + 1] = page.px[at + 2] = ink;
        }
    const unsigned top = margin + caretH + gap;
    for (unsigned y = 0; y < strip.h; ++y)
        for (unsigned x = 0; x < strip.w; ++x) {
            const std::uint8_t *s = &strip.px[(static_cast<std::size_t>(y) * strip.w + x) * 4];
            std::uint8_t *d = &page.px[(static_cast<std::size_t>(top + y) * page.w + margin + x) * 4];
            const unsigned inv = 255 - s[3];
            for (int c = 0; c < 3; ++c) d[c] = static_cast<std::uint8_t>(std::min<unsigned>(255, s[c] + (d[c] * inv + 127) / 255));
        }
    return page;
}

GlyphStripView &configure(GlyphStripView &v, const Scenario &s) {
    GlyphLook look;
    look.dark = s.dark;
    look.safe = s.safe;
    look.still = s.safe;
    v.setLook(look);
    v.setScale(s.scale);
    v.setDistance(s.characters);
    v.setClock(s.dark ? 2230 : 1030, 1'700'000'000'000);
    v.setZhuyin(s.zhuyin);
    v.setGame(s.game);
    v.setExpanded(s.expanded);
    std::vector<bool> jp = s.japanese;
    jp.resize(s.words.size(), false);
    v.setCandidates(s.words, jp, s.selected);
    return v;
}

struct Dist {
    std::vector<double> v;
    void print(const char *name) {
        std::sort(v.begin(), v.end());
        const auto at = [&](double q) { return v[std::min(v.size() - 1, static_cast<std::size_t>(q * static_cast<double>(v.size())))]; };
        std::printf("    %-34s median %6.0f us  p95 %6.0f us  max %6.0f us  (n=%zu)\n", name, at(0.5), at(0.95), v.back(), v.size());
    }
};

int bench(GlyphTextEngine &text) {
    const std::vector<std::string> words = {"你好", "妳好", "你好呀", "擬好", "泥好", "尼好", "你號", "您好", "呢好"};
    const std::vector<std::string> other = {"蘇軾", "素食", "速食", "宿舍", "訴說", "塑身", "素什", "寿司", "輸入"};
    using clock = std::chrono::steady_clock;
    const auto since = [](clock::time_point t) { return std::chrono::duration<double, std::micro>(clock::now() - t).count(); };
    for (double scale : {1.0, 1.5, 2.5}) {
        GlyphStripView v(text);
        Scenario s{};
        s.scale = scale;
        s.words = words;
        configure(v, s);
        const GlyphSize z = v.size();
        std::vector<std::uint8_t> dst(static_cast<std::size_t>(z.pixelWidth + 64) * (z.pixelHeight + 64) * 4);
        const std::size_t stride = static_cast<std::size_t>(z.pixelWidth + 64) * 4;
        const auto draw = [&] {
            const GlyphSize c = v.size();
            v.present(dst.data(), stride, z.pixelWidth + 64, c.pixelHeight);
        };
        draw();
        std::int64_t now = 1000;
        for (int i = 0; i < 60; ++i) v.tick(now += 16); // the idle ticks fill the atlas
        // Warm the word masks of both lists (a cold word is rasterised once by the platform's text engine).
        for (int i = 0; i < 4; ++i) {
            v.setCandidates(i % 2 ? words : other, std::vector<bool>(9, false), 0);
            v.key(GlyphKey::Letter, now += 90);
            draw();
            v.tick(now += 200);
            draw();
        }
        Dist arrow, letter, land;
        for (int i = 0; i < 3000; ++i) { // <- -> Tab: select + present from the atlas
            const auto t0 = clock::now();
            v.select(static_cast<std::size_t>(i * 5 % 9));
            draw();
            arrow.v.push_back(since(t0));
        }
        for (int i = 0; i < 600; ++i) {
            // a letter key: the words change, the cats step, the ground stays until the land step
            const auto t0 = clock::now();
            v.setCandidates(i % 2 ? words : other, std::vector<bool>(9, false), 0);
            const double a = since(t0);
            v.key(GlyphKey::Letter, now += 90);
            const double b = since(t0);
            draw();
            letter.v.push_back(since(t0));
            if (std::getenv("BENCH_PARTS") && i % 100 == 0) std::printf("      parts: setCandidates %.0f key %.0f draw %.0f\n", a, b - a, since(t0) - b);
            // the land step 60-160 ms later: the new ground, on the timer, not on the key
            const auto t1 = clock::now();
            v.tick(now += 120);
            draw();
            land.v.push_back(since(t1));
            for (int j = 0; j < 12; ++j)
                if (v.tick(now += 16)) draw(); // the window's loop: a tick that changed the picture is drawn
        }
        std::printf("k=%u strip %ux%u px, window %ux%u px (host, DIB only; no UpdateLayeredWindow):\n", z.k, z.stripWidth, z.stripHeight, z.pixelWidth, z.pixelHeight);
        arrow.print("arrow key (atlas slice)");
        letter.print("letter key (OVER, CATS, words)");
        land.print("land step (new ground, on the timer)");
    }
    return 0;
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s <Assets/fonts> <output directory> [bench]\n", argv[0]);
        return 2;
    }
    CoreTextGlyphText text(argv[1]);
    if (argc > 3 && std::string(argv[3]) == "bench") return bench(text);
    const std::string out = argv[2];
    const std::vector<std::string> nihao = {"你好", "妳好", "你好呀", "擬好", "泥好", "尼好", "你號"};
    const std::vector<std::string> sushi = {"蘇軾", "素食", "速食", "宿舍", "訴說", "塑身", "素什", "寿司"};
    const std::vector<std::string> many = {"蘇軾", "素食", "速食", "宿舍", "訴說", "塑身", "素什", "寿司", "輸入", "樹式", "數時", "述師"};
    std::vector<Scenario> all;
    for (double scale : {1.0, 1.5}) {
        const char *tag = scale == 1.0 ? "k1" : "k2";
        auto add = [&](const std::string &name, Scenario s) {
            s.name = name + "-" + tag;
            s.scale = scale;
            all.push_back(std::move(s));
        };
        add("light", {"", 1, false, false, false, false, false, nihao, {}, 0});
        add("light-second", {"", 1, false, false, false, false, false, nihao, {}, 1});
        add("dark", {"", 1, true, false, false, false, false, nihao, {}, 0});
        add("light-sushi", {"", 1, false, false, false, false, false, sushi, {false, false, false, false, false, false, false, true}, 7});
        add("safe", {"", 1, false, true, false, false, false, nihao, {}, 0});
        add("safe-dark", {"", 1, true, true, false, false, false, nihao, {}, 0});
        add("game", {"", 1, false, false, true, false, false, nihao, {}, 0});
        add("game-dark", {"", 1, true, false, true, false, false, nihao, {}, 0});
        add("expanded", {"", 1, false, false, false, true, false, many, {}, 5});
        add("expanded-dark", {"", 1, true, false, false, true, false, many, {}, 5});
        add("zhuyin", {"", 1, false, false, false, false, true, nihao, {}, 0});
    }
    for (const Scenario &s : all) {
        GlyphStripView v(text);
        configure(v, s);
        const Shot strip = shoot(v);
        const std::string base = out + "/glyph-" + s.name;
        if (!writePng(base + ".png", strip.px, strip.w, strip.h)) { std::fprintf(stderr, "cannot write %s\n", base.c_str()); return 1; }
        const Shot page = onPage(strip, v.k(), s.dark);
        writePng(base + "-page.png", page.px, page.w, page.h);
    }
    std::printf("%zu 字景 previews in %s\n", all.size(), out.c_str());
    return 0;
}
