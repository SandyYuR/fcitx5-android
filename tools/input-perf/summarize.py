#!/usr/bin/env python3
"""Summarize named trace slices; these are component times, not end-to-end latency."""
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace")
    parser.add_argument("--trace-processor", default="trace_processor")
    args = parser.parse_args()
    query = """SELECT name, dur / 1000000.0 AS ms FROM slice
        WHERE dur > 0 AND (name GLOB 'rime.*' OR name IN
        ('sendKey JNI', 'HandleFcitxEvent', 'updateCandidates'))"""
    result = subprocess.run([args.trace_processor, "query", args.trace, query],
                            check=True, capture_output=True, text=True)
    series = defaultdict(list)
    for row in csv.DictReader(io.StringIO(result.stdout)):
        series[row["name"]].append(float(row["ms"]))
    if not series:
        raise SystemExit("No Rime slices found; enable inputDiagnostics and capture the correct APK.")
    print(json.dumps({"metric": "trace slice duration; not end-to-end latency",
                      "slices": {name: percentiles(values) for name, values in sorted(series.items())}},
                     indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
