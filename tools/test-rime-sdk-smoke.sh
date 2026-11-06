#!/usr/bin/env bash
# Run on a disposable emulator and retain native errors even when instrumentation fails.
set -uo pipefail

adb get-state || exit 1
./gradlew -PdirectRimePrototype=true -PbuildABI=x86_64 :direct-rime:connectedDebugAndroidTest
test_result=$?

if [ "$test_result" -eq 0 ]; then
  python3 tools/check-junit-results.py direct-rime/build/outputs/androidTest-results/connected || test_result=$?
fi

report_dir=direct-rime/build/reports/androidTests/sdk-diagnostics
mkdir -p "$report_dir"
adb logcat -d -v threadtime > "$report_dir/logcat.txt" 2>&1
exit "$test_result"
