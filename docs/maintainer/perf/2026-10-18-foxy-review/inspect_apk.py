#!/usr/bin/env python3
"""Read-only APK inventory and ART 010 profile method audit (Python stdlib only).

Usage: python3 inspect_apk.py /path/to/target.apk [other.apk ...]
Does not install an APK or execute its code. Profile audit supports one classes.dex.
"""
import collections
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile
import zlib


def profile_methods(profile, dex, dex_zip_crc):
    if profile[:8] != b"pro\x00010\x00":
        raise ValueError("Only ART profile 010 is supported")
    count = profile[8]
    size, compressed_size = struct.unpack_from("<II", profile, 9)
    if count != 1 or len(profile) != 17 + compressed_size:
        raise ValueError("Expected one DEX entry and an intact profile")
    body = zlib.decompress(profile[17:])
    if len(body) != size:
        raise ValueError("Profile size mismatch")
    key_size, class_count, hot_size, checksum, method_count = struct.unpack_from("<HHIII", body)
    key = body[16:16 + key_size].decode()
    if key != "classes.dex" or checksum != dex_zip_crc:
        raise ValueError("Profile does not match classes.dex")
    pos = 16 + key_size
    hot_end = pos + hot_size
    methods = set()
    method_id = 0
    flags = collections.Counter()
    while pos < hot_end:
        delta, caches = struct.unpack_from("<HH", body, pos)
        pos += 4
        method_id += delta
        methods.add(method_id)
        flags["hot"] += 1
        for _ in range(caches):
            _, maps = struct.unpack_from("<HB", body, pos)
            pos += 3
            if maps not in (6, 7):
                for _ in range(maps):
                    _, classes = body[pos:pos + 2]
                    pos += 2 + 2 * classes
    if pos != hot_end:
        raise ValueError("Invalid inline cache region")
    pos += 2 * class_count
    bits = body[pos:pos + (method_count * 2 + 7) // 8]
    pos += len(bits)
    if pos != len(body):
        raise ValueError("Trailing or missing profile data")
    for bit in range(2 * method_count):
        if bits[bit // 8] & (1 << (bit % 8)):
            methods.add(bit % method_count)
            flags["startup" if bit < method_count else "post_startup"] += 1
    string_count, string_offset = struct.unpack_from("<II", dex, 56)
    strings = []
    for i in range(string_count):
        offset = struct.unpack_from("<I", dex, string_offset + 4 * i)[0]
        while dex[offset] & 128:
            offset += 1
        offset += 1  # skip the final byte of the UTF-16 length ULEB128
        strings.append(dex[offset:dex.index(b"\0", offset)].decode("utf-8", "replace"))
    type_count, type_offset = struct.unpack_from("<II", dex, 64)
    types = [strings[struct.unpack_from("<I", dex, type_offset + 4 * i)[0]] for i in range(type_count)]
    dex_method_count, method_offset = struct.unpack_from("<II", dex, 88)
    if dex_method_count != method_count or any(i >= method_count for i in methods):
        raise ValueError("DEX method count mismatch")
    names = []
    for i in sorted(methods):
        cls, _, name = struct.unpack_from("<HHI", dex, method_offset + 8 * i)
        names.append(types[cls] + "->" + strings[name])
    relevant = ["Lat0;", "Llq0;", "Lmq0;", "Lpt0;", "Lj10;", "Lgf;"]
    return {
        "format": "010", "profile_checksum_matches_apk_dex_crc": True,
        "profile_class_count": class_count, "dex_method_count": method_count,
        "unique_profiled_methods": len(methods), "flags": dict(flags),
        "named_app_methods": [n for n in names if n.startswith("Lcom/fxliang/foxy/")],
        "key_obfuscated_class_methods": {c: [n for n in names if n.startswith(c + "->")] for c in relevant},
        "top_profiled_classes": collections.Counter(n.split("->")[0] for n in names).most_common(8),
    }


def inspect(path):
    result = {"file": path.name, "bytes": path.stat().st_size,
              "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    with zipfile.ZipFile(path) as apk:
        entries = apk.infolist()
        dex = [i for i in entries if i.filename.endswith(".dex")]
        result.update(dex_count=len(dex), dex_bytes=sum(i.file_size for i in dex),
                      native_libraries={i.filename: i.file_size for i in entries
                                        if i.filename.startswith("lib/") and i.filename.endswith(".so")})
        profiles = {i.filename: i.file_size for i in entries if i.filename.startswith("assets/dexopt/")}
        result["profiles"] = profiles
        if len(dex) == 1 and "assets/dexopt/baseline.prof" in profiles:
            result["profile_method_audit"] = profile_methods(apk.read("assets/dexopt/baseline.prof"), apk.read("classes.dex"), apk.getinfo("classes.dex").CRC)
    return result


if __name__ == "__main__":
    print(json.dumps([inspect(Path(arg)) for arg in sys.argv[1:]], ensure_ascii=False, indent=2))
