# 中州鹅直连版维护手册

本页描述默认分支 `fx-rime-only` 的现行维护方式；本轮范围与固定提交见[2026-11-05 代码与文档核对](changes/2026-11-05.md)，此前的完整基线见[2026-10-30 对照](changes/index.md)。功能操作见[用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)。
历史实验、阶段结论和当时的测试数量在[直连版历史](history/index.md)中保留，含 Fcitx 层的旧版资料在[靓企鹅中州韵档案](../legacy/index.md)中保留。

## 仓库与模块

| 位置 | 职责 |
| --- | --- |
| `app/` | 完整主应用、输入法前端、设置、布局、主题、语音与数据功能 |
| `direct-rime/src/main/cpp/` | 主应用使用的 JNI 桥与 `libdirect-rime.so` |
| `app/src/main/java/org/fcitx/fcitx5/android/core/` | 主应用的 DirectRimeBackend、owner 线程、生命周期和事件队列 |
| `direct-rime/src/engine/java/` | 主应用和诊断模块共用的 Native 接口、Snapshot 与方案 ZIP 工具 |
| `direct-rime/src/main/java/` | 独立诊断应用的 RimeRuntime、服务与实验页面 |
| `lib/common/` | IPC、剪贴板及语音 AIDL 公共接口 |
| `native/rime-sdk.lock.json`、`native/rime-sdk/` | 固定的 librime SDK 版本、摘要与自动下载的静态库/头文件/数据 |
| `native/rime-prelude` | Rime 共享预设子模块 |
| `native/rime-data/default.yaml` | 空方案列表的共享默认配置，不删除 `schema_list: []` |
| `build-logic/`、`tools/`、`.github/workflows/` | 构建版本权威、验证脚本与 CI |

当前只有 `native/rime-prelude` 一个子模块；引擎改为下载固定版本的 SDK。旧 `lib/fcitx5`、`plugin/rime`、`app/src/main/cpp` 已移除；本地遗留的未跟踪目录不属于构建输入。
代码分支不放 `docs/`；站点、协议和报告统一在 `docs` 分支。

## 构建与安装

工具版本由 `build-logic/convention/src/main/kotlin/Versions.kt` 及构建配置决定。当前使用 Java 17、SDK 36、NDK `28.0.13004108`、CMake `3.31.6`，最低 Android 6.0 / API 23。

```sh
git status --short --branch
git submodule status
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/debug/*.apk
```

`buildABI` 支持 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。非空 `BUILD_ABI` 环境变量优先于 `-PbuildABI` 属性；两者均未设置时才构建全部支持 ABI。
本机日常脚本与当前 PR/发布 CI 选择 ARM64，设备回归可按需要构建 x86_64。不要把“引擎产物支持四 ABI”写成“当前 PR CI 打包四 ABI”。

| 包 | 包名 | 用途 |
| --- | --- | --- |
| Release 主应用 | `org.fcitx.fcitx5.android.fx.rime` | 日常使用，需要有效签名 |
| Debug 主应用 | `org.fcitx.fcitx5.android.fx.rime.debug` | 完整功能调试，数据与 Release 独立 |
| 独立诊断 APK | `org.fcitx.fcitx5.android.fx.rime.lab` | 最小引擎诊断，不是日用输入法 |

普通构建固定为直连，不需要选择后端。旧参数 `-PdirectRimeFrontend=true` 只作兼容，显式 `false` 会报错；`-PdirectRimePrototype=true` 才额外包含诊断模块。
`-PinputDiagnostics=true` 开启细粒度输入统计，日常构建避免承担这些统计开销。

版本号使用本仓库的历史基准标签 `0.1.3` 及提交距离；原始带注释标签已原样保留到本仓库。日常 CI 不再拉取 Fcitx 上游标签，后续基准版本由本项目自行维护。更新引擎 SDK 不会改变版本号生成规则。

