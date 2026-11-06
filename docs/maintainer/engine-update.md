# 引擎、插件与依赖更新

本页说明中州鹅当前的构建与更新流程。这里的**引擎 SDK** 是提供给输入法链接的 librime 静态库开发包，包含四 ABI 的库、头文件、OpenCC 数据和来源清单；Android SDK、NDK、CMake 则是构建工具。

## 两条链路

构建与发布：

```text
rime/librime 官方源码
  → SandyYuR/librime 的 zhongzhou：官方基线与独有补丁
  → Android SDK CI：编译固定插件、依赖、引擎并生成 OpenCC 数据
  → android-sdk-* 标签对应的 SDK Release
  → 输入法 native/rime-sdk.lock.json
  → Gradle prepareRimeSdk → JNI 链接 → APK → 用户安装
```

运行时：

```text
Android 输入法前端 → DirectRimeBackend → rime-main 线程
  → JNI → C++ Engine → librime
  → 上屏、合成串和候选快照 → InputConnection 与键盘界面
```

JNI 是 Kotlin/Java 调用 C++ 的本地接口，发生在同一进程；JNI 调用本身不要求切换线程或跨进程通信。`rime-main` 是应用安排的引擎线程。当前一次普通送键调用汇总返回结果；诊断中的 `native_us` 包含引擎计算、结果读取及 JNI 封装，不能全部视为 JNI 开销。

## 仓库、分支与文件的职责

| 位置 | 管理内容 |
| --- | --- |
| `SandyYuR/librime` 的 `master` | 跟踪官方源码的基线分支 |
| 同仓库 `zhongzhou` | 产品使用的定制源码、Android 构建配方和依赖锁 |
| `downstream/manifest.json` | 源码仓库、维护分支、官方基线及定制补丁来源 |
| `downstream/android/sources.lock.json` | 各组件的仓库、版本或提交、下载 URL、源码包与补丁摘要 |
| `downstream/android/patches/` | Android 构建所需的固定依赖补丁 |
| `.github/workflows/android-sdk.yml` | 四 ABI 编译、主机回归、打包及标签发布 |
| 输入法 `native/rime-sdk.lock.json` | 消费哪个已发布 SDK，固定仓库、标签、包摘要和来源提交 |
| 输入法 `native/rime-prelude` | Rime 共享预设子模块，独立于引擎 SDK |

Android 构建的依赖版本以 `sources.lock.json` 为准，不能只改 librime 的 `deps/*` 子模块。源码从下载缓存解压到 `build/android/sources/<组件>`；缓存位于 `~/.cache/rime-android-sources/<源码包摘要>`。核心源码由当前已提交的 `HEAD` 导出，插件在隔离的副本内应用补丁。

旧 `prebuilder`、`prebuilt`、`fcitx5-rime` 仓库已归档，当前构建不再读取它们。历史提交仍可能需要旧链路重建，因此归档内容与历史标签保留。

本地可走[纯 Windows 源码开发链路](local-engine-development.md)：用临时索引快照构建当前修改，生成带来源和摘要的开发 SDK，再显式接入 Debug。该入口不修改正式版本锁，也不能用于 Release；工具统一从 D 盘读取。

## 哪些更新互相独立

| 要更新的内容 | 需要操作 |
| --- | --- |
| librime 核心、上游 PR、定制补丁 | 更新 `zhongzhou` 源码与官方基线，重新构建 SDK |
| Lua、librime-lua、octagram、predict、LevelDB 等 | 更新 Android 依赖锁及兼容补丁，重新构建 SDK |
| 万象等方案的 YAML、词典、Lua 脚本、模型 | 按方案说明更新用户文件并部署 |
| 用户手机上的内置引擎 | 安装引用新 SDK 的输入法 APK |

重新运行 CI、启动输入法、检查应用更新，都不会自动把固定的组件前移到上游最新提交。仅更新依赖时可以保留当前官方核心基线，不必同时变基核心。方案里的 `.lua` 文件、Lua 运行时和 librime-lua 插件也应分别记录版本。

