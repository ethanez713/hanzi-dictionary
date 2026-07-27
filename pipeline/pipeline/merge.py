"""Merge mechanical fields (decomposition, readings, S/T variants) with fixed
priority rules; extract interpretive fields as claims, never voted (spec §8.4).
"""
from __future__ import annotations

from dataclasses import dataclass

from pipeline import ids_parser
from pipeline.normalize import GlyphEntity, RawDecomposition

# Well-known systematic simplification substitutions: (traditional_component -> simplified_component).
# Covers the common radical-level rules; anything not in this table falls back to the
# computable "irregular (unclassified)" residual per spec §8.5 — no curation required to ship.
# A curated cursive-vs-new-coinage split for the irregular set is explicitly deferred (spec §8.5).
SYSTEMATIC_SUBSTITUTIONS: dict[str, str] = {
    "言": "讠", "金": "钅", "食": "饣", "糸": "纟", "門": "门", "頁": "页",
    "貝": "贝", "車": "车", "馬": "马", "鳥": "鸟", "見": "见", "長": "长",
    "韋": "韦", "風": "风", "魚": "鱼", "電": "电", "氣": "气", "邊": "边",
    "廣": "广", "簡": "简", "會": "会", "產": "产", "壽": "寿", "興": "兴",
    "舉": "举", "麥": "麦", "無": "无", "與": "与", "義": "义", "為": "为",
}


@dataclass
class ResolvedDecomposition:
    ids: str
    source: str
    region: str | None


def is_navigable(ids: str) -> bool:
    """True when every first-level part of [ids] is a real, encoded character.

    An IDS can be structurally right and useless to a reader: cjkvi-ids describes 举 as
    ⿱兴⿻二丨, where the bottom is a *shape* (two horizontals crossed by a vertical) with no
    codepoint of its own. The app can only show that as "part not in the component index"
    and can't link to it, while makemeahanzi's ⿱兴扌 names the same part as 扌, which has a
    gloss, a reading and a page. Both are true; only one teaches.
    """
    try:
        parts = ids_parser.direct_components(ids)
    except ids_parser.IdsParseError:
        return False
    if len(parts) == 1 and parts[0] == ids:
        return False   # atomic — no breakdown at all
    # A part is usable when it's a single encoded character: not a nested IDS fragment, and
    # not makemeahanzi's ？ placeholder for a part it couldn't identify.
    return all(len(p) == 1 and p != "？" and p not in ids_parser.ALL_OPERATORS for p in parts)


def resolve_decomposition(e: GlyphEntity) -> ResolvedDecomposition | None:
    """Priority: cjkvi-ids > makemeahanzi; within cjkvi-ids, region-preferred > untagged
    > any other region (spec §8.4, §15). is_simp/is_trad inferred from variant edges —
    a glyph with a distinct traditional form prefers G-tagged IDS, and vice versa.

    Cutting across that order: a decomposition whose parts are all *encoded* characters
    beats a higher-priority one that bottoms out in unencoded shapes (see [is_navigable]).
    Source priority is about which analysis to trust; this is about whether the answer can
    be shown at all, which comes first.
    """
    is_simp_script = bool(e.traditional_variants or e.opencc_traditional_of)
    is_trad_script = bool(e.simplified_variants or e.opencc_simplified_of)
    preferred_region = "G" if is_simp_script and not is_trad_script else ("T" if is_trad_script and not is_simp_script else None)

    cjkvi = [d for d in e.decompositions if d.source == "cjkvi-ids"]
    mmh = [d for d in e.decompositions if d.source == "makemeahanzi"]

    def pick(cands: list[RawDecomposition], region: str | None) -> RawDecomposition | None:
        for d in cands:
            if d.region == region:
                return d
        return None

    ordered: list[RawDecomposition] = []
    if preferred_region:
        d = pick(cjkvi, preferred_region)
        if d:
            ordered.append(d)
    d = pick(cjkvi, None)
    if d:
        ordered.append(d)
    ordered += [d for d in cjkvi if d not in ordered]
    ordered += mmh

    for d in ordered:
        if is_navigable(d.ids):
            return ResolvedDecomposition(d.ids, d.source, d.region)
    if ordered:
        d = ordered[0]
        return ResolvedDecomposition(d.ids, d.source, d.region)
    return None


