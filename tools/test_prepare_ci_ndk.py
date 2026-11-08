"""NDK preparation must reject incomplete installs and retry only the pinned package."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("prepare_ci_ndk", Path(__file__).with_name("prepare-ci-ndk.py"))
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class PrepareCiNdkTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ndk-preparation-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.version = PREPARE.pinned_version()
        self.host = patch.object(PREPARE.platform, "system", return_value="Linux")
        self.host.start()
        self.addCleanup(self.host.stop)

    def install(self, revision=None, compiler=True):
        directory = self.root / "ndk" / self.version
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "source.properties").write_text(f"Pkg.Revision = {revision or self.version}\n", encoding="utf-8")
        if compiler:
            binary = directory / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
            binary.parent.mkdir(parents=True, exist_ok=True)
            binary.write_bytes(b"fixture compiler")
            binary.chmod(0o755)

    def test_ready_install_does_not_download_again(self):
        self.install()
        with patch.object(PREPARE.subprocess, "run") as run:
            PREPARE.prepare(self.root, self.version, "sdkmanager")
        run.assert_not_called()

    def test_corrupt_download_retries_with_fresh_cache_and_the_same_version(self):
        environments = []

        def download(command, **options):
            self.assertEqual(["sdkmanager", f"--sdk_root={self.root}", "--install", f"ndk;{self.version}"], command)
            environments.append(options["env"]["ANDROID_USER_HOME"])
            self.assertTrue(Path(environments[-1]).is_dir())
            if len(environments) == 2:
                self.install()
            return subprocess.CompletedProcess(command, 1 if len(environments) == 1 else 0)

        with patch.object(PREPARE.subprocess, "run", side_effect=download), patch.object(PREPARE.time, "sleep"):
            PREPARE.prepare(self.root, self.version, "sdkmanager")
        self.assertEqual(2, len(set(environments)))

    def test_success_exit_without_compiler_is_not_a_successful_install(self):
        self.install(compiler=False)
        with patch.object(PREPARE.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run, \
                patch.object(PREPARE.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "after 3 attempts"):
                PREPARE.prepare(self.root, self.version, "sdkmanager")
        self.assertEqual(3, run.call_count)

    def test_wrong_revision_is_rejected(self):
        self.install(revision="0.0.0")
        self.assertFalse(PREPARE.ndk_ready(self.root, self.version))

    def test_timeout_has_a_bounded_retry_budget(self):
        with patch.object(PREPARE.subprocess, "run", side_effect=subprocess.TimeoutExpired("sdkmanager", 900)) as run, \
                patch.object(PREPARE.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "after 3 attempts"):
                PREPARE.prepare(self.root, self.version, "sdkmanager")
        self.assertEqual(3, run.call_count)


if __name__ == "__main__":
    unittest.main()
