"""Fetch Unihan.zip (readings, S/T variants, radicals, shape codes) from unicode.org.

License: Unicode Data Files License (permissive). See
https://www.unicode.org/copyright.html#Exhibit1
"""
from __future__ import annotations

import urllib.request
import zipfile
from email.utils import parsedate_to_datetime
from pathlib import Path

from fetchers.common import download, manifest_exists, snapshot_dir, write_manifest

URL = "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip"
LICENSE = "Unicode Data Files License (permissive)"

# Only the fields the spec's schema actually needs: readings (kMandarin, kCantonese,
# kJapanese, kHanyuPinyin, and kHanyuPinlu for multi-reading frequency ordering per §15),
# S/T variants, and radical/stroke counts (kRSUnicode/kTotalStrokes live in IRGSources,
# not RadicalStrokeCounts, which is Adobe-glyph-collection-specific and not useful here).
#
# DictionaryLikeData carries the two SHAPE encodings — kCangjie (the character as a
# sequence of shape keys) and kFourCornerCode (its four corners as digits) — which is what
# `pipeline/lookalike.py` computes visually-confusable pairs from. Deriving look-alikes
# from shape codes is the standard method (Liu et al. 2011 build the Chinese spell-check
# confusion sets from Cangjie edit distance); this keeps that computation on data whose
# license we already ship.
WANTED_FILES = {
    "Unihan_Readings.txt",
    "Unihan_Variants.txt",
    "Unihan_IRGSources.txt",
    "Unihan_DictionaryLikeData.txt",
}


def _remote_version() -> str:
    req = urllib.request.Request(URL, method="HEAD", headers={"User-Agent": "hanzi-dictionary-pipeline/0.1"})
    with urllib.request.urlopen(req) as resp:
        last_modified = resp.headers.get("Last-Modified")
    if not last_modified:
        raise RuntimeError("Unihan.zip response had no Last-Modified header; cannot version snapshot")
    return parsedate_to_datetime(last_modified).date().isoformat()


def fetch(force: bool = False) -> Path:
    version = _remote_version()
    if not force and manifest_exists("unihan", version):
        print(f"unihan {version} already fetched, skipping")
        return snapshot_dir("unihan", version)

    out_dir = snapshot_dir("unihan", version)
    zip_path = out_dir / "Unihan.zip"
    download(URL, zip_path)

    extracted = []
    with zipfile.ZipFile(zip_path) as zf:
        for name in WANTED_FILES:
            zf.extract(name, out_dir)
            extracted.append(out_dir / name)
    zip_path.unlink()

    write_manifest("unihan", URL, version, LICENSE, out_dir, extracted)
    print(f"unihan {version}: {len(extracted)} files -> {out_dir}")
    return out_dir


if __name__ == "__main__":
    fetch()
