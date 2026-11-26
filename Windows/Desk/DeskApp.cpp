// MeowpuutDesk.exe, 喵撲貓窩: the desk-pet process of the three (FINAL.md §7.1).
//
// A login item (HKCU Run), never started by Steam and never calling SteamAPI.
// It measures the desktop (window rectangles only), forwards your pointer and
// the input method's pulses to the behaviour core (mp_desk_step) and draws the
// two clay cats, one small layered window each:
//   WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_TOPMOST | WS_EX_NOACTIVATE, plus
//   WS_EX_TRANSPARENT (click-through) except while the pointer has rested
//   250 ms on a cat's opaque pixels (PETS.md §2.5, FINAL.md §4.6 item 5).
// Timers follow the core's schedule and nothing else (DeskLoop.h): none while
// you are away (only a coalescable 30 s GetLastInputInfo check), none while a
// full-screen program runs (the foreground hook brings the cats back), 2 Hz
// sampling while every cat sleeps, 10 Hz while one is awake.
//
// The touches the core reports (petted, purr, dropped) go into the pet ledger
// (PetLedger.h, PetJournal.bin), whose state the core adopts at start and after
// every flush. With 「記錄時間花在哪裡」 on (FINAL.md §11, off by default) it also
// senses the foreground app and idle for the time ledger (ForegroundWatcher.h,
// Activity.bin) and publishes the current category in the pulse section.
//
//   MeowpuutDesk.exe                     run (exits when DeskPets is 0 or one already runs)
//   MeowpuutDesk.exe --register-login    HKCU\...\Run\MeowpuutDesk = this executable
//   MeowpuutDesk.exe --unregister-login  removes it
//   MeowpuutDesk.exe --smoke             the headless smoke run (DeskSmoke.h) with the real art,
//                                        plus a pulse-channel round trip; exit code 0 when it passes
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <windowsx.h>
#include <dwmapi.h>
#include <objbase.h>
#include <shellapi.h>
#include <shellscalingapi.h>
#include <wincodec.h>

#include "../PetLedger.h"
#include "DeskArt.h"
#include "DeskLoop.h"
#include "DeskSettings.h"
#include "DeskSmoke.h"
#include "DeskWorld.h"
#include "ForegroundWatcher.h"
#include "PulseChannel.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <tuple>

using namespace meowpuut;
using namespace meowpuut::desk;

namespace {

// --- small helpers ---------------------------------------------------------------

template <class T>
class Com {
public:
    Com() = default;
    ~Com() { reset(); }
    Com(const Com &) = delete;
    Com &operator=(const Com &) = delete;
    T *get() const { return p_; }
    T *operator->() const { return p_; }
    T **put() {
        reset();
        return &p_;
    }
    void reset() {
        if (p_) p_->Release();
        p_ = nullptr;
    }
    explicit operator bool() const { return p_ != nullptr; }

private:
    T *p_ = nullptr;
};

std::uint64_t monoMs() {
    ULONGLONG t = 0; // 100 ns, without the time the machine slept
    QueryUnbiasedInterruptTime(&t);
    return t / 10'000;
}

MPPetsClock deskClock(std::uint64_t now) {
    const LocalClock local = systemLedgerClock().local;
    return MPPetsClock{now, local.day, local.minute};
}

std::uint32_t monitorDpi(HMONITOR monitor) {
    UINT x = 96, y = 96;
    if (!monitor || FAILED(GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &x, &y))) return 96;
    return std::clamp<UINT>(x, 96, 960);
}

std::uint32_t primaryDpi() { return monitorDpi(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY)); }

RectPx rectPx(const RECT &r) {
    RectPx x;
    x.left = r.left, x.top = r.top, x.right = r.right, x.bottom = r.bottom;
    return x;
}

std::wstring directoryOf(const std::wstring &path) {
    const auto slash = path.find_last_of(L"\\/");
    return slash == std::wstring::npos ? L"." : path.substr(0, slash);
}

bool fileExists(const std::wstring &path) {
    const DWORD a = GetFileAttributesW(path.c_str());
    return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
}

// The cat art: Assets\ beside the executable (a build folder), or one level up
// (installed: Program Files\Meowpuut\<hash>\x64\MeowpuutDesk.exe and <hash>\Assets).
std::wstring assetDirectory() {
    const std::wstring here = directoryOf(exePath());
    for (const std::wstring &dir : {here + L"\\Assets", directoryOf(here) + L"\\Assets"})
        if (fileExists(dir + L"\\mimimo_idle.png")) return dir;
    return here + L"\\Assets";
}

// --- the clay PNGs, decoded with WIC --------------------------------------------

class WicImages final : public ImageSource {
public:
    explicit WicImages(std::wstring directory) : directory_(std::move(directory)) {
        if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(wic_.put()))))
            wic_.reset();
    }
    bool valid() const { return static_cast<bool>(wic_); }
    const std::wstring &directory() const { return directory_; }

    const Image *scaled(const CatSprite &sprite, int width, int height) override {
        if (!wic_ || width <= 0 || height <= 0 || width > 2048 || height > 2048) return nullptr;
        const auto key = std::make_tuple(&sprite, width, height);
        if (auto it = images_.find(key); it != images_.end()) return it->second.width ? &it->second : nullptr;
        if (images_.size() > 96) images_.clear(); // sizes change with the monitor; keep a few
        Image &img = images_[key];
        IWICBitmapSource *source = decoded(sprite);
        Com<IWICBitmapScaler> scaler;
        const auto highQualityCubic = static_cast<WICBitmapInterpolationMode>(4); // Windows 10; older headers lack the name
        if (!source || FAILED(wic_->CreateBitmapScaler(scaler.put())) ||
            (FAILED(scaler->Initialize(source, static_cast<UINT>(width), static_cast<UINT>(height), highQualityCubic)) &&
             FAILED(scaler->Initialize(source, static_cast<UINT>(width), static_cast<UINT>(height), WICBitmapInterpolationModeFant))))
            return nullptr;
        std::vector<std::uint8_t> px(static_cast<std::size_t>(width) * height * 4);
        if (FAILED(scaler->CopyPixels(nullptr, static_cast<UINT>(width) * 4, static_cast<UINT>(px.size()), px.data()))) return nullptr;
        img.width = width;
        img.height = height;
        img.bgra = std::move(px);
        return &img;
    }

private:
    // Decoded once into premultiplied BGRA; a file that fails is not tried again.
    IWICBitmapSource *decoded(const CatSprite &sprite) {
        auto [it, fresh] = decoded_.try_emplace(&sprite);
        if (!fresh) return it->second.get();
        std::wstring path = directory_ + L"\\";
        for (const char *c = sprite.name; *c; ++c) path += static_cast<wchar_t>(*c);
        path += L".png";
        Com<IWICBitmapDecoder> decoder;
        Com<IWICBitmapFrameDecode> frame;
        Com<IWICFormatConverter> converter;
        if (SUCCEEDED(wic_->CreateDecoderFromFilename(path.c_str(), nullptr, GENERIC_READ, WICDecodeMetadataCacheOnDemand, decoder.put())) &&
            SUCCEEDED(decoder->GetFrame(0, frame.put())) && SUCCEEDED(wic_->CreateFormatConverter(converter.put())) &&
            SUCCEEDED(converter->Initialize(frame.get(), GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr, 0.0,
                                            WICBitmapPaletteTypeCustom))) {
            IWICBitmap *bitmap = nullptr;
            if (SUCCEEDED(wic_->CreateBitmapFromSource(converter.get(), WICBitmapCacheOnLoad, &bitmap))) it->second.reset(bitmap);
        }
        return it->second.get();
    }

    struct Release {
        void operator()(IUnknown *p) const {
            if (p) p->Release();
        }
    };
    std::wstring directory_;
    Com<IWICImagingFactory> wic_;
    std::map<const CatSprite *, std::unique_ptr<IWICBitmap, Release>> decoded_;
    std::map<std::tuple<const CatSprite *, int, int>, Image> images_;
};

