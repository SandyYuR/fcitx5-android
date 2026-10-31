# 中州鹅直连版历史

这里记录从 librime 原型到完整主应用的演进，以及后续审阅、合入和功能修订。涉及 Fcitx 的对照实验仍属于**直连版研发记录**；保留 Fcitx 层版本自身的手册和报告另在[靓企鹅中州韵档案](/legacy/)。

## 按演进路线阅读

| 阶段 | 记录 |
| --- | --- |
| 原型与完整前端接入 | [原型实现](./DIRECT_RIME_IMPLEMENTATION_2026-10-16) · [原界面接入](./DIRECT_RIME_FRONTEND_2026-10-16) · [依赖解耦](./DIRECT_RIME_DECOUPLING_2026-10-16) |
| 去掉 Fcitx 原生后端 | [后端清理](./DIRECT_RIME_CLEANUP_2026-10-17) · [迁移审计](./DIRECT_RIME_AUDIT_2026-10-17) · [无调用方审阅](./DIRECT_RIME_UNUSED_CODE_AUDIT_2026-10-17) |
| 输入、候选与性能 | [首键和会话](./DIRECT_RIME_RESPONSIVENESS_2026-10-18) · [首次候选点选](./DIRECT_RIME_CANDIDATE_TAP_2026-10-18) · [触摸与画面对照](./DIRECT_RIME_SCREEN_PERF_2026-10-17) |
| 引擎来源与产品交付 | [定制源码维护](./DIRECT_RIME_SOURCE_MAINTENANCE_2026-10-21) · [引擎更新](./DIRECT_RIME_ENGINE_UPDATE_2026-10-21) · [合入交付](./DIRECT_RIME_RELEASE_READY_2026-10-21) |
| 合入后的审阅与补充 | [旧报告逐项对照](./DIRECT_RIME_LEGACY_REVIEW_COMPARISON_2026-10-24) · [10 月 27 日记录](./review-2026-10) · [10 月 30 日记录](./review-2026-10-30) |

## 所有直连版阶段报告

最新补充：[2026-10-31 设置、主题、网页与文档审阅修复](./review-fixes-2026-10-31)。

下面的目录包括全部迁移后的报告。正文、原始附件及旧路径均可追溯；[验证与性能原始资料](/maintainer/evidence)单独索引。

<DocsCatalog scope="history" />
