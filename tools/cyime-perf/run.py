#!/usr/bin/env python3
"""Run paired physical touch injections and save the original frame and editor evidence."""

import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

from calibrate import HOST, PACKAGES, WORDS, adb, select_product
from sentences import SENTENCES
from video import analyze, describe


def replay(directory, label, bounds, words, cadence, sentence=False, pause_after=0, pause_ms=0, record_video=True,
           quick=False, idle_before_ms=0):
    directory.mkdir()
    select_product(PACKAGES[label])
    adb("shell", "run-as", HOST, "rm", "-f", "files/touches.json", "files/candidate-editor.json",
        "files/before.png", "files/first-candidates.png", "files/wrong-output.png")
    recorded = "/sdcard/cyime-latency.mp4"
    if record_video:
        adb("shell", "rm", "-f", recorded)
    arguments = ["am", "instrument", "--no-hidden-api-checks", "-w", "-e", "package", PACKAGES[label],
                 "-e", "bounds", json.dumps(bounds, separators=(",", ":")),
                 "-e", "words", json.dumps(words, ensure_ascii=False, separators=(",", ":")),
                 "-e", "cadence", str(cadence), "-e", "sentence", str(sentence).lower(),
                 "-e", "quick", str(quick).lower(), "-e", "idleBeforeMs", str(idle_before_ms),
                 "-e", "pauseAfter", str(pause_after), "-e", "pauseMs", str(pause_ms), HOST + "/.VisualReplay"]
    with (directory / "recorder.txt").open("w") as log:
        process = None
        try:
            if record_video:
                process = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "180", "--bit-rate", "12000000", recorded],
                                           stdout=log, stderr=subprocess.STDOUT)
                time.sleep(1)
                if not adb("shell", "pidof", "screenrecord", check=False):
                    raise RuntimeError("screenrecord failed to start")
            response = adb("shell", shlex.join(arguments), timeout=240)
            (directory / "instrumentation.txt").write_text(response)
            if "status=ok" not in response:
                raise RuntimeError("Replay failed, see instrumentation evidence")
        finally:
            if process:
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
    if not record_video:
        editor = json.loads((directory / "candidate-editor.json").read_text())
        expected = [word for _, word in words]
        commits = [item["text"] for item in editor["commits"] if item["text"]]
        return {"observed_text": editor["text"], "exact_commit": commits == expected and editor["text"] == "".join(expected)}
    result = analyze(directory, bounds, words, cadence, sentence, pause_after, pause_ms)
    (directory / "analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return result


def compare_sentences(output, products):
    reports = []
    pairs = []
    cadence = 120

    def pair(name, trial, order, pause_ms=0):
        syllables, text = SENTENCES[name]
        pause_after = len("".join(syllables.split()[:10])) if pause_ms else 0
        result = {}
        for label in order:
            prefix = f"{name}-{trial}-{label}"
            report = {"case": name, "trial": trial, "label": label, "order": list(order),
                      "cadence_ms": cadence, "pinyin": syllables, "expected_text": text,
                      "pause_after_letters": pause_after, "extra_pause_ms": pause_ms}
            result[label] = report
            reports.append(report)
            print(f"START {prefix}", flush=True)
            try:
                report.update(replay(output / prefix, label, products[label]["bounds"],
                                     [(syllables.replace(" ", ""), text)], cadence, True, pause_after, pause_ms))
            except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
                report["failure"] = str(error)
            print(f"{prefix}: {report.get('failure', report.get('metrics'))}, "
                  f"exact={report.get('exact_commit')}, text={report.get('observed_text')!r}", flush=True)
            (output / "sentence-replays.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n")
        if all("rows" in report for report in result.values()):
            direct, cyime = result["direct"], result["cyime"]
            letters = [(a, b) for a, b in zip(direct["rows"], cyime["rows"])
                       if a["key"] != "select" and a["on_cadence"] and b["on_cadence"]]
            matched = {"case": name, "trial": trial, "all_letters": direct["letters"],
                       "letters_with_both_intervals_within_10ms": len(letters),
                       "exact_both": direct["exact_commit"] and cyime["exact_commit"]}
            for metric in ("down_to_feedback", "up_to_candidate"):
                observed = [(a[metric], b[metric]) for a, b in letters if a[metric] is not None and b[metric] is not None]
                matched[metric] = {"direct": describe([a for a, _ in observed]),
                                   "cyime": describe([b for _, b in observed]),
                                   "cyime_minus_direct": describe([b - a for a, b in observed])}
            pairs.append(matched)
            print(json.dumps(matched, ensure_ascii=False), flush=True)
            (output / "paired-sentences.json").write_text(json.dumps(pairs, ensure_ascii=False, indent=2) + "\n")
        return result

    orders = (("direct", "cyime"), ("cyime", "direct"))
    initial = {name: pair(name, "continuous", orders[i % 2]) for i, name in enumerate(SENTENCES)}
    common = [name for name, paired in initial.items() if all(result.get("exact_commit") for result in paired.values())]
    chosen = common[0] if common else "lyric"
    order = tuple(initial[chosen]["direct"]["order"])
    pair(chosen, "continuous-repeat", order[::-1])
    pair(chosen, "pause", order, 650)
    pair(chosen, "pause-repeat", order[::-1], 650)
    if not any(pair["trial"] == "continuous" and pair["up_to_candidate"]["direct"]["n"] for pair in pairs):
        raise RuntimeError("No paired, on-cadence candidate observations for continuous sentences")


def diagnose_pause(output, bounds):
    """Read CyIME's own correction timings in a separate, instrumented run."""
    name = "log.tag.CyIMELatency"
    saved = adb("shell", "getprop", name)
    adb("shell", "setprop", name, "DEBUG")
    reports = []
    try:
        pinyin, text = SENTENCES["park"]
        cut = len("".join(pinyin.split()[:10]))
        for variant, code, pause in (("continuous", pinyin, 0), ("pause", pinyin, 650),
                                     ("pause-swap", pinyin.replace(" gong ", " gogn "), 650)):
            path = output / ("diagnostic-" + variant)
            report = {"variant": variant, "pinyin": code, "pause_after_letters": cut if pause else None}
            try:
                result = replay(path, "cyime", bounds, [(code.replace(" ", ""), text)], 120, True, cut, pause)
                report["candidate_settle"] = result["candidate_settle"]
            except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
                report["failure"] = str(error)
            log = path / "logcat.txt"
            if log.exists():
                logs = log.read_text()
                report["probe_enabled"] = "probe on sdk=" in logs
                report["corrections"] = [{"letters": int(length), "engine_ms": int(elapsed), "changed": changed == "true"}
                                         for length, elapsed, changed in re.findall(
                                             r"CyIMELatency\s*:\s+refine len=(\d+) ms=(\d+) changed=(true|false)", logs)]
            reports.append(report)
            print(json.dumps(report, ensure_ascii=False), flush=True)
        (output / "pause-diagnostics.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n")
        if not any(report.get("probe_enabled") for report in reports):
            raise RuntimeError("CyIME did not activate its built-in latency probe")
    finally:
        adb("shell", shlex.join(("setprop", name, saved)))


def trace_long_sentence(output, bounds):
    """Capture the existing release trace markers alongside the same lyric replay."""
    capture = Path(__file__).resolve().parent.parent / "input-perf" / "capture.sh"
    pinyin, text = SENTENCES["lyric"]
    trace = output / "direct-long.perfetto-trace"
    with (output / "direct-long-capture.txt").open("w") as log:
        recorder = subprocess.Popen(["bash", str(capture), str(trace), "45000"], stdout=subprocess.PIPE,
                                    stderr=log, text=True)
        try:
            ready = recorder.stdout.readline()
            log.write(ready)
            if "Tracing is ready" not in ready:
                raise RuntimeError("Perfetto did not start")
            report = replay(output / "direct-long-traced", "direct", bounds,
                            [(pinyin.replace(" ", ""), text)], 120, True)
            (output / "direct-long-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        finally:
            stdout, _ = recorder.communicate(timeout=65)
            log.write(stdout)
    if recorder.returncode or not trace.is_file():
        raise RuntimeError("Perfetto did not save a trace")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sentences", action="store_true")
    parser.add_argument("--pause-diagnostic", action="store_true")
    parser.add_argument("--trace-long", action="store_true")
    args = parser.parse_args()
    output = args.output
    if not adb("get-serialno").startswith("emulator-"):
        raise RuntimeError("The experiment requires a disposable emulator")
    products = json.loads((output / "calibration" / "products.json").read_text())
    for label in products:
        package = PACKAGES[label]
        profile = adb("shell", "am", "broadcast", "-a", "androidx.profileinstaller.action.INSTALL_PROFILE",
                      "-n", package + "/androidx.profileinstaller.ProfileInstallReceiver", check=False)
        compile_result = adb("shell", "cmd", "package", "compile", "-f", "-m", "speed-profile", package, check=False)
        (output / (label + "-art.txt")).write_text(profile + "\n" + compile_result + "\n" + adb("shell", "dumpsys", "package", package))

    if args.pause_diagnostic:
        diagnose_pause(output, products["cyime"]["bounds"])
        return
    if args.trace_long:
        trace_long_sentence(output, products["direct"]["bounds"])
        return
    if args.sentences:
        compare_sentences(output, products)
        return

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
