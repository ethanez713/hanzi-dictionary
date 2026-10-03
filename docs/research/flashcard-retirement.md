# When is a flashcard learned? Evidence behind retirement, new words and reminders

Why this exists: the app can now tag a card "ready to archive" (`core/Mastery.kt`), decide
whether the deck is thin enough to suggest new words (`core/DeckHealth.kt`), and send a study
reminder (`core/Nudge.kt`). Every threshold in those files comes from the literature below —
or is flagged as a judgement call where the literature has nothing to say.

**Provenance caveat.** Compiled from publisher abstracts, indexes (ERIC, PubMed, Springer) and
secondary summaries; the network proxy blocked direct reads of most academic hosts. Figures
marked **[secondary]** were not read in the original. Check them against the paper before
quoting them anywhere that matters.

## A. When can a card be retired?

**One correct answer is the documented failure mode.**
- Karpicke & Roediger 2008, *Science* 319:966 — once a Swahili word had been recalled, dropping
  it from further *testing* cut one-week recall to about a third (~33–36% vs ~80%, **[secondary]**);
  further *study* added nothing. Learners' predictions of their own recall were uncorrelated with
  what they recalled.
- Karpicke 2009, *JEP:General* 138:469 — learners believe an item is "learned" once recalled and
  drop it, which leads to poor retention.
- Kornell & Bjork 2008, *Memory* 16:125 — letting learners drop cards had small but consistently
  negative effects; their monitoring is unreliable and they undervalue further study.
- Pyc & Rawson 2007, *Memory & Cognition* 35:1917 — dropping items at criterion is fine *within a
  learning phase*; it does not support permanent retirement.

**Several spaced successes are what work.**
- Rawson & Dunlosky 2011, *JEP:General* 140:283 — recommended ≈3 correct recalls initially, then
  ≈3 relearning sessions, widely spaced; retention rose from ~56% to ~83% going from two to five
  relearning sessions **[secondary]**. Returns diminish after about three.
- Rawson, Vaughn, Walsh & Dunlosky 2018, *JEP:Applied* 24:57 — each extra relearning session added
  retention; roughly 20% / 50% / 70% / ~80% after 0 / 1 / 2 / 3 sessions, one week out
  **[secondary, via Rawson & Dunlosky 2022]**.
- Pyc & Rawson 2009, *J. Memory & Language* 60:437 — harder (more spaced) correct retrievals give
  better final memory; raising the criterion has diminishing returns.

**Long gaps and permastore.**
- Bahrick 1979, *JEP:General* 108:296 — access stays "indefinite" while the relearning gap is no
  longer than the later test delay; best at ~30-day gaps.
- Bahrick et al. 1993, *Psychological Science* 4:316 — 13 sessions at 56-day spacing matched 26
  sessions at 14-day spacing.
- Bahrick 1984, *JEP:General* 113:1 — school Spanish decays for ~3–6 years then plateaus
  ("permastore"). Caveat: multi-year coursework, not a single flashcard.
- Cepeda et al. 2008, *Psychological Science* 19:1095 — the best gap is ~10–20% of the retention
  interval wanted (about 5% for a one-year target).

**Conventions, not findings.** Anki's "mature" card (interval ≥ 21 days) and "leech" (8 lapses).
FSRS stability *S* is the days for recall to fall from 100% to 90%, so at 90% desired retention
the interval equals *S*.

**What the app does with it** (`Mastery.kt`; every constant is named there):

| Label | Rule | Anchor |
|---|---|---|
| streak | consecutive Good answers on **distinct local days**; same-day repeats neither count nor break it; any miss (and Hard) resets it | Rawson & Dunlosky |
| learned | streak ≥ 3 and the latest success after a ≥ 21-day gap | Anki "mature"; below Bahrick's 30 d so a weekly studier can reach it |
| ready to archive | streak ≥ 3, FSRS stability ≥ 180 d, and one success in the streak after a ≥ 60-day gap | Bahrick 56 d spacing; the 180 d figure is a **judgement call** |
| struggling / leech | ≥ 4 / ≥ 8 lapses *and* not back on a streak | Anki convention |

