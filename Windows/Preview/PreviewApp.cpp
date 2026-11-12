// Meowpuut candidate window preview for Windows.
//
// A plain edit box that types through meowpuut::Session and Keyboard.h and
// shows the candidate window under the composition. It is not a text service:
// the composition is kept inline in this one edit control.
//
//   MeowpuutCandidatePreview.exe [--lexicon <dir>]
//
// <dir> holds native-pinyin.tsv, native-zhuyin.tsv and native-japanese.tsv
// (scripts/export-windows-lexicon.py). Without the option the program looks
// for a "lexicon" folder next to the executable.
//
// Fixed states for screenshots (scripts/windows-vm.sh). With --at the window
// stays at one place, keeps the candidates up without focus and quits after
// --seconds (default 60):
//
//   --at X,Y                 caret position in screen pixels
//   --shape capsule|wave|cloud   --tone light|dark|system
//   --type <keys>            type ASCII keys through the session, or
//   --candidates a,b,ja:c    a fixed page; "ja:" marks a Japanese candidate
//   --preedit <text>  --highlight <n>  --page <n>  --more  --expanded
#ifndef UNICODE
#define UNICODE
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <imm.h>
#include <shellapi.h>

#include "../Keyboard.h"
#include "../Session.h"
#include "../UI/CandidateWindow.h"
#include "../UI/SnapshotAdapter.h"

#include <algorithm>
#include <filesystem>
#include <memory>
#include <cctype>
#include <string>
#include <vector>

using namespace meowpuut;

