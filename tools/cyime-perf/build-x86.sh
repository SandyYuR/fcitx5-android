#!/usr/bin/env bash
set -euo pipefail
source_dir=${1:?CyIME checkout}
output_apk=${2:?destination APK}
evidence=${3:?evidence directory}
cd "$source_dir"
test "$(git rev-parse HEAD)" = 3341da598dfbf99d624139d792624093a41a9851
test -z "$(git status --porcelain)"
git rev-parse HEAD > "$evidence/cyime-source.txt"
git submodule status --recursive > "$evidence/cyime-submodules.txt"
python3 - <<'PY'
from pathlib import Path

gradle = Path("app/build.gradle.kts")
text = gradle.read_text()
for original, updated in (
    ('abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")', 'abiFilters += listOf("x86_64")'),
    ('include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")', 'include("x86_64")'),
    ("isUniversalApk = true", "isUniversalApk = false"),
):
    if text.count(original) != 1:
        raise SystemExit(f"Unexpected CyIME ABI configuration: {original}")
    text = text.replace(original, updated)
gradle.write_text(text)
PY
git diff -- app/build.gradle.kts > "$evidence/cyime-abi.patch"
keystore="$RUNNER_TEMP/cyime-test.keystore"
keytool -genkeypair -keystore "$keystore" -storepass android -keypass android -alias cyime-test \
  -dname CN=LocalBenchmark -keyalg RSA -validity 30 >/dev/null 2>&1
cat > app/keystore.properties <<EOF
storeFile=$keystore
storePassword=android
keyAlias=cyime-test
keyPassword=android
EOF
./gradlew --no-daemon --max-workers=3 -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleRelease 2>&1 | tee "$evidence/cyime-build.log"
cp app/build/outputs/apk/release/CyIME-1.15.101-x86_64.apk "$output_apk"
python3 - "$output_apk" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    abis = {name.split('/')[1] for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')}
    if abis != {'x86_64'}:
        raise SystemExit(f"Unexpected native libraries: {abis}")
PY
sha256sum "$output_apk" > "$evidence/cyime-x86-sha256.txt"
