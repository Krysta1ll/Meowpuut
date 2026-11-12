#ifdef _WIN32
#include "CandidateWindow.h"

#include "Blur.h"
#include "Festival.h"
#include "Scene.h"

#include <d2d1.h>
#include <dwrite.h>
#include <wincodec.h>
#include <windowsx.h>

#include <algorithm>
#include <cfloat>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <list>
#include <map>
#include <optional>
#include <unordered_map>
#include <utility>
#include <vector>

namespace meowpuut::ui {
namespace {

// Minimal COM pointer (no WRL, so MinGW and MSVC build alike).
template <class T>
class Com {
public:
    Com() = default;
    ~Com() { reset(); }
    Com(const Com &o) : p_(o.p_) { if (p_) p_->AddRef(); }
    Com &operator=(const Com &o) {
        if (this != &o) { reset(); p_ = o.p_; if (p_) p_->AddRef(); }
        return *this;
    }
    Com(Com &&o) noexcept : p_(o.p_) { o.p_ = nullptr; }
    Com &operator=(Com &&o) noexcept {
        if (this != &o) { reset(); p_ = o.p_; o.p_ = nullptr; }
        return *this;
    }
    T *get() const { return p_; }
    T *operator->() const { return p_; }
    explicit operator bool() const { return p_ != nullptr; }
    T **put() { reset(); return &p_; }           // for out parameters
    void attach(T *p) { reset(); p_ = p; }       // takes over one reference
    T *detach() { T *p = p_; p_ = nullptr; return p; }
    void reset() { if (p_) { p_->Release(); p_ = nullptr; } }

private:
    T *p_ = nullptr;
};

const wchar_t kClassName[] = L"MeowpuutCandidateWindow";
const UINT kRedrawMessage = WM_APP + 1;

std::wstring widen(std::string_view s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<std::size_t>(std::max(0, n)), L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

// Plain constructors for the Direct2D structs, so the code does not depend on
// which d2d1helper.h conveniences a given SDK (MSVC or MinGW) ships.
D2D1_COLOR_F colorF(float r, float g, float b, float a) {
    D2D1_COLOR_F c;
    c.r = r; c.g = g; c.b = b; c.a = a;
    return c;
}
D2D1_POINT_2F pointF(float x, float y) {
    D2D1_POINT_2F p;
    p.x = x; p.y = y;
    return p;
}
D2D1_RECT_F rectF(float l, float t, float r, float b) {
    D2D1_RECT_F rc;
    rc.left = l; rc.top = t; rc.right = r; rc.bottom = b;
    return rc;
}
D2D1_COLOR_F d2d(const Color &c) { return colorF(c.r, c.g, c.b, c.a); }
D2D1_RECT_F d2d(const RectF &r) { return rectF(r.x, r.y, r.right(), r.bottom()); }
D2D1_POINT_2F d2d(PointF p) { return pointF(p.x, p.y); }
D2D1_MATRIX_3X2_F identity() {
    D2D1_MATRIX_3X2_F m;
    m._11 = 1; m._12 = 0; m._21 = 0; m._22 = 1; m._31 = 0; m._32 = 0;
    return m;
}
// Rotation by `degrees` (clockwise on screen) about (cx, cy).
D2D1_MATRIX_3X2_F rotation(float degrees, float cx, float cy) {
    const float a = degrees * 3.14159265f / 180.0f, c = std::cos(a), s = std::sin(a);
    D2D1_MATRIX_3X2_F m;
    m._11 = c; m._12 = s; m._21 = -s; m._22 = c;
    m._31 = cx - c * cx + s * cy;
    m._32 = cy - s * cx - c * cy;
    return m;
}
D2D1_MATRIX_3X2_F d2d(const Affine &a) {
    D2D1_MATRIX_3X2_F m;
    m._11 = a.a; m._12 = a.b; m._21 = a.c; m._22 = a.d; m._31 = a.e; m._32 = a.f;
    return m;
}
D2D1_PIXEL_FORMAT premultipliedBgra() {
    D2D1_PIXEL_FORMAT f;
    f.format = DXGI_FORMAT_B8G8R8A8_UNORM;
    f.alphaMode = D2D1_ALPHA_MODE_PREMULTIPLIED;
    return f;
}
D2D1_BITMAP_PROPERTIES bitmapProperties() {
    D2D1_BITMAP_PROPERTIES p;
    p.pixelFormat = premultipliedBgra();
    p.dpiX = p.dpiY = 96.0f; // DrawBitmap scales to the destination rectangle
    return p;
}
D2D1_SIZE_U sizeU(UINT w, UINT h) {
    D2D1_SIZE_U s;
    s.width = w; s.height = h;
    return s;
}

template <class Fn>
Fn procAddress(HMODULE module, const char *name) {
    if (!module) return nullptr;
    return reinterpret_cast<Fn>(reinterpret_cast<void (*)()>(GetProcAddress(module, name)));
}

// Per-monitor DPI awareness (v2) for the duration of a call, so the window
// and every coordinate it handles are in physical pixels even inside a host
// process that is not DPI aware. Does nothing before Windows 10 1607.
class DpiScope {
public:
    DpiScope() {
        static const SetFn set = procAddress<SetFn>(GetModuleHandleW(L"user32.dll"), "SetThreadDpiAwarenessContext");
        set_ = set;
        if (set_) previous_ = set_(reinterpret_cast<HANDLE>(static_cast<INT_PTR>(-4))); // PER_MONITOR_AWARE_V2
    }
    ~DpiScope() { if (set_ && previous_) set_(previous_); }
    DpiScope(const DpiScope &) = delete;
    DpiScope &operator=(const DpiScope &) = delete;

private:
    using SetFn = HANDLE(WINAPI *)(HANDLE);
    SetFn set_ = nullptr;
    HANDLE previous_ = nullptr;
};

float monitorScale(HMONITOR monitor) {
    using GetDpiFn = HRESULT(WINAPI *)(HMONITOR, int, UINT *, UINT *);
    static const GetDpiFn getDpi =
        procAddress<GetDpiFn>(LoadLibraryExW(L"shcore.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32), "GetDpiForMonitor");
    UINT x = 96, y = 96;
    if (getDpi && SUCCEEDED(getDpi(monitor, 0 /* MDT_EFFECTIVE_DPI */, &x, &y)) && x > 0)
        return static_cast<float>(x) / 96.0f;
    int dpi = 96;
    if (HDC screen = GetDC(nullptr)) {
        dpi = GetDeviceCaps(screen, LOGPIXELSX);
        ReleaseDC(nullptr, screen);
    }
    return static_cast<float>(dpi > 0 ? dpi : 96) / 96.0f;
}

bool readDword(const wchar_t *path, const wchar_t *name, DWORD &out) {
    DWORD size = sizeof out;
    return RegGetValueW(HKEY_CURRENT_USER, path, name, RRF_RT_REG_DWORD, nullptr, &out, &size) == ERROR_SUCCESS;
}

Color sysColor(int index) {
    const COLORREF c = GetSysColor(index);
    return {static_cast<float>(GetRValue(c)) / 255.0f, static_cast<float>(GetGValue(c)) / 255.0f,
            static_cast<float>(GetBValue(c)) / 255.0f, 1};
}

// The folder of the module this code is linked into: the text service DLL,
// or the preview executable.
std::wstring moduleDirectory() {
    static const int marker = 0; // any address inside this module
    HMODULE module = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(&marker), &module))
        module = nullptr;
    std::wstring path(MAX_PATH, L'\0');
    for (;;) {
        const DWORD n = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
        if (n == 0) return L".";
        if (n < path.size()) { path.resize(n); break; }
        if (path.size() >= 32768) return L".";
        path.resize(path.size() * 2);
    }
    const auto slash = path.find_last_of(L"\\/");
    return slash == std::wstring::npos ? L"." : path.substr(0, slash);
}

// A small file as bytes; empty when it is missing, unreadable or over 1 MB.
std::string readSmallFile(const std::wstring &path) {
    const HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                                    OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) return {};
    std::string bytes;
    LARGE_INTEGER size;
    size.QuadPart = 0;
    if (GetFileSizeEx(file, &size) && size.QuadPart > 0 && size.QuadPart <= (1 << 20)) {
        bytes.resize(static_cast<std::size_t>(size.QuadPart));
        DWORD read = 0;
        if (!ReadFile(file, bytes.data(), static_cast<DWORD>(bytes.size()), &read, nullptr)) read = 0;
        bytes.resize(read);
    }
    CloseHandle(file);
    return bytes;
}

// DirectWrite text. One single-line layout per (weight, size, text) is kept
// with its width and baseline, so typing does not shape the same candidates
// again and again.
class TextCache : public TextMeasurer {
public:
    struct Line {
        IDWriteTextLayout *layout = nullptr; // owned by the cache; valid until the next call
        float width = 0, baseline = 0;
    };

