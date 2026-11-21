#!/usr/bin/env python3
"""Prepare and verify two IME builds on the same disposable Android emulator."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

from sentences import LETTERS

# Keep this suite's modules ahead of the helpers with the same filenames.
sys.path.insert(1, str(Path(__file__).resolve().parent.parent / "foxy-perf"))
from pilot import adb, capture, HOST, selected_ime

PACKAGES = {"direct": "org.fcitx.fcitx5.android.fx.rime", "cyime": "com.cyletix.cyime"}
WORDS = [("ni", "你"), ("nihao", "你好"), ("zhongguo", "中国"), ("women", "我们")]
WANXIANG_MODEL = "wanxiang-lts-zh-hans.gram"
WANXIANG_MODEL_SHA = "769854c83f6c914a05a1e1abb67c31c00f1ae622a41878e05957bcdee3dbda9a"
WANXIANG_PAGE_SIZE = 6


def wait_for_root(output):
    responses = []
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        try:
            uid = adb("shell", "id", "-u", check=False, timeout=8)
            if uid == "0":
                (output / "adb-root.txt").write_text("\n".join(responses + ["uid=0"]) + "\n")
                return
            response = subprocess.run(["adb", "root"], capture_output=True, text=True, timeout=8)
            responses.append((response.stdout + response.stderr).strip())
        except subprocess.TimeoutExpired:
            responses.append("ADB temporarily offline during startup or root restart")
        time.sleep(3)
    (output / "adb-root.txt").write_text("\n".join(responses) + "\n")
    raise RuntimeError("Root adbd did not return on the disposable emulator")


def install_fixture(source, output, label):
    package = PACKAGES[label]
    if label == "direct":
        target = f"/sdcard/Android/data/{package}/files/data/rime"
        physical = target.replace("/sdcard/", "/data/media/0/", 1)
        # The app creates its own scoped-storage directory.
        select_product(package)
        adb("shell", "am", "start", "-W", "-n", HOST + "/.VisualHost")
        parent = str(Path(physical).parents[1])
        deadline = time.monotonic() + 45
        while not adb("shell", "ls", "-d", parent, check=False):
            if time.monotonic() >= deadline:
                raise RuntimeError("The IME did not create its own scoped storage directory")
            time.sleep(1)
        adb("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS")
        adb("shell", "am", "force-stop", HOST)
    else:
        target = physical = f"/data/user/0/{package}/files/rime"
        parent = str(Path(physical).parents[1])
    owner = adb("shell", "cmd", "package", "list", "packages", "-U", package)
    uid = re.search(r"uid:(\d+)", owner)[1]
    adb("shell", "am", "force-stop", package)
    staging = physical + ".benchmark-fixture"
    adb("shell", "mkdir", "-p", staging)
    adb("push", str(source) + "/.", staging)
    # The empty first startup can leave generated state in rime/. Swap in
    # the complete fixture rather than overlaying that state.
    if adb("shell", "ls", "-d", physical, check=False):
        adb("shell", "mv", physical, physical + ".unseeded")
    adb("shell", "mv", staging, physical)
    data = str(Path(physical).parent)
    adb("shell", "chown", "-R", f"{uid}:{uid}", data)
    adb("shell", "restorecon", "-RF", data)
    (output / f"{label}-wanxiang-files.txt").write_text(adb("shell", "ls", "-ldZ", parent, data, physical, physical + "/default.yaml"))
    return target


def configure_preferences(package, prefs_name, key, wanxiang_page_size=None):
    # Keep the actual key highlight, while stopping the enlarged key popup
    # from entering the candidate pixels on the row above the keys.
    adb("shell", "am", "force-stop", package)
    folder = f"/data/user/0/{package}/shared_prefs"
    path = f"{folder}/{prefs_name}.xml"
    saved = adb("shell", "cat", path, check=False)
    settings = ET.fromstring(saved) if saved else ET.Element("map")
    values = {key: ("boolean", "false")}
    if wanxiang_page_size is not None:
        # CyIME takes emoji from Android settings when it restores the scheme,
        # overriding the true value in the pinned Wanxiang user.yaml otherwise.
        values.update(current_schema=("string", "wanxiang"), current_schema_dual=("boolean", "true"),
                      page_size=("int", str(wanxiang_page_size)), candidate_emoji=("boolean", "true"),
                      builtin_schemas_merged=("boolean", "true"), cyime_chinese_defaults_v1=("boolean", "true"),
                      cyime_double_pinyin_defaults_v1=("boolean", "true"),
                      japanese_schemas_added_v1=("boolean", "true"))
    for item in list(settings):
        if item.get("name") in values:
            settings.remove(item)
    for name, (kind, value) in values.items():
        item = ET.SubElement(settings, kind, {"name": name})
        if kind == "string":
            item.text = value
        else:
            item.set("value", value)
    uid = re.search(r"uid:(\d+)", adb("shell", "cmd", "package", "list", "packages", "-U", package))[1]
    with tempfile.TemporaryDirectory(prefix="cyime-prefs-") as source:
        xml = Path(source) / "settings.xml"
        ET.ElementTree(settings).write(xml, encoding="utf-8", xml_declaration=True)
        adb("shell", "mkdir", "-p", folder)
        adb("push", xml, path)
    adb("shell", "chown", f"{uid}:{uid}", folder, path)
    adb("shell", "chmod", "700", folder)
    adb("shell", "chmod", "600", path)
    adb("shell", "restorecon", "-RF", folder)


def seed_profiles(fixture, output, packages, shared):
    profiles = {"cyime": (f"/data/user/0/{PACKAGES['cyime']}/files/rime", "rime_ice")}
    checksums = {}
    with tempfile.TemporaryDirectory(prefix="cyime-fixture-") as directory:
        source = Path(directory)
        with zipfile.ZipFile(fixture) as archive:
            if any(Path(n).is_absolute() or ".." in Path(n).parts for n in archive.namelist()):
                raise ValueError("Invalid fixture path")
            archive.extractall(source)
        if shared:
            import yaml

            # CyIME imports its bundled global patch when this file is absent.
            # Carry the fixture's own values in both profiles to keep the same defaults.
            custom = source / "default.custom.yaml"
            if not custom.exists():
                defaults = yaml.safe_load((source / "default.yaml").read_text(encoding="utf-8-sig"))
                patch = {"patch": {"schema_list": defaults["schema_list"], "menu": defaults["menu"]}}
                custom.write_text(yaml.safe_dump(patch, sort_keys=False, allow_unicode=True, indent=4))
            (output / "wanxiang-seed-default.custom.yaml").write_bytes(custom.read_bytes())
            for path in sorted(source.rglob("*")):
                relative = path.relative_to(source)
                # Both packages can supply documentation with the same name.
                if path.is_file() and relative.suffix not in (".md", ".jpg") and relative.parts[0] != "build" and relative.name not in (
                    "default.yaml", "default.custom.yaml", "user.yaml", "installation.yaml"
                ):
                    digest = hashlib.sha256()
                    with path.open("rb") as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                            digest.update(chunk)
                    checksums[relative.as_posix()] = digest.hexdigest()
            if checksums.get(WANXIANG_MODEL) != WANXIANG_MODEL_SHA or "wanxiang.custom.yaml" not in checksums:
                raise RuntimeError("The shared fixture is missing the pinned grammar model or Wanxiang customization")
            (output / "wanxiang-source-sha256.json").write_text(json.dumps(checksums, indent=2) + "\n")
            (output / "wanxiang-seed-user.yaml").write_bytes((source / "user.yaml").read_bytes())
        for label in packages:
            if shared or label == "direct":
                profiles[label] = (install_fixture(source, output, label), "wanxiang")
    return profiles, checksums


def verify_shared_profile(out, label, pid, root, checksums, entry):
    import yaml

    logs = adb("logcat", "-d", "--pid=" + pid)
    (out / "startup-log.txt").write_text(logs)
    if label == "cyime":
        if not re.search(r"initRimeEngine: .*savedSchema=wanxiang(?:,|\s)", logs):
            raise RuntimeError("CyIME did not select Wanxiang at startup")
    schema = yaml.safe_load((out / "effective-schema.yaml").read_text())
    if schema["schema"]["schema_id"] != "wanxiang" or schema["menu"]["page_size"] != WANXIANG_PAGE_SIZE:
        raise RuntimeError("Deployed schema or candidate page size differ from the shared fixture")
    schema.pop("__build_info", None)
    (out / "effective-schema.json").write_text(json.dumps(schema, indent=2, ensure_ascii=False, sort_keys=True) + "\n")
    user_file = adb("shell", "cat", f"{root}/user.yaml")
    (out / "user-after-calibration.yaml").write_text(user_file)
    user = yaml.safe_load(user_file)["var"]
    if user.get("previously_selected_schema") != "wanxiang":
        raise RuntimeError("The engine did not persist Wanxiang as the selected schema")
    if schema["grammar"]["language"] + ".gram" != WANXIANG_MODEL:
        raise RuntimeError("The deployed schema names a different grammar model")
    # Check the open model after real typing, and hash that exact file rather than a copy next to it.
    maps = adb("shell", "cat", f"/proc/{pid}/maps")
    (out / "active-maps.txt").write_text(maps)
    paths = {line.split(maxsplit=5)[-1] for line in maps.splitlines() if line.endswith("/" + WANXIANG_MODEL)}
    if len(paths) != 1:
        raise RuntimeError(f"Expected one mapped Wanxiang grammar model, found {sorted(paths)}")
    model_path = paths.pop()
    entry["grammar_model_sha256"] = adb("shell", shlex.join(["sha256sum", model_path])).split()[0]
    if entry["grammar_model_sha256"] != WANXIANG_MODEL_SHA:
        raise RuntimeError("Mapped Wanxiang grammar model has a different checksum")
    entry["grammar_model_path"] = model_path
    entry["page_size"] = schema["menu"]["page_size"]
    files = [name for name in checksums if name != WANXIANG_MODEL]
    result = {}
    for start in range(0, len(files), 50):
        command = f"cd {shlex.quote(root)} && " + shlex.join(["sha256sum", *files[start:start + 50]])
        for line in adb("shell", command, check=False).splitlines():
            digest, _, name = line.partition("  ")
            if re.fullmatch(r"[0-9a-f]{64}", digest):
                result[name] = digest
    (out / "wanxiang-source-sha256.json").write_text(json.dumps(result, indent=2) + "\n")
    entry["source_changes"] = [name for name in files if result.get(name) != checksums[name]]
    entry["seeded_options"] = user.get("option", {})
    for name in ("default.yaml", "default.custom.yaml", "build/default.yaml"):
        data = adb("shell", "cat", f"{root}/{name}", check=False)
        if data:
            (out / name.replace("/", "-")).write_text(data)


def verify_shared_comparison(output, support):
    import yaml

    schemas = [json.loads((output / label / "effective-schema.json").read_text()) for label in PACKAGES]
    differences = sorted(key for key in schemas[0].keys() | schemas[1].keys() if schemas[0].get(key) != schemas[1].get(key))
    defaults = [yaml.safe_load((output / label / "build-default.yaml").read_text()) for label in PACKAGES]
    default_differences = sorted(key for key in defaults[0].keys() | defaults[1].keys()
                                 if key != "__build_info" and defaults[0].get(key) != defaults[1].get(key))
    seeded = yaml.safe_load((output / "wanxiang-seed-user.yaml").read_text())["var"]["option"]
    report = {
        "schema_differences": differences,
        "default_differences": default_differences,
        "source_changes": {label: entry["source_changes"] for label, entry in support.items()},
        "seeded_option_differences": {
            label: {key: {"expected": value, "observed": entry["seeded_options"].get(key)}
                    for key, value in seeded.items() if entry["seeded_options"].get(key) != value}
            for label, entry in support.items()
        },
    }
    report["verified"] = not (differences or default_differences or any(report["source_changes"].values())
                              or any(report["seeded_option_differences"].values()))
    (output / "shared-wanxiang.json").write_text(json.dumps(report, indent=2) + "\n")
    if not report["verified"]:
        raise RuntimeError(f"Wanxiang profiles differ after deployment: {report}")
    print("Verified both active Wanxiang schemas, saved input options and mapped grammar SHA-256", flush=True)


def select_product(package):
    adb("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS")
    adb("shell", "am", "force-stop", "com.google.android.apps.nexuslauncher", check=False)
    for process in [HOST, *PACKAGES.values()]:
        adb("shell", "am", "force-stop", process)
    adb("logcat", "-c")
    return selected_ime(package)


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


def calibrate_touch(out, package, sentence_suite=False):
    keys = LETTERS if sentence_suite else "".join(sorted(set("".join(code for code, _ in WORDS))))
    evidence = ("touches.json", "candidate-editor.json", "before.png", "first-candidates.png", "committed.png")
    adb("shell", "run-as", HOST, "rm", "-f", *["files/" + name for name in evidence])
    args = ["am", "instrument", "-w", "-e", "package", package, "-e", "probe", "true",
            "-e", "letters", keys, HOST + "/.VisualReplay"]
    response = adb("shell", shlex.join(args), timeout=190)
    (out / "touch-calibration.txt").write_text(response)
    for name in evidence:
        reply = subprocess.run(["adb", "exec-out", "run-as", HOST, "cat", "files/" + name], capture_output=True)
        if reply.returncode == 0 and reply.stdout:
            (out / name).write_bytes(reply.stdout)
    record = json.loads((out / "touches.json").read_text())
    if "status=ok" not in response or record.get("output") != "你":
        raise ValueError(record.get("failure", "Actual touch did not commit 你"))
    return record["bounds"]


def verify_apk_abi(apk, abi):
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    with zipfile.ZipFile(apk) as archive:
        libraries = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
        if not libraries or {name.split("/")[1] for name in libraries} != {abi}:
            raise ValueError(f"{apk.name} does not contain only {abi} native libraries")
        for name in libraries:
            with archive.open(name) as library:
                header = library.read(20)
            if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != machine:
                raise ValueError(f"{name} is not a little-endian {abi} ELF64 library")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("direct", "cyime", "fixture", "host", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    parser.add_argument("--measure", action="store_true")
    parser.add_argument("--sentences", action="store_true")
    parser.add_argument("--pause-diagnostic", action="store_true")
    parser.add_argument("--shared-wanxiang", action="store_true")
    args = parser.parse_args()
    if args.shared_wanxiang and (args.pause_diagnostic or not args.measure):
        parser.error("--shared-wanxiang requires both products and measured touch calibration")
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    if not adb("get-serialno", check=False, timeout=10).startswith("emulator-"):
        raise RuntimeError("The experiment requires a disposable emulator")
    wait_for_root(output)
    adb("logcat", "-G", "16M", check=False)
    device_abi = adb("shell", "getprop", "ro.product.cpu.abi")
    if args.abi not in adb("shell", "getprop", "ro.product.cpu.abilist").split(","):
        raise RuntimeError(f"The Android image cannot run the configured ABI {args.abi}")
    for namespace, key, value in (
        ("global", "device_provisioned", "1"), ("secure", "user_setup_complete", "1"),
        ("global", "hide_error_dialogs", "1"), ("secure", "anr_show_background", "0"),
        ("secure", "show_ime_with_hard_keyboard", "1"), ("system", "screen_off_timeout", "1800000"),
    ):
        adb("shell", "settings", "put", namespace, key, value)
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS")
    adb("shell", "am", "force-stop", "com.google.android.apps.nexuslauncher", check=False)
    adb("shell", "wm", "size", "480x960")
    adb("shell", "wm", "density", "160")
    for package in ("com.google.android.setupwizard", "com.google.android.apps.messaging", "com.android.chrome"):
        adb("shell", "pm", "disable-user", "--user", "0", package, check=False)
    (output / "device-properties.txt").write_text(adb("shell", "getprop"))
    (output / "display.txt").write_text(adb("shell", "dumpsys", "display"))
    adb("install", "-r", args.host)
    packages = {"cyime": PACKAGES["cyime"]} if args.pause_diagnostic else PACKAGES
    for label in packages:
        apk = getattr(args, label)
        verify_apk_abi(apk, args.abi)
        adb("install", "-r", apk)
    if "direct" in packages:
        configure_preferences(PACKAGES["direct"], PACKAGES["direct"] + "_preferences", "popup_on_key_press")
    configure_preferences(PACKAGES["cyime"], "kime_settings", "show_press_bubble_v2",
                          WANXIANG_PAGE_SIZE if args.shared_wanxiang else None)
    if not args.shared_wanxiang:
        (output / "cyime-first-launch.txt").write_text(adb("shell", "am", "start", "-W", "-n",
            PACKAGES["cyime"] + "/com.kingzcheung.xime.MainActivity", check=False, timeout=45))
        (output / "cyime-first-launch-crashes.txt").write_text(adb("logcat", "-b", "crash", "-d", "-v", "threadtime"))
    profiles, checksums = seed_profiles(args.fixture, output, packages, args.shared_wanxiang)
    support = {}
    errors = []
    for label, package in packages.items():
        out = output / label
        out.mkdir(exist_ok=True)
        root, schema = profiles[label]
        entry = {"apk_sha256": hashlib.sha256(getattr(args, label).read_bytes()).hexdigest(), "schema": schema, "abi": args.abi}
        support[label] = entry
        try:
            entry["ime"] = select_product(package)
            adb("shell", "am", "start", "-W", "-n", HOST + "/.VisualHost")
            pid = wait_for_keyboard(label, package)
            maps = adb("shell", "cat", f"/proc/{pid}/maps")
            (out / "maps.txt").write_text(maps)
            package_info = adb("shell", "dumpsys", "package", package)
            (out / "package.txt").write_text(package_info)
            primary_abi = re.search(r"^\s*primaryCpuAbi=(\S+)\s*$", package_info, re.MULTILINE)
            if not primary_abi or primary_abi[1] != args.abi:
                raise RuntimeError(f"Process architecture does not match {args.abi}")
            entry["primary_cpu_abi"] = primary_abi[1]
            entry["device_cpu_abi"] = device_abi
            entry["arm64_translation"] = primary_abi[1] == "arm64-v8a" and device_abi == "x86_64"
            (out / "effective-schema.yaml").write_text(adb("shell", "cat", f"{root}/build/{schema}.schema.yaml"))
            user = adb("shell", "cat", f"{root}/user.yaml", check=False)
            if user:
                (out / "user.yaml").write_text(user)
            capture(out, "keyboard")
            if args.measure:
                entry["bounds"] = calibrate_touch(out, package, args.sentences)
            if args.shared_wanxiang:
                verify_shared_profile(out, label, pid, root, checksums, entry)
        except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
            entry["error"] = str(error)
            errors.append(label)
            print(f"{label}: {error}", flush=True)
            pids = adb("shell", "pidof", package, check=False).split()
            if pids:
                (out / "startup-log.txt").write_text(adb("logcat", "-d", "--pid=" + pids[0], check=False))
        finally:
            if not (out / "keyboard.png").exists():
                capture(out, "keyboard")
            (out / "input-method.txt").write_text(adb("shell", "dumpsys", "input_method"))
            (out / "window.txt").write_text(adb("shell", "dumpsys", "window", "windows"))
            (out / "logcat.txt").write_text(adb("logcat", "-d", "-v", "threadtime", "-t", "2500"))
            (out / "crashes.txt").write_text(adb("logcat", "-b", "crash", "-d", "-v", "threadtime"))
            (out / "exit-info.txt").write_text(adb("shell", "dumpsys", "activity", "exit-info", package, check=False))
            (output / "products.json").write_text(json.dumps(support, indent=2) + "\n")
        if label not in errors:
            print(f"{label}: keyboard and schema verified", flush=True)
    if errors:
        raise RuntimeError(f"Calibration failed for {', '.join(errors)}")
    if args.shared_wanxiang:
        verify_shared_comparison(output, support)


if __name__ == "__main__":
    main()
