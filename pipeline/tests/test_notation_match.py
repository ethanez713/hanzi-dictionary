"""Match-tier regression tests for the MC/OC notation comparators.

Guards the empty-onset trap: two vowel-initial syllables both split to an empty onset,
and a shared *empty* onset must not count as an "initial" match (pinyin.py guards this;
its MC/OC mirrors originally did not). No such reading exists in the current wiktextract
dump, but a source change could introduce one.

Run: pipeline/.venv/bin/python -m unittest discover -s pipeline/tests   (or plain python3 -m).
"""
import unittest

from pipeline import mc_notation as mc, oc_notation as oc


class EmptyOnsetTest(unittest.TestCase):
    def test_mc_vowel_initials_with_different_rimes_do_not_match(self):
        self.assertIsNone(mc.compare_syllables("aeng", "ijk"))

    def test_oc_vowel_initials_with_different_rimes_do_not_match(self):
        self.assertIsNone(oc.compare_syllables("aː", "en"))

    def test_mc_real_initial_match_still_holds(self):
        # both kh- onset, different rime -> initial
        self.assertEqual(mc.compare_syllables("khwen", "khaeng"), "initial")

    def test_oc_shared_rime_still_matches(self):
        self.assertEqual(oc.compare_syllables("kaːŋ", "paːŋ"), "rime")


if __name__ == "__main__":
    unittest.main()
