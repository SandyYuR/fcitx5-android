#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# The only remaining submodule supplies Rime presets. Engine artifacts are
# downloaded from the pinned librime SDK release with checksum verification.
git submodule update --init --depth 1 native/rime-prelude
./gradlew prepareRimeSdk
