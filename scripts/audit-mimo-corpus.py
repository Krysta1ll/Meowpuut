#!/usr/bin/env python3
"""Audit an already compiled mimo lexicon; never change its words or source files.

Example: python3 scripts/audit-mimo-corpus.py --resources .build/Meowpuut.app/Contents/Resources --output /tmp/mimo-review.json
The report contains evidence for human review, not automatic deletion decisions.
"""
import argparse
import collections
import contextlib
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sqlite3
import sys

ROOT = Path(__file__).resolve().parent.parent


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda: file.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def additional_source_hashes(root):
    result = {}
    for name in ('mcbopomofo-retained.tsv', 'source-reading-exclusions.json',
                 'reviewed-simplified-exclusions.tsv', 'simplified-priorities.tsv'):
        path = root / 'Resources/Lexicon' / name
        if path.exists() and not path.is_file():
            raise ValueError(f'Expected a source file: {path}')
        # Keep missing and present sources distinguishable in historical audits.
        result[name] = sha256(path) if path.is_file() else None
    return result


def classify(traditional, readings, pinned):
    """Classify evidence, never assert that another form is invalid."""
    target = pinned.get(('t2s', traditional))
    reverse = pinned.get(('s2t', target)) if target is not None else None
    paired = any(item['hans'] == target and item['mcb_exact_pair'] and
                 item['pinyinpro_same_reading_frequency'] is not None for item in readings)
    if paired and reverse == traditional:
        result = 'two_sources_and_pinned_roundtrip'
    elif paired:
        result = 'two_sources_pinned_t2s_reverse_unavailable_or_ambiguous'
    elif target is not None and any(item['hans'] == target for item in readings):
        result = 'pinned_t2s_matches_one_variant_needs_review'
    else:
        result = 'unresolved'
    return result, target, reverse

def read_score_rows(connection):
    """Return traditional/simplified scores, falling back for V1 databases."""
    columns = {row[1] for row in connection.execute('PRAGMA table_info(lexicon)')}
    dual = 'hans_weight' in columns
    simplified = 'hans_weight' if dual else 'weight'
    query = f'SELECT hant,hans,toned,weight,{simplified} FROM lexicon'
    return connection.execute(query), dual

def ordered_variants(hant, toned, values, pinyin_evidence, mcb):
    """Rank simplified variants by their simplified score, not provenance."""
    return [{'hans': hans, 'traditional_weight': weights[0], 'hans_weight': weights[1],
             'pinyinpro_same_reading_frequency': pinyin_evidence.get((hans, toned)),
             'mcb_exact_pair': (hant, hans, toned) in mcb}
            for hans, weights in sorted(values.items(), key=lambda item: (-item[1][1], item[0]))]


