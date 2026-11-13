#!/usr/bin/env python3
"""Export generated Mimo tables with their notices for a Windows adapter."""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLES = ('mimo_base.bin', 'mimo_pairs.bin', 'native-pinyin.tsv', 'native-zhuyin.tsv', 'native-japanese.tsv')
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('resources', type=Path, help='directory of freshly generated native tables')
    parser.add_argument('output', type=Path, help='new destination directory')
    args = parser.parse_args()
    sources = [(args.resources / name, Path(name)) for name in TABLES]
    for source, _ in sources:
        if not source.is_file():
            parser.error(f'missing file: {source}')
    args.output.mkdir(parents=True, exist_ok=False)
    notices = args.output / 'Licenses'
    subprocess.run([sys.executable, ROOT / 'Engine/scripts/copy-lexicon-notices.py', notices], check=True)
    shutil.copyfile(ROOT / 'ASSETS-LICENSE.md', notices / 'Meowpuut-Artwork.txt')
    for source, target in sources:
        shutil.copyfile(source, args.output / target)
    print(f'Exported Windows lexicon and notices: {args.output}')


if __name__ == '__main__':
    main()
