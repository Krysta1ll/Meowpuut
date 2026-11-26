// Host test of the word-count ledger: grapheme counting, the local clock math
// and the shared file (PetLedger.cpp against the Rust static library).
#include "../../Engine/crates/meowpuut-ffi/include/meowpuut.h"
#include "../PetLedger.h"

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <unistd.h>
#include <vector>

using namespace meowpuut;
namespace fs = std::filesystem;

static int failures = 0;
#define CHECK(cond) \
    do { \
        if (!(cond)) { std::fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); ++failures; } \
    } while (0)

static std::string readAll(const fs::path &p) {
    std::ifstream in(p, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(in), {});
}

// The format version byte of a state file (1, or 2 once a day has a place).
static int versionOf(const fs::path &p) {
    const std::string bytes = readAll(p);
    return bytes.size() > 2 ? static_cast<unsigned char>(bytes[2]) : -1;
}

static LedgerClock at(std::uint64_t ms, std::uint32_t day = 20000, std::uint32_t minute = 600) {
    LedgerClock c;
    c.monoMs = ms;
    c.local = {day, minute};
    return c;
}

int main() {
    // Graphemes.
    CHECK(countGraphemes("你好") == 2);
    CHECK(countGraphemes("😊👍🏽") == 2);
    CHECK(countGraphemes("👨‍👩‍👧") == 1);
    CHECK(countGraphemes("🇯🇵🇰🇷") == 2);
    CHECK(countGraphemes("e\xCC\x81") == 1);
    CHECK(countGraphemes("ニャー") == 3);
    CHECK(countGraphemes("ok") == 2);
    CHECK(countGraphemes("") == 0);

    // Local day and minute. 2023-11-14 22:13:20 UTC = 1700000000.
    LocalClock utc = localClockFromUtc(1'700'000'000, 0);
    CHECK(utc.day == 19675 && utc.minute == 22 * 60 + 13);
    LocalClock tpe = localClockFromUtc(1'700'000'000, 8 * 3600); // UTC+8: next day 06:13
    CHECK(tpe.day == 19676 && tpe.minute == 6 * 60 + 13);
    LocalClock west = localClockFromUtc(3600, -5 * 3600); // 1970-01-01 01:00 UTC at UTC-5: before the epoch day
    CHECK(west.day == 0 && west.minute == 20 * 60);

    char tmpl[] = "/tmp/meowpuut-ledger-XXXXXX";
    const fs::path root = mkdtemp(tmpl);
    const fs::path dir = root / "a";
    const fs::path file = dir / PetLedger::kFileName;

    {
        PetLedger ledger(dir);
        ledger.committed(countGraphemes("你好"), at(1000));
        ledger.committed(3, at(2000));
        CHECK(!fs::exists(file)); // no file before the first flush
        CHECK(ledger.flush());
    }
    CHECK(fs::exists(file));
    CHECK(fs::file_size(file) == MP_PETS_STATE_BYTES);
    CHECK(readAll(file).find("你好") == std::string::npos);
    {
        PetLedger reload(dir);
        CHECK(reload.committedGraphemes() == 5);
        CHECK(reload.dayCount() == 1);
#ifdef MEOWPUUT_GLYPH
        // The flush recorded the day's place: 5 characters in, the cats are still at the sea (place 1).
        CHECK(mp_glyph_place_of_characters(5) == 1);
        CHECK(reload.dayPlaces() == std::vector<std::uint8_t>{1});
        CHECK(versionOf(file) == 2);
#else
        CHECK(reload.dayPlaces() == std::vector<std::uint8_t>{MP_PETS_PLACE_UNKNOWN});
        CHECK(versionOf(file) == 1);
#endif
    }

    // Two processes' ledgers on one directory: both increments are kept.
    {
        PetLedger a(dir), b(dir);
        a.committed(10, at(1000));
        b.committed(100, at(1000));
        CHECK(a.flush());
        CHECK(b.flush());
        CHECK(a.flush());
        PetLedger check(dir);
        CHECK(check.committedGraphemes() == 115);
    }

    // The 60 s batching: a commit alone does not write.
    {
        const fs::path d2 = root / "batch";
        PetLedger a(d2);
        a.committed(1, at(5000));
        a.committed(1, at(5000 + PetLedger::kFlushIntervalMs - 1));
        CHECK(!fs::exists(d2 / PetLedger::kFileName));
        a.committed(1, at(5000 + PetLedger::kFlushIntervalMs));
        CHECK(fs::exists(d2 / PetLedger::kFileName));
        PetLedger check(d2);
        CHECK(check.committedGraphemes() == 3);
    }

    // Clear: zero, and an older ledger flushing afterwards does not bring the numbers back.
    {
        PetLedger old(dir);
        old.committed(1, at(1000)); // loaded before the clear
        PetLedger cleaner(dir);
        cleaner.committedGraphemes();
        cleaner.clear();
        PetLedger zero(dir);
        CHECK(zero.committedGraphemes() == 0);
        CHECK(zero.dayCount() == 0);
        old.flush();
        PetLedger after(dir);
        CHECK(after.committedGraphemes() == 0);
        CHECK(after.dayCount() == 0);
    }

    // The place: on the day of the last commit, from the characters, only after typing, once per flush.
    {
        const fs::path d4 = root / "journey";
        const fs::path f4 = d4 / PetLedger::kFileName;
        PetLedger ledger(d4);
        ledger.touched(MP_PETS_TAP, MP_PETS_MIMIMO, at(1000, 20100));
        CHECK(ledger.flush());
        CHECK(versionOf(f4) == 1); // a touch on the desk is no journey
        ledger.committed(2600, at(2000, 20101, 1300));
        CHECK(ledger.flush()); // e.g. Deactivate, the next morning: still the evening's day
        CHECK(ledger.flush());
        PetLedger check(d4);
        const std::vector<std::uint8_t> places = check.dayPlaces();
        CHECK(places.size() == 2);
#ifdef MEOWPUUT_GLYPH
        CHECK(places.size() == 2 && places[0] == MP_PETS_PLACE_UNKNOWN);
        CHECK(places.size() == 2 && places[1] == mp_glyph_place_of_characters(2600));
        CHECK(versionOf(f4) == 2);
#endif
        // Clearing forgets the places, and records none for what was typed before it.
        ledger.committed(5, at(3000, 20101));
        ledger.clear();
        PetLedger zero(d4);
        CHECK(zero.dayPlaces().empty());
        CHECK(versionOf(f4) == 1);
    }

    // A disabled ledger does nothing.
    {
        const fs::path d3 = root / "off";
        PetLedger off(d3, false);
        off.committed(5, at(1000));
        off.flush();
        CHECK(!fs::exists(d3));
    }

    std::error_code ec;
    fs::remove_all(root, ec);
    if (failures) { std::fprintf(stderr, "%d check(s) failed\n", failures); return 1; }
    std::puts("pet_ledger_test passed");
    return 0;
}
