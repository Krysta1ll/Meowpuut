#ifdef _WIN32
#include "GlyphText.h"

#include <dwrite.h>
#include <dwrite_1.h>

#include <algorithm>
#include <cmath>
#include <map>
#include <vector>

namespace meowpuut::ui {
namespace {

template <class T>
class Ptr {
public:
    Ptr() = default;
    ~Ptr() { reset(); }
    Ptr(const Ptr &) = delete;
    Ptr &operator=(const Ptr &) = delete;
    Ptr(Ptr &&o) noexcept : p_(o.p_) { o.p_ = nullptr; }
    Ptr &operator=(Ptr &&o) noexcept {
        if (this != &o) { reset(); p_ = o.p_; o.p_ = nullptr; }
        return *this;
    }
    T *get() const { return p_; }
    T *operator->() const { return p_; }
    explicit operator bool() const { return p_ != nullptr; }
    T **put() { reset(); return &p_; }
    void reset() { if (p_) { p_->Release(); p_ = nullptr; } }

private:
    T *p_ = nullptr;
};

std::wstring widen(std::string_view s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<std::size_t>(std::max(0, n)), L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

const wchar_t kFamilyA[] = L"Meowpuut Pixel A";
const wchar_t kFamilyB[] = L"Meowpuut Pixel B";

// The files of the private collection, handed to DirectWrite as the collection key (paths joined by '|').
class FileEnumerator final : public IDWriteFontFileEnumerator {
public:
    FileEnumerator(IDWriteFactory *factory, std::vector<std::wstring> paths) : factory_(factory), paths_(std::move(paths)) {}
    ~FileEnumerator() { if (current_) current_->Release(); }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **object) override {
        if (!object) return E_POINTER;
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IDWriteFontFileEnumerator)) {
            *object = static_cast<IDWriteFontFileEnumerator *>(this);
            AddRef();
            return S_OK;
        }
        *object = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return static_cast<ULONG>(InterlockedIncrement(&refs_)); }
    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG n = static_cast<ULONG>(InterlockedDecrement(&refs_));
        if (n == 0) delete this;
        return n;
    }
    HRESULT STDMETHODCALLTYPE MoveNext(BOOL *has) override {
        if (!has) return E_POINTER;
        *has = FALSE;
        if (current_) { current_->Release(); current_ = nullptr; }
        while (++index_ < paths_.size()) {
            if (SUCCEEDED(factory_->CreateFontFileReference(paths_[index_].c_str(), nullptr, &current_)) && current_) {
                *has = TRUE;
                break;
            }
            current_ = nullptr;
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetCurrentFontFile(IDWriteFontFile **file) override {
        if (!file) return E_POINTER;
        *file = current_;
        if (current_) current_->AddRef();
        return current_ ? S_OK : E_FAIL;
    }

private:
    LONG refs_ = 1;
    IDWriteFactory *factory_;
    std::vector<std::wstring> paths_;
    std::size_t index_ = static_cast<std::size_t>(-1);
    IDWriteFontFile *current_ = nullptr;
};

class CollectionLoader final : public IDWriteFontCollectionLoader {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **object) override {
        if (!object) return E_POINTER;
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IDWriteFontCollectionLoader)) {
            *object = static_cast<IDWriteFontCollectionLoader *>(this);
            AddRef();
            return S_OK;
        }
        *object = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return static_cast<ULONG>(InterlockedIncrement(&refs_)); }
    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG n = static_cast<ULONG>(InterlockedDecrement(&refs_));
        if (n == 0) delete this;
        return n;
    }
    HRESULT STDMETHODCALLTYPE CreateEnumeratorFromKey(IDWriteFactory *factory, const void *key, UINT32 size,
                                                      IDWriteFontFileEnumerator **out) override {
        if (!factory || !key || !out) return E_POINTER;
        const std::wstring all(static_cast<const wchar_t *>(key), size / sizeof(wchar_t));
        std::vector<std::wstring> paths;
        std::size_t from = 0;
        while (from <= all.size()) {
            const std::size_t bar = all.find(L'|', from);
            const std::wstring one = all.substr(from, bar == std::wstring::npos ? std::wstring::npos : bar - from);
            if (!one.empty()) paths.push_back(one);
            if (bar == std::wstring::npos) break;
            from = bar + 1;
        }
        *out = new FileEnumerator(factory, std::move(paths));
        return S_OK;
    }

private:
    LONG refs_ = 1;
};

