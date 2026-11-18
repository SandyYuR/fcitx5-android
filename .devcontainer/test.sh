#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

python3 -B .devcontainer/toolchain-check.py
export BUILD_ABI=arm64-v8a
./gradlew --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process :app:testFxDebugUnitTest
python3 -B tools/check-junit-results.py "${REPO_ROOT}/app/build/test-results/testFxDebugUnitTest"
