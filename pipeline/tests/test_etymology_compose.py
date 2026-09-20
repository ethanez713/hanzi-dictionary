"""Join a traditional form's glyph-origin onto a simplified-from mapping note.

Fast, snapshot-free. The phonetic veto still reads etymology_text_claim (raw);
these tests pin the pack-facing join in composed_etymology_text.

Run: pipeline/.venv/bin/python -m unittest discover -s pipeline/tests
"""
import unittest

from pipeline import merge
from pipeline.normalize import GlyphEntity


def glyph(char, *, etymology_text=None, traditional_of=None, traditional_variants=None):
    e = GlyphEntity(codepoint=ord(char), char=char)
    if etymology_text:
        e.wiktextract_entries = [{"etymology_text": etymology_text}]
    if traditional_of:
        e.opencc_traditional_of = [ord(traditional_of)]
    if traditional_variants:
        e.traditional_variants = [ord(c) for c in traditional_variants]
    return e


def world(*entities):
    return {e.codepoint: e for e in entities}


class ComposedEtymologyTest(unittest.TestCase):
    def test_appends_named_traditional_etymology(self):
        ents = world(
            glyph("续", etymology_text="Simplified from 續 (糹 → 纟 and 賣 → 卖).",
                  traditional_of="續"),
            glyph("續", etymology_text="Phono-semantic compound (形聲): semantic 糸 + phonetic 𧶠."),
        )
        out = merge.composed_etymology_text(ents[ord("续")], ents)
        self.assertEqual(
            out,
            "Simplified from 續 (糹 → 纟 and 賣 → 卖).\n"
            "Phono-semantic compound (形聲): semantic 糸 + phonetic 𧶠.",
        )

    def test_adds_missing_period_so_the_mapping_stays_the_first_sentence(self):
        ents = world(
            glyph("时", etymology_text="Simplified from 時 (寺 → 寸)", traditional_of="時"),
            glyph("時", etymology_text="Phono-semantic compound (形聲): semantic 日 + phonetic 寺."),
        )
        out = merge.composed_etymology_text(ents[ord("时")], ents)
        self.assertTrue(out.startswith("Simplified from 時 (寺 → 寸).\n"))
        self.assertIn("phonetic 寺", out)

    def test_named_form_without_etymology_falls_back_to_opencc_traditional(self):
        # 说 names 説 (no etymology); OpenCC's traditional 說 has the origin.
        ents = world(
            glyph("说", etymology_text="Simplified from 説 (訁 → 讠).", traditional_of="說"),
            glyph("説"),
            glyph("說", etymology_text="Phono-semantic compound (形聲): semantic 言 + phonetic 兌."),
        )
        out = merge.composed_etymology_text(ents[ord("说")], ents)
        self.assertIn("phonetic 兌", out)
        self.assertTrue(out.startswith("Simplified from 説 (訁 → 讠)."))

    def test_named_form_wins_over_a_different_traditional(self):
        ents = world(
            glyph("说", etymology_text="Simplified from 説 (訁 → 讠).", traditional_of="說"),
            glyph("説", etymology_text="FROM SETSU: semantic 言 + phonetic 兌."),
            glyph("說", etymology_text="FROM SHUO: should not be chosen."),
        )
        out = merge.composed_etymology_text(ents[ord("说")], ents)
        self.assertIn("FROM SETSU", out)
        self.assertNotIn("FROM SHUO", out)

    def test_ids_named_form_falls_back_to_traditional(self):
        ents = world(
            glyph("罢", etymology_text="Simplified from ⿱罒䏻 (elimination of 䏍).",
                  traditional_of="罷"),
            glyph("罷", etymology_text="Ideogrammic compound (會意): 网 + 能."),
        )
        out = merge.composed_etymology_text(ents[ord("罢")], ents)
        self.assertIn("网 + 能", out)

    def test_skips_a_traditional_that_is_itself_only_a_mapping_note(self):
        ents = world(
            glyph("伪", etymology_text="Simplified from 偽.",
                  traditional_of="偽", traditional_variants="僞"),
            glyph("偽", etymology_text="Simplified from 僞."),
            glyph("僞", etymology_text="Phono-semantic compound (形聲): semantic 亻 + phonetic 為."),
        )
        out = merge.composed_etymology_text(ents[ord("伪")], ents)
        self.assertIn("phonetic 為", out)
        self.assertNotIn("Simplified from 僞.\nSimplified from 僞.", out)

    def test_does_not_duplicate_when_traditional_text_is_already_present(self):
        trad = "Phono-semantic compound (形聲): semantic 糸 + phonetic 𧶠."
        own = f"Simplified from 續 (糹 → 纟 and 賣 → 卖).\n{trad}"
        ents = world(
            glyph("续", etymology_text=own, traditional_of="續"),
            glyph("續", etymology_text=trad),
        )
        self.assertEqual(merge.composed_etymology_text(ents[ord("续")], ents), own)

    def test_leaves_entries_that_do_not_start_with_simplified_from(self):
        own = ("Pictogram (象形), a man’s face.\n"
               "Etymology 2: Simplified from 麵 (elimination of 麥).")
        ents = world(
            glyph("面", etymology_text=own, traditional_of="麵"),
            glyph("麵", etymology_text="Phono-semantic compound (形聲): semantic 麥 + phonetic 面."),
        )
        self.assertEqual(merge.composed_etymology_text(ents[ord("面")], ents), own)

    def test_unchanged_when_traditional_has_no_etymology(self):
        own = "Simplified from 説 (訁 → 讠)."
        ents = world(glyph("说", etymology_text=own, traditional_of="説"), glyph("説"))
        self.assertEqual(merge.composed_etymology_text(ents[ord("说")], ents), own)

    def test_unchanged_when_there_is_no_etymology(self):
        ents = world(glyph("说"))
        self.assertIsNone(merge.composed_etymology_text(ents[ord("说")], ents))

    def test_raw_claim_is_not_composed(self):
        ents = world(
            glyph("续", etymology_text="Simplified from 續 (糹 → 纟 and 賣 → 卖).",
                  traditional_of="續"),
            glyph("續", etymology_text="Phono-semantic compound (形聲): semantic 糸 + phonetic 𧶠."),
        )
        self.assertEqual(
            merge.etymology_text_claim(ents[ord("续")]),
            "Simplified from 續 (糹 → 纟 and 賣 → 卖).",
        )

    def test_does_not_recurse_through_the_traditional_form(self):
        # 為 names 爲; 爲's text points back. Append 爲 as-is, once.
        ents = world(
            glyph("為", etymology_text="Simplified from 爲.", traditional_of="爲"),
            glyph("爲", etymology_text="Originally an ideogrammic compound (會意): 又 + 象. See 為."),
        )
        out = merge.composed_etymology_text(ents[ord("為")], ents)
        self.assertEqual(out.count("See 為."), 1)
        self.assertNotIn("Simplified from 爲.\nSimplified from 爲.", out)
