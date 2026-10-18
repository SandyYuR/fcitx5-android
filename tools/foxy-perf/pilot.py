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
    # Without -a Android lists only enabled IMEs, omitting a fresh installation.
    # Package-added registration is asynchronous on some system images.
    deadline = time.monotonic()+10
    ime = None
    while ime is None and time.monotonic()<deadline:
        methods = adb('shell', 'ime', 'list', '-a', '-s').splitlines()
        ime = next((i.strip() for i in methods if i.strip().startswith(package+'/')), None)
        if ime is None:
            time.sleep(.2)
    if ime is None:
        raise RuntimeError(f'{package}: service absent from all IMEs: {methods}')
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
    background = ['com.google.android.gms', 'com.google.android.setupwizard', 'com.google.android.googlequicksearchbox',
        'com.google.android.apps.maps', 'com.google.android.apps.messaging', 'com.google.android.calendar',
        'com.google.android.videos', 'com.google.android.apps.photos', 'com.google.android.gm',
        'com.google.android.ims', 'com.android.chrome', 'com.google.android.youtube']
    for package in background:
        adb('shell', 'pm', 'disable-user', '--user', '0', package, check=False)
    (a.output/'disabled-background-packages.json').write_text(json.dumps(background, indent=2))
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
    failures = []
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
            # Root adbd writes can retain storage_file labels on Android 11.
            # Restore normal external-media labels and app ownership before the
            # app reads its profile. Keep SELinux enforcing for both products.
            physical = target.replace('/sdcard/', '/data/media/0/', 1)
            uid_text = adb('shell', 'cmd', 'package', 'list', 'packages', '-U', package)
            uid = re.search(r'uid:(\d+)', uid_text).group(1)
            before = adb('shell', 'ls', '-ldZ', physical, physical+'/default.yaml')
            adb('shell', 'chown', '-R', uid+':'+uid, physical)
            adb('shell', 'restorecon', '-RF', physical)
            after = adb('shell', 'ls', '-ldZ', physical, physical+'/default.yaml')
            (out/'fixture-permissions.txt').write_text('BEFORE\n'+before+'\nAFTER\n'+after+'\n'+adb('shell', 'getenforce'))
            ime = selected_ime(package)
            adb('logcat', '-c')
            adb('shell', 'am', 'start', '-W', '-n', HOST+'/.BenchHost')
            # Recheck the selected service after the editor starts. Android may
            # report an invalid component as shell text with exit status zero.
            selected_ime(package)
            deadline = time.monotonic() + 240
            maps = ''
            initialized = maintenance = deployed = ready = False
            while time.monotonic() < deadline:
                pid = adb('shell', 'pidof', package, check=False)
                if pid:
                    maps = adb('shell', 'cat', '/proc/'+pid.split()[0]+'/maps', check=False)
                logs = adb('logcat', '-d', '-t', '1200')
                if label == 'foxy':
                    initialized |= 'Rime initialized in' in logs
                    maintenance |= 'maintenanceStarted=true' in logs or 'type=deploy value=start' in logs
                    deployed |= 'type=deploy value=success' in logs
                    if 'type=deploy value=failure' in logs:
                        raise RuntimeError('Foxy deployment failed; see last-logcat.txt')
                    ready = initialized and (not maintenance or deployed)
                else:
                    ready = 'ready=true' in logs and 'RimeLifecycle' in logs
                if ready:
                    break
                time.sleep(1)
            assert ready, label+': deployment/readiness timed out'
            # Initialization completion can precede the controller recreating
            # its editor session after maintenance. Rebind both products during
            # setup, then require the actual grammar mapping before calibration.
            adb('shell', 'am', 'force-stop', HOST)
            adb('shell', 'am', 'start', '-W', '-n', HOST+'/.BenchHost')
            deadline = time.monotonic()+180
            while time.monotonic()<deadline:
                pid = adb('shell', 'pidof', package, check=False)
                if pid:
                    maps = adb('shell', 'cat', '/proc/'+pid.split()[0]+'/maps', check=False)
                    session_logs = adb('logcat', '-d', '--pid='+pid.split()[0])
                    # Grammar is mapped midway through component construction.
                    # Foxy sets its session options only after create_session
                    # returns; tips/replacer database construction can be slow.
                    session_ready = ('type=option value=soft_cursor' in session_logs if label == 'foxy' else
                                     re.search(r'RimeLifecycle: bind .*ready=true', session_logs) is not None)
                    if '.gram' in maps and session_ready:
                        break
                time.sleep(.5)
            capture(out, 'keyboard')
            (out/'input-method.txt').write_text(adb('shell', 'dumpsys', 'input_method'))
            (out/'package.txt').write_text(adb('shell', 'dumpsys', 'package', package))
            assert maps, label+': IME process did not start'
            if label == 'foxy':
                # Calibrated from the original 320x640 keyboard screenshot.
                # Foxy's adb virtual-hardware path can bypass its Rime session;
                # use its actual soft keys, as the timed driver will do.
                assert adb('shell', 'wm', 'size').endswith('320x640')
                adb('shell', 'input', 'tap', '224', '574')
                time.sleep(.1)
                adb('shell', 'input', 'tap', '240', '479')
            else:
                adb('shell', 'input', 'text', 'ni')
            time.sleep(2)
            capture(out, 'candidates')
            if label == 'foxy':
                adb('shell', 'input', 'tap', '160', '617')
            else:
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
            actual = json.loads(editor)['text']
            checks = {'chinese_commit':actual == '你', 'model_mapped':'.gram' in maps,
                      'arm64_bridge_mapped':'libndk_translation.so' in maps}
            if not all(checks.values()):
                failures.append({'product':label, 'checks':checks, 'actual_text':actual})
            provenance['products'][label] = {'package':package, 'ime':ime, 'profile':target,
                'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'actual_text':actual, **checks}
            print(label+' calibration '+('passed' if all(checks.values()) else 'FAILED'), flush=True)
    finally:
        shutil.rmtree(raw)
        (a.output/'provenance.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2))
        (a.output/'last-logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime'))
    if failures:
        raise SystemExit(json.dumps(failures, ensure_ascii=False))


if __name__ == '__main__':
    main()
