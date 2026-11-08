#!/usr/bin/env python3
"""Exercise the Windows SQLite reader against old and dual-weight databases.

On macOS/Linux a temporary Windows loader shim calls the system SQLite library;
no product code or fixture database is modified.
"""
import sqlite3
import subprocess
import tempfile
import time
from pathlib import Path

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='meowpuut-windows-evidence-') as directory:
    temporary = Path(directory)
    (temporary / 'windows.h').write_text(r'''
#pragma once
#include <dlfcn.h>
using HMODULE = void *;
constexpr unsigned long LOAD_LIBRARY_SEARCH_SYSTEM32 = 0;
inline HMODULE LoadLibraryExW(const wchar_t *, void *, unsigned long) {
#ifdef __APPLE__
    return dlopen("/usr/lib/libsqlite3.dylib", RTLD_NOW);
#else
    return dlopen("libsqlite3.so.0", RTLD_NOW);
#endif
}
inline void *GetProcAddress(HMODULE module, const char *name) { return dlsym(module, name); }
inline int FreeLibrary(HMODULE module) { return dlclose(module); }
''', encoding='utf-8')
    (temporary / 'test.cpp').write_text(r'''
#include "WordEvidence.h"
#include <cassert>
#include <filesystem>

int main(int argc, char **argv) {
    assert(argc == 4);
    WordEvidence evidence;
    evidence.open(std::filesystem::path(argv[1]));
    auto traditional = evidence.dictionary("ni");
    auto simplified = evidence.dictionary("ni", true);
    assert(traditional.at(u8"妳") == 7777 && !traditional.count(u8"你"));
    assert(simplified.at(u8"你") == 1234 && !simplified.count(u8"妳"));
    assert(simplified.at(u8"泥") == 600); // null dual weight uses the legacy value
    assert(evidence.dictionary("ce", true).at(u8"目标") == 9000);
    assert(!evidence.dictionary("ce").count(u8"目標"));
    assert(evidence.dictionary("lue", true).at(u8"略") == 900);
    assert(evidence.dictionary("xian", true).count(u8"先"));
    auto separated = evidence.dictionary("xi'an", true);
    assert(separated.count(u8"西安") && !separated.count(u8"先"));
    assert(evidence.dictionary("xi1an1", true).count(u8"西安"));
    assert(!evidence.dictionary("xi1an1", true).count(u8"先"));
    assert(evidence.dictionary("xian1", true).count(u8"先"));
    assert(!evidence.dictionary("xian1", true).count(u8"西安"));
    assert(evidence.dictionary("xi4an1", true).empty());
    assert(evidence.dictionary("du", true).at(u8"相同") == 1500);
    assert(evidence.dictionary("du", true).count(u8"不同"));
    const std::string table = "MEOWPUUT-PINYIN-1\n"
        "xian\txian1\t先\t先\t9000\n"
        "xian\txi1an1\t西安\t西安\t1000\n";
    auto *lexicon = mpn_lexicon_create(table.data(), table.size());
    assert(lexicon);
    auto *session = mpn_session_create(lexicon);
    assert(session);
    mpn_set_simplified(session, 1);
    for (const char c : std::string("xi'an")) assert(mpn_key(session, c));
    const auto candidates = windowsCandidates(session, {}, [&](const std::string &input) {
        return evidence.dictionary(input, true);
    }, false);
    assert(!candidates.empty() && candidates.front().text == u8"西安");
    assert(std::none_of(candidates.begin(), candidates.end(), [](const auto &candidate) { return candidate.text == u8"先"; }));
    assert(candidates.front().coreIndex && mpn_choose(session, *candidates.front().coreIndex));
    assert(std::string(mpn_committed(session)) == u8"西安" && !*mpn_input(session));
    mpn_session_destroy(session);
    mpn_lexicon_destroy(lexicon);
    evidence.open(std::filesystem::path(argv[2])); // nullable transition schema
    assert(evidence.dictionary("ni", true).at(u8"你") == 1234);
    assert(evidence.dictionary("ni", true).at(u8"泥") == 600);
    assert(evidence.dictionary("ce", true).at(u8"目标") == 9000);
    evidence.open(std::filesystem::path(argv[3])); // close and reopen with the old schema
    assert(evidence.dictionary("ni").at(u8"妳") == 7777);
    assert(evidence.dictionary("ni", true).at(u8"你") == 7777);
    assert(!evidence.dictionary("ce", true).count(u8"目标"));
    assert(evidence.dictionary("lue", true).at(u8"略") == 400);
    assert(evidence.dictionary("xi'an", true).count(u8"西安"));
    assert(!evidence.dictionary("xi'an", true).count(u8"先"));
    assert(evidence.dictionary("du", true).count(u8"不同"));
}
''', encoding='utf-8')
    databases = []
    for schema in ('not-null', 'nullable', 'legacy'):
        dual = schema != 'legacy'
        path = temporary / f'{schema}.sqlite'
        databases.append(path)
        with sqlite3.connect(path) as db:
            db.execute('CREATE TABLE lexicon(reading TEXT, hant TEXT, hans TEXT, weight INTEGER, toned TEXT' +
                       (', hans_weight INTEGER' + (' NOT NULL' if schema == 'not-null' else '') if dual else '') + ')')
            if dual:
                db.execute('CREATE INDEX lexicon_reading_hans_weight ON lexicon(reading,hans_weight DESC)')
            columns = 6 if dual else 5
            values = [
                ('ni', '妳', '你', 7777, 'ni3', 1234),
                ('ni', '泥', '泥', 600, 'ni2', None if schema == 'nullable' else 600),
                ('lve', '略', '略', 400, 'lve4', 900),
                ('ce', '目標', '目标', 1, 'ce4', 9000),
                ('xian', '先', '先', 10000, 'xian1', 10000),
                ('xian', '西安', '西安', 1, 'xi1an1', 1),
                ('du', '不同', '不同', 1, 'du1', 1),
                ('du', '繁高', '相同', 1500, 'du1', 1500),
            ] + [('ce', f'繁{i}', f'简{i}', 1000, 'ce4', 1000) for i in range(2050)] \
              + [('xian', f'不符{i}', f'不符{i}', 9000, 'xian1', 9000) for i in range(2050)] \
              + [('du', f'繁複{i}', '相同', 1000, 'du1', 1000) for i in range(2050)]
            db.executemany('INSERT INTO lexicon VALUES(' + ','.join('?' * columns) + ')',
                           [row[:columns] for row in values])
            if schema == 'not-null':
                indexed = 'SELECT hans,hans_weight FROM lexicon WHERE reading=? ORDER BY hans_weight DESC LIMIT 2048'
                coalesced = 'SELECT hans,COALESCE(hans_weight,weight) FROM lexicon WHERE reading=? ORDER BY COALESCE(hans_weight,weight) DESC LIMIT 2048'
                plan = [row[3] for row in db.execute('EXPLAIN QUERY PLAN ' + indexed, ('ce',))]
                assert any('lexicon_reading_hans_weight' in row for row in plan) and not any('TEMP B-TREE' in row for row in plan), plan
                timings = []
                for query in (indexed, coalesced):
                    start = time.perf_counter()
                    for _ in range(30):
                        list(db.execute(query, ('ce',)))
                    timings.append((time.perf_counter() - start) * 1000 / 30)
                print(f'SQLite 2051-row lookup: indexed {timings[0]:.2f} ms; COALESCE {timings[1]:.2f} ms (local)')
    executable = temporary / 'word-evidence-test'
    subprocess.run(['c++', '-std=c++17', '-O2', '-I', str(temporary), '-I', str(root / 'Windows'),
                    str(temporary / 'test.cpp'), str(root / 'Core/MeowpuutCore.cpp'), '-o', str(executable)], check=True)
    subprocess.run([str(executable), *(str(path) for path in databases)], check=True)
print('PASS: Windows evidence reads NOT NULL, nullable and legacy weights, sorts before truncation and preserves aliases')
