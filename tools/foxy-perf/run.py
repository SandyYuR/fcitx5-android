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
CASES = {'ni': ('ni', '你'), 'phrase': ('zaoshanghao', '早上好')}


def describe(values):
    if not values:
        return {'n': 0}
    ordered = sorted(values)
    return {'n': len(values), 'median_ms': statistics.median(values),
            'min_ms': ordered[0], 'max_ms': ordered[-1],
            'p95_nearest_rank_ms': ordered[max(0, (95*len(values)+99)//100-1)]}


def analyze(directory, replay, phases=('fresh', 'rebind')):
    result = []
    word = replay['word']
    for phase in phases:
        path = directory/(phase+'-editor.json')
        if not path.exists():
            continue
        editor = json.loads(path.read_text())
        samples = [s for s in replay.get('samples', []) if s['phase'] == phase and 'candidate_down_ns' in s]
        # Some IMEs clear an old composition with an empty commit on binding.
        # Keep all raw callbacks, but count nonempty text deliveries one-to-one.
        commits = [c for c in editor['commits'] if c['text']]
        if len(commits) != len(samples) or editor['text'] != word*len(samples):
            raise ValueError(f'{phase}: unmatched commits or editor output')
        for sample, commit in zip(samples, commits):
            assert commit['text'] == word, 'Unexpected commit payload'
            expected = word*(sample['round']+1)
            edit = next(e for e in editor['edits'] if e['text'] == expected and e['at_ns'] >= commit['at_ns'])
            draw = next(e for e in editor['draws'] if e['text'] == expected and e['at_ns'] >= edit['at_ns'])
            assert commit['at_ns'] >= sample['candidate_down_ns'], 'Commit predates tap'
            row = {'phase':phase, 'round':sample['round']}
            for action in ('down', 'up'):
                origin = sample['candidate_'+action+'_ns']
                for endpoint, event in [('ic', commit), ('edit', edit), ('draw', draw)]:
                    row[f'{action}_to_{endpoint}_ms'] = (event['at_ns']-origin)/1e6
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
    for iteration in range(a.trials*len(CASES)):
        trial, case_index = divmod(iteration, len(CASES))
        case = list(CASES)[case_index]
        sequence, word = CASES[case]
        order = ['direct', 'foxy'] if (trial+case_index) % 2 == 0 else ['foxy', 'direct']
        for label in order:
            package = PACKAGES[label]
            out = a.output/f'{trial:02d}-{case}-{label}'
            out.mkdir(exist_ok=True)
            adb('shell', 'am', 'start', '-W', '-a', 'android.settings.SETTINGS')
            for pkg in [HOST, *PACKAGES.values()]:
                adb('shell', 'am', 'force-stop', pkg, check=False)
            adb('shell', 'run-as', HOST, 'sh', '-c', shlex.quote('rm -f files/*.json files/*.png'))
            selected_ime(package)
            adb('logcat', '-c')
            args = ['am', 'instrument', '-w', '-e', 'package', package, '-e', 'points',
                    json.dumps(points[label], separators=(',', ':')), '-e', 'rounds', str(a.rounds),
                    '-e', 'sequence', sequence, '-e', 'word', word, HOST+'/.Replay']
            started = time.time()
            try:
                response = adb('shell', shlex.join(args), timeout=600)
            except sp.TimeoutExpired as error:
                response = 'Host instrumentation timed out: '+str(error)
            (out/'instrumentation.txt').write_text(response)
            (out/'logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime'))
            # The neutral host is debuggable; neither IME needs run-as access.
            for name in ['replay.json', 'fresh-editor.json', 'rebind-editor.json', 'candidate-editor.json',
                         'fresh-editor.png', 'rebind-editor.png', 'fresh-candidates.png', 'rebind-candidates.png', 'failure.png']:
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
                    records.append(dict(row, product=label, trial=trial, case=case))
                print(f'{trial} {case} {label}: {len(measured)} correct selections, {time.time()-started:.1f}s', flush=True)
            except (ValueError, AssertionError, FileNotFoundError, StopIteration, KeyError) as error:
                failures.append({'trial':trial, 'case':case, 'product':label, 'error':str(error)})
                print(f'{trial} {case} {label}: FAILED {error}', flush=True)
            (a.output/'records.json').write_text(json.dumps(records, indent=2))
            (a.output/'failures.json').write_text(json.dumps(failures, indent=2))
    summary = {'method':'external touch, 24ms hold; endpoints are IC entry, TextWatcher and CPU onDraw; not screen presentation',
               'cache':'process-fresh; prepared profiles, OS caches and learned user databases retained',
               'selection_precondition':'Current binding ready and stable for 500ms, then type and wait fixed 1000ms in both products; first candidate screenshot before each phase; not a typing latency measurement',
               'trials':a.trials, 'rounds_per_phase':a.rounds, 'failures':failures, 'products':{}}
    for label in PACKAGES:
        groups = summary['products'][label] = {}
        for group in [ (case, phase) for case in CASES for phase in ('fresh', 'rebind') ]:
            case, phase = group
            for kind in ('first', 'warm'):
                subset = [r for r in records if r['product']==label and r['case']==case and r['phase']==phase and ((r['round']==0)==(kind=='first'))]
                groups[case+'_'+phase+'_'+kind] = {metric:describe([r[metric] for r in subset]) for metric in
                    ['down_to_ic_ms','up_to_ic_ms','up_to_edit_ms','up_to_draw_ms']}
    (a.output/'summary.json').write_text(json.dumps(summary, indent=2))
    if failures:
        raise SystemExit(f'{len(failures)} trials failed; raw evidence retained, no silent exclusions')


if __name__ == '__main__':
    main()
