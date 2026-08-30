"""Wiktionary cross-check that vetoes makemeahanzi's false phonetic labels on simplified
glyphs (audit 2026-08). Fast, snapshot-free: hand-built entities mirror the real data shapes
verified in docs/audits/breakdown-audit-2026-08.md.

Run: pipeline/.venv/bin/python -m unittest discover -s pipeline/tests   (or plain python3 -m).
"""
import unittest

from pipeline import merge, phonetic
from pipeline.merge import ResolvedDecomposition
from pipeline.normalize import GlyphEntity, RawDecomposition


def glyph(char, *, mmh_phonetic=None, mmh_semantic=None, etymology_text=None,
          traditional_of=None, simplified_of=None, decomposition=None):
    """Build a minimal GlyphEntity. traditional_of/simplified_of take a single char."""
    e = GlyphEntity(codepoint=ord(char), char=char)
    if mmh_phonetic or mmh_semantic:
        e.makemeahanzi = {"etymology": {"type": "pictophonetic",
                                        "phonetic": mmh_phonetic, "semantic": mmh_semantic}}
    if etymology_text:
        e.wiktextract_entries = [{"etymology_text": etymology_text}]
    if traditional_of:
        e.opencc_traditional_of = [ord(traditional_of)]
    if simplified_of:
        e.opencc_simplified_of = [ord(simplified_of)]
    if decomposition:
        e.decompositions = [RawDecomposition(decomposition, None, "makemeahanzi")]
    return e


def world(*entities):
    return {e.codepoint: e for e in entities}


