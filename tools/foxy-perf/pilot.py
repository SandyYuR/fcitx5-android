#!/usr/bin/env python3
"""Prepare both original ARM64 Release APKs with the same deployed Wanxiang fixture.

Calibration only: keyboard screenshots, actual model mappings and Chinese commit.
No latency statistics from these setup/diagnostic runs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess as sp
import time
import zipfile

HOST = 'io.github.sandyyur.inputbench'


def adb(*args, check=True, timeout=300):
    r = sp.run(['adb', *map(str, args)], text=True, capture_output=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(f'adb {args[:3]}: {r.stdout} {r.stderr}')
    return r.stdout.strip()


def selected_ime(package):
    methods = adb('shell', 'ime', 'list', '-s').splitlines()
    ime = next(i for i in methods if i.startswith(package+'/'))
    adb('shell', 'ime', 'enable', ime)
    response = adb('shell', 'ime', 'set', ime)
    assert adb('shell', 'settings', 'get', 'secure', 'default_input_method') == ime, response
    return ime


def capture(out, name):
    adb('shell', 'screencap', '-p', '/data/local/tmp/perf-image.png')
    adb('pull', '/data/local/tmp/perf-image.png', out/(name+'.png'))
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/perf-ui.xml', check=False)
    (out/(name+'.xml')).write_text(adb('shell', 'cat', '/data/local/tmp/perf-ui.xml', check=False))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ['foxy', 'direct', 'host', 'fixture', 'output']:
        p.add_argument('--'+key, type=Path, required=True)
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    assert adb('get-serialno').startswith('emulator-'), 'Disposable emulator only'
    adb('root', check=False)
    adb('wait-for-device')
    for namespace, key, value in [('global', 'device_provisioned', '1'), ('secure', 'user_setup_complete', '1'),
        ('secure', 'show_ime_with_hard_keyboard', '1'), ('system', 'screen_off_timeout', '1800000')]:
        adb('shell', 'settings', 'put', namespace, key, value)
    # A Google system image supplies the ARM64 native bridge. Stop unrelated
    # background setup/sync work on this disposable device for both products.
    for package in ['com.google.android.gms', 'com.google.android.setupwizard', 'com.google.android.googlequicksearchbox']:
        adb('shell', 'pm', 'disable-user', '--user', '0', package, check=False)
    (a.output/'properties.txt').write_text(adb('shell', 'getprop'))
    assert 'arm64-v8a' in adb('shell', 'getprop', 'ro.product.cpu.abilist')
    adb('install', '-r', a.host)
    raw = a.output.parent/'wanxiang-unpacked'
    raw.mkdir(exist_ok=True)
    with zipfile.ZipFile(a.fixture) as archive:
        assert all(not Path(i.filename).is_absolute() and '..' not in Path(i.filename).parts for i in archive.infolist())
        archive.extractall(raw)
    provenance = {'fixture_sha256': hashlib.sha256(a.fixture.read_bytes()).hexdigest(),
        'native_bridge': adb('shell', 'getprop', 'ro.dalvik.vm.native.bridge'), 'products': {}}
    try:
        for label, apk, package, relative in [
            ('foxy', a.foxy, 'com.fxliang.foxy', 'foxy/rime/default'),
            ('direct', a.direct, 'io.github.sandyyur.rime.direct', 'data/rime')]:
            out = a.output/label
            out.mkdir(exist_ok=True)
            for pkg in ['com.fxliang.foxy', 'io.github.sandyyur.rime.direct', HOST]:
                adb('shell', 'am', 'force-stop', pkg, check=False)
            adb('install', '-r', apk)
            target = f'/sdcard/Android/data/{package}/files/{relative}'
            adb('shell', 'mkdir', '-p', target)
            adb('push', str(raw)+'/.', target)
            ime = selected_ime(package)
            adb('logcat', '-c')
            adb('shell', 'am', 'start', '-W', '-n', HOST+'/.BenchHost')
            # Recheck the selected service after the editor starts. Android may
            # report an invalid component as shell text with exit status zero.
            selected_ime(package)
            deadline = time.monotonic() + 240
            maps = ''
            while time.monotonic() < deadline:
                pid = adb('shell', 'pidof', package, check=False)
                if pid:
                    maps = adb('shell', 'cat', '/proc/'+pid.split()[0]+'/maps', check=False)
                logs = adb('logcat', '-d', '-t', '1200')
                if 'FATAL EXCEPTION' in logs:
                    break
                if ((label == 'foxy' and 'Rime initialized in' in logs) or
                    (label == 'direct' and 'ready=true' in logs and 'RimeLifecycle' in logs)):
                    break
                time.sleep(1)
            capture(out, 'keyboard')
            (out/'input-method.txt').write_text(adb('shell', 'dumpsys', 'input_method'))
            (out/'package.txt').write_text(adb('shell', 'dumpsys', 'package', package))
            assert maps, label+': IME process did not start'
            adb('shell', 'input', 'text', 'ni')
            time.sleep(2)
            capture(out, 'candidates')
            adb('shell', 'input', 'keyevent', '62')
            time.sleep(1)
            adb('shell', 'am', 'broadcast', '-n', HOST+'/.BenchControl', '--es', 'command', 'dump')
            editor = adb('shell', 'run-as', HOST, 'cat', 'files/candidate-editor.json')
            (out/'editor.json').write_text(editor)
            pid = adb('shell', 'pidof', package)
            maps = adb('shell', 'cat', '/proc/'+pid.split()[0]+'/maps')
            (out/'maps.txt').write_text(maps)
            (out/'memory.txt').write_text(adb('shell', 'dumpsys', 'meminfo', package))
            logs = adb('logcat', '-d', '-v', 'threadtime')
            (out/'logcat.txt').write_text(logs)
            (out/'effective-schema.yaml').write_text(adb('shell', 'cat', target+'/build/wanxiang.schema.yaml', check=False))
            (out/'runtime-user.yaml').write_text(adb('shell', 'cat', target+'/user.yaml'))
            (out/'mutable-db-files.txt').write_text(adb('shell', 'find', target, '-name', 'CURRENT'))
            assert json.loads(editor)['text'] == '你', label+': Chinese commit failed'
            assert '.gram' in maps, label+': model was not actually mapped'
            assert 'libndk_translation.so' in maps, label+': expected ARM64 bridge missing'
            provenance['products'][label] = {'package':package, 'ime':ime, 'profile':target,
                'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'chinese_commit':'你',
                'model_mapped':True, 'arm64_bridge_mapped':True}
            print(label+' calibration passed', flush=True)
    finally:
        shutil.rmtree(raw)
        (a.output/'provenance.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2))
        (a.output/'last-logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime'))


if __name__ == '__main__':
    main()
