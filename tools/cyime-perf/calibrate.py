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
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "foxy-perf"))
from pilot import adb, capture, HOST, selected_ime

PACKAGES = {"direct": "org.fcitx.fcitx5.android.fx.rime", "cyime": "com.cyletix.cyime"}
WORDS = [("ni", "你"), ("nihao", "你好"), ("zhongguo", "中国"), ("women", "我们")]


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


def disable_press_bubble(package, prefs_name, key):
    # Keep the actual key highlight, while stopping the enlarged key popup
    # from entering the candidate pixels on the row above the keys.
    adb("shell", "am", "force-stop", package)
    folder = f"/data/user/0/{package}/shared_prefs"
    path = f"{folder}/{prefs_name}.xml"
    saved = adb("shell", "cat", path, check=False)
    settings = ET.fromstring(saved) if saved else ET.Element("map")
    for item in list(settings):
        if item.get("name") == key:
            settings.remove(item)
    ET.SubElement(settings, "boolean", {"name": key, "value": "false"})
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


def select_product(package):
    adb("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS")
    for process in [HOST, *PACKAGES.values()]:
        adb("shell", "am", "force-stop", process)
    ime = selected_ime(package)
    adb("logcat", "-c")
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


def labeled_nodes(path, package):
    nodes = []
    for node in ET.parse(path).iter("node"):
        if node.get("package") != package:
            continue
        rect = tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(rect) == 4 and rect[2] > rect[0] and rect[3] > rect[1]:
            nodes.append(((node.get("text", ""), node.get("content-desc", "")), rect))
    return nodes


def key_bounds(nodes):
    keys = {}
    for key in sorted(set("".join(code for code, _ in WORDS))):
        matches = [rect for labels, rect in nodes if rect[1] > 480 and any(key == label.lower() for label in labels)]
        if not matches:
            raise ValueError(f"No accessible key bounds for {key!r}")
        keys[key] = max(matches, key=lambda r: (r[2] - r[0]) * (r[3] - r[1]))
    return keys


def tap(rect):
    adb("shell", "input", "tap", (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2)


def calibrate_touch(out, package):
    keys = key_bounds(labeled_nodes(out / "keyboard.xml", package))
    for key in "ni":
        tap(keys[key])
        time.sleep(.18)
    time.sleep(.6)
    capture(out, "composing")
    nodes = labeled_nodes(out / "composing.xml", package)
    composing_keys = key_bounds(nodes)
    for key, rect in keys.items():
        x, y = (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2
        active = composing_keys[key]
        if not (active[0] <= x < active[2] and active[1] <= y < active[3]):
            raise ValueError(f"{key}: keyboard moves outside its calibrated hit area while composing")
    top = min(rect[1] for rect in composing_keys.values())
    choices = [rect for labels, rect in nodes if top - 180 <= rect[1] < rect[3] <= top
               and any(re.fullmatch(r"\s*[1１]?[.、 ]*你\s*", label) for label in labels)]
    if not choices:
        raise ValueError("First candidate 你 is absent from the accessibility snapshot")
    selected = min(choices, key=lambda rect: (rect[0], -(rect[2] - rect[0]) * (rect[3] - rect[1])))
    tap(selected)
    time.sleep(.5)
    capture(out, "committed")
    editor_texts = [node.get("text") for node in ET.parse(out / "committed.xml").iter("node")
                    if node.get("package") == HOST and node.get("class") == "android.widget.EditText"]
    if editor_texts != ["你"]:
        raise ValueError(f"Actual touch did not commit 你: {editor_texts!r}")
    keys.update(select=selected, candidate=(0, selected[1], 480, selected[3]))
    return keys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("direct", "cyime", "fixture", "host", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--measure", action="store_true")
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
    disable_press_bubble(PACKAGES["direct"], PACKAGES["direct"] + "_preferences", "popup_on_key_press")
    disable_press_bubble(PACKAGES["cyime"], "kime_settings", "show_press_bubble_v2")
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
            entry["ime"] = select_product(package)
            adb("shell", "am", "start", "-W", "-n", HOST + "/.VisualHost")
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
            capture(out, "keyboard")
            if args.measure:
                entry["bounds"] = calibrate_touch(out, package)
        except (RuntimeError, OSError, ValueError, ET.ParseError) as error:
            entry["error"] = str(error)
            errors.append(label)
        finally:
            if not (out / "keyboard.png").exists():
                capture(out, "keyboard")
            (out / "input-method.txt").write_text(adb("shell", "dumpsys", "input_method"))
            (out / "logcat.txt").write_text(adb("logcat", "-d", "-v", "threadtime", "-t", "2500"))
            (output / "products.json").write_text(json.dumps(support, indent=2) + "\n")
    if errors:
        raise RuntimeError(f"Calibration failed for {', '.join(errors)}")


if __name__ == "__main__":
    main()
