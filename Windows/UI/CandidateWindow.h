#pragma once
// Win32 candidate window: a layered, non-activating popup drawn with
// Direct2D, DirectWrite and WIC. It replays the Scene built from
// CandidateLayout, the same list the SVG snapshots and the unit tests use.
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

#include <functional>
#include <memory>
#include <string>

namespace meowpuut::ui {

enum class ToneSetting { System, Light, Dark };

struct CandidateWindowOptions {
    GlassShape shape = GlassShape::Capsule;
    Palette palette = Palette::Rose;
    ToneSetting tone = ToneSetting::System;
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

    // False when Direct2D, DirectWrite, WIC or the window could not be
    // created; every other call is then a no-op.
    bool valid() const;

    // Replaces the content. A visible window redraws once, on the next trip
    // through the message loop or on the next showNear(), whichever comes first.
    void update(const CandidateViewModel &model);
    // Draws and places the window under the caret, above it when there is no
    // room below, inside the monitor work area. `caret` is in physical screen
    // pixels (per-monitor DPI). An empty page hides the window.
    void showNear(const RECT &caret);
    void hide();
    bool visible() const;

    void setOptions(const CandidateWindowOptions &options);
    const CandidateWindowOptions &options() const;
    // Re-reads light/dark, transparency and high contrast.
    // The window does this itself on WM_SETTINGCHANGE; call it when the host
    // swallows broadcasts.
    void refreshSystemTheme();

    HWND hwnd() const;
    // Title of the outfit in use, or empty.
    std::wstring activeOutfitTitle() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace meowpuut::ui
#endif
