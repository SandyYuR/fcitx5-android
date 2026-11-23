# 中州鹅直连版历史

这里记录从 librime 原型到完整主应用的演进，以及后续审阅、合入和功能修订。涉及 Fcitx 的对照实验仍属于**直连版研发记录**；保留 Fcitx 层版本自身的手册和报告另在[靓企鹅中州韵档案](/legacy/)。

## 按演进路线阅读

| 阶段 | 记录 |
| --- | --- |
| 原型与完整前端接入 | [原型实现](./DIRECT_RIME_IMPLEMENTATION_2026-10-16) · [原界面接入](./DIRECT_RIME_FRONTEND_2026-10-16) · [依赖解耦](./DIRECT_RIME_DECOUPLING_2026-10-16) |
| 去掉 Fcitx 原生后端 | [后端清理](./DIRECT_RIME_CLEANUP_2026-10-17) · [迁移审计](./DIRECT_RIME_AUDIT_2026-10-17) · [无调用方审阅](./DIRECT_RIME_UNUSED_CODE_AUDIT_2026-10-17) |
| 输入、候选与性能 | [首键和会话](./DIRECT_RIME_RESPONSIVENESS_2026-10-18) · [首次候选点选](./DIRECT_RIME_CANDIDATE_TAP_2026-10-18) · [触摸与画面对照](./DIRECT_RIME_SCREEN_PERF_2026-10-17) |
| 首键、候选点选与 PR #32 | [万象与旧 Fcitx 复测](../perf/wanxiang-2026-11-08/) · [首键与点选调查](../perf/wanxiang-2026-11-08/supplement-2026-11-11/investigation-2026-11-11/) · [候选布局与按键发布](../perf/candidate-latency-fix-2026-11-12/) · [输入链路复核](../perf/current-input-pipeline-2026-11-13/) · [RenderThread 等待](../perf/render-wait-2026-11-14/) · [PR #32 整合](../perf/pr32-integration-2026-11-14/) |
| 直连后端职责拆分 | [后端审查核对](./direct-rime-backend-review-2026-11-12) · [职责拆分性能对照](./direct-rime-refactor-performance-2026-11-14) |
| 万象长句与输入跟手度 | [CyIME 同方案与冷缓存](../perf/cyime-wanxiang-2026-11-21/) · [气泡、输入调度与长句 CPU](../perf/input-fluency-2026-11-22/) |
| 引擎来源与产品交付 | [定制源码维护](./DIRECT_RIME_SOURCE_MAINTENANCE_2026-10-21) · [引擎更新](./DIRECT_RIME_ENGINE_UPDATE_2026-10-21) · [合入交付](./DIRECT_RIME_RELEASE_READY_2026-10-21) |
| 合入后的审阅与补充 | [旧报告逐项对照](./DIRECT_RIME_LEGACY_REVIEW_COMPARISON_2026-10-24) · [10 月 27 日记录](./review-2026-10) · [10 月 30 日记录](./review-2026-10-30) |

## 所有直连版阶段报告

最新补充：[2026-11-22 按键气泡、输入调度与 Rime 长句 CPU](../perf/input-fluency-2026-11-22/)，其中三个提交已以 `e1c5ec3e`、`8697a7eb`、`b4670127` 合入默认分支，现行顺序约束见[直连架构](/maintainer/implementation/direct-rime-architecture)；前一轮为[2026-11-21 CyIME 同方案与冷缓存](../perf/cyime-wanxiang-2026-11-21/)。直连后端拆分见[2026-11-12 审查核对](./direct-rime-backend-review-2026-11-12)与[2026-11-14 性能对照](./direct-rime-refactor-performance-2026-11-14)。

更早有[2026-11-10 输入链路可靠性与局部优化](./input-reliability-2026-11-10)，其实施依据来自[2026-11-06 直连层正确性、性能与同类项目审阅](./direct-rime-review-2026-11-06)及后续输入链路评估；此前的[2026-10-31 设置、主题、网页与文档审阅修复](./review-fixes-2026-10-31)继续保留。

下面的目录包括全部迁移后的报告。正文、原始附件及旧路径均可追溯；[验证与性能原始资料](/maintainer/evidence)单独索引。

<DocsCatalog scope="history" />
