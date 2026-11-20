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
    ("splits {\n        abi {\n            isEnable = true", "splits {\n        abi {\n            isEnable = false"),
):
    if text.count(original) != 1:
        raise SystemExit(f"Unexpected CyIME ABI configuration: {original}")
    text = text.replace(original, updated)
gradle.write_text(text)

# Both published translation binaries are unavailable. The upstream script
# supports an empty map, and this experiment does not open the translation UI.
translation = Path("app/build-logic/tasks-offline-translate.gradle.kts")
text = translation.read_text()
start = text.index("val offlineTranslateLibraryHashes = mapOf<String, String>(")
end = text.index("\nval offlineTranslateLibraryRoot", start)
translation.write_text(text[:start] + "val offlineTranslateLibraryHashes = emptyMap<String, String>()" + text[end:])

# Every ONNX header succeeded from the official source during the first build.
# Keep the upstream mirrors as fallback and try the official source first.
native = Path("app/build-logic/tasks-native.gradle.kts")
text = native.read_text()
start = text.index("fun githubUrls(url: String): List<String> = buildList {")
end = text.index("\n}", start) + len("\n}")
native.write_text(text[:start] + '''fun githubUrls(url: String): List<String> = buildList {
    add(url)
    ghMirrors.forEach { add("${it.trimEnd('/')}/$url") }
}''' + text[end:])
PY
git diff -- app/build.gradle.kts app/build-logic/tasks-offline-translate.gradle.kts app/build-logic/tasks-native.gradle.kts > "$evidence/cyime-build.patch"
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
cp app/build/outputs/apk/release/CyIME-1.15.101-universal.apk "$output_apk"
python3 - "$output_apk" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    abis = {name.split('/')[1] for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')}
    if abis != {'x86_64'}:
        raise SystemExit(f"Unexpected native libraries: {abis}")
PY
sha256sum "$output_apk" > "$evidence/cyime-x86-sha256.txt"
