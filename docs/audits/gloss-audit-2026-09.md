# Gloss audit — one gloss per glyph, rendered in three roles (2026-09-04)

Prompted by a native speaker's report of errors in the character/word screens. The
reported errors are real, and four of the six are the same defect seen from different
angles. This audit records the evidence and the fix plan.

## Bottom line

The pack stores **exactly one gloss per glyph** — `glyph.definition`, which is Unihan's
`kDefinition`, a *classical/Kangxi standalone* gloss. The app renders that single string in
three unrelated roles:

| Role | Site | What it needs to say |
| --- | --- | --- |
| Headline meaning | `LookupSheet.kt:391` | what the character means **in modern Chinese** |
| Component contribution | `LookupSheet.kt:470` over `HanziRepository.kt:159` | what this part **contributes to this character** |
| Word-chip gloss | word screen "Characters" strip | either of the above, short |

Only the first is even approximately right, and it is wrong whenever the modern sense is a
borrowing (项). The second is wrong wherever a component's role meaning diverges from its
standalone meaning — which is the normal case for the highest-traffic components.

Two secondary defects are independent: example sentences are ranked by **length only**, and
`build_words` deliberately discards CC-CEDICT's single-character entries.

## Findings

### F1 — Component glosses are standalone glosses, not role glosses  *(the reported 项 bug)*

`HanziRepository.characterPage` sets `definition = compGlyph?.definition`. There is no
role-gloss concept anywhere in the schema, so 项 renders as *工 SOUND · gōng "labor, work"* +
*页 MEANING "page, sheet, leaf"* — a learner reads "work + page → neck". The component card
gives the gloss a single ellipsized line (`LookupSheet.kt:470`), so a long standalone gloss
is truncated where a short role gloss would fit whole.

Measured against makemeahanzi's `etymology.hint` (the semantic component's meaning **in
role**, 6,881 pictophonetic entries covering 98% of semantic-component uses):

- **2,350 of 7,094 semantic/`both` component displays (33%)** show a gloss whose content
  words do not intersect the role hint.
- Most of that is harmless synonymy (艹 grass/plant, 钅 gold/metal, 亻 man/person). The
  genuinely misleading residue is concentrated in a short list of very common components:

| Comp | uses | app shows | role meaning (modal hint) | agreement |
| --- | --- | --- | --- | --- |
| 王 | 86 | king, ruler | **jade** | 83/86 |
| 贝 + 貝 | 86 | sea shell | **money** | 83/86 |
| 页 + 頁 | 66 | page, sheet, leaf | **head** | 49/64 |
| 广 | 38 | wide, extensive, broad | **building** | 16/33 |
| 礻 | 34 | cult | **spirit** | 33/33 |
| 攵 | 33 | rap, tap | *script / strike* (tied) | 7/28 |
| 歹 | 27 | bad, vicious, depraved | **death** | 19/26 |
| 囗 | 26 | erect, proud | **enclosure** | 18/19 |
| 皿 | 19 | shallow container | **dish** | 11/13 |
| 厂 | 14 | factory, workshop | **building** | 9/13 |
| 鬼 | 16 | ghost | *demon / ghost* | 7/15 |

- **259 displays across 20 components show a blank gloss** — including ⺼ (89 uses), 鸟
  (69), ⺮ (59). Five of those (⺼ ⺮ 鸟 ⻏ 𤣩) have no `glyph` row at all, so
  `radicals.py` never runs on them; the rest have a `glyph` row with a NULL `kDefinition`.
  makemeahanzi has a decisive hint for every one (⺼ flesh 105/144, ⺮ bamboo 111/115,
  鸟 bird 69/69).

**The corrective data is already in the pack's inputs and is being thrown away twice.**
`merge.component_function_claim` reads makemeahanzi's `hint` into the `component_function`
claim, and then:

1. `build.py:441` **deletes that claim** for every character Dong covers — the 4,569 most
   common ones, i.e. exactly the characters a learner meets. Dong's replacement claim has
   no `hint` field. (Dong's own per-component glosses, recoverable from its comment lines,
   are standalone glosses too — 页 is "page, sheet, leaf" there — so Dong cannot fix this.)
2. Even where the claim survives, the component card never reads it.

### F2 — Example sentences are ranked by length alone

`build_sentences` has one ordering signal: shortest-first, with a `≤34` char pass-1 cap.
No quality, nativeness, or rating signal exists, because `fetch_tatoeba.py` fetches only
`cmn_sentences`, `eng_sentences` and `links`. 这个项目是一块金 (Tatoeba 805208) is short and
well-formed, so it sorts high.

