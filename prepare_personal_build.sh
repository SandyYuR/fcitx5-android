#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# Use the checked-in Rime gitlinks. Never fetch a moving branch or overwrite
# local changes with --force; the old Fcitx Core/addon patches are no longer used.
git submodule update --init --depth 1 native/prebuilt native/rime-prelude
