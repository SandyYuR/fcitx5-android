#!/usr/bin/env python3
"""Compare the untouched Fcitx default APK and direct frontend on a disposable emulator."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import time
import zipfile
from pathlib import Path


def checksum(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fcitx', type=Path)
    parser.add_argument('fcitx_tests', type=Path)
    parser.add_argument('direct', type=Path)
    parser.add_argument('direct_tests', type=Path)
    parser.add_argument('--wanxiang', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/engine-comparison'))
    parser.add_argument('--profile', action='store_true', help='Capture one extra direct run, outside the timing comparison')
    parser.add_argument('--profile-only', action='store_true', help='Reuse fixed APKs and collect only the extra direct trace')
    parser.add_argument('--preset', choices=('upstream-default', 'user-full-pinyin'), default='upstream-default')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    aapt = sorted(sdk.glob('build-tools/*/aapt2'))[-1]

    def adb(*arguments, **kwargs):
        return subprocess.run(['adb', *arguments], capture_output=True, text=True, timeout=300, check=True, **kwargs)

    if not adb('get-serialno').stdout.strip().startswith('emulator-'):
        raise SystemExit('Use a disposable emulator, never a personal phone.')
    assert adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() == '1'
    apks = {'fcitx': args.fcitx, 'direct': args.direct}
    packages, hashes, libraries = {}, {}, {}
    for backend, apk in apks.items():
        badging = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], capture_output=True, text=True, check=True).stdout
        packages[backend] = re.search(r"^package: name='([^']+)'", badging).group(1)
        hashes[backend] = checksum(apk)
        with zipfile.ZipFile(apk) as archive:
            libraries[backend] = sorted(name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so'))
        assert any('Fcitx5Core' in name for name in libraries[backend]) == (backend == 'fcitx')
        adb('install', '-r', str(apk))
        adb('install', '-r', str(args.fcitx_tests if backend == 'fcitx' else args.direct_tests))
        package = packages[backend]
        adb('shell', 'run-as', package, 'mkdir', '-p', 'files')
    assert packages['fcitx'] != packages['direct']
    adb('push', str(args.wanxiang), '/data/local/tmp/wanxiang-perf.zip')
    for package in packages.values():
        adb('shell', f"cat /data/local/tmp/wanxiang-perf.zip | run-as {package} sh -c 'cat > files/wanxiang-perf.zip'")
    adb('shell', 'rm', '/data/local/tmp/wanxiang-perf.zip')
    fixture_hash = checksum(args.wanxiang)
    harness_hash = checksum(Path(__file__).with_name('EngineComparisonTest.kt'))

    def replay(backend, round_name):
        for package in packages.values():
            adb('shell', 'am', 'force-stop', package)
        package = packages[backend]
        label = f'{round_name}-{backend}'
        print('Starting ' + label, flush=True)
        with (args.output / (label + '-instrumentation.txt')).open('w') as log:
            run = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', 'org.fcitx.fcitx5.android.core.EngineComparisonTest#wanxiangReplay',
                '-e', 'backend', backend, '-e', 'round', round_name,
                '-e', 'fixturePreset', args.preset,
                package + '.test/androidx.test.runner.AndroidJUnitRunner'],
                stdout=log, stderr=subprocess.STDOUT, timeout=1200)
        output = (args.output / (label + '-instrumentation.txt')).read_text()
        if run.returncode != 0 or 'OK (1 test)' not in output:
            # Retain useful crash/maintenance diagnostics even when the harness fails.
            (args.output / (label + '-logcat.txt')).write_text(adb('logcat', '-d', '-t', '1500').stdout)
            raise RuntimeError(label + ' failed; see instrumentation and logcat artifacts')
        result = json.loads(adb('shell', 'run-as', package, 'cat', 'files/engine-comparison.json').stdout)
        result.update(apk_sha256=hashes[backend], fixture_sha256=fixture_hash, harness_sha256=harness_hash,
                      packaged_native_libraries=libraries[backend])
        assert result['model_mapped'] and result['keys'] == 784 and len(result['commits']) == 100
        (args.output / (label + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('backend', 'round', 'app_commit', 'wall_ms', 'process_cpu_ms')}, ensure_ascii=False), flush=True)
        return result

    # Three fresh-profile runs per backend, with order reversal to expose warm/host drift.
    results = []
    for round_name, order in [('round1', ['fcitx', 'direct']), ('round2', ['direct', 'fcitx']), ('round3', ['fcitx', 'direct'])]:
        if args.profile_only:
            break
        for backend in order:
            results.append(replay(backend, round_name))
    first = results[0] if results else None
    for result in results:
        assert result['commits'] == first['commits'], 'Input output changed between backends or rounds'
        assert result['candidate_text_sha256'] == first['candidate_text_sha256'], 'Visible candidate order changed'
    if results:
        (args.output / 'comparison.json').write_text(json.dumps({
        'order': [f"{item['round']}-{item['backend']}" for item in results],
        'identical_commits': True, 'identical_candidate_texts': True,
        'harness_sha256': harness_hash, 'fixture_sha256': fixture_hash,
        'fixture_preset': args.preset,
        'apk_sha256': hashes, 'packaged_native_libraries': libraries,
        'metrics': {backend: [{
            'round': r['round'], 'wall_ms': r['wall_ms'], 'process_cpu_ms': r['process_cpu_ms'],
            'memory_before': r['memory_before'], 'memory_after': r['memory_after'],
            **{metric: {key: value for key, value in r[metric].items() if key != 'raw_ns'}
               for metric in ('send_key_return', 'key_to_candidates', 'space_to_commit', 'word_to_result')}
        } for r in results if r['backend'] == backend] for backend in apks}
        }, ensure_ascii=False, indent=2) + '\n')
        print('All six runs preserved all commits and candidate texts.', flush=True)

    if args.profile or args.profile_only:
        remote = '/data/misc/perfetto-traces/wanxiang-direct.perfetto-trace'
        config = f'''buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
duration_ms: 120000
write_into_file: true
file_write_period_ms: 1000
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  atrace_categories: "view"
  atrace_categories: "dalvik"
  atrace_apps: "{packages['direct']}"
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ proc_stats_poll_ms: 1000 }} }} }}
'''
        pid = adb('shell', 'perfetto', '--background-wait', '--txt', '-c', '-', '-o', remote, input=config).stdout.strip()
        assert pid.isdigit(), 'Perfetto did not report its capture PID: ' + pid
        replay('direct', 'profile')
        # Let Perfetto finish naturally. Killing the consumer can leave an empty output;
        # process exit and a non-empty, fully flushed trace are both required.
        for _ in range(300):
            alive = subprocess.run(['adb', 'shell', 'kill', '-0', pid], capture_output=True).returncode == 0
            if not alive:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError('Perfetto did not finish flushing')
        adb('pull', remote, str(args.output / 'wanxiang-direct.perfetto-trace'))
        assert (args.output / 'wanxiang-direct.perfetto-trace').stat().st_size > 0, 'Perfetto produced an empty trace'


if __name__ == '__main__':
    main()
