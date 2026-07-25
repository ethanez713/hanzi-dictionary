"""Fetch Tatoeba Mandarin sentences + English translations (CC BY 2.0 FR).

Three official Tatoeba exports (https://tatoeba.org/downloads):
  - per_language/cmn/cmn_sentences.tsv.bz2  (id \\t lang \\t text)
  - per_language/eng/eng_sentences.tsv.bz2
  - links.tar.bz2                           (links.csv: sentence_id \\t translation_id)

The join cmn -> links -> eng yields Mandarin sentences with an English gloss, which the
build step attaches to words as example sentences (spec §9 word page). The dumps aren't
dated in-file, so the snapshot is versioned by fetch date.
"""
from __future__ import annotations

import bz2
import shutil
import tarfile
from datetime import date
from pathlib import Path

from fetchers.common import download, manifest_exists, snapshot_dir, write_manifest

BASE = "https://downloads.tatoeba.org/exports"
CMN_URL = f"{BASE}/per_language/cmn/cmn_sentences.tsv.bz2"
ENG_URL = f"{BASE}/per_language/eng/eng_sentences.tsv.bz2"
LINKS_URL = f"{BASE}/links.tar.bz2"
LICENSE = "CC BY 2.0 FR"


def _download_bz2(url: str, dest: Path) -> None:
    """Download a .bz2 and stream-decompress it to dest."""
    tmp = dest.with_name(dest.name + ".bz2")
    download(url, tmp)
    with bz2.open(tmp, "rb") as src, dest.open("wb") as out:
        shutil.copyfileobj(src, out)
    tmp.unlink()


def fetch(force: bool = False) -> Path:
    version = date.today().isoformat()
    if not force and manifest_exists("tatoeba", version):
        print(f"tatoeba {version} already fetched, skipping")
        return snapshot_dir("tatoeba", version)

    out_dir = snapshot_dir("tatoeba", version)
    cmn = out_dir / "cmn_sentences.tsv"
    eng = out_dir / "eng_sentences.tsv"
    links = out_dir / "links.csv"

    _download_bz2(CMN_URL, cmn)
    _download_bz2(ENG_URL, eng)

    # links.tar.bz2 wraps links.csv in a tar; stream the member out.
    links_tar = out_dir / "links.tar.bz2"
    download(LINKS_URL, links_tar)
    with tarfile.open(links_tar, "r:bz2") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("links.csv"))
        extracted = tar.extractfile(member)
        assert extracted is not None
        with extracted as src, links.open("wb") as out:
            shutil.copyfileobj(src, out)
    links_tar.unlink()

    write_manifest("tatoeba", BASE, version, LICENSE, out_dir, [cmn, eng, links])
    print(f"tatoeba {version}: cmn+eng sentences + links -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