    TextCache(IDWriteFactory *factory, std::wstring family, std::wstring locale)
        : factory_(factory), family_(std::move(family)), locale_(std::move(locale)) {}

    float measure(std::string_view text, float size, bool medium) override { return line(text, size, medium).width; }

    Line line(std::string_view text, float size, bool medium) {
        std::string key;
        key.reserve(text.size() + 16);
        key += medium ? 'M' : 'R';
        key += std::to_string(std::lround(size * 4));
        key += '|';
        key.append(text.data(), text.size());
        if (auto it = index_.find(key); it != index_.end()) {
            order_.splice(order_.begin(), order_, it->second);
            return view(*it->second);
        }
        IDWriteTextFormat *textFormat = format(size, medium);
        if (!textFormat) return {};
        const auto wide = widen(text);
        Entry entry;
        entry.key = key;
        if (FAILED(factory_->CreateTextLayout(wide.c_str(), static_cast<UINT32>(wide.size()), textFormat, 16384.0f,
                                              512.0f, entry.layout.put())))
            return {};
        DWRITE_TEXT_METRICS metrics{};
        if (SUCCEEDED(entry.layout->GetMetrics(&metrics))) entry.width = metrics.widthIncludingTrailingWhitespace;
        DWRITE_LINE_METRICS first{};
        UINT32 lines = 0;
        // E_NOT_SUFFICIENT_BUFFER cannot happen: the format never wraps.
        if (SUCCEEDED(entry.layout->GetLineMetrics(&first, 1, &lines)) && lines > 0) entry.baseline = first.baseline;
        else entry.baseline = size * 0.88f;
        order_.push_front(std::move(entry));
        index_[key] = order_.begin();
        if (order_.size() > kCapacity) {
            index_.erase(order_.back().key);
            order_.pop_back();
        }
        return view(order_.front());
    }

    void setFamily(std::wstring family, std::wstring locale) {
        if (family == family_ && locale == locale_) return;
        family_ = std::move(family);
        locale_ = std::move(locale);
        formats_.clear();
        index_.clear();
        order_.clear();
    }

private:
    static constexpr std::size_t kCapacity = 768;
    struct Entry {
        std::string key;
        Com<IDWriteTextLayout> layout;
        float width = 0, baseline = 0;
    };
    static Line view(const Entry &e) {
        Line l;
        l.layout = e.layout.get();
        l.width = e.width;
        l.baseline = e.baseline;
        return l;
    }
    IDWriteTextFormat *format(float size, bool medium) {
        const long key = std::lround(size * 4) * 2 + (medium ? 1 : 0);
        if (auto it = formats_.find(key); it != formats_.end()) return it->second.get();
        Com<IDWriteTextFormat> f;
        if (FAILED(factory_->CreateTextFormat(family_.c_str(), nullptr,
                                              medium ? DWRITE_FONT_WEIGHT_MEDIUM : DWRITE_FONT_WEIGHT_NORMAL,
                                              DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL, size,
                                              locale_.c_str(), f.put())))
            return nullptr;
        // Candidates stay on one line. Text that is too long is cut in the
        // middle by the layout (ellipsizeMiddle) before it gets here.
        if (FAILED(f->SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP))) return nullptr;
        IDWriteTextFormat *raw = f.get();
        formats_[key] = std::move(f);
        return raw;
    }
    IDWriteFactory *factory_;
    std::wstring family_, locale_;
    std::map<long, Com<IDWriteTextFormat>> formats_;
    std::list<Entry> order_;
    std::unordered_map<std::string, std::list<Entry>::iterator> index_;
};

enum TimerId : UINT_PTR { kCatFrame = 1 };

// How long a cat's answer to a click lasts, in ms.
constexpr ULONGLONG kReactionMs = 600;

// Windows that share the class in this module. A DLL has to unregister its
// classes itself before it unloads. A text service can live on several UI
// threads of one process, hence the lock.
SRWLOCK g_classLock = SRWLOCK_INIT;
int g_classUsers = 0;

} // namespace

struct CandidateWindow::Impl {
    HINSTANCE instance = nullptr;
    HWND hwnd = nullptr;
    CandidateWindowOptions options;
    CandidateWindowCallbacks callbacks;
    bool comInitialized = false;
    bool classRegistered = false;

    CandidateViewModel model;
    bool hasModel = false;
    CandidateLayout layout;
    float scale = 1;
    RECT work{0, 0, 1920, 1080};
    RECT caret{0, 0, 0, 0};
    bool haveCaret = false;
    bool shown = false;
    bool dirty = false;         // the content changed since the last frame
    bool redrawPosted = false;  // kRedrawMessage is in the queue
    bool rendering = false;     // inside render(); guards against re-entry
    POINT position{0, 0};
    SIZE pixels{0, 0};

    bool systemDark = false, transparency = true, highContrast = false;

    // Device-independent.
    Com<ID2D1Factory> d2dFactory;
    Com<IDWriteFactory> dwrite;
    Com<IWICImagingFactory> wic;
    Com<ID2D1StrokeStyle> roundStroke;
    std::unique_ptr<TextCache> text;
    std::wstring assetDir;
    std::map<std::wstring, Com<IWICBitmap>> decoded; // a null entry is a file that failed once

    // The frame is drawn into a top-down DIB section and handed to the
    // layered window from there.
    HDC memDC = nullptr;
    HBITMAP dib = nullptr;
    HGDIOBJ oldBitmap = nullptr;
    UINT dibW = 0, dibH = 0;

