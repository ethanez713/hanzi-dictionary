"""Score the held-out TEST half with the prompt chosen on DEV. Run this once.

Twelve variants were compared on 200 dev sentences, so the best dev score is partly luck —
the gap between a variant's dev and test numbers is the only honest estimate of how much.
This also re-scores the incumbent baseline on test, because "the new prompt wins" has to mean
it beats what is already shipping, on data neither prompt was chosen on.

    python experiments/sentence-scorer-2026-09/confirm.py <variant_name> [<variant_name> ...]
"""
from __future__ import annotations

import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from prompts import D, WORKERS, build_variants, metrics, score  # noqa: E402

HERE = Path(__file__).resolve().parent


def main() -> None:
    names = sys.argv[1:] or ["A_baseline_zh"]
    split = json.loads((D / "split.json").read_text(encoding="utf-8"))
    bank = json.loads((D / "bank.json").read_text(encoding="utf-8"))
    variants = build_variants(bank)
    dev_results = json.loads((HERE / "prompt-results.json").read_text(encoding="utf-8"))

    cache_path = D / "confirm.jsonl"
    cache = {}
    if cache_path.exists():
        for line in cache_path.read_text(encoding="utf-8").splitlines():
            try:
                r = json.loads(line)
                cache[(r["v"], r["k"])] = r["s"]
            except (json.JSONDecodeError, KeyError):
                continue

    out_rows = {}
    with cache_path.open("a", encoding="utf-8") as out:
        for name in names:
            spec = variants[name]
            todo = [t for t in split["test"]["bad"] + split["test"]["good"]
                    if (name, t) not in cache]
            if todo:
                with ThreadPoolExecutor(WORKERS) as ex:
                    for text, s in zip(todo, ex.map(lambda t: score(t, spec), todo)):
                        if s is None:
                            continue
                        cache[(name, text)] = s
                        out.write(json.dumps({"v": name, "k": text, "s": s},
                                             ensure_ascii=False) + "\n")
                        out.flush()
            b = [cache[(name, t)] for t in split["test"]["bad"] if (name, t) in cache]
            g = [cache[(name, t)] for t in split["test"]["good"] if (name, t) in cache]
            m = metrics(b, g)
            dev = dev_results.get(name, {})
            m["dev_recall_at_p95"] = dev.get("recall_at_p95")
            m["dev_minus_test"] = (round(dev["recall_at_p95"] - m["recall_at_p95"], 3)
                                   if dev.get("recall_at_p95") is not None else None)
            out_rows[name] = m
            print(f"{name:<22} TEST recall@P95={m['recall_at_p95']:<6} (dev was "
                  f"{m['dev_recall_at_p95']}, drop {m['dev_minus_test']})")

    (HERE / "confirm-results.json").write_text(
        json.dumps(out_rows, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
