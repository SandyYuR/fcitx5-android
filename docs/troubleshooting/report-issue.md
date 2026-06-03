# 反馈问题

## 先确认你用的是哪个版本

请先确认要反馈的是 **哪个主程序构建** 与 **哪个来源的插件**（详见 [构建版本与插件兼容性](/guide/builds-and-plugins)）：

- **fx 构建**（fxliang）：包名 `org.fcitx.fcitx5.android.fx`，应用 **设置 → 关于** 中版本号与 [fxliang Release](https://github.com/fxliang/fcitx5-android/releases) 一致
- **mainline 构建**（fxliang）：包名 `org.fcitx.fcitx5.android`，但版本号与 [fxliang Release](https://github.com/fxliang/fcitx5-android/releases) 一致
- **上游官方**：包名 `org.fcitx.fcitx5.android`，版本号与 [上游 Release](https://github.com/fcitx5-android/fcitx5-android/releases) 一致

## 提交渠道

| 问题类型 | 应提交到 |
|---------|----------|
| fxliang 独有的功能 bug（浮动键盘、编辑器、QR 分享等） | [fxliang/fcitx5-android/issues](https://github.com/fxliang/fcitx5-android/issues) |
| fxliang 修改过的功能（Kawaii Bar、候选窗增强等） | [fxliang/fcitx5-android/issues](https://github.com/fxliang/fcitx5-android/issues) |
| 上游通用功能（基础输入、Fcitx5 引擎、Rime 引擎本身） | [上游 issues](https://github.com/fcitx5-android/fcitx5-android/issues) |
| 不确定 | 先提到 [fxliang issues](https://github.com/fxliang/fcitx5-android/issues)，会按需转向上游 |

## 在提交 issue 前

1. 查看 [常见问题](/troubleshooting/faq)
2. 检查 [OEM 关联启动](/troubleshooting/oem-startup) 是否相关
3. 在对应仓库的 issues 中搜索关键词，确认问题尚未被报告

## 提交 issue 时请附上

- **设备型号 / 厂商**：例如 Redmi K60
- **ROM 名称与版本**：例如 MIUI 14.0.6.0
- **Android 版本**：例如 Android 14
- **主程序构建与版本**：fx 构建 / mainline 构建 / 上游 + 在 **设置 → 关于** 中查看的版本号（含 commit hash 段）
- **已安装的插件版本**（含每个插件来源：fxliang / 上游）
- **问题描述与重现步骤**
- **崩溃日志**（若有）
- **截图或录屏**（若是 UI 问题）

## 抓取崩溃日志

应用崩溃时会在内部存储生成 `crashlog-YYYY-MM-DD.txt`，可在文件管理器中找到并附加到 issue。

详细的日志查看方式见应用内的 **设置 → 高级 → 日志**。

## 提交渠道汇总

- fx 相关：[https://github.com/fxliang/fcitx5-android/issues](https://github.com/fxliang/fcitx5-android/issues)
- 上游相关：[https://github.com/fcitx5-android/fcitx5-android/issues](https://github.com/fcitx5-android/fcitx5-android/issues)
