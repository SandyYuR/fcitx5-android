# Android Rime SDK and shared presets

`rime-sdk.lock.json` pins a release of [SandyYuR/librime](https://github.com/SandyYuR/librime/tree/zhongzhou): an exact tag, source commit and SDK SHA-256. The root Gradle task `prepareRimeSdk` downloads, verifies and extracts that version to the ignored `native/rime-sdk/` directory. Native configuration and OpenCC asset preparation depend on this task automatically, including JVM builds that prepare app assets.

The only remaining submodule, `rime-prelude`, supplies punctuation and key-binding presets. `rime-data/default.yaml` supplies shared defaults without bundling a Chinese scheme.

```sh
git submodule update --init --depth 1 native/rime-prelude
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/debug/*.apk
```

On Windows use `gradlew.bat`; downloading the SDK uses Java and does not require Python. The archive is cached under `GRADLE_USER_HOME/caches/rime-sdk/<sha256>.zip`. After one successful online preparation, `--offline` uses this verified cache. A missing cache, checksum mismatch or wrong source version fails the build. Gradle detects changed extracted files and restores them from the verified archive.

The SDK contains four ABIs of librime with Lua, octagram and predict, its six runtime dependency archives, public headers, OpenCC data, licenses and provenance. Boost is used only while building the engine. The [SDK build instructions](https://github.com/SandyYuR/librime/blob/zhongzhou/downstream/android/README.zh-CN.md) describe pinned sources, native regression tests and release creation. No prebuilder/prebuilt checkout is used in the current build. Historical performance comparisons fetch each historical revision's recorded submodules.

To upgrade, first publish and verify a new immutable `android-sdk-*` release in librime, then change this project's lock. Never replace a published archive or follow a moving branch. To roll back, restore the prior lock; user data is unaffected.

`librime.a` is linked directly into `libdirect-rime.so`. The linker writes `direct-rime.linkmap`; the audit checks the actual packaged ELF build ID, map/library hashes and static inputs: librime, glog, LevelDB, Lua, Marisa, OpenCC and yaml-cpp plus NDK runtime archives. Unexpected archives or Fcitx C++ symbols fail the check. Direct Rime is the only backend; Fcitx comparisons use separate historical checkouts.
