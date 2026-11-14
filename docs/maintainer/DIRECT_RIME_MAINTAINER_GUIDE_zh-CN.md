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

### 直连后端的职责划分

下表的 Kotlin 路径相对 `app/src/main/java/org/fcitx/fcitx5/android/`。后续职责的分批拆分见[2026-11-14 分批核对](changes/2026-11-14-direct-rime-responsibility-splits.md)；发布状态拆分的固定源码与验证记录见[2026-11-14 发布状态核对](changes/2026-11-14-direct-rime-publication-state.md)，此前展示与文件事务提取的当次事实保留在[2026-11-13 后端重构核对](changes/2026-11-13-direct-rime-backend-seams.md)。

| 组件 | 职责与调用边界 |
| --- | --- |
| `core/DirectRimeBackend` | 持有单一 native owner、输入绑定与 generation、唯一 started、本次 Snapshot、启动错误及 keyGate／events；保留启动／停止与薄维护入口，提供原 enter／leave 屏障、实际状态事件、方案刷新及绑定读写回调，执行 native 命令并交付输入批次 |
| `core/DirectRimeMaintenanceCoordinator<B>` | 唯一持有维护 Mutex，编排 owner 维护操作、激活结果及绑定恢复；通过固定 Owner 访问后端当次状态和实际动作，private Binding 保持在 Backend，不增加 owner／scope／线程或实时状态副本 |
| `core/DirectRimeInteractionTargets` | 仅保存 Owner 引用，动态判定动作上下文、owner 内 revision 与候选分页版本，同步编排显式光标校验及既有位置映射；Snapshot、代次与展示状态仍从 Backend 读取，raw input／act 9／光标恢复事件仍由 Backend 回调执行 |
| `core/DirectRimeEngineOperations` | 在原 owner 上同步协调引擎配置、槽切换、维护停止／重启及激活结果；持有 activeSelection、newActivation、维护 pending selection 和 Handler／watchdog，惰性取得实际 Repository／shared，透过 Owner 访问后端唯一 started／快照／启动错误 |
| `core/DirectRimePublicationState` | 持有四类前端展示缓存、已发布 generation、候选版本及候选事件缓存；在 owner 上登记候选目标、构造有序输入发布批次、清空绑定展示及更新提示过期面板；不可用面板保留既有启动回调的同步更新方式 |
| `core/DirectRimeStatusHintScheduler` | 在原 engineScope／owner 上协调提示文本、提示 generation、Job 与 `StatusHintLifetime`；保留首次呈现及兜底寿命、替换／清理顺序，过期只调用后端提供的纯面板回调 |
| `core/DirectRimeStatusMenu` | 组装状态菜单并解码动作 ID；命令交回后端在 owner 上执行 |
| `core/DirectRimePresentation` | 将本次 Snapshot 转为 preedit、panel、带版本的音节标签及候选展示值；不调用 native、不发布事件 |
| `core/DirectRimeManagementCommands` | 解码 Profile、方案导入和方案列表管理请求的字段；保留只读命令头部及分支字段的原读取时点，交回既有维护入口执行 |
| `core/DirectRimeProfileOperations` | 在后端既有维护 owner 上同步协调工作目录管理、mutation lease、错误登记及导入注册／切换／失败恢复；调用实际 Config／Repository，停止和重启回调由后端提供 |
| `core/DirectRimeMaintenanceFiles` | 在 owner 上、停机前核对高级设置／自定义更新／万象更新任务，生成包含目标、payload 和策略的安装计划 |
| `data/RimeProfileUpdateInstaller` | 共用停机、资源安装及失败恢复流程；停止和重启回调由后端提供，保留外部引擎启动守卫与 watchdog |
| `data/RimeProfileImportTransaction` | 负责方案 ZIP 的路径检查、暂存解压、目录安装与登记前失败清理；登记、切换和失败恢复由 `DirectRimeProfileOperations` 协调，重启沿用后端回调 |

