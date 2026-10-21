# 默认分支的 Material 3 界面补齐

本次将 `feat/direct-rime-prototype` 中的共享 MD3 界面修改提取到
`fx-rime-only`，整理为一个提交。来源为 `6db49cec`、`776e60d5`、
`4b9e14e7` 的界面部分，以及输入法菜单需要的窗口焦点兼容处理。

## 修改范围

- 更新器四个页面的 XML Toolbar 与 Kotlin MaterialToolbar 类型保持一致，
  修复进入更新器时的 ClassCastException。下载进度、表单、开关与操作图标
  使用 Material 控件及配色，并修正浅色主题中的图标对比度。
- 导入导出等共用加载对话框使用 MaterialAlertDialogBuilder、
  LinearProgressIndicator；关闭系统动画时保留文字提示。
- 实时日志、崩溃日志、裁剪、剪贴板编辑与布局选择页面使用统一的设置主题，
  覆盖浅色、深色、浮动和透明窗口。崩溃提示在页面销毁时关闭，避免窗口泄漏。
- 编辑器的按钮、文本框、复选框、开关和滑块使用 Material 组件。
  下拉选择器保留 AppCompatSpinner 的 adapter 和选择回调，仅统一外观；
  滑块的整数进度与回调保持兼容，隐藏会暴露内部进度索引的默认浮动标签。
- 输入法选择与 Rime 方案菜单使用 Material 对话框；附着在键盘上的选择菜单
  保持编辑框焦点，支持返回键及预测返回，避免密码输入框因菜单抢焦点隐藏键盘。
- 删除无入口的剪贴板同步旧 Activity 外壳和专用空布局，保留原来的嵌套
  SettingsFragment 类名，实际宿主仍为 ClipboardSyncSettingsActivity。

## 与直连分支的边界

本次没有迁移 Direct Rime 引擎、性能策略、包名、应用名、关于页面文案或
直连专用的 Rime 导入页面。默认分支的 Fcitx 原生库、Rime 插件、gitlink、
构建配置及现有更新器入口保持原有实现。日志导出沿用默认分支逻辑。

## 验证方式

默认分支单独编译并运行 JVM 单元测试；Android 回归测试为
`MaterialSettingsPagesTest`，通过真实 Activity 启动覆盖更新器、实时日志、
崩溃提示、裁剪、剪贴板及共享控件，在浅色和深色模式下验证控件类型、
滑块触摸、下拉选择回调、图标像素对比度与窗口样式。另外覆盖普通文本、
文本密码、网页密码、数字密码四种编辑框中的菜单焦点和返回操作。

`Material settings UI` 工作流在默认分支和 `ui/**` 分支相关界面改动后执行，也可手动运行。
测试报告、20 张界面截图与 logcat 保存在 `material-settings-ui-results` 产物。
这组测试不依赖万象词库，也不作引擎性能结论。
