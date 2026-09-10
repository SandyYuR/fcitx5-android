# 靓企鹅·中州韵（Rime-only）· 文档分支

> **`rime-docs`** 是文档专用**孤儿分支**（与代码分支无共同历史，只含文档文件）。
> 代码、构建与 CI 以 [`fx-rime-only`](https://github.com/SandyYuR/fcitx5-android/tree/fx-rime-only) 分支为准，项目主页见该分支 README。

## 文档索引

- [交接文档](docs/HANDOVER-rime-only.md) —— 项目历史、rime 引擎更新 runbook、故障经验；信息密度最高，建议从这份读起
- [Rime-only 简体中文用户指南](docs/RIME_ONLY_USER_GUIDE_zh-CN.md) —— 安装、配置、迁移与 FAQ
- [代码审阅报告（2026-09-07）](docs/CODE_REVIEW_REPORT_2026-09-07.md) —— 风险清单与验证建议
- [性能审阅报告（2026-09-07）](docs/PERFORMANCE_REVIEW_REPORT_2026-09-07.md) —— 热点分析与测量门控
- [Rime 整合实施方案](docs/rime-integration-plan.md) —— 历史设计文档
- [Rime 专用化可行性报告](docs/rime-only-feasibility.md) —— 历史设计文档

## 关于历史

2026-09-10 文档分拆：此前文档与代码混在 `fx-rime-only`，分拆时该分支已重写为纯代码历史（移除全部文档提交）。同日晚些时候又做了**提交标题重写**：全部标题统一为 `类型(模块): 内容` 格式，19 组同模块同类型的相邻提交合并，源码零改动——逐提交明细直接看 `fx-rime-only` 的 `git log --oneline`。

**分拆前的完整历史**（184 个提交，含全部代码与文档提交，tip `b3da998e`）保存在 tag [`archive/pre-doc-split`](../../tree/archive/pre-doc-split)。交接文档中引用的旧提交 SHA 多属该 tag 下的历史；`fx-rime-only` 当前提交以实测为准，旧→新追溯方式见交接文档第 2 节「历史 SHA 的三个纪元」。

文档改动直接提交到本分支。
