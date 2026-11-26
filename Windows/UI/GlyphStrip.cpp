#include "GlyphStrip.h"

#ifndef MEOWPUUT_GLYPH
#define MEOWPUUT_GLYPH 1
#endif
#include "../../Engine/crates/meowpuut-ffi/include/meowpuut.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <unordered_map>

namespace meowpuut::ui {
namespace {

constexpr int kStepMs = 1000 / 12;          // the cats and the bird tick twelve times a second while busy
constexpr int kFillMs = 16;                 // one more atlas frame per tick while the strip is idle
constexpr std::size_t kAtlasCap = 40u << 20; // bytes; past it the frames far from the selection are let go
constexpr std::size_t kMaskCap = 1024;
constexpr double kPad = 8, kRow = 55, kRowHeight = 22; // strip.rs: PAD, ROW, ROW_H
constexpr int kHaloReach = 3;               // 0 < a^2 + b^2 <= 10: |a|, |b| <= 3

struct MaskEntry {
    GlyphMask text, halo;
    bool haloBuilt = false;
    bool ok = false;
};

// The text dilated by every offset (a, b) * k with 0 < a^2 + b^2 <= 10 (meowpuut.h, "The halo").
GlyphMask makeHalo(const GlyphMask &m, unsigned k) {
    GlyphMask h;
    if (m.width <= 0 || m.height <= 0) return h;
    const int reach = kHaloReach * static_cast<int>(k);
    h.left = m.left - reach;
    h.top = m.top - reach;
    h.width = m.width + 2 * reach;
    h.height = m.height + 2 * reach;
    h.on.assign(static_cast<std::size_t>(h.width) * static_cast<std::size_t>(h.height), 0);
    for (int b = -kHaloReach; b <= kHaloReach; ++b) {
        for (int a = -kHaloReach; a <= kHaloReach; ++a) {
            const int d = a * a + b * b;
            if (d == 0 || d > 10) continue;
            const int dx = a * static_cast<int>(k) + reach, dy = b * static_cast<int>(k) + reach;
            for (int y = 0; y < m.height; ++y) {
                const std::uint8_t *src = &m.on[static_cast<std::size_t>(y) * static_cast<std::size_t>(m.width)];
                std::uint8_t *dst = &h.on[static_cast<std::size_t>(y + dy) * static_cast<std::size_t>(h.width) +
                                          static_cast<std::size_t>(dx)];
                for (int x = 0; x < m.width; ++x) dst[x] = static_cast<std::uint8_t>(dst[x] | src[x]);
            }
        }
    }
    return h;
}

// One pixel of straight ARGB over a premultiplied BGRA bitmap.
inline void plot(std::uint8_t *px, std::uint32_t argb) {
    const unsigned a = argb >> 24;
    if (a == 0) return;
    const unsigned r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
    if (a == 255) {
        px[0] = static_cast<std::uint8_t>(b);
        px[1] = static_cast<std::uint8_t>(g);
        px[2] = static_cast<std::uint8_t>(r);
        px[3] = 255;
        return;
    }
    const unsigned inv = 255 - a;
    px[0] = static_cast<std::uint8_t>((b * a + px[0] * inv + 127) / 255);
    px[1] = static_cast<std::uint8_t>((g * a + px[1] * inv + 127) / 255);
    px[2] = static_cast<std::uint8_t>((r * a + px[2] * inv + 127) / 255);
    px[3] = static_cast<std::uint8_t>(a + (px[3] * inv + 127) / 255);
}

void stamp(std::uint8_t *dst, std::size_t stride, unsigned width, unsigned height, const GlyphMask &m, int penX,
           int baseline, std::uint32_t argb) {
    if ((argb >> 24) == 0 || m.width <= 0) return;
    for (int y = 0; y < m.height; ++y) {
        const int py = baseline + m.top + y;
        if (py < 0 || py >= static_cast<int>(height)) continue;
        const std::uint8_t *row = &m.on[static_cast<std::size_t>(y) * static_cast<std::size_t>(m.width)];
        for (int x = 0; x < m.width; ++x) {
            if (!row[x]) continue;
            const int px = penX + m.left + x;
            if (px < 0 || px >= static_cast<int>(width)) continue;
            plot(dst + static_cast<std::size_t>(py) * stride + static_cast<std::size_t>(px) * 4, argb);
        }
    }
}

} // namespace

struct GlyphStripView::Impl {
    explicit Impl(GlyphTextEngine &t) : text(t), strip(mp_glyph_strip_create()) {}
    ~Impl() { mp_glyph_strip_free(strip); }

