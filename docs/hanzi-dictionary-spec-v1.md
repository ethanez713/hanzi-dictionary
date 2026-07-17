# Hanzi Component Dictionary — Product Spec v1.2 (reviewed)

## 1. Overview

A free, offline-first Android dictionary that explains **how Chinese characters work**: per-script semantic/phonetic/empty component breakdowns, computed phonetic-reliability scores, drift explanations grounded in historical phonology, simplified↔traditional mapping types, confusable disambiguation, and component-series navigation — all with per-claim provenance from open data sources.

**Positioning:** the open, personalized alternative to Pleco+Outlier. Differentiation is not data volume but (a) friction-free capture, (b) connections filtered by *the user's own known characters*, (c) computed (not copied) component-function labels with honest sourcing, (d) a fully open published dataset.

**Precise gap claim (verified July 2026):** free component *breakdowns* already exist (Dong Chinese wiki — human-verified top ~1000 with open dumps; Hanly; ArchChinese). No product, free or paid, combines computed phonetic-reliability scores + per-stratum drift explanations + per-claim provenance + known-vocab-filtered series + offline native both-script + a 20k-scale open dataset. Marketing must claim that combination, never "first free breakdown app."

## 2. Goals & Non-Goals

**Goals (v1)**
- Sub-10-second path from "saw a character" to full component breakdown.
- Component pages as first-class citizens (series, reliability, names, variants).
- Both scripts treated equally, with mapping-type explanations and merger warnings.
- "Known characters" model from day one (imports + one-tap marking).
- Published open dataset with per-record attribution (CC BY-SA compliance).

**Non-Goals (v1)** — explicitly deferred: SRS/review scheduling, quizzes, mnemonics layer, sentence reader, handwriting engine (system IME suffices), Cantonese/topolect UI surfacing (data is stored), word-level audio recordings, iOS app, web app, backend/accounts.

## 3. Target User

Intermediate self-learners (≈300–2,000 known characters) who already own Pleco. Default explanation depth: learner tier; scholarly tier (script forms, OC/MC readings) behind expansion. Component spoken names (三点水, 提手旁) shown throughout.

## 4. Decision Log

| Decision | Choice |
|---|---|
| Product core | Dictionary first; SRS later (data model SRS-ready) |
| Audience | Intermediate self-learner |
| Scripts | Simplified & Traditional, equal priority |
| Platform | Native: Kotlin/Compose Android first; Swift/SwiftUI iOS later; web later |
| Business | Free; no monetization at launch; ads permissible later (BY-SA allows commercial use) |
| Data strategy | Open sources only; no human/LLM etymology review; cross-reference + computed validation; provenance-first display |
| Dataset | Published openly (GitHub); community corrections via issues/PRs |
| Infra | None. Offline-first; data packs via GitHub Releases |
| Distribution | Google Play only |
| Telemetry | Opt-in (default off): Firebase Analytics + Crashlytics, single toggle |

## 5. Data Sources & Licenses

| Source | Provides | License / status |
|---|---|---|
| Wiktionary via Wiktextract (kaikki.org) | glyph origins, definitions, readings, embedded Baxter-Sagart & Zhengzhang reconstructions | CC BY-SA 4.0 |
| Unihan | multi-language readings, S↔T variants, radicals | permissive (Unicode) |
| CC-CEDICT | word definitions | CC BY-SA |
| makemeahanzi | decomposition, semantic/phonetic etymology fields, stroke SVGs/medians | dictionary data derived from Unihan+CJKlib; graphics under Arphic PL |
| cjkvi-ids (CHISE) | region-tagged IDS decompositions | GPL |
| OpenCC | S↔T conversion tables | Apache 2.0 |
| Guangyun-derived repos (rime, poem/guangyun) | Middle Chinese readings | per-repo, mostly permissive |
| GlyphWiki | ancient/variant glyph images | free |
| wordfreq | frequency ranks | MIT |
| Dong Chinese wiki dumps / chinese-lexicon (optional) | human-verified component functions + etymology for top ~1000 chars, per-script (simp/trad) etymologies, 多音字 pinyin frequencies | monthly dumps "free to download" — **confirm license before ingesting**; usable privately for validation regardless |

