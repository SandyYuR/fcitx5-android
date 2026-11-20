#!/usr/bin/env python3
"""Assemble the signed release directory from per-ABI CI artifacts.

Each artifact directory holds one APK and the build-metadata.json sidecar that
BuildMetadataPlugin writes next to it. The APK's native libraries decide its
ABI; the sidecar decides versionName and commitHash. Every expected ABI must
appear exactly once, all sidecars must agree on one version, and that version
must come from the commit this workflow is running for. A failure aborts the
job before any file is published, so a release is either complete for every
expected ABI or absent.
"""

import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

KNOWN_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
VERSION_PATTERN = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*$")
NATIVE_LIB_PATTERN = re.compile(r"^lib/([^/]+)/.+[.]so$")


def fail(message):
    print("error: " + message, file=sys.stderr)
    sys.exit(1)


def detect_abi(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
    abis = sorted({match.group(1) for name in names if (match := NATIVE_LIB_PATTERN.match(name))})
    unknown = [abi for abi in abis if abi not in KNOWN_ABIS]
    if unknown:
        fail(f"{apk}: unknown ABI directories {unknown}")
    if len(abis) != 1:
        fail(f"{apk}: expected exactly one ABI, found {abis or 'none'}")
    abi = abis[0]
    if f"lib/{abi}/libdirect-rime.so" not in names:
        fail(f"{apk}: missing lib/{abi}/libdirect-rime.so")
    return abi


def read_metadata(apk):
    path = apk.parent / "build-metadata.json"
    if not path.is_file():
        fail(f"{apk}: missing {path}")
    try:
        metadata = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        fail(f"{path}: cannot read metadata: {error}")
    version = metadata.get("versionName")
    commit = metadata.get("commitHash")
    if not isinstance(version, str) or not VERSION_PATTERN.match(version):
        fail(f"{path}: unusable versionName {version!r}")
    if not isinstance(commit, str) or not commit:
        fail(f"{path}: unusable commitHash {commit!r}")
    return version, commit


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--artifacts", required=True, type=Path,
                        help="directory containing the downloaded artifacts")
    parser.add_argument("--output", required=True, type=Path,
                        help="directory receiving the renamed APKs and version.txt")
    parser.add_argument("--expected-abis", required=True,
                        help="comma-separated ABIs that must all be present")
    parser.add_argument("--expected-commit", required=True,
                        help="full commit hash this release is built from")
    arguments = parser.parse_args()

    expected_abis = [abi.strip() for abi in arguments.expected_abis.split(",") if abi.strip()]
    if not expected_abis:
        fail("--expected-abis is empty")
    duplicated = sorted({abi for abi in expected_abis if expected_abis.count(abi) > 1})
    if duplicated:
        fail(f"--expected-abis repeats {duplicated}")
    unknown = [abi for abi in expected_abis if abi not in KNOWN_ABIS]
    if unknown:
        fail(f"--expected-abis contains unknown ABIs {unknown}")
    expected_commit = arguments.expected_commit.strip().lower()

    apks = sorted(path for path in arguments.artifacts.rglob("*.apk") if path.is_file())
    if not apks:
        fail(f"no APK under {arguments.artifacts}")

    releases = {}
    versions = set()
    commits = set()
    for apk in apks:
        abi = detect_abi(apk)
        version, commit = read_metadata(apk)
        if abi in releases:
            fail(f"ABI {abi} appears twice: {releases[abi][0]} and {apk}")
        releases[abi] = (apk, version, commit)
        versions.add(version)
        commits.add(commit.lower())

    if len(versions) != 1:
        fail(f"artifacts disagree on versionName: {sorted(versions)}")
    version = versions.pop()
    if len(commits) != 1:
        fail(f"artifacts disagree on commitHash: {sorted(commits)}")
    commit = commits.pop()
    if commit != expected_commit:
        fail(f"commitHash {commit} does not match expected {expected_commit}")

    missing = [abi for abi in expected_abis if abi not in releases]
    extra = sorted(set(releases) - set(expected_abis))
    if missing or extra:
        fail(f"expected ABIs {expected_abis}, missing {missing}, unexpected {extra}")

    staging = arguments.output.with_name(arguments.output.name + ".tmp")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    try:
        rows = []
        for abi in expected_abis:
            source, _, _ = releases[abi]
            target = staging / f"zhongzhoue-{version}-{abi}.apk"
            shutil.copyfile(source, target)
            digest = hashlib.sha256(target.read_bytes()).hexdigest()
            rows.append((abi, target.name, target.stat().st_size, digest))
        (staging / "version.txt").write_text(version + "\n", encoding="utf-8")
        if arguments.output.exists():
            shutil.rmtree(arguments.output)
        staging.rename(arguments.output)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise

    print(f"version: {version}")
    print(f"commit:  {commit}")
    print(f"{'abi':<14}{'size':>12}  {'sha256':<64}file")
    for abi, name, size, digest in rows:
        print(f"{abi:<14}{size / 1048576:>9.1f} MiB  {digest}  {name}")


if __name__ == "__main__":
    main()
