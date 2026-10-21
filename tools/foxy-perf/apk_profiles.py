#!/usr/bin/env python3
"""Audit ART 010 baseline methods against the APK's actual DEX IDs and CRCs."""
import json
from pathlib import Path
import struct
import sys
import zipfile
import zlib


def method_names(dex):
    strings = []
    count, start = struct.unpack_from('<II', dex, 56)
    for i in range(count):
        offset = struct.unpack_from('<I', dex, start + i * 4)[0]
        while dex[offset] & 128:
            offset += 1
        offset += 1
        strings.append(dex[offset:dex.index(b'\0', offset)].decode('utf-8', 'replace'))
    count, start = struct.unpack_from('<II', dex, 64)
    types = [strings[struct.unpack_from('<I', dex, start + 4 * i)[0]] for i in range(count)]
    count, start = struct.unpack_from('<II', dex, 88)
    result = []
    for i in range(count):
        cls, _, name = struct.unpack_from('<HHI', dex, start + 8 * i)
        result.append(types[cls] + '->' + strings[name])
    return result


def inspect(path):
    with zipfile.ZipFile(path) as apk:
        profile = apk.read('assets/dexopt/baseline.prof')
        assert profile[:8] == b'pro\x00010\x00'
        count = profile[8]
        size, compressed = struct.unpack_from('<II', profile, 9)
        assert compressed == len(profile) - 17
        body = zlib.decompress(profile[17:])
        assert len(body) == size
        pos, headers = 0, []
        for _ in range(count):
            keys, classes, hot_size, checksum, methods = struct.unpack_from('<HHIII', body, pos)
            pos += 16
            key = body[pos:pos+keys].decode(); pos += keys
            dex_name = key.replace(':', '!').split('!')[-1]
            if not dex_name.endswith('.dex'):
                dex_name = 'classes.dex'
            assert apk.getinfo(dex_name).CRC == checksum, (key, checksum)
            names = method_names(apk.read(dex_name))
            assert len(names) == methods
            headers.append((key, classes, hot_size, methods, names))
        result = []
        for key, classes, hot_size, methods, names in headers:
            selected, method_id, end = set(), 0, pos + hot_size
            while pos < end:
                delta, caches = struct.unpack_from('<HH', body, pos); pos += 4
                method_id += delta; selected.add(method_id)
                for _ in range(caches):
                    _, maps = struct.unpack_from('<HB', body, pos); pos += 3
                    if maps not in (6, 7):
                        for _ in range(maps):
                            _, entries = body[pos:pos+2]
                            pos += 2 + 2 * entries
            assert pos == end
            pos += 2 * classes
            bits = body[pos:pos+(methods*2+7)//8]; pos += len(bits)
            for bit in range(methods*2):
                if bits[bit//8] & (1 << (bit % 8)):
                    selected.add(bit % methods)
            app_methods = [names[i] for i in sorted(selected) if names[i].startswith((
                'Lorg/fcitx/directrime/', 'Lorg/fcitx/fcitx5/android/', 'Lcom/fxliang/foxy/',
                'Lat0;', 'Llq0;', 'Lmq0;', 'Lpt0;', 'Lj10;', 'Lgf;'))]
            result.append({'key':key, 'checksum_matches':True, 'dex_method_count':methods,
                'profile_method_count':len(selected), 'app_methods':app_methods})
        assert pos == len(body)
        return {'apk':Path(path).name, 'format':'010', 'dex_entries':result}


if __name__ == '__main__':
    print(json.dumps([inspect(p) for p in sys.argv[1:]], ensure_ascii=False, indent=2))
