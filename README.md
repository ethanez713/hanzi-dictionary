# Hanzi Component Dictionary

Offline-first Android dictionary explaining how Chinese characters work — component
breakdowns, computed phonetic-reliability scores, drift explanations, S/T mapping types,
confusables, and component-series navigation, with per-claim provenance.

Full spec: [docs/hanzi-dictionary-spec-v1.md](docs/hanzi-dictionary-spec-v1.md).

## Layout

- `pipeline/` — data ingestion, normalization, merge, and compute pipeline (Python). Produces
  the SQLite data packs the app consumes. Intended to be split out into its own publicly
  published repo once the dataset is ready to release (spec §5, §8.7).
- `app/` — Kotlin/Compose Android app (added later, per milestone order in spec §12).
- `docs/ATTRIBUTIONS.md` — per-source license/attribution, read this before publishing anything.
- `LICENSES/` — full text of every license the bundled data carries.

## Status

- **M0 (pipeline PoC)** — done: ingest 5 core sources, phonetic detector validated against
  Dong Chinese's human-verified etymologies.
- **M1 (data pack v0.1)** — engineering done: full T1/T2 coverage (~42.8k characters, ~11.8k
  with Middle+Old Chinese data), 3-stratum phonetic detector (pinyin/MC/OC), QA gates green,
  core.db/ids.db license-layered split + FTS5 + open JSONL dump built. **Not yet done:**
  actually publishing the dataset repo publicly — see `docs/ATTRIBUTIONS.md` first, in
  particular the makemeahanzi LGPL-3.0 correction, which the spec's original source table
  didn't flag.
- **M2 (app alpha)** — done: Kotlin/Compose app (`app/`) with search + character page +
  component page over the offline pack. Core repository unit-tested against the real pack,
  debug APK builds (~37.5 MB). See `app/README.md`.
- **M3 (beta)** — mostly done. Verified in-sandbox: HSK levels (glyph.hsk + import picker),
  confusables (Unihan kSpoofingVariant), the known-characters model (on-device user DB,
  "I know this", My Characters + HSK/paste import, known-first series ordering — the spec's
  headline differentiator), and photo-library scanning (scan recent camera-roll photos for
  hanzi via ML Kit, watermark dedup — workflow logic unit-tested with fakes). Core suite
  15/15. Audio: device Mandarin TTS on the primary reading (the Piper syllable bank
  failed the ear-check — inaccurate tones — and was removed; spec §16). Live camera OCR
  dropped in favor of photo scanning. Anki .apkg import deferred (needs a sample file).
- **M3.5 (definitions & attributions)** — done except sense↔reading pairing (needs
  wiktextract per-pronunciation senses in the pipeline; shown unpaired for now, which
  §15 permits): character-page glosses via Unihan `kDefinition` (radical forms like 讠
  derive learner glosses from their parent, e.g. "words, speech"), CC-CEDICT ingested
  (~110k words, "In words" section, tone-marked pinyin, word-level HSK), English search
  over glosses, word search (word_fts: CJK prefix / pinyin syllables / English) with
  word pages (both forms, senses, per-character strip, device-TTS word audio), and the
  in-app ⓘ attributions dialog.
- **M3.7 (capture/search completion)** — partial: SEND share target (share text from any
  app; one hanzi opens its page, more prefill search) and paste-on-open (clipboard hanzi
  offered as a one-tap search when the window gains focus). Remaining: component-compose
  search chip, fuzzy fallback.
- **M3.56 (study system)** — done: named **study lists** holding characters and words, all
  edited from one bottom sheet (membership checkboxes + a global archive action); "Add to
  list" is the primary button on character and word pages. Practice is scheduled by
  **FSRS-6** (`core/Fsrs.kt`, no dependency, checked against py-fsrs's golden interval
  sequence): due cards first in ascending predicted recall, a per-day cap on new cards,
  then practice-ahead so the deck is never empty. Swipe interaction throughout — right
  affirms (save / knew it / archive), left negates (didn't know / remove). The intro screen
  shows the whole photo feed as detailed rows with **Scan again**. A home-screen widget
  serves one card from the active list: tap left/right to grade, tap the middle to open it
  in Practice. Spec §17.
- **Learning features (beyond spec)** — practice deck sources (encountered / HSK band /
  component series), reverse (production-recall) cards, confusable drills writing
  confusion_event, per-HSK progress bars on the Archive.
- **M3.55 (practice & archive)** — done: flashcard Practice screen over characters
  encountered (lookups/photo finds) but not yet known; "I knew it" archives them, and
  My Characters is reframed as the Archive (spec §16). No SRS scheduling (v2).
- **M3.6** — partial: coverage-tier honesty chip (T1 full historical data / T2 modern
  only) in Details, per-component resolution badges (✓ cross-checked / source-asserted /
  sources disagree), function distribution + positional-variant links (水/氵/氺) on
  component pages. Remaining: GlyphWiki script forms + scholarly tier, spoken names,
  Wikimedia syllable recordings.
- **M3.8–M3.9** — not started (app polish: fonts subsetting, strokes, confusable
  compare, series browser, settings, strings; pack updates — blocked on the public
  dataset repo decision). Spec §12 remains the complete work plan.
