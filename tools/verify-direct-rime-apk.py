#!/usr/bin/env python3
"""Fail if the full direct frontend packages or links a Fcitx component."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path


def verify_clipboard_rules(apk, descriptor):
    """The built-in filter must ship its pinned data and notices in every variant."""
    commit = "11086f40512774dcadef54079f1ba023bfacf940"
    expected = {
        "data.min.json": "b328d2a5b95b1b23bac2c91342a8deee3f9a3826a5950bf9e28e332ffd94cd2f",
        "LICENSE.LGPL-3.0.txt": "da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768",
        "COPYING.GPL-3.0.txt": "fb981668c18a279e285fc4d83fba1e836cc84dd4daa73c9697d3cfd2d8aca6e0",
        "NOTICE.md": None,
        "source.json": None,
    }
    payloads = {}
    for name, digest in expected.items():
        path = f"assets/clearurls/{name}"
        assert path in apk.namelist(), f"Missing bundled ClearURLs asset: {name}"
        payloads[name] = apk.read(path)
        if digest:
            assert hashlib.sha256(payloads[name]).hexdigest() == digest, f"Unexpected ClearURLs asset bytes: {name}"
    source = json.loads(payloads["source.json"])
    assert source["commit"] == commit, "ClearURLs source revision does not match the pinned catalog"
    assert source["data_sha256"] == expected["data.min.json"], "ClearURLs source checksum mismatch"
    providers = len(json.loads(payloads["data.min.json"])["providers"])
    assert providers == source["providers"] == 206, "ClearURLs provider count mismatch"
    assert not any(name == "clearurls" or name.startswith("clearurls/")
                   for name in descriptor["files"]), "Host ClearURLs assets must not be copied into Rime data"
    return {"commit": commit, "sha256": expected["data.min.json"], "providers": providers}


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
        obsolete = ("Lorg/fcitx/fcitx5/android/core/Fcitx",
                    "Lorg/fcitx/fcitx5/android/core/FcitxDispatcher",
                    "Lorg/fcitx/fcitx5/android/core/FcitxUtils")
        classes = [name for dex in dex_files for name in dex_classes(apk.read(dex))]
        legacy = sorted(name for name in classes
                        if any(name == prefix + ";" or name.startswith(prefix + "$") for prefix in obsolete))
        assert not legacy, f"Retired backend or helper classes remain: {legacy}"
        unused_picker = sorted(name for name in classes if name.startswith("Lcom/luck/picture/"))
        assert not unused_picker, f"Unused PictureSelector components remain: {unused_picker}"
        libs = sorted(n for n in names if n.startswith("lib/") and n.endswith(".so"))
        assert libs, "APK has no native engine"
        assert not any(n.endswith(".so") and not n.startswith("lib/") for n in names), "Native libraries must not be hidden in assets"
        alignments = {n.split('/')[1]: 16384 if n.split('/')[1] in {"arm64-v8a", "x86_64"} else 4096 for n in libs}
        for abi in {n.split('/')[1] for n in libs}:
            packaged = {n.split('/')[2] for n in libs if n.split('/')[1] == abi}
            assert packaged == allowed, f"Unexpected {abi} libraries: {packaged}"
        native_dependencies = {}
        for name in libs:
            needed = dependencies(apk.read(name))
            native_dependencies[name] = needed
            assert set(needed) <= allowed | system, f"Unexpected dependency in {name}: {needed}"
        assert not any(n.startswith("assets/usr/share/fcitx5/") for n in names), "Fcitx assets remain"
        assert not any(n.startswith("assets/usr/lib/fcitx5/") for n in names), "Fcitx addon assets remain"
        assert not any(n.endswith(".a") for n in names), "Static build archives must not be packaged"
        descriptor = json.loads(apk.read("assets/descriptor.json"))
        clipboard_rules = verify_clipboard_rules(apk, descriptor)
        for name, digest in descriptor["files"].items():
            if digest:
                assert f"assets/{name}" in names, f"Descriptor refers to missing asset: {name}"
    print(json.dumps({"apk": str(path), "native_libraries": libs, "fcitx_dependencies": [],
                      "apk_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                      "native_dependencies": native_dependencies, "obsolete_picker_classes": unused_picker,
                      "legacy_backend_classes": legacy, "dex_files_checked": len(dex_files),
                      "clearurls": clipboard_rules,
                      "page_size": max(alignments.values()), "required_alignment_by_abi": alignments}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    for path in parser.parse_args().apk:
        verify(path)