    GlyphTextEngine &text;
    MPGlyphStrip *strip;
    unsigned k = 1;
    bool zhuyin = false, game = false, expanded = false;
    GlyphLook look;
    bool haveLook = false;
    std::int64_t distance = -1;

    std::vector<std::string> texts;
    std::vector<bool> japanese;
    std::vector<MPGlyphStripCellIn> measured;
    std::vector<MPGlyphStripCell> cells;
    std::size_t sel = 0;

    // The atlas: frames[i] is the LAND layer with the sun behind word i (a transparent-cornered opaque picture).
    // Valid only while landClean; sunMoved says the strip's LAND dirty bit is just the selection's sun.
    std::vector<std::vector<std::uint8_t>> frames;
    std::size_t atlasBytes = 0;
    unsigned atlasW = 0, atlasH = 0; // what the frames measure
    bool landClean = false, sunMoved = false;
    std::vector<std::uint8_t> overlay;

    std::unordered_map<std::string, MaskEntry> masks;

    std::int64_t lastKeyMs = -1, landAtMs = 0, lastStepMs = 0;
    bool landPending = false;

    // ---- helpers --------------------------------------------------------------------------------------
    MPGlyphStripSize sizeNow() const {
        MPGlyphStripSize s{};
        mp_glyph_strip_size(strip, &s);
        return s;
    }

    std::size_t frameCount() const { return std::max<std::size_t>(1, texts.size()); }

    void relayoutChanged() {
        landClean = false;
        sunMoved = false;
        clearStale();
    }

    // The land the window shows between a letter key and the land step: the selected frame of the layout before the key.
    std::vector<std::uint8_t> stale;
    unsigned staleW = 0, staleH = 0;
    bool catchUp = false;
    std::int64_t catchAtMs = -1;

    void clearStale() {
        std::vector<std::uint8_t>().swap(stale);
        catchUp = false;
        catchAtMs = -1;
    }

    unsigned shadow() const { return 2 * k; }

    // The strip's hard drop shadow, in the window's bands right of and below the strip (and the notched corners):
    // black, 0.2 alpha (0.6 at night), premultiplied, the strip's outline moved 2 pt right and down. Reads the
    // silhouette from pixels of alpha >= 200 (the shadow itself is never above 153).
    void bakeShadow(std::uint8_t *dst, std::size_t stride, std::size_t pw, std::size_t ph) const {
        const std::size_t off = shadow(), c = 6 * static_cast<std::size_t>(k);
        const std::uint8_t sa = look.dark ? 153 : 51;
        const auto px = [&](std::size_t x, std::size_t y) { return dst + y * stride + x * 4; };
        const auto one = [&](std::size_t x, std::size_t y) {
            if (x < off || y < off) return;
            std::uint8_t *d = px(x, y);
            if (d[3] != 0) return;
            const std::size_t sx = x - off, sy = y - off;
            if (sx >= pw || sy >= ph || px(sx, sy)[3] < 200) return;
            d[0] = d[1] = d[2] = 0;
            d[3] = sa;
        };
        const std::size_t W = pw + off, H = ph + off;
        for (std::size_t y = off; y < H; ++y) // the right band
            for (std::size_t x = pw; x < W; ++x) one(x, y);
        for (std::size_t y = ph; y < H; ++y) // the bottom band
            for (std::size_t x = off; x < pw; ++x) one(x, y);
        for (std::size_t cy : {std::size_t{0}, ph > c ? ph - c : 0}) // the four notched corners
            for (std::size_t cx : {std::size_t{0}, pw > c ? pw - c : 0})
                for (std::size_t y = cy; y < cy + c && y < ph; ++y)
                    for (std::size_t x = cx; x < cx + c && x < pw; ++x) one(x, y);
    }

    const MaskEntry &mask(const std::string &text_, bool withHalo) {
        std::string key = text_;
        key.push_back('\0');
        key += static_cast<char>('0' + k);
        auto it = masks.find(key);
        if (it == masks.end()) {
            if (masks.size() >= kMaskCap) masks.clear();
            MaskEntry e;
            e.ok = text.raster(text_, k, e.text);
            it = masks.emplace(std::move(key), std::move(e)).first;
        }
        MaskEntry &e = it->second;
        if (withHalo && e.ok && !e.haloBuilt) {
            e.halo = makeHalo(e.text, k);
            e.haloBuilt = true;
        }
        return e;
    }