Debug 产物在 `app/build/outputs/apk/fx/debug/`，Release 在 `fx/release/`。核对 `output-metadata.json`、文件时间及来源提交，不要从目录中随意挑旧 APK。
安装 Debug 后也需要自行导入方案并部署。签名或包名变化前先备份；同包名同签名才可能覆盖升级。

Windows 使用工作区的一键脚本，不另改系统环境：

```powershell
pwsh -File D:\GitHub\fx2-rime\build-debug.ps1
pwsh -File D:\GitHub\fx2-rime\build-debug.ps1 -Test
pwsh -File D:\GitHub\fx2-rime\build-debug.ps1 -Install
pwsh -File D:\GitHub\fx2-rime\build-release.ps1
```

环境异常先看[Windows 本机构建](LOCAL_BUILD_ENVIRONMENT_zh-CN.md)。Release 签名、安装到用户设备与对外发布各有独立授权和验证要求。

## 输入事件链路

```text
软键盘 / 物理按键
  → FcitxInputMethodService 串行队列（入队前获取 ticket）
  → DirectRimeBackend 与会话代次校验
  → rime-main 单一 owner 线程 → JNI → librime session
  → commit / preedit / 候选 / 方案 / 状态的一次快照
  → InputEventQueue 的有序批次 → InputConnection 与界面
```

`FcitxInputMethodService` 等保留的类名、兼容键名和布局字段不代表旧原生后端仍在运行。

| 必须保持 | 源码 / 验证入口 |
| --- | --- |
| 入队前取得按键 ticket，只有一条服务按键队列 | `InputKeyGate`、`postFcitxJob`、`InputKeyGateTest` |
| commit、delete、key down/up 有序且无损 | `InputEventQueue` 保留副作用批次；纯展示状态只在相邻无副作用批次间合并 |
| 结果消费前校验 session/generation | `prepareInputSession`、`requireInputSession` 与 binding |
| 组合改变时取消候选分页，批间允许其他工作运行 | 顶层函数 `readCandidatePage`、`CandidatePageReaderTest` |
| 部署、同步和重载期间不处理按键，结束后恢复 | `maintain`、`maintenanceMutex`、`keyGate` |
| 刷新和状态提示不能重放 commit | `publishWithoutCommit`、native action 9 |
| DOWN 有兜底 UP，线程优先级在 finally 恢复 | 键盘生命周期、`InteractiveThreadPriority` |

当前事件载体是 `InputEventQueue` 的批次队列；其 `Channel.CONFLATED` 仅发出唤醒信号，不承载输入正文。容量等待发生在进入 owner 前，独立消费者仍可请求候选。早期 `Channel(UNLIMITED)` 的描述属于历史实现，不应据此恢复旧结构。

密码、显式禁用组词的输入框，以及首次解锁前的输入，直接转发按键。它们在 native 返回“无方案”时仍可完成绑定；普通中文输入框仍需要可用方案。直接输入继续经过相同的 ticket、代次和有序事件队列，不能绕过部署/同步门控或交付过期按键。`InputBindingStartupTest` 覆盖无方案密码输入、DOWN/UP 顺序、重复 focus 与过期会话拒绝。

Direct Boot 启动时，应用设置使用 `directBootAwareContext`，引擎只在 DE 内部文件目录建立独立空 profile，不访问受凭据保护的外部方案目录。已解锁进程通过 `DirectBootLayoutSync` 将当前选中布局同步为固定的 DE 快照，首次解锁前由 `ConfigProvider` 读取；不在锁屏阶段扫描外部布局目录。缺失、解析失败或没有可用按键时，密码布局沿回退链继续选择，最终使用内置 QWERTY。字体、图标及日常中文方案仍待解锁后的进程重启恢复；旧 CE 资产清理也只在解锁后执行。

