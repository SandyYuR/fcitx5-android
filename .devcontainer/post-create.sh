#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(pwd)"

# Ensure the mounted Gradle cache volume is writable by vscode
if [ -d "/home/vscode/.gradle" ]; then
  sudo chown -R "$(id -u):$(id -g)" /home/vscode/.gradle
fi

# Mark repository safe for git operations inside the container
git config --global --add safe.directory "${REPO_ROOT}"

# Initialize git submodules (native/rime-prelude)
git submodule update --init --recursive

# Ensure wrapper and preparation scripts are executable
chmod +x "${REPO_ROOT}/gradlew" 2>/dev/null || true
chmod +x "${REPO_ROOT}/prepare_personal_build.sh" 2>/dev/null || true

# Write local.properties if missing so Gradle/AGP resolves the SDK immediately
if [ ! -f "${REPO_ROOT}/local.properties" ] && [ -n "${ANDROID_HOME:-}" ]; then
  printf 'sdk.dir=%s\n' "${ANDROID_HOME}" > "${REPO_ROOT}/local.properties"
fi

# Run repository personal build preparation script if present
if [ -f "${REPO_ROOT}/prepare_personal_build.sh" ]; then
  bash "${REPO_ROOT}/prepare_personal_build.sh"
fi

echo "=== Codespace Toolchain Ready ==="
java -version
echo "ANDROID_HOME=${ANDROID_HOME:-unset}"
echo "ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-unset}"
cmake --version | head -n 1
python3 --version
echo ""
echo "Quick build command (arm64-v8a Debug):"
echo "  ./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug"
echo "Quick unit test command:"
echo "  ./gradlew :app:testFxDebugUnitTest"
