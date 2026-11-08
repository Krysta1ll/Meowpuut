#pragma once

#include <windows.h>
#include <cstring>
#include <filesystem>
#include "MixedComposition.h"

// Windows ships SQLite as a system component. Load only from System32.
class WordEvidence {
public:
    WordEvidence() = default;
    WordEvidence(const WordEvidence &) = delete;
    WordEvidence &operator=(const WordEvidence &) = delete;
    ~WordEvidence() { close(); }

    void open(const std::filesystem::path &path) {
        close();
        library = LoadLibraryExW(L"winsqlite3.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
        if (!library) return;
        openDb = proc<decltype(openDb)>("sqlite3_open_v2");
        closeDb = proc<decltype(closeDb)>("sqlite3_close");
        prepare = proc<decltype(prepare)>("sqlite3_prepare_v2");
        finalize = proc<decltype(finalize)>("sqlite3_finalize");
        reset = proc<decltype(reset)>("sqlite3_reset");
        bind = proc<decltype(bind)>("sqlite3_bind_text");
        step = proc<decltype(step)>("sqlite3_step");
        text = proc<decltype(text)>("sqlite3_column_text");
        weight = proc<decltype(weight)>("sqlite3_column_int");
        if (!openDb || !closeDb || !prepare || !finalize || !reset || !bind || !step || !text || !weight ||
            openDb(path.u8string().c_str(), &db, 1, nullptr) != 0 ||
            prepare(db, "SELECT hant,weight,toned FROM lexicon WHERE reading=? ORDER BY weight DESC",
                    -1, &queries[0], nullptr) != 0) { close(); return; }
        // The NOT NULL V2 column can use the (reading,hans_weight) index.
        // Nullable transition databases fall back per row, while older
        // databases retain the original weight.
        bool hasSimplifiedWeight = false, simplifiedWeightNotNull = false;
        void *schema = nullptr;
        if (prepare(db, "PRAGMA table_info(lexicon)", -1, &schema, nullptr) == 0) {
            while (step(schema) == 100) {
                const auto *name = reinterpret_cast<const char *>(text(schema, 1));
                if (name && std::strcmp(name, "hans_weight") == 0) {
                    hasSimplifiedWeight = true;
                    simplifiedWeightNotNull = weight(schema, 3) != 0;
                    break;
                }
            }
        }
        if (schema) finalize(schema);
        const char *sql = !hasSimplifiedWeight
                ? "SELECT hans,weight,toned FROM lexicon WHERE reading=? ORDER BY weight DESC"
            : simplifiedWeightNotNull
                ? "SELECT hans,hans_weight,toned FROM lexicon WHERE reading=? ORDER BY hans_weight DESC"
                : "SELECT hans,COALESCE(hans_weight,weight),toned FROM lexicon WHERE reading=? ORDER BY COALESCE(hans_weight,weight) DESC";
        if (prepare(db, sql, -1, &queries[1], nullptr) != 0) close();
    }

    ChineseEvidence dictionary(const std::string &input, bool simplified = false) const {
        ChineseEvidence result;
        void *query = queries[simplified ? 1 : 0];
        if (!query || input.empty()) return result;
        for (const auto &reading : pinyinEvidenceReadings(input)) {
            if (bind(query, 1, reading.c_str(), -1, reinterpret_cast<void (*)(void *)>(-1)) == 0) {
                std::set<std::string> matchedWords;
                while (matchedWords.size() < 2048 && step(query) == 100) {
                    const auto *value = reinterpret_cast<const char *>(text(query, 0));
                    const auto *toned = reinterpret_cast<const char *>(text(query, 2));
                    if (value && toned && pinyinEvidenceMatches(input, toned)) {
                        matchedWords.emplace(value);
                        const int current = weight(query, 1);
                        auto found = result.find(value);
                        if (found == result.end()) result.emplace(value, current);
                        else found->second = std::max(found->second, current);
                    }
                }
            }
            reset(query);
        }
        return result;
    }

private:
    void close() {
        for (auto &query : queries) {
            if (query) finalize(query);
            query = nullptr;
        }
        if (db) closeDb(db);
        if (library) FreeLibrary(library);
        db = nullptr;
        library = nullptr;
    }

    template<class Function> Function proc(const char *name) const {
        return reinterpret_cast<Function>(GetProcAddress(library, name));
    }

    HMODULE library = nullptr;
    void *db = nullptr, *queries[2] = {};
    int (*openDb)(const char *, void **, int, const char *) = nullptr;
    int (*closeDb)(void *) = nullptr;
    int (*prepare)(void *, const char *, int, void **, const char **) = nullptr;
    int (*finalize)(void *) = nullptr;
    int (*reset)(void *) = nullptr;
    int (*bind)(void *, int, const char *, int, void (*)(void *)) = nullptr;
    int (*step)(void *) = nullptr;
    const unsigned char *(*text)(void *, int) = nullptr;
    int (*weight)(void *, int) = nullptr;
};
