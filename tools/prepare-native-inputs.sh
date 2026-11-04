#!/usr/bin/env bash
# Historical comparisons initialize each revision's own recorded inputs.
# Current builds use a versioned SDK; historical builds retain their gitlinks.
set -euo pipefail
checkout_dir=${1:?checkout directory}
git -C "$checkout_dir" submodule update --init --depth 1
if [ -f "$checkout_dir/native/rime-sdk.lock.json" ]; then
    (cd "$checkout_dir" && ./gradlew prepareRimeSdk)
fi
