"""Recover manifests from fixed synthetic products without building or running an APK."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


HERE = Path(__file__).resolve().parent
SOURCES = {
    'baseline': '0380d70f16efb6a9946a164821d5cad7118ce4ff',
    'regressed': '4d77cd79a580a9984130a5653b13f949ec5bc0a9',
    'current': 'adb6a4e33426333677934ac82f0a19997245556a',
}
ORIGINAL_DRIVER = '483c2870a6136f61cd5a13c416829fcfcca274b0'
FAILED_RUN = '43173173211'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class StudyProductRecoveryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='study-recovery-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.products = self.root / 'products'
        self.products.mkdir()
        self.host_files = (
            'tools/candidate-perf/host/BenchHost.java',
            'tools/candidate-perf/host/BenchControl.java',
            'tools/candidate-perf/host/AndroidManifest.xml',
            'tools/screen-perf/build-host.sh',
        )
        self.original_files = {}
        for name in self.host_files:
            self.original_files[name] = (HERE.parents[1] / name).read_bytes()
        self.original_files['tools/input-perf/CadenceCandidateTapTest.kt'] = (HERE / 'CadenceCandidateTapTest.kt').read_bytes()
        for name, data in self.original_files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        (self.root / 'tools/input-perf/apk_signers.py').write_bytes((HERE / 'apk_signers.py').read_bytes())
        # git show in the builder reads canonical LF bytes, even on a CRLF checkout.
        self.lock = (HERE.parents[1] / 'native/rime-sdk.lock.json').read_text().encode('utf-8')
        sdk = self.root / 'sdk/build-tools/36.1.0'
        sdk.mkdir(parents=True)
        (sdk / 'aapt2').write_text('never executed')
        self.sdk = self.root / 'sdk'
        probe_hash = digest(self.root / 'tools/input-perf/CadenceCandidateTapTest.kt')
        for label, sha in SOURCES.items():
            (self.products / (label + '-commit.txt')).write_text(sha)
            (self.products / (label + '-harness-sha256.txt')).write_text(probe_hash)
            native = (label + '-native').encode()
            with zipfile.ZipFile(self.products / (label + '.apk'), 'w') as apk:
                apk.writestr('lib/x86_64/libdirect-rime.so', native)
            (self.products / (label + '-tests.apk')).write_bytes((label + '-tests').encode())
            (self.products / (label + '-native-link-audit.json')).write_text(json.dumps([{
                'apk': label + '.apk', 'abi': 'x86_64', 'apk_native_sha256': hashlib.sha256(native).hexdigest(),
                'fcitx_cpp_symbols': [],
            }]))
            (self.products / (label + '-apk-audit.json')).write_text(json.dumps({
                'apk_sha256': digest(self.products / (label + '.apk')), 'fcitx_dependencies': [],
            }))
        (self.products / 'host.apk').write_bytes(b'fixed host binary')
        (self.products / 'baseline-tests.apk.signer.txt').write_text('original failure evidence')
        script = (HERE / 'build-study-products.sh').read_text()
        python = script.split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
        self.code = compile(python, 'build-study-products.sh metadata', 'exec')
        self.calls = []

    def command(self, args, **kwargs):
        self.calls.append(args)
        if args[0] == 'git':
            if args[3:] == ['rev-parse', 'HEAD']:
                return b'ffffffffffffffffffffffffffffffffffffffff\n'
            self.assertEqual('show', args[3])
            revision, path = args[4].split(':', 1)
            if path == 'native/rime-sdk.lock.json':
                self.assertIn(revision, SOURCES.values())
                return self.lock
            self.assertEqual(ORIGINAL_DRIVER, revision)
            return self.original_files[path]
        if Path(args[0]).name == 'aapt2':
            self.assertEqual(['dump', 'packagename'], args[1:3])
            suffix = '.test' if Path(args[3]).name.endswith('-tests.apk') else ''
            return 'org.example.ime.debug' + suffix + '\n'
        if Path(args[0]).name == 'apksigner':
            self.assertEqual(['verify', '--print-certs'], args[1:3])
            return 'V2 Signer: certificate SHA-256 digest: ' + 'd' * 64 + '\n'
        self.fail('Unexpected subprocess: ' + repr(args))

    def recover(self, error=None):
        argv = ['metadata', 'recover', str(self.root), str(self.products), SOURCES['current'], '', ORIGINAL_DRIVER, FAILED_RUN]
        with patch.object(sys, 'argv', argv), patch.object(sys, 'path', list(sys.path)), \
                patch.dict(os.environ, {'ANDROID_HOME': str(self.sdk)}), \
                patch.object(subprocess, 'check_output', side_effect=self.command), \
                contextlib.redirect_stdout(io.StringIO()):
            if error:
                with self.assertRaisesRegex(SystemExit, error):
                    exec(self.code, {})
                self.assertFalse((self.products / 'products.json').exists())
                return None
            exec(self.code, {})
        return json.loads((self.products / 'products.json').read_text())

    def test_recovery_preserves_binaries_original_driver_and_failed_run(self):
        before = {p.name: digest(p) for p in self.products.glob('*.apk')}
        result = self.recover()
        self.assertEqual(before, {p.name: digest(p) for p in self.products.glob('*.apk')})
        self.assertEqual(ORIGINAL_DRIVER, result['driver_commit'])
        self.assertEqual('f' * 40, result['metadata_driver_commit'])
        self.assertEqual(FAILED_RUN, result['recovery']['failed_build_run'])
        self.assertIs(False, result['recovery']['rebuilt_products'])
        self.assertEqual('original failure evidence', (self.products / 'baseline-tests.apk.signer.txt').read_text())
        self.assertTrue((self.products / 'baseline-tests.apk.recovery.signer.txt').is_file())
        self.assertEqual(set(SOURCES), set(result['native_audit_sha256']))

    def test_recovery_rejects_changed_original_source_record(self):
        (self.products / 'baseline-commit.txt').write_text('0' * 40)
        self.recover('Original product source record mismatch')

    def test_recovery_rejects_changed_probe_record(self):
        (self.products / 'regressed-harness-sha256.txt').write_text('0' * 64)
        self.recover('Original product probe record mismatch')

    def test_recovery_rejects_changed_host_source(self):
        (self.root / self.host_files[0]).write_bytes(b'changed host source')
        self.recover('Recovery changed the original neutral host source')

    def test_recovery_rejects_apk_changed_after_original_audit(self):
        with zipfile.ZipFile(self.products / 'current.apk', 'a') as apk:
            apk.writestr('changed.dex', b'changed product')
        self.recover('Original APK audit does not match')

    def test_recovery_rejects_native_audit_for_another_library(self):
        path = self.products / 'baseline-native-link-audit.json'
        record = json.loads(path.read_text())
        record[0]['apk_native_sha256'] = '0' * 64
        path.write_text(json.dumps(record))
        self.recover('Original native audit does not match')

    def test_recovery_rejects_changed_sdk_lock(self):
        self.lock += b'\n'
        self.recover('All products must use the original frozen SDK lock')


if __name__ == '__main__':
    unittest.main()
