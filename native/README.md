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

For local engine development, librime's `downstream/android/build.py --development` snapshots local edits without changing the real Git index or HEAD. Its packaging tool exports a development directory with an explicit marker, source tree, ABIs and file hashes. Windows can build it using the pinned NDK and MSVC host tools; the workspace `build-engine-local.ps1 -BuildApp` runs the complete SDK-to-Debug flow.

Pass `-PlocalRimeSdkDir=/absolute/path/to/development-sdk` with the requested `-PbuildABI` to use that verified SDK in Debug. Missing ABIs, invalid provenance and changed files fail validation. Release tasks reject this option. Omitting it restores the SDK selected by `rime-sdk.lock.json`; the release lock and verified download cache remain separate from local builds.

APK packaging keeps `jniLibs.useLegacyPackaging = true`: `.so` entries are ZIP-compressed and Android extracts them when installing the APK. This reduces the APK download size while keeping extracted libraries on disk; it does not reduce their loaded memory size. `verify-direct-rime-apk.py` checks compression together with the native library whitelist and ELF load alignment. It also rejects unused Commons Codec phonetic rule resources and retired frontend assets/classes. Compare APK sizes only after a clean build with the same ABI and Release shrinking settings.

`librime.a` is linked directly into `libdirect-rime.so`. The linker writes `direct-rime.linkmap`; the audit checks the actual packaged ELF build ID, map/library hashes and static inputs: librime, glog, LevelDB, Lua, Marisa, OpenCC and yaml-cpp plus NDK runtime archives. Unexpected archives or Fcitx C++ symbols fail the check. Direct Rime is the only backend; Fcitx comparisons use separate historical checkouts.

This lock and static link describe the always-present built-in engine. User-authorized runtime variants are complete self-contained Android DSOs loaded through the same Rime C API after ELF validation and an isolated-UID probe; they neither replace `rime-sdk.lock.json` nor restore another backend. Provider APK payloads are copied to hash-addressed read-only snapshots before the main process loads them.
