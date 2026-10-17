#!/usr/bin/env python3
"""Run only on a disposable KVM emulator. Both product APKs remain unmodified."""
import argparse
import json
import os
import re
import subprocess as sp
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
HOST = 'io.github.sandyyur.inputbench'
# Whole phrases, each followed by a candidate-commit space; only full-pinyin keys.
PHRASES = ['jintian', 'tianqi', 'henhao', 'women', 'yiqi', 'shiyong', 'zhongwen', 'shurufa',
           'ceshi', 'xingneng', 'houxuan', 'fangan', 'qiehuan', 'shuru', 'zhongguo', 'nihao',
           'shenghuo', 'gongzuo', 'xuexi', 'kuaile', 'shijie', 'pengyou', 'shijian', 'wenti',
           'fangfa', 'jiandan', 'qingchu', 'wending', 'sudu', 'zhongzhou']
TEXT = ' '.join(PHRASES) + ' '

def adb(*args, check=True, timeout=300):
    return sp.run(['adb', *map(str, args)], text=True, capture_output=True, check=check, timeout=timeout).stdout.strip()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apks', type=Path, required=True)
    p.add_argument('--fixture', type=Path, required=True)
    p.add_argument('--host', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--pilot', action='store_true')
    args = p.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    assert adb('get-serialno').startswith('emulator-'), 'Disposable emulator required'
    adb('shell', 'setprop', 'debug.hwui.drawing_enabled', 'true')
    for key, value in [('screen_off_timeout', '1800000'), ('accelerometer_rotation', '0'), ('user_rotation', '0'), ('show_touches', '0')]:
        adb('shell', 'settings', 'put', 'system', key, value)
    adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
    adb('shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
    adb('shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
    adb('shell', 'svc', 'power', 'stayon', 'true')
    for name, command in [('display.txt', ['dumpsys', 'display']), ('surfaceflinger.txt', ['dumpsys', 'SurfaceFlinger']), ('cpu.txt', ['cat', '/proc/cpuinfo']), ('properties.txt', ['getprop'])]:
        (out/name).write_text(adb('shell', *command))
    sdk = Path(os.environ['ANDROID_HOME'])
    aapt = sorted(sdk.glob('build-tools/*/aapt2'))[-1]
    packages = {}
    for backend in ('fcitx', 'direct'):
        apk = args.apks/f'{backend}.apk'
        badging = sp.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
        packages[backend] = re.search(r"package: name='([^']+)'", badging)[1]
        adb('install', '-r', apk)
        adb('install', '-r', args.apks/f'{backend}-tests.apk')
        package = packages[backend]
        adb('shell', 'run-as', package, 'mkdir', '-p', 'files')
    assert packages['fcitx'] != packages['direct']
    adb('install', '-r', args.host)
    adb('push', args.fixture, '/data/local/tmp/wanxiang-perf.zip')
    for package in packages.values():
        adb('shell', f"cat /data/local/tmp/wanxiang-perf.zip | run-as {package} sh -c 'cat > files/wanxiang-perf.zip'")
    adb('shell', 'rm', '/data/local/tmp/wanxiang-perf.zip')
    runs = [('groundtruth-1', 'hardware', 80, 1)]
    if args.pilot:
        runs += [('pilot', 'touch', 160, 1)]
    else:
        runs += [('groundtruth-2', 'hardware', 80, 2)]
        runs += [(f'steady-r{i}', 'touch', 160, 2) for i in range(1, 4)]
        runs += [('fast', 'touch', 120, 2), ('jitter', 'touch', 'jitter', 1)]
    summaries = []
    for index, (name, mode, cadence, repeats) in enumerate(runs):
        for backend in (('fcitx', 'direct') if index % 2 == 0 else ('direct', 'fcitx')):
            label = f'{name}-{backend}'; run = out/label; run.mkdir()
            package = packages[backend]
            print(f'START {label}', flush=True)
            for pkg in packages.values(): adb('shell', 'am', 'force-stop', pkg)
            adb('shell', 'am', 'force-stop', HOST)
            # Only the disposable benchmark profile is reset, never application/user data.
            profile = f'/sdcard/Android/data/{package}/files/data/screen-bench'
            adb('shell', f"if [ -d {profile} ]; then find {profile} -maxdepth 1 -name '*.userdb*' -exec rm -rf '{{}}' +; fi")
            adb('push', HERE/'config/user.yaml', '/data/local/tmp/screen-user.yaml')
            adb('shell', f'if [ -d {profile} ]; then cp /data/local/tmp/screen-user.yaml {profile}/user.yaml; fi')
            adb('shell', 'run-as', package, 'rm', '-f', 'files/screen-go', 'files/screen-ready.json', 'files/screen-result.json')
            text = TEXT*repeats if not args.pilot else (' '.join(PHRASES[:8])+' ')
            t, seed, steps = 0, 20240921, []
            for c in text:
                steps.append({'key': c, 'at_ms': t})
                seed = (1664525*seed+1013904223) & 0xffffffff
                t += 90+(seed % 141) if cadence == 'jitter' else cadence
            schedule = {'text': text, 'steps': steps, 'cadence_ms': cadence, 'hold_ms': 32, 'seed': 20240921}
            (run/'schedule.json').write_text(json.dumps(schedule, indent=2))
            adb('push', run/'schedule.json', '/data/local/tmp/screen-schedule.json')
            adb('shell', f"cat /data/local/tmp/screen-schedule.json | run-as {package} sh -c 'cat > files/screen-schedule.json'")
            log = (run/'instrumentation.txt').open('w')
            proc = sp.Popen(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                'org.fcitx.fcitx5.android.core.ScreenFixtureTest#replay', '-e', 'backend', backend,
                '-e', 'mode', mode, package+'.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=sp.STDOUT)
            recorder = None
            try:
                deadline = time.monotonic()+420
                while True:
                    ready = adb('shell', 'run-as', package, 'cat', 'files/screen-ready.json', check=False)
                    if ready.startswith('{'):
                        (run/'ready.json').write_text(ready); break
                    if proc.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('Fixture failed/timed out: '+label)
                    time.sleep(2)
                adb('shell', 'am', 'broadcast', '-n', HOST+'/.BenchControl', '--es', 'command', 'dump')
                (run/'host-before.json').write_text(adb('shell', 'run-as', HOST, 'cat', 'files/state.json'))
                (run/'effective-schema.yaml').write_text(adb('shell', 'run-as', package, 'cat', 'files/screen-effective-schema.yaml'))
                (run/'memory-before.txt').write_text(adb('shell', 'dumpsys', 'meminfo', package))
                adb('shell', 'screencap', '-p', '/data/local/tmp/screen-before.png')
                adb('pull', '/data/local/tmp/screen-before.png', run/'before.png')
                adb('shell', 'rm', '-f', '/sdcard/screen-bench.mp4')
                if mode == 'touch':
                    recorder = sp.Popen(['adb', 'shell', 'screenrecord --time-limit 180 --bit-rate 12000000 /sdcard/screen-bench.mp4'], stdout=(run/'screenrecord.txt').open('w'), stderr=sp.STDOUT)
                    time.sleep(2)
                    assert adb('shell', 'pidof', 'screenrecord', check=False), 'screenrecord failed to start'
                adb('shell', 'run-as', package, 'touch', 'files/screen-go')
                proc.wait(timeout=220)
                log.close()
                assert proc.returncode == 0 and 'OK (1 test)' in (run/'instrumentation.txt').read_text(), label
                (run/'result.json').write_text(adb('shell', 'run-as', package, 'cat', 'files/screen-result.json'))
                adb('shell', 'am', 'broadcast', '-n', HOST+'/.BenchControl', '--es', 'command', 'dump')
                host = json.loads(adb('shell', 'run-as', HOST, 'cat', 'files/state.json'))
                (run/'host-after.json').write_text(json.dumps(host, ensure_ascii=False, indent=2))
                (run/'memory-after.txt').write_text(adb('shell', 'dumpsys', 'meminfo', package))
                adb('shell', 'screencap', '-p', '/data/local/tmp/screen-after.png')
                adb('pull', '/data/local/tmp/screen-after.png', run/'after.png')
                # Steady/fast repeats share the same learning trajectory; compare equal-length references.
                reference = next((s['output'] for s in summaries if s['schedule_text'] == text), None)
                if mode == 'touch': assert reference is not None, 'Missing hardware reference'
                if reference is not None: assert host['text'] == reference, 'Input output differs: '+label
                assert any('\u3400' <= c <= '\u9fff' for c in host['text']), 'No Chinese committed'
                result = {'label': label, 'backend': backend, 'mode': mode, 'cadence_ms': cadence,
                          'keys': len(text), 'schedule_text': text, 'output': host['text'], 'correct': True}
                summaries.append(result)
                (out/'runs.json').write_text(json.dumps(summaries, ensure_ascii=False, indent=2))
                print('PASS '+label+' keys='+str(len(text))+' chars='+str(len(host['text'])), flush=True)
            finally:
                if recorder is not None:
                    adb('shell', 'pkill', '-INT', 'screenrecord', check=False)
                    try: recorder.wait(timeout=15)
                    except sp.TimeoutExpired: recorder.kill(); recorder.wait()
                    adb('pull', '/sdcard/screen-bench.mp4', run/'run.mp4', check=False)
                if proc.poll() is None: proc.terminate(); proc.wait(timeout=10)
                log.close()
                (run/'logcat.txt').write_text(adb('logcat', '-d', '-t', '1500'))
            if mode == 'touch':
                sp.run(['python3', str(HERE/'analyze.py'), str(run)], check=True)
    print('ALL RUNS COMPLETE', flush=True)

if __name__ == '__main__': main()