namespace {

enum Command : UINT {
    kPinyin = 100, kZhuyin, kTraditional, kSimplified, kCapsule, kWave, kCloud,
    kToneSystem, kToneLight, kToneDark, kFestivals
};

std::wstring widen(const std::string &s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<std::size_t>(std::max(0, n)), L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::wstring executableDirectory() {
    wchar_t path[MAX_PATH] = {};
    const DWORD n = GetModuleFileNameW(nullptr, path, MAX_PATH);
    std::wstring dir(path, n);
    const auto slash = dir.find_last_of(L"\\/");
    return slash == std::wstring::npos ? L"." : dir.substr(0, slash);
}

struct App {
    HINSTANCE instance = nullptr;
    HWND window = nullptr, edit = nullptr;
    WNDPROC editProc = nullptr;
    HFONT font = nullptr;
    std::unique_ptr<Session> pinyin, zhuyin;
    InputMode mode = InputMode::Pinyin;
    bool english = false;
    std::unique_ptr<ui::CandidateWindow> candidates;
    ui::CandidateWindowState state;
    Snapshot last;
    // The composition lives inline in the edit control: [start, start + length).
    DWORD compositionStart = 0, compositionLength = 0;
    bool eatChar = false;
    bool shiftDown = false, shiftUsed = false;
    bool scripted = false;  // --at: fixed caret, candidates stay up without focus
    RECT fixedCaret{};

    Session &session() { return mode == InputMode::Pinyin ? *pinyin : *zhuyin; }

    void updateTitle() {
        std::wstring title = L"\x55B5\x64B2\x5019\x9078\x7A97\x9810\x89BD \x2014 "; // 喵撲候選窗預覽 —
        if (english) title += L"English";
        else title += std::wstring(mode == InputMode::Pinyin ? L"\x62FC\x97F3" : L"\x6CE8\x97F3") + L" \x00B7 " +
                      (state.simplified ? L"\x7B80" : L"\x7E41");
        const auto outfit = candidates ? candidates->activeOutfitTitle() : std::wstring();
        if (!outfit.empty()) title += L"  (" + outfit + L")";
        SetWindowTextW(window, title.c_str());
    }

    RECT compositionRect() {
        if (scripted) return fixedCaret;
        RECT r{};
        const LRESULT pos = SendMessageW(edit, EM_POSFROMCHAR, compositionStart, 0);
        POINT p{};
        if (pos != -1) {
            p.x = static_cast<short>(LOWORD(pos));
            p.y = static_cast<short>(HIWORD(pos));
        } else {
            GetCaretPos(&p);
        }
        TEXTMETRICW tm{};
        tm.tmHeight = 20;
        if (HDC dc = GetDC(edit)) {
            HGDIOBJ old = SelectObject(dc, font);
            GetTextMetricsW(dc, &tm);
            SelectObject(dc, old);
            ReleaseDC(edit, dc);
        }
        POINT top = p, bottom{p.x + 1, p.y + tm.tmHeight};
        ClientToScreen(edit, &top);
        ClientToScreen(edit, &bottom);
        r = {top.x, top.y, bottom.x, bottom.y};
        return r;
    }

    // Replaces the inline composition with committed text plus the new preedit.
    void writeInline(const std::string &committed, const std::string &preedit) {
        if (compositionLength == 0) {
            DWORD start = 0, end = 0;
            SendMessageW(edit, EM_GETSEL, reinterpret_cast<WPARAM>(&start), reinterpret_cast<LPARAM>(&end));
            compositionStart = start;
            SendMessageW(edit, EM_SETSEL, start, end);
        } else {
            SendMessageW(edit, EM_SETSEL, compositionStart, compositionStart + compositionLength);
        }
        const auto commitW = widen(committed), preeditW = widen(preedit);
        const std::wstring text = commitW + preeditW;
        SendMessageW(edit, EM_REPLACESEL, TRUE, reinterpret_cast<LPARAM>(text.c_str()));
        compositionStart += static_cast<DWORD>(commitW.size());
        compositionLength = static_cast<DWORD>(preeditW.size());
        SendMessageW(edit, EM_SETSEL, compositionStart + compositionLength, compositionStart + compositionLength);
    }

    void refresh() {
        auto snapshot = session().takeSnapshot();
        if (snapshot.input != last.input || snapshot.page != last.page) state.highlighted = 0;
        writeInline(snapshot.committed, snapshot.preedit);
        last = snapshot;
        last.committed.clear();
        if (snapshot.input.empty()) {
            compositionLength = 0;
            state.expanded = false;
        }
        showCandidates();
    }

    void showCandidates() {
        if (!candidates) return;
        if (last.input.empty() || last.candidates.empty()) {
            candidates->hide();
            return;
        }
        state.zhuyin = mode == InputMode::Zhuyin;
        candidates->update(ui::makeViewModel(last, state, candidates->options().shape));
        candidates->showNear(compositionRect());
    }

    void finishComposition() {
        if (last.input.empty()) return;
        session().chooseOnPage(state.highlighted);
        refresh();
        if (!last.input.empty()) {
            // A partial choice leaves the rest of the reading; keep it as typed.
            const std::string rest = last.preedit;
            session().clear();
            writeInline(rest, "");
            last = Snapshot{};
        }
        compositionLength = 0;
        if (candidates) candidates->hide();
    }

    KeyEvent event(WPARAM vk, LPARAM lp) {
        BYTE keys[256] = {};
        GetKeyboardState(keys);
        wchar_t buffer[4] = {};
        const UINT scan = (static_cast<UINT>(lp) >> 16) & 0xff;
        // Flag 4: do not change the keyboard's dead-key state.
        const int n = ToUnicodeEx(static_cast<UINT>(vk), scan, keys, buffer, 4, 4, GetKeyboardLayout(0));
        KeyEvent ev{static_cast<std::uint32_t>(vk), 0};
        if (n == 1 && buffer[0] >= 0x20 && buffer[0] < 0x7f) ev.printable = static_cast<char>(buffer[0]);
        ev.shift = GetKeyState(VK_SHIFT) < 0;
        ev.control = GetKeyState(VK_CONTROL) < 0;
        ev.alt = GetKeyState(VK_MENU) < 0;
        ev.win = GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0;
        return ev;
    }

    bool keyDown(WPARAM vk, LPARAM lp) {
        if (vk == VK_SHIFT) {
            if (!shiftDown) { shiftDown = true; shiftUsed = false; }
            return false;
        }
        if (shiftDown) shiftUsed = true;
        if (english) return false;
        const bool composing = !last.input.empty();
        const auto pageSize = last.candidates.size();
        if (composing && pageSize > 0) {
            switch (vk) {
            case VK_LEFT:
            case VK_UP:
                state.highlighted = (state.highlighted + pageSize - 1) % pageSize;
                showCandidates();
                return true;
            case VK_RIGHT:
            case VK_DOWN:
            case VK_TAB:
                state.highlighted = (state.highlighted + 1) % pageSize;
                showCandidates();
                return true;
            case VK_RETURN:
                if (state.highlighted > 0) {
                    session().chooseOnPage(state.highlighted);
                    refresh();
                    return true;
                }
                break;
            default: break;
            }
        }
        const auto ev = event(vk, lp);
        if (canHandleKey(session(), mode, ev)) {
            handleKey(session(), mode, ev);
            refresh();
            return true;
        }
        if (!composing) return false;
        // The engine does not take this key. A character ends the composition
        // and is typed after it.
        if (ev.printable != 0 && !ev.control && !ev.alt) {
            finishComposition();
            return false;
        }
        // Everything else (Delete, Home, Ctrl+Z, Ctrl+V, Shift+Insert, …) is
        // dropped: the edit box must not change the text around the inline
        // composition. Modifier keys pass.
        return !(vk == VK_CONTROL || vk == VK_MENU || vk == VK_CAPITAL || vk == VK_LWIN || vk == VK_RWIN);
    }

    void keyUp(WPARAM vk) {
        if (vk != VK_SHIFT) return;
        // A Shift press with no other key switches between Chinese and English.
        if (shiftDown && !shiftUsed) {
            finishComposition();
            english = !english;
            updateTitle();
        }
        shiftDown = false;
    }

    static LRESULT CALLBACK editHook(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
        auto *app = reinterpret_cast<App *>(GetWindowLongPtrW(GetParent(hwnd), GWLP_USERDATA));
        switch (msg) {
        case WM_KEYDOWN:
            if (app->keyDown(wp, lp)) { app->eatChar = true; return 0; }
            app->eatChar = false;
            break;
        case WM_CHAR:
            if (app->eatChar) { app->eatChar = false; return 0; }
            break;
        case WM_KEYUP:
            app->keyUp(wp);
            break;
        case WM_KILLFOCUS:
            if (app->candidates && !app->scripted) app->candidates->hide();
            break;
        case WM_SETFOCUS:
            app->showCandidates();
            break;
        case WM_LBUTTONDOWN:
        case WM_RBUTTONDOWN:
        case WM_CONTEXTMENU:   // the menu's Undo, Cut and Paste edit the text too
            app->finishComposition();
            break;
        default: break;
        }
        return CallWindowProcW(app->editProc, hwnd, msg, wp, lp);
    }

    void setMode(InputMode next) {
        finishComposition();
        mode = next;
        english = false;
        updateTitle();
    }

    // Switches Traditional/Simplified. An open composition stays open and its
    // candidates are converted in place.
    void setSimplified(bool simplified) {
        state.simplified = simplified;
        pinyin->setSimplified(simplified);
        zhuyin->setSimplified(simplified);
        auto options = candidates->options();
        options.simplified = simplified;
        candidates->setOptions(options);
        syncMenu();
        updateTitle();
        if (!last.input.empty()) refresh();
    }

    void command(UINT id) {
        auto options = candidates->options();
        switch (id) {
        case kPinyin: setMode(InputMode::Pinyin); break;
        case kZhuyin: setMode(InputMode::Zhuyin); break;
        case kTraditional:
        case kSimplified:
            finishComposition();
            setSimplified(id == kSimplified);
            break;
        case kCapsule: options.shape = ui::GlassShape::Capsule; candidates->setOptions(options); break;
        case kWave: options.shape = ui::GlassShape::Wave; candidates->setOptions(options); break;
        case kCloud: options.shape = ui::GlassShape::Cloud; candidates->setOptions(options); break;
        case kToneSystem: options.tone = ui::ToneSetting::System; candidates->setOptions(options); break;
        case kToneLight: options.tone = ui::ToneSetting::Light; candidates->setOptions(options); break;
        case kToneDark: options.tone = ui::ToneSetting::Dark; candidates->setOptions(options); break;
        case kFestivals: options.festivalOutfits = !options.festivalOutfits; candidates->setOptions(options); break;
        default: return;
        }
        syncMenu();
        updateTitle();
        showCandidates();
        SetFocus(edit);
    }

    void syncMenu() {
        HMENU menu = GetMenu(window);
        const auto &o = candidates->options();
        CheckMenuRadioItem(menu, kPinyin, kZhuyin, mode == InputMode::Pinyin ? kPinyin : kZhuyin, MF_BYCOMMAND);
        CheckMenuRadioItem(menu, kTraditional, kSimplified, state.simplified ? kSimplified : kTraditional, MF_BYCOMMAND);
        CheckMenuRadioItem(menu, kCapsule, kCloud,
                           o.shape == ui::GlassShape::Wave ? kWave : o.shape == ui::GlassShape::Cloud ? kCloud : kCapsule,
                           MF_BYCOMMAND);
        CheckMenuRadioItem(menu, kToneSystem, kToneDark,
                           o.tone == ui::ToneSetting::Light ? kToneLight : o.tone == ui::ToneSetting::Dark ? kToneDark : kToneSystem,
                           MF_BYCOMMAND);
        CheckMenuItem(menu, kFestivals, MF_BYCOMMAND | (o.festivalOutfits ? MF_CHECKED : MF_UNCHECKED));
    }

    HMENU buildMenu() {
        HMENU bar = CreateMenu();
        HMENU input = CreatePopupMenu(), script = CreatePopupMenu(), look = CreatePopupMenu();
        AppendMenuW(input, MF_STRING, kPinyin, L"\x62FC\x97F3");                         // 拼音
        AppendMenuW(input, MF_STRING, kZhuyin, L"\x6CE8\x97F3");                         // 注音
        AppendMenuW(script, MF_STRING, kTraditional, L"\x7E41\x9AD4");                   // 繁體
        AppendMenuW(script, MF_STRING, kSimplified, L"\x7B80\x4F53");                    // 简体
        AppendMenuW(look, MF_STRING, kCapsule, L"\x81A0\x56CA");                         // 膠囊
        AppendMenuW(look, MF_STRING, kWave, L"\x6CE2\x6D6A");                            // 波浪
        AppendMenuW(look, MF_STRING, kCloud, L"\x96F2\x6735");                           // 雲朵
        AppendMenuW(look, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(look, MF_STRING, kToneSystem, L"\x8DDF\x96A8\x7CFB\x7D71");          // 跟隨系統
        AppendMenuW(look, MF_STRING, kToneLight, L"\x6DFA\x8272");                       // 淺色
        AppendMenuW(look, MF_STRING, kToneDark, L"\x6DF1\x8272");                        // 深色
        AppendMenuW(look, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(look, MF_STRING, kFestivals, L"\x7BC0\x65E5\x88DD\x626E");           // 節日裝扮
        AppendMenuW(bar, MF_POPUP, reinterpret_cast<UINT_PTR>(input), L"\x8F38\x5165\x65B9\x5F0F"); // 輸入方式
        AppendMenuW(bar, MF_POPUP, reinterpret_cast<UINT_PTR>(script), L"\x5B57\x5F62");            // 字形
        AppendMenuW(bar, MF_POPUP, reinterpret_cast<UINT_PTR>(look), L"\x5019\x9078\x7A97");        // 候選窗
        return bar;
    }

    void layoutChildren() {
        RECT r{};
        GetClientRect(window, &r);
        MoveWindow(edit, 0, 0, r.right, r.bottom, TRUE);
    }

    void applyFont() {
        UINT dpi = 96;
        using DpiFn = UINT(WINAPI *)(HWND);
        static const auto getDpi = reinterpret_cast<DpiFn>(
            reinterpret_cast<void (*)()>(GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow")));
        if (getDpi) dpi = getDpi(window);
        HFONT previous = font;
        font = CreateFontW(-MulDiv(15, static_cast<int>(dpi), 72), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                           DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH,
                           L"Microsoft JhengHei UI");
        SendMessageW(edit, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
        if (previous) DeleteObject(previous); // only after the edit control has let go of it
    }

    static LRESULT CALLBACK windowProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
        auto *app = reinterpret_cast<App *>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        switch (msg) {
        case WM_NCCREATE: {
            auto *created = static_cast<App *>(reinterpret_cast<CREATESTRUCTW *>(lp)->lpCreateParams);
            created->window = hwnd;
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(created));
            break;
        }
        case WM_SIZE:
            if (app && app->edit) {
                app->layoutChildren();
                app->showCandidates();
            }
            return 0;
        case WM_SETFOCUS:
            if (app && app->edit) SetFocus(app->edit);
            return 0;
        case WM_COMMAND:
            if (app && HIWORD(wp) == 0) app->command(LOWORD(wp));
            return 0;
        case WM_MOVE:
            if (app) app->showCandidates();
            return 0;
        case WM_DPICHANGED:
            if (app) {
                const auto *suggested = reinterpret_cast<const RECT *>(lp);
                SetWindowPos(hwnd, nullptr, suggested->left, suggested->top, suggested->right - suggested->left,
                             suggested->bottom - suggested->top, SWP_NOZORDER | SWP_NOACTIVATE);
                app->applyFont();
                app->showCandidates();
            }
            return 0;
        case WM_TIMER:
            if (wp == 1) DestroyWindow(hwnd);  // --seconds ran out
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
        default: break;
        }
        return DefWindowProcW(hwnd, msg, wp, lp);
    }
};

std::filesystem::path lexiconDirectory() {
    int argc = 0;
    LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    std::filesystem::path dir = std::filesystem::path(executableDirectory()) / L"lexicon";
    for (int i = 1; argv && i + 1 < argc; ++i)
        if (std::wstring(argv[i]) == L"--lexicon") dir = argv[i + 1];
    if (argv) LocalFree(argv);
    return dir;
}

std::vector<std::wstring> arguments() {
    int argc = 0;
    LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    std::vector<std::wstring> args(argv + 1, argv + argc);
    LocalFree(argv);
    return args;
}

std::string narrow(const std::wstring &w) {
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<std::size_t>(std::max(0, n)), '\0');
    if (n > 0) WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

// Applies the screenshot options (see the top of this file). Returns the
// number of seconds to stay up, or 0 without --at.
UINT applyScript(App &app) {
    const auto args = arguments();
    auto value = [&](const wchar_t *name) -> std::wstring {
        for (std::size_t i = 0; i + 1 < args.size(); ++i)
            if (args[i] == name) return args[i + 1];
        return {};
    };
    auto flag = [&](const wchar_t *name) { return std::find(args.begin(), args.end(), name) != args.end(); };
    const auto at = value(L"--at");
    if (at.empty()) return 0;
    app.scripted = true;
    const int x = _wtoi(at.c_str()), y = _wtoi(at.substr(at.find(L',') + 1).c_str());
    const int line = MulDiv(22, static_cast<int>(GetDpiForWindow(app.window)), 96);
    app.fixedCaret = {x, y, x + 1, y + line};
    // The preview window sits behind the caret so the candidates lie on its text.
    SetWindowPos(app.window, nullptr, x - 3 * line, y - 4 * line, 40 * line, 18 * line, SWP_NOZORDER | SWP_NOACTIVATE);

    auto options = app.candidates->options();
    const auto shape = value(L"--shape"), tone = value(L"--tone");
    if (shape == L"wave") options.shape = ui::GlassShape::Wave;
    if (shape == L"cloud") options.shape = ui::GlassShape::Cloud;
    if (tone == L"light") options.tone = ui::ToneSetting::Light;
    if (tone == L"dark") options.tone = ui::ToneSetting::Dark;
    app.candidates->setOptions(options);
    app.state.expanded = flag(L"--expanded");

    if (const auto keys = value(L"--type"); !keys.empty()) {
        for (wchar_t c : keys) {
            const auto ch = static_cast<char>(c);
            handleKey(app.session(), app.mode, KeyEvent{static_cast<std::uint32_t>(std::toupper(ch)), ch});
        }
        app.refresh();
    } else {
        Snapshot page;
        page.input = "x";
        page.preedit = narrow(value(L"--preedit"));
        page.page = static_cast<std::size_t>(_wtoi(value(L"--page").c_str()));
        page.lastPage = !flag(L"--more");
        std::wstring list = value(L"--candidates");
        for (std::size_t start = 0; start <= list.size();) {
            const auto end = std::min(list.find(L',', start), list.size());
            std::wstring item = list.substr(start, end - start);
            const bool japanese = item.rfind(L"ja:", 0) == 0;
            page.candidates.push_back(narrow(japanese ? item.substr(3) : item));
            page.japaneseCandidates.push_back(japanese);
            start = end + 1;
        }
        app.writeInline("", page.preedit);
        app.last = page;
    }
    app.state.highlighted = static_cast<std::size_t>(_wtoi(value(L"--highlight").c_str()));
    app.syncMenu();
    app.showCandidates();
    const auto seconds = value(L"--seconds");
    return seconds.empty() ? 60 : static_cast<UINT>(_wtoi(seconds.c_str()));
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    const bool comReady = SUCCEEDED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED));
    App app;
    app.instance = instance;
    const auto dir = lexiconDirectory();
    try {
        app.pinyin = std::make_unique<Session>(dir / L"native-pinyin.tsv");
        app.zhuyin = std::make_unique<Session>(dir / L"native-zhuyin.tsv");
        app.pinyin->registerJapaneseWords(dir / L"native-japanese.tsv");
    } catch (const std::exception &) {
        const std::wstring message =
            L"\x627E\x4E0D\x5230\x8A5E\x5EAB\xFF1A" + dir.wstring() +            // 找不到詞庫：
            L"\n\n\x8ACB\x5148\x57F7\x884C scripts/export-windows-lexicon.py\xFF0C"  // 請先執行 …，
            L"\x628A\x8F38\x51FA\x76EE\x9304\x653E\x5728\x7A0B\x5F0F\x65C1\x7684 lexicon \x8CC7\x6599\x593E\xFF0C" // 把輸出目錄放在程式旁的 lexicon 資料夾，
            L"\x6216\x7528 --lexicon \x6307\x5B9A\x3002";                              // 或用 --lexicon 指定。
        MessageBoxW(nullptr, message.c_str(), L"Meowpuut", MB_ICONWARNING);
        if (comReady) CoUninitialize();
        return 1;
    }

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof wc;
    wc.lpfnWndProc = &App::windowProc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    wc.lpszClassName = L"MeowpuutCandidatePreview";
    HMENU menu = app.buildMenu();
    if (!RegisterClassExW(&wc) ||
        !CreateWindowExW(0, wc.lpszClassName, L"Meowpuut", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 900, 560,
                         nullptr, menu, instance, &app)) {
        DestroyMenu(menu); // a window that was never created does not own its menu
        if (comReady) CoUninitialize();
        return 1;
    }
    app.edit = CreateWindowExW(0, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_VSCROLL | ES_MULTILINE | ES_AUTOVSCROLL |
                                   ES_WANTRETURN | ES_NOHIDESEL,
                               0, 0, 0, 0, app.window, nullptr, instance, nullptr);
    // Detach the system IME from the edit box. Otherwise an active Chinese or
    // Japanese keyboard takes the keys first and this program never sees them.
    ImmAssociateContext(app.edit, nullptr);
    app.editProc = reinterpret_cast<WNDPROC>(SetWindowLongPtrW(app.edit, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&App::editHook)));
    app.applyFont();
    ui::CandidateWindowCallbacks callbacks;
    callbacks.choose = [&app](std::size_t index) {
        app.session().chooseOnPage(index);
        app.refresh();
    };
    callbacks.page = [&app](int direction) {
        app.session().key(direction < 0 ? 0xff55 : 0xff56);
        app.refresh();
    };
    callbacks.toggleExpanded = [&app] {
        app.state.expanded = !app.state.expanded;
        app.showCandidates();
    };
    callbacks.toggleScript = [&app] { app.setSimplified(!app.state.simplified); };
    app.candidates = std::make_unique<ui::CandidateWindow>(instance, ui::CandidateWindowOptions{}, callbacks);
    if (!app.candidates->valid()) {
        // 無法建立候選窗（Direct2D、DirectWrite 或 WIC 不可用）。
        MessageBoxW(app.window,
                    L"\x7121\x6CD5\x5EFA\x7ACB\x5019\x9078\x7A97\xFF08" L"Direct2D\x3001" L"DirectWrite \x6216 WIC "
                    L"\x4E0D\x53EF\x7528\xFF09\x3002",
                    L"Meowpuut", MB_ICONWARNING);
    }
    app.syncMenu();
    app.updateTitle();
    app.layoutChildren();
    ShowWindow(app.window, show);
    SetFocus(app.edit);
    if (const UINT seconds = applyScript(app)) SetTimer(app.window, 1, seconds * 1000, nullptr);

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    app.candidates.reset(); // before COM goes away: it holds Direct2D, DirectWrite and WIC objects
    if (app.font) DeleteObject(app.font);
    if (comReady) CoUninitialize();
    return static_cast<int>(msg.wParam);
}
