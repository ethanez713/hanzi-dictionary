"""Naturalness scoring for example sentences, from the local Ollama model.

Tatoeba and OPUS both carry sentences that are grammatical but not idiomatic — the audit's
case was 这个项目是一块金 ("this project is a gold nugget"), an English metaphor rendered
word for word. Nothing in the sentence's shape marks it: it is short, common-word, and a
word-bigram model over TED2020 ranks it FIRST of its five siblings, because brevity and word
frequency are what such a model measures. A language model asked the question directly scores
it 2 while its siblings score 4-5, which is the only signal found that separates them.

Scoring is offline and cached rather than part of the build: it is hours of local GPU, and
the pipeline must still build a valid pack on a machine with no Ollama at all.

See docs/audits/gloss-audit-2026-09.md.
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable, Iterable

OLLAMA_URL = "http://localhost:11434/api/generate"
# Chosen for Chinese: the Qwen line is trained on it natively, which matters more here than
# raw size. 4b is the largest of its generation that fits this box's 6 GB card — the 9b
# starts at 6.6 GB. Before trusting this for a long run, check whether it has aged out:
# `ollama list` prints the install date and https://ollama.com/library carries the current
# generation. A model swap invalidates every cached score (see load_cache).
MODEL = "qwen3.5:4b"

# One sentence per request, and this is not negotiable. Scoring ten at a time was three
# times faster and destroyed the result: the score depended on which neighbours a sentence
# was batched with, every one of the 45 sentences that happened to be scored twice disagreed
# with itself, and precision at the drop threshold measured 17% — five good sentences deleted
# per bad one. Alone the model is deterministic and precision is 94%. See
# experiments/sentence-scorer-2026-09/REVIEW-200.md for the failure and REVIEW-400.md for the
# measurement that replaced it.
#
# Two workers is the throughput knob instead: 0.35 s/sentence against 0.50 at one, and four
# is slower again because the GPU is already saturated.
WORKERS = 2

# A bare digit rather than JSON. Measured over the same 400 labelled sentences it scores as
# well (94.3% precision at the drop threshold against 92.2%) and is 3.7x faster, because
# nearly all the generated tokens in the JSON form were punctuation.
_PROMPT_HEAD = (
    "你是中文母语者。给这句话的自然度打分，只输出一个数字"
    "（5=完全自然地道，4=自然，3=别扭，2=有语法或搭配错误，1=不通顺），不要输出别的。\n句子："
)


class ScoringUnavailable(RuntimeError):
    """Ollama is not reachable — the caller decides whether that is fatal."""


def _call_ollama(prompt: str, num_predict: int, timeout: int, model: str) -> str:
    """One scoring call. `think: false` is load-bearing on a reasoning model: left on, the
    current generation puts its whole answer in the `thinking` field and returns an EMPTY
    `response`, so every sentence scores None and the run silently produces nothing. It also
    halves the cost here — there is nothing to reason about in "rate this 1-5", and the
    scores come back identical either way. The `thinking` fallback covers a model or Ollama
    build that ignores the flag, so the failure is at worst slow rather than total."""
    body = json.dumps({
        "model": model, "prompt": prompt, "stream": False, "think": False,
        "options": {"temperature": 0, "num_predict": num_predict},
    }).encode()
    request = urllib.request.Request(OLLAMA_URL, body, {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read())
    except urllib.error.URLError as exc:
        raise ScoringUnavailable(f"{OLLAMA_URL} unreachable: {exc}") from exc
    return payload.get("response") or payload.get("thinking") or ""


def score_one(sentence: str, *, model: str = MODEL, timeout: int = 300) -> int | None:
    """Naturalness 1-5, or None when the model gave no usable answer."""
    try:
        raw = _call_ollama(_PROMPT_HEAD + sentence, 3, timeout, model).strip()
    except ScoringUnavailable:
        raise
    except (ValueError, TypeError, KeyError, json.JSONDecodeError):
        return None
    digit = next((c for c in raw if c.isdigit()), None)
    return int(digit) if digit and "1" <= digit <= "5" else None


def score_batch(sentences: list[str], *, model: str = MODEL, timeout: int = 300) -> list[int | None]:
    """Kept for callers that hand over a list; still one request per sentence."""
    return [score_one(s, model=model, timeout=timeout) for s in sentences]


def load_cache(path: Path, model: str = MODEL) -> dict[str, int]:
    """Scores already on disk **for this model**.

    Rows carry the model that produced them and rows from any other are ignored, so swapping
    models re-scores instead of silently blending two models' judgements into one ranking —
    the scores are only comparable to each other. Rows are never rewritten or deleted; the
    file is append-only so an interrupted run cannot corrupt what came before.

    A half-written final line is dropped rather than raised on: the process is expected to be
    killed mid-run, and one lost score is not worth a crash."""
    if not path.exists():
        return {}
    out: dict[str, int] = {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            try:
                row = json.loads(line)
                if row.get("m") == model:
                    out[row["k"]] = row["s"]
            except (json.JSONDecodeError, KeyError):
                continue
    return out


def score_all(
    items: Iterable[tuple[str, str]],
    cache_path: Path,
    *,
    model: str = MODEL,
    workers: int = WORKERS,
    on_progress: Callable[[int, int], None] | None = None,
) -> dict[str, int]:
    """Score (key, sentence) pairs, resuming from and appending to [cache_path].

    Every batch is appended and fsynced before the next one starts, so a kill at any point
    costs at most the batch in flight — the run is hours long and being interrupted is the
    expected case, not the exceptional one. Failures are deliberately NOT cached, so a
    later run retries them instead of inheriting a hole."""
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache = load_cache(cache_path, model)
    pending = [(k, text) for k, text in items if k not in cache]
    done = 0

    with cache_path.open("a", encoding="utf-8") as out, ThreadPoolExecutor(workers) as pool:
        for (key, _text), score in zip(pending, pool.map(
                lambda kt: score_one(kt[1], model=model), pending)):
            done += 1
            if score is not None:
                cache[key] = score
                out.write(json.dumps({"k": key, "s": score, "m": model},
                                     ensure_ascii=False) + "\n")
                out.flush()
                os.fsync(out.fileno())
            if on_progress:
                on_progress(done, len(pending))
    return cache
