#!/usr/bin/env bash
set -euo pipefail
output=app/build/reports/androidTests/cold-probe
mkdir -p "$output"
./gradlew -PbuildABI=x86_64 :app:assembleFxDebug :app:assembleFxDebugAndroidTest
adb install -r app/build/outputs/apk/fx/debug/*.apk
adb push "$RUNNER_TEMP/cold-fixture.zip" /data/local/tmp/direct-cold-fixture.zip
adb shell run-as io.github.sandyyur.rime.direct mkdir -p files
adb shell run-as io.github.sandyyur.rime.direct cp /data/local/tmp/direct-cold-fixture.zip files/cold-fixture.zip
adb logcat -c
set +e
./gradlew -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.DirectRimeFrontendTest\#diagnoseColdCandidateCore \
  -Pandroid.testInstrumentationRunnerArguments.fixtureArchive=cold-fixture.zip \
  -Pandroid.testInstrumentationRunnerArguments.probeCold=true \
  -Pandroid.testInstrumentationRunnerArguments.waitMs=180000 \
  :app:connectedFxDebugAndroidTest
result=$?
set -e
adb logcat -d -v threadtime > "$output/logcat.txt"
adb shell run-as io.github.sandyyur.rime.direct cat files/direct-rime-cold-probe.json > "$output/result.json" || true
cp "$RUNNER_TEMP/cold-fixture.manifest.json" "$output/fixture.manifest.json"
exit "$result"
