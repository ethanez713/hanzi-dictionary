"""Dump every sentence-quality judgement for independent review.

The filter hides sentences on the say-so of a small local model measured at ~95% precision,
which means roughly one in twenty of the hidden ones does not deserve it. Nothing is deleted,
so this exists to make the calls checkable by someone who was not involved in making them —
and to make the corpus recoverable if the judgements turn out badly.

    python -m pipeline.review_sentences                # summary + review.tsv
    python -m pipeline.review_sentences --limit 200    # just the first N hidden ones

Undoing the filter entirely needs no rebuild: drop `AND shown = 1` from `sentencesForWord`
in Hanzi.sq, or `UPDATE sentence SET shown = 1` on the pack.
"""
from __future__ import annotations

import argparse
import csv
import sqlite3
from pathlib import Path

PACK = Path(__file__).resolve().parents[1] / "data" / "build" / "core.db"
OUT = Path(__file__).resolve().parents[1] / "data" / "derived" / "sentence-review.tsv"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pack", type=Path, default=PACK)
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--limit", type=int, default=0, help="print this many hidden ones")
    args = ap.parse_args()

    con = sqlite3.connect(f"file:{args.pack}?mode=ro", uri=True)
    columns = {r[1] for r in con.execute("PRAGMA table_info(sentence)")}
    if not {"quality", "shown"} <= columns:
        raise SystemExit(
            f"{args.pack} predates the quality columns — rebuild it first:\n"
            "  python -m pipeline.build && python -m pipeline.package")
    rows = con.execute("""
        SELECT simp, quality, shown, zh, COALESCE(en, ''),
               CASE WHEN tatoeba_id IS NULL THEN 'opus' ELSE 'tatoeba' END
        FROM sentence
        ORDER BY shown, COALESCE(quality, 9), simp
    """).fetchall()

    total = len(rows)
    scored = sum(1 for r in rows if r[1] is not None)
    hidden = sum(1 for r in rows if not r[2])
    kept_bad = sum(1 for r in rows if r[2] and r[1] is not None and r[1] <= 2)
    print(f"sentences {total:,} | scored {scored:,} ({scored / total:.0%}) | "
          f"hidden {hidden:,} ({hidden / total:.1%}) | "
          f"low-scoring but shown as a word's only example {kept_bad:,}")
    print("  by score:", {v: sum(1 for r in rows if r[1] == v) for v in range(1, 6)})

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t", quoting=csv.QUOTE_MINIMAL)
        w.writerow(["word", "quality", "shown", "zh", "en", "source"])
        w.writerows(rows)
    print(f"  wrote {args.out}  (hidden sentences first, worst score first)")

    for simp, q, shown, zh, en, src in rows[:args.limit]:
        if not shown:
            print(f"    [{q}] {simp}\t{zh}\t{en[:60]}")


if __name__ == "__main__":
    main()
