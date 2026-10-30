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

## 候选手势与编码保护

按住候选后上滑可打开选字操作，下滑打开候选菜单。忘记词汇、注释和词频行为取决于方案与引擎支持。快速滑出候选范围会取消本次选择，见[候选指南](/features/candidate-window)。
长按退格删空编码后会保护正文，抬手后再按可继续删正文；长按连删后上滑，在合成中只清编码，非合成时才清空输入框，见[退格键](/features/keys/backspace-key)。

## 引擎来源与更新

引擎使用 `SandyYuR/librime` 的 `zhongzhou` 定制源码，经 prebuilder 构建，由主仓库固定 `SandyYuR/prebuilt` 的 gitlink。不要以共享产物仓库中存在其他引擎文件判断它们被当前 APK 使用。
版本和定制来源以当前源码与元数据为准，查询及六步更新见[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN#librime-从哪里来)。
此前更新的固定提交、数量与验证保留在[2026-10-21 引擎记录](/maintainer/history/DIRECT_RIME_ENGINE_UPDATE_2026-10-21)。

## 输入与学习

按键、候选操作和会话管理由单一 owner 串行处理；同应用普通编辑器可复用会话，跨应用与敏感输入隔离。
用户词库通过 Rime 学习和同步。多个输入法不要同时打开同一个外部工作库。
实测数据与边界见[历史交付记录](/maintainer/history/DIRECT_RIME_RELEASE_READY_2026-10-21)。
