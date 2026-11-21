#!/usr/bin/env python3
"""Compare sequential and overlapping taps on the two consecutive g keys in zhongguo."""

import argparse
import json
from pathlib import Path
import shlex
import subprocess

from calibrate import HOST, PACKAGES, adb, select_product


def run(output, label, bounds, mode):
    directory = output / f"overlap-{label}-{mode}"
    directory.mkdir()
    select_product(PACKAGES[label])
    names = ("touches.json", "candidate-editor.json", "first-candidates.png", "committed.png")
    adb("shell", "run-as", HOST, "rm", "-f", *["files/" + name for name in names])
    parts = ["am", "instrument", "--no-hidden-api-checks", "-w", "-e", "package", PACKAGES[label],
             "-e", "bounds", json.dumps(bounds, separators=(",", ":")), "-e", "overlap", mode, HOST + "/.VisualReplay"]
    response = adb("shell", shlex.join(parts), timeout=220)
    (directory / "instrumentation.txt").write_text(response)
    for name in names:
        reply = subprocess.run(["adb", "exec-out", "run-as", HOST, "cat", "files/" + name], capture_output=True)
        if reply.returncode == 0 and reply.stdout:
            (directory / name).write_bytes(reply.stdout)
    (directory / "logcat.txt").write_text(adb("logcat", "-d", "-t", "1800"))
    if "status=ok" not in response:
        return {"failure": response}
    evidence = json.loads((directory / "touches.json").read_text())
    preedit = next((item["text"].split("|")[0].strip() for item in evidence["overlap_candidates"]
                    if item["text"].startswith(("zhong guo", "zhon guo"))), None)
    return {"text": evidence["output"], "preedit": preedit,
            "overlap_events": [event for event in evidence["touches"] if "action" in event]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expect-handover", action="store_true")
    args = parser.parse_args()
    if not adb("get-serialno").startswith("emulator-"):
        raise RuntimeError("The experiment requires a disposable emulator")
    products = json.loads((args.output / "calibration" / "products.json").read_text())
    results = {}
    for mode in ("sequential", "one-g", "older-up-first", "newer-up-first"):
        results[mode] = {}
        for label in PACKAGES:
            result = run(args.output, label, products[label]["bounds"], mode)
            results[mode][label] = result
            print(mode, label, result, flush=True)
            (args.output / "overlap-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    if any(result.get("text") != "中国" for result in results["sequential"].values()):
        raise RuntimeError("The sequential control did not commit 中国 in both products")
    if any("failure" in result for pair in results.values() for result in pair.values()):
        raise RuntimeError("An overlap replay could not inject its touches")
    if args.expect_handover:
        results["cancel-second"] = {"direct": run(args.output, "direct", products["direct"]["bounds"], "cancel-second")}
        (args.output / "overlap-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
        expected = {"sequential": "zhong guo", "one-g": "zhon guo", "older-up-first": "zhong guo",
                    "newer-up-first": "zhong guo", "cancel-second": "zhon guo"}
        for mode, preedit in expected.items():
            if results[mode]["direct"].get("preedit") != preedit:
                raise RuntimeError(f"{mode}: expected {preedit}, observed {results[mode]['direct']}")


if __name__ == "__main__":
    main()
