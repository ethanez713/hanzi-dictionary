"""Re-measure the sentence scorer against the 400 hand-labelled sentences.

The threshold this defends, and the limits of the measurement, are in
docs/sentence-quality-filter.md. Run it after any change to the scoring regime — the prompt,
the model, or the Ollama version — since none of those keep scores comparable.

Corrupting a sentence and checking the score falls measures recall on damage, which a model
biased toward answering 2 passes while destroying the corpus. This measures the opposite and
load-bearing question: of the sentences the filter would hide, how many deserved it.

Run:  cd pipeline && PYTHONPATH=. .venv/bin/python -m pipeline.validate_scorer
Resumable — scores are cached per sentence and per model.
"""
from __future__ import annotations

import json
import sqlite3
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from pipeline import quality

ROOT = Path(__file__).resolve().parents[1]
CACHE_DIR = ROOT / "data" / "derived" / "scorer-validation"
LABELS = CACHE_DIR / "labeled.json"
RESULTS = CACHE_DIR / "classify-results.json"
WORKERS = 2   # 0.39 s/sentence at two; four is slower — the GPU is already saturated


def score_one(text: str) -> int | None:
    return quality.score_batch([text])[0]


def run(texts: list[str], cache_path: Path) -> dict[str, int]:
    cache = quality.load_cache(cache_path)
    todo = [t for t in texts if t not in cache]
    if todo:
        with cache_path.open("a", encoding="utf-8") as out, ThreadPoolExecutor(WORKERS) as ex:
            for text, score in zip(todo, ex.map(score_one, todo)):
                if score is None:
                    continue
                cache[text] = score
                out.write(json.dumps({"k": text, "s": score, "m": quality.MODEL},
                                     ensure_ascii=False) + "\n")
                out.flush()
    return cache


def main() -> None:
    labels = json.loads(LABELS.read_text(encoding="utf-8"))
    bad, good = labels["bad"], labels["good"]
    started = time.time()
    scores = run(bad + good, CACHE_DIR / "classify.jsonl")
    b = [scores[t] for t in bad if t in scores]
    g = [scores[t] for t in good if t in scores]

    con = sqlite3.connect(f"file:{ROOT / 'data/build/core.db'}?mode=ro", uri=True)
    from_tatoeba = {zh for zh, in con.execute(
        "SELECT DISTINCT zh FROM sentence WHERE tatoeba_id IS NOT NULL")}

    results: dict[str, Any] = {"model": quality.MODEL, "n_bad": len(b), "n_good": len(g),
               "mean_bad": round(sum(b) / len(b), 2), "mean_good": round(sum(g) / len(g), 2),
               "elapsed_minutes": round((time.time() - started) / 60, 1), "thresholds": {}}

    for cut in (1, 2, 3):
        tp = sum(1 for s in b if s <= cut)          # defective, correctly dropped
        fp = sum(1 for s in g if s <= cut)          # sound, wrongly deleted
        precision = tp / (tp + fp) if tp + fp else None
        recall = tp / len(b)
        results["thresholds"][f"drop_at_{cut}"] = {
            "precision": round(precision, 3) if precision is not None else None,
            "recall": round(recall, 3),
            "f1": round(2 * precision * recall / (precision + recall), 3)
            if precision and recall else None,
            "sound_sentences_deleted": fp,
            "defective_sentences_caught": tp,
        }

    # Same measure over Tatoeba-only sentences, where the two classes share a source and the
    # OPUS/Tatoeba split cannot be doing the work.
    tb = [scores[t] for t in bad if t in scores and t in from_tatoeba]
    tg = [scores[t] for t in good if t in scores and t in from_tatoeba]
    tp = sum(1 for s in tb if s <= 2)
    fp = sum(1 for s in tg if s <= 2)
    results["tatoeba_only_drop_at_2"] = {
        "n_bad": len(tb), "n_good": len(tg),
        "precision": round(tp / (tp + fp), 3) if tp + fp else None,
        "recall": round(tp / len(tb), 3) if tb else None,
    }
    results["distribution"] = {
        "bad": {str(v): sum(1 for s in b if s == v) for v in range(1, 6)},
        "good": {str(v): sum(1 for s in g if s == v) for v in range(1, 6)},
    }
    # A filter is only worth shipping if it deletes far more defects than sound sentences.
    at2 = results["thresholds"]["drop_at_2"]
    results["verdict"] = ("SHIP" if (at2["precision"] or 0) >= 0.7 and at2["recall"] >= 0.3
                          else "DO NOT SHIP")
    RESULTS.write_text(
        json.dumps(results, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(results, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
