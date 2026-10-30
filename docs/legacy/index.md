# 两代项目与历史档案

**中州鹅**是现在维护的 Rime 直连版。**靓企鹅中州韵**（旧文档亦写作“靓企鹅·中州韵”）是保留 Fcitx 中间层的历史 Rime 专版。

| 项目 | 中州鹅 | 靓企鹅中州韵 |
| --- | --- | --- |
| 引擎链路 | Kotlin → JNI → librime | Android 前端 → Fcitx 层 → Rime |
| 文档用途 | 目前功能、操作与维护 | 历史功能、设计、审阅和迁移追溯 |
| 代码定位 | 默认分支 `fx-rime-only` | 归档标签 `v0-archive-fcitx-before-direct-20261019` |
| 用户入口 | [当前用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) | [旧版用户手册](/legacy/fcitx/manual/RIME_ONLY_USER_GUIDE_zh-CN) |
| 维护入口 | [现行维护](/maintainer/) · [直连版历史](/maintainer/history/) | [原站维护报告](/legacy/fcitx/maintainer/) |

旧版曾经也使用 `fx-rime-only` 分支名，包名也可能与 Release 相同；**“只支持 Rime”不等于“已经移除 Fcitx”**。判断资料要看架构、日期、代码提交与归档来源。

## 靓企鹅中州韵：原站快照

原站的 **64 篇文档、其中 13 篇维护报告**完整保留。固定来源为 `docs@edcf536f`，原始源码 ZIP 与恢复校验清单也保留；此数量指原站快照，不包括后来新增的索引与批注。

- [原站完整目录](/legacy/fcitx/contents)：按原站栏目阅读。
- [原站维护报告](/legacy/fcitx/maintainer/)：交接、审阅、性能、MD3 方案和早期可行性分析。
- [原站首页](/legacy/fcitx/)：保留当时的产品介绍。
- [原始源码快照](/legacy/fcitx/source-snapshot-edcf536f.zip) · [恢复校验清单](/legacy/fcitx/restoration-manifest.json)。

## 旧版批注和原始方案

此前混在当前维护区的旧版手册、带后续批注的报告，以及另存的可行性原文，现在集中到[批注与方案目录](/legacy/fcitx/notes/)。
这些版本可能与冻结原站存在差异，因此分别保留，不覆盖原站快照，也不把它们当作中州鹅现行维护说明。

## 如何继续阅读或迁移

使用新版本请从[安装与升级](/guide/installation)和[迁移说明](/guide/migrate-from-upstream)开始。追溯“为什么改成直连”请看[中州鹅研发历史](/maintainer/history/)。
本次整理保留旧地址跳转及原章节片段；按原文件名查找可使用[全站目录](/contents)。
