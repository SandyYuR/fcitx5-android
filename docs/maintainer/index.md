# 维护文档索引

## 当前修复批次

| 要查看的内容 | 入口 |
| --- | --- |
| 最新默认分支审阅、fix 修复、验证与本次合入 | [2026 年 10 月 30 日合入记录](review-2026-10-30.md) |
| 前一批代码与文档 PR、分类改动和合入记录 | [2026 年 10 月 27 日合入准备](review-2026-10.md) |
| 当前构建、中文文档迁移与来源核对 | [本次合入验证](audit/merge-prep-2026-10-27/index.md) |
| 两份旧 Fcitx 审阅报告在直连版的全部 139 项状态 | [逐项对照表](DIRECT_RIME_LEGACY_REVIEW_COMPARISON_2026-10-24.md) |
| 同步、下载、加密备份与整包恢复的当前协议 | [中文实现说明](implementation/index.md) |
| 编译、CI、librime 更新和设备诊断 | [维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md) |
| Windows 本机工具链、环境坑与子模块恢复 | [Windows 本机构建环境](LOCAL_BUILD_ENVIRONMENT_zh-CN.md) |

用户操作请先查看[用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)、[备份与恢复](../features/backup-restore.md)、[外部语音输入](../features/voice-input.md)和[在线编辑器](../features/online-editor.md)。

## 验证记录按主题查找

下表链接的是固定提交时点的原始记录；当前源码、当前构建与旧设备结果的关系，以本批合入准备中的范围说明为准。

| 分类 | 记录 |
| --- | --- |
| 输入与候选 | [直连触屏基线](audit/direct-rime-2026-10-25/index.md)、[旧布局回调](audit/queued-layout-2026-10-25/index.md)、[菜单与窗口焦点](audit/sync-candidates-2026-10-25/index.md)、[提示绘制确认](audit/status-hints-2026-10-27/index.md) |
| 编辑器 | [保存和取消](audit/editor-saves-2026-10-25/index.md)、[页面返回与重建](audit/editor-results-2026-10-26/index.md)、[草稿一致性](audit/draft-persistence-2026-10-26/index.md) |
| 剪贴板与传输 | [ClearURLs](audit/clearurls-2026-10-25/index.md)、[WebSocket 接收限额](audit/websocket-receive-2026-10-26/index.md) |
| 更新下载 | [任务持久化与发布恢复](audit/hosts-downloads-2026-10-27/index.md) |
| 备份及恢复 | [剩余清单、语音、HTTPS 与加密备份](audit/remaining-checklist-2026-10-26/index.md)、[跨进程恢复、偏好与 WAL](audit/user-data-restore-2026-10-27/index.md) |
| 关机持久化 | [学习词典与新内核读回](audit/native-shutdown-2026-10-27/index.md) |
| 分支整理 | [perf 原提交映射、行标题栏与新回归](audit/perf-rebase-2026-10-27/index.md) |

原始日志、JSON、截图和测试失败保留在对应目录，正文提供来源与解释。
软件模拟器的功能结果不作为真机性能数据；物理断电、真实卷拔插等范围没有验收时不会标为通过。

## 历史与文档组织

- [代码与文档分离记录](DIRECT_RIME_DOCUMENTATION_SPLIT_2026-10-21.md)：迁移清单、固定来源与当前存放约定。
- [完整直连历史索引](../direct-rime.md)：引擎实现、前端接入、性能和依赖演进。
- [旧版 Fcitx 文档专区](../legacy/fcitx/contents.md)：原站 64 篇文档与 13 篇维护报告，保持历史原文。

当前功能说明集中在功能页，当前实现协议集中在 `implementation/`，历史证据集中在 `audit/`。
新增说明优先写入对应栏目，避免在代码仓库根目录或多个历史报告里维护完整副本。
