# 界面设计与共用组件

中州鹅的设置、编辑器和辅助面板使用主界面的 Material 3 Expressive 体系。源代码分支根目录的 `DESIGN.md` 保存简明规则；本页记录应用位置、例外和验证方式。

设计方法参考 [awesome-design-md](https://github.com/VoltAgent/awesome-design-md)：把颜色角色、排版、间距、组件和交互状态写成可维护的规则。本次参考固定在 `fcc70284932276d085f01ef9f83edde90b1258cb`，阅读了 Linear、Raycast、Claude 的分析材料；具体颜色和实现仍使用项目已有的 Android 体系。

## 统一入口

| 区域 | 实现与规则 |
| --- | --- |
| 设置主界面与页面 | `SettingsUiActivity` 管理外观、系统栏与页面边距；分组卡片沿用 20 dp 圆角 |
| 布局、宏、字体等编辑器 | `EditorUi`、`MaterialSpinner` 和 `Widget.FcitxSettings.TextField`；输入框与默认下拉框使用 12 dp 圆角 |
| 方案与语音选择器 | 通过 `SettingsUiPreferences.themedContext` 创建 Material 对话框；保留各自选择、权限和回调流程 |
| AI / JS App 面板 | 同一外观入口，浮动面板 28 dp 圆角、16 dp 内边距；标题、内容与操作建立清楚层次 |
| 数据恢复进程 | `restoreUiContext` 仅使用资源主题与系统深浅，不读取正在替换的偏好、数据库或启动原生引擎 |
| 用户键盘画布 | 继续由用户键盘主题、布局和比例控制；辅助界面的一致性规则不能覆盖这些设置 |

颜色从 `SettingsUiPreferences` 的选择和 Material 颜色角色读取。Monet、手动色板、对比度、纯黑及跟随键盘取色共用现有设置，不另增一套自动主题状态。

- 页面背景：`colorSurfaceContainer`；浮动面板：`colorSurfaceContainerHigh`。
- 输入：`colorSurfaceContainerLowest`；回复与次操作：`colorSecondaryContainer` / `colorOnSecondaryContainer`。
- 主操作：`colorPrimary` / `colorOnPrimary`；错误：`colorErrorContainer` / `colorOnErrorContainer`。
- 正文、辅助信息、边框：`colorOnSurface`、`colorOnSurfaceVariant`、`colorOutlineVariant`。

使用成对的背景与前景角色。主题颜色不直接从键盘的 `popupTextColor`、`accentKeyBackgroundColor` 等独立字段拼出第二套面板色板。

`SettingsKeyboardColors.wrapContext` 在资源加载器建立独立 Context 后，先显式应用完整的 Settings 浅／深基础主题，再应用键盘颜色 overlay。只复制配色层会丢失 `editTextBackground`、按钮样式或 TextAppearance；回归必须开启键盘取色并实际构造输入框和按钮。

## AI 面板结构

`JsAppDialog` 负责原生展示，内置 `llm-assistant.js` 负责内容、用途和事件。显示顺序为标题、用途/服务、连接摘要、输入、回复、操作；HTTP 方法与完整路径放在连接摘要的长按说明中。

根列的首个 `header` 和末尾 `actions` 在正常空间下固定，中间内容滚动。高度不足以容纳标题、按钮及最小正文时，面板改为整体滚动。切换只移动现有视图；编辑器输入连接、脚本字段值和请求状态不能因此重新创建或丢失。

`JsAppActionRow` 按完整按钮换行并靠末端排列，支持 RTL。按钮至少 48 dp 高；当前主要操作使用实色，重新生成使用次级色，清空与关闭使用轻量样式。图标按钮保留文字作为无障碍说明，未知图标不能留下空白按钮。

`JsAppActionRow` 复用 `WrappingActionRow`，剪贴板编辑表单也使用这一组件；测量需保留子项的边距与高度约束。其余 XML 表单显式引用 `Widget.FcitxSettings.TextField`，避免局部指定默认样式而绕开共用圆角与背景。

授权提示显示期间隐藏原表单的固定标题与操作，避免背后按钮造成误导；结束后恢复原草稿。加载条不遮挡关闭入口，错误留在当前面板内。回复区可选择复制，仍不能接收键盘编辑。具体字段见 [JS 宿主协议](./js-voice-plugins)。

## 列表与任务状态

- 主题名称行按字号计算，图标视觉行收至 32 dp；`ThemeCardDetails` 向名称区扩展图标点击范围至 48 dp。随机按钮移到底部，与编辑按钮同排。需要验证真实触摸事件，不能只检查图标非空。
- 主题网格的纵向边距与行间距固定为 16 dp，独立于横向列宽；窄屏单列不会把较大的左右留白重复用作上下间距。
- 剪贴板 `ClipboardEditor` 复用 `JsAppDialog` 作为原生面板，不创建 JS 运行时、Activity 或新的系统 IME 目标。原应用连接与面板连接分离；会话过期拒绝新写入，已确认的 `ClipboardManager.saveEditedText` 仍由应用持有，写入成功后才复制。
- 布局管理首次仅展开当前启用的 profile，`expanded` 集合只维护本页与旋转状态；折叠标题的高亮由整个卡片绘制，移除正文底部留白。
- 主题卡片将色彩预览和名称／操作区分离；预览子视图必须按卡片轮廓裁剪，选中标记独立于名称。主题列表使用名称身份保持当前、日间和夜间选择，增删、移动与重命名不能用过期位置推断状态。
- 插件页从原始字段的 `isSecret` 分组，沿用 `visibleWhen` 与依赖默认值；隐藏整个空分组时仍要更新其子项状态。密钥概况复用 IO 线程取得的 `SecretState`，列表不额外解密或探测网络，不能把“已保存”写成“服务可用”。
- `OperationStatusView` 只承载状态，任务与取消仍由调用方负责。失败详情明确展开，切换任务后清除旧内容；不显示没有实际依据的百分比。
- 更新项使用 Material 图标按钮与颜色角色，下载进度更新复用同一控件；主操作仍经过原有下载、校验与安装入口。
- 剪贴板保留主题色、分页、敏感内容遮盖及撤销逻辑；增加短条目触摸高度与置顶标记留白，不能改变上屏和复制时序。

输入统计的键面热力图采用固定字号例外：按屏幕密度绘制键名和百分比，不随应用或系统字体设置放大，收紧水平间距以优先展示完整键位。只有实际宽度不足才横向滚动并显示提示；完整展示时隐藏提示。例外仅限键面热力图，统计页普通文字、词云、分布图和按键频次条形图继续采用各自正常的字体缩放规则。几何比例仍来自实际键盘，不能为了凑宽度丢失分离键盘的空隙。

## 验证

先检查生产调用链，再运行相关 JVM 测试和 Debug 构建；窗口、键盘及触摸行为补实际设备或模拟器验证。

- `JsAppDialogAppearanceTest`：浅色、深色、窄窗口、大字号、按钮可见范围与图标。
- `JsAppFloatingPanelTest`：首次显示、键盘边界、移动、焦点和菜单。
- `JsAppWorkflowTest`、`JsAppDialogTest`：真实脚本与原生按钮、输入分流、授权提示恢复和确认上屏。
- `RestoreUiThemeTest`、`UserDataRestoreStartupTest`：恢复 UI 不打开偏好，独立进程约束继续成立。
- `MaterialSettingsPagesTest`：在可丢弃设备上检查共用控件与实际页面。

Android 图形测试截图先附着窗口、解析布局方向，再测量和绘制；离屏视图未解析相对图标时可能出现假缺失。渲染图必须标为组件渲染，不能称作真机截图。实际图像还要检查文字、控件是否被裁切，不能仅凭测试退出码判断美观或可用。