// Forwards the layout's glyph runs to the bitmap render target (white on black: the mask is read back from it).
class MaskRenderer : public IDWriteTextRenderer {
public:
    MaskRenderer(IDWriteBitmapRenderTarget *target, IDWriteRenderingParams *params) : target_(target), params_(params) {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **object) override {
        if (!object) return E_POINTER;
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IDWritePixelSnapping) || iid == __uuidof(IDWriteTextRenderer)) {
            *object = static_cast<IDWriteTextRenderer *>(this);
            return S_OK;
        }
        *object = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; } // lives on the stack of raster()
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE IsPixelSnappingDisabled(void *, BOOL *disabled) override {
        *disabled = FALSE;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetCurrentTransform(void *, DWRITE_MATRIX *m) override {
        m->m11 = 1; m->m12 = 0; m->m21 = 0; m->m22 = 1; m->dx = 0; m->dy = 0;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetPixelsPerDip(void *, FLOAT *ppd) override {
        *ppd = 1.0f;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE DrawGlyphRun(void *, FLOAT x, FLOAT y, DWRITE_MEASURING_MODE mode, const DWRITE_GLYPH_RUN *run,
                                           const DWRITE_GLYPH_RUN_DESCRIPTION *, IUnknown *) override {
        return target_->DrawGlyphRun(x, y, mode, run, params_, RGB(255, 255, 255), nullptr);
    }
    HRESULT STDMETHODCALLTYPE DrawUnderline(void *, FLOAT, FLOAT, const DWRITE_UNDERLINE *, IUnknown *) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE DrawStrikethrough(void *, FLOAT, FLOAT, const DWRITE_STRIKETHROUGH *, IUnknown *) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE DrawInlineObject(void *, FLOAT, FLOAT, IDWriteInlineObject *, BOOL, BOOL, IUnknown *) override { return S_OK; }

private:
    IDWriteBitmapRenderTarget *target_;
    IDWriteRenderingParams *params_;
};

} // namespace

struct DirectWriteGlyphText::Impl {
    Ptr<IDWriteFactory> factory;
    Ptr<IDWriteGdiInterop> interop;
    Ptr<IDWriteFontCollection> collection;
    Ptr<IDWriteFontFace> faceA, faceB;
    Ptr<IDWriteRenderingParams> aliased;
    Ptr<IDWriteBitmapRenderTarget> target;
    CollectionLoader *loader = nullptr;
    int targetW = 0, targetH = 0;
    std::wstring locale = L"zh-TW";
    std::map<int, Ptr<IDWriteTextFormat>> formats; // by pixel size
    std::map<std::string, double> widths;

    ~Impl() {
        target.reset();
        formats.clear();
        collection.reset();
        if (loader) {
            if (factory) factory->UnregisterFontCollectionLoader(loader);
            loader->Release();
        }
    }

    static bool faceOf(IDWriteFontCollection *c, const wchar_t *family, IDWriteFontFace **out) {
        UINT32 index = 0;
        BOOL exists = FALSE;
        if (FAILED(c->FindFamilyName(family, &index, &exists)) || !exists) return false;
        Ptr<IDWriteFontFamily> fam;
        Ptr<IDWriteFont> font;
        return SUCCEEDED(c->GetFontFamily(index, fam.put())) &&
               SUCCEEDED(fam->GetFirstMatchingFont(DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STRETCH_NORMAL, DWRITE_FONT_STYLE_NORMAL, font.put())) &&
               SUCCEEDED(font->CreateFontFace(out));
    }

    bool has(IDWriteFontFace *face, UINT32 cp) const {
        if (!face) return false;
        UINT16 g = 0;
        return SUCCEEDED(face->GetGlyphIndices(&cp, 1, &g)) && g != 0;
    }

    IDWriteTextFormat *format(int px) {
        auto it = formats.find(px);
        if (it != formats.end()) return it->second.get();
        Ptr<IDWriteTextFormat> f;
        if (FAILED(factory->CreateTextFormat(kFamilyA, collection.get(), DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL,
                                             DWRITE_FONT_STRETCH_NORMAL, static_cast<FLOAT>(px), locale.c_str(), f.put())))
            return nullptr;
        f->SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP);
        IDWriteTextFormat *raw = f.get();
        formats.emplace(px, std::move(f));
        return raw;
    }

    // One line at `px`, each code point in the first of Cubic 11 and Fusion Pixel that has it (else the system's fallback).
    bool layout(std::string_view utf8, int px, IDWriteTextLayout **out) {
        IDWriteTextFormat *f = format(px);
        if (!f) return false;
        const std::wstring w = widen(utf8);
        if (w.empty()) return false;
        if (FAILED(factory->CreateTextLayout(w.c_str(), static_cast<UINT32>(w.size()), f, 8192.0f, static_cast<FLOAT>(px * 2), out)))
            return false;
        if (faceB) {
            for (UINT32 i = 0; i < w.size();) {
                UINT32 cp = w[i], len = 1;
                if (cp >= 0xD800 && cp < 0xDC00 && i + 1 < w.size() && w[i + 1] >= 0xDC00 && w[i + 1] < 0xE000) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (static_cast<UINT32>(w[i + 1]) - 0xDC00);
                    len = 2;
                }
                if (!has(faceA.get(), cp) && has(faceB.get(), cp)) {
                    DWRITE_TEXT_RANGE r;
                    r.startPosition = i;
                    r.length = len;
                    (*out)->SetFontCollection(collection.get(), r);
                    (*out)->SetFontFamilyName(kFamilyB, r);
                }
                i += len;
            }
        }
        return true;
    }

    bool ensureTarget(int w, int h) {
        if (target && w <= targetW && h <= targetH) return true;
        w = std::max(w, targetW);
        h = std::max(h, targetH);
        target.reset();
        if (FAILED(interop->CreateBitmapRenderTarget(nullptr, static_cast<UINT32>(w), static_cast<UINT32>(h), target.put()))) return false;
        target->SetPixelsPerDip(1.0f);
        Ptr<IDWriteBitmapRenderTarget1> t1;
        if (SUCCEEDED(target->QueryInterface(__uuidof(IDWriteBitmapRenderTarget1), reinterpret_cast<void **>(t1.put()))))
            t1->SetTextAntialiasMode(DWRITE_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        targetW = w;
        targetH = h;
        return true;
    }
};

