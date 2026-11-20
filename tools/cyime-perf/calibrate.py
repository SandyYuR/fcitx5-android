#!/usr/bin/env python3
"""Prepare the two unmodified ARM64 IMEs on the same disposable Android emulator."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile
import time
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "foxy-perf"))
from pilot import adb, capture, HOST, selected_ime

PACKAGES = {"direct": "org.fcitx.fcitx5.android.fx.rime", "cyime": "com.cyletix.cyime"}


def install_fixture(fixture):
    target = f"/sdcard/Android/data/{PACKAGES['direct']}/files/data/rime"
    with tempfile.TemporaryDirectory(prefix="cyime-fixture-") as source:
        with zipfile.ZipFile(fixture) as archive:
            if any(Path(n).is_absolute() or ".." in Path(n).parts for n in archive.namelist()):
                raise ValueError("Invalid fixture path")
            archive.extractall(source)
        adb("shell", "mkdir", "-p", target)
        adb("push", source + "/.", target)
    physical = target.replace("/sdcard/", "/data/media/0/", 1)
    uid = re.search(r"uid:(\d+)", adb("shell", "cmd", "package", "list", "packages", "-U", PACKAGES["direct"]))[1]
    adb("shell", "chown", "-R", f"{uid}:{uid}", physical)
    adb("shell", "restorecon", "-RF", physical)
    return target


def open_editor(package):
    for process in [HOST, *PACKAGES.values()]:
        adb("shell", "am", "force-stop", process)
    ime = selected_ime(package)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", HOST + "/.BenchHost")
    return ime


def wait_for_keyboard(label, package):
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        pid = adb("shell", "pidof", package, check=False).split()
        if pid:
            logs = adb("logcat", "-d", "--pid=" + pid[0])
            window = adb("shell", "dumpsys", "input_method")
            if label == "direct":
                ready = re.search(r"RimeLifecycle: bind .*ready=true", logs) is not None
            else:
                ready = "initRimeEngine: ready in " in logs
            if ready and "mInputShown=true" in window and HOST in window:
                return pid[0]
        time.sleep(1)
    raise RuntimeError(f"{label}: Rime readiness or visible IME timed out")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("direct", "cyime", "fixture", "host", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args()
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    if not adb("get-serialno").startswith("emulator-"):
        raise RuntimeError("The experiment requires a disposable emulator")
    adb("root")
    adb("wait-for-device")
    if adb("shell", "id", "-u") != "0":
        raise RuntimeError("The emulator must allow root adbd to install the fixed Rime fixture")
    if "arm64-v8a" not in adb("shell", "getprop", "ro.product.cpu.abilist").split(","):
        raise RuntimeError("The Android image cannot run both original ARM64 APKs")
    adb("shell", "wm", "size", "480x960")
    adb("shell", "wm", "density", "160")
    for namespace, key, value in (
        ("global", "device_provisioned", "1"), ("secure", "user_setup_complete", "1"),
        ("secure", "show_ime_with_hard_keyboard", "1"), ("system", "screen_off_timeout", "1800000"),
    ):
        adb("shell", "settings", "put", namespace, key, value)
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "wm", "dismiss-keyguard")
    (output / "device-properties.txt").write_text(adb("shell", "getprop"))
    (output / "display.txt").write_text(adb("shell", "dumpsys", "display"))
    adb("install", "-r", args.host)
    for apk in (args.direct, args.cyime):
        adb("install", "-r", apk)
    target = install_fixture(args.fixture)
    profiles = {"direct": (target, "wanxiang"), "cyime": (f"/data/user/0/{PACKAGES['cyime']}/files/rime", "rime_ice")}
    support = {}
    errors = []
    for label, package in PACKAGES.items():
        out = output / label
        out.mkdir(exist_ok=True)
        root, schema = profiles[label]
        entry = {"apk_sha256": hashlib.sha256(getattr(args, label).read_bytes()).hexdigest(), "schema": schema}
        support[label] = entry
        try:
            entry["ime"] = open_editor(package)
            pid = wait_for_keyboard(label, package)
            maps = adb("shell", "cat", f"/proc/{pid}/maps")
            (out / "maps.txt").write_text(maps)
            entry["arm64_translation"] = "libndk_translation.so" in maps
            if not entry["arm64_translation"]:
                raise RuntimeError("ARM64 bridge was not loaded")
            (out / "effective-schema.yaml").write_text(adb("shell", "cat", f"{root}/build/{schema}.schema.yaml"))
            user = adb("shell", "cat", f"{root}/user.yaml", check=False)
            if user:
                (out / "user.yaml").write_text(user)
        except (RuntimeError, OSError) as error:
            entry["error"] = str(error)
            errors.append(label)
        finally:
            capture(out, "keyboard")
            (out / "input-method.txt").write_text(adb("shell", "dumpsys", "input_method"))
            (out / "logcat.txt").write_text(adb("logcat", "-d", "-v", "threadtime", "-t", "2500"))
            (output / "products.json").write_text(json.dumps(support, indent=2) + "\n")
    if errors:
        raise RuntimeError(f"Calibration failed for {', '.join(errors)}")


if __name__ == "__main__":
    main()
