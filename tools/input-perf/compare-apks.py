#!/usr/bin/env python3
"""Compare direct frontend APKs on a disposable Android emulator."""
import argparse
import hashlib
import json
import subprocess
import time
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
    raise SystemExit('Use a disposable emulator; the replay changes test IME settings.')
print('Emulator ready. Stop host compilers before collecting timing results.', flush=True)
command('install', '-r', str(baseline))
command('install', '-r', str(test))
command('shell', 'run-as', package, 'mkdir', '-p', 'files')
if args.wanxiang:
    source_hash = checksum(args.wanxiang)
    existing = command('shell', 'run-as', package, 'sha256sum', 'files/wanxiang-perf.zip', check=False)
    if not existing.stdout.startswith(source_hash):
        command('push', str(args.wanxiang), '/data/local/tmp/direct-wanxiang-perf.zip')
        command('shell', "cat /data/local/tmp/direct-wanxiang-perf.zip | run-as io.github.sandyyur.rime.direct sh -c 'cat > files/wanxiang-perf.zip'")
        command('shell', 'rm', '/data/local/tmp/direct-wanxiang-perf.zip')
print('Replay archives staged; profile creation stays inside the test app.', flush=True)

def replay(label, apk, full):
    print('Starting ' + label, flush=True)
    args = [adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'org.fcitx.fcitx5.android.core.DirectRimeFrontendTest#replayPerformanceWorkload']
    if full:
        args += ['-e', 'fixtureArchive', 'wanxiang-perf.zip']
    args += [package + '.test/androidx.test.runner.AndroidJUnitRunner']
    log_path = artifacts / (label + '-instrumentation.txt')
    with log_path.open('w') as log:
        run = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, timeout=2400)
    output = log_path.read_text()
    if run.returncode != 0 or 'OK (1 test)' not in output:
        raise RuntimeError(label + ' failed; see ' + str(log_path))
    result = json.loads(command('shell', 'run-as', package, 'cat', 'files/direct-rime-replay.json').stdout)
    result['apk_sha256'] = checksum(apk)
    result['model_mapped'] = result.get('model_mapped', False) if full else None
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
