#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("summarize.py")
SPEC = importlib.util.spec_from_file_location("input_perf_summarize", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class SummarizeTest(unittest.TestCase):
    def test_pins_csv_output_for_every_trace_query(self):
        results = [
            subprocess.CompletedProcess([], 0, 'name,ms\n"rime.direct.snapshot",0.25\n', ""),
            subprocess.CompletedProcess([], 0, 'name,value\n"rime.service.wait_ns",1000000\n', ""),
        ]
        output = io.StringIO()
        with mock.patch.object(MODULE.subprocess, "run", side_effect=results) as run:
            with mock.patch.object(sys, "argv", [str(SCRIPT), "capture.trace", "--trace-processor", "tp"]):
                with contextlib.redirect_stdout(output):
                    MODULE.main()

        self.assertEqual(2, run.call_count)
        for call in run.call_args_list:
            self.assertEqual(
                ["tp", "query", "--no-agent", "--format", "csv", "capture.trace"],
                call.args[0][:6],
            )
        summary = json.loads(output.getvalue())
        self.assertEqual(0.25, summary["slices"]["rime.direct.snapshot"]["p95_ms"])
        self.assertEqual(1.0, summary["counters"]["rime.service.wait_ns"]["p99_ms"])


if __name__ == "__main__":
    unittest.main()
