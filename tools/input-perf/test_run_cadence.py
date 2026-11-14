#!/usr/bin/env python3
"""Device-safety regression tests. Every subprocess entry point is mocked."""
import ast
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).with_name('run-cadence.py')
SPEC = importlib.util.spec_from_file_location('run_cadence', SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
PROTOCOL = 'fixed_cadence_100ms_input_manager_async_cached_targets_v2'
SERIAL = 'emulator-5574'
PACKAGE = 'org.fcitx.fcitx5.android.fx.rime.debug'
COMPONENT = PACKAGE + '/org.fcitx.fcitx5.android.input.FcitxInputMethodService'


class RunCadenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='cadence-runner-test-')
        self.addCleanup(self.directory.cleanup)
        # Ubuntu 22.04's Python 3.10 predates TestCase.enterContext.
        self.contexts = contextlib.ExitStack()
        self.addCleanup(self.contexts.close)
        self.root = Path(self.directory.name)
        self.apks = self.root / 'apks'
        self.apks.mkdir()
        self.sdk = self.root / 'sdk'
        tools = self.sdk / 'build-tools' / '36.1.0'
        tools.mkdir(parents=True)
        self.aapt = tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
        self.aapt.write_bytes(b'mocked aapt; never executed')
        self.fixture = self.root / 'fixture.zip'
        self.fixture.write_bytes(b'fixture bytes')
        self.host = self.root / 'host.apk'
        self.host.write_bytes(b'host bytes')
        self.manifest = self.root / 'fixture-manifest.json'
        self.manifest.write_text(json.dumps({
            'fixture_sha256': hashlib.sha256(self.fixture.read_bytes()).hexdigest(),
        }))
        self.output = self.root / 'output'
        self.sources = {'before': 'a' * 40, 'after': 'b' * 40}
        self.current_revision = self.sources['before']
        apks = {}
        for label, revision in self.sources.items():
            (self.apks / (label + '-commit.txt')).write_text(revision)
            for suffix in ('.apk', '-tests.apk'):
                name = label + suffix
                data = name.encode()
                (self.apks / name).write_bytes(data)
                apks[name] = hashlib.sha256(data).hexdigest()
        self.products = {
            'sources': self.sources,
            'apks': apks,
            'harness_sha256': hashlib.sha256(
                SCRIPT.with_name('CadenceCandidateTapTest.kt').read_bytes()).hexdigest(),
            'protocol': PROTOCOL,
        }
        self.write_products()
        self.argv = [
            '--apks', str(self.apks), '--fixture', str(self.fixture),
            '--fixture-manifest', str(self.manifest), '--host', str(self.host),
            '--output', str(self.output), '--trials', '1',
            '--serial', SERIAL, '--expected-avd-name', 'rime',
        ]
        self.actual_avd = 'rime'
        self.actual_serial = SERIAL
        self.device_state = 'device'
        self.default_ime = COMPONENT
        self.fail_replay = False
        self.commands = []
        self.processes = []
        self.run_mock = self.contexts.enter_context(patch.object(
            runner.subprocess, 'run', side_effect=self.fake_run))
        self.popen_mock = self.contexts.enter_context(patch.object(
            runner.subprocess, 'Popen', side_effect=self.fake_popen))
        self.output_mock = self.contexts.enter_context(patch.object(
            runner.subprocess, 'check_output', side_effect=self.fake_check_output))
        self.contexts.enter_context(patch.object(runner.time, 'sleep'))
        self.contexts.enter_context(patch.dict(os.environ, {
            'ANDROID_HOME': str(self.sdk), 'ANDROID_SERIAL': 'emulator-9999',
        }))

    def write_products(self):
        (self.apks / 'products.json').write_text(json.dumps(self.products))

    def sample(self):
        rounds = []
        editor = {'text': '你' * 6, 'commits': [], 'edits': [], 'draws': []}
        for index in range(6):
            base = 1_000_000_000 + index * 500_000_000

            def touch(offset):
                planned_up = base + offset + 20_000_000
                return {'planned_up_ns': planned_up, 'inject_up_ns': planned_up + 100,
                        'dispatch_up_ns': planned_up + 200,
                        'dispatch_return_ns': planned_up + 300, 'geometry_valid': True}

            row = {'n': touch(0), 'i': touch(100_000_000), 'select': touch(200_000_000)}
            row['preedit_collected_ns'] = row['n']['dispatch_up_ns'] + 1_000
            row['commit_collected_ns'] = row['select']['dispatch_up_ns'] + 2_000
            rounds.append(row)
            editor['commits'].append({'at_ns': row['select']['dispatch_up_ns'] + 3_000,
                                      'text': '你'})
            for kind, delay in (('edits', 4_000), ('draws', 5_000)):
                editor[kind].append({'at_ns': row['select']['dispatch_up_ns'] + delay,
                                     'text': '你' * (index + 1)})
        ime = {'rounds': rounds, 'commit': self.current_revision, 'model_mapped': True,
               'fcitx_core_mapped': False, 'injection_protocol': PROTOCOL}
        return ime, editor

    def fake_run(self, command, **kwargs):
        self.assertEqual(['adb', '-s', SERIAL], command[:3])
        self.commands.append(command)
        parts = command[3:]
        stdout, code = '', 0
        if parts == ['get-serialno']:
            stdout = self.actual_serial + '\n'
        elif parts == ['get-state']:
            stdout = self.device_state + '\n'
        elif parts == ['emu', 'avd', 'name']:
            stdout = self.actual_avd + '\nOK\n'
        elif parts[:2] == ['logcat', '-d']:
            stdout = 'started: replay(org.fcitx.fcitx5.android.core.CandidateTapTest)\n'
        elif parts == ['shell', 'settings', 'get', 'secure', 'default_input_method']:
            stdout = self.default_ime + '\n'
        elif parts[:2] == ['shell', 'perfetto']:
            stdout = '1234\n'
        elif parts[:3] == ['shell', 'test', '-d']:
            code = 1
        elif parts[-1] == 'files/candidate-taps.json':
            stdout = json.dumps(self.sample()[0])
        elif parts[-1] == 'files/candidate-editor.json':
            stdout = json.dumps(self.sample()[1])
        return subprocess.CompletedProcess(command, code, stdout, '')

    def fake_popen(self, command, **kwargs):
        self.assertEqual(['adb', '-s', SERIAL], command[:3])
        self.commands.append(command)
        self.current_revision = command[command.index('expectedCommit') + 1]
        replay = any('#replay' in part for part in command)
        process = Mock(returncode=0)
        process.poll.return_value = None
        result = 'FAILURES!!!' if replay and self.fail_replay else 'OK (1 test)'
        process.communicate.return_value = (result, '')
        self.processes.append(process)
        return process

    def fake_check_output(self, command, **kwargs):
        self.assertEqual(str(self.aapt), command[0])
        self.assertEqual(['dump', 'packagename'], command[1:3])
        return PACKAGE + '\n'

    def main(self, extra=()):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            runner.main([*self.argv, *extra])

    def assert_no_subprocess(self):
        self.run_mock.assert_not_called()
        self.popen_mock.assert_not_called()
        self.output_mock.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_import_does_not_parse_arguments_or_touch_device(self):
        module = importlib.util.module_from_spec(SPEC)
        with patch.object(runner.argparse.ArgumentParser, 'parse_args') as parse:
            SPEC.loader.exec_module(module)
        parse.assert_not_called()
        self.assert_no_subprocess()

    def test_runner_has_no_optimization_sensitive_asserts(self):
        tree = ast.parse(SCRIPT.read_text(encoding='utf-8'), filename=str(SCRIPT))
        self.assertEqual([], [node.lineno for node in ast.walk(tree) if isinstance(node, ast.Assert)])

    def test_long_lived_emulator_without_reset_flag_never_touched(self):
        with self.assertRaises(SystemExit) as error:
            self.main()
        self.assertEqual(2, error.exception.code)
        self.assert_no_subprocess()

    def test_run_entry_also_requires_reset_authorization(self):
        args = runner.build_parser().parse_args(self.argv)
        with self.assertRaisesRegex(ValueError, '--allow-destructive-reset'):
            runner.run(args)
        self.assert_no_subprocess()

    def test_explicit_serial_and_avd_name_are_required(self):
        for option in ('--serial', '--expected-avd-name'):
            with self.subTest(option=option):
                arguments = self.argv.copy()
                index = arguments.index(option)
                del arguments[index:index + 2]
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    runner.main([*arguments, '--allow-destructive-reset'])
                self.assert_no_subprocess()

    def test_wrong_avd_name_fails_before_any_device_write(self):
        self.actual_avd = 'another-avd'
        with self.assertRaisesRegex(RuntimeError, 'AVD name does not match'):
            self.main(['--allow-destructive-reset'])
        self.assertEqual([['get-serialno'], ['get-state'], ['emu', 'avd', 'name']],
                         [command[3:] for command in self.commands])
        self.popen_mock.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_wrong_serial_or_offline_device_fails_before_any_device_write(self):
        self.actual_serial = 'emulator-5554'
        with self.assertRaisesRegex(RuntimeError, 'serial does not match'):
            self.main(['--allow-destructive-reset'])
        self.assertEqual([['get-serialno']], [command[3:] for command in self.commands])
        self.commands.clear()
        self.actual_serial = SERIAL
        self.device_state = 'offline'
        with self.assertRaisesRegex(RuntimeError, 'not online'):
            self.main(['--allow-destructive-reset'])
        self.assertEqual([['get-serialno'], ['get-state']],
                         [command[3:] for command in self.commands])
        self.popen_mock.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_explicit_reset_keeps_clean_fixture_protocol_and_pins_all_commands(self):
        self.main(['--allow-destructive-reset'])
        self.assertEqual([['get-serialno'], ['get-state'], ['emu', 'avd', 'name']],
                         [command[3:] for command in self.commands[:3]])
        parts = [command[3:] for command in self.commands]
        self.assertEqual(2, parts.count(['uninstall', PACKAGE]))
        self.assertEqual(2, parts.count(['uninstall', PACKAGE + '.test']))
        self.assertEqual(2, parts.count(['shell', 'pm', 'clear', PACKAGE]))
        self.assertEqual(4, self.popen_mock.call_count)
        for call in self.popen_mock.call_args_list:
            self.assertEqual(['adb', '-s', SERIAL], call.args[0][:3])
        results = json.loads((self.output / 'comparison.json').read_text())
        self.assertEqual(['before', 'after'], list(results))
        self.assertEqual(6, len(results['before'][0]['timings_ms']))
        manifest = json.loads((self.output / 'run-manifest.json').read_text())
        self.assertEqual(SERIAL, manifest['serial'])
        self.assertEqual('rime', manifest['expected_avd_name'])
        self.assertIs(True, manifest['allow_destructive_reset'])

    def test_old_harness_protocol_cannot_start_device_writes(self):
        self.products['protocol'] = 'fixed_cadence_100ms_input_manager_async_cached_letters'
        self.write_products()
        with self.assertRaisesRegex(ValueError, 'cached_targets_v2'):
            self.main(['--allow-destructive-reset'])
        self.assertEqual([['get-serialno'], ['get-state'], ['emu', 'avd', 'name']],
                         [command[3:] for command in self.commands])
        self.popen_mock.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_tampered_product_fails_before_device_writes(self):
        (self.apks / 'before.apk').write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'does not match products.json'):
            self.main(['--allow-destructive-reset'])
        self.assertEqual([['get-serialno'], ['get-state'], ['emu', 'avd', 'name']],
                         [command[3:] for command in self.commands])
        self.popen_mock.assert_not_called()
        self.output_mock.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_failed_ime_selection_reaps_instrumentation_process(self):
        self.default_ime = 'com.android.inputmethod.latin/.LatinIME'
        with self.assertRaisesRegex(RuntimeError, 'did not select the benchmark IME'):
            self.main(['--allow-destructive-reset'])
        replay = self.processes[-1]
        replay.kill.assert_called_once_with()
        replay.communicate.assert_called_once_with(timeout=15)

    def test_trace_failure_recovery_uses_the_verified_serial(self):
        self.fail_replay = True
        with self.assertRaisesRegex(RuntimeError, 'trial 1 failed'):
            self.main(['--allow-destructive-reset', '--profile'])
        parts = [command[3:] for command in self.commands]
        self.assertIn(['shell', 'kill', '-INT', '1234'], parts)
        self.assertIn(['pull', '/data/misc/perfetto-traces/candidate.perfetto-trace',
                       str(self.output / 'before-1.perfetto-trace')], parts)
        self.assertTrue((self.output / 'failed-trace-retention.txt').is_file())

    def test_aapt_lookup_uses_platform_executable_name(self):
        self.assertEqual(self.aapt, runner.find_aapt(self.sdk))

    def test_release_product_is_rejected_before_device_writes(self):
        self.output_mock.side_effect = None
        self.output_mock.return_value = 'org.example.release\n'
        with self.assertRaisesRegex(ValueError, '.debug package name'):
            self.main(['--allow-destructive-reset'])
        self.assertEqual([['get-serialno'], ['get-state'], ['emu', 'avd', 'name']],
                         [command[3:] for command in self.commands])
        self.popen_mock.assert_not_called()
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
