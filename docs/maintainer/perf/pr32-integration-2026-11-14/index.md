# PR #32 整合与临时分支清理（2026-11-14）

本次按用户要求，把字体缓存修复、两项候选链路改动及可复用的采样工具整理到同一个 [PR #32](https://github.com/SandyYuR/fcitx5-android/pull/32)。**PR 保持草稿，正式性能收益仍待验收。** 旧版数据继续对应各自的源码，没有改记成这次最终组合的性能结果。

## 当前代码与提交

整合前重新获取了远端：实际默认分支为 **0f3e334350c183eb4699ef7b7f74d676efc44056**，已包括 PR #35 的后端职责拆分及此前的 JS 工具、源码定制和翻译功能。新 PR 基于该提交，没有覆盖外置引擎管理和新 JS 功能。默认代码和 docs 分支均不直接操作。

PR 头为 **aafc6d583f2096bc26ae621d7cc18bd627ae22d2**，相对基线只有五个线性提交：

| 提交 | 内容与来源 |
| --- | --- |
| `516260a1` | 字体变化时清理测量缓存；生产改动与原 PR `2e86e2ec` 的最终版本相同，保留字宽和样式测试 |
| `851754ec` | 真正上滑选字时才拆分字形簇；迁自 `cc8c2601` |
| `b9204e0b` | 按接收组件能力分发分页快照，移除 500ms 模式猜测；迁自 `f02bbaec`，保留版本和模式切换测试 |
| `b935ff78` | 整合原 PR 的测试包所有权、真实触摸时长与原始数据保护，并应用 `a3c9bea6` 的周期 trace flush 修正 |
| `aafc6d58` | 接入工具单测、探针编译、前端设备回归及 README；保留最新引擎说明、引擎设置页截图收集，并修正菜单测试的异步等待 |

[范围核验](./integration-scope.json)逐文件确认六份生产 Kotlin 与已审阅版本字节一致。原生引擎、SDK 锁、构建依赖和 Rime 学习语义没有改动；没有恢复已撤下的 Paint、角标及 owner 优先级试验。

## 采样工具如何保留

代码 PR 保留原 PR 可复用的 v2 采样链路，普通触摸至少保持实际 DOWN 后 20ms，设备和测试包仍须通过身份／所有权校验。独立追踪配置补上 `flush_period_ms: 1000`；工作流不再预填某个过时的当前产品 SHA，运行时须明确提供完整提交。

v3 独立进程发键器、冻结的 88a664d4／f02bbaec 双机计划、复用固定旧 APK 的工作流，以及未形成有效缓存干预的诊断驱动，继续保存在[输入链路报告](../current-input-pipeline-2026-11-13/index.md)、[绘制等待报告](../render-wait-2026-11-14/index.md)和本页的完整分支历史归档中。没有把一次性实验工作流设为日常工作流，也没有将 v2 与 v3 原始数据混用。

这次整理不会使之前报错的八份追踪变成有效追踪。旧报告的 `config_write_into_file_no_flush`、缓存对照未成立和性能门槛未达标记录均保留。

## 验证与数据边界

本地采样工具 **109 项单测通过**，见[完整执行记录](./tool-tests.txt)。[预期设备用例](./expected-device-cases.json)根据这次实际源码提取，排除需要独立模型夹具的 `diagnoseColdCandidateCore`，普通前端设备门禁应完整执行 **60 项**。

本次整合 CI 的实际结果见[逐项核验](./ci-validation.json)与[原始验证资料](./ci-records.zip)：[PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/43553382733)、[Direct Rime CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/43553382736)。附件身份见[Actions 清单](./artifacts.json)。构建及功能检查不能替代性能对照。

首轮整合版 `8ee233bc` 的设备回归为 59／60，通过项不覆盖掉失败项：第二次打开状态区时，测试立即检查了异步填充前的空 adapter。生产 `StatusAreaWindow.onAttached` 会先在 owner 上获取菜单，再投递到主线程更新列表。修正只让测试用既有的有界 `await` 等待真实 Rime 条目出现；之后仍执行真实点击、两向模式切换及提示断言，没有跳过或放宽行为验证。首轮 XML／日志和[失败登记](./initial-ci-failure.json)保留；修正后的中间版 `82d88f95` 随后因默认分支合入 PR #35 而被最终变基版取代；相应任务按各自实际状态登记，不作为最终验收。最终源码重新运行完整门禁。

历史结论继续保留：

- 字体修复的旧对照仍有首次点选 **+1.271ms／+2.646ms** 风险，见[字体报告](../pr32-font-confirmation-2026-11-13/index.md)。
- 两项候选改动的旧对照针对 **88a664d4 → f02bbaec**，不含本次字体修复和新增 JS 功能；有冷态改善迹象，未达到稳定收益门槛。
- 原有两条有效追踪说明主线程可能被前一帧 EGL 同步挡住；它们不能证明本次最终组合普遍更快或更慢。

本次没有重新运行正式性能对照，也没有真机、ARM Release 或候选帧呈现延迟的验收。用户要求的是将已审阅改动放入同一 PR 并清理分支；不以这项指示改写过去的测量结论。

## 清理范围与可恢复性

只清理本轮临时分支 **`perf/input-pipeline-20261113`** 及其 `/workspace/fcitx5-android-pipeline` 工作区；归档推送后执行，具体状态见[清理回执](./cleanup-receipt.json)。

保留 PR #32 的 `fix/first-candidate-latency-20261112` 和文档 PR #31 的 `report/wanxiang-retest-20261108`。`perf/pr35-default-comparison-20261114`、用户重构与文档分支，以及其他新功能分支属于其他正在进行的工作，不纳入本轮清理。现有手动性能工作流的启停状态和历史 CI 保留。

整合前的旧 PR 头 **2e86e2ec** 和临时分支头 **a3c9bea6** 都保存为 Git bundle，另保存最终 PR 的五项提交、完整源码和旧新补丁。已在独立仓库恢复两个旧分支及新源码；新源码 ZIP 的 **1,565 份文件**与恢复结果逐字节一致，见[恢复核验](./history-validation.json)。

- [分支历史、旧新补丁及恢复脚本](./branch-history.zip)
- [最终 PR 完整源码](./integrated-source.zip)
- [五项提交 mbox](./integrated-commits.mbox.txt)
- [此前证据完整性核验](./previous-evidence-validation.json)
- [本页附件 SHA-256](./checksums.sha256.txt)

恢复历史时先解开 `branch-history.zip`，在空仓库获取所需前置基线 `0380d70f16efb6a9946a164821d5cad7118ce4ff`、`1230ee45bf11cce18a445475f785cdf62bf141b6`、`0f3e334350c183eb4699ef7b7f74d676efc44056`（可使用 `git fetch --depth 1`）。将 `before-integration.bundle` 的两个分支分别导入 `refs/archive/pr32-before`、`refs/archive/pipeline-before`，再从 `integrated-history.bundle` 获取最终 PR 分支。具体命令由归档内的 `verify-history.py` 展示；与 `integrated-source.zip`、`integration-scope.json` 放在同一目录即可独立核验，无需被删除的远端临时分支。

历史报告内的临时分支链接保留当时文字；清理后的源码查询以对应报告的源码归档或本页历史包为准。原有报告与原始附件不因整理分支而重写。日期按北京时间，CI 原时间保留 UTC。
