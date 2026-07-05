"""Phonetic detector (pinyin stratum) + series reliability rollup, spec §8.5.

Compares each direct component's own pinyin reading(s) against the parent
character's, independent of makemeahanzi's editorial semantic/phonetic labels —
the two are computed and displayed side by side (merge.component_function_claim
carries the editorial version; this module carries the computed one).

MC/OC strata are deferred: those need dedicated Baxter/Baxter-Sagart notation
parsers, called out in spec §15 as separate remaining engineering work. So for
every row produced here, best_stratum is always "pinyin".
"""
from __future__ import annotations

from dataclasses import dataclass

from pipeline import pinyin
from pipeline.merge import ResolvedDecomposition, direct_component_ids
from pipeline.normalize import GlyphEntity


@dataclass
class ComponentUseRow:
    glyph_id: int
    component_id: str  # codepoint (int-as-str via caller) or "ids:<subtree>"
    position: int
    function: str        # semantic|phonetic|both|empty|unknown
    match_class: str | None
    confidence: float | None
    resolution: str       # computed|single_source


def _pinyin_readings(e: GlyphEntity | None) -> list[str]:
    if e is None:
        return []
    return [r.value for r in e.readings if r.system == "pinyin"]


CONFIDENCE_BY_MATCH = {"exact": 0.95, "tone": 0.8, "initial": 0.55, "rime": 0.55}


def compute_component_use(
    glyph_cp: int,
    entities: dict[int, GlyphEntity],
    resolved: ResolvedDecomposition,
    semantic_claim_component: str | None,
) -> list[ComponentUseRow]:
    parent_readings = _pinyin_readings(entities.get(glyph_cp))
    component_refs = direct_component_ids(resolved)

    rows: list[ComponentUseRow] = []
    for position, comp_ref in enumerate(component_refs):
        if comp_ref.startswith("ids:"):
            # unencoded compound sub-component: no reading of its own to compare.
            is_semantic_claim = semantic_claim_component == comp_ref
            rows.append(ComponentUseRow(
                glyph_id=glyph_cp, component_id=comp_ref, position=position,
                function="semantic" if is_semantic_claim else "unknown",
                match_class=None, confidence=0.5 if is_semantic_claim else None,
                resolution="single_source" if is_semantic_claim else "computed",
            ))
            continue

        comp_cp = ord(comp_ref)
        comp_readings = _pinyin_readings(entities.get(comp_cp))
        match_class = pinyin.best_match(parent_readings, comp_readings) if comp_readings else None
        is_semantic_claim = semantic_claim_component == comp_ref

        if match_class:
            function = "both" if is_semantic_claim else "phonetic"
            confidence = CONFIDENCE_BY_MATCH[match_class]
            resolution = "computed"
        elif is_semantic_claim:
            function, match_class, confidence, resolution = "semantic", None, 0.5, "single_source"
        else:
            function, confidence, resolution = "unknown", None, "computed"

        rows.append(ComponentUseRow(
            glyph_id=glyph_cp, component_id=comp_ref, position=position,
            function=function, match_class=match_class, confidence=confidence, resolution=resolution,
        ))
    return rows


@dataclass
class PhoneticSeriesRow:
    component_id: str
    member_count: int
    reliability: float
    best_stratum: str = "pinyin"


def compute_phonetic_series(all_component_use_rows: list[ComponentUseRow], freq_rank: dict[int, int]) -> list[PhoneticSeriesRow]:
    """Frequency-weighted rollup of member match_classes for components acting as
    phonetics in >=2 characters (a 'series' of one member isn't a series)."""
    by_component: dict[str, list[ComponentUseRow]] = {}
    for row in all_component_use_rows:
        if row.function in ("phonetic", "both") and row.match_class:
            by_component.setdefault(row.component_id, []).append(row)

    out = []
    for component_id, members in by_component.items():
        if len(members) < 2:
            continue
        total_weight = 0.0
        weighted_sum = 0.0
        for m in members:
            glyph_cp = m.glyph_id
            weight = 1.0 / max(1, freq_rank.get(glyph_cp, 10_000))
            total_weight += weight
            weighted_sum += weight * (m.confidence or 0.0)
        reliability = weighted_sum / total_weight if total_weight else 0.0
        out.append(PhoneticSeriesRow(component_id=component_id, member_count=len(members), reliability=reliability))
    return out
