// Meowpuut text service: a TSF keyboard text input processor in a COM DLL.
//
// TSF makes one Service for every GUI thread that takes keyboard input and
// calls ActivateEx on it. Each ITfContext that gets keys has a ContextState
// with its own meowpuut::Session. The lexicon files are mapped once per
// process and shared by all sessions. The candidate window (UI/CandidateWindow)
// belongs to the thread's Service and shows the context that typed last.
//
// The reading is a TSF composition: underlined text in the document. Committed
// text waits in ContextState::pending until an edit session writes it, so text
// the host refuses stays queued for the same document and is never sent to
// another one.
//
// This code runs inside other people's programs. Every entry point that TSF,
// COM or the candidate window calls catches all exceptions: one that escaped
// would end the host program.
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <msctf.h>
#include <olectl.h>
#include <wchar.h>

#include "../Keyboard.h"
#include "../Session.h"
#include "../UI/CandidateWindow.h"
#include "../UI/SnapshotAdapter.h"
#include "TsfDeclarations.h"

#include <algorithm>
#include <atomic>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>

using namespace meowpuut;
using namespace meowpuut::tsf;

namespace {

// The class and the Pinyin profile keep the 0.1.x values, so installing this
// version replaces 0.1.x instead of adding a second entry.
const CLSID kServiceId = {0x961c0ada, 0x4dc6, 0x488f, {0x95, 0x63, 0x4f, 0xc1, 0x44, 0xef, 0xae, 0x27}};
const GUID kPinyinProfile = {0x8fde097d, 0x3513, 0x486a, {0x9a, 0xb5, 0x2d, 0x1b, 0x2c, 0x67, 0xc3, 0x59}};
const GUID kZhuyinProfile = {0x1f5d9d62, 0xf4af, 0x4aaf, {0x82, 0xa6, 0xa4, 0x82, 0xd7, 0xec, 0x3a, 0xe2}};
const GUID kReadingAttribute = {0xaa6fa177, 0x4a76, 0x4191, {0xa2, 0xfe, 0x06, 0x18, 0xd6, 0xcc, 0x2f, 0x65}};
const LANGID kLanguage = MAKELANGID(LANG_CHINESE, SUBLANG_CHINESE_TRADITIONAL); // zh-TW
const wchar_t kSettingsKey[] = L"Software\\Meowpuut";

struct Profile {
    const GUID *guid;
    const wchar_t *name;
};
const Profile kProfiles[] = {{&kPinyinProfile, L"喵撲拼音"}, {&kZhuyinProfile, L"喵撲注音"}};

HMODULE g_module = nullptr;
std::atomic<long> g_objects{0}; // live COM objects plus LockServer locks, for DllCanUnloadNow

// --- small COM helpers --------------------------------------------------------

template <class T>
class Ref {
public:
    Ref() = default;
    Ref(const Ref &other) : p_(other.p_) { if (p_) p_->AddRef(); }
    Ref(Ref &&other) noexcept : p_(std::exchange(other.p_, nullptr)) {}
    Ref &operator=(Ref other) noexcept { std::swap(p_, other.p_); return *this; }
    ~Ref() { reset(); }
    static Ref retain(T *p) {
        Ref r;
        r.p_ = p;
        if (p) p->AddRef();
        return r;
    }
    static Ref adopt(T *p) {
        Ref r;
        r.p_ = p;
        return r;
    }
    T *get() const { return p_; }
    T *operator->() const { return p_; }
    explicit operator bool() const { return p_ != nullptr; }
    T **put() { reset(); return &p_; }
    void **putVoid() { return reinterpret_cast<void **>(put()); }
    void reset() { if (p_) std::exchange(p_, nullptr)->Release(); }

private:
    T *p_ = nullptr;
};

template <class T, class From>
Ref<T> query(From *from, REFIID iid) {
    Ref<T> out;
    if (from && FAILED(from->QueryInterface(iid, out.putVoid()))) out.reset();
    return out;
}

class Object {
public:
    Object() { ++g_objects; }
    Object(const Object &) = delete;
    Object &operator=(const Object &) = delete;
    virtual ~Object() { --g_objects; }

protected:
    ULONG retain() { return ++refs_; }
    ULONG release() {
        const ULONG left = --refs_;
        if (left == 0) delete this;
        return left;
    }

private:
    std::atomic<ULONG> refs_{1};
};

#define MEOWPUUT_REFCOUNT                                               \
    ULONG STDMETHODCALLTYPE AddRef() override { return retain(); }     \
    ULONG STDMETHODCALLTYPE Release() override { return release(); }

// --- files, text and settings ---------------------------------------------------

std::wstring modulePath() {
    std::wstring path(MAX_PATH, L'\0');
    for (;;) {
        const DWORD n = GetModuleFileNameW(g_module, path.data(), static_cast<DWORD>(path.size()));
        if (n == 0) return {};
        if (n < path.size()) {
            path.resize(n);
            return path;
        }
        path.resize(path.size() * 2);
    }
}

std::filesystem::path lexiconDirectory() { return std::filesystem::path(modulePath()).parent_path() / L"lexicon"; }

std::wstring widen(const std::string &s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<std::size_t>(std::max(n, 0)), L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

// Traditional or Simplified, shared by every program through HKCU. A program
// in an app container may not read it; it then starts in Traditional.
bool storedSimplified() {
    DWORD value = 0, size = sizeof value;
    return RegGetValueW(HKEY_CURRENT_USER, kSettingsKey, L"Simplified", RRF_RT_REG_DWORD, nullptr, &value, &size) ==
               ERROR_SUCCESS &&
           value != 0;
}

void storeSimplified(bool on) {
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, kSettingsKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) !=
        ERROR_SUCCESS)
        return;
    const DWORD value = on ? 1 : 0;
    RegSetValueExW(key, L"Simplified", 0, REG_DWORD, reinterpret_cast<const BYTE *>(&value), sizeof value);
    RegCloseKey(key);
}

// --- lexicons -----------------------------------------------------------------

// A read-only view of a whole file. The engine reads mimo_base.bin and
// mimo_pairs.bin in place, so every program that loads the text service shares
// the same file pages instead of building its own copy on the heap.
struct Mapping {
    HANDLE file = INVALID_HANDLE_VALUE, section = nullptr;
    const char *bytes = nullptr;
    std::size_t size = 0;
};

void unmap(void *context) {
    auto *m = static_cast<Mapping *>(context);
    if (m->bytes) UnmapViewOfFile(m->bytes);
    if (m->section) CloseHandle(m->section);
    if (m->file != INVALID_HANDLE_VALUE) CloseHandle(m->file);
    delete m;
}

Mapping *mapFile(const std::filesystem::path &path) {
    auto *m = new Mapping;
    m->file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
                          FILE_ATTRIBUTE_NORMAL, nullptr);
    LARGE_INTEGER size{};
    if (m->file != INVALID_HANDLE_VALUE && GetFileSizeEx(m->file, &size) && size.QuadPart > 0) {
        m->section = CreateFileMappingW(m->file, nullptr, PAGE_READONLY, 0, 0, nullptr);
        if (m->section) m->bytes = static_cast<const char *>(MapViewOfFile(m->section, FILE_MAP_READ, 0, 0, 0));
        m->size = static_cast<std::size_t>(size.QuadPart);
    }
    if (m->bytes) return m;
    unmap(m);
    return nullptr;
}

std::mutex g_lexiconLock;
std::weak_ptr<MPNLexicon> g_lexicons[2]; // [0] Zhuyin, [1] Pinyin
bool g_lexiconFailed[2] = {false, false};

