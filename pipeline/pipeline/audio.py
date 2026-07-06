"""Generate the syllable bank: one clip per pinyin syllable×tone (spec §9), covering
every character's isolated reading.

Rather than feed bare pinyin to the TTS (which won't reliably produce the right tone),
each syllable×tone is voiced via a *representative character* whose default reading is
that syllable — preferring monophonic (single-reading) high-frequency characters, so the
TTS's hanzi G2P lands on the intended tone. Generated with Piper (MIT-licensed,
redistributable output; avoids cloud-TTS output-redistribution ambiguity per spec §9).

Outputs:
- data/build/audio/<key>.wav   (key = base pinyin + tone digit, neutral tone = 5)
- data/build/audio-index.json  { reading_value_as_stored : key }  so the app maps a
  character's pinyin reading straight to a clip without reimplementing tone-stripping.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import wave
from collections import defaultdict
from pathlib import Path

from pipeline import pinyin
from pipeline.build import BUILD_DIR

VOICE_DIR = Path(__file__).resolve().parent.parent / "data" / "tts-voices"
VOICE_NAME = "zh_CN-huayan-medium"
AUDIO_DIR = BUILD_DIR / "audio"
AUDIO_INDEX = BUILD_DIR / "audio-index.json"
SOURCE_DB = BUILD_DIR / "hanzi.db"


def syllable_key(value: str) -> str | None:
    """'qǐng' -> 'qing3'; neutral tone -> '5'. Rejects multi-syllable values (spaces)."""
    if " " in value.strip():
        return None
    base, tone = pinyin.strip_tone(value)
    if not base or not base.isalpha():
        return None
    return f"{base}{tone if tone else 5}"


def choose_representatives(conn: sqlite3.Connection) -> tuple[dict[str, str], dict[str, str]]:
    rows = conn.execute("SELECT glyph_id, value, freq FROM reading WHERE system='pinyin'").fetchall()
    glyph_char = dict(conn.execute("SELECT id, char FROM glyph"))
    glyph_freq = dict(conn.execute("SELECT id, freq_rank FROM glyph"))
    glyph_scripts = dict(conn.execute("SELECT id, scripts FROM glyph"))

    # each glyph's primary (most frequent) pinyin reading — used to prefer a char whose
    # MAIN reading is the target syllable, so the TTS's default pronunciation matches.
    best_reading: dict[str, tuple[int, str]] = {}
    for gid, val, freq in rows:
        f = freq or 0
        if gid not in best_reading or f > best_reading[gid][0]:
            best_reading[gid] = (f, val)

    candidates: dict[str, list[tuple]] = defaultdict(list)
    key_for_value: dict[str, str] = {}
    for gid, val, freq in rows:
        key = syllable_key(val)
        if not key:
            continue
        key_for_value[val] = key
        # A simplified voice (zh_CN) mispronounces traditional-only glyphs, so rank those
        # last; then prefer chars whose primary reading is this syllable; then the most
        # common char (the TTS reliably knows common characters).
        candidates[key].append((
            0 if glyph_scripts.get(gid) in ("simp", "both") else 1,
            0 if best_reading[gid][1] == val else 1,
            glyph_freq.get(gid) or 1_000_000_000,
            glyph_char[gid],
        ))

    reps = {key: min(cands)[3] for key, cands in candidates.items()}
    return reps, key_for_value


def generate(limit: int | None = None) -> None:
    from piper import PiperVoice

    voice = PiperVoice.load(
        str(VOICE_DIR / f"{VOICE_NAME}.onnx"),
        config_path=str(VOICE_DIR / f"{VOICE_NAME}.onnx.json"),
    )
    conn = sqlite3.connect(SOURCE_DB)
    reps, key_for_value = choose_representatives(conn)
    conn.close()

    AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    keys = sorted(reps)
    if limit:
        keys = keys[:limit]

    for i, key in enumerate(keys, 1):
        out = AUDIO_DIR / f"{key}.wav"
        with wave.open(str(out), "wb") as wf:
            voice.synthesize_wav(reps[key], wf)
        if i % 200 == 0:
            print(f"  {i}/{len(keys)} clips")

    # only index values whose key we actually generated (matters when --limit is used)
    generated = set(keys)
    index = {val: key for val, key in key_for_value.items() if key in generated}
    AUDIO_INDEX.write_text(json.dumps(index, ensure_ascii=False))
    print(f"audio bank: {len(keys)} clips -> {AUDIO_DIR}; index {len(index)} readings -> {AUDIO_INDEX}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate the syllable audio bank")
    parser.add_argument("--limit", type=int, default=None, help="only generate the first N keys (for testing)")
    args = parser.parse_args()
    generate(limit=args.limit)