def audit(resources):
    import frozen_normalization
    spec = importlib.util.spec_from_file_location('mimo_lexicon_compiler', ROOT / 'scripts/compile-native-lexicon.py')
    compiler = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compiler)
    database = resources / 'language-evidence.sqlite'
    manifest_path = resources / 'mimo-manifest.json'
    merge_report_path = resources / 'lexicon-merge-report.json'
    for path in (database, manifest_path, merge_report_path):
        if not path.is_file():
            raise FileNotFoundError(f'Missing compiled resource: {path}')
    database_hash = sha256(database)
    pinned = frozen_normalization._mappings()
    pinyinpro_path = ROOT / 'DataSources/PinyinPro/complete.json'
    pinyinpro = json.loads(pinyinpro_path.read_text(encoding='utf-8'))
    pinyin_evidence = {}
    for text, entry in pinyinpro.items():
        try:
            toned = ''.join(compiler.numbered_pinyin(part) for part in entry[0].split())
            pinyin_evidence[text, toned] = float(entry[1])
        except (TypeError, IndexError, ValueError):
            continue
    # Source loader validates source snapshot hashes and never writes results.
    # Redirect diagnostics so JSON emitted to stdout remains machine-readable.
    with contextlib.redirect_stdout(sys.stderr):
        imported = compiler.imported_words(None)
    mcb = {(hant, hans, re.sub(r'\s+', '', reading)) for hant, hans, reading in imported}

    connection = sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True)
    try:
        grouped = collections.defaultdict(dict)
        all_traditional, all_simplified = set(), set()
        rows = mismatched_length = missing_text = 0
        score_rows, dual_weights = read_score_rows(connection)
        for hant, hans, toned, weight, hans_weight in score_rows:
            rows += 1
            if not hant or not hans or not toned:
                missing_text += 1
                continue
            all_traditional.add(hant)
            all_simplified.add(hans)
            syllables = sum(char in '12345' for char in toned)
            if len(hant) != len(hans) or len(hans) != syllables:
                mismatched_length += 1
            values = grouped[hant, toned]
            previous = values.get(hans, (0, 0))
            values[hans] = (max(weight, previous[0]), max(hans_weight, previous[1]))
        # Different traditional forms sharing a simplified spelling are
        # potentially meaningful alternatives, not automatic duplicates.
        traditional_variants = []
        score_column = 'hans_weight' if dual_weights else 'weight'
        query = (f'SELECT hans,toned,MAX(weight),MAX({score_column}),group_concat(DISTINCT hant) FROM lexicon '
                 'GROUP BY hans,toned HAVING count(DISTINCT hant)>1 '
                 f'ORDER BY MAX({score_column}) DESC,hans,toned')
        for hans, toned, max_weight, max_hans_weight, forms in connection.execute(query):
            traditional_variants.append({'simplified': hans, 'toned': toned,
                                         'max_traditional_weight': max_weight, 'max_hans_weight': max_hans_weight,
                                         'traditional_forms': sorted(forms.split(',')),
                                         'classification': 'distinct_forms_review_not_automatic_duplicates'})
    finally:
        connection.close()
    conflicts = [(hant, toned, max(weights[1] for weights in values.values()), values)
                 for (hant, toned), values in grouped.items() if len(values) > 1]
    conflicts.sort(key=lambda item: (-item[2], item[0], item[1]))
    categories = collections.Counter()
    results = []
    for hant, toned, max_hans_weight, values in conflicts:
        variants = ordered_variants(hant, toned, values, pinyin_evidence, mcb)
        classification, target, reverse = classify(hant, variants, pinned)
        categories[classification] += 1
        results.append({'traditional': hant, 'toned': toned,
                        'max_traditional_weight': max(weights[0] for weights in values.values()),
                        'max_hans_weight': max_hans_weight,
                        'pinned_t2s': target, 'pinned_reverse': reverse,
                        'classification': classification, 'variants': variants})

    # Phrase-level equivalent words need not correspond character by character:
    # e.g. 紀錄/记录 does not prove that 紀 is simplified as 记. Record
    # suspicious generated pairs for review, without changing the dictionary.
    derived = []
    overlay = ROOT / 'Resources/Lexicon/chinese.tsv'
    for number, line in enumerate(overlay.read_text(encoding='utf-8').splitlines(), 1):
        if not line or line.startswith('#'):
            continue
        fields = line.split('|')
        if len(fields) != 4:
            raise ValueError(f'Invalid reviewed overlay row {number}')
        hant, hans, reading, priority = fields
        sounds = reading.split()
        if len(hant) != len(hans) or len(hans) != len(sounds) or len(hant) < 2:
            continue
        for traditional, simplified, sound in zip(hant, hans, sounds):
            target = pinned.get(('t2s', traditional))
            if target is None or target == simplified:
                continue
            toned = compiler.canonical_pinyin(sound)
            derived.append({'line': number, 'phrase_traditional': hant, 'phrase_simplified': hans,
                            'traditional': traditional, 'derived_simplified': simplified,
                            'toned': toned, 'priority': int(priority), 'pinned_t2s': target,
                            'present_in_compiled_lexicon': simplified in grouped.get((traditional, toned), {}),
                            'mcb_exact_pair': (traditional, simplified, toned) in mcb,
                            'pinyinpro_same_reading_frequency': pinyin_evidence.get((simplified, toned))})
    derived.sort(key=lambda item: (-item['priority'], item['line'], item['traditional']))
    source_manifest = ROOT / 'DataSources/MimoNormalization/SOURCE.json'
    manifest = json.loads(source_manifest.read_text(encoding='utf-8'))
    extra_sources = additional_source_hashes(ROOT)
    if sha256(database) != database_hash:
        raise ValueError('Compiled SQLite changed during audit; retry after the build finishes')
    return {
        'schema_version': 2,
        'method': ('Read-only triage of the compiled SQLite index and current source evidence. '
                   'Matches and roundtrips do not prove that other forms are wrong; review manually. '
                   'Scores are ranking priors, not proof of which source supplied an entry. '
                   'Source hashes identify the inspected files, not proof that the database was built from them.'),
        'inputs': {'sqlite_sha256': database_hash, 'generator_sha256': sha256(ROOT / 'scripts/compile-native-lexicon.py'),
                   'pinyinpro_sha256': sha256(pinyinpro_path), 'overlay_sha256': sha256(overlay),
                   'mcb_manifest_sha256': sha256(ROOT / 'DataSources/McBopomofo/SOURCE.json'),
                   'additional_lexicon_sources_sha256': extra_sources,
                   'normalization_manifest_sha256': sha256(source_manifest),
                   'normalization_snapshot_sha256': manifest['snapshot_sha256'],
                   'normalization_overrides_sha256': manifest['overrides_sha256']},
        'compiled_manifest': json.loads(manifest_path.read_text(encoding='utf-8')),
        'compiled_merge_report': json.loads(merge_report_path.read_text(encoding='utf-8')),
        'simplified_score_column': 'hans_weight' if dual_weights else 'weight (V1 fallback)',
        'counts': {'rows': rows, 'traditional_texts': len(all_traditional),
                   'simplified_texts': len(all_simplified), 'empty_field_rows': missing_text,
                   'text_syllable_alignment_errors': mismatched_length,
                   'conflicting_groups': len(results), 'classifications': dict(sorted(categories.items())),
                   'traditional_variant_groups': len(traditional_variants),
                   'derived_pair_review_items': len(derived)},
        'conflicting_mappings': results,
        'traditional_variants': traditional_variants,
        'derived_pair_review': derived,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / '.build/Meowpuut.app/Contents/Resources',
                        help='directory containing compiled mimo resources')
    parser.add_argument('--output', type=Path, help='new JSON file; existing files are never overwritten (default: stdout)')
    args = parser.parse_args()
    if args.output and args.output.exists():
        parser.error(f'Output already exists: {args.output}')
    report = json.dumps(audit(args.resources), ensure_ascii=False, indent=2) + '\n'
    if args.output:
        with args.output.open('x', encoding='utf-8') as file:
            file.write(report)
        print(f'Wrote review report: {args.output}', file=sys.stderr)
    else:
        sys.stdout.write(report)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f'Audit failed: {error}', file=sys.stderr)
        sys.exit(1)
