#!/usr/bin/env python3
"""Compare the same Direct Rime APK and deployed profile on emulated and private storage."""

import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

from calibrate import HOST, PACKAGES, WANXIANG_MODEL, WANXIANG_MODEL_SHA, adb
from run import replay
from sentences import SENTENCES


PACKAGE = PACKAGES["direct"]
EXTERNAL = f"/data/media/0/Android/data/{PACKAGE}/files"
PRIVATE = f"/data/user/0/{PACKAGE}/files/benchmark-wanxiang"
PROFILE_CONF = EXTERNAL + "/config/conf/rime-data-dir.conf"
ASSET_PROBE = "/data/local/tmp/cyime-asset-residency"
ASSETS = (WANXIANG_MODEL, "build/wanxiang.table.bin")


def stop_input():
    adb("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS")
    adb("shell", "am", "force-stop", HOST)
    adb("shell", "am", "force-stop", PACKAGE)


def profile_evidence(root):
    file_hashes = adb("shell", "cd " + shlex.quote(root) + " && " + shlex.join([
        "find", ".", "-type", "f", "-exec", "sha256sum", "{}", "+",
    ]))
    return dict(sorted((line.split("  ", 1)[1], line.split("  ", 1)[0]) for line in file_hashes.splitlines()))


def storage_profile(output, location, uid, alternate):
    stop_input()
    alternate_user_path = alternate.replace("/data/media/0/", "/storage/emulated/0/", 1)
    paths = "" if location == "external" else "rime".encode().hex() + "|" + alternate_user_path.encode().hex()
    config = f'Current="rime"\nProfiles="rime"\nExternalPathsV2="{paths}"\n'
    local = output / f"{location}-profile.conf"
    local.write_text(config)
    adb("shell", "mkdir", "-p", str(Path(PROFILE_CONF).parent))
    adb("push", local, PROFILE_CONF)
    adb("shell", "chown", f"{uid}:{uid}", PROFILE_CONF, str(Path(PROFILE_CONF).parent))


def kib_fields(text):
    return {name: int(value) for name, value in re.findall(r"^([\w()]+):\s+(\d+) kB$", text, re.MULTILINE)}


def measure_assets(root, mode="inspect"):
    return {name: json.loads(adb("shell", ASSET_PROBE, mode, f"{root}/{name}")) for name in ASSETS}


def app_memory(pid):
    maps = adb("shell", "cat", f"/proc/{pid}/smaps")
    files = {name: {} for name in ASSETS}
    # smaps reports a separate section for every mapped region.
    for region in re.split(r"(?=^[a-f0-9]+-[a-f0-9]+ )", maps, flags=re.MULTILINE):
        if not region:
            continue
        for name in files:
            if region.split("\n", 1)[0].endswith("/" + name):
                for field, size in kib_fields(region).items():
                    files[name][field] = files[name].get(field, 0) + size
    if any("Rss" not in fields for fields in files.values()):
        raise RuntimeError("The IME did not map both measured assets")
    return {"process_kib": kib_fields(adb("shell", "cat", f"/proc/{pid}/smaps_rollup")), "asset_mappings_kib": files}