## 组件源码在哪里

以下为 2026-11-05 这一轮更新后的版本；完整 SHA 和下载摘要以[依赖锁](https://github.com/SandyYuR/librime/blob/zhongzhou/downstream/android/sources.lock.json)为准。

| 组件 | 源码 | 本轮版本 |
| --- | --- | --- |
| Lua 运行时 | [lua.org](https://www.lua.org/ftp/) | 官方 5.4.9，保持 5.4 系列 |
| librime-lua | [hchunhui/librime-lua](https://github.com/hchunhui/librime-lua) | `6da5d6ce` |
| octagram | [lotem/librime-octagram](https://github.com/lotem/librime-octagram) | `57d18b9f` |
| predict | [fxliang/librime-predict-leveldb](https://github.com/fxliang/librime-predict-leveldb) | `0c30981a`，无新提交 |
| LevelDB | [google/leveldb](https://github.com/google/leveldb) | `bb74ef73` |
| OpenCC | [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC) | 正式版 1.4.2 |
| Marisa | [s-yata/marisa-trie](https://github.com/s-yata/marisa-trie) | 正式版 0.3.1 |
| glog | [google/glog](https://github.com/google/glog) | 正式版 0.7.1 |
| yaml-cpp | [jbeder/yaml-cpp](https://github.com/jbeder/yaml-cpp) | 正式版 0.9.0 |
| Boost | [boostorg/boost](https://github.com/boostorg/boost) | 正式版 1.92.0 |

Marisa、glog、yaml-cpp 已是本轮检查时各自的最新正式版，保留现有版本。predict 使用的是表中明确的 LevelDB 分支，切换到其他 predict 仓库需要另查接口、配置与数据兼容性。

## 一、准备源码更新分支

以下命令在 librime 仓库的 Bash 中执行，示例分支后缀请按本次更新命名：

```sh
git status --short --branch
git fetch origin zhongzhou
rime_update_branch=build/android-sdk-20261105-update
git switch -c "$rime_update_branch" origin/zhongzhou
```

`build/android-sdk-*` 前缀会自动触发 Android SDK CI；任意临时分支名不一定触发它。普通分支构建只上传 Actions 产物，不创建 SDK Release。

若本次同步官方核心，先配置好 `upstream` 指向 `https://github.com/rime/librime.git`，再执行：

```sh
git fetch upstream master
rime_old_upstream=$(python3 -c 'import json; print(json.load(open("downstream/manifest.json"))["upstream_commit"])')
git rebase --onto upstream/master "$rime_old_upstream"
```

逐项核对被重放的独有补丁。官方已包含或代码等效的改动不再重复合入；同一补丁仍有独有部分时只保留那部分。处理冲突后更新 `downstream/manifest.json` 的官方基线与现行补丁说明；原迁移记录中的历史 SHA 不应假装成新提交。变基需要完整祖先历史，浅克隆须先补齐。

## 二、升级选定的依赖或插件

1. 对比当前固定提交、上游变更和正式版本。稳定库优先考虑正式版；沿提交维护的插件仍锁到明确 SHA。检查构建要求、API、Lua 主版本及用户数据格式。
2. 修改 `sources.lock.json` 对应条目：版本或提交、同一份源码的 `url`、下载包 `sha256`；换仓库时同时修改 `repository`。**只改 `commit` 不会改变实际下载的源码。**
3. 下载新源码，检查并重放本地补丁。上游已包含的补丁移除引用；修改补丁内容后重新计算 `patches[].sha256`。
4. 组件的目录、目标名、安装位置、库名或构建选项改变时，同步调整 `build.py`、补丁与打包逻辑。Lua 的最小 CMake 安装配方在 `lua.patch`，升级版本时也要检查其中的版本和头文件清单。
5. 提交依赖锁、配方及补丁之后构建。构建要求工作树的已跟踪文件与 `HEAD` 一致，导出的引擎源码也来自 `HEAD`。

三个摘要分别对应不同对象：

| 摘要 | 校验对象 |
| --- | --- |
| `sources.lock.json` 中组件的 `sha256` | 下载的源码压缩包 |
| `patches[].sha256` | 本地 `.patch` 文件 |
| 输入法 `rime-sdk.lock.json` 的 `sha256` | 最终 Release 中的 `rime-android-sdk.zip` |

可以用 `sha256sum 文件名` 计算摘要。Actions 下载得到的外层产物 ZIP 与其中的 SDK ZIP 也不是同一文件，不能混用摘要。

构建配方会按源码摘要刷新 Boost 头文件缓存，避免复用工作目录时沿用旧头文件；配置、编译与安装均隔离父目录 Git 信息。OpenCC 的 Android 库和主机数据生成工具使用同一源码，并严格校验版本，避免把 librime 标签误当成 OpenCC 版本。

## 三、构建并核对验证范围

```sh
python3 -m json.tool downstream/android/sources.lock.json >/dev/null
python3 -m unittest discover -s downstream/android -p 'test_*.py' -v
git push -u origin "$rime_update_branch"
```

以下为 Linux 命令（SDK CI 使用 Python 3.12）。[Windows 本地开发](local-engine-development.md)提供 MSVC 宿主工具、NDK 交叉编译和工作树快照入口。配方需要 Python 3.12 或以上、Git、Ninja、C++ 编译器及锁定工具链：

```sh
export ANDROID_NDK_ROOT=/path/to/android-sdk/ndk/28.0.13004108
export CMAKE_BIN=/path/to/android-sdk/cmake/3.31.6/bin/cmake
export PATH="$(dirname "$CMAKE_BIN"):$PATH"
python3 downstream/android/build.py --abi arm64-v8a --work build/android
```

四个 ABI 为 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。

| 检查 | 证明的范围 |
| --- | --- |
| Commit CI | 当前源码的格式与 Linux、macOS、Windows 等平台构建、回归 |
| Android SDK 的主机回归 | 原生引擎回归；该任务使用 Ubuntu 系统依赖 |
| 四 ABI 源码构建与最终链接 | 实际锁定的插件、依赖和引擎能够在对应 Android ABI 编译、完整链接 |
| SDK 打包门禁 | 归档对象 ELF 架构、必需 API、源码/工具链/依赖元数据、OpenCC 数据及文件摘要 |
| 输入法 JNI 链接与 APK 审计 | SDK 能被当前输入法桥接使用，原生库及静态贡献符合应用要求 |
| Android 运行回归 | 在实际构建的设备包中检查输入、上屏、Lua、词库和转换行为 |

主机回归不能替代新版 Android 组件的运行验证。Lua 应检查方案脚本、回调与关闭重启；octagram 应检查模型加载和整句候选；predict 应检查接口、配置及学习数据；LevelDB 应检查已有词库、学习撤销、持久化、同步与恢复；OpenCC 应检查数据生成与实际转换。

通用 Commit CI 的 macOS、Windows 任务沿用各平台的插件安装脚本，未按 Android 的 `sources.lock.json` 固定插件提交；它们还使用 `rime/librime-predict`，与 Android 产品锁定的 `fxliang/librime-predict-leveldb` 不同。这些任务通过说明相应平台的源码兼容性，不能据此认定 Android 插件已升级；Android 产物仍只采用锁内的版本。

源码 Commit CI 与 Android SDK CI 是独立工作流。SDK 的发布任务只等待本流水线的构建、主机回归与打包，不会自动等待另一个源码工作流；跨仓库更新、设备任务及最终推进由维护者核对和安排。

确认相关检查通过后，将源码提交更新到 `zhongzhou`。变基改写了已推送历史时先核对远端旧 SHA，并使用精确的 `--force-with-lease`；普通新增提交正常快进推送。已验证的核心输入完全相同而只改构建配方时，记录输入等效证据，继续运行受影响的构建与运行检查。

## 四、发布新的 SDK 版本

在已验证的源码提交上创建一个**新**标签，例如：

```sh
git tag -a android-sdk-20261105-2 -m 'Android SDK：更新固定插件与依赖' HEAD
git push origin refs/tags/android-sdk-20261105-2
```

示例标签已用于本轮发布，后续必须使用新的日期或序号。标签触发的 SDK CI 会重新编译、测试和打包，再上传 `rime-android-sdk.zip`、`SHA256SUMS` 和 `sdk-manifest.json`；当前实现不会直接把工作分支的产物提升为 Release。

核对标签实际提交、最终发布包的 SHA-256、四 ABI 和来源清单。Release 的 `target_commitish` 是发布元数据，不能替代标签与 SDK 清单的源码校验。SDK 有七个静态库：librime、glog、LevelDB、Lua、Marisa、OpenCC、yaml-cpp；三个引擎插件合并在 librime 中。

已发布标签与包不覆盖。即使只升级依赖、核心代码未变，也要发布新的 SDK 版本。

## 五、更新输入法并发布 APK

1. 修改输入法 `native/rime-sdk.lock.json` 的 SDK 标签、最终发布包摘要、`librime_commit` 和适用的 `upstream_commit`；换 SDK 来源时一并改仓库。保留实际支持的 ABI 清单。
   Gradle 自动校验并提取到生成目录 `native/rime-sdk/`，缓存位于 `GRADLE_USER_HOME/caches/rime-sdk/<sha256>.zip`。首次准备成功后可离线使用缓存；升级以版本锁和发布包为准，生成目录由任务重新生成。
2. 执行准备、构建和回归：

```sh
./gradlew prepareRimeSdk
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug :app:testFxDebugUnitTest
./gradlew --no-daemon -p build-logic :convention:test --tests RimeSdkArchiveTest
```

3. 检查 APK 原生白名单、链接贡献和所用 SDK 来源；按变更运行设备回归。`PluginSdkSmokeTest` 联合检查 Lua 翻译器、Memory 用户词库、OpenCC 和关闭重启后的持久化。
4. Linux 上可运行 `python3 tools/input-perf/test-leveldb-open.py build/leveldb-check`，它读取已准备 SDK 的来源清单，测试同版本 LevelDB 的日志复用、持久化、显式同步和恢复。若以后新增 LevelDB 补丁，需同步让探针应用并验证补丁。
5. 输入法的依赖升级单独提交，正常推送默认分支 `fx-rime-only`。默认 Commit CI 构建签名 ARM64 Release、运行单元测试和 SDK 下载器测试；Nightly 等待构建与测试成功后发布。

引擎 SDK 支持四 ABI，不等于主应用默认 CI 打包四 ABI。`Direct Rime CI` 的 `engine-smoke` 只运行引擎设备检查，其余三个诊断任务会跳过；全量前端触摸、真机锁屏、语音服务等需要分别选择相应验证。

用户安装新 APK 后才使用新的内置引擎。用户的方案、模型和学习库不会因 SDK 发布而自动替换。

## 回退与更换源码仓库

回退时新增提交恢复上一版 `rime-sdk.lock.json`，重建并验证 APK；不重写已发布 SDK 标签，也不删除用户词库。方案版本、模型和用户数据备份分别管理，回退 APK 不会自动回退这些文件。

如果换到另一个 librime fork：已有兼容 SDK 时可以更新消费端版本锁；只有源码时，需要带上等效的 Android 构建、打包、来源元数据和必需 API。当前配方编译所在仓库的 `HEAD`，尚未实现传入任意核心仓库地址自动构建。移植不要求使用完全相同的提交，但需对应调整 workflow 分支、`downstream/manifest.json` 及输入法依赖的扩展接口，同时核对最低 API 23、ABI、NDK 与 C++ 运行库。完全不同的引擎还需要适配后端和前端调用契约。

相关页面：[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md) · [用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md) · [本轮源码与文档核对](changes/2026-11-05.md)。
