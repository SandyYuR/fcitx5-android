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
| 官方引擎 | [rime/librime](https://github.com/rime/librime)，固定 `ef1a16aa2c962bccf347b116443331f39d56e037` |
| Android 编译和定制补丁 | [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder) |
| 四 ABI 静态库 | [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt)，gitlink `1126616c3ec569617da772e30d7a97f6b2694172` |
| 主仓库桥接 | `direct-rime/src/main/cpp/` 和 `DirectRimeBackend.kt` |

主应用只链接需要的静态输入；共享 prebuilt 中存有其他产物不等于会打包或运行它们。
librime 升级要在 prebuilder 新分支更新固定版本、复核定制补丁、构建四 ABI，
再更新主仓库 gitlink 并验证 API、方案、Lua、用户库和性能。
完整步骤见[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN#更新-librime)。

## 输入与学习

按键、候选操作和会话管理由单一 owner 串行处理；同应用普通编辑器可复用会话，跨应用与敏感输入隔离。
用户词库通过 Rime 学习和同步。多个输入法不要同时打开同一个外部工作库。
实测数据与边界见[当前交付](/maintainer/DIRECT_RIME_RELEASE_READY_2026-10-21)。
