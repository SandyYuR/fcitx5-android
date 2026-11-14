# 2026-11-14 候选修复与性能文档压缩合入

本次按维护者要求复核代码、文档和已完成检查，代码与文档分别以 Squash and merge 合入，只在各自目标分支新增一个提交。原始实验、失败样本和源码身份保留，不将旧数据改记为合并后版本的新性能测试。

## 代码与验证映射

| 项目 | 固定来源 |
| --- | --- |
| 代码 PR | [#32](https://github.com/SandyYuR/fcitx5-android/pull/32) |
| 合入前默认分支 | `0f3e334350c183eb4699ef7b7f74d676efc44056` |
| 已测试的 PR 头 | `4a87997ac115badf407dc741ffb8d124879a665b` |
| 压缩合入提交 | `3e0a870001a1f59ee33bc1e4dd0971b8d37b225d` |
| 配套文档 PR | [#31](https://github.com/SandyYuR/fcitx5-android/pull/31)，最终文档提交及部署状态以该 PR 和 Docs 工作流为准 |
| 本轮文档收尾前的头 | `6b489e4c7adfe41286cee4b21ab862ac44d59635` |

代码压缩提交的父提交为原默认分支，与已测试 PR 头的完整内容树一致；合入前六个审阅提交因此不作为六个新提交进入默认分支。没有更改引擎、SDK 锁、插件或其他依赖，也没有把独立性能驱动的实验工作流带入产品。

复核确认分页与列表接收方各收到一种候选表示，真实模式切换立即生效；空页和同文字的新选择版本仍正常交付。普通绑定延后字形簇拆分，字体引用变化使测量缓存失效，正文、注释及高亮样式继续保留。代码检查未发现需要另改生产逻辑的遗漏。文档补齐[正确性契约](../implementation/correctness-contracts.md#候选目标、坐标与展示快照)中的分发、字形和字体规则，现行架构不再显示“等待 PR #32 合入”。

本次沿用同一内容树的实际验收：[PR CI 43560767674](https://github.com/SandyYuR/fcitx5-android/actions/runs/43560767674) 与 [Direct Rime CI 43560767684](https://github.com/SandyYuR/fcitx5-android/actions/runs/43560767684) 六个任务通过，2,327 项应用 JVM、60 项前端设备、6 项引擎 smoke 及 109 项工具测试通过，失败／错误／跳过均为 0；构建、lint、探针编译和 APK／native 审计通过。原件及来源摘要见[对齐记录](./2026-11-14-pr32-alignment.md)。这些是功能及构建验收，不是合入后重新测得的端到端性能或真机验收。

## 分支与历史保留

两份 PR 均完成合入后清理以下远端、本地分支及其多余 worktree；实际完成状态登记在 PR 描述中。清理前先提交本页的恢复资料，删除时核对固定源头，避免误删后续变化。

| 分支 | 清理前固定头 | 内容去向 |
| --- | --- | --- |
| `fix/first-candidate-latency-20261112` | `4a87997ac115badf407dc741ffb8d124879a665b` | 产品压缩提交、原审阅 PR 及恢复 bundle |
| `report/wanxiang-retest-20261108` | `6b489e4c…` 加本轮文档收尾 | 文档压缩提交、原审阅 PR；历史报告和原件继续原位保留 |
| `perf/pr35-default-comparison-20261114` | `a3486b32b483a209eff9ccb069526d5926489c1a` | 恢复 bundle 与独立驱动快照；此前测量结果继续保存在[职责拆分性能报告](../history/direct-rime-refactor-performance-2026-11-14.md) |

剩余 perf 分支的测试已结束，无关联的未合入 PR。其核心后端、直连模块及 native 文件与清理前默认分支完全相同；独立驱动未作为新产品功能合入。首次元数据失败和随后成功的 Actions 均继续保留，性能工作流启停状态未调整，旧 CI、发布标签和历史报告未删除。

[代码恢复 ZIP](../audit/pr32-squash-merge-2026-11-14/code-history.zip) 包含代码 PR 头和 perf 头的 Git bundle，以及性能分支的完整工作流和三个驱动文件。为避免重复整个仓库，该 bundle 依赖已有默认历史中的 `88a664d46ba82712d5457d86c037629c478b042c` 与 `0f3e334350c183eb4699ef7b7f74d676efc44056`；使用完整克隆，在仓库内运行 `git bundle verify code-history.bundle` 后，以 `git fetch /绝对路径/code-history.bundle 'refs/archive/*:refs/archive/*'` 恢复归档引用。已在独立 bare 仓库取回所需基线并恢复两条归档引用，提交与内容树逐项一致，见[恢复核验](../audit/pr32-squash-merge-2026-11-14/verification.json)及[附件校验清单](../audit/pr32-squash-merge-2026-11-14/checksums.sha256.txt)。

## 历史结果与发布范围

此前有利与不利结果继续按各自固定产品解释：候选链路两版对照尚未证明普遍稳定提速，字体对照的首次点选风险、无效的绘制缓存干预和 #35 的对象分配增加信号均未被删除。合并提交与测试源内容相同，也不能将旧条件扩展到所有设备、Release 或 GPU 帧呈现。

默认分支沿既有 CI 构建签名 Release，并在构建与单测成功后生成 Nightly；本轮未手动另建应用版本或调整发布流程。文档仅在 Docs 部署成功后更新站点；PR 描述改用固定来源链接，删除分支不影响证据入口。
