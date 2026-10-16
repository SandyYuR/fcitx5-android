#!/usr/bin/env python3
"""Compare direct frontend APKs on a disposable, root-capable AOSP emulator."""
import argparse
import hashlib
import json
import subprocess
import threading
import time
import io
import tarfile
from pathlib import Path

def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("before", type=Path)
parser.add_argument("after", type=Path)
parser.add_argument("tests", type=Path, help="Full frontend androidTest APK")
parser.add_argument("--wanxiang", type=Path, help="ZIP containing the scheme and its LTS model")
parser.add_argument("--output", type=Path, default=Path("build/input-perf-comparison"))
parser.add_argument("--adb", default="adb")
args = parser.parse_args()
adb = args.adb
artifacts = args.output
artifacts.mkdir(parents=True, exist_ok=True)
package = 'io.github.sandyyur.rime.direct'
baseline, optimized, test = args.before, args.after, args.tests
for path in [baseline, optimized, test] + ([args.wanxiang] if args.wanxiang else []):
    if not path.is_file(): parser.error(str(path) + " is not a file")

def command(*args, timeout=300, check=True):
    return subprocess.run([adb, *args], capture_output=True, text=True, timeout=timeout, check=check)

deadline = time.monotonic() + 600
while time.monotonic() < deadline:
    result = command('shell', 'getprop', 'sys.boot_completed', timeout=10, check=False)
    if result.returncode == 0 and result.stdout.strip() == '1':
        break
    time.sleep(4)
else:
    raise RuntimeError('Emulator did not finish booting')
serial = command('get-serialno').stdout.strip()
if not serial.startswith('emulator-'):
    raise SystemExit('Use a disposable emulator; this tool resets its test profile registrations.')
command('root')
command('wait-for-device')
if command('shell', 'id', '-u').stdout.strip() != '0':
    raise SystemExit('Use a root-capable AOSP emulator.')
print('Emulator ready. Stop host compilers before collecting timing results.', flush=True)
command('install', '-r', str(baseline))
command('install', '-r', str(test))
if args.wanxiang:
    source_hash = checksum(args.wanxiang)
    existing = command('shell', 'run-as', package, 'sha256sum', 'files/wanxiang-perf.zip', check=False)
    if not existing.stdout.startswith(source_hash):
        command('push', str(args.wanxiang), '/data/local/tmp/direct-wanxiang-perf.zip')
        command('shell', "cat /data/local/tmp/direct-wanxiang-perf.zip | run-as io.github.sandyyur.rime.direct sh -c 'cat > files/wanxiang-perf.zip'")
        command('shell', 'rm', '/data/local/tmp/direct-wanxiang-perf.zip')
# A valid restoration profile makes each replay independent of other test runs.
command('shell', 'am', 'force-stop', package)
base = '/storage/emulated/0/Android/data/' + package + '/files'
command('shell', 'mkdir', '-p', base + '/data/perf-home')
payload = io.BytesIO()
with tarfile.open(fileobj=payload, mode='w') as tar:
    for path in (Path(__file__).resolve().parents[2] / 'direct-rime/src/main/assets/smoke').iterdir():
        tar.add(path, arcname=path.name)
subprocess.run([adb,'shell','tar','xf','-','-C',base+'/data/perf-home'],input=payload.getvalue(),check=True,timeout=60)
subprocess.run([adb,'shell',"cat > " + base + "/config/conf/rime-data-dir.conf"],
               input=b'Current=perf-home\nProfiles=perf-home\n',check=True,timeout=60)
print('Isolated replay profiles prepared.', flush=True)

def replay(label, apk, full):
    print('Starting ' + label, flush=True)
    args = [adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'org.fcitx.fcitx5.android.core.DirectRimeFrontendTest#replayPerformanceWorkload']
    if full:
        args += ['-e', 'fixtureArchive', 'wanxiang-perf.zip']
    args += [package + '.test/androidx.test.runner.AndroidJUnitRunner']
    stop = threading.Event()
    model_mapped = []
    def inspect_model():
        while not stop.wait(10):
            try:
                pid = command('shell', 'pidof', package, timeout=10, check=False).stdout.strip()
                if not pid.isdigit(): continue
                maps = command('shell', 'cat', '/proc/' + pid + '/maps', timeout=10, check=False)
                if 'wanxiang-lts-zh-hans.gram' in maps.stdout:
                    (artifacts / (label + '-maps.txt')).write_text(maps.stdout)
                    model_mapped.append(True)
                    print(label + ': LTS model memory mapping verified.', flush=True)
                    return
            except subprocess.TimeoutExpired:
                pass
    monitor = threading.Thread(target=inspect_model, daemon=True)
    if full: monitor.start()
    log_path = artifacts / (label + '-instrumentation.txt')
    try:
        with log_path.open('w') as log:
            run = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, timeout=2400)
        output = log_path.read_text()
        if run.returncode != 0 or 'OK (1 test)' not in output:
            raise RuntimeError(label + ' failed; see ' + str(log_path))
    finally:
        stop.set()
        if full: monitor.join(timeout=15)
    result = json.loads(command('shell', 'run-as', package, 'cat', 'files/direct-rime-replay.json').stdout)
    result['apk_sha256'] = checksum(apk)
    result['model_mapped'] = (result.get('model_mapped', False) or bool(model_mapped)) if full else None
    result['fixture_sha256'] = source_hash if full else None
    (artifacts / (label + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({k:v for k,v in result.items() if k not in ('commits',)},ensure_ascii=False), flush=True)
    return result

before_smoke = replay('direct-stable-before-smoke', baseline, False)
before_wanxiang = replay('direct-stable-before-wanxiang', baseline, True) if args.wanxiang else None
command('install', '-r', str(optimized))
after_wanxiang = replay('direct-stable-after-wanxiang', optimized, True) if args.wanxiang else None
after_smoke = replay('direct-stable-after-smoke', optimized, False)
for name,before,after in [('smoke',before_smoke,after_smoke),('wanxiang',before_wanxiang,after_wanxiang)]:
    if before is None: continue
    assert before['keys'] == after['keys'] and before['commits'] == after['commits'], name + ' replay changed output'
    assert before['events']['CommitStringEvent'] == after['events']['CommitStringEvent'] == 100
print('Replays kept identical commits and key counts. Timings exclude display latency.', flush=True)