    // Device-dependent: dropped together when the target is lost.
    Com<ID2D1DCRenderTarget> rt;
    Com<ID2D1SolidColorBrush> brush;
    std::map<std::wstring, Com<ID2D1Bitmap>> sprites; // key: path|w|h|blur
    struct ShadowEntry {
        std::string key;
        Com<ID2D1Bitmap> bitmap;
        float width = 0, height = 0; // DIPs the bitmap covers
    };
    std::vector<ShadowEntry> shadows; // one per Shadow command of the scene
    std::vector<bool> clips;          // open PushClip commands; true when a layer was pushed

    FestivalManifest manifest;
    std::optional<FestivalTheme> festival;
    LocalDate outfitDay;

    std::optional<std::size_t> hovered;
    Hit pressed;
    bool trackingLeave = false;
    int wheel = 0;
    ULONGLONG petStart[2] = {0, 0}; // GetTickCount64 when each cat was last clicked; 0 when still
    int momoPets = 0;            // pets on 莫莫 in a row
    ULONGLONG lastMomoPet = 0;   // GetTickCount64 of the last one

    // --- setup --------------------------------------------------------------
    void create() {
        // S_FALSE (already initialised) also needs a matching CoUninitialize.
        // RPC_E_CHANGED_MODE means the thread already runs COM another way,
        // which is fine for the in-process objects used here.
        comInitialized = SUCCEEDED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED));
        if (FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, __uuidof(ID2D1Factory), nullptr,
                                     reinterpret_cast<void **>(d2dFactory.put()))))
            d2dFactory.reset();
        if (FAILED(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory),
                                       reinterpret_cast<IUnknown **>(dwrite.put()))))
            dwrite.reset();
        if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, __uuidof(IWICImagingFactory),
                                    reinterpret_cast<void **>(wic.put()))))
            wic.reset();
        if (d2dFactory) {
            D2D1_STROKE_STYLE_PROPERTIES round;
            round.startCap = round.endCap = round.dashCap = D2D1_CAP_STYLE_ROUND;
            round.lineJoin = D2D1_LINE_JOIN_ROUND;
            round.miterLimit = 10.0f;
            round.dashStyle = D2D1_DASH_STYLE_SOLID;
            round.dashOffset = 0.0f;
            if (FAILED(d2dFactory->CreateStrokeStyle(&round, nullptr, 0, roundStroke.put()))) roundStroke.reset();
        }
        if (dwrite) text = std::make_unique<TextCache>(dwrite.get(), fontFamily(), locale());
        assetDir = options.assetDirectory.empty() ? moduleDirectory() + L"\\Assets" : options.assetDirectory;
        loadManifest();
        readSystemTheme();

        WNDCLASSEXW wc{};
        wc.cbSize = sizeof wc;
        wc.lpfnWndProc = &Impl::proc;
        wc.hInstance = instance;
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.lpszClassName = kClassName;
        AcquireSRWLockExclusive(&g_classLock);
        if (RegisterClassExW(&wc) || GetLastError() == ERROR_CLASS_ALREADY_EXISTS) {
            classRegistered = true;
            ++g_classUsers;
        }
        ReleaseSRWLockExclusive(&g_classLock);
        // Created under per-monitor v2, the window stays that way whatever the
        // host process is: its messages and coordinates are in physical pixels.
        DpiScope dpi;
        if (classRegistered)
            CreateWindowExW(WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, kClassName, L"",
                            WS_POPUP, 0, 0, 1, 1, nullptr, nullptr, instance, this); // proc() stores hwnd
        memDC = CreateCompatibleDC(nullptr);
    }

    void destroy() {
        if (hwnd) DestroyWindow(hwnd); // WM_NCDESTROY clears hwnd; timers die with the window
        hwnd = nullptr;
        if (classRegistered) {
            AcquireSRWLockExclusive(&g_classLock);
            if (--g_classUsers == 0) UnregisterClassW(kClassName, instance);
            ReleaseSRWLockExclusive(&g_classLock);
        }
        classRegistered = false;
        discardDevice();
        releaseDib();
        if (memDC) { DeleteDC(memDC); memDC = nullptr; }
        decoded.clear();
        text.reset();
        roundStroke.reset();
        wic.reset();
        dwrite.reset();
        d2dFactory.reset();
        if (comInitialized) CoUninitialize();
        comInitialized = false;
    }

    bool valid() const { return hwnd && memDC && d2dFactory && wic && text; }

    std::wstring fontFamily() const { return options.simplified ? L"Microsoft YaHei UI" : L"Microsoft JhengHei UI"; }
    std::wstring locale() const { return options.simplified ? L"zh-CN" : L"zh-TW"; }

    void readSystemTheme() {
        const wchar_t *personalize = L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
        DWORD value = 1;
        systemDark = readDword(personalize, L"AppsUseLightTheme", value) && value == 0;
        value = 1;
        transparency = !readDword(personalize, L"EnableTransparency", value) || value != 0;
        HIGHCONTRASTW hc{};
        hc.cbSize = sizeof hc;
        highContrast = SystemParametersInfoW(SPI_GETHIGHCONTRAST, sizeof hc, &hc, 0) && (hc.dwFlags & HCF_HIGHCONTRASTON);
    }

    bool dark() const {
        switch (options.tone) {
        case ToneSetting::Light: return false;
        case ToneSetting::Dark: return true;
        case ToneSetting::System: break;
        }
        return systemDark;
    }

    Theme theme() const {
        Theme t = makeTheme(dark() ? Appearance::Dark : Appearance::Light, options.palette, transparency, highContrast,
                            sysColor(COLOR_WINDOW), sysColor(COLOR_WINDOWTEXT), sysColor(COLOR_HIGHLIGHT),
                            sysColor(COLOR_HIGHLIGHTTEXT), sysColor(COLOR_GRAYTEXT));
        if (outfitActive()) applyFestivalPill(t, *festival);
        return t;
    }

    // --- festival outfits ---------------------------------------------------
    void loadManifest() {
        manifest = parseFestivalManifest(readSmallFile(assetDir + L"\\festivals\\festivals.json"));
        outfitDay = {};
        refreshOutfit();
    }

    // Picks the outfit for today; cheap when the date has not changed.
    void refreshOutfit() {
        SYSTEMTIME now{};
        GetLocalTime(&now);
        const LocalDate today{now.wYear, now.wMonth, now.wDay};
        if (today.key() == outfitDay.key()) return;
        outfitDay = today;
        const FestivalTheme *worn = wornTheme(manifest, today, FestivalWardrobe{});
        if (worn) festival = *worn;
        else festival.reset();
    }

    bool outfitActive() const { return options.festivalOutfits && festival.has_value() && !highContrast; }

    // File behind a cat's picture (catArt: where a festival can bring its own).
    std::wstring spritePath(Sprite sprite) const {
        return assetDir + L"\\" + widen(catArt(sprite == Sprite::Mimimo ? Cat::Mimimo : Cat::Momo).image);
    }

    // --- bitmaps ------------------------------------------------------------
    IWICBitmap *decode(const std::wstring &path) {
        if (auto it = decoded.find(path); it != decoded.end()) return it->second.get();
        Com<IWICBitmap> result;
        Com<IWICBitmapDecoder> decoder;
        Com<IWICBitmapFrameDecode> frame;
        Com<IWICFormatConverter> converter;
        if (SUCCEEDED(wic->CreateDecoderFromFilename(path.c_str(), nullptr, GENERIC_READ, WICDecodeMetadataCacheOnDemand,
                                                     decoder.put())) &&
            SUCCEEDED(decoder->GetFrame(0, frame.put())) && SUCCEEDED(wic->CreateFormatConverter(converter.put())) &&
            SUCCEEDED(converter->Initialize(frame.get(), GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr,
                                            0.0, WICBitmapPaletteTypeCustom))) {
            // Decode once into memory; the file can change or vanish later.
            if (FAILED(wic->CreateBitmapFromSource(converter.get(), WICBitmapCacheOnLoad, result.put()))) result.reset();
        }
        IWICBitmap *raw = result.get();
        decoded[path] = std::move(result);
        return raw;
    }

    // The bitmap resampled to exactly w×h device pixels. With a blur it grows
    // by `pad` pixels on every side so the soft edge is not cut off. With a
    // tint only its shape is kept, in the tint's colour.
    ID2D1Bitmap *sprite(const std::wstring &path, UINT w, UINT h, float sigma, UINT pad, const Color &tint) {
        if (path.empty() || w == 0 || h == 0 || w > 4096 || h > 4096 || !rt) return nullptr;
        wchar_t suffix[96];
        std::swprintf(suffix, 96, L"|%ux%u|%d|%02x%02x%02x", w, h, static_cast<int>(std::lround(sigma * 10)),
                      static_cast<unsigned>(std::lround(tint.r * 255 * tint.a)), static_cast<unsigned>(std::lround(tint.g * 255 * tint.a)),
                      static_cast<unsigned>(std::lround(tint.b * 255 * tint.a)));
        const std::wstring key = path + suffix;
        if (auto it = sprites.find(key); it != sprites.end()) return it->second.get();
        Com<ID2D1Bitmap> bitmap;
        if (IWICBitmap *source = decode(path)) {
            Com<IWICBitmapScaler> scaler;
            if (SUCCEEDED(wic->CreateBitmapScaler(scaler.put())) &&
                (SUCCEEDED(scaler->Initialize(source, w, h, static_cast<WICBitmapInterpolationMode>(4))) || // HighQualityCubic
                 SUCCEEDED(scaler->Initialize(source, w, h, WICBitmapInterpolationModeFant)))) {
                const UINT fullW = w + 2 * pad, fullH = h + 2 * pad;
                std::vector<std::uint8_t> px(static_cast<std::size_t>(fullW) * fullH * 4, 0);
                // Straight into the middle of the padded buffer.
                const std::size_t first = (static_cast<std::size_t>(pad) * fullW + pad) * 4;
                if (SUCCEEDED(scaler->CopyPixels(nullptr, fullW * 4, static_cast<UINT>(px.size() - first), px.data() + first))) {
                    if (tint.a > 0)
                        for (std::size_t i = 0; i < px.size(); i += 4) {
                            const float a = px[i + 3];
                            px[i + 0] = static_cast<std::uint8_t>(std::lround(tint.b * a));
                            px[i + 1] = static_cast<std::uint8_t>(std::lround(tint.g * a));
                            px[i + 2] = static_cast<std::uint8_t>(std::lround(tint.r * a));
                        }
                    if (pad > 0) blurPremultiplied(px, static_cast<int>(fullW), static_cast<int>(fullH), sigma);
                    const auto props = bitmapProperties();
                    if (FAILED(rt->CreateBitmap(sizeU(fullW, fullH), px.data(), fullW * 4, &props, bitmap.put())))
                        bitmap.reset();
                }
            }
        }
        ID2D1Bitmap *raw = bitmap.get();
        sprites[key] = std::move(bitmap); // failures are kept too: a broken file is tried once per target
        return raw;
    }

    void drawSprite(const std::wstring &path, const RectF &box, float opacity, float blurDips, const Color &tint) {
        // Whole device pixels, so the art is resampled once and not again by DrawBitmap.
        const float x0 = std::round(box.x * scale), y0 = std::round(box.y * scale);
        const float x1 = std::round(box.right() * scale), y1 = std::round(box.bottom() * scale);
        if (x1 <= x0 || y1 <= y0) return;
        const float sigma = blurDips * scale;
        const UINT pad = sigma >= 0.5f ? static_cast<UINT>(std::ceil(sigma * 3)) : 0;
        if (ID2D1Bitmap *bitmap = sprite(path, static_cast<UINT>(x1 - x0), static_cast<UINT>(y1 - y0), sigma, pad, tint)) {
            const float p = static_cast<float>(pad);
            const auto dest = rectF((x0 - p) / scale, (y0 - p) / scale, (x1 + p) / scale, (y1 + p) / scale);
            rt->DrawBitmap(bitmap, &dest, opacity, D2D1_BITMAP_INTERPOLATION_MODE_LINEAR, nullptr);
        }
    }

    // --- target -------------------------------------------------------------
    void releaseDib() {
        if (dib) {
            if (memDC && oldBitmap) SelectObject(memDC, oldBitmap);
            DeleteObject(dib);
        }
        dib = nullptr;
        oldBitmap = nullptr;
        dibW = dibH = 0;
    }

    void discardDevice() {
        sprites.clear();
        shadows.clear();
        brush.reset();
        rt.reset();
    }

    bool ensureTarget(UINT w, UINT h) {
        if (w == 0 || h == 0 || w > 16384 || h > 16384) return false;
        if (!dib || w > dibW || h > dibH) {
            // Grow with some slack: the bar changes width on every keystroke.
            const UINT newW = std::max(w, dibW + dibW / 4), newH = std::max(h, dibH + dibH / 4);
            releaseDib();
            BITMAPINFO bmi{};
            bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
            bmi.bmiHeader.biWidth = static_cast<LONG>(newW);
            bmi.bmiHeader.biHeight = -static_cast<LONG>(newH); // top-down
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = BI_RGB;
            void *bits = nullptr;
            dib = CreateDIBSection(memDC, &bmi, DIB_RGB_COLORS, &bits, nullptr, 0);
            if (!dib) return false;
            oldBitmap = SelectObject(memDC, dib);
            dibW = newW;
            dibH = newH;
        }
        if (!rt) {
            // Software on purpose: an input method lives inside every
            // application, and a Direct3D device in each of them costs far
            // more than rasterising one small bar on the CPU.
            D2D1_RENDER_TARGET_PROPERTIES props;
            props.type = D2D1_RENDER_TARGET_TYPE_SOFTWARE;
            props.pixelFormat = premultipliedBgra();
            props.dpiX = props.dpiY = 0.0f;
            props.usage = D2D1_RENDER_TARGET_USAGE_NONE;
            props.minLevel = D2D1_FEATURE_LEVEL_DEFAULT;
            if (FAILED(d2dFactory->CreateDCRenderTarget(&props, rt.put()))) {
                rt.reset();
                return false;
            }
            // ClearType needs an opaque background; this one is see-through.
            rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
            const auto black = colorF(0, 0, 0, 1);
            if (FAILED(rt->CreateSolidColorBrush(&black, nullptr, brush.put()))) {
                discardDevice();
                return false;
            }
        }
        const RECT bind{0, 0, static_cast<LONG>(w), static_cast<LONG>(h)};
        if (FAILED(rt->BindDC(memDC, &bind))) {
            discardDevice();
            return false;
        }
        rt->SetDpi(96.0f * scale, 96.0f * scale);
        return true;
    }

    // --- replaying the scene -----------------------------------------------
    Com<ID2D1PathGeometry> outlineGeometry(const BezierPath &path) {
        Com<ID2D1PathGeometry> g;
        Com<ID2D1GeometrySink> sink;
        if (path.empty() || FAILED(d2dFactory->CreatePathGeometry(g.put())) || FAILED(g->Open(sink.put()))) return {};
        sink->SetFillMode(D2D1_FILL_MODE_WINDING);
        sink->BeginFigure(d2d(path.start), D2D1_FIGURE_BEGIN_FILLED);
        for (const auto &s : path.segments) {
            D2D1_BEZIER_SEGMENT segment;
            segment.point1 = d2d(s.c1);
            segment.point2 = d2d(s.c2);
            segment.point3 = d2d(s.end);
            sink->AddBezier(&segment);
        }
        sink->EndFigure(D2D1_FIGURE_END_CLOSED);
        if (FAILED(sink->Close())) return {};
        return g;
    }

    Com<ID2D1PathGeometry> polyline(const std::vector<PointF> &points) {
        Com<ID2D1PathGeometry> g;
        Com<ID2D1GeometrySink> sink;
        if (points.size() < 2 || FAILED(d2dFactory->CreatePathGeometry(g.put())) || FAILED(g->Open(sink.put()))) return {};
        sink->BeginFigure(d2d(points[0]), D2D1_FIGURE_BEGIN_HOLLOW);
        for (std::size_t i = 1; i < points.size(); ++i) sink->AddLine(d2d(points[i]));
        sink->EndFigure(D2D1_FIGURE_END_OPEN);
        if (FAILED(sink->Close())) return {};
        return g;
    }

    // The brush for a paint. Solid paints reuse the one solid brush; a
    // gradient is created into `hold`, which must outlive the draw call.
    ID2D1Brush *brushFor(const Paint &p, Com<ID2D1Brush> &hold) {
        if (p.kind == Paint::Kind::Solid || p.stops.empty()) {
            const auto color = d2d(p.kind == Paint::Kind::Solid ? p.color : Color{0, 0, 0, 0});
            brush->SetColor(&color);
            return brush.get();
        }
        std::vector<D2D1_GRADIENT_STOP> stops(p.stops.size());
        for (std::size_t i = 0; i < p.stops.size(); ++i) {
            stops[i].position = p.stops[i].offset;
            stops[i].color = d2d(p.stops[i].color);
        }
        Com<ID2D1GradientStopCollection> collection;
        if (FAILED(rt->CreateGradientStopCollection(stops.data(), static_cast<UINT32>(stops.size()), D2D1_GAMMA_2_2,
                                                    D2D1_EXTEND_MODE_CLAMP, collection.put())))
            return nullptr;
        if (p.kind == Paint::Kind::Linear) {
            D2D1_LINEAR_GRADIENT_BRUSH_PROPERTIES props;
            props.startPoint = d2d(p.from);
            props.endPoint = d2d(p.to);
            Com<ID2D1LinearGradientBrush> gradient;
            if (FAILED(rt->CreateLinearGradientBrush(&props, nullptr, collection.get(), gradient.put()))) return nullptr;
            hold.attach(gradient.detach());
        } else {
            D2D1_RADIAL_GRADIENT_BRUSH_PROPERTIES props;
            props.center = d2d(p.from);
            props.gradientOriginOffset = pointF(0, 0);
            props.radiusX = std::max(0.01f, p.to.x);
            props.radiusY = std::max(0.01f, p.to.y);
            Com<ID2D1RadialGradientBrush> gradient;
            if (FAILED(rt->CreateRadialGradientBrush(&props, nullptr, collection.get(), gradient.put()))) return nullptr;
            hold.attach(gradient.detach());
        }
        return hold.get();
    }

    // Drop shadow: the outline's coverage blurred on the CPU, tinted, and kept
    // until the outline or the scale changes.
    void drawShadow(const DrawCommand &c, const GlassOutline &outline, SizeF size, std::size_t slot) {
        const Color tint = c.paint.color;
        // A tight shadow needs the display's pixels; a wide one has no detail.
        const float perDip = c.blur < 2.5f ? std::min(scale, 2.0f) : 1.0f;
        char key[200];
        std::snprintf(key, sizeof key, "%d|%zu|%.2f,%.2f,%.2f,%.2f|%.2fx%.2f|%.2f|%.2f|%.3f,%.3f,%.3f,%.3f",
                      static_cast<int>(layout.shape), outline.polygon.size(), static_cast<double>(outline.bounds.x),
                      static_cast<double>(outline.bounds.y), static_cast<double>(outline.bounds.width),
                      static_cast<double>(outline.bounds.height), static_cast<double>(size.width),
                      static_cast<double>(size.height), static_cast<double>(c.blur), static_cast<double>(perDip),
                      static_cast<double>(tint.r), static_cast<double>(tint.g), static_cast<double>(tint.b),
                      static_cast<double>(tint.a));
        if (shadows.size() <= slot) shadows.resize(slot + 1);
        ShadowEntry &entry = shadows[slot];
        if (entry.key != key) {
            entry.key = key;
            entry.bitmap.reset();
            const AlphaMask mask = shadowMask(outline.polygon, size, perDip, c.blur);
            if (mask.width > 0 && mask.height > 0) {
                std::vector<std::uint8_t> px(mask.alpha.size() * 4);
                for (std::size_t i = 0; i < mask.alpha.size(); ++i) {
                    const float a = static_cast<float>(mask.alpha[i]) * tint.a; // 0…255, premultiplies the colour
                    px[i * 4 + 0] = static_cast<std::uint8_t>(std::lround(tint.b * a));
                    px[i * 4 + 1] = static_cast<std::uint8_t>(std::lround(tint.g * a));
                    px[i * 4 + 2] = static_cast<std::uint8_t>(std::lround(tint.r * a));
                    px[i * 4 + 3] = static_cast<std::uint8_t>(std::lround(a));
                }
                const auto props = bitmapProperties();
                const UINT w = static_cast<UINT>(mask.width), h = static_cast<UINT>(mask.height);
                if (FAILED(rt->CreateBitmap(sizeU(w, h), px.data(), w * 4, &props, entry.bitmap.put())))
                    entry.bitmap.reset();
                entry.width = static_cast<float>(mask.width) / perDip;
                entry.height = static_cast<float>(mask.height) / perDip;
            }
        }
        if (entry.bitmap) {
            const auto dest = rectF(0, c.offsetY, entry.width, c.offsetY + entry.height);
            rt->DrawBitmap(entry.bitmap.get(), &dest, 1.0f, D2D1_BITMAP_INTERPOLATION_MODE_LINEAR, nullptr);
        }
    }

    void drawText(const DrawCommand &c) {
        const TextCache::Line line = text->line(c.text, c.size, c.medium);
        if (!line.layout) return;
        float left = c.x;
        if (c.align == TextAlign::Center) left -= line.width / 2;
        if (c.align == TextAlign::Trailing) left -= line.width;
        const auto color = d2d(c.paint.color);
        brush->SetColor(&color);
        // 4 = D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT (emoji), which older SDK headers lack.
        rt->DrawTextLayout(pointF(left, c.baseline - line.baseline), line.layout, brush.get(),
                           static_cast<D2D1_DRAW_TEXT_OPTIONS>(4));
    }

    // A clip that cannot be made still counts, so its PopClip has a partner.
    void pushClip(ID2D1Geometry *g, const D2D1_MATRIX_3X2_F &transform) {
        Com<ID2D1Layer> layer;
        if (FAILED(rt->CreateLayer(nullptr, layer.put()))) layer.reset();
        D2D1_LAYER_PARAMETERS params;
        params.contentBounds = rectF(-FLT_MAX, -FLT_MAX, FLT_MAX, FLT_MAX);
        params.geometricMask = g;
        params.maskAntialiasMode = D2D1_ANTIALIAS_MODE_PER_PRIMITIVE;
        params.maskTransform = transform;
        params.opacity = g && layer ? 1.0f : 0.0f; // no clip available: draw nothing rather than spill
        params.opacityBrush = nullptr;
        params.layerOptions = D2D1_LAYER_OPTIONS_NONE;
        if (layer) rt->PushLayer(&params, layer.get());
        clips.push_back(static_cast<bool>(layer));
    }

    void replay(const Scene &scene) {
        std::vector<Com<ID2D1PathGeometry>> outlines;
        outlines.reserve(scene.outlines.size());
        for (const auto &o : scene.outlines) outlines.push_back(outlineGeometry(o.path));
        const auto outline = [&](std::size_t index) -> ID2D1Geometry * {
            return index < outlines.size() ? outlines[index].get() : nullptr;
        };
        std::size_t shadowSlot = 0;
        clips.clear();
        using Kind = DrawCommand::Kind;
        for (const DrawCommand &c : scene.commands) {
            Com<ID2D1Brush> hold;
            switch (c.kind) {
            case Kind::Shadow:
                if (c.path < scene.outlines.size()) drawShadow(c, scene.outlines[c.path], scene.size, shadowSlot);
                ++shadowSlot;
                break;
            case Kind::FillPath:
                if (ID2D1Geometry *g = outline(c.path))
                    if (ID2D1Brush *b = brushFor(c.paint, hold)) rt->FillGeometry(g, b, nullptr);
                break;
            case Kind::StrokePath:
                if (ID2D1Geometry *g = outline(c.path))
                    if (ID2D1Brush *b = brushFor(c.paint, hold)) rt->DrawGeometry(g, b, c.width, nullptr);
                break;
            case Kind::PushClip:
                pushClip(outline(c.path), identity());
                break;
            case Kind::PushClipOut: {
                // The window's rectangle with the outline taken out.
                Com<ID2D1RectangleGeometry> all;
                Com<ID2D1GeometryGroup> outside;
                ID2D1Geometry *g = outline(c.path);
                const auto box = rectF(-40, -40, scene.size.width + 40, scene.size.height + 40);
                if (g && SUCCEEDED(d2dFactory->CreateRectangleGeometry(&box, all.put()))) {
                    ID2D1Geometry *parts[] = {all.get(), g};
                    if (FAILED(d2dFactory->CreateGeometryGroup(D2D1_FILL_MODE_ALTERNATE, parts, 2, outside.put()))) outside.reset();
                }
                pushClip(outside.get(), identity());
                break;
            }
            case Kind::PushClipOvals: {
                std::vector<Com<ID2D1EllipseGeometry>> ovals;
                std::vector<ID2D1Geometry *> parts;
                for (const RectF &o : c.ovals) {
                    D2D1_ELLIPSE e;
                    e.point = pointF(o.midX(), o.midY());
                    e.radiusX = o.width / 2;
                    e.radiusY = o.height / 2;
                    Com<ID2D1EllipseGeometry> g;
                    if (SUCCEEDED(d2dFactory->CreateEllipseGeometry(&e, g.put()))) {
                        parts.push_back(g.get());
                        ovals.push_back(std::move(g));
                    }
                }
                Com<ID2D1GeometryGroup> group;
                if (parts.empty() || FAILED(d2dFactory->CreateGeometryGroup(D2D1_FILL_MODE_WINDING, parts.data(),
                                                                            static_cast<UINT32>(parts.size()), group.put())))
                    group.reset();
                pushClip(group.get(), d2d(c.transform));
                break;
            }
            case Kind::PopClip:
                if (!clips.empty()) {
                    if (clips.back()) rt->PopLayer();
                    clips.pop_back();
                }
                break;
            case Kind::StrokeLine:
                if (auto g = polyline(c.points))
                    if (ID2D1Brush *b = brushFor(c.paint, hold)) rt->DrawGeometry(g.get(), b, c.width, roundStroke.get());
                break;
            case Kind::FillRoundRect:
                if (ID2D1Brush *b = brushFor(c.paint, hold)) {
                    D2D1_ROUNDED_RECT rr;
                    rr.rect = d2d(c.rect);
                    rr.radiusX = rr.radiusY = c.radius;
                    rt->FillRoundedRectangle(&rr, b);
                }
                break;
            case Kind::FillEllipse:
                if (ID2D1Brush *b = brushFor(c.paint, hold)) {
                    D2D1_ELLIPSE e;
                    e.point = pointF(c.rect.midX(), c.rect.midY());
                    e.radiusX = c.rect.width / 2;
                    e.radiusY = c.rect.height / 2;
                    if (c.rotation != 0) {
                        const auto turned = rotation(c.rotation, e.point.x, e.point.y);
                        rt->SetTransform(&turned);
                    }
                    rt->FillEllipse(&e, b);
                    if (c.rotation != 0) {
                        const auto id = identity();
                        rt->SetTransform(&id);
                    }
                }
                break;
            case Kind::Image: {
                const auto moved = d2d(c.transform);
                if (!c.transform.identity()) rt->SetTransform(&moved);
                drawSprite(spritePath(c.sprite), c.rect, c.opacity, c.blur, c.tint);
                if (!c.transform.identity()) {
                    const auto id = identity();
                    rt->SetTransform(&id);
                }
                break;
            }
            case Kind::Text:
                drawText(c);
                break;
            }
        }
        // EndDraw fails on an open layer; close whatever a malformed scene left.
        while (!clips.empty()) {
            if (clips.back()) rt->PopLayer();
            clips.pop_back();
        }
    }

    // --- layout, placement, presentation -----------------------------------
    CandidateViewModel frameModel() const {
        CandidateViewModel m = model;
        m.shape = options.shape;
        m.decorations = !highContrast;
        m.hovered = hovered;
        if (pressed.kind == HitKind::Candidate) m.pressed = pressed.index;
        else m.pressed.reset();
        const ULONGLONG now = GetTickCount64();
        for (int cat = 0; cat < 2; ++cat)
            if (petStart[cat] != 0)
                m.reactions[cat] = catReaction(static_cast<Cat>(cat), static_cast<float>(now - petStart[cat]) / kReactionMs);
        return m;
    }

    // Draws the current content and hands it to the window. False when
    // nothing new reached the screen.
    bool render() {
        dirty = false;
        if (!valid() || !hasModel) return false;
        if (rendering) {
            // Reached from a message sent while this frame is being presented.
            dirty = true;
            return false;
        }
        rendering = true;
        const bool ok = renderFrame();
        rendering = false;
        if (dirty && shown) requestRedraw();
        return ok;
    }

    bool renderFrame() {
        DpiScope dpi;
        const CandidateViewModel m = frameModel();
        LayoutLimits limits;
        limits.maxWidth = std::max(240.0f, static_cast<float>(work.right - work.left) / scale);
        layout = layoutCandidates(m, *text, limits);
        const Scene scene = buildScene(layout, m, theme());
        pixels.cx = static_cast<LONG>(std::ceil(layout.size.width * scale));
        pixels.cy = static_cast<LONG>(std::ceil(layout.size.height * scale));
        if (pixels.cx <= 0 || pixels.cy <= 0) return false;
        bool drawn = false;
        for (int attempt = 0; attempt < 2 && !drawn; ++attempt) {
            if (!ensureTarget(static_cast<UINT>(pixels.cx), static_cast<UINT>(pixels.cy))) return false;
            rt->BeginDraw();
            const auto id = identity();
            rt->SetTransform(&id);
            const auto clear = colorF(0, 0, 0, 0);
            rt->Clear(&clear);
            replay(scene);
            const HRESULT hr = rt->EndDraw();
            if (hr == static_cast<HRESULT>(D2DERR_RECREATE_TARGET)) {
                discardDevice(); // bitmaps and brushes belong to the lost target
                continue;
            }
            if (FAILED(hr)) return false;
            drawn = true;
        }
        if (!drawn) return false;

        if (haveCaret) {
            const PixelPoint at = placeNearCaret(layout, scale, {caret.left, caret.top, caret.right, caret.bottom},
                                                 {work.left, work.top, work.right, work.bottom});
            position.x = at.x;
            position.y = at.y;
        }
        // The DIB holds premultiplied BGRA, which is what ULW_ALPHA expects.
        POINT source{0, 0};
        SIZE size = pixels;
        BLENDFUNCTION blend;
        blend.BlendOp = AC_SRC_OVER;
        blend.BlendFlags = 0;
        blend.SourceConstantAlpha = 255;
        blend.AlphaFormat = AC_SRC_ALPHA;
        return UpdateLayeredWindow(hwnd, nullptr, &position, &size, memDC, &source, 0, &blend, ULW_ALPHA) != FALSE;
    }

    // One redraw on the next trip through the message loop, however many
    // changes arrive before it.
    void requestRedraw() {
        dirty = true;
        if (!shown || redrawPosted || !hwnd) return;
        redrawPosted = PostMessageW(hwnd, kRedrawMessage, 0, 0) != FALSE;
    }

    void showNear(const RECT &where) {
        if (!valid() || !hasModel) return;
        if (model.candidates.empty()) {
            hide();
            return;
        }
        DpiScope dpi;
        caret = where;
        haveCaret = true;
        const HMONITOR monitor = MonitorFromRect(&where, MONITOR_DEFAULTTONEAREST);
        MONITORINFO info{};
        info.cbSize = sizeof info;
        if (GetMonitorInfoW(monitor, &info)) work = info.rcWork;
        const float next = monitorScale(monitor);
        if (std::fabs(next - scale) > 0.001f) {
            scale = next;
            sprites.clear(); // sized in device pixels
            shadows.clear();
        }
        refreshOutfit();
        if (!render() || shown) return;
        shown = true;
        // Shows and raises the window without taking the focus.
        SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
    }

    void hide() {
        if (!shown) return;
        shown = false;
        dirty = false;
        hovered.reset();
        pressed = {};
        wheel = 0;
        if (hwnd) {
            if (GetCapture() == hwnd) ReleaseCapture();
            stopTimers();
            ShowWindow(hwnd, SW_HIDE);
        }
    }

    // --- cats ---------------------------------------------------------------
    // A click on a cat plays its answer (catReaction), redrawn on a frame timer
    // that runs only while the window is on screen. With the system's
    // animations off the cat stays still.
    void stopTimers() {
        KillTimer(hwnd, kCatFrame);
        petStart[0] = petStart[1] = 0;
    }

    void onTimer(UINT_PTR id) {
        if (id != kCatFrame) return;
        const ULONGLONG now = GetTickCount64();
        bool moving = false;
        for (ULONGLONG &start : petStart) {
            if (start != 0 && now - start >= kReactionMs) start = 0;
            moving = moving || start != 0;
        }
        if (!moving || !shown) KillTimer(hwnd, kCatFrame);
        requestRedraw();
    }

    static bool animationsOn() {
        BOOL on = TRUE;
        return !SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &on, 0) || on;
    }

    // Returns true on the fifth pet in a row on 莫莫; a gap of more than 1.6 s
    // starts the count again.
    bool pet(bool mimimo) {
        if (animationsOn()) {
            petStart[mimimo ? 0 : 1] = GetTickCount64();
            SetTimer(hwnd, kCatFrame, 16, nullptr);
        }
        bool fifth = false;
        if (!mimimo) {
            const ULONGLONG now = GetTickCount64();
            momoPets = now - lastMomoPet > 1600 ? 1 : momoPets + 1;
            lastMomoPet = now;
            if (momoPets >= 5) {
                momoPets = 0;
                fifth = true;
            }
        }
        requestRedraw();
        return fifth;
    }

    // --- input --------------------------------------------------------------
    PointF clientDips(LPARAM lp) const {
        return {static_cast<float>(GET_X_LPARAM(lp)) / scale, static_cast<float>(GET_Y_LPARAM(lp)) / scale};
    }

    PointF screenDips(LPARAM lp) const {
        POINT p{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
        ScreenToClient(hwnd, &p);
        return {static_cast<float>(p.x) / scale, static_cast<float>(p.y) / scale};
    }

    void settingsChanged() {
        readSystemTheme();
        if (shown && highContrast) stopTimers();
        requestRedraw();
    }

    LRESULT handle(UINT msg, WPARAM wp, LPARAM lp) {
        switch (msg) {
        case WM_MOUSEACTIVATE:
            return MA_NOACTIVATE; // a click on a candidate never takes the focus from the text field
        case WM_NCHITTEST:
            // The shadow around the glass lets clicks through to the window below.
            return coversPoint(layout, screenDips(lp)) ? HTCLIENT : HTTRANSPARENT;
        case WM_MOUSEMOVE: {
            const Hit hit = hitTest(layout, clientDips(lp));
            std::optional<std::size_t> next;
            if (hit.kind == HitKind::Candidate) next = hit.index;
            if (next != hovered) {
                hovered = next;
                requestRedraw();
            }
            if (!trackingLeave) {
                TRACKMOUSEEVENT tme{};
                tme.cbSize = sizeof tme;
                tme.dwFlags = TME_LEAVE;
                tme.hwndTrack = hwnd;
                trackingLeave = TrackMouseEvent(&tme) != FALSE;
            }
            return 0;
        }
        case WM_MOUSELEAVE:
            trackingLeave = false;
            if (hovered) {
                hovered.reset();
                requestRedraw();
            }
            return 0;
        case WM_LBUTTONDOWN:
            pressed = hitTest(layout, clientDips(lp));
            SetCapture(hwnd);
            if (pressed.kind == HitKind::Candidate) requestRedraw();
            return 0;
        case WM_LBUTTONUP: {
            const Hit down = pressed;
            pressed = {};
            if (GetCapture() == hwnd) ReleaseCapture();
            if (down.kind == HitKind::Candidate) requestRedraw();
            const Hit hit = hitTest(layout, clientDips(lp));
            if (down.kind == HitKind::None || hit.kind != down.kind || hit.index != down.index) return 0;
            // A callback may update, move or hide the window; nothing below touches state.
            switch (hit.kind) {
            case HitKind::Candidate: if (callbacks.choose) callbacks.choose(hit.index); break;
            case HitKind::Expand: if (callbacks.toggleExpanded) callbacks.toggleExpanded(); break;
            case HitKind::PrevPage: if (callbacks.page) callbacks.page(-1); break;
            case HitKind::NextPage: if (callbacks.page) callbacks.page(+1); break;
            case HitKind::Mimimo: pet(true); break;
            case HitKind::Momo: if (pet(false) && callbacks.toggleScript) callbacks.toggleScript(); break;
            case HitKind::None: break;
            }
            return 0;
        }
        case WM_CAPTURECHANGED:
            if (pressed.kind != HitKind::None) {
                pressed = {};
                requestRedraw();
            }
            return 0;
        case WM_MOUSEWHEEL: {
            // One page per notch; precision wheels add up to a notch first.
            wheel += GET_WHEEL_DELTA_WPARAM(wp);
            int pages = wheel / WHEEL_DELTA;
            wheel -= pages * WHEEL_DELTA;
            for (; pages != 0 && shown && callbacks.page; pages += pages > 0 ? -1 : 1) callbacks.page(pages > 0 ? -1 : +1);
            return 0;
        }
        case WM_SETCURSOR: {
            if (LOWORD(lp) != HTCLIENT) return DefWindowProcW(hwnd, msg, wp, lp);
            POINT p{0, 0};
            Hit hit;
            if (GetCursorPos(&p) && ScreenToClient(hwnd, &p))
                hit = hitTest(layout, {static_cast<float>(p.x) / scale, static_cast<float>(p.y) / scale});
            SetCursor(LoadCursorW(nullptr, hit.kind != HitKind::None ? IDC_HAND : IDC_ARROW));
            return TRUE;
        }
        case WM_TIMER:
            onTimer(wp);
            return 0;
        case WM_SETTINGCHANGE:
            settingsChanged();
            // The taskbar moved or resized: place the window again.
            if (wp == SPI_SETWORKAREA && shown && haveCaret && !rendering) showNear(RECT(caret));
            return 0;
        case WM_THEMECHANGED:
        case WM_SYSCOLORCHANGE:
            settingsChanged();
            return 0;
        case WM_DISPLAYCHANGE:
        case WM_DPICHANGED:
            // The scale and the work area are read again from the caret's
            // monitor. While a frame is being presented that has just happened.
            if (shown && haveCaret && !rendering) showNear(RECT(caret));
            return 0;
        case WM_ERASEBKGND:
            return 1;
        default:
            if (msg == kRedrawMessage) {
                redrawPosted = false;
                if (dirty && shown) render();
                return 0;
            }
            return DefWindowProcW(hwnd, msg, wp, lp);
        }
    }

    static LRESULT CALLBACK proc(HWND window, UINT msg, WPARAM wp, LPARAM lp) {
        if (msg == WM_NCCREATE) {
            auto *created = static_cast<Impl *>(reinterpret_cast<CREATESTRUCTW *>(lp)->lpCreateParams);
            created->hwnd = window;
            SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(created));
            return DefWindowProcW(window, msg, wp, lp);
        }
        auto *impl = reinterpret_cast<Impl *>(GetWindowLongPtrW(window, GWLP_USERDATA));
        if (!impl) return DefWindowProcW(window, msg, wp, lp);
        if (msg == WM_NCDESTROY) {
            // Also reached when something else destroys the window (thread exit).
            SetWindowLongPtrW(window, GWLP_USERDATA, 0);
            impl->hwnd = nullptr;
            impl->shown = false;
            impl->redrawPosted = false;
            return DefWindowProcW(window, msg, wp, lp);
        }
        return impl->handle(msg, wp, lp);
    }
};