// --- the desk-pet process -----------------------------------------------------------

constexpr UINT kTrayMessage = WM_APP + 1;
constexpr UINT kDisarmMessage = WM_APP + 2;
constexpr UINT_PTR kWakeTimer = 1, kFrameTimer = 2, kSampleTimer = 3, kProbeTimer = 4, kDwellTimer = 5, kLocationTimer = 6,
                   kActivityTimer = 7, kActivityProbeTimer = 8, kArtTimer = 9;
constexpr UINT kTrayId = 1;
constexpr int kHotkey = 1;
enum : UINT { kCmdPet = 100, kCmdHome, kCmdOut, kCmdQuiet, kCmdWalk, kCmdLogin, kCmdOff, kCmdQuit, kCmdActivity, kCmdActivityPause };
enum : unsigned { kPointer = 1, kMeasure = 2, kScreen = 4 };
constexpr std::uint64_t kQuietMs = 3'600'000;
constexpr std::uint64_t kStaleWorldMs = 500; // a pulse re-measures a world older than this
constexpr std::uint64_t kLocationMs = 33;    // a window being dragged is measured at most at 30 Hz
const wchar_t *const kControlClass = L"MeowpuutDeskControl";
const wchar_t *const kCatClass = L"MeowpuutDeskCat";

class App;
App *g_app = nullptr;

class App {
public:
    explicit App(HINSTANCE instance)
        : instance_(instance), petLedger_(PetLedger::defaultDirectory()), images_(assetDirectory()),
          watcher_(PetLedger::defaultDirectory()) {
        g_app = this;
    }
    ~App() {
        shutdown();
        g_app = nullptr;
    }
    App(const App &) = delete;
    App &operator=(const App &) = delete;

    int run() {
        settings_ = readSettings();
        if (!settings_.deskPets) return 0;
        unitDpi_ = primaryDpi();
        const pulse::Server::Open opened = server_.open(unitDpi_);
        if (opened == pulse::Server::Open::AlreadyRunning) return 0;
        // Without the channel the cats still live on the desk; they just do not hear you type.
        if (!createWindows()) return 1;
        loop_ = std::make_unique<DeskLoop>(coreSettings(settings_), static_cast<std::uint32_t>(GetTickCount64()) ^ GetCurrentProcessId());
        if (!loop_->valid()) return 1;
        adoptLedger(true);
        taskbarCreated_ = RegisterWindowMessageW(L"TaskbarCreated");
        addTray();
        RegisterHotKey(control_, kHotkey, MOD_CONTROL | MOD_ALT | MOD_SHIFT | MOD_NOREPEAT, 'M');
        hook();
        watchSettings();
        ecoQoS(true);

        const std::uint64_t now = monoMs();
        measure(now);
        samplePointer(now);
        checkScreen();
        applyActivity(settings_.activity);
        for (std::uint32_t cat : {MP_PETS_MIMIMO, MP_PETS_MOMO})
            if (settings_.catsOut & cat) loop_->addEvent(MP_DESK_EV_OUT, cat, 1);
        loadDeskCounters();
        if (settings_.catsOut != 0) {
            ++deskCounters_.outings;
            deskCountersDirty_ = true;
        }
        step(now);
        return messageLoop();
    }

    LRESULT controlProc(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
        if (message == taskbarCreated_ && taskbarCreated_) {
            trayAdded_ = false;
            addTray();
            wake(kMeasure);
            return 0;
        }
        switch (message) {
        case WM_TIMER: onTimer(wp); return 0;
        case kTrayMessage:
            switch (LOWORD(lp)) {
            case NIN_SELECT:
            case NIN_KEYSELECT: toggleOut(); return 0;
            case WM_CONTEXTMENU: menu(0, POINT{GET_X_LPARAM(wp), GET_Y_LPARAM(wp)}); return 0;
            default: return 0;
            }
        case kDisarmMessage: applyDwell(dwell_.reset()); return 0;
        case WM_HOTKEY:
            if (wp == kHotkey) toggleOut();
            return 0;
        case WM_DISPLAYCHANGE:
        case WM_DPICHANGED:
            unitDpi_ = primaryDpi();
            server_.setUnitDpi(unitDpi_);
            wake(kMeasure | kScreen);
            return message == WM_DPICHANGED ? 0 : DefWindowProcW(hwnd, message, wp, lp);
        case WM_SETTINGCHANGE:
            if (wp == SPI_SETCLIENTAREAANIMATION) applySettings(readSettings());
            if (wp == SPI_SETWORKAREA) wake(kMeasure);
            return 0;
        case WM_ENDSESSION:
            // The session may end as soon as this returns: write the ledgers now.
            if (wp) {
                flushLedgers();
                PostQuitMessage(0);
            }
            return 0;
        case WM_POWERBROADCAST:
            if (wp == PBT_APMSUSPEND) flushLedgers();
            return TRUE;
        default: return DefWindowProcW(hwnd, message, wp, lp);
        }
    }

    LRESULT catProc(HWND hwnd, int cat, UINT message, WPARAM wp, LPARAM lp) {
        const std::uint32_t id = cat == 0 ? MP_PETS_MIMIMO : MP_PETS_MOMO;
        switch (message) {
        case WM_NCHITTEST: {
            // Clickable only on its own opaque pixels, never on the shadow.
            if (dwell_.captured() || hitTest(frames_[cat], GET_X_LPARAM(lp), GET_Y_LPARAM(lp))) return HTCLIENT;
            PostMessageW(control_, kDisarmMessage, 0, 0);
            return HTTRANSPARENT;
        }
        case WM_MOUSEACTIVATE: return MA_NOACTIVATE;
        case WM_SETCURSOR: SetCursor(LoadCursorW(nullptr, IDC_HAND)); return TRUE;
        case WM_MOUSEMOVE: {
            POINT p{};
            GetCursorPos(&p);
            if (dwell_.captured()) {
                // Every move goes to the core (the throw's speed comes from them); a lifted cat
                // is drawn by the frame timer, so only the move that lifts it steps at once.
                loop_->addEvent(MP_DESK_EV_DRAG, id, 0, p.x / unit(), p.y / unit());
                if (!framesOn_) wake(0);
            } else if (!hitTest(frames_[cat], p.x, p.y)) {
                applyDwell(dwell_.reset());
            }
            return 0;
        }
        case WM_LBUTTONDOWN: {
            POINT p{};
            GetCursorPos(&p);
            SetCapture(hwnd);
            dwell_.capture(true);
            loop_->addEvent(MP_DESK_EV_PRESS, id, 0, p.x / unit(), p.y / unit());
            wake(0);
            return 0;
        }
        case WM_LBUTTONUP:
            if (GetCapture() == hwnd) ReleaseCapture(); // WM_CAPTURECHANGED sends the release
            return 0;
        case WM_CAPTURECHANGED:
            if (dwell_.captured()) {
                dwell_.capture(false);
                loop_->addEvent(MP_DESK_EV_RELEASE, id);
                wake(kPointer);
            }
            return 0;
        case WM_LBUTTONDBLCLK:
            loop_->addEvent(MP_DESK_EV_CARD, id);
            wake(0);
            return 0;
        case WM_RBUTTONUP: {
            POINT p{};
            GetCursorPos(&p);
            menu(id, p);
            return 0;
        }
        case WM_DPICHANGED: return 0; // the cat's frame is drawn and placed for its monitor already
        default: return DefWindowProcW(hwnd, message, wp, lp);
        }
    }

