"""Require executed, successful tests; a skipped environment-specific case is not CI acceptance."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testFxDebugUnitTest")
totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
reports = list(root.rglob("TEST-*.xml"))
for report in reports:
    suite = ET.parse(report).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, "0"))
print({"report_files": len(reports), **totals})
if not totals["tests"] or any(totals[key] for key in ("failures", "errors", "skipped")):
    raise SystemExit("JUnit acceptance requires tests with no failures, errors or skipped cases")
