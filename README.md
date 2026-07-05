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

## Status

M0 (pipeline PoC) in progress: ingest 5 core sources, build SQLite for ~1,000 characters,
validate the phonetic detector against Dong Chinese's human-verified top-1000.
