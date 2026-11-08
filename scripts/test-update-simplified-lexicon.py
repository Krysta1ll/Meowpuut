#!/usr/bin/env python3
"""Offline synthetic regressions for safe source update and interrupted recovery."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('update_source', ROOT / 'scripts/update-simplified-lexicon.py')
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)
update.MIN_SOURCE_ROWS = 1


def fixture(root):
    dest = root / 'DataSources/PinyinPro'
    dest.mkdir(parents=True)
    (root / 'scripts').mkdir()
    (root / 'Resources').mkdir()
    payloads = {'complete.json': b'{"old":["old1",1]}', 'LICENSE': b'MIT\n', 'README.md': b'old\n'}
    for name, data in payloads.items(): (dest / name).write_bytes(data)
    manifest = {'revision': 'a' * 40, 'data_commit': 'a' * 40, 'data_commit_date': 'old',
                'files': {name: update.digest(data) for name, data in payloads.items()}}
    (dest / 'SOURCE.json').write_text(json.dumps(manifest), encoding='utf-8')
    # Simulate a compiler with a missing pinned normalization key; the test
    # exercises the actual isolated preview subprocess and copy path.
    (root / 'scripts/compile-native-lexicon.py').write_text(
        'from pathlib import Path\nimport json\n'
        'd=json.loads((Path(__file__).resolve().parent.parent/"DataSources/PinyinPro/complete.json").read_text())\n'
        'if "new-missing-key" in d: raise ValueError("Normalization snapshot missing s2t mapping for new-missing-key")\n', encoding='utf-8')
    return dest


def read_source(dest):
    return {name: (dest / name).read_bytes() for name in update.NAMES}


def candidate(dest):
    payloads = {'complete.json': b'{"new-missing-key":["new1",1]}', 'LICENSE': b'MIT\n', 'README.md': b'new\n'}
    meta = json.loads((dest / 'SOURCE.json').read_text())
    meta['revision'] = 'b' * 40
    meta['files'] = {name: update.digest(data) for name, data in payloads.items()}
    payloads['SOURCE.json'] = (json.dumps(meta) + '\n').encode()
    return payloads


def commit_json(revision):
    return json.dumps({'sha': revision, 'commit': {'committer': {'date': '2026-01-01T00:00:00Z'}}}).encode()


def upstream_fetch(data):
    def fake(url):
        if 'commits?' in url: return b'[' + commit_json('c' * 40) + b']'
        if '/commits/' in url: return commit_json('b' * 40)
        if url.endswith('/complete.json'): return data
        if url.endswith('/LICENSE'): return b'MIT\n'
        if url.endswith('/README.md'): return b'new\n'
        raise AssertionError(url)
    return fake


with tempfile.TemporaryDirectory(prefix='meowpuut-source-update-test-') as tmp:
    root = Path(tmp)
    dest = fixture(root)
    update.ROOT = root
    update.has_local_changes = lambda *_: False
    before = read_source(dest)
    mtimes = {name: (dest / name).stat().st_mtime_ns for name in update.NAMES}
    calls = []
    def unchanged(url):
        calls.append(url)
        assert '/commits/' in url
        return commit_json('a' * 40)
    update.fetch = unchanged
    with contextlib.redirect_stdout(io.StringIO()): update.main(['--apply'])
    assert len(calls) == 1 and read_source(dest) == before
    assert all((dest / name).stat().st_mtime_ns == mtimes[name] for name in update.NAMES)
    assert not (dest.parent / update.JOURNAL).exists()

    (dest / 'complete.json').write_bytes(b'local edit')
    try: update.main(['--apply'])
    except ValueError as error: assert 'checksum mismatch' in str(error)
    else: raise AssertionError('dirty snapshot accepted')
    assert (dest / 'complete.json').read_bytes() == b'local edit'
    (dest / 'complete.json').write_bytes(before['complete.json'])

    update.has_local_changes = lambda *_: True
    try:
        with contextlib.redirect_stdout(io.StringIO()): update.main(['--apply'])
    except ValueError as error: assert 'local Git changes' in str(error)
    else: raise AssertionError('dirty tracked metadata accepted')
    assert read_source(dest) == before
    update.has_local_changes = lambda *_: False

    new = candidate(dest)
    try: update.preview_build(root, new)
    except ValueError as error: assert 'Normalization snapshot missing' in str(error)
    else: raise AssertionError('missing conversion key was accepted')
    assert read_source(dest) == before and not (dest.parent / update.JOURNAL).exists()
    update.fetch = upstream_fetch(new['complete.json'])
    try:
        with contextlib.redirect_stdout(io.StringIO()): update.main(['--apply'])
    except ValueError as error: assert 'Normalization snapshot missing' in str(error)
    else: raise AssertionError('update applied after missing conversion key')
    assert read_source(dest) == before and not (dest.parent / update.JOURNAL).exists()

    state = {'count': 0}
    def interrupted(src, target):
        state['count'] += 1
        if state['count'] == 2: raise KeyboardInterrupt()
        os.replace(src, target)
    try: update.commit_source(dest, new, replace=interrupted)
    except KeyboardInterrupt: pass
    else: raise AssertionError('interruption not propagated')
    assert read_source(dest) == before and not (dest.parent / update.JOURNAL).exists()

    # Simulate an uncatchable process termination after the first atomic swap.
    child = '''import importlib.util,os,sys\nfrom pathlib import Path\nspec=importlib.util.spec_from_file_location("update",sys.argv[1]);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)\nd=Path(sys.argv[2]);p=Path(sys.argv[3]);files={n:(p/n).read_bytes() for n in m.NAMES}\nc=[0]\ndef kill(src,dst):\n os.replace(src,dst);c[0]+=1\n if c[0]==1: os._exit(79)\nm.commit_source(d,files,replace=kill)\n'''
    staged = root / 'candidate'; staged.mkdir()
    for name, data in new.items(): (staged / name).write_bytes(data)
    process = subprocess.run([sys.executable, '-c', child, str(ROOT / 'scripts/update-simplified-lexicon.py'), str(dest), str(staged)])
    assert process.returncode == 79 and (dest.parent / update.JOURNAL).is_dir()
    update.recover(dest)
    assert read_source(dest) == before and not (dest.parent / update.JOURNAL).exists()

    # Recovery must not overwrite an edit made after the interruption.
    process = subprocess.run([sys.executable, '-c', child, str(ROOT / 'scripts/update-simplified-lexicon.py'), str(dest), str(staged)])
    assert process.returncode == 79
    (dest / 'README.md').write_bytes(b'new user edit')
    try: update.recover(dest)
    except ValueError as error: assert 'changed since the interruption' in str(error)
    else: raise AssertionError('recovery overwrote later user edit')
    assert (dest / 'README.md').read_bytes() == b'new user edit'

with tempfile.TemporaryDirectory(prefix='meowpuut-source-success-test-') as tmp:
    root = Path(tmp)
    dest = fixture(root)
    update.ROOT = root
    update.has_local_changes = lambda *_: False
    update.fetch = upstream_fetch(b'{"new-valid":["new1",1]}')
    with contextlib.redirect_stdout(io.StringIO()): update.main(['--apply'])
    manifest = json.loads((dest / 'SOURCE.json').read_text())
    assert manifest['revision'] == 'b' * 40 and manifest['data_commit'] == 'c' * 40
    update.verify_installed(dest, manifest)
    assert not (dest.parent / update.JOURNAL).exists()

with tempfile.TemporaryDirectory(prefix='meowpuut-source-preview-race-test-') as tmp:
    root = Path(tmp)
    dest = fixture(root)
    update.ROOT = root
    update.has_local_changes = lambda *_: False  # also protects source archives without Git
    update.fetch = upstream_fetch(b'{"new-valid":["new1",1]}')
    build = update.preview_build
    def edit_during_preview(path, payloads):
        build(path, payloads)
        with (dest / 'SOURCE.json').open('a') as file: file.write(' ')
    update.preview_build = edit_during_preview
    try:
        with contextlib.redirect_stdout(io.StringIO()): update.main(['--apply'])
    except ValueError as error: assert 'changed during preview' in str(error)
    else: raise AssertionError('metadata edit during preview was overwritten')
    assert (dest / 'SOURCE.json').read_text().endswith(' ')
    assert not (dest.parent / update.JOURNAL).exists()
    update.preview_build = build

with tempfile.TemporaryDirectory(prefix='meowpuut-source-before-swap-test-') as tmp:
    root = Path(tmp)
    dest = fixture(root)
    before = read_source(dest)
    expected = {name: update.digest(data) for name, data in before.items()}
    new = candidate(dest)
    (dest / 'complete.json').write_bytes(b'edit just before swap')
    try: update.commit_source(dest, new, expected=expected)
    except ValueError as error: assert 'changed during preview' in str(error)
    else: raise AssertionError('edit immediately before swap was overwritten')
    assert (dest / 'complete.json').read_bytes() == b'edit just before swap'
    assert not (dest.parent / update.JOURNAL).exists()

with tempfile.TemporaryDirectory(prefix='meowpuut-source-git-test-') as tmp:
    import shutil
    git = shutil.which('git')
    if git:
        root = Path(tmp)
        dest = fixture(root)
        subprocess.run([git, 'init', '-q', str(root)], check=True)
        subprocess.run([git, '-C', str(root), 'add', 'DataSources/PinyinPro'], check=True)
        subprocess.run([git, '-C', str(root), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                        'commit', '-qm', 'source fixture'], check=True)
        # Re-import the real checker after tests used a stub.
        spec.loader.exec_module(update)
        assert not update.has_local_changes(root, dest)
        with (dest / 'SOURCE.json').open('a') as file: file.write(' ')
        assert update.has_local_changes(root, dest)
        subprocess.run([git, '-C', str(root), 'add', 'DataSources/PinyinPro/SOURCE.json'], check=True)
        assert update.has_local_changes(root, dest)

print('PASS: offline no-op, dirty source, missing normalization, rollback and interrupted recovery')
