# 中州鹅直连版文档

适用于 `feat/direct-rime-prototype`，包名 `io.github.sandyyur.rime.direct`。
本目录随代码分支维护；默认分支和旧文档站的 Fcitx 版说明属于另一条发布线。

| 需要做什么 | 从这里开始 |
| --- | --- |
| 安装、导入万象、中英文切换、调整键盘、备份与排错 | [用户手册](manual/DIRECT_RIME_USER_GUIDE_zh-CN.md) |
| 编译、更新 librime、验证、维护性能测试 | [维护手册](maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md) |
| 查看本会话版本、安装包、CI 与已完成/待完成事项 | [本会话交付索引](maintainer/DIRECT_RIME_SESSION_2026-10-17.md) |
| 查看工作区、临时分支及 Actions 记录处理原则 | [工作区与分支整理](maintainer/DIRECT_RIME_WORKSPACE_CLEANUP_2026-10-17.md) |

## 实施与验证历史

以下文档保留对应提交时点的事实。日常操作以当前用户/维护手册为准，不能把首轮诊断 APK 的限制当成当前主应用的功能。

| 阶段 | 记录 |
| --- | --- |
| 独立直连引擎原型 | [实现与验证](maintainer/DIRECT_RIME_IMPLEMENTATION_2026-10-16.md) |
| 完整键盘、设置、布局编辑器接入 | [前端接入](maintainer/DIRECT_RIME_FRONTEND_2026-10-16.md) |
| 直连版本之间的依赖与性能调整 | [解耦记录](maintainer/DIRECT_RIME_DECOUPLING_2026-10-16.md) |
| 含 Fcitx 默认分支与直连的万象对照 | [迁移与性能审计](maintainer/DIRECT_RIME_AUDIT_2026-10-17.md) |
| 用户工具包、万象 base + 模型的触摸与画面对照 | [触摸与录屏报告](maintainer/DIRECT_RIME_SCREEN_PERF_2026-10-17.md) |
| 早期 Muse 使用分支的代码借鉴与冷启动风险 | [旧原型性能审查](maintainer/DIRECT_RIME_PROTO_PERFORMANCE_REVIEW_2026-10-18.md) |
| 用户真机日志的版本、重绑时间线与卡顿定位范围 | [日志补充分析](maintainer/DIRECT_RIME_USER_LOG_REVIEW_2026-10-18.md) |
| 首键等待、同应用会话复用与立即点选验证 | [响应性改进](maintainer/DIRECT_RIME_RESPONSIVENESS_2026-10-18.md) |
| 原 Fcitx 也有首次点选成本；缓存修正、独立编辑器与 trace 定位 | [首次点选对照](maintainer/DIRECT_RIME_CANDIDATE_TAP_2026-10-18.md) |
| Foxy 上传 APK 的输入链路、候选补取、Release 与 ART 对照 | [Foxy 实现审查](maintainer/DIRECT_RIME_FOXY_REVIEW_2026-10-18.md) |
| Foxy 审查后的非输入框会话、候选分批、JNI 与 Release 修复 | [修复及同条件验证](maintainer/DIRECT_RIME_FOXY_FIXES_2026-10-18.md) |
| 新日志中的 145ms 选词、同步 IO 定点诊断、连续导出空文件修复 | [冷路径与日志导出](maintainer/DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19.md) |
| 删除旧原生后端、构建工程和子模块 | [后端清理](maintainer/DIRECT_RIME_CLEANUP_2026-10-17.md) |
| 删除只被自测引用的工具和其他无调用方代码 | [无调用方审计](maintainer/DIRECT_RIME_UNUSED_CODE_AUDIT_2026-10-17.md) |

性能原始数据在 [maintainer/perf/](maintainer/perf/)，各阶段的 JSON 验证记录保留在维护文档旁。
原始设想已随本分支归档：[2026-10-07 再评估](maintainer/archive/DIRECT_RIME_REEVALUATION_2026-10-07.md)
和 [2026-10-02 可行性原文](maintainer/archive/ARCHIVE_DIRECT_RIME_FEASIBILITY_ORIGINAL_2026-10-02.md)；它们是历史方案，不是当前操作手册。
