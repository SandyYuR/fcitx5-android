#!/usr/bin/env python3
"""Check the exact ARM64 APK can run, before reporting any comparative timings."""
import argparse
import json
from pathlib import Path
import subprocess
import time


def adb(*args, check=True):
    return subprocess.run(['adb', *map(str, args)], check=check, text=True,
                          capture_output=True, timeout=120).stdout.strip()


p = argparse.ArgumentParser()
p.add_argument('--foxy', type=Path, required=True)
p.add_argument('--host', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
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
    ime = 'com.fxliang.foxy/com.fxliang.foxy.input.RimeInputMethodService'
    adb('shell', 'ime', 'enable', ime)
    adb('shell', 'ime', 'set', ime)
    adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
    adb('shell', 'am', 'start', '-n', 'io.github.sandyyur.inputbench/.BenchHost')
    # Observe actual native loading, not just the advertised ABI list.
    deadline = time.monotonic() + 120
    maps = ''
    while time.monotonic() < deadline:
        pid = adb('shell', 'pidof', 'com.fxliang.foxy', check=False)
        if pid:
            maps = adb('shell', 'cat', '/proc/'+pid.split()[0]+'/maps', check=False)
            if 'libfoxy.so' in maps:
                break
        time.sleep(1)
    (a.output/'foxy-maps.txt').write_text(maps)
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/foxy-ui.xml', check=False)
    (a.output/'foxy-ui.xml').write_text(adb('shell', 'cat', '/data/local/tmp/foxy-ui.xml', check=False))
    adb('shell', 'screencap', '-p', '/data/local/tmp/foxy-probe.png')
    adb('pull', '/data/local/tmp/foxy-probe.png', a.output/'foxy-probe.png')
    assert 'libfoxy.so' in maps, 'ARM64 library did not load; no timing comparison is valid'
    (a.output/'probe.json').write_text(json.dumps({'native_loaded': True,
        'native_bridge': adb('shell', 'getprop', 'ro.dalvik.vm.native.bridge'),
        'abi_list': adb('shell', 'getprop', 'ro.product.cpu.abilist'),
        'comparison_performed': False}, indent=2))
finally:
    (a.output/'logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime'))
