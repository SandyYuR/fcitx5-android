# Shared Rime dependencies

`prebuilt` is the same pinned engine fork previously stored under `lib/fcitx5`;
moving it does not upgrade librime or remove the custom input-tab ABI.
`rime-prelude` supplies the common punctuation and key-binding presets.
`rime-data/default.yaml` supplies shared defaults without bundling a Chinese scheme.

The full direct frontend needs only these two submodules:

```sh
git submodule update --init --depth 1 native/prebuilt native/rime-prelude
./gradlew -PdirectRimeFrontend=true -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

It does not configure the Fcitx library project, use Fcitx headers, or require
gettext/ECM. The old backend remains available without `directRimeFrontend` for
regression comparisons; its build still uses `prepare_personal_build.sh`.