DirectWriteGlyphText::DirectWriteGlyphText() : impl_(std::make_unique<Impl>()) {}
DirectWriteGlyphText::~DirectWriteGlyphText() = default;

std::unique_ptr<DirectWriteGlyphText> DirectWriteGlyphText::create(const std::wstring &fontDirectory) {
    std::unique_ptr<DirectWriteGlyphText> self(new DirectWriteGlyphText());
    Impl &m = *self->impl_;
    // Isolated: the collection loader is this factory's alone, whatever else the host program does with DirectWrite.
    if (FAILED(DWriteCreateFactory(DWRITE_FACTORY_TYPE_ISOLATED, __uuidof(IDWriteFactory), reinterpret_cast<IUnknown **>(m.factory.put()))))
        return nullptr;
    const std::wstring a = fontDirectory + L"\\pixel_a.ttf", b = fontDirectory + L"\\pixel_b.otf";
    if (GetFileAttributesW(a.c_str()) == INVALID_FILE_ATTRIBUTES) return nullptr;
    std::wstring key = a;
    if (GetFileAttributesW(b.c_str()) != INVALID_FILE_ATTRIBUTES) key += L"|" + b;
    m.loader = new CollectionLoader();
    if (FAILED(m.factory->RegisterFontCollectionLoader(m.loader))) {
        m.loader->Release();
        m.loader = nullptr;
        return nullptr;
    }
    if (FAILED(m.factory->CreateCustomFontCollection(m.loader, key.c_str(), static_cast<UINT32>(key.size() * sizeof(wchar_t)), m.collection.put())))
        return nullptr;
    if (!Impl::faceOf(m.collection.get(), kFamilyA, m.faceA.put())) return nullptr;
    Impl::faceOf(m.collection.get(), kFamilyB, m.faceB.put()); // optional
    if (FAILED(m.factory->GetGdiInterop(m.interop.put()))) return nullptr;
    // Aliased: every pixel is either covered or not, as the font's 12 px grid is drawn.
    if (FAILED(m.factory->CreateCustomRenderingParams(1.0f, 0.0f, 0.0f, DWRITE_PIXEL_GEOMETRY_FLAT, DWRITE_RENDERING_MODE_ALIASED,
                                                      m.aliased.put())))
        return nullptr;
    return self;
}

void DirectWriteGlyphText::setSimplified(bool on) {
    Impl &m = *impl_;
    const std::wstring next = on ? L"zh-CN" : L"zh-TW";
    if (next == m.locale) return;
    m.locale = next;
    m.formats.clear();
}

