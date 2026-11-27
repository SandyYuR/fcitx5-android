# 引擎、插件与依赖更新

本页说明中州鹅当前的构建与更新流程。这里的**引擎 SDK** 是提供给输入法链接的 librime 静态库开发包，包含四 ABI 的库、头文件、OpenCC 数据和来源清单；Android SDK、NDK、CMake 则是构建工具。

## 两条链路

现行固定版本为 2026-11-25 的 [`android-sdk-20261125-4`](https://github.com/SandyYuR/librime/releases/tag/android-sdk-20261125-4)：官方基线 `1c0a468e7fff2b2763400abe1e892ed66d08b00e`，定制源码 `83fbd0810e8c24db87f3b9dc9acce193401c7c4f`，正式 SDK ZIP SHA-256 `ed02b4f090a28899389e476082be46312a31e20cb91b3d2782c387ab294d2bfa`，由输入法提交 `1b8cbf2c0` 写入版本锁（默认分支头 `f48ab5742`）。该源码保留“音节栏仅对九键数字输入显示”的前置限制，并修正九键音节栏：音节标签按占用按键数降序、同长度按标签字母序排列；选中音节后，方案模糊规则能从该标签派生的音节仍参与候选（万象九键选 `shen` 后仍能选到 `sheng` 音节的“生”）。历次版本见下表。

历次 SDK 交付的源码、发布核验、默认分支消费与设备回归按日期记录，现行页只保留下表索引：

| SDK 标签 | 定制源码 | 主要变化 | 交付与验证记录 |
| --- | --- | --- | --- |
| `android-sdk-20261125-4`（现行） | `83fbd081…` | 同步上游 1.18.0（基线 `1c0a468e`）；音节栏跨度降序、同跨度字母序；选中音节后保留方案模糊派生的音节 | 输入法提交 `1b8cbf2c0`（默认分支头 `f48ab5742`）；[2026-11-25 九键音节栏记录](changes/2026-11-25-t9-tab-fuzzy-order.md) |
| `android-sdk-20261125-3` | `8c92891f…` | 音节栏跨度降序、同跨度字母序；选中音节后保留方案模糊派生的音节 | 输入法提交 `bcfe31fe3`（已被 `-4` 改写替换）；同上记录 |
| `android-sdk-20261120-1` | `7c61add0…` | 音节栏只对九键数字输入显示 | 输入法提交 `858e4851`；本页历史版本 |
| `android-sdk-20261116-2` | `9a317501…` | 九键字面音节标签、声母韵母组合模糊候选保护、无模糊规则方案兼容；输入法默认分支重写为 `f340e09a` 后消费 | [新版 SDK 消费与默认分支重写](changes/2026-11-16-syllable-fuzzy-filter-fix.md#新版-sdk-消费与默认分支重写) · [双模糊与字面标签最终修复](changes/2026-11-16-syllable-fuzzy-filter-fix.md#双模糊与字面标签最终修复) |
| `android-sdk-20261116-1` | `8bbdf773…` | 有序模糊候选筛选 | [正式 SDK 发布与核验](changes/2026-11-16-syllable-fuzzy-filter-fix.md#正式-sdk-发布与核验) |

SDK 继续使用 spdlog 1.17.0、Android `libspdlog.a` 与 logcat sink，保留官方 #1272 紧凑词条编译、#1260 用户库恢复，以及尚未上游的 #1274 紧凑词条与两段式反向词典构建和下游映射保护。临时库回收、清理守卫、失败传播和同次上屏的用户词学习事务继续保留；插件与依赖按 Android 来源锁固定。此前 glog → spdlog 换版和原生日志开关的固定产物事实见[2026-11-15 引擎与日志记录](changes/2026-11-15-engine-sdk-spdlog.md)。
上一版 `.temp` 枚举修复的交付证据见[设备反馈修复](changes/2026-11-06-device-fixes.md)，更早的官方源码同步见[2026-11-06 引擎记录](changes/2026-11-06-engine-input-fixes.md)。历史研究的源码引用、SDK 和产物元数据保持原身份，不因现行 SDK 换版而修改。

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
| 同仓库 `zhongzhou`（默认分支） | 产品使用的定制源码、Android 构建配方和依赖锁 |
| `downstream/manifest.json` | 源码仓库、维护分支、官方基线及定制补丁来源 |
| `downstream/android/sources.lock.json` | 各组件的仓库、版本或提交、下载 URL、源码包与补丁摘要 |
| `downstream/android/patches/` | Android 构建所需的固定依赖补丁 |
| `.github/workflows/android-sdk.yml` | 四 ABI 编译、主机回归、打包及标签发布 |
| 输入法 `native/rime-sdk.lock.json` | 消费哪个已发布 SDK，固定仓库、标签、包摘要和来源提交 |
| 输入法 `native/rime-prelude` | Rime 共享预设子模块，独立于引擎 SDK |

Android 构建的依赖版本以 `sources.lock.json` 为准，不能只改 librime 的 `deps/*` 子模块。源码从下载缓存解压到 `build/android/sources/<组件>`；缓存位于 `~/.cache/rime-android-sources/<源码包摘要>`。核心源码由当前已提交的 `HEAD` 导出，插件在隔离的副本内应用补丁。

旧 `prebuilder`、`prebuilt`、`fcitx5-rime` 仓库已归档，当前构建不再读取它们。历史提交仍可能需要旧链路重建，因此归档内容与历史标签保留。

本地可走[纯 Windows 源码开发链路](local-engine-development.md)：用临时索引快照构建当前修改，生成带来源和摘要的开发 SDK，再显式接入 Debug。该入口不修改正式版本锁，也不能用于 Release；工具统一从 D 盘读取。

输入法将本次实际参与链接的 `sdk-manifest.json` 原样打入 APK，内置引擎来源页显示 `librime_commit`，开发 SDK 另显示 `source_tree`。开发产物的 commit 只是基线，不能代替工作树摘要；清单缺字段时明确显示未知，不根据当前运行的外部引擎猜测。清单结构由 SDK 打包器与 `RimeSdkManifest` 共同约束，新增字段需同时更新读取方，旧清单不保证被更严格的读取器接受。

系统单汉字字符集准入属于新版外置 EVO 引擎的能力，使用 EVO 自己的 rewrite 资源编译接口。内置 SDK 和其他非 EVO / 不支持该接口的引擎不实现此功能；应用不提供替代过滤。适配与配置范围见[EVO 字符集准入](../features/rime-enhancements.md#evo-字符集准入)。

## 哪些更新互相独立

| 要更新的内容 | 需要操作 |
| --- | --- |
| librime 核心、上游 PR、定制补丁 | 更新 `zhongzhou` 源码与官方基线，重新构建 SDK |
| Lua、librime-lua、octagram、predict、LevelDB 等 | 更新 Android 依赖锁及兼容补丁，重新构建 SDK |
| 万象等方案的 YAML、词典、Lua 脚本、模型 | 按方案说明更新用户文件并部署 |
| 用户手机上的内置引擎 | 安装引用新 SDK 的输入法 APK |

重新运行 CI、启动输入法、检查应用更新，都不会自动把固定的组件前移到上游最新提交。仅更新依赖时可以保留当前官方核心基线，不必同时变基核心。方案里的 `.lua` 文件、Lua 运行时和 librime-lua 插件也应分别记录版本。

## 运行时引擎变体与提供 APK

主应用仍以 `native/rime-sdk.lock.json` 固定的静态 SDK 构建内置引擎。运行时变体是另一条消费链：把完整引擎及其依赖最终链接成一个可加载的 Android DSO，再由用户直接导入，或放进提供 APK 的 `lib/<abi>/`。`SandyYuR/librime` 的 `android-sdk` 发布物包含静态归档、头文件和数据，不能把其中的 `librime.a` 政名为 `.so` 后导入；必须完成最终共享库链接、隐藏非入口导出并验证动态依赖。

可导入 DSO 的最低契约为：当前进程 ABI 的 `ET_DYN`、16 KiB LOAD 对齐、无 W+X、无可执行栈、无 TEXTREL/RPATH/RUNPATH、只依赖允许的 Android 系统库，并导出 `rime_get_api` 或兼容入口。静态检查不能证明函数表兼容，因此主应用在用户首次确认后还会在一次性 Android `isolatedProcess` 中加载 DSO，核对 `RimeApi.data_size`、必需函数和版本。该进程使用独立 isolated UID，不继承应用已授予权限，应用的常规偏好、恢复和数据初始化也不在其中运行。由于 isolated UID 不能执行主应用代码缓存的 inode，探测桥接把传入的只读 FD 有界复制到隔离进程创建并加写入／增长／缩小封印的 memfd，再从同字节 memfd 加载；不能以 SELinux 限制为由退回同 UID 探测或未封印 ashmem。设备内核缺少 `memfd_create`、seccomp/LSM 拒绝或 seal 失败时，探测明确报不支持并 fail closed；这意味着最低 API 23 不保证所有旧内核可使用运行时变体。调用方串行化探测，取消、超时或收到结果后解除唯一绑定，服务销毁时结束隔离进程。探测只证明这一字节载荷能暴露当前桥接所需的 C API，不调用目标 profile 的部署流程，也不能证明具体方案、词典、Lua 或模型可用。正式启用后 DSO 仍在输入法主进程运行，因此最终授权文案必须继续说明完整信任边界。

提供 APK 通过可查询 Activity 声明 `org.rime.plugin.MANIFEST` intent，并在 application metadata 提供：

| 字段 | 当前值或含义 |
| --- | --- |
| `org.rime.plugin.API_VERSION` | 协议版本 `1` |
| `org.rime.plugin.RIME_API_ABI` | Rime API 契约版本 `1` |
| `org.rime.plugin.PLUGIN_ID` | 提供者稳定 ID |
| `org.rime.plugin.SONAME` | 安装后 `nativeLibraryDir` 中的 DSO 文件名 |
| `org.rime.plugin.LIBRIME_VERSION` | 供列表显示的声明版本，不能替代运行时探测 |
| `org.rime.plugin.CHANNEL` | 例如 `release` 或 `test` |

这是中州鹅兼容的社区协议，不是 Rime 上游标准。主应用把授权绑定到包名、签名证书 SHA-256、DSO SHA-256 和 SONAME；更新或换签后要求重新授权。实际启动不直接 `dlopen` 提供 APK 的 `nativeLibraryDir` 路径：先把载荷复制、重新静态检查并原子发布为应用管理的只读 `plugin-<SHA-256>.so` 执行快照，再重新查询包证书、provider ID、协议/API 版本、SONAME、载荷摘要和入口。复制期间发生安装更新或元数据变化时本次启动失败并走回退，不把旧授权套到新包。提供 APK 应设置 `extractNativeLibs=true`，确保宿主能从 `nativeLibraryDir` 取得可供复制的真实文件。稳定分发必须复用同一签名；临时 CI 签名只用于测试。

提供 APK 的证书读取遵循系统版本：API 33 及以上使用 `PackageInfoFlags` 重载，API 28–32 使用整数 flags 的 `GET_SIGNING_CERTIFICATES`，两者都取 `SigningInfo.apkContentsSigners` 当前证书；API 27 及以下使用 `GET_SIGNATURES`。不能把历史签名证书误当作当前授权身份，也不能在 API 28–32 调用 API 33 的新重载。`RimeEnginePluginsTest` 在 API 27、28、32、33 的 Robolectric 运行时中调用真实发现、ELF 校验和再次身份核对，设备加载能力仍由单独回归确认。

每次进程准备使用外部引擎，而不只是首次切换，都会在载荷发布和加载前原子写入启动守卫。显式切换或修改活动引擎绑定时还设置完整部署标志；普通稳定启动不因此无条件重编方案。只有 native 初始化、所需部署和方案刷新成功才清除守卫，未完成的下一次启动按保存的前一引擎回退。外部引擎活动期间，兼容保留的 `profile-manager` 不允许直接切换目录，`import-profile` 也不允许导入并切换；应在「中州韵引擎变体」页修改绑定。任何导入引擎、已授权 provider 或内置回退状态仍引用的 profile 均不得重命名或删除。

`SandyYuR/librime` 的外置引擎工作流在推送 `zhongzhou` 或 `build/android-engine-*`，且改动命中其 `paths` 列表时自动触发；分支与路径条件须同时满足。也可在 GitHub 网页手动构建：

1. 打开仓库 **Actions**，选择 [Android ARM64 engine](https://github.com/SandyYuR/librime/actions/workflows/android-engine.yml)。
2. 点击 **Run workflow**，在 **Branch** 中选择包含此工作流和下游构建脚本的分支，例如默认分支 `zhongzhou`。
3. 点击面板中的 **Run workflow** 发起该分支构建。

仓库默认分支现为 `zhongzhou`，其中的 `android-engine.yml` 已声明 `workflow_dispatch`，满足网页手动入口的要求。手动事件不受该文件 `push` 的分支和路径过滤限制。`master` 保留纯官方基线，缺少此工作流和下游构建配方，不能直接选择它构建该外置引擎。

`SandyYuR/librime-evo` 的 [android-engine.yml](https://github.com/SandyYuR/librime-evo/actions/workflows/android-engine.yml) 是另一份完整示例。默认工作分支 `build/android-engine-variants` 以 amzxyz 的 `librime-evo` main 为源码基线，叠加本仓库的 Android 工作流、`downstream/` 配方及独立配置编译失败传播修复；fork 的 `main` 保持上游源码与桌面流程。Lua 插件使用 amzxyz 的 `librime-evo-lua` 并固定 LuaJIT，模型插件使用 amzxyz 的 `librime-evo-octagram`（Evo 整句／前文评分接口与 `.dlm` 格式）。模型属于用户方案，构建只提供引擎能力，不下载或覆盖方案目录中的模型。

手动构建上游 main 时，**Run workflow** 的工作流分支仍选 `build/android-engine-variants`，在 `source_ref` 填 `main`（也可填标签或完整 SHA）。工作流与引擎源码分别检出，`build.py --source-root` 从指定源码 checkout 导出核心并校验模块固定提交；依赖锁和补丁来自配方 checkout。只把工作流执行分支选成 `main` 并不可行，因为该分支没有这套 Android 配方。

自动构建由源码仓库 `main` 的 push 触发：`Commit CI` 成功后，build 分支的 `workflow_run` 构建对应 `head_sha`。工作流核对来源仓库、`head_branch=main`、原事件为 push 且结论 success；失败、PR 或其他仓库的运行不进入。产物清单分别记录引擎提交、源码树与 `android_recipe_commit`，不能只看 APK 文件名判断实际引擎版本。

需要包含部署失败传播修复的构建时，`source_ref` 填 `build/android-engine-variants` 或含修复的完整 SHA；只选 build 作为执行分支、`source_ref` 仍为 `main` 时构建的是纯上游。使用方应按 artifact 名称与 manifest 中实际的引擎 SHA 选择。签名方面，只有工作流执行分支为 `main` 的非 PR 构建且四个 `ANDROID_ENGINE_*` Secrets 齐备时才使用稳定发行签名；未配置 keystore、非 `main`、PR 或手动 `source_ref=main`／`workflow_run` 构建均生成本次运行专用的测试签名，不能原位覆盖已安装的稳定签名包。方案兼容仍需单独设备验证，构建成功不代表任意 YAML、词典或模型完整。

设备门禁的两个 `RimeEngineVariantDeviceTest` 方法必须用两次独立 instrumentation 启动；同一 runner 进程不会卸载第一个 DSO，连续执行会污染第二项。2026-11-12 本机固定的 x86_64 夹具为：内置 SDK 最终链接 DSO SHA-256 `401da5c080b00876e173c9b351c881888f6c0eedc278728aba88ca15df7fabaf`，`librime-evo@8727992e` provider APK SHA-256 `75638150c5d273dc9a497d7b1a91ba51316e1129474cca114e9d7ad59a041f36`，可运行万象 profile ZIP SHA-256 `1bfe7d8652a71977b3cb67aaaefdcd0efc7c71f874c90eb1a615bea968a70788`（方案基线 `45eeda12`）。运行前逐项复核摘要、安装 provider，并把 DSO/ZIP 放到下列固定设备路径；不能用同名未核验文件替代。

```sh
runner='org.fcitx.fcitx5.android.fx.rime.debug.test/androidx.test.runner.AndroidJUnitRunner'
target='org.fcitx.fcitx5.android.fx.rime.debug'
class='org.fcitx.fcitx5.android.core.engine.RimeEngineVariantDeviceTest'

adb shell am force-stop "$target"
adb shell am instrument -w -r \
  -e class "$class#importedEnginePassesDisposableProbeAndRunsThroughDirectBridge" \
  -e enginePath /data/local/tmp/librime-current-x86_64.so "$runner"

adb shell am force-stop "$target"
adb shell am instrument -w -r \
  -e class "$class#installedProviderIsDiscoveredProbedAndRunsThroughDirectBridge" \
  -e providerPackage org.rime.plugin.sandyyur.librime_evo \
  -e profilePath /data/local/tmp/wanxiang-evo-profile-45eeda12-runnable-v2.zip "$runner"
```

两次输出须各自为 `OK (1 test)`。导入项检查 isolated UID 与不可写探测快照、受管载荷、损坏执行缓存重发布及真实 bridge 启动；provider 项强制要求 `profilePath`，检查包身份重核、按摘要执行快照、完整部署、候选上屏以及同一 DSO 的 stop/start。当前通用 workflow 不携带这些外部固定夹具，故不会自动执行本项；必须把本机结果与常规 CI 分开记录。

### EVO 候选排序独立回归

涉及 EVO editor 动作时，另以全新 instrumentation 进程单独运行 `RimeEvoOrderingDeviceTest#installedEvoPersistsActualManualOrdering`，不要和已加载其他 DSO 的测试合并调用：

```sh
adb -s emulator-5574 shell am instrument -w -r \
  -e class org.fcitx.fcitx5.android.core.engine.RimeEvoOrderingDeviceTest#installedEvoPersistsActualManualOrdering \
  org.fcitx.fcitx5.android.fx.rime.debug.test/androidx.test.runner.AndroidJUnitRunner
```

该测试要求已安装通过身份校验的 EVO provider，使用独立临时 profile，不修改用户方案或引擎选择设置。核对实际候选顺序、页内／全局目标与跨页落点、陈旧请求拒绝、空提交、两类重置的 UserDB 字段和引擎 stop/finalize/start 后的持久化；不能只凭 ready 或按键返回 handled 判定排序成功。2026-11-26 在 `rime` 上为 `OK (1 test)`，固定引擎、APK 摘要与覆盖边界见[日期记录](changes/2026-11-26-evo-candidate-ordering)。

菜单按实际加载的 EVO 身份和 `editor/bindings` 中可投递的声明独立提供动作：`manual_delete` 为“重置手动排序”，保留自动学习；`auto_delete` 为“重置自动调频”，保留手动排序。后者有效时替代旧“忘记此词”，未声明时保留旧入口。万象 `super_sequence` 路径保持原行为。

### 外部引擎的方案部署排查

固定引擎载荷摘要、方案压缩包摘要及实际绑定目录，分别记录 API 探测、方案部署、候选与上屏结果。配置语法、Lua 接口、词典名称和模型格式都须符合该 fork；引擎 ARM64 CI 成功不能替代配套方案在设备上的验证。

从本次部署日志中定位首个 `error applying patch`、`unresolved dependency`、`error building config`，并核对其指向的 `__patch`／`__include` 路径；词典、模型缺失则按明确文件名核对来源和目录。旧编译文件与可枚举的方案名称不能证明本次编译成功。验证错误通知时使用新解压的独立目录，避免旧缓存遮蔽失败，同时检查 `deploy/failure` 与 `deploy/success`，最终仍须输入中文编码并实际选字上屏。

2026-11-12 已在 `librime-evo@06947eedd6236318cbf9a2745e2d0cfc65011af7` 复现：配置 Link 失败、目标编译文件缺失，但维护流程仍发出 `deploy/success`。修复在保留部分解析数据的前提下，将编译结果传给 `ConfigFileUpdate`，并让 `WorkspaceUpdate` 传播默认配置失败；独立设备进程已验证错误通知及自然码诊断包上屏。修复已随 `05037824` 推送至该仓库的开发分支，CI 已生成引擎与提供 APK 产物，但未自动更新设备上的引擎，完整范围见[日期记录](changes/2026-11-12-evo-profile-diagnosis)。上面的 `runnable-v2` 是裁剪过的测试夹具，不代表原始万象 EVO 方案通过。

2026-11-23 的构建整理中，先是 `b456bae` 按用户要求保持 amzxyz 核心、未叠加部署修复 `37a694a`；随后按同一要求把修复恢复到 build 分支并推送 `d7eab4859953a2a7ea701505f1dab652c46b7ef4`。该补丁把配置加载、Link、输出插件和默认配置的失败传播到部署结果，保留已解析的部分数据和重试，但**不补缺失的 `__include`、词典或模型**。用户当时日志实际指向缺少混合编码词典，流程报告失败并回退，不能把传播修复当成补齐文件。上游当前未吸收同等传播；判断可用性须看部署日志、目标编译文件和实际输入，不能只看 `deploy/success`。详见[2026-11-23 记录](changes/2026-11-23-evo-android-engine)。

2026-11-24 应用侧把“部署任务失败”和“当前工作区是否可用”分开判定。收到 `deploy/failure` 后，只有 `<user>/build/default.yaml` 是普通文件、`get_schema_list` 非空、当前方案的 `build/<schema>.schema.yaml` 非空，且 `translator/dictionary` 对应 `build/<dict>.table.bin` 与 `build/<prism>.prism.bin` 都存在且非空，才清除输入门禁并记录降级；任一条件不满足仍失败关闭。主 prism 按方案解析：优先 `translator/prism`；`translator/prisms` 引用 `input_types` 时按当前输入类型取对应 `name`，列表形式取首项；两者都未声明时使用 `build/dictionaries/<dict>/algebra` 的首个分组。显式名称优先限定为 `<dict>.<prism>`。没有活动会话时按 `switcher/fix_schema_list_order` 和 `user.yaml` 的 `previously_selected_schema` 选择当前方案。日志、缺失文件和失败通知不改写，降级不表示方案完整或模型已验证。

设备验证使用 `librime-evo@9d27c24b` 的 x86_64 DSO（SHA-256 `977167F8F977B1230AED7C57B28545D8830346029E09A26D07922D5850E3E2F8`）和原样 EVO 方案 ZIP（含 `wanxiang-lts-zh-hans.dlm`，SHA-256 `2AF5F0A6C3065FAFF958352E38C0B87723EA66ED6E18E9FE3839788805711B86`）。部署仍报 1 failure（缺 `wanxiang_abbrev`、`wanxiang_mixedcode` 包装词典），设备门禁在原样方案上通过，输入 `ni` 出候选并上屏，日志没有 `.dlm` 错误。设置页同时在“启用的输入方案”方案行加入 EVO 输入类型回写，只改当前方案 `*.custom.yaml` 的 `input_types/enabled` 与 `input_types/reset`。完整改动、测试边界和 squash 合并信息见[2026-11-24 记录](changes/2026-11-24-evo-settings-degraded-deploy)。

## 组件源码在哪里

以下为 2026-11-15 这一轮更新后的版本；完整 SHA 和下载摘要以[依赖锁](https://github.com/SandyYuR/librime/blob/zhongzhou/downstream/android/sources.lock.json)为准。

| 组件 | 源码 | 本轮版本 |
| --- | --- | --- |
| Lua 运行时 | [lua.org](https://www.lua.org/ftp/) | 官方 5.4.9，保持 5.4 系列 |
| librime-lua | [hchunhui/librime-lua](https://github.com/hchunhui/librime-lua) | `6da5d6ce` |
| octagram | [lotem/librime-octagram](https://github.com/lotem/librime-octagram) | `57d18b9f` |
| predict | [fxliang/librime-predict-leveldb](https://github.com/fxliang/librime-predict-leveldb) | `0c30981a`，无新提交 |
| LevelDB | [google/leveldb](https://github.com/google/leveldb) | `bb74ef73` |
| OpenCC | [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC) | 正式版 1.4.2 |
| Marisa | [s-yata/marisa-trie](https://github.com/s-yata/marisa-trie) | 正式版 0.3.1 |
| spdlog | [gabime/spdlog](https://github.com/gabime/spdlog) | 正式版 1.17.0 |
| yaml-cpp | [jbeder/yaml-cpp](https://github.com/jbeder/yaml-cpp) | 正式版 0.9.0 |
| Boost | [boostorg/boost](https://github.com/boostorg/boost) | 正式版 1.92.0 |

Marisa、spdlog、yaml-cpp 已是本轮检查时各自的最新正式版，保留现有版本。glog 已随上游 spdlog 迁移移除，不再作为 Android 依赖。predict 使用的是表中明确的 LevelDB 分支，切换到其他 predict 仓库需要另查接口、配置与数据兼容性。

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

核对标签实际提交、最终发布包的 SHA-256、四 ABI 和来源清单。Release 的 `target_commitish` 是发布元数据，不能替代标签与 SDK 清单的源码校验。SDK 有七个静态库：librime、spdlog、LevelDB、Lua、Marisa、OpenCC、yaml-cpp；三个引擎插件合并在 librime 中。

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

3. 检查 APK 原生白名单、链接贡献和所用 SDK 来源；按变更运行设备回归。`PluginSdkSmokeTest` 联合检查 Lua 翻译器、Memory 用户词库、OpenCC、残留 `.temp.userdb` 下的真实同步及关闭重启后的持久化。`Direct Rime CI` 对 JVM 和设备报告运行 `tools/check-junit-results.py`，要求实际执行且失败、错误、跳过均为零，并上传 XML 供核对；编译成功不能替代这些运行结果。
4. Linux 上可运行 `python3 tools/input-perf/test-leveldb-open.py build/leveldb-check`，它读取已准备 SDK 的来源清单，测试同版本 LevelDB 的日志复用、持久化、显式同步和恢复。若以后新增 LevelDB 补丁，需同步让探针应用并验证补丁。
5. 输入法的依赖升级单独提交，正常推送默认分支 `fx-rime-only`。默认 Commit CI 构建签名 Release，运行单元测试和 SDK 下载器测试；单元报告必须实际执行且无失败、错误、跳过，全部构建与测试门禁成功后才发布 Nightly。发布 ABI 与资产命名见[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN#按改动选择验证门禁)。

引擎 SDK 覆盖四 ABI；Commit CI 的发布矩阵见维护手册，PR CI 与 Direct Rime CI 只构建 ARM64。`Direct Rime CI` 的 `engine-smoke` 只运行引擎设备检查，其余三个诊断任务会跳过；完整前端 ARM64 APK（`frontend`）只在手动 `all` 时运行，PR 上的完整构建、全量 JVM 与 APK 审计由 PR CI 负责；全量前端触摸、真机锁屏、语音服务等需要分别选择相应验证。

用户安装新 APK 后才使用新的内置引擎。用户的方案、模型和学习库不会因 SDK 发布而自动替换。

## 回退与更换源码仓库

回退时新增提交恢复上一版 `rime-sdk.lock.json`，重建并验证 APK；不重写已发布 SDK 标签，也不删除用户词库。方案版本、模型和用户数据备份分别管理，回退 APK 不会自动回退这些文件。

如果换到另一个 librime fork：已有兼容 SDK 时可以更新消费端版本锁；只有源码时，需要带上等效的 Android 构建、打包、来源元数据和必需 API。当前配方编译所在仓库的 `HEAD`，尚未实现传入任意核心仓库地址自动构建。移植不要求使用完全相同的提交，但需对应调整 workflow 分支、`downstream/manifest.json` 及输入法依赖的扩展接口，同时核对最低 API 23、ABI、NDK 与 C++ 运行库。完全不同的引擎还需要适配后端和前端调用契约。

相关页面：[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md) · [用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md) · [本轮源码与文档核对](changes/2026-11-05.md)。
