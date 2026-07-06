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
  15/15. Audio syllable bank (Piper TTS, 1,559 clips) generated + wired to tappable
  readings; structurally verified, **pending the user's ear-check for tone correctness**.
  Live camera OCR dropped in favor of photo scanning. Anki .apkg import deferred (needs a
  sample file).
- **M3.5 (definitions & attributions)** — added in spec v1.2: CC-CEDICT ingestion (the
  `word`/`word_char` tables are currently empty, so the app shows no English glosses) plus
  an in-app ⓘ attributions element (spec §16). Not started.
- **M3.6–M3.9** — added in spec v1.2: scholarly & component depth, capture/search
  completion, app polish, and pack updates. Spec §12 is now the complete work plan —
  every spec requirement belongs to exactly one milestone. None started.
