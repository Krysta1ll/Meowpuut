#pragma once

#include "../Core/MeowpuutCore.h"

// Ctrl+left Alt+Shift+Q. Requiring left Alt avoids consuming AltGr input;
// Windows keys and any additional right Alt modifier are passed through.
inline bool pinyinScriptShortcut(unsigned int key, bool control, bool shift,
                                 bool leftAlt, bool rightAlt, bool windows) {
    return key == 'Q' && control && shift && leftAlt && !rightAlt && !windows;
}

// Returns whether candidate ordering must be rebuilt. This changes neither
// the raw composition nor the session's pending commit buffer.
inline bool applyPinyinScript(MPNSession *session, bool current, bool desired) {
    if (!session || current == desired) return false;
    mpn_set_simplified(session, desired);
    return true;
}
