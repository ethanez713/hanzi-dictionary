"""Fetch OPUS parallel corpora (moses format) for example-sentence coverage fill.

Currently just **TED2020** (en-zh_cn): professionally-translated TED-talk transcripts,
sentence-aligned. Used in `build.build_sentences` to fill example sentences for the ~1/3 of
HSK words Tatoeba never mentions, lifting HSK multi-char word coverage from ~67% to ~95%.

**OpenSubtitles is deliberately NOT fetched here.** It was evaluated (en-zh_cn, 11M pairs)
and rejected: subtitle "translations" are localised/condensed rather than faithful and are
frequently mis-aligned, which is wrong for a dictionary example — it added only ~4% more HSK
coverage over TED for far more noise. The URL is recorded below only so the evaluation is
reproducible, not because it should be re-added.

moses zips contain aligned files named `<Corpus>.<pair>.<lang>` (one sentence per line, line
i in the two languages a translation pair). Not part of fetch_all; run directly.
"""
from __future__ import annotations

import shutil
import zipfile
from datetime import date
from pathlib import Path

from fetchers.common import download, manifest_exists, snapshot_dir, write_manifest

BASE = "https://object.pouta.csc.fi"
TED2020_URL = f"{BASE}/OPUS-TED2020/v1/moses/en-zh_cn.txt.zip"
# Evaluated and rejected — see module docstring. Kept for reproducibility only:
_OPENSUBTITLES_URL = f"{BASE}/OPUS-OpenSubtitles/v2018/moses/en-zh_cn.txt.zip"
LICENSE = "TED2020: CC BY-NC-ND 4.0 (TED talk transcripts, via OPUS)"

# The two aligned member files inside the TED2020 moses zip.
TED2020_MEMBERS = ["TED2020.en-zh_cn.zh_cn", "TED2020.en-zh_cn.en"]


def fetch(force: bool = False) -> Path:
    version = date.today().isoformat()
    if not force and manifest_exists("opus", version):
        print(f"opus {version} already fetched, skipping")
        return snapshot_dir("opus", version)

    root = snapshot_dir("opus", version)
    ted_dir = root / "ted2020"
    ted_dir.mkdir(parents=True, exist_ok=True)

    zip_path = ted_dir / "_ted2020.zip"
    download(TED2020_URL, zip_path)
    tracked = []
    with zipfile.ZipFile(zip_path) as z:
        for name in TED2020_MEMBERS:
            with z.open(name) as src, (ted_dir / name).open("wb") as out:
                shutil.copyfileobj(src, out)
            tracked.append(ted_dir / name)
    zip_path.unlink()

    write_manifest("opus", BASE, version, LICENSE, root, tracked)
    print(f"opus {version}: TED2020 en-zh_cn -> {ted_dir}")
    return root


if __name__ == "__main__":
    fetch()
