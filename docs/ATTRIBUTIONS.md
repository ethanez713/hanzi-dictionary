# Attributions

This dataset and app pack are built from open data sources, each retaining its
own upstream license. This file is the source of truth for both the published
dataset repo's NOTICE and the in-app attributions screen (spec §9's IA item 9,
§15). Full license texts are bundled in [`/LICENSES`](../LICENSES).

## Sources in the current build

| Source | Provides here | License | Full text |
|---|---|---|---|
| [Unihan](http://unicode.org/charts/unihan.html) (Unicode Consortium) | Readings (Mandarin, Cantonese, Japanese), S/T variant links, radical/stroke counts, kHanyuPinlu frequency, kSpoofingVariant confusable pairs, and the kCangjie/kFourCornerCode shape encodings the look-alike pairs are computed from (the shared-component look-alike layer is derived from the same decompositions as `component_use`) | Unicode License v3 (permissive) | [Unicode-License.txt](../LICENSES/Unicode-License.txt) |
| [Wiktionary via Wiktextract](https://github.com/tatuylonen/wiktextract) (kaikki.org) | Etymology text, Middle Chinese (Baxter) and Old Chinese (Zhengzhang, Baxter-Sagart) reconstructed readings | CC BY-SA 4.0 | [CC-BY-SA-4.0.txt](../LICENSES/CC-BY-SA-4.0.txt) |
| [makemeahanzi](https://github.com/skishore/makemeahanzi) `dictionary.txt` (derived from Unihan + [CJKlib](https://github.com/cburgmer/cjklib)) | Character decomposition (fallback when cjkvi-ids has none), semantic/phonetic component claims, formation-type claims | **LGPL-3.0-or-later** | [LGPL-3.0.txt](../LICENSES/LGPL-3.0.txt) |
| [cjkvi-ids](https://github.com/cjkvi/cjkvi-ids) (CHISE) | Region-tagged IDS decomposition (primary source; shipped separately as `ids.db`, see below) | GPL-2.0 | [GPL-2.0.txt](../LICENSES/GPL-2.0.txt) |
| [OpenCC](https://github.com/BYVoid/OpenCC) `STCharacters.txt`/`TSCharacters.txt` | Simplified/Traditional character conversion tables, cross-checked against Unihan for variant-edge classification | Apache-2.0 | [Apache-2.0.txt](../LICENSES/Apache-2.0.txt) |
| [complete-hsk-vocabulary](https://github.com/drkameleon/complete-hsk-vocabulary) | HSK 3.0 level assignments (glyph.hsk, word.hsk, hsk-levels.json import picker) | MIT | (short license, see upstream repo) |
| [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict) (MDBG export) | Word-level entries: `word`/`word_char` tables — simplified/traditional forms, pinyin, English definitions ("In words" on character pages) | CC BY-SA 4.0 | [CC-BY-SA-4.0.txt](../LICENSES/CC-BY-SA-4.0.txt) |
| [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles 2018 zh_cn) | `word.freq_rank` — word-level corpus frequency used for result ranking. Chosen over SUBTLEX-CH (research-use terms): same subtitle lineage, open license | CC BY-SA 4.0 | [CC-BY-SA-4.0.txt](../LICENSES/CC-BY-SA-4.0.txt) |
| [Tatoeba](https://tatoeba.org) (cmn + eng sentence exports, joined via links) | `sentence` table — example Mandarin sentences with English translations shown on word pages (spec §9). Short sentences containing each word, credited in-app | CC BY 2.0 FR | [CC-BY-2.0.txt](../LICENSES/CC-BY-2.0.txt) |
| [hanzi-writer-data](https://github.com/chanind/hanzi-writer-data) (makemeahanzi graphics, Arphic KaiTi outlines) | App launcher icon only: the brush-stroke outline paths of 字 (not shipped in the data pack) | Arphic Public License | [Arphic-Public-License.txt](../LICENSES/Arphic-Public-License.txt) |

## Bundled app assets (not part of the dataset)

| Asset | Provides | License | Full text |
|---|---|---|---|
| [Noto Sans CJK SC](https://github.com/notofonts/noto-cjk) (`res/font/noto_sans_cjk_sc.otf`) | UI typeface — renders CJK Extension chars and IDS operators the system font shows as tofu | SIL OFL 1.1 | [OFL-1.1.txt](../LICENSES/OFL-1.1.txt) |
| [FSRS-6](https://github.com/open-spaced-repetition) (Open Spaced Repetition) | The spaced-repetition algorithm and its 21 default parameters, used to schedule practice cards (`core/Fsrs.kt`, spec §17) | MIT | (algorithm + parameters; see note below) |

**On FSRS:** `core/Fsrs.kt` is an original Kotlin implementation written from the
published FSRS-6 formulas and verified against the reference implementation
[py-fsrs](https://github.com/open-spaced-repetition/py-fsrs) (MIT) by porting its
golden interval sequence into `FsrsTest`. No FSRS code is vendored. Note that the
community [FSRS-Kotlin](https://github.com/open-spaced-repetition/FSRS-Kotlin)
port ships **no license file** and so was deliberately not used — the MIT-licensed
Python and Rust implementations are the ones to reference if this is ever revisited.

**Personal build only (not for the published dataset):** [TED2020](http://opus.nlpl.eu/TED2020-v1.php)
(en-zh_cn, via OPUS) — professionally-translated TED-talk sentence pairs, mined by
`build.build_sentences` to fill example sentences for the ~1/3 of HSK words Tatoeba never
mentions (HSK multi-char word coverage ~67%→95%). TED talk transcripts are **CC BY-NC-ND
4.0**, so these sentences cannot be redistributed in a published dataset — the OPUS fill is
optional (built only when the snapshot is present), so a publishable Tatoeba-only pack is
still producible. See `pipeline/fetchers/fetch_opus.py`. **OpenSubtitles (OPUS) was evaluated
and rejected:** subtitle "translations" are localised/condensed rather than faithful, and
frequently mis-aligned — wrong for dictionary examples — for only ~4% more HSK coverage than
TED.

Also personal-build-only: **Dong Chinese lexicon** (`peterolson/chinese-lexicon`, built for
dong-chinese.com) — human-verified component functions + formation types for ~4,569 shipped
characters, parsed by `pipeline/dong.py` and used as the AUTHORITATIVE override for the
algorithmic detector on the characters it covers (user decision 2026-09-01: "Dong wins where
it has data"). Its license is unconfirmed for publication, so like TED2020 it must not go into
a published dataset — the override is optional (a clean checkout without the snapshot builds a
detector-only, publishable pack). See `pipeline/fetchers/fetch_dong_chinese.py`.

**Not currently included:** makemeahanzi's `graphics.txt`/stroke-order SVGs
(Arphic Public License) — deferred (stroke rendering not built; spec §9/M3).

## Why makemeahanzi matters here specifically

The spec's original source table (§5) described makemeahanzi's dictionary
data only as "derived from Unihan+CJKlib" without stating dictionary.txt's own
license. Checking the upstream repo's `COPYING` file directly: dictionary.txt
is **LGPL-3.0-or-later**, not a permissive license. That's a real copyleft
obligation (source availability, share-alike on modifications to that file),
not just an attribution nicety — it affects the "core.db (BY-SA-side sources
+ computed layer)" characterization in spec §15, which implicitly assumed
core.db was BY-SA-only aside from the computed layer. In practice core.db
also carries an LGPL-3.0 component now. This doesn't block anything (LGPL is
fully redistributable, satisfied by attribution + keeping the source
available, which it already is on GitHub), but it's exactly the kind of thing
spec §13's "counsel review pre-launch" line is there to catch, so flagging it
explicitly here rather than letting the original mischaracterization stand.

## Computed/derived layer

Fields not directly copied from a single source — computed phonetic
match_class/confidence (pinyin/MC/OC comparison), mapping_type classification
(systematic/merger/irregular), phonetic_series reliability rollups — are
original computation over the above sources and are released under
**CC BY-SA 4.0** (spec §5 "computed/derived layer under CC BY-SA 4.0"), since
that's the most restrictive share-alike license among the direct inputs it's
derived from other than GPL-2.0 (which is kept in its own separate `ids.db`
specifically so it doesn't force the rest of the pack into GPL).

## GPL / BY-SA layering in the shipped pack (spec §15)

Two attached SQLite files, matching how they'd be laid out in the published
dump:

- **`core.db`** — Unicode/Unihan (permissive), Wiktextract (CC BY-SA 4.0),
  makemeahanzi dictionary.txt (LGPL-3.0), OpenCC (Apache-2.0), and the
  computed layer (CC BY-SA 4.0).
- **`ids.db`** — cjkvi-ids decomposition rows only (GPL-2.0), kept separate so
  GPL doesn't propagate into the rest of the pack.

The app opens `core.db` and `ids.db` as two separate SQLite connections and
merges results in Kotlin (deliberately no ATTACH — see `app/README.md`); they
are never combined into a single file.