    void onWinEvent(DWORD event, HWND hwnd, LONG object, LONG child) {
        if (event == EVENT_SYSTEM_FOREGROUND) {
            // 「記錄時間花在哪裡」: only while it is on is the window's process looked at at all.
            if (watcher_.enabled() && hwnd && !isOurs(hwnd)) {
                std::string name;
                const std::string app = appIdentifier(hwnd, watcher_.wantsAppName() ? &name : nullptr);
                watcher_.foreground(app, deskClock(monoMs()), idleMs(), std::move(name));
                afterActivity();
            }
            hookLocation(hwnd);
            // The taskbar lifts itself to the front of the topmost band when clicked.
            for (HWND w : cats_)
                if (w && IsWindowVisible(w)) SetWindowPos(w, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            wake(kScreen | kMeasure);
            return;
        }
        if (object != OBJID_WINDOW || child != CHILDID_SELF) return;
        if (event == EVENT_OBJECT_LOCATIONCHANGE) {
            if (hwnd != GetForegroundWindow()) return;
            // Alt+Enter, a window dragged by its title bar: at most every kLocationMs.
            const std::uint64_t now = monoMs();
            if (now - locationAt_ >= kLocationMs) {
                locationAt_ = now;
                wake(kScreen | (loop_->deskCats() ? kMeasure : 0u));
            } else {
                SetTimer(control_, kLocationTimer, static_cast<UINT>(kLocationMs), nullptr);
            }
            return;
        }
        // Moved, resized, minimized, restored, cloaked: the cats may stand on it.
        if (loop_->deskCats()) wake(kMeasure);
    }

private:
    // --- setup ----------------------------------------------------------------------
    static LRESULT CALLBACK controlThunk(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
        return g_app ? g_app->controlProc(hwnd, message, wp, lp) : DefWindowProcW(hwnd, message, wp, lp);
    }
    static LRESULT CALLBACK catThunk(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
        const int cat = static_cast<int>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        return g_app && g_app->loop_ ? g_app->catProc(hwnd, cat, message, wp, lp) : DefWindowProcW(hwnd, message, wp, lp);
    }
    static void CALLBACK winEventThunk(HWINEVENTHOOK, DWORD event, HWND hwnd, LONG object, LONG child, DWORD, DWORD) {
        if (g_app && g_app->loop_) g_app->onWinEvent(event, hwnd, object, child);
    }

    bool createWindows() {
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof wc;
        wc.lpfnWndProc = &App::controlThunk;
        wc.hInstance = instance_;
        wc.lpszClassName = kControlClass;
        if (!RegisterClassExW(&wc)) return false;
        WNDCLASSEXW cc{};
        cc.cbSize = sizeof cc;
        cc.style = CS_DBLCLKS;
        cc.lpfnWndProc = &App::catThunk;
        cc.hInstance = instance_;
        cc.hCursor = LoadCursorW(nullptr, IDC_HAND);
        cc.lpszClassName = kCatClass;
        if (!RegisterClassExW(&cc)) return false;
        // A hidden top-level window (not message-only: it hears broadcasts and owns the tray icon).
        control_ = CreateWindowExW(WS_EX_TOOLWINDOW, kControlClass, L"喵撲貓窩", WS_POPUP, 0, 0, 0, 0, nullptr, nullptr, instance_, nullptr);
        if (!control_) return false;
        for (int i = 0; i < 2; ++i) {
            cats_[i] = CreateWindowExW(WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_TOPMOST | WS_EX_NOACTIVATE, kCatClass,
                                       i == 0 ? L"米米莫" : L"莫莫", WS_POPUP, 0, 0, 1, 1, nullptr, nullptr, instance_, nullptr);
            if (!cats_[i]) return false;
            SetWindowLongPtrW(cats_[i], GWLP_USERDATA, i);
        }
        memDC_ = CreateCompatibleDC(nullptr);
        return memDC_ != nullptr;
    }

    void addTray() {
        if (trayAdded_ || !control_) return;
        NOTIFYICONDATAW nid{};
        nid.cbSize = sizeof nid;
        nid.hWnd = control_;
        nid.uID = kTrayId;
        nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP;
        nid.uCallbackMessage = kTrayMessage;
        nid.hIcon = static_cast<HICON>(LoadImageW(instance_, MAKEINTRESOURCEW(1), IMAGE_ICON, GetSystemMetrics(SM_CXSMICON),
                                                  GetSystemMetrics(SM_CYSMICON), LR_DEFAULTCOLOR));
        wcsncpy_s(nid.szTip, L"喵撲貓窩", _TRUNCATE);
        trayAdded_ = Shell_NotifyIconW(NIM_ADD, &nid) != FALSE;
        nid.uVersion = NOTIFYICON_VERSION_4;
        if (trayAdded_) Shell_NotifyIconW(NIM_SETVERSION, &nid);
    }

    void hook() {
        const DWORD flags = WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS;
        hooks_[0] = SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, nullptr, &App::winEventThunk, 0, 0, flags);
        hooks_[1] = SetWinEventHook(EVENT_SYSTEM_MOVESIZESTART, EVENT_SYSTEM_MOVESIZEEND, nullptr, &App::winEventThunk, 0, 0, flags);
        hooks_[2] = SetWinEventHook(EVENT_SYSTEM_MINIMIZESTART, EVENT_SYSTEM_MINIMIZEEND, nullptr, &App::winEventThunk, 0, 0, flags);
        hooks_[3] = SetWinEventHook(EVENT_OBJECT_CLOAKED, EVENT_OBJECT_UNCLOAKED, nullptr, &App::winEventThunk, 0, 0, flags);
        hookLocation(GetForegroundWindow());
    }

    // EVENT_OBJECT_LOCATIONCHANGE is frequent: only for the process of the window you are using.
    void hookLocation(HWND foreground) {
        DWORD pid = 0;
        if (foreground) GetWindowThreadProcessId(foreground, &pid);
        if (pid == locationPid_ && locationHook_) return;
        if (locationHook_) UnhookWinEvent(locationHook_);
        locationHook_ = nullptr;
        locationPid_ = pid;
        if (pid && pid != GetCurrentProcessId())
            locationHook_ = SetWinEventHook(EVENT_OBJECT_LOCATIONCHANGE, EVENT_OBJECT_LOCATIONCHANGE, nullptr, &App::winEventThunk, pid, 0,
                                            WINEVENT_OUTOFCONTEXT);
    }

