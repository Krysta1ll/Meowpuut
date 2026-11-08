"""Check the generated merged dictionary; no network or third-party packages."""
import json
import pathlib
import sqlite3
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".build/Meowpuut.app/Contents/Resources")
db = sqlite3.connect(root / "language-evidence.sqlite")
report = json.loads((root / "lexicon-merge-report.json").read_text())
assert report["name"] == "mimo" and report["version"] == "1.0"
count = db.execute("SELECT count(*) FROM lexicon").fetchone()[0]
assert count == report["final_readings"], (count, report)
# A simplified word can represent multiple traditional spellings with the
# same sound. Repeated traditional forms still indicate an invalid merge.
invalid = db.execute("""SELECT hans,phonetic,count(*) FROM lexicon
    GROUP BY hans,phonetic HAVING count(*)>1 AND
    count(distinct hant)<>count(*)
    LIMIT 1""").fetchone()
assert invalid is None, invalid
for simplified, sound, forms in [('志', '54', ('志', '誌')), ('周', '5. ', ('周', '週'))]:
    found = {row[0] for row in db.execute('SELECT hant FROM lexicon WHERE hans=? AND phonetic=?', (simplified, sound))}
    assert set(forms) <= found, (simplified, sound, found)
assert db.execute("SELECT weight FROM lexicon WHERE hant='喵' AND hans='喵' AND reading='miao'").fetchone()[0] == 7000
assert db.execute("SELECT count(DISTINCT phonetic) FROM lexicon WHERE hans='重'").fetchone()[0] >= 2
assert db.execute("SELECT count(*) FROM lexicon WHERE hant='優化' AND hans='优化'").fetchone()[0] > 0
assert db.execute("SELECT count(*) FROM lexicon WHERE hant='喵撲' AND hans='喵扑'").fetchone()[0] == 1
for mode in ("pinyin", "zhuyin"):
    table = (root / f"native-{mode}.tsv").read_text()
    expected = "\t喵\t喵\t7000\t7000\n" if table.startswith(f"MEOWPUUT-{mode.upper()}-2\n") else "\t喵\t喵\t7000\n"
    assert expected in table
print(f"PASS: {count:,} merged readings; traditional variants, deduplication, polyphony and native indexes verified")
