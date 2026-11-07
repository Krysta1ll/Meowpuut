#include <windows.h>
#include <msctf.h>
#include <array>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <new>
#include <string>
#include "WordEvidence.h"

namespace {
const CLSID serviceId = {0x961c0ada,0x4dc6,0x488f,{0x95,0x63,0x4f,0xc1,0x44,0xef,0xae,0x27}};
const GUID profileId = {0x8fde097d,0x3513,0x486a,{0x9a,0xb5,0x2d,0x1b,0x2c,0x67,0xc3,0x59}};
constexpr size_t pageSize = 7;
constexpr LANGID language = MAKELANGID(LANG_CHINESE, SUBLANG_CHINESE_TRADITIONAL);
constexpr wchar_t name[] = L"Meowpuut Pinyin";
constexpr wchar_t classKey[] = L"Software\\Classes\\CLSID\\{961C0ADA-4DC6-488F-9563-4FC144EFAE27}";
HMODULE module;
LONG objects;

std::wstring modulePath() {
    std::wstring path(32768, L'\0');
    const DWORD length = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
    path.resize(length < path.size() ? length : 0);
    return path;
}

std::wstring wide(const char *text) {
    const int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, nullptr, 0);
    if (length <= 1) return {};
    std::wstring result(length, L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, result.data(), length);
    result.pop_back();
    return result;
}

class RefCount {
public:
    RefCount() { InterlockedIncrement(&objects); }
    virtual ~RefCount() { InterlockedDecrement(&objects); }
    ULONG retain() { return InterlockedIncrement(&refs); }
    ULONG release() {
        const LONG remaining = InterlockedDecrement(&refs);
        if (!remaining) delete this;
        return remaining;
    }
private:
    LONG refs = 1;
};

class EditSession final : public ITfEditSession, public RefCount {
public:
    EditSession(ITfContext *context, std::wstring text) : context(context), text(std::move(text)) { context->AddRef(); }
    ~EditSession() override { context->Release(); }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **out) override {
        *out = nullptr;
        if (id != IID_IUnknown && id != IID_ITfEditSession) return E_NOINTERFACE;
        *out = static_cast<ITfEditSession *>(this);
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return retain(); }
    ULONG STDMETHODCALLTYPE Release() override { return release(); }
    RECT caret{};

    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override {
        if (text.empty()) return readCaret(cookie);
        ITfInsertAtSelection *selection = nullptr;
        HRESULT result = context->QueryInterface(IID_ITfInsertAtSelection, reinterpret_cast<void **>(&selection));
        if (FAILED(result)) return result;
        ITfRange *inserted = nullptr;
        result = selection->InsertTextAtSelection(cookie, 0, text.c_str(),
                                                 static_cast<LONG>(text.size()), &inserted);
        selection->Release();
        if (SUCCEEDED(result) && inserted) {
            // Move the insertion point past the committed text in rich-text hosts.
            if (SUCCEEDED(inserted->Collapse(cookie, TF_ANCHOR_END))) {
                TF_SELECTION caret{inserted, {TF_AE_NONE, FALSE}};
                context->SetSelection(cookie, 1, &caret);
            }
        }
        if (inserted) inserted->Release();
        return result;
    }
private:
    HRESULT readCaret(TfEditCookie cookie) {
        ITfContextView *view = nullptr;
        HRESULT result = context->GetActiveView(&view);
        if (FAILED(result)) return result;
        TF_SELECTION selection{};
        ULONG fetched = 0;
        result = context->GetSelection(cookie, TF_DEFAULT_SELECTION, 1, &selection, &fetched);
        if (SUCCEEDED(result) && fetched) {
            BOOL clipped = FALSE;
            const TfAnchor edge = selection.style.ase == TF_AE_START ? TF_ANCHOR_START : TF_ANCHOR_END;
            result = selection.range->Collapse(cookie, edge);
            if (SUCCEEDED(result)) result = view->GetTextExt(cookie, selection.range, &caret, &clipped);
            selection.range->Release();
            if (clipped || caret.bottom <= caret.top) result = S_FALSE;
        } else if (SUCCEEDED(result)) {
            result = S_FALSE;
        }
        view->Release();
        return result;
    }

    ITfContext *context;
    std::wstring text;
};

