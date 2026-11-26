// Tests of the 字景 strip's platform half (GlyphStrip.cpp) on the host: a fixed text engine stands in for DirectWrite.
//   c++ -std=c++17 -I Windows/UI Windows/UI/GlyphStrip.cpp Windows/UI/tests/glyph_strip_test.cpp \
//       target/release/libmeowpuut_ffi.a (built with --features glyph) -o glyph_strip_test
#include "GlyphStrip.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using namespace meowpuut::ui;

namespace {
int failures = 0;
void check(bool ok, const char *what, int line) {
    if (!ok) { ++failures; std::fprintf(stderr, "FAIL line %d: %s\n", line, what); }
}
#define CHECK(x) check((x), #x, __LINE__)

// Every code point is 12 px wide and a box 10 x 9 px above the baseline (at k = 1): enough to test where the words go.
class FixedText : public GlyphTextEngine {
public:
    int rasters = 0;
    double measure(std::string_view s) override {
        double n = 0;
        for (unsigned char c : s) n += (c & 0xC0) == 0x80 ? 0 : 12;
        return n;
    }
    bool raster(std::string_view s, unsigned k, GlyphMask &out) override {
        ++rasters;
        const int n = static_cast<int>(measure(s) / 12);
        const int ik = static_cast<int>(k);
        out.left = 0;
        out.top = -9 * ik;
        out.width = n * 12 * ik;
        out.height = 9 * ik;
        out.on.assign(static_cast<std::size_t>(out.width) * static_cast<std::size_t>(out.height), 0);
        for (int c = 0; c < n; ++c)
            for (int y = 0; y < out.height; ++y)
                for (int x = 0; x < 10 * ik; ++x) out.on[static_cast<std::size_t>(y * out.width + c * 12 * ik + x)] = 1;
        return true;
    }
};

const std::vector<std::string> kWords = {"你好", "妳好", "你好呀", "擬好", "泥好", "尼好", "你號", "您好"};

struct Pic {
    std::vector<std::uint8_t> px;
    unsigned w = 0, h = 0;
    bool operator==(const Pic &o) const { return w == o.w && h == o.h && px == o.px; }
};

Pic shoot(GlyphStripView &v) {
    Pic p;
    const GlyphSize s = v.size();
    p.w = s.pixelWidth;
    p.h = s.pixelHeight;
    p.px.assign(static_cast<std::size_t>(p.w) * p.h * 4, 0);
    CHECK(v.present(p.px.data(), static_cast<std::size_t>(p.w) * 4, p.w, p.h));
    return p;
}

void setup(GlyphStripView &v, double scale, bool dark = false) {
    GlyphLook look;
    look.dark = dark;
    v.setLook(look);
    v.setScale(scale);
    v.setDistance(1200);
    v.setClock(1030, 1'700'000'000'000);
    v.setCandidates(kWords, std::vector<bool>(kWords.size(), false), 0);
}

bool anyOpaque(const Pic &p) {
    for (std::size_t i = 3; i < p.px.size(); i += 4)
        if (p.px[i] != 0) return true;
    return false;
}
} // namespace