SharedLexicon sharedLexicon(bool pinyin) {
    std::lock_guard<std::mutex> lock(g_lexiconLock);
    if (auto existing = g_lexicons[pinyin].lock()) return existing;
    if (g_lexiconFailed[pinyin]) return {};
    g_lexiconFailed[pinyin] = true;
    const auto dir = lexiconDirectory();
    Mapping *base = mapFile(dir / L"mimo_base.bin");
    if (!base) return {};
    // The engine calls unmap once it is done with the bytes, or right away on failure.
    MPNLexicon *raw = mpn_lexicon_create_from_base_in_place(base->bytes, base->size, pinyin ? 1 : 0, unmap, base);
    if (!raw) return {};
    if (Mapping *pairs = mapFile(dir / L"mimo_pairs.bin"))
        mpn_lexicon_attach_pairs(raw, pairs->bytes, pairs->size, unmap, pairs);
    SharedLexicon lexicon(raw, mpn_lexicon_destroy);
    g_lexicons[pinyin] = lexicon;
    g_lexiconFailed[pinyin] = false;
    return lexicon;
}

std::unique_ptr<Session> makeSession(InputMode mode, bool simplified) {
    const bool pinyin = mode == InputMode::Pinyin;
    auto lexicon = sharedLexicon(pinyin);
    if (!lexicon) return nullptr;
    auto session = std::make_unique<Session>(std::move(lexicon), pinyin);
    if (pinyin) {
        try {
            session->registerJapaneseWords(lexiconDirectory() / L"native-japanese.tsv");
        } catch (const std::exception &) {
            // Without the word list the session types Chinese only.
        }
    }
    session->setSimplified(simplified);
    return session;
}

// --- keys ---------------------------------------------------------------------

KeyEvent keyEvent(WPARAM vk, LPARAM lp) {
    BYTE keys[256] = {};
    GetKeyboardState(keys);
    wchar_t buffer[4] = {};
    const UINT scan = (static_cast<UINT>(lp) >> 16) & 0xff;
    // Flag 4: leave the layout's dead-key state alone.
    const int n = ToUnicodeEx(static_cast<UINT>(vk), scan, keys, buffer, 4, 4, GetKeyboardLayout(0));
    KeyEvent event{static_cast<std::uint32_t>(vk), 0};
    if (n == 1 && buffer[0] >= 0x20 && buffer[0] < 0x7f) event.printable = static_cast<char>(buffer[0]);
    event.shift = GetKeyState(VK_SHIFT) < 0;
    event.control = GetKeyState(VK_CONTROL) < 0;
    event.alt = GetKeyState(VK_MENU) < 0;
    event.win = GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0;
    return event;
}

// Chinese punctuation, as on macOS (Sources/EngineKeyHandler.swift). Pinyin
// takes it from the unshifted keys and leaves shifted ones as typed; Zhuyin's
// unshifted keys are Bopomofo, so it takes the shifted ones.
wchar_t punctuation(InputMode mode, const KeyEvent &event) {
    if (mode == InputMode::Pinyin) {
        if (event.shift) return 0;
        switch (event.printable) {
        case ',': return L'，';
        case '.': return L'。';
        case ';': return L'；';
        case '/': return L'？';
        case '\\': return L'、';
        case '[': return L'「';
        case ']': return L'」';
        default: return 0;
        }
    }
    switch (event.printable) {
    case '<': return L'，';
    case '>': return L'。';
    case '?': return L'？';
    case ':': return L'：';
    case '!': return L'！';
    case '(': return L'（';
    case ')': return L'）';
    case '[': return L'「';
    case ']': return L'」';
    case '\\': return L'、';
    case '{': return L'『';
    case '}': return L'』';
    default: return 0;
    }
}

bool isShift(WPARAM vk) { return vk == VK_SHIFT || vk == VK_LSHIFT || vk == VK_RSHIFT; }

// Keys that only change the state of others; pressing one leaves a reading open.
bool isModifier(WPARAM vk) {
    switch (vk) {
    case VK_SHIFT: case VK_CONTROL: case VK_MENU: case VK_LSHIFT: case VK_RSHIFT: case VK_LCONTROL:
    case VK_RCONTROL: case VK_LMENU: case VK_RMENU: case VK_LWIN: case VK_RWIN: case VK_CAPITAL:
    case VK_NUMLOCK: case VK_SCROLL: case VK_PROCESSKEY: case VK_PACKET:
        return true;
    default:
        return false;
    }
}

bool compartmentOn(IUnknown *owner, REFGUID guid) {
    auto manager = query<ITfCompartmentMgr>(owner, IID_ITfCompartmentMgr);
    Ref<ITfCompartment> compartment;
    if (!manager || FAILED(manager->GetCompartment(guid, compartment.put())) || !compartment) return false;
    VARIANT value;
    VariantInit(&value);
    const bool on = SUCCEEDED(compartment->GetValue(&value)) && value.vt == VT_I4 && value.lVal != 0;
    VariantClear(&value);
    return on;
}

// Turns a rectangle from the host window's DPI space into physical pixels,
// which is what the candidate window works in.
void toPhysical(HWND window, RECT &rect) {
    if (!window) return;
    POINT topLeft{rect.left, rect.top}, bottomRight{rect.right, rect.bottom};
    if (LogicalToPhysicalPointForPerMonitorDPI(window, &topLeft) &&
        LogicalToPhysicalPointForPerMonitorDPI(window, &bottomRight))
        rect = {topLeft.x, topLeft.y, bottomRight.x, bottomRight.y};
}

// --- per-context state ----------------------------------------------------------

struct ContextState {
    explicit ContextState(ITfContext *c) : context(Ref<ITfContext>::retain(c)) {}
    Ref<ITfContext> context;
    std::unique_ptr<Session> session;
    InputMode mode = InputMode::Pinyin;
    Ref<ITfComposition> composition;
    std::wstring pending;  // committed text not yet in the document, oldest first
    std::wstring preedit;  // what the composition should show
    std::wstring written;  // what it shows now
    Snapshot last;         // the latest snapshot, its commit taken out
    std::size_t highlighted = 0;
    bool expanded = false;
    RECT caret{};
    bool haveCaret = false;
    DWORD layoutCookie = TF_INVALID_COOKIE; // ITfTextLayoutSink on this context
};

// Ignore eats a key and does nothing. FinishThenPass ends the reading and
// lets the key go on to the program.
enum class Act { Pass, Engine, Move, Expand, Collapse, Choose, Finish, Insert, Literal, Ignore, FinishThenPass };
struct Plan {
    Act act = Act::Pass;
    KeyEvent event{0, 0};
    int delta = 0;
    wchar_t text = 0;
};

// --- display attribute (the reading's underline) --------------------------------

TF_DISPLAYATTRIBUTE readingAttribute() {
    TF_DISPLAYATTRIBUTE a{};
    a.crText.type = TF_CT_NONE;
    a.crBk.type = TF_CT_NONE;
    a.lsStyle = TF_LS_SOLID;
    a.fBoldLine = FALSE;
    a.crLine.type = TF_CT_NONE;
    a.bAttr = TF_ATTR_INPUT;
    return a;
}

