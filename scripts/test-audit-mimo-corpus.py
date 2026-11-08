#!/usr/bin/env python3
"""Focused checks for review classification and no-overwrite behavior."""
import json
import hashlib
from pathlib import Path
import runpy
import sqlite3
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
module = runpy.run_path(str(ROOT / 'scripts/audit-mimo-corpus.py'))
classify = module['classify']
additional_source_hashes = module['additional_source_hashes']
read_score_rows = module['read_score_rows']
ordered_variants = module['ordered_variants']
values = [
    {'hans': '纪', 'weight': 4000, 'mcb_exact_pair': True, 'pinyinpro_same_reading_frequency': 0.1},
    {'hans': '记', 'weight': 3000, 'mcb_exact_pair': False, 'pinyinpro_same_reading_frequency': 0.2},
]
category, target, reverse = classify('紀', values, {('t2s', '紀'): '纪', ('s2t', '纪'): '紀'})
assert (category, target, reverse) == ('two_sources_and_pinned_roundtrip', '纪', '紀')
# A roundtrip supports one pair, but does not assert other written words are wrong.
assert values[1]['hans'] == '记'
category, _, reverse = classify('乾隆', [
    {'hans': '干隆', 'mcb_exact_pair': True, 'pinyinpro_same_reading_frequency': None},
    {'hans': '乾隆', 'mcb_exact_pair': False, 'pinyinpro_same_reading_frequency': 1.0},
], {('t2s', '乾隆'): '干隆'})
assert category == 'pinned_t2s_matches_one_variant_needs_review' and reverse is None
category, _, _ = classify('未知', [{'hans': '未知', 'mcb_exact_pair': False,
                                    'pinyinpro_same_reading_frequency': None}], {})
assert category == 'unresolved'
# A V1 database uses the original score for both scripts.  In V2 the
# simplified review order must use hans_weight even when traditional order is
# the opposite; source evidence is kept separate from either score.
for dual in (False, True):
    with sqlite3.connect(':memory:') as db:
        suffix = ', hans_weight INTEGER' if dual else ''
        db.execute('CREATE TABLE lexicon(hant TEXT, hans TEXT, toned TEXT, weight INTEGER' + suffix + ')')
        if dual:
            db.executemany('INSERT INTO lexicon VALUES(?,?,?,?,?)', [('甲', '甲', 'jia3', 9000, 1000),
                                                                     ('甲', '佳', 'jia3', 2000, 8000)])
        else:
            db.executemany('INSERT INTO lexicon VALUES(?,?,?,?)', [('甲', '甲', 'jia3', 9000),
                                                                   ('甲', '佳', 'jia3', 2000)])
        rows, has_dual = read_score_rows(db)
        assert has_dual == dual
        values = {hans: (weight, hans_weight) for _, hans, _, weight, hans_weight in rows}
        assert values['甲'] == (9000, 1000 if dual else 9000)
        ranked = ordered_variants('甲', 'jia3', values, {}, set())
        assert ranked[0]['hans'] == ('佳' if dual else '甲')
        assert all(item['pinyinpro_same_reading_frequency'] is None and not item['mcb_exact_pair'] for item in ranked)
        assert set(ranked[0]) == {'hans', 'traditional_weight', 'hans_weight',
                                  'pinyinpro_same_reading_frequency', 'mcb_exact_pair'}
with tempfile.TemporaryDirectory(prefix='meowpuut-audit-test-') as directory:
    directory = Path(directory)
    lexicon = directory / 'Resources/Lexicon'
    lexicon.mkdir(parents=True)
    reviewed = lexicon / 'reviewed-simplified-exclusions.tsv'
    reviewed.write_text('為|wei2|為|为\n', encoding='utf-8')
    hashes = additional_source_hashes(directory)
    assert hashes['reviewed-simplified-exclusions.tsv'] == hashlib.sha256(reviewed.read_bytes()).hexdigest()
    assert hashes['simplified-priorities.tsv'] is None
    assert hashes['mcbopomofo-retained.tsv'] is None and hashes['source-reading-exclusions.json'] is None
    priorities = lexicon / 'simplified-priorities.tsv'
    priorities.write_text('学姐|xue2 jie3|6000\n', encoding='utf-8')
    assert additional_source_hashes(directory)['simplified-priorities.tsv'] == hashlib.sha256(priorities.read_bytes()).hexdigest()
    existing = directory / 'existing.json'
    existing.write_text('do not overwrite', encoding='utf-8')
    result = subprocess.run([sys.executable, str(ROOT / 'scripts/audit-mimo-corpus.py'),
                             '--resources', str(directory / 'missing'), '--output', str(existing)],
                            capture_output=True, text=True)
    assert result.returncode != 0 and existing.read_text(encoding='utf-8') == 'do not overwrite'
    target = directory / 'new.json'
    result = subprocess.run([sys.executable, str(ROOT / 'scripts/audit-mimo-corpus.py'),
                             '--resources', str(directory / 'missing'), '--output', str(target)],
                            capture_output=True, text=True)
    assert result.returncode != 0 and not target.exists() and 'Missing compiled resource' in result.stderr
print('PASS: read-only evidence classification and no-overwrite behavior')