int main() {
    // k per display scale (FINAL.md §3).
    {
        FixedText t;
        GlyphStripView v(t);
        CHECK(v.valid());
        CHECK(v.setScale(1.0) == 1);
        CHECK(v.setScale(1.25) == 2);
        CHECK(v.setScale(2.0) == 2);
        CHECK(v.setScale(2.5) == 3);
    }
    // Sizes: 72 pt tall, k pixels a point.
    for (unsigned scale : {1u, 2u}) {
        FixedText t;
        GlyphStripView v(t);
        setup(v, scale == 1 ? 1.0 : 1.5);
        const GlyphSize s = v.size();
        CHECK(s.k == scale);
        CHECK(s.height == 72.0);
        CHECK(s.stripHeight == 72 * scale);
        CHECK(static_cast<double>(s.stripWidth) == std::ceil(s.width) * scale);
        CHECK(s.pixelWidth == s.stripWidth + 2 * scale && s.pixelHeight == s.stripHeight + 2 * scale); // the drop shadow
        CHECK(s.width >= 240.0);
        const Pic p = shoot(v);
        CHECK(anyOpaque(p));
        // The frame is ink and opaque at the edge; the notched corner is clear.
        CHECK(p.px[3] == 0);
        CHECK(p.px[(static_cast<std::size_t>(p.h / 2) * p.w) * 4 + 3] == 255);
        // The hard drop shadow: black at 0.2 right of the strip and below it, none further out, none on the strip's left.
        const auto at = [&](unsigned x, unsigned y) { return &p.px[(static_cast<std::size_t>(y) * p.w + x) * 4]; };
        CHECK(at(s.stripWidth + 1, p.h / 2)[3] == 51 && at(s.stripWidth + 1, p.h / 2)[0] == 0);
        CHECK(at(p.w / 2, s.stripHeight + 1)[3] == 51);
        CHECK(at(p.w - 1, 0)[3] == 0);
    }
    // The atlas slice is the land a full draw makes: ← → Tab through the words, against a view that never had an atlas.
    {
        FixedText ta, tb;
        GlyphStripView a(ta), b(tb);
        setup(a, 1.5);
        setup(b, 1.5);
        shoot(a); // a's atlas now holds the selected word's frame; the idle ticks fill the rest
        std::int64_t now = 1000;
        for (int i = 0; i < 20; ++i) a.tick(now += 16);
        CHECK(a.stats().atlasFrames == kWords.size());
        for (std::size_t j : {3u, 1u, 7u, 0u, 5u}) {
            a.select(j);
            b.select(j);
            // b has no atlas (idle() dropped it): every frame is drawn from scratch.
            b.idle();
            CHECK(shoot(a) == shoot(b));
        }
    }
    // A key keeps the atlas valid for ← →; a letter does not (the candidates change).
    {
        FixedText t;
        GlyphStripView v(t);
        setup(v, 1.0);
        shoot(v);
        std::int64_t now = 5000;
        for (int i = 0; i < 20; ++i) v.tick(now += 16);
        const int before = t.rasters;
        v.key(GlyphKey::Other, now);
        v.select(2);
        shoot(v);
        CHECK(v.stats().atlasFrames == kWords.size()); // nothing was dropped
        CHECK(t.rasters >= before);                     // words rasterised once, then cached
        const int after = t.rasters;
        v.select(5);
        shoot(v);
        shoot(v);
        CHECK(t.rasters == after);                      // zero rasterisation on the key path
    }
    // A letter key draws no land: the previous one stands until the land step, which draws the new one.
    {
        FixedText t;
        GlyphStripView v(t);
        setup(v, 1.0);
        shoot(v);
        CHECK(v.stats().atlasFrames == 1);
        std::int64_t now = 20'000;
        v.setCandidates({"蘇軾", "素食", "速食", "宿舍好好好"}, std::vector<bool>(4, false), 0);
        v.key(GlyphKey::Letter, now);
        const Pic p = shoot(v);
        CHECK(anyOpaque(p));
        CHECK(v.stats().atlasFrames == 0);
        for (int i = 0; i < 30; ++i) if (v.tick(now += 20)) shoot(v);
        CHECK(v.stats().atlasFrames >= 1);
    }
    // The land follows a letter 60-160 ms later; at rest nothing is due.
    {
        FixedText t;
        GlyphStripView v(t);
        setup(v, 1.0);
        shoot(v);
        std::int64_t now = 10'000;
        v.key(GlyphKey::Letter, now);
        const int d = v.nextDelayMs(now);
        CHECK(d >= 0 && d <= 160);
        // The window's loop: a tick that changed the picture is drawn again.
        int drawn = 0;
        for (int i = 0; i < 1700; ++i) {
            if (v.tick(now)) { shoot(v); ++drawn; }
            now += 20;
        }
        CHECK(drawn > 0);
        // Long after: no cats walking, no land due, the atlas filled, the bird has landed and the strip rests.
        CHECK(v.nextDelayMs(now) == -1);
    }
    // Hit tests: a word, the arrow, nothing.
    {
        FixedText t;
        GlyphStripView v(t);
        setup(v, 1.0);
        const GlyphSize s = v.size();
        CHECK(v.hit(v.firstWordX() + 2, 55).kind == GlyphHitKind::Candidate);
        CHECK(v.hit(v.firstWordX() + 2, 55).index == 0);
        CHECK(v.hit(s.width - 12, 55).kind == GlyphHitKind::Arrow);
        CHECK(v.hit(120, 3).kind == GlyphHitKind::None);
    }
    // Game: the one line, 24 pt.
    {
        FixedText t;
        GlyphStripView v(t);
        setup(v, 1.0);
        v.setGame(true);
        CHECK(v.size().height == 24.0);
        CHECK(anyOpaque(shoot(v)));
    }
    // Expanded grows down; too small a bitmap is refused.
    {
        FixedText t;
        GlyphStripView v(t);
        setup(v, 1.0);
        const double h = v.size().height;
        v.setExpanded(true);
        CHECK(v.size().height > h);
        std::vector<std::uint8_t> tiny(16);
        CHECK(!v.present(tiny.data(), 16, 2, 2));
    }
    // At night the shadow is darker (0.6).
    {
        FixedText t;
        GlyphStripView v(t);
        setup(v, 1.0, true);
        const Pic p = shoot(v);
        CHECK(p.px[(static_cast<std::size_t>(p.h / 2) * p.w + v.size().stripWidth + 1) * 4 + 3] == 153);
    }
    // Safe drawing: the words sit on paper (no halo), the strip is still drawn.
    {
        FixedText t;
        GlyphStripView v(t);
        setup(v, 1.0);
        GlyphLook look;
        look.safe = true;
        look.still = true;
        v.setLook(look);
        CHECK(anyOpaque(shoot(v)));
    }
    if (failures == 0) std::printf("glyph_strip_test passed\n");
    return failures == 0 ? 0 : 1;
}
