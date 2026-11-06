#!/usr/bin/env python3
"""Run with .build/dmg-tools/bin/python after package-macos.sh."""
from pathlib import Path
import hashlib
import os
import plistlib
import dmgbuild
root = Path(__file__).resolve().parent.parent
app = root / '.build/Meowpuut.app'
version = plistlib.loads((app / 'Contents/Info.plist').read_bytes())['CFBundleShortVersionString']
label = os.environ.get('MEOWPUUT_RELEASE_LABEL', version + '-alpha.1')
out = root / '.build/releases'
package = out / f'Meowpuut-{label}-macos-arm64.pkg'
assert package.is_file(), 'Build the fallback package first'
dmg = out / f'Meowpuut-{label}-macos-arm64.dmg'
dmgbuild.build_dmg(str(dmg), '喵撲 Meowpuut', settings={
    'files': [str(app), (str(package), '安裝或更新.pkg')],
    'symlinks': {'輸入法': '/Library/Input Methods'},
    'background': str(root / '.build/installer-background.png'),
    'icon': str(app / 'Contents/Resources/Meowpuut.icns'),
    'window_rect': ((160, 120), (760, 540)),
    'icon_locations': {'Meowpuut.app': (210, 220), '輸入法': (550, 220), '安裝或更新.pkg': (660, 410)},
    'icon_size': 88, 'text_size': 13,
    'default_view': 'icon-view', 'show_toolbar': False, 'show_status_bar': False,
    'show_pathbar': False, 'show_sidebar': False, 'format': 'UDZO',
})
with (out / 'SHA256SUMS.txt').open('w', encoding='utf-8') as checksums:
    for artifact in (dmg, package):
        digest = hashlib.sha256()
        with artifact.open('rb') as content:
            for chunk in iter(lambda: content.read(1024 * 1024), b''):
                digest.update(chunk)
        checksums.write(f'{digest.hexdigest()}  {artifact.name}\n')
