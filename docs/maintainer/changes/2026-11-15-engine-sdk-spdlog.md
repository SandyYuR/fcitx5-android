# 2026-11-15 引擎 SDK 换版与原生日志开关

本记录对应代码 [PR #41](https://github.com/SandyYuR/fcitx5-android/pull/41)（`feat/rime-engine-log-switch` → `fx-rime-only`）和本页所在的文档同步分支。结果限定于下列来源与本次实际构建的产物，不继承此前提交的设备验收结论。

## 来源与固定版本

| 项目 | 固定来源 |
| --- | --- |
| SDK 标签 | `android-sdk-20261115-2` |
| librime 定制源码 | `da8d1e7c41c8abddea4f5a55b2983f91866b84a3` |
| 上游基线 | `bb56876890ba60f446fa2640c244b1794b965eda` |
| SDK 压缩包 SHA-256 | `88d2c43f9455f9be47bd8eb4f38a589fad7584dc989e8ff367b6d57961301792` |
| 上一版 SDK | `android-sdk-20261110-1`，librime `b5561910d9eba97a273e09e7996535679b730032`，SHA-256 `d22bf604adc844ac568c375b57b2ff575f3fa9c05e46f33ead394bd31bf815e9` |
| spdlog | 正式版 1.17.0，`79524ddd08a4ec981b7fea76afd08ee05f83755d`，源码包 SHA-256 `c6dee844e35a54cc80e3ab76aae2310763e0b49c7397549dfa4d4657d9737138` |

## 引擎侧变化

两版之间共 20 个提交，其中影响应用侧的有：

| 提交 | 内容 |
| --- | --- |
| `e548b08f` | 上游新增 `deps/spdlog` 子模块 |
| `6e23ed28` | 日志门面由 glog 换成 spdlog |
| `023f7872` | 移除 glog、gflags 依赖 |
| `614124ee` | Android 侧安装 spdlog logcat sink |
| `c95c0d23` | 官方 #1272：词典编译紧凑条目与并行分组 |
| `bb568768` | 官方 #1260：用户库打开失败时隔离损坏库并恢复 |
| `e352de16` | #1274：紧凑词条与两段式反向词典构建（尚未上游） |
| `da8d1e7c` | 下游文档与来源清单 |

- `src/rime_api.h` 只有注释改动，没有 ABI 变化；应用侧不需要改调用约定。
- `downstream/android/sources.lock.json`：移除 glog（`7b134a5c82c0c0b5698bb6bf7a835b230c5638e4`、0.7.1、带 `glog.patch`），新增 spdlog（`79524ddd`、1.17.0、无补丁）。
- 新增 `downstream/android/package_engine.py`、`engine-exports.map` 与 `.github/workflows/android-engine.yml`，用于运行时引擎变体打包。应用侧 `RimeEngineStore`／`DirectRimeEngineOperations` 消费的是导入的 `.so` 或提供 APK，协议未变，本次没有配套改动。
- 归档结构随依赖替换变化：`spdlog/<abi>/lib/libspdlog.a`，不再有 `glog/<abi>/lib/libglog.a`；静态库数量仍是七个。

## 应用侧改动

下表路径相对 `fx-rime-only/`。

下表保留当时改动；性能研究摘要项的后续更正见本页“2026-11-16 审阅后更正”一节。

| 位置 | 改动 |
| --- | --- |
| `native/rime-sdk.lock.json` | 标签、SHA-256、`librime_commit`、`upstream_commit` 换成新版本 |
| `build-logic/convention/src/main/kotlin/RimeSdkPlugin.kt:68` | 预编译库清单 `glog` → `spdlog`（`libglog.a` → `libspdlog.a`） |
| `build-logic/convention/src/test/kotlin/RimeSdkArchiveTest.kt:37` | 归档夹具同步为 spdlog |
| `direct-rime/src/main/cpp/CMakeLists.txt:33` | 链接依赖 `glog` → `spdlog` |
| `tools/audit-direct-rime-link.py:14` | 链接审计清单 `glog` → `spdlog` |
| `native/README.md:26` | 静态输入说明 |
| `tools/input-perf/build-study-products.sh` | 候选延迟研究固定的 SDK 锁摘要同步为新锁的 `63e8acc8…`；历史 baseline／regressed 引用仍带上一版锁，重跑研究前要重建基线 |
| `app/licenses/libraries/glog.json` → `spdlog.json` | 许可条目换成 MIT／1.17.0，AboutLibraries 生成物随构建刷新 |
| `direct-rime/src/main/cpp/bridge.cpp:16`、`engine.cpp:514` | 注释里的 glog 改为 logging |

### 原生日志开关

旧 SDK 用 glog，日志写入进程 stderr；应用进程的 stderr 指向 `/dev/null`，实时日志页用 `logcat --pid=<pid>` 抓取，因此连 ERROR 都收不到。新 SDK 用 `android_sink_mt` 写 logcat（tag 为 `traits.app_name`，即 `rime.direct-android`），但默认 `min_log_level=2` 只放行 ERROR／FATAL。本次把级别做成显式开关：

| 位置 | 改动 |
| --- | --- |
| `direct-rime/src/main/cpp/engine.h`、`engine.cpp` | `start(..., int minLogLevel = 2)`；`std::clamp(minLogLevel, 0, 3)` 后写入 `traits.min_log_level` |
| `direct-rime/src/main/cpp/bridge.cpp:182` | JNI `start` 增加 `jint minLogLevel` |
| `direct-rime/src/engine/java/org/fcitx/directrime/Snapshot.kt:107` | `external fun start(..., minLogLevel: Int = 2)` |
| `app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeEngineLog.kt` | `INFO = 0`、`ERROR = 2`，读 `AppPrefs.internal.rimeEngineLog` |
| `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:44` | `val rimeEngineLog = bool("rime_engine_log", false)` |
| `DirectRimeBackend.kt:214`、`DirectRimeEngineOperations.kt:224`／`:272` | 三处 `Native.start` 传入 `minLogLevel` |
| `app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:92` | 开发者页新增开关，标题与摘要用 `R.string.rime_engine_log` |
| `res/values*/strings.xml` | 中英文及 de、es、ja、ko、ru、zh-rTW 翻译 |
| `SettingsSearchIndex.kt:246` | 设置搜索索引登记 |

- 生效时机：librime 的 `InitializeLogging` 每进程只执行一次（`setup()` 只调一次），所以开关**重启应用**才生效；同进程重建引擎实例不会重新初始化日志。
- 默认关闭，仍只输出 ERROR／FATAL；打开后 INFO／WARNING 进入实时日志页，日志量大，排查完应关闭。

## 验证记录

| 验证 | 结果 |
| --- | --- |
| `prepareRimeSdk -PbuildABI=arm64-v8a` | 生成目录为 spdlog 版；`sdk-manifest.json` 的 `librime_commit` 为 `da8d1e7c`、`upstream_commit` 为 `bb568768` |
| 符号核查（`llvm-nm`） | `librime.a` 未定义 spdlog 符号 22 个、glog 符号 0 个；内含 `spdlog::sinks::android_sink` 模板实例 16 个、未定义 `__android_log_print` 1 个；`libspdlog.a` 定义 1514 个符号 |
| ARM64 `:app:assembleFxDebug`（`build-debug.ps1`） | 构建通过；`libdirect-rime.so` 5,177,384 字节（glog 版为 4,893,904），page size 16384 |
| `tools/verify-direct-rime-apk.py` | 通过 |
| `tools/audit-direct-rime-link.py` | 通过；静态输入为 `spdlog: libspdlog.a`（5 objects），无 glog；librime 130 objects |
| `:app:testFxDebugUnitTest` | 2327 tests、0 failures、0 errors、30 skipped（283 份 JUnit 报告） |
| `tools/input-perf` 单测 | 换版后先因冻结摘要不匹配失败；更新摘要后 109 tests 通过 |
| `:build-logic:convention:test` | 23 tests，`RimeSdkArchiveTest` 10 tests 通过；仅 `DataDescriptorPathTest` 的 2 个用例失败，见下节 |
| 设备验证（模拟器 `rime`，API 35 x86_64） | 安装 x86_64 Debug APK；开关关闭时启动后 `rime.direct-android` 0 条，打开开关并重启后 87 条 INFO（`registering core components` 等），实时日志页按 tag 过滤可见 |

| APK | SHA-256 |
| --- | --- |
| ARM64 Debug（提交 `c595263f2`） | `30b7377b185a43b0e4d52a9827d4192c5782ee20351ab0a87572ea164a74769d` |
| x86_64 Debug（提交 `c595263f2`，设备验证用） | `b4ed1b4d2d1fc63605dac77f140a9c50ac43674c07ee5c6cc395cfb0acaaa3a9` |

两个 APK 都由提交 `c595263f2` 构建，版本后缀 `-4-gc595263f2`。变基前 `c0c8db6b6` 的 ARM64 产物为 `811fa85654183271bccd32ef3470d3841026a92bb8dd8695bf1ebc17c9ac7aa5`，提交前用未提交工作区构建的产物为 `e4b1e6d3f131248149af107a34832310e8f74cf4aa8dfeca994805995dbe31c0`（后缀 `-1-g05acf9336`），都只对应各自的输入。

代码分支已变基到默认分支最新提交 `2411d9d13`（原 base `3e0a87000`），3 个提交重放无冲突；变基前后 `git diff origin/fx-rime-only...HEAD` 的摘要同为 `51364415…`，补丁内容未变。

## 本机环境限制

- 本会话的 JVM 进程只能写 `D:\GitHub\fx2-rime` 与 `D:\GitHub\tools`，Robolectric 默认要写 `~/.robolectric-download-lock`，会以 `Couldn't create lock file` 让全部 Robolectric 用例失败。用 `--init-script` 把测试 JVM 的 `user.home` 指到工作区内目录（并把 `.m2` 复制过去）后恢复正常；这是本机验证方式，不进入仓库。
- `build-logic` 的 `DataDescriptorPathTest` 在 Windows 上失败：`File("/assets")` 在 Windows 不是绝对路径，`descriptorKey` 因此原样返回 `/assets/usr/share/rime-data`。该用例与本次改动无关，Linux CI 正常；本次没有把它算作通过。

## 2026-11-16 审阅后更正

本次修复以代码 `c595263f2accb4959ee0a6b8228ac08d480e1d85`、文档 `5248ca8a1a000b3f428b90e23f3c724ad2237381` 为修改前来源。上文把性能研究冻结摘要改为 `63e8acc8…` 并要求重建基线的处理引入了回归：固定 baseline／regressed 仍携带旧锁，新构建会在 baseline 校验处失败，原本合法的历史产物也无法通过恢复和校验。上文 109 项单测的结果保留为当时事实，但测试把历史提交模拟成当前锁内容，漏检了这个差异。

旧研究恢复使用 SDK 锁 SHA-256 `6500f364626c863bee98157e51b6539990bb0f4e392c38ec442b6b3e5eeaa92f`。以下三个历史源码引用中的 Git 锁字节均为 418 字节，摘要相同：

| 研究角色 | 固定源码提交 |
| --- | --- |
| baseline | `0380d70f16efb6a9946a164821d5cad7118ce4ff` |
| regressed | `4d77cd79a580a9984130a5653b13f949ec5bc0a9` |
| 历史 current | `adb6a4e33426333677934ac82f0a19997245556a` |

本轮修复保留旧研究身份。使用相同 SDK 的历史 current 仍可构建、恢复和校验；使用本次 spdlog SDK 的 current 会因身份不匹配而拒绝。当前应用的 SDK 锁保持 `63e8acc8…`；若要比较新 SDK，须另建源码引用、SDK 身份与元数据一致的新研究。`products_run` 复用和 `--recover` 只按原研究身份核对。

用户手册同时更正日志默认范围：Rime 引擎默认仅输出 ERROR／FATAL，应用自身日志不受“Rime 引擎日志”开关控制。依据为 `DirectRimeEngineLog.ERROR = 2`、`ConciseTree` 默认仍保留 INFO 及以上日志，以及 `Logcat` 没有默认 ERROR 过滤。

本轮验证针对上述来源上的未提交修复：`python3 -m unittest discover -s tools/input-perf -p 'test_*.py' -v` 实际执行 117 项测试，失败、错误和跳过均为 0；`bash -n tools/input-perf/build-study-products.sh` 与 `git diff --check` 通过。测试使用固定的真实历史锁夹具，以及来自代码 `c595263f2` 的升级锁夹具，覆盖构建预检查、构建元数据、恢复和校验的旧身份成功及异身份拒绝；历史夹具已与上述三个源码提交的 Git 锁字节逐字节核对。

校验单测只执行到方案／模型远端夹具检查之前，没有下载原研究 ZIP。本轮未构建 APK、触发远端 workflow 或执行设备性能研究；此前 APK 和设备结果仍只对应上文原始输入。

以上工具修复和 117 项回归结果属于 11 月 16 日的首个阶段，当时应用锁仍为 `63e8acc8…`。随后源码修复发布了新的正式 SDK `android-sdk-20261116-1`，应用锁和 SDK 交付验证另见[同日音节筛选记录](2026-11-16-syllable-fuzzy-filter-fix.md)；旧研究的 `6500f364…` 身份继续保持。
