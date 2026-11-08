#pragma once

#include <cstddef>

// Windows virtual-key codes for unshifted US-layout punctuation. Shift and
// other modifiers are passed to the host by the TSF adapter.
constexpr wchar_t pinyinPunctuation(unsigned int key) {
    switch (key) {
    case 0xbc: return L'，'; // VK_OEM_COMMA
    case 0xbe: return L'。'; // VK_OEM_PERIOD
    case 0xba: return L'；'; // VK_OEM_1
    case 0xbf: return L'？'; // VK_OEM_2
    case 0xdb: return L'「'; // VK_OEM_4
    case 0xdd: return L'」'; // VK_OEM_6
    case 0xdc: return L'、'; // VK_OEM_5
    default: return 0;
    }
}

// The other digits remain literal input when no corresponding candidate is
// displayed, rather than reaching the editor ahead of an active composition.
constexpr bool pinyinDigitSelects(unsigned int key, std::size_t start, std::size_t count) {
    return key >= '1' && key <= '7' && start < count && key - '1' < count - start;
}

// These unmodified keys are literal text after a selection has cleared the
// reading, but must not overtake a pending asynchronous TSF insertion.
constexpr bool pinyinQueuedLiteral(unsigned int key) {
    return key == 0x20 || (key >= '0' && key <= '9');
}
