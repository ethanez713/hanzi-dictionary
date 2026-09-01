"""Fetch peterolson/chinese-lexicon (built for dong-chinese.com) — human-verified component
function labels + formation types for ~4,500 characters (`pipeline/dong.py` parses them).

**Now ingested into the PERSONAL app pack** (user decision 2026-09-01: "Dong wins where it
has data") as the authoritative component-function source for the characters it covers,
overriding the algorithmic detector — see the Dong override block in `build.py`. This is fine
because the app is **not published anywhere**; the source's license is still unconfirmed for
publication, so it must NOT go into any PUBLISHED dataset (the override reads a snapshot that
a clean checkout won't have, so a published Tatoeba-/detector-only pack still builds without
it). Not part of fetch_all/FETCHERS; run directly.
"""
from __future__ import annotations

import shutil
from pathlib import Path

from fetchers.common import git_shallow_clone, manifest_exists, snapshot_dir, write_manifest

REPO_URL = "https://github.com/peterolson/chinese-lexicon"
LICENSE = "UNCONFIRMED — private validation use only, not for publication (spec §5)"

TRACKED_FILES = ["etymology/index.js", "etymology/etymologyCommands.js"]


def fetch(force: bool = False) -> Path:
    tmp_clone = snapshot_dir("dong-chinese-lexicon-PRIVATE", "_tmp_clone")
    commit = git_shallow_clone(REPO_URL, tmp_clone)
    version = commit[:12]

    if not force and manifest_exists("dong-chinese-lexicon-PRIVATE", version):
        print(f"dong-chinese-lexicon (private) {version} already fetched, skipping")
        shutil.rmtree(tmp_clone)
        return snapshot_dir("dong-chinese-lexicon-PRIVATE", version)

    out_dir = snapshot_dir("dong-chinese-lexicon-PRIVATE", version)
    tracked = []
    for rel in TRACKED_FILES:
        src = tmp_clone / rel
        if not src.exists():
            raise FileNotFoundError(f"expected {rel} in chinese-lexicon repo, not found")
        dest = out_dir / Path(rel).name
        dest.write_bytes(src.read_bytes())
        tracked.append(dest)

    shutil.rmtree(tmp_clone)

    write_manifest("dong-chinese-lexicon-PRIVATE", REPO_URL, version, LICENSE, out_dir, tracked)
    print(f"dong-chinese-lexicon (private) {version}: {len(tracked)} files -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
