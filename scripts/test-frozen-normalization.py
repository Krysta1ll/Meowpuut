#!/usr/bin/env python3
"""Verify pinned conversions and optionally all offline build entry points."""
import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'scripts'))
import frozen_normalization as frozen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--integration', action='store_true', help='build all three compiler entry points offline')
args = parser.parse_args()

assert frozen.convert(['誌', '週', '𰻞'], traditional=False) == ['志', '周', '𰻝']
assert frozen.convert(['网络游戏', '短发', '什么样'], traditional=True) == ['網絡遊戲', '短髮', '什麼樣']
try:
    frozen.convert(['new-mimo-source-key-not-in-snapshot'], traditional=True)
except ValueError as error:
    assert 'missing s2t mapping' in str(error) and 'README.md' in str(error)
else:
    raise AssertionError('new source key must not silently use the system converter')

with tempfile.TemporaryDirectory(prefix='meowpuut-frozen-test-') as temporary:
    root = Path(temporary)
    for name in ('SOURCE.json', 'conversions-icu78.2.tsv.gz', 'reviewed-overrides.tsv',
                 'ICU-LICENSE.txt', 'ICU-Hans_Hant.txt'):
        shutil.copyfile(frozen.SOURCE / name, root / name)
    old_source = frozen.SOURCE
    try:
        frozen.SOURCE = root
        frozen._mappings.cache_clear()
        manifest_path = root / 'SOURCE.json'
        original_manifest = manifest_path.read_bytes()
        manifest = json.loads(original_manifest)
        manifest['format_version'] = 999
        manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
        try:
            frozen.convert(['誌'])
        except ValueError as error:
            assert 'Unsupported normalization snapshot version' in str(error)
        else:
            raise AssertionError('unknown snapshot versions must fail')
        manifest = json.loads(original_manifest)
        manifest['snapshot_content_sha256'] = '0' * 64
        manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
        frozen._mappings.cache_clear()
        try:
            frozen.convert(['誌'])
        except ValueError as error:
            assert 'content checksum mismatch' in str(error)
        else:
            raise AssertionError('wrong logical snapshot checksum must fail')
        manifest_path.write_bytes(original_manifest)
        frozen._mappings.cache_clear()
        override_path = root / 'reviewed-overrides.tsv'
        original_overrides = override_path.read_bytes()
        override_path.write_bytes(original_overrides + b'\n')
        try:
            frozen.convert(['誌'])
        except ValueError as error:
            assert 'checksum mismatch' in str(error)
        else:
            raise AssertionError('tampered overrides must fail')
        override_path.write_bytes(original_overrides)
        frozen._mappings.cache_clear()
        snapshot = root / 'conversions-icu78.2.tsv.gz'
        data = bytearray(snapshot.read_bytes())
        data[-1] ^= 1
        snapshot.write_bytes(data)
        try:
            frozen.convert(['誌'])
        except ValueError as error:
            assert 'checksum mismatch' in str(error)
        else:
            raise AssertionError('tampered snapshot must fail')
    finally:
        frozen.SOURCE = old_source
        frozen._mappings.cache_clear()

    # Exercise Git's real CRLF checkout conversion even on non-Windows hosts.
    git = shutil.which('git')
    if git:
        repo = root / 'git-test'
        repo.mkdir()
        files = ('ICU-LICENSE.txt', 'ICU-Hans_Hant.txt', 'reviewed-overrides.tsv', 'conversions-icu78.2.tsv.gz')
        target = repo / 'DataSources/MimoNormalization'
        target.mkdir(parents=True)
        shutil.copyfile(ROOT / '.gitattributes', repo / '.gitattributes')
        for name in files:
            shutil.copyfile(frozen.SOURCE / name, target / name)
        (repo / 'crlf-probe.txt').write_bytes(b'one\ntwo\n')
        subprocess.run([git, 'init', '-q', str(repo)], check=True)
        subprocess.run([git, '-C', str(repo), 'config', 'core.autocrlf', 'true'], check=True)
        subprocess.run([git, '-C', str(repo), 'add', '.gitattributes', 'DataSources', 'crlf-probe.txt'],
                       check=True, capture_output=True)
        checkout = root / 'git-checkout'
        checkout.mkdir()
        subprocess.run([git, '-C', str(repo), 'checkout-index', '--all', '--prefix', str(checkout) + '/'], check=True)
        assert (checkout / 'crlf-probe.txt').read_bytes() == b'one\r\ntwo\r\n', 'CRLF conversion was not exercised'
        for name in files:
            assert (checkout / 'DataSources/MimoNormalization' / name).read_bytes() == (frozen.SOURCE / name).read_bytes(), name
        print('PASS: pinned files survive a Git core.autocrlf=true checkout unchanged')
    else:
        print('SKIP: Git checkout conversion test (git unavailable)')

    if args.integration:
        # Empty PATH ensures neither the ICU CLI nor any system converter can run.
        entrypoints = [
            ('mac-ios', [str(ROOT / 'scripts/compile-native-lexicon.py')]),
            ('android', [str(ROOT / 'scripts/compile-native-lexicon.py')]),
            ('windows', [str(ROOT / 'Windows/compile-lexicon.py')]),
        ]
        outputs = []
        for index, (name, command) in enumerate(entrypoints):
            output = root / name
            extra = ['--uconv'] if name == 'android' else []  # compatibility with older scripts
            env = {**os.environ, 'PATH': '', 'PYTHONHASHSEED': str(index + 1)}
            result = subprocess.run([sys.executable, *command, str(output), *extra], cwd=ROOT,
                                    env=env, text=True, capture_output=True)
            if result.returncode:
                raise AssertionError(f'{name} compiler failed: {result.stdout[-500:]} {result.stderr[-1000:]}')
            outputs.append(output)
        names = ['native-pinyin.tsv', 'native-zhuyin.tsv', 'native-japanese.json', 'native-japanese.tsv',
                 'associations.json', 'mimo-manifest.json', 'lexicon-merge-report.json', 'source-reading-audit.json']
        for name in names:
            hashes = [hashlib.sha256((output / name).read_bytes()).hexdigest() for output in outputs]
            assert len(set(hashes)) == 1, (name, hashes)
        def logical_database(path):
            digest = hashlib.sha256()
            with sqlite3.connect(path) as db:
                assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                for table in ('lexicon', 'words'):
                    columns = len(db.execute(f'PRAGMA table_info({table})').fetchall())
                    order = ','.join(str(i) for i in range(1, columns + 1))
                    for row in db.execute(f'SELECT * FROM {table} ORDER BY {order}'):
                        for value in row:
                            data = str(value).encode('utf-8')
                            digest.update(len(data).to_bytes(4, 'big'))
                            digest.update(data)
            return digest.hexdigest()
        hashes = [logical_database(output / 'language-evidence.sqlite') for output in outputs]
        assert len(set(hashes)) == 1, ('SQLite content differs', hashes)
        print('PASS: macOS/iOS, Android legacy --uconv and Windows entry points build identical indexes offline')
print('PASS: pinned normalization, reviewed forms, missing keys and tampering')
