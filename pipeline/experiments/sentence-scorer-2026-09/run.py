"""Does the local model actually measure idiomaticity, or just fluency-shaped noise?

Answers it without anyone hand-labelling Chinese: corrupt sentences the model already
rated highly, in ways that mimic the defect we are hunting, and see whether the score
follows. Corruption manufactures a labelled set for free.

Run:  cd pipeline && PYTHONPATH=. .venv/bin/python experiments/sentence-scorer-2026-09/run.py
Resumable — every score is cached, so a killed run picks up where it stopped.
Pass criteria and how to read the output: see EXPERIMENT.md beside this file.
"""
from __future__ import annotations

import json
import random
import re
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from pipeline import quality  # noqa: E402

HERE = Path(__file__).resolve().parent
PACK = HERE.parents[1] / "data" / "build" / "core.db"
CACHE_DIR = HERE.parents[1] / "data" / "derived" / "scorer-validation"
AUDIO_IDS = CACHE_DIR / "audio_ids.txt"

N_CORRUPT = 240      # originals; each yields up to one corruption of each kind
N_RETEST = 200
N_AUDIO = 200
SEED = 20260905

CLASSIFIERS = ["个", "条", "只", "本", "张", "件", "块", "位", "头", "台", "把", "辆"]


def load_words(con: sqlite3.Connection) -> set[str]:
    return {r[0] for r in con.execute("SELECT simp FROM word")}


def segment(text: str, words: set[str], max_len: int = 6) -> list[str]:
    out, i, n = [], 0, len(text)
    while i < n:
        for size in range(min(max_len, n - i), 1, -1):
            if text[i:i + size] in words:
                out.append(text[i:i + size])
                i += size
                break
        else:
            out.append(text[i])
            i += 1
    return out


def corrupt_transpose(tokens: list[str], rng: random.Random) -> str | None:
    """Swap two adjacent words. Obviously wrong to any reader — the floor test: a model
    that misses this is not reading the sentence at all."""
    spots = [i for i in range(len(tokens) - 1)
             if len(tokens[i]) > 1 and len(tokens[i + 1]) > 1]
    if not spots:
        return None
    i = rng.choice(spots)
    swapped = tokens[:]
    swapped[i], swapped[i + 1] = swapped[i + 1], swapped[i]
    return "".join(swapped)


def corrupt_classifier(text: str, rng: random.Random) -> str | None:
    """Mismatch a measure word — the family the reported bug belongs to (一块金)."""
    match = re.search(r"([一二三两这那几每])([个条只本张件块位头台把辆])", text)
    if not match:
        return None
    replacement = rng.choice([c for c in CLASSIFIERS if c != match.group(2)])
    return text[:match.start(2)] + replacement + text[match.end(2):]


def corrupt_noun_swap(tokens: list[str], donor: list[str], rng: random.Random) -> str | None:
    """Swap a content word for one of the same length from an unrelated sentence: still
    grammatical, but the collocation is wrong. The closest analogue to 一块金."""
    spots = [i for i, t in enumerate(tokens) if len(t) == 2]
    pool = [t for t in donor if len(t) == 2 and t not in tokens]
    if not spots or not pool:
        return None
    swapped = tokens[:]
    swapped[rng.choice(spots)] = rng.choice(pool)
    return "".join(swapped)


def fetch_audio_ids() -> set[int]:
    """Sentence ids with a recorded reading — a positive control. Cached after one fetch."""
    if AUDIO_IDS.exists():
        return {int(x) for x in AUDIO_IDS.read_text().split() if x.isdigit()}
    import bz2
    import io
    import tarfile
    import urllib.request
    url = "https://downloads.tatoeba.org/exports/sentences_with_audio.tar.bz2"
    try:
        with urllib.request.urlopen(url, timeout=300) as response:
            blob = response.read()
    except Exception as exc:  # noqa: BLE001 — a missing control degrades, never blocks
        print(f"  ! could not fetch audio list ({exc}); skipping the audio control")
        return set()
    ids = set()
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:bz2") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith(".csv"))
        for line in tar.extractfile(member).read().decode("utf-8", "replace").splitlines():
            head = line.split("\t")[0]
            if head.isdigit():
                ids.add(int(head))
    AUDIO_IDS.parent.mkdir(parents=True, exist_ok=True)
    AUDIO_IDS.write_text("\n".join(str(i) for i in sorted(ids)))
    return ids


