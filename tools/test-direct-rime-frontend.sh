#!/usr/bin/env bash
# Run on a disposable emulator; preserve diagnostics before the runner shuts it down.
set -uo pipefail

adb shell setprop debug.hwui.drawing_enabled true
./gradlew -PdirectRimeFrontend=true -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.DirectRimeFrontendTest \
  :app:connectedFxDebugAndroidTest
test_result=$?

if [ "$test_result" -eq 0 ]; then
  for apk in app/build/outputs/apk/fx/debug/*.apk; do
    python3 tools/verify-direct-rime-apk.py "$apk" || test_result=$?
  done
fi

report_dir=app/build/reports/androidTests/frontend-diagnostics
mkdir -p "$report_dir"
adb logcat -d -v threadtime > "$report_dir/logcat.txt" 2>&1
adb shell run-as io.github.sandyyur.rime.direct cat files/direct-rime-replay.json > "$report_dir/replay.json" 2>/dev/null || true
for name in settings rime-settings themes keyboard-settings layout-editor keyboard commit failure; do
  adb pull "/data/local/tmp/direct-frontend-$name.png" "$report_dir/$name.png" 2>/dev/null || true
done
exit "$test_result"
