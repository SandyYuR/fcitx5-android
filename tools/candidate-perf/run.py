#!/usr/bin/env python3
"""First candidate tap after fresh process, then five taps in the same editor."""
import argparse
import json
import os
import re
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apks', type=Path, required=True)
parser.add_argument('--fixture', type=Path, required=True)
parser.add_argument('--host', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--trials', type=int, default=3)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
host = 'io.github.sandyyur.inputbench'

def adb(*parts, check=True, timeout=900):
    return subprocess.run(['adb', *map(str, parts)], text=True, capture_output=True, check=check, timeout=timeout)

def instrument(package, method, revision):
    command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
              'org.fcitx.fcitx5.android.core.CandidateTapTest#' + method,
              '-e', 'expectedCommit', revision,
              package + '.test/androidx.test.runner.AndroidJUnitRunner']
    process = subprocess.Popen(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if method == 'replay':
        # force-stop (including am instrument's own restart) makes Android select
        # its fallback IME. Select our IME only AFTER the fresh test process starts.
        deadline = time.monotonic() + 30
        started = False
        while process.poll() is None and time.monotonic() < deadline:
            logs = adb('logcat', '-d', '-s', 'TestRunner:I', '*:S').stdout
            if 'started: replay(org.fcitx.fcitx5.android.core.CandidateTapTest)' in logs:
                started = True
                break
            time.sleep(0.1)
        if started:
            component = package + '/org.fcitx.fcitx5.android.input.FcitxInputMethodService'
            adb('shell', 'ime', 'enable', component)
            adb('shell', 'ime', 'set', component)
            assert adb('shell', 'settings', 'get', 'secure', 'default_input_method').stdout.strip() == component
    stdout, stderr = process.communicate(timeout=900)
    run = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    return run, run.returncode == 0 and 'OK (1 test)' in run.stdout

assert adb('get-serialno').stdout.strip().startswith('emulator-'), 'Disposable emulator required'
adb('shell', 'setprop', 'debug.hwui.drawing_enabled', 'true')
adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
adb('shell', 'settings', 'put', 'system', 'screen_off_timeout', '1800000')
adb('install', '-r', args.host)
adb('push', args.fixture, '/data/local/tmp/candidate-fixture.zip')
results = {}
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
aapt = sorted((sdk / 'build-tools').glob('*/aapt2'), key=lambda p: [int(x) for x in re.findall(r'\d+', p.parent.name)])[-1]
for label in ('fcitx', 'before', 'after'):
    if not (args.apks / (label + '.apk')).is_file():
        continue
    package = subprocess.check_output([str(aapt), 'dump', 'packagename', str(args.apks / (label + '.apk'))], text=True).strip()
    assert re.fullmatch(r'[a-zA-Z0-9_.]+', package), package
    revision = (args.apks / (label + '-commit.txt')).read_text().strip()
    # Reference APKs may have a different CI debug signer. Never upgrade across
    # signers or inherit the previous product's profile on this disposable device.
    adb('uninstall', package + '.test', check=False)
    adb('uninstall', package, check=False)
    adb('install', '-r', args.apks / (label + '.apk'))
    adb('install', '-r', args.apks / (label + '-tests.apk'))
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'pm', 'clear', package)
    adb('shell', 'run-as', package, 'mkdir', '-p', 'files')
    adb('shell', f"cat /data/local/tmp/candidate-fixture.zip | run-as {package} sh -c 'cat > files/candidate-fixture.zip'")
    adb('logcat', '-c')
    prepare, passed = instrument(package, 'prepare', revision)
    (args.output / (label + '-prepare.txt')).write_text(prepare.stdout + prepare.stderr)
    if not passed:
        (args.output / (label + '-prepare-logcat.txt')).write_text(adb('logcat', '-d', '-v', 'threadtime').stdout)
    assert passed, label + ': fixture preparation failed'
    component = package + '/org.fcitx.fcitx5.android.input.FcitxInputMethodService'
    adb('shell', 'ime', 'enable', component)
    # The replay runner selects it after instrumentation's final process restart.
    trials = []
    for trial in range(args.trials):
        print(f'Testing {label} fresh process {trial + 1}', flush=True)
        adb('shell', 'am', 'force-stop', host)
        adb('shell', 'pm', 'clear', host)
        for other in ('org.fcitx.fcitx5.android.fx.rime.debug', 'io.github.sandyyur.rime.direct'):
            adb('shell', 'am', 'force-stop', other)
        adb('shell', 'am', 'force-stop', package)
        adb('logcat', '-c')
        run, passed = instrument(package, 'replay', revision)
        prefix = args.output / f'{label}-{trial + 1}'
        prefix.with_suffix('.instrumentation.txt').write_text(run.stdout + run.stderr)
        prefix.with_suffix('.logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime').stdout)
        assert passed, f'{label} trial {trial + 1} failed'
        ime = json.loads(adb('shell', 'run-as', package, 'cat', 'files/candidate-taps.json').stdout)
        # Flush the editor dump AFTER timing; its own callbacks contain the timestamps.
        adb('shell', 'am', 'broadcast', '-n', host + '/.BenchControl', '--es', 'command', 'dump')
        editor = json.loads(adb('shell', 'run-as', host, 'cat', 'files/candidate-editor.json').stdout)
        assert editor['text'] == '你' * 6, editor
        assert ime['commit'] == revision and ime['model_mapped']
        assert ime['fcitx_core_mapped'] == (label == 'fcitx')
        data = {'ime': ime, 'editor': editor}
        data['timings_ms'] = []
        for index, row in enumerate(ime['rounds']):
            selected = row['select']
            up = selected['dispatch_up_ns']
            expected = '你' * (index + 1)
            edit = next(e['at_ns'] for e in editor['edits'] if e['text'] == expected)
            draw = next(e['at_ns'] for e in editor['draws'] if e['text'] == expected)
            data['timings_ms'].append({
                'round': index + 1,
                'injection_up_to_ime_dispatch': (up - selected['inject_up_ns']) / 1e6,
                'ime_dispatch_up_to_commit_collector': (row['commit_collected_ns'] - up) / 1e6,
                'ime_dispatch_up_to_editor_change': (edit - up) / 1e6,
                'ime_dispatch_up_to_editor_draw': (draw - up) / 1e6,
                'ime_touch_up_handler': (selected['dispatch_return_ns'] - up) / 1e6,
                'n_dispatch_up_to_preedit_collector': (row['preedit_collected_ns'] - row['n']['dispatch_up_ns']) / 1e6,
            })
        prefix.with_suffix('.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        trials.append(data)
        print(json.dumps(data['timings_ms'], ensure_ascii=False), flush=True)
    results[label] = trials
(args.output / 'comparison.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
