#!/usr/bin/env bash
set -euo pipefail
output=app/build/reports/androidTests/cold-probe
mkdir -p "$output"
./gradlew -PbuildABI=x86_64 :app:assembleFxDebug :app:assembleFxDebugAndroidTest
adb install -r app/build/outputs/apk/fx/debug/*.apk
adb push "$RUNNER_TEMP/cold-fixture.zip" /data/local/tmp/direct-cold-fixture.zip
adb shell run-as org.fcitx.fcitx5.android.fx.rime mkdir -p files
adb shell run-as org.fcitx.fcitx5.android.fx.rime cp /data/local/tmp/direct-cold-fixture.zip files/cold-fixture.zip
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
adb shell run-as org.fcitx.fcitx5.android.fx.rime cat files/direct-rime-cold-probe.json > "$output/result.json" || true
cp "$RUNNER_TEMP/cold-fixture.manifest.json" "$output/fixture.manifest.json"
python3 - "$output" <<'PY'
import json, re, sys
from pathlib import Path
output = Path(sys.argv[1])
text = (output / 'logcat.txt').read_text()
records = re.findall(r'DirectRimeColdProbe: (\{[^\n]+\})', text)
assert records, 'Missing cold operation report before AGP teardown'
report = json.loads(records[-1])
assert len(report['records']) == 6
(output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
assert 'DirectRimeProbe initialized=1' in text, 'Lua DB diagnostic was not active'
assert 'DirectRimeProbe db=context_reorder method=open' in text, 'Context DB open was not observed'
assert 'DirectRimeProbe storage_records_checked=400' in text, 'Pinned Android DB persistence probe did not pass'
PY
exit "$result"
