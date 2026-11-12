#!/usr/bin/env python3
"""Export generated Mimo tables with their notices for a Windows adapter."""
import argparse
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLES = ('mimo_base.bin', 'mimo_pairs.bin', 'native-pinyin.tsv', 'native-zhuyin.tsv', 'native-japanese.tsv')
NOTICES = {
    'Meowpuut-MIT.txt': 'LICENSE',
    'mimo-tc-MIT.txt': 'DataSources/mimo-tc/LICENSE.txt',
    'OpenCC-Apache-2.0.txt': 'DataSources/mimo-tc/OPENCC-LICENSE.txt',
    'jieba-MIT.txt': 'DataSources/mimo-tc/JIEBA-LICENSE.txt',
    'mimo-tc-SOURCE.json': 'DataSources/mimo-tc/SOURCE.json',
    'mimo-sc-MIT.txt': 'DataSources/mimo-sc/LICENSE',
    'mimo-sc-SOURCE.json': 'DataSources/mimo-sc/SOURCE.json',
    'mimo-jp-derived-BSD.txt': 'DataSources/mimo-jp/LICENSE.txt',
    'mimo-jp-BSD.txt': 'DataSources/mimo-jp/BSD.txt',
    'mimo-jp-COPYING.txt': 'DataSources/mimo-jp/COPYING.txt',
    'mimo-jp-SOURCE.json': 'DataSources/mimo-jp/SOURCE.json',
    'mimo-jp-Mozc-BSD.txt': 'DataSources/mimo-jp/MOZC-LICENSE.txt',
    'mimo-jp-Unicode-3.0.txt': 'DataSources/mimo-jp/UNICODE-LICENSE.txt',
    'mimo-pairs-SOURCE.json': 'DataSources/mimo-pairs/SOURCE.json',
    'mimo-pairs-Apache-2.0.txt': 'DataSources/mimo-pairs/Apache-2.0.txt',
    'mimo-pairs-CC-BY-4.0.txt': 'DataSources/mimo-pairs/CC-BY-4.0.txt',
    'mimo-names-SOURCE.json': 'DataSources/mimo-names/SOURCE.json',
    'mimo-phrases-SOURCE.json': 'DataSources/mimo-phrases/SOURCE.json',
    'mimo-pairs-Tatoeba-CC-BY-2.0-FR.html': 'DataSources/mimo-pairs/CC-BY-2.0-FR.html',
    'mimo-pairs-CommonVoice-CC0-1.0.txt': 'DataSources/mimo-pairs/CC0-1.0.txt',
    'mimo-latin-SOURCE.json': 'DataSources/mimo-latin/SOURCE.json',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('resources', type=Path, help='directory of freshly generated native tables')
    parser.add_argument('output', type=Path, help='new destination directory')
    args = parser.parse_args()
    sources = [(args.resources / name, Path(name)) for name in TABLES]
    sources += [(ROOT / path, Path('Licenses') / name) for name, path in NOTICES.items()]
    for source, _ in sources:
        if not source.is_file():
            parser.error(f'missing file: {source}')
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / 'Licenses').mkdir()
    for source, target in sources:
        shutil.copyfile(source, args.output / target)
    print(f'Exported Windows lexicon and notices: {args.output}')


if __name__ == '__main__':
    main()