    // HKCU\Software\Meowpuut changes (the IME's menu, the companion app): read again.
    void watchSettings() {
        if (!settingsEvent_) settingsEvent_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!settingsKey_ &&
            RegCreateKeyExW(HKEY_CURRENT_USER, kSettingsKey, 0, nullptr, 0, KEY_NOTIFY | KEY_READ, nullptr, &settingsKey_, nullptr) != ERROR_SUCCESS)
            settingsKey_ = nullptr;
        if (settingsKey_ && settingsEvent_)
            RegNotifyChangeKeyValue(settingsKey_, FALSE, REG_NOTIFY_CHANGE_LAST_SET, settingsEvent_, TRUE);
    }

    // EcoQoS while nothing moves under your hand.
    void ecoQoS(bool on) {
        if (eco_ == static_cast<int>(on)) return;
        eco_ = on;
        PROCESS_POWER_THROTTLING_STATE state{};
        state.Version = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
        state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
        state.StateMask = on ? PROCESS_POWER_THROTTLING_EXECUTION_SPEED : 0;
        SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, &state, sizeof state);
    }

    void shutdown() {
        flushLedgers();
        for (HWINEVENTHOOK &h : hooks_)
            if (h) UnhookWinEvent(h), h = nullptr;
        if (locationHook_) UnhookWinEvent(locationHook_), locationHook_ = nullptr;
        if (trayAdded_) {
            NOTIFYICONDATAW nid{};
            nid.cbSize = sizeof nid;
            nid.hWnd = control_;
            nid.uID = kTrayId;
            Shell_NotifyIconW(NIM_DELETE, &nid);
            trayAdded_ = false;
        }
        if (control_) UnregisterHotKey(control_, kHotkey);
        for (int i = 0; i < 2; ++i) {
            if (cats_[i]) DestroyWindow(cats_[i]), cats_[i] = nullptr;
            if (dib_[i]) DeleteObject(dib_[i]), dib_[i] = nullptr;
        }
        if (control_) DestroyWindow(control_), control_ = nullptr;
        if (memDC_) DeleteDC(memDC_), memDC_ = nullptr;
        if (settingsKey_) RegCloseKey(settingsKey_), settingsKey_ = nullptr;
        if (settingsEvent_) CloseHandle(settingsEvent_), settingsEvent_ = nullptr;
    }

    int messageLoop() {
        for (;;) {
            HANDLE handles[2];
            DWORD count = 0;
            if (server_.signal()) handles[count++] = server_.signal();
            if (settingsEvent_) handles[count++] = settingsEvent_;
            const DWORD r = MsgWaitForMultipleObjectsEx(count, handles, INFINITE, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
            if (r < WAIT_OBJECT_0 + count) {
                const HANDLE h = handles[r - WAIT_OBJECT_0];
                if (h == server_.signal()) onPulse();
                else if (h == settingsEvent_) {
                    applySettings(readSettings());
                    watchSettings();
                }
            }
            MSG msg;
            while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
                if (msg.message == WM_QUIT) return static_cast<int>(msg.wParam);
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
    }

    // --- sensing ----------------------------------------------------------------------
    double unit() const { return unitDpi_ / 96.0; }

    bool isOurs(HWND hwnd) const {
        DWORD pid = 0;
        return hwnd && GetWindowThreadProcessId(hwnd, &pid) && pid == GetCurrentProcessId();
    }

    WindowFacts facts(HWND hwnd, DWORD shellPid, HWND shellWindow, const std::vector<MonitorFacts> &monitors) const {
        WindowFacts f;
        if (!hwnd) return f;
        f.id = static_cast<std::uint32_t>(reinterpret_cast<std::uintptr_t>(hwnd));
        f.visible = IsWindowVisible(hwnd) != FALSE;
        if (!f.visible) return f;
        f.minimized = IsIconic(hwnd) != FALSE;
        DWORD cloaked = 0;
        f.cloaked = SUCCEEDED(DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, &cloaked, sizeof cloaked)) && cloaked != 0;
        const LONG_PTR ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
        f.toolWindow = (ex & WS_EX_TOOLWINDOW) != 0;
        f.appWindow = (ex & WS_EX_APPWINDOW) != 0;
        f.clickThrough = (ex & WS_EX_LAYERED) && (ex & WS_EX_TRANSPARENT);
        DWORD pid = 0;
        GetWindowThreadProcessId(hwnd, &pid);
        f.ours = pid == GetCurrentProcessId();
        RECT r{};
        if (FAILED(DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, &r, sizeof r))) GetWindowRect(hwnd, &r);
        f.bounds = rectPx(r);
        // The desktop background: the shell window, or a window of its process covering a whole monitor (WorkerW).
        if (hwnd == shellWindow) f.shell = true;
        else if (pid == shellPid)
            for (const auto &m : monitors)
                if (f.bounds.left <= m.monitor.left && f.bounds.top <= m.monitor.top && f.bounds.right >= m.monitor.right &&
                    f.bounds.bottom >= m.monitor.bottom)
                    f.shell = true;
        return f;
    }

    struct EnumContext {
        const App *app;
        Snapshot *snapshot;
        DWORD shellPid;
        HWND shellWindow;
    };

    static BOOL CALLBACK monitorThunk(HMONITOR monitor, HDC, LPRECT, LPARAM lp) {
        auto *s = reinterpret_cast<Snapshot *>(lp);
        MONITORINFO info{};
        info.cbSize = sizeof info;
        if (!GetMonitorInfoW(monitor, &info)) return TRUE;
        MonitorFacts m;
        m.monitor = rectPx(info.rcMonitor);
        m.work = rectPx(info.rcWork);
        m.dpi = monitorDpi(monitor);
        m.primary = (info.dwFlags & MONITORINFOF_PRIMARY) != 0;
        s->monitors.push_back(m);
        return s->monitors.size() < 32;
    }

    static BOOL CALLBACK windowThunk(HWND hwnd, LPARAM lp) {
        auto *c = reinterpret_cast<EnumContext *>(lp);
        WindowFacts f = c->app->facts(hwnd, c->shellPid, c->shellWindow, c->snapshot->monitors);
        if (keep(f)) c->snapshot->windows.push_back(f);
        return c->snapshot->windows.size() < 512;
    }

    Snapshot snapshot() const {
        Snapshot s;
        s.unitDpi = unitDpi_;
        s.catScale = settings_.catScale;
        EnumDisplayMonitors(nullptr, nullptr, &App::monitorThunk, reinterpret_cast<LPARAM>(&s));
        APPBARDATA bar{};
        bar.cbSize = sizeof bar;
        if (SHAppBarMessage(ABM_GETTASKBARPOS, &bar)) {
            s.taskbar.known = true;
            s.taskbar.rect = rectPx(bar.rc);
            s.taskbar.edge = bar.uEdge;
            s.taskbar.autoHide = (SHAppBarMessage(ABM_GETSTATE, &bar) & ABS_AUTOHIDE) != 0;
        }
        const HWND shellWindow = GetShellWindow();
        DWORD shellPid = 0;
        if (shellWindow) GetWindowThreadProcessId(shellWindow, &shellPid);
        EnumContext context{this, &s, shellPid, shellWindow};
        EnumWindows(&App::windowThunk, reinterpret_cast<LPARAM>(&context));
        s.foreground = facts(GetForegroundWindow(), shellPid, shellWindow, s.monitors);
        NOTIFYICONIDENTIFIER icon{};
        icon.cbSize = sizeof icon;
        icon.hWnd = control_;
        icon.uID = kTrayId;
        RECT tray{};
        if (trayAdded_ && SUCCEEDED(Shell_NotifyIconGetRect(&icon, &tray))) {
            s.trayKnown = true;
            s.tray = rectPx(tray);
        }
        return s;
    }

    void measure(std::uint64_t now) {
        snapshot_ = snapshot();
        loop_->setWorld(buildWorld(snapshot_));
        measuredAt_ = now;
    }

    // Full screen (FINAL §4.6 item 4): the shell's own state or the foreground's geometry.
    void checkScreen() {
        QUERY_USER_NOTIFICATION_STATE state = QUNS_ACCEPTS_NOTIFICATIONS;
        const bool busy = SUCCEEDED(SHQueryUserNotificationState(&state)) && fullScreenState(static_cast<int>(state));
        Snapshot s;
        s.monitors = snapshot_.monitors;
        if (s.monitors.empty()) EnumDisplayMonitors(nullptr, nullptr, &App::monitorThunk, reinterpret_cast<LPARAM>(&s));
        const HWND shellWindow = GetShellWindow();
        DWORD shellPid = 0;
        if (shellWindow) GetWindowThreadProcessId(shellWindow, &shellPid);
        s.foreground = facts(GetForegroundWindow(), shellPid, shellWindow, s.monitors);
        const bool full = busy || coversMonitor(s);
        if (full != hidden_) {
            hidden_ = full;
            loop_->addEvent(MP_DESK_EV_HIDDEN, 0, full ? 1u : 0u);
            watcher_.fullScreen(full, deskClock(monoMs()), idleMs()); // a step only while recording
            afterActivity();
        }
    }

    void samplePointer(std::uint64_t now) {
        POINT p{};
        if (!GetCursorPos(&p)) return;
        const int button = GetSystemMetrics(SM_SWAPBUTTON) ? VK_RBUTTON : VK_LBUTTON;
        const bool down = (GetAsyncKeyState(button) & 0x8000) != 0;
        loop_->addEvent(MP_DESK_EV_POINTER, 0, down ? 1u : 0u, p.x / unit(), p.y / unit());
        LASTINPUTINFO input{};
        input.cbSize = sizeof input;
        if (GetLastInputInfo(&input)) {
            lastInputTick_ = input.dwTime;
            const DWORD idle = GetTickCount() - input.dwTime;
            loop_->addEvent(MP_DESK_EV_IDLE, 0, idle);
            if (watcher_.enabled()) {
                // The time ledger's idle reading rides on this sampling (10 Hz / 2 Hz); it steps only when due.
                watcher_.sample(deskClock(now), idle);
                afterActivity();
            }
        }
        std::uint32_t over = 0;
        if (hitTest(frames_[1], p.x, p.y)) over = MP_PETS_MOMO;
        else if (hitTest(frames_[0], p.x, p.y)) over = MP_PETS_MIMIMO;
        const bool typing = loop_->output().user_state == MP_DESK_USER_TYPING;
        applyDwell(dwell_.sample(now, over, down, typing, !hidden_), p);
    }

    void applyDwell(const DwellStep &d, POINT p = POINT{}) {
        if (d.disarm)
            for (HWND w : cats_) clickable(w, false);
        if (d.arm) {
            clickable(cats_[d.arm == MP_PETS_MIMIMO ? 0 : 1], true);
            loop_->addEvent(MP_DESK_EV_NOTICE, d.arm, 0, p.x / unit(), p.y / unit());
        }
        if (d.checkAt) {
            const std::uint64_t now = monoMs();
            SetTimer(control_, kDwellTimer, static_cast<UINT>(std::clamp<std::uint64_t>(d.checkAt > now ? d.checkAt - now : 1, 1, kDwellMs)), nullptr);
        }
    }

    static void clickable(HWND w, bool on) {
        if (!w) return;
        const LONG_PTR ex = GetWindowLongPtrW(w, GWL_EXSTYLE);
        const LONG_PTR next = on ? (ex & ~static_cast<LONG_PTR>(WS_EX_TRANSPARENT)) : (ex | WS_EX_TRANSPARENT);
        if (next == ex) return;
        SetWindowLongPtrW(w, GWL_EXSTYLE, next);
        SetWindowPos(w, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    }

    // --- the loop ---------------------------------------------------------------------
    void onPulse() {
        std::uint8_t bytes[MP_PULSE_BYTES];
        const std::uint64_t now = monoMs();
        if (server_.take(now, bytes) != pulse::Read::Fresh) return;
        MPPulse p{};
        std::uint32_t owner = 0;
        if (mp_pulse_decode(bytes, sizeof bytes, &p) == MP_PULSE_OK && (p.flags & MP_PULSE_ORIGIN)) {
            if (now - measuredAt_ > kStaleWorldMs) measure(now);
            owner = ownerAt(loop_->world(), p.origin_x * 8.0 + 4, p.origin_y * 8.0 + 4);
        }
        loop_->addPulse(bytes, owner);
        applyDwell(dwell_.sample(now, 0, false, true, true)); // typing: click-through
        step(now);
    }

    void onTimer(WPARAM id) {
        switch (id) {
        case kWakeTimer:
            KillTimer(control_, kWakeTimer);
            wake(0);
            break;
        case kFrameTimer: wake(0); break;
        case kSampleTimer: wake(kPointer | kMeasure | kScreen); break;
        case kProbeTimer: {
            // Away: the core hears of it only when there was input; otherwise nothing runs at all.
            LASTINPUTINFO input{};
            input.cbSize = sizeof input;
            if (GetLastInputInfo(&input) && input.dwTime != lastInputTick_) wake(kPointer | kScreen);
            break;
        }
        case kDwellTimer:
            KillTimer(control_, kDwellTimer);
            wake(kPointer);
            break;
        case kLocationTimer:
            KillTimer(control_, kLocationTimer);
            locationAt_ = monoMs();
            wake(kScreen | kMeasure);
            break;
        case kActivityTimer:
            KillTimer(control_, kActivityTimer);
            activityTickAt_ = 0;
            watcher_.sample(deskClock(monoMs()), idleMs());
            afterActivity();
            break;
        case kActivityProbeTimer:
            watcher_.sample(deskClock(monoMs()), idleMs());
            afterActivity();
            break;
        case kArtTimer:
            KillTimer(control_, kArtTimer);
            draw(monoMs()); // the turn after a walk is over: the front-facing figure
            break;
        default: break;
        }
    }

    void wake(unsigned what) {
        if (!loop_) return;
        const std::uint64_t now = monoMs();
        if (what & kScreen) checkScreen();
        if ((what & kMeasure) && !hidden_) measure(now);
        if ((what & kPointer) && !hidden_) samplePointer(now);
        step(now);
    }

    void step(std::uint64_t now) {
        if (quietUntil_ && now >= quietUntil_) {
            quietUntil_ = 0;
            settings_.quiet = false;
            loop_->setSettings(deskClock(now), coreSettings(settings_));
        }
        const int r = loop_->step(deskClock(now));
        if (r == MP_DESK_E_PANIC) {
            // The core started over with both cats at home: bring them out again.
            for (std::uint32_t cat : {MP_PETS_MIMIMO, MP_PETS_MOMO})
                if (settings_.catsOut & cat) loop_->addEvent(MP_DESK_EV_OUT, cat, 1);
            loop_->setWorld(loop_->world());
        }
        if (r != MP_DESK_OK) return;
        const MPDeskOutput &out = loop_->output();
        // Full screen: both cats are back on the candidate glass (FINAL.md §4.2).
        server_.setDeskCats((out.flags & MP_DESK_OUT_IS_HIDDEN) ? 0u : loop_->deskCats());
        if (recordTouches(loop_->taken(), petLedger_, systemLedgerClock())) adoptLedger(false);
        recordDeskCounters(loop_->taken(), now);
        if (out.user_state == MP_DESK_USER_AWAY && userState_ != MP_DESK_USER_AWAY) flushLedgers(); // you went away
        userState_ = out.user_state;
        for (int i = 0; i < 2; ++i) gaits_[i].step(out.cats[i], now);
        draw(now);
        arm(now);
    }

    // --- the ledgers ------------------------------------------------------------------
    // Today's shared limits from the pet ledger: at start, and whenever a flush merged the file.
    void adoptLedger(bool always) {
        const std::uint64_t merges = petLedger_.merges();
        if (!always && merges == petMerges_) return;
        petMerges_ = merges;
        const std::vector<std::uint8_t> state = petLedger_.state();
        if (!state.empty()) {
            loop_->adopt(state);
            if (state.size() >= 8) {
                const std::uint32_t epoch = static_cast<std::uint32_t>(state[4]) |
                                            (static_cast<std::uint32_t>(state[5]) << 8) |
                                            (static_cast<std::uint32_t>(state[6]) << 16) |
                                            (static_cast<std::uint32_t>(state[7]) << 24);
                if (epoch != 0 && epoch != deskCounters_.epoch) {
                    if (deskCounters_.epoch != 0 && epoch > deskCounters_.epoch) deskCounters_ = DeskCounters{};
                    deskCounters_.epoch = epoch;
                    deskCountersDirty_ = true;
                }
            }
        }
    }

    void flushLedgers() {
        petLedger_.flush();
        watcher_.flush();
        flushDeskCounters(true, monoMs());
        if (loop_) adoptLedger(false);
    }

    struct DeskCounters {
        std::uint32_t epoch = 0, outings = 0, purrs = 0, rides = 0, caught = 0, found = 0, roof = 0, nose = 0, moved = 0;
        std::uint64_t steps = 0;
    };

    static std::uint64_t parseJsonUint(const std::string &json, const char *key) {
        const std::string needle = std::string("\"") + key + "\":";
        const std::size_t pos = json.find(needle);
        if (pos == std::string::npos) return 0;
        std::size_t i = pos + needle.size();
        while (i < json.size() && (json[i] == ' ' || json[i] == '\t')) ++i;
        std::uint64_t v = 0;
        while (i < json.size() && json[i] >= '0' && json[i] <= '9') {
            v = v * 10 + static_cast<std::uint64_t>(json[i] - '0');
            ++i;
        }
        return v;
    }

    void loadDeskCounters() {
        const std::wstring path = (PetLedger::defaultDirectory() / L"DeskCounters.json").wstring();
        HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
                               FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f == INVALID_HANDLE_VALUE) return;
        std::string buf(4096, '\0');
        DWORD got = 0;
        const BOOL ok = ReadFile(f, buf.data(), static_cast<DWORD>(buf.size()), &got, nullptr);
        CloseHandle(f);
        if (!ok || got == 0) return;
        buf.resize(got);
        deskCounters_.epoch = static_cast<std::uint32_t>(parseJsonUint(buf, "epoch"));
        deskCounters_.outings = static_cast<std::uint32_t>(parseJsonUint(buf, "outings"));
        deskCounters_.purrs = static_cast<std::uint32_t>(parseJsonUint(buf, "purrs"));
        deskCounters_.rides = static_cast<std::uint32_t>(parseJsonUint(buf, "rides"));
        deskCounters_.caught = static_cast<std::uint32_t>(parseJsonUint(buf, "caught"));
        deskCounters_.found = static_cast<std::uint32_t>(parseJsonUint(buf, "found"));
        deskCounters_.roof = static_cast<std::uint32_t>(parseJsonUint(buf, "roof"));
        deskCounters_.steps = parseJsonUint(buf, "steps");
        deskCounters_.nose = static_cast<std::uint32_t>(parseJsonUint(buf, "nose"));
        deskCounters_.moved = static_cast<std::uint32_t>(parseJsonUint(buf, "moved"));
    }

    void recordDeskCounters(const std::vector<MPDeskEventOut> &taken, std::uint64_t now) {
        for (const MPDeskEventOut &e : taken) {
            switch (e.kind) {
            case MP_DESK_OUT_PURR: ++deskCounters_.purrs; deskCountersDirty_ = true; break;
            case MP_DESK_OUT_RODE: ++deskCounters_.rides; deskCountersDirty_ = true; break;
            case MP_DESK_OUT_FOUND: ++deskCounters_.found; deskCountersDirty_ = true; break;
            case MP_DESK_OUT_ROOF: ++deskCounters_.roof; deskCountersDirty_ = true; break;
            case MP_DESK_OUT_STEP: ++deskCounters_.steps; deskCountersDirty_ = true; break;
            default: break;
            }
        }
        flushDeskCounters(false, now);
    }

    void flushDeskCounters(bool force, std::uint64_t now) {
        if (!deskCountersDirty_) return;
        if (!force && deskCountersFlushedAt_ != 0 && now - deskCountersFlushedAt_ < 60000) return;
        const std::filesystem::path dir = PetLedger::defaultDirectory();
        CreateDirectoryW(dir.c_str(), nullptr);
        const std::wstring tmp = (dir / L"DeskCounters.json.tmp").wstring();
        const std::wstring dst = (dir / L"DeskCounters.json").wstring();
        char buf[384];
        const int len = std::snprintf(
            buf, sizeof buf,
            "{\"epoch\":%u,\"outings\":%u,\"purrs\":%u,\"rides\":%u,\"caught\":%u,\"found\":%u,\"roof\":%u,\"steps\":%llu,\"nose\":%u,\"moved\":%u}\n",
            deskCounters_.epoch, deskCounters_.outings, deskCounters_.purrs, deskCounters_.rides, deskCounters_.caught,
            deskCounters_.found, deskCounters_.roof, static_cast<unsigned long long>(deskCounters_.steps),
            deskCounters_.nose, deskCounters_.moved);
        if (len <= 0 || static_cast<std::size_t>(len) >= sizeof buf) return;
        HANDLE f = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f == INVALID_HANDLE_VALUE) return;
        DWORD wrote = 0;
        const BOOL ok = WriteFile(f, buf, static_cast<DWORD>(len), &wrote, nullptr);
        CloseHandle(f);
        if (ok && wrote == static_cast<DWORD>(len) &&
            MoveFileExW(tmp.c_str(), dst.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
            deskCountersDirty_ = false;
            deskCountersFlushedAt_ = now;
        } else {
            DeleteFileW(tmp.c_str());
        }
    }

    // --- 「記錄時間花在哪裡」 (ForegroundWatcher.h) --------------------------------------------
    static std::uint64_t idleMs() {
        LASTINPUTINFO input{};
        input.cbSize = sizeof input;
        return GetLastInputInfo(&input) ? static_cast<std::uint64_t>(GetTickCount() - input.dwTime) : 0;
    }

    void applyActivity(const ActivitySwitches &next) {
        const std::uint64_t now = monoMs();
        // The app in front is looked at again when recording was just turned on, or when the
        // display name was just allowed: it must show without waiting for the next foreground change.
        const bool fresh = next.on && (!watcher_.enabled() || (next.shareAppName && !watcher_.wantsAppName()));
        settings_.activity = next;
        watcher_.configure(next, deskClock(now), idleMs());
        if (fresh) {
            const HWND front = GetForegroundWindow();
            if (front && !isOurs(front)) {
                std::string name;
                const std::string app = appIdentifier(front, watcher_.wantsAppName() ? &name : nullptr);
                watcher_.foreground(app, deskClock(now), idleMs(), std::move(name));
            }
        }
        afterActivity();
    }

    // Publishes what changed to the section and arms the tracker's own timers: a 60 s tick while
    // minutes are counted, the 30 s input check while you are away. Both coalescable; none while off.
    void afterActivity() {
        if (watcher_.changes() != nowPublished_) {
            nowPublished_ = watcher_.changes();
            server_.setNow(watcher_.current());
        }
        if (!control_) return;
        const ForegroundWatcher::Schedule s = watcher_.schedule();
        if (s.tickAt != activityTickAt_) {
            activityTickAt_ = s.tickAt;
            if (s.tickAt) {
                const std::uint64_t now = monoMs();
                const UINT ms = static_cast<UINT>(std::clamp<std::uint64_t>(s.tickAt > now ? s.tickAt - now : 1, USER_TIMER_MINIMUM, 600'000));
                SetCoalescableTimer(control_, kActivityTimer, ms, nullptr, 5000);
            } else {
                KillTimer(control_, kActivityTimer);
            }
        }
        if (s.probe != activityProbeOn_) {
            activityProbeOn_ = s.probe;
            if (s.probe) SetCoalescableTimer(control_, kActivityProbeTimer, ForegroundWatcher::kProbeMs, nullptr, 5000);
            else KillTimer(control_, kActivityProbeTimer);
        }
    }

    void draw(std::uint64_t now) {
        const MPDeskOutput &out = loop_->output();
        const bool hidden = (out.flags & MP_DESK_OUT_IS_HIDDEN) != 0;
        std::uint64_t turnEnds = UINT64_MAX;
        for (int i = 0; i < 2; ++i) {
            const MPDeskCat &c = out.cats[i];
            double opacity = 1;
            if (c.flags & MP_DESK_CAT_FADING) {
                if (!fadeSince_[i]) fadeSince_[i] = now;
                opacity = std::min(1.0, static_cast<double>(now - fadeSince_[i]) / 200.0);
            } else {
                fadeSince_[i] = 0;
            }
            PaintContext ctx;
            ctx.unitDpi = unitDpi_;
            ctx.monitorDpi = monitorDpi(MonitorFromPoint(POINT{static_cast<LONG>(std::lround(std::clamp(c.x * unit(), -1e7, 1e7))),
                                                               static_cast<LONG>(std::lround(std::clamp(c.y * unit(), -1e7, 1e7)) - 1)},
                                                         MONITOR_DEFAULTTONEAREST));
            ctx.catScale = settings_.catScale;
            ctx.timeMs = static_cast<double>(now);
            ctx.reduceMotion = settings_.reduceMotion;
            ctx.opacity = opacity;
            ctx.walkFrame = gaits_[i].walkFrame();
            ctx.walkLength = gaits_[i].length();
            ctx.turning = gaits_[i].turning(now);
            MPDeskCat shown = c;
            if (hidden) shown.body = MP_DESK_BODY_HOME; // full screen: nothing on the desk
            if (paintCat(i, shown, ctx, images_, frames_[i])) present(i);
            if (ctx.turning && !settings_.reduceMotion) turnEnds = std::min(turnEnds, gaits_[i].turnUntil());
        }
        // The turn after a walk lasts kTurnMs; then the front-facing figure, drawn by one timer.
        if (turnEnds != UINT64_MAX) SetTimer(control_, kArtTimer, static_cast<UINT>(std::max<std::uint64_t>(turnEnds - now, 1)), nullptr);
    }

    void present(int i) {
        const Frame &f = frames_[i];
        HWND w = cats_[i];
        if (!f.visible) {
            if (IsWindowVisible(w)) ShowWindow(w, SW_HIDE);
            if (dwell_.armed() == (i == 0 ? MP_PETS_MIMIMO : MP_PETS_MOMO) && !dwell_.captured()) applyDwell(dwell_.reset());
            return;
        }
        if (!dib_[i] || dibW_[i] != f.width || dibH_[i] != f.height) {
            if (dib_[i]) DeleteObject(dib_[i]);
            BITMAPINFO bi{};
            bi.bmiHeader.biSize = sizeof bi.bmiHeader;
            bi.bmiHeader.biWidth = f.width;
            bi.bmiHeader.biHeight = -f.height; // top-down
            bi.bmiHeader.biPlanes = 1;
            bi.bmiHeader.biBitCount = 32;
            bi.bmiHeader.biCompression = BI_RGB;
            dib_[i] = CreateDIBSection(memDC_, &bi, DIB_RGB_COLORS, &bits_[i], nullptr, 0);
            dibW_[i] = f.width;
            dibH_[i] = f.height;
            if (!dib_[i]) return;
        }
        std::memcpy(bits_[i], f.bgra.data(), f.bgra.size());
        GdiFlush();
        const HGDIOBJ old = SelectObject(memDC_, dib_[i]);
        POINT position{f.left, f.top}, origin{0, 0};
        SIZE size{f.width, f.height};
        BLENDFUNCTION blend{AC_SRC_OVER, 0, f.opacity, AC_SRC_ALPHA};
        UpdateLayeredWindow(w, nullptr, &position, &size, memDC_, &origin, 0, &blend, ULW_ALPHA);
        SelectObject(memDC_, old);
        if (!IsWindowVisible(w)) {
            ShowWindow(w, SW_SHOWNOACTIVATE);
            SetWindowPos(w, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        }
    }

    // One timer per need, as DeskLoop::timers() says; none while hidden.
    void arm(std::uint64_t now) {
        const Timers t = loop_->timers();
        const bool hidden = (loop_->output().flags & MP_DESK_OUT_IS_HIDDEN) != 0;
        if (t.frames && !hidden) {
            if (!framesOn_) SetTimer(control_, kFrameTimer, kFrameMs, nullptr);
            framesOn_ = true;
        } else if (framesOn_) {
            KillTimer(control_, kFrameTimer);
            framesOn_ = false;
        }
        if (t.wake && !hidden) {
            const std::uint64_t delay = t.wakeAt > now ? t.wakeAt - now : 1;
            const UINT ms = static_cast<UINT>(std::clamp<std::uint64_t>(delay, USER_TIMER_MINIMUM, 0x7FFFFFF0u));
            SetCoalescableTimer(control_, kWakeTimer, ms, nullptr, ms <= 200 ? TIMERV_DEFAULT_COALESCING : std::min<UINT>(ms / 10, 1000));
        } else {
            KillTimer(control_, kWakeTimer);
        }
        const std::uint32_t sample = hidden ? 0 : t.sampleMs;
        if (sample != sampleMs_) {
            sampleMs_ = sample;
            if (sample) SetCoalescableTimer(control_, kSampleTimer, sample, nullptr, sample <= kAwakeSampleMs ? 16 : 100);
            else KillTimer(control_, kSampleTimer);
        }
        const bool probe = t.idleProbe && !hidden;
        if (probe != probeOn_) {
            probeOn_ = probe;
            if (probe) SetCoalescableTimer(control_, kProbeTimer, kIdleProbeMs, nullptr, 5000);
            else KillTimer(control_, kProbeTimer);
        }
        ecoQoS(!(t.frames || dwell_.captured()));
    }

    // --- commands ---------------------------------------------------------------------
    bool anyOut() const { return loop_->deskCats() != 0 || settings_.catsOut != 0; }

    void setOut(bool out) {
        for (std::uint32_t cat : {MP_PETS_MIMIMO, MP_PETS_MOMO}) loop_->addEvent(MP_DESK_EV_OUT, cat, out ? 1u : 0u);
        settings_.catsOut = out ? 3u : 0u;
        writeSetting(L"DeskCatsOut", settings_.catsOut);
        wake(kMeasure | kPointer);
    }

    void toggleOut() { setOut(!anyOut()); }

    void applySettings(const DeskSettings &next) {
        if (!next.deskPets) {
            PostQuitMessage(0);
            return;
        }
        if (next.activity != settings_.activity) applyActivity(next.activity);
        const bool changed = next.typingSeat != settings_.typingSeat || next.reduceMotion != settings_.reduceMotion;
        settings_.typingSeat = next.typingSeat;
        settings_.reduceMotion = next.reduceMotion;
        if (changed) {
            const std::uint64_t now = monoMs();
            loop_->setSettings(deskClock(now), coreSettings(settings_));
            step(now);
        }
    }

    void menu(std::uint32_t cat, POINT at) {
        HMENU m = CreatePopupMenu(), sub = CreatePopupMenu();
        if (!m || !sub) return;
        if (cat) {
            AppendMenuW(m, MF_STRING, kCmdPet, cat == MP_PETS_MIMIMO ? L"摸摸米米莫" : L"摸摸莫莫");
            AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
        }
        const bool out = anyOut();
        AppendMenuW(m, MF_STRING | (out ? 0u : MF_GRAYED), kCmdHome, L"叫牠們回家\tCtrl+Alt+Shift+M");
        AppendMenuW(m, MF_STRING | (out ? MF_GRAYED : 0u), kCmdOut, L"叫牠們出門\tCtrl+Alt+Shift+M");
        AppendMenuW(m, MF_STRING | (settings_.quiet ? MF_CHECKED : 0u), kCmdQuiet, L"安靜模式（一小時）");
        AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(sub, MF_STRING | (settings_.typingSeat == MP_DESK_TYPING_SPLIT ? MF_CHECKED : 0u), kCmdWalk, L"打字時陪走");
        AppendMenuW(sub, MF_STRING | (loginRegistered() ? MF_CHECKED : 0u), kCmdLogin, L"登入時開啟貓窩");
        AppendMenuW(sub, MF_SEPARATOR, 0, nullptr);
        // FINAL.md §11.1: off by default; asked in the companion app's guide, changeable here too.
        AppendMenuW(sub, MF_STRING | (settings_.activity.on ? MF_CHECKED : 0u), kCmdActivity, L"記錄時間花在哪裡");
        AppendMenuW(sub, MF_STRING | (settings_.activity.paused ? MF_CHECKED : 0u) | (settings_.activity.on ? 0u : MF_GRAYED),
                    kCmdActivityPause, L"暫停記錄");
        AppendMenuW(sub, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(sub, MF_STRING, kCmdOff, L"關閉桌面貓");
        AppendMenuW(m, MF_POPUP, reinterpret_cast<UINT_PTR>(sub), L"設定");
        if (!cat) {
            AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
            AppendMenuW(m, MF_STRING, kCmdQuit, L"結束貓窩");
        }
        SetForegroundWindow(control_);
        const UINT command = static_cast<UINT>(
            TrackPopupMenuEx(m, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY, at.x, at.y, control_, nullptr));
        PostMessageW(control_, WM_NULL, 0, 0);
        DestroyMenu(m); // and its submenu
        perform(command, cat);
    }

    void perform(UINT command, std::uint32_t cat) {
        const std::uint64_t now = monoMs();
        switch (command) {
        case kCmdPet:
            loop_->addEvent(MP_DESK_EV_PET, cat);
            step(now);
            break;
        case kCmdHome: setOut(false); break;
        case kCmdOut: setOut(true); break;
        case kCmdQuiet:
            settings_.quiet = !settings_.quiet;
            quietUntil_ = settings_.quiet ? now + kQuietMs : 0;
            loop_->setSettings(deskClock(now), coreSettings(settings_));
            step(now);
            break;
        case kCmdWalk:
            writeSetting(L"TypingSeat", settings_.typingSeat == MP_DESK_TYPING_SPLIT ? 1u : 0u);
            applySettings(readSettings());
            break;
        case kCmdLogin:
            if (loginRegistered()) unregisterLogin();
            else registerLogin(exePath());
            break;
        case kCmdOff:
            // Off: no desk pets and no login item; the cats stay in the candidate window.
            writeSetting(L"DeskPets", 0);
            unregisterLogin();
            PostQuitMessage(0);
            break;
        case kCmdQuit: PostQuitMessage(0); break;
        case kCmdActivity:
            if (!settings_.activity.on &&
                MessageBoxW(control_,
                            L"貓窩會在這台電腦上記下每一天、每一類（寫作、程式、設計、瀏覽、通訊、影音、遊戲、學習、辦公、其他）"
                            L"用了幾分鐘。\n\n只存類別和分鐘數：不記視窗標題或網址，App 的名稱只在記憶體裡拿來分類，"
                            L"不寫進檔案，也不上傳。隨時可以在這裡暫停或關掉。",
                            L"記錄時間花在哪裡", MB_OKCANCEL | MB_ICONINFORMATION | MB_SETFOREGROUND) != IDOK)
                break;
            writeSetting(L"ActivityStats", settings_.activity.on ? 0u : 1u);
            applySettings(readSettings());
            break;
        case kCmdActivityPause:
            writeSetting(L"ActivityPaused", settings_.activity.paused ? 0u : 1u);
            applySettings(readSettings());
            break;
        default: break;
        }
    }

    HINSTANCE instance_;
    HWND control_ = nullptr;
    HWND cats_[2] = {nullptr, nullptr};
    HDC memDC_ = nullptr;
    HBITMAP dib_[2] = {nullptr, nullptr};
    void *bits_[2] = {nullptr, nullptr};
    int dibW_[2] = {0, 0}, dibH_[2] = {0, 0};
    HWINEVENTHOOK hooks_[4] = {};
    HWINEVENTHOOK locationHook_ = nullptr;
    DWORD locationPid_ = 0;
    std::uint64_t locationAt_ = 0;
    HANDLE settingsEvent_ = nullptr;
    HKEY settingsKey_ = nullptr;
    UINT taskbarCreated_ = 0;
    bool trayAdded_ = false;

    DeskSettings settings_;
    pulse::Server server_;
    PetLedger petLedger_;
    std::uint64_t petMerges_ = 0;
    std::unique_ptr<DeskLoop> loop_;
    WicImages images_;
    Frame frames_[2];
    Gait gaits_[2] = {Gait(walkCycleLength(0)), Gait(walkCycleLength(1))};
    Dwell dwell_;
    ForegroundWatcher watcher_;
    std::uint64_t nowPublished_ = 0, activityTickAt_ = 0;
    bool activityProbeOn_ = false;
    std::uint32_t userState_ = MP_DESK_USER_PRESENT;
    Snapshot snapshot_;
    std::uint64_t measuredAt_ = 0, quietUntil_ = 0, fadeSince_[2] = {0, 0};
    std::uint32_t unitDpi_ = 96, sampleMs_ = 0;
    DWORD lastInputTick_ = 0;
    bool hidden_ = false, framesOn_ = false, probeOn_ = false;
    int eco_ = -1;
    DeskCounters deskCounters_;
    bool deskCountersDirty_ = false;
    std::uint64_t deskCountersFlushedAt_ = 0;
};

// --- --smoke ----------------------------------------------------------------------------

void attachConsole() {
    if (AttachConsole(ATTACH_PARENT_PROCESS) || AllocConsole()) {
        FILE *f = nullptr;
        freopen_s(&f, "CONOUT$", "w", stdout);
        freopen_s(&f, "CONOUT$", "w", stderr);
    }
}

DWORD WINAPI secondDesk(void *result) {
    pulse::Server second(pulse::kSmokeNames);
    *static_cast<pulse::Server::Open *>(result) = second.open(144);
    return 0;
}

// The real channel in this session, under the smoke's own names (a running desk is not disturbed).
bool channelRoundTrip(std::string &log) {
    bool ok = true;
    const auto check = [&](bool good, const char *what) {
        log += good ? "ok   " : "FAIL ";
        log += what;
        log += '\n';
        ok = ok && good;
    };
    pulse::Server server(pulse::kSmokeNames);
    check(server.open(144) == pulse::Server::Open::Ok, "the desk creates the private namespace, the slot and the event");
    // Another thread stands in for another process: the first desk's thread owns the instance mutex.
    pulse::Server::Open second = pulse::Server::Open::Failed;
    if (HANDLE thread = CreateThread(nullptr, 0, &secondDesk, &second, 0, nullptr)) {
        WaitForSingleObject(thread, 5000);
        CloseHandle(thread);
    }
    check(second == pulse::Server::Open::AlreadyRunning, "a second desk sees the first and leaves");
    pulse::Client client(pulse::kSmokeNames);
    client.prepare(!appContainerProcess());
    pulse::Input in;
    in.keys = 1;
    in.composing = true;
    in.haveOrigin = true;
    in.originX = 451;
    in.originY = 477;
    in.lineHeight = 33;
    check(client.send(in), "the input method's side opens the channel and writes a pulse");
    check(WaitForSingleObject(server.signal(), 1000) == WAIT_OBJECT_0, "the desk's event is set");
    std::uint8_t bytes[MP_PULSE_BYTES];
    MPPulse p{};
    check(server.take(GetTickCount64(), bytes) == pulse::Read::Fresh && mp_pulse_decode(bytes, sizeof bytes, &p) == MP_PULSE_OK &&
              p.keys == 1 && p.origin_x == 37 && p.origin_y == 39,
          "the desk reads it: one key, the origin on the 8 pt grid");
    check(client.glassCats() == (MP_PETS_MIMIMO | MP_PETS_MOMO), "both cats on the candidate glass while none is out");
    server.setDeskCats(MP_PETS_MIMIMO);
    check(client.glassCats() == MP_PETS_MOMO, "Mimimo out on the desk: the candidate window seats only Momo");
    MPActivityCurrent now{};
    now.state = MP_ACTIVITY_NOW_ACTIVE;
    now.category = MP_ACTIVITY_CAT_WRITE;
    server.setNow(now);
    MPActivityCurrent read{};
    check(pulse::now(server.section(), read) && read.state == MP_ACTIVITY_NOW_ACTIVE && read.category == MP_ACTIVITY_CAT_WRITE,
          "the current category reaches the section");
    return ok;
}

int smoke() {
    attachConsole();
    const HRESULT com = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    bool passed = true;
    {
        WicImages images(assetDirectory());
        const bool art = images.valid() && fileExists(images.directory() + L"\\momo_idle.png");
        std::printf("== smoke (%s)\n", art ? "the clay PNGs" : "no Assets folder found: ovals");
        const SmokeResult result = runSmoke(art ? &images : nullptr);
        std::fputs(result.log.c_str(), stdout);
        std::string log;
        const bool channel = channelRoundTrip(log);
        std::fputs(log.c_str(), stdout);
        passed = result.passed && channel;
    }
    if (SUCCEEDED(com)) CoUninitialize();
    std::puts(passed ? "smoke passed" : "smoke FAILED");
    std::fflush(stdout);
    return passed ? 0 : 1;
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    int argc = 0;
    LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    std::wstring arg = argv && argc > 1 ? argv[1] : L"";
    if (argv) LocalFree(argv);
    if (arg == L"--register-login") return registerLogin(exePath()) ? 0 : 1;
    if (arg == L"--unregister-login") return unregisterLogin() ? 0 : 1;
    if (arg == L"--smoke") return smoke();
    if (!arg.empty()) return 2;

    const HRESULT com = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    int code = 0;
    {
        App app(instance);
        code = app.run();
    }
    if (SUCCEEDED(com)) CoUninitialize();
    return code;
}
