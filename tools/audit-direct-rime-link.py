#!/usr/bin/env python3
"""Match packaged ELF build IDs to linker maps and reject retired static inputs."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from collections import defaultdict
from pathlib import Path


PREBUILT = {
    "librime": "librime.a", "glog": "libglog.a", "leveldb": "libleveldb.a",
    "lua": "liblua_static.a", "marisa": "libmarisa.a", "opencc": "libopencc.a",
    "yaml-cpp": "libyaml-cpp.a",
}
NDK_ARCHIVES = {"libatomic.a", "libunwind.a", "libc++abi.a", "libandroid_support.a"}


def build_id(data):
    assert data[:4] == b"\x7fELF", "Not an ELF file"
    wide = data[4] == 2
    order = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(order + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
    size, count = struct.unpack_from(order + "HH", data, 54 if wide else 42)
    for i in range(count):
        fields = struct.unpack_from(order + ("IIQQQQQQ" if wide else "IIIIIIII"), data, phoff + i * size)
        if fields[0] != 4:  # PT_NOTE survives stripping.
            continue
        off, length = (fields[2], fields[5]) if wide else (fields[1], fields[4])
        end = off + length
        while off + 12 <= end:
            names, descs, kind = struct.unpack_from(order + "III", data, off)
            off += 12
            name = data[off:off + names].rstrip(b"\0")
            off += (names + 3) & ~3
            desc = data[off:off + descs]
            off += (descs + 3) & ~3
            assert off <= end, "Truncated ELF note"
            if kind == 3 and name == b"GNU":
                return desc.hex()
    raise ValueError("ELF has no GNU build ID")


def cache_values(path):
    result = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith(("#", "//")) and "=" in line:
            name, value = line.split("=", 1)
            result[name.split(":", 1)[0]] = value
    return result


def audit_map(path, cache, prebuilt):
    text = path.read_text()
    assert not re.search(r"\bfcitx(?:5)?::|_Z\S*[56]fcitx", text), "Fcitx C++ symbols in native map"
    abi = cache["ANDROID_ABI"]
    expected = {(prebuilt / lib / abi / "lib" / name).resolve(): lib for lib, name in PREBUILT.items()}
    ndk = Path(cache["CMAKE_ANDROID_NDK"]).resolve()
    contributions = defaultdict(set)
    for line in text.splitlines():
        columns = line.split(None, 4)
        if len(columns) < 5:
            continue
        # lld: VMA LMA Size Align InputArchive(Object):(...section...).
        match = re.match(r"(.+\.a)\(([^)]+)\):", columns[4])
        if match:
            contributions[Path(match[1]).resolve()].add(match[2])
    assert contributions, f"No archive contributions in {path}"
    found, runtime = {}, {}
    for archive, objects in contributions.items():
        if archive in expected:
            found[expected[archive]] = {"archive": archive.name, "objects": len(objects)}
        else:
            allowed = archive.name in NDK_ARCHIVES or re.fullmatch(r"libclang_rt\.builtins-[\w-]+\.a", archive.name)
            assert archive.is_relative_to(ndk) and allowed, f"Unexpected static archive: {archive}"
            runtime[archive.name] = len(objects)
    assert found.keys() == PREBUILT.keys(), f"Missing expected engine archives: {PREBUILT.keys() - found.keys()}"
    return {"abi": abi, "static_prebuilt_archives": found, "ndk_archives": runtime,
            "fcitx_cpp_symbols": [], "map_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def verify_binding(path, cache):
    recorded = path.with_name(path.name + ".binding").read_text().splitlines()
    library = Path(cache["CMAKE_LIBRARY_OUTPUT_DIRECTORY"]) / "libdirect-rime.so"
    native_hash = hashlib.sha256(library.read_bytes()).hexdigest()
    map_hash = hashlib.sha256(path.read_bytes()).hexdigest()
    assert recorded == [native_hash, map_hash], f"Stale or mismatched map/library binding: {path}"
    return native_hash


def audit(apks, build_root, prebuilt):
    native = {}
    for path in sorted(build_root.rglob("direct-rime.linkmap")):
        cache = cache_values(path.with_name("CMakeCache.txt"))
        library = Path(cache["CMAKE_LIBRARY_OUTPUT_DIRECTORY"]) / "libdirect-rime.so"
        if library.exists():
            native.setdefault((cache["ANDROID_ABI"], build_id(library.read_bytes())), []).append((path, cache))
    assert native, "No built direct Rime maps/libraries found"
    result = []
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            names = [n for n in archive.namelist() if re.fullmatch(r"lib/[^/]+/libdirect-rime\.so", n)]
            assert names, f"No direct Rime library in {apk}"
            for name in names:
                data = archive.read(name)
                abi, identity = name.split("/")[1], build_id(data)
                matches = native.get((abi, identity), [])
                assert matches, f"No map matches packaged {abi} ELF build ID {identity}"
                # Audit every matching build, including duplicate cache directories.
                reports = [{"unstripped_native_sha256": verify_binding(path, cache),
                            **audit_map(path, cache, prebuilt)} for path, cache in matches]
                result.append({"apk": apk.name, "elf_build_id": identity,
                               "apk_native_sha256": hashlib.sha256(data).hexdigest(), **reports[0]})
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    parser.add_argument("--build-root", type=Path, default=Path("app/.cxx"))
    parser.add_argument("--prebuilt", type=Path, default=Path("native/prebuilt"))
    args = parser.parse_args()
    print(json.dumps(audit(args.apk, args.build_root, args.prebuilt), indent=2))
