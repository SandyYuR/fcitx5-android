#!/usr/bin/env bash
# Product sources stay pinned and untouched; only the same androidTest file is overlaid.
set -euo pipefail
repo_dir=$(pwd)
output_dir=${1:?output directory}
mkdir -p "$output_dir"
sudo apt-get update -qq
sudo apt-get install -y -qq extra-cmake-modules gettext
git submodule update --init --depth 1 native/rime-prelude
for label in fcitx before after; do
    if [ "$label" = fcitx ]; then revision=cecea4940bb5a5db67f91d6b20e00ee5c97877ea;
    elif [ "$label" = before ]; then revision=e6d317182e25d225a6128420542ea47d161c03b9;
    else revision=$(git rev-parse HEAD); fi
    # This legacy driver deliberately rebuilds every product. Its former path
    # whitelist omitted codegen and root build inputs, so a cache hit could bind
    # an old APK to the requested comparison. Source SHA alone also omits the
    # overlaid probe and toolchain; do not restore reuse without full provenance.
    checkout_dir="$RUNNER_TEMP/candidate-$label"
    git clone --shared --no-checkout "$repo_dir" "$checkout_dir"
    git -C "$checkout_dir" checkout --detach "$revision"
    if [ "$label" = fcitx ]; then
        git -C "$checkout_dir" submodule update --init --depth 1 lib/fcitx5/src/main/cpp/fcitx5 plugin/rime/src/main/cpp/fcitx5-rime
    fi
    bash "$repo_dir/tools/prepare-native-inputs.sh" "$checkout_dir"
    target="$checkout_dir/app/src/androidTest/java/org/fcitx/fcitx5/android/core"
    mkdir -p "$target"
    cp tools/candidate-perf/CandidateTapTest.kt "$target/"
    (
        cd "$checkout_dir"
        if [ "$label" = fcitx ]; then ./prepare_personal_build.sh; fi
        git diff --exit-code -- app/src/main app/build.gradle.kts settings.gradle.kts build-logic
        ./gradlew -PbuildABI=x86_64 -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 :app:assembleFxDebug :app:assembleFxDebugAndroidTest
        cp app/build/outputs/apk/fx/debug/*.apk "$output_dir/$label.apk"
        cp app/build/outputs/apk/androidTest/fx/debug/*.apk "$output_dir/$label-tests.apk"
        ./gradlew --stop
    )
    echo "$revision" > "$output_dir/$label-commit.txt"
done
test_path=app/src/androidTest/java/org/fcitx/fcitx5/android/core/CandidateTapTest.kt
if [ -d "$RUNNER_TEMP/candidate-fcitx" ]; then
    cmp "$RUNNER_TEMP/candidate-fcitx/$test_path" "$RUNNER_TEMP/candidate-after/$test_path"
    cmp "$RUNNER_TEMP/candidate-before/$test_path" "$RUNNER_TEMP/candidate-after/$test_path"
fi
sha256sum tools/candidate-perf/CandidateTapTest.kt | awk '{print $1}' > "$output_dir/harness-sha256.txt"
sha256sum "$output_dir"/*.apk > "$output_dir/apk-sha256.txt"
