"""All subprocesses are mocked; this suite never contacts adb or installs an APK."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('isolated_study', Path(__file__).with_name('run-isolated-study.py'))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
ORIGINAL = runner.ORIGINAL_MAIN.split('/')[0]
ORIGINAL_IME = ORIGINAL + '/org.fcitx.fcitx5.android.input.FcitxInputMethodService'


def sample(revision):
    rounds, commits = [], []
    for index in range(6):
        origin = 10**18 + index * 1_000_000_000
        def touch(key, offset):
            down = origin + offset * 1_000_000
            return {'key': key, 'planned_down_ns': down, 'planned_up_ns': down + 20_000_000,
                    'inject_down_ns': down + 1_000_000, 'inject_up_ns': down + 21_000_000,
                    'inject_down_return_ns': down + 2_000_000, 'inject_up_return_ns': down + 22_000_000,
                    'dispatch_down_ns': down + 2_000_000, 'dispatch_up_ns': down + 24_000_000,
                    'dispatch_return_ns': down + 25_000_000, 'geometry_valid': True}
        row = {'round': index + 1, 'n': touch('n', 0), 'i': touch('i', 100),
               'select': touch('你', 250), 'ni_ready_ns': origin + 130_000_000,
               'preedit_collected_ns': None}
        rounds.append(row)
        commits.append({'at_ns': row['select']['dispatch_up_ns'] + 10_000_000, 'text': '你'})
    return {'ime': {'commit': revision, 'injection_protocol': runner.PROTOCOL,
                    'model_mapped': True, 'fcitx_core_mapped': False, 'rounds': rounds},
            'editor': {'text': '你' * 6, 'commits': commits}}


class IsolatedStudyTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='pr32-isolated-mock-')
        self.addCleanup(temporary.cleanup)
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.root = Path(temporary.name)
        self.apks = self.root / 'apks'
        self.apks.mkdir()
        self.output = self.root / 'output'
        self.fixture = self.root / 'fixture.zip'
        self.fixture.write_bytes(b'mocked pinned fixture; no actual model is required')
        self.fixture_hash = runner.digest(self.fixture)
        self.stack.enter_context(patch.object(runner, 'FIXTURE_SHA256', self.fixture_hash))
        self.fixture_manifest = self.root / 'fixture.json'
        self.fixture_manifest.write_text(json.dumps({'fixture_sha256': self.fixture_hash}), encoding='utf-8')
        self.host = self.root / 'host.apk'
        self.host.write_bytes(b'mocked neutral host')
        self.sources = dict(zip(('baseline', 'regressed', 'current'), ('a' * 40, 'b' * 40, 'c' * 40)))
        hashes = {}
        for label, revision in self.sources.items():
            (self.apks / (label + '-commit.txt')).write_text(revision, encoding='utf-8')
            for suffix in ('.apk', '-tests.apk'):
                path = self.apks / (label + suffix)
                path.write_bytes(path.name.encode())
                hashes[path.name] = runner.digest(path)
        self.products = {'sources': self.sources, 'apks': hashes, 'protocol': runner.PROTOCOL,
                         'harness_sha256': runner.digest(Path(runner.__file__).with_name('CadenceCandidateTapTest.kt')),
                         'init_script_sha256': runner.digest(Path(runner.__file__).with_name('pr32-bench-application-id.init.gradle')),
                         'host_sha256': runner.digest(self.host)}
        self.write_products()
        self.sdk = self.root / 'sdk'
        tools = self.sdk / 'build-tools/36.1.0'
        (tools / 'lib').mkdir(parents=True)
        self.aapt = tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
        self.aapt.touch()
        (tools / 'lib/apksigner.jar').touch()
        self.java = str(self.root / 'java')
        self.stack.enter_context(patch.dict(os.environ, {'ANDROID_HOME': str(self.sdk)}))
        self.stack.enter_context(patch.object(runner.shutil, 'which', return_value=self.java))
        self.installed = {ORIGINAL, 'com.android.settings'}
        self.default_ime = ORIGINAL_IME
        self.enabled = {ORIGINAL_IME}
        self.settings = {'show_ime_with_hard_keyboard': 'null', 'screen_off_timeout': '60000'}
        self.hwui = ''
        self.focus = runner.ORIGINAL_MAIN
        self.wakefulness = 'Awake'
        self.stuck_wake = False
        self.stuck_sleep = False
        self.wake_timeout = False
        self.fail_raw_read = None
        self.wake_transition = None
        self.sleep_transition = None
        self.pending_wakefulness = []
        self.serial, self.avd = 'emulator-5574', 'rime'
        self.process_id = None
        self.next_pid = 900
        self.revision = None
        self.commands, self.writes = [], []
        self.bad_manifest = None
        self.bad_target = False
        self.bad_authority = False
        self.bad_signer = None
        self.stale_log = False
        self.same_millisecond_log = False
        self.wrong_log_pid = False
        self.fail_replay = False
        self.fail_install = False
        self.fail_after_install = None
        self.fail_restore = False
        self.bad_raw = False
        self.elapsed = 0
        self.argv = ['--serial', self.serial, '--expected-avd-name', self.avd,
                     '--apks', str(self.apks), '--fixture', str(self.fixture),
                     '--fixture-manifest', str(self.fixture_manifest), '--host', str(self.host),
                     '--output', str(self.output)]
        self.run_mock = self.stack.enter_context(patch.object(runner.subprocess, 'run', side_effect=self.fake_run))
        self.popen_mock = self.stack.enter_context(patch.object(runner.subprocess, 'Popen', side_effect=self.fake_popen))
        self.stack.enter_context(patch.object(runner.subprocess, 'check_output', side_effect=AssertionError('Unexpected subprocess API')))
        self.stack.enter_context(patch.object(runner.time, 'sleep', return_value=None))
        self.stack.enter_context(patch.object(runner.time, 'monotonic', side_effect=self.clock))

    def clock(self):
        self.elapsed += 2
        return self.elapsed

    def write_products(self):
        (self.apks / 'products.json').write_text(json.dumps(self.products), encoding='utf-8')

    def fake_run(self, command, **kwargs):
        command = list(map(str, command))
        if command[0] == str(self.aapt):
            name = Path(command[-1]).name
            package = runner.HOST if name == 'host.apk' else runner.TEST_PACKAGE if '-tests.apk' in name else runner.PACKAGE
            if self.bad_manifest == name:
                package = ORIGINAL
            target = (ORIGINAL if self.bad_target else runner.PACKAGE) if '-tests.apk' in name else None
            stdout = f'A: package="{package}" (Raw: "{package}")\n'
            if target:
                stdout += f'A: android:targetPackage(0x01010021)="{target}" (Raw: "{target}")\n'
            if self.bad_authority:
                stdout += f'A: android:authorities(0x01010018)="{ORIGINAL}.provider"\n'
            return subprocess.CompletedProcess(command, 0, stdout, '')
        if command[0] == self.java:
            certificate = 'e' * 64 if self.bad_signer == Path(command[-1]).name else 'd' * 64
            return subprocess.CompletedProcess(command, 0, 'Signer #1 certificate SHA-256 digest: ' + certificate + '\n', '')
        self.assertEqual(['adb', '-s', 'emulator-5574'], command[:3])
        self.commands.append(command)
        p = command[3:]
        stdout, code = '', 0
        if p == ['get-serialno']:
            stdout = self.serial
        elif p == ['get-state']:
            stdout = 'device'
        elif p == ['emu', 'avd', 'name']:
            stdout = self.avd + '\nOK\n'
        elif p == ['shell', 'pm', 'list', 'packages']:
            stdout = '\n'.join('package:' + name for name in sorted(self.installed))
        elif p[:3] == ['shell', 'settings', 'get']:
            key = p[4]
            stdout = self.default_ime if key == 'default_input_method' else ':'.join(sorted(self.enabled)) if key == 'enabled_input_methods' else self.settings[key]
        elif p == ['shell', 'getprop', runner.PROPERTY]:
            stdout = self.hwui
        elif p == ['shell', 'getprop']:
            stdout = '[ro.hardware.egl]: [emulation]\n'
        elif p == ['shell', 'dumpsys', 'window', 'windows']:
            stdout = 'mCurrentFocus=Window{123 u0 ' + self.focus + '}\n' if self.focus else 'mCurrentFocus=null\n'
        elif p == ['shell', 'dumpsys', 'SurfaceFlinger']:
            stdout = 'GLES: NVIDIA, RTX 4060\n'
        elif p == ['shell', 'dumpsys', 'power']:
            stdout = 'Power Manager State:\n  mWakefulness=' + self.wakefulness + '\n'
            if self.pending_wakefulness:
                self.wakefulness = self.pending_wakefulness.pop(0)
        elif p[:3] == ['shell', 'input', 'keyevent']:
            self.writes.append(p)
            self.assertTrue((self.output / 'initial-state.json').is_file())
            self.assertIn(p[3], ('KEYCODE_WAKEUP', 'KEYCODE_SLEEP'))
            if p[3] == 'KEYCODE_WAKEUP':
                if not self.stuck_wake:
                    states = self.wake_transition or ['Awake']
                    self.wakefulness, self.pending_wakefulness = states[0], list(states[1:])
                if self.wake_timeout:
                    raise subprocess.TimeoutExpired(command, 60)
            elif not self.stuck_sleep:
                states = self.sleep_transition or ['Asleep']
                self.wakefulness, self.pending_wakefulness = states[0], list(states[1:])
        elif p == ['shell', 'date', '+%s.%N']:
            stdout = '1700000000.123456789'
        elif p[:2] == ['shell', 'pidof']:
            stdout = str(self.process_id) if self.process_id else ''
            code = 0 if self.process_id else 1
        elif p[0] == 'logcat':
            self.assertNotIn('-c', p)
            self.assertIn('-T', p)
            pid = next(part.split('=', 1)[1] for part in p if part.startswith('--pid='))
            if self.wrong_log_pid:
                pid = str(int(pid) + 1)
            timestamp = '1699999999.999999999' if self.stale_log else '1700000001.000000000'
            if self.same_millisecond_log:
                timestamp = '1700000000.123'
            stdout = f'{timestamp} {pid} {pid} I TestRunner: started: replay(org.fcitx.fcitx5.android.core.CandidateTapTest)\n'
        elif p[0] == 'install':
            self.writes.append(p)
            if self.fail_install:
                raise subprocess.CalledProcessError(1, command, 'INSTALL_FAILED', '')
            name = Path(p[1]).name
            package = runner.HOST if name == 'host.apk' else runner.TEST_PACKAGE if '-tests.apk' in name else runner.PACKAGE
            self.assertNotIn(package, self.installed)
            self.installed.add(package)
            if self.fail_after_install == 'timeout':
                raise subprocess.TimeoutExpired(command, 180)
            if self.fail_after_install == 'interrupt':
                raise KeyboardInterrupt('Interrupted after PackageManager installed the APK')
            if self.fail_after_install == 'missing-success':
                return subprocess.CompletedProcess(command, 0, '', '')
            stdout = 'Success\n'
        elif p[0] == 'uninstall':
            self.writes.append(p)
            self.assertIn(p[1], runner.PACKAGES)
            self.installed.discard(p[1])
            if p[1] == runner.PACKAGE:
                self.enabled.discard(runner.COMPONENT)
                if self.default_ime == runner.COMPONENT:
                    self.default_ime = ORIGINAL_IME
            stdout = 'Success\n'
        elif p[:3] == ['shell', 'am', 'force-stop']:
            self.writes.append(p)
            self.assertIn(p[3], runner.PACKAGES)
            if p[3] == runner.PACKAGE:
                self.process_id = None
        elif p[:3] == ['shell', 'pm', 'clear']:
            self.writes.append(p)
            self.assertIn(p[3], runner.PACKAGES)
            stdout = 'Success\n'
        elif p[:3] in (['shell', 'settings', 'put'], ['shell', 'settings', 'delete']):
            self.writes.append(p)
            self.settings[p[4]] = p[5] if p[2] == 'put' else 'null'
        elif p[:3] == ['shell', 'setprop', runner.PROPERTY]:
            self.writes.append(p)
            if self.fail_restore and p[3] == "''":
                raise subprocess.CalledProcessError(1, command, '', 'restore failed')
            self.hwui = '' if p[3] == "''" else p[3]
        elif p[:2] == ['shell', 'ime']:
            self.writes.append(p)
            if p[2] == 'set':
                self.default_ime = p[3]
            elif p[2] == 'enable':
                self.enabled.add(p[3])
            elif p[2] == 'disable':
                self.enabled.discard(p[3])
        elif p[:3] == ['shell', 'am', 'start']:
            self.writes.append(p)
            self.assertEqual(runner.ORIGINAL_MAIN, p[-1])
        elif p[:2] == ['exec-in', 'run-as']:
            self.writes.append(p)
            self.assertEqual(runner.PACKAGE, p[2])
            self.assertEqual(self.fixture.read_bytes(), kwargs['stdin'].read())
        elif p[:3] == ['shell', 'run-as', runner.PACKAGE] and p[3] == 'mkdir':
            self.writes.append(p)
        elif p[:2] == ['shell', 'run-as'] and p[3] == 'cat':
            if self.fail_raw_read == p[2]:
                raise subprocess.CalledProcessError(1, command, '', 'No such diagnostic file')
            data = sample(self.revision)
            if self.fail_replay:
                data['ime']['diagnostic_incomplete'] = True
            if self.bad_raw:
                data['editor']['text'] = 'wrong'
            stdout = json.dumps(data['ime'] if p[2] == runner.PACKAGE else data['editor'])
        elif p[:3] == ['shell', 'am', 'broadcast']:
            self.writes.append(p)
            self.assertEqual(runner.HOST + '/.BenchControl', p[4])
        elif p[:2] == ['shell', 'perfetto']:
            self.writes.append(p)
            self.assertIn(runner.PACKAGE, kwargs['input'])
            stdout = '3333\n'
        elif p[:3] == ['shell', 'test', '-d']:
            code = 1
        elif p[0] == 'pull':
            self.assertTrue(p[1].startswith('/data/misc/perfetto-traces/pr32-isolated-'))
            Path(p[2]).write_bytes(b'mocked trace')
        else:
            raise AssertionError('Unexpected mocked device command: ' + repr(p))
        if kwargs.get('check', True) and code:
            raise subprocess.CalledProcessError(code, command, stdout, '')
        return subprocess.CompletedProcess(command, code, stdout, '')

    def fake_popen(self, command, **kwargs):
        self.assertEqual(['adb', '-s', 'emulator-5574'], command[:3])
        self.assertEqual('Awake', self.wakefulness)
        self.commands.append(command)
        self.writes.append(command[3:])
        self.assertEqual(runner.TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner', command[-1])
        self.revision = command[command.index('expectedCommit') + 1]
        self.next_pid += 1
        self.process_id = self.next_pid
        failed = self.fail_replay and any('#replay' in part for part in command)
        process = Mock(returncode=None)
        process.poll.side_effect = lambda: process.returncode
        def communicate(**ignored):
            process.returncode = 1 if failed else 0
            return ('FAILURES!!!' if failed else 'OK (1 test)', '')
        process.communicate.side_effect = communicate
        process.kill.side_effect = lambda: setattr(process, 'returncode', -9)
        return process

    def main(self, extra=()):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            runner.main([*self.argv, *extra])

    def assert_restored(self, wakefulness='Awake'):
        self.assertEqual({ORIGINAL, 'com.android.settings'}, self.installed)
        self.assertEqual(ORIGINAL_IME, self.default_ime)
        self.assertEqual({ORIGINAL_IME}, self.enabled)
        self.assertEqual({'show_ime_with_hard_keyboard': 'null', 'screen_off_timeout': '60000'}, self.settings)
        self.assertEqual('', self.hwui)
        self.assertEqual(wakefulness, self.wakefulness)
        restoration = json.loads((self.output / 'restoration.json').read_text(encoding='utf-8'))
        self.assertEqual(wakefulness, restoration['initial']['wakefulness'])
        self.assertEqual(wakefulness, restoration['final']['wakefulness'])
        self.assertEqual([], restoration['errors'])
        self.assertEqual([], restoration['remaining_owned_packages'])
        self.assertEqual([], restoration['remaining_diagnostic_packages'])

    def test_import_does_not_execute_subprocesses(self):
        module = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(module)
        self.run_mock.assert_not_called()
        self.popen_mock.assert_not_called()

    def test_explicit_authorization_and_identity_arguments_required(self):
        with self.assertRaises(SystemExit):
            self.main()
        for option in ('--serial', '--expected-avd-name'):
            argv = self.argv.copy()
            index = argv.index(option)
            del argv[index:index + 2]
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                runner.main([*argv, '--allow-owned-test-apps'])
        self.run_mock.assert_not_called()
        self.popen_mock.assert_not_called()

    def test_all_three_initially_absent_is_mandatory(self):
        for package in runner.PACKAGES:
            with self.subTest(package=package):
                self.installed.add(package)
                with self.assertRaisesRegex(RuntimeError, 'initially be absent'):
                    self.main(['--allow-owned-test-apps'])
                self.installed.remove(package)
                self.assertEqual([], self.writes)
                self.assertFalse(self.output.exists())

    def test_wrong_avd_fails_before_writes(self):
        self.avd = 'different-avd'
        self.wakefulness = 'Asleep'
        with self.assertRaisesRegex(RuntimeError, 'AVD identity mismatch'):
            self.main(['--allow-owned-test-apps'])
        self.assertEqual([], self.writes)

    def test_windows_console_empty_lines_preserve_exact_avd_validation(self):
        self.avd = 'rime\n\n'
        self.main(['--allow-owned-test-apps'])
        self.assert_restored()

    def test_process_disappearance_is_awaited_after_force_stop(self):
        original = runner.OwnedDevice.call
        remaining = [2]
        def call(device, *parts, **kwargs):
            if parts == ('shell', 'pidof', runner.PACKAGE) and remaining[0]:
                remaining[0] -= 1
                return subprocess.CompletedProcess(parts, 0, stdout='899\n', stderr='')
            return original(device, *parts, **kwargs)
        with patch.object(runner.OwnedDevice, 'call', new=call):
            self.main(['--allow-owned-test-apps'])
        self.assertEqual(remaining[0], 0)
        self.assert_restored()

    def test_process_that_does_not_stop_fails_and_restores_owned_apps(self):
        original = runner.OwnedDevice.call
        def call(device, *parts, **kwargs):
            if parts == ('shell', 'pidof', runner.PACKAGE):
                return subprocess.CompletedProcess(parts, 0, stdout='899\n', stderr='')
            return original(device, *parts, **kwargs)
        with patch.object(runner.OwnedDevice, 'call', new=call), self.assertRaisesRegex(RuntimeError, 'did not stop'):
            self.main(['--allow-owned-test-apps'])
        self.assert_restored()

    def test_apk_package_target_and_signer_are_checked_before_adb(self):
        for field, value in (('bad_manifest', 'baseline.apk'), ('bad_manifest', 'host.apk'),
                             ('bad_target', True), ('bad_authority', True), ('bad_signer', 'regressed-tests.apk')):
            with self.subTest(field=field):
                setattr(self, field, value)
                with self.assertRaises(ValueError):
                    self.main(['--allow-owned-test-apps'])
                setattr(self, field, False if field in ('bad_target', 'bad_authority') else None)
                self.assertEqual([], self.commands)

    def test_host_probe_overlay_fixture_and_apk_hashes_are_pinned(self):
        for key in ('host_sha256', 'harness_sha256', 'init_script_sha256'):
            original = self.products[key]
            self.products[key] = '0' * 64
            self.write_products()
            with self.assertRaises(ValueError):
                self.main(['--allow-owned-test-apps'])
            self.products[key] = original
        self.write_products()
        self.fixture.write_bytes(b'changed fixture')
        with self.assertRaisesRegex(ValueError, 'pinned 333e'):
            self.main(['--allow-owned-test-apps'])
        self.assertEqual([], self.commands)

    def test_changed_apk_and_commit_provenance_fail_before_adb(self):
        commit_file = self.apks / 'baseline-commit.txt'
        commit_file.write_text('f' * 40, encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Commit file'):
            self.main(['--allow-owned-test-apps'])
        commit_file.write_text(self.sources['baseline'], encoding='utf-8')
        (self.apks / 'baseline.apk').write_bytes(b'changed build output')
        with self.assertRaisesRegex(ValueError, 'APK hash mismatch'):
            self.main(['--allow-owned-test-apps'])
        self.assertEqual([], self.commands)

    def test_previous_output_is_never_overwritten(self):
        self.output.mkdir()
        evidence = self.output / 'keep.txt'
        evidence.write_text('previous evidence', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Output must be empty'):
            self.main(['--allow-owned-test-apps'])
        self.assertEqual('previous evidence', evidence.read_text(encoding='utf-8'))
        self.assertEqual([], self.commands)

    def test_successful_three_product_pilot_restores_everything(self):
        self.main(['--allow-owned-test-apps'])
        self.assert_restored()
        summary = json.loads((self.output / 'summary.json').read_text(encoding='utf-8'))
        self.assertEqual(3, summary['completed_samples'])
        self.assertEqual(18, summary['selection_count'])
        samples = json.loads((self.output / 'samples.json').read_text(encoding='utf-8'))
        self.assertEqual(['baseline', 'regressed', 'current'], [row['label'] for row in samples])
        self.assertEqual(6, len(samples[0]['measurements']['rounds']))
        for command in self.commands:
            p = command[3:]
            if p[:3] in (['shell', 'pm', 'clear'], ['shell', 'am', 'force-stop']):
                self.assertIn(p[3], runner.PACKAGES)
            self.assertNotEqual(['logcat', '-c'], p)
        self.assertIn(['shell', 'am', 'start', '-W', '-n', runner.ORIGINAL_MAIN], self.writes)
        self.assertFalse(any(p[:3] == ['shell', 'input', 'keyevent'] for p in self.writes))

    def test_asleep_pilot_wakes_after_initial_state_and_restores_sleep(self):
        self.wakefulness, self.focus = 'Asleep', None
        self.main(['--allow-owned-test-apps', '--order', 'current'])
        self.assert_restored('Asleep')
        keys = [p for p in self.writes if p[:3] == ['shell', 'input', 'keyevent']]
        self.assertEqual([['shell', 'input', 'keyevent', key] for key in ('KEYCODE_WAKEUP', 'KEYCODE_SLEEP')], keys)
        self.assertEqual(keys[0], self.writes[0])
        self.assertEqual(keys[-1], self.writes[-1])
        initial = json.loads((self.output / 'initial-state.json').read_text(encoding='utf-8'))
        self.assertEqual('Asleep', initial['state']['wakefulness'])
        self.assertIn('mWakefulness=Asleep', initial['power_dump'])
        self.assertEqual('Awake', json.loads((self.output / 'wake-up.json').read_text(encoding='utf-8'))['wakefulness'])

    def test_transitional_wakefulness_is_polled_until_terminal_state(self):
        self.wakefulness, self.focus = 'Asleep', None
        self.wake_transition = ['Dozing', 'Awake']
        self.sleep_transition = ['Dreaming', 'Asleep']
        self.main(['--allow-owned-test-apps', '--order', 'current'])
        self.assert_restored('Asleep')
        keys = [p for p in self.writes if p[:3] == ['shell', 'input', 'keyevent']]
        self.assertEqual([['shell', 'input', 'keyevent', key] for key in ('KEYCODE_WAKEUP', 'KEYCODE_SLEEP')], keys)

    def test_initial_awake_is_restored_if_device_sleeps_before_cleanup(self):
        original_cleanup = runner.OwnedDevice.cleanup

        def cleanup(device):
            self.wakefulness = 'Asleep'
            return original_cleanup(device)

        with patch.object(runner.OwnedDevice, 'cleanup', new=cleanup):
            self.main(['--allow-owned-test-apps', '--order', 'current'])
        self.assert_restored('Awake')
        keys = [p for p in self.writes if p[:3] == ['shell', 'input', 'keyevent']]
        self.assertEqual([['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']], keys)

    def test_unknown_or_ambiguous_initial_wakefulness_fails_before_mutations(self):
        for state in ('Dozing', 'Dreaming', '', 'Awake\n  mWakefulness=Asleep'):
            with self.subTest(state=state):
                self.wakefulness = state
                with self.assertRaisesRegex(RuntimeError, 'Awake/Asleep'):
                    self.main(['--allow-owned-test-apps'])
                self.assertEqual([], self.writes)
                self.assertFalse(self.output.exists())

    def test_failed_replay_restores_initial_sleep(self):
        self.wakefulness, self.focus = 'Asleep', None
        self.fail_replay = True
        with self.assertRaisesRegex(RuntimeError, 'Instrumentation failed'):
            self.main(['--allow-owned-test-apps'])
        self.assert_restored('Asleep')

    def test_wake_timeout_after_waking_still_restores_sleep(self):
        self.wakefulness, self.focus = 'Asleep', None
        self.wake_timeout = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.main(['--allow-owned-test-apps'])
        self.assert_restored('Asleep')
        self.assertFalse(any(p[0] == 'install' for p in self.writes))

    def test_unconfirmed_wake_never_starts_instrumentation(self):
        self.wakefulness, self.focus = 'Asleep', None
        self.stuck_wake = True
        with self.assertRaisesRegex(RuntimeError, 'did not become Awake'):
            self.main(['--allow-owned-test-apps'])
        self.assert_restored('Asleep')
        self.popen_mock.assert_not_called()
        self.assertFalse(any(p[0] == 'install' for p in self.writes))

    def test_failed_sleep_verification_is_a_restoration_failure(self):
        self.wakefulness, self.focus = 'Asleep', None
        self.stuck_sleep = True
        with self.assertRaisesRegex(RuntimeError, 'restoration needs attention'):
            self.main(['--allow-owned-test-apps', '--order', 'current'])
        restoration = json.loads((self.output / 'restoration.json').read_text(encoding='utf-8'))
        self.assertTrue(any(error['action'] == 'restore and verify wakefulness' for error in restoration['errors']))
        self.assertEqual({ORIGINAL, 'com.android.settings'}, self.installed)
        self.assertFalse((self.output / 'summary.json').exists())

    def test_failed_replay_keeps_raw_evidence_and_restores_state(self):
        self.fail_replay = True
        with self.assertRaisesRegex(RuntimeError, 'Instrumentation failed'):
            self.main(['--allow-owned-test-apps'])
        self.assert_restored()
        evidence = self.output / 'b01-p01-baseline/baseline-1.instrumentation.txt'
        self.assertIn('FAILURES', evidence.read_text(encoding='utf-8'))
        self.assertTrue((self.output / 'failure.json').exists())
        retained = json.loads((self.output / 'failed-raw-retention.json').read_text(encoding='utf-8'))
        self.assertEqual({runner.PACKAGE, runner.HOST}, {item['package'] for item in retained})
        self.assertTrue(all(item['saved'] and not item['valid_sample'] for item in retained))
        raw = json.loads((self.output / 'failed-candidate-taps.json').read_text(encoding='utf-8'))
        self.assertTrue(raw['diagnostic_incomplete'])
        self.assertTrue((self.output / 'failed-candidate-editor.json').is_file())
        self.assertFalse((self.output / 'samples.json').exists())
        self.assertFalse((self.output / 'summary.json').exists())

    def test_missing_failure_diagnostics_do_not_mask_original_failure(self):
        self.fail_replay, self.fail_raw_read = True, runner.HOST
        with self.assertRaisesRegex(RuntimeError, 'Instrumentation failed: replay'):
            self.main(['--allow-owned-test-apps'])
        self.assert_restored()
        retained = json.loads((self.output / 'failed-raw-retention.json').read_text(encoding='utf-8'))
        self.assertEqual([True, False], [entry['saved'] for entry in retained])
        self.assertIn('CalledProcessError', retained[1]['error'])
        self.assertFalse((self.output / 'summary.json').exists())

    def test_invalid_measurements_preserve_raw_json_and_restore(self):
        self.bad_raw = True
        with self.assertRaisesRegex(ValueError, 'six successful'):
            self.main(['--allow-owned-test-apps'])
        self.assertTrue((self.output / 'b01-p01-baseline/baseline-1.json').exists())
        self.assert_restored()

    def test_stale_started_line_from_same_pid_does_not_select_ime(self):
        self.stale_log = True
        with self.assertRaisesRegex(RuntimeError, 'No fresh replay start'):
            self.main(['--allow-owned-test-apps'])
        self.assertNotIn(['shell', 'ime', 'set', runner.COMPONENT], self.writes)
        self.assert_restored()

    def test_started_line_from_another_pid_does_not_select_ime(self):
        self.wrong_log_pid = True
        with self.assertRaisesRegex(RuntimeError, 'No fresh replay start'):
            self.main(['--allow-owned-test-apps'])
        self.assertNotIn(['shell', 'ime', 'set', runner.COMPONENT], self.writes)
        self.assert_restored()

    def test_logcat_millisecond_precision_accepts_a_fresh_process_start(self):
        self.same_millisecond_log = True
        self.main(['--allow-owned-test-apps'])
        self.assert_restored()

    def test_failed_install_never_claims_or_uninstalls_an_app(self):
        self.fail_install = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.main(['--allow-owned-test-apps'])
        self.assertFalse(any(p[0] == 'uninstall' for p in self.writes))
        self.assert_restored()

    def assert_unconfirmed_install_is_reported(self):
        self.assertIn(runner.HOST, self.installed)
        self.assertFalse(any(p[0] == 'uninstall' for p in self.writes))
        self.assertFalse(any(p[:3] in (['shell', 'am', 'force-stop'], ['shell', 'pm', 'clear'])
                             and p[3] == runner.HOST for p in self.writes))
        restoration = json.loads((self.output / 'restoration.json').read_text(encoding='utf-8'))
        self.assertEqual([], restoration['remaining_owned_packages'])
        self.assertEqual([runner.HOST], restoration['remaining_diagnostic_packages'])
        self.assertTrue(restoration['errors'])
        self.assertEqual('unconfirmed', restoration['install_attempts'][0]['status'])
        self.assertEqual(runner.digest(self.host), restoration['install_attempts'][0]['sha256'])
        ownership = json.loads((self.output / 'ownership.json').read_text(encoding='utf-8'))
        self.assertEqual('unconfirmed', ownership['install_attempts'][0]['status'])
        self.assertEqual(ORIGINAL_IME, self.default_ime)
        self.assertEqual({'show_ime_with_hard_keyboard': 'null', 'screen_off_timeout': '60000'}, self.settings)
        self.assertEqual('', self.hwui)
        self.assertFalse((self.output / 'summary.json').exists())
        self.assertFalse(any(command[3:5] == ['shell', 'run-as'] for command in self.commands))

    def test_timeout_after_device_installs_does_not_claim_restoration_success(self):
        self.fail_after_install = 'timeout'
        with self.assertRaisesRegex(RuntimeError, 'restoration needs attention') as caught:
            self.main(['--allow-owned-test-apps'])
        self.assertIsInstance(caught.exception.__cause__, subprocess.TimeoutExpired)
        self.assert_unconfirmed_install_is_reported()

    def test_interruption_after_device_installs_keeps_uncertain_app_unowned(self):
        self.fail_after_install = 'interrupt'
        with self.assertRaisesRegex(RuntimeError, 'restoration needs attention') as caught:
            self.main(['--allow-owned-test-apps'])
        self.assertIsInstance(caught.exception.__cause__, KeyboardInterrupt)
        self.assert_unconfirmed_install_is_reported()

    def test_missing_success_response_cannot_hide_an_installed_package(self):
        self.fail_after_install = 'missing-success'
        with self.assertRaisesRegex(RuntimeError, 'restoration needs attention') as caught:
            self.main(['--allow-owned-test-apps'])
        self.assertIn('Installation did not report success', str(caught.exception.__cause__))
        self.assert_unconfirmed_install_is_reported()

    def test_restore_failure_does_not_prevent_other_cleanup(self):
        self.fail_restore = True
        with self.assertRaisesRegex(RuntimeError, 'restoration needs attention'):
            self.main(['--allow-owned-test-apps'])
        self.assertEqual({ORIGINAL, 'com.android.settings'}, self.installed)
        self.assertEqual(ORIGINAL_IME, self.default_ime)
        self.assertIn(['shell', 'am', 'start', '-W', '-n', runner.ORIGINAL_MAIN], self.writes)
        self.assertTrue(json.loads((self.output / 'restoration.json').read_text(encoding='utf-8'))['errors'])

    def test_primary_failure_cannot_hide_restoration_failure(self):
        self.fail_replay = True
        self.fail_restore = True
        with self.assertRaisesRegex(RuntimeError, 'restoration needs attention') as caught:
            self.main(['--allow-owned-test-apps'])
        self.assertIsInstance(caught.exception.__cause__, RuntimeError)
        self.assertIn('Instrumentation failed: replay', str(caught.exception.__cause__))
        self.assertEqual({ORIGINAL, 'com.android.settings'}, self.installed)
        self.assertTrue(json.loads((self.output / 'restoration.json').read_text(encoding='utf-8'))['errors'])

    def test_other_foreground_activity_is_not_replaced_by_main(self):
        self.focus = 'com.android.settings/.Settings'
        self.main(['--allow-owned-test-apps'])
        self.assertFalse(any(p[:3] == ['shell', 'am', 'start'] for p in self.writes))
        self.assert_restored()

    def test_profile_is_retained_even_after_failure(self):
        self.fail_replay = True
        with self.assertRaises(RuntimeError):
            self.main(['--allow-owned-test-apps', '--profile'])
        self.assertTrue((self.output / 'b01-p01-baseline/baseline-1.perfetto-trace').is_file())
        self.assert_restored()

    def test_trace_and_audit_retention_failures_cannot_skip_restoration(self):
        original_save = runner.save

        def save(path, value):
            if path.name == 'trace-retention-failure.json':
                raise OSError('mocked full audit volume')
            return original_save(path, value)

        with patch.object(runner, 'retain_trace', side_effect=RuntimeError('mocked trace pull failure')):
            with patch.object(runner, 'save', side_effect=save):
                with self.assertRaisesRegex(RuntimeError, 'mocked trace pull failure'):
                    self.main(['--allow-owned-test-apps', '--profile'])
        self.assert_restored()
        restoration = json.loads((self.output / 'restoration.json').read_text(encoding='utf-8'))
        self.assertTrue(any('mocked full audit volume' in error for error in restoration['audit_errors']))

    def test_restoration_report_failure_preserves_primary_failure(self):
        self.fail_replay = True
        original_save = runner.save

        def save(path, value):
            if path.name == 'restoration.json':
                raise OSError('mocked restoration report failure')
            return original_save(path, value)

        with patch.object(runner, 'save', side_effect=save):
            with self.assertRaisesRegex(RuntimeError, 'restoration/finalization could not be verified.*mocked') as caught:
                self.main(['--allow-owned-test-apps'])
        self.assertIsInstance(caught.exception.__cause__, RuntimeError)
        self.assertIn('Instrumentation failed: replay', str(caught.exception.__cause__))
        self.assertFalse((self.output / 'restoration.json').exists())
        self.assertEqual({ORIGINAL, 'com.android.settings'}, self.installed)
        self.assertEqual(ORIGINAL_IME, self.default_ime)

    def test_restoration_report_failure_without_primary_is_explicit(self):
        original_save = runner.save

        def save(path, value):
            if path.name == 'restoration.json':
                raise OSError('mocked restoration report failure')
            return original_save(path, value)

        with patch.object(runner, 'save', side_effect=save):
            with self.assertRaisesRegex(RuntimeError, 'restoration/finalization could not be verified.*mocked') as caught:
                self.main(['--allow-owned-test-apps'])
        self.assertIsInstance(caught.exception.__cause__, OSError)
        self.assertFalse((self.output / 'restoration.json').exists())
        self.assertEqual({ORIGINAL, 'com.android.settings'}, self.installed)
        self.assertEqual(ORIGINAL_IME, self.default_ime)
        self.assertEqual({ORIGINAL_IME}, self.enabled)
        self.assertEqual({'show_ime_with_hard_keyboard': 'null', 'screen_off_timeout': '60000'}, self.settings)
        self.assertEqual('', self.hwui)

    def test_allowlist_rejects_unowned_package_and_arbitrary_shell(self):
        args = runner.build_parser().parse_args([*self.argv, '--allow-owned-test-apps'])
        _, _, _, _, installs = runner.preflight(args)
        device = runner.OwnedDevice(args, installs)
        device.initialize()
        for parts in (('uninstall', ORIGINAL), ('shell', 'pm', 'clear', ORIGINAL),
                      ('shell', 'am', 'force-stop', ORIGINAL), ('shell', 'sh', '-c', 'echo arbitrary'),
                      ('uninstall', runner.PACKAGE), ('install', '-r', str(self.apks / 'baseline.apk')),
                      ('shell', 'ime', 'set', ORIGINAL_IME), ('logcat', '-c')):
            with self.subTest(parts=parts), self.assertRaises(ValueError):
                device.permit(parts)
        device.owned.add(ORIGINAL)  # Even corrupt bookkeeping cannot authorize the user's package.
        with self.assertRaises(ValueError):
            device.permit(('uninstall', ORIGINAL))

    def test_power_keys_are_directional_and_gated_by_initial_state_and_phase(self):
        args = runner.build_parser().parse_args([*self.argv, '--allow-owned-test-apps'])
        _, _, _, _, installs = runner.preflight(args)
        device = runner.OwnedDevice(args, installs)
        wake = ('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        sleep = ('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
        device.permit(('shell', 'dumpsys', 'power'))
        for command in (wake, sleep):
            with self.assertRaises(ValueError):
                device.permit(command)
        self.wakefulness = 'Asleep'
        device.initialize()
        device.permit(wake)
        with self.assertRaises(ValueError):
            device.permit(sleep)
        device.restoring = True
        device.permit(sleep)
        with self.assertRaises(ValueError):
            device.permit(wake)
        device.initial['wakefulness'] = 'Awake'
        device.restoring = False
        for command in (wake, sleep, ('shell', 'input', 'keyevent', 'KEYCODE_POWER'),
                        ('shell', 'input', 'keyevent', '26'), ('shell', 'wm', 'dismiss-keyguard')):
            with self.subTest(command=command, restoring=False), self.assertRaises(ValueError):
                device.permit(command)
        device.restoring = True
        device.permit(wake)
        for command in (sleep, ('shell', 'input', 'keyevent', 'KEYCODE_POWER'),
                        ('shell', 'input', 'keyevent', '26'), ('shell', 'wm', 'dismiss-keyguard')):
            with self.subTest(command=command, restoring=True), self.assertRaises(ValueError):
                device.permit(command)
        self.assertEqual([], self.writes)

    def test_audit_write_failure_cannot_block_restoration(self):
        original_cleanup, original_save = runner.OwnedDevice.cleanup, runner.save
        def cleanup(device):
            def save(path, value):
                if path.name == 'commands.json':
                    raise OSError('mocked full audit volume')
                return original_save(path, value)
            with patch.object(runner, 'save', side_effect=save):
                return original_cleanup(device)
        with patch.object(runner.OwnedDevice, 'cleanup', new=cleanup):
            self.main(['--allow-owned-test-apps'])
        self.assert_restored()
        restoration = json.loads((self.output / 'restoration.json').read_text(encoding='utf-8'))
        self.assertTrue(restoration['audit_errors'])

    def test_install_rechecks_initial_absence_and_apk_hash(self):
        args = runner.build_parser().parse_args([*self.argv, '--allow-owned-test-apps'])
        _, _, _, _, installs = runner.preflight(args)
        device = runner.OwnedDevice(args, installs)
        device.initialize()
        self.installed.add(runner.PACKAGE)
        with self.assertRaisesRegex(RuntimeError, 'appeared before fresh install'):
            device.install(self.apks / 'baseline.apk')
        self.installed.remove(runner.PACKAGE)
        (self.apks / 'baseline.apk').write_bytes(b'changed after verification')
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            device.install(self.apks / 'baseline.apk')
        self.assertEqual([], self.writes)


class ApkIdentityFormatTest(unittest.TestCase):
    namespaces = ('android:', 'http://schemas.android.com/apk/res/android:')

    def identity(self, attribute_lines):
        tree = f'E: manifest\n  A: package="{runner.TEST_PACKAGE}"\n' + attribute_lines
        signature = 'V2 Signer: certificate SHA-256 digest: ' + 'a' * 64 + '\n'
        with patch.object(runner, 'host_command', side_effect=(tree, signature)):
            return runner.apk_identity(Path('sample.apk'), 'aapt2', 'java', 'apksigner.jar')

    def test_short_and_uri_namespace_targets_and_authorities(self):
        for namespace in self.namespaces:
            with self.subTest(namespace=namespace):
                identity = self.identity(
                    f'  A: {namespace}targetPackage(0x01010021)="{runner.PACKAGE}"\n'
                    f'  A: {namespace}authorities(0x01010018)="{runner.TEST_PACKAGE}.files"\n')
                self.assertEqual(runner.PACKAGE, identity['target'])
                self.assertEqual([runner.TEST_PACKAGE + '.files'], identity['authorities'])

    def test_reject_unowned_provider_and_shared_uid_in_both_formats(self):
        for namespace in self.namespaces:
            for attribute in ('authorities(0x01010018)="unowned.provider"',
                              'sharedUserId(0x0101000b)="android.uid.system"'):
                with self.subTest(namespace=namespace, attribute=attribute), self.assertRaises(ValueError):
                    self.identity(f'  A: {namespace}{attribute}\n')

    def test_unresolved_manifest_attributes_are_not_ignored(self):
        for namespace in self.namespaces:
            with self.subTest(namespace=namespace), self.assertRaisesRegex(ValueError, 'Cannot resolve'):
                self.identity(f'  A: {namespace}authorities(0x01010018)=@0x7f000001\n')


if __name__ == '__main__':
    unittest.main()
