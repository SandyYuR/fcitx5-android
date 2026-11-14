# AGENTS.md — 中州鹅

供本仓库及 fork 的 AI 编程助手使用。路径相对仓库根；遵循当前授权与目录指令。事实以代码、构建和 CI 为先，历史报告不代表当前验证。

## 项目与入口

架构为 Kotlin → 单一 owner → JNI → librime，仅 `fx` flavor。Fcitx 类名、包名、IPC/AIDL 和布局字段保留兼容用途；不恢复旧原生后端／子模块、`app/src/main/cpp`、`mainline` 或后端开关。

| 位置 | 内容 |
| --- | --- |
| `app/src/main/` | 输入、键盘／候选、设置、布局／主题、语音／AI、数据与更新 |
| `app/src/main/java/org/fcitx/fcitx5/android/core/engine/` | 运行时引擎来源、授权、探测与配置绑定 |
| `direct-rime/src/engine/java/`、`direct-rime/src/main/cpp/` | 引擎接口、快照和 JNI，主应用与诊断模块共用 |
| `native/rime-sdk.lock.json`、`native/rime-prelude` | 固定引擎 SDK 来源及唯一的共享预设子模块 |

直连后端位于 `app/src/main/java/org/fcitx/fcitx5/android/core/`。`DirectRimeBackend` 保留唯一 native owner、实时 Binding／Snapshot／started、输入 epoch、keyGate 与事件交付；展示缓存及发布版本由 `DirectRimePublicationState` 持有。

| 职责 | 当前入口 |
| --- | --- |
| 管理命令解码／profile 操作 | `DirectRimeManagementCommands`、`DirectRimeProfileOperations` |
| 展示转换／菜单／发布缓存／提示 | `DirectRimePresentation`、`DirectRimeStatusMenu`、`DirectRimePublicationState`、`DirectRimeStatusHintScheduler` |
| 引擎激活／槽切换 | `DirectRimeEngineOperations` |
| 更新准备／维护与绑定恢复 | `DirectRimeMaintenanceFiles`、`DirectRimeMaintenanceCoordinator` |
| 候选／音节目标与光标校验 | `DirectRimeInteractionTargets` |

改动先从真实 API／服务调用定位职责类与测试，沿用既有 owner、dispatcher 和 scope。职责类不得建立可独立更新的实时绑定、native 快照或输入门控副本；保留动态状态读取、generation／native revision／candidateVersion／tabsRevision 各自的校验、取消恢复及事件交付顺序。

完整手册在 `docs` 分支，使用独立 worktree 或[文档站][manual]，不在代码分支新建 `docs/`。fork 使用上游文档时核对代码差异；本仓库不依赖维护者私有脚本。

## 开始修改前

先查 `git status --short --branch`、`git branch -vv`、`git submodule status`、`git worktree list` 和远端，保留已有修改与暂存。上游默认分支为 `fx-rime-only`，fork 以现场为准。

获取远端用 `git fetch --no-recurse-submodules --prune origin`，仅按需初始化 `native/rime-prelude`。找符号同时查生产调用和测试，文件存在不等于仍在使用。

按改动阅读相关契约：

| 改动 | 必读 |
| --- | --- |
| 输入、候选、生命周期、Direct Boot、配置、同步、统计与方案更新 | [正确性契约][contracts] |
| 设置、布局、主题、辅助面板 | [DESIGN.md](DESIGN.md)、[设计与共用组件][design] |
| JS 语音、AI 或工具宿主、网络授权与回填 | [JS 宿主协议][voice] |
| SDK、引擎、插件与依赖 | [native/README.md](native/README.md)、[引擎更新流程][engine] |
| 备份与恢复 | [恢复事务][restore]及其中的备份格式链接 |
| 文档站、导航、搜索及历史记录 | [文档维护约定][docs] |

## 必须保持的边界

- 输入沿既有队列／owner，入队前取 ticket；副作用保序保全，只合并相邻展示。候选／音节绑定显示代次，过期拒绝；维护、取消、关机不复活旧会话。
- 分页候选经 `InputBroadcaster` 按 `PagedCandidateReceiver` 能力给每个接收方分发一种表示；列表接收方仍需兼容，不用超时猜测分发模式。
- 普通候选绑定不拆分字形簇，上滑选字时才解析。字体引用变化须清理测量／绘制变换缓存并重新布局，保留正文、注释样式及选择目标。
- 区分 UTF-16、Unicode、UTF-8 位置与 X11 keysym；刷新不重放提交，缓存不恢复旧输入状态。
- Direct Boot 不访问受保护数据。备份／更新先停写并走事务，保留回滚和取消边界；不删数据库／`LOCK` 绕过问题。资源更新保护用户文件，`BundledPresets.assetSizes` 登记上一版字节数。
- 密钥由宿主保存与注入；语音、AI 与 JS 工具遵循各自授权，失效编辑器拒写。工具按钮按稳定插件／App ID 解析当前来源；源码切换须失效旧会话及旧 App／主机授权。AI 预览后明确确认上屏；布局兼容、草稿和保存时序按契约维护。
- 统计与字词记录分别启用，排除敏感输入并校验 epoch。方案更新使用不可变任务／目标，整批暂存后应用，不混淆暂停、无变更与成功。

