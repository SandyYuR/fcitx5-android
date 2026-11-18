#!/usr/bin/env python3
"""Fail before preparation when the Codespaces toolchain differs from the build."""

import argparse
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys


# Android command-line tools build 15859902, used by this image's Dockerfile.
CMDLINE_TOOLS_REVISION = "22.0"


def build_versions(path):
    source = path.read_text(encoding="utf-8")
    patterns = {
        "java": r"^\s*val java\s*=\s*JavaVersion\.VERSION_(\d+)",
        "compileSdk": r"^\s*const val compileSdk\s*=\s*(\d+)",
        "defaultBuildTools": r'^\s*const val defaultBuildTools\s*=\s*"([^"]+)"',
        "defaultNDK": r'^\s*const val defaultNDK\s*=\s*"([^"]+)"',
        "defaultCMake": r'^\s*const val defaultCMake\s*=\s*"([^"]+)"',
    }
    versions = {}
    for name, pattern in patterns.items():
        match = re.search(pattern, source, re.MULTILINE)
        if match is None:
            raise ValueError(f"Cannot read {name} from {path}")
        versions[name] = match[1]
    return versions


def package_property(directory, key):
    properties = directory / "source.properties"
    source = properties.read_text(encoding="utf-8")
    match = re.search(rf"^{re.escape(key)}\s*=\s*(\S+)\s*$", source, re.MULTILINE)
    if match is None:
        raise ValueError(f"Missing {key} in {properties}")
    return match[1]


def require_value(label, actual, expected):
    if actual != expected:
        raise ValueError(f"{label}: expected {expected}, found {actual}. Rebuild the container.")
    print(f"{label}: {actual}")


def require_file(path, executable=False):
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"Missing or empty toolchain file: {path}")
    if executable and not os.access(path, os.X_OK):
        raise ValueError(f"Toolchain file is not executable: {path}")


def command_output(command, expected_path, *arguments):
    require_file(expected_path, executable=True)
    resolved = shutil.which(command)
    if resolved is None or Path(resolved).resolve() != expected_path.resolve():
        raise ValueError(f"PATH must resolve {command} to {expected_path}; found {resolved}")
    result = subprocess.run(
        [resolved, *arguments], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, check=True, timeout=30,
    )
    return result.stdout


def output_version(label, output, pattern):
    match = re.search(pattern, output)
    if match is None:
        raise ValueError(f"Cannot read {label} version from: {output.strip()}")
    return match[1]


def check_toolchain(versions_file):
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("Codespaces Android tools require Linux x86_64 (Docker --platform=linux/amd64).")
    versions = build_versions(versions_file)
    sdk_home = os.environ.get("ANDROID_HOME")
    java_home = os.environ.get("JAVA_HOME")
    if not sdk_home or not java_home:
        raise ValueError("ANDROID_HOME and JAVA_HOME must name the container's installed toolchain.")
    sdk = Path(sdk_home).resolve()
    java = Path(java_home).resolve()
    sdk_root = os.environ.get("ANDROID_SDK_ROOT")
    if sdk_root and Path(sdk_root).resolve() != sdk:
        raise ValueError("ANDROID_SDK_ROOT and ANDROID_HOME must name the same SDK.")

    cmdline_tools = sdk / "cmdline-tools/latest"
    build_tools = sdk / "build-tools" / versions["defaultBuildTools"]
    ndk = sdk / "ndk" / versions["defaultNDK"]
    cmake = sdk / "cmake" / versions["defaultCMake"]
    android = sdk / "platforms" / f'android-{versions["compileSdk"]}'
    for label, directory, key, expected in (
        ("Command-line tools", cmdline_tools, "Pkg.Revision", CMDLINE_TOOLS_REVISION),
        ("Android platform", android, "AndroidVersion.ApiLevel", versions["compileSdk"]),
        ("Build Tools", build_tools, "Pkg.Revision", versions["defaultBuildTools"]),
        ("NDK", ndk, "Pkg.Revision", versions["defaultNDK"]),
        ("CMake package", cmake, "Pkg.Revision", versions["defaultCMake"]),
    ):
        require_value(label, package_property(directory, key), expected)
    print(f'Platform Tools: {package_property(sdk / "platform-tools", "Pkg.Revision")}')
    require_file(android / "android.jar")
    for executable in (
        cmdline_tools / "bin/sdkmanager", sdk / "platform-tools/adb",
        build_tools / "aapt2", build_tools / "zipalign", cmake / "bin/ninja",
        ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang",
    ):
        require_file(executable, executable=True)

    java_output = command_output("java", java / "bin/java", "-version")
    require_value("Java", output_version("Java", java_output, r'version "(\d+)[.\"]'), versions["java"])
    javac_output = command_output("javac", java / "bin/javac", "-version")
    require_value("javac", output_version("javac", javac_output, r"javac (\d+)(?:\.|\s|$)"), versions["java"])
    cmake_output = command_output("cmake", cmake / "bin/cmake", "--version")
    require_value("CMake", output_version("CMake", cmake_output, r"cmake version (\S+)"), versions["defaultCMake"])
    print("Codespaces toolchain matches Versions.kt.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--versions", type=Path,
        default=Path(__file__).resolve().parents[1] / "build-logic/convention/src/main/kotlin/Versions.kt",
        help="Repository build requirements (default: this checkout's Versions.kt)",
    )
    arguments = parser.parse_args()
    try:
        check_toolchain(arguments.versions)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Codespaces toolchain check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
