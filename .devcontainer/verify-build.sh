#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

python3 -B .devcontainer/toolchain-check.py
# BUILD_ABI takes precedence over -PbuildABI; this entry point always verifies ARM64.
export BUILD_ABI=arm64-v8a
./gradlew --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process :app:assembleFxDebug

shopt -s nullglob
apks=("${REPO_ROOT}"/app/build/outputs/apk/fx/debug/*.apk)
if [ "${#apks[@]}" -eq 0 ]; then
  echo "No Debug APK was produced." >&2
  exit 1
fi

python3 -B tools/verify-direct-rime-apk.py "${apks[@]}"
python3 -B tools/audit-direct-rime-link.py "${apks[@]}" \
  --build-root "${REPO_ROOT}/app/.cxx" --prebuilt "${REPO_ROOT}/native/rime-sdk"
sha256sum "${apks[@]}"
