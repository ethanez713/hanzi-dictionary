"""Entity resolution: turn the 5 sources' raw parses into one record per codepoint.

Mechanical union only — no priority/conflict resolution here (that's merge.py) and
no interpretive judgment (that's claim data, passed through as-is for merge.py to
store, never voted on).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from pipeline import parsers


@dataclass
class RawReading:
    system: str
    value: str
    freq: int | None
    source: str


@dataclass
class RawDecomposition:
    ids: str
    region: str | None
    source: str


@dataclass
class GlyphEntity:
    codepoint: int
    char: str = ""
    readings: list[RawReading] = field(default_factory=list)
    decompositions: list[RawDecomposition] = field(default_factory=list)
    total_strokes: int | None = None
    simplified_variants: list[int] = field(default_factory=list)   # this char -> simpler form(s)
    traditional_variants: list[int] = field(default_factory=list)  # this char -> traditional form(s)
    opencc_simplified_of: list[int] = field(default_factory=list)  # this char (traditional) -> simplified form(s)
    opencc_traditional_of: list[int] = field(default_factory=list)  # this char (simplified) -> traditional form(s)
    makemeahanzi: dict | None = None
    wiktextract_entries: list[dict] = field(default_factory=list)


_JP_ON_SYSTEM = "jp_on"
_JP_KUN_SYSTEM = "jp_kun"


def build_entities(snapshot_paths: dict[str, Path]) -> dict[int, GlyphEntity]:
    """snapshot_paths maps source name -> its snapshot directory (latest fetched version)."""
    unihan_dir = snapshot_paths["unihan"]
    readings_raw = parsers.parse_unihan_file(unihan_dir / "Unihan_Readings.txt")
    variants_raw = parsers.parse_unihan_file(unihan_dir / "Unihan_Variants.txt")
    irg_raw = parsers.parse_unihan_file(unihan_dir / "Unihan_IRGSources.txt")
    pinlu = parsers.parse_hanyu_pinlu(readings_raw)

    makemeahanzi = parsers.parse_makemeahanzi(snapshot_paths["makemeahanzi"] / "dictionary.txt")
    cjkvi_ids = parsers.parse_cjkvi_ids(snapshot_paths["cjkvi-ids"] / "ids.txt")
    opencc_st = parsers.parse_opencc_char_table(snapshot_paths["opencc"] / "STCharacters.txt")
    opencc_ts = parsers.parse_opencc_char_table(snapshot_paths["opencc"] / "TSCharacters.txt")
    wiktextract = parsers.parse_wiktextract_single_char(snapshot_paths["wiktextract"] / "zh-single-char.jsonl")

    all_codepoints = (
        set(readings_raw) | set(variants_raw) | set(irg_raw) | set(makemeahanzi)
        | set(cjkvi_ids) | set(opencc_st) | set(opencc_ts) | set(wiktextract)
    )

    entities: dict[int, GlyphEntity] = {}
    for cp in all_codepoints:
        e = GlyphEntity(codepoint=cp, char=chr(cp))

        fields = readings_raw.get(cp, {})
        for value in fields.get("kMandarin", []):
            e.readings.append(RawReading("pinyin", value, None, "unihan"))
        for value in fields.get("kCantonese", []):
            e.readings.append(RawReading("jyutping", value, None, "unihan"))
        for value in fields.get("kJapaneseOn", []):
            for v in value.split(" "):
                e.readings.append(RawReading(_JP_ON_SYSTEM, v, None, "unihan"))
        for value in fields.get("kJapaneseKun", []):
            for v in value.split(" "):
                e.readings.append(RawReading(_JP_KUN_SYSTEM, v, None, "unihan"))
        for pinyin, freq in pinlu.get(cp, []):
            e.readings.append(RawReading("pinyin", pinyin, freq, "unihan"))

        variant_fields = variants_raw.get(cp, {})
        for value in variant_fields.get("kSimplifiedVariant", []):
            for v in value.split(" "):
                e.simplified_variants.append(parsers.cp_to_int(v))
        for value in variant_fields.get("kTraditionalVariant", []):
            for v in value.split(" "):
                e.traditional_variants.append(parsers.cp_to_int(v))

        irg_fields = irg_raw.get(cp, {})
        if irg_fields.get("kTotalStrokes"):
            # may list multiple IRG-source-specific counts; take the first (unified) one
            try:
                e.total_strokes = int(irg_fields["kTotalStrokes"][0].split(" ")[0])
            except ValueError:
                pass

        e.makemeahanzi = makemeahanzi.get(cp)
        if e.makemeahanzi and e.makemeahanzi.get("decomposition"):
            ids = e.makemeahanzi["decomposition"]
            if not parsers.is_placeholder_ids(ids):
                e.decompositions.append(RawDecomposition(ids, None, "makemeahanzi"))

        for region, ids in cjkvi_ids.get(cp, []):
            if not parsers.is_placeholder_ids(ids):
                e.decompositions.append(RawDecomposition(ids, region, "cjkvi-ids"))

        e.opencc_simplified_of = opencc_ts.get(cp, [])
        e.opencc_traditional_of = opencc_st.get(cp, [])

        e.wiktextract_entries = wiktextract.get(cp, [])
        for entry in e.wiktextract_entries:
            for sound in entry.get("sounds", []):
                tags = set(sound.get("tags", []))
                value = sound.get("zh_pron")
                if not value:
                    continue
                if tags == {"Middle-Chinese"}:
                    e.readings.append(RawReading("mc_baxter", value, None, "wiktextract"))
                elif tags == {"Old-Chinese", "Zhengzhang"}:
                    e.readings.append(RawReading("oc_zz", value, None, "wiktextract"))
                elif tags == {"Old-Chinese", "Baxter-Sagart"}:
                    # stored for future display only; not used by the phonetic detector
                    # (see oc_notation.py docstring for why Zhengzhang drives comparison)
                    e.readings.append(RawReading("oc_bs", value, None, "wiktextract"))

        entities[cp] = e

    return entities
