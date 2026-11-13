# 实现协议与数据处理

这里维护随当前直连版演进的中文实现说明，供排查兼容问题、修改代码和对接服务时查阅。

| 主题 | 内容 |
| --- | --- |
| [Rime 直连架构与职责](direct-rime-architecture.md) | owner、生命周期、按键/事件链路、候选目标、维护和缓存边界 |
| [正确性约束与代码定位](correctness-contracts.md) | 输入队列、前端渲染、布局兼容、数据保护与症状定位 |
| [ClipCascade 接收限额](clipcascade-receive-limits.md) | WebSocket 帧、分片、堆预算、TLS 与代理兼容性 |
| [自定义 hosts 下载任务](hosts-downloads.md) | 持久任务、断点续传、取消和 MediaStore 发布恢复 |
| [加密备份格式](protected-backup-format.md) | `.fcbak` 文件头、密钥派生、分块认证与旧 ZIP 兼容 |
| [整包恢复事务](user-data-restore.md) | 独立恢复进程、目录所有权、恢复日志与偏好／WAL 处理 |
| [语音 AIDL 接入协议](https://github.com/SandyYuR/fcitx5-android/blob/fx-rime-only/ASR_VOICE_INPUT_AIDL_INTEGRATION.md) | 随源码维护的接口契约；浮窗回退须遵守会话令牌约定 |

## 文档放在哪里

- 用户操作说明放在[功能页](../../features/overview.md)和[用户手册](../../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)。
- 当前实现说明放在本目录。代码仓库的 README 提供入口，避免两份完整说明独立更新。
- AIDL 等需要随源码同步的接入契约保留在代码仓库；本站说明使用方式并链接到契约。
- 对照表、原始日志、截图和固定提交的验收结果放在 `maintainer/audit/`，保留当时语言、失败现场和来源 SHA。
- 旧版文档和历史报告保持原文，不能把历史包的结果改记到新的源码提交上。

同步接收、hosts 下载、加密备份与恢复事务四篇说明最初由 perf 分支的英文根目录文件迁入并译为中文。原英文快照仍随各批审计记录保留；本目录继续收录现行协议和正确性约束。
当前代码与文档对应关系见[2026-11-05 核对记录](/maintainer/changes/2026-11-05)，此前完整基线见[2026-10-30 核对](/maintainer/changes/)，引擎构建与发布见[引擎与依赖更新](/maintainer/engine-update)，完整分类见[维护索引](../index.md)。


## 语音插件宿主

[JS 语音宿主与开发接口](./js-voice-plugins)：注册、设置字段、回调、二进制、HMAC、逐插件授权、资源预算及 stub 契约。用户安装步骤独立放在[JS 插件指南](/features/voice-input-js)。

近期复核：[首个 i 分发等待与候选性能补丁撤回](../perf/input-dispatch-2026-11-12/index.md)，包含固定节拍协议、原始数据和当前代码范围。

后续复核：[PR #32 独立发键与实际触摸时长](../perf/pr32-phase-review-2026-11-12/index.md)，保留正式对照、点选风险和采样修复。

收窄验收：[PR #32 字体修复反向验证与两版对照](../perf/pr32-font-confirmation-2026-11-13/index.md)，记录撤下快路径后的字宽复现、48 个新进程与首次点选风险。

旧优化重审：[PR #32 候选分配与发布优先级重新对照](../perf/pr32-reviewed-reapplication-2026-11-13/index.md)，分开保存第一版筛查、Paint 字段边界修正及修正版独立验证，按事前门槛决定是否采用。

整体链路复核：[当前默认版候选分发与按需选字对照](../perf/current-input-pipeline-2026-11-13/index.md)，以含新增引擎管理的实际默认版为基线，记录两项前端改动、48 个新进程对照和独立绘制追踪；有冷态改善迹象，尚未满足稳定提速门槛，代码保留在独立审阅分支。

绘制等待追查：[首键与 RenderThread 的前后帧、图形缓存复核](../perf/render-wait-2026-11-14/index.md)，定位主线程被前一帧 EGL 同步挡住的机制；默认版也有同类长帧，记录模拟器缓存限制、未形成有效干预的补充试验和追踪工具修正，不据此宣布应用提速。
