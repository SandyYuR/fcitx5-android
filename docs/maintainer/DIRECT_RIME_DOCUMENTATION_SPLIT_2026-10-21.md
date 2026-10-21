# 代码与文档分支分离（2026-10-21）

用户手册、维护手册、历史设计、性能报告、截图与原始验证证据统一保存在 `docs` 分支，由文档站 PR #7 交付。
代码 PR #4 删除整个 `docs/` 目录，README 提供文档入口；构建工具随附的 README 继续随工具维护。

## 提交与来源

代码 PR #4 基于默认分支最新的 `c5d4f80abf10be875b59a332c2145ecbb6f3b2ac`，
该提交将符号面板最近记录由三行扩大至十行；同时承接 `b6810693` 的语音 partial 修复。以 rebase 保持基线，功能分支无 merge 提交。
代码保留五类提交：直连后端与完整前端、引擎与源码来源、连续日志导出修复、CI 与构建交付、README。
最后一个提交仅修改根 README，并删除默认分支遗留的 MD3 合入文档及其 `docs/` 目录。
该 MD3 文档统一保存在文档分支[维护记录](MD3_UI_EXTRACTION_2026-10-21.md)。
不再把手册、历史报告与原始测量数据作为代码 PR 的独立文档提交。

迁移前代码为 `61588f8b376d25d995014be7e92d25dd1672bf8c`，文档站为 `3230bd6fe993e88a1941b069ffa85d524efe3809`。
两者分别保留归档标签
[v0-archive-direct-rime-before-docs-split-20261021](https://github.com/SandyYuR/fcitx5-android/tree/v0-archive-direct-rime-before-docs-split-20261021) 与
[v0-archive-direct-rime-docs-before-split-20261021](https://github.com/SandyYuR/fcitx5-android/tree/v0-archive-direct-rime-docs-before-split-20261021)。

## 迁移核对

代码原有 492 个文档与证据文件，其中 446 个此前未收录的文件原样补入文档分支。
458 个非 Markdown 原件逐字节核对相同（需更新的旧版 rebase 交付摘要单独保存到 `archive/`）；已收录的 Markdown 保留 VitePress 链接和锚点适配。
原代码 `docs/README.md` 对应文档站 `docs/direct-rime.md`。逐文件 SHA256 与映射见
[迁移清单](direct-rime-documentation-split-2026-10-21.json)。

历史报告中的固定代码提交和性能测量来源继续保留；不会将旧 APK 或测量改标为新源码结果。
原始测试证据在文档仓库的 `docs/maintainer/perf/` 和 `docs/maintainer/audit/` 内，
仓库原始文件不需要全部转换为站点页面。

## 链接与发布

README、应用“关于”的用户与维护手册入口、Nightly 发版说明和候选性能工具说明改为文档分支链接。
应用自身源码链接仍固定到 APK 的实际构建提交。输入、候选、用户库与引擎代码没有修改。

合入顺序调整为：prebuilder #1 → prebuilt #1 → 文档 #7 并确认部署 → 代码 #4 → 核对默认分支签名 Nightly。
这样发布应用时，文档分支上的手册已经可访问。所有 PR 继续采用 **Rebase and merge**。
本轮只准备 PR，不执行实际合并或部署。
