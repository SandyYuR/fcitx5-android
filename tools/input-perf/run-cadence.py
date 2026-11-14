#!/usr/bin/env python3
"""First candidate tap after fresh process, then five taps in the same editor."""
import argparse
from touch_timing import validate_touch_hold
import hashlib
import shutil
import json
import os
import re
import subprocess
import time
from pathlib import Path

def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Exact adb emulator serial; never uses ANDROID_SERIAL or a default device')
    parser.add_argument('--expected-avd-name', required=True, help='AVD name to verify before changing the device')
    parser.add_argument('--allow-destructive-reset', action='store_true',
                        help='Explicitly authorize uninstalling/resetting benchmark apps and changing settings on this named test AVD; never use on a personal or long-lived AVD')
    parser.add_argument('--apks', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--trials', type=int, default=3)
    parser.add_argument('--order', default='before,after', help='Comma-separated labels in products.json')
    parser.add_argument('--fixture-manifest', type=Path, required=True)
    parser.add_argument('--pilot', action='store_true', help='One process per product; calibration only')
    parser.add_argument('--profile', action='store_true', help='Separate diagnostic run; do not compare its timings with untraced runs')
    return parser


class Adb:
    """All calls, including instrumentation and failure recovery, target one device."""

    def __init__(self, serial):
        if not re.fullmatch(r'emulator-\d+', serial):
            raise ValueError('--serial must name an Android emulator explicitly')
        self.serial = serial

    def command(self, *parts):
        return ['adb', '-s', self.serial, *map(str, parts)]

    def __call__(self, *parts, check=True, timeout=900, input=None):
        return subprocess.run(self.command(*parts), input=input, text=True,
                              capture_output=True, check=check, timeout=timeout)

    def verify_avd(self, expected_name):
        # These are read-only. An emulator serial alone does not make its data disposable.
        if self('get-serialno').stdout.strip() != self.serial:
            raise RuntimeError('Connected serial does not match --serial')
        if self('get-state').stdout.strip() != 'device':
            raise RuntimeError('The named emulator is not online')
        lines = self('emu', 'avd', 'name').stdout.strip().splitlines()
        if lines and lines[-1] == 'OK':
            lines.pop()
        if lines != [expected_name]:
            raise RuntimeError('AVD name does not match --expected-avd-name; no device changes made')

    def instrument(self, package, method, revision):
        command = self.command('shell', 'am', 'instrument', '--no-hidden-api-checks', '-w', '-r', '-e', 'class',
                  'org.fcitx.fcitx5.android.core.CandidateTapTest#' + method,
                  '-e', 'expectedCommit', revision,
                  package + '.test/androidx.test.runner.AndroidJUnitRunner')
        process = subprocess.Popen(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            if method == 'replay':
                # Instrumentation restarts the process. Select the IME after that restart.
                deadline = time.monotonic() + 30
                started = False
                while process.poll() is None and time.monotonic() < deadline:
                    logs = self('logcat', '-d', '-s', 'TestRunner:I', '*:S').stdout
                    if 'started: replay(org.fcitx.fcitx5.android.core.CandidateTapTest)' in logs:
                        started = True
                        break
                    time.sleep(0.1)
                if started:
                    component = package + '/org.fcitx.fcitx5.android.input.FcitxInputMethodService'
                    self('shell', 'ime', 'enable', component)
                    self('shell', 'ime', 'set', component)
                    selected = self('shell', 'settings', 'get', 'secure', 'default_input_method').stdout.strip()
                    if selected != component:
                        raise RuntimeError(f'Device did not select the benchmark IME: {selected!r}')
            stdout, stderr = process.communicate(timeout=900)
        except BaseException:
            try:
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=15)
            except BaseException:
                pass
            raise
        run = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
        return run, run.returncode == 0 and 'OK (1 test)' in run.stdout


def find_aapt(sdk):
    name = 'aapt2.exe' if os.name == 'nt' else 'aapt2'
    paths = list((sdk / 'build-tools').glob('*/' + name))
    if not paths:
        raise RuntimeError(f'No {name} found under {sdk / "build-tools"}')
    return max(paths, key=lambda p: [int(x) for x in re.findall(r'\d+', p.parent.name)])


def run(args):
    if not args.allow_destructive_reset:
        raise ValueError('This benchmark resets app data. --allow-destructive-reset is required.')
    if not args.expected_avd_name or args.expected_avd_name.strip() != args.expected_avd_name:
        raise ValueError('--expected-avd-name must be a nonempty exact AVD name')
    adb = Adb(args.serial)
    # Check identity before any device settings, installation, reset or fixture write.
    adb.verify_avd(args.expected_avd_name)
    if args.pilot:
        args.trials = 1
    PRODUCTS = tuple(args.order.split(','))
    if (not PRODUCTS or len(PRODUCTS) != len(set(PRODUCTS)) or
            not all(re.fullmatch(r'[a-z][a-z0-9_-]*', label) for label in PRODUCTS)):
        raise ValueError('--order must contain unique safe product labels')
    if not 0 < args.trials <= 100:
        raise ValueError('--trials must be between 1 and 100')
    if args.output.exists() and any(args.output.iterdir()):
        raise SystemExit('Output directory must be empty; preserve prior measurements.')
    record = json.loads((args.apks/'products.json').read_text())
    harness = Path(__file__).with_name('CadenceCandidateTapTest.kt')
    if hashlib.sha256(harness.read_bytes()).hexdigest() != record['harness_sha256']:
        raise ValueError('Harness source does not match products.json')
    if record['protocol'] != 'fixed_cadence_100ms_input_manager_async_cached_targets_v2':
        raise ValueError('Product manifest must use the cached_targets_v2 harness protocol')
    for name,sha in record['apks'].items():
        if Path(name).name != name:
            raise ValueError(f'APK manifest entry must be a filename: {name!r}')
        if hashlib.sha256((args.apks/name).read_bytes()).hexdigest() != sha:
            raise ValueError(f'APK does not match products.json: {name}')
    for label in PRODUCTS:
        source = record['sources'].get(label)
        if not isinstance(source, str) or not re.fullmatch(r'[a-f0-9]{40}', source):
            raise ValueError(f'Invalid or missing source revision for {label}')
        if (args.apks/(label+'-commit.txt')).read_text().strip() != source:
            raise ValueError(f'Commit record does not match products.json: {label}')
        if not all(label+suffix in record['apks'] for suffix in ('.apk','-tests.apk')):
            raise ValueError(f'Product APK entries are incomplete: {label}')
    fixture = json.loads(args.fixture_manifest.read_text())
    if hashlib.sha256(args.fixture.read_bytes()).hexdigest() != fixture['fixture_sha256']:
        raise ValueError('Fixture does not match its manifest')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    aapt = find_aapt(sdk)
    packages = {}
    for label in PRODUCTS:
        package = subprocess.check_output(
            [str(aapt), 'dump', 'packagename', str(args.apks / (label + '.apk'))],
            text=True).strip()
        if not re.fullmatch(r'[a-zA-Z0-9_.]+', package) or not package.endswith('.debug'):
            raise ValueError(f'Benchmark product must have a .debug package name: {package}')
        packages[label] = package
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.apks/'products.json',args.output/'products.json')
    shutil.copyfile(args.fixture_manifest,args.output/'fixture.manifest.json')
    (args.output/'run-manifest.json').write_text(json.dumps({'order':PRODUCTS,'trials':args.trials,'profile':args.profile,
        'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'harness_sha256':record['harness_sha256'],'neutral_host_sha256':hashlib.sha256(args.host.read_bytes()).hexdigest(),
        'serial':args.serial,'expected_avd_name':args.expected_avd_name,'allow_destructive_reset':True},indent=2)+'\n')
    host = 'io.github.sandyyur.inputbench'

    active_trace = None
    def preserve_failed_trace():
        if active_trace is None: return
        pid,remote,local = active_trace
        if local.exists(): return
        try:
            adb('shell','kill','-INT',pid,check=False,timeout=10)
            time.sleep(0.3)
            result=adb('pull',remote,str(local),check=False,timeout=30)
            (args.output/'failed-trace-retention.txt').write_text(result.stdout+result.stderr)
        except Exception as error:
            (args.output/'failed-trace-retention.txt').write_text(repr(error)+'\n')

    try:
        (args.output/'device-properties.txt').write_text(adb('shell','getprop').stdout)
        adb('shell', 'setprop', 'debug.hwui.drawing_enabled', 'true')
        adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
        adb('shell', 'settings', 'put', 'system', 'screen_off_timeout', '1800000')
        adb('install', '-r', args.host)
        adb('push', args.fixture, '/data/local/tmp/candidate-fixture.zip')
        results = {}
        for label in PRODUCTS:
            package = packages[label]
            revision = (args.apks / (label + '-commit.txt')).read_text().strip()
            # Reference APKs may have a different CI debug signer. Never upgrade across
            # signers or inherit the previous product's profile on this explicitly authorized test AVD.
            adb('uninstall', package + '.test', check=False)
            adb('uninstall', package, check=False)
            adb('install', '-r', args.apks / (label + '.apk'))
            adb('install', '-r', args.apks / (label + '-tests.apk'))
            adb('shell', 'am', 'force-stop', package)
            adb('shell', 'pm', 'clear', package)
            adb('shell', 'run-as', package, 'mkdir', '-p', 'files')
            adb('shell', f"cat /data/local/tmp/candidate-fixture.zip | run-as {package} sh -c 'cat > files/candidate-fixture.zip'")
            adb('logcat', '-c')
            prepare, passed = adb.instrument(package, 'prepare', revision)
            (args.output / (label + '-prepare.txt')).write_text(prepare.stdout + prepare.stderr)
            if not passed:
                (args.output / (label + '-prepare-logcat.txt')).write_text(adb('logcat', '-d', '-v', 'threadtime').stdout)
                raise RuntimeError(label + ': fixture preparation failed')
            component = package + '/org.fcitx.fcitx5.android.input.FcitxInputMethodService'
            adb('shell', 'ime', 'enable', component)
            # The replay runner selects it after instrumentation's final process restart.
            trials = []
            for trial in range(args.trials):
                print(f'Testing {label} fresh process {trial + 1}', flush=True)
                adb('shell', 'am', 'force-stop', host)
                adb('shell', 'pm', 'clear', host)
                for other in ('org.fcitx.fcitx5.android.fx.rime.debug', 'io.github.sandyyur.rime.direct', 'org.fcitx.fcitx5.android.fx.rime'):
                    adb('shell', 'am', 'force-stop', other)
                adb('shell', 'am', 'force-stop', package)
                adb('logcat', '-c')
                trace_pid = None
                if args.profile:
                    remote_trace = '/data/misc/perfetto-traces/candidate.perfetto-trace'
                    config = f'''buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
        duration_ms: 20000
        write_into_file: true
        file_write_period_ms: 1000
        flush_period_ms: 1000
        data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
         ftrace_events: "sched/sched_switch"
         ftrace_events: "sched/sched_waking"
         atrace_categories: "view"
         atrace_categories: "gfx"
         atrace_categories: "input"
         atrace_categories: "dalvik"
         atrace_apps: "{package}"
         atrace_apps: "{host}"
        }} }} }}
        data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ proc_stats_poll_ms: 1000 }} }} }}
        '''
                    trace_pid = adb('shell', 'perfetto', '--background-wait', '--txt', '-c', '-', '-o', remote_trace, input=config).stdout.strip()
                    if not trace_pid.isdigit():
                        raise RuntimeError(f'Perfetto did not return a numeric PID: {trace_pid!r}')
                    (args.output / (label + "-" + str(trial + 1) + ".trace-config.txt")).write_text(config)
                prefix = args.output / f'{label}-{trial + 1}'
                if trace_pid: active_trace=(trace_pid,remote_trace,prefix.with_suffix('.perfetto-trace'))
                run, passed = adb.instrument(package, 'replay', revision)
                prefix = args.output / f'{label}-{trial + 1}'
                prefix.with_suffix('.instrumentation.txt').write_text(run.stdout + run.stderr)
                prefix.with_suffix('.logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime').stdout)
                if not passed:
                    raise RuntimeError(f'{label} trial {trial + 1} failed')
                ime = json.loads(adb('shell', 'run-as', package, 'cat', 'files/candidate-taps.json').stdout)
                # Preserve original IME evidence even when the new stimulus policy fails.
                prefix.with_suffix('.ime-raw.json').write_text(json.dumps(ime, ensure_ascii=False, indent=2) + '\n')
                validate_touch_hold(ime)
                # Flush the editor dump AFTER timing; its own callbacks contain the timestamps.
                adb('shell', 'am', 'broadcast', '-n', host + '/.BenchControl', '--es', 'command', 'dump')
                editor = json.loads(adb('shell', 'run-as', host, 'cat', 'files/candidate-editor.json').stdout)
                if editor.get('text') != '你' * 6:
                    raise RuntimeError('Editor did not receive six expected candidate commits')
                if ime.get('commit') != revision or ime.get('model_mapped') is not True:
                    raise RuntimeError('IME result does not match the pinned product/model')
                if ime.get('injection_protocol') != 'fixed_cadence_100ms_input_manager_async_cached_targets_v2':
                    raise RuntimeError('IME result uses an unexpected injection protocol')
                try:
                    geometry_valid = all(
                        touch.get('geometry_valid') is True
                        for round_data in ime['rounds']
                        for touch in (round_data['n'], round_data['i'], round_data['select'])
                    )
                except (KeyError, TypeError):
                    geometry_valid = False
                if not geometry_valid:
                    raise RuntimeError('IME result contains missing or invalid touch geometry')
                if ime.get('fcitx_core_mapped') is not False:
                    raise RuntimeError('This protocol requires the direct Rime engine')
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
                        'ime_dispatch_up_to_editor_input_connection': (editor['commits'][index]['at_ns'] - up) / 1e6,
                        'ime_dispatch_up_to_editor_change': (edit - up) / 1e6,
                        'ime_dispatch_up_to_editor_draw': (draw - up) / 1e6,
                        'ime_touch_up_handler': (selected['dispatch_return_ns'] - up) / 1e6,
                        # The n presentation may validly be coalesced during rapid input.
                        'n_dispatch_up_to_preedit_collector': None if row['preedit_collected_ns'] is None else (row['preedit_collected_ns'] - row['n']['dispatch_up_ns']) / 1e6,
                        'i_planned_up_to_dispatch': (row['i']['dispatch_up_ns'] - row['i']['planned_up_ns']) / 1e6,
                        'i_actual_inject_up_to_dispatch': (row['i']['dispatch_up_ns'] - row['i']['inject_up_ns']) / 1e6,
                        'i_up_submission_lateness': (row['i']['inject_up_ns'] - row['i']['planned_up_ns']) / 1e6,
                    })
                prefix.with_suffix('.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
                trials.append(data)
                print(json.dumps(data['timings_ms'], ensure_ascii=False), flush=True)
                if trace_pid:
                    deadline = time.monotonic() + 30
                    while adb('shell', 'test', '-d', '/proc/' + trace_pid, check=False).returncode == 0:
                        if time.monotonic() >= deadline:
                            raise RuntimeError('Perfetto did not finish')
                        time.sleep(0.5)
                    adb('pull', remote_trace, str(prefix.with_suffix('.perfetto-trace')))
                    active_trace=None
            results[label] = trials
        (args.output / 'comparison.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
    finally:
        preserve_failed_trace()


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if not args.allow_destructive_reset:
        parser.error('--allow-destructive-reset is required: this benchmark uninstalls/resets apps and changes device settings')
    run(args)


if __name__ == '__main__':
    main()
