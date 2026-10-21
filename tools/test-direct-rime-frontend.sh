#!/usr/bin/env bash
# Run on a disposable emulator; preserve diagnostics before the runner shuts it down.
set -uo pipefail

adb shell setprop debug.hwui.drawing_enabled true
./gradlew -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.DirectRimeFrontendTest,org.fcitx.fcitx5.android.ui.main.log.LogExportTest,org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter.KeyboardLayoutAdapterViewTest,org.fcitx.fcitx5.android.ui.main.MaterialSettingsPagesTest \
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
adb shell run-as org.fcitx.fcitx5.android.fx.rime cat files/direct-rime-replay.json > "$report_dir/replay.json" 2>/dev/null || true
adb shell run-as org.fcitx.fcitx5.android.fx.rime cat files/direct-rime-rebind.json > "$report_dir/rebind.json" 2>/dev/null || true
for name in settings rime-settings themes keyboard-settings layout-editor keyboard commit failure; do
  adb pull "/data/local/tmp/direct-frontend-$name.png" "$report_dir/$name.png" 2>/dev/null || true
done
for page in UpdateCheckActivity UpdateHostsSettingsActivity UpdateMirrorEditActivity UpdateMirrorListActivity LogActivity RimeImportActivity CropImageActivity ClipboardSyncSettingsActivity SettingsActivity ClipboardEditActivity TextKeyboardLayoutProfilePickerActivity UpdateMirrorListActivity-controls; do
  for mode in light dark; do
    adb pull "/data/local/tmp/direct-frontend-md3-$page-$mode.png" "$report_dir/md3-$page-$mode.png" 2>/dev/null || true
  done
done
exit "$test_result"
