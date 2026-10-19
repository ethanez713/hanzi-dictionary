# Daily-use mechanics: evidence behind streaks, goals, reminders and the heatmap

Anchors the constants in `core/Streak.kt` and the streak parts of `core/Nudge.kt`. Where the
evidence is a company's own account rather than a controlled study, it says so.

**Provenance caveat.** Compiled from search-result summaries, not the original papers. Company
claims (Duolingo) are self-reported and not independently replicated.

## Streak

- A visible, intact streak raises the chance of acting that day, even when past behaviour is
  identical (Barasch & Silverman, seven experiments, >4,000 participants, including a simulated
  language app — INSEAD Knowledge summary).
- Duolingo's retention team describes the streak as loss aversion: protection grows with streak
  length after the novelty fades. Company-authored.
- Causal field evidence is thin: a Northwestern working paper (IPR wp-26-05) notes few causal
  studies, and that Khan Academy retired its streak over motivation concerns.
- **Decision:** a study day is one or more graded reviews with `origin <> 'triage'` (a triage
  claim is a self-report, as in `Mastery`). The streak is display-only and never gates
  scheduling, so a lapse costs a number, never the user's cards.

## Freeze

- A broken streak is the main quit trigger, and Duolingo iterated on freezes for that reason.
  No published effect size.
- **Decision:** one missed day is bridged if no other bridged day falls in the preceding 6 days;
  two consecutive misses break the streak. Bridged days count toward length.

## Daily goal

- Goal-gradient effect: effort rises as a visible target nears. Applied here by analogy from
  fitness rings and Duolingo's daily goal; no language-app-specific study was found.
- **Decision:** default 20 reviews a day, selectable 10/20/30/50. The streak does not depend on
  the goal.

## Reminder

- Duolingo's "streak saver" was the first major win of their streak work (company account).
- Yancey & Settles, KDD 2020: repeating one notification template loses effect and recovers when
  rested; a bandit over templates gave +0.5% daily users and +2% new-user retention.
- **Decision:** the at-risk reminder fires only in the last 3 hours of the reminder window, only
  when the streak is at least 1 and nothing has been reviewed today, at most once a day, on the
  existing silent channel. Title wording rotates by day index.

## Heatmap

- Anki's Review Heatmap add-on is the reference: contribution-graph shading plus streak and
  daily-average figures.
- **Decision:** 26 weeks; cell shade by distinct items reviewed that day, in four buckets
  (zero, then quartiles of the user's non-zero days).

## Milestones

- **Decision:** celebrate streak days 7/30/100/365 and archived counts 100/500/1000, each once.
  Judgement call; no source.