double DirectWriteGlyphText::measure(std::string_view utf8) {
    Impl &m = *impl_;
    const std::string key(utf8);
    if (auto it = m.widths.find(key); it != m.widths.end()) return it->second;
    double width = 0;
    Ptr<IDWriteTextLayout> layout;
    if (m.layout(utf8, 12, layout.put())) {
        DWRITE_TEXT_METRICS metrics{};
        if (SUCCEEDED(layout->GetMetrics(&metrics))) width = static_cast<double>(metrics.widthIncludingTrailingWhitespace);
    }
    if (m.widths.size() > 4096) m.widths.clear();
    m.widths[key] = width;
    return width;
}

bool DirectWriteGlyphText::raster(std::string_view utf8, unsigned k, GlyphMask &out) {
    Impl &m = *impl_;
    out = GlyphMask{};
    const int px = 12 * static_cast<int>(std::max(1u, k));
    Ptr<IDWriteTextLayout> layout;
    if (!m.layout(utf8, px, layout.put())) return false;
    DWRITE_TEXT_METRICS metrics{};
    if (FAILED(layout->GetMetrics(&metrics))) return false;
    DWRITE_LINE_METRICS line{};
    UINT32 lines = 0;
    layout->GetLineMetrics(&line, 1, &lines);
    const int pad = px;                       // room for a glyph that leans out of its advance
    const int baselineRow = px * 2;           // pixels above the baseline the bitmap holds
    const int w = static_cast<int>(std::ceil(metrics.widthIncludingTrailingWhitespace)) + 2 * pad;
    const int h = baselineRow + px * 2;
    if (w <= 0 || w > 8192 || !m.ensureTarget(w, h)) return false;
    HDC dc = m.target->GetMemoryDC();
    BitBlt(dc, 0, 0, m.targetW, m.targetH, nullptr, 0, 0, BLACKNESS);
    MaskRenderer renderer(m.target.get(), m.aliased.get());
    const FLOAT baseline = lines > 0 ? line.baseline : static_cast<FLOAT>(px) * 10.0f / 12.0f;
    if (FAILED(layout->Draw(nullptr, &renderer, static_cast<FLOAT>(pad), static_cast<FLOAT>(baselineRow) - baseline))) return false;

    DIBSECTION ds{};
    HGDIOBJ bitmap = GetCurrentObject(dc, OBJ_BITMAP);
    if (!bitmap || GetObjectW(bitmap, sizeof ds, &ds) < static_cast<int>(sizeof(BITMAP)) || !ds.dsBm.bmBits || ds.dsBm.bmBitsPixel != 32)
        return false;
    const auto *bits = static_cast<const std::uint8_t *>(ds.dsBm.bmBits);
    const int stride = ds.dsBm.bmWidthBytes, bw = ds.dsBm.bmWidth, bh = ds.dsBm.bmHeight;
    const bool topDown = ds.dsBmih.biHeight < 0;
    const auto at = [&](int x, int y) {
        const int row = topDown ? y : bh - 1 - y;
        const std::uint8_t *p = bits + static_cast<std::ptrdiff_t>(row) * stride + x * 4;
        return std::max({p[0], p[1], p[2]}) >= 128;
    };
    int x0 = bw, y0 = bh, x1 = -1, y1 = -1;
    const int rw = std::min(bw, m.targetW), rh = std::min(bh, m.targetH);
    for (int y = 0; y < rh; ++y)
        for (int x = 0; x < rw; ++x)
            if (at(x, y)) {
                x0 = std::min(x0, x); x1 = std::max(x1, x);
                y0 = std::min(y0, y); y1 = std::max(y1, y);
            }
    if (x1 < 0) return true; // a blank line (a space): no pixels
    out.left = x0 - pad;
    out.top = y0 - baselineRow;
    out.width = x1 - x0 + 1;
    out.height = y1 - y0 + 1;
    out.on.assign(static_cast<std::size_t>(out.width) * static_cast<std::size_t>(out.height), 0);
    for (int y = 0; y < out.height; ++y)
        for (int x = 0; x < out.width; ++x) out.on[static_cast<std::size_t>(y) * static_cast<std::size_t>(out.width) + static_cast<std::size_t>(x)] = at(x0 + x, y0 + y) ? 1 : 0;
    return true;
}

} // namespace meowpuut::ui
#endif
