#!/usr/bin/env python3
"""Fail if the full direct frontend packages or links a Fcitx component."""
import argparse
import json
import re
import struct
import zipfile
from pathlib import Path


def dex_classes(data):
    """Read class definitions, rather than mistaking a string reference for a class."""
    assert data[:4] == b"dex\n", "Not a standard APK DEX file"
    strings = struct.unpack_from("<I", data, 60)[0]
    types = struct.unpack_from("<I", data, 68)[0]
    count, classes = struct.unpack_from("<II", data, 96)
    for index in range(count):
        type_index = struct.unpack_from("<I", data, classes + index * 32)[0]
        string_index = struct.unpack_from("<I", data, types + type_index * 4)[0]
        offset = struct.unpack_from("<I", data, strings + string_index * 4)[0]
        # Skip the ULEB128 UTF-16 length; these descriptors end at the NUL byte.
        for _ in range(5):
            byte = data[offset]
            offset += 1
            if byte < 0x80:
                break
        else:
            raise ValueError("Invalid DEX string length")
        yield data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace")


def dependencies(data):
    assert data[:4] == b"\x7fELF", "Not an ELF library"
    wide = data[4] == 2
    order = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(order + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
    size, count = struct.unpack_from(order + "HH", data, 54 if wide else 42)
    loads, dynamic = [], None
    for i in range(count):
        fields = struct.unpack_from(order + ("IIQQQQQQ" if wide else "IIIIIIII"), data, phoff + i * size)
        kind = fields[0]
        off, addr, length = (fields[2], fields[3], fields[5]) if wide else (fields[1], fields[2], fields[4])
        if kind == 1:
            loads.append((addr, off, length))
            # Android's 16 KiB requirement applies to 64-bit ABIs. The NDK's
            # ARMv7/x86 C++ runtimes correctly retain 4 KiB alignment.
            required_alignment = 16384 if wide else 4096
            assert fields[7] >= required_alignment, f"ELF load alignment is below {required_alignment} bytes"
        if kind == 2:
            dynamic = (off, length)
    assert dynamic is not None, "No dynamic section"
    needed, strings = [], None
    stride = 16 if wide else 8
    for off in range(dynamic[0], sum(dynamic), stride):
        tag, value = struct.unpack_from(order + ("qQ" if wide else "iI"), data, off)
        if tag == 0:
            break
        if tag == 1:
            needed.append(value)
        if tag == 5:
            strings = value
    assert strings is not None, "No dynamic string table"
    base = next(off + strings - addr for addr, off, length in loads if addr <= strings < addr + length)
    return [data[base + n:data.index(b"\0", base + n)].decode() for n in needed]


def verify(path):
    allowed = {"libdirect-rime.so", "libc++_shared.so"}
    system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
    with zipfile.ZipFile(path) as apk:
        # aapt2 writes UTF-8 names without consistently setting ZIP's UTF-8 flag.
        def decoded_name(info):
            if info.flag_bits & 0x800:
                return info.filename
            try:
                return info.filename.encode("cp437").decode("utf-8")
            except UnicodeError:
                return info.filename
        names = {decoded_name(info) for info in apk.infolist()}
        dex_files = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
        assert dex_files, "APK has no application DEX"
        obsolete = ("Lorg/fcitx/fcitx5/android/core/Fcitx", "Lorg/fcitx/fcitx5/android/core/FcitxDispatcher")
        legacy = sorted(name for dex in dex_files for name in dex_classes(apk.read(dex))
                        if any(name == prefix + ";" or name.startswith(prefix + "$") for prefix in obsolete))
        assert not legacy, f"Unused legacy backend classes remain: {legacy}"
        libs = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
        assert libs, "APK has no native engine"
        alignments = {n.split('/')[1]: 16384 if n.split('/')[1] in {"arm64-v8a", "x86_64"} else 4096 for n in libs}
        for abi in {n.split('/')[1] for n in libs}:
            packaged = {n.split('/')[2] for n in libs if n.split('/')[1] == abi}
            assert packaged == allowed, f"Unexpected {abi} libraries: {packaged}"
        for name in libs:
            needed = dependencies(apk.read(name))
            assert set(needed) <= allowed | system, f"Unexpected dependency in {name}: {needed}"
        assert not any(n.startswith("assets/usr/share/fcitx5/") for n in names), "Fcitx assets remain"
        descriptor = json.loads(apk.read("assets/descriptor.json"))
        for name, digest in descriptor["files"].items():
            if digest:
                assert f"assets/{name}" in names, f"Descriptor refers to missing asset: {name}"
    print(json.dumps({"apk": str(path), "native_libraries": libs, "fcitx_dependencies": [],
                      "legacy_backend_classes": legacy, "dex_files_checked": len(dex_files),
                      "page_size": max(alignments.values()), "required_alignment_by_abi": alignments}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    for path in parser.parse_args().apk:
        verify(path)
