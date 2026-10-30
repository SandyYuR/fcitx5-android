# 直连版现状与迁移

中州鹅的默认分支 `fx-rime-only` 直接使用 librime，保留 Android 键盘、候选、主题、布局编辑器和常用工具。旧 Fcitx 原生后端已经移除，文档站独立维护在 `docs` 分支。

## 应该阅读哪套文档

| 目的 | 入口 |
| --- | --- |
| 现在能做什么 | [功能总览](/features/overview) |
| 安装、方案、布局和数据 | [当前用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) |
| 当前构建、CI、依赖来源 | [维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN) |
| 本次核对的默认分支提交 | [代码与文档对照](/maintainer/changes/) |
| 当年怎样完成直连迁移 | [中州鹅历史](/maintainer/history/) |
| 旧版靓企鹅中州韵的操作 | [含 Fcitx 的历史用户手册](/legacy/fcitx/manual/RIME_ONLY_USER_GUIDE_zh-CN) |

历史交付页说明当时的包和验证，不代表正在发布的最新 APK。安装时核对 Release 或成功 CI 的源码提交、包名及签名，参见[安装与升级](/guide/installation)。

## 迁移与数据

Release 使用 `org.fcitx.fcitx5.android.fx.rime`，同包名同签名且版本允许时可以覆盖旧 Fcitx 版。先在旧版备份，升级后核对方案、用户词库、布局和主题。
Debug 使用 `.debug` 后缀，与 Release 数据独立；旧 `io.github.sandyyur.rime.direct` 测试包也需要手动迁移。
完整步骤见[迁移说明](/guide/migrate-from-upstream)，多个输入法分别使用独立 Rime 工作目录。

## 已知范围

布局 Compose Override 是已经提供的前端功能；完整 XKB Compose 和按应用 `app_options` 等价仍有维护边界。
Material 3 设置、布局、主题等前端功能既有继承，也有持续改进，不能把它们全部归因于更换引擎。
性能比较与设备验证都有特定条件，请连同[原始记录](/maintainer/evidence)阅读。
