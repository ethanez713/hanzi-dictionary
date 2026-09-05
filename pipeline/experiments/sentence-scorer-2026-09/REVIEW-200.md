# Review of 200 scored sentences — the PROCEED verdict does not hold

`results.json` says PROCEED. **It is wrong, and the run described in EXPERIMENT.md must not
be started on its strength.** This file supersedes it; read this first.

Method: a stratified sample of 200 natural (uncorrupted) sentences from the validation
caches — all 46 that scored below 5, plus 154 of the 5s — read and assessed by Claude
(Opus 5) rather than by the user, who is not a native speaker. Sample and raw scores:
`data/derived/scorer-validation/`.

## What the sample shows

**Precision at the drop threshold is about 17%.** Of the 18 sentences scored 2 — every drop
the filter would have made in this sample — only 3 are genuinely defective:

| Sentence | Why it is bad |
| --- | --- |
| 阿非利康族是**源在**南非形成的民族。 | 源在 is not Chinese; 源於/起源於 |
| 我今天晚上死掉。 | 死掉 cannot take a bare future time without 要/会 |
| 这种奇怪的情况是我对**中**的印象的典型。 | 中 alone for 中国, and stacked 的…的 |

Three more are mildly stiff (他在他的办公桌前 · 比爾很早起床這樣他就可以趕上第一班火車了 ·
你大约认识多少英文词？). **The remaining twelve are ordinary, correct Chinese** — 讓我們坐公共
汽車去, 我在学两门外语, 拉丁语一点也不好学, 我用浴室的体重计量了体重, 有人被困在车里,
这次出游下了三次雨, 我们同意了第二天一早开始, 她为我作钢琴伴奏, 我人生地不熟的, 测试延迟了
两天…, 我去年夏天到訪的是一個位於長野縣的小村落, 這裡種植有生菜、高麗菜和芹菜等高原蔬菜.

The 21 sentences scored 3 are nearly all fine as well; only 虽然他考得很好，他口语的汉语不见得
比你的好 (口语的汉语 for 汉语口语) and Tatoeba:好像怪客入侵還不夠似的 deserve it. Reading all
154 fives turned up nothing the filter should have caught, so the score is not missing bad
sentences — it is inventing them.

## Root cause: the batch contaminates the score

The same sentence scored in two different batches gets two different scores. Across the
423 distinct sentences in the caches, 45 were scored more than once and **all 45 disagree**;
**15 of the 20 sentences ever scored ≤2 were scored above 2 somewhere else.** Three quarters
of the drop verdicts are an artefact of which neighbours a sentence was scored beside.

Scored **alone**, the model is perfectly deterministic — 19 sentences × 3 runs, 57/57
identical — and most of the damage disappears: 12 of those 15 contested sentences settle at
5, and 3 of the 4 known-bad ones stay at ≤2. So the instability is batch contamination, not
sampling noise, and `temperature: 0` was never the thing protecting us.

## Why the experiment missed it

It measured **recall on damaged sentences** — corrupt a sentence, does the score fall — and
got 95%. It never measured **precision on undamaged ones**. A model biased toward answering
2 scores well on every corruption arm *and* destroys good sentences; those two behaviours are
indistinguishable from inside that design. Any future version of this experiment needs a
precision arm over untouched sentences, and that arm needs a reviewer who reads Chinese.

## Where this leaves the filter

Not abandoned, but not ready. `--scope hsk14` as specified would delete roughly five good
sentences for every bad one.

The one-at-a-time result is enough to justify **one more validation round, not a production
run**: score at `BATCH_SIZE = 1`, and re-run with a precision arm over natural sentences plus
a pass criterion of at least 70% precision at the threshold. Cost is 3.0 s/sentence against
1.1 — about 9 h for `hsk14` rather than 3.4 — so it is only worth buying if precision
actually clears the bar. Even scored alone, 2 of those 15 good sentences (我用浴室的体重计量了
体重, 我们同意了第二天一早开始) still land at 2, so the bar is not obviously reachable.

If the second round fails, drop the idea and leave the sentence ranking as it is. That is a
cheap outcome, and it is the one this whole validation path existed to make cheap.