No paper validates a specific "retire at stability ≥ N" number. The app only *suggests*; archiving
stays the user's swipe. Self-reported "I know it" from new-word triage never counts as a success.

## B. Which measurements predict durable learning?

- **Success after a long elapsed interval** is the most informative single event (it produces the
  largest FSRS stability gain); then stability itself; then the spaced-success streak; then lapses.
- **Response time.** Mettler, Massey & Kellman 2016 (*JEP:General* 145:897) — ARTS retires an item
  after 3 consecutive correct answers under an RT threshold (≈7–10 s in their materials
  **[secondary]**), and beat fixed schedules at immediate and delayed tests. But the criterion is
  validated over hours to weeks, not months of vocabulary. Madigan, Neuse & Roeber 2000 found faster
  correct recall predicts later recall; Benjamin, Bjork & Schwartz 1998 found fluency can *mislead*
  learners' own judgements. Hence the app **records** face-down time (`review_log.reveal_ms`,
  practice screen only) but does not use it for retirement yet.
- Settles & Meeder 2016 (half-life regression) and the FSRS model both rest on stability and
  elapsed time; Lindsey et al. 2014 found model-based per-item review gave +16.5% end-of-semester
  retention over massed study.

## C. Deck size and when to add words

- Kornell 2009 — one large (spaced) stack beat several small (massed) ones for ~90% of learners,
  yet 72% believed massing worked better.
- Nakata & Webb 2016 (*SSLA* 38:523) — part vs whole sets: no posttest advantage once spacing is
  matched. It is spacing, not set size, that matters.
- Kim & Webb 2022 (*Language Learning*, meta-analysis, 48 experiments) — spacing has a medium-to-large
  effect on L2 learning; longer beats shorter; equal and expanding schedules are equivalent.
- Workload: FSRS "minimum recommended retention" simulations land near 87%; pushing retention
  higher raises workload steeply. The "10 reviews per new card" ratio is community convention.
- **No peer-reviewed metric rule for when to add items exists.** `DeckHealth.kt` is an engineering
  heuristic: offer words only when fewer than a sitting's worth (5) of cards are due or unseen in the
  next 2 days, the overdue backlog is under 2 sittings, and recent accuracy (last 20 real reviews,
  triage excluded) is ≥ 80%. For someone reviewing a few cards every couple of days it will usually
  say "not yet".

## D. Reminders

- Yancey & Settles 2020 (KDD) — Duolingo sends ~23.5 h after the last session (about the habitual
  hour), and templates lose effect when repeated (novelty effect). Gains are real but small
  (+0.5% DAU, +2% new-user retention). Hence adaptive mode: ~23 h after the last review, at most one
  a day, skipped if you already studied today, titles rotated daily.
- Wohllebe et al. 2021 — more frequent notifications meant more uninstalls and fewer opens (retail
  app, not learning). Hence the back-off after 4 ignored nudges in a row (to every 48 h) — a
  heuristic, no study gives the number.
- Dingler et al. 2017 (QuickLearn, MobileHCI) — answering vocabulary inside the notification added
  many short sessions; Cai et al. 2015 (Wait-Learning, CHI) — ~57 new words in 2 weeks (n = 20).
  Hence a grade-in-the-notification micro-session capped at 5 cards.
- Evidence quality: large effects come from proprietary A/B tests with small deltas; HCI studies are
  small and short. Nothing sets an optimal frequency for a single learner.

## E. How far to trust "I know it"

- Yes/No vocabulary tests (Meara & Buxton 1987; Stubbe 2012) see false-alarm rates of ~4–25%
  depending on learner — claims of knowing are inflated by a measurable, learner-specific amount.
- Koriat 1997; Dunlosky & Rawson 2012 — judgements of learning rest on fluency and familiarity;
  overconfident self-scoring leads learners to stop early and retain less.
- Recognising a word is easier than recalling its meaning or pinyin.
- Hence triage: **know** files the word with an Easy first rating (initial stability ≈ 8 days) so a
  real recall check arrives in about a week; it is never archived on the claim alone. Not
  implemented: seeding fake words to estimate the user's false-alarm rate (Meara-style).
