# Hanzi Component Dictionary — Product Spec

## 1. Overview

A free, offline-first Android dictionary that explains **how Chinese characters work**: per-script semantic/phonetic/empty component breakdowns, computed phonetic-reliability scores, drift explanations grounded in historical phonology, simplified↔traditional mapping types, confusable pairs in the data pack, and component-series navigation — all with per-claim provenance from open data sources.

**Positioning:** the open, personalized alternative to Pleco+Outlier. Differentiation is not data volume but (a) friction-free capture from the photo library, (b) connections filtered by *the user's own known characters*, (c) computed (not copied) component-function labels with honest sourcing, (d) a fully open dataset, when publication is decided.

**Gap claim:** free component *breakdowns* already exist (Dong Chinese wiki — human-verified top ~1000 with open dumps; Hanly; ArchChinese). No product, free or paid, combines computed phonetic-reliability scores + per-stratum drift explanations + per-claim provenance + known-vocab-filtered series + offline native both-script + a 20k-scale open dataset. Marketing must claim that combination, never "first free breakdown app."

## 2. Goals & Non-Goals

**Goals**
- Sub-10-second path from "saw a character" to full component breakdown.
- Component pages as first-class citizens (series, reliability, names, variants).
- Both scripts treated equally, with mapping-type explanations and merger warnings.
- Known-characters model (imports + archive) feeding known-first series ordering.
- FSRS-6 scheduled practice over named study lists of characters and words.
- Published open dataset with per-record attribution (CC BY-SA compliance), when that call is made.

**Non-Goals** — explicitly deferred: confusable quizzes and a look-alike drill, mnemonics layer, sentence reader, handwriting engine (system IME suffices), live camera OCR, Cantonese/topolect UI surfacing (data is stored), word-level audio recordings, iOS app, web app, backend/accounts.

## 3. Target User

Intermediate self-learners (≈300–2,000 known characters) who already own Pleco. Default explanation depth: learner tier; scholarly tier (script forms, OC/MC readings) behind expansion. Component spoken names (三点水, 提手旁) shown throughout.

## 4. Decisions

| Decision | Choice |
|---|---|
| Product core | Dictionary first; FSRS-6 practice is the consumption loop, not a quiz app |
| Audience | Intermediate self-learner |
| Scripts | Simplified & Traditional, equal priority |
| Platform | Native: Kotlin/Compose Android; Swift/SwiftUI iOS later; web later |
| Business | Free; no monetization; ads permissible later (BY-SA allows commercial use) |
| Data strategy | Open sources only; no human/LLM etymology review; cross-reference + computed validation; provenance-first display |
| Dataset | Built for public release (layered licenses); the repo stays private until that call is made |
| Infra | None. Offline-first; data packs via GitHub Releases when updates ship |
| Distribution | Sideloaded; Google Play for a public launch |
| Telemetry | None until a Play listing. Then opt-in (default off): Firebase Analytics + Crashlytics, single toggle |

## 5. Data Sources & Licenses

Canonical per-source table: `docs/ATTRIBUTIONS.md`. In brief:

| Source | Provides | License |
|---|---|---|
| Wiktionary via Wiktextract (kaikki.org) | glyph origins, definitions, readings, embedded Baxter-Sagart & Zhengzhang reconstructions | CC BY-SA 4.0 |
| Unihan | multi-language readings, S↔T variants, radicals, kSpoofingVariant, shape codes | Unicode License v3 |
| CC-CEDICT | word definitions | CC BY-SA 4.0 |
| makemeahanzi | decomposition, semantic/phonetic etymology fields; graphics under Arphic PL | dictionary data derived from Unihan+CJKlib; **LGPL-3.0-or-later** |
| cjkvi-ids (CHISE) | region-tagged IDS decompositions | GPL |
| OpenCC | S↔T conversion tables | Apache 2.0 |
| Guangyun-derived repos (rime, poem/guangyun) | Middle Chinese readings | per-repo, mostly permissive |
| FrequencyWords (OpenSubtitles) | word frequency ranks | CC BY-SA 4.0 |
| complete-hsk-vocabulary | HSK 3.0 levels | MIT |
| Tatoeba | example sentences | CC BY 2.0 FR |
| Dong Chinese wiki dumps | private QA of component functions for the top ~1000; not for the published dump until license is confirmed | unconfirmed |

**Excluded:** 小學堂 & hanziyuan.net images (rights unclear), SUBTLEX-CH (research license), Baxter-Sagart's own distribution files (possible NC terms — reconstructions ingested via Wiktionary instead; confirm with counsel).

