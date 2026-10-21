#!/usr/bin/env bash
set -euo pipefail
# Start this, then type/replay the same workload on each IME during the capture.
destination=${1:-rime-input.perfetto-trace}
duration_ms=${2:-30000}
[[ "$duration_ms" =~ ^[0-9]+$ ]] || { echo 'duration must be milliseconds' >&2; exit 2; }
remote=/data/misc/perfetto-traces/rime-input-$(date +%s)-$$.perfetto-trace
capture_pid=$(adb shell perfetto --background-wait --txt -c - -o "$remote" <<CONFIG
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
duration_ms: $duration_ms
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      atrace_categories: "input"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "dalvik"
      atrace_apps: "org.fcitx.fcitx5.android.fx.rime.debug"
      atrace_apps: "io.github.sandyyur.rime.direct"
      atrace_apps: "org.fcitx.fcitx5.android.fx.rime"
    }
  }
}
data_sources { config { name: "linux.process_stats" process_stats_config { proc_stats_poll_ms: 1000 } } }
CONFIG
)
capture_pid=${capture_pid//$'\r'/}
[[ "$capture_pid" =~ ^[0-9]+$ ]] || { echo 'Perfetto did not return a capture PID' >&2; exit 1; }
trap 'adb shell kill -TERM "$capture_pid" >/dev/null 2>&1 || true' EXIT
echo 'Tracing is ready; perform the input workload now.'
while adb shell kill -0 "$capture_pid" >/dev/null 2>&1; do sleep 1; done
adb pull "$remote" "$destination"
