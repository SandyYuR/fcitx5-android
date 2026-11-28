"""Offline regressions for legacy performance identity and reset gates; never contacts ADB."""
import argparse
import contextlib
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import legacy_perf
import frozen_fixture_source

HERE = Path(__file__).resolve().parent
OLD = 'io.github.sandyyur.rime.direct'
DEBUG = 'org.fcitx.fcitx5.android.fx.rime.debug'
SERIAL = 'emulator-5554'


def manifest(package, target=None, namespace='android:'):
    text = f'E: manifest\n  A: package="{package}"\n  E: application\n    A: {namespace}name="UnrelatedApplication"\n'
    if target:
        text += (f'  E: instrumentation\n    A: {namespace}name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"\n'
                 f'    A: {namespace}targetPackage(0x01010021)="{target}"\n')
    return text


class LegacyDeviceTest(unittest.TestCase):
    def args(self, **overrides):
        values = dict(serial=SERIAL, expected_avd_name='test', allow_destructive_reset=True, adb='adb')
        values.update(overrides)
        return argparse.Namespace(**values)

    def fake(self, command, **kwargs):
        values = {'get-serialno': SERIAL, 'get-state': 'device', 'emu': 'test\nOK\n'}
        return subprocess.CompletedProcess(command, 0, values[command[3]], '')

    def test_missing_ack_or_invalid_identity_never_contacts_adb(self):
        for values in ({'allow_destructive_reset': False}, {'serial': 'physical'}, {'expected_avd_name': ''}):
            with self.subTest(values=values), patch('legacy_perf.subprocess.run') as run:
                with self.assertRaises(ValueError):
                    legacy_perf.Device(self.args(**values))
                run.assert_not_called()

    def test_wrong_avd_stops_after_read_only_queries(self):
        with patch('legacy_perf.subprocess.run', side_effect=self.fake) as run:
            with self.assertRaisesRegex(ValueError, 'AVD name'):
                legacy_perf.Device(self.args(expected_avd_name='rime'))
        self.assertEqual(['get-serialno', 'get-state', 'emu'], [call.args[0][3] for call in run.call_args_list])
        self.assertTrue(all(call.args[0][1:3] == ['-s', SERIAL] for call in run.call_args_list))

    def test_all_commands_stay_on_the_explicit_serial(self):
        with patch('legacy_perf.subprocess.run', side_effect=self.fake):
            device = legacy_perf.Device(self.args())
        self.assertEqual(['adb', '-s', SERIAL, 'shell', 'pm', 'clear', DEBUG],
                         device.command('shell', 'pm', 'clear', DEBUG))


class LegacyManifestTest(unittest.TestCase):
    def test_debug_historical_and_uri_namespaces_are_parsed(self):
        for package in (OLD, DEBUG):
            for namespace in ('android:', 'http://schemas.android.com/apk/res/android:'):
                identity = legacy_perf.parse_manifest(manifest(package + '.test', package, namespace))
                self.assertEqual(package, identity['target_package'])
                self.assertEqual('androidx.test.runner.AndroidJUnitRunner', identity['runner'])

    def test_wrong_target_rejected(self):
        with patch('legacy_perf.apk_identity', side_effect=[legacy_perf.parse_manifest(manifest(DEBUG)),
                     legacy_perf.parse_manifest(manifest(OLD + '.test', OLD))]):
            with self.assertRaisesRegex(ValueError, 'targetPackage'):
                legacy_perf.apk_pair('app.apk', 'tests.apk', 'aapt2')

    def test_relative_runner_is_resolved_from_test_package(self):
        text = manifest(DEBUG + '.test', DEBUG).replace('androidx.test.runner.AndroidJUnitRunner', '.Runner')
        self.assertEqual(DEBUG + '.test.Runner', legacy_perf.parse_manifest(text)['runner'])

    def test_ambiguous_or_resource_attributes_are_rejected(self):
        text = manifest(DEBUG + '.test', DEBUG)
        for invalid in (text + '  E: instrumentation\n', text.replace('="' + DEBUG + '"', '=@0x7f000001')):
            with self.subTest(tree=invalid), self.assertRaises(ValueError):
                legacy_perf.parse_manifest(invalid)


class LegacyDriversTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='legacy-perf-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for label in ('before', 'after'):
            for suffix in ('.apk', '-tests.apk'):
                (self.root / (label + suffix)).write_bytes(b'fake APK; not executed')
            (self.root / (label + '-commit.txt')).write_text(label * 8)
        self.fixture = self.root / 'fixture.zip'
        self.fixture.write_bytes(b'fake fixture')
        self.commands = []

    def fake(self, command, **kwargs):
        self.commands.append(command)
        if command[0] == 'aapt2':
            name = Path(command[-1]).name
            package = OLD if name.startswith('before') else DEBUG
            stdout = manifest(package + '.test', package) if '-tests' in name else manifest(package)
        else:
            self.assertEqual(['adb', '-s', SERIAL], command[:3])
            parts = command[3:]
            stdout = ''
            if parts == ['get-serialno']: stdout = SERIAL
            elif parts == ['get-state']: stdout = 'device'
            elif parts == ['emu', 'avd', 'name']: stdout = 'test\nOK\n'
            elif parts == ['shell', 'getprop', 'sys.boot_completed']: stdout = '1'
            elif parts[:3] == ['shell', 'am', 'instrument']: stdout = 'OK (1 test)'
            elif parts[:2] == ['shell', 'run-as'] and 'cat' in parts:
                label = 'before' if parts[2] == OLD else 'after'
                stdout = json.dumps({'harness_commit': label * 8, 'keys': 100, 'commits': ['same'],
                                     'events': {'CommitStringEvent': 100}})
            destination = kwargs.get('stdout')
            if hasattr(destination, 'write'):
                destination.write(stdout)
                stdout = None
        return subprocess.CompletedProcess(command, 0, stdout, '')

    def run_driver(self, name, args):
        with patch.object(sys, 'argv', [name, *map(str, args)]), \
                patch('legacy_perf.find_aapt', return_value='aapt2'), \
                patch('subprocess.run', side_effect=self.fake), contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(str(HERE / name), run_name='__main__')

    def test_rebind_routes_each_product_and_runner_to_its_manifest(self):
        self.run_driver('compare-rebind.py', ['--apks', self.root, '--fixture', self.fixture,
                        '--output', self.root / 'out', '--serial', SERIAL, '--expected-avd-name', 'test',
                        '--allow-destructive-reset'])
        for package in (OLD, DEBUG):
            self.assertIn(['adb', '-s', SERIAL, 'shell', 'pm', 'clear', package], self.commands)
            self.assertTrue(any(command[-1] == package + '.test/androidx.test.runner.AndroidJUnitRunner'
                                for command in self.commands))

    def test_replay_uses_separate_after_test_apk(self):
        self.run_driver('compare-apks.py', [self.root / 'before.apk', self.root / 'after.apk',
                        self.root / 'before-tests.apk', '--after-tests', self.root / 'after-tests.apk',
                        '--output', self.root / 'out', '--serial', SERIAL, '--expected-avd-name', 'test',
                        '--allow-destructive-reset'])
        self.assertIn(['adb', '-s', SERIAL, 'install', '-r', str(self.root / 'after-tests.apk')], self.commands)
        for package in (OLD, DEBUG):
            self.assertTrue(any(command[-1] == package + '.test/androidx.test.runner.AndroidJUnitRunner'
                                for command in self.commands))

    def test_reused_test_for_different_target_fails_before_device_changes(self):
        with self.assertRaisesRegex(ValueError, 'targetPackage'):
            self.run_driver('compare-apks.py', [self.root / 'before.apk', self.root / 'after.apk',
                            self.root / 'before-tests.apk', '--output', self.root / 'out', '--serial', SERIAL,
                            '--expected-avd-name', 'test', '--allow-destructive-reset'])
        adb_calls = [command[3:] for command in self.commands if command[0] == 'adb']
        self.assertEqual([['get-serialno'], ['get-state'], ['emu', 'avd', 'name']], adb_calls)

    def test_all_legacy_drivers_require_device_flags_before_contact(self):
        scripts = (HERE / 'compare-apks.py', HERE / 'compare-rebind.py', HERE.parent / 'candidate-perf/run.py')
        for path in scripts:
            with self.subTest(path=path.name), patch.object(sys, 'argv', [str(path)]), \
                    patch('subprocess.run') as run, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    runpy.run_path(str(path), run_name='__main__')
                run.assert_not_called()


class FrozenFixtureSourceTest(unittest.TestCase):
    def test_missing_or_invalid_id_never_fetches(self):
        for run, artifact in (('', '1'), ('2', ''), ('0', '1'), ('2;echo', '1')):
            with patch('urllib.request.urlopen') as fetch:
                with self.assertRaises(ValueError):
                    frozen_fixture_source.source_record('owner/repo', run, artifact, 'token')
                fetch.assert_not_called()

    def test_live_artifact_must_belong_to_the_configured_run(self):
        for record in ({'id': 12, 'workflow_run': {'id': 34}, 'expired': True},
                       {'id': 12, 'workflow_run': {'id': 99}, 'expired': False}):
            with patch('urllib.request.urlopen') as fetch:
                fetch.return_value.__enter__.return_value = io.StringIO(json.dumps(record))
                with self.assertRaises(ValueError):
                    frozen_fixture_source.source_record('owner/repo', '34', '12', 'token')

    def test_valid_source_records_ids_and_api_identity_without_token(self):
        record = {'id': 12, 'workflow_run': {'id': 34, 'head_sha': 'a' * 40},
                  'expired': False, 'digest': 'sha256:abc', 'name': 'frozen-fixture'}
        with patch('urllib.request.urlopen') as fetch:
            fetch.return_value.__enter__.return_value = io.StringIO(json.dumps(record))
            source = frozen_fixture_source.source_record('owner/repo', '34', '12', 'secret-token')
        self.assertEqual('34', source['run_id'])
        self.assertEqual('12', source['artifact_id'])
        self.assertEqual('sha256:abc', source['artifact_digest'])
        self.assertNotIn('secret-token', json.dumps(source))


if __name__ == '__main__':
    unittest.main()
