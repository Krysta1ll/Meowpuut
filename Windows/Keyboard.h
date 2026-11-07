#pragma once
#include "Session.h"
#include <cstdint>

namespace meowpuut {
enum class InputMode { Pinyin, Zhuyin };

// vk is a Windows virtual-key code; printable is the ASCII character generated
// by the current keyboard layout, or zero if there is none. Modifiers describe
// the key event, not a global keyboard state sampled later.
struct KeyEvent {
    std::uint32_t vk;
    char printable;
    bool shift = false, control = false, alt = false, win = false;
};

// A side-effect-free query for TSF's OnTestKeyDown. The context's session and
// mode must not change between this query and the corresponding OnKeyDown.
bool canHandleKey(const Session &session, InputMode mode, const KeyEvent &event);

// Returns true only when the caller should consume this keydown. The caller
// must not call this for key-up, dead keys, or while handling an OS shortcut.
bool handleKey(Session &session, InputMode mode, const KeyEvent &event);
}
