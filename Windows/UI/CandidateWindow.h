#pragma once
// Win32 candidate window: a layered, non-activating popup drawn with
// Direct2D, DirectWrite and WIC. It replays the Scene built from
// CandidateLayout, the same list the SVG snapshots and the unit tests use.
//
// Microsoft's IME requirements for it ("IME requirements", UI design guidelines):
// a top-level popup owned by the window being typed in (setOwner), topmost,
// never activated (WS_EX_NOACTIVATE, MA_NOACTIVATE, PA_NOACTIVATE), no taskbar
// button (WS_EX_TOOLWINDOW), and EVENT_OBJECT_IME_SHOW / _HIDE / _CHANGE raised
// with NotifyWinEvent when it appears, disappears, moves or changes size.
//
// Threading: create, use and destroy the window on one thread that runs a
// message loop (the TSF UI thread in a text service, the main thread in the
// preview app).
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include "CandidateLayout.h"
#include "Theme.h"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace meowpuut::ui {

enum class ToneSetting { System, Light, Dark };

CandidateTextSize loadCandidateTextSize();
void saveCandidateTextSize(CandidateTextSize size);

// The look of the window: the glass (the default, DESIGN.md) or 字景, the strip of the app's place with the pixel type
// (FINAL.md §3, GlyphStrip.h). HKCU\Software\Meowpuut, DWORD CandidateSkin: 0 glass (also when missing), 1 glyph.
// The companion writes it; the text service reads it when a composition starts, so a change shows at the next one.
enum class CandidateSkin { Glass = 0, Glyph = 1 };
CandidateSkin loadCandidateSkin();

struct CandidateWindowOptions {
    GlassShape shape = GlassShape::Capsule;
    Palette palette = Palette::Rose;
    ToneSetting tone = ToneSetting::System;
    CandidateTextSize textSize = CandidateTextSize::Standard;
    CandidateSkin skin = CandidateSkin::Glass;
    bool festivalOutfits = true;  // docs/FESTIVALS.md; off shows the plain cats
    bool simplified = false;      // picks Microsoft YaHei UI and zh-CN shaping
    std::wstring assetDirectory;  // empty: "Assets" next to this module
};

// Called from the window's message handler, on the window's thread.
struct CandidateWindowCallbacks {
    std::function<void(std::size_t index)> choose;  // index on the current page
    std::function<void(int direction)> page;        // -1 previous, +1 next (buttons and mouse wheel)
    std::function<void()> toggleExpanded;
    std::function<void()> toggleScript;             // 莫莫 was petted five times: switch Traditional/Simplified
};

class CandidateWindow {
public:
    // `instance` is the module that owns the window class: the DLL's handle in
    // a text service. The class is unregistered when the last window of this
    // module is destroyed, so destroy every window before the DLL unloads.
    CandidateWindow(HINSTANCE instance, CandidateWindowOptions options, CandidateWindowCallbacks callbacks);
    ~CandidateWindow();
    CandidateWindow(const CandidateWindow &) = delete;
    CandidateWindow &operator=(const CandidateWindow &) = delete;

    // False when Direct2D, DirectWrite, WIC or the window class could not be
    // set up; every other call is then a no-op.
    bool valid() const;

    // The window the candidates belong to: ITfContextView::GetWnd, or
    // GetFocus when the view has none (Microsoft, "IME requirements", Owned
    // window). Its top-level ancestor owns the popup. Ownership cannot be
    // transferred after creation ("Window Features", Owned Windows), so a new
    // owner replaces the popup; the next showNear() shows it again. A window of
    // another thread, or none, leaves the popup unowned: a cross-thread owner
    // would attach the two threads' input queues. Inside one of the popup's own
    // callbacks the change waits for the next call.
    void setOwner(HWND window);

    // Replaces the content. A visible window redraws once, on the next trip
    // through the message loop or on the next showNear(), whichever comes first.
    void update(const CandidateViewModel &model);
    // Draws and places the window under the caret, above it when there is no
    // room below, inside the monitor work area. `caret` is in physical screen
    // pixels (per-monitor DPI). An empty page hides the window.
    // While the foreground window fills its monitor without a frame (a
    // borderless full-screen game or video), the bar is the plain single row:
    // no cats, no caustics, opaque, nothing animated.
    void showNear(const RECT &caret);
    void hide();
    bool visible() const;

    void setOptions(const CandidateWindowOptions &options);
    // 字景 only: the characters the ledger has committed (PetLedger::committedGraphemes), which is how far the
    // journey has gone and so which place the strip shows. Read it when a composition starts, not on a key.
    void setJourney(std::int64_t committedCharacters);
    // True while the last frame was the 字景 strip (it falls back to the glass when the pixel fonts are not next to
    // the DLL, and in a high-contrast theme, which draws the plain system-colour bar).
    bool glyphShown() const;
    const CandidateWindowOptions &options() const;
    // Re-reads light/dark, transparency and high contrast.
    // The window does this itself on WM_SETTINGCHANGE; call it when the host
    // swallows broadcasts.
    void refreshSystemTheme();

    HWND hwnd() const; // null after its owner was destroyed, until the next showNear()
    // Title of the outfit in use, or empty.
    std::wstring activeOutfitTitle() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace meowpuut::ui
#endif