同步监听所选 Profile 和目录事件，目录暂不可用时保留完整快照并退避重试，删除或无效 JSON 则清理快照；写入使用内容比较和原子替换。解锁接收器通过退出进程切回正常 CE 初始化，不把 DE 默认设置反写到 CE。`DirectBootStartupTest`、`DirectBootLayoutStoreTest`、`DirectBootLayoutSyncTest` 覆盖初始化、存储与同步；这些测试不能写成真机冷重启已验收。

历史问题与修正过程见[响应性记录](history/DIRECT_RIME_RESPONSIVENESS_2026-10-18.md)、[候选点选](history/DIRECT_RIME_CANDIDATE_TAP_2026-10-18.md)和[后续输入回归](audit/direct-rime-2026-10-25/index.md)。

## librime 从哪里来

来源链为 **官方 rime/librime → SandyYuR/librime 的 zhongzhou 定制源码与 Android SDK Release → 输入法版本锁**。
定制源码包含音节接口、rewriter 和学习事务等功能，不能用未经验证的官方预编译库直接覆盖。

`native/rime-sdk.lock.json` 固定仓库、发布标签、源码提交、官方基线、四 ABI 和 SDK 包 SHA-256。根 Gradle 任务 `prepareRimeSdk` 自动下载到 `native/rime-sdk/`；原生配置和 OpenCC 资产任务都显式依赖它。Windows 下载也使用 Java，不新增 Python 要求。SDK 只含实际需要的七个静态库、头文件、OpenCC 数据、许可证及来源清单。

```sh
cat native/rime-sdk.lock.json
./gradlew prepareRimeSdk
cat native/rime-sdk/sdk-manifest.json
```

SDK 缓存在 `GRADLE_USER_HOME/caches/rime-sdk/<sha256>.zip`。首次在线准备后可以 `--offline` 构建；没有缓存时明确失败，不回退到其他版本。下载包、文件清单、逐文件摘要、源码版本及 ABI 完整性均会校验。Gradle 检测提取目录的内容变化后会从已验证缓存恢复。

Android SDK CI 位于 librime 仓库：从固定来源编译 Boost 头文件、六个原生依赖、三个插件与引擎，并在主机生成 OpenCC 数据。它不读取旧 prebuilt，四 ABI 均执行最终链接验证，打包前核验全部归档对象的 ELF 架构、API 和来源。prebuilder/prebuilt 保留历史重建用途，不再参与当前更新。

`librime.a` 静态链接进 `libdirect-rime.so`。LevelDB 的 `reuse_logs` 与恢复验证仍由 `tools/input-perf/test-leveldb-open.py` 等工具守护；调整依赖时同步核对实际导出和存储行为。

### 更新 librime

完整步骤、组件来源、摘要计算、变基与补丁重放、SDK 发布、输入法更新和回退见[引擎、插件与依赖更新](engine-update.md)。

1. 在 `SandyYuR/librime` 的 `zhongzhou` 更新固定官方基线，依次重放独有补丁，更新 `downstream/manifest.json`；核心补丁只维护在源码提交中。
2. 需要升级依赖/插件时修改 `downstream/android/sources.lock.json` 和固定补丁，保持来源、摘要、工具链明确。
3. 源码 CI 与 Android SDK CI 通过后，核对原生回归、四 ABI 编译/最终链接、SDK 文件清单和来源。
4. 在已验证提交上创建新的 `android-sdk-*` 标签，由 CI 发布版本化 SDK；已发布标签和包不覆盖。
5. 输入法更新 `native/rime-sdk.lock.json`，运行下载校验、APK 原生白名单/链接审计与必要的设备回归。只需维护 librime 和输入法两个代码仓库。
6. 回退时恢复上一版锁文件，不改用户词库。不能追踪移动分支或只按“最新”前移版本。