class Service final : public ITfTextInputProcessor, public ITfKeyEventSink, public RefCount {
public:
    ~Service() override { Deactivate(); }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **out) override {
        *out = nullptr;
        if (id == IID_IUnknown || id == IID_ITfTextInputProcessor)
            *out = static_cast<ITfTextInputProcessor *>(this);
        else if (id == IID_ITfKeyEventSink)
            *out = static_cast<ITfKeyEventSink *>(this);
        else return E_NOINTERFACE;
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return retain(); }
    ULONG STDMETHODCALLTYPE Release() override { return release(); }

    HRESULT STDMETHODCALLTYPE Activate(ITfThreadMgr *manager, TfClientId id) override {
        try {
            const auto folder = std::filesystem::path(modulePath()).parent_path();
            std::ifstream file(folder / L"native-pinyin.tsv", std::ios::binary);
            std::ifstream japanese(folder / L"japanese.tsv", std::ios::binary);
            japaneseWords = readJapaneseWords(japanese);
            evidence.open(folder / L"language-evidence.sqlite");
            std::string bytes((std::istreambuf_iterator<char>(file)), {});
            MPNLexicon *lexicon = mpn_lexicon_create(bytes.data(), bytes.size());
            session = mpn_session_create(lexicon);
            mpn_lexicon_destroy(lexicon);
        } catch (...) { return E_FAIL; }
        if (!session) return E_FAIL;
        HRESULT result = manager->QueryInterface(IID_ITfKeystrokeMgr, reinterpret_cast<void **>(&keys));
        client = id;
        if (SUCCEEDED(result)) result = keys->AdviseKeyEventSink(client, this, TRUE);
        if (FAILED(result)) Deactivate();
        return result;
    }

    HRESULT STDMETHODCALLTYPE Deactivate() override {
        if (lastForeground) sendGlass({});
        if (keys) {
            auto *manager = keys;
            keys = nullptr;
            manager->UnadviseKeyEventSink(client);
            manager->Release();
        }
        if (renderer) {
            if (HWND target = rendererWindow()) PostMessageW(target, WM_CLOSE, 0, 0);
            CloseHandle(renderer);
            renderer = nullptr;
        }
        if (popup) {
            DestroyWindow(popup);
            popup = nullptr;
        }
        if (activeContext) {
            activeContext->Release();
            activeContext = nullptr;
        }
        mpn_session_destroy(session);
        session = nullptr;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE OnSetFocus(BOOL) override {
        if (session) mpn_clear(session);
        resetCandidates();
        sendGlass({});
        if (popup) ShowWindow(popup, SW_HIDE);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnTestKeyDown(ITfContext *context, WPARAM key, LPARAM, BOOL *eaten) override {
        *eaten = accepts(context, key);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnTestKeyUp(ITfContext *, WPARAM, LPARAM, BOOL *eaten) override {
        *eaten = FALSE;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyUp(ITfContext *, WPARAM, LPARAM, BOOL *eaten) override {
        *eaten = FALSE;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnPreservedKey(ITfContext *, REFGUID, BOOL *eaten) override {
        *eaten = FALSE;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyDown(ITfContext *context, WPARAM key, LPARAM, BOOL *eaten) override {
        *eaten = FALSE;
        if (!accepts(context, key)) return S_OK;
        try {
            if (activeContext != context) {
                mpn_clear(session);
                resetCandidates();
                if (activeContext) activeContext->Release();
                activeContext = context;
                activeContext->AddRef();
            }
            const size_t start = page * pageSize;
            const size_t total = candidateCount();
            const size_t count = total > start ? (std::min)(pageSize, total - start) : 0;
            std::wstring text;
            if (key == VK_F4) {
                expanded = !expanded;
                *eaten = TRUE;
            } else if (key == VK_SPACE) {
                text = wide(mpn_input(session)) + L" ";
                mpn_clear(session);
                resetCandidates();
                *eaten = TRUE;
            } else if (key == VK_PRIOR || key == VK_NEXT) {
                if (key == VK_PRIOR && page) --page;
                if (key == VK_NEXT && (page + 1) * pageSize < total) ++page;
                highlighted = 0;
                *eaten = TRUE;
            } else if (const wchar_t mark = punctuation(key)) {
                size_t choice = start + highlighted;
                while (*mpn_input(session)) {
                    const std::string remaining = mpn_input(session);
                    if (!chooseCandidate(choice, text)) mpn_commit(session);
                    text += wide(mpn_committed(session));
                    mpn_clear_commit(session);
                    if (remaining == mpn_input(session)) {
                        text += wide(remaining.c_str());
                        mpn_clear(session);
                        break;
                    }
                    refreshCandidates();
                    choice = 0;
                }
                text += mark;
                highlighted = 0;
                *eaten = TRUE;
            } else if (navigation(key) && count) {
                const bool backward = key == VK_LEFT || key == VK_UP || (key == VK_TAB && GetKeyState(VK_SHIFT) < 0);
                const size_t offset = backward ? count - 1 : 1;
                highlighted = (highlighted + offset) % count;
                *eaten = TRUE;
            } else {
                if (key >= '1' && key <= '7')
                    *eaten = chooseCandidate(start + key - '1', text);
                else if (key == VK_RETURN && count)
                    *eaten = chooseCandidate(start + (std::min)(highlighted, count - 1), text);
                else
                    *eaten = mpn_key(session, translate(key));
                refreshCandidates();
            }

            text += wide(mpn_committed(session));
            if (!text.empty()) {
                auto *edit = new EditSession(context, std::move(text));
                HRESULT editResult = E_FAIL;
                HRESULT request = context->RequestEditSession(client, edit, TF_ES_SYNC | TF_ES_READWRITE, &editResult);
                if (request == TF_E_SYNCHRONOUS || editResult == TF_E_SYNCHRONOUS)
                    request = context->RequestEditSession(client, edit, TF_ES_ASYNC | TF_ES_READWRITE, &editResult);
                edit->Release();
                if (FAILED(request) || FAILED(editResult)) *eaten = FALSE;
                mpn_clear_commit(session);
            }
            updatePopup();
        } catch (...) {
            mpn_clear(session);
            resetCandidates();
            sendGlass({});
            if (popup) ShowWindow(popup, SW_HIDE);
            *eaten = FALSE;
        }
        return S_OK;
    }
private:
    static bool navigation(WPARAM key) {
        return key == VK_LEFT || key == VK_RIGHT || key == VK_UP || key == VK_DOWN || key == VK_TAB;
    }

    static wchar_t punctuation(WPARAM key) {
        switch (key) {
        case VK_OEM_COMMA: return L'，';
        case VK_OEM_PERIOD: return L'。';
        case VK_OEM_1: return L'；';
        default: return 0;
        }
    }

    static int translate(WPARAM key) {
        switch (key) {
        case VK_BACK: return 0xff08;
        case VK_ESCAPE: return 0xff1b;
        case VK_RETURN: return 0xff0d;
        case VK_PRIOR: return 0xff55;
        case VK_NEXT: return 0xff56;
        case VK_SPACE: return ' ';
        case VK_OEM_7: return '\'';
        default: return key >= 'A' && key <= 'Z' ? static_cast<int>(key - 'A' + 'a') : 0;
        }
    }

    static bool disabled(ITfContext *context) {
        ITfCompartmentMgr *manager = nullptr;
        if (FAILED(context->QueryInterface(IID_ITfCompartmentMgr, reinterpret_cast<void **>(&manager)))) return false;
        ITfCompartment *compartment = nullptr;
        HRESULT result = manager->GetCompartment(GUID_COMPARTMENT_KEYBOARD_DISABLED, &compartment);
        manager->Release();
        if (FAILED(result)) return false;
        VARIANT value{};
        result = compartment->GetValue(&value);
        compartment->Release();
        const bool disabled = SUCCEEDED(result) && value.vt == VT_I4 && value.lVal != 0;
        VariantClear(&value);
        return disabled;
    }

    bool accepts(ITfContext *context, WPARAM key) const {
        if (!session || !context || GetKeyState(VK_CONTROL) < 0 || GetKeyState(VK_MENU) < 0
            || (key != VK_TAB && GetKeyState(VK_SHIFT) < 0) || (GetKeyState(VK_CAPITAL) & 1)) return false;
        TF_STATUS status{};
        if (FAILED(context->GetStatus(&status)) || (status.dwDynamicFlags & TF_SD_READONLY)) return false;
        if (disabled(context)) return false;
        HWND focus = GetFocus();
        wchar_t className[16]{};
        if (focus && GetClassNameW(focus, className, ARRAYSIZE(className))
            && lstrcmpiW(className, L"Edit") == 0 && (GetWindowLongPtrW(focus, GWL_STYLE) & ES_PASSWORD))
            return false;
        if (key >= 'A' && key <= 'Z') return true;
        if (context != activeContext || !*mpn_input(session)) return false;
        if (key >= '1' && key <= '7')
            return page * pageSize + key - '1' < candidateCount();
        if (navigation(key) || key == VK_F4)
            return page * pageSize < candidateCount();
        return punctuation(key) || translate(key) != 0;
    }

    void resetCandidates() {
        choices.clear();
        page = highlighted = 0;
        chineseLocked = expanded = false;
    }

    void refreshCandidates() {
        page = highlighted = 0;
        if (!*mpn_input(session)) {
            resetCandidates();
            return;
        }
        choices = windowsCandidates(session, japaneseWords, [&](const std::string &input) {
            return evidence.dictionary(input);
        }, chineseLocked);
    }

    size_t candidateCount() const { return choices.size(); }

    std::wstring candidateText(size_t index) const { return wide(choices[index].text.c_str()); }

    std::wstring candidateReading(size_t index) const {
        if (!choices[index].coreIndex) return {};
        const auto consumed = mpn_candidate_consumed(session, *choices[index].coreIndex);
        const std::string input = mpn_input(session);
        return consumed < input.size() ? wide(input.substr(0, consumed).c_str()) : std::wstring();
    }

    bool chooseCandidate(size_t index, std::wstring &text) {
        if (index >= candidateCount()) return false;
        if (choices[index].coreIndex) {
            if (!mpn_choose(session, *choices[index].coreIndex)) return false;
            chineseLocked = *mpn_input(session) != '\0';
        } else {
            text += candidateText(index);
            mpn_clear(session);
        }
        return true;
    }

    HWND rendererWindow() const {
        if (!renderer) return nullptr;
        HWND window = FindWindowW(nullptr, rendererName.c_str());
        DWORD pid = 0;
        if (window) GetWindowThreadProcessId(window, &pid);
        return pid && pid == GetProcessId(renderer) ? window : nullptr;
    }

    HWND desktopRendererWindow() const {
        HWND window = FindWindowW(nullptr, L"MeowpuutCandidates-desktop");
        DWORD pid = 0;
        if (window) GetWindowThreadProcessId(window, &pid);
        if (!pid) return nullptr;
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
        if (!process) return nullptr;
        wchar_t image[32768];
        DWORD length = ARRAYSIZE(image);
        const BOOL found = QueryFullProcessImageNameW(process, 0, image, &length);
        CloseHandle(process);
        const auto expected = std::filesystem::path(modulePath()).parent_path() / L"MeowpuutGlassPreview.exe";
        return found && lstrcmpiW(image, expected.c_str()) == 0 ? window : nullptr;
    }

    bool sendGlass(const std::wstring &text) {
        if (text.size() >= 4096) return false;
        HWND target = desktopRendererWindow();
        if (!target && !text.empty() && (!renderer || WaitForSingleObject(renderer, 0) == WAIT_OBJECT_0)) {
            if (renderer) CloseHandle(renderer);
            renderer = nullptr;
            const auto id = std::to_wstring(GetCurrentProcessId()) + L"-"
                + std::to_wstring(reinterpret_cast<uintptr_t>(this));
            rendererName = L"MeowpuutCandidates-" + id;
            auto path = std::filesystem::path(modulePath()).parent_path() / L"MeowpuutGlassPreview.exe";
            auto command = L"\"" + path.wstring() + L"\" --ime " + id;
            STARTUPINFOW startup{};
            startup.cb = sizeof(startup);
            PROCESS_INFORMATION process{};
            if (CreateProcessW(path.c_str(), command.data(), nullptr, nullptr, FALSE, 0, nullptr,
                               nullptr, &startup, &process)) {
                renderer = process.hProcess;
                CloseHandle(process.hThread);
            }
        }

        if (!target) target = rendererWindow();
        if (!target) return false;
        if (!text.empty()) lastForeground = GetForegroundWindow();
        COPYDATASTRUCT packet{0x4D50, static_cast<DWORD>((text.size() + 1) * sizeof(wchar_t)),
                              const_cast<wchar_t *>(text.c_str())};
        DWORD_PTR accepted = 0;
        return SendMessageTimeoutW(target, WM_COPYDATA, reinterpret_cast<WPARAM>(lastForeground),
                                   reinterpret_cast<LPARAM>(&packet), SMTO_ABORTIFHUNG | SMTO_BLOCK, 35,
                                   &accepted) && accepted;
    }

    static LRESULT CALLBACK candidateWindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
        auto *service = reinterpret_cast<Service *>(GetWindowLongPtrW(window, GWLP_USERDATA));
        if (message == WM_MOUSEACTIVATE) return MA_NOACTIVATE;
        if (message == WM_LBUTTONUP && service && service->activeContext
            && GetForegroundWindow() == service->popupForeground) {
            const int y = static_cast<short>(HIWORD(lParam)) - 1;
            const int candidate = y / service->popupRowHeight - 1;
            if (y >= service->popupRowHeight && candidate >= 0 && candidate < static_cast<int>(pageSize)) {
                BOOL eaten = FALSE;
                if (static_cast<size_t>(candidate) < service->popupIndices.size())
                    service->OnKeyDown(service->activeContext, '1' + service->popupIndices[candidate], 0, &eaten);
            }
            return 0;
        }
        return service && service->originalPopupProc
            ? CallWindowProcW(service->originalPopupProc, window, message, wParam, lParam)
            : DefWindowProcW(window, message, wParam, lParam);
    }

    POINT candidateAnchor() const {
        if (activeContext) {
            auto *edit = new (std::nothrow) EditSession(activeContext, {});
            if (edit) {
                HRESULT result = E_FAIL;
                const HRESULT request = activeContext->RequestEditSession(client, edit, TF_ES_SYNC | TF_ES_READ, &result);
                const RECT caret = edit->caret;
                edit->Release();
                if (request == S_OK && result == S_OK) return {caret.left, caret.bottom};
            }
        }

        GUITHREADINFO gui{};
        gui.cbSize = sizeof(gui);
        POINT anchor{};
        if (GetGUIThreadInfo(0, &gui) && gui.hwndCaret) {
            anchor = {gui.rcCaret.left, gui.rcCaret.bottom};
            ClientToScreen(gui.hwndCaret, &anchor);
        } else {
            GetCursorPos(&anchor);
        }
        return anchor;
    }

    void updatePopup() {
        if (!*mpn_input(session)) {
            sendGlass({});
            if (popup) ShowWindow(popup, SW_HIDE);
            return;
        }
        auto display = wide(mpn_preedit(session));
        const auto start = page * pageSize;
        popupIndices.clear();
        std::optional<size_t> japanese;
        for (size_t i = 0; i < pageSize && start + i < candidateCount(); ++i) {
            if (!expanded && choices[start + i].japanese) {
                if (!japanese || i == highlighted) japanese = i;
            } else {
                popupIndices.push_back(i);
            }
        }
        if (japanese) {
            popupIndices.push_back(*japanese);
            std::sort(popupIndices.begin(), popupIndices.end());
        }
        for (const auto i : popupIndices) {
            display += std::wstring(i == highlighted ? L"\r\n> " : L"\r\n  ") + std::to_wstring(i + 1)
                + L"  " + candidateText(start + i);
            if (choices[start + i].japanese) display += L"  [日]";
        }

        POINT anchor = candidateAnchor();
        HMONITOR monitor = MonitorFromPoint(anchor, MONITOR_DEFAULTTONEAREST);
        MONITORINFO monitorInfo{};
        monitorInfo.cbSize = sizeof(monitorInfo);
        if (GetMonitorInfoW(monitor, &monitorInfo)) {
            const auto work = monitorInfo.rcWork;
            anchor.x = (std::max)(work.left, (std::min)(anchor.x, work.right - 850));
            if (anchor.y + 155 > work.bottom)
                anchor.y = (std::max)(work.top, anchor.y - 155);
        }
        auto payload = std::to_wstring(anchor.x) + L"\n" + std::to_wstring(anchor.y + 7) + L"\n"
            + wide(mpn_preedit(session)) + L"\t" + std::to_wstring(highlighted);
        const size_t pageCount = (candidateCount() + pageSize - 1) / pageSize;
        payload += L"\t";
        if (pageCount > 1)
            payload += std::to_wstring(page + 1) + L" / " + std::to_wstring(pageCount);
        payload += expanded ? L"\t1" : L"\t0";
        for (size_t i = 0; i < pageSize && start + i < candidateCount(); ++i)
            payload += L"\n" + candidateText(start + i) + L"\t" + candidateReading(start + i)
                + (choices[start + i].japanese ? L"\tja" : L"");
        if (sendGlass(payload)) {
            if (popup) ShowWindow(popup, SW_HIDE);
            return;
        }
        if (!popup) {
            popup = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST,
                                    L"STATIC", nullptr, WS_POPUP | WS_BORDER | SS_LEFTNOWORDWRAP | SS_NOPREFIX | SS_NOTIFY,
                                    0, 0, 0, 0, nullptr, nullptr, module, nullptr);
            if (!popup) return;
            SendMessageW(popup, WM_SETFONT, reinterpret_cast<WPARAM>(GetStockObject(DEFAULT_GUI_FONT)), 0);
            SetWindowLongPtrW(popup, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(this));
            originalPopupProc = reinterpret_cast<WNDPROC>(SetWindowLongPtrW(
                popup, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&candidateWindowProc)));
        }

        SetWindowTextW(popup, display.c_str());
        RECT size{};
        HDC dc = GetDC(popup);
        HGDIOBJ previous = SelectObject(dc, GetStockObject(DEFAULT_GUI_FONT));
        DrawTextW(dc, display.c_str(), -1, &size, DT_CALCRECT | DT_NOPREFIX);
        TEXTMETRICW metrics{};
        if (GetTextMetricsW(dc, &metrics) && metrics.tmHeight > 0) popupRowHeight = metrics.tmHeight;
        popupForeground = GetForegroundWindow();
        SelectObject(dc, previous);
        ReleaseDC(popup, dc);
        SetWindowPos(popup, HWND_TOPMOST, anchor.x, anchor.y + 7, (std::max)(size.right + 16, 180L), size.bottom + 8,
                     SWP_NOACTIVATE | SWP_SHOWWINDOW);
    }

    ITfKeystrokeMgr *keys = nullptr;
    ITfContext *activeContext = nullptr;
    TfClientId client = 0;
    MPNSession *session = nullptr;
    std::vector<JapaneseWord> japaneseWords;
    WordEvidence evidence;
    std::vector<WindowsCandidate> choices;
    bool chineseLocked = false, expanded = false;
    size_t page = 0, highlighted = 0;
    HWND popup = nullptr;
    HWND popupForeground = nullptr;
    WNDPROC originalPopupProc = nullptr;
    std::vector<size_t> popupIndices;
    int popupRowHeight = 16;
    HWND lastForeground = nullptr;
    HANDLE renderer = nullptr;
    std::wstring rendererName;
};

class Factory final : public IClassFactory, public RefCount {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **out) override {
        *out = nullptr;
        if (id != IID_IUnknown && id != IID_IClassFactory) return E_NOINTERFACE;
        *out = static_cast<IClassFactory *>(this);
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return retain(); }
    ULONG STDMETHODCALLTYPE Release() override { return release(); }
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown *outer, REFIID id, void **out) override {
        *out = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        auto *service = new (std::nothrow) Service;
        if (!service) return E_OUTOFMEMORY;
        const HRESULT result = service->QueryInterface(id, out);
        service->Release();
        return result;
    }
    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override {
        if (lock) InterlockedIncrement(&objects);
        else InterlockedDecrement(&objects);
        return S_OK;
    }
};

