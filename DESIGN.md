# 中州鹅界面设计约定

面向 Android 设置、编辑器、对话框和辅助面板。此文件把设计选择对应到生产组件；详细手册与验证记录放在 `docs` 分支。当前主界面的 Material 3 Expressive 体系是基准。

## 配色与表面

通过 `SettingsUiPreferences.themedContext` 和 `settingsColor` 读取主界面的色板、深浅模式、Monet、对比度与纯黑设置。辅助面板使用同一入口；跟随键盘配色由现有设置决定，不另建一套自动模式。

| 用途 | Material 颜色角色 |
| --- | --- |
| 页面背景 | `colorSurfaceContainer` |
| 分组卡片 | `colorSurfaceBright` |
| 浮动面板 | `colorSurfaceContainerHigh` |
| 可编辑输入框 | `colorSurfaceContainerLowest` |
| AI 回复、次操作 | `colorSecondaryContainer` / `colorOnSecondaryContainer` |
| 主要操作 | `colorPrimary` / `colorOnPrimary` |
| 正文、辅助文字、细边框 | `colorOnSurface` / `colorOnSurfaceVariant` / `colorOutlineVariant` |
| 错误提示 | `colorErrorContainer` / `colorOnErrorContainer` |

成对使用表面色和前景色。用层次和主操作建立重点，避免到处加彩色描边、渐变、阴影。键盘画布属于用户自定义主题，其尺寸、按键配色与触摸规则继续遵循输入模块契约。

恢复数据的独立进程使用 `restoreUiContext` 的资源主题，跟随系统深浅；不能为了配色读取正在替换的偏好、数据库或启动原生引擎。

## 几何与排版

- 间距以 4 dp 为基准；邻近控件 8–12 dp，辅助面板内边距 16 dp，大段内容 20–24 dp。
- 输入框与下拉菜单 12 dp 圆角，分组卡片 20 dp，浮动面板 28 dp；胶囊按钮半径 24 dp。
- 标题约 20 sp、正文 14–16 sp、标签与辅助信息 12 sp。保留系统字体和字体缩放。
- 操作按钮至少 48 dp 触摸高度。窄屏或大字号时按完整按钮换行，不能把按钮挤出面板。
- 主界面与编辑器复用 `SettingsUiActivity`、`EditorUi`、`MaterialSpinner` 及 `Widget.FcitxSettings.TextField`。

## AI 助手

标题、用途与服务、输入、回复、操作依次形成清楚的层次。关闭按钮放在标题旁，发送或确认只突出当前一步的主要操作。连接信息默认展示主机和模型，详细接口信息可通过辅助说明查看。

输入保持中性表面，回复使用浅层次的色块并允许选择复制。错误在原面板中解释，输入草稿继续保留。加载指示不能遮挡关闭入口。操作区使用 `JsAppActionRow` 换行，长内容通过已有滚动容器访问。

常规窗口固定标题和操作区，中间内容滚动；极短窗口改为整体滚动。授权提示期间隐藏原表单的固定操作，结束后恢复草稿。浮动窗口继续避开键盘、支持标题拖动、不抢原输入框焦点。等待中仍可关闭；原应用的临时预览、确认上屏、取消恢复及会话过期拒绝写入是功能契约，不由视觉样式改变。

## 验证与参考

主题卡片上方展示主题自己的颜色与图片，下方名称和操作使用设置色板。名称保持单行，按钮紧接着占一行，不预留空白名称行或底部空行；长名称省略并提供完整提示。图标行视觉高度 32 dp，`ThemeCardDetails` 将触摸范围向上扩至 48 dp；随机按钮与编辑按钮放在同一操作行。选中／日间／夜间标记独立于名称，卡片必须裁剪预览子视图；高度随字号调整。新增入口使用相同圆角和 Material 色板。

主题网格的纵向边距和行间距保持 16 dp，独立于列宽和两侧留白；窄屏只显示一列时，也不能把宽大的左右边距用作上下间距。

`WrappingActionRow` 同时服务于普通表单和 AI 操作区，按完整控件换行并保留边距。插件字段按声明区分普通参数与密钥，权限与管理单列分组；保存状态不能代替服务验证。导入、备份和下载状态对应真实阶段，错误详情由用户展开，下一次操作清除旧错误。

视觉改动检查浅色、深色、窄屏、大字号、加载、错误与回复状态。Android 图形单测用于快速比较，真机或模拟器用于验证实际窗口、键盘与触摸行为；两类结果分别记录。

键盘取色的独立 Context 必须先保留完整 Settings 基础主题，再应用颜色覆盖，不能只带一层配色 overlay。剪贴板编辑复用 IME 附着面板与本地输入连接，不切换 Activity；浅色输入框使用 `colorSurfaceContainerLowest`，接近白色属于表面层次设计。

本约定参考 [awesome-design-md](https://github.com/VoltAgent/awesome-design-md) 将颜色角色、间距、排版和组件规则写成可维护文本的方法，并结合 Linear、Raycast、Claude 的分析材料。参考版本为 `fcc70284932276d085f01ef9f83edde90b1258cb`；本项目使用自己的原生组件、颜色角色和交互契约。