def record(output, name, bounds, location, cold, alternate, assets_warm_ms, resources):
    capture = Path(__file__).resolve().parent.parent / "input-perf" / "capture.sh"
    trace = output / f"{name}.perfetto-trace"
    pinyin, expected = SENTENCES["lyric"]
    with (output / f"{name}-capture.txt").open("w") as log:
        duration = "42000" if cold else "30000"
        recorder = subprocess.Popen(["bash", str(capture), str(trace), duration, "storage"], stdout=subprocess.PIPE,
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
            folder = "/files/data/rime/" if location == "external" else alternate[alternate.index("/files/"):] + "/"
            if len(model_paths) != 1 or folder not in model_paths[0]:
                raise RuntimeError(f"Mapped profile did not use {location}: {model_paths}")
            report["location"] = location
            report["guest_page_cache_dropped"] = cold
            if assets_warm_ms is not None:
                report["model_and_table_warm_ms"] = assets_warm_ms
            if resources is not None:
                root = EXTERNAL + "/data/rime" if location == "external" else alternate
                resources["after_typing"] = {
                    "system_kib": kib_fields(adb("shell", "cat", "/proc/meminfo")),
                    "assets": measure_assets(root),
                    **app_memory(pid),
                }
                (output / name / "resources.json").write_text(json.dumps(resources, indent=2) + "\n")
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
    parser.add_argument("--cold", action="store_true")
    parser.add_argument("--prewarm", action="store_true",
                        help="compare cold external profiles with and without reading the grammar and main table first")
    parser.add_argument("--asset-probe", type=Path)
    args = parser.parse_args()
    if args.asset_probe and not args.prewarm:
        parser.error("--asset-probe requires the --prewarm comparison")
    output = args.output
    if not adb("get-serialno").startswith("emulator-") or adb("shell", "id", "-u") != "0":
        raise RuntimeError("This comparison requires the disposable root Android emulator")
    calibration = output / "calibration"
    if not json.loads((calibration / "shared-wanxiang.json").read_text())["verified"]:
        raise RuntimeError("Shared Wanxiang calibration did not pass")
    if args.asset_probe:
        adb("push", args.asset_probe, ASSET_PROBE)
        adb("shell", "chmod", "755", ASSET_PROBE)
    bounds = json.loads((calibration / "products.json").read_text())["direct"]["bounds"]
    stop_input()
    uid = re.search(r"uid:(\d+)", adb("shell", "cmd", "package", "list", "packages", "-U", PACKAGE))[1]
    owner = f"{uid}:{uid}"
    external = EXTERNAL + "/data/rime"
    alternate = EXTERNAL + "/data/rime-warmed" if args.prewarm else PRIVATE
    adb("shell", "mkdir", "-p", alternate)
    adb("shell", "cp", "-a", external + "/.", alternate)
    adb("shell", "chown", "-R", owner, alternate)
    adb("shell", "restorecon", "-RF", alternate)
    original = profile_evidence(external)
    if original.get("./" + WANXIANG_MODEL) != WANXIANG_MODEL_SHA or original != profile_evidence(alternate):
        raise RuntimeError("The two benchmark profiles must start with identical files")
    (output / "storage-profile-sha256.json").write_text(json.dumps(original, ensure_ascii=False, indent=2) + "\n")
    reports = []
    other = "warmed" if args.prewarm else "private"
    cold = args.cold or args.prewarm
    try:
        for i, location in enumerate(("external", other, other, "external"), 1):
            storage_profile(output, location, uid, alternate)
            if cold:
                # Run only on the root disposable emulator checked above, with both input processes stopped.
                adb("shell", "sync && echo 3 > /proc/sys/vm/drop_caches")
            resources = None
            if args.asset_probe:
                root = external if location == "external" else alternate
                resources = {"before_preparation": {
                    "system_kib": kib_fields(adb("shell", "cat", "/proc/meminfo")), "assets": measure_assets(root),
                }}
            prepared_ms = None
            if location == "warmed":
                start = time.monotonic()
                if args.asset_probe:
                    resources["worker"] = measure_assets(alternate, "read")
                else:
                    command = shlex.join(["cat", *(f"{alternate}/{name}" for name in ASSETS)])
                    adb("shell", command + " > /dev/null")
                prepared_ms = round((time.monotonic() - start) * 1000, 2)
            if resources is not None:
                resources["before_ime_start"] = {
                    "system_kib": kib_fields(adb("shell", "cat", "/proc/meminfo")), "assets": measure_assets(root),
                }
            reports.append(record(output, f"storage-{i}-{location}", bounds, location, cold, alternate, prepared_ms, resources))
    finally:
        storage_profile(output, "external", uid, alternate)
    if not all(report["exact_commit"] for report in reports):
        raise RuntimeError("At least one storage replay produced a different sentence")


if __name__ == "__main__":
    main()