def mean(xs: list[float]) -> float:
    return sum(xs) / len(xs) if xs else float("nan")


def main() -> None:
    started = time.time()
    rng = random.Random(SEED)
    con = sqlite3.connect(f"file:{PACK}?mode=ro", uri=True)
    words = load_words(con)

    rows = con.execute(
        "SELECT tatoeba_id, zh FROM sentence WHERE tatoeba_id IS NOT NULL"
        " AND length(zh) BETWEEN 8 AND 30 GROUP BY tatoeba_id"
    ).fetchall()
    rng.shuffle(rows)

    def progress(label):
        return lambda done, total: (
            print(f"    {label}: {done}/{total}", flush=True) if done % 200 == 0 else None
        )

    results: dict = {"seed": SEED, "model": quality.MODEL, "batch_size": quality.BATCH_SIZE}

    # --- 0. The motivating case ----------------------------------------------------------
    # The observation that started all of this, re-checked against whatever model is
    # configured now. Cheap, and a model that cannot reproduce it has no business scoring
    # 10,000 sentences however well it does on synthetic corruptions.
    print("[0/3] the reported case...", flush=True)
    flagged = "这个项目是一块金。"
    siblings = [zh for (zh,) in con.execute(
        "SELECT zh FROM sentence WHERE simp = '项目' AND zh != ?", (flagged,))]
    case = quality.score_all(
        [(f"c{i}", t) for i, t in enumerate([flagged] + siblings)],
        CACHE_DIR / "known_case.jsonl", on_progress=None)
    sibling_scores = [case[f"c{i}"] for i in range(1, len(siblings) + 1) if f"c{i}" in case]
    results["known_case"] = {
        "flagged_sentence": flagged,
        "flagged_score": case.get("c0"),
        "n_siblings": len(sibling_scores),
        "mean_sibling_score": round(mean(sibling_scores), 3),
    }
    print(f"    flagged={case.get('c0')}  siblings mean={mean(sibling_scores):.2f}", flush=True)

    # --- 1. Corruption sensitivity -------------------------------------------------------
    print("[1/3] scoring originals...", flush=True)
    originals = rows[:N_CORRUPT]
    base = quality.score_all(
        [(f"o{sid}", zh) for sid, zh in originals],
        CACHE_DIR / "originals.jsonl", on_progress=progress("originals"))

    print("[1/3] building and scoring corruptions...", flush=True)
    donor_tokens = [t for _sid, zh in rows[N_CORRUPT:N_CORRUPT + 400] for t in segment(zh, words)]
    variants: dict[str, list[tuple[str, str, int]]] = {"transpose": [], "classifier": [], "noun_swap": []}
    for sid, zh in originals:
        if base.get(f"o{sid}") is None:
            continue
        tokens = segment(zh, words)
        for kind, text in (
            ("transpose", corrupt_transpose(tokens, rng)),
            ("classifier", corrupt_classifier(zh, rng)),
            ("noun_swap", corrupt_noun_swap(tokens, donor_tokens, rng)),
        ):
            if text and text != zh:
                variants[kind].append((f"{kind[:2]}{sid}", text, base[f"o{sid}"]))

    corrupted = quality.score_all(
        [(k, t) for group in variants.values() for k, t, _ in group],
        CACHE_DIR / "corrupted.jsonl", on_progress=progress("corrupted"))

    results["corruption"] = {}
    for kind, group in variants.items():
        pairs = [(before, corrupted[k]) for k, _t, before in group if k in corrupted]
        clean = [(b, a) for b, a in pairs if b >= 4]
        results["corruption"][kind] = {
            "n": len(pairs),
            "n_original_ge4": len(clean),
            "mean_original": round(mean([b for b, _ in pairs]), 3),
            "mean_corrupted": round(mean([a for _, a in pairs]), 3),
            "mean_drop": round(mean([b - a for b, a in pairs]), 3),
            "pct_scored_lower": round(100 * sum(1 for b, a in pairs if a < b) / len(pairs), 1) if pairs else None,
            "pct_caught_at_threshold": round(
                100 * sum(1 for b, a in clean if a <= 2) / len(clean), 1) if clean else None,
        }

    # --- 2. Test-retest ------------------------------------------------------------------
    print("[2/3] test-retest...", flush=True)
    retest_rows = rows[:N_RETEST]
    first = {f"o{sid}": base[f"o{sid}"] for sid, _zh in retest_rows if f"o{sid}" in base}
    shuffled = retest_rows[:]
    rng.shuffle(shuffled)   # different batch grouping, same sentences
    second = quality.score_all(
        [(f"r{sid}", zh) for sid, zh in shuffled],
        CACHE_DIR / "retest.jsonl", on_progress=progress("retest"))
    both = [(first[f"o{sid}"], second[f"r{sid}"])
            for sid, _zh in retest_rows if f"o{sid}" in first and f"r{sid}" in second]
    results["retest"] = {
        "n": len(both),
        "pct_exact": round(100 * sum(1 for a, b in both if a == b) / len(both), 1) if both else None,
        "pct_within_1": round(100 * sum(1 for a, b in both if abs(a - b) <= 1) / len(both), 1) if both else None,
        "mean_abs_diff": round(mean([abs(a - b) for a, b in both]), 3),
    }

    # How much of that instability lands on the decision the filter actually makes. A score
    # that wobbles 4<->5 costs nothing; one that wobbles across the drop threshold decides a
    # sentence's fate on a coin flip, and the mean absolute difference hides the difference.
    threshold_flips = sum(1 for a, b in both if (a <= 2) != (b <= 2))
    condemned = [(a, b) for a, b in both if a <= 2 or b <= 2]
    results["threshold_stability"] = {
        "threshold": 2,
        "n": len(both),
        "pct_verdict_flips": round(100 * threshold_flips / len(both), 1) if both else None,
        "n_condemned_by_either_run": len(condemned),
        "n_condemned_by_both": sum(1 for a, b in condemned if a <= 2 and b <= 2),
    }

    # --- 3. Recorded-audio control -------------------------------------------------------
    print("[3/3] audio control...", flush=True)
    audio_ids = fetch_audio_ids()
    if audio_ids:
        with_audio = [(sid, zh) for sid, zh in rows if sid in audio_ids][:N_AUDIO]
        without = [(sid, zh) for sid, zh in rows if sid not in audio_ids][:N_AUDIO]
        control = quality.score_all(
            [(f"a{sid}", zh) for sid, zh in with_audio] + [(f"n{sid}", zh) for sid, zh in without],
            CACHE_DIR / "audio_control.jsonl", on_progress=progress("audio"))
        a = [control[f"a{sid}"] for sid, _ in with_audio if f"a{sid}" in control]
        n = [control[f"n{sid}"] for sid, _ in without if f"n{sid}" in control]
        results["audio_control"] = {
            "n_with_audio": len(a), "n_without": len(n),
            "mean_with_audio": round(mean(a), 3), "mean_without": round(mean(n), 3),
            "difference": round(mean(a) - mean(n), 3),
        }
    else:
        results["audio_control"] = None

    # --- Verdict against criteria fixed before the run -----------------------------------
    retest = results["retest"]
    corruption = results["corruption"]
    noise = retest["mean_abs_diff"]
    real = [k for k in ("classifier", "noun_swap")
            if (corruption[k]["mean_drop"] or 0) >= noise + 1.0]
    known = results["known_case"]
    checks = {
        "known_case_separated": (known["flagged_score"] is not None
                                 and known["flagged_score"] <= 2
                                 and known["mean_sibling_score"] >= 4),
        "retest_within_1_ge_90": (retest["pct_within_1"] or 0) >= 90,
        "retest_noise_le_0_5": noise <= 0.5,
        "transpose_lower_ge_60pct": (corruption["transpose"]["pct_scored_lower"] or 0) >= 60,
        "a_realistic_corruption_beats_noise_by_1": bool(real),
        "audio_control_not_reversed": (results["audio_control"] is None
                                       or results["audio_control"]["difference"] >= 0),
    }
    # Deliberately not a pass criterion: a single-pass filter is unreliable at the threshold,
    # but that is a fixable property of the filter, not a verdict on the model. The fix — a
    # confirming second pass over only the condemned sentences — is in EXPERIMENT.md.
    results["threshold_stability"]["single_pass_is_reliable"] = (
        results["threshold_stability"]["pct_verdict_flips"] or 0) < 0.5
    results["checks"] = checks
    results["verdict"] = "PROCEED" if all(checks.values()) else "DO NOT PROCEED"
    results["elapsed_minutes"] = round((time.time() - started) / 60, 1)

    (HERE / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(results, indent=2, ensure_ascii=False))
    print(f"\nverdict: {results['verdict']}  ->  {HERE / 'results.json'}")


if __name__ == "__main__":
    main()
