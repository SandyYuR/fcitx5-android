#!/usr/bin/env bash
# Run only on a disposable emulator. Preserve UI diagnostics even on failure.
set -uo pipefail

adb shell setprop debug.hwui.drawing_enabled true
./gradlew -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.ui.main.MaterialSettingsPagesTest \
  :app:connectedFxDebugAndroidTest
test_result=$?
if [[ "$test_result" -eq 0 ]]; then
  python3 tools/check-junit-results.py app/build/outputs/androidTest-results/connected
  test_result=$?
fi

report_dir=app/build/reports/androidTests/material-settings-diagnostics
mkdir -p "$report_dir"
adb logcat -d -v threadtime > "$report_dir/logcat.txt" 2>&1
for page in UpdateCheckActivity UpdateHostsSettingsActivity UpdateMirrorEditActivity UpdateMirrorListActivity LogActivity RimeImportActivity CropImageActivity ClipboardSyncSettingsActivity ClipboardEditActivity TextKeyboardLayoutProfilePickerActivity UpdateMirrorListActivity-controls; do
  for mode in light dark; do
    adb pull "/data/local/tmp/md3-settings-$page-$mode.png" "$report_dir/$page-$mode.png" 2>/dev/null || \
      adb pull "/data/local/tmp/direct-frontend-md3-$page-$mode.png" "$report_dir/$page-$mode.png" 2>/dev/null || true
  done
done
for mode in light dark; do
  if ! adb pull "/data/local/tmp/direct-frontend-md3-UpdateMirrorListActivity-brand-icons-$mode.png" \
      "$report_dir/brand-icons-$mode.png"; then
    echo "Missing required brand icon rendering evidence ($mode)" >&2
    test_result=1
  fi
  for page in SetupActivity MainActivity; do
    if ! adb pull "/data/local/tmp/direct-frontend-md3-$page-$mode.png" \
        "$report_dir/$page-$mode.png"; then
      echo "Missing required $page brand icon evidence ($mode)" >&2
      test_result=1
    fi
  done
done
exit "$test_result"
