# Attributions

This dataset and app pack are built from open data sources, each retaining its
own upstream license. This file is the source of truth for both the published
dataset repo's NOTICE and the in-app attributions screen (spec §9's IA item 9,
§15). Full license texts are bundled in [`/LICENSES`](../LICENSES).

## Sources in the current build

| Source | Provides here | License | Full text |
|---|---|---|---|
| [Unihan](http://unicode.org/charts/unihan.html) (Unicode Consortium) | Readings (Mandarin, Cantonese, Japanese), S/T variant links, radical/stroke counts, kHanyuPinlu frequency | Unicode License v3 (permissive) | [Unicode-License.txt](../LICENSES/Unicode-License.txt) |
| [Wiktionary via Wiktextract](https://github.com/tatuylonen/wiktextract) (kaikki.org) | Etymology text, Middle Chinese (Baxter) and Old Chinese (Zhengzhang, Baxter-Sagart) reconstructed readings | CC BY-SA 4.0 | [CC-BY-SA-4.0.txt](../LICENSES/CC-BY-SA-4.0.txt) |
| [makemeahanzi](https://github.com/skishore/makemeahanzi) `dictionary.txt` (derived from Unihan + [CJKlib](https://github.com/cburgmer/cjklib)) | Character decomposition (fallback when cjkvi-ids has none), semantic/phonetic component claims, formation-type claims | **LGPL-3.0-or-later** | [LGPL-3.0.txt](../LICENSES/LGPL-3.0.txt) |
| [cjkvi-ids](https://github.com/cjkvi/cjkvi-ids) (CHISE) | Region-tagged IDS decomposition (primary source; shipped separately as `ids.db`, see below) | GPL-2.0 | [GPL-2.0.txt](../LICENSES/GPL-2.0.txt) |
| [OpenCC](https://github.com/BYVoid/OpenCC) `STCharacters.txt`/`TSCharacters.txt` | Simplified/Traditional character conversion tables, cross-checked against Unihan for variant-edge classification | Apache-2.0 | [Apache-2.0.txt](../LICENSES/Apache-2.0.txt) |
| [complete-hsk-vocabulary](https://github.com/drkameleon/complete-hsk-vocabulary) | HSK 3.0 level assignments (glyph.hsk, word.hsk, hsk-levels.json import picker) | MIT | (short license, see upstream repo) |
| [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict) (MDBG export) | Word-level entries: `word`/`word_char` tables — simplified/traditional forms, pinyin, English definitions ("In words" on character pages) | CC BY-SA 4.0 | [CC-BY-SA-4.0.txt](../LICENSES/CC-BY-SA-4.0.txt) |

## Bundled app assets (not part of the dataset)

| Asset | Provides | License | Full text |
|---|---|---|---|
| [Noto Sans CJK SC](https://github.com/notofonts/noto-cjk) (`res/font/noto_sans_cjk_sc.otf`) | UI typeface — renders CJK Extension chars and IDS operators the system font shows as tofu | SIL OFL 1.1 | [OFL-1.1.txt](../LICENSES/OFL-1.1.txt) |

**Not currently included:** makemeahanzi's `graphics.txt`/stroke-order SVGs
(Arphic Public License) — deferred until stroke rendering is actually built
(spec §9/M3); Dong Chinese lexicon data (`peterolson/chinese-lexicon`) — used
only for private validation (spec §12), never ingested into any published or
shipped artifact; see `pipeline/fetchers/fetch_dong_chinese.py`.

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
