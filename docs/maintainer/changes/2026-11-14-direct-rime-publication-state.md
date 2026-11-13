# 2026-11-14 直连后端发布状态拆分核对

本次继续拆分 PR #35 的发布状态，降低跨职责理解成本，将展示缓存、候选版本与事件批次规则集中到一个由 Backend 私有持有、沿现有 owner 使用的状态持有者。验收以状态字段归属、生产调用边界和行为等价为依据；行数与职责拆分本身不构成性能改善的证据。此前菜单、展示转换与文件事务提取的固定范围及验证结果保留在[2026-11-13 记录](./2026-11-13-direct-rime-backend-seams.md)，不改写为本次结果。

## 来源与合入状态

| 项目 | 核对值 |
| --- | --- |
| PR #35 目标基线 | [`88a664d46ba82712d5457d86c037629c478b042c`](https://github.com/SandyYuR/fcitx5-android/commit/88a664d46ba82712d5457d86c037629c478b042c) |
| 本次开始时的代码 PR 提交 | [`ae74bcfbb5b10effc21fc544924a310ceb31f66b`](https://github.com/SandyYuR/fcitx5-android/commit/ae74bcfbb5b10effc21fc544924a310ceb31f66b) |
| 本次发布状态拆分提交 | [`34c64a696c05e3f06a53998dc702980fbd6133c0`](https://github.com/SandyYuR/fcitx5-android/commit/34c64a696c05e3f06a53998dc702980fbd6133c0) |
| 配套文档开始时的提交 | `998ebd6c37031278fa1677b7bd1adeeff0e19b63` |
| 代码／文档 PR | [PR #35](https://github.com/SandyYuR/fcitx5-android/pull/35)／[PR #36](https://github.com/SandyYuR/fcitx5-android/pull/36) |

本次代码提交已固定，两条自动 PR 检查运行均对应该提交且结论为 success。配套文档仍在代码 PR 合入后合入 `docs`；这些状态记录本次核对现场，后续状态以 Git 和 CI 查询为准。

## 职责与行为边界

Kotlin 路径相对 `app/src/main/java/org/fcitx/fcitx5/android/`。

| 生产入口 | 本次职责 |
| --- | --- |
| `core/DirectRimePublicationState.kt` | 持有 client preedit、input panel、输入法展示条目及状态菜单四类缓存，已发布 generation、候选版本和候选事件缓存；登记候选目标，构造发布批次，清空绑定展示并更新提示过期／不可用面板 |
| `core/DirectRimeBackend.kt` | 继续持有单一 owner、输入 generation 和 native 快照；校验绑定与结果、读取 Native 选项、执行输入门控和提示计时、停止／重启 native，将生成的输入批次交付 `InputEventQueue`，并继续生成生命周期事件 |
| `core/DirectRimePresentation.kt` | 继续完成本次 Snapshot 到展示值的无状态转换 |
| `core/DirectRimeStatusMenu.kt` | 继续组装菜单并解码命令，命令在 Backend owner 上执行 |

完整输入批次构造、候选版本与缓存转换、绑定清空、菜单清空和提示过期仍由 Backend 在现有单一 owner 上执行。状态类不创建线程、不调用 Native、不直接写事件队列。启动时同步 `onStarting` 沿原调用线程更新 volatile 不可用面板，不推进候选版本，也不改变其不绑定 generation 的事件语义。输入绑定的 generation 与 native revision 仍取自本次引擎结果，不能从展示缓存恢复旧会话。

发布规则继续保持统计与提交先于展示、候选目标绑定实际发布版本、过期结果不覆盖当前状态。满足既有不变松键条件时仅替换 Backend 持有的 native 快照；强制刷新读取新结果，不重放旧 commit。候选强制重发与候选失效分别处理，绑定结束清空展示时使旧候选目标失效，同时保留菜单与输入法展示条目到新绑定发布。提示寿命与任务取消仍由 Backend 处理，面板内容更新交给发布状态。状态类构造时注入菜单 builder 和返回 primitive long 的 `CommitClock`，不为每个按键创建计时回调。

现行契约见[后端展示与菜单](../implementation/correctness-contracts.md#后端展示与菜单)，维护职责见[维护手册](../DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md#直连后端的职责划分)。本次资源安装与 profile 导入仍沿用此前的文件事务边界。

本轮相对 `ae74bcfbb…` 修改 6 个源码／测试文件，新增 507 行、删除 98 行；相对 PR 原目标基线共 16 个文件，新增 1844 行、删除 229 行。Backend 从本轮开始时的 1160 行降为 1107 行，相对 PR 原目标基线为 1248 → 1107 行；新 `DirectRimePublicationState` 为 129 行。行数包含排版、接口和测试夹具，职责是否迁移仍以状态字段和生产调用为准。

## 回归入口

新增 `core/DirectRimePublicationStateTest` 共 8 项，覆盖完整输入批次、统计与 commit 计时关联、处理按键时相同首屏的版本推进、强制重发保持版本且不重放提交、选项／分页失效、清空绑定后保留菜单与输入法条目、提示过期仅更新面板、不可用面板默认 session 为 0，以及菜单清空后重建和输入法条目变化。

既有 `DirectRimePublicationContractTest`、`CandidateActionGuardTest` 和 `CandidateSelectionQueueTest` 将反射夹具迁到新的状态持有者，保留原有行为断言；目标回归还包括不变松键与输入统计的 `ReleaseSnapshotTest`、`InputStatisticsBackendTest`。前一轮新增 24 项测试继续保留，本轮新增 8 项，PR #35 相对原目标基线共新增 32 项。测试源码存在不代表执行通过，实际结果见下表。

## 本次验证

本次不能沿用 `ae74bcfbb…` 的旧 CI 成功状态；远端结果必须对应本次代码提交。

| 检查 | 本次状态 |
| --- | --- |
| 发布状态规则与 Backend 集成目标 JVM 测试 | 本机两批实际执行 12 类、87 项（74 + 13），failures／errors／skipped 均为 0；其中新增状态类 8 项全部执行 |
| 发布状态缓存与计时接口字节码 | `javap` 确认五个对外状态字段保留 volatile，`CommitClock.now()` 返回 primitive long |
| Debug 构建与相关检查 | 本机相同源码的 ARM64 Debug 构建、`verify-direct-rime-apk.py` 与 `audit-direct-rime-link.py` 通过；三库、16 KiB 对齐及无 Fcitx 符号审计均通过 |
| PR CI 与 Direct Rime CI | [PR CI 43476803134](https://github.com/SandyYuR/fcitx5-android/actions/runs/43476803134)／[Direct Rime CI 43476803224](https://github.com/SandyYuR/fcitx5-android/actions/runs/43476803224) 均对应 `34c64a69…`，6 个检查全部 success |
| 本机设备验证 | 本轮没有安装 APK 或执行新的本机设备测试；真实引擎与触摸范围按本轮远端报告登记 |
| `git diff --check` | 通过 |

本机证据目录为 `.dsh-tmp/direct-rime-publication-state-verify-20261114`：首轮 10 类、74 项的 XML 保存在 `jvm-target-74-results`；随后 `ReleaseSnapshotTest` 的 4 项与 `InputStatisticsBackendTest` 的 9 项 XML 在 `repo/app/build/test-results/testFxDebugUnitTest`。两批测试类互不重复，合计 12 类、87 项。该 D 盘验证副本的 Git HEAD 为 `ae74bcfbb…`，叠加本轮 6 个源码／测试文件；副本与源工作 tree 的逐文件 SHA-256 字节一致，按 Git 文本换行规则核对后与 `34c64a69…` 的对应 blob 一致。4 个仓库内部符号链接在副本物化为相同内容，预设子模块仍固定 `082425ea0684bca36474415d4a0e8db9b016487e`，依赖锁未调整。

该副本生成的 Gradle 版本元数据仍沿 `ae74bcfbb…`，本机 APK 仅用于核对本次源码的构建与审计，不能称为远端 `34c64a69…` 的发行产物。本次没有安装或上传本机 APK，也没有执行新的本机设备测试；新提交的完整 APK、全量 JVM 和设备验收按远端 CI 的实际产物与报告登记。

### 本次远端 CI

以下运行的 `head_sha` 均为 `34c64a696c05e3f06a53998dc702980fbd6133c0`，由现有 PR 分支更新自动触发。

| 运行 | 实际通过范围 |
| --- | --- |
| [PR CI 43476803134](https://github.com/SandyYuR/fcitx5-android/actions/runs/43476803134) | `JVM unit tests`、`Debug APK build`；SDK 下载器与应用 JVM、ARM64 Debug、`lintFxDebug`、APK／原生链接审计 |
| [Direct Rime CI 43476803224](https://github.com/SandyYuR/fcitx5-android/actions/runs/43476803224) | `prototype`、`android_smoke`、`Full frontend ARM64 APK`、`Full frontend touch and engine tests`；原生安全契约、诊断模块、完整前端 ARM64、真实引擎 smoke 与前端设备回归 |

从本轮 `Direct Rime CI` 的三个 artifact 解析 JUnit XML，结果如下；新增 `DirectRimePublicationStateTest` 的 8 项全部实际执行。

| 报告范围 | 实际测试数 | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 应用 JVM | 2137 | 0 | 0 | 0 |
| 完整前端设备 | 46 | 0 | 0 | 0 |
| 引擎设备 smoke | 6 | 0 | 0 | 0 |

来源是 `direct-rime-frontend-arm64-debug`、`direct-rime-frontend-android-results` 与 `direct-rime-android-smoke-results`，三者均绑定上述运行与提交。应用 artifact 原 ZIP 为 23,668,958 字节，通过 HTTP Range 选取全部 264 个 JUnit XML 的原压缩 member，并逐项验证源 ZIP CRC，保存为 222,821 字节的 `direct-rime-frontend-arm64-debug-xml-only.zip`；该本机文件为 XML 子集，完整原 ZIP 的 SHA-256 未作本机整包复核。两份设备 artifact 原 ZIP 则整包下载并核对 SHA-256 和 member CRC。解析时还逐项核对 XML SHA-256；旧提交与不同运行的报告不计入这些数量。

本机汇总为上述证据目录的 `ci-artifacts/junit-current-summary.json`，XML 子集、源 central directory 与设备原 ZIP 同目录保留。这些本机验证资料不属于文档站发布附件，在线来源为上表 CI 运行的 artifacts。

## 本次文档验证

文档基于配套 PR 的独立 worktree 编辑。此前 C 盘生成目录出现过 `EPERM`，本次使用 `D:/GitHub/fx2-rime/.dsh-tmp/direct-rime-publication-docs-verify-20261114` 的独立验证 worktree，核对相同 HEAD 及本次 5 个改动文件的 SHA-256 字节内容后运行门禁。复用现有 D 盘 `node_modules` 前核对两份 `package-lock.json` 的 SHA-256 相同；没有调整依赖锁、混入原 docs 工作区的未提交内容或覆盖此前证据。

| 检查 | 本次状态 |
| --- | --- |
| `npm --prefix docs run build` | 通过；240 篇正文、45 个保留路由、721 个下载附件 |
| `python docs/scripts/check-links.py` | 通过；292 个页面，页面与资源链接错误均为 0 |
| `python docs/scripts/check-legacy-docs.py` | 通过；64 个恢复页面、13 份维护报告、83 个原始文件及源归档核对成功 |
| `node docs/scripts/check-doc-catalog.mjs` | 通过；目录、保留路由与功能映射错误为 0 |
| `node docs/scripts/check-doc-search.mjs` | 通过；8 项查询、5 项范围检查，错误为 0 |
| `git diff --check` | 通过 |

本次尚未进行新的性能测量，职责拆分本身不构成延迟改善的证据。
