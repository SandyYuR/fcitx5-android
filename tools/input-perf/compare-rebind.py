#!/usr/bin/env python3
"""Six immediate typing/selection rounds, before and after, on one disposable emulator."""
import argparse
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apks', type=Path, required=True)
parser.add_argument('--fixture', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
package = 'io.github.sandyyur.rime.direct'

def adb(*parts, check=True, timeout=900):
    return subprocess.run(['adb', *map(str, parts)], text=True, capture_output=True, check=check, timeout=timeout)

assert adb('get-serialno').stdout.strip().startswith('emulator-'), 'Use a disposable emulator'
adb('shell', 'setprop', 'debug.hwui.drawing_enabled', 'true')
adb('push', args.fixture, '/data/local/tmp/rebind-fixture.zip')
results = {}
for label in ('before', 'after'):
    print('Testing ' + label, flush=True)
    adb('install', '-r', args.apks / (label + '.apk'))
    adb('install', '-r', args.apks / (label + '-tests.apk'))
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'pm', 'clear', package)
    adb('shell', 'run-as', package, 'mkdir', '-p', 'files')
    adb('shell', "cat /data/local/tmp/rebind-fixture.zip | run-as io.github.sandyyur.rime.direct sh -c 'cat > files/rebind-fixture.zip'")
    adb('logcat', '-c')
    run = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
              'org.fcitx.fcitx5.android.core.DirectRimeFrontendTest#immediateTypingAndCandidateTapAfterRebind',
              '-e', 'fixtureArchive', 'rebind-fixture.zip', '-e', 'waitMs', '60000',
              package + '.test/androidx.test.runner.AndroidJUnitRunner', check=False)
    (args.output / (label + '-instrumentation.txt')).write_text(run.stdout + run.stderr)
    (args.output / (label + '-logcat.txt')).write_text(adb('logcat', '-d', '-v', 'threadtime').stdout)
    passed = run.returncode == 0 and 'OK (1 test)' in run.stdout
    result = {'passed': passed, 'commit': (args.apks / (label + '-commit.txt')).read_text().strip()}
    data = adb('shell', 'run-as', package, 'cat', 'files/direct-rime-rebind.json', check=False)
    if data.returncode == 0:
        result.update(json.loads(data.stdout))
    results[label] = result
    (args.output / (label + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False), flush=True)
(args.output / 'comparison.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
assert results['after']['passed'], 'New product must pass immediate first-key and candidate-tap regression'
assert results['before']['passed'], 'Baseline did not complete; inspect logs before comparing timings'
