#!/usr/bin/env bash
# Run on a disposable emulator and retain native errors even when instrumentation fails.
set -uo pipefail

adb get-state || exit 1

report_dir=direct-rime/build/reports/androidTests/sdk-diagnostics
results_dir=direct-rime/build/outputs/androidTest-results/connected
input_connection_test=org.fcitx.directrime.InputConnectionSmokeTest
mkdir -p "$report_dir" || exit 1

run_suite() {
  local suite=$1 selector=$2 result=0 suite_report
  ./gradlew -PdirectRimePrototype=true -PbuildABI=x86_64 \
    "-Pandroid.testInstrumentationRunnerArguments.$selector=$input_connection_test" \
    :direct-rime:connectedDebugAndroidTest || result=$?
  if [ "$result" -eq 0 ]; then
    python3 tools/check-junit-results.py "$results_dir" || result=$?
  fi
  if [ -d "$results_dir" ]; then
    suite_report=$(mktemp -d "$report_dir/$suite.XXXXXX") || return 1
    cp -a "$results_dir/." "$suite_report/" || return 1
  fi
  return "$result"
}

# The input-connection test activates the real IME, whose asynchronous teardown
# can invalidate a direct engine fixture in the same instrumentation process.
run_suite engine notClass
engine_result=$?
run_suite input-connection class
input_result=$?
adb logcat -d -v threadtime > "$report_dir/logcat.txt" 2>&1
if [ "$engine_result" -ne 0 ]; then
  exit "$engine_result"
fi
exit "$input_result"
