#!/usr/bin/env python3
"""Run paired physical touch injections and save the original frame and editor evidence."""

import argparse
import json
from pathlib import Path
import shlex
import subprocess
import time

from calibrate import HOST, PACKAGES, WORDS, adb, select_product
from video import analyze, describe


def replay(directory, label, bounds, words, cadence):
    directory.mkdir()
    select_product(PACKAGES[label])
    adb("shell", "run-as", HOST, "rm", "-f", "files/touches.json", "files/candidate-editor.json",
        "files/before.png", "files/first-candidates.png", "files/wrong-output.png")
    recorded = "/sdcard/cyime-latency.mp4"
    adb("shell", "rm", "-f", recorded)
    arguments = ["am", "instrument", "-w", "-e", "package", PACKAGES[label],
                 "-e", "bounds", json.dumps(bounds, separators=(",", ":")),
                 "-e", "words", json.dumps(words, ensure_ascii=False, separators=(",", ":")),
                 "-e", "cadence", str(cadence), HOST + "/.VisualReplay"]
    with (directory / "recorder.txt").open("w") as log:
        process = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "180", "--bit-rate", "12000000", recorded],
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            time.sleep(1)
            if not adb("shell", "pidof", "screenrecord", check=False):
                raise RuntimeError("screenrecord failed to start")
            response = adb("shell", shlex.join(arguments), timeout=240)
            (directory / "instrumentation.txt").write_text(response)
            if "status=ok" not in response:
                raise RuntimeError("Replay failed, see instrumentation evidence")
        finally:
            adb("shell", "pkill", "-INT", "screenrecord", check=False)
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            adb("pull", recorded, directory / "run.mp4", check=False)
            for name in ("touches.json", "candidate-editor.json", "before.png", "first-candidates.png", "wrong-output.png"):
                reply = subprocess.run(["adb", "exec-out", "run-as", HOST, "cat", "files/" + name], capture_output=True)
                if reply.returncode == 0 and reply.stdout:
                    (directory / name).write_bytes(reply.stdout)
            (directory / "logcat.txt").write_text(adb("logcat", "-d", "-t", "1800"))
    result = analyze(directory, bounds, words)
    (directory / "analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output
    if not adb("get-serialno").startswith("emulator-"):
        raise RuntimeError("The experiment requires a disposable emulator")
    products = json.loads((output / "calibration" / "products.json").read_text())
    for label, package in PACKAGES.items():
        profile = adb("shell", "am", "broadcast", "-a", "androidx.profileinstaller.action.INSTALL_PROFILE",
                      "-n", package + "/androidx.profileinstaller.ProfileInstallReceiver", check=False)
        compile_result = adb("shell", "cmd", "package", "compile", "-f", "-m", "speed-profile", package, check=False)
        (output / (label + "-art.txt")).write_text(profile + "\n" + compile_result + "\n" + adb("shell", "dumpsys", "package", package))

    reports = []
    for trial, cadence in enumerate((120, 80, 80, 120), start=1):
        order = ("direct", "cyime") if trial in (1, 2) else ("cyime", "direct")
        words = WORDS * 3
        for label in order:
            report = {"trial": trial, "label": label, "cadence_ms": cadence, "order": list(order)}
            reports.append(report)
            prefix = f"{trial:02d}-{label}"
            print(f"START {prefix} at {cadence} ms", flush=True)
            try:
                report.update(replay(output / prefix, label, products[label]["bounds"], words, cadence))
            except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
                report["failure"] = str(error)
            print(f"{prefix}: {report.get('failure', report.get('metrics'))}", flush=True)
            (output / "replays.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n")

    successful = [report for report in reports if "failure" not in report]
    summary = {}
    for cadence in (120, 80):
        pairs = []
        for trial in sorted({report["trial"] for report in reports if report["cadence_ms"] == cadence}):
            pair = {report["label"]: report for report in successful if report["trial"] == trial}
            if len(pair) == 2:
                pairs.append(pair)
        summary[str(cadence)] = {"paired_replays": len(pairs), "products": {}}
        for label in PACKAGES:
            analysis = [pair[label] for pair in pairs]
            summary[str(cadence)]["products"][label] = {
                metric: describe([item["metrics"][metric]["median_ms"] for item in analysis if item["metrics"][metric]["n"]])
                for metric in ("down_to_feedback", "up_to_candidate", "selection_down_to_commit", "warm_up_to_candidate")
            }
    (output / "paired-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    if len(successful) != len(reports):
        raise RuntimeError(f"Only {len(successful)}/{len(reports)} replays passed, see evidence")


if __name__ == "__main__":
    main()
