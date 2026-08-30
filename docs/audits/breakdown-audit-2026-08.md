# Breakdown audit — source-asserted component functions (2026-08-30)

Wiktionary-grounded audit of character breakdown errors, prompted by a reviewer's report
that "the source-asserted ones have errors, especially for simplified words." Confirmed:
the errors are real, they are concentrated exactly where the reviewer said, and they have a
single mechanical cause.

## Bottom line

- **Semantic labels are fine.** Every `single_source` semantic label on a simplified glyph
  is a radical (讠 speech, 纟 silk, 钅 metal, 氵 water, 鱼, 鸟, 口, 马 …). No errors there.
- **The errors live in `single_source` *phonetic* labels on simplified glyphs** — 290 rows.
  These are makemeahanzi's editorial "phonetic = X" claims that our own phonetic detector
  **could not confirm** (that is precisely why they resolve to `single_source`, shown in the
  app as the muted *source-asserted* badge, `CharacterScreen.kt:343`).
- That bucket is a **mix of two things the app currently renders identically**:
  1. **Correct-but-drifted** (the majority) — genuine ancient phonetics that are simply
     inaudible in modern Mandarin (创 chuàng ← 仓 cāng; 组 zǔ ← 且 jū; 说 shuō ← 兑 duì).
     These are *right*; the "source-asserted" badge is doing its job.
  2. **Simplification artifacts** (the true errors) — a component that became "the phonetic"
     only through simplification and never carried the sound. These are flat wrong.
- Because the app shows both with the same "phonetic · source-asserted" label **and reads the
  component's pinyin aloud as the sound**, a wrong one (卖 mài "is the phonetic of" 续 xù)
  is taught as confidently as a right one. That is what the reviewer is seeing.

## Root cause

makemeahanzi's per-character etymology is keyed to the character's **traditional** analysis.
For a simplified glyph it keeps the traditional phonetic label even when simplification
**replaced** the phonetic with an unrelated graphic sign or **merged** it into a different
character. The pipeline (`merge.component_function_claim` → `phonetic.compute_component_use`)
faithfully carries that claim, the detector fails to hear the sound link, and we keep the
label as `single_source` phonetic anyway (`phonetic.py:107-113`, the `opaque` branch).

So the label points at a component that, in the simplified form, is a 记号 (sign) — not a
phonetic. Two sub-cases:

- **Merger:** 續 → 续 mapped the phonetic 𧶠 (yù) onto 卖, which also spells 賣 (mài, "sell").
  The label now reads the wrong character's sound.
- **Replacement / corruption:** 陸 → 陆 swapped phonetic 坴 (lù) for the sign 击 (jī);
  細's 囟 (xìn) corrupted to 田 (tián) after the Han. The new part never had the sound.

## Confirmed errors (Wiktionary-cited)

| Char(s) | Asserted phonetic | Truth (Wiktionary glyph origin) |
|---|---|---|
| 续 读 渎 椟 牍 觌 黩 | 卖 (mài) | Phono-semantic; phonetic is **𧶠 (yù, OC \*luɡ)**. 續/讀 = "semantic 糸/言 + phonetic 𧶠." 卖 is a simplification merger with 賣 (mài) and carries no sound. |
| 细 / 細 | 田 (tián) | Phonetic is **囟 (xìn)**; "the right component is etymologically unrelated to 田, which it was corrupted to after the Han dynasty." |
| 陆 / 陸 | 击 (jī) | "Simplified from 陸 (坴 → 击)." Traditional phonetic **坴 (lù)**; 击 is the simplified replacement, not a phonetic. |

## High-confidence errors (same mechanism; Wiktionary shows the replacement but does not call
the new part phonetic)

| Char | Asserted phonetic | Traditional phonetic (replaced by a sign) |
|---|---|---|
| 显 / 顯 | 业 (yè) | 㬎 (xiǎn) — 业 is a graphic reduction |
| 灵 / 靈 | 彐 (jì) | 霝 (líng) — 彐 is a graphic reduction |

These two are high-frequency; the full simplified set should be triaged by the method below.

## What is NOT an error (do not "fix" these)

The same screen (asserted phonetic shares no modern initial/final with the character) flags
**285 of 290** rows — because Chinese phonetics routinely drift past recognition in Mandarin.
The large families below are **correct** ancient phonetics and must be left alone:

- 仓 (创枪抢呛怆…), 尧 (晓浇翘侥骁…), 监 (蓝篮滥槛…), 岂 (剀恺铠…), 啬 (墙嫱樯蔷),
  呙 (窝蜗娲莴), 禀 (凛廪懔檩), 刍 (诌邹驺), 兑 (说脱悦), 且 (组诅龃), 各 (骆络辂),
  台 (诒贻饴), 占 (钻贴), 员 (损勋埙), 聂 (摄慑滠), 寿 (铸梼焘) …

The lesson: **"Mandarin sound doesn't match" is not a usable error signal** — it fires on the
correct cases as hard as the wrong ones. The discriminator is graphical (did simplification
replace/merge the phonetic?), not phonological.

## Fix (implemented — pack v12)

`merge.phonetic_claim_contradicted` cross-checks makemeahanzi's editorial phonetic against the
**traditional form's Wiktionary glyph-origin** (`pipeline/pipeline/merge.py`), and
`phonetic.compute_component_use` vetoes the label when contradicted: the component drops from
`phonetic`/`single_source` to `unknown`/`computed`, so the app renders it as an unlabeled sign —
no "sound" label, no voicing, dropped from the Sound drift line — with the reading still shown
muted. Unit tests: `pipeline/tests/test_phonetic_crosscheck.py`.

**The key lesson — why the obvious approach fails:** a simplified glyph's Mandarin sound drift is
*not* a usable error signal. 续 (真error, 卖←𧶠) and 没 (fine, 殳 is the drifted phonetic) look
identical there, and "no shared initial/final" flags 285 of 290 — including every correct-but-
drifted phonetic (创←仓, 说←兑). So the veto fires **only with graphical proof from the traditional
character**, and is deliberately conservative:

- **No phonetic exists** — the traditional form is ideographic/pictographic per Wiktionary
  (顯→显, 聯→联, 糞→粪, 宮→宫); makemeahanzi invented one.
- **The phonetic was removed by simplification** — Wiktionary's phonetic sits in the traditional
  character's own decomposition (續 carries 𧶠, 陸 carries 坴, 牆 carries 爿) yet is gone from the
  simplified glyph (𧶠→卖, 坴→击, 爿→土).
- **Explicit corruption** — the traditional etymology says makemeahanzi's component is a
  corruption of the real one (細: "phonetic 囟 … corrupted to 田").

It **abstains** when Wiktionary names only an archaic ancestor that never appears graphically
(没's 𠬛, 家's 𢑓) — no proof, usually just notation — and ignores a derived variant that merely
contains the glyph (傢, 製). Net effect on pack v12: **51 component labels** flip phonetic→sign,
all on simplified glyphs; the drifted-but-genuine phonetics (说/创/组/样/状/没/到 …) are untouched.
The one in-place simplified phonetic OpenCC's char table can't see, 爿→丬 (状/壮/妆), is handled by
a one-entry `_COMPONENT_SIMPLIFICATIONS` table.

**Not caught (out of scope):** errors on *traditional* glyphs (細 itself), and makemeahanzi
picking the wrong one of two present components on an unchanged glyph (有: 月 labelled instead of
又) — neither is a simplification artifact. Extend by adding cases to the audit and, where needed,
`_COMPONENT_SIMPLIFICATIONS`.
