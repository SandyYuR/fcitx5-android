#!/usr/bin/env bash
# Run only on a disposable emulator. Preserve UI diagnostics even on failure.
set -uo pipefail

adb shell setprop debug.hwui.drawing_enabled true
./gradlew -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.ui.main.MaterialSettingsPagesTest \
  :app:connectedFxDebugAndroidTest
test_result=$?

report_dir=app/build/reports/androidTests/material-settings-diagnostics
mkdir -p "$report_dir"
adb logcat -d -v threadtime > "$report_dir/logcat.txt" 2>&1
for page in UpdateCheckActivity UpdateHostsSettingsActivity UpdateMirrorEditActivity UpdateMirrorListActivity LogActivity CropImageActivity ClipboardSyncSettingsActivity ClipboardEditActivity TextKeyboardLayoutProfilePickerActivity UpdateMirrorListActivity-controls; do
  for mode in light dark; do
    adb pull "/data/local/tmp/md3-settings-$page-$mode.png" "$report_dir/$page-$mode.png" 2>/dev/null || true
  done
done
exit "$test_result"