    void dropAtlas() {
        frames.clear();
        atlasBytes = 0;
    }

    // Draws one LAND frame into the atlas. The selected word's frame is the strip's current land (-1), which marks the
    // layer drawn; the others are the land with the sun moved.
    bool drawFrame(std::size_t i) {
        const MPGlyphStripSize s = sizeNow();
        if (s.pixel_width == 0 || s.pixel_height == 0) return false;
        if (frames.size() != frameCount()) {
            dropAtlas();
            frames.resize(frameCount());
        }
        const std::size_t bytes = static_cast<std::size_t>(s.pixel_width) * s.pixel_height * 4;
        if (atlasBytes + bytes > kAtlasCap) {
            // Too much: keep the selected word's frame, the others are drawn again when wanted.
            for (std::size_t j = 0; j < frames.size(); ++j) {
                if (j == sel || j == i || frames[j].empty()) continue;
                atlasBytes -= frames[j].size();
                std::vector<std::uint8_t>().swap(frames[j]);
            }
        }
        std::vector<std::uint8_t> &f = frames[i];
        atlasBytes -= f.size();
        f.assign(bytes, 0);
        const bool current = texts.empty() || i == sel;
        const int rc = mp_glyph_strip_render(strip, MP_GLYPH_STRIP_LAND, current ? -1 : static_cast<int32_t>(i),
                                             MP_GLYPH_STRIP_BGRA_PREMUL, f.data(), s.pixel_width * 4u, s.pixel_width,
                                             s.pixel_height);
        if (rc != MP_GLYPH_STRIP_OK) {
            std::vector<std::uint8_t>().swap(f);
            return false;
        }
        atlasBytes += f.size();
        atlasW = s.pixel_width;
        atlasH = s.pixel_height;
        return true;
    }

    // The nearest word to the selection that has no frame yet.
    bool missingFrame(std::size_t &which) const {
        if (!landClean || texts.empty() || frames.size() != texts.size()) return false;
        for (std::size_t d = 0; d < frames.size(); ++d) {
            if (sel + d < frames.size() && frames[sel + d].empty()) { which = sel + d; return true; }
            if (d > 0 && sel >= d && frames[sel - d].empty()) { which = sel - d; return true; }
        }
        return false;
    }

    void readCells() {
        const std::size_t n = mp_glyph_strip_cell_count(strip);
        cells.assign(n, MPGlyphStripCell{});
        for (std::size_t i = 0; i < n; ++i) mp_glyph_strip_cell(strip, i, &cells[i]);
    }