HRESULT registerProfile(bool install) {
    ITfInputProcessorProfiles *profiles = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_ITfInputProcessorProfiles, reinterpret_cast<void **>(&profiles));
    if (FAILED(hr)) return hr;
    if (install) {
        hr = profiles->Register(serviceId);
        if (SUCCEEDED(hr)) hr = profiles->AddLanguageProfile(serviceId, language, profileId, name,
                                                            ARRAYSIZE(name) - 1, nullptr, 0, 0);
        if (SUCCEEDED(hr)) hr = profiles->EnableLanguageProfileByDefault(serviceId, language, profileId, TRUE);
        if (SUCCEEDED(hr)) hr = profiles->EnableLanguageProfile(serviceId, language, profileId, TRUE);
    } else {
        profiles->RemoveLanguageProfile(serviceId, language, profileId);
        hr = profiles->Unregister(serviceId);
    }
    profiles->Release();
    if (install && FAILED(hr)) return hr;
    ITfCategoryMgr *categories = nullptr;
    hr = CoCreateInstance(CLSID_TF_CategoryMgr, nullptr, CLSCTX_INPROC_SERVER,
                          IID_ITfCategoryMgr, reinterpret_cast<void **>(&categories));
    if (FAILED(hr)) return hr;
    hr = install ? categories->RegisterCategory(serviceId, GUID_TFCAT_TIP_KEYBOARD, serviceId)
                 : categories->UnregisterCategory(serviceId, GUID_TFCAT_TIP_KEYBOARD, serviceId);
    categories->Release();
    return hr;
}
}

