#!/usr/bin/env python3
"""Run with .build/dmg-tools/bin/python after building the macOS app and pkg."""
from pathlib import Path
import hashlib
import os
import plistlib
import subprocess
import dmgbuild
root = Path(__file__).resolve().parent.parent
app = root / '.build/Meowpuut.app'
build = plistlib.loads((app / 'Contents/Info.plist').read_bytes())['CFBundleVersion']
label = os.environ.get('MEOWPUUT_RELEASE_LABEL', f'Sill-26A{int(build):03d}')
out = Path(os.environ.get('MEOWPUUT_RELEASE_DIR', root / '.build/releases')).resolve()
package = out / f'Meowpuut-{label}-arm64.pkg'
assert package.is_file(), 'Build the fallback package first'
dmg = out / f'Meowpuut-{label}-arm64.dmg'
instructions = root / '.build/installer-resources/安裝說明.txt'
instructions.parent.mkdir(parents=True, exist_ok=True)
instructions.write_text(
    '把 Meowpuut.app 拖到右側的「輸入法」資料夾，再登出並重新登入。\n'
    '在「系統設定 → 鍵盤 → 文字輸入 → 編輯」加入 Meowpuut。\n\n'
    '更新時若系統提示喵撲正在使用，可從發行頁面下載同版本的安裝套件：\n'
    'https://github.com/Krysta1ll/Meowpuut/releases\n'
    f'{package.name}\n',
    encoding='utf-8',
)
background = root / '.build/installer-background.png'
subprocess.run(
    ['xcrun', 'swift', str(root / 'scripts/InstallerArt.swift'), str(background)],
    env={**os.environ, 'DEVELOPER_DIR': os.environ.get('MEOWPUUT_XCODE_DIR', '/Applications/Xcode.app/Contents/Developer')},
    check=True,
)
dmgbuild.build_dmg(str(dmg), '喵撲 Meowpuut', settings={
    'files': [str(app), str(instructions)],
    'symlinks': {'輸入法': '/Library/Input Methods'},
    'background': str(background),
    'icon': str(app / 'Contents/Resources/Meowpuut.icns'),
    'window_rect': ((160, 120), (760, 540)),
    'icon_locations': {'Meowpuut.app': (210, 220), '輸入法': (550, 220), instructions.name: (660, 410)},
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
