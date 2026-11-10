# 中州鹅界面设计约定

适用于 Android 设置、编辑器、对话框和辅助面板，以现有 Material 3 Expressive 界面为基准。组件细节、例外与测试入口见[设计与共用组件][details]，输入及数据边界见[正确性契约][contracts]。

## 配色

通过 `SettingsUiPreferences.themedContext` 与 `settingsColor` 使用现有深浅模式、Monet、对比度、纯黑及键盘取色设置，不另建主题状态。

| 用途 | Material 角色 |
| --- | --- |
| 页面／分组卡片／浮动面板 | `colorSurfaceContainer` / `colorSurfaceBright` / `colorSurfaceContainerHigh` |
| 主操作 | `colorPrimary` / `colorOnPrimary` |
| 回复与次操作 | `colorSecondaryContainer` / `colorOnSecondaryContainer` |
| 正文／辅助信息／边框 | `colorOnSurface` / `colorOnSurfaceVariant` / `colorOutlineVariant` |
| 错误 | `colorErrorContainer` / `colorOnErrorContainer` |

背景与前景成对使用。输入框沿用容器底色，以描边、标签和焦点区分可编辑区域。层次与主操作优先，避免无目的的彩色描边、渐变和阴影。

## 布局与组件

- 间距以 4 dp 为基准，邻近控件 8–12 dp，面板内边距 16 dp，大段内容 20–24 dp。
- 输入框与下拉框 12 dp 圆角，分组卡片 20 dp，浮动面板 28 dp；沿用共用样式。
- 标题约 20 sp、正文 14–16 sp、辅助文字 12 sp。跟随系统字体与缩放；键位热力图的固定字号例外见详细文档。
- 普通操作按钮至少 48 dp 触摸高度。窄屏、大字号与 RTL 下完整换行；短窗口允许滚动到全部内容和操作。
- 复用 `SettingsUiActivity`、`EditorUi`、`MaterialSpinner`、`Widget.FcitxSettings.TextField`；操作区用 `WrappingActionRow`，AI 使用其封装 `JsAppActionRow`。

## 交互边界

用户键盘画布由键盘主题和布局控制。主题卡片的预览展示主题自身外观，名称与操作使用设置色板，选中状态独立呈现。

面板按标题、输入、结果、操作组织；突出当前主要动作，关闭入口持续可用。错误留在当前界面并保留草稿，进度反映实际阶段。视觉调整保持原编辑器焦点、输入连接、请求代次及保存时序。

恢复进程只用 `restoreUiContext` 资源主题，不为外观读取正在替换的偏好或启动引擎。菜单、IME 附着面板与 Activity 文本表单各有窗口策略，修改前查详细契约。

## 视觉验收

检查浅色／深色、窄屏、大字号、RTL，以及加载、错误和长内容。图形单测用于组件检查；实际窗口、键盘、触摸及返回行为使用设备验证，分别记录来源和限制。截图通过不替代输入与保存功能测试。

[details]: https://sandyyur.github.io/fcitx5-android/maintainer/implementation/design-system
[contracts]: https://sandyyur.github.io/fcitx5-android/maintainer/implementation/correctness-contracts