    void options() { mp_glyph_strip_set_options(strip, (zhuyin ? static_cast<std::uint32_t>(MP_GLYPH_STRIP_ZHUYIN) : 0u) | (game ? static_cast<std::uint32_t>(MP_GLYPH_STRIP_GAME) : 0u), 0); }
};

GlyphStripView::GlyphStripView(GlyphTextEngine &text) : impl_(std::make_unique<Impl>(text)) {}
GlyphStripView::~GlyphStripView() = default;

bool GlyphStripView::valid() const { return impl_->strip != nullptr; }

void GlyphStripView::setLook(const GlyphLook &look) {
    Impl &m = *impl_;
    if (m.haveLook && look.dark == m.look.dark && look.level == m.look.level && look.still == m.look.still && look.safe == m.look.safe)
        return;
    m.look = look;
    m.haveLook = true;
    mp_glyph_strip_set_theme(m.strip, look.dark ? 1 : 0, look.level, look.still ? 1 : 0, look.safe ? 1 : 0);
    m.relayoutChanged();
}

unsigned GlyphStripView::setScale(double scale) {
    const int k = mp_glyph_strip_set_scale(impl_->strip, scale);
    const unsigned next = k >= 1 ? static_cast<unsigned>(k) : 1u;
    if (next != impl_->k) {
        impl_->k = next;
        impl_->dropAtlas();
        impl_->relayoutChanged();
    }
    return impl_->k;
}

unsigned GlyphStripView::k() const { return impl_->k; }

void GlyphStripView::setDistance(std::int64_t committedCharacters) {
    Impl &m = *impl_;
    const std::int64_t characters = std::max<std::int64_t>(0, committedCharacters);
    if (characters == m.distance) return;
    m.distance = characters;
    mp_glyph_strip_set_characters(m.strip, characters);
    m.relayoutChanged();
}

void GlyphStripView::setClock(int hm, std::int64_t nowMs) { mp_glyph_strip_set_clock(impl_->strip, hm, nowMs); }

void GlyphStripView::setZhuyin(bool on) {
    if (impl_->zhuyin == on) return;
    impl_->zhuyin = on;
    impl_->options();
    impl_->relayoutChanged();
}

void GlyphStripView::setGame(bool on) {
    if (impl_->game == on) return;
    impl_->game = on;
    impl_->options();
    impl_->dropAtlas();
    impl_->relayoutChanged();
}

void GlyphStripView::setExpanded(bool on) {
    if (impl_->expanded == on) return;
    impl_->expanded = on;
    mp_glyph_strip_expand(impl_->strip, on ? 1 : 0);
    impl_->dropAtlas();
    impl_->relayoutChanged();
}

void GlyphStripView::setCandidates(const std::vector<std::string> &texts, const std::vector<bool> &japanese,
                                   std::size_t selected) {
    Impl &m = *impl_;
    if (texts == m.texts && japanese == m.japanese) { // the same page: only the highlight may have moved
        select(selected);
        return;
    }
    m.japanese = japanese;
    std::vector<MPGlyphStripCellIn> in;
    in.reserve(texts.size());
    for (std::size_t i = 0; i < texts.size(); ++i) {
        MPGlyphStripCellIn c{};
        c.number_w = m.text.measure(std::to_string(i + 1));
        c.text_w = m.text.measure(texts[i]);
        c.paw_w = i < japanese.size() && japanese[i] ? MP_GLYPH_STRIP_PAW_W : 0;
        in.push_back(c);
    }
    bool same = in.size() == m.measured.size();
    for (std::size_t i = 0; same && i < in.size(); ++i)
        same = in[i].number_w == m.measured[i].number_w && in[i].text_w == m.measured[i].text_w && in[i].paw_w == m.measured[i].paw_w;
    if (!same) {
        // Keep the land on screen: the ground follows at the land step (see the header).
        const MPGlyphStripSize before = m.sizeNow();
        std::vector<std::uint8_t> keep;
        if (m.sel < m.frames.size() && !m.frames[m.sel].empty() && m.atlasW == before.pixel_width && m.atlasH == before.pixel_height)
            keep = std::move(m.frames[m.sel]);
        mp_glyph_strip_set_cells(m.strip, in.data(), in.size());
        m.measured = std::move(in);
        m.dropAtlas();
        m.relayoutChanged();
        if (!keep.empty()) {
            m.stale = std::move(keep);
            m.staleW = before.pixel_width;
            m.staleH = before.pixel_height;
            m.catchUp = true;
        }
    }
    m.texts = texts; // the words themselves matter only to the masks
    select(selected);
}

void GlyphStripView::select(std::size_t index) {
    Impl &m = *impl_;
    const std::size_t clamped = m.texts.empty() ? 0 : std::min(index, m.texts.size() - 1);
    if (clamped != m.sel) m.sunMoved = true; // the land differs only by where the sun stands
    m.sel = clamped;
    mp_glyph_strip_select(m.strip, clamped);
}

std::size_t GlyphStripView::selected() const { return impl_->sel; }

void GlyphStripView::key(GlyphKey kind, std::int64_t nowMs) {
    Impl &m = *impl_;
    mp_glyph_strip_key(m.strip, static_cast<std::uint32_t>(kind));
    if (kind != GlyphKey::Other) {
        const double since = m.lastKeyMs < 0 ? 1000.0 : static_cast<double>(nowMs - m.lastKeyMs);
        const double delay = std::clamp(since * 0.6, 60.0, 160.0);
        m.landAtMs = nowMs + static_cast<std::int64_t>(delay);
        m.landPending = true;
    }
    m.lastKeyMs = nowMs;
}

GlyphSize GlyphStripView::size() {
    const MPGlyphStripSize s = impl_->sizeNow();
    GlyphSize out;
    out.width = s.width;
    out.height = s.height;
    out.k = s.k;
    out.stripWidth = s.pixel_width;
    out.stripHeight = s.pixel_height;
    out.pixelWidth = s.pixel_width + (s.pixel_width ? impl_->shadow() : 0);
    out.pixelHeight = s.pixel_height + (s.pixel_height ? impl_->shadow() : 0);
    return out;
}

double GlyphStripView::firstWordX() const {
    MPGlyphStripCell c{};
    if (mp_glyph_strip_cell(impl_->strip, 0, &c) == MP_GLYPH_STRIP_OK) return c.number_x;
    return kPad + 6;
}

bool GlyphStripView::present(std::uint8_t *bgra, std::size_t strideBytes, unsigned width, unsigned height) {
    Impl &m = *impl_;
    if (!m.strip || !bgra) return false;
    const MPGlyphStripSize s = m.sizeNow();
    const unsigned shadow = m.shadow();
    if (s.pixel_width == 0 || s.pixel_height == 0 || width < s.pixel_width + shadow || height < s.pixel_height + shadow ||
        strideBytes < static_cast<std::size_t>(width) * 4)
        return false;
    const std::size_t pw = s.pixel_width, ph = s.pixel_height, row = pw * 4;
    // The shadow's bands start clear.
    for (std::size_t y = 0; y < ph + shadow; ++y) {
        std::uint8_t *line = bgra + y * strideBytes;
        if (y < ph) std::memset(line + row, 0, shadow * 4);
        else std::memset(line, 0, (pw + shadow) * 4);
    }

    // 1. LAND: the atlas frame of the selected word (drawn now when the land changed or the frame is not there yet).
    //    Between a letter key and its land step the previous land stands in, fitted to the new width.
    if (!m.landClean && m.catchUp && !m.stale.empty() && m.staleH == ph) {
        const std::size_t sw = m.staleW, copy = std::min(sw, pw);
        for (std::size_t y = 0; y < ph; ++y) {
            const std::uint8_t *src = m.stale.data() + y * sw * 4;
            std::uint8_t *dst = bgra + y * strideBytes;
            std::memcpy(dst, src, copy * 4);
            for (std::size_t x = copy; x < pw; ++x) std::memcpy(dst + x * 4, src + (sw - 1 - (x - sw) % sw) * 4, 4);
        }
    } else {
        m.clearStale();
        if (!m.landClean || m.frames.size() != m.frameCount()) {
            m.dropAtlas();
            m.frames.resize(m.frameCount());
            if (!m.drawFrame(m.sel)) return false;
            m.landClean = true;
            m.sunMoved = false;
        } else if (m.frames[m.sel].empty()) {
            if (!m.drawFrame(m.sel)) return false;
        }
        const std::vector<std::uint8_t> &land = m.frames[m.sel];
        for (std::size_t y = 0; y < ph; ++y) std::memcpy(bgra + y * strideBytes, land.data() + y * row, row);
    }

    // 2. OVER and CATS (the reflection block, light, arrow and frame; the cats and the bird), in one transparent
    //    buffer, then over the land.
    m.overlay.resize(pw * ph * 4);
    if (mp_glyph_strip_render(m.strip, MP_GLYPH_STRIP_OVER | MP_GLYPH_STRIP_CATS, -1, MP_GLYPH_STRIP_BGRA_PREMUL,
                              m.overlay.data(), row, s.pixel_width, s.pixel_height) != MP_GLYPH_STRIP_OK)
        return false;
    for (std::size_t y = 0; y < ph; ++y) {
        const std::uint8_t *src = m.overlay.data() + y * row;
        std::uint8_t *dst = bgra + y * strideBytes;
        for (std::size_t x = 0; x < pw; ++x, src += 4, dst += 4) {
            std::uint32_t word;
            std::memcpy(&word, src, 4);
            if (word == 0) continue; // most of the overlay is clear
            const unsigned a = src[3];
            if (a == 255) {
                std::memcpy(dst, &word, 4);
                continue;
            }
            const unsigned inv = 255 - a;
            for (int c = 0; c < 4; ++c) dst[c] = static_cast<std::uint8_t>(std::min<unsigned>(255, src[c] + (dst[c] * inv + 127) / 255));
        }
    }

    m.bakeShadow(bgra, strideBytes, pw, ph);

    // 3. The words, over the pixels Rust drew: the halo first, then the number and the text (meowpuut.h).
    m.readCells();
    const int k = static_cast<int>(m.k);
    for (std::size_t i = 0; i < m.cells.size() && i < m.texts.size(); ++i) {
        const MPGlyphStripCell &c = m.cells[i];
        const int baseline = static_cast<int>(std::lround(c.baseline * k));
        const std::string number = std::to_string(i + 1);
        const int numberPen = static_cast<int>(std::lround(c.number_x * k)), textPen = static_cast<int>(std::lround(c.text_x * k));
        const MaskEntry &n = m.mask(number, c.halo_color != 0);
        const MaskEntry &t = m.mask(m.texts[i], c.halo_color != 0);
        if (c.halo_color != 0) {
            if (n.ok) stamp(bgra, strideBytes, width, height, n.halo, numberPen, baseline, c.halo_color);
            if (t.ok) stamp(bgra, strideBytes, width, height, t.halo, textPen, baseline, c.halo_color);
        }
        if (n.ok) stamp(bgra, strideBytes, width, height, n.text, numberPen, baseline, c.number_color);
        if (t.ok) stamp(bgra, strideBytes, width, height, t.text, textPen, baseline, c.text_color);
    }
    return true;
}

int GlyphStripView::nextDelayMs(std::int64_t nowMs) {
    Impl &m = *impl_;
    if (!m.strip) return -1;
    std::int64_t best = -1;
    const auto want = [&](std::int64_t d) {
        d = std::max<std::int64_t>(0, d);
        if (best < 0 || d < best) best = d;
    };
    if (m.landPending) want(m.landAtMs - nowMs);
    else if (m.catchUp) { // a change with no key behind it (a page): the ground follows 60 ms later
        if (m.catchAtMs < 0) m.catchAtMs = nowMs + 60;
        want(m.catchAtMs - nowMs);
    }
    if (mp_glyph_strip_busy(m.strip)) want(m.lastStepMs + kStepMs - nowMs);
    std::size_t which = 0;
    if (m.landClean && !m.landPending && m.missingFrame(which)) want(kFillMs);
    return best < 0 ? -1 : static_cast<int>(best);
}

bool GlyphStripView::tick(std::int64_t nowMs) {
    Impl &m = *impl_;
    if (!m.strip) return false;
    bool changed = false;
    bool landStep = false;
    if (m.landPending && nowMs >= m.landAtMs) {
        m.landPending = false;
        landStep = true;
        if (mp_glyph_strip_land(m.strip) == 1) {
            m.landClean = false;
            m.sunMoved = false;
            changed = true;
        }
    }
    if (m.catchUp && (landStep || (!m.landPending && m.catchAtMs >= 0 && nowMs >= m.catchAtMs))) {
        m.clearStale(); // the new land is drawn by the next present
        changed = true;
    }
    if (mp_glyph_strip_busy(m.strip) && nowMs >= m.lastStepMs + kStepMs) {
        m.lastStepMs = nowMs;
        const std::uint32_t bits = mp_glyph_strip_step(m.strip);
        if (bits & (MP_GLYPH_STRIP_OVER | MP_GLYPH_STRIP_CATS)) changed = true;
        // A LAND bit with no selection since the last full land is a real change (the fog, the journey).
        if ((bits & MP_GLYPH_STRIP_LAND) && m.landClean && !m.sunMoved) {
            m.landClean = false;
            changed = true;
        }
    }
    std::size_t which = 0;
    if (!changed && m.landClean && !m.landPending && m.missingFrame(which) && which < m.frames.size()) m.drawFrame(which);
    return changed;
}

void GlyphStripView::idle() {
    impl_->dropAtlas();
    impl_->overlay.clear();
    impl_->overlay.shrink_to_fit();
    impl_->landClean = false;
    impl_->landPending = false;
    impl_->clearStale();
}

GlyphHit GlyphStripView::hit(double xPt, double yPt) const {
    const Impl &m = *impl_;
    GlyphHit out;
    if (!m.strip) return out;
    MPGlyphStripSize s{};
    mp_glyph_strip_size(m.strip, &s);
    if (!m.game && xPt >= s.width - kPad - 22 && xPt <= s.width && yPt >= kRow - kRowHeight / 2 && yPt <= kRow + kRowHeight / 2) {
        out.kind = GlyphHitKind::Arrow;
        return out;
    }
    const std::size_t n = mp_glyph_strip_cell_count(m.strip);
    for (std::size_t i = 0; i < n; ++i) {
        MPGlyphStripCell c{};
        if (mp_glyph_strip_cell(m.strip, i, &c) != MP_GLYPH_STRIP_OK) continue;
        if (xPt >= c.hit_x && xPt < c.hit_x + c.hit_w && yPt >= c.hit_y && yPt < c.hit_y + c.hit_h) {
            out.kind = GlyphHitKind::Candidate;
            out.index = i;
            return out;
        }
    }
    return out;
}

GlyphStripView::Stats GlyphStripView::stats() const {
    Stats s;
    for (const auto &f : impl_->frames) s.atlasFrames += f.empty() ? 0 : 1;
    s.atlasBytes = impl_->atlasBytes;
    s.masks = impl_->masks.size();
    return s;
}

} // namespace meowpuut::ui