**Dataset publication:** layered per-source files each retaining upstream license; computed/derived layer under CC BY-SA 4.0. Legal check on merged-form licensing (note: CC BY-SA 4.0 is one-way compatible into GPLv3). makemeahanzi is LGPL-3.0, which the original source table missed — `docs/ATTRIBUTIONS.md` is the authority.

## 6. Coverage Tiers (shown honestly in UI)

- **T1 — full drift analysis:** characters with OC + MC data (~4–5k; covers essentially all HSK).
- **T2 — modern-computed:** function + reliability from pinyin wherever IDS + readings exist (~20k+).
- **T3 — decomposition only:** remaining CJK.

## 7. Schema (canonical)

Design principles: **codepoint = primary entity** (S/T are `variant_edge`s, so mergers and per-script analysis fall out naturally); **hybrid provenance** (typed tables for mechanical facts, `claim` table for interpretive ones); unencoded components use `ids:` pseudo-IDs; every computed field carries `resolution` for honesty badges. The pack schema lives in `pipeline/pipeline/schema.sql`; this section is the contract, not a second copy of every column.

```sql
glyph(id PK, char, scripts, stroke_count, freq_rank, hsk, definition, cedict_defs)
reading(glyph_id, system, value, sense_hint, freq, source_id)
  -- system: pinyin|jyutping|mc_baxter|oc_bs|oc_zz|jp_on…
decomposition(glyph_id, ids TEXT, region, source_id)
component_use(glyph_id, component_id, position, function, match_class, confidence, resolution)
claim(subject_id, predicate, value JSON, source_id, source_ref, confidence, retrieved_at)
variant_edge(from_glyph, to_glyph, relation, mapping_type, sense_condition, source_id)
phonetic_series(component_id PK, member_count, reliability, best_stratum)
word(id PK, simp, trad, pinyin, defs JSON, freq_rank, hsk)
word_char(word_id, glyph_id, position)
sentence(simp, zh, en, tatoeba_id, quality, shown)
component_gloss(component_id PK, gloss, source_id, support, agreement)
script_form(glyph_id, era, image_ref, source_id)   -- declared; unused
confusable(glyph_a, glyph_b, kind, diff)
audio(reading_id, file, source)                    -- declared; unused (device TTS)
source(id PK, name, license, url, version, ingest_date)
```

**On-device user DB** (`user.db`, migrates in place; `app/core/.../User.sq`):

```sql
known_item(item_id PK, kind, status, added_at)          -- the archive; global
study_list(id PK, name, created_at)
list_item(list_id, item_id, kind, added_at)
card_state(item_id PK, kind, state, step, stability, difficulty, due, last_review, reviews, lapses)
review_log(item_id, rating, ts, elapsed_days, stability, difficulty, origin)
lookup_event(item_id, ts)
confusion_event(glyph_a, glyph_b, ts)                   -- reserved for v2 quizzes
photo_find(glyph_id, photo_id, found_at, dismissed_at)
scan_state(id, last_photo_timestamp)
app_pref(key PK, value)
```

Known-item imports: HSK level picker and paste. Anki `.apkg` / Pleco export are deferred.

## 8. Merge / Validation Pipeline (CI only; no server)

