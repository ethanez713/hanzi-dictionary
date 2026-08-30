"""Merge mechanical fields (decomposition, readings, S/T variants) with fixed
priority rules; extract interpretive fields as claims, never voted (spec §8.4).
"""
from __future__ import annotations

import re
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


# --- Wiktionary cross-check of makemeahanzi's editorial phonetic label (audit 2026-08) ---
#
# makemeahanzi keys its etymology to a character's *graphical* form, so on a simplified glyph it
# keeps the traditional phonetic label even when simplification *replaced* the phonetic with an
# unrelated sign (陸's 坴→击) or *merged* it into a different character (續's 𧶠→卖). The phonetic
# detector already refuses to confirm these (they land in the "opaque" branch of
# phonetic.compute_component_use), and this cross-check vetoes them against the traditional form's
# Wiktionary glyph-origin — see docs/audits/breakdown-audit-2026-08.md for the method and evidence.
#
# It is deliberately conservative: a simplified glyph and its Mandarin phonetic drift are NOT a
# usable error signal (說's 兌→兑 and 陸's 坴→击 look identical there), so the veto fires only with
# graphical proof from the traditional character — its Wiktionary phonetic sits in its own
# decomposition yet is gone from the simplified glyph, or Wiktionary says it has no phonetic at
# all (顯 is ideographic). A phonetic Wiktionary names only as an archaic ancestor that never
# appears graphically (没's 𠬛, 家's 𢑓) is left alone: no proof, and usually just notation.

_IDS_OPERATORS = frozenset(chr(c) for c in range(0x2FF0, 0x3000))
_WIKT_PHONETIC_RE = re.compile(r"phonetic\s+([㐀-鿿\U00020000-\U0002FFFF])")
# Formation-type verdicts we recognize in an English-Wiktionary etymology, most specific first.
_FORMATION_KEYWORDS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("phono-semantic", ("phono-semantic", "形聲", "形声")),
    ("ideographic", ("ideogrammic", "ideographic", "會意", "会意")),
    ("pictographic", ("pictogram", "pictographic", "象形")),
    ("indicative", ("ideogram indicating", "指事")),
)


# Component-level simplifications OpenCC's *character* table misses because the traditional
# component never stood alone as a character (爿, the phonetic of 狀/壯/妝, simplifies to 丬 only
# inside a glyph). Without these, a genuine phonetic that was merely simplified in place reads as
# "replaced" and gets wrongly vetoed. Keep this to combining phonetics OpenCC can't see.
_COMPONENT_SIMPLIFICATIONS: dict[str, str] = {"爿": "丬"}


def _to_simplified(ch: str, entities: dict[int, GlyphEntity]) -> str:
    if len(ch) != 1:
        return ch
    if ch in _COMPONENT_SIMPLIFICATIONS:
        return _COMPONENT_SIMPLIFICATIONS[ch]
    e = entities.get(ord(ch))
    if e and e.opencc_simplified_of:
        return chr(e.opencc_simplified_of[0])
    return ch


def _decomp_component_chars(ch: str, entities: dict[int, GlyphEntity]) -> set[str]:
    if len(ch) != 1:
        return set()
    e = entities.get(ord(ch))
    if not e:
        return set()
    return {
        c for d in e.decompositions for c in d.ids
        if c not in _IDS_OPERATORS and c not in "？?"
    }


def _phonetic_survives(p_wik: str, e: GlyphEntity, entities: dict[int, GlyphEntity]) -> bool:
    """Whether the traditional form's phonetic p_wik still sits in the simplified glyph e —
    as itself, its own simplified form, or (樣→样) a graphical core of it that survived the
    reduction. When it does, e's makemeahanzi phonetic is that same genuine (drifted) phonetic
    and must be kept; when it doesn't, simplification replaced or merged it away (續 𧶠→卖)."""
    parts = _decomp_component_chars(e.char, entities)
    parts |= {_to_simplified(c, entities) for c in parts}
    reps = {p_wik, _to_simplified(p_wik, entities)}
    reps |= _decomp_component_chars(p_wik, entities)
    reps |= {_to_simplified(r, entities) for r in set(reps)}
    return bool(reps & parts)


def _wiktextract_formation(e: GlyphEntity) -> tuple[str | None, str | None]:
    """(phonetic_component, formation) from a glyph's OWN wiktextract etymology, or
    (None, None) when the text carries no glyph-origin verdict we recognize — a bare
    'Simplified from X' note included, so the caller falls through to the traditional form."""
    text = etymology_text_claim(e)
    if not text:
        return None, None
    lowered = text.lower()
    formation = next((name for name, kws in _FORMATION_KEYWORDS
                      if any(kw.lower() in lowered for kw in kws)), None)
    if formation is None:
        return None, None
    phonetic = None
    if formation == "phono-semantic":
        m = _WIKT_PHONETIC_RE.search(text)
        phonetic = m.group(1) if m else None
    return phonetic, formation


def _traditional_origin(e: GlyphEntity, entities: dict[int, GlyphEntity]) -> GlyphEntity | None:
    """The traditional character this simplified glyph descends from, whose Wiktionary
    glyph-origin we judge makemeahanzi against. Skips the glyph itself and any 'traditional'
    that merely *contains* it (傢 = 亻+家, 製 = 制+衣) — those are derived characters, not the
    origin, and their phonetic is the glyph itself, which no cross-check can confirm."""
    for trad_cp in e.opencc_traditional_of:
        if trad_cp == e.codepoint:
            continue
        te = entities.get(trad_cp)
        if te is not None and e.char not in _decomp_component_chars(te.char, entities):
            if _wiktextract_formation(te)[1] is not None:
                return te
    return None


def phonetic_claim_contradicted(e: GlyphEntity, entities: dict[int, GlyphEntity]) -> bool:
    """True when makemeahanzi's editorial phonetic label for a *simplified* glyph is a
    simplification artifact that we can prove against the traditional form's Wiktionary
    glyph-origin, so it should not be shown or voiced as the phonetic:

    - the traditional form has no phonetic at all — it is ideographic/pictographic (顯 → 显), or
    - simplification removed the real phonetic: the traditional phonetic is a component of the
      traditional character (續's decomp carries 𧶠, 陸's carries 坴) yet no longer appears in the
      simplified glyph (𧶠→卖, 坴→击), or
    - the traditional etymology explicitly says makemeahanzi's component is a corruption of the
      real phonetic (細: 'phonetic 囟 … corrupted to 田').

    Deliberately conservative. When Wiktionary names only an archaic ancestor that never appears
    graphically (没's 𠬛 for the visible 殳, 家's 𢑓 for 豕), there is no proof of a replacement, so
    it abstains — those are notation differences on a genuine, merely-drifted phonetic, not errors."""
    claim = component_function_claim(e)
    p_mmh = claim and claim.get("phonetic_component")
    if not p_mmh:
        return False
    te = _traditional_origin(e, entities)
    if te is None:
        return False
    p_wik, formation = _wiktextract_formation(te)
    if formation != "phono-semantic":
        return True  # ideographic/pictographic: makemeahanzi's phonetic is spurious
    if not p_wik:
        return False
    if _phonetic_survives(p_wik, e, entities):
        return False  # the real phonetic carried through simplification (說 兌→兑) — keep
    trad_carries_phonetic = p_wik in _decomp_component_chars(te.char, entities)
    corruption_of_claim = "corrupt" in (etymology_text_claim(te) or "").lower() and \
        p_mmh in (etymology_text_claim(te) or "")
    return trad_carries_phonetic or corruption_of_claim
