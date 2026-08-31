"""Regression tests for the shape-code look-alike pairing (lookalike.compute_pairs).

Run: pipeline/.venv/bin/python -m unittest discover -s pipeline/tests   (or plain python3 -m).
"""
import unittest

from pipeline import lookalike
from pipeline.lookalike import Shape


class SelfPairTest(unittest.TestCase):
    def test_repeated_cangjie_key_does_not_pair_a_glyph_with_itself(self):
        # 三 = Cangjie "MMM": every single-key deletion yields "MM", so the symmetric-delete
        # bucket "MM" held 三 three times and compute_pairs paired it with itself (516 such
        # self-loops shipped in the pack before this guard).
        shapes = {
            ord("三"): Shape(cangjie="MMM", four_corner="10100", strokes=3, radical="1"),
            ord("二"): Shape(cangjie="MM", four_corner="10100", strokes=2, radical="7"),
        }
        pairs = lookalike.compute_pairs(shapes)
        self.assertFalse(
            [(a, b) for a, b, _ in pairs if a == b],
            "a glyph must never be its own look-alike",
        )

    def test_distinct_one_key_neighbours_still_pair(self):
        # 妹 = VJD, 姑 = VJR — one Cangjie key apart, same radical, stroke delta 0: a real pair.
        shapes = {
            ord("妹"): Shape(cangjie="VJD", four_corner="44490", strokes=8, radical="38"),
            ord("姑"): Shape(cangjie="VJR", four_corner="44460", strokes=8, radical="38"),
        }
        pairs = lookalike.compute_pairs(shapes)
        lo, hi = sorted((ord("妹"), ord("姑")))
        self.assertIn((lo, hi), {(a, b) for a, b, _ in pairs})


if __name__ == "__main__":
    unittest.main()
