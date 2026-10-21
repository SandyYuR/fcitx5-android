# Rime 引擎与中州韵设置

中州鹅直接调用 librime，保留完整 Android 键盘和设置。
原生 Fcitx Core、fcitx5-rime 及 AndroidFrontend 已移除，Rime 无需插件 APK。
APK 不预装中文方案、完整词典或万象模型，见[快速上手](/guide/quick-start)。

## 目录、导入与部署

“输入与候选 → 中州韵设置”提供方案、预编辑、数据目录、导入、部署与同步。
导入完整方案依赖，或将文件放到当前目录并在 `default.custom.yaml` 配置 `schema_list`。
显式重新部署会重建 Rime 环境，重读 Lua、方案列表与开关；以实际候选和开关变化判断结果。
部署期间不接收输入，模型二进制和用户词库不因重部署清空。

## 方案与开关

语言键短按发独立 Shift，长按打开方案列表。状态区的互斥开关按组显示，方案列表不重复列开关。
候选注释遵循方案配置；切换语言、方案会短暂显示提示。
中文空格标签为“中州韵（方案名）”，紧凑为“㞢”，ASCII 状态在中文界面显示“英文模式”。

## 引擎来源与更新

| 层次 | 来源 |
| --- | --- |
| 官方引擎 | [rime/librime](https://github.com/rime/librime)，固定 `1b3417d2b6a11b5e50e0e1fa9dc396056aa11dce` |
| 中州鹅定制源码 | [SandyYuR/librime](https://github.com/SandyYuR/librime/tree/zhongzhou)，固定 `446f8584eb01ab8f72b3f0b970fda3919b1c1a55` |
| Android 编译和插件准备 | [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder) |
| 四 ABI 静态库 | [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt)，gitlink `d297ddee183b437d0fd8c9bdf37eced6ec8d09cd` |
| 主仓库桥接 | `direct-rime/src/main/cpp/` 和 `DirectRimeBackend.kt` |

主应用只链接需要的静态输入；共享 prebuilt 中存有其他产物不等于会打包或运行它们。
librime 核心定制维护在自有源码仓库的 `zhongzhou` 分支；prebuilder 固定该源码提交、准备插件并构建四 ABI，
prebuilt 保存验证后的产物，输入法再固定引用。旧 fcitx5-rime 仓库已归档。
职责、迁入功能和操作步骤见[源码维护迁移](/maintainer/DIRECT_RIME_SOURCE_MAINTENANCE_2026-10-21)。
本次合入 23 项上游提交，版本标记为 `1.17.0-1b3417d`；官方版本仍为 1.17.0。
固定来源、定制适配与验证见[引擎更新记录](/maintainer/DIRECT_RIME_ENGINE_UPDATE_2026-10-21)。
完整步骤见[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN#更新-librime)。

## 输入与学习

按键、候选操作和会话管理由单一 owner 串行处理；同应用普通编辑器可复用会话，跨应用与敏感输入隔离。
用户词库通过 Rime 学习和同步。多个输入法不要同时打开同一个外部工作库。
实测数据与边界见[当前交付](/maintainer/DIRECT_RIME_RELEASE_READY_2026-10-21)。