**Excluded:** 小學堂 & hanziyuan.net images (rights unclear), SUBTLEX-CH (research license), Baxter-Sagart's own distribution files (possible NC terms — reconstructions ingested via Wiktionary instead; confirm with counsel).

**Dataset publication:** layered per-source files each retaining upstream license; computed/derived layer under CC BY-SA 4.0. Legal check on merged-form licensing (note: CC BY-SA 4.0 is one-way compatible into GPLv3).

## 6. Coverage Tiers (shown honestly in UI)

- **T1 — full drift analysis:** characters with OC + MC data (~4–5k; covers essentially all HSK).
- **T2 — modern-computed:** function + reliability from pinyin wherever IDS + readings exist (~20k+).
- **T3 — decomposition only:** remaining CJK.

## 7. Schema (canonical)

Design principles: **codepoint = primary entity** (S/T are `variant_edge`s, so mergers and per-script analysis fall out naturally); **hybrid provenance** (typed tables for mechanical facts, `claim` table for interpretive ones); unencoded components use `ids:` pseudo-IDs; every computed field carries `resolution` for honesty badges.

```sql
glyph(id PK, char, scripts, stroke_count, freq_rank, hsk)

reading(glyph_id, system, value, sense_hint, source_id)
  -- system: pinyin|jyutping|mc_baxter|oc_bs|oc_zz|jp_on…

decomposition(glyph_id, ids TEXT, region, source_id)  -- IDS tree; region G/T/J

component_use(glyph_id, component_id, position,
  function,       -- semantic|phonetic|both|empty|unknown
  match_class,    -- exact|tone|initial|rime|oc_only|opaque (computed)
  confidence, resolution)  -- computed|agreement|single_source|conflict

claim(subject_id, predicate, value JSON, source_id, source_ref,
  confidence, retrieved_at)
  -- interpretive only: component_function, original_meaning,
  -- formation_type, etymology_text, simplification_note

variant_edge(from_glyph, to_glyph, relation,
  mapping_type,   -- systematic|cursive|merger|new_coinage
  sense_condition, source_id)

phonetic_series(component_id PK, member_count, reliability, best_stratum)

word(id PK, simp, trad, pinyin, defs JSON, freq_rank, hsk)
word_char(word_id, glyph_id, position)

script_form(glyph_id, era, image_ref, source_id)
confusable(glyph_a, glyph_b, kind, diff)          -- computed
audio(reading_id, file, source)                    -- tts|recorded
source(id PK, name, license, url, version, ingest_date)
```

**On-device user DB (separate):**

```sql
known_item(item_id, kind, status, added_at)   -- SRS columns reserved
lookup_event(item_id, ts)
confusion_event(glyph_a, glyph_b, ts)
```

Known-item imports: HSK level picker, Anki .apkg, Pleco export.

## 8. Merge / Validation Pipeline (CI only; no server)

1. **Ingest** — per-source fetchers → immutable versioned snapshots + license metadata.
2. **Normalize** — NFC, IDS operator unification, canonical predicates.
3. **Resolve entities** — codepoint keys; unification table for makemeahanzi ↔ cjkvi-ids component tokens.
4. **Merge** — *mechanical* fields: agreement → accept; conflict → fixed priority (Unihan for readings; cjkvi-ids over makemeahanzi for decomposition, esp. traditional) → else conflict queue. *Interpretive* fields: never vote; all stored as claims and displayed with disagreements.
5. **Compute**
   - *Phonetic detector:* compare component vs. character readings at pinyin / Middle Chinese / Old Chinese strata → `function`, `match_class`, `best_stratum` (the last stratum where the link held **is** the drift explanation).
   - *Series reliability:* frequency-weighted rollup of member match_classes.
   - *Simplification classifier:* OpenCC + systematic-substitution rules + merger detection (one simp → multiple trad); everything unmatched auto-labels `irregular (unclassified)` — a computable residual category, so **no curation is required to ship**. Optional quality upgrade later: a ~500-row curated table splitting irregulars into cursive-derived vs. new-coinage.
   - *Confusables:* stroke-median distance (makemeahanzi) + IDS-differs-by-one.
