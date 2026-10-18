#!/usr/bin/env bash
# Run the two observations independently; retain both sets if either harness fails.
set +e
set -uo pipefail
output=${1:?output directory}
python3 tools/foxy-perf/run.py --points tools/foxy-perf/points.json --output "$output/timed"
normal_status=$?
python3 tools/foxy-perf/send_race.py --points tools/foxy-perf/points.json --output "$output/send-race"
send_status=$?
(( normal_status == 0 && send_status == 0 ))
