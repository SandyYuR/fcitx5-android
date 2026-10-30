# 中文文档归并与合入验证 · 2026-10-27

本批当前代码为 `710e4ef93f20eaf1d80d93913be1edfaefdb097d`，代码 PR #13 面向 `fx-rime-only`，文档 PR #12 面向 `docs`。
分类范围和合入顺序见[本批合入准备](/maintainer/history/review-2026-10)。

## 文档归并核对

四篇英文说明已译入[中文实现说明](../../implementation/index.md)，不再放在代码根目录维护。
逐篇核对确认，原英文正文与对应历史审计快照逐字节一致，原始内容没有丢失。
中文译文提供当前阅读入口，原始英文快照继续用于追溯当时的实现和验收。
[迁移路径、摘要及源码差异](documentation-migration.json)。

与前一代码提交 `9c9013ca` 相比，只改动 README、四篇说明的存放位置及普通 PR 的 `BUILD_ABI=arm64-v8a`。
应用、引擎、工具、测试和构建逻辑源码未变。
历史代码及文档分别保存在 `backup/perf-before-docs-zh-20261027` 和 `backup/docs-before-merge-prep-20261027`。

## 当前代码验证

- 当前提交的完整应用 JVM 报告已下载并核对归档摘要：1234 项，零失败、零跳过。[统计及来源](ci-jvm-summary.json)。
- [PR CI 40509320725](https://github.com/SandyYuR/fcitx5-android/actions/runs/40509320725) 全绿：应用 JVM 与 ARM64 Debug 通过。检查对象是临时合并提交 `e22d1123`，源码分支为 `710e4ef9`；[Debug 包实际校验](pr-debug-apk-verification.json)。
- [Commit CI 40509323655](https://github.com/SandyYuR/fcitx5-android/actions/runs/40509323655) 全绿：当前源码的完整 JVM 与签名 ARM64 Release 通过；[工作流和产物摘要](ci-summary.json)。
- [签名 ARM64 APK](https://github.com/SandyYuR/fcitx5-android/actions/runs/40509323655/artifacts/12736668812) 仅直连 Rime/C++ 原生库、2 个 dex、16 KiB 对齐；[实际校验输出](arm64-apk-verification.json)。

构建逻辑的六项本地回归及两项 API35 行标题栏设备用例来自前一提交 `9c9013ca`，来源见[上一轮原始记录](../perf-rebase-2026-10-27/index.md)。
应用与测试源码内容一致，但没有把旧装机记录改标为新提交运行结果。

## 文档与验收范围

当前中文指南、维护入口、导航与历史恢复文件通过文档构建和链接检查：208 页、21182 个链接、169 个资产链接，零错误。
旧版原站 83 份原始文件按归档清单校验，保持原文。
旧输入、提示、关机与恢复设备证据保留实际 SHA；无 KVM 模拟器不用于声称手机性能。
