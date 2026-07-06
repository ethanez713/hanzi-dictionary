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
- **M3 (beta)** — partially done. Done + verified: HSK levels (glyph.hsk + import picker),
  confusables (Unihan kSpoofingVariant), and the known-characters model (on-device user DB,
  "I know this", My Characters + HSK/paste import, known-first series ordering — the spec's
  headline differentiator). Core suite 10/10. **Deferred pending device/perceptual
  verification:** camera OCR (needs a camera; ML Kit) and the audio syllable bank (needs
  ears to check tone correctness). Anki .apkg import deferred (needs a sample file).
