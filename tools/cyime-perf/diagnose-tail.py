#!/usr/bin/env python3
"""Compare the same Direct Rime APK and deployed profile on emulated and private storage."""

import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess

from calibrate import HOST, PACKAGES, WANXIANG_MODEL, WANXIANG_MODEL_SHA, adb
from run import replay
from sentences import SENTENCES


PACKAGE = PACKAGES["direct"]
EXTERNAL = f"/data/media/0/Android/data/{PACKAGE}/files"
PRIVATE = f"/data/user/0/{PACKAGE}/files/benchmark-wanxiang"
PROFILE_CONF = EXTERNAL + "/config/conf/rime-data-dir.conf"


def stop_input():
    adb("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS")
    adb("shell", "am", "force-stop", HOST)
    adb("shell", "am", "force-stop", PACKAGE)


def profile_evidence(root):
    file_hashes = adb("shell", "cd " + shlex.quote(root) + " && " + shlex.join([
        "find", ".", "-type", "f", "-exec", "sha256sum", "{}", "+",
    ]))
    return dict(sorted((line.split("  ", 1)[1], line.split("  ", 1)[0]) for line in file_hashes.splitlines()))


def storage_profile(output, location, uid):
    stop_input()
    paths = "" if location == "external" else "rime".encode().hex() + "|" + PRIVATE.encode().hex()
    config = f'Current="rime"\nProfiles="rime"\nExternalPathsV2="{paths}"\n'
    local = output / f"{location}-profile.conf"
    local.write_text(config)
    adb("shell", "mkdir", "-p", str(Path(PROFILE_CONF).parent))
    adb("push", local, PROFILE_CONF)
    adb("shell", "chown", f"{uid}:{uid}", PROFILE_CONF, str(Path(PROFILE_CONF).parent))


def record(output, name, bounds, location):
    capture = Path(__file__).resolve().parent.parent / "input-perf" / "capture.sh"
    trace = output / f"{name}.perfetto-trace"
    pinyin, expected = SENTENCES["lyric"]
    with (output / f"{name}-capture.txt").open("w") as log:
        recorder = subprocess.Popen(["bash", str(capture), str(trace), "30000", "storage"], stdout=subprocess.PIPE,
                                    stderr=log, text=True)
        try:
            ready = recorder.stdout.readline()
            log.write(ready)
            if "Tracing is ready" not in ready:
                raise RuntimeError("Perfetto did not start")
            report = replay(output / name, "direct", bounds, [(pinyin.replace(" ", ""), expected)], 120,
                            sentence=True, record_video=False)
            pid = adb("shell", "pidof", PACKAGE).split()[0]
            maps = adb("shell", "cat", f"/proc/{pid}/maps")
            (output / name / "maps.txt").write_text(maps)
            model_paths = sorted({line.split(maxsplit=5)[-1] for line in maps.splitlines() if line.endswith("/" + WANXIANG_MODEL)})
            folder = "/files/benchmark-wanxiang/" if location == "private" else "/files/data/rime/"
            if len(model_paths) != 1 or folder not in model_paths[0]:
                raise RuntimeError(f"Mapped profile did not use {location}: {model_paths}")
            report["location"] = location
            report["mapped_model"] = model_paths[0]
            (output / name / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
            print(f"{name}: {report}", flush=True)
        finally:
            stdout, _ = recorder.communicate(timeout=55)
            log.write(stdout)
    if recorder.returncode or not trace.is_file():
        raise RuntimeError(f"Perfetto did not save {name}")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output
    if not adb("get-serialno").startswith("emulator-") or adb("shell", "id", "-u") != "0":
        raise RuntimeError("This comparison requires the disposable root Android emulator")
    calibration = output / "calibration"
    if not json.loads((calibration / "shared-wanxiang.json").read_text())["verified"]:
        raise RuntimeError("Shared Wanxiang calibration did not pass")
    bounds = json.loads((calibration / "products.json").read_text())["direct"]["bounds"]
    stop_input()
    uid = re.search(r"uid:(\d+)", adb("shell", "cmd", "package", "list", "packages", "-U", PACKAGE))[1]
    owner = f"{uid}:{uid}"
    external = EXTERNAL + "/data/rime"
    adb("shell", "mkdir", "-p", PRIVATE)
    adb("shell", "cp", "-a", external + "/.", PRIVATE)
    adb("shell", "chown", "-R", owner, PRIVATE)
    adb("shell", "restorecon", "-RF", PRIVATE)
    original = profile_evidence(external)
    if original.get("./" + WANXIANG_MODEL) != WANXIANG_MODEL_SHA or original != profile_evidence(PRIVATE):
        raise RuntimeError("The two benchmark profiles must start with identical files")
    (output / "storage-profile-sha256.json").write_text(json.dumps(original, ensure_ascii=False, indent=2) + "\n")
    reports = []
    try:
        for i, location in enumerate(("external", "private", "private", "external"), 1):
            storage_profile(output, location, uid)
            reports.append(record(output, f"storage-{i}-{location}", bounds, location))
    finally:
        storage_profile(output, "external", uid)
    if not all(report["exact_commit"] for report in reports):
        raise RuntimeError("At least one storage replay produced a different sentence")


if __name__ == "__main__":
    main()
