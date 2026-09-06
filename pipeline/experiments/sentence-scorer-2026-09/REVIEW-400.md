# 400 labelled sentences: the scorer works, one sentence at a time

Supersedes `REVIEW-200.md`, which retracted the earlier PROCEED verdict. That retraction was
right about the numbers and right about the cause. **The cause was the batching, not the
model.** Scored one sentence per request, precision at the drop threshold goes from 17% to
94%.

## The test

200 defective sentences and 200 sound ones, all real rows from the pack, never synthesised,
each read and labelled by Claude (Opus 5) — the user is not a native speaker and asked not to
label. Set: `data/derived/scorer-validation/labeled.json`. Re-run:

```bash
cd pipeline && PYTHONPATH=. .venv/bin/python experiments/sentence-scorer-2026-09/classify.py
```

This measures what the corruption experiment could not. Corrupting a sentence and checking
that the score falls measures **recall on damage**; a model biased toward answering 2 passes
that while deleting the corpus. What matters is the opposite question: of the sentences the
filter would delete, how many deserved it.

## Results (`classify-results.json`)

| Drop when score ≤ | Precision | Recall | Sound sentences deleted | Defects caught |
| --- | --- | --- | --- | --- |
| 2 | **95.7%** | 33% | 3 / 200 | 66 / 200 |
| 3 | **94.3%** | 66% | 8 / 200 | 132 / 200 |

Mean score 3.17 on defective sentences against 4.75 on sound ones. Nothing scored 1.

The classes are **length-matched** (mean 24.2 vs 24.0 characters, bucket by bucket), so a
model that merely penalised long sentences could not produce this. The defective set is
mostly OPUS and the sound set entirely Tatoeba, so the same measure restricted to
Tatoeba-only sentences — where both classes share a source — is reported separately:
**90% precision** at ≤2. The split is not doing the work.

## What changed since the failure

| | Batched (10/request) | One per request |
| --- | --- | --- |
| Precision at ≤2 | 17% | 95.7% |
| Same sentence, scored twice | all 45 disagreed | deterministic, 57/57 identical |
| Speed | 0.74 s/sentence | 0.35 s/sentence |

The batched form was not even faster in the end. Its 0.74 s/sentence was measured against a
JSON response whose tokens were nearly all punctuation; a bare digit generates three tokens,
and two concurrent workers saturate the GPU. Both changes are in `pipeline/quality.py`, which
now refuses to batch.

## Limits of this measurement

Read these before trusting the numbers further than they go.

- **The labels are mine, not a native speaker's.** They are defensible — most defective picks
  are outright broken (源在 for 源於, 的的, 我吧所以时间, English word order) rather than
  matters of taste — but a native reviewer would move some of them.
- **The defective set is enriched, not representative.** It was found by reading ~1,750 OPUS
  subtitle rows and ~700 Tatoeba sentences from low-declared-proficiency authors, which
  over-represents clearly-broken text. Subtle translationese of the kind that started this
  (这个项目是一块金, which the model does catch at 2) is rarer and harder, so **recall on the
  real corpus will be lower than 66%.**
- **The sound set is cleaner than the corpus.** Anything I was not confident about was
  excluded rather than labelled, which biases precision upward. The real false-deletion rate
  is somewhere above the measured 4-6%.

Both biases push in the same direction as the decision, so the honest reading is: precision is
high enough to ship, recall is optimistic, and the filter will leave more bad sentences in
than it removes.

## Shipping it

**Drop at ≤2 unconditionally; drop at 3 only when the word keeps another sentence.** That
takes the safest precision where a deletion is irreversible and the higher recall only where
the cost of being wrong is one of several examples. It composes with the existing rule that a
word never loses its last sentence.

Production pass, now cheap enough to cover the whole corpus:

| `--scope` | Sentences | On battery | On mains |
| --- | --- | --- | --- |
| `hsk14` | 10,703 | ~26 min | ~13 min |
| `common` | 24,561 | ~73 min | ~37 min |
| `all` | 57,337 | ~3.8 h | ~1.9 h |

`--scope all` is the sensible choice now. Interrupt it freely; it resumes from its cache.