def direct_component_ids(resolved: ResolvedDecomposition) -> list[str]:
    try:
        components = ids_parser.direct_components(resolved.ids)
    except ids_parser.IdsParseError:
        return []
    if len(components) == 1 and components[0] == resolved.ids:
        return []  # atomic character, no sub-components
    return components


@dataclass
class VariantEdgeDraft:
    from_glyph: int   # traditional
    to_glyph: int      # simplified
    mapping_type: str | None = None


def resolve_variant_edges(entities: dict[int, GlyphEntity]) -> list[VariantEdgeDraft]:
    """Union Unihan kSimplifiedVariant/kTraditionalVariant with OpenCC ST/TS as a
    cross-check; classify mapping_type (merger detection first, then the systematic
    substitution table, else the computable 'irregular (unclassified)' residual)."""
    pairs: set[tuple[int, int]] = set()
    for cp, e in entities.items():
        # kSimplifiedVariant is attached to the *traditional* char, pointing at its simplified form(s)
        for simp_cp in e.simplified_variants:
            pairs.add((cp, simp_cp))
        # kTraditionalVariant is attached to the *simplified* char, pointing at its traditional form(s)
        for trad_cp in e.traditional_variants:
            pairs.add((trad_cp, cp))
        for trad_cp in e.opencc_traditional_of:  # e is simplified -> trad_cp is traditional
            pairs.add((trad_cp, cp))
        for simp_cp in e.opencc_simplified_of:  # e is traditional -> simp_cp is simplified
            pairs.add((cp, simp_cp))

    pairs = {(t, s) for t, s in pairs if t != s}

    merger_targets: dict[int, int] = {}
    for _, simp_cp in pairs:
        merger_targets[simp_cp] = merger_targets.get(simp_cp, 0) + 1

    resolved_cache: dict[int, ResolvedDecomposition | None] = {}

    def resolved_of(cp: int) -> ResolvedDecomposition | None:
        if cp not in resolved_cache:
            resolved_cache[cp] = resolve_decomposition(entities[cp]) if cp in entities else None
        return resolved_cache[cp]

    drafts = []
    for trad_cp, simp_cp in pairs:
        if merger_targets.get(simp_cp, 0) > 1:
            mapping_type = "merger"
        else:
            trad_res = resolved_of(trad_cp)
            simp_res = resolved_of(simp_cp)
            mapping_type = "irregular (unclassified)"
            if trad_res and simp_res:
                trad_components = set(direct_component_ids(trad_res))
                simp_components = set(direct_component_ids(simp_res))
                removed = trad_components - simp_components
                added = simp_components - trad_components
                if len(removed) == 1 and len(added) == 1:
                    (rem,) = removed
                    (add,) = added
                    if SYSTEMATIC_SUBSTITUTIONS.get(rem) == add:
                        mapping_type = "systematic"
                if chr(trad_cp) in SYSTEMATIC_SUBSTITUTIONS and SYSTEMATIC_SUBSTITUTIONS[chr(trad_cp)] == chr(simp_cp):
                    mapping_type = "systematic"
        drafts.append(VariantEdgeDraft(trad_cp, simp_cp, mapping_type))

    return drafts


def etymology_text_claim(e: GlyphEntity) -> str | None:
    """Wiktextract's free-text etymology (interpretive, stored as-is per §8.4)."""
    for entry in e.wiktextract_entries:
        text = entry.get("etymology_text")
        if text:
            return text
    return None


def component_function_claim(e: GlyphEntity) -> dict | None:
    """Interpretive claim from makemeahanzi's editorial etymology — stored as-is,
    never voted on; the phonetic detector computes its own independent function
    label mechanically (phonetic.py), and the two are displayed side by side."""
    if not e.makemeahanzi or "etymology" not in e.makemeahanzi:
        return None
    ety = e.makemeahanzi["etymology"]
    return {
        "formation_type": ety.get("type"),
        "phonetic_component": ety.get("phonetic"),
        "semantic_component": ety.get("semantic"),
        "hint": ety.get("hint"),
    }
