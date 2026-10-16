#!/usr/bin/env bash
# Run on a disposable emulator; preserve diagnostics before the runner shuts it down.
set -uo pipefail

adb shell setprop debug.hwui.drawing_enabled true
./gradlew -PdirectRimeFrontend=true -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.DirectRimeFrontendTest \
  :app:connectedFxDebugAndroidTest
test_result=$?

report_dir=app/build/reports/androidTests/frontend-diagnostics
mkdir -p "$report_dir"
adb logcat -d -v threadtime > "$report_dir/logcat.txt" 2>&1
for name in settings rime-settings themes keyboard-settings layout-editor keyboard commit failure; do
  adb pull "/data/local/tmp/direct-frontend-$name.png" "$report_dir/$name.png" 2>/dev/null || true
done
exit "$test_result"
