# 2026-11-14 直连后端职责分批拆分核对

本页按已完成项保存后续职责拆分的固定源码与验证结果。每项先完成独立审阅、本机相关验证和对应提交的代码 CI，再更新配套文档并进入下一项。此前展示、文件事务和发布状态提取的原范围与结果保留在[2026-11-13 记录](./2026-11-13-direct-rime-backend-seams.md)及[2026-11-14 发布状态记录](./2026-11-14-direct-rime-publication-state.md)，不改写为后续阶段结果。

本轮目标仍是降低跨职责理解成本。输入绑定、native 快照、单一 owner、维护门控和实际事件队列继续沿现有边界执行；职责移动与行数变化不构成性能改善的证据。

## 基线与合入边界

| 项目 | 固定值 |
| --- | --- |
| 代码 PR 目标基线 `fx-rime-only` | [`88a664d46ba82712d5457d86c037629c478b042c`](https://github.com/SandyYuR/fcitx5-android/commit/88a664d46ba82712d5457d86c037629c478b042c) |
| 分批拆分开始时的代码 | [`34c64a696c05e3f06a53998dc702980fbd6133c0`](https://github.com/SandyYuR/fcitx5-android/commit/34c64a696c05e3f06a53998dc702980fbd6133c0) |
| 本页开始时的配套文档 | `54aa40a39c458f360718a4076e1e6bfbff399f24` |
| 代码／文档 PR | [PR #35](https://github.com/SandyYuR/fcitx5-android/pull/35)／[PR #36](https://github.com/SandyYuR/fcitx5-android/pull/36) |

配套文档随代码版本生效，仍在代码 PR 合入后合入 `docs`。本页只记录已完成阶段，不将旧提交或未执行检查写作当前验收。

## 第 1 项：管理命令解析

代码提交为 [`3ee199dd67e3d19d0719613d51d2092af211a5f4`](https://github.com/SandyYuR/fcitx5-android/commit/3ee199dd67e3d19d0719613d51d2092af211a5f4)，基线为 `34c64a69…`。相对基线改动 4 个源码／测试文件，新增 322 行、删除 16 行；新增两类测试共 11 项。此前 PR 已新增 32 项，本阶段之后累计新增 43 项；这些新增数与本机／CI 执行范围的总项数分别统计。

Kotlin 路径相对 `app/src/main/java/org/fcitx/fcitx5/android/`。

| 入口 | 本阶段职责 |
| --- | --- |
| `core/DirectRimeManagementCommands.kt` | 解码 Profile 管理、ZIP 导入和方案列表请求字段，生成只读命令；保留未知动作及原分支字段读取时点 |
| `core/DirectRimeBackend.kt` | 在原维护入口接入三类解码，继续执行 Direct Boot、lease、引擎、路径和安装守卫，以及配置与 Native 操作 |

Profile 的 Name 后 Action 仍在 Direct Boot 拒绝之后读取；Unknown 在原 lease／try 内报错并记录 `config.error`。可变 RawConfig 的 ExternalPath 在 create 调用处读取，NewName 在引用检查、必要 stop 之后的 rename try 内读取，头部捕获与后续字段读取保持原区别。

Import 的 idle、builtin 检查仍优先于 Archive 后 Destination 的解码。缺少 Archive、空字串与字串中的空白保留原含义，解析组件不做 canonical path 或文件校验。Schema 行仍按 tab 的三列规则筛选，保留选择顺序、重复项、空 ID 和第三列剩余内容。未知路径继续不进入维护、不改变输入代次或发布缓存；三类更新资源请求不重复交给这个组件处理。

独立审阅未发现阻止合入的问题。现行边界见[管理命令解析契约](../implementation/correctness-contracts.md#管理命令解析)，职责表见[维护手册](../DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md#直连后端的职责划分)。

### 测试与代码 CI

`DirectRimeManagementCommandsTest` 的 4 项检查默认值、只读头部与可变分支字段、导入字串和方案行规则。`DirectRimeManagementCommandContractTest` 的 7 项通过真实后端入口与临时 repository 检查未知路径、未知动作、busy lease、Direct Boot 以及 import 前置错误次序；复用现有 Native shadow，没有新增生产回调。

| 报告范围 | 实际测试数 | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 本机相关 JVM，10 类 | 65 | 0 | 0 | 0 |
| 当前提交的应用 JVM | 2148 | 0 | 0 | 0 |
| 当前提交的完整前端设备 | 46 | 0 | 0 | 0 |
| 当前提交的引擎 smoke | 6 | 0 | 0 | 0 |

本机范围包括命令解析与后端契约、配置安全、维护文件准备、发布契约、引擎 store、输入绑定、维护恢复及导入／安装事务。原始 XML 的 suite tests 与实际 testcase 数均为 65；新增的 4 项解析和 7 项后端契约测试实际执行。相同源码的本机 ARM64 Debug、APK 白名单和原生链接审计通过，包含三库、16 KiB 对齐及无 Fcitx 依赖／原生符号的检查。

以下运行均由现有 PR 分支更新自动触发，`head_sha` 为 `3ee199dd67e3d19d0719613d51d2092af211a5f4`，两条工作流及 6 个 job 全部 success：

| 运行 | 实际通过范围 |
| --- | --- |
| [PR CI 43487258166](https://github.com/SandyYuR/fcitx5-android/actions/runs/43487258166) | SDK 下载器与全量应用 JVM、ARM64 Debug、`lintFxDebug`、APK／原生链接审计 |
| [Direct Rime CI 43487258012](https://github.com/SandyYuR/fcitx5-android/actions/runs/43487258012) | prototype 原生契约／诊断构建、完整前端 ARM64、真实前端设备与引擎 smoke |

当前 CI 的 266 份应用 JUnit XML 中包含本阶段全部 11 项管理命令测试，也包含此前发布状态的 8 项。两份设备报告分别为 46 和 6 项。逐份解析实际 testcase 并校验 XML SHA-256；三个范围的失败、错误、跳过均为 0，旧提交或不同运行的报告不计入这些数量。

### 本机证据与来源区别

本阶段证据在工作区 `.dsh-tmp/direct-rime-recommended-splits-20261114/stage-01`：`junit-local`、`local-junit-summary.json`、`debug.apk`、构建及审计日志、`source-manifest.json` 和 `committed-blobs.json`。本阶段构建时，D 盘代码验证副本的 Git HEAD 为父提交 `34c64a69…`，叠加本阶段 4 文件；源工作 tree 与副本的 SHA-256 字节一致，按 Git 文本换行规则核对后与本阶段提交的对应 blob 一致。固定预设与 SDK／依赖锁沿用基线。

该副本 APK 的版本元数据仍来自父提交，仅用于相同源码的本机构建与审计，不能称为新代码 SHA 的发行产物。上表设备范围来源于本阶段远端 CI，源码、构建和设备的验收来源分别登记。

远端原始来源为 `direct-rime-frontend-arm64-debug`、`direct-rime-frontend-android-results` 和 `direct-rime-android-smoke-results`。应用 artifact 通过 HTTP Range 提取全部 JUnit XML 原压缩 member，校验源 ZIP CRC 后保存 XML-only 子集；两份设备 artifact 保留完整原 ZIP。XML 子集、源 central directory、两份设备原 ZIP及 `ci-artifacts/junit-current-summary.json` 同证据目录保留，均已按记录的摘要与 CRC 核对；XML-only 子集不是完整应用 artifact，不声称对应用整包作本机 SHA-256 复核。这些本机资料不属于文档站发布附件，在线来源为上表对应运行的 artifacts。

## 第 2 项：Profile 操作协调

代码提交为 [`0d761a76b1c065b69b7ecf6c65458dbb44877c45`](https://github.com/SandyYuR/fcitx5-android/commit/0d761a76b1c065b69b7ecf6c65458dbb44877c45)，基线为第 1 项 `3ee199dd67e3d19d0719613d51d2092af211a5f4`。本项改动 4 个源码／测试文件，新增 549 行、删除 45 行；新增 `DirectRimeProfileOperationsTest` 21 项，PR 累计新增 64 项。既有后端管理契约仍为 7 项，强化了 Direct Boot 的 Repository 未初始化断言。

按最终 Git blob 统计，Backend 从 1106 行变为 1068 行，协调器为 68 行。新增 diff 包含 471 行新测试；职责拆分的效果应结合执行边界与回归覆盖评估，行数变化不作为性能证据。

| 入口 | 本阶段职责 |
| --- | --- |
| `core/DirectRimeProfileOperations.kt` | 同步协调 Profile 管理、mutation lease、错误登记及导入安装后的注册／切换／失败恢复；调用实际 Config、Repository 和现有文件事务 |
| `core/DirectRimeBackend.kt` | 保留路径分发、Direct Boot、maintain、导入初始检查、命令解码与 transaction prepare；提供原停止／重启回调 |

Backend 的协调器 `by lazy` 构造，Repository 通过 supplier 获取；manager 中首次 Repository 访问继续在原 try 内的 lease 位置，初始化失败仍写 `config.error`。ExternalPath 与 NewName 保持原惰性读取时点；活动 rename 首次停止位于内部 try／finally 之前，停止失败不会读取 NewName 或重启，finally 重启异常保持原优先级。

管理 switch 的重启失败保留已写入的新选择。导入仍先 install，再取得 lease 并重复 builtin 检查；create 成功后立即 markRegistered，随后捕获 previous、switch／restart。导入切换失败尝试切回 previous 并重启；注册前失败清理未登记 target，注册后失败保留已登记目录。manager 与 import 各自保留原 catch(Exception)、取消、恢复异常顺序及 `config.error` 策略。

独立审阅通过。现行边界见[工作目录操作协调契约](../implementation/correctness-contracts.md#工作目录操作协调)及[方案 ZIP 导入](../implementation/correctness-contracts.md#方案-zip-导入)。Config／Repository 的持久化、保护与唯一状态源继续沿实际实现，协调器没有新 scope、dispatcher 或输入会话副本。

### 本阶段测试与代码 CI

新 21 项使用实际配置、Repository 和临时 ZIP 事务，检查 lease 与字段读取、活动／非活动 rename、初次停止失败、持久化和重启失败、manager switch／取消、导入注册前后清理及恢复异常顺序。回调用于记录生命周期顺序并注入停止／重启失败，文件与 Repository 执行真实实现。强化的后端 Direct Boot case 通过真实入口与现有 Native shadow 检查构造和两条拒绝路径仍未初始化 Repository。

| 报告范围 | 实际测试数 | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 本机相关 JVM，11 类 | 86 | 0 | 0 | 0 |
| 当前提交的应用 JVM | 2169 | 0 | 0 | 0 |
| 当前提交的完整前端设备 | 46 | 0 | 0 | 0 |
| 当前提交的引擎 smoke | 6 | 0 | 0 | 0 |

原始本机 11 份 XML 的 suite tests 与实际 testcase 均为 86，包含新 21 项与强化的原 7 项。首轮本机命令遗漏既有 `local-tests.init.gradle.kts`，Robolectric 下载锁落入 C 盘并拒绝访问；失败日志与原 XML 保存在 `jvm-failed-01.log`／`junit-failed-01`。补回原 D 盘测试 init 后完整执行 86 项，全零；生产和测试没有为此改写或豁免。

当前 SHA 的两条代码工作流及 6 个 jobs 全 success：

| 运行 | 实际通过范围 |
| --- | --- |
| [PR CI 43493292245](https://github.com/SandyYuR/fcitx5-android/actions/runs/43493292245) | SDK 下载器与全量应用 JVM、ARM64 Debug、lint、APK／链接审计 |
| [Direct Rime CI 43493292237](https://github.com/SandyYuR/fcitx5-android/actions/runs/43493292237) | prototype 原生契约与诊断构建、完整前端 ARM64、真实前端设备与引擎 smoke |

本阶段应用 artifact 的 267 份原始 XML 实际 testcase 为 2169，包含新 Profile 操作 21 项、管理解析 4 项、后端管理 7 项和此前发布状态 8 项。两份设备原 XML 分别为 46 和 6 项；全部报告的 SHA-256、源 member CRC 及失败／错误／跳过已核对，不继承第 1 项的报告数量。

### 本阶段证据与构建来源

证据在工作区 `.dsh-tmp/direct-rime-recommended-splits-20261114/stage-02`：成功／失败的本机 XML 与日志、`local-junit-summary.json`、源码 `source-manifest.json`／`committed-blobs.json`、`debug.apk`、构建和两项原生审计日志。本机 ARM64 Debug、三库白名单、16 KiB 对齐和原生链接审计通过。

本阶段构建时 D 验证副本 HEAD 为父提交 `3ee199dd6…`，叠加本项四路径；源与副本逐字节一致，并按 Git 文本换行规则与 `0d761a76…` 对应 blob 一致。APK 版本元数据仍来自父提交，仅用于相同源码的本机构建与审计；本阶段设备范围取自当前提交远端 CI。验证副本随后推进 HEAD，不改写构建时来源。

当前 CI 的 XML-only 应用子集、源 central directory、两份设备原 ZIP 及 `ci-artifacts/junit-current-summary.json` 保存在同目录。应用子集保留原压缩 XML member 并校验源 ZIP CRC；设备包保留完整原 ZIP 并核对摘要。与第 1 项相同，这些本机资料不属于站点附件，在线来源是上表对应运行的 artifacts。