完整构建、依赖锁和发布说明见 [librime Android SDK 维护文档](https://github.com/SandyYuR/librime/blob/zhongzhou/downstream/android/README.zh-CN.md)。10 月的四仓库迁移报告属于历史记录，保留当时的 SHA、测试与流程，不能作为当前升级步骤。

### 依赖与插件版本

`downstream/android/sources.lock.json` 固定每个组件的源码 URL、版本或提交、源码包 SHA-256 和本地补丁摘要。重新运行 CI 或同步 librime 上游都不会自动更新这些组件。升级时同时修改来源、版本、下载 URL 和摘要，重放兼容补丁，必要时更新构建配方；发布新 SDK 后再更新输入法版本锁。

2026-11-05 这一轮更新涉及以下组件，具体提交以源码仓库的依赖锁为准：

| 组件 | 更新目标 |
| --- | --- |
| Lua 运行时 | 官方 5.4.9 源码包，保持 Lua 5.4 系列 |
| librime-lua | `6da5d6ce`，包含关闭 Lua VM 时的 GC 修正 |
| octagram | `57d18b9f` |
| LevelDB | `bb74ef73` |
| OpenCC | 正式版 1.4.2，Android 库与主机生成工具同源 |
| Boost | 正式版 1.92.0 |

predict 的固定提交仍是其仓库最新提交；glog 0.7.1、yaml-cpp 0.9.0、Marisa 0.3.1 保持各自最新正式版。万象方案自己的 `.lua` 脚本随方案管理，不会随 Lua 运行时或 librime-lua 升级而自动改变。

Lua 官方源码包的最小 CMake 配方与 Android API 23 的文件定位兼容修正保存在 `lua.patch`。Boost 头文件缓存按源码包摘要刷新，复用构建目录也不会沿用旧版本。`tools/input-perf/test-leveldb-open.py` 从已准备 SDK 的来源清单读取实际 LevelDB 版本，检查持久化、日志复用、显式同步与恢复；不再固定测试另一个旧版 LevelDB。

依赖的配置、编译和安装都限制 Git 向父目录查找，避免 OpenCC 在嵌套构建目录中误用 librime 的标签或提交号作为自身版本。Android 库与主机生成工具都要求 OpenCC 版本与依赖锁完全一致。

Android 的 `PluginSdkSmokeTest` 联合检查 Lua 翻译器、Memory 用户词库接口、OpenCC 转换，并在仍有 Lua 翻译迭代器时关闭引擎、重启后读取持久化词条。SDK CI 的主机原生回归仍使用 Ubuntu 系统依赖；四 ABI 编译通过与 Android 运行验证需要分别核对。

## 语音宿主与原生打包

AIDL 外部提供方与 JS 插件共用会话、录音、静音判定和回填。JS 提供方使用 `js:` ID 前缀，QuickJS 依赖由版本目录固定。

当前 APK 原生白名单是 **`libdirect-rime.so`、`libc++_shared.so`、`libquickjs.so`**，权威为 `tools/verify-direct-rime-apk.py` 的 `allowed`。
任何新增 `.so` 都必须同时更新并审阅白名单，不能因为 Debug 能构建就认为发布门禁会通过。

语音宿主的密钥、逐插件主机授权、prelude/stub 双侧接口和收尾预算见[JS 宿主协议](implementation/js-voice-plugins.md)。
目标测试加载真实 bundled 脚本；JVM 使用 `quickjs-kt-jvm` 替换 Android native 依赖。

## 按改动选择验证门禁

| 改动 | 最低检查 |
| --- | --- |
| 文档和站点 | 人工核对、`git diff --check`、站点构建、链接与历史完整性检查；布局调整检查桌面及窄屏 |
| 纯 Kotlin | 相关 JVM 测试，必要时 Debug 出包 |
| UI、IME、协程和输入链路 | 目标测试 + Debug；行为改动补设备回归 |
| JS 语音 | `--tests 'org.fcitx.fcitx5.android.input.voice.*'` + Debug；API 改动补真实脚本契约测试 |
| JNI、native 或 librime | native 出包 + APK 白名单和链接审计 |
| 键盘形态、主题、工具栏尺寸 | 真机或模拟器截图与实际输入，不能仅凭 JVM 单测 |
| Release / 签名 | 本地签名脚本 + `apksigner verify --print-certs` |

```sh
./gradlew :app:testFxDebugUnitTest --tests '完整测试类名'
tools/test-direct-rime-frontend.sh
tools/test-material-settings.sh
```

默认分支当前的 PR CI 运行 ARM64 Debug 与 JVM，Commit CI 运行签名 ARM64 Release、ART profile 审计与 JVM；Direct Rime CI 覆盖原生诊断与完整前端回归，Material settings UI 覆盖 MD3 页面。
实际执行项以 workflow 内容为准；CI 不跑全量 lint。测试类“仍在仓库里”不等于仍被 CI 执行。

全量 Robolectric 测试使用单个并行 worker 和 1536 MiB 堆，为各 Android SDK 沙箱资源表预留空间。该配置不减少测试；SDK 下载器的 Gradle 测试单独执行并在完成后结束外层构建进程。

本机已知 Windows 失败涉及文件覆盖式 `renameTo` 与符号链接权限；只有再次核对原因一致，才能标成已有环境失败。
没有跑的检查明确写“未运行”。Gradle 构建成功只证明能出包，不证明功能已经验收。

## 资产、数据与配置维护

- 修改内置布局、主题时，在 `BundledPresets.assetSizes` 登记**上一版**字节数，保护用户已修改文件。
- 资产描述采用相对路径、POSIX 分隔符与流式摘要；新增、移动或同名文件必须触发正确增量更新。
- 完整备份先停止原生写入，再归档缓存；保存词典不等于停止引擎。外部目录并发修改须检测并报错。
- 密钥在备份中保持密文，Android Keystore 密钥不导出；跨设备后需重填语音密钥。
- 设置入口变化同步分组、承载页、搜索索引与现有语言资源，避免出现入口能点但搜不到的情况。

协议详见[同步接收](implementation/clipcascade-receive-limits.md)、[加密备份](implementation/protected-backup-format.md)、[整包恢复](implementation/user-data-restore.md)。

## 性能与历史证据

性能工具分别位于 `tools/input-perf/`、`tools/candidate-perf/` 和 `tools/screen-perf/`。复现前核对比较版本、方案、模型、ABI、构建类型、设备、输入速度和计时区间。
相同文件名的远端模型也可能换过内容，应按原报告哈希找匹配夹具，不能跳过检查。
手动触发性能 workflow、上传日志或设备数据前取得明确授权。

日常构建的 `RimeLifecycle bind` 记录每次绑定的总耗时，以及 `native_begin_us`、`restore_us`、`publish_us` 和会话 `created`/`reused` 计数。三段分别对应 native 绑定、恢复方案/中英文状态、读取和发布前端状态；单位为微秒，总计 `duration_ms` 为毫秒，不包括进入 owner 前的排队。`ready` 表示 native 方案可用，`usable` 表示此编辑器可以收键，`direct` 标识直接输入。`binding_key wait_ms` 是按键在绑定期间的等待时间，`delivered=true` 表示延后交付，不能据此归为丢键。

绑定的三段同步工作均在 `InteractiveThreadPriority` 作用域内，退出或异常时恢复原优先级。日常日志只增加每次绑定的少量计时与计数；逐键 CPU、缺页和文件 I/O 统计仍须 `-PinputDiagnostics=true`。没有分段记录的旧日志只能定位到整次绑定，不能直接断言耗时来自词库重开、Lua 或界面绘制；优化后的真机延迟须在相同方案和设备上复测。

所有原始报告及 ZIP/JSON/截图见[验证证据目录](evidence.md)。历史 Actions artifact 有保留期限，不能当成永久下载或当前版本证明。
完整 XKB Compose、按应用行为等价和广泛真机回归仍有边界，不因旧后端被移除而自动视为完成。


输入、键盘、布局、主题和数据保护的详细约束与故障定位，见[正确性约束与代码定位](./implementation/correctness-contracts)。