BOOL WINAPI DllMain(HMODULE handle, DWORD reason, void *) {
    if (reason == DLL_PROCESS_ATTACH) module = handle;
    return TRUE;
}

extern "C" __declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID id, REFIID iid, void **out) {
    *out = nullptr;
    if (id != serviceId) return CLASS_E_CLASSNOTAVAILABLE;
    auto *factory = new (std::nothrow) Factory;
    if (!factory) return E_OUTOFMEMORY;
    HRESULT result = factory->QueryInterface(iid, out);
    factory->Release();
    return result;
}

extern "C" __declspec(dllexport) HRESULT WINAPI DllCanUnloadNow() {
    return InterlockedCompareExchange(&objects, 0, 0) == 0 ? S_OK : S_FALSE;
}

extern "C" __declspec(dllexport) HRESULT WINAPI DllRegisterServer() {
    const auto path = modulePath();
    if (path.empty()) return E_FAIL;
    HKEY server = nullptr;
    const std::wstring key = std::wstring(classKey) + L"\\InprocServer32";
    LSTATUS status = RegCreateKeyExW(HKEY_LOCAL_MACHINE, key.c_str(), 0, nullptr, 0, KEY_SET_VALUE,
                                   nullptr, &server, nullptr);
    if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
    status = RegSetValueExW(server, nullptr, 0, REG_SZ, reinterpret_cast<const BYTE *>(path.c_str()),
                            static_cast<DWORD>((path.size() + 1) * sizeof(wchar_t)));
    if (status == ERROR_SUCCESS) {
        constexpr wchar_t model[] = L"Apartment";
        status = RegSetValueExW(server, L"ThreadingModel", 0, REG_SZ,
                               reinterpret_cast<const BYTE *>(model), sizeof(model));
    }
    RegCloseKey(server);
    HRESULT result = status == ERROR_SUCCESS ? registerProfile(true) : HRESULT_FROM_WIN32(status);
    if (FAILED(result)) {
        registerProfile(false);
        RegDeleteTreeW(HKEY_LOCAL_MACHINE, classKey);
    }
    return result;
}

extern "C" __declspec(dllexport) HRESULT WINAPI DllUnregisterServer() {
    HRESULT result = registerProfile(false);
    const LSTATUS status = RegDeleteTreeW(HKEY_LOCAL_MACHINE, classKey);
    return status == ERROR_SUCCESS || status == ERROR_FILE_NOT_FOUND ? result : HRESULT_FROM_WIN32(status);
}
