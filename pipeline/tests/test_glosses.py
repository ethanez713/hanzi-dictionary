"""Component role glosses and the CC-CEDICT gloss/pinyin fixes (audit 2026-09).
Fast, snapshot-free: hand-built inputs mirror the real data shapes measured in
docs/audits/gloss-audit-2026-09.md.

Run: pipeline/.venv/bin/python -m unittest discover -s pipeline/tests   (or plain python3 -m).
"""
import unittest

from pipeline import radicals
from pipeline.build import single_char_cedict_defs
from pipeline.pinyin import neutralize_potential_bu


def mmh(semantic, hint, kind="pictophonetic"):
    return {"etymology": {"type": kind, "semantic": semantic, "hint": hint}}


def entries(*specs):
    return {i: mmh(*s) for i, s in enumerate(specs)}


class VoteRoleGlosses(unittest.TestCase):
    def test_modal_hint_wins(self):
        g = radicals.vote_role_glosses(entries(("页", "head"), ("页", "head"),
                                               ("页", "head"), ("页", "leaf")))
        gloss, source, support, agreement = g["页"]
        self.assertEqual(("head", "makemeahanzi", 3), (gloss, source, support))
        self.assertAlmostEqual(agreement, 0.75)

    def test_synonym_split_still_ships(self):
        """艹 is plant/grass/herb at 172/118/30 in the real data. A low agreement score is
        lexical variation, not doubt, so it must not suppress the gloss."""
        g = radicals.vote_role_glosses(entries(*([("艹", "plant")] * 3 + [("艹", "grass")] * 2)))
        self.assertEqual(g["艹"][0], "plant")

    def test_thin_evidence_is_dropped(self):
        g = radicals.vote_role_glosses(entries(("寸", "inch"), ("寸", "inch")))
        self.assertEqual(g["寸"][1], "curated")

    def test_only_pictophonetic_hints_are_counted(self):
        """On a pictographic entry, hint describes the whole character ("A person's head")
        rather than the part, so it must not vote."""
        g = radicals.vote_role_glosses(entries(
            *([("贝", "A person's head", "pictographic")] * 5)))
        self.assertNotIn("贝", {k: v for k, v in g.items() if v[1] == "makemeahanzi"})

    def test_prose_hints_are_excluded(self):
        g = radicals.vote_role_glosses(entries(
            ("氵", "water"), ("氵", "water"), ("氵", "water"),
            ("氵", "water in a spirit level")))
        self.assertEqual(g["氵"][3], 1.0)

    def test_curated_overrides_the_vote(self):
        g = radicals.vote_role_glosses(entries(*([("攵", "script")] * 9)))
        self.assertEqual(g["攵"], ("action, striking", "curated", 0, 1.0))

    def test_curated_reaches_components_with_no_glyph_row(self):
        """⻏ and 𤣩 have no glyph row at all, so nothing else in the pipeline can gloss
        them; they were two of the blank component cards this fix exists to close."""
        g = radicals.vote_role_glosses({})
        self.assertEqual(g["⻏"][0], "city")
        self.assertEqual(g["𤣩"][0], "jade")


class NeutralizePotentialBu(unittest.TestCase):
    KNOWN = {"受不了", "受得了", "三不管", "冬不拉", "来不及"}

    def test_rewrites_a_potential_complement(self):
        self.assertEqual(
            neutralize_potential_bu("受不了", "shou4 bu4 liao3", self.KNOWN),
            "shou4 bu5 liao3")

    def test_leaves_plain_negation_alone(self):
        """三不管 and 冬不拉 (a Kazakh instrument) share the X不Y shape but have no
        affirmative form, which is the whole point of the test."""
        for word, py in (("三不管", "san1 bu4 guan3"), ("冬不拉", "dong1 bu4 la1")):
            self.assertEqual(neutralize_potential_bu(word, py, self.KNOWN), py)

    def test_leaves_already_neutral_alone(self):
        self.assertEqual(
            neutralize_potential_bu("来不及", "lai2 bu5 ji2", self.KNOWN), "lai2 bu5 ji2")

    def test_ignores_words_that_are_not_three_syllables(self):
        self.assertEqual(
            neutralize_potential_bu("不是", "bu4 shi4", self.KNOWN), "bu4 shi4")


class SingleCharCedictDefs(unittest.TestCase):
    def setUp(self):
        import tempfile, pathlib
        self.dir = pathlib.Path(tempfile.mkdtemp())
        (self.dir / "cedict_ts.u8").write_text(
            "# comment\n"
            "項 项 [Xiang4] /surname Xiang/\n"
            "項 项 [xiang4] /back of neck/item/thing/\n"
            "頁 页 [xie2] /head/\n"
            "頁 页 [ye4] /page/leaf/\n"
            "項目 项目 [xiang4 mu4] /item; project/\n",
            encoding="utf-8")

    def test_proper_noun_readings_sort_last(self):
        """CC-CEDICT's surname entry for 项 would otherwise head the character's gloss."""
        defs = single_char_cedict_defs(self.dir)
        self.assertEqual(defs["项"],
                         ["back of neck", "item", "thing", "surname Xiang"])

    def test_all_readings_of_a_character_contribute(self):
        self.assertEqual(single_char_cedict_defs(self.dir)["页"], ["head", "page", "leaf"])

    def test_multi_character_entries_are_skipped(self):
        self.assertNotIn("项目", single_char_cedict_defs(self.dir))


if __name__ == "__main__":
    unittest.main()
