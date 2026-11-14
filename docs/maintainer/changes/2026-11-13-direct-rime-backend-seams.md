# 2026-11-13 直连后端职责拆分核对

本次补齐直连后端重构的维护说明与验证记录，不修改应用代码。结论限于下列固定提交；测试通过不构成性能改善的证据。

## 来源与合入状态

| 项目 | 核对值 |
| --- | --- |
| 代码基线／核对时默认分支 `fx-rime-only` | [`88a664d46ba82712d5457d86c037629c478b042c`](https://github.com/SandyYuR/fcitx5-android/commit/88a664d46ba82712d5457d86c037629c478b042c) |
| 重构分支 `refactor/direct-rime-backend-seams` | [`ae74bcfbb5b10effc21fc544924a310ceb31f66b`](https://github.com/SandyYuR/fcitx5-android/commit/ae74bcfbb5b10effc21fc544924a310ceb31f66b) |
| 代码 PR | [PR #35](https://github.com/SandyYuR/fcitx5-android/pull/35) |
| 文档基线 `origin/docs` | `08ffd6f035adead6bf70f90ac7dd1d13baed77c7` |

核对时 PR #35 为 open、非草稿，GitHub 报告可合并、可 rebase，尚未合入默认分支。配套维护说明应随该代码版本生效，文档 PR 在代码 PR 之后合入。此状态仅记录本次核对现场，不代替后续 Git 查询。

本机原有 `docs` 工作区仍在 `9d663ea74177f96e7624b9b133278912a52113db`，落后上述文档基线 1 个提交，并有此前未提交的文档修改。本次从最新 `origin/docs` 建立独立分支，未混入或改动那批内容。

## 改动与职责边界

相对代码基线，重构涉及 6 个生产文件和 6 个新增测试类，共新增 24 个测试方法：

| 生产入口 | 拆分后的职责 |
| --- | --- |
| `core/DirectRimeBackend.kt` | 继续唯一拥有 native owner、输入 generation、snapshot、candidateVersion、事件顺序、维护门控与 native 生命周期 |
| `core/DirectRimeStatusMenu.kt` | 组装方案／模式／选项菜单，解码动作 ID；执行命令仍回到后端 owner |
| `core/DirectRimePresentation.kt` | 将 Snapshot 转为 preedit、panel、tabs 和候选展示；候选选择版本仍由后端登记 |
| `core/DirectRimeMaintenanceFiles.kt` | 校验三类更新请求并形成安装计划，保留原有空任务边界 |
| `data/RimeProfileUpdateInstaller.kt` | 执行资源安装事务，保留停写、双层恢复与异常传播；外部引擎 watchdog 仍由后端管理 |
| `data/RimeProfileImportTransaction.kt` | 准备导入 staging、原子安装及注册前失败清理 |

上述路径相对 `app/src/main/java/org/fcitx/fcitx5/android/`。新增测试覆盖菜单 ID 与互斥组选项、展示坐标与目标代次、完整发布批次、过期结果拒绝、刷新不重复提交，以及文件安装与导入失败恢复。`DirectRimePublicationContractTest` 的同一份 4 项测试也曾在未重构的基线 Backend 上执行通过。

现行约束仍以[正确性约束与代码定位](../implementation/correctness-contracts.md)为准。本次没有迁移输入状态所有权，也没有修改 SDK 锁、JNI、依赖或 workflow。

## CI 与报告核对

以下运行均对应代码提交 `ae74bcfbb5b10effc21fc544924a310ceb31f66b`，结论为 success：

| 运行 | 覆盖 |
| --- | --- |
| [Commit CI 43261103963](https://github.com/SandyYuR/fcitx5-android/actions/runs/43261103963) | ARM64 Release、应用 JVM 与 SDK 下载器测试；非默认分支的 nightly 发布按条件跳过 |
| [Direct Rime CI 43261905353](https://github.com/SandyYuR/fcitx5-android/actions/runs/43261905353) | 原生诊断、ARM64 Debug、应用 JVM、完整前端设备与引擎 smoke |
| [PR CI 43392458774](https://github.com/SandyYuR/fcitx5-android/actions/runs/43392458774) | PR #35 的 JVM、Debug、`lintFxDebug`、APK 白名单与原生链接审计 |
| [Direct Rime CI 43392458699](https://github.com/SandyYuR/fcitx5-android/actions/runs/43392458699) | PR #35 的原生、完整前端与引擎设备回归 |

从前两条运行下载的报告逐份解析 JUnit XML，得到以下计数；后两条运行另核对 PR 的提交与成功状态，不把不同运行的测试数累加：

| 报告范围 | 实际测试数 | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 应用 JVM | 2129 | 0 | 0 | 0 |
| SDK 下载器 | 10 | 0 | 0 | 0 |
| 完整前端设备 | 46 | 0 | 0 | 0 |
| 引擎设备 smoke | 6 | 0 | 0 | 0 |

本机核对记录保存在工作区 `.dsh-tmp/review-direct-rime-backend-seams-20261113/verified-ci-test-summary.json` 与 `pr-final.json`，原始 ZIP 同目录保留；这些本机文件不属于文档发布附件。在线来源为上表各 CI 运行及其 artifacts。

原开发会话的 Windows 目标测试有 1 项因创建符号链接权限不足而跳过，不能记为本机全绿；额外触摸夹具停在 Android 35 `showSoftInput()` 的 served-view 前置条件，也不能记为候选触摸断言通过。上述 Linux JVM 与远端设备报告提供各自实际执行的验收依据，不改写这两项本机限制。此次补文档未重新安装应用或运行本机设备测试，也未进行新的性能测量。

## 本次文档验证

以下检查在 D 盘的独立文档验证 worktree 执行。C 盘 Codex worktree 创建生成目录时遇到 `EPERM`，提升权限重试仍失败；验证副本沿用同一文档基线，并核对本次 5 个改动文件的内容一致，没有调整依赖锁或站点检查规则。

| 检查 | 状态 |
| --- | --- |
| `npm --prefix docs run build` | 通过；239 篇正文、45 个保留路由，721 个下载附件发布到构建目录 |
| `python docs/scripts/check-links.py` | 通过；291 个页面，页面链接与资源链接错误均为 0 |
| `python docs/scripts/check-legacy-docs.py` | 通过；64 个恢复页面、13 份维护报告、83 个原始文件及源归档核对成功 |
| `node docs/scripts/check-doc-catalog.mjs` | 通过；目录、保留路由与功能映射错误为 0 |
| `node docs/scripts/check-doc-search.mjs` | 通过；8 项查询、5 项范围检查，错误为 0 |
| `git diff --check` | 通过 |