6. **QA gates** — referential integrity, IDS parse validity, S↔T round-trip, **license lint** (blocks NC-tagged data from publishable set), diff vs. previous build.
7. **Publish** — versioned SQLite+FTS5 app pack; open JSONL dump with attributions. Conflict queue → GitHub issues on the dataset repo (community review loop).

## 9. App

**Stack:** Kotlin, Jetpack Compose, min SDK 26. Non-UI core (data access, search, pack reader) as a **pure-Kotlin KMP-ready module** for iOS reuse. SQLDelight (+FTS5). Bundled subsetted Noto Sans SC + TC with explicit per-script typefaces. Stroke rendering from makemeahanzi medians via Compose Canvas.

**Capture:** ML Kit Text Recognition v2 Chinese (unbundled, on-device) behind a `CaptureProvider` interface; camera → tap-any-character → entry; Hans+Hant recognition with script post-detection; single-character crop mode with N-best. Plus: SEND-intent share target, paste-on-open, system handwriting IME, component-compose search off the IDS index.

**Search ranking:** exact char > word > pinyin (tone-optional) > English FTS > fuzzy; compose-search as mode chip; camera button inline.

**Character page IA:**
1. Header — both script forms side by side, pinyin + audio, top glosses, freq/HSK chips, "I know this" toggle.
2. Breakdown strip — components color-coded by function, spoken names, tap → component page.
3. Sound line — reliability badge + drift sentence from `best_stratum`.
4. Formation — type + etymology claims with source chips; expandable scholarly tier (oracle→seal forms, OC/MC).
5. Script mapping card — mapping_type + merger warnings.
6. In your words — containing words, known-first then frequency.
7. Series — phonetic siblings grouped by match_class; semantic siblings.
8. Confusables — side-by-side diff highlighting.
9. Provenance footer — sources + prefilled GitHub-issue report link.

**Component page:** names + own readings, function distribution across characters, series grouped by function (known-first), reliability meter, positional variants (水/氵/氺).

**Other screens:** word page, series browser, confusables compare, My Characters (known list + imports), Settings (script preference, depth default, telemetry toggle, pack updates).

**Audio:** syllable bank (~1,800 clips ≈ 20 MB, all syllable×tone combos incl. neutral tone) covers every character in isolation. **Generate with an open-source TTS model (MIT-licensed, e.g., Piper/MeloTTS)** — fully redistributable, avoiding cloud-TTS output-redistribution ambiguity; prefer Wikimedia Commons recordings (CC, surfaced in kaikki data) where they exist. On-device Android TTS for words (tone sandhi makes syllable concatenation wrong); word audio deferred.

**Updates:** semver data packs from GitHub Releases; delta downloads; app fully offline afterward.

## 10. Telemetry & Privacy

Default **off**; single onboarding toggle enables Firebase Analytics + Crashlytics. No ad ID. Events: search type used, entry views, component-section expansion, known-toggles, capture success/failure. Play Data Safety disclosure required. User data never leaves device otherwise.

## 11. Success Metrics

- Activation: first successful lookup < 60 s from install.
- Depth: % of lookups that open a component page or series.
- Habit: weekly returning users; known-list adoption rate.
- Capture: camera-lookup success rate.
- Ecosystem: dataset repo issues/PRs from non-maintainers.

## 12. Milestones

**Milestones are the complete work plan.** Every v1 requirement in this spec belongs to exactly one milestone below, so an implementer can work through the milestones without re-reading every section hunting for stragglers. If you find a spec requirement no milestone names, add it to a milestone before implementing — that drift is how definitions were missed the first time.

