#!/usr/bin/env python3
"""Report a fixed, human-written Pinyin challenge against the portable core.

Usage: python3 scripts/eval-pinyin-holdout.py [--set development|validation] [--resources PATH] [--json]
The answers are examples, not a frequency-derived or statistically sampled gold set.
"""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = {name: ROOT / 'Core/tests/data' / f'pinyin-{name}.tsv'
            for name in ('development', 'validation')}
GROUPS = ('character', 'phrase', 'sentence', 'prefix')


def cases(fixture):
    # Exclude answers explicitly present in editable overlays at evaluation
    # time. Imported lexicons and shorter pieces may still contain them.
    authored = set()
    for filename in ('chinese.tsv', 'context.tsv', 'simplified-priorities.tsv'):
        path = ROOT / 'Resources/Lexicon' / filename
        if not path.exists():
            continue
        for line in path.read_text(encoding='utf-8').splitlines():
            if not line or line.startswith('#'):
                continue
            fields = line.split('|')
            authored.add(fields[0])
            if filename == 'chinese.tsv':
                authored.add(fields[1])
    result, excluded, seen = [], [], set()
    for number, line in enumerate(fixture.read_text(encoding='utf-8').splitlines(), 1):
        if not line or line.startswith('#'):
            continue
        fields = line.split('|')
        if len(fields) not in (4, 5):
            raise ValueError(f'{fixture}:{number}: expected four fields, or five for a prefix')
        group, raw, hans, hant = fields[:4]
        suffix = fields[4] if len(fields) == 5 else ''
        if (group not in GROUPS or not re.fullmatch('[a-z]+', raw) or not hans or not hant or
                (group == 'prefix') != bool(suffix) or
                (suffix and (not re.fullmatch('[a-z]+', suffix) or not raw.endswith(suffix) or raw == suffix))):
            raise ValueError(f'{fixture}:{number}: invalid example')
        key = (group, raw, hans, hant, suffix)
        if key in seen:
            raise ValueError(f'{fixture}:{number}: duplicate example')
        seen.add(key)
        entry = {'group': group, 'input': raw, 'hans': hans, 'hant': hant, 'suffix': suffix}
        (excluded if hans in authored or hant in authored else result).append(entry)
    return result, excluded


