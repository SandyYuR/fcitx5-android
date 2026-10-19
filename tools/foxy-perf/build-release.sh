#!/usr/bin/env bash
set -euo pipefail
output_dir=${1:?output directory}
mkdir -p "$output_dir"
git submodule update --init --depth 1 native/prebuilt native/rime-prelude
if [ -z "${SIGN_KEY_BASE64:-}" ] && [ -z "${SIGN_KEY_FILE:-}" ]; then
    if [ "${REQUIRE_RELEASE_SIGNING:-false}" = true ]; then
        printf '%s\n' 'Release signing is required; configure the repository signing key.' >&2
        exit 1
    fi
    export SIGN_KEY_FILE="$RUNNER_TEMP/perf-release.keystore"
    export SIGN_KEY_ALIAS=perf
    export SIGN_KEY_PWD=android
    keytool -genkeypair -keystore "$SIGN_KEY_FILE" -storepass android -keypass android \
        -alias perf -dname CN=PerformanceTest -keyalg RSA -validity 3650 >/dev/null 2>&1
    printf '%s\n' 'CI-generated test key; back up before switching from a differently signed APK.' > "$output_dir/signing.txt"
else
    printf '%s\n' 'Repository-configured release signing key.' > "$output_dir/signing.txt"
fi
./gradlew '-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8 -XX:+UseParallelGC' \
    -PbuildABI=arm64-v8a -PbuildCommitHash="$(git rev-parse HEAD)" \
    -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 :app:assembleFxRelease
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/release/*.apk
python3 tools/foxy-perf/apk_profiles.py app/build/outputs/apk/fx/release/*.apk > "$output_dir/packaged-profile-audit.json"
python3 - "$output_dir/packaged-profile-audit.json" <<'PY'
import json, sys
profile = json.load(open(sys.argv[1]))[0]
methods = [m for dex in profile['dex_entries'] for m in dex['app_methods']]
assert any(m.startswith('Lorg/fcitx/fcitx5/android/core/DirectRimeBackend;->sendKey-') for m in methods), 'Mangled key entry missing from packed ART profile'
assert any('DirectRimeBackend$' in m and m.endswith('->invokeSuspend') for m in methods), 'Backend coroutine entries missing from packed ART profile'
PY
cp app/build/outputs/apk/fx/release/*.apk "$output_dir/direct.apk"
cp app/build/outputs/apk/fx/release/build-metadata.json "$output_dir/"
cp app/build/intermediates/combined_art_profile/fxRelease/compileFxReleaseArtProfile/baseline-prof.txt "$output_dir/resolved-baseline-prof.txt"
git rev-parse HEAD > "$output_dir/source.txt"
sha256sum "$output_dir/direct.apk" > "$output_dir/sha256.txt"
./gradlew --stop
