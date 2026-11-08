#!/usr/bin/env python3
"""Create a reviewable candidate normalization snapshot; never overwrite the pinned one."""
import argparse
import gzip
import hashlib
import io
import json
import runpy
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--converter', type=Path, required=True, help='compiled FreezeConversions.cpp using ICU 78.2')
parser.add_argument('--output', type=Path, required=True, help='new candidate gzip path; must not exist')
args = parser.parse_args()
converter = args.converter.resolve(strict=True)
if args.output.exists():
    parser.error('candidate output already exists; choose a new path')
version = subprocess.run([str(converter), '--version'], text=True, capture_output=True, check=True).stdout.strip()
if version != 'ICU 78.2':
    parser.error('converter must be built against ICU 78.2')

sys.path.insert(0, str(ROOT / 'scripts'))
compiler = runpy.run_path(str(ROOT / 'scripts/compile-native-lexicon.py'))
main = compiler['main']
records = {}
normalization = ROOT / 'DataSources/MimoNormalization'
manifest = json.loads((normalization / 'SOURCE.json').read_text(encoding='utf-8'))
overrides_data = (normalization / manifest['overrides_file']).read_bytes()
if hashlib.sha256(overrides_data).hexdigest() != manifest['overrides_sha256']:
    raise ValueError('Reviewed normalization override checksum mismatch')
overrides = {}
for number, row in enumerate(overrides_data.decode('utf-8').splitlines(), 1):
    fields = row.split('\t')
    if len(fields) != 3 or fields[0] not in {'s2t', 't2s'} or (fields[0], fields[1]) in overrides:
        raise ValueError(f'Invalid or duplicate normalization override row {number}')
    overrides[fields[0], fields[1]] = fields[2]

def capture(_ignored, words, traditional=False):
    if not words:
        return []
    command = [str(converter)] + (['--traditional'] if traditional else [])
    result = subprocess.run(command, input='\n'.join(words) + '\n', text=True,
                            encoding='utf-8', capture_output=True, check=True).stdout.splitlines()
    if len(result) != len(words):
        raise ValueError('converter changed the number of input lines')
    mode = 's2t' if traditional else 't2s'
    effective = []
    for original, converted in zip(words, result):
        if any(c in original or c in converted for c in '\t\r\n'):
            raise ValueError('conversion contains a tab or newline')
        key = (mode, original)
        if key in records and records[key] != converted:
            raise ValueError(f'Inconsistent conversion for {key!r}')
        records[key] = converted
        # Later compiler branches must see exactly what normal builds see;
        # only the unmodified ICU output belongs in the base snapshot.
        effective.append(overrides.get(key, converted))
    return effective

main.__globals__['convert_script'] = capture
with tempfile.TemporaryDirectory(prefix='meowpuut-freeze-') as generated:
    main(generated)

override_issues = [(key, 'removed' if key not in records else 'redundant')
                   for key, value in overrides.items()
                   if key not in records or records[key] == value]

payload = ''.join(f'{mode}\t{source}\t{records[(mode, source)]}\n'
                  for mode in ('t2s', 's2t')
                  for source in sorted(source for direction, source in records if direction == mode)).encode('utf-8')
# GzipFile fixes timestamp, name and OS header across supported Python versions.
buffer = io.BytesIO()
with gzip.GzipFile(filename='', fileobj=buffer, mode='wb', compresslevel=9, mtime=0) as file:
    file.write(payload)
encoded = buffer.getvalue()
# Exclusive creation avoids accidentally replacing the reviewed snapshot.
with args.output.open('xb') as file:
    file.write(encoded)
print(f'Candidate: {args.output} ({len(records)} mappings)')
print(f'SHA-256: {hashlib.sha256(encoded).hexdigest()}')
print(f'Content SHA-256: {hashlib.sha256(payload).hexdigest()}')
print('Review changed mappings and overrides, then update SOURCE.json; see DataSources/MimoNormalization/README.md.')
if override_issues:
    for key, reason in override_issues:
        print(f'Override requires review ({reason}): {key!r}', file=sys.stderr)
    print('The candidate was saved for comparison; review overrides before pinning it.', file=sys.stderr)
    raise SystemExit(2)
