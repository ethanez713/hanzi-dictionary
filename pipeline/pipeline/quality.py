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
from pathlib import Path
from typing import Callable, Iterable, Iterator

OLLAMA_URL = "http://localhost:11434/api/generate"
# Chosen for Chinese: the Qwen line is trained on it natively, which matters more here than
# raw size. 4b is the largest of its generation that fits this box's 6 GB card — the 9b
# starts at 6.6 GB. Before trusting this for a long run, check whether it has aged out:
# `ollama list` prints the install date and https://ollama.com/library carries the current
# generation. A model swap invalidates every cached score (see load_cache).
MODEL = "qwen3.5:4b"

# Ten at a time costs 0.74 s/sentence against 3.02 s one at a time and agrees with the
# single-shot score within one point 98% of the time, so the batching is nearly free.
BATCH_SIZE = 10

_PROMPT_HEAD = (
    "你是中文母语者。给每句话打分：5=完全自然地道，4=自然，3=能懂但别扭，"
    "2=有语法或搭配错误，1=不通顺。\n"
    '只输出 JSON：{"r":[{"i":句子编号,"s":分数}]}，每句一条，编号必须与输入一致。\n句子：\n'
)


class ScoringUnavailable(RuntimeError):
    """Ollama is not reachable — the caller decides whether that is fatal."""


def _call_ollama(prompt: str, num_predict: int, timeout: int, model: str) -> dict:
    body = json.dumps({
        "model": model, "prompt": prompt, "stream": False, "format": "json",
        "options": {"temperature": 0, "num_predict": num_predict},
    }).encode()
    request = urllib.request.Request(OLLAMA_URL, body, {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(json.loads(response.read())["response"])
    except urllib.error.URLError as exc:
        raise ScoringUnavailable(f"{OLLAMA_URL} unreachable: {exc}") from exc


def score_batch(sentences: list[str], *, model: str = MODEL, timeout: int = 600) -> list[int | None]:
    """Scores 1-5 for each sentence, None where the model gave no usable answer.

    The response schema echoes each sentence's number and every number is checked back
    against what was sent. An earlier bare {"scores":[...]} array silently returned 19 scores
    for 20 sentences, and a dropped element shifts every score after it onto the wrong
    sentence — a failure that looks like data rather than an error. On any mismatch the batch
    is re-scored one sentence at a time, where there is no alignment to get wrong."""
    if not sentences:
        return []
    prompt = _PROMPT_HEAD + "\n".join(f"{i + 1}. {s}" for i, s in enumerate(sentences))
    try:
        raw = _call_ollama(prompt, 60 * len(sentences) + 40, timeout, model)
        by_index = {
            int(o["i"]): int(o["s"])
            for o in raw.get("r", []) if isinstance(o, dict) and "i" in o and "s" in o
        }
        if all(i + 1 in by_index for i in range(len(sentences))):
            return [_clamp(by_index[i + 1]) for i in range(len(sentences))]
    except (ScoringUnavailable, ValueError, TypeError, KeyError, json.JSONDecodeError):
        if len(sentences) == 1:
            return [None]
    if len(sentences) == 1:
        return [None]
    return [score_batch([s], model=model, timeout=timeout)[0] for s in sentences]


def _clamp(score: int) -> int | None:
    return score if 1 <= score <= 5 else None


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
    batch_size: int = BATCH_SIZE,
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

    with cache_path.open("a", encoding="utf-8") as out:
        for chunk in _chunks(pending, batch_size):
            scores = score_batch([text for _k, text in chunk], model=model)
            for (key, _text), score in zip(chunk, scores):
                if score is None:
                    continue
                cache[key] = score
                out.write(json.dumps({"k": key, "s": score, "m": model}) + "\n")
            out.flush()
            os.fsync(out.fileno())
            done += len(chunk)
            if on_progress:
                on_progress(done, len(pending))
    return cache


def _chunks(rows: list, size: int) -> Iterator[list]:
    for i in range(0, len(rows), size):
        yield rows[i:i + size]
