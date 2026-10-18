#!/usr/bin/env python3
"""External touch comparison of two unmodified, already calibrated ARM64 IMEs.

Process-fresh, prepared profiles; file caches and learned user databases remain.
No old product, injected IME instrumentation or generated engine warmup is used.
"""
import argparse
import json
from pathlib import Path
import shlex
import statistics
import subprocess as sp
import time

from pilot import adb, selected_ime, HOST

PACKAGES = {'foxy': 'com.fxliang.foxy', 'direct': 'io.github.sandyyur.rime.direct'}


def describe(values):
    if not values:
        return {'n': 0}
    ordered = sorted(values)
    return {'n': len(values), 'median_ms': statistics.median(values),
            'min_ms': ordered[0], 'max_ms': ordered[-1],
            'p95_nearest_rank_ms': ordered[max(0, (95*len(values)+99)//100-1)]}


def analyze(directory, replay):
    result = []
    for phase in ('fresh', 'rebind'):
        path = directory/(phase+'-editor.json')
        if not path.exists():
            continue
        editor = json.loads(path.read_text())
        samples = [s for s in replay.get('samples', []) if s['phase'] == phase and 'candidate_down_ns' in s]
        commits = editor['commits']
        if len(commits) != len(samples) or editor['text'] != '你'*len(samples):
            raise ValueError(f'{phase}: unmatched commits or editor output')
        for sample, commit in zip(samples, commits):
            assert commit['text'] == '你', 'Unexpected commit payload'
            expected = '你'*(sample['round']+1)
            edit = next(e for e in editor['edits'] if e['text'] == expected and e['at_ns'] >= commit['at_ns'])
            draw = next(e for e in editor['draws'] if e['text'] == expected and e['at_ns'] >= edit['at_ns'])
            assert commit['at_ns'] >= sample['candidate_down_ns'], 'Commit predates tap'
            row = {'phase':phase, 'round':sample['round']}
            for action in ('down', 'up'):
                origin = sample['candidate_'+action+'_ns']
                for endpoint, event in [('ic', commit), ('edit', edit), ('draw', draw)]:
                    row[f'{action}_to_{endpoint}_ms'] = (event['at_ns']-origin)/1e6
            row['i_up_to_preedit_observed_ms'] = (sample['preedit_observed_ns']-sample['i_up_ns'])/1e6
            result.append(row)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--points', type=Path, required=True)
    parser.add_argument('--trials', type=int, default=6)
    parser.add_argument('--rounds', type=int, default=8)
    a = parser.parse_args()
    assert adb('get-serialno').startswith('emulator-'), 'Disposable emulator only'
    a.output.mkdir(parents=True, exist_ok=True)
    points = json.loads(a.points.read_text())
    records, failures = [], []
    # Apply each unmodified APK's own packaged profile equally, and retain the
    # shell output rather than assuming compilation succeeded on this image.
    for label, package in PACKAGES.items():
        profile = adb('shell', 'am', 'broadcast', '-a', 'androidx.profileinstaller.action.INSTALL_PROFILE',
                      '-n', package+'/androidx.profileinstaller.ProfileInstallReceiver', check=False)
        compile_result = adb('shell', 'cmd', 'package', 'compile', '-f', '-m', 'speed-profile', package, check=False)
        (a.output/(label+'-art.txt')).write_text(profile+'\n'+compile_result+'\n'+
            adb('shell', 'dumpsys', 'package', package))
    for trial in range(a.trials):
        order = ['direct', 'foxy'] if trial % 2 == 0 else ['foxy', 'direct']
        for label in order:
            package = PACKAGES[label]
            out = a.output/f'{trial:02d}-{label}'
            out.mkdir(exist_ok=True)
            adb('shell', 'am', 'start', '-W', '-a', 'android.settings.SETTINGS')
            for pkg in [HOST, *PACKAGES.values()]:
                adb('shell', 'am', 'force-stop', pkg, check=False)
            adb('shell', 'run-as', HOST, 'sh', '-c', shlex.quote('rm -f files/*.json files/*.png'))
            selected_ime(package)
            adb('logcat', '-c')
            args = ['am', 'instrument', '-w', '-e', 'package', package, '-e', 'points',
                    json.dumps(points[label], separators=(',', ':')), '-e', 'rounds', str(a.rounds), HOST+'/.Replay']
            started = time.time()
            try:
                response = adb('shell', shlex.join(args), timeout=240)
            except sp.TimeoutExpired as error:
                response = 'Host instrumentation timed out: '+str(error)
            (out/'instrumentation.txt').write_text(response)
            (out/'logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime'))
            # The neutral host is debuggable; neither IME needs run-as access.
            for name in ['replay.json', 'fresh-editor.json', 'rebind-editor.json', 'candidate-editor.json',
                         'fresh-editor.png', 'rebind-editor.png', 'failure.png']:
                value = sp.run(['adb', 'exec-out', 'run-as', HOST, 'cat', 'files/'+name], capture_output=True)
                if value.returncode == 0:
                    (out/name).write_bytes(value.stdout)
            (out/'memory.txt').write_text(adb('shell', 'dumpsys', 'meminfo', package))
            pid = adb('shell', 'pidof', package, check=False)
            if pid:
                (out/'maps.txt').write_text(adb('shell', 'cat', '/proc/'+pid.split()[0]+'/maps'))
            try:
                replay = json.loads((out/'replay.json').read_text())
                if replay.get('failure'):
                    raise ValueError(replay['failure'])
                measured = analyze(out, replay)
                assert len(measured) == a.rounds*2, 'Incomplete trial'
                for row in measured:
                    records.append(dict(row, product=label, trial=trial))
                print(f'{trial} {label}: {len(measured)} correct selections, {time.time()-started:.1f}s', flush=True)
            except (ValueError, AssertionError, FileNotFoundError, StopIteration, KeyError) as error:
                failures.append({'trial':trial, 'product':label, 'error':str(error)})
                print(f'{trial} {label}: FAILED {error}', flush=True)
            (a.output/'records.json').write_text(json.dumps(records, indent=2))
            (a.output/'failures.json').write_text(json.dumps(failures, indent=2))
    summary = {'method':'external touch, 24ms hold; endpoints are IC entry, TextWatcher and CPU onDraw; not screen presentation',
               'cache':'process-fresh; prepared profiles, OS caches and learned user databases retained',
               'trials':a.trials, 'rounds_per_phase':a.rounds, 'failures':failures, 'products':{}}
    for label in PACKAGES:
        groups = summary['products'][label] = {}
        for phase in ('fresh', 'rebind'):
            for kind in ('first', 'warm'):
                subset = [r for r in records if r['product']==label and r['phase']==phase and ((r['round']==0)==(kind=='first'))]
                groups[phase+'_'+kind] = {metric:describe([r[metric] for r in subset]) for metric in
                    ['down_to_ic_ms','up_to_ic_ms','up_to_edit_ms','up_to_draw_ms','i_up_to_preedit_observed_ms']}
    (a.output/'summary.json').write_text(json.dumps(summary, indent=2))
    if failures:
        raise SystemExit(f'{len(failures)} trials failed; raw evidence retained, no silent exclusions')


if __name__ == '__main__':
    main()
