#!/usr/bin/env bash
set -euo pipefail
source_dir=$(cd "$(dirname "$0")/host" && pwd)
output_dir=${1:?output directory}
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?SDK required}}
build_tools=$(find "$sdk_dir/build-tools" -name aapt2 | sort -V | tail -1 | xargs dirname)
android_jar=$(find "$sdk_dir/platforms" -name android.jar | sort -V | tail -1)
mkdir -p "$output_dir/classes" "$output_dir/dex"
javac -source 8 -target 8 -classpath "$android_jar" -d "$output_dir/classes" "$source_dir"/*.java
"$build_tools/d8" --lib "$android_jar" --output "$output_dir/dex" "$output_dir"/classes/io/github/sandyyur/inputbench/*.class
"$build_tools/aapt2" link -I "$android_jar" --manifest "$source_dir/AndroidManifest.xml" -o "$output_dir/host-unsigned.apk"
(cd "$output_dir/dex" && zip -q -u "$output_dir/host-unsigned.apk" classes.dex)
keytool -genkeypair -keystore "$output_dir/host.keystore" -storepass android -keypass android -alias androiddebugkey -dname CN=Benchmark -keyalg RSA -validity 3650 >/dev/null 2>&1
"$build_tools/apksigner" sign --ks "$output_dir/host.keystore" --ks-pass pass:android --out "$output_dir/host.apk" "$output_dir/host-unsigned.apk"
