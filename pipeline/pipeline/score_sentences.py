"""Score the pack's example sentences for naturalness, offline, on the local model.

Hours of local GPU, so it runs on its own and caches every score; `pipeline.build` reads the
cache when it exists and ignores it when it doesn't. Interrupt it freely — rerunning resumes.

    python -m pipeline.score_sentences --scope hsk14    # ~11k sentences, ~2.7 h
    python -m pipeline.score_sentences --scope common   # ~25k sentences, ~6 h
    python -m pipeline.score_sentences --scope all      # ~57k sentences, ~14 h

Scope exists because most of the corpus cannot benefit: a word with a single example keeps it
whatever it scores (build_sentences never empties a word), and an obscure word's example is
not worth the GPU. Every scope therefore covers only words with more than one sentence.
"""
from __future__ import annotations

import argparse
import sqlite3
import time
from pathlib import Path

from pipeline import quality

PACK = Path(__file__).resolve().parents[1] / "data" / "build" / "core.db"
CACHE = Path(__file__).resolve().parents[1] / "data" / "derived" / "sentence-quality.jsonl"

# Each scope adds a restriction to "the word has more than one sentence", which is the
# condition for a score to be able to change anything at all.
SCOPES = {
    "hsk14": "AND (w.hsk <= 4 OR w.freq_rank <= 2000)",
    "common": "AND (w.hsk IS NOT NULL OR w.freq_rank <= 5000)",
    "all": "",
}


# Measured over the corpus itself at two workers: 0.89 s/sentence on mains, 0.99 on battery.
# It does not track sentence length, because the 445-token rules-and-examples prompt head is
# ~95% of every request (0.65 s of prompt eval against 0.04 s of generation) and Ollama
# re-evaluates it in full each time rather than reusing the identical prefix.
SECONDS_PER_SENTENCE = 0.9


def estimate_hours(texts: list[str]) -> float:
    return len(texts) * SECONDS_PER_SENTENCE / 3600


def sentences_for(scope: str) -> list[tuple[str, str]]:
    """Sentences to score, **most learner-relevant first**.

    The full run is many hours, so the order decides what a pack rebuilt part-way through
    gets: HSK words ahead of the long tail, low levels ahead of high, frequent words ahead of
    rare. Stopping early then costs the least useful scores rather than a random slice, and
    the per-sentence cache makes resuming free."""
    con = sqlite3.connect(f"file:{PACK}?mode=ro", uri=True)
    rows = con.execute(f"""
        SELECT s.zh FROM sentence s
        JOIN word w ON w.simp = s.simp
        WHERE s.simp IN (SELECT simp FROM sentence GROUP BY simp HAVING COUNT(*) > 1)
        {SCOPES[scope]}
        GROUP BY s.zh
        ORDER BY MIN(COALESCE(w.hsk, 99)), MIN(COALESCE(w.freq_rank, 999999))
    """).fetchall()
    con.close()
    # Keyed by the text, not a sentence id: the OPUS rows have no id, and identical text
    # attached to two words is one judgement, not two.
    return [(zh, zh) for (zh,) in rows]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=sorted(SCOPES), default="hsk14")
    parser.add_argument("--cache", type=Path, default=CACHE)
    args = parser.parse_args()

    items = sentences_for(args.scope)
    cached = quality.load_cache(args.cache)
    pending = [text for k, text in items if k not in cached]
    print(f"scope={args.scope}: {len(items)} sentences, "
          f"{len(items) - len(pending)} already cached, {len(pending)} to score")
    print(f"estimated {estimate_hours(pending):.1f} h — safe to interrupt, rerun to resume\n")

    started = time.time()

    def report(done: int, total: int) -> None:
        if done % 500 and done != total:
            return
        rate = (time.time() - started) / max(done, 1)
        print(f"  {done}/{total}  {rate:.2f}s/sentence  "
              f"~{(total - done) * rate / 60:.0f} min left", flush=True)

    try:
        cache = quality.score_all(items, args.cache, on_progress=report)
    except quality.ScoringUnavailable as exc:
        raise SystemExit(f"{exc}\nStart Ollama (`ollama serve`) and rerun; nothing is lost.")
    except KeyboardInterrupt:
        raise SystemExit(f"\ninterrupted — {len(quality.load_cache(args.cache))} scores kept "
                         f"in {args.cache}; rerun to resume")

    scored = [cache[k] for k, _ in items if k in cache]
    print(f"\n{len(scored)} scored -> {args.cache}")
    for value in range(1, 6):
        n = sum(1 for s in scored if s == value)
        print(f"  score {value}: {n:>6} ({100 * n / max(len(scored), 1):.1f}%)")


if __name__ == "__main__":
    main()
