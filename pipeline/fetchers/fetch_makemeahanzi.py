"""Fetch makemeahanzi (decomposition, semantic/phonetic etymology fields, stroke data).

Dictionary data derived from Unihan+CJKlib; graphics under Arphic Public License.
https://github.com/skishore/makemeahanzi
"""
from __future__ import annotations

from pathlib import Path

from fetchers.common import git_shallow_clone, manifest_exists, snapshot_dir, write_manifest

REPO_URL = "https://github.com/skishore/makemeahanzi"
LICENSE = "dictionary.txt: derived from Unihan+CJKlib (permissive); graphics.txt: Arphic Public License"

TRACKED_FILES = ["dictionary.txt", "graphics.txt"]


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