Tatoeba publishes the signals that would separate these, and they are reachable:

| Export | HTTP | Gives |
| --- | --- | --- |
| `sentences_detailed.tar.bz2` | 200 | sentence → author username |
| `user_languages.tar.bz2` | 200 | username → self-declared skill level per language (5 = native) |
| `sentences_with_audio.tar.bz2` | 200 | sentences with recorded audio |

There is **no** public per-sentence ratings export (`users_sentences` 404s), so "filter by
rating" is not available; author-nativeness and has-audio are.

### F3 — Headline glosses are Kangxi, not modern

`build_words` skips single-character CC-CEDICT entries, with the comment *"single-char
entries are skipped (`glyph.definition` covers those)"*. It does not cover them:

```
项  Unihan   neck, nape of neck; sum; funds
    CEDICT   back of neck / item / thing / term (in a mathematical formula) /
             sum (of money) / classifier for principles, items, clauses, tasks,
             research projects etc
```

The app's own "In words" list for 项 (项目 选项 强项 事项) is entirely the *item* sense the
headline omits. CC-CEDICT has a single-char entry for **10,728** of the pack's glyphs,
including **841 that currently have no gloss at all**.

### F4 — Pinyin neutral-tone inconsistency passes through

CC-CEDICT is internally inconsistent (`來不及 [lai2 bu5 ji2]` vs `等不及 [deng3 bu4 ji2]`)
and the pipeline transcribes it verbatim. Cosmetic, real.

### Not defects

- **及's etymology wording.** A verbatim wiktextract quote, correctly attributed. Editorial
  disagreement with Wiktionary is not a data error.
- **HSK version mixing.** All HSK levels — character and word — come from one source
  (`drkameleon/complete-hsk-vocabulary`) and only `n1..n7` codes are read, so everything
  shown is HSK 3.0. 页 = HSK 1 is correct (it is an HSK 3.0 level-1 word).
  The adjacent honesty gap is real though: `glyph.hsk` means *"lowest level of any HSK word
  containing this character"*, and the UI shows it as a bare `HSK n` badge identical to the
  word badge, which reads as a claim about the character itself.
- **项圈 "necklace".** CC-CEDICT's own gloss, verbatim. Correcting it means overriding
  CEDICT per-entry, which we have no source for.

## Fixes

### Fix 1 — a `component_gloss` table, and a component card that reads it

New pack table, one row per component, holding the meaning that component **contributes**:

```sql
CREATE TABLE component_gloss (
    component_id TEXT PRIMARY KEY,   -- glyph.char or combining form, as in component_use
    gloss        TEXT NOT NULL,      -- role meaning: "head", "money", "jade"
    source_id    TEXT NOT NULL,      -- makemeahanzi | curated
    support      INTEGER NOT NULL,   -- characters voting for this gloss
    agreement    REAL NOT NULL       -- support / total votes
);
```

Built by modal vote over makemeahanzi `etymology.hint`, grouped by
`etymology.semantic`, restricted to `type == "pictophonetic"` (where `hint` is a role
noun; on `pictographic`/`ideographic` entries it is a prose sentence about the whole
character and must not be voted on).

Accept a voted gloss when `support >= 3 and agreement >= 0.6`. Below that, or absent
entirely, fall back to a **curated table in `radicals.py`** — the same place
`SPECIAL_GLOSSES` already lives. Seed it with the components the vote cannot settle: 攵
(strike), 又 (hand), 广 (building), 鬼 (demon), 寸 (hand, measure), plus any residue the
build reports.

Pipeline steps: build the table in `build.py`; add it to `core_tables` in `package.py`;
mirror the DDL in `Hanzi.sq`; add a `componentGlossFor` query. It belongs in `core.db`, not
`ids.db` — the layering exists to isolate cjkvi-ids' GPL, and makemeahanzi-derived tables
(`component_use`, `claim`) already ship in `core.db`.

App: `ComponentBreakdown` gains `roleGloss: String?`, and the component card
(`LookupSheet.kt:470`) picks its gloss by the part's function:

- **`semantic` / `both`** — `roleGloss`, *replacing* the standalone gloss rather than
  appending to it. 页 reads "head", not "head · page, sheet, leaf". The card's vertical
  space is the scarce resource and the standalone sense is one tap away.