## 构建与验证

使用 Gradle Wrapper；工具链与 ABI 查 `build-logic/convention/src/main/kotlin/Versions.kt`，依赖查 `gradle/libs.versions.toml`。从仓库根执行（POSIX 示例）：

```sh
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
./gradlew -PbuildABI=arm64-v8a :app:testFxDebugUnitTest --tests 'org.fcitx.fcitx5.android.core.InputEventQueueTest'
python3 tools/check-junit-results.py
```

按改动选择测试类。Windows 用 `gradlew.bat` 和 Python；无 Bash 时以 `git submodule update --init --depth 1 native/rime-prelude`、`./gradlew.bat prepareRimeSdk` 准备。非空 `BUILD_ABI` 优先于 `-PbuildABI`，均未设则构建全部 ABI。

SDK 按锁校验生成 `native/rime-sdk/`，首次准备后才离线，不改生成物／锁来绕过下载问题。开发 SDK 仅通过 `localRimeSdkDir` 接入 Debug，Release 拒绝；保留 `.debug` 后缀。`inputDiagnostics` 与用户统计独立。

锁文件约束内置 SDK。运行时变体沿 `RimeEngineStore`／`DirectRimeEngineOperations` 的授权、来源校验、profile 绑定与进程重启／回退路径处理。

| 改动 | 最低验证 |
| --- | --- |
| 说明文件 | diff、路径、链接和命令入口；不重跑整套业务测试 |
| Kotlin | 相关 JVM 测试 |
| UI／IME／协程／输入行为 | 目标测试 + Debug；行为或视觉变化加设备验证 |
| 候选分发／字体排版 | `CandidateBroadcastTest`、`CandidateRenderingStateTest`；前端设备门禁 `tools/test-direct-rime-frontend.sh` |
| JS 宿主 API | 相关 JVM 测试 + Debug + 真实 bundled 脚本契约测试 |
| 性能采样工具 | `python3 -m unittest discover -s tools/input-perf -p 'test_*.py' -v`；触摸探针改动另编译 AndroidTest |
| JNI／native | 原生契约测试、出包、APK 白名单与链接审计 |
| SDK 锁／下载器 | `./gradlew --no-daemon -p build-logic :convention:test --tests RimeSdkArchiveTest`；升级另核四 ABI 和设备 |
| 文档站 | 构建、链接、历史完整性、目录、搜索五项检查，命令见[维护约定][docs] |

APK 门禁为 `tools/verify-direct-rime-apk.py`、`tools/audit-direct-rime-link.py`，须匹配本次 APK 与构建输入。测试及 CI 条件查[维护手册][manual]和 workflow。

设备操作核对目标、授权及数据影响，不自行清空。JUnit 须实际执行且失败／错误／跳过全零，阻塞写明原因。组件图、旧 CI 和构建成功不能当设备验收。

性能对照固定完整源码 SHA、APK 摘要、SDK／方案／模型／配置与协议，保留失败及不利样本。固定节拍触摸探针从实际 DOWN 起保持至少 20ms，分别记录计划、注入和窗口接收时间；首轮／热态、trace／无 trace 分开统计，缺失覆盖不得算作零 CPU。探针只用于专门的 AndroidTest，不进入产品或普通前端回归。

## 收尾

行为同步手册，构建／协议同步维护文档，实测结果进入日期报告。复查 diff、Git 状态，报告检查及未验证范围。

提交／推送／PR／发布、依赖变更与上传须明确授权；仅暂存指定文件，保护签名、数据和未知目录。保持线性历史，强推须授权并用 `--force-with-lease`；沿用仓库身份与中文提交风格。

[manual]: https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN
[contracts]: https://sandyyur.github.io/fcitx5-android/maintainer/implementation/correctness-contracts
[design]: https://sandyyur.github.io/fcitx5-android/maintainer/implementation/design-system
[voice]: https://sandyyur.github.io/fcitx5-android/maintainer/implementation/js-voice-plugins
[engine]: https://sandyyur.github.io/fcitx5-android/maintainer/engine-update
[restore]: https://sandyyur.github.io/fcitx5-android/maintainer/implementation/user-data-restore
[docs]: https://sandyyur.github.io/fcitx5-android/maintainer/documentation
