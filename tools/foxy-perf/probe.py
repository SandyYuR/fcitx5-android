#!/usr/bin/env python3
"""Check the exact ARM64 APK can run, before reporting any comparative timings."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import zipfile


def adb(*args, check=True):
    return subprocess.run(['adb', *map(str, args)], check=check, text=True,
                          capture_output=True, timeout=120).stdout.strip()


p = argparse.ArgumentParser()
p.add_argument('--foxy', type=Path, required=True)
p.add_argument('--host', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--fixture', type=Path)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
assert adb('get-serialno').startswith('emulator-'), 'Disposable emulator only'
(a.output/'properties.txt').write_text(adb('shell', 'getprop'))
adb('root', check=False)
adb('wait-for-device')
adb('logcat', '-c')
try:
    adb('install', '-r', a.foxy)
    adb('install', '-r', a.host)
    available = adb('shell', 'ime', 'list', '-s')
    (a.output/'ime-list.txt').write_text(available)
    # Android's ID uses a shortened component for Foxy, whose Java namespace
    # matches its package. Never guess that ID from the manifest class name.
    ime = next(x for x in available.splitlines() if x.startswith('com.fxliang.foxy/'))
    (a.output/'ime-selection.txt').write_text(adb('shell', 'ime', 'enable', ime)+'\n'+adb('shell', 'ime', 'set', ime))
    assert adb('shell', 'settings', 'get', 'secure', 'default_input_method') == ime
    if a.fixture:
        fixture_dir = a.output/'fixture-source'
        fixture_dir.mkdir(exist_ok=True)
        with zipfile.ZipFile(a.fixture) as archive:
            for entry in archive.infolist():
                assert not Path(entry.filename).is_absolute() and '..' not in Path(entry.filename).parts
            archive.extractall(fixture_dir)
        target = '/sdcard/Android/data/com.fxliang.foxy/files/foxy/rime/default'
        adb('shell', 'mkdir', '-p', target)
        adb('push', str(fixture_dir)+'/.', target)
        # Runtime fixture is an upstream model, not a report artifact.
        import shutil
        shutil.rmtree(fixture_dir)
    adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
    adb('shell', 'am', 'start', '-n', 'io.github.sandyyur.inputbench/.BenchHost')
    # Observe actual native loading, not just the advertised ABI list.
    deadline = time.monotonic() + 120
    maps = ''
    while time.monotonic() < deadline:
        pid = adb('shell', 'pidof', 'com.fxliang.foxy', check=False)
        if pid:
            maps = adb('shell', 'cat', '/proc/'+pid.split()[0]+'/maps', check=False)
            if 'libfoxy.so' in maps or ('libndk_translation.so' in maps and 'com.fxliang.foxy-' in maps):
                break
        time.sleep(1)
    (a.output/'foxy-maps.txt').write_text(maps)
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/foxy-ui.xml', check=False)
    (a.output/'foxy-ui.xml').write_text(adb('shell', 'cat', '/data/local/tmp/foxy-ui.xml', check=False))
    adb('shell', 'screencap', '-p', '/data/local/tmp/foxy-probe.png')
    adb('pull', '/data/local/tmp/foxy-probe.png', a.output/'foxy-probe.png')
    (a.output/'input-method.txt').write_text(adb('shell', 'dumpsys', 'input_method'))
    assert maps, 'Foxy process did not start; no timing comparison is valid'
    (a.output/'probe.json').write_text(json.dumps({'process_started': True, 'selected_ime': ime,
        'native_library_name_in_maps': 'libfoxy.so' in maps,
        'native_bridge_in_maps': 'libndk_translation.so' in maps,
        'native_bridge': adb('shell', 'getprop', 'ro.dalvik.vm.native.bridge'),
        'abi_list': adb('shell', 'getprop', 'ro.product.cpu.abilist'),
        'comparison_performed': False}, indent=2))
finally:
    (a.output/'logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime'))
