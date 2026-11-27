# 空格长按、剪贴板收藏与句号键 · 2026-11-27

> **后续复核：** 审阅后已将 [`393f5b593db682091a52101df46dd68b096b0afc`](https://github.com/SandyYuR/fcitx5-android/commit/393f5b593db682091a52101df46dd68b096b0afc) 快进推送至默认分支 `fx-rime-only`。在该提交上重跑 9 类 68 项目标测试，失败、错误、跳过均为 0；`x86_64` Debug APK（SHA-256：`ba1be5186d0523e91f1c7be8efffa7bc449698a76fbacd7f996de4d610b0f671`）通过内容及原生链接审计。[Commit CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45975478962) 已通过，变更文件与文档见[当日提交对照](./commit-map-2026-11-27.json)。

本轮验证对象为本机工作区改动，代码基线为 `fx-rime-only@590e0bbc0ddcc629342f968e258fa142527d3bd1`，文档基线为 `docs@b2a8ca77830d173dddda80454ce87e53f71ccd72`。这些 SHA 不包含本轮修改。

| 入口 | 本轮核对的行为 |
| --- | --- |
| [空格键长按](/features/keys/space-key) | 新选项调用 Android 系统输入法选择器；原先保存的 `ShowPicker` 仍打开 Rime 方案选单，未设置时仍为「无」。 |
| [剪贴板](/features/clipboard-sync#应用内剪贴板) | 开关默认关闭；开启后，键盘和浮动搜索面板的「全部」都排除已收藏条目，「收藏」与其他分类不受影响。 |
| [布局编辑器](/features/editor/layout-editor#按键类型下拉框) | 简体和繁体中文的键型名称分别为「句号键」和「句號鍵」。只改应用内文案，布局 JSON 仍使用 `SymbolKey`。 |

## 提交前的本机验证

- 9 个目标 JVM 测试类实际执行 68 项，失败、错误、跳过均为 0。`SpaceLongPressBehaviorTest` 验证长按派发系统选择器而抬手不再派发；`ClipboardSearchPagingTest`、`ClipboardSearchPanelTest` 覆盖分组过滤、收藏状态切换和清空「全部」时不删除或抑制隐藏的收藏。另覆盖空格键手势、设置搜索及布局编辑往返。
- `x86_64` Debug APK 构建、内容白名单和本次构建输入的 native 链接审计通过；APK SHA-256 为 `9ecf8b7851b82ba9c935a54a667c56207db31197e2c1dd53e86937fbaee29206`。已用 `aapt2 dump resources` 核对包内的简繁句号键文案、两个不同的长按选项及剪贴板开关。
- `:app:lintFxDebug` 首次因 Gradle daemon 意外退出未完成；改用单次 daemon、单 worker 重跑通过，未修改项目配置。文档站构建、链接、历史完整性、目录和搜索检查通过。

未安装 APK 或操作日用模拟器：系统弹窗的实际显示、触摸及返回未做设备验收。
