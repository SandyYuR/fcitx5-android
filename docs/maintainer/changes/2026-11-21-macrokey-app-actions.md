# 2026-11-21 MacroKey 应用动作快捷入口

## 来源与范围

代码基线为 `fx-rime-only@899eedbf1b7eb8475f7f2a3e162e6ec014763be5`，实现已提交为 [`8bb9fe7af7c0d11387fcdf2d00ae1881b043bde6`](https://github.com/SandyYuR/fcitx5-android/commit/8bb9fe7af7c0d11387fcdf2d00ae1881b043bde6)，文档基线为 `docs@c6431d610a432917c6d0e65c01202f3c3aa05ce9`。本轮只修改应用动作注册、宏选择器、相关回归测试与对应说明；开始时已有的网页编辑器桥与文档修改由其他会话维护，保留其工作区状态。

| 新增选项 | 执行入口 |
| --- | --- |
| `settings_input_stats` | `SettingsInputStatsAction` → `InputStatsActivity` |
| `voice_input`、`ai_assistant`、`split_keyboard` | 既有 `VoiceInputAction`、`AiAssistantAction`、`SplitKeyboardAction` |
| `theme_toggle`、`random_theme` | 既有 `ThemeToggleAction`、`RandomThemeAction` |
| `text_keyboard_layout_profiles` | `TextKeyboardLayoutProfilesAction` → `TextKeyboardLayoutProfileManagerActivity` |
| `settings_js_plugins` | `SettingsJsPluginsAction` → `JsVoicePluginsActivity.intent(context)` |
| `settings_scheme_updates` | `SettingsSchemeUpdatesAction` → `RimeSchemeUpdateActivity` |
| `settings_data_backup` | `SettingsDataBackupAction` → `SettingsRoute.DataBackup` |

`MacroEditorDialogFragment.APP_ACTION_IDS` 提供这 10 个新增选项，标签复用现有资源及 `ButtonAction.defaultLabelRes`。`BaseKeyboard` 与文本编辑宏通过既有按钮解析路径执行，不新增输入队列或引擎状态。页面动作支持 Service Context 跳转，只打开页面，不自动开启统计、更新、备份或授予插件权限。

使用方法及动作 ID 见[MacroKey 编辑器](/features/editor/macrokey-editor#应用动作快捷入口)；统计隐私及记录开关仍遵循[输入统计](/features/input-statistics)。

## 本轮验证

目标 JVM 测试共 52 项实际执行，失败、错误、跳过均为 0；`tools/check-junit-results.py` 核验了本轮 4 份 XML。范围为 `MacroAppActionTest`、`MacroKeyDialogTest`、`MacroBaselineTest`、`MacroEditorDialogLayoutTest`。新测试在 API 23、35 检查真实选择器选择统计行并确认回填、10 个 ID 的可选与可执行对应关系，以及从 Service Context 启动五个页面的 Activity 和导航参数。

x86_64 Debug 构建及 `verify-direct-rime-apk.py`、`audit-direct-rime-link.py` 均通过；审计使用本次 APK、主仓库 `app/.cxx` 和锁定的 `native/rime-sdk`。本轮 APK SHA-256 为 `2337c88f1b80e8f3543bdddf71576f4e3a1e5e3272a9c8c1235a24d3a2d19065`。构建时工作树还包含前述其他会话的编辑桥改动，APK 对应本轮完整工作树，不冒充只含此提交的独立构建。

文档站构建、链接、历史完整性、目录、搜索五项门禁及 `git diff --check` 均通过。

本机唯一 AVD `rime`（API 35，`emulator-5574`）已只读核对在线。未安装本轮 APK，未执行设备点击与返回验收；安装仍受本机授权边界约束。JVM 和 Debug 结果不代替设备验收。

原始构建、测试和文档日志位于本机 `.verify/macrokey-input-stats-20261121/`。本轮未新增依赖或变更签名。
