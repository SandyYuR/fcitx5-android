#!/usr/bin/env python3
"""Summarize named slices and opt-in input counters without adding their percentiles."""
import argparse
import csv
import io
import json
import math
import subprocess
from collections import defaultdict


def percentiles(values):
    values = sorted(values)
    return {"samples": len(values), **{
        f"p{p}_ms": values[max(0, math.ceil(len(values) * p / 100) - 1)]
        for p in (50, 95, 99)
    }}


def counter_summary(name, values):
    if name.endswith("_ns"):
        return {"unit": "ms", **percentiles([value / 1_000_000.0 for value in values])}
    return {"samples": len(values), "last": values[-1], "max": max(values)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace")
    parser.add_argument("--trace-processor", default="trace_processor")
    args = parser.parse_args()
    query = """SELECT name, dur / 1000000.0 AS ms FROM slice
        WHERE dur > 0 AND (name GLOB 'rime.*' OR name IN
        ('sendKey JNI', 'HandleFcitxEvent', 'updateCandidates'))"""
    # New trace_processor releases auto-detect coding agents and switch stdout
    # from CSV to Markdown unless the format is pinned. Keep this script stable
    # across interactive shells, CI and agent-driven local investigations.
    query_command = [args.trace_processor, "query", "--no-agent", "--format", "csv"]
    slice_result = subprocess.run([*query_command, args.trace, query],
                                  check=True, capture_output=True, text=True)
    slices = defaultdict(list)
    for row in csv.DictReader(io.StringIO(slice_result.stdout)):
        slices[row["name"]].append(float(row["ms"]))
    counter_query = """SELECT t.name AS name, c.value AS value FROM counter c
        JOIN counter_track t ON c.track_id = t.id
        WHERE t.name GLOB 'rime.*' ORDER BY c.ts"""
    counter_result = subprocess.run([*query_command, args.trace, counter_query],
                                    check=True, capture_output=True, text=True)
    counters = defaultdict(list)
    for row in csv.DictReader(io.StringIO(counter_result.stdout)):
        counters[row["name"]].append(float(row["value"]))
    if not slices and not counters:
        raise SystemExit("No Rime slices or counters found; enable inputDiagnostics and capture the correct APK.")
    print(json.dumps({"metric": "component slices and counters; do not add independent percentiles",
                      "slices": {name: percentiles(values) for name, values in sorted(slices.items())},
                      "counters": {name: counter_summary(name, values) for name, values in sorted(counters.items())}},
                     indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
