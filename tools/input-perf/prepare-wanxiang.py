#!/usr/bin/env python3
"""Build a reproducible Wanxiang v18.1.3 + LTS fixture, without shipping it in an APK."""
import argparse
import hashlib
import shutil
import urllib.request
import zipfile
from pathlib import Path

SOURCES = [
    ("rime-wanxiang-base.zip", "https://github.com/amzxyz/rime-wanxiang/releases/download/v18.1.3/rime-wanxiang-base.zip",
     "fc6495ba8489a898737ca29883dedaf61ff3baf593c78900dc59102c919d0e63"),
    ("wanxiang-lts-zh-hans.gram", "https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/wanxiang-lts-zh-hans.gram",
     "20f968dfe5dc461d23c355b93aa63925d6d36a5fb8f459ccd5c4dcd1cae6e6b9"),
]


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--cache', type=Path, default=Path('build/wanxiang-source'))
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for name, url, digest in SOURCES:
        path = args.cache / name
        if not path.is_file() or checksum(path) != digest:
            with urllib.request.urlopen(url, timeout=90) as source, path.open('wb') as out:
                shutil.copyfileobj(source, out, 1024 * 1024)
        if checksum(path) != digest:
            raise SystemExit('Source checksum changed: ' + name)
    with zipfile.ZipFile(args.cache / SOURCES[0][0]) as source, zipfile.ZipFile(args.output, 'w') as out:
        for info in source.infolist():
            if info.is_dir():
                continue
            entry = zipfile.ZipInfo(info.filename, info.date_time)
            with source.open(info) as contents, out.open(entry, 'w') as target:
                shutil.copyfileobj(contents, target, 1024 * 1024)
        entry = zipfile.ZipInfo(SOURCES[1][0], (2026, 10, 16, 0, 0, 0))
        with (args.cache / SOURCES[1][0]).open('rb') as contents, out.open(entry, 'w') as target:
            shutil.copyfileobj(contents, target, 1024 * 1024)
    print(str(args.output) + ' sha256:' + checksum(args.output))


if __name__ == '__main__':
    main()
