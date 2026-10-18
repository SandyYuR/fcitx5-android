#!/usr/bin/env bash
# Compare this change with the last direct product, without changing product sources.
set -euo pipefail
repo_dir=$(pwd)
output_dir=${1:?output directory}
mkdir -p "$output_dir"
git submodule update --init --depth 1 native/prebuilt native/rime-prelude
for label in before after; do
    checkout_dir="$RUNNER_TEMP/rebind-$label"
    if [ "$label" = before ]; then revision=2f6a0ea08527831d3f07df12499f5b72d5ffc0bd; else revision=$(git rev-parse HEAD); fi
    git clone --shared --no-checkout "$repo_dir" "$checkout_dir"
    git -C "$checkout_dir" checkout --detach "$revision"
    for item in prebuilt rime-prelude; do
        path="native/$item"
        destination="$checkout_dir/$path"
        git -C "$checkout_dir" submodule init "$path"
        rmdir "$destination"
        git clone --shared --no-checkout "$repo_dir/$path" "$destination"
        expected=$(git -C "$checkout_dir" rev-parse "HEAD:$path")
        test "$expected" = "$(git -C "$repo_dir/$path" rev-parse HEAD)"
        git -C "$destination" checkout --detach "$expected"
        git -C "$checkout_dir" submodule absorbgitdirs "$path"
    done
    # Exactly the same UI harness on both APKs; omit only the new gate-specific
    # contract test, whose APIs intentionally do not exist in the old product.
    python3 - "$repo_dir" "$checkout_dir" <<'PY'
from pathlib import Path
import sys
path = 'app/src/androidTest/java/org/fcitx/fcitx5/android/core/DirectRimeFrontendTest.kt'
text = (Path(sys.argv[1]) / path).read_text()
start = text.index('    @Test fun silentNonEditorCanBePromotedWithoutDroppingItsFirstKey()')
end = text.index('    @Test fun modeAndSchemaHintsExpireWithoutReplayingCommits()', start)
(Path(sys.argv[2]) / path).write_text(text[:start] + text[end:])
PY
    (
        cd "$checkout_dir"
        git diff --exit-code -- app/src/main app/build.gradle.kts direct-rime/src/engine direct-rime/src/main settings.gradle.kts build-logic
        ./gradlew -PbuildABI=x86_64 -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 :app:assembleFxDebug :app:assembleFxDebugAndroidTest
        cp app/build/outputs/apk/fx/debug/*.apk "$output_dir/$label.apk"
        cp app/build/outputs/apk/androidTest/fx/debug/*.apk "$output_dir/$label-tests.apk"
        ./gradlew --stop
    )
    git -C "$checkout_dir" rev-parse HEAD > "$output_dir/$label-commit.txt"
done
python3 tools/verify-direct-rime-apk.py "$output_dir/after.apk"
sha256sum "$output_dir"/*.apk > "$output_dir/apk-sha256.txt"