CandidateWindow::CandidateWindow(HINSTANCE instance, CandidateWindowOptions options, CandidateWindowCallbacks callbacks)
    : impl_(std::make_unique<Impl>()) {
    impl_->instance = instance;
    impl_->options = std::move(options);
    impl_->callbacks = std::move(callbacks);
    impl_->create();
}

CandidateWindow::~CandidateWindow() { impl_->destroy(); }

bool CandidateWindow::valid() const { return impl_->valid(); }

void CandidateWindow::update(const CandidateViewModel &model) {
    impl_->model = model;
    impl_->hasModel = true;
    if (impl_->hovered && *impl_->hovered >= model.candidates.size()) impl_->hovered.reset();
    impl_->pressed = {};
    if (impl_->shown) impl_->requestRedraw();
}

void CandidateWindow::showNear(const RECT &caret) { impl_->showNear(caret); }

void CandidateWindow::hide() { impl_->hide(); }

bool CandidateWindow::visible() const { return impl_->shown; }

void CandidateWindow::setOptions(const CandidateWindowOptions &options) {
    const bool assets = options.assetDirectory != impl_->options.assetDirectory;
    impl_->options = options;
    if (impl_->text) impl_->text->setFamily(impl_->fontFamily(), impl_->locale());
    if (assets) {
        impl_->assetDir = options.assetDirectory.empty() ? moduleDirectory() + L"\\Assets" : options.assetDirectory;
        impl_->decoded.clear();
        impl_->sprites.clear();
        impl_->loadManifest();
    }
    if (impl_->shown) impl_->requestRedraw();
}

const CandidateWindowOptions &CandidateWindow::options() const { return impl_->options; }

void CandidateWindow::refreshSystemTheme() { impl_->settingsChanged(); }

HWND CandidateWindow::hwnd() const { return impl_->hwnd; }

std::wstring CandidateWindow::activeOutfitTitle() const {
    if (!impl_->outfitActive()) return {};
    return widen(impl_->options.simplified ? impl_->festival->hans : impl_->festival->hant);
}

} // namespace meowpuut::ui
#endif
