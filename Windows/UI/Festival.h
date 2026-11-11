#pragma once
// Festival outfits (docs/FESTIVALS.md). The manifest lists outfits by local
// date range; while one is active its overlays are drawn on the same canvas as
// the cat sprite and may replace expression frames and the selection colour.
// The shipped manifest is usually empty, and then nothing changes.
#include "CandidateLayout.h"
#include "Theme.h"

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace meowpuut::ui {

struct LocalDate {
    int year = 0, month = 0, day = 0;
    int key() const { return year * 10000 + month * 100 + day; }
};

bool parseLocalDate(std::string_view text, LocalDate &out); // "YYYY-MM-DD"
std::optional<Color> parseHexColor(std::string_view text);  // "#RRGGBB"

struct CatOutfit {
    std::string front;      // file name inside the outfit directory, or empty
    std::string back;
    std::string frames[3];  // replacements indexed by CatFace; empty keeps the original
    bool empty() const {
        return front.empty() && back.empty() && frames[0].empty() && frames[1].empty() && frames[2].empty();
    }
};

struct Outfit {
    std::string id;         // also the directory name under festivals/
    std::string titleHant, titleHans;
    LocalDate start, end;   // inclusive, device-local dates
    CatOutfit mimimo, momo;
    std::optional<Color> pill;
};

struct FestivalManifest {
    int version = 1;
    std::vector<Outfit> outfits;
};

// Malformed JSON, an unknown version or invalid entries yield fewer (or no)
// outfits; `error` describes the first problem when given.
FestivalManifest parseFestivalManifest(std::string_view json, std::string *error = nullptr);

// The first outfit in list order whose range contains `today`.
const Outfit *activeOutfit(const FestivalManifest &manifest, LocalDate today);

// Selection pill in the outfit colour, adjusted for the appearance.
void applyOutfitPill(Theme &theme, Color pill);

} // namespace meowpuut::ui
