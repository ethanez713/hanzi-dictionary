# Sentence-scorer validation — read this first

**Ephemeral.** Delete this whole directory once the sentence-quality filter has shipped or
been abandoned. It exists to answer one question and then stop existing.

## The question

The pack ships example sentences that are grammatical but not idiomatic — the case that
started this was 这个项目是一块金 ("this project is a gold nugget"), an English metaphor
rendered word for word. Sentences are currently ranked by **length alone**, and nothing in a
bad sentence's shape marks it: a word-bigram model over TED2020 ranked that one *first* of
its five siblings, because brevity and word frequency are what such a model measures.

Asked directly, the local `qwen2.5:7b` scores it 2 while its siblings score 4-5. That is one
case. **This experiment asks whether the model is measuring idiomaticity at all, or noise
that happened to land right once** — before anyone spends hours of GPU on it.

No human labels Chinese anywhere in this. Corrupting sentences the model already rated highly
manufactures a labelled set for free.

Background and the surrounding fixes: `docs/audits/gloss-audit-2026-09.md`.

## Reviewing the result

```bash
cat pipeline/experiments/sentence-scorer-2026-09/results.json     # everything you need
```

`results.json` ends with `checks` (each criterion, true/false) and a `verdict` of `PROCEED`
or `DO NOT PROCEED`. The criteria below were fixed before any data was collected; do not
move them to fit the numbers. If the file is missing or `verdict` is absent, the run did not
finish — see *Rerunning* below.

| Field | Reading it |
| --- | --- |
| `retest.pct_within_1`, `retest.mean_abs_diff` | The **noise floor**. The same sentences scored twice in different batch groupings. Everything else is only meaningful against this. |
| `corruption.transpose` | Two adjacent words swapped — obviously wrong to any reader. The **floor test**: a model that misses this is not reading the sentence. |
| `corruption.classifier` | Measure word mismatched (一块 → 一条). The family the reported bug belongs to. |
| `corruption.noun_swap` | A content word replaced by one from an unrelated sentence: still grammatical, wrong collocation. The closest analogue to 一块金. |
| `mean_drop` | Original score minus corrupted score. Compare against `retest.mean_abs_diff` — a drop smaller than the noise floor means nothing. |
| `pct_caught_at_threshold` | Of corruptions whose original scored ≥4, the share the model pushed to ≤2 — i.e. what the shipped filter would actually catch. |
| `audio_control` | Sentences with a recorded Tatoeba reading vs without. A weak positive control; a **negative** difference means the score is tracking something other than quality. |

### Pass criteria (fixed 2026-09-05, before the run)

1. `retest.pct_within_1` ≥ 90 and `retest.mean_abs_diff` ≤ 0.5 — below this the score is too
   unstable to threshold, and nothing else in the file matters.
2. `corruption.transpose.pct_scored_lower` ≥ 60.
3. `classifier` **or** `noun_swap` has `mean_drop` ≥ `retest.mean_abs_diff` + 1.0.
4. `audio_control.difference` ≥ 0 (or the control is `null`, if Tatoeba's audio list could
   not be fetched — that degrades the evidence but does not fail the run).

`verdict` is `PROCEED` only when all four hold.

### What to do with each verdict

- **PROCEED** — run the production pass below.
- **DO NOT PROCEED** — read which check failed before touching anything.
  - Only (1) failed: the model is unstable, not wrong. Try `BATCH_SIZE = 5` in
    `pipeline/quality.py` (measured at 100% single-shot agreement against 98% at ten) and
    rerun; the run gets slower, not worse.
  - (2) failed: the model is not reading Chinese at this size. Do not tune the prompt — try a
    different local model, or drop the idea. **Do not send sentences to a cloud model**; this
    is a token-cost decision the user has already made.
  - (3) failed but (2) passed: it detects grammar, not idiomaticity. That is exactly the
    n-gram failure again and does not fix the reported bug. Abandon the filter and leave the
    sentence ranking as it is — the honest outcome, and the one this experiment exists to
    make cheap.
  - (4) failed: investigate before trusting anything else; a reversed control usually means
    the sample is confounded (audio sentences are older and shorter), not that quality is
    inverted.

## Rerunning

Everything is cached per sentence in `pipeline/data/derived/scorer-validation/` (gitignored),
appended and fsynced after every batch. A kill at any point costs at most ten scores.

```bash
cd pipeline
PYTHONPATH=. .venv/bin/python experiments/sentence-scorer-2026-09/run.py
```

Rerunning is free for work already done — it re-scores nothing. Needs Ollama up
(`curl -s localhost:11434/api/tags`); if it is down the script says so and keeps its cache.
Deleting a `*.jsonl` under that directory forces that stage to be re-scored.

## The production pass (not yet run)

Only after a `PROCEED` verdict.

```bash
cd pipeline
PYTHONPATH=. .venv/bin/python -m pipeline.score_sentences --scope hsk14
```

~10,700 sentences, ~3.5 h on the RTX 3050, writing
`pipeline/data/derived/sentence-quality.jsonl`. Interrupt it whenever; rerun to resume. It
prints a rate and an ETA every 500 sentences, and a score histogram at the end.

Scope is a knob because most of the corpus cannot benefit — a word with one example keeps it
whatever it scores, and an obscure word's example is not worth the GPU:

| `--scope` | Sentences | GPU | Covers |
| --- | --- | --- | --- |
| `hsk14` | ~10,700 | ~3.5 h | HSK 1-4 or top-2000 frequency |
| `common` | ~24,600 | ~10 h | any HSK level or top-5000 frequency |
| `all` | ~57,300 | ~30 h | everything, including the OPUS/TED2020 rows |

Counts are distinct sentence *texts*, which is what gets scored — the same sentence attached
to two words is one judgement. Times are measured at 1.5 s/sentence for a 13-character
sentence and scale with length, so the CLI's own estimate is the one to trust.

The cache is keyed by sentence text, so a later wider scope only scores what the narrower one
did not. Start at `hsk14` and look at what it removed before buying more.

**Sanity-check the histogram before wiring it in.** If almost everything scores 5 the filter
will do nothing; if a large share scores ≤2 it is flagging ordinary sentences and the
threshold is wrong. Then read a sample of what it would drop:

```bash
cd pipeline && PYTHONPATH=. .venv/bin/python -c "
from pipeline import quality; from pathlib import Path
c = quality.load_cache(Path('data/derived/sentence-quality.jsonl'))
for t, s in list(c.items())[:400]:
    if s <= 2: print(s, t)
"
```

## Wiring it into the build (still to do)

Steps 7-9 of `~/.claude/worklog/chinese.md`. In `build_sentences`:

1. Load the cache when it exists; when it does not, change nothing — a clean checkout with no
   Ollama must still build a valid, publishable pack, exactly as it does without the Dong
   lexicon.
2. Drop a sentence scoring ≤2 **only while its word keeps at least one**. 9,325 words have
   exactly one example and 21,857 have two; an unconditional cut empties thousands of word
   pages. Coverage must not regress, and the build should print how many sentences were
   dropped and how many words kept their last one anyway.
3. **The score filters; it does not sort.** Above the threshold, keep the existing ordering
   with author-nativeness and recorded audio as tie-breaks ahead of length. Sorting by the raw
   score hands the model's biases the whole ranking, and it has them: in trial runs it scored
   ordinary traditional-character and foreign-proper-noun sentences 4 where equivalent
   simplified ones got 5.