- **`phonetic` / `unknown` / `empty`** — the canonical modern meaning (Fix 2's CEDICT-first
  gloss). A sound component contributes no meaning, so a role gloss would be a fiction; its
  ordinary meaning is what lets a learner recognise the part at all.
- Every part keeps its reading — a sound-bearing part already carries it in its
  `SOUND · gōng` role label — because the reading is what makes a sound component make
  sense, so it is never the thing that gets demoted.

Fall back to `shortGloss(definition)` wherever `roleGloss` is absent. The **component page**
(what else shares this part) keeps showing the standalone `definition` — there the
component *is* the subject, so its own dictionary meaning is the right thing.

Because the vote is over makemeahanzi and the Dong override only replaces `component_use`
and the `component_function` claim, this survives the Dong override untouched — no change
to `build.py:441` is needed.

### Fix 2 — modern headline glosses from CC-CEDICT

This is unimplemented spec, not new design: §16 requires that "the character-page header's
top glosses (§9 IA item 1) come from the character's own CC-CEDICT entries", and §9 IA item 1
says *glosses*, plural. `build_words` discarding the single-char entries is what left it
unbuilt.

Add `glyph.cedict_defs` (JSON array of senses, NULL when CEDICT has no single-char entry)
populated from the entries `build_words` currently discards. Multiple readings for one
character (頁 `[xie2] /head/` and `[ye4] /page/leaf/`) merge into one ordered list.

