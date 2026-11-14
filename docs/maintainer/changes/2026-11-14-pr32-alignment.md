# 2026-11-14 PR #32 默认分支与协作指引对齐

本轮核对远端代码、文档分支和最近的协作指引，将 PR #32 的长期维护约束补入 `AGENTS.md`，并修正文档中的旧架构描述。本轮没有新增运行时优化，也没有重新进行正式性能对照；此前的性能条件、失败样本和不利结果继续保留。

## 来源与变基结果

| 项目 | 核对值 |
| --- | --- |
| GitHub 默认分支 | `fx-rime-only` |
| 代码远端基线 | `0f3e334350c183eb4699ef7b7f74d676efc44056`，已合入后端职责拆分 #35 |
| 代码 PR #32 上轮头 | `aafc6d583f2096bc26ae621d7cc18bd627ae22d2` |
| 本轮代码头 | `4a87997ac115badf407dc741ffb8d124879a665b` |
| 文档远端基线 | `9337c8c6a1f5cc61a2f2eba56d766bf985c0c539`，已合入配套文档 #36 |
| 文档 PR #31 变基前头 | `30c880b1df5a892ae151415370e52430561cf3e8` |
| 文档原 20 个提交重放后的头 | `bed42f0d8434ff68b59500ac871f4748567d2c38`，不含本页等后续修订 |

以 Git 实时引用、GitHub 默认分支设置和提交 API 交叉核对，代码 PR 已包含最新默认分支，代码变基检查为 up to date，没有需要手工解决的代码冲突。默认分支上的 #35 状态归属和此前 JS、引擎管理改动均保留。PR 后续状态应重新查询远端，不能把本次记录当作未来的最新值。

文档分支确有更新：原 20 个提交已重放到最新 `docs`，逐项 `range-diff` 全部相等。手工更新架构说明前，重放结果的内容树 `545529eff1c4268f30bab9a366d9fe15f3bf23c7` 与 Git 自动合并预览一致。两条默认分支均未由本任务推送。

## AGENTS.md 的修订范围

