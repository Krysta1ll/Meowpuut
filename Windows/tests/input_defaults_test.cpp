#include "../InputDefaults.h"
#include <iostream>
#include <stdexcept>

using namespace meowpuut;

static void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

int main() {
    const Start zhuyinTraditional{StartMode::Zhuyin, false};
    const Start pinyinSimplified{StartMode::Pinyin, true};
    const Start pinyinTraditional{StartMode::Pinyin, false};
    struct Row { const char *region; std::vector<std::string> languages; Start expected; };
    const std::vector<Row> table = {
        {"TW", {"zh-Hant-TW"}, zhuyinTraditional},
        {"TW", {"en-US"}, zhuyinTraditional},
        {"US", {"zh-Hant-TW", "en-US"}, zhuyinTraditional},
        {"US", {"zh-TW"}, zhuyinTraditional},
        {"CN", {"zh-Hans-CN"}, pinyinSimplified},
        {"SG", {"en-SG"}, pinyinSimplified},
        {"MY", {"ms-MY"}, pinyinSimplified},
        {"US", {"en-US", "zh-Hans-US"}, pinyinSimplified},
        {"JP", {"zh-CN"}, pinyinSimplified},
        {"HK", {"zh-Hant-HK"}, pinyinTraditional},
        {"MO", {"en"}, pinyinTraditional},
        {"hk", {"en"}, pinyinTraditional},
        {"US", {"en-US", "zh-Hant"}, pinyinTraditional},
        {"JP", {"ja-JP", "zh-Hant-HK"}, pinyinTraditional},
        {"US", {"en-US"}, pinyinSimplified},
        {"", {}, pinyinSimplified},
        {"", {"zh"}, pinyinSimplified},
        {"FR", {"fr-FR", "zh-Hant", "zh-Hans"}, pinyinSimplified},
        {"FR", {"fr-FR", "zh-Hant", "zh"}, pinyinTraditional},
        {"FR", {"fr_FR", "ZH_hant"}, pinyinTraditional},
    };
    int index = 0;
    for (const auto &row : table) {
        require(chooseStart(row.region, row.languages) == row.expected, "start for row " + std::to_string(index));
        ++index;
    }

    const auto existing = firstRun(true, false, "TW", {"zh-Hant-TW"});
    require(existing.mixedJapanese && !existing.storeScript, "existing install keeps mixed Japanese on and its script");
    const auto fresh = firstRun(false, false, "TW", {});
    require(!fresh.mixedJapanese && fresh.storeScript && fresh.start == zhuyinTraditional, "fresh install starts off with the regional script");
    const auto chosen = firstRun(false, true, "TW", {});
    require(!chosen.mixedJapanese && !chosen.storeScript, "a stored script is never replaced");
    std::cout << "input_defaults_test passed\n";
}