- **M0 — Pipeline PoC:** ingest 5 core sources; SQLite for 1,000 chars; phonetic detector validated by **automated agreement check against Dong Chinese's human-verified top-1000** (private QA use — no license needed), with manual review only of disagreements.
- **M1 — Data pack v0.1:** full T1/T2 coverage, QA gates green, dataset repo public.
- **M2 — App alpha:** search + character/component pages, offline pack install.
- **M3 — Beta:** photo-library scanning (revised from live camera OCR, §16), HSK/paste imports, confusables, syllable audio bank, merger cards.
- **M3.5 — Definitions & attributions:** CC-CEDICT ingested — character-page glosses, `word`/`word_char` populated, "In words" on character pages, word search + word pages (§15), English FTS in the search ranking (§9), sense↔reading pairing shown where present (§15); in-app ⓘ attributions element (§16).
- **M3.55 — Practice & archive:** flashcard deck of encountered-but-unknown characters (lookups + photo finds); "I knew it" archives; My Characters reframed as Archive (§16). No SRS scheduling (v2, §14).
- **M3.6 — Scholarly & component depth:** GlyphWiki script forms ingested (`script_form`) + the expandable scholarly tier on the character page (oracle→seal forms, OC/MC readings display, §9 IA item 4); component spoken names (~200 rows, §15) on breakdown strips and component pages; component-page positional variants (水/氵/氺) and function distribution (§9); coverage-tier / `resolution` honesty badges surfaced in the UI (§6, §7); prefer Wikimedia Commons syllable recordings where kaikki surfaces them (§9 Audio).
- **M3.7 — Capture & search completion:** SEND-intent share target; paste-on-open; component-compose search off the IDS index as a search mode chip; fuzzy-search fallback (§9).
- **M3.8 — App polish:** bundled subsetted Noto Sans SC + TC with explicit per-script typefaces (§9); stroke rendering from makemeahanzi medians via Compose Canvas (§9); confusables side-by-side diff highlighting + compare screen (§9); series browser (§9); Settings screen — script preference, explanation-depth default, pack updates (telemetry toggle lands with M4) (§9); TalkBack labels on breakdown strips and audio buttons + remaining defined empty/error states (§15); strings externalized (§15); provenance footer's prefilled GitHub-issue report link (§9); Anki `.apkg` and Pleco-export imports (§7).
- **M3.9 — Pack updates:** semver data packs on GitHub Releases; delta downloads; manifest with SHA-256 + signature verified before install (§15); pack-download-failure state (§15); app fully offline afterward (§9).
- **M4 — Launch:** Play listing + Data Safety disclosure (§10); dataset docs + contribution templates; conflict queue → GitHub issues loop on the dataset repo (§8); licensing/counsel review of the published merged dump and Baxter-Sagart ingestion path (§5, §13); opt-in telemetry (Firebase Analytics + Crashlytics, single toggle) live (§10).

## 13. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Correlated sources echo folk etymology (Shuowen lineage) | Never vote on interpretive claims; computed function labels; provenance UI; gold-set spot checks |
| Baxter-Sagart licensing ambiguity | Ingest via Wiktionary (BY-SA); counsel review pre-launch |
| GPL (cjkvi-ids) × BY-SA in one dump | Layered per-source publication; counsel review of merged form |
| ML Kit accuracy on stylized signage/handwriting | Crop mode + N-best candidates; provider abstraction allows swap |
| Scope creep toward learning app | Non-goals enforced; SRS-ready schema means no rework later |
| Bundle size | Font subsetting, opus audio, unbundled ML Kit; target < 150 MB installed |

## 14. Deferred (v2+)

SRS reviews with "component reunion" sessions, confusable quizzes seeded by `confusion_event`s, mnemonics layer (clearly labeled non-etymology), Cantonese/topolect readings surfaced as drift evidence, recorded word audio, in-app suggest-edit, iOS (SwiftUI over KMP core), web app.

## 15. v1.1 Review Addenda (all decided — no open questions)