默认分支最近两次相关更新是 `0380d70f` 的指引精简和 `0f3e3343` 的职责拆分。本轮保留其入口与 Backend／PublicationState 状态归属，按 [AGENTS.md 官方说明](https://agents.md/)及 [OpenAI 编写指南](https://learn.chatgpt.com/docs/agent-configuration/agents-md)使用普通 Markdown、实际路径、可执行命令和长期约束，不引入额外的必填模板，也不把日期、临时分支、CI 计数或性能结论写入指引。

| 补充内容 | 对应依据 |
| --- | --- |
| 分页候选按 `PagedCandidateReceiver` 能力向每个接收方分发一种表示 | PR #32 的真实协议，避免重新引入重复绑定或超时猜测 |
| 普通绑定延后字形簇拆分；字体引用变化使测量和绘制变换缓存失效 | 本 PR 的候选行为和字体正确性边界，保留正文、注释样式及选择目标 |
| 运行时引擎入口与内置 SDK 锁分开说明 | 最近的引擎管理功能，保留来源、授权、profile 绑定及重启／回退 |
| JS 工具按稳定插件／App ID 解析当前来源，来源变化使旧会话和授权失效 | 最近的 JS 工具功能，保留编辑器有效性和宿主密钥边界 |
| 候选、JS、采样工具的实际验证入口 | 沿用 JVM、Debug、bundled 契约和专用 AndroidTest，避免只凭构建成功验收 |
| 固定源码与 APK 等输入身份、实际触摸时长、多种时钟和 trace 范围 | 本 PR 可复用采样工具的协议，保留失败／不利样本，不把缺失覆盖计为零 CPU |

本轮 `AGENTS.md` 为 8,771 字节。相对 `aafc6d58` 仅此文件改变；应用、native、SDK、依赖、工作流和测试源码均未变化。现行[架构页](../implementation/direct-rime-architecture.md)同时更新 Backend 与职责组件的边界，并去掉已撤回的候选纯字符串／高亮快路径描述。PR #32 尚未合入的规则在正文中明确标识。

## 对当前代码头的验证

以下均对应 `4a87997a`，没有复用上轮 CI 的通过状态。CI 的合并预览为 `25897796eaf4094569c1e5fa85084a44c90c4e78`，与 PR 头的内容树完全相同。

| 检查 | 结果 |
| --- | --- |
| [PR CI 43560767674](https://github.com/SandyYuR/fcitx5-android/actions/runs/43560767674) | JVM 与 Debug 两个任务成功，包含 lint、探针编译及 APK 检查 |
| [Direct Rime CI 43560767684](https://github.com/SandyYuR/fcitx5-android/actions/runs/43560767684) | 原生契约、真实引擎、完整前端设备与 ARM64 四个任务成功 |
| 应用 JVM 原 XML | 283 份报告、2,327 项测试，失败／错误／跳过均为 0 |
| 前端设备原 XML | 60 项，逐项与既有设备用例清单匹配，失败／错误／跳过均为 0 |
| 引擎设备 smoke 原 XML | 6 项，失败／错误／跳过均为 0 |
| 本地采样工具单测 | 109 项通过 |
| ARM64 APK | 本地复核白名单、16 KiB 对齐及打包 native 摘要；与本次链接审计匹配，无 Fcitx C++ 符号 |

三个源 artifact 均整包下载，核对 GitHub 的字节数、SHA-256 与 ZIP CRC；归档保留全部 JUnit XML 和 native 链接审计，APK 摘要及原 artifact 身份独立记录。本轮为功能和构建验证，未安装到用户手机，也未执行 ARM Release 或新的性能对照。

## 文档与证据

变基后既有 14 组校验清单、2,312 条校验记录保持不变；旧报告、原始日志和来源 SHA 没有被改写。文档构建、链接、旧站完整性、目录和搜索五项检查通过：262 篇正文、45 个保留路由、3,053 个下载附件；314 个页面的链接检查错误为 0。旧站 64 页、13 份维护报告、83 份原始文件及源归档校验通过。目录与搜索检查错误均为 0，`git diff --check` 通过。

Chromium 检查桌面及 390px 页面，正文没有整页横向溢出，现行架构页与实现索引入口可达；逐一下载本轮 12 份附件，与源文件 SHA-256 一致，浏览器脚本错误为 0。

原件与复核入口：

- [远端引用与代码包含关系](../audit/pr32-alignment-2026-11-14/remote-check.json)
- [对齐范围及内容树](../audit/pr32-alignment-2026-11-14/alignment-scope.json)、[20 个文档提交对应关系](../audit/pr32-alignment-2026-11-14/docs-range-diff.txt)
- [AGENTS 修改补丁](../audit/pr32-alignment-2026-11-14/agents.patch.zip)、[完整指引快照](../audit/pr32-alignment-2026-11-14/AGENTS.snapshot.txt)
- [CI 身份和任务](../audit/pr32-alignment-2026-11-14/ci-records.json)、[测试与 APK 汇总](../audit/pr32-alignment-2026-11-14/ci-validation.json)
- [原 XML 与 native 审计 ZIP](../audit/pr32-alignment-2026-11-14/ci-records.zip)、[源 artifact 下载校验](../audit/pr32-alignment-2026-11-14/download-validation.json)、[APK 白名单复核](../audit/pr32-alignment-2026-11-14/apk-verification.json)
- [109 项工具测试](../audit/pr32-alignment-2026-11-14/tool-tests.txt)、[此前证据完整性](../audit/pr32-alignment-2026-11-14/previous-evidence-validation.json)
- [本轮附件校验清单](../audit/pr32-alignment-2026-11-14/checksums.sha256.txt)

此前整合、失败与分支清理过程见[整合记录](../perf/pr32-integration-2026-11-14/index.md)，正式样本的来源及适用范围仍以各自原报告为准。代码 PR #32 继续草稿，配套文档在 PR #31 审阅；本次没有合入或发布。
