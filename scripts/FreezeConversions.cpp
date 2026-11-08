// Optional snapshot-maintenance tool; normal builds use the pinned table.
// Build with ICU 78.2 and keep its Unicode license and rule notices.
#include <unicode/translit.h>
#include <unicode/unistr.h>
#include <unicode/uversion.h>
#include <iostream>
#include <memory>
#include <string>

int main(int argc, char **argv) {
    UVersionInfo version;
    u_getVersion(version);
    if (version[0] != 78 || version[1] != 2) {
        std::cerr << "The normalization snapshot requires ICU 78.2.\n";
        return 2;
    }
    if (argc == 2 && std::string(argv[1]) == "--version") {
        std::cout << "ICU 78.2\n";
        return 0;
    }
    if (argc > 2 || (argc == 2 && std::string(argv[1]) != "--traditional")) return 2;
    UErrorCode error = U_ZERO_ERROR;
    const auto direction = argc == 2 ? UTRANS_REVERSE : UTRANS_FORWARD;
    auto transform = std::unique_ptr<icu::Transliterator>(icu::Transliterator::createInstance(
        icu::UnicodeString::fromUTF8("Traditional-Simplified"), direction, error));
    if (U_FAILURE(error) || !transform) return 2;
    std::string line;
    while (std::getline(std::cin, line)) {
        auto value = icu::UnicodeString::fromUTF8(line);
        transform->transliterate(value);
        std::string result;
        value.toUTF8String(result);
        std::cout << result << '\n';
    }
    return std::cin.bad() || !std::cout ? 1 : 0;
}
