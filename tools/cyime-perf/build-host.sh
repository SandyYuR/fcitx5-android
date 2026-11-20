#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
output=${1:?output directory}
staged="$output/source"
mkdir -p "$staged"
cp "$here/../foxy-perf/host/"{BenchHost,Replay}.java "$staged/"
cp "$here/host/"* "$staged/"
bash "$here/../screen-perf/build-host.sh" "$output" "$staged"
