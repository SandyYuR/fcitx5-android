#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

# A new Docker volume can be root-owned. Reused writable caches need no chown.
GRADLE_USER_HOME="${GRADLE_USER_HOME:-${HOME}/.gradle}"
export GRADLE_USER_HOME
if [ -d "${GRADLE_USER_HOME}" ] && [ ! -w "${GRADLE_USER_HOME}" ]; then
  sudo chown -R "$(id -u):$(id -g)" "${GRADLE_USER_HOME}"
fi
mkdir -p "${GRADLE_USER_HOME}"

# Mark repository safe for git operations inside the container
if ! git config --global --get-all safe.directory | grep -Fxq "${REPO_ROOT}"; then
  git config --global --add safe.directory "${REPO_ROOT}"
fi

python3 -B .devcontainer/toolchain-check.py

# Write local.properties if missing so Gradle/AGP resolves the SDK immediately
if [ ! -f "${REPO_ROOT}/local.properties" ]; then
  printf 'sdk.dir=%s\n' "${ANDROID_HOME}" > "${REPO_ROOT}/local.properties"
fi

# The repository entry point initializes only native/rime-prelude and verifies
# the SDK selected by native/rime-sdk.lock.json. Preparation failures propagate.
bash "${REPO_ROOT}/prepare_personal_build.sh"

echo "=== 中州鹅 Codespace ready ==="
echo ""
echo "ARM64 Debug build and native audit: bash .devcontainer/verify-build.sh"
echo "JVM tests and JUnit validation:    bash .devcontainer/test.sh"
echo "These commands are also available under Terminal > Run Task."
echo "APK output: app/build/outputs/apk/fx/debug/"
