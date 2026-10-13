#!/usr/bin/bash
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

# Use the parent repository's gitlinks, not a previous local submodule checkout.
# A missing pin is an error; falling back to master can silently remove features.
prepare_pinned_source() {
  local path="$1" remote="$2" pinned
  pinned=$(git rev-parse "HEAD:$path")
  pushd "$path"
  if [ -n "$(git status --porcelain)" ]; then
    echo "Refusing to overwrite local changes in $path" >&2
    exit 1
  fi
  git remote add gh "$remote" 2>/dev/null || git remote set-url gh "$remote"
  git fetch -v gh "$pinned"
  git checkout --detach "$pinned"
  echo "$path: using gitlink-pinned $pinned"
  popd
}

prepare_pinned_source plugin/rime/src/main/cpp/fcitx5-rime https://github.com/SandyYuR/fcitx5-rime.git

# Preserve local core edits and fail when the required patch cannot be applied.
PATCH="$ROOT/plugin/rime/src/main/cpp/fcitx5-rime/fcitx5-alt-trigger-v4point1.patch"
pushd lib/fcitx5/src/main/cpp/fcitx5
if git apply --check "$PATCH"; then
  git apply "$PATCH"
elif git apply --reverse --check "$PATCH"; then
  echo "fcitx5 patch already applied"
else
  echo "Required fcitx5 patch does not match this checkout" >&2
  exit 1
fi
popd

prepare_pinned_source lib/fcitx5/src/main/cpp/prebuilt https://github.com/SandyYuR/prebuilt.git
