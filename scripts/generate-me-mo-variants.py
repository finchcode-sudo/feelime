#!/usr/bin/env python3
"""给词库里含「么(me)」的高频词批量生成 mo 变体，写进 builtin-words.tsv 的生成标记块。

口语里「么」常按 mo 打（zenmo / shenmo / zenmohuishi），而词典读音是 me，
T9 九宫格下 6 3 与 6 6 差一个键就命不中。builtin-words.tsv 是词库叠加层，
这里把词频前 TOP 的含 me 词，把读音里的 me 换成 mo 后追加成额外的码。

数据源：scripts/data/luna_pinyin.table.txt（与 generate-t9-syllables.py 同源）。
用法：generate-me-mo-variants.py   （幂等，重跑替换标记块）
"""
import pathlib
import re

TABLE = pathlib.Path("scripts/data/luna_pinyin.table.txt")
TARGET = pathlib.Path("app/src/main/assets/builtin-words.tsv")
BEGIN = "# BEGIN GENERATED ME_MO_VARIANTS"
END = "# END GENERATED ME_MO_VARIANTS"
TOP = 1500


def main() -> int:
    rows = {}
    entry = re.compile(r"^(\S+)\t([a-z ]+)\t([\d.]+(?:e[+-]?\d+)?)\s*$", re.I)
    for line in TABLE.read_text(encoding="utf-8").splitlines():
        m = entry.match(line)
        if not m:
            continue
        word, reading, weight = m.group(1), m.group(2), float(m.group(3))
        syl = reading.split()
        if len(word) < 2 or "me" not in syl or len(syl) != len(word):
            continue
        key = (word, "".join("mo" if s == "me" else s for s in syl))
        rows[key] = max(rows.get(key, 0), weight)
    picked = sorted(rows.items(), key=lambda kv: -kv[1])[:TOP]

    text = TARGET.read_text(encoding="utf-8")
    block_re = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END) + r"\n?", re.S)
    base = block_re.sub("", text)
    existing = {tuple(l.split("\t")[:2]) for l in base.splitlines()
                if l and not l.startswith("#") and "\t" in l}
    lines = [f"{w}\t{c}" for (w, c), _ in picked if (w, c) not in existing]
    block = BEGIN + "\n" + "\n".join(lines) + "\n" + END + "\n"
    if not base.endswith("\n"):
        base += "\n"
    TARGET.write_text(base + block, encoding="utf-8")
    print(f"{len(lines)} me->mo variants written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
