"""Install the pinned NDK before AGP configures the app, with bounded download retries."""
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import time


def pinned_version():
    versions = Path(__file__).resolve().parents[1] / "build-logic/convention/src/main/kotlin/Versions.kt"
    match = re.search(r'const val defaultNDK = "([0-9]+(?:\.[0-9]+){2})"', versions.read_text(encoding="utf-8"))
    if match is None:
        raise RuntimeError(f"Cannot find the pinned NDK version in {versions}")
    return match[1]


def ndk_ready(sdk_root, version):
    directory = sdk_root / "ndk" / version
    properties = directory / "source.properties"
    host, executable = {
        "Linux": ("linux-x86_64", "clang"),
        "Darwin": ("darwin-x86_64", "clang"),
        "Windows": ("windows-x86_64", "clang.exe"),
    }[platform.system()]
    compiler = directory / "toolchains/llvm/prebuilt" / host / "bin" / executable
    if not properties.is_file() or not compiler.is_file() or compiler.stat().st_size == 0:
        return False
    revision = re.search(r"^Pkg\.Revision\s*=\s*(\S+)\s*$", properties.read_text(encoding="utf-8"), re.MULTILINE)
    return revision is not None and revision[1] == version and os.access(compiler, os.X_OK)


def prepare(sdk_root, version, sdkmanager, attempts=3):
    if ndk_ready(sdk_root, version):
        print(f"Pinned NDK {version} is ready.", flush=True)
        return
    for attempt in range(1, attempts + 1):
        print(f"Installing pinned NDK {version} (attempt {attempt}/{attempts}).", flush=True)
        # Each SDK-manager attempt gets fresh download metadata/cache and temporary files.
        # Keep the existing SDK root, accepted licenses and other installed versions.
        with tempfile.TemporaryDirectory(prefix="ci-ndk-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
            environment = os.environ.copy()
            environment.update(ANDROID_USER_HOME=temporary, TMPDIR=temporary, TMP=temporary, TEMP=temporary)
            try:
                result = subprocess.run(
                    [sdkmanager, f"--sdk_root={sdk_root}", "--install", f"ndk;{version}"],
                    env=environment, check=False, timeout=900,
                )
                succeeded = result.returncode == 0 and ndk_ready(sdk_root, version)
            except subprocess.TimeoutExpired:
                succeeded = False
                print("SDK manager timed out.", flush=True)
        if succeeded:
            print(f"Pinned NDK {version} is ready.", flush=True)
            return
        if attempt < attempts:
            print("NDK installation failed or is incomplete; retrying the same version.", flush=True)
            time.sleep(5)
    raise RuntimeError(f"Pinned NDK {version} is not ready after {attempts} attempts")


def main():
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not root:
        raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT must name the Android SDK")
    sdk_root = Path(root).resolve(strict=True)
    version = pinned_version()
    sdkmanager = shutil.which("sdkmanager") or str(sdk_root / "cmdline-tools/latest/bin/sdkmanager")
    prepare(sdk_root, version, sdkmanager)


if __name__ == "__main__":
    main()
