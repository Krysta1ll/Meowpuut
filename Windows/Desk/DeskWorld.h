#pragma once
// What the desk-pet process measures of the desktop (PETS.md §2.1–§2.4,
// FINAL.md §4.3), as plain data. The Win32 shell fills a Snapshot from
// EnumWindows (front to back), DwmGetWindowAttribute (DWMWA_CLOAKED,
// DWMWA_EXTENDED_FRAME_BOUNDS), the monitors, the taskbar and the tray icon;
// buildWorld turns it into the grounds, window sides, doors and walls that
// mp_desk_step takes. Portable C++17, so the rules are tested on the host.
//
// Only rectangles and window handles: never a window title, a class name, an
// executable or a process name.
//
// Units. The core works in desk points; on Windows a desk point is a physical
// pixel × 96 / unitDpi, where unitDpi is the primary monitor's DPI (the same
// unitDpi the pulse channel publishes to the input method). Every physical
// coordinate (Per-Monitor V2) maps to it with one scale, so the screens stay
// one flat plane; on a monitor with another DPI the cats are drawn at that
// monitor's scale (DeskArt) and only their spacing is slightly off.
#include "../../Engine/crates/meowpuut-ffi/include/meowpuut.h"

#include <cstdint>
#include <vector>

namespace meowpuut::desk {

struct RectPx {
    std::int32_t left = 0, top = 0, right = 0, bottom = 0;
    std::int32_t width() const { return right - left; }
    std::int32_t height() const { return bottom - top; }
    bool empty() const { return right <= left || bottom <= top; }
};

// One top-level window as EnumWindows lists it.
struct WindowFacts {
    std::uint32_t id = 0;        // the window handle's low 32 bits (user handles fit in them)
    RectPx bounds;               // DWMWA_EXTENDED_FRAME_BOUNDS: the visible frame, without the resize border
    bool visible = true;         // IsWindowVisible
    bool minimized = false;      // IsIconic
    bool cloaked = false;        // DWMWA_CLOAKED: another virtual desktop, a suspended app, a hidden shell surface
    bool toolWindow = false;     // WS_EX_TOOLWINDOW
    bool appWindow = false;      // WS_EX_APPWINDOW: a tool window that still has a taskbar button
    bool clickThrough = false;   // WS_EX_LAYERED | WS_EX_TRANSPARENT: an overlay that takes no input
    bool ours = false;           // this process's windows
    bool shell = false;          // the shell's desktop background (the shell window's process, covering a monitor)
};

struct MonitorFacts {
    RectPx monitor, work;        // GetMonitorInfo rcMonitor / rcWork
    std::uint32_t dpi = 96;      // GetDpiForMonitor, MDT_EFFECTIVE_DPI
    bool primary = false;
};

// SHAppBarMessage ABM_GETTASKBARPOS / ABM_GETSTATE: the primary taskbar.
struct TaskbarFacts {
    bool known = false;
    RectPx rect;
    std::uint32_t edge = 3;      // ABE_LEFT 0, ABE_TOP 1, ABE_RIGHT 2, ABE_BOTTOM 3
    bool autoHide = false;
};

struct Snapshot {
    std::vector<WindowFacts> windows;  // front to back (EnumWindows order)
    std::vector<MonitorFacts> monitors;
    TaskbarFacts taskbar;
    WindowFacts foreground;            // GetForegroundWindow (id 0: none)
    bool trayKnown = false;            // Shell_NotifyIconGetRect of our tray icon: the cat door
    RectPx tray;
    std::uint32_t unitDpi = 96;
    double catScale = 1.0;             // the size setting (1: medium, the core's default body boxes)
};

// A kept window in desk points, front to back.
struct Window {
    std::uint32_t id = 0;
    double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
};

struct World {
    std::vector<MPDeskGround> grounds;
    std::vector<MPDeskSide> sides;
    MPDeskDoors doors{};
    MPDeskWalls walls{};
    std::vector<Window> windows;       // what the edges came from (the caret's window); not sent to the core
};

// Rules from PETS.md §2.1, in desk points at catScale 1.
inline constexpr double kCaptionLeft = 12;   // a window's left corner is not a seat
inline constexpr double kCaptionRight = 146; // nor its three caption buttons
inline constexpr double kMinEdge = 56;       // shorter edge pieces are not grounds
inline constexpr double kMinBase = 40;       // shorter taskbar or floor pieces are not grounds

// The window filter: visible, not minimized, not cloaked, not a tool window
// (unless it has a taskbar button), not a click-through overlay, not ours, not
// the desktop background, and not empty.
bool keep(const WindowFacts &w);
World buildWorld(const Snapshot &s);
// The part the core sees (grounds, sides, doors, walls).
bool sameWorld(const World &a, const World &b);
// The front-most kept window containing the point (desk points); 0: none.
std::uint32_t ownerAt(const World &w, double x, double y);
// The window you are using fills its whole monitor (taskbar included): a
// borderless full-screen game or video, F11. Ours and the desktop never count.
bool coversMonitor(const Snapshot &s);
// SHQueryUserNotificationState says a full-screen program runs: QUNS_BUSY (2),
// QUNS_RUNNING_D3D_FULL_SCREEN (3) or QUNS_PRESENTATION_MODE (4).
bool fullScreenState(int quns);

} // namespace meowpuut::desk
