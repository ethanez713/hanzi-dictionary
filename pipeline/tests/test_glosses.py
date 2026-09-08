"""Component role glosses and the CC-CEDICT gloss/pinyin fixes.
Fast, snapshot-free: hand-built inputs mirror the real data shapes they were measured on.

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


class MarkUnnatural(unittest.TestCase):
    """The sentence-quality marking (audit 2026-09). In: (simp, zh, en, tatoeba_id);
    out: the same plus (quality, shown). Nothing is ever removed."""

    def setUp(self):
        import tempfile, pathlib
        from pipeline import build as build_mod
        self.build = build_mod
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self._saved = build_mod.QUALITY_CACHE

    def tearDown(self):
        self.build.QUALITY_CACHE = self._saved

    def cache(self, scores):
        import json
        from pipeline import quality
        path = self.dir / "q.jsonl"
        path.write_text("".join(
            json.dumps({"k": k, "s": v, "m": quality.MODEL}, ensure_ascii=False) + "\n"
            for k, v in scores.items()), encoding="utf-8")
        self.build.QUALITY_CACHE = path

    def rows(self, *pairs):
        return [(w, z, None, None) for w, z in pairs]

    def shown(self, out):
        return sorted(r[1] for r in out if r[5])

    def test_nothing_is_ever_deleted(self):
        """The whole point: a wrong judgement must be recoverable without a re-fetch."""
        self.cache({"good": 5, "bad": 2})
        out = self.build.mark_unnatural(self.rows(("词", "good"), ("词", "bad")))
        self.assertEqual(len(out), 2)
        self.assertEqual(sorted(r[1] for r in out), ["bad", "good"])

    def test_the_score_is_stored_beside_the_sentence(self):
        self.cache({"good": 5, "bad": 2})
        out = {r[1]: r[4] for r in self.build.mark_unnatural(
            self.rows(("词", "good"), ("词", "bad")))}
        self.assertEqual(out, {"good": 5, "bad": 2})

    def test_defective_sentence_is_hidden_when_the_word_can_spare_it(self):
        self.cache({"good": 5, "bad": 2})
        out = self.build.mark_unnatural(self.rows(("词", "good"), ("词", "bad")))
        self.assertEqual(self.shown(out), ["good"])

    def test_a_word_never_loses_its_last_example(self):
        """9,325 words have exactly one sentence. Emptying their pages to remove one bad
        example is a far worse trade than showing it."""
        self.cache({"only": 2})
        out = self.build.mark_unnatural(self.rows(("词", "only")))
        self.assertEqual(self.shown(out), ["only"])

    def test_all_defective_still_shows_the_best_one(self):
        self.cache({"bad1": 2, "bad2": 1})
        out = self.build.mark_unnatural(self.rows(("词", "bad1"), ("词", "bad2")))
        self.assertEqual(self.shown(out), ["bad1"])

    def test_merely_awkward_survives_when_it_is_all_there_is(self):
        self.cache({"awkward": 3})
        self.assertEqual(self.shown(self.build.mark_unnatural(self.rows(("词", "awkward")))),
                         ["awkward"])

    def test_merely_awkward_is_hidden_when_something_better_exists(self):
        self.cache({"fine": 5, "awkward": 3})
        out = self.build.mark_unnatural(self.rows(("词", "fine"), ("词", "awkward")))
        self.assertEqual(self.shown(out), ["fine"])

    def test_unscored_sentences_are_shown(self):
        self.cache({"scored": 5})
        out = self.build.mark_unnatural(self.rows(("词", "scored"), ("词", "unscored")))
        self.assertEqual(self.shown(out), ["scored", "unscored"])

    def test_no_cache_shows_everything(self):
        """A clean checkout with no Ollama must still build a valid pack."""
        self.build.QUALITY_CACHE = self.dir / "absent.jsonl"
        out = self.build.mark_unnatural(self.rows(("词", "a"), ("词", "b")))
        self.assertEqual(self.shown(out), ["a", "b"])
        self.assertTrue(all(r[4] is None for r in out))

    def test_words_are_judged_independently(self):
        self.cache({"a_good": 5, "a_bad": 2, "b_bad": 2})
        out = self.build.mark_unnatural(
            self.rows(("甲", "a_good"), ("甲", "a_bad"), ("乙", "b_bad")))
        self.assertEqual(self.shown(out), ["a_good", "b_bad"])


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