1. **Ingest** — per-source fetchers → immutable versioned snapshots + license metadata.
2. **Normalize** — NFC, IDS operator unification, canonical predicates.
3. **Resolve entities** — codepoint keys; unification table for makemeahanzi ↔ cjkvi-ids component tokens.
4. **Merge** — *mechanical* fields: agreement → accept; conflict → fixed priority (Unihan for readings; cjkvi-ids over makemeahanzi for decomposition, esp. traditional, except when a decomposition's parts are not navigable characters) → else conflict queue. *Interpretive* fields: never vote; all stored as claims and displayed with disagreements.
5. **Compute**
   - *Phonetic detector:* compare component vs. character readings at pinyin / Middle Chinese / Old Chinese strata → `function`, `match_class`, `best_stratum` (the last stratum where the link held **is** the drift explanation). Compares against *all* readings of a component and takes the best match.
   - *Series reliability:* frequency-weighted rollup of member match_classes.
   - *Simplification classifier:* OpenCC + systematic-substitution rules + merger detection (one simp → multiple trad); everything unmatched auto-labels `irregular (unclassified)` — a computable residual category, so **no curation is required to ship**.
   - *Confusables:* Unihan kSpoofingVariant + shape-code neighbours (Cangjie/Four-Corner) + pairs that share a big distinctive component. See `pipeline/lookalike.py`.
6. **QA gates** — referential integrity, IDS parse validity, S↔T round-trip, **license lint** (blocks NC-tagged data from the publishable set), diff vs. previous build.
7. **Publish** — versioned SQLite+FTS5 app pack (`core.db` + `ids.db`); open JSONL dump with attributions when the dataset is public. Conflict queue → GitHub issues on the dataset repo.

**IDS region rule:** prefer G-tagged IDS for simplified glyphs and T-tagged for traditional; fall back to untagged.

**GPL layering in the shipped pack:** two SQLite files — `core.db` (BY-SA-side sources + computed layer) and `ids.db` (cjkvi-ids-derived decompositions, GPL) — opened as two SQLDelight drivers, never ATTACHed. The app never migrates a pack; a new pack fully replaces the old one.

## 9. App

**Stack:** Kotlin, Jetpack Compose, min SDK 26. Non-UI core (data access, search, pack reader, FSRS, widget/practice state machines) as a **pure-Kotlin JVM module** (`:core`) for testability and later iOS reuse. SQLDelight (+FTS5 via requery). Bundled Noto Sans CJK SC.

**Navigation.** Three tabs, and a lookup is a layer over one of them — never a destination. There is no nav graph and no back stack.

- **Study** — the FSRS deck for the active list (or Everything). The practice card is the surface; looking a character up does not leave it.
- **Dictionary** — search, the photo-library feed, and recently viewed. This is also the capture surface.
- **Library** — named lists, HSK/encountered pools, archive, session progress.

Tapping a component chip, word row, search result, or photo tile **pushes a level onto that tab's lookup sheet**. The sheet carries its own trail (好 › 女 › 妈) and, when a practice card is suspended under it, a chip back to that card. System back pops a trail level, then dismisses the sheet — it never leaves the tab. Settings, Archive, and the list editor are **overlays** above the tabs, not a fourth tab.

A component chip opens the component's *character* entry, not its component page. The component page (what else shares this part) is reached from the plain footer links at the foot of an entry. Only a combining form with no character of its own (氵) opens the component page directly.

**Capture:** photo-library scanning — ML Kit Text Recognition v2 Chinese (unbundled, on-device) OCRs recent camera-roll photos (watermark-deduped, `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`) and surfaces found characters in the Dictionary tab's feed. Swipe right files a find into the active list; swipe left dismisses it. Plus: SEND-intent share target (one hanzi opens its entry, more prefills search), paste-on-open, system handwriting IME, component-compose search off the IDS index as a mode chip.

**Search ranking:** exact char > word > pinyin (tone-optional, including run-together syllables) > English FTS > fuzzy (typos and zh/z, ch/c, sh/s, l/n). Traditional hits fold into the simplified row. Results whose glyph the bundled face cannot draw are dropped (no tofu). Compose-search is a mode chip, not a separate screen.

**Character entry** (lookup sheet):
1. Header — glyph, all pinyin readings (frequency order; extras as "also …"), TTS on the character, CC-CEDICT senses leading with Unihan `kDefinition` as fallback, freq/HSK/stroke line, **Add to list** + Archive.
2. Breakdown chips — components color-coded by function, spoken names or readings, role gloss on meaning parts; tap → that component's character entry.
3. Formation / etymology claims, first sentence then expandable. A Wiktionary etymology that is a "Simplified from X" note is stored with X's etymology after it (or another traditional form's, if X has none).
4. In words — containing words, corpus-frequency first; tap → word entry. Heteronyms keep a row per reading.
5. Footer links — phonetic series of the sound part, and "X as a component".

**Word entry:** simp (and trad when it differs), pinyin, TTS, senses, Add to list / Archive, one chip per constituent character, Tatoeba/TED example sentences with in-sheet credit.

**Component entry:** names + own readings, phonetic members ("sound series"), semantic members ("shares this meaning part"), link through to the component's own character entry when it has one. Positional variants (水/氵/氺) resolve in compose-search and on the breakdown.

**Practice card:** structure, not headings. Front is the glyph; revealed side is bands split by hairlines — answer, components, words, sentences. The glyph sits in a fixed zone and must not move when the card flips: revealed content only ever grows *below* it. That side scrolls and is meant to fill.

**Audio:** the device's Mandarin TTS (`android.speech.tts`, zh-CN) on the primary reading of a character and on words/sentences. A device without an installed Mandarin voice shows no audio. Only the primary 多音字 reading is spoken (an engine reads a bare character with its most common pronunciation). Readings without a Wiktionary sense pairing are listed unpaired.

