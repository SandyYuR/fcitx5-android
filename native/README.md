# Shared Rime dependencies

`prebuilt` is the same pinned engine fork previously stored under `lib/fcitx5`;
moving it does not upgrade librime or remove the custom input-tab ABI.
`rime-prelude` supplies the common punctuation and key-binding presets.
`rime-data/default.yaml` supplies shared defaults without bundling a Chinese scheme.

The full direct frontend needs only these two submodules:

```sh
git submodule update --init --depth 1 native/prebuilt native/rime-prelude
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

It does not configure the Fcitx library project, use Fcitx headers, or require
gettext/ECM. Direct Rime is the default and only backend in this branch.
`-PdirectRimeFrontend=true` remains accepted for existing scripts; explicitly
passing `false` is rejected. Fcitx regression comparisons use a separate checkout
of the pinned historical baseline `a714feff`, not a backend switch in this tree.