def run(resources, dataset):
    data = (resources / 'native-pinyin.tsv').read_bytes()
    source = (ROOT / 'Core/MeowpuutCore.cpp').read_bytes()
    header = (ROOT / 'Core/MeowpuutCore.h').read_bytes()
    fixture = FIXTURES[dataset]
    examples, excluded = cases(fixture)
    with tempfile.TemporaryDirectory(prefix='meowpuut-holdout-') as temporary:
        directory = Path(temporary)
        library = directory / ('core.dylib' if sys.platform == 'darwin' else 'core.so')
        # Snapshot the exact code being evaluated, even if another worktree
        # process edits the shared checkout while this command is running.
        (directory / 'MeowpuutCore.cpp').write_bytes(source)
        (directory / 'MeowpuutCore.h').write_bytes(header)
        flags = ['-dynamiclib'] if sys.platform == 'darwin' else ['-shared', '-fPIC']
        subprocess.run(['c++', '-std=c++17', '-O2', *flags, str(directory / 'MeowpuutCore.cpp'),
                        '-o', str(library)], check=True)
        core = ctypes.CDLL(str(library))
        ptr, size, string = ctypes.c_void_p, ctypes.c_size_t, ctypes.c_char_p
        for name, args, result in [
            ('mpn_lexicon_create', [string, size], ptr), ('mpn_lexicon_destroy', [ptr], None),
            ('mpn_session_create', [ptr], ptr), ('mpn_session_destroy', [ptr], None),
            ('mpn_set_simplified', [ptr, ctypes.c_int], None), ('mpn_clear', [ptr], None),
            ('mpn_key', [ptr, ctypes.c_int32], ctypes.c_int), ('mpn_candidate_count', [ptr], size),
            ('mpn_candidate', [ptr, size], string), ('mpn_candidate_consumed', [ptr, size], size),
            ('mpn_choose', [ptr, size], ctypes.c_int), ('mpn_committed', [ptr], string),
            ('mpn_input', [ptr], string),
        ]:
            function = getattr(core, name)
            function.argtypes, function.restype = args, result
        lexicon = core.mpn_lexicon_create(data, len(data))
        if not lexicon:
            raise ValueError('Portable core rejected the Pinyin table')
        session = core.mpn_session_create(lexicon)
        if not session:
            core.mpn_lexicon_destroy(lexicon)
            raise MemoryError('Cannot create portable core session')
        observations = []
        try:
            for example in examples:
                for simplified, script in ((1, 'simplified'), (0, 'traditional')):
                    core.mpn_clear(session)
                    core.mpn_set_simplified(session, simplified)
                    raw, target, suffix = (example['input'], example['hans' if simplified else 'hant'],
                                           example['suffix'])
                    for key in raw:
                        if not core.mpn_key(session, ord(key)):
                            raise AssertionError(f'Input rejected: {raw!r}, {key!r}')
                    count = core.mpn_candidate_count(session)
                    index, first = None, []
                    for i in range(count):
                        text = core.mpn_candidate(session, i).decode('utf-8')
                        if i < 7:
                            first.append(text)
                        if index is None and text == target and core.mpn_candidate_consumed(session, i) == len(raw) - len(suffix):
                            index = i
                        if index is not None and i >= 6:
                            break
                    if index is not None:
                        if (not core.mpn_choose(session, index) or core.mpn_committed(session).decode('utf-8') != target or
                                core.mpn_input(session).decode('ascii') != suffix):
                            raise AssertionError(f'Candidate did not commit with expected suffix: {script} {raw!r} {target!r}')
                    observations.append({'group': example['group'], 'script': script, 'input': raw,
                                         'target': target, 'expected_suffix': suffix,
                                         'rank': None if index is None else index + 1,
                                         'first_seven': first})
        finally:
            core.mpn_session_destroy(session)
            core.mpn_lexicon_destroy(lexicon)
    summary = []
    for script in ('simplified', 'traditional'):
        for group in GROUPS:
            selected = [o for o in observations if o['script'] == script and o['group'] == group]
            summary.append({'script': script, 'group': group, 'cases': len(selected),
                            'target_first': sum(o['rank'] == 1 for o in selected),
                            'target_first_seven': sum(o['rank'] is not None and o['rank'] <= 7 for o in selected),
                            'target_available': sum(o['rank'] is not None for o in selected)})
    return {'dataset': dataset, 'fixture_sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
            'core_cpp_sha256': hashlib.sha256(source).hexdigest(),
            'core_header_sha256': hashlib.sha256(header).hexdigest(),
            'lexicon_sha256': hashlib.sha256(data).hexdigest(),
            'method': ('Human-written challenge; target rank is not accuracy. Exact target overlaps with local '
                       'editorial overlays are excluded. Imported lexicons, context pieces and other tests may '
                       'contain these words; this is not an independent corpus or representative usage sample. '
                       'Development examples may be used for tuning; validation must remain unseen until tuning stops.'),
            'excluded_overlay_targets': excluded, 'summary': summary, 'observations': observations}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / '.build/Meowpuut.app/Contents/Resources')
    parser.add_argument('--set', choices=tuple(FIXTURES), default='development', dest='dataset')
    parser.add_argument('--json', action='store_true', help='emit detailed machine-readable results')
    args = parser.parse_args()
    report = run(args.resources, args.dataset)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(report['method'])
        print('set', report['dataset'], 'fixture', report['fixture_sha256'],
              'core', report['core_cpp_sha256'], 'lexicon', report['lexicon_sha256'])
        print('excluded exact overlay targets:', len(report['excluded_overlay_targets']))
        for row in report['summary']:
            print('{script:11} {group:9} cases={cases:2} first={target_first:2} '
                  'top7={target_first_seven:2} available={target_available:2}'.format(**row))
