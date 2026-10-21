#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(pwd)
output_dir=${1:?output directory}
mkdir -p "$output_dir"
sudo apt-get update -qq
sudo apt-get install -y -qq extra-cmake-modules gettext ffmpeg
git submodule update --init --depth 1 native/prebuilt native/rime-prelude
for backend in fcitx direct; do
    checkout_dir="$RUNNER_TEMP/screen-$backend"
    if [ "$backend" = fcitx ]; then revision=cecea4940bb5a5db67f91d6b20e00ee5c97877ea; else revision=cfd0ddc4d92dbcfcee815089ca34b97bdfa95780; fi
    git clone --shared --no-checkout "$repo_dir" "$checkout_dir"
    git -C "$checkout_dir" checkout --detach "$revision"
    if [ "$backend" = fcitx ]; then
        git -C "$checkout_dir" submodule update --init --depth 1 lib/fcitx5/src/main/cpp/fcitx5 plugin/rime/src/main/cpp/fcitx5-rime
    fi
    for item in prebuilt rime-prelude; do
        if [ "$backend" = direct ]; then destination="$checkout_dir/native/$item";
        elif [ "$item" = prebuilt ]; then destination="$checkout_dir/lib/fcitx5/src/main/cpp/prebuilt";
        else destination="$checkout_dir/plugin/rime/src/main/cpp/rime-prelude"; fi
        path=${destination#"$checkout_dir/"}
        git -C "$checkout_dir" submodule init "$path"
        if [ -d "$destination" ]; then rmdir "$destination"; fi
        git clone --shared --no-checkout "$repo_dir/native/$item" "$destination"
        expected=$(git -C "$checkout_dir" rev-parse "HEAD:$path")
        git -C "$destination" checkout --detach "$expected"
        test "$expected" = "$(git -C "$repo_dir/native/$item" rev-parse HEAD)"
        git -C "$checkout_dir" submodule absorbgitdirs "$path"
    done
    target="$checkout_dir/app/src/androidTest/java/org/fcitx/fcitx5/android/core"
    mkdir -p "$target"
    cp "$repo_dir/tools/screen-perf/ScreenFixtureTest.kt" "$target/"
    (
        cd "$checkout_dir"
        if [ "$backend" = fcitx ]; then ./prepare_personal_build.sh; fi
        git diff --exit-code -- app/src/main app/build.gradle.kts settings.gradle.kts build-logic
        ./gradlew -PbuildABI=x86_64 -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 :app:assembleFxDebug :app:assembleFxDebugAndroidTest
        cp app/build/outputs/apk/fx/debug/*.apk "$output_dir/$backend.apk"
        cp app/build/outputs/apk/androidTest/fx/debug/*.apk "$output_dir/$backend-tests.apk"
        ./gradlew --stop
    )
done
python3 tools/verify-direct-rime-apk.py "$output_dir/direct.apk"
sha256sum "$output_dir"/*.apk > "$output_dir/apk-sha256.txt"
sha256sum tools/screen-perf/ScreenFixtureTest.kt | awk '{print $1}' > "$output_dir/harness-sha256.txt"
compiler_pids=$(jps -l | awk '$2 == "org.jetbrains.kotlin.daemon.KotlinCompileDaemon" {print $1}')
if [ -n "$compiler_pids" ]; then kill $compiler_pids; fi
