#pragma once

#include <windows.h>
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
            prepare(db, "SELECT hant,weight FROM lexicon WHERE reading=? ORDER BY weight DESC LIMIT 256",
                    -1, &query, nullptr) != 0) close();
    }

    ChineseEvidence dictionary(const std::string &input) const {
        ChineseEvidence result;
        if (!query || input.empty()) return result;
        std::string reading;
        for (char c : input) if (c != '\'') reading += c;
        if (bind(query, 1, reading.c_str(), -1, reinterpret_cast<void (*)(void *)>(-1)) == 0) {
            while (step(query) == 100) {
                const auto *value = reinterpret_cast<const char *>(text(query, 0));
                if (value) result.emplace(value, weight(query, 1));
            }
        }
        reset(query);
        return result;
    }

private:
    void close() {
        if (query) finalize(query);
        if (db) closeDb(db);
        if (library) FreeLibrary(library);
        query = db = nullptr;
        library = nullptr;
    }

    template<class Function> Function proc(const char *name) const {
        return reinterpret_cast<Function>(GetProcAddress(library, name));
    }

    HMODULE library = nullptr;
    void *db = nullptr, *query = nullptr;
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