**Gestures, app-wide:** right = affirm (knew it / add / archive), left = negative (didn't know / remove), up = skip. Grading is swipe-only and only two grades (Again / Good); Hard/Easy exist in FSRS arithmetic and are never offered. Every swipe also exposes a TalkBack `CustomAccessibilityAction`.

**Settings overlay:** theme (auto/light/dark), swipe-hint toggle, pack version, attributions listing every `source` row.

**UI language:** English-only.

**Updates:** the app ships a bundled pack and copies it on first run / version bump.

## 10. Telemetry & Privacy

No telemetry ships. A Play listing requires a Data Safety disclosure and, if analytics are added, a single onboarding toggle defaulting **off** for Firebase Analytics + Crashlytics. No ad ID. User data never leaves the device otherwise.

## 11. Success Metrics

- Activation: first successful lookup < 60 s from install.
- Depth: share of lookups that open a component or series.
- Habit: returning use; list and archive adoption.
- Capture: photo-scan yield that gets filed rather than dismissed.

## 12. Remaining v1

Still in scope for v1:

- Scholarly tier: GlyphWiki `script_form` ingest + oracle→seal / OC/MC display on the character entry.
- Coverage-tier and `resolution` badges on the sheet (the fields are already computed).
- Look-alikes listed on character and word entries (the pack already stores them).
- Stroke playback from makemeahanzi medians; subsetted SC+TC typefaces with explicit per-script faces.
- Sense↔reading pairing from Wiktionary's per-pronunciation senses.
- Pack updates from GitHub Releases (semver, delta, SHA-256 + signature, failure state).
- Strings externalized; provenance footer's prefilled GitHub-issue report link.
- Play listing + Data Safety; counsel review of the published merged dump and the Baxter-Sagart ingestion path.
- Dataset repo public, with contribution templates and the conflict-queue → issues loop — only after the publication call.

## 13. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Correlated sources echo folk etymology (Shuowen lineage) | Never vote on interpretive claims; computed function labels; provenance UI; gold-set spot checks |
| Baxter-Sagart licensing ambiguity | Ingest via Wiktionary (BY-SA); counsel review pre-launch |
| GPL (cjkvi-ids) × BY-SA × LGPL (makemeahanzi) in one dump | Layered per-source publication; two-file pack split; counsel review of merged form |
| ML Kit accuracy on stylized signage/handwriting | Photo-library scan rather than live viewfinder; share/paste/IME as backups |
| Bundle size | Font subsetting, unbundled ML Kit; target < 150 MB installed |

## 14. Deferred (v2+)

Confusable quizzes seeded by `confusion_event`s, a look-alike drill, mnemonics layer (clearly labeled non-etymology), Cantonese/topolect readings surfaced as drift evidence, recorded word audio, in-app suggest-edit, Anki `.apkg` and Pleco-export imports, on-device FSRS parameter optimisation, iOS (SwiftUI over KMP core), web app, live camera OCR.

## 15. Cross-cutting rules

- **多音字:** the header enumerates *all* readings, ordered by Unihan `kHanyuPinlu` frequency. Sense↔reading pairing is taken from Wiktionary where present, else shown unpaired. TTS plays the primary reading only.
- **Component spoken names (三点水 etc.):** extracted from zh.wikipedia / Wiktionary radical & 偏旁 name tables (CC BY-SA); a combining form's name displaces its Unihan reading.
- **Word entry:** header (simp/trad, pinyin, TTS, defs, freq/HSK) + one chip per constituent character linking to character entries + example sentences.
- **HSK lists:** community MIT-licensed repos (word lists as facts). `glyph.hsk` is the lowest HSK 3.0 level of any word *containing* the character.
- **Data pack integrity (when updates exist):** manifest with SHA-256 + signature verified before install.
- **Accessibility & states:** TalkBack labels on breakdown chips, audio buttons, and swipe actions; defined empty/error states for search-no-result, OCR-no-text, and (when built) pack-download-failure.
- **Attributions:** Settings lists every `source` row (name, license, version) with access to the texts in `LICENSES/`.

## 16. Capture & audio

**Capture is photo-library scanning**, not a live viewfinder. The Dictionary tab's "From your photos" feed is the full scrollable list of finds with a rescan control; there is no separate Photos screen. A find stays in the feed, greyed under an on-tile Undo, until the feed settles or the user leaves it.

**Audio is the device's Mandarin TTS.** A generated syllable bank is not shipped — tones failed an ear-check. Wikimedia Commons recordings remain a preference for a later audio pass.

## 17. Study system

**FSRS-6** schedules practice cards (Free Spaced Repetition Scheduler, Open Spaced Repetition, MIT). Implemented as pure arithmetic in `core/Fsrs.kt` (the community Kotlin port carries no LICENSE), with default parameters, desired retention 0.90, learning steps 1 min/10 min, relearning step 10 min, and interval fuzz. Verified against py-fsrs's golden interval sequence. On-device parameter optimisation stays v2 — `review_log` records every grading so it stays possible.

**Deck order:** never-graded cards lead, capped per day (`NEW_PER_DAY=15`) so a large import cannot bury reviews; then cards due now, ordered by *ascending predicted recall*; then, when nothing is due, a top-up of not-yet-due cards in the same order, so Practice and the widget are never empty. New-card *presentation* order is shuffled per session; due/ahead stay recall-sorted. Within a session a missed card returns to the back of the queue; a skip (swipe up) does the same without grading.

**Study lists.** Multiple named lists (`study_list`/`list_item`), holding characters *and* words. Membership is per list; **memory is not** — `card_state` is keyed by item alone, so an item in three lists has one schedule. **Archiving is global**: "I know this" writes `known_item`, which every deck filters on, so archiving in one list archives in all of them (and discards the schedule). Archived items stay *in* their lists as a record.

**One editing surface.** Membership checkboxes plus the archive action live in a single sheet opened from an item. On character and word entries the primary button is **"Add to list"** — filing something to study is the common act; archiving is the rare one, and it says on the control that it applies everywhere.

**Active list.** One list is active at a time. It is where swipe-right files things and what the home-screen widget follows; "Everything" (all lists pooled) is a valid choice.

**Which flashcard is on screen is a pin**, not composable state (`CardPin` + `deckOpenedAt` in `:core`). Decks get rebuilt constantly; re-dealing the top card each time would lose the card you just looked up. A widget tap sets the pin *and* bumps a resume token so an already-open deck repositions. After 30 minutes idle (`isStaleSession`) the pin, query, lookup stacks and overlay reset and the app lands on Study; an arriving intent still wins.

**Home-screen widget.** One card from the active list. Launchers give widgets taps and no gestures, so the swipe grammar becomes three tap zones: left = Again, right = Good, middle flips the card and then opens **that item in a lookup sheet over Study**, pinning it so Practice resumes on it too. Grading only — archiving needs the full entry. Feedback arrives one card late (the answer to the card just graded is echoed under the next one), because a widget cannot reveal-then-advance without a timer. Built on `RemoteViews` (whitelist view classes only; a bare `<View>` makes the launcher refuse the widget).

**Study reminder.** An optional notification (Settings → Reminders, off by default) is the practice screen in miniature, in the app's own palette and type (amber ✗ AGAIN and jade ✓ KNEW gutters, a paper0 card with the glyph in Noto CJK; text is drawn as bitmaps because RemoteViews only get system fonts): the gutters grade in place (`review_log.origin = 'notification'`), and a tap on the card opens it through the widget's hand-off. After a grade the next card is dealt with the *previous* card's answer shown above it, ending after five cards. An hourly WorkManager job only asks the pure `shouldNudge` rules (`core/Nudge.kt`): inside a waking window, something due or unseen, and either *adaptive* (about a day after the last review, once a day, skipped if you already studied, backing off to every 48 h after four ignored nudges) or *interval* (every 2/4/8 h). Rationale and caveats: `docs/research/flashcard-retirement.md`.

**Retiring a card.** The app never archives on its own and never treats one correct answer as learned. `core/Mastery.kt` derives *learned* / *ready to archive* / *struggling* from graded history (a streak of ≥ 3 successes on distinct days, the latest after a long gap, FSRS stability ≥ 180 days for "ready"; any miss resets), shown as a tag and a "Ready to archive" filter on list detail and as an evidence line above the Archive button in the lookup sheet. `review_log.reveal_ms` records face-down time on the practice screen; it is recorded, not yet used.

**New words.** The app suggests vocabulary only while the deck is thin (`core/DeckHealth.kt`): fewer than a sitting (5) of cards due or unseen in the next two days, backlog under two sittings, recent accuracy ≥ 80%. A row on the deck strip opens a triage overlay over unseen HSK words (easiest level first, then most common): right = know, left = don't know, up = skip. *Know* files the word with an Easy first rating so FSRS schedules a recall check about a week out; the claim is never trusted outright and is logged with `origin = 'triage'`, which mastery and the accuracy gate ignore. These thresholds are heuristics where the research has no validated rule.

**Counter names.** The "didn't know"/"knew this" tallies are stored as `lapses` and `reviews` (Anki's vocabulary) and shown as "missed"/"recalled".
