"""Phonetic detector (pinyin -> MC -> OC strata) + series reliability rollup, spec §8.5.

Compares each direct component's own reading(s) against the parent character's,
independent of makemeahanzi's editorial semantic/phonetic labels — the two are
computed and displayed side by side (merge.component_function_claim carries the
editorial version; this module carries the computed one).

Strata are tried modern-to-ancient (pinyin, then Middle Chinese, then Old
Chinese) and the search stops at the first one that matches — that's the
"last stratum where the link held" per spec §8.5, i.e. the drift explanation:
a character whose phonetic connection only shows up in Old Chinese but not
pinyin has clearly drifted apart since; one that still matches in pinyin
hasn't. Per the schema's own match_class vocabulary (exact|tone|initial|
rime|oc_only|opaque), a pinyin or MC match gets a real tier; an OC-only match
is just flagged "oc_only" rather than sub-classified, since at that stratum
the precise tier matters less than the "this is a historical/drifted
connection" signal itself. Confidence is scaled down per stratum — a modern
pinyin match is more legible/certain to a reader than a reconstruction-backed
one.
"""
from __future__ import annotations

from dataclasses import dataclass

from pipeline import mc_notation, oc_notation, pinyin
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
    stratum: str | None = None  # pinyin|mc|oc — internal bookkeeping for the series rollup


def _readings(e: GlyphEntity | None, system: str) -> list[str]:
    if e is None:
        return []
    return [r.value for r in e.readings if r.system == system]


PINYIN_CONFIDENCE = {"exact": 0.95, "tone": 0.8, "initial": 0.55, "rime": 0.55}
MC_CONFIDENCE = {"exact": 0.75, "tone": 0.65, "initial": 0.45, "rime": 0.45}
OC_CONFIDENCE = 0.35


def _detect_phonetic_match(parent: GlyphEntity | None, component_cp: int, entities: dict[int, GlyphEntity]) -> tuple[str, float, str] | None:
    """Returns (match_class, confidence, stratum) for the first stratum (modern to
    ancient) where the component's reading matches the parent's, or None."""
    comp = entities.get(component_cp)

    tier = pinyin.best_match(_readings(parent, "pinyin"), _readings(comp, "pinyin"))
    if tier:
        return tier, PINYIN_CONFIDENCE[tier], "pinyin"

    tier = mc_notation.best_match(_readings(parent, "mc_baxter"), _readings(comp, "mc_baxter"))
    if tier:
        return tier, MC_CONFIDENCE[tier], "mc"

    tier = oc_notation.best_match(_readings(parent, "oc_zz"), _readings(comp, "oc_zz"))
    if tier:
        return "oc_only", OC_CONFIDENCE, "oc"

    return None


def compute_component_use(
    glyph_cp: int,
    entities: dict[int, GlyphEntity],
    resolved: ResolvedDecomposition,
    semantic_claim_component: str | None,
    phonetic_claim_component: str | None = None,
    phonetic_claim_contradicted: bool = False,
) -> list[ComponentUseRow]:
    parent = entities.get(glyph_cp)
    component_refs = direct_component_ids(resolved)

    rows: list[ComponentUseRow] = []
    for position, comp_ref in enumerate(component_refs):
        is_semantic_claim = semantic_claim_component == comp_ref
        is_phonetic_claim = phonetic_claim_component == comp_ref

        if comp_ref.startswith("ids:"):
            # unencoded compound sub-component: no reading of its own to compare.
            rows.append(ComponentUseRow(
                glyph_id=glyph_cp, component_id=comp_ref, position=position,
                function="semantic" if is_semantic_claim else "unknown",
                match_class=None, confidence=0.5 if is_semantic_claim else None,
                resolution="single_source" if is_semantic_claim else "computed",
            ))
            continue

        comp_cp = ord(comp_ref)
        match = _detect_phonetic_match(parent, comp_cp, entities)

        if match:
            match_class, confidence, stratum = match
            function = "both" if is_semantic_claim else "phonetic"
            # the reading comparison and the source's etymology agreeing is stronger
            # evidence than either alone (spec §7 resolution enum)
            resolution = "agreement" if is_phonetic_claim else "computed"
        elif is_phonetic_claim and not phonetic_claim_contradicted:
            # The source asserts a phonetic role but the sound link is inaudible at
            # every stratum we can compute (说/兑, 家/豕 …): keep the assertion, mark
            # it "opaque" so the UI can be honest about the drift, and weight it low
            # so it drags its series' reliability down rather than up.
            function = "both" if is_semantic_claim else "phonetic"
            match_class, confidence, resolution, stratum = "opaque", 0.3, "single_source", None
        elif is_semantic_claim:
            function, match_class, confidence, resolution, stratum = "semantic", None, 0.5, "single_source", None
        else:
            # Also lands a phonetic claim Wiktionary contradicts (续's 卖, 细's 田): no audible
            # match and the source is overruled, so the part is an unlabeled sign, not a phonetic.
            function, match_class, confidence, resolution, stratum = "unknown", None, None, "computed", None

        rows.append(ComponentUseRow(
            glyph_id=glyph_cp, component_id=comp_ref, position=position,
            function=function, match_class=match_class, confidence=confidence,
            resolution=resolution, stratum=stratum,
        ))
    return rows


@dataclass
class PhoneticSeriesRow:
    component_id: str
    member_count: int
    reliability: float
    best_stratum: str


_STRATUM_RANK = {"pinyin": 3, "mc": 2, "oc": 1}


def compute_phonetic_series(all_component_use_rows: list[ComponentUseRow], freq_rank: dict[int, int]) -> list[PhoneticSeriesRow]:
    """Frequency-weighted rollup of member match_classes for components acting as
    phonetics in >=2 characters (a 'series' of one member isn't a series).
    best_stratum is the most modern stratum any member matched at."""
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
        best_stratum = "oc"
        for m in members:
            weight = 1.0 / max(1, freq_rank.get(m.glyph_id, 10_000))
            total_weight += weight
            weighted_sum += weight * (m.confidence or 0.0)
            if m.stratum and _STRATUM_RANK[m.stratum] > _STRATUM_RANK[best_stratum]:
                best_stratum = m.stratum
        reliability = weighted_sum / total_weight if total_weight else 0.0
        out.append(PhoneticSeriesRow(
            component_id=component_id, member_count=len(members),
            reliability=reliability, best_stratum=best_stratum,
        ))
    return out
