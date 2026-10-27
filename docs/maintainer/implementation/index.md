# 实现协议与数据处理

这里维护随当前直连版演进的中文实现说明，供排查兼容问题、修改代码和对接服务时查阅。

| 主题 | 内容 |
| --- | --- |
| [ClipCascade 接收限额](clipcascade-receive-limits.md) | WebSocket 帧、分片、堆预算、TLS 与代理兼容性 |
| [自定义 hosts 下载任务](hosts-downloads.md) | 持久任务、断点续传、取消和 MediaStore 发布恢复 |
| [加密备份格式](protected-backup-format.md) | `.fcbak` 文件头、密钥派生、分块认证与旧 ZIP 兼容 |
| [整包恢复事务](user-data-restore.md) | 独立恢复进程、目录所有权、恢复日志与偏好／WAL 处理 |
| [语音 AIDL 接入协议](https://github.com/SandyYuR/fcitx5-android/blob/fx-rime-only/ASR_VOICE_INPUT_AIDL_INTEGRATION.md) | 随源码维护的接口契约；本批新增的浮窗会话令牌见代码 PR |

## 文档放在哪里

- 用户操作说明放在[功能页](../../features/overview.md)和[用户手册](../../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)。
- 当前实现说明放在本目录。代码仓库的 README 提供入口，避免两份完整说明独立更新。
- AIDL 等需要随源码同步的接入契约保留在代码仓库；本站说明使用方式并链接到契约。
- 对照表、原始日志、截图和固定提交的验收结果放在 `maintainer/audit/`，保留当时语言、失败现场和来源 SHA。
- 旧版文档和历史报告保持原文，不能把历史包的结果改记到新的源码提交上。

本目录四篇说明由 perf 分支原有的英文根目录文件迁入并译为中文。原英文快照仍随各批审计记录保留。
本批的代码与文档状态见[合入准备](../review-2026-10.md)，完整分类见[维护索引](../index.md)。
