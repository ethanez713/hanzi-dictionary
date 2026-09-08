# Example-sentence quality filter

The pack ships example sentences that are grammatical but not idiomatic — 这个项目是一块金
("this project is a gold nugget"), an English metaphor rendered word for word. Nothing in
such a sentence's *shape* marks it: a word-bigram model over TED2020 ranks that one **first**
of its five siblings, because brevity and word frequency are what such a model measures. A
language model asked the question directly scores it 2.

So each sentence carries a 1-5 naturalness score from the local model
(`pipeline/quality.py`), cached in `data/derived/sentence-quality.jsonl` and applied by the
build to `sentence.quality` / `sentence.shown`.

**Nothing is ever deleted.** The score and the decision are columns; the app filters on
`shown`. To undo the filter entirely, drop `AND shown = 1` from `sentencesForWord` in
`Hanzi.sq`.

## The threshold

**Drop at ≤2 unconditionally; drop at 3 only when the word keeps another sentence.** That
takes the safest precision where a deletion is irreversible, and the higher recall only where
being wrong costs one of several examples. It composes with the rule that a word never loses
its last sentence.

Measured on 400 real pack sentences — 200 defective, 200 sound — hand-labelled, and
**length-matched** bucket by bucket (mean 24.2 vs 24.0 characters) so that penalising long
sentences could not produce the result:

| Drop when score ≤ | Precision | Recall | Sound sentences deleted |
| --- | --- | --- | --- |
| 2 | **95.7%** | 33% | 3 / 200 |
| 3 | 94.3% | 66% | 8 / 200 |

Mean score 3.17 on defective sentences against 4.75 on sound ones. Restricted to Tatoeba-only
sentences, where both classes share a source, precision at ≤2 is 90% — the OPUS/Tatoeba split
is not doing the work.

**Read the numbers as optimistic in one direction.** The labels are not a native speaker's;
the defective set was found by reading OPUS subtitles and low-declared-proficiency Tatoeba
rows, so it over-represents obviously-broken text; and anything doubtful was excluded from the
sound set rather than labelled, which biases precision upward. Recall on the real corpus is
therefore lower than 66%, and the filter leaves more bad sentences in than it wrongly removes.
Both biases favour the shipped decision.

`python -m pipeline.validate_scorer` re-runs this measurement against the labelled set in
`data/derived/scorer-validation/labeled.json`.

## Score one sentence per request — never batch

Scoring ten at a time is three times faster and destroys the result. The score comes to depend
on which neighbours a sentence was batched with: every one of the 45 sentences that happened
to be scored twice **disagreed with itself**, and precision at the drop threshold measured
**17%** — five good sentences deleted per bad one. Alone the model is deterministic (57/57
identical on a re-score) and precision is 94%.

The speed argument does not survive either: batching's 0.74 s/sentence was measured against a
JSON response of mostly punctuation, while a bare digit generates three tokens.

## The Ollama version is part of the scoring regime

Scores are comparable only within one model **and one Ollama version**. `load_cache` filters
by model name, so a model swap re-scores automatically — but it cannot see a runtime change,
and a version bump mid-corpus silently mixes two regimes.

0.33.3 against the pinned 0.24.0, same model blob, one server at a time: it disagreed on
**9 of 200** sentences, always a short sentence falling from 5 to 1 (`您有什么？`, `工作。`,
`谢谢？`, `我是朋友。`), where 0.24 reproduces its own cached scores 200/200. At 4.5% that
quietly bins several hundred good beginner sentences.

Upgrading is therefore fine *between* corpus runs and never during one, and it invalidates
every cached score: re-run `pipeline.validate_scorer` to re-establish precision, then re-score.

**There is no speed argument for upgrading.** The 445-token rules-and-examples prompt head is
~95% of every request (0.65 s of prompt eval against 0.04 s of generation) and is
re-evaluated on every sentence; working prefix reuse would be worth ~2.8x. 0.33.3 does not
provide it — its `prompt_eval_cached_count` reports **0** at an unchanged 0.9 s/sentence.
v0.30.8's caching fix covers multi-turn context shift, not independent requests sharing a
prefix, and `/api/chat` with the head as a system message is slightly worse (the chat template
adds tokens).

## Rejected: ranking by Tatoeba author nativeness

Tatoeba records each contributor's self-declared proficiency, which looks like a free
alternative to scoring — prefer sentences by declared natives. It is too weak to use: **39% of
the pack's sentences have an author with no declared level**, including the two most prolific
contributors, and a native-declared author still wrote 没有男人有能把控住女性理性的想象力.
It would need its own fetcher for a signal that is missing where it is most needed.
