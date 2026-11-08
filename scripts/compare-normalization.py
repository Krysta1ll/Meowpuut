#!/usr/bin/env python3
"""Print every added, removed or changed mapping between two snapshot candidates."""
import gzip
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit('Usage: python3 scripts/compare-normalization.py OLD.tsv.gz NEW.tsv.gz')

def load(path):
    records = {}
    for number, row in enumerate(gzip.decompress(Path(path).read_bytes()).decode('utf-8').splitlines(), 1):
        fields = row.split('\t')
        if len(fields) != 3 or fields[0] not in {'s2t', 't2s'} or (fields[0], fields[1]) in records:
            raise ValueError(f'Invalid or duplicate mapping at {path}:{number}')
        records[fields[0], fields[1]] = fields[2]
    return records

old, new = load(sys.argv[1]), load(sys.argv[2])
for key in sorted(old.keys() | new.keys()):
    before, after = old.get(key), new.get(key)
    if before != after:
        kind = 'ADDED' if before is None else 'REMOVED' if after is None else 'CHANGED'
        print(f'{kind}\t{key[0]}\t{key[1]}\t{before if before is not None else ""}\t{after if after is not None else ""}')