class AttributeInfo final : public ITfDisplayAttributeInfo, public Object {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **out) override {
        if (!out) return E_POINTER;
        *out = nullptr;
        if (iid != IID_IUnknown && iid != IID_ITfDisplayAttributeInfo) return E_NOINTERFACE;
        *out = static_cast<ITfDisplayAttributeInfo *>(this);
        AddRef();
        return S_OK;
    }
    MEOWPUUT_REFCOUNT
    HRESULT STDMETHODCALLTYPE GetGUID(GUID *guid) override {
        if (!guid) return E_INVALIDARG;
        *guid = kReadingAttribute;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetDescription(BSTR *description) override {
        if (!description) return E_INVALIDARG;
        *description = SysAllocString(L"Meowpuut reading");
        return *description ? S_OK : E_OUTOFMEMORY;
    }
    HRESULT STDMETHODCALLTYPE GetAttributeInfo(TF_DISPLAYATTRIBUTE *attribute) override {
        if (!attribute) return E_INVALIDARG;
        *attribute = readingAttribute();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE SetAttributeInfo(const TF_DISPLAYATTRIBUTE *) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Reset() override { return S_OK; }
};

class AttributeList final : public IEnumTfDisplayAttributeInfo, public Object {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **out) override {
        if (!out) return E_POINTER;
        *out = nullptr;
        if (iid != IID_IUnknown && iid != IID_IEnumTfDisplayAttributeInfo) return E_NOINTERFACE;
        *out = static_cast<IEnumTfDisplayAttributeInfo *>(this);
        AddRef();
        return S_OK;
    }
    MEOWPUUT_REFCOUNT
    HRESULT STDMETHODCALLTYPE Clone(IEnumTfDisplayAttributeInfo **out) override {
        if (!out) return E_INVALIDARG;
        auto *copy = new (std::nothrow) AttributeList;
        if (!copy) return E_OUTOFMEMORY;
        copy->done_ = done_;
        *out = copy;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Next(ULONG count, ITfDisplayAttributeInfo **infos, ULONG *fetched) override {
        if (!infos) return E_INVALIDARG;
        ULONG n = 0;
        if (count > 0 && !done_) {
            infos[0] = new (std::nothrow) AttributeInfo;
            if (!infos[0]) return E_OUTOFMEMORY;
            done_ = true;
            n = 1;
        }
        if (fetched) *fetched = n;
        return n == count ? S_OK : S_FALSE;
    }
    HRESULT STDMETHODCALLTYPE Reset() override {
        done_ = false;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Skip(ULONG count) override {
        if (count == 0) return S_OK;
        const bool skipped = !done_ && count == 1;
        done_ = true;
        return skipped ? S_OK : S_FALSE;
    }

private:
    bool done_ = false;
};

// --- the text service -------------------------------------------------------------

class Service;

// The taskbar's input mode indicator (GUID_LBI_INPUTMODE): 中 or 英. A click
// switches, as a Shift tap does; its menu switches Traditional and Simplified.
class ModeButton final : public ITfLangBarItemButton, public ITfSource, public Object {
public:
    explicit ModeButton(Service *owner) : owner_(owner) {}
    void detach() {
        owner_ = nullptr;
        sink_.reset();
    }
    void changed() {
        if (sink_) sink_->OnUpdate(TF_LBI_ICON | TF_LBI_TEXT | TF_LBI_TOOLTIP);
    }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **out) override {
        if (!out) return E_POINTER;
        *out = nullptr;
        if (iid == IID_IUnknown || iid == IID_ITfLangBarItem || iid == kIidLangBarItemButton)
            *out = static_cast<ITfLangBarItemButton *>(this);
        else if (iid == IID_ITfSource)
            *out = static_cast<ITfSource *>(this);
        else
            return E_NOINTERFACE;
        AddRef();
        return S_OK;
    }
    MEOWPUUT_REFCOUNT

    HRESULT STDMETHODCALLTYPE GetInfo(TF_LANGBARITEMINFO *info) override {
        if (!info) return E_INVALIDARG;
        info->clsidService = kServiceId;
        info->guidItem = kInputModeItem;
        info->dwStyle = TF_LBI_STYLE_BTN_BUTTON | TF_LBI_STYLE_SHOWNINTRAY;
        info->ulSort = 0;
        wcsncpy(info->szDescription, L"喵撲中英切換", TF_LBI_DESC_MAXLEN - 1);
        info->szDescription[TF_LBI_DESC_MAXLEN - 1] = 0;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetStatus(DWORD *status) override {
        if (!status) return E_INVALIDARG;
        *status = 0;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Show(BOOL) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetTooltipString(BSTR *text) override {
        return bstr(english() ? L"英文（按 Shift 換中文）" : L"中文（按 Shift 換英文）", text);
    }
    HRESULT STDMETHODCALLTYPE OnClick(TfLBIClick click, POINT, const RECT *) override;
    HRESULT STDMETHODCALLTYPE InitMenu(ITfMenu *menu) override;
    HRESULT STDMETHODCALLTYPE OnMenuSelect(UINT id) override;
    HRESULT STDMETHODCALLTYPE GetIcon(HICON *icon) override;
    HRESULT STDMETHODCALLTYPE GetText(BSTR *text) override { return bstr(english() ? L"英" : L"中", text); }

    HRESULT STDMETHODCALLTYPE AdviseSink(REFIID iid, IUnknown *unknown, DWORD *cookie) override {
        if (!cookie || !unknown) return E_INVALIDARG;
        if (iid != IID_ITfLangBarItemSink) return CONNECT_E_CANNOTCONNECT;
        if (sink_) return CONNECT_E_ADVISELIMIT;
        sink_ = query<ITfLangBarItemSink>(unknown, IID_ITfLangBarItemSink);
        if (!sink_) return E_NOINTERFACE;
        *cookie = kCookie;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE UnadviseSink(DWORD cookie) override {
        if (cookie != kCookie || !sink_) return CONNECT_E_NOCONNECTION;
        sink_.reset();
        return S_OK;
    }

private:
    static HRESULT bstr(const wchar_t *value, BSTR *out) {
        if (!out) return E_INVALIDARG;
        *out = SysAllocString(value);
        return *out ? S_OK : E_OUTOFMEMORY;
    }
    bool english() const;
    static constexpr DWORD kCookie = 0x4d505554; // one sink at a time
    Service *owner_;
    Ref<ITfLangBarItemSink> sink_;
};

// 中 or 英 for the taskbar, in the taskbar's text colour. GDI draws the glyph
// white on black; its brightness becomes the icon's alpha.
HICON modeIcon(bool english) {
    const int size = GetSystemMetricsForDpi(SM_CXSMICON, GetDpiForSystem());
    BITMAPINFO info{};
    info.bmiHeader.biSize = sizeof info.bmiHeader;
    info.bmiHeader.biWidth = size;
    info.bmiHeader.biHeight = -size;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void *bits = nullptr;
    HBITMAP color = CreateDIBSection(nullptr, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
    HBITMAP mask = CreateBitmap(size, size, 1, 1, nullptr);
    HDC dc = CreateCompatibleDC(nullptr);
    HICON icon = nullptr;
    if (color && mask && dc && bits) {
        HGDIOBJ oldBitmap = SelectObject(dc, color);
        HFONT font = CreateFontW(-size * 7 / 8, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                                 CLIP_DEFAULT_PRECIS, ANTIALIASED_QUALITY, DEFAULT_PITCH, L"Microsoft JhengHei UI");
        HGDIOBJ oldFont = SelectObject(dc, font);
        SetBkMode(dc, TRANSPARENT);
        SetTextColor(dc, RGB(255, 255, 255));
        RECT box{0, 0, size, size};
        DrawTextW(dc, english ? L"英" : L"中", 1, &box, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
        GdiFlush();
        DWORD light = 0, length = sizeof light;
        RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                     L"SystemUsesLightTheme", RRF_RT_REG_DWORD, nullptr, &light, &length);
        const std::uint32_t ink = light ? 0x000000u : 0xffffffu;
        auto *pixel = static_cast<std::uint32_t *>(bits);
        for (int i = 0; i < size * size; ++i) {
            const std::uint32_t alpha = std::max({pixel[i] & 0xffu, (pixel[i] >> 8) & 0xffu, (pixel[i] >> 16) & 0xffu});
            pixel[i] = (alpha << 24) | ink;
        }
        SelectObject(dc, oldFont);
        SelectObject(dc, oldBitmap);
        DeleteObject(font);
        ICONINFO parts{TRUE, 0, 0, mask, color};
        icon = CreateIconIndirect(&parts);
    }
    if (dc) DeleteDC(dc);
    if (mask) DeleteObject(mask);
    if (color) DeleteObject(color);
    return icon;
}

class EditSession final : public ITfEditSession, public Object {
public:
    // caretOnly: read where the reading is and place the window, change nothing.
    EditSession(Service *service, std::shared_ptr<ContextState> state, bool caretOnly = false);
    ~EditSession() override;
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **out) override {
        if (!out) return E_POINTER;
        *out = nullptr;
        if (iid != IID_IUnknown && iid != IID_ITfEditSession) return E_NOINTERFACE;
        *out = static_cast<ITfEditSession *>(this);
        AddRef();
        return S_OK;
    }
    MEOWPUUT_REFCOUNT
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override;

private:
    Service *service_;
    std::shared_ptr<ContextState> state_;
    bool caretOnly_;
};

class Service final : public ITfTextInputProcessorEx,
                      public ITfKeyEventSink,
                      public ITfThreadMgrEventSink,
                      public ITfThreadFocusSink,
                      public ITfCompositionSink,
                      public ITfDisplayAttributeProvider,
                      public ITfInputProcessorProfileActivationSink,
                      public ITfTextLayoutSink,
                      public Object {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **out) override {
        if (!out) return E_POINTER;
        *out = nullptr;
        if (iid == IID_IUnknown || iid == IID_ITfTextInputProcessor || iid == kIidTextInputProcessorEx)
            *out = static_cast<ITfTextInputProcessorEx *>(this);
        else if (iid == IID_ITfKeyEventSink)
            *out = static_cast<ITfKeyEventSink *>(this);
        else if (iid == IID_ITfThreadMgrEventSink)
            *out = static_cast<ITfThreadMgrEventSink *>(this);
        else if (iid == IID_ITfThreadFocusSink)
            *out = static_cast<ITfThreadFocusSink *>(this);
        else if (iid == IID_ITfCompositionSink)
            *out = static_cast<ITfCompositionSink *>(this);
        else if (iid == kIidDisplayAttributeProvider)
            *out = static_cast<ITfDisplayAttributeProvider *>(this);
        else if (iid == IID_ITfInputProcessorProfileActivationSink)
            *out = static_cast<ITfInputProcessorProfileActivationSink *>(this);
        else if (iid == kIidTextLayoutSink)
            *out = static_cast<ITfTextLayoutSink *>(this);
        else
            return E_NOINTERFACE;
        AddRef();
        return S_OK;
    }
    MEOWPUUT_REFCOUNT

    // ITfTextInputProcessor(Ex)
    HRESULT STDMETHODCALLTYPE Activate(ITfThreadMgr *threadMgr, TfClientId clientId) override {
        return ActivateEx(threadMgr, clientId, 0);
    }
    HRESULT STDMETHODCALLTYPE ActivateEx(ITfThreadMgr *threadMgr, TfClientId clientId, DWORD) override {
        if (!threadMgr) return E_INVALIDARG;
        try {
            threadMgr_ = Ref<ITfThreadMgr>::retain(threadMgr);
            clientId_ = clientId;
            keystrokes_ = query<ITfKeystrokeMgr>(threadMgr, IID_ITfKeystrokeMgr);
            HRESULT result = keystrokes_ ? keystrokes_->AdviseKeyEventSink(clientId_, static_cast<ITfKeyEventSink *>(this), TRUE)
                                         : E_NOINTERFACE;
            if (FAILED(result)) {
                keystrokes_.reset();
                Deactivate();
                return result;
            }
            if (auto source = query<ITfSource>(threadMgr, IID_ITfSource)) {
                source->AdviseSink(IID_ITfThreadMgrEventSink, static_cast<ITfThreadMgrEventSink *>(this), &threadCookie_);
                source->AdviseSink(IID_ITfThreadFocusSink, static_cast<ITfThreadFocusSink *>(this), &focusCookie_);
                source->AdviseSink(IID_ITfInputProcessorProfileActivationSink,
                                   static_cast<ITfInputProcessorProfileActivationSink *>(this), &profileCookie_);
            }
            Ref<ITfCategoryMgr> categories;
            if (SUCCEEDED(CoCreateInstance(CLSID_TF_CategoryMgr, nullptr, CLSCTX_INPROC_SERVER, IID_ITfCategoryMgr,
                                           categories.putVoid())) &&
                FAILED(categories->RegisterGUID(kReadingAttribute, &attributeAtom_)))
                attributeAtom_ = TF_INVALID_GUIDATOM;
            Ref<ITfInputProcessorProfileMgr> profiles;
            TF_INPUTPROCESSORPROFILE profile{};
            if (SUCCEEDED(CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr, CLSCTX_INPROC_SERVER,
                                           IID_ITfInputProcessorProfileMgr, profiles.putVoid())) &&
                SUCCEEDED(profiles->GetActiveProfile(GUID_TFCAT_TIP_KEYBOARD, &profile)) &&
                IsEqualCLSID(profile.clsid, kServiceId))
                mode_ = IsEqualGUID(profile.guidProfile, kZhuyinProfile) ? InputMode::Zhuyin : InputMode::Pinyin;
            simplified_ = storedSimplified();
            langBar_ = query<ITfLangBarItemMgr>(threadMgr, IID_ITfLangBarItemMgr);
            button_ = Ref<ModeButton>::adopt(new ModeButton(this));
            if (langBar_) langBar_->AddItem(static_cast<ITfLangBarItemButton *>(button_.get()));
            activated_ = true;
            return S_OK;
        } catch (...) {
            Deactivate();
            return E_FAIL;
        }
    }
    HRESULT STDMETHODCALLTYPE Deactivate() override {
        activated_ = false; // no candidate window from here on
        try {
            // Leaving with a reading open keeps what the candidates offer, as on macOS.
            for (auto &entry : states_) {
                const auto &state = entry.second;
                if (state->session && state->session->hasComposition()) {
                    finish(*state);
                    deliver(state);
                }
            }
        } catch (...) {
        }
        window_.reset(); // before the DLL can unload: the window class belongs to it
        if (button_) {
            if (langBar_) langBar_->RemoveItem(static_cast<ITfLangBarItemButton *>(button_.get()));
            button_->detach();
            button_.reset();
        }
        langBar_.reset();
        if (keystrokes_) {
            keystrokes_->UnadviseKeyEventSink(clientId_);
            keystrokes_.reset();
        }
        if (auto source = query<ITfSource>(threadMgr_.get(), IID_ITfSource)) {
            for (DWORD *cookie : {&threadCookie_, &focusCookie_, &profileCookie_}) {
                if (*cookie != TF_INVALID_COOKIE) source->UnadviseSink(*cookie);
                *cookie = TF_INVALID_COOKIE;
            }
        }
        for (auto &entry : states_) unadviseLayout(*entry.second);
        states_.clear();
        active_.reset();
        threadMgr_.reset();
        return S_OK;
    }

    // ITfKeyEventSink
    HRESULT STDMETHODCALLTYPE OnSetFocus(BOOL foreground) override {
        if (!foreground) hideWindow();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnTestKeyDown(ITfContext *context, WPARAM vk, LPARAM lp, BOOL *eaten) override {
        if (!eaten) return E_INVALIDARG;
        *eaten = FALSE;
        try {
            noteKeyDown(vk);
            const Plan next = plan(context, vk, lp);
            // TSF does not call OnKeyDown for a key that is not eaten here, so
            // a key that goes on to the program ends the reading now.
            if (next.act == Act::FinishThenPass) {
                const auto state = states_.at(context);
                perform(state, next);
            } else {
                *eaten = next.act != Act::Pass;
            }
        } catch (...) {
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyDown(ITfContext *context, WPARAM vk, LPARAM lp, BOOL *eaten) override {
        if (!eaten) return E_INVALIDARG;
        *eaten = FALSE;
        try {
            noteKeyDown(vk);
            const Plan next = plan(context, vk, lp);
            if (next.act == Act::Pass) return S_OK;
            *eaten = next.act != Act::FinishThenPass;
            const auto state = states_.at(context);
            perform(state, next);
        } catch (...) {
            // Keep the reading; the key is not passed on twice.
            hideWindow();
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnTestKeyUp(ITfContext *context, WPARAM vk, LPARAM, BOOL *eaten) override {
        if (!eaten) return E_INVALIDARG;
        *eaten = FALSE;
        try {
            if (shiftTapped(vk)) toggleEnglish(context);
        } catch (...) {
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyUp(ITfContext *context, WPARAM vk, LPARAM lp, BOOL *eaten) override {
        return OnTestKeyUp(context, vk, lp, eaten);
    }
    HRESULT STDMETHODCALLTYPE OnPreservedKey(ITfContext *, REFGUID, BOOL *eaten) override {
        if (!eaten) return E_INVALIDARG;
        *eaten = FALSE;
        return S_OK;
    }

    // ITfThreadMgrEventSink
    HRESULT STDMETHODCALLTYPE OnInitDocumentMgr(ITfDocumentMgr *) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnUninitDocumentMgr(ITfDocumentMgr *) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnSetFocus(ITfDocumentMgr *, ITfDocumentMgr *) override {
        // A reading in the field left behind stays there and goes on with
        // the next key typed in it.
        hideWindow();
        active_.reset();
        cancelShift();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnPushContext(ITfContext *) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnPopContext(ITfContext *context) override {
        try {
            const auto found = states_.find(context);
            if (found != states_.end()) {
                if (found->second == active_) {
                    hideWindow();
                    active_.reset();
                }
                unadviseLayout(*found->second);
                states_.erase(found);
            }
        } catch (...) {
        }
        return S_OK;
    }

    // ITfThreadFocusSink: another program took the focus, or gave it back.
    HRESULT STDMETHODCALLTYPE OnSetThreadFocus() override {
        try {
            if (active_) showWindow(*active_);
        } catch (...) {
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKillThreadFocus() override {
        hideWindow();
        cancelShift(); // the Shift-up goes to another program
        return S_OK;
    }

    // ITfCompositionSink: the host ended the reading (a click elsewhere, an undo).
    // Its text stays in the document as it was.
    HRESULT STDMETHODCALLTYPE OnCompositionTerminated(TfEditCookie cookie, ITfComposition *composition) override {
        try {
            for (auto &entry : states_) {
                ContextState &s = *entry.second;
                if (s.composition.get() != composition) continue;
                Ref<ITfRange> range;
                if (composition && SUCCEEDED(composition->GetRange(range.put())) && range)
                    clearAttribute(cookie, s.context.get(), range.get());
                s.composition.reset();
                s.written.clear();
                s.preedit.clear();
                s.last = {};
                s.highlighted = 0;
                s.expanded = false;
                if (s.session) s.session->clear();
                if (entry.second == active_) hideWindow();
            }
        } catch (...) {
        }
        return S_OK;
    }

    // ITfDisplayAttributeProvider
    HRESULT STDMETHODCALLTYPE EnumDisplayAttributeInfo(IEnumTfDisplayAttributeInfo **result) override {
        if (!result) return E_INVALIDARG;
        *result = new (std::nothrow) AttributeList;
        return *result ? S_OK : E_OUTOFMEMORY;
    }
    HRESULT STDMETHODCALLTYPE GetDisplayAttributeInfo(REFGUID guid, ITfDisplayAttributeInfo **result) override {
        if (!result) return E_INVALIDARG;
        *result = nullptr;
        if (!IsEqualGUID(guid, kReadingAttribute)) return E_INVALIDARG;
        *result = new (std::nothrow) AttributeInfo;
        return *result ? S_OK : E_OUTOFMEMORY;
    }

    // ITfInputProcessorProfileActivationSink: switching between 喵撲拼音 and
    // 喵撲注音 keeps this object and only reports the new profile here.
    HRESULT STDMETHODCALLTYPE OnActivated(DWORD type, LANGID, REFCLSID clsid, REFGUID, REFGUID profile, HKL,
                                          DWORD flags) override {
        try {
            if (type == TF_PROFILETYPE_INPUTPROCESSOR && IsEqualCLSID(clsid, kServiceId) && (flags & TF_IPSINK_FLAG_ACTIVE))
                setMode(IsEqualGUID(profile, kZhuyinProfile) ? InputMode::Zhuyin : InputMode::Pinyin);
        } catch (...) {
        }
        return S_OK;
    }

    // ITfTextLayoutSink: the host moved or laid out the text. Hosts such as
    // Chromium give no position inside the edit session that wrote the reading
    // and report it here once they have one.
    HRESULT STDMETHODCALLTYPE OnLayoutChange(ITfContext *context, TfLayoutCode code, ITfContextView *) override {
        try {
            if (code != TF_LC_CHANGE || !active_ || active_->context.get() != context || !active_->composition)
                return S_OK;
            auto *edit = new EditSession(this, active_, true);
            HRESULT inner = S_OK;
            context->RequestEditSession(clientId_, edit, TF_ES_ASYNCDONTCARE | TF_ES_READ, &inner);
            edit->Release();
        } catch (...) {
        }
        return S_OK;
    }

    // Runs inside an edit session: brings the document in line with the state.
    HRESULT write(TfEditCookie cookie, ContextState &s) {
        ITfContext *context = s.context.get();
        if (!s.pending.empty()) {
            HRESULT result = E_FAIL;
            Ref<ITfRange> range;
            if (s.composition) {
                // The committed text replaces the reading and the composition ends.
                result = s.composition->GetRange(range.put());
                if (SUCCEEDED(result)) result = range->SetText(cookie, 0, s.pending.c_str(), static_cast<LONG>(s.pending.size()));
                if (SUCCEEDED(result)) clearAttribute(cookie, context, range.get());
                if (SUCCEEDED(result)) {
                    caretAfter(cookie, context, range.get());
                    // The host may have ended it inside SetText (OnCompositionTerminated).
                    if (s.composition) s.composition->EndComposition(cookie);
                    s.composition.reset();
                    s.written.clear();
                }
            } else if (auto insert = query<ITfInsertAtSelection>(context, IID_ITfInsertAtSelection)) {
                result = insert->InsertTextAtSelection(cookie, 0, s.pending.c_str(), static_cast<LONG>(s.pending.size()),
                                                       range.put());
                if (SUCCEEDED(result) && range) caretAfter(cookie, context, range.get());
            }
            // Refused text stays queued for this document and goes with the next change.
            if (FAILED(result)) return result;
            s.pending.clear();
        }
        if (!s.preedit.empty()) {
            if (!s.composition) startComposition(cookie, s);
            Ref<ITfRange> range;
            if (s.composition && SUCCEEDED(s.composition->GetRange(range.put())) && range &&
                SUCCEEDED(range->SetText(cookie, 0, s.preedit.c_str(), static_cast<LONG>(s.preedit.size())))) {
                setAttribute(cookie, context, range.get());
                caretAfter(cookie, context, range.get());
                s.written = s.preedit;
            }
        } else if (s.composition) {
            // Cancelled or deleted down to nothing.
            Ref<ITfRange> range;
            if (SUCCEEDED(s.composition->GetRange(range.put())) && range) range->SetText(cookie, 0, L"", 0);
            if (s.composition) s.composition->EndComposition(cookie);
            s.composition.reset();
            s.written.clear();
        }
        readCaret(cookie, s);
        return S_OK;
    }

    static void locate(TfEditCookie cookie, ContextState &s) { readCaret(cookie, s); }
    void edited(const std::shared_ptr<ContextState> &s) {
        if (s == active_) showWindow(*s);
    }

    // From the taskbar's mode button.
    bool english() const { return english_; }
    bool simplified() const { return simplified_; }
    void switchEnglish() { toggleEnglish(active_ ? active_->context.get() : nullptr); }
    void chooseScript(bool simplified) {
        if (simplified != simplified_) toggleSimplified();
    }

private:
    // --- key planning -------------------------------------------------------
    // plan() has no visible effect, so OnTestKeyDown and OnKeyDown agree.
    bool usable(ITfContext *context) const {
        TF_STATUS status{};
        if (FAILED(context->GetStatus(&status)) || (status.dwDynamicFlags & TF_SD_READONLY)) return false;
        if (compartmentOn(context, GUID_COMPARTMENT_KEYBOARD_DISABLED) || compartmentOn(context, GUID_COMPARTMENT_EMPTYCONTEXT))
            return false;
        // A classic password box.
        HWND focus = GetFocus();
        wchar_t name[16] = {};
        return !(focus && GetClassNameW(focus, name, 16) && lstrcmpiW(name, L"Edit") == 0 &&
                 (GetWindowLongPtrW(focus, GWL_STYLE) & ES_PASSWORD));
    }

    ContextState *stateFor(ITfContext *context) {
        auto found = states_.find(context);
        if (found == states_.end()) {
            found = states_.emplace(context, std::make_shared<ContextState>(context)).first;
            if (auto source = query<ITfSource>(context, IID_ITfSource))
                source->AdviseSink(kIidTextLayoutSink, static_cast<ITfTextLayoutSink *>(this), &found->second->layoutCookie);
        }
        ContextState &s = *found->second;
        if (!s.session || s.mode != mode_) {
            s.session = makeSession(mode_, simplified_);
            s.mode = mode_;
            s.last = {};
        }
        return s.session ? &s : nullptr;
    }

    Plan plan(ITfContext *context, WPARAM vk, LPARAM lp) {
        if (!activated_ || english_ || !context || !usable(context)) return {};
        ContextState *s = stateFor(context);
        if (!s) return {};
        Session &session = *s->session;
        const bool composing = session.hasComposition();
        // Caps Lock types capitals; a reading already open is still ended by them.
        if (!composing && (GetKeyState(VK_CAPITAL) & 1)) return {};
        Plan p;
        p.event = keyEvent(vk, lp);
        const auto count = s->last.candidates.size();
        // A shortcut such as Ctrl+Enter acts on the text: the reading is ended
        // first, as macOS does through commitComposition.
        if (p.event.control || p.event.alt || p.event.win) {
            if (composing && !isModifier(vk)) p.act = Act::FinishThenPass;
            return p;
        }
        if (composing && vk == VK_RETURN && p.event.shift) {
            // Shift+Enter sends the letters as typed (macOS does the same).
            p.act = Act::Literal;
            return p;
        }
        if (composing && count > 0 && !p.event.shift) {
            const auto act = [&](Act a, int delta = 0) {
                p.act = a;
                p.delta = delta;
                return p;
            };
            switch (vk) {
            case VK_LEFT: return act(Act::Move, -1);
            case VK_RIGHT: return act(Act::Move, +1);
            case VK_TAB: return act(Act::Move, +1);
            case VK_UP: return s->expanded ? act(Act::Collapse) : act(Act::Move, -1);
            case VK_DOWN: return s->expanded ? act(Act::Move, +1) : act(Act::Expand);
            case VK_ESCAPE:
                if (s->expanded) return act(Act::Collapse);
                break;
            case VK_RETURN: return act(Act::Choose);
            case VK_SPACE:
                // Pinyin: Space takes the highlighted candidate (macOS does the
                // same). Zhuyin: Space is the first tone, and after a tone it
                // takes the candidate and ends the reading.
                if (mode_ == InputMode::Pinyin) return act(Act::Choose);
                if (std::string_view(" 6347").find(session.input().back()) != std::string_view::npos)
                    return act(Act::Finish);
                break;
            default: break;
            }
        }
        if (composing && count > 0 && p.event.shift && vk == VK_TAB) {
            p.act = Act::Move;
            p.delta = -1;
            return p;
        }
        if (canHandleKey(session, mode_, p.event)) {
            p.act = Act::Engine;
            return p;
        }
        if (const wchar_t mark = punctuation(mode_, p.event)) {
            p.act = Act::Insert;
            p.text = mark;
            return p;
        }
        if (composing && p.event.printable) {
            // Any other character ends the reading and is typed after it.
            p.act = Act::Insert;
            p.text = static_cast<wchar_t>(p.event.printable);
            return p;
        }
        if (composing && (vk == VK_LEFT || vk == VK_RIGHT || vk == VK_UP || vk == VK_DOWN)) {
            // No caret moves inside the reading (macOS keeps these too).
            p.act = Act::Ignore;
            return p;
        }
        // Delete, Home, F5 and the like act on the text around the reading.
        if (composing && !isModifier(vk)) p.act = Act::FinishThenPass;
        return p;
    }

    void perform(const std::shared_ptr<ContextState> &state, const Plan &p) {
        ContextState &s = *state;
        Session &session = *s.session;
        if (!session.hasComposition()) syncSimplified();
        const auto count = s.last.candidates.size();
        switch (p.act) {
        case Act::Engine: handleKey(session, mode_, p.event); break;
        case Act::Move:
            s.highlighted = (std::min(s.highlighted, count - 1) + (p.delta > 0 ? 1 : count - 1)) % count;
            active_ = state;
            showWindow(s);
            return;
        case Act::Expand:
        case Act::Collapse:
            s.expanded = p.act == Act::Expand;
            active_ = state;
            showWindow(s);
            return;
        case Act::Choose: session.chooseOnPage(std::min(s.highlighted, count - 1)); break;
        case Act::Finish: finish(s); break;
        case Act::Insert: finish(s); break;
        case Act::Literal: {
            const std::string typed(session.input());
            session.clear();
            deliver(state, widen(typed));
            return;
        }
        case Act::FinishThenPass:
            // Synchronously when the host allows it: the key that follows must
            // find the finished text in the document, not the reading.
            finish(s);
            deliver(state, {}, true);
            return;
        case Act::Ignore:
        case Act::Pass: return;
        }
        deliver(state, p.act == Act::Insert ? std::wstring(1, p.text) : std::wstring());
    }

    // Takes the highlighted candidate, then the first candidate of whatever
    // is left, until the reading is used up.
    void finish(ContextState &s) {
        Session &session = *s.session;
        if (!session.hasComposition()) return;
        const auto count = s.last.candidates.size();
        if (count > 0) session.chooseOnPage(std::min(s.highlighted, count - 1));
        for (int i = 0; i < 64 && session.hasComposition(); ++i) session.commit();
        if (session.hasComposition()) session.commitLiteralWithSpace();
    }

    // Moves the session's commit into the pending queue, then asks for an edit
    // session to write it and the new reading.
    void deliver(const std::shared_ptr<ContextState> &state, const std::wstring &tail = {}, bool now = false) {
        ContextState &s = *state;
        Snapshot snapshot = s.session->takeSnapshot();
        s.pending += widen(snapshot.committed) + tail;
        if (snapshot.input != s.last.input || snapshot.page != s.last.page) s.highlighted = 0;
        if (!snapshot.committed.empty() || !tail.empty() || snapshot.input.empty()) s.expanded = false;
        snapshot.committed.clear();
        s.preedit = widen(snapshot.preedit);
        s.last = std::move(snapshot);
        // A new reading may start anywhere: wait for the edit session to say where.
        if (!s.composition) s.haveCaret = false;
        active_ = state;
        showWindow(s);
        if (s.pending.empty() && s.preedit == s.written) return;
        auto *edit = new EditSession(this, state);
        HRESULT inner = S_OK;
        if (!now || FAILED(s.context->RequestEditSession(clientId_, edit, TF_ES_SYNC | TF_ES_READWRITE, &inner)) ||
            inner == TF_E_SYNCHRONOUS)
            s.context->RequestEditSession(clientId_, edit, TF_ES_ASYNCDONTCARE | TF_ES_READWRITE, &inner);
        edit->Release();
    }

    // --- shift tap: Chinese / English -----------------------------------------
    // A lone Shift press switches Chinese and English. Mouse clicks never
    // reach the key sink, so the thread's key state is compared instead: every
    // press of a key or mouse button flips its low bit, and keys the shell
    // takes for itself (Win+Shift+S) still flip Win's. Any other key or button
    // pressed, or still held, between Shift down and up cancels the switch;
    // so does a press longer than a second.
    void noteKeyDown(WPARAM vk) {
        if (!isShift(vk)) {
            shiftTap_ = false;
        } else if (!shiftDown_) {
            shiftDown_ = true;
            shiftAt_ = GetTickCount64();
            GetKeyboardState(shiftKeys_);
            shiftTap_ = !otherKeyChanged(shiftKeys_, true);
        }
    }
    // True once per press; TSF may ask about the same key-up twice.
    bool shiftTapped(WPARAM vk) {
        if (!isShift(vk)) return false;
        bool tap = shiftDown_ && shiftTap_ && GetTickCount64() - shiftAt_ < 1000;
        if (tap) {
            BYTE now[256] = {};
            tap = GetKeyboardState(now) && !otherKeyChanged(now, false);
        }
        cancelShift();
        return tap;
    }
    // With `held`, whether any key but Shift is down in `keys`; without it,
    // also whether any was pressed since Shift went down.
    bool otherKeyChanged(const BYTE *keys, bool held) const {
        for (int vk = 1; vk < 256; ++vk) {
            // Lock keys flip on their own; TSF's VK_PROCESSKEY and VK_PACKET stand in for keys already counted.
            if (isShift(static_cast<WPARAM>(vk)) || vk == VK_CAPITAL || vk == VK_NUMLOCK || vk == VK_SCROLL ||
                vk == VK_PROCESSKEY || vk == VK_PACKET)
                continue;
            if (keys[vk] & 0x80) return true;
            if (!held && ((keys[vk] ^ shiftKeys_[vk]) & 1)) return true;
        }
        return false;
    }
    void cancelShift() { shiftDown_ = shiftTap_ = false; }
    void toggleEnglish(ITfContext *context) {
        if (!activated_) return;
        const auto found = states_.find(context);
        if (found != states_.end() && found->second->session && found->second->session->hasComposition()) {
            finish(*found->second);
            deliver(found->second);
        }
        english_ = !english_;
        if (button_) button_->changed();
    }

    void setMode(InputMode next) {
        if (next == mode_) return;
        if (english_ && button_) {
            english_ = false;
            button_->changed();
        }
        for (auto &entry : states_) {
            if (entry.second->session && entry.second->session->hasComposition()) {
                finish(*entry.second);
                deliver(entry.second);
            }
        }
        mode_ = next;
        english_ = false;
    }

    // --- Traditional / Simplified ---------------------------------------------
    void applySimplified(bool on) {
        simplified_ = on;
        for (auto &entry : states_)
            if (entry.second->session) entry.second->session->setSimplified(on);
        if (window_) {
            auto options = window_->options();
            options.simplified = on;
            window_->setOptions(options);
        }
    }
    // Another program may have switched; a new reading follows it.
    void syncSimplified() {
        const bool stored = storedSimplified();
        if (stored != simplified_) applySimplified(stored);
    }
    void toggleSimplified() {
        applySimplified(!simplified_);
        storeSimplified(simplified_);
        if (active_ && active_->session && active_->session->hasComposition()) deliver(active_);
    }

    // --- candidate window ------------------------------------------------------
    template <class F>
    void fromWindow(F &&f) noexcept {
        AddRef();
        try {
            if (active_ && active_->session) f(active_);
        } catch (...) {
            hideWindow();
        }
        Release();
    }

    void ensureWindow() {
        if (window_ || !activated_) return;
        ui::CandidateWindowOptions options;
        options.simplified = simplified_;
        ui::CandidateWindowCallbacks callbacks;
        callbacks.choose = [this](std::size_t index) {
            fromWindow([&](const std::shared_ptr<ContextState> &s) {
                if (s->session->chooseOnPage(index)) deliver(s);
            });
        };
        callbacks.page = [this](int direction) {
            fromWindow([&](const std::shared_ptr<ContextState> &s) {
                if (s->session->key(direction < 0 ? 0xff55 : 0xff56)) deliver(s);
            });
        };
        callbacks.toggleExpanded = [this] {
            fromWindow([&](const std::shared_ptr<ContextState> &s) {
                s->expanded = !s->expanded;
                showWindow(*s);
            });
        };
        callbacks.toggleScript = [this] { fromWindow([&](const std::shared_ptr<ContextState> &) { toggleSimplified(); }); };
        window_ = std::make_unique<ui::CandidateWindow>(static_cast<HINSTANCE>(g_module), options, std::move(callbacks));
    }

    void showWindow(ContextState &s) {
        if (!activated_ || &s != active_.get()) return;
        if (!s.session || !s.session->hasComposition() || s.last.candidates.empty()) {
            hideWindow();
            return;
        }
        ensureWindow();
        if (!window_ || !window_->valid()) return;
        ui::CandidateWindowState view;
        view.highlighted = s.highlighted;
        view.expanded = s.expanded;
        view.zhuyin = s.mode == InputMode::Zhuyin;
        view.simplified = simplified_;
        window_->update(ui::makeViewModel(s.last, view, window_->options().shape));
        if (s.haveCaret) window_->showNear(s.caret);
    }

    static void unadviseLayout(ContextState &s) {
        if (s.layoutCookie == TF_INVALID_COOKIE) return;
        if (auto source = query<ITfSource>(s.context.get(), IID_ITfSource)) source->UnadviseSink(s.layoutCookie);
        s.layoutCookie = TF_INVALID_COOKIE;
    }

    void hideWindow() noexcept {
        if (window_) window_->hide();
    }

    // --- edit session pieces ---------------------------------------------------
    void startComposition(TfEditCookie cookie, ContextState &s) {
        auto insert = query<ITfInsertAtSelection>(s.context.get(), IID_ITfInsertAtSelection);
        auto composer = query<ITfContextComposition>(s.context.get(), IID_ITfContextComposition);
        Ref<ITfRange> range;
        if (!insert || !composer ||
            FAILED(insert->InsertTextAtSelection(cookie, TF_IAS_QUERYONLY, nullptr, 0, range.put())) || !range)
            return;
        Ref<ITfComposition> composition;
        if (SUCCEEDED(composer->StartComposition(cookie, range.get(), static_cast<ITfCompositionSink *>(this),
                                                 composition.put())))
            s.composition = composition; // null when the host declines
    }

    void setAttribute(TfEditCookie cookie, ITfContext *context, ITfRange *range) {
        if (attributeAtom_ == TF_INVALID_GUIDATOM) return;
        Ref<ITfProperty> property;
        if (FAILED(context->GetProperty(GUID_PROP_ATTRIBUTE, property.put())) || !property) return;
        VARIANT value;
        VariantInit(&value);
        value.vt = VT_I4;
        value.lVal = static_cast<LONG>(attributeAtom_);
        property->SetValue(cookie, range, &value);
    }

    static void clearAttribute(TfEditCookie cookie, ITfContext *context, ITfRange *range) {
        Ref<ITfProperty> property;
        if (SUCCEEDED(context->GetProperty(GUID_PROP_ATTRIBUTE, property.put())) && property) property->Clear(cookie, range);
    }

    static void caretAfter(TfEditCookie cookie, ITfContext *context, ITfRange *range) {
        Ref<ITfRange> end;
        if (FAILED(range->Clone(end.put())) || !end) return;
        end->Collapse(cookie, TF_ANCHOR_END);
        TF_SELECTION selection{};
        selection.range = end.get();
        selection.style.ase = TF_AE_NONE;
        selection.style.fInterimChar = FALSE;
        context->SetSelection(cookie, 1, &selection);
    }

    // Where the reading is on screen. A host that has no layout yet keeps the
    // last place; the very first time the system caret stands in.
    static void readCaret(TfEditCookie cookie, ContextState &s) {
        Ref<ITfContextView> view;
        Ref<ITfRange> range;
        if (s.composition) {
            s.composition->GetRange(range.put());
        } else {
            TF_SELECTION selection{};
            ULONG fetched = 0;
            if (SUCCEEDED(s.context->GetSelection(cookie, TF_DEFAULT_SELECTION, 1, &selection, &fetched)) && fetched)
                range = Ref<ITfRange>::adopt(selection.range);
        }
        RECT rect{};
        BOOL clipped = FALSE;
        HWND window = nullptr;
        if (range && SUCCEEDED(s.context->GetActiveView(view.put())) && view &&
            SUCCEEDED(view->GetTextExt(cookie, range.get(), &rect, &clipped)) && rect.bottom > rect.top) {
            view->GetWnd(&window);
            toPhysical(window, rect);
            s.caret = rect;
            s.haveCaret = true;
            return;
        }
        if (s.haveCaret) return;
        GUITHREADINFO gui{};
        gui.cbSize = sizeof gui;
        if (GetGUIThreadInfo(GetCurrentThreadId(), &gui) && gui.hwndCaret) {
            rect = gui.rcCaret;
            MapWindowPoints(gui.hwndCaret, nullptr, reinterpret_cast<POINT *>(&rect), 2);
            toPhysical(gui.hwndCaret, rect);
            s.caret = rect;
            s.haveCaret = true;
        }
    }

    Ref<ITfThreadMgr> threadMgr_;
    Ref<ITfKeystrokeMgr> keystrokes_;
    TfClientId clientId_ = TF_CLIENTID_NULL;
    DWORD threadCookie_ = TF_INVALID_COOKIE, focusCookie_ = TF_INVALID_COOKIE, profileCookie_ = TF_INVALID_COOKIE;
    TfGuidAtom attributeAtom_ = TF_INVALID_GUIDATOM;
    bool activated_ = false;
    InputMode mode_ = InputMode::Pinyin;
    bool english_ = false, simplified_ = false;
    Ref<ITfLangBarItemMgr> langBar_;
    Ref<ModeButton> button_;
    bool shiftDown_ = false, shiftTap_ = false;
    ULONGLONG shiftAt_ = 0;
    BYTE shiftKeys_[256] = {};
    std::unordered_map<ITfContext *, std::shared_ptr<ContextState>> states_;
    std::shared_ptr<ContextState> active_; // the context whose candidates are on screen
    std::unique_ptr<ui::CandidateWindow> window_;
};

bool ModeButton::english() const { return owner_ && owner_->english(); }
HRESULT ModeButton::OnClick(TfLBIClick click, POINT, const RECT *) {
    try {
        if (click == TF_LBI_CLK_LEFT && owner_) owner_->switchEnglish();
    } catch (...) {
    }
    return S_OK;
}
HRESULT ModeButton::InitMenu(ITfMenu *menu) {
    if (!menu || !owner_) return S_OK;
    const bool simplified = owner_->simplified();
    menu->AddMenuItem(1, simplified ? 0 : TF_LBMENUF_RADIOCHECKED, nullptr, nullptr, L"繁體中文", 4, nullptr);
    menu->AddMenuItem(2, simplified ? TF_LBMENUF_RADIOCHECKED : 0, nullptr, nullptr, L"简体中文", 4, nullptr);
    return S_OK;
}
HRESULT ModeButton::OnMenuSelect(UINT id) {
    try {
        if (owner_ && (id == 1 || id == 2)) owner_->chooseScript(id == 2);
    } catch (...) {
    }
    return S_OK;
}
HRESULT ModeButton::GetIcon(HICON *icon) {
    if (!icon) return E_INVALIDARG;
    *icon = modeIcon(english());
    return *icon ? S_OK : E_FAIL;
}

EditSession::EditSession(Service *service, std::shared_ptr<ContextState> state, bool caretOnly)
    : service_(service), state_(std::move(state)), caretOnly_(caretOnly) {
    service_->AddRef();
}
EditSession::~EditSession() { service_->Release(); }
HRESULT EditSession::DoEditSession(TfEditCookie cookie) {
    try {
        HRESULT result = S_OK;
        if (caretOnly_) Service::locate(cookie, *state_);
        else result = service_->write(cookie, *state_);
        service_->edited(state_);
        return result;
    } catch (...) {
        return E_FAIL;
    }
}

class Factory final : public IClassFactory, public Object {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **out) override {
        if (!out) return E_POINTER;
        *out = nullptr;
        if (iid != IID_IUnknown && iid != IID_IClassFactory) return E_NOINTERFACE;
        *out = static_cast<IClassFactory *>(this);
        AddRef();
        return S_OK;
    }
    MEOWPUUT_REFCOUNT
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown *outer, REFIID iid, void **out) override {
        if (!out) return E_POINTER;
        *out = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        auto *service = new (std::nothrow) Service;
        if (!service) return E_OUTOFMEMORY;
        const HRESULT result = service->QueryInterface(iid, out);
        service->Release();
        return result;
    }
    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override {
        if (lock) ++g_objects;
        else --g_objects;
        return S_OK;
    }
};

// --- registration ---------------------------------------------------------------

std::wstring classKey() {
    wchar_t clsid[40] = {};
    StringFromGUID2(kServiceId, clsid, 40);
    return std::wstring(L"Software\\Classes\\CLSID\\") + clsid;
}

HRESULT setString(HKEY key, const wchar_t *name, const std::wstring &value) {
    const LSTATUS status = RegSetValueExW(key, name, 0, REG_SZ, reinterpret_cast<const BYTE *>(value.c_str()),
                                          static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
    return HRESULT_FROM_WIN32(status);
}

HRESULT registerServer(const std::wstring &dll) {
    HKEY server = nullptr;
    const std::wstring key = classKey() + L"\\InprocServer32";
    const LSTATUS status = RegCreateKeyExW(HKEY_LOCAL_MACHINE, key.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &server,
                                           nullptr);
    if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
    HRESULT result = setString(server, nullptr, dll);
    if (SUCCEEDED(result)) result = setString(server, L"ThreadingModel", L"Apartment");
    RegCloseKey(server);
    return result;
}

HRESULT registerProfiles(bool install, const std::wstring &dll) {
    Ref<ITfInputProcessorProfileMgr> profiles;
    HRESULT result = CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr, CLSCTX_INPROC_SERVER,
                                      IID_ITfInputProcessorProfileMgr, profiles.putVoid());
    if (FAILED(result)) return result;
    for (const Profile &profile : kProfiles) {
        result = install ? profiles->RegisterProfile(kServiceId, kLanguage, *profile.guid, profile.name,
                                                     static_cast<ULONG>(wcslen(profile.name)), dll.c_str(),
                                                     static_cast<ULONG>(dll.size()), 0, nullptr, 0, TRUE, 0)
                         : profiles->UnregisterProfile(kServiceId, kLanguage, *profile.guid, 0);
        if (install && FAILED(result)) return result;
    }
    Ref<ITfCategoryMgr> categories;
    result = CoCreateInstance(CLSID_TF_CategoryMgr, nullptr, CLSCTX_INPROC_SERVER, IID_ITfCategoryMgr, categories.putVoid());
    if (FAILED(result)) return result;
    for (const GUID *category :
         {&GUID_TFCAT_TIP_KEYBOARD, &GUID_TFCAT_DISPLAYATTRIBUTEPROVIDER, &kCategoryImmersive, &kCategorySystray}) {
        result = install ? categories->RegisterCategory(kServiceId, *category, kServiceId)
                         : categories->UnregisterCategory(kServiceId, *category, kServiceId);
        if (install && FAILED(result)) return result;
    }
    // Without this an empty TIP key stays under HKLM\SOFTWARE\Microsoft\CTF\TIP.
    Ref<ITfInputProcessorProfiles> old;
    if (!install && SUCCEEDED(CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr, CLSCTX_INPROC_SERVER,
                                               IID_ITfInputProcessorProfiles, old.putVoid())))
        old->Unregister(kServiceId);
    return S_OK;
}

} // namespace

extern "C" BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = instance;
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID iid, LPVOID *out) {
    if (!out) return E_POINTER;
    *out = nullptr;
    if (!IsEqualCLSID(clsid, kServiceId)) return CLASS_E_CLASSNOTAVAILABLE;
    auto *factory = new (std::nothrow) Factory;
    if (!factory) return E_OUTOFMEMORY;
    const HRESULT result = factory->QueryInterface(iid, out);
    factory->Release();
    return result;
}

STDAPI DllCanUnloadNow() { return g_objects.load() == 0 ? S_OK : S_FALSE; }

STDAPI DllRegisterServer() {
    try {
        const std::wstring dll = modulePath();
        if (dll.empty()) return SELFREG_E_CLASS;
        HRESULT result = registerServer(dll);
        if (SUCCEEDED(result)) result = registerProfiles(true, dll);
        if (FAILED(result)) {
            registerProfiles(false, dll);
            RegDeleteTreeW(HKEY_LOCAL_MACHINE, classKey().c_str());
        }
        return result;
    } catch (...) {
        return E_FAIL;
    }
}

STDAPI DllUnregisterServer() {
    try {
        const HRESULT result = registerProfiles(false, modulePath());
        const LSTATUS status = RegDeleteTreeW(HKEY_LOCAL_MACHINE, classKey().c_str());
        if (status != ERROR_SUCCESS && status != ERROR_FILE_NOT_FOUND) return HRESULT_FROM_WIN32(status);
        return result;
    } catch (...) {
        return E_FAIL;
    }
}