发布状态、展示转换与菜单命令解码不取得输入会话所有权。`DirectRimePublicationState` 接收后端已校验的本次结果与展示上下文，不调用 Native、不创建线程、不直接写事件队列；完整输入批次和候选版本转换在现有单一 owner 上执行，后端调用 `events.offer`。启动时同步 `onStarting` 可以在调用线程更新 volatile 不可用面板；这条既有生命周期路径不推进候选版本。资源更新与方案导入的取消边界分别见[正确性契约](implementation/correctness-contracts.md#资源维护与方案导入)。

引擎运行协调器复用原 Store 的授权、绑定保护和启动守卫，没有运行标志、Snapshot、native session 或 scope 副本。Backend 保留 startupStage 的 assets／config／native 顺序和输入实时状态；维护流程及恢复时点由 `DirectRimeMaintenanceCoordinator` 控制，enter／leave 的实际旗标、同步代次和 keyGate 仍由 Backend 回调执行。既有停止／重启薄委托 `DirectRimeEngineOperations`；槽切换规则见[引擎运行协调](implementation/correctness-contracts.md#引擎运行协调)，Mutex、取消及绑定恢复见[维护与绑定恢复协调](implementation/correctness-contracts.md#维护与绑定恢复协调)。

交互目标组件复用 Backend 的动态 Owner 投影，保留候选显示版本、native revision、音节 tabsRevision 和 inputSession 的不同检查；原短路及重复代次读取不合并成一次捕获。索引、全局分页、交互 admission、诊断／统计、实际 Native 调用及最后的新快照门仍在 Backend；目标判定和同步光标分支见[交互目标校验与光标操作](implementation/correctness-contracts.md#交互目标校验与光标操作)。

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
| 刷新和状态提示不能重放 commit 或移动编码光标 | `refreshPresentation` 使用 native action 13；action 9 只用于真实原始编码光标移动 |
| DOWN 有兜底 UP，线程优先级在 finally 恢复 | 键盘生命周期、`InteractiveThreadPriority` |

当前事件载体是 `InputEventQueue` 的批次队列；其 `Channel.CONFLATED` 仅发出唤醒信号，不承载输入正文。容量等待发生在进入 owner 前，独立消费者仍可请求候选。早期 `Channel(UNLIMITED)` 的描述属于历史实现，不应据此恢复旧结构。

密码、显式禁用组词的输入框，以及首次解锁前的输入，直接转发按键。它们在 native 返回“无方案”时仍可完成绑定；普通中文输入框仍需要可用方案。直接输入继续经过相同的 ticket、代次和有序事件队列，不能绕过部署/同步门控或交付过期按键。`InputBindingStartupTest` 覆盖无方案密码输入、DOWN/UP 顺序、重复 focus 与过期会话拒绝。

绑定时直接使用 `Native.beginInput` 返回的 Rime 初始快照，不再把上一编辑器的方案和 ASCII 状态回放给引擎。`reset`、已保存开关及 `fix_schema_list_order` 的选择结果由 librime 决定。同应用只有前后都非敏感且原 session 有效时才复用；进入密码、PIN 或禁止个性化学习的输入框后，回到文本框也会创建新会话，避免带回 Lua 内部状态。状态菜单的 action 6/8 共用按 `switcher/save_options` 保存的入口；独立 Shift 由 Rime `ascii_composer` 处理，不能把按键中的临时西文强制记为长期偏好。

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

本地修改引擎后可走[Windows 本地开发](local-engine-development.md)：工作树快照、单 ABI 构建、SDK 校验和 Debug 接入各有明确入口。开发输入带标记；正式 SDK 与 Release 继续使用已提交源码及固定版本锁。

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

`jniLibs.useLegacyPackaging = true` 是已有的打包策略：三类 `.so` 在 APK 中以 ZIP_DEFLATED 压缩，Android 安装时解压后加载。它减少下载包大小，安装目录仍有解压出的库，加载内存不会按 ZIP 压缩比例减少。APK 门禁同时检查压缩格式、ELF 加载段对齐和依赖白名单；压缩不能代替 16 KiB 对齐验证。比较包体需先 clean、使用相同 ABI、固定 SDK 和 Release 裁剪配置，不把已有压缩收益当作本次清理收益。

APK 已精确排除 Commons Codec 的 `org/apache/commons/codec/language/bm/**` 和 `dmrules.txt` 姓名匹配规则，压缩依赖及实际解包能力仍保留。过时 `assets/README.md` 不再进入 APK 和 `descriptor.json`。旧输入法选择弹窗、Table 文件选择页、弃用分隔线及 Fcitx `punctuation`/`chttrans` UI 和回调已移除；`tools/verify-direct-rime-apk.py` 检查退休前端类及闲置资源不能回到产物中，静态链接审计仍由 `tools/audit-direct-rime-link.py` 检查实际库和 linkmap。

以下 Fcitx 名称仍有生产用途，不能据名字继续删除：`FcitxInputMethodService`、`FcitxAPI`、`FcitxEvent` 和队列/连接接口承载直连前端；包名、AIDL、IPC 权限、备份格式与 `fcitx://` 路由用于已有客户端兼容。`direct-rime/src/engine/java` 的 `Native`/`Snapshot` 及 `direct-rime/src/main/cpp` 同时供主应用使用，独立 `.lab` 诊断入口也保留。Rime 通用预设、OpenCC、内置布局/主题/符号及语音/AI 脚本均继续由生产入口加载。

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

默认分支当前的 PR CI 运行 ARM64 Debug、全量 JVM、`lintFxDebug` 及 APK/链接门禁；Commit CI 运行签名 ARM64 Release、ART profile 审计与 JVM；Direct Rime CI 覆盖原生诊断与完整前端回归，Material settings UI 覆盖 MD3 页面。
实际执行项以 workflow 内容为准；`lintFxDebug` 通过不等于所有构建变体的 lint 均已执行。测试类“仍在仓库里”不等于仍被 CI 执行。

Commit CI 与 PR CI 在调用 `prepare_personal_build.sh` 前先执行 `gradle/actions/setup-gradle`，使脚本里的首次 Gradle 调用能够使用恢复的缓存。缓存未命中时，Wrapper 按 `gradle/wrapper/gradle-wrapper.properties` 中固定的官方地址下载 Gradle，网络超时为 60 秒。若日志停在 `Downloading ...gradle-...zip` 并报网络超时，失败发生在 Gradle 启动前；应检查分发包下载与缓存恢复步骤，不能当作编译或业务测试失败。

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

[2026-11-14 职责拆分性能对照](history/direct-rime-refactor-performance-2026-11-14.md)固定默认产品与拆分产品，在两组 Actions Debug 模拟器测量候选点击及 Backend 工作负载；保留对象分配增加与延迟波动的信号和边界，不将职责移动或行数变化写成提速结论。

日常构建的 `RimeLifecycle bind` 记录每次绑定的总耗时，以及 `native_begin_us`、`publish_us` 和会话 `created`/`reused` 计数。前者包含 native 创建或复用会话、由 Rime 恢复方案/开关和读取初始快照，后者记录前端发布与绑定门控处理；前端快照回放已移除，不再输出单独的 `restore_us`。单位为微秒，总计 `duration_ms` 为毫秒，不包括进入 owner 前的排队。`ready` 表示 native 方案可用，`usable` 表示此编辑器可以收键，`direct` 标识直接输入。`binding_key wait_ms` 是按键在绑定期间的等待时间，`delivered=true` 表示延后交付，不能据此归为丢键。

绑定及首次快照发布的同步工作均在 `InteractiveThreadPriority` 作用域内，退出或异常时恢复原优先级。日常日志只增加每次绑定的少量计时与计数；逐键 CPU、缺页和文件 I/O 统计仍须 `-PinputDiagnostics=true`。没有分段记录的旧日志只能定位到整次绑定，不能直接断言耗时来自词库重开、Lua 或界面绘制；优化后的真机延迟须在相同方案和设备上复测。

所有原始报告及 ZIP/JSON/截图见[验证证据目录](evidence.md)。历史 Actions artifact 有保留期限，不能当成永久下载或当前版本证明。
完整 XKB Compose、按应用行为等价和广泛真机回归仍有边界，不因旧后端被移除而自动视为完成。


输入、键盘、布局、主题和数据保护的详细约束与故障定位，见[正确性约束与代码定位](./implementation/correctness-contracts)。
