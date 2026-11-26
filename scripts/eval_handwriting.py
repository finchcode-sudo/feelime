#!/usr/bin/env python3
"""手写样本评测：读设置页导出的 feelime-ink-samples-*.json，统计命中率。

每条样本 = 用户实际点选的字（truth）在当时候选条里的排名（rank，0 起）。
用法：python3 scripts/eval_handwriting.py samples.json [--strokes-by-char]
输出 top1/top3/top5/top10/top20 命中率、平均排名，以及排名靠后的字（调参时重点看）。
样本里的 rank 是记录时线上实际的排名，所以改了重排参数后，对比"改前导出 vs 改后导出"。
"""
import json
import sys
from collections import Counter


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    with open(argv[1], encoding="utf-8") as handle:
        samples = json.load(handle)
    if not samples:
        print("没有样本")
        return 1
    ranks = [s["rank"] for s in samples]
    total = len(ranks)
    for k in (1, 3, 5, 10, 20):
        hit = sum(1 for r in ranks if r < k)
        print(f"top{k:<2}: {hit}/{total} = {hit / total:.1%}")
    print(f"平均排名: {sum(ranks) / total:.2f}")
    strokes = Counter(len(s["ink"]["strokes"]) for s in samples)
    print("笔数分布:", dict(sorted(strokes.items())))
    worst = sorted(samples, key=lambda s: -s["rank"])[:15]
    print("排名最靠后的:")
    for s in worst:
        print(f"  {s['truth']}  rank={s['rank']}  笔数={len(s['ink']['strokes'])}  前三={''.join(s['top'][:3])}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
