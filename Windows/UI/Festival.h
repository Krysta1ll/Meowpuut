#pragma once
// The festival wardrobe (docs/FESTIVALS.md), the same rules as
// Sources/Festivals.swift: a theme is worn by the calendar only, from
// leadDays before the festival's first day through its last day, in the
// device's own date. The manifest is Assets\festivals\festivals.json.
#include "CandidateLayout.h"
#include "Theme.h"

#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <vector>

namespace meowpuut::ui {

struct LocalDate {
    int year = 0, month = 0, day = 0;
    int key() const { return year * 10000 + month * 100 + day; }
};

bool parseLocalDate(std::string_view text, LocalDate &out); // "YYYY-MM-DD"
int dayNumber(LocalDate date);                              // days since 1970-01-01
std::optional<Color> parseHexColor(std::string_view text);  // "#RRGGBB"

struct FestivalInsets {
    float top = 0, left = 0, bottom = 0, right = 0;
};

// A dressed cat: logical size in points, feet as a fraction of the size from the top left.
struct FestivalSprite {
    std::string image; // Assets\festivals\<image>.png
    float width = 0, height = 0, anchorX = 0, anchorY = 0;
};

// The decoration around the glass; only the middle between the caps stretches.
struct FestivalTrim {
    std::string image;
    float width = 0, height = 0;
    FestivalInsets caps, safeText, rim;
};

// #RRGGBB: light and dark are the selected capsule's top and bottom; contrast replaces both.
struct FestivalColors {
    std::string accent;
    std::vector<std::string> light, dark;
    std::string contrast;
};

// A festival's own days in one year, inclusive.
struct FestivalDays {
    LocalDate first, main, last;
};

struct FestivalTheme {
    std::string id;
    std::string hant, hans, ja, en;
    FestivalSprite mimimo, momo;
    FestivalTrim trim;
    FestivalColors colors;
    std::vector<FestivalDays> days;
};

struct FestivalManifest {
    int leadDays = 7;
    std::vector<FestivalTheme> themes;
};

// What the person chose: off means the plain cats always; `skipped` are themes switched off.
struct FestivalWardrobe {
    bool on = true;
    std::set<std::string> skipped;
};

// A manifest that is not version 2 gives no themes; a theme that does not
// read is left out. `error` describes the first problem when given.
FestivalManifest parseFestivalManifest(std::string_view json, std::string *error = nullptr);

// The theme worn on `today`, from the date and the switches alone. Where
// several are worn: a festival on its main day; then one on its other own
// days (the one that began last, then the one that ends first); then one in
// its lead-up week (the one that begins soonest). Festivals that share a main
// day and stand on the same step take turns by day.
const FestivalTheme *wornTheme(const FestivalManifest &manifest, LocalDate today, const FestivalWardrobe &wardrobe);

// Themes whose worn range has begun at least once between `since` and `today`, in manifest order.
std::vector<const FestivalTheme *> metThemes(const FestivalManifest &manifest, LocalDate since, LocalDate today);

// The selected capsule in the theme's colours for the appearance (the
// manifest's light or dark pair). High contrast keeps the system's.
void applyFestivalPill(Theme &theme, const FestivalTheme &festival);

} // namespace meowpuut::ui
