"""Parse the Dong Chinese lexicon (peterolson/chinese-lexicon `etymology/index.js`) into
human-verified component-function data.

The file is JavaScript: a long series of calls that register one character's etymology each.
The four that carry component **function** information (what we ingest):

  semphon(char, left, right, ...)  — 形声 phono-semantic: left = MEANING, right = SOUND
                                     (isSoundAndMeaning arg ⇒ the sound part also means → both)
  phonsem(char, left, right, ...)  — same but left = SOUND, right = MEANING
  semsem(char, left, right, ...)   — 会意 associative: both components MEANING
  icon(char, ...)                  — 象形/指事 pictographic/indicative: no phonetic component

We take these as authoritative for the characters they cover (the user's "Dong wins where it
has data"), overriding the algorithmic detector in build.py. Only single-character heads whose
components are themselves single characters are used — the pack keys component_use by bare
character, and the fragment/stroke bookkeeping Dong also carries is irrelevant here.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

# Pack `function` values (schema component_use.function): semantic|phonetic|both|empty|unknown.
MEANING = "semantic"
SOUND = "phonetic"
BOTH = "both"

# Pack formation_type values (must match what the detector emits: pictophonetic=形声,
# ideographic=会意/指事, pictographic=象形).
PHONO_SEMANTIC = "pictophonetic"
ASSOCIATIVE = "ideographic"
PICTOGRAPHIC = "pictographic"


@dataclass
class DongEntry:
    char: str
    formation_type: str
    # (component_char, function) in reading order; empty for a bare pictograph.
    components: list[tuple[str, str]]


def _split_args(text: str, open_idx: int) -> list[str] | None:
    """Split the argument list of a call whose '(' is at [open_idx], returning the raw
    top-level argument substrings (quotes/brackets respected). None if unbalanced."""
    args: list[str] = []
    depth = 0
    i = open_idx + 1
    start = i
    quote = ""
    n = len(text)
    while i < n:
        c = text[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = ""
        elif c in "\"'`":
            quote = c
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            if c == ")" and depth == 0:
                args.append(text[start:i])
                return args
            depth -= 1
        elif c == "," and depth == 0:
            args.append(text[start:i])
            start = i + 1
        i += 1
    return None


def _str_arg(raw: str) -> str | None:
    """A single-character string literal argument → its char, else None (skips fragments,
    numbers, expressions, multi-char components we can't key on)."""
    s = raw.strip()
    if len(s) >= 2 and s[0] in "\"'`" and s[-1] == s[0]:
        inner = s[1:-1]
        return inner if len(inner) == 1 else None
    return None


def _bool_arg(raw: str) -> bool:
    return raw.strip() == "true"


def parse_dong(index_js: Path) -> dict[str, DongEntry]:
    text = index_js.read_text(encoding="utf-8")
    out: dict[str, DongEntry] = {}

    def scan(func: str):
        needle = func + "("
        i = 0
        while True:
            j = text.find(needle, i)
            if j < 0:
                return
            # Ensure it's a call, not a substring of a longer identifier.
            if j > 0 and (text[j - 1].isalnum() or text[j - 1] == "_"):
                i = j + len(needle)
                continue
            args = _split_args(text, j + len(func))
            i = j + len(needle)
            if not args:
                continue
            yield args

    def add(char, formation, comps):
        # First definition wins (index.js lists a char once); ignore non-single-char heads.
        if char and char not in out:
            out[char] = DongEntry(char, formation, comps)

    # semphon: left=meaning, right=sound (isSoundAndMeaning is arg index 7 → sound is 'both')
    for args in scan("semphon"):
        char, left, right = _str_arg(args[0]), _str_arg(args[1]) if len(args) > 1 else None, _str_arg(args[2]) if len(args) > 2 else None
        if not char:
            continue
        sound_fn = BOTH if len(args) > 7 and _bool_arg(args[7]) else SOUND
        comps = [(c, fn) for c, fn in ((left, MEANING), (right, sound_fn)) if c]
        add(char, PHONO_SEMANTIC, comps)

    # phonsem: left=sound, right=meaning
    for args in scan("phonsem"):
        char, left, right = _str_arg(args[0]), _str_arg(args[1]) if len(args) > 1 else None, _str_arg(args[2]) if len(args) > 2 else None
        if not char:
            continue
        sound_fn = BOTH if len(args) > 7 and _bool_arg(args[7]) else SOUND
        comps = [(c, fn) for c, fn in ((left, sound_fn), (right, MEANING)) if c]
        add(char, PHONO_SEMANTIC, comps)

    # semsem: both meaning
    for args in scan("semsem"):
        char, left, right = _str_arg(args[0]), _str_arg(args[1]) if len(args) > 1 else None, _str_arg(args[2]) if len(args) > 2 else None
        if not char:
            continue
        comps = [(c, MEANING) for c in (left, right) if c]
        add(char, ASSOCIATIVE, comps)

    # icon: pictograph/indicative, but its 5th arg can carry nested meaning/sound/iconComponent
    # parts (森 = iconComponent 木 ×3). A part with a sound component is really phono-semantic;
    # ≥2 parts is really associative (会意, 森/明); 0-1 part is a true simple pictograph.
    for args in scan("icon"):
        char = _str_arg(args[0])
        if not char:
            continue
        raw = _nested_components(args[4]) if len(args) > 4 else []
        # Classify on the raw parts (森 = 木×3 is associative), then dedup repeated chars for
        # the displayed chips (one 木, not three).
        if any(fn in (SOUND, BOTH) for _, fn in raw):
            formation = PHONO_SEMANTIC
        elif len(raw) >= 2:
            formation = ASSOCIATIVE
        else:
            formation = PICTOGRAPHIC
        seen: dict[str, str] = {}
        for c, fn in raw:
            seen.setdefault(c, fn)
        add(char, formation, list(seen.items()))

    return out


def _nested_components(arg_text: str) -> list[tuple[str, str]]:
    """Component parts inside an icon() components array — meaningComponent/soundComponent/
    iconComponent calls → (char, function)."""
    roles = {"meaningComponent": MEANING, "soundComponent": SOUND, "iconComponent": MEANING}
    out: list[tuple[str, str]] = []
    for name, fn in roles.items():
        needle = name + "("
        i = 0
        while True:
            j = arg_text.find(needle, i)
            if j < 0:
                break
            i = j + len(needle)
            # first string arg after the open paren
            k = arg_text.find('"', j)
            if 0 <= k and k < arg_text.find(")", j):
                ch = arg_text[k + 1:k + 2]
                if ch and ord(ch) > 0x2E80:  # a CJK-ish char, not punctuation
                    out.append((ch, fn))
    return out
