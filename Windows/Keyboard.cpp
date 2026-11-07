#include "Keyboard.h"
#include <string_view>

namespace meowpuut {
namespace {
enum class Kind { Pass, Core, Choose, LiteralSpace };
struct Action { Kind kind = Kind::Pass; std::int32_t value = 0; };

// Mirrors the core's non-mutating key acceptance rules. Do not decode while
// answering TSF's OnTestKeyDown: it can be called repeatedly for one event.
bool acceptsCoreKey(std::string_view input, InputMode mode, std::int32_t key) {
    if (key == 0xff55 || key == 0xff56 || key == 0xff1b || key == 0xff08 || key == 0xff0d)
        return !input.empty();
    if (key < 32 || key > 126) return false;
    const auto ch = static_cast<char>(key);
    if (mode == InputMode::Pinyin) {
        if (ch == ' ' && !input.empty()) return true;
        const bool boundary = (ch >= '1' && ch <= '5') || ch == '\'';
        if (!(ch >= 'a' && ch <= 'z') && !boundary) return false;
        if (boundary && (input.empty() || (input.back() >= '1' && input.back() <= '5') || input.back() == '\'')) return false;
        return true;
    }
    constexpr std::string_view keys = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'";
    const bool boundary = std::string_view(" 6347'").find(ch) != std::string_view::npos;
    return keys.find(ch) != std::string_view::npos && !(boundary && input.empty());
}

Action actionFor(const Session &session, InputMode mode, const KeyEvent &event) {
    if (event.control || event.alt || event.win) return {};
    const auto input = session.input();
    const auto vk = event.vk;
    const auto core = [&](std::int32_t key) -> Action {
        return acceptsCoreKey(input, mode, key) ? Action{Kind::Core, key} : Action{};
    };
    if (!input.empty()) {
        if (!event.shift) {
            switch (vk) {
            case 0x21: return core(0xff55);
            case 0x22: return core(0xff56);
            case 0x1b: return core(0xff1b);
            case 0x08: return core(0xff08);
            case 0x0d: return core(0xff0d);
            case 0x20: if (mode == InputMode::Pinyin) return {Kind::LiteralSpace, 0};
                       break;
            default: break;
            }
        }
        if (mode == InputMode::Pinyin && event.shift) {
            switch (vk) {
            case 0x20: return core('1');
            case 0x36: return core('2');
            case 0x33: return core('3');
            case 0x34: return core('4');
            case 0x37: return core('5');
            default: return {};
            }
        }
        if (vk >= 0x31 && vk <= (mode == InputMode::Pinyin ? 0x38u : 0x37u) &&
            ((mode == InputMode::Pinyin && !event.shift) ||
             (mode == InputMode::Zhuyin && event.shift))) {
            const auto index = vk - 0x31;
            return session.canChooseOnPage(index) ? Action{Kind::Choose, static_cast<std::int32_t>(index)} : Action{};
        }
    }
    if (event.shift || event.printable < 0x20 || event.printable > 0x7e) return {};
    return core(event.printable);
}
}

bool canHandleKey(const Session &session, InputMode mode, const KeyEvent &event) {
    return actionFor(session, mode, event).kind != Kind::Pass;
}
bool handleKey(Session &session, InputMode mode, const KeyEvent &event) {
    const auto action = actionFor(session, mode, event);
    switch (action.kind) {
    case Kind::Core: return session.key(action.value);
    case Kind::Choose: return session.chooseOnPage(static_cast<std::size_t>(action.value));
    case Kind::LiteralSpace: return session.commitLiteralWithSpace();
    case Kind::Pass: return false;
    }
    return false;
}
}
