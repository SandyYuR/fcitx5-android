# 反馈问题

## 先确认你用的是哪个版本

请先确认反馈的应用、包名和构建类型；Debug、Release、旧测试包及上游版本的数据可能相互独立：

- **中州鹅（本版）**：包名 `org.fcitx.fcitx5.android.fx.rime`，Debug 主应用另带 `.debug` 后缀，版本号见应用内 **关于 → 当前版本**，与 [本仓库 Release](https://github.com/SandyYuR/fcitx5-android/releases) 一致
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
3. 确认 [方案已正确放入并部署](/guide/quick-start)（首次安装需要自行准备方案）
4. 在 issues 中搜索关键词，确认问题尚未被报告

新建 issue 时选择“问题报告”或“功能请求”模板；本仓库已关闭空白 issue，模板中的系统版本与应用版本为必填项。

## 提交 issue 时请附上

- **应用版本和构建提交哈希**：见设置首页“关于”中的“当前版本”与“构建 Git 哈希”条目；从 Release 安装时一并写明所下载的 `zhongzhoue-<版本>-<abi>.apk` 文件名
- **设备型号 / ROM 名称与版本 / Android 版本 / ABI**
- **当前 Rime 方案 / profile**（脱敏后的 `schema_list` 即可）
- **可重复的最短步骤、期望结果与实际结果**
- **是否使用自定义 YAML、布局、宏、主题、字体或同步服务**
- **崩溃日志**（若有）：从应用崩溃/日志页面导出；内部崩溃留存位置为 `crash-reports`，不要只寻找旧版固定文件名
- **截图或录屏**（若是 UI 问题）

## 不要公开上传

::: danger 隐私红线
不要上传：完整备份 ZIP、剪贴板数据库、同步密码、私钥、真实输入内容或包含个人信息的截图。能够用最小配置复现时，优先提供最小配置，日志先脱敏。
:::

更完整的清单见 [当前用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)。

## 取得日志

从“设置 → 高级 → 开发者 → 实时日志”进入日志页。对应 [PR #55](https://github.com/SandyYuR/fcitx5-android/pull/55) 的版本将搜索、过滤、刷新、清空和导出集中在顶栏，日志内容使用可点击展开的紧凑列表。

打开页面会合并保留的应用日志与当前 logcat 快照。文件保留能帮助查看较早的应用事件，但仅写入 logcat 的原生或系统记录仍可能已被覆盖；遇到问题后尽早导出。顶栏“导出”可以保存文件或分享，采用当前筛选结果的固定快照，并附加仍保留的崩溃报告。先核对报告中的时间、构建和进程，再脱敏后提交。

“清空”会一并删除保留的应用日志和崩溃报告，收集证据前不要清空。完整的保留范围、过滤与恢复行为见[用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md#保存日志与卡顿反馈)。
