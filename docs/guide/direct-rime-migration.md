# 直连版迁移与当前状态

本站当前手册对应已通过 [PR #4](https://github.com/SandyYuR/fcitx5-android/pull/4) 合入默认分支的“中州鹅”直连 librime 版本。
默认分支 `fx-rime-only` 已使用直连后端；历史 Fcitx 版本由归档标签保留。文档站在独立的 `docs` 分支维护。

| 需要核对 | 入口 |
| --- | --- |
| 当前基线、签名包、CI 与合入顺序 | [合并交付](/maintainer/DIRECT_RIME_RELEASE_READY_2026-10-21) |
| 安装、导入万象、迁移与用户词库 | [用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) |
| librime 更新、构建、依赖与性能复现 | [维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN) |
| 历史性能与源码证据 | [直连文档索引](/direct-rime) |
| 旧 Fcitx 版操作记录 | [历史用户手册](/manual/RIME_ONLY_USER_GUIDE_zh-CN) |

共享 MD3 界面已独立合入默认分支 `579b1632`，不属于直连引擎独有功能。
中州鹅继续保留原 Android 键盘、编辑器、主题和剪贴板功能，删除原生 Fcitx 后端。

依赖、文档与代码已按交付记录依次合入；默认分支 Nightly 的实际源码、签名和补丁来源见该记录。
旧报告保留时间、源码和模型边界，不能把不同轮次的数据当作本轮提速结论。
