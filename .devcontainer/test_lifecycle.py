#!/usr/bin/env python3
"""Exercise the real container entry points without downloads or Android tools.

External Git, Gradle and Python operations are stubbed; Bash executes the actual
entry points. Fixtures and command logs remain under the configured temporary
directory so failures can be inspected. Works with Linux Bash and Git Bash.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent
REPOSITORY = SCRIPTS.parent
BASH = shutil.which("bash")


@unittest.skipUnless(BASH, "Bash is required (Git Bash is supported on Windows).")
class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Path(tempfile.mkdtemp(prefix="codespaces-lifecycle-"))
        self.repo = self.fixture / "repo"
        self.bin = self.fixture / "bin"
        self.home = self.fixture / "home"
        self.log = self.fixture / "commands.log"
        self.safe_directories = self.fixture / "safe-directories.txt"
        for directory in (self.repo / ".devcontainer", self.bin, self.home):
            directory.mkdir(parents=True, exist_ok=True)
        for name in ("post-create.sh", "verify-build.sh", "test.sh"):
            shutil.copyfile(SCRIPTS / name, self.repo / ".devcontainer" / name)
        shutil.copyfile(REPOSITORY / "prepare_personal_build.sh", self.repo / "prepare_personal_build.sh")
        self.write_executable(self.bin / "git", r'''#!/usr/bin/env bash
set -euo pipefail
printf 'git|%s|%s\n' "$PWD" "$*" >> "$FIXTURE_LOG"
case "$*" in
  'config --global --get-all safe.directory')
    if [ -f "$FIXTURE_SAFE_DIRECTORIES" ]; then
      cat "$FIXTURE_SAFE_DIRECTORIES"
    else
      exit 1
    fi
    ;;
  'config --global --add safe.directory '*)
    printf '%s\n' "$5" >> "$FIXTURE_SAFE_DIRECTORIES"
    ;;
  'rev-parse --show-toplevel') printf '%s\n' "$FIXTURE_REPO" ;;
  'submodule update --init --depth 1 native/rime-prelude') ;;
  *) echo "Unexpected fixture git command: $*" >&2; exit 91 ;;
esac
''')
        self.write_executable(self.bin / "python3", r'''#!/usr/bin/env bash
set -euo pipefail
printf 'python3|%s|%s\n' "$PWD" "$*" >> "$FIXTURE_LOG"
if [ "${1:-}" = '-B' ]; then shift; fi
case "${1:-}" in
  .devcontainer/toolchain-check.py) exit "${FIXTURE_TOOLCHAIN_EXIT:-0}" ;;
  tools/verify-direct-rime-apk.py) exit "${FIXTURE_APK_AUDIT_EXIT:-0}" ;;
  tools/audit-direct-rime-link.py) exit "${FIXTURE_LINK_AUDIT_EXIT:-0}" ;;
  tools/check-junit-results.py) exit "${FIXTURE_JUNIT_EXIT:-0}" ;;
  *) echo "Unexpected fixture python command: $*" >&2; exit 92 ;;
esac
''')
        self.write_executable(self.repo / "gradlew", r'''#!/usr/bin/env bash
set -euo pipefail
printf 'gradlew|%s|BUILD_ABI=%s|%s\n' "$PWD" "${BUILD_ABI:-}" "$*" >> "$FIXTURE_LOG"
if [ "${FIXTURE_GRADLE_EXIT:-0}" != 0 ]; then exit "$FIXTURE_GRADLE_EXIT"; fi
if [ "${FIXTURE_CREATE_APK:-0}" = 1 ]; then
  mkdir -p app/build/outputs/apk/fx/debug
  printf 'fixture APK for entry-point orchestration\n' > app/build/outputs/apk/fx/debug/fixture.apk
fi
''')
        self.environment = os.environ.copy()
        self.environment.update({
            "PATH": str(self.bin) + os.pathsep + self.environment["PATH"],
            "HOME": self.home.as_posix(),
            "GRADLE_USER_HOME": (self.fixture / "gradle-cache").as_posix(),
            "ANDROID_HOME": "/opt/android-sdk",
            "FIXTURE_REPO": self.repo.as_posix(),
            "FIXTURE_LOG": self.log.as_posix(),
            "FIXTURE_SAFE_DIRECTORIES": self.safe_directories.as_posix(),
        })
        # Do not let an enclosing test run accidentally select fixture failures.
        for name in tuple(self.environment):
            if name.startswith("FIXTURE_") and name.endswith("_EXIT"):
                del self.environment[name]
        self.environment.pop("FIXTURE_CREATE_APK", None)

    @staticmethod
    def write_executable(path, content):
        path.write_text(content, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def run_script(self, name, *, cwd=None, **environment):
        result = subprocess.run(
            [BASH, "--noprofile", "--norc", (self.repo / ".devcontainer" / name).as_posix()],
            cwd=cwd or self.repo, env={**self.environment, **environment},
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30,
        )
        with (self.fixture / "script-output.log").open("a", encoding="utf-8") as output:
            output.write(f"\n{name}: exit {result.returncode}\n{result.stdout}")
        return result

    def commands(self):
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout)

    def assert_no_audits(self):
        operations = "\n".join(self.commands())
        self.assertNotIn("tools/verify-direct-rime-apk.py", operations)
        self.assertNotIn("tools/audit-direct-rime-link.py", operations)

    def test_post_create_resolves_repository_from_another_directory(self):
        other = self.fixture / "unrelated"
        other.mkdir()
        result = self.run_script("post-create.sh", cwd=other)
        self.assert_success(result)
        self.assertIn("Codespace ready", result.stdout)
        self.assertTrue(self.commands())
        for command in self.commands():
            self.assertTrue(command.split("|")[1].endswith("/repo"), command)
        self.assertIn("submodule update --init --depth 1 native/rime-prelude", "\n".join(self.commands()))

    def test_post_create_writes_fresh_sdk_properties(self):
        result = self.run_script("post-create.sh")
        self.assert_success(result)
        self.assertEqual((self.repo / "local.properties").read_bytes(), b"sdk.dir=/opt/android-sdk\n")
        self.assertTrue((self.fixture / "gradle-cache").is_dir())

    def test_post_create_preserves_existing_properties_exactly(self):
        original = b"sdk.dir=/custom/sdk\r\ncustom.setting=keep me\r\n"
        (self.repo / "local.properties").write_bytes(original)
        self.assert_success(self.run_script("post-create.sh"))
        self.assertEqual((self.repo / "local.properties").read_bytes(), original)

    def test_post_create_propagates_preparation_failure_without_ready_message(self):
        result = self.run_script("post-create.sh", FIXTURE_GRADLE_EXIT="37")
        self.assertEqual(result.returncode, 37, result.stdout)
        self.assertNotIn("Codespace ready", result.stdout)
        self.assertIn("prepareRimeSdk", "\n".join(self.commands()))

    def test_post_create_adds_safe_directory_only_once(self):
        self.assert_success(self.run_script("post-create.sh"))
        first = self.safe_directories.read_bytes()
        self.assert_success(self.run_script("post-create.sh"))
        self.assertEqual(self.safe_directories.read_bytes(), first)
        adds = [line for line in self.commands() if "config --global --add safe.directory" in line]
        self.assertEqual(len(adds), 1, adds)

    def test_post_create_stops_before_writes_when_toolchain_check_fails(self):
        result = self.run_script("post-create.sh", FIXTURE_TOOLCHAIN_EXIT="23")
        self.assertEqual(result.returncode, 23, result.stdout)
        self.assertFalse((self.repo / "local.properties").exists())
        self.assertFalse(any(line.startswith("gradlew|") for line in self.commands()))
        self.assertNotIn("Codespace ready", result.stdout)

    def test_verify_build_propagates_gradle_failure_without_auditing(self):
        result = self.run_script("verify-build.sh", FIXTURE_GRADLE_EXIT="37")
        self.assertEqual(result.returncode, 37, result.stdout)
        self.assert_no_audits()

    def test_verify_build_rejects_missing_apk_without_auditing(self):
        result = self.run_script("verify-build.sh")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("No Debug APK was produced.", result.stdout)
        self.assert_no_audits()

    def test_verify_build_forces_arm64_and_audits_the_produced_apk(self):
        result = self.run_script("verify-build.sh", BUILD_ABI="x86_64", FIXTURE_CREATE_APK="1")
        self.assert_success(result)
        commands = self.commands()
        gradle = next(line for line in commands if line.startswith("gradlew|"))
        self.assertIn("BUILD_ABI=arm64-v8a|", gradle)
        apk_audit = next(line for line in commands if "tools/verify-direct-rime-apk.py" in line)
        link_audit = next(line for line in commands if "tools/audit-direct-rime-link.py" in line)
        self.assertIn("/app/build/outputs/apk/fx/debug/fixture.apk", apk_audit)
        self.assertIn("--build-root ", link_audit)
        self.assertIn("/repo/app/.cxx --prebuilt ", link_audit)
        self.assertIn("/repo/native/rime-sdk", link_audit)
        self.assertLess(commands.index(apk_audit), commands.index(link_audit))

    def test_verify_build_propagates_apk_audit_failure_before_link_audit(self):
        result = self.run_script("verify-build.sh", FIXTURE_CREATE_APK="1", FIXTURE_APK_AUDIT_EXIT="29")
        self.assertEqual(result.returncode, 29, result.stdout)
        self.assertFalse(any("tools/audit-direct-rime-link.py" in line for line in self.commands()))

    def test_test_entry_point_propagates_junit_gate_failure(self):
        result = self.run_script("test.sh", FIXTURE_JUNIT_EXIT="31")
        self.assertEqual(result.returncode, 31, result.stdout)
        junit = next(line for line in self.commands() if "tools/check-junit-results.py" in line)
        self.assertIn("/repo/app/build/test-results/testFxDebugUnitTest", junit)

    def test_test_entry_point_does_not_check_junit_after_failed_gradle(self):
        result = self.run_script("test.sh", FIXTURE_GRADLE_EXIT="37")
        self.assertEqual(result.returncode, 37, result.stdout)
        self.assertFalse(any("tools/check-junit-results.py" in line for line in self.commands()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
