#!/usr/bin/env python3
"""Check or safely stage a pinned Pinyin Pro source update."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
NAMES = ('complete.json', 'LICENSE', 'README.md', 'SOURCE.json')
PATHS = {'complete.json': 'packages/data/json/complete.json', 'LICENSE': 'packages/data/LICENSE',
         'README.md': 'packages/data/README.md'}
MIN_SOURCE_ROWS = 100000
JOURNAL = '.PinyinPro-update'


def fetch(url):
    return subprocess.check_output(['curl', '--fail', '--silent', '--show-error', '--location', '--max-time', '90', url])


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_installed(dest, current):
    for name in NAMES[:-1]:
        expected = current.get('files', {}).get(name)
        if not expected or digest((dest / name).read_bytes()) != expected:
            raise ValueError(f'Installed source has local changes or a checksum mismatch: {name}; source remains untouched')


def has_local_changes(root, dest):
    """Preserve tracked or untracked edits when the source is in a Git checkout."""
    try:
        probe = subprocess.run(['git', '-C', str(root), 'rev-parse', '--show-toplevel'],
                               text=True, capture_output=True)
    except FileNotFoundError:
        return False  # Source hashes are still checked in source archives without Git.
    if probe.returncode != 0:
        return False
    result = subprocess.run(['git', '-C', str(root), 'status', '--porcelain=v1', '--untracked-files=all', '--', str(dest)],
                            text=True, capture_output=True, check=True)
    return bool(result.stdout.strip())


def validate_payloads(dest, payloads):
    if payloads['LICENSE'] != (dest / 'LICENSE').read_bytes():
        raise ValueError('Upstream license changed; source remains untouched for review')
    data = json.loads(payloads['complete.json'])
    if not isinstance(data, dict) or len(data) < MIN_SOURCE_ROWS:
        raise ValueError('Unexpected dictionary shape or truncation')
    for word, item in data.items():
        if (not isinstance(word, str) or not isinstance(item, list) or len(item) != 2 or
            not isinstance(item[0], str) or isinstance(item[1], bool) or not isinstance(item[1], (int, float)) or
            not math.isfinite(item[1]) or item[1] < 0):
            raise ValueError('Unexpected dictionary row')


def preview_build(root, payloads):
    """Build the new source with the existing normalization in a disposable workspace."""
    with tempfile.TemporaryDirectory(prefix='meowpuut-pinyin-preview-') as temp:
        preview = Path(temp)
        for folder in ('scripts', 'DataSources', 'Resources'):
            shutil.copytree(root / folder, preview / folder, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
        source = preview / 'DataSources/PinyinPro'
        for name, data in payloads.items():
            (source / name).write_bytes(data)
        result = subprocess.run([sys.executable, str(preview / 'scripts/compile-native-lexicon.py'), str(preview / 'compiled')],
                                cwd=preview, text=True, capture_output=True)
        if result.returncode:
            details = (result.stderr or result.stdout).strip()[-1800:]
            raise ValueError('Candidate build failed with the current pinned normalization; installed source remains untouched. '
                             'Review missing mappings following DataSources/MimoNormalization/README.md.\n' + details)


def _transaction_paths(dest):
    journal = dest.parent / JOURNAL
    return journal, journal / 'old', journal / 'new', journal / 'journal.json'


def recover(dest):
    """Recover an interrupted transaction without overwriting later user edits."""
    journal, old, _, metadata = _transaction_paths(dest)
    if not journal.is_dir():
        raise ValueError('No interrupted Pinyin Pro update to recover')
    if not metadata.is_file():
        raise ValueError(f'Incomplete update journal at {journal}; inspect it before removing anything')
    record = json.loads(metadata.read_text(encoding='utf-8'))
    if set(record.get('old', {})) != set(NAMES) or set(record.get('new', {})) != set(NAMES):
        raise ValueError(f'Invalid update journal at {journal}; source remains untouched')
    for name in NAMES:
        backup = (old / name).read_bytes()
        if digest(backup) != record['old'][name]:
            raise ValueError(f'Update backup checksum mismatch: {name}; source remains untouched')
        if (dest / name).is_symlink():
            raise ValueError(f'{name} is a symbolic link; refusing to replace it during recovery')
        existing = (dest / name).read_bytes()
        if digest(existing) not in (record['old'][name], record['new'][name]):
            raise ValueError(f'{name} changed since the interruption; refusing to overwrite it')
    if record.get('committed'):
        if any(digest((dest / name).read_bytes()) != record['new'][name] for name in NAMES):
            raise ValueError('Committed update journal does not match source; inspect it before recovery')
    else:
        for name in NAMES:
            if digest((dest / name).read_bytes()) == record['old'][name]:
                continue
            # Preserve backups if recovery itself is interrupted.
            restore = journal / (name + '.restore')
            shutil.copyfile(old / name, restore)
            os.replace(restore, dest / name)
    shutil.rmtree(journal)


def commit_source(dest, payloads, replace=os.replace, expected=None):
    """Replace the verified files with a recoverable journal and rollback on errors."""
    journal, old, new, metadata = _transaction_paths(dest)
    try:
        journal.mkdir()
    except FileExistsError as error:
        raise ValueError(f'An earlier update is unfinished: {journal}; run --recover after inspection') from error
    try:
        old.mkdir(); new.mkdir()
        for name in NAMES:
            if (dest / name).is_symlink():
                raise ValueError(f'{name} is a symbolic link; refusing to replace it')
            original = (dest / name).read_bytes()
            if expected is not None and digest(original) != expected[name]:
                raise ValueError(f'{name} changed during preview; refusing to overwrite it')
            (old / name).write_bytes(original)
            (new / name).write_bytes(payloads[name])
        record = {'old': {n: digest((old / n).read_bytes()) for n in NAMES},
                  'new': {n: digest(payloads[n]) for n in NAMES}, 'committed': False}
        temporary = journal / 'journal.tmp'
        temporary.write_text(json.dumps(record, sort_keys=True), encoding='utf-8')
        os.replace(temporary, metadata)
    except BaseException:
        # No destination file has been touched before the journal is complete.
        shutil.rmtree(journal)
        raise
    try:
        for name in NAMES:
            if digest((dest / name).read_bytes()) != record['old'][name]:
                raise ValueError(f'{name} changed during the update; refusing to overwrite it')
            replace(new / name, dest / name)
        record['committed'] = True
        temporary = journal / 'journal.tmp'
        temporary.write_text(json.dumps(record, sort_keys=True), encoding='utf-8')
        os.replace(temporary, metadata)
    except BaseException:
        try:
            recover(dest)
        except BaseException as rollback_error:
            raise RuntimeError(f'Update interrupted and rollback could not finish; preserve {journal} and run --recover after inspection') from rollback_error
        raise
    shutil.rmtree(journal)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Apply only after source and pinned-normalization preflight')
    parser.add_argument('--revision', help='Optional upstream 40-character commit')
    parser.add_argument('--recover', action='store_true', help='Recover an interrupted source update without contacting the network')
    args = parser.parse_args(argv)
    dest = ROOT / 'DataSources/PinyinPro'
    journal, _, _, _ = _transaction_paths(dest)
    if args.recover:
        if args.apply or args.revision:
            parser.error('--recover cannot be combined with --apply or --revision')
        recover(dest)
        print('Interrupted update recovered.')
        return
    if journal.exists():
        raise ValueError(f'An earlier update is unfinished: {journal}; run --recover after inspection')
    if args.revision and not re.fullmatch(r'[0-9a-f]{40}', args.revision):
        parser.error('--revision must be a 40-character lowercase commit SHA')
    source_manifest = (dest / 'SOURCE.json').read_bytes()
    current = json.loads(source_manifest)
    verify_installed(dest, current)
    original = {name: (dest / name).read_bytes() for name in NAMES}
    if original['SOURCE.json'] != source_manifest:
        raise ValueError('Source metadata changed during inspection; source remains untouched')
    commit = json.loads(fetch('https://api.github.com/repos/zh-lx/pinyin-pro/commits/' + (args.revision or 'main')))
    revision = commit['sha']
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Invalid revision')
    print(json.dumps({'installed_source': current['revision'], 'upstream': revision,
                      'repository_commit_date': commit['commit']['committer']['date'],
                      'changed': revision != current['revision']}, ensure_ascii=False))
    if not args.apply:
        return
    if has_local_changes(ROOT, dest):
        raise ValueError('Pinyin Pro source has local Git changes; source remains untouched')
    if revision == current['revision']:
        print('Source already matches this revision; nothing changed.')
        return
    payloads = {name: fetch(f'https://raw.githubusercontent.com/zh-lx/pinyin-pro/{revision}/{path}')
                for name, path in PATHS.items()}
    validate_payloads(dest, payloads)
    history = json.loads(fetch(f'https://api.github.com/repos/zh-lx/pinyin-pro/commits?sha={revision}&path=packages/data/json/complete.json&per_page=1'))
    if not history or not re.fullmatch(r'[0-9a-f]{40}', history[0].get('sha', '')):
        raise ValueError('Invalid upstream data history; source remains untouched')
    updated = dict(current)
    updated.update(revision=revision, data_commit=history[0]['sha'], data_commit_date=history[0]['commit']['committer']['date'])
    updated['files'] = dict(current['files'])
    updated['files'].update({name: digest(value) for name, value in payloads.items()})
    payloads['SOURCE.json'] = (json.dumps(updated, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    preview_build(ROOT, payloads)
    # Check again after the potentially long preview: never overwrite new edits.
    verify_installed(dest, current)
    if any((dest / name).read_bytes() != original[name] for name in NAMES):
        raise ValueError('Pinyin Pro source changed during preview; source remains untouched')
    if has_local_changes(ROOT, dest):
        raise ValueError('Pinyin Pro source changed during preview; source remains untouched')
    commit_source(dest, payloads, expected={name: digest(data) for name, data in original.items()})
    print('Source updated after isolated build. Run full cross-platform tests before release.')


if __name__ == '__main__':
    main()