- **多音字:** header enumerates *all* readings, each with its own audio; order by Unihan `kHanyuPinlu` frequency. Sense↔reading pairing taken from Wiktionary's per-pronunciation sense structure where present, else shown unpaired.
- **GPL layering in the shipped pack:** two attached SQLite files — `core.db` (BY-SA-side sources + computed layer) and `ids.db` (cjkvi-ids-derived decompositions, GPL) — so license layers stay separate in the app exactly as in the published dump. Bundle ARPHICPL license text + an in-app attributions screen listing all sources.
- **Component spoken names (三点水 etc.):** extracted from zh.wikipedia / Wiktionary radical & 偏旁 name tables (CC BY-SA); ~200 rows, mechanical extraction.
- **IDS region rule:** prefer G-tagged IDS for simplified glyphs and T-tagged for traditional; fall back to untagged.
- **Word page IA:** header (simp/trad, pinyin, audio via TTS, defs, freq/HSK) + one breakdown strip per constituent character linking to character pages.
- **HSK lists:** community MIT-licensed repos (word lists as facts; low risk).
- **Data pack integrity:** manifest with SHA-256 + signature verified before install.
- **UI language:** English-only v1; strings externalized for later localization.
- **Accessibility & states:** TalkBack labels on breakdown strips and audio buttons; defined empty/error states for search-no-result, OCR-no-text, pack-download-failure.
- **Remaining work that is engineering, not design:** deterministic parsers for Baxter MC notation and B-S OC notation (onset/rime extraction); phonetic detector compares against *all* readings of a component and takes the best match.

## 16. v1.2 Addenda

- **Definitions are an explicit v1 requirement, not implied.** M2/M3 shipped with `word`/`word_char` empty, so the app had no English glosses anywhere — unacceptable for a dictionary. Required: ingest CC-CEDICT; the character-page header's "top glosses" (§9 IA item 1) come from the character's own CC-CEDICT entries; populated `word`/`word_char` power "In your words" (§9 IA item 6), word-level search ranking (§9), and word pages (§15). Tracked as milestone M3.5.
- **Attributions UI element.** The §15 "in-app attributions screen" is satisfied by a lightweight ⓘ info button (e.g. in the search screen's top bar) opening a dialog or simple screen that lists every `source` row — name, license, version, URL (`HanziRepository.sources()` already returns exactly this) — with access to the full license texts already collected in `LICENSES/`. Required before any public/Play release; the sideloaded alpha may ship without it, but M3.5 includes it so it isn't forgotten.
- **Capture revision (decided during M3).** Live camera OCR — viewfinder, single-character crop mode, N-best candidates — is replaced by **photo-library scanning**: the app OCRs the most recent camera-roll photos (watermark-deduped, READ_MEDIA_IMAGES) and surfaces found characters in a "From your photos" feed. §9's Capture paragraph is superseded to that extent; the SEND-intent share target, paste-on-open, system handwriting IME, and component-compose search entries remain (M3.7).
- **Audio revision (decided after the smoke test).** The generated syllable bank (§9 Audio) failed the ear-check — the open-source voice's tones weren't accurate — so character audio now uses the **device's own Mandarin TTS** (`android.speech.tts`, zh-CN), which §9 already trusted for words. Accepted tradeoffs: only the primary reading of a 多音字 gets a play affordance (an engine reads a bare character with its most common pronunciation), and devices without an installed Mandarin voice show no audio. The Wikimedia-Commons-recordings preference (M3.6) and word audio (§14) are unaffected.
- **Practice & archive (decided post-smoke-test; supersedes part of §2's SRS deferral).** The known-characters model gains its missing consumer loop: characters the user *encounters* (lookups, photo finds) but hasn't marked known form a **practice deck** of simple flashcards (front: character; back: readings + gloss + a sample word + audio). "I knew it" archives the character into the known list — which keeps powering known-first series ordering (§1's differentiator) — and "My Characters" is reframed as that **Archive** (HSK/paste import = bulk-archiving what you already know). Deliberately *not* an SRS: no scheduling, no intervals — that remains v2 (§14); this is a self-check pass over the current deck. Tracked as milestone M3.55.
- **Smoke-test UI decisions (v0.1 alpha).** (a) Character-page glosses ship early via Unihan `kDefinition` (`glyph.definition`), shown directly under the header; CC-CEDICT (M3.5) remains the plan for full word data and richer glosses. (b) Reference metadata — frequency rank, HSK band, script, stroke count — collapses behind a "Details" toggle instead of a permanent chip row. (c) The breakdown strip suppresses the "unknown"/"empty" function labels (an associative compound like 好 showed a distracting "unknown" under every part). (d) Search results are filtered to characters the bundled Noto Sans CJK SC face can draw, so ultra-rare Ext-B+ glyphs never render as tofu; the font is bundled unsubsetted for now (§9's subsetting lands with M3.8).
