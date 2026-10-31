#!/usr/bin/env bash
# Animation and predictive-back acceptance on a disposable emulator.
set -euo pipefail
report=app/build/reports/androidTests/settings-navigation
mkdir -p "$report"
collect_diagnostics() {
  result=$?
  adb logcat -d -v threadtime > "$report/logcat.txt" 2>&1 || true
  for remote in $(adb shell 'ls /data/local/tmp/settings-*.png' 2>/dev/null | tr -d '\r'); do
    adb pull "$remote" "$report/" >/dev/null 2>&1 || true
  done
  exit "$result"
}
trap collect_diagnostics EXIT

[[ "$(adb get-state)" == device ]]
if [[ -z "$(adb shell pm path com.android.systemui | tr -d '\r')" ]]; then
  echo 'Real edge gestures require a full system image with SystemUI; ATD is not suitable.' >&2
  exit 1
fi
adb shell pidof com.android.systemui
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell setprop debug.hwui.drawing_enabled true
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1
adb shell settings put global enable_back_animation 1
adb shell cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.gestural
navigation_mode=''
for attempt in {1..40}; do
  navigation_mode=$(adb shell settings get secure navigation_mode | tr -d '\r')
  [[ "$navigation_mode" == 2 ]] && break
  sleep 0.25
done
if [[ "$navigation_mode" != 2 ]]; then
  echo "Gestural navigation was not enabled (navigation_mode=$navigation_mode)." >&2
  exit 1
fi
adb shell input keyevent KEYCODE_HOME
launcher_focus=''
launcher_ready() {
  for attempt in {1..80}; do
    launcher_focus=$(adb shell dumpsys window | tr -d '\r' | grep 'mCurrentFocus=' || true)
    if [[ "$launcher_focus" == *nexuslauncher/* || "$launcher_focus" == *launcher3/* ]]; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}
if ! launcher_ready; then
  adb shell screencap -p /data/local/tmp/settings-launcher-startup-failure.png
  launcher_component=$(adb shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)
  launcher_package=${launcher_component%%/*}
  case "$launcher_package" in
    com.google.android.apps.nexuslauncher|com.android.launcher3) ;;
    *) echo "Unexpected launcher: $launcher_component" >&2; exit 1 ;;
  esac
  echo "Restarting the emulator launcher once before instrumentation: $launcher_component"
  adb shell am force-stop "$launcher_package"
  adb shell am start -W -n "$launcher_component"
  if ! launcher_ready; then
    echo "Launcher did not become ready before testing: $launcher_focus" >&2
    exit 1
  fi
fi
adb shell screencap -p /data/local/tmp/settings-startup.png
{
  adb shell getprop ro.build.version.sdk
  adb shell settings get secure navigation_mode
  adb shell settings get global animator_duration_scale
  adb shell settings get global transition_animation_scale
  adb shell settings get global window_animation_scale
  echo "$launcher_focus"
} > "$report/environment.txt"
./gradlew -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.ui.main.SettingsNavigationGesturesTest \
  :app:connectedFxDebugAndroidTest
python3 tools/check-junit-results.py app/build/outputs/androidTest-results/connected