`CharacterScreen`'s headline prefers `cedict_defs` and falls back to `definition`; Unihan's
gloss moves under `Details ▾` labelled as the classical gloss. `shortGloss` call sites
(word-screen character chips, component cards' fallback) take the first CEDICT sense the
same way.

Keeping both columns rather than overwriting `definition` matters: Unihan is the only gloss
for 32k of the pack's 42.7k glyphs, and the classical sense is the one that explains the
component breakdown.

### Fix 3 — sentence quality

Two mechanical filters, in order of how much they are worth. Neither needs a human to read
any Chinese.

**3a — a local-model naturalness score (the one that works).** The local Ollama model
(`pipeline.quality.MODEL`) rates each sentence 1-5 for naturalness. Measured on the reported
case it separates cleanly: 这个项目是一块金 scores 2 while every sibling sentence for 项目,
项链 and 及时 scores 4-5. This is the only signal tested that identifies the defect the
native speaker reported. Scores are comparable only within one model, so the cache records
which produced each and a model swap re-scores rather than blending the two.

- **Scoring runs offline and is cached, never at build time.** A `sentence_quality` snapshot
  under `pipeline/data/snapshots/` maps `tatoeba_id` (and a hash of the text, for the OPUS
  rows that have no id) to a score. `build_sentences` reads it when present and ignores it
  when absent, exactly as the Dong lexicon is treated — so a clean checkout with no Ollama
  still builds a valid, publishable pack.
- **The rule is drop-if-replaceable, never drop-to-empty.** 9,325 words have exactly one
  example sentence and 21,857 have two, so an unconditional cut would empty thousands of
  word pages. A sentence scoring ≤2 is dropped only while its word keeps at least one
  sentence; the last one is kept whatever it scores. Coverage cannot regress.
- Scoring is stdlib-only (`urllib.request` against `localhost:11434`), matching the
  pipeline's no-dependency rule, and stays local — no sentence leaves the machine.
- **Ten sentences per request, and the response schema echoes each sentence's number**
  (`{"r":[{"i":<n>,"s":<score>}]}`). Batching is what makes the run affordable — 0.74 s per
  sentence at ten, against 3.02 s one at a time — and costs nothing in quality: 98% of
  scores land within one point of the single-shot score, and no batch came back malformed.
  The echoed index is load-bearing, not decoration: an earlier bare `{"scores":[…]}` array
  silently returned 19 scores for 20 sentences, which shifts every score after the gap.
  Reject and re-score any chunk whose returned indices don't match what was sent.
- **Score only the sentences where a score can change something.** A word with one example
  keeps it whatever it scores, so scoring it is wasted GPU, and an obscure word's example is
  not worth an hour of it. Restricted to words that have more than one sentence *and* are
  HSK-graded or in the top 5,000 by frequency, the run is 24,600 distinct sentences (~10 h);
  tightened to HSK 1-4 or the top 2,000, it is 10,700 (~3.5 h) against 30 h for the whole
  pack. The
  cache is keyed per sentence and fills incrementally, so a tighter scope now does not
  prevent a wider one later.

**3b — author nativeness as a tie-break.** `fetch_tatoeba.py` additionally fetches
`per_language/cmn/cmn_sentences_detailed` and `user_languages`, giving each sentence its
author's self-declared Mandarin level. Of the 36,321 Tatoeba sentences the pack ships, 41%
are by a self-declared native (level 5), 20% by a declared non-native, and 39% by someone
with no declaration at all — including the two most prolific contributors, who between them
wrote 21% of the pack. Nativeness is therefore **too coarse to filter on** and is used only
to break ties between sentences of equal quality score. `sentences_with_audio` is a second
tie-break on the same footing: a recorded sentence is more likely to be worth hearing.

**The score filters; it does not sort.** Sentences scoring ≤2 are dropped (subject to the
drop-if-replaceable rule); everything above that keeps the existing ordering, with nativeness
and audio as tie-breaks ahead of length. Sorting by the raw score would hand the model's
systematic biases the whole ranking, and it has them: in trial runs it scored ordinary
traditional-character and foreign-proper-noun sentences 4 where equivalent simplified ones
got 5. A bottom-end filter is insensitive to that; a sort key is not.

**Rejected: corpus n-gram plausibility.** A word-bigram model over TED2020 (399k lines,
1.48M bigrams, CEDICT segmentation) ranked 这个项目是一块金 **first** of the five 项目
sentences. It scores brevity and word frequency, not idiomaticity, and no smoothing fixes
that. Don't rebuild it.

**Rejected: Tatoeba ratings.** There is no public per-sentence ratings export
(`users_sentences` 404s). The tags export exists but carries nothing usable for this — the
flagged sentence is untagged.

### Fix 4 — neutral-tone normalization

In `pinyin.cedict_pinyin`, normalize the `X不Y` pattern to neutral `bu5` when the middle
syllable is 不 between two full syllables — the potential-complement construction, which is
neutral in speech regardless of how CEDICT tagged it. Applies to 来不及/等不及/看不见/买不起
and their affirmative counterparts (得), which CEDICT tags inconsistently across entries.

### Fix 5 — HSK badge honesty

The character sheet's meta line renders `"HSK $it"` (`LookupSheet.kt:398`) and the word
sheet does the same, but the two numbers mean different things. The word badge becomes
`HSK 3.0 · 4`. The character badge becomes `HSK 3.0 · 1 word`, because `glyph.hsk` is the
lowest level of any HSK word *containing* the character, not a claim about the character.

## Measurement

No hand-labelling. Every check below is either mechanical or a small read-through of a
ranked list.

**Fix 1 — component gloss accuracy.** The build prints the `component_gloss` table sorted by
usage, with `support`/`agreement` and the source (`makemeahanzi` or `curated`) on each row.
The top 60 components cover 79% of all semantic displays, so that printout is short enough
to read in full and is the acceptance gate: no gloss in the top 60 that contradicts the
component's role, and no component in the top 60 left blank. Below the top 60 the gate is
mechanical — every component with `agreement < 0.6` or no vote at all must have a curated
entry or it is not shipped as a role gloss, and falls back to the standalone gloss.

**Fix 3 — sentence quality.** Measured mechanically against a fixed held-out set: score the
sentences the current pack ships for a sample of HSK 1-4 words, then check (a) the flagged
sentence 这个项目是一块金 is scored ≤2, (b) the score distribution is not degenerate — if
almost everything scores 5 the instrument is measuring nothing, and if a large fraction
scores ≤2 it is measuring noise, (c) coverage after filtering is unchanged (see the rule in
Fix 3: a word never loses its last example). Report all three counts in the build output.

**Tier 0.** `build.py` already prints per-table row counts. Add: components resolved by
vote / by curation / left blank; sentences dropped by the quality filter and how many words
would have been emptied (and so kept their best sentence anyway). A rebuild that shifts
these is a regression to look at, not a number to ignore.

## Ordering

Fix 1 and Fix 2 both add pack schema and both need a `BUNDLED_PACK_VERSION` bump, so they
land together as one rebuild; Fix 4 is pipeline-only and rides along with it. Fix 5 is
app-only and independent.

Fix 3 lands last and separately: it needs two new Tatoeba exports, an offline scoring run
over the sentence corpus, and its own rebuild. The scoring run is the long pole — it is a
one-time local batch whose results are cached by sentence id, so later rebuilds are free.
