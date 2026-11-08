#!/usr/bin/env python3
"""Export generated Mimo tables with their notices for a Windows adapter."""
import argparse
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLES = ('native-pinyin.tsv', 'native-zhuyin.tsv', 'native-japanese.tsv')
NOTICES = {
    'Meowpuut-MIT.txt': 'LICENSE',
    'McBopomofo-MIT.txt': 'DataSources/McBopomofo/LICENSE.txt',
    'McBopomofo-SOURCE.json': 'DataSources/McBopomofo/SOURCE.json',
    'PinyinPro-MIT.txt': 'DataSources/PinyinPro/LICENSE',
    'PinyinPro-SOURCE.json': 'DataSources/PinyinPro/SOURCE.json',
    'UniDic-derived-BSD.txt': 'DataSources/UniDic/LICENSE.txt',
    'UniDic-BSD.txt': 'DataSources/UniDic/UNIDIC-BSD.txt',
    'UniDic-COPYING.txt': 'DataSources/UniDic/UNIDIC-COPYING.txt',
    'UniDic-SOURCE.json': 'DataSources/UniDic/SOURCE.json',
    'ICU-LICENSE.txt': 'DataSources/MimoNormalization/ICU-LICENSE.txt',
    'ICU-Hans_Hant.txt': 'DataSources/MimoNormalization/ICU-Hans_Hant.txt',
    'MimoNormalization-SOURCE.json': 'DataSources/MimoNormalization/SOURCE.json',
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
