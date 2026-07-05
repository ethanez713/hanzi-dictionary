"""Fetch makemeahanzi dictionary.txt (decomposition, semantic/phonetic etymology fields).

Per the repo's own COPYING file, dictionary.txt (derived from Unihan+CJKlib) is
LGPL-3.0-or-later — a real copyleft license, not "permissive" as earlier recorded
here; that was wrong and matters for publication (spec §8.6 license lint, §13
"counsel review pre-launch"). graphics.txt (stroke-order SVG data, Arphic Public
License — a third, different license again) isn't fetched: nothing in the
pipeline uses it yet (stroke rendering is spec §9/M3, a later milestone), so
there's no reason to take on tracking a third license for unused data before
it's actually needed.
https://github.com/skishore/makemeahanzi
"""
from __future__ import annotations

from pathlib import Path

from fetchers.common import git_shallow_clone, manifest_exists, snapshot_dir, write_manifest

REPO_URL = "https://github.com/skishore/makemeahanzi"
LICENSE = "LGPL-3.0-or-later"

TRACKED_FILES = ["dictionary.txt"]


def fetch(force: bool = False) -> Path:
    tmp_clone = snapshot_dir("makemeahanzi", "_tmp_clone")
    commit = git_shallow_clone(REPO_URL, tmp_clone)
    version = commit[:12]

    if not force and manifest_exists("makemeahanzi", version):
        print(f"makemeahanzi {version} already fetched, skipping")
        import shutil

        shutil.rmtree(tmp_clone)
        return snapshot_dir("makemeahanzi", version)

    out_dir = snapshot_dir("makemeahanzi", version)
    tracked = []
    for name in TRACKED_FILES:
        src = tmp_clone / name
        if not src.exists():
            raise FileNotFoundError(f"expected {name} in makemeahanzi repo, not found")
        dest = out_dir / name
        dest.write_bytes(src.read_bytes())
        tracked.append(dest)

    import shutil

    shutil.rmtree(tmp_clone)

    write_manifest("makemeahanzi", REPO_URL, version, LICENSE, out_dir, tracked)
    print(f"makemeahanzi {version}: {len(tracked)} files -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
