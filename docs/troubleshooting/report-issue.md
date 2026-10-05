# 反馈问题

## 先确认你用的是哪个版本

请先确认反馈的是哪个发行版——它们是**不同包名的并存应用**：

- **靓企鹅·中州韵（本版）**：包名 `org.fcitx.fcitx5.android.fx.rime`（中文显示名「靓企鹅·中州韵」，其他语言显示 Fcitx5.fx.rime），版本号见应用内 **关于 → 当前版本**，与 [本仓库 Release](https://github.com/SandyYuR/fcitx5-android/releases) 一致
- **fxliang fx 构建**：包名 `org.fcitx.fcitx5.android.fx`，见 [fxliang Releases](https://github.com/fxliang/fcitx5-android/releases)
- **上游官方**：包名 `org.fcitx.fcitx5.android`，见 [上游 Releases](https://github.com/fcitx5-android/fcitx5-android/releases)

## 提交渠道

| 问题类型 | 应提交到 |
|---------|----------|
| Rime 专版行为（方案部署、语言键、候选手势、退格行为等） | [SandyYuR/fcitx5-android/issues](https://github.com/SandyYuR/fcitx5-android/issues) |
| 本分支新增功能（设置搜索、符号面板、分体布局配置、剪贴板搜索等） | [SandyYuR/fcitx5-android/issues](https://github.com/SandyYuR/fcitx5-android/issues) |
| 继承自 fx 分支的功能且在本版可复现 | 先提到 [本仓库 issues](https://github.com/SandyYuR/fcitx5-android/issues)，会按需转向 |
| 纯 fx 分支 / 上游的问题（本版未涉及） | [fxliang issues](https://github.com/fxliang/fcitx5-android/issues) / [上游 issues](https://github.com/fcitx5-android/fcitx5-android/issues) |
| 不确定 | 先提到 [本仓库 issues](https://github.com/SandyYuR/fcitx5-android/issues) |

## 在提交 issue 前

1. 查看 [常见问题](/troubleshooting/faq)
2. 检查 [OEM 自启动与后台限制](/troubleshooting/oem-startup) 是否相关
3. 确认 [方案已正确放入并部署](/guide/quick-start)（「打不出字」绝大多数是这个原因）
4. 在 issues 中搜索关键词，确认问题尚未被报告

## 提交 issue 时请附上

- **应用版本和构建提交哈希**：见 **关于 → 当前版本**
- **设备型号 / ROM 名称与版本 / Android 版本 / ABI**
- **当前 Rime 方案 / profile**（脱敏后的 `schema_list` 即可）
- **可重复的最短步骤、期望结果与实际结果**
- **是否使用自定义 YAML、布局、宏、主题、字体或同步服务**
- **崩溃日志**（若有）：崩溃时会在内部存储生成 `crashlog-YYYY-MM-DD.txt`
- **截图或录屏**（若是 UI 问题）

## 不要公开上传

::: danger 隐私红线
不要上传：完整备份 ZIP、剪贴板数据库、同步密码、私钥、真实输入内容或包含个人信息的截图。能够用最小配置复现时，优先提供最小配置，日志先脱敏。
:::

更完整的清单见 [用户指南 §12](https://github.com/SandyYuR/fcitx5-android/blob/rime-docs/docs/RIME_ONLY_USER_GUIDE_zh-CN.md)。
