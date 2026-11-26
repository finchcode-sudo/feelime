#!/usr/bin/env python3
"""Generate engine-data/handwriting/char_prior.tsv: per-character priors for
the handwriting candidate reranker (docs/design/handwriting.md §14).

Each line: `char<TAB>weight<TAB>strokes`, in vocab.json order, for exactly the
characters the recognizer can output.
  weight  = max essay.txt frequency over the character's stroke.dict entries
            (stroke.dict.yaml already carries the simplified-aggregated weight,
            see generate-stroke-dict.py).
  strokes = most common stroke-code length (ties -> smaller). The stroke codes
            use the 5-class scheme where one code letter = one written stroke,
            so code length equals the standard stroke count (你=7, 的=8).

Usage: generate-handwriting-prior.py [--check]
"""
import argparse
import collections
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/engine-data'
STROKE = ASSETS / 'rime/stroke.dict.yaml'
VOCAB = ASSETS / 'handwriting/vocab.json'
DST = ASSETS / 'handwriting/char_prior.tsv'


def build() -> str:
    vocab = json.loads(VOCAB.read_text(encoding='utf-8'))
    weight = {}
    lengths = collections.defaultdict(collections.Counter)
    in_body = False
    for line in STROKE.read_text(encoding='utf-8').splitlines():
        if line == '...':
            in_body = True
            continue
        if not in_body or not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) < 3 or len(parts[0]) != 1:
            continue
        char, code, raw = parts[0], parts[1], parts[2]
        if not raw.isdigit():
            continue
        weight[char] = max(weight.get(char, 0), int(raw))
        lengths[char][len(code)] += 1
    rows = []
    missing = []
    for char in vocab:
        if not char:
            continue
        if char not in weight:
            missing.append(char)
            continue
        counter = lengths[char]
        best = max(counter.values())
        strokes = min(length for length, n in counter.items() if n == best)
        rows.append(f'{char}\t{weight[char]}\t{strokes}')
    if missing:
        print(f'note: {len(missing)} vocab chars have no stroke entry: {"".join(missing[:20])}',
              file=sys.stderr)
    return '\n'.join(rows) + '\n'


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    text = build()
    if args.check:
        if not DST.exists() or DST.read_text(encoding='utf-8') != text:
            print('char_prior.tsv is stale; rerun generate-handwriting-prior.py', file=sys.stderr)
            return 1
        return 0
    DST.write_text(text, encoding='utf-8')
    print(f'wrote {DST.relative_to(ROOT)} ({text.count(chr(10))} rows)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