class ContradictionTest(unittest.TestCase):
    def test_merger_artifact_is_contradicted(self):
        # 续: makemeahanzi says phonetic 卖, but 續 carries phonetic 𧶠 that simplification merged away.
        ents = world(
            glyph("续", mmh_phonetic="卖", mmh_semantic="纟", decomposition="⿰纟卖",
                  etymology_text="Simplified from 續 (糹 → 纟 and 賣 → 卖).", traditional_of="續"),
            glyph("續", decomposition="⿰糹𧶠",
                  etymology_text="Phono-semantic compound (形聲): semantic 糸 + phonetic 𧶠 (OC *luɡ)."),
        )
        self.assertTrue(merge.phonetic_claim_contradicted(ents[ord("续")], ents))

    def test_replacement_artifact_is_contradicted(self):
        # 陆: 陸 carries phonetic 坴; simplification replaced it with the sign 击.
        ents = world(
            glyph("陆", mmh_phonetic="击", mmh_semantic="阝", decomposition="⿰阝击", traditional_of="陸"),
            glyph("陸", decomposition="⿰阝坴",
                  etymology_text="Phono-semantic compound (形聲): semantic 阝 + phonetic 坴 (OC *m·ruɡ)."),
        )
        self.assertTrue(merge.phonetic_claim_contradicted(ents[ord("陆")], ents))

    def test_clerical_corruption_is_contradicted(self):
        # 细: makemeahanzi says phonetic 田, but the real phonetic is 囟 (corrupted to 田 post-Han).
        ents = world(
            glyph("细", mmh_phonetic="田", mmh_semantic="纟", decomposition="⿰纟田",
                  etymology_text="Simplified from 細 (糹 → 纟).", traditional_of="細"),
            glyph("細", decomposition="⿰糹田",
                  etymology_text=("Phono-semantic compound (形聲): semantic 糸 + phonetic 囟. The right "
                                  "component is unrelated to 田 in which it was corrupted to after the Han.")),
        )
        self.assertTrue(merge.phonetic_claim_contradicted(ents[ord("细")], ents))

    def test_archaic_ancestor_notation_is_kept(self):
        # 没 / 沒: Wiktionary names phonetic 𠬛 (an archaic ancestor never in the decomposition);
        # the visible 殳 is the genuine drifted phonetic — no proof of replacement, so keep.
        ents = world(
            glyph("没", mmh_phonetic="殳", mmh_semantic="氵", decomposition="⿰氵殳", traditional_of="沒"),
            glyph("沒", decomposition="⿰氵殳",
                  etymology_text="Phono-semantic compound (形聲): semantic 水 + phonetic 𠬛."),
        )
        self.assertFalse(merge.phonetic_claim_contradicted(ents[ord("没")], ents))

    def test_in_place_simplified_phonetic_is_kept(self):
        # 状 / 狀: the phonetic 爿 was simplified in place to 丬 (a mapping OpenCC's char table
        # lacks) — it is the same genuine phonetic and must be kept, not read as a replacement.
        ents = world(
            glyph("状", mmh_phonetic="丬", mmh_semantic="犬", decomposition="⿰丬犬", traditional_of="狀"),
            glyph("狀", decomposition="⿰爿犬",
                  etymology_text="Phono-semantic compound (形聲): phonetic 爿 + semantic 犬."),
        )
        self.assertFalse(merge.phonetic_claim_contradicted(ents[ord("状")], ents))

    def test_derived_variant_traditional_is_ignored(self):
        # 制's opencc 'traditional' 製 = 制 + 衣 merely contains 制; it is not 制's origin, so its
        # (ideographic) verdict must not veto 制's own phonetic.
        ents = world(
            glyph("制", mmh_phonetic="巾", mmh_semantic="刂", decomposition="⿰?刂", traditional_of="製"),
            glyph("製", decomposition="⿱制衣", etymology_text="Ideogrammic compound (會意): 制 + 衣."),
        )
        self.assertFalse(merge.phonetic_claim_contradicted(ents[ord("制")], ents))

    def test_ideographic_character_has_no_phonetic(self):
        # 显 / 顯 is ideogrammic — makemeahanzi's phonetic 业 is spurious.
        ents = world(
            glyph("显", mmh_phonetic="业", mmh_semantic="日", traditional_of="顯"),
            glyph("顯", etymology_text="Ideogrammic compound (會意): 日 + 絲 + 頁."),
        )
        self.assertTrue(merge.phonetic_claim_contradicted(ents[ord("显")], ents))

    def test_drifted_but_genuine_phonetic_is_kept(self):
        # 说 / 說: phonetic 兌→兑 agrees after T→S normalization; must NOT be vetoed.
        ents = world(
            glyph("说", mmh_phonetic="兑", mmh_semantic="讠", decomposition="⿰讠兑",
                  etymology_text="Simplified from 説 (訁 → 讠).", traditional_of="說"),
            glyph("說", etymology_text="Phono-semantic compound (形聲): semantic 言 + phonetic 兌."),
            glyph("兌", simplified_of="兑"),
        )
        self.assertFalse(merge.phonetic_claim_contradicted(ents[ord("说")], ents))

    def test_reduced_phonetic_core_is_kept_by_containment(self):
        # 样 / 樣: simplification reduced phonetic 羕 to its core 羊 (a component of 羕) — kept.
        ents = world(
            glyph("样", mmh_phonetic="羊", mmh_semantic="木", decomposition="⿰木羊", traditional_of="樣"),
            glyph("樣", etymology_text="Phono-semantic compound (形聲): semantic 木 + phonetic 羕."),
            glyph("羕", decomposition="⿱羊永"),
        )
        self.assertFalse(merge.phonetic_claim_contradicted(ents[ord("样")], ents))

    def test_abstains_without_wiktionary_verdict(self):
        # makemeahanzi asserts a phonetic but Wiktionary offers no glyph-origin verdict: keep as-is.
        ents = world(glyph("说", mmh_phonetic="兑", mmh_semantic="讠"))
        self.assertFalse(merge.phonetic_claim_contradicted(ents[ord("说")], ents))

    def test_no_phonetic_claim_is_not_contradicted(self):
        ents = world(glyph("时", mmh_semantic="日", etymology_text="Ideogrammic compound (會意)."))
        self.assertFalse(merge.phonetic_claim_contradicted(ents[ord("时")], ents))


class DemotionTest(unittest.TestCase):
    def test_contradicted_phonetic_becomes_unknown_not_phonetic(self):
        # End-to-end: a vetoed, phonetically-opaque claim must render as an unlabeled sign.
        ents = world(glyph("续"))  # parent readings absent -> detector finds no match
        resolved = ResolvedDecomposition(ids="⿰纟卖", source="makemeahanzi", region=None)
        rows = phonetic.compute_component_use(
            ord("续"), ents, resolved,
            semantic_claim_component="纟", phonetic_claim_component="卖",
            phonetic_claim_contradicted=True,
        )
        mai = next(r for r in rows if r.component_id == "卖")
        self.assertEqual(mai.function, "unknown")
        self.assertNotIn(mai.resolution, ("single_source", "agreement"))

    def test_uncontradicted_opaque_phonetic_stays_source_asserted(self):
        ents = world(glyph("说"))
        resolved = ResolvedDecomposition(ids="⿰讠兑", source="makemeahanzi", region=None)
        rows = phonetic.compute_component_use(
            ord("说"), ents, resolved,
            semantic_claim_component="讠", phonetic_claim_component="兑",
            phonetic_claim_contradicted=False,
        )
        dui = next(r for r in rows if r.component_id == "兑")
        self.assertEqual(dui.function, "phonetic")
        self.assertEqual(dui.resolution, "single_source")


if __name__ == "__main__":
    unittest.main()
