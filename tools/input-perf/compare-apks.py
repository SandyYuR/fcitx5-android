#!/usr/bin/env python3
"""Compare direct frontend APKs on a disposable Android emulator."""
import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path
from legacy_perf import Device, apk_pair, device_arguments, find_aapt

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
parser.add_argument("--after-tests", type=Path, help="Matching after-product androidTest APK when its target differs")
device_arguments(parser)
args = parser.parse_args()
device = Device(args)
artifacts = args.output
artifacts.mkdir(parents=True, exist_ok=True)
baseline, optimized, test = args.before, args.after, args.tests
for path in [baseline, optimized, test] + ([args.wanxiang] if args.wanxiang else []):
    if not path.is_file(): parser.error(str(path) + " is not a file")

command = device
aapt = find_aapt()
identities = {
    "before": apk_pair(baseline, test, aapt),
    "after": apk_pair(optimized, args.after_tests or test, aapt),
}

deadline = time.monotonic() + 600
while time.monotonic() < deadline:
    result = command('shell', 'getprop', 'sys.boot_completed', timeout=10, check=False)
    if result.returncode == 0 and result.stdout.strip() == '1':
        break
    time.sleep(4)
else:
    raise RuntimeError('Emulator did not finish booting')
print('Emulator ready. Stop host compilers before collecting timing results.', flush=True)
source_hash = checksum(args.wanxiang) if args.wanxiang else None

def stage(label, apk, tests):
    package = identities[label]['package']
    command('install', '-r', str(apk))
    command('install', '-r', str(tests))
    command('shell', 'run-as', package, 'mkdir', '-p', 'files')
    if args.wanxiang:
        existing = command('shell', 'run-as', package, 'sha256sum', 'files/wanxiang-perf.zip', check=False)
        if not existing.stdout.startswith(source_hash):
            command('push', str(args.wanxiang), '/data/local/tmp/direct-wanxiang-perf.zip')
            command('shell', f"cat /data/local/tmp/direct-wanxiang-perf.zip | run-as {package} sh -c 'cat > files/wanxiang-perf.zip'")
            command('shell', 'rm', '/data/local/tmp/direct-wanxiang-perf.zip')

stage('before', baseline, test)
print('Replay archives staged; profile creation stays inside the test app.', flush=True)

def replay(label, apk, full, product):
    package = identities[product]['package']
    print('Starting ' + label, flush=True)
    args = device.command('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'org.fcitx.fcitx5.android.core.DirectRimeFrontendTest#replayPerformanceWorkload')
    if full:
        args += ['-e', 'fixtureArchive', 'wanxiang-perf.zip']
    args += [identities[product]['instrumentation']]
    log_path = artifacts / (label + '-instrumentation.txt')
    with log_path.open('w') as log:
        run = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, timeout=2400)
    output = log_path.read_text()
    if run.returncode != 0 or 'OK (1 test)' not in output:
        raise RuntimeError(label + ' failed; see ' + str(log_path))
    result = json.loads(command('shell', 'run-as', package, 'cat', 'files/direct-rime-replay.json').stdout)
    result['apk_sha256'] = checksum(apk)
    result['apk_identity'] = identities[product]
    result['model_mapped'] = result.get('model_mapped', False) if full else None
    result['fixture_sha256'] = source_hash if full else None
    (artifacts / (label + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({k:v for k,v in result.items() if k not in ('commits',)},ensure_ascii=False), flush=True)
    return result

before_smoke = replay('direct-stable-before-smoke', baseline, False, 'before')
before_wanxiang = replay('direct-stable-before-wanxiang', baseline, True, 'before') if args.wanxiang else None
stage('after', optimized, args.after_tests or test)
after_wanxiang = replay('direct-stable-after-wanxiang', optimized, True, 'after') if args.wanxiang else None
after_smoke = replay('direct-stable-after-smoke', optimized, False, 'after')
for name,before,after in [('smoke',before_smoke,after_smoke),('wanxiang',before_wanxiang,after_wanxiang)]:
    if before is None: continue
    assert before['keys'] == after['keys'] and before['commits'] == after['commits'], name + ' replay changed output'
    assert before['events']['CommitStringEvent'] == after['events']['CommitStringEvent'] == 100
print('Replays kept identical commits and key counts. Timings exclude display latency.', flush=True)
