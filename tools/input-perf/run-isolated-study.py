#!/usr/bin/env python3
"""One fresh six-selection sample per product, using newly owned diagnostic packages only."""
import argparse
from decimal import Decimal, ROUND_FLOOR
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid


PACKAGE = 'org.fcitx.fcitx5.android.pr32bench.debug'
TEST_PACKAGE = PACKAGE + '.test'
HOST = 'io.github.sandyyur.inputbench'
PACKAGES = frozenset((PACKAGE, TEST_PACKAGE, HOST))
COMPONENT = PACKAGE + '/org.fcitx.fcitx5.android.input.FcitxInputMethodService'
ORIGINAL_MAIN = 'org.fcitx.fcitx5.android.fx.rime.debug/org.fcitx.fcitx5.android.ui.main.MainActivity'
PROTOCOL = 'fixed_cadence_100ms_input_manager_async_cached_targets_v2'
FIXTURE_SHA256 = '333e0a0c8c9beb8a942bd56d3f9c55f1c9123836635a4663744e23f21228404f'
SETTING_KEYS = (('secure', 'show_ime_with_hard_keyboard'), ('system', 'screen_off_timeout'))
PROPERTY = 'debug.hwui.drawing_enabled'


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def study_module():
    spec = importlib.util.spec_from_file_location('isolated_study_metrics', Path(__file__).with_name('run-study.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('apks', 'fixture', 'fixture-manifest', 'host', 'output'):
        parser.add_argument('--' + option, type=Path, required=True)
    parser.add_argument('--serial', required=True, help='Explicit adb serial, e.g. emulator-5574')
    parser.add_argument('--expected-avd-name', required=True, help='Exact existing AVD name, e.g. rime')
    parser.add_argument('--allow-owned-test-apps', action='store_true',
                        help='Allow this run to create/remove the three initially absent diagnostic apps and restore temporary device settings')
    parser.add_argument('--order', default='baseline,regressed,current')
    parser.add_argument('--profile', action='store_true', help='Separate diagnostic sample; never combine with untraced timings')
    return parser


def host_command(command):
    return subprocess.run(list(map(str, command)), check=True, text=True, capture_output=True, timeout=120).stdout


def apk_identity(apk, aapt, java, signer_jar):
    tree = host_command((aapt, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk))
    package = re.findall(r'^\s*A: package="([^"]+)"', tree, re.MULTILINE)
    # aapt2 may print the Android namespace URI instead of its short prefix.
    namespace = r'(?:android:|http://schemas\.android\.com/apk/res/android:)'
    def attributes(name):
        prefix = r'A:\s+' + namespace + name
        values = re.findall(prefix + r'(?:\([^)]*\))?="([^"]+)"', tree)
        if len(values) != len(re.findall(prefix + r'(?=\(|=)', tree)):
            raise ValueError(f'Cannot resolve {name} in {apk.name}')
        return values
    targets = attributes('targetPackage')
    if (len(package) != 1 or len(targets) > 1 or
            re.search(r'A:\s+' + namespace + r'sharedUserId(?=\(|=)', tree)):
        raise ValueError(f'Unexpected package/UID manifest in {apk.name}')
    authorities = attributes('authorities')
    if any(not authority.startswith(package[0] + '.') for authority in authorities):
        raise ValueError(f'Provider authority is outside its diagnostic package: {apk.name}')
    signature = host_command((java, '-jar', signer_jar, 'verify', '--print-certs', apk))
    from apk_signers import signer_sha256
    certificate = signer_sha256(signature)
    return {'package': package[0], 'target': targets[0] if targets else None,
            'signer_sha256': certificate, 'authorities': authorities}


def preflight(args):
    if not args.allow_owned_test_apps:
        raise ValueError('--allow-owned-test-apps is required; no device changes made')
    if not re.fullmatch(r'emulator-\d+', args.serial) or not re.fullmatch(r'[A-Za-z0-9_.-]+', args.expected_avd_name):
        raise ValueError('An explicit emulator serial and exact AVD name are required')
    labels = args.order.split(',')
    if not labels or len(labels) != len(set(labels)) or not set(labels) <= {'baseline', 'regressed', 'current'}:
        raise ValueError('Choose distinct baseline/regressed/current labels')
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError('Output must be empty; retain previous evidence')
    products = json.loads((args.apks / 'products.json').read_text(encoding='utf-8'))
    probe = Path(__file__).with_name('CadenceCandidateTapTest.kt')
    overlay = Path(__file__).with_name('pr32-bench-application-id.init.gradle')
    if products['protocol'] != PROTOCOL or products['harness_sha256'] != digest(probe):
        raise ValueError('Probe source/protocol does not match products.json')
    if products['init_script_sha256'] != digest(overlay) or products['host_sha256'] != digest(args.host):
        raise ValueError('Build overlay or host APK does not match products.json')
    fixture = json.loads(args.fixture_manifest.read_text(encoding='utf-8'))
    if fixture['fixture_sha256'] != FIXTURE_SHA256 or digest(args.fixture) != FIXTURE_SHA256:
        raise ValueError('Only the pinned 333e fixture is accepted')
    for label in labels:
        revision = products['sources'][label]
        if not re.fullmatch(r'[a-f0-9]{40}', revision):
            raise ValueError('Every source must be a full commit SHA')
        if (args.apks / (label + '-commit.txt')).read_text(encoding='utf-8').strip() != revision:
            raise ValueError('Commit file and source manifest disagree')
        for suffix in ('.apk', '-tests.apk'):
            if label + suffix not in products['apks']:
                raise ValueError('Missing product/test APK hash')
    for name, expected in products['apks'].items():
        if not re.fullmatch(r'[a-z][a-z0-9_-]*\.apk', name) or not re.fullmatch(r'[a-f0-9]{64}', expected):
            raise ValueError('APK manifest contains an unsafe filename or digest')
        if digest(args.apks / name) != expected:
            raise ValueError('APK hash mismatch: ' + name)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tool_dirs = [path for path in (sdk / 'build-tools').iterdir()
                 if (path / ('aapt2.exe' if os.name == 'nt' else 'aapt2')).is_file()
                 and (path / 'lib/apksigner.jar').is_file()]
    if not tool_dirs:
        raise ValueError('Android build tools with aapt2 and apksigner.jar are required')
    tools = max(tool_dirs, key=lambda path: tuple(int(x) for x in re.findall(r'\d+', path.name)))
    aapt = tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
    java = shutil.which('java')
    if not java:
        raise ValueError('A configured JDK is required')
    identities, installs = {}, {}
    for label in labels:
        for suffix, expected_package in (('.apk', PACKAGE), ('-tests.apk', TEST_PACKAGE)):
            path = (args.apks / (label + suffix)).resolve()
            identity = apk_identity(path, aapt, java, tools / 'lib/apksigner.jar')
            expected_target = PACKAGE if suffix == '-tests.apk' else None
            if identity['package'] != expected_package or identity['target'] != expected_target:
                raise ValueError('APK identity/target is not the isolated diagnostic app: ' + path.name)
            identities[path.name] = identity
            installs[str(path)] = {'package': expected_package, 'sha256': products['apks'][path.name]}
    if len({item['signer_sha256'] for item in identities.values()}) != 1:
        raise ValueError('All product and instrumentation APKs must have the same verified signer')
    host_path = args.host.resolve()
    host_identity = apk_identity(host_path, aapt, java, tools / 'lib/apksigner.jar')
    if host_identity['package'] != HOST or host_identity['target'] is not None:
        raise ValueError('Unexpected neutral host APK')
    identities['host'] = host_identity
    installs[str(host_path)] = {'package': HOST, 'sha256': products['host_sha256']}
    return labels, products, fixture, identities, installs


def enabled_components(value):
    return {entry.split(';', 1)[0] for entry in value.split(':') if entry and entry != 'null'}


def parse_wakefulness(power_dump):
    values = re.findall(r'^\s*mWakefulness=(\S+)\s*$', power_dump, re.MULTILINE)
    if len(values) != 1 or values[0] not in ('Awake', 'Asleep', 'Dozing', 'Dreaming'):
        raise RuntimeError('Cannot safely identify Awake/Asleep/Dozing/Dreaming state from dumpsys power')
    return values[0]


class OwnedDevice:
    """An exact command allowlist, with ownership acquired only after successful fresh installs."""
    def __init__(self, args, installs):
        self.args, self.installs = args, installs
        self.owned = set()
        self.active = False
        self.restoring = False
        self.initial = None
        self.commands = []
        self.trace_paths = set()
        self.trace_pids = set()
        self.audit_errors = []
        self.install_attempts = []

    def permit(self, parts):
        p = tuple(map(str, parts))
        reads = {('get-serialno',), ('get-state',), ('emu', 'avd', 'name'),
                 ('shell', 'pm', 'list', 'packages'), ('shell', 'getprop'),
                 ('shell', 'getprop', PROPERTY), ('shell', 'dumpsys', 'window', 'windows'),
                 ('shell', 'dumpsys', 'SurfaceFlinger'), ('shell', 'dumpsys', 'power'),
                 ('shell', 'date', '+%s.%N')}
        reads |= {('shell', 'settings', 'get', area, key) for area, key in SETTING_KEYS}
        reads |= {('shell', 'settings', 'get', 'secure', key)
                  for key in ('default_input_method', 'enabled_input_methods')}
        if p in reads or (len(p) == 3 and p[:2] == ('shell', 'pidof') and p[2] in PACKAGES):
            return
        if not self.active:
            raise ValueError('Device mutation before initial absence/state verification')
        wake_key = None
        if self.restoring:
            wake_key = 'KEYCODE_WAKEUP' if self.initial['wakefulness'] == 'Awake' else 'KEYCODE_SLEEP'
        elif self.initial['wakefulness'] == 'Asleep':
            wake_key = 'KEYCODE_WAKEUP'
        if wake_key and p == ('shell', 'input', 'keyevent', wake_key):
            return
        if len(p) == 2 and p[0] == 'install' and p[1] in self.installs:
            if self.installs[p[1]]['package'] not in PACKAGES:
                raise ValueError('Never install outside the three diagnostic packages')
            if digest(Path(p[1])) != self.installs[p[1]]['sha256']:
                raise ValueError('APK bytes changed after preflight')
            return
        if len(p) == 2 and p[0] == 'uninstall' and p[1] in self.owned & PACKAGES:
            return
        if p[:3] in (('shell', 'am', 'force-stop'), ('shell', 'pm', 'clear')) and len(p) == 4 and p[3] in self.owned & PACKAGES:
            return
        if p in {('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files'),
                 ('exec-in', 'run-as', PACKAGE, 'sh', '-c', 'cat > files/candidate-fixture.zip'),
                 ('shell', 'run-as', PACKAGE, 'cat', 'files/candidate-taps.json')} and PACKAGE in self.owned:
            return
        if p == ('shell', 'run-as', HOST, 'cat', 'files/candidate-editor.json') and HOST in self.owned:
            return
        if p == ('shell', 'am', 'broadcast', '-n', HOST + '/.BenchControl', '--es', 'command', 'dump') and HOST in self.owned:
            return
        if p[:3] == ('shell', 'am', 'instrument') and TEST_PACKAGE in self.owned and PACKAGE in self.owned:
            expected = ('shell', 'am', 'instrument', '--no-hidden-api-checks', '-w', '-r', '-e', 'class')
            if (p[:8] == expected and len(p) == 13 and p[9:11] == ('-e', 'expectedCommit')
                    and p[8] in ('org.fcitx.fcitx5.android.core.CandidateTapTest#prepare',
                                 'org.fcitx.fcitx5.android.core.CandidateTapTest#replay')
                    and re.fullmatch(r'[a-f0-9]{40}', p[11])
                    and p[12] == TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'):
                return
        if p[:3] == ('shell', 'ime', 'enable') and p[3:] == (COMPONENT,) and PACKAGE in self.owned:
            return
        if p[:3] == ('shell', 'ime', 'set') and len(p) == 4:
            if p[3] == COMPONENT and PACKAGE in self.owned:
                return
            if self.restoring and p[3] == self.initial['default_ime']:
                return
        if self.restoring and p == ('shell', 'ime', 'disable', COMPONENT) and PACKAGE in self.owned:
            return
        if p[:3] == ('shell', 'settings', 'put') and len(p) == 6:
            key = (p[3], p[4])
            if key in SETTING_KEYS:
                target = {'show_ime_with_hard_keyboard': '1', 'screen_off_timeout': '1800000'}[p[4]]
                if p[5] == (self.initial[p[4]] if self.restoring else target):
                    return
        if self.restoring and p[:3] == ('shell', 'settings', 'delete') and len(p) == 5:
            if ((p[3], p[4]) in SETTING_KEYS and self.initial[p[4]] == 'null') or (
                    p[3:] == ('secure', 'default_input_method') and self.initial['default_ime'] == 'null'):
                return
        if p[:3] == ('shell', 'setprop', PROPERTY) and len(p) == 4:
            expected = (self.initial['hwui'] or "''") if self.restoring else 'true'
            if p[3] == expected:
                return
        if self.restoring and self.initial['focus'] == ORIGINAL_MAIN and p == ('shell', 'am', 'start', '-W', '-n', ORIGINAL_MAIN):
            return
        if (len(p) == 10 and p[:4] == ('logcat', '-d', '-v', 'epoch') and p[4] == '-T'
                and re.fullmatch(r'\d+\.\d+', p[5]) and re.fullmatch(r'--pid=\d+', p[6])
                and p[7:] == ('-s', 'TestRunner:I', '*:S')):
            return
        if len(p) == 7 and p[:4] == ('logcat', '-d', '-v', 'threadtime') and p[4] == '-T' and re.fullmatch(r'\d+\.\d+', p[5]) and re.fullmatch(r'--pid=\d+', p[6]):
            return
        if p[:7] == ('shell', 'perfetto', '--background-wait', '--txt', '-c', '-', '-o') and len(p) == 8 and p[7] in self.trace_paths:
            return
        if p[:3] == ('shell', 'test', '-d') and len(p) == 4 and p[3].removeprefix('/proc/') in self.trace_pids and p[3].startswith('/proc/'):
            return
        if p[0] == 'pull' and len(p) == 3 and p[1] in self.trace_paths and Path(p[2]).resolve().is_relative_to(self.args.output.resolve()):
            return
        raise ValueError('Command is outside this run\'s package/settings allowlist: ' + repr(p))

    def command(self, *parts):
        self.permit(parts)
        command = ['adb', '-s', self.args.serial, *map(str, parts)]
        self.commands.append(command)
        if self.args.output.exists():
            try:
                save(self.args.output / 'commands.json', self.commands)
            except OSError as error:
                if not self.restoring:
                    raise
                # A full disk must not prevent restoring the device or removing owned apps.
                self.audit_errors.append(repr(error))
        return command

    def call(self, *parts, check=True, timeout=60, input=None, stdin=None):
        text = input is None or isinstance(input, str)
        result = subprocess.run(self.command(*parts), check=check, capture_output=True,
                                text=text, timeout=timeout, input=input, stdin=stdin)
        if isinstance(result.stdout, bytes):
            result.stdout = result.stdout.decode('utf-8', errors='replace')
            result.stderr = result.stderr.decode('utf-8', errors='replace')
        return result

    def packages(self):
        lines = self.call('shell', 'pm', 'list', 'packages').stdout.splitlines()
        if any(not re.fullmatch(r'package:[A-Za-z0-9_.]+', line) for line in lines):
            raise RuntimeError('Cannot reliably enumerate installed packages')
        return {line.removeprefix('package:') for line in lines}

    def initialize(self):
        if self.call('get-serialno').stdout.strip() != self.args.serial:
            raise RuntimeError('Connected serial differs from the requested serial')
        if self.call('get-state').stdout.strip() != 'device':
            raise RuntimeError('The requested emulator is not online')
        # Windows emulator-console output can use CR-CR-LF, which universal
        # newline decoding turns into empty lines. Compare every nonempty field.
        name = [line.strip() for line in self.call('emu', 'avd', 'name').stdout.splitlines() if line.strip()]
        if name and name[-1] == 'OK':
            name.pop()
        if name != [self.args.expected_avd_name]:
            raise RuntimeError('AVD identity mismatch')
        installed = self.packages()
        if installed & PACKAGES:
            raise RuntimeError('All three diagnostic apps must initially be absent; existing apps are never adopted')
        state = {key: self.call('shell', 'settings', 'get', area, key).stdout.strip() for area, key in SETTING_KEYS}
        state['default_ime'] = self.call('shell', 'settings', 'get', 'secure', 'default_input_method').stdout.strip()
        state['enabled_imes'] = self.call('shell', 'settings', 'get', 'secure', 'enabled_input_methods').stdout.strip()
        state['hwui'] = self.call('shell', 'getprop', PROPERTY).stdout.strip()
        power = self.power_state()
        state['wakefulness'] = power['wakefulness']
        if state['wakefulness'] not in ('Awake', 'Asleep'):
            raise RuntimeError('Initial wakefulness must be Awake/Asleep before any device changes')
        windows = self.call('shell', 'dumpsys', 'window', 'windows').stdout
        focus = re.search(r'mCurrentFocus=Window\{[^\n]*?\s([A-Za-z0-9_.]+/[A-Za-z0-9_.]+)\}', windows)
        state['focus'] = focus.group(1) if focus and focus.group(1) == ORIGINAL_MAIN else None
        if state['default_ime'] != 'null' and not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.]+', state['default_ime']):
            raise RuntimeError('Cannot safely restore the current default IME')
        if state['default_ime'] != 'null' and state['default_ime'].split('/', 1)[0] not in installed:
            raise RuntimeError('The original default IME package is not installed')
        if COMPONENT in enabled_components(state['enabled_imes']) or state['default_ime'] == COMPONENT:
            raise RuntimeError('Stale benchmark IME state must be inspected before ownership is established')
        if any(not re.fullmatch(r'null|-?\d+', state[key]) for _, key in SETTING_KEYS) or state['hwui'] not in ('', 'true', 'false', '0', '1'):
            raise RuntimeError('Unexpected settings values; refuse to change them')
        self.initial = state
        self.args.output.mkdir(parents=True, exist_ok=True)
        save(self.args.output / 'initial-state.json', {'state': state, 'initially_absent': sorted(PACKAGES),
                                                     'installed_packages': sorted(installed), 'window_dump': windows,
                                                     'power_dump': power['power_dump']})
        self.active = True

    def power_state(self):
        power_dump = self.call('shell', 'dumpsys', 'power').stdout
        return {'wakefulness': parse_wakefulness(power_dump), 'power_dump': power_dump}

    def wait_wakefulness(self, expected):
        deadline = time.monotonic() + 10
        while True:
            state = self.power_state()
            if state['wakefulness'] == expected:
                return state
            if time.monotonic() >= deadline:
                raise RuntimeError(f'Device did not become {expected}; last state: {state["wakefulness"]}')
            time.sleep(0.1)

    def wake_for_study(self):
        if self.initial['wakefulness'] == 'Asleep':
            # A directional key is idempotent; never toggle power or bypass a lock screen.
            self.call('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
            save(self.args.output / 'wake-up.json', self.wait_wakefulness('Awake'))

    def restore_wakefulness(self, final):
        expected = self.initial['wakefulness']
        current = self.power_state()
        if current['wakefulness'] != expected:
            key = 'KEYCODE_WAKEUP' if expected == 'Awake' else 'KEYCODE_SLEEP'
            self.call('shell', 'input', 'keyevent', key)
        final.update(self.wait_wakefulness(expected))

    def retain_failed_raw(self):
        retained = []
        for package, name in ((PACKAGE, 'candidate-taps.json'), (HOST, 'candidate-editor.json')):
            if package not in self.owned:
                continue
            entry = {'package': package, 'file': 'failed-' + name, 'valid_sample': False}
            retained.append(entry)
            try:
                raw = self.call('shell', 'run-as', package, 'cat', 'files/' + name, timeout=15).stdout
                (self.args.output / entry['file']).write_text(raw, encoding='utf-8')
                entry['saved'] = True
            except BaseException as error:
                # Missing/partial diagnostics must not replace the original test failure.
                entry.update(saved=False, error=repr(error))
        try:
            save(self.args.output / 'failed-raw-retention.json', retained)
        except OSError as error:
            self.audit_errors.append(repr(error))

    def install(self, path):
        path = str(path.resolve())
        package = self.installs[path]['package']
        if package in self.packages():
            raise RuntimeError('Package appeared before fresh install; it is not owned: ' + package)
        attempt = {'package': package, 'apk': path, 'sha256': self.installs[path]['sha256'],
                   'status': 'pending'}
        self.install_attempts.append(attempt)
        # Persist intent before invoking adb. A timeout/interruption can happen after
        # PackageManager has installed the APK but before adb reports success.
        def record():
            save(self.args.output / 'ownership.json', {'initially_absent': sorted(PACKAGES),
                 'owned': sorted(self.owned), 'install_attempts': self.install_attempts})
        record()
        try:
            result = self.call('install', path, timeout=180)
            if 'Success' not in result.stdout:
                raise RuntimeError('Installation did not report success: ' + package)
            self.owned.add(package)
            attempt['status'] = 'confirmed'
            record()
        except BaseException as error:
            if package not in self.owned:
                attempt['status'] = 'unconfirmed'
            attempt['error'] = repr(error)
            try:
                record()
            except OSError as audit_error:
                self.audit_errors.append(repr(audit_error))
            raise

    def uninstall(self, package):
        if package not in self.owned:
            return
        result = self.call('uninstall', package, timeout=90)
        if 'Success' not in result.stdout or package in self.packages():
            raise RuntimeError('Owned package removal failed: ' + package)
        self.owned.remove(package)

    def clock(self):
        value = self.call('shell', 'date', '+%s.%N').stdout.strip()
        if not re.fullmatch(r'\d+\.\d+', value):
            raise RuntimeError('Device clock cannot delimit this instrumentation run')
        # logcat's epoch format may print only milliseconds. Keep the lower bound
        # at that precision; the independently checked fresh PID rejects old runs.
        return format(Decimal(value).quantize(Decimal('0.001'), rounding=ROUND_FLOOR), 'f')

    def instrument(self, method, revision, prefix):
        self.call('shell', 'am', 'force-stop', PACKAGE)
        deadline = time.monotonic() + 5
        # ActivityManager may return before the killed process disappears from
        # /proc. Wait for that boundary before starting a new instrumentation PID.
        stopping = []
        while True:
            observed = self.call('shell', 'pidof', PACKAGE, check=False)
            observed_at = time.monotonic()
            stopping.append({'at_monotonic_seconds': observed_at, 'returncode': observed.returncode,
                             'stdout': observed.stdout, 'stderr': observed.stderr})
            save(prefix.with_suffix('.process-stop.json'), stopping)
            if not observed.stdout.strip():
                break
            if observed_at >= deadline:
                raise RuntimeError('Previous diagnostic process did not stop')
            time.sleep(0.05)
        checkpoint = self.clock()
        parts = ('shell', 'am', 'instrument', '--no-hidden-api-checks', '-w', '-r', '-e', 'class',
                 'org.fcitx.fcitx5.android.core.CandidateTapTest#' + method, '-e', 'expectedCommit', revision,
                 TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner')
        process = subprocess.Popen(self.command(*parts), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        observed_pid = None
        try:
            if method == 'replay':
                deadline = time.monotonic() + 30
                started = False
                while process.poll() is None and time.monotonic() < deadline:
                    pid = self.call('shell', 'pidof', PACKAGE, check=False).stdout.strip()
                    if re.fullmatch(r'\d+', pid):
                        observed_pid = pid
                        logs = self.call('logcat', '-d', '-v', 'epoch', '-T', checkpoint, '--pid=' + pid,
                                         '-s', 'TestRunner:I', '*:S').stdout
                        for line in logs.splitlines():
                            match = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+I\s+TestRunner\s*:\s*started: replay\(org\.fcitx\.fcitx5\.android\.core\.CandidateTapTest\)', line)
                            if match and match[2] == pid and Decimal(match[1]) >= Decimal(checkpoint):
                                started = True
                                break
                    if started:
                        self.call('shell', 'ime', 'enable', COMPONENT)
                        self.call('shell', 'ime', 'set', COMPONENT)
                        if self.call('shell', 'settings', 'get', 'secure', 'default_input_method').stdout.strip() != COMPONENT:
                            raise RuntimeError('The diagnostic IME was not selected')
                        break
                    time.sleep(0.1)
                if not started:
                    raise RuntimeError('No fresh replay start from the current diagnostic PID')
            stdout, stderr = process.communicate(timeout=900)
            prefix.with_suffix('.instrumentation.txt').write_text(stdout + stderr, encoding='utf-8')
            if process.returncode != 0 or 'OK (1 test)' not in stdout:
                raise RuntimeError('Instrumentation failed: ' + method)
        except BaseException:
            if process.poll() is None:
                process.kill()
            stdout, stderr = process.communicate(timeout=15)
            if not prefix.with_suffix('.instrumentation.txt').exists():
                prefix.with_suffix('.instrumentation.txt').write_text(stdout + stderr, encoding='utf-8')
            raise
        finally:
            try:
                pid = observed_pid or self.call('shell', 'pidof', PACKAGE, check=False).stdout.strip()
                logs = self.call('logcat', '-d', '-v', 'threadtime', '-T', checkpoint, '--pid=' + pid, check=False).stdout if re.fullmatch(r'\d+', pid or '') else 'Diagnostic process PID unavailable; see instrumentation output.\n'
                prefix.with_suffix('.logcat.txt').write_text(logs, encoding='utf-8')
            except Exception as error:
                prefix.with_suffix('.logcat-error.txt').write_text(repr(error), encoding='utf-8')

    def cleanup(self):
        self.restoring = True
        errors = []
        def attempt(name, action):
            try:
                action()
            except BaseException as error:
                errors.append({'action': name, 'error': repr(error)})
        for package in (HOST, PACKAGE):
            if package in self.owned:
                attempt('stop ' + package, lambda package=package: self.call('shell', 'am', 'force-stop', package))
        if self.initial['default_ime'] == 'null':
            attempt('restore default IME', lambda: self.call('shell', 'settings', 'delete', 'secure', 'default_input_method'))
        else:
            attempt('restore default IME', lambda: self.call('shell', 'ime', 'set', self.initial['default_ime']))
        if PACKAGE in self.owned:
            attempt('disable added diagnostic IME', lambda: self.call('shell', 'ime', 'disable', COMPONENT))
        for area, key in SETTING_KEYS:
            value = self.initial[key]
            parts = ('shell', 'settings', 'delete', area, key) if value == 'null' else ('shell', 'settings', 'put', area, key, value)
            attempt('restore ' + key, lambda parts=parts: self.call(*parts))
        attempt('restore hwui', lambda: self.call('shell', 'setprop', PROPERTY, self.initial['hwui'] or "''"))
        for package in (TEST_PACKAGE, PACKAGE, HOST):
            attempt('uninstall ' + package, lambda package=package: self.uninstall(package))
        if self.initial['focus'] == ORIGINAL_MAIN:
            attempt('restore original MainActivity', lambda: self.call('shell', 'am', 'start', '-W', '-n', ORIGINAL_MAIN))
        final = {}
        if self.initial['focus'] == ORIGINAL_MAIN:
            def verify_focus():
                deadline = time.monotonic() + 5
                while True:
                    windows = self.call('shell', 'dumpsys', 'window', 'windows').stdout
                    final['window_dump'] = windows
                    focus = re.search(r'mCurrentFocus=Window\{[^\n]*?\s([A-Za-z0-9_.]+/[A-Za-z0-9_.]+)\}', windows)
                    if focus and focus.group(1) == ORIGINAL_MAIN:
                        final['focus'] = ORIGINAL_MAIN
                        return
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Original MainActivity did not regain focus')
                    time.sleep(0.1)
            attempt('verify original focus', verify_focus)
        for area, key in SETTING_KEYS:
            attempt('verify ' + key, lambda area=area, key=key: final.update({key: self.call('shell', 'settings', 'get', area, key).stdout.strip()}))
        attempt('verify default IME', lambda: final.update(default_ime=self.call('shell', 'settings', 'get', 'secure', 'default_input_method').stdout.strip()))
        attempt('verify enabled IMEs', lambda: final.update(enabled_imes=self.call('shell', 'settings', 'get', 'secure', 'enabled_input_methods').stdout.strip()))
        attempt('verify hwui', lambda: final.update(hwui=self.call('shell', 'getprop', PROPERTY).stdout.strip()))
        for key in ('default_ime', 'hwui', *(key for _, key in SETTING_KEYS)):
            if final.get(key) != self.initial[key]:
                errors.append({'action': 'verify ' + key, 'error': 'Restored value differs from initial state'})
        if enabled_components(final.get('enabled_imes', '')) != enabled_components(self.initial['enabled_imes']):
            errors.append({'action': 'verify enabled IMEs', 'error': 'Enabled IME set changed; unowned IMEs were not modified'})
        # Checking only self.owned misses an install that succeeded on the device
        # while its adb response timed out. Do not adopt/remove an uncertain package
        # by name; report the residue and require identity verification before cleanup.
        attempt('verify diagnostic packages absent', lambda: final.update(
            diagnostic_packages=sorted(self.packages() & PACKAGES)))
        remaining_diagnostic = final.get('diagnostic_packages')
        if remaining_diagnostic:
            errors.append({'action': 'verify diagnostic packages absent',
                           'error': 'Initially absent diagnostic packages remain; unconfirmed apps were not adopted or removed',
                           'packages': remaining_diagnostic})
        elif remaining_diagnostic is None:
            errors.append({'action': 'verify diagnostic packages absent',
                           'error': 'Could not confirm that all initially absent diagnostic packages are absent again'})
        attempt('restore and verify wakefulness', lambda: self.restore_wakefulness(final))
        save(self.args.output / 'restoration.json', {'initial': self.initial, 'final': final,
                                                  'remaining_owned_packages': sorted(self.owned),
                                                  'remaining_diagnostic_packages': remaining_diagnostic,
                                                  'install_attempts': self.install_attempts,
                                                  'audit_errors': self.audit_errors, 'errors': errors})
        return errors


def trace_config():
    return f'''buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
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
 atrace_apps: "{PACKAGE}"
 atrace_apps: "{HOST}"
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ proc_stats_poll_ms: 1000 }} }} }}
'''


def retain_trace(device, pid, remote, local):
    deadline = time.monotonic() + 35
    while device.call('shell', 'test', '-d', '/proc/' + pid, check=False).returncode == 0:
        if time.monotonic() >= deadline:
            break  # Keep a partial file; never kill an unverified/reused device PID.
        time.sleep(0.25)
    device.call('pull', remote, str(local), timeout=60)


def run(args):
    labels, products, fixture, identities, installs = preflight(args)
    metrics = study_module()
    device = OwnedDevice(args, installs)
    device.initialize()
    save(args.output / 'products.json', products)
    save(args.output / 'fixture.manifest.json', fixture)
    save(args.output / 'apk-identities.json', identities)
    plan = {'mode': 'isolated-pilot', 'profile': args.profile, 'serial': args.serial,
            'expected_avd_name': args.expected_avd_name, 'plan': [labels], 'sources': products['sources'],
            'protocol': PROTOCOL, 'fixture_sha256': FIXTURE_SHA256, 'harness_sha256': products['harness_sha256'],
            'script_sha256': digest(Path(__file__)), 'metrics_script_sha256': digest(Path(__file__).with_name('run-study.py')),
            'ownership': 'all three packages absent initially; fresh product/test installation per sample'}
    save(args.output / 'study-plan.json', plan)
    samples, failure, trace = [], None, None
    restoration_errors, cleanup_failure = None, None
    try:
        device.wake_for_study()
        (args.output / 'device-properties.txt').write_text(device.call('shell', 'getprop').stdout, encoding='utf-8')
        (args.output / 'surfaceflinger.txt').write_text(device.call('shell', 'dumpsys', 'SurfaceFlinger').stdout, encoding='utf-8')
        device.call('shell', 'setprop', PROPERTY, 'true')
        device.call('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
        device.call('shell', 'settings', 'put', 'system', 'screen_off_timeout', '1800000')
        device.install(args.host)
        for position, label in enumerate(labels, 1):
            device.call('shell', 'am', 'force-stop', HOST)
            device.uninstall(TEST_PACKAGE)
            device.uninstall(PACKAGE)
            device.install(args.apks / (label + '.apk'))
            device.install(args.apks / (label + '-tests.apk'))
            device.call('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files')
            if digest(args.fixture) != FIXTURE_SHA256:
                raise ValueError('Fixture bytes changed after preflight')
            with args.fixture.open('rb') as source:
                device.call('exec-in', 'run-as', PACKAGE, 'sh', '-c', 'cat > files/candidate-fixture.zip', stdin=source, timeout=180)
            output = args.output / f'b01-p{position:02d}-{label}'
            output.mkdir()
            revision = products['sources'][label]
            device.instrument('prepare', revision, output / (label + '-prepare'))
            device.call('shell', 'am', 'force-stop', HOST)
            device.call('shell', 'pm', 'clear', HOST)
            prefix = output / (label + '-1')
            if args.profile:
                remote = '/data/misc/perfetto-traces/pr32-isolated-' + uuid.uuid4().hex + '.perfetto-trace'
                device.trace_paths.add(remote)
                config = trace_config()
                prefix.with_suffix('.trace-config.txt').write_text(config, encoding='utf-8')
                pid = device.call('shell', 'perfetto', '--background-wait', '--txt', '-c', '-', '-o', remote, input=config).stdout.strip()
                if not re.fullmatch(r'\d+', pid):
                    raise RuntimeError('Perfetto did not return its process ID')
                device.trace_pids.add(pid)
                trace = (pid, remote, prefix.with_suffix('.perfetto-trace'))
            device.instrument('replay', revision, prefix)
            ime = device.call('shell', 'run-as', PACKAGE, 'cat', 'files/candidate-taps.json').stdout
            prefix.with_suffix('.ime.json').write_text(ime, encoding='utf-8')
            device.call('shell', 'am', 'broadcast', '-n', HOST + '/.BenchControl', '--es', 'command', 'dump')
            editor = device.call('shell', 'run-as', HOST, 'cat', 'files/candidate-editor.json').stdout
            prefix.with_suffix('.editor.json').write_text(editor, encoding='utf-8')
            raw = {'ime': json.loads(ime), 'editor': json.loads(editor)}
            save(prefix.with_suffix('.json'), raw)
            sample = {'block': 1, 'position': position, 'label': label, 'source': revision,
                      'raw': str(prefix.with_suffix('.json').relative_to(args.output)),
                      'measurements': metrics.measurements(raw, revision, PROTOCOL)}
            samples.append(sample)
            save(args.output / 'samples.json', samples)
            if trace:
                retain_trace(device, *trace)
                trace = None
    except BaseException as error:
        failure = error
        try:
            save(args.output / 'failure.json', {'error': repr(error), 'completed_samples': len(samples)})
        except OSError as audit_error:
            device.audit_errors.append(repr(audit_error))
        device.retain_failed_raw()
    finally:
        try:
            if trace:
                try:
                    retain_trace(device, *trace)
                except BaseException as error:
                    try:
                        save(args.output / 'trace-retention-failure.json', {'error': repr(error)})
                    except OSError as audit_error:
                        device.audit_errors.append(repr(audit_error))
        finally:
            try:
                restoration_errors = device.cleanup()
            except BaseException as error:
                cleanup_failure = error
    if cleanup_failure:
        finalization_failure = RuntimeError(
            'Device restoration/finalization could not be verified: ' + repr(cleanup_failure)
        )
        if failure:
            raise finalization_failure from failure
        raise finalization_failure from cleanup_failure
    if restoration_errors:
        restoration_failure = RuntimeError('Device restoration needs attention; inspect restoration.json')
        if failure:
            raise restoration_failure from failure
        raise restoration_failure
    if failure:
        raise failure.with_traceback(failure.__traceback__)
    result = {'plan': plan, 'completed_samples': len(samples), 'selection_count': len(samples) * 6,
              'traced': args.profile, 'statistics': metrics.summarize(samples, labels[0])}
    save(args.output / 'summary.json', result)
    return result


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if not args.allow_owned_test_apps:
        parser.error('--allow-owned-test-apps is required')
    run(args)


if __name__ == '__main__':
    main()
